import test from 'node:test'
import assert from 'node:assert/strict'
import { thermalPlanFixture } from '../tests/fixtures/eclipseThermalPlan.js'
import { validThermalPlan, matchesThermalPlan, thermalPlanResponse, thermalPlanPeriods, thermalPlanEffective, thermalPlanCsv } from '../src/views/SoftwareIntegration/eclipseThermalPlan.js'
for (const kind of ['official', 'output']) for (const name of ['two-stage', 'restore', 'pressure-limited']) test(`${kind} ${name}: native staged response and original controls`, () => {
  const f = thermalPlanFixture(kind), run = f.plans[name], p = run.parameters
  assert.ok(validThermalPlan(p)); assert.ok(matchesThermalPlan(p, f.inspection))
  const response = thermalPlanResponse(run.result.summary, f.baseline.result.summary, p, f.inspection)
  assert.ok(response.verified); assert.ok(response.preStageVerified); assert.ok(response.complete)
  const final = response.metrics.find(m => m.keyword === 'WWIT').finalValue
  assert.equal(final, name === 'two-stage' ? 104937.5 : name === 'restore' ? 109500 : 155220.328125)
  assert.equal(response.pressureLimited, name === 'pressure-limited'); assert.equal(response.temperatureAvailable, kind === 'output')
  const periods = thermalPlanPeriods(p, f.inspection); assert.equal(periods.at(-1).endTimeDays, 3650)
  assert.equal(thermalPlanEffective(p, p.stages[0].startTimeDays), p.original)
  assert.equal(thermalPlanEffective(p, p.stages[0].startTimeDays + 1), p.stages[0])
  if (name === 'restore') assert.equal(thermalPlanEffective(p, 3000).surfaceInjectionRate, 37.5)
  const csv = thermalPlanCsv(response, run.id, p.baselineRunId); assert.equal(csv.split('\r\n').length, response.rows.length + 1); assert.ok(response.rows.every(r => r.targetRateUnit === p.rateUnit && r.pressureLimitUnit === p.pressureUnit))
  assert.ok(response.rows.every(r => r.difference === r.value - r.baselineValue || r.baselineValue === null && r.difference === null))
})
test('plan protocol rejects invalid shapes, ordering, nonfinite values and no-op stages', () => {
  const f = thermalPlanFixture(), original = f.plans['two-stage'].parameters
  for (const mutate of [p => p.stages.pop(), p => p.stages[0].startTimeDays = 2190, p => p.stages[0].startTimeDays = 1095.5,
    p => p.stages[0].surfaceInjectionRate = '25', p => p.stages[0].steamQuality = 1.1, p => p.stages[0].injectionTemperature = -459.67,
    p => p.stages[0].bhpUpperLimit = Infinity, p => p.stages[1].extra = 1, p => delete p.stages[0].steamQuality,
    p => Object.assign(p.stages[0], p.original), p => p.pressureUnit = 'barsa', p => p.baselineRunId = '1',
    p => p.schemaVersion = 'eclipse-thermal-injection-parameters/3', p => p.stages.push(...Array(7).fill(p.stages[0]))]) {
    const p = structuredClone(original); mutate(p); assert.equal(validThermalPlan(p), false)
  }
})
test('every report boundary, original field and physical source must match inspection', () => {
  const f = thermalPlanFixture(), original = f.plans['two-stage'].parameters
  for (const mutate of [p => p.stages[0].startTimeDays = 1094, p => p.stages[1].startTimeDays = 3650, p => p.sourceSha256 = '0'.repeat(64),
    p => p.well = 'PNEAR', p => p.lineNumber++, p => p.controlLineNumber--, p => p.original.steamQuality = .6, p => p.original.injectionTemperature++]) {
    const p = structuredClone(original); mutate(p); assert.equal(matchesThermalPlan(p, f.inspection), false)
  }
})
test('an earlier curve change, missing column or actual limit breach cannot pass verification', () => {
  for (const fault of ['early', 'missing', 'rate', 'pressure', 'end']) {
    const f = thermalPlanFixture('output'), run = f.plans['two-stage'], summary = run.result.summary
    if (fault === 'early') summary.series.find(s => s.keyword === 'WOPT' && s.objectName === 'PNEAR').points[0].value += 1
    if (fault === 'missing') summary.series.pop()
    if (fault === 'rate') summary.series.find(s => s.keyword === 'WWIR' && s.objectName === 'I').points.at(-1).value = 26
    if (fault === 'pressure') summary.series.find(s => s.keyword === 'WBHP' && s.objectName === 'I').points.at(-1).value = 1001
    if (fault === 'end') for (const s of summary.series) s.points.pop()
    const response = thermalPlanResponse(summary, f.baseline.result.summary, run.parameters, f.inspection)
    assert.ok(!response || !response.verified, fault)
  }
})
