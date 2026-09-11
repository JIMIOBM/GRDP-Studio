import test from 'node:test'
import assert from 'node:assert/strict'
import {
  gasConditionImportColumns, gasConditionTemplateRows, parseGasConditionRows
} from './pipelineGasConditionImport.js'
import {
  assertTemperatureImportSheet, parseTemperatureImportCsv, temperatureImportCsv
} from './pipelineTemperatureImport.js'

const headers = ['序号', '压力（MPa绝压）', '温度（℃）']
const rows = (pressureMpa = 8, temperatureC = 25) => [[...headers], [1, pressureMpa, temperatureC]]

test('the gas-condition template has exactly one point and the three specified columns', () => {
  assert.deepEqual(gasConditionImportColumns, [
    { key: 'sequence', label: '序号' },
    { key: 'pressureMpa', label: '压力（MPa绝压）' },
    { key: 'temperatureC', label: '温度（℃）' }
  ])
  assert.deepEqual(gasConditionTemplateRows({ pressureMpa: 8, temperatureC: 0 }), [headers, [1, 8, 0]])
})

test('missing point values stay blank in the template and import as null rather than invented conditions', () => {
  for (const point of [undefined, null, {}, { pressureMpa: null, temperatureC: undefined }]) {
    const template = gasConditionTemplateRows(point)
    assert.deepEqual(template, [headers, [1, '', '']])
    assert.deepEqual(parseGasConditionRows(template), { pressureMpa: null, temperatureC: null })
  }
  for (const blank of ['', null, undefined, '  ']) {
    const data = rows()
    data[1][1] = blank
    data[1][2] = blank
    assert.deepEqual(parseGasConditionRows(data), { pressureMpa: null, temperatureC: null })
  }
  assert.deepEqual(parseGasConditionRows(rows('', -20)), { pressureMpa: null, temperatureC: -20 })
  assert.deepEqual(parseGasConditionRows(rows(8, '')), { pressureMpa: 8, temperatureC: null })
})

test('only the manual point fields prefill the template, without reading PVT results or topology data', () => {
  const point = {
    pressureMpa: 8, temperatureC: -10, pvtId: 7, method: 'BWRS',
    gasResults: [{ pressureMpa: 99, temperatureC: 200, z: 0.8 }],
    segments: [{ edgeId: 'pipe-a', propertyPressureMpa: 30, propertyTemperatureC: 50 }],
    topology: { edges: [{ id: 'pipe-a' }] }
  }
  const snapshot = structuredClone(point)
  const template = gasConditionTemplateRows(point)
  assert.deepEqual(template, [headers, [1, 8, -10]])
  const parsed = parseGasConditionRows(template)
  assert.deepEqual(parsed, { pressureMpa: 8, temperatureC: -10 })
  assert.deepEqual(Object.keys(parsed).sort(), ['pressureMpa', 'temperatureC'])
  template[1][1] = 100
  parsed.temperatureC = 900
  assert.deepEqual(point, snapshot)
  const { pressureMpa, temperatureC, ...unrelated } = point
  assert.deepEqual(gasConditionTemplateRows(unrelated), [headers, [1, '', '']])
})

test('decimal and scientific point values are accepted without modifying the worksheet', () => {
  const data = [[...headers], ['1', '1.25e2', '-2.5E1']]
  const snapshot = structuredClone(data)
  assert.deepEqual(parseGasConditionRows(data), { pressureMpa: 125, temperatureC: -25 })
  assert.deepEqual(data, snapshot)
})

test('blank worksheet rows are allowed but exactly one nonblank point row is required', () => {
  assert.deepEqual(parseGasConditionRows([[], headers, ['', null, ' '], [1, 8, 0], [], [null, null, null]]), {
    pressureMpa: 8, temperatureC: 0
  })
  for (const invalid of [[], [headers], [headers, []], [headers, ['', '', '']],
    [...rows(), [1, 10, 30]], [...rows(), [2, '', '']]]) {
    assert.throws(() => parseGasConditionRows(invalid))
  }
})

test('the single point must have sequence one instead of a missing, duplicate or unrelated index', () => {
  for (const invalid of ['', null, 0, -1, 2, 1.5, '0x1', true, '第一个']) {
    const data = rows()
    data[1][0] = invalid
    assert.throws(() => parseGasConditionRows(data), `accepted sequence ${String(invalid)}`)
  }
})

