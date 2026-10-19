import test from 'node:test'
import assert from 'node:assert/strict'
import { inputCurveNumber, inputCurveModel, inputCurvePanels, inputCurveCsv } from '../src/views/SoftwareIntegration/eclipseInputCurves.js'
import { buildOilPvt } from '../src/views/SoftwareIntegration/eclipseOilPvt.js'
import { readFileSync } from 'node:fs'

const model = (tables, extra = {}) => inputCurveModel({ tables, unitSystem: 'FIELD', ...extra })
const pvdo = [{ keyword: 'PVDO', keywordLineNumber: 10, rows: [{ line: 11, pressure: '2500', bo: '1.26', viscosity: '0.5', target: 1 }, { line: 12, pressure: '5000', bo: '1.243', viscosity: '0.5', target: 1 }] }]
test('explicit numbers accept Fortran exponents and zero without coercing defaults or blanks', () => {
  for (const [token, number] of [['-2D-3', -.002], ['.5d+2', 50], ['0', 0], [0, 0], ['+2.', 2], [' 2e1 ', 20]]) assert.equal(inputCurveNumber(token), number)
  for (const value of ['', ' ', null, undefined, false, '*', '2*0.3', '1/2', '0x10', 'NaN', Infinity, '1e999']) assert.equal(inputCurveNumber(value), null)
})
test('untouched input never validates or displays a numerical target', () => {
  const result = model(pvdo, { field: 'VISCOSITY', validate() { assert.fail('No draft') } })
  assert.equal(result.status, 'original'); assert.equal(result.pointCount, 2)
  assert.deepEqual(result.tables[0].branches[0].metrics[1].points.map(p => p.target), [null, null])
})
test('Bo and viscosity use separate physical panels; only selected column gets a target', () => {
  const result = model(pvdo, { field: 'VISCOSITY', multiplier: 2, validate() { return {} } }), panels = inputCurvePanels(result.tables[0])
  assert.equal(result.status, 'preview'); assert.deepEqual(panels.map(p => p.unit), ['rb/stb', 'cP'])
  assert.equal(panels[0].series.length, 1); assert.equal(panels[1].series.length, 2)
  assert.deepEqual(panels[1].series[1].points.map(p => [p.x, p.original, p.target, p.line]), [[2500, .5, 1, 11], [5000, .5, 1, 12]])
})
test('actual builder rejects invalid draft and curve retains original points only', () => {
  const inspection = JSON.parse(readFileSync(new URL('../tests/fixtures/eclipseOilPvt/platform-inspection.json', import.meta.url), 'utf8'))
  const run = { id: 538, modelVersionId: 175, runType: 'eclipse', status: 'SUCCEEDED', resultContract: 'VALID_FULL', parameters: null }
  for (const multiplier of [0, 1, 1e10]) {
    const result = model(pvdo, { field: 'VISCOSITY', multiplier, validate: () => buildOilPvt({ inspection, run, field: 'VISCOSITY', multiplier }) })
    assert.equal(result.status, 'invalid'); assert.ok(result.error); assert.equal(inputCurvePanels(result.tables[0]).flatMap(p => p.series).filter(s => s.target).length, 0)
    assert.equal(result.tables[0].branches[0].metrics[0].points[0].original, 1.26)
  }
})
test('missing validation hides all target points', () => {
  const result = model(pvdo, { field: 'VISCOSITY', multiplier: 2 })
  assert.equal(result.status, 'invalid'); assert.match(result.error, /校验/)
  assert.equal(result.tables[0].branches[0].metrics[1].points[0].target, null)
})
test('two oil relative-permeability columns are both preserved and previewed; zero is a point', () => {
  const result = model([{ keyword: 'SOF3', rows: [{ line: 1, saturation: '0', kr: '0', third: '0', target: 0, targetOilGas: 0 }, { line: 2, saturation: '.8', kr: '.9', third: '.9', target: .45, targetOilGas: .45 }] }], { multiplier: .5, validate() {} })
  const panels = inputCurvePanels(result.tables[0]); assert.equal(panels.length, 1); assert.equal(panels[0].series.length, 4)
  assert.deepEqual(panels[0].series[1].points.map(p => p.target), [0, .45]); assert.deepEqual(panels[0].labels, ['Krow', 'Krog'])
})
for (const [keyword, field] of [['SWOF', 'OIL_KR'], ['SGOF', 'GAS_KR']]) test(`${keyword} phase/oil and capillary pressure do not mix units or target columns`, () => {
  const result = model([{ keyword, rows: [{ line: 1, saturation: '.2', phase: '.1', oil: '.9', pc: '-2D-3', target: .05 }] }], { field, multiplier: .5, validate() {} })
  const panels = inputCurvePanels(result.tables[0]); assert.deepEqual(panels.map(p => p.unit), ['无量纲', 'psi'])
  assert.equal(panels[1].series.length, 1); assert.equal(panels[1].series[0].points[0].original, -.002)
  const target = panels[0].series.find(s => s.target); assert.equal(target.metric, field === 'OIL_KR' ? 'Krow' : 'Krg')
})
test('PVTO never connects different Rs branches, including single saturated table points', () => {
  const result = model([{ keyword: 'PVTO', rows: [{ line: 1, branch: 1, rs: '.1', pressure: '100', bo: '1.1', viscosity: '.9', state: '饱和' }, { line: 2, branch: 2, rs: '.2', pressure: '200', bo: '1.2', viscosity: '.8', state: '饱和' }, { line: 3, branch: 2, rs: '.2', pressure: '400', bo: '1.15', viscosity: '.85', state: '欠饱和' }] }])
  const table = result.tables[0], panel = inputCurvePanels(table)[0]
  assert.equal(table.xLabel, '压力'); assert.equal(table.xUnit, 'psia'); assert.equal(panel.series.length, 2)
  assert.deepEqual(panel.series.map(s => s.points.map(p => p.x)), [[100], [200, 400]])
  assert.match(table.branches[1].label, /Rs 0.2 Mscf\/stb/); assert.equal(inputCurvePanels(table, '1')[0].series.length, 1)
})
test('PVTG x is descending Rv and branches are constant pressures; source order is retained', () => {
  const result = model([{ keyword: 'PVTG', rows: [{ line: 1, branch: 1, pressure: '100', rv: '2D-4', bg: '.1', viscosity: '.02' }, { line: 2, branch: 1, pressure: '100', rv: '0', bg: '.09', viscosity: '.021' }, { line: 3, branch: 2, pressure: '200', rv: '3D-4', bg: '.08', viscosity: '.03' }] }], { unitSystem: 'METRIC' })
  const table = result.tables[0]; assert.equal(table.xLabel, '汽化油气比 Rv'); assert.equal(table.xUnit, 'sm3/sm3')
  assert.match(table.branches[0].label, /压力 100 barsa/); assert.deepEqual(table.branches[0].metrics[0].points.map(p => p.x), [.0002, 0])
  assert.equal(inputCurvePanels(table)[0].unit, 'rm3/sm3')
})
test('gas FIELD volume has rb/Mscf and unknown systems are blocked', () => {
  assert.equal(model([{ keyword: 'PVDG', rows: pvdo[0].rows }]).tables[0].branches[0].metrics[0].unit, 'rb/Mscf')
  assert.equal(model(pvdo, { unitSystem: 'UNKNOWN' }).status, 'unavailable')
})
test('partial, infinite or ambiguous source data never produces fabricated curve points', () => {
  for (const row of [{ ...pvdo[0].rows[0], bo: '' }, { ...pvdo[0].rows[0], pressure: Infinity }, { ...pvdo[0].rows[0], line: undefined }]) {
    const result = model([{ keyword: 'PVDO', rows: [row] }]); assert.equal(result.status, 'unavailable'); assert.deepEqual(result.tables, [])
  }
  const rows = [{ line: 1, branch: 1, rs: '.1', pressure: '100', bo: '1', viscosity: '1' }, { line: 2, branch: 1, rs: '.2', pressure: '200', bo: '1', viscosity: '1' }]
  assert.equal(model([{ keyword: 'PVTO', rows }]).status, 'unavailable')
})
test('CSV retains identity, all branches, source line, original values, zero and preview state', () => {
  const result = model(pvdo, { field: 'VISCOSITY', multiplier: 2, validate() {} })
  const csv = inputCurveCsv(result, { runId: 538, modelVersionId: 175, sourceFile: '=bad,"name".DATA', region: 2, regionKind: 'PVT' })
  assert.equal(csv.split('\r\n').length, 5); assert.match(csv, /"538","175","'=bad,""name"".DATA","PVT","2","PVDO","10"/)
  assert.match(csv, /"0.5","1","11","","preview"/); assert.match(csv, /"1.26","","11"/)
})
