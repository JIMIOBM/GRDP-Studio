import test from 'node:test'
import assert from 'node:assert/strict'
import { thermalRockFixture } from '../tests/fixtures/eclipseThermalRock.js'
import { validThermalInspection, thermalResult } from '../src/views/SoftwareIntegration/eclipseThermal.js'
import { validThermalRockContext, thermalRockRecords, validThermalRock, matchesThermalRock, buildThermalRock, thermalRockResponse, thermalRockCsv } from '../src/views/SoftwareIntegration/eclipseThermalRock.js'
import { buildThermalPlan, matchesThermalPlan } from '../src/views/SoftwareIntegration/eclipseThermalPlan.js'
for (const kind of ['official', 'output']) for (const name of ['heat-capacity', 'conductivity']) test(`${kind} ${name}: real property input and native thermal response`, () => {
  const f = thermalRockFixture(kind), run = f.cases[name], p = run.parameters
  assert.ok(validThermalInspection(f.inspection)); assert.ok(thermalResult(run, f.inspection)); assert.ok(validThermalRock(p)); assert.ok(matchesThermalRock(p, f.inspection))
  assert.equal(f.inspection.thermalRockContext.cellCount, 180); assert.equal(f.inspection.thermalRockContext.activeCellCount, 100)
  const view = thermalRockResponse(run, f.baseline, f.inspection); assert.ok(view); assert.ok(view.changed)
  const oil = view.cards.find(m => m.keyword === 'WOPT'); assert.equal(oil.value, name === 'heat-capacity' ? 15137.5087890625 : 14966.296875); assert.equal(oil.baselineValue, 15079.8134765625)
  assert.ok(view.metrics.some(m => m.keyword === 'FHLR')); assert.ok(view.rows.some(r => r.difference === null)); assert.equal(view.cards.every(m => m.finalTime === 3650), true)
  const csv = thermalRockCsv(view, run); assert.equal(csv.split('\r\n').length, view.rows.length + 1); assert.ok(csv.includes('vectorIndex'))
})
test('schema23 retains old injection plans while frozen15 rejects new context', () => {
  const f = thermalRockFixture(), original = f.inspection
  const p = buildThermalPlan({ inspection: original, baselineRunId: f.baseline.id, well: 'I', stages: [
    { startTimeDays: 1095, surfaceInjectionRate: 25, injectionTemperature: 450, steamQuality: .7, bhpUpperLimit: 1000 },
    { startTimeDays: 2190, surfaceInjectionRate: 25, injectionTemperature: 400, steamQuality: .7, bhpUpperLimit: 1000 }
  ] })
  assert.ok(matchesThermalPlan(p, original))
  const old = structuredClone(original); old.schemaVersion = 'eclipse-data-inspection/15'; assert.equal(validThermalInspection(old), false)
  delete old.thermalRockContext; assert.ok(validThermalInspection(old)); assert.deepEqual(thermalRockRecords(old), [])
})
test('wrong source, array count, activity, units or records never offers property editing', () => {
  for (const mutate of [c => c.sourceSha256 = '0'.repeat(64), c => c.cellCount = 100, c => c.activeCellCount = 181, c => c.activeCellCount = 0,
    c => c.records.pop(), c => c.records[1].field = 'HEATCR', c => c.records[0].unit = 'degF', c => c.records[0].lineNumber = 0, c => c.extra = 1]) {
    const f = thermalRockFixture(); mutate(f.inspection.thermalRockContext); assert.equal(validThermalRockContext(f.inspection.thermalRockContext, f.inspection), false)
    assert.equal(validThermalInspection(f.inspection), false); assert.deepEqual(thermalRockRecords(f.inspection), [])
  }
})
test('strict scalar protocol and original physical source binding', () => {
  const f = thermalRockFixture(), original = f.cases['heat-capacity'].parameters
  for (const mutate of [p => p.targetValue = 0, p => p.targetValue = '45', p => p.targetValue = NaN, p => p.targetValue = p.originalValue, p => p.unit = 'degF', p => p.extra = 1, p => p.cellCount = 180.5]) {
    const p = structuredClone(original); mutate(p); assert.equal(validThermalRock(p), false)
  }
  for (const mutate of [p => p.sourceSha256 = '0'.repeat(64), p => p.lineNumber++, p => p.cellCount = 100, p => p.originalValue = 34, p => p.unit = 'kJ/m3/K']) {
    const p = structuredClone(original); mutate(p); assert.equal(matchesThermalRock(p, f.inspection), false)
  }
  assert.ok(buildThermalRock({ inspection: f.inspection, baselineRunId: f.baseline.id, field: 'THCONR', targetValue: 0 }))
})
test('foreign or partial baseline and missing raw columns cannot show actual response', () => {
  for (const fault of ['version', 'baseline', 'partial', 'column']) {
    const f = thermalRockFixture(), run = f.cases['heat-capacity']
    if (fault === 'version') f.baseline.modelVersionId++
    if (fault === 'baseline') f.baseline.id++
    if (fault === 'partial') run.resultContract = 'VALID_PARTIAL'
    if (fault === 'column') run.result.summary.series.pop()
    assert.equal(thermalRockResponse(run, f.baseline, f.inspection), null)
  }
})