test('column names, units and ordering must match and populated extra columns cannot be ignored', () => {
  for (const alter of [
    data => { data[0][0] = '管道序号' },
    data => { data[0][1] = '压力（kPa）' },
    data => { data[0][2] = '温度（K）' },
    data => { [data[0][1], data[0][2]] = [data[0][2], data[0][1]] },
    data => { data[0].push('PVT方法') },
    data => { data[1].push('PR') },
    data => { data[1].push(0) }
  ]) {
    const data = rows()
    alter(data)
    const snapshot = structuredClone(data)
    assert.throws(() => parseGasConditionRows(data))
    assert.deepEqual(data, snapshot)
  }
})

test('pressure must be strictly positive and finite with no artificial upper limit', () => {
  for (const invalid of [0, '0', '-0', -1, '-1e-3', '1e-999']) {
    assert.throws(() => parseGasConditionRows(rows(invalid, 25)))
  }
  for (const valid of [0.001, 120, 100000, '1e100']) {
    assert.equal(parseGasConditionRows(rows(valid, 25)).pressureMpa, Number(valid))
  }
})

test('temperature accepts zero, negative values above absolute zero and high finite values', () => {
  for (const valid of [0, '0', -20, -273.149, 5000, '1e100']) {
    assert.equal(parseGasConditionRows(rows(8, valid)).temperatureC, Number(valid))
  }
  for (const invalid of [-273.15, '-273.1500', -274, -1e9]) {
    assert.throws(() => parseGasConditionRows(rows(8, invalid)))
  }
})

test('neither pressure nor temperature coerces invalid decimal text or nonnumeric cell types', () => {
  for (const column of [1, 2]) {
    for (const invalid of ['NaN', 'Infinity', '1e309', '0x10', '0b10', '1_000', '1,000', '20 ℃',
      NaN, Infinity, -Infinity, true, false, {}, [], new Date('2026-09-09T00:00:00Z')]) {
      const data = rows()
      data[1][column] = invalid
      assert.throws(() => parseGasConditionRows(data), `column ${column} accepted ${String(invalid)}`)
    }
  }
})

test('CSV point templates round trip blank conditions, zero, negative temperature and high conditions', () => {
  for (const point of [{}, { pressureMpa: 8, temperatureC: 0 },
    { pressureMpa: 120, temperatureC: -20 }, { pressureMpa: 100000, temperatureC: 5000 }]) {
    const template = gasConditionTemplateRows(point)
    const csv = temperatureImportCsv(template)
    assert.deepEqual(parseGasConditionRows(parseTemperatureImportCsv(csv)), parseGasConditionRows(template))
  }
})

test('real XLSX and XLS workbooks preserve the single point, blanks and numeric values', async () => {
  const XLSX = await import('xlsx')
  for (const bookType of ['xlsx', 'biff8']) {
    for (const point of [{}, { pressureMpa: 8, temperatureC: 0 },
      { pressureMpa: 120, temperatureC: -20 }, { pressureMpa: 100000, temperatureC: 5000 }]) {
      const template = gasConditionTemplateRows(point)
      const workbook = XLSX.utils.book_new()
      XLSX.utils.book_append_sheet(workbook, XLSX.utils.aoa_to_sheet(template), '物性计算工况')
      const bytes = XLSX.write(workbook, { type: 'buffer', bookType })
      const restored = XLSX.read(bytes, { type: 'buffer', cellFormula: true, cellDates: true })
      const sheet = restored.Sheets[restored.SheetNames[0]]
      assertTemperatureImportSheet(sheet)
      const table = XLSX.utils.sheet_to_json(sheet, { header: 1, raw: true, defval: null, blankrows: true })
      assert.equal(table[0].length, 3)
      assert.equal(table.length, 2)
      assert.deepEqual(parseGasConditionRows(table), parseGasConditionRows(template))
    }
  }
})

test('Excel formulas and error cells are rejected before they can become blank pressure or temperature', async () => {
  const XLSX = await import('xlsx')
  for (const address of ['B2', 'C2']) {
    for (const badCell of [{ t: 'e', v: 7 }, { t: 'n', v: 2, f: '1+1' }]) {
      const workbook = XLSX.utils.book_new()
      const original = XLSX.utils.aoa_to_sheet(rows())
      original[address] = badCell
      XLSX.utils.book_append_sheet(workbook, original, '物性计算工况')
      const restored = XLSX.read(XLSX.write(workbook, { type: 'buffer', bookType: 'xlsx' }), { type: 'buffer', cellFormula: true })
      assert.throws(() => assertTemperatureImportSheet(restored.Sheets[restored.SheetNames[0]]))
    }
  }
  assert.throws(() => assertTemperatureImportSheet({ B2: { t: 'n', v: 2, F: 'B2:C2' } }))
})
