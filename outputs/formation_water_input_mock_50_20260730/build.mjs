import fs from 'node:fs/promises'
import path from 'node:path'
import { SpreadsheetFile, Workbook } from '@oai/artifact-tool'

const outputDir = path.resolve('.')
const headers = [
  '地层水矿化度(mg/L)',
  '原始地层压力(MPa)',
  '地层温度(℃)'
]

const rows = Array.from({ length: 50 }, (_, index) => [
  25000,
  (index + 1) * 4,
  119.85
])

const csvText = [headers, ...rows]
  .map(row => row.join(','))
  .join('\r\n')

const workbook = await Workbook.fromCSV(csvText, { sheetName: '地层水计算输入' })
const sheet = workbook.worksheets.getItem('地层水计算输入')
sheet.showGridLines = false
sheet.freezePanes.freezeRows(1)

sheet.getRange('A1:C1').format = {
  fill: '#1F4E78',
  font: { bold: true, color: '#FFFFFF' },
  horizontalAlignment: 'center',
  verticalAlignment: 'center',
  borders: { preset: 'outside', style: 'thin', color: '#17365D' }
}
sheet.getRange('A1:C1').format.rowHeight = 30
sheet.getRange('A2:C51').format = {
  horizontalAlignment: 'right',
  verticalAlignment: 'center',
  borders: {
    insideHorizontal: { style: 'thin', color: '#D9E2F3' }
  },
  numberFormat: '0.00'
}
sheet.getRange('A1:A51').format.columnWidth = 25
sheet.getRange('B1:B51').format.columnWidth = 23
sheet.getRange('C1:C51').format.columnWidth = 18

const inspected = await workbook.inspect({
  kind: 'table',
  range: '地层水计算输入!A1:C51',
  include: 'values,formulas',
  tableMaxRows: 51,
  tableMaxCols: 3,
  maxChars: 10000
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
  sheetName: '地层水计算输入',
  range: 'A1:C51',
  scale: 1,
  format: 'png'
})
await fs.writeFile(
  path.join(outputDir, '地层水三列模板_50条模拟数据_预览.png'),
  new Uint8Array(await preview.arrayBuffer())
)

const xlsx = await SpreadsheetFile.exportXlsx(workbook)
await xlsx.save(path.join(outputDir, '地层水三列模板_50条模拟数据.xlsx'))
await fs.writeFile(
  path.join(outputDir, '地层水三列模板_50条模拟数据.csv'),
  `\uFEFF${csvText}`,
  'utf8'
)
