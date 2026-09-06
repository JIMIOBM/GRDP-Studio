import fs from 'node:fs/promises'
import path from 'node:path'
import { SpreadsheetFile, Workbook } from '@oai/artifact-tool'

const outputDir = path.resolve('.')
await fs.mkdir(outputDir, { recursive: true })

const headers = [
  '压力(MPa)',
  '温度(℃)',
  '地层水矿化度(mg/L)',
  '天然气在水中的溶解度(dless)',
  '地层水体积系数(dless)',
  '地层水密度(kg/m³)',
  '地层水等温压缩系数(MPa⁻¹)',
  '地层水粘度(mPa·s)'
]

const rows = Array.from({ length: 20 }, (_, index) => {
  const pressure = (index + 1) * 10
  return [
    pressure,
    119.85,
    25000,
    Number((0.0045 * pressure).toFixed(4)),
    Number((1.02 + 0.0004 * pressure).toFixed(4)),
    Number((1018 + 0.07 * pressure).toFixed(2)),
    Number((0.00042 - 0.00000055 * pressure).toFixed(8)),
    Number((0.30 + 0.00035 * pressure).toFixed(4))
  ]
})

const escapeCsv = (value) => {
  const text = String(value)
  return /[",\r\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text
}

const csvText = [headers, ...rows]
  .map(row => row.map(escapeCsv).join(','))
  .join('\r\n')

const workbook = await Workbook.fromCSV(csvText, { sheetName: '地层水结果数据' })
const sheet = workbook.worksheets.getItem('地层水结果数据')
sheet.showGridLines = false
sheet.freezePanes.freezeRows(1)

sheet.getRange('A1:H1').format = {
  fill: '#1F4E78',
  font: { bold: true, color: '#FFFFFF' },
  horizontalAlignment: 'center',
  verticalAlignment: 'center',
  wrapText: true,
  borders: { preset: 'outside', style: 'thin', color: '#17365D' }
}
sheet.getRange('A1:H1').format.rowHeight = 42
sheet.getRange('A2:H21').format = {
  horizontalAlignment: 'right',
  verticalAlignment: 'center',
  borders: {
    insideHorizontal: { style: 'thin', color: '#D9E2F3' }
  }
}
sheet.getRange('A2:A21').format.numberFormat = '0.00'
sheet.getRange('B2:B21').format.numberFormat = '0.00'
sheet.getRange('C2:C21').format.numberFormat = '0.00'
sheet.getRange('D2:F21').format.numberFormat = '0.00'
sheet.getRange('G2:G21').format.numberFormat = '0.00E+00'
sheet.getRange('H2:H21').format.numberFormat = '0.00'

const widths = [13, 13, 23, 29, 23, 22, 31, 20]
widths.forEach((width, index) => {
  sheet.getRangeByIndexes(0, index, 21, 1).format.columnWidth = width
})

const inspected = await workbook.inspect({
  kind: 'table',
  range: '地层水结果数据!A1:H21',
  include: 'values,formulas',
  tableMaxRows: 21,
  tableMaxCols: 8,
  maxChars: 12000
})
console.log(inspected.ndjson)

const errors = await workbook.inspect({
  kind: 'match',
  searchTerm: '#REF!|#DIV/0!|#VALUE!|#NAME\\?|#N/A',
  options: { useRegex: true, maxResults: 50 },
  summary: 'final formula error scan'
})
console.log(errors.ndjson)

const preview = await workbook.render({
  sheetName: '地层水结果数据',
  range: 'A1:H21',
  scale: 1.5,
  format: 'png'
})
await fs.writeFile(
  path.join(outputDir, '地层水结果数据_20条模拟数据_预览.png'),
  new Uint8Array(await preview.arrayBuffer())
)

const xlsx = await SpreadsheetFile.exportXlsx(workbook)
await xlsx.save(path.join(outputDir, '地层水结果数据_20条模拟数据.xlsx'))
await fs.writeFile(
  path.join(outputDir, '地层水结果数据_20条模拟数据.csv'),
  `\uFEFF${csvText}`,
  'utf8'
)
