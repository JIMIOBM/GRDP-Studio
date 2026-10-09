export function assertNoSpreadsheetFormulaCells(XLSX, sheet) {
  if (!sheet?.['!ref']) throw new Error('工作表为空')
  const range = XLSX.utils.decode_range(sheet['!ref'])
  for (let row = range.s.r; row <= range.e.r; row++) {
    for (let column = range.s.c; column <= range.e.c; column++) {
      const address = XLSX.utils.encode_cell({ r: row, c: column })
      const cell = sheet[address]
      if (cell?.t === 'e') throw new Error(`单元格 ${address} 包含 Excel 错误值，请先修正`)
      if (cell?.f != null || cell?.F != null) throw new Error(`单元格 ${address} 包含公式，请将公式粘贴为数值后再导入`)
    }
  }
}
