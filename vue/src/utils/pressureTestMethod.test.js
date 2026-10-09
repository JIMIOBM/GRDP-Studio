import test from 'node:test'
import assert from 'node:assert/strict'
import * as XLSX from 'xlsx'
import { buildPressureTestComparison } from './pressureTestMethod.js'
import { mergePressureTestImportRows, parsePressureTestRows, pressureTestImportColumns } from './pressureTestImport.js'

test('test method chart uses actual pressures, averages duplicate same-day measurements and leaves gaps blank', () => {
  const model = buildPressureTestComparison({
    rows: [
      { wellName: 'X-1', date: '2025-01-01', measuredPressure: '20' },
      { wellName: 'X-1', date: '2025-01-01', measuredPressure: '22' },
      { wellName: 'X-2', date: '2025-01-02', measuredPressure: '18' },
      { wellName: 'X-2', date: '2025-01-03', measuredPressure: '', manual: true },
      { wellName: 'outside', date: '2025-01-01', measuredPressure: 99 }
    ],
    selectedWells: ['X-1', 'X-2'], dateRange: 'all', chartAxis: 'well'
  })
  assert.deepEqual(model.categories, ['X-1', 'X-2'])
  assert.deepEqual(model.series.map(row => row.data), [[21, null], [null, 18]])
  assert.equal(model.maxPressure, 21)
})

test('test method table import accepts only current member well pressure measurements', () => {
  const workbook = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(workbook, XLSX.utils.aoa_to_sheet([
    pressureTestImportColumns,
    ['X-1', new Date('2025-03-11T00:00:00Z'), 22.4],
    ['X-2', 45727.5, '21.8'],
    ['', '', '']
  ]), '测试法')
  const file = XLSX.read(XLSX.write(workbook, { type: 'buffer', bookType: 'xlsx' }), { type: 'buffer', cellDates: true })
  const rows = XLSX.utils.sheet_to_json(file.Sheets[file.SheetNames[0]], { header: 1, raw: true, defval: '' })
  assert.deepEqual(parsePressureTestRows(rows, ['X-1', 'X-2']), [
    { wellName: 'X-1', date: '2025-03-11', measuredPressure: '22.4' },
    { wellName: 'X-2', date: '2025-03-11', measuredPressure: '21.8' }
  ])
  assert.throws(() => parsePressureTestRows([pressureTestImportColumns, ['X-3', '2025-01-01', 20]], ['X-1']), /不是当前储气库成员井/)
})

test('test method import rejects partially filled rows and extra values instead of silently dropping them', () => {
  const header = pressureTestImportColumns
  assert.throws(() => parsePressureTestRows([header, ['X-1', '', '']], ['X-1']), /日期无效/)
  assert.throws(() => parsePressureTestRows([header, ['X-1', '2025-01-01', 20, 'unexpected']], ['X-1']), /表头之外/)
  assert.throws(() => parsePressureTestRows([header, ['X-1', '2025-01-01', true]], ['X-1']), /数字单元格/)
  assert.equal(parsePressureTestRows([[...header, ''], ['X-1', '2025-01-01', 20, '']], ['X-1']).length, 1)
  assert.throws(() => parsePressureTestRows([header, ['X-1', Number.MAX_VALUE, 20]], ['X-1']), /日期无效/)
})

test('reimport updates a single saved test and keeps multiple same-day readings without duplicating them', () => {
  const item1 = { wellName: 'X-1', date: '2025-01-01', measuredPressure: '20' }
  const item2 = { wellName: 'X-1', date: '2025-01-01', measuredPressure: '22' }
  const once = mergePressureTestImportRows([], [item1, item2], () => 'new-row')
  assert.deepEqual(once.map(row => row.measuredPressure), ['20', '22'])
  const repeated = mergePressureTestImportRows(once, [item1, item2], () => 'duplicate-row')
  assert.equal(repeated.length, 2)
  const corrected = mergePressureTestImportRows(once.slice(0, 1), [{ ...item1, measuredPressure: '21' }], () => 'unexpected-row')
  assert.equal(corrected.length, 1)
  assert.equal(corrected[0].measuredPressure, '21')
})
