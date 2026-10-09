import test from 'node:test'
import assert from 'node:assert/strict'
import * as XLSX from 'xlsx'
import { cleanPressureGradientImportRows, mergePressureGradientImportRows, parsePressureGradientRows, pressureGradientImportColumns } from './pressureGradientImport.js'
import { assertNoSpreadsheetFormulaCells } from './spreadsheetImport.js'

test('imports rows for actual member wells and normalizes Excel date and numbers', () => {
  const workbook = XLSX.utils.book_new()
  const sheet = XLSX.utils.aoa_to_sheet([
    pressureGradientImportColumns,
    ['X-1', new Date('2025-03-11T00:00:00Z'), 22.4, 2100, 0.011],
    ['X-2', 45727, '21.8', '', 0.01],
    ['', '', '', '', '']
  ])
  XLSX.utils.book_append_sheet(workbook, sheet, '压力梯度')
  const file = XLSX.read(XLSX.write(workbook, { type: 'buffer', bookType: 'xlsx' }), { type: 'buffer', cellDates: true, cellFormula: true })
  const firstSheet = file.Sheets[file.SheetNames[0]]
  assertNoSpreadsheetFormulaCells(XLSX, firstSheet)
  const rows = XLSX.utils.sheet_to_json(firstSheet, { header: 1, raw: true, defval: '' })
  assert.deepEqual(parsePressureGradientRows(rows, ['X-1', 'X-2']), [
    { wellName: 'X-1', date: '2025-03-11', measuredPressure: '22.4', measuredCoordinate: '2100', gradient: '0.011' },
    { wellName: 'X-2', date: '2025-03-11', measuredPressure: '21.8', measuredCoordinate: '', gradient: '0.01' }
  ])
})

test('rejects foreign wells, invalid dates, negative values, formulas and wrong headers', () => {
  const header = pressureGradientImportColumns
  assert.throws(() => parsePressureGradientRows([header, ['outside', '2025-01-01', 20, 2000, 0.01]], ['X-1']), /不是当前储气库成员井/)
  assert.throws(() => parsePressureGradientRows([header, ['X-1', '2025-02-30', 20, 2000, 0.01]], ['X-1']), /日期无效/)
  assert.throws(() => parsePressureGradientRows([header, ['X-1', '2025-01-01', -1, 2000, 0.01]], ['X-1']), /非负/)
  assert.throws(() => parsePressureGradientRows([['date', ...header.slice(1)]], ['X-1']), /表头必须/)
  const sheet = XLSX.utils.aoa_to_sheet([header, ['X-1', '2025-01-01', 20, 2000, 0.01]])
  sheet.E2.f = '1/100'
  assert.throws(() => assertNoSpreadsheetFormulaCells(XLSX, sheet), /包含公式/)
})

test('does not silently drop partially filled rows or ignore extra data columns', () => {
  const header = pressureGradientImportColumns
  assert.throws(() => parsePressureGradientRows([header, ['X-1', '', '', '', '']], ['X-1']), /日期无效/)
  assert.throws(() => parsePressureGradientRows([header, ['X-1', '2025-01-01', 20, 2000, 0.01, 'unexpected']], ['X-1']), /表头之外/)
  assert.deepEqual(parsePressureGradientRows([['\uFEFF井号', ...header.slice(1)], ['X-1', '2025-01-01', 20, 2000, 0.01]], ['X-1']), [
    { wellName: 'X-1', date: '2025-01-01', measuredPressure: '20', measuredCoordinate: '2000', gradient: '0.01' }
  ])
  assert.equal(parsePressureGradientRows([[...header, '', ''], ['X-1', '2025-01-01', 20, 2000, 0.01, '', '']], ['X-1']).length, 1)
  assert.throws(() => parsePressureGradientRows([header, ['X-1', Number.MAX_VALUE, 20, 2000, 0.01]], ['X-1']), /日期无效/)
})

test('keeps multiple same-day depth points and updates them on a repeated import', () => {
  const existing = [{
    id: 'source-1', sourceKey: 'source:X-1:2025-01-01:0', wellName: 'X-1', date: '2025-01-01',
    measuredPressure: '20', measuredCoordinate: '', gradient: '', manual: false, deleted: false
  }]
  const imported = [
    { wellName: 'X-1', date: '2025-01-01', measuredPressure: '20', measuredCoordinate: '2100', gradient: '0.01' },
    { wellName: 'X-1', date: '2025-01-01', measuredPressure: '19', measuredCoordinate: '2200', gradient: '0.01' }
  ]
  const once = mergePressureGradientImportRows(existing, imported, () => 'import-2')
  assert.equal(once.length, 2)
  assert.deepEqual(once.map(row => row.measuredCoordinate), ['2100', '2200'])

  const repeated = mergePressureGradientImportRows(once, [
    { ...imported[0], measuredPressure: '20.5' },
    imported[1]
  ], () => 'unexpected-duplicate')
  assert.equal(repeated.length, 2)
  assert.deepEqual(repeated.map(row => row.measuredPressure), ['20.5', '19'])
})

test('cleans only requested blank rows, blank columns, and all-zero measurement rows', () => {
  const rows = [
    [...pressureGradientImportColumns, ''],
    ['X-1', '2025-01-01', 0, 0, 0, ''],
    ['', '', '', '', '', ''],
    ['X-2', '2025-02-01', 21, 2000, 0.01, '']
  ]

  const cleaned = cleanPressureGradientImportRows(rows, {
    removeEmptyRows: true,
    removeEmptyColumns: true,
    removeZeroRows: true
  })

  assert.deepEqual(cleaned, [
    pressureGradientImportColumns,
    ['X-2', '2025-02-01', 21, 2000, 0.01]
  ])
})
