import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { waterInjectionPlanRecords, buildWaterInjectionPlan, waterInjectionPlanMatches, validWaterInjectionPlan,
  waterInjectionPlanResponse } from '../src/views/SoftwareIntegration/eclipseWaterInjectionPlan.js'

const load = name => JSON.parse(readFileSync(new URL(`../tests/fixtures/eclipseWaterInjectionPlan/${name}.json`, import.meta.url), 'utf8'))
const inspection = load('inspection'), native = load('native')
const request = () => buildWaterInjectionPlan({ inspection, baselineRunId: 1, edits: waterInjectionPlanRecords(inspection).map((r, n) =>
  ({ ...r, targetInjectionRate: n === 2 ? .5 : n === 5 ? .7 : n === 6 ? .9 : r.originalInjectionRate })) })

test('native-qualified water control chain binds eight records and three different physical source rows', () => {
  const records = waterInjectionPlanRecords(inspection), p = request()
  assert.equal(records.length, 8)
  assert.deepEqual(p.edits.map(r => [r.well, r.timeDays, r.lineNumber, r.targetInjectionRate]),
    [['INJECTR5', 365, 2, .5], ['INJECTR6', 730, 3, .7], ['INJECTR5', 1095, 2, .9]])
  assert.equal(p.schemaVersion, 'eclipse-water-injection-plan-parameters/1')
  assert.ok(waterInjectionPlanMatches(p, inspection))
})

test('actual native water injection response preserves preceding boundary interval and later original override', () => {
  const response = waterInjectionPlanResponse(native.target, native.baseline, request(), inspection)
  assert.ok(native.nativeVerified && native.completeCurvesEqualFlatSource)
  assert.equal(native.target.series.length, 11)
  assert.ok(response.available && response.consistent && response.verified)
  const value = (well, time) => response.rows.find(r => r.well === well && r.timeDays === time)
  for (const [well, time, rate] of [['INJECTR5', 365, 1], ['INJECTR5', 730, .5], ['INJECTR5', 1095, 1], ['INJECTR6', 1095, .7], ['INJECTR6', 1460, 1]]) {
    assert.ok(Math.abs(value(well, time).rate - rate) < .0001)
    assert.equal(value(well, time).target, rate)
  }
})

test('native pressure-limited water injection respects the upper BHP bound and never claims all targets attained', () => {
  const p = buildWaterInjectionPlan({ inspection, baselineRunId: 1, edits: waterInjectionPlanRecords(inspection)
    .filter(r => r.timeDays > 0).map(r => ({ ...r, targetInjectionRate: 10 })) })
  const response = waterInjectionPlanResponse(native.limited, native.baseline, p, inspection)
  assert.ok(response.available && response.consistent && response.constrained)
  assert.equal(response.verified, false)
  assert.ok(response.rows.some(r => r.atBhpLimit && !r.targetReached && r.rate < r.target))
  assert.ok(response.rows.every(r => r.bhp <= 6000.1))
})

test('physical hashes, raw phase, date, units and control order cannot be forged into editable water records', () => {
  for (const fault of ['hash', 'phase', 'date', 'unit', 'order']) {
    const i = structuredClone(inspection), r = i.waterInjectionControlPlanContext.records[2]
    if (fault === 'hash') r.sourceSha256 = '0'.repeat(64)
    if (fault === 'phase') i.scheduleMetadata.records.find(x => x.keyword === 'WCONINJE' && x.sourceFile === r.sourceFile && x.lineNumber === r.lineNumber).values[1] = 'GAS'
    if (fault === 'date') r.date = '1983-10-20'
    if (fault === 'unit') r.unit = 'sm3/day'
    if (fault === 'order') i.waterInjectionControlPlanContext.records.reverse()
    assert.deepEqual(waterInjectionPlanRecords(i), [], fault)
  }
  const p = request(); p.edits[0].targetInjectionRate = '0.5'
  assert.equal(validWaterInjectionPlan(p), false)
})

test('missing real injection points and mixed pressure units cannot fabricate a comparison', () => {
  const s = structuredClone(native.target)
  s.series.find(r => r.keyword === 'WBHP').unit = 'barsa'
  assert.equal(waterInjectionPlanResponse(s, native.baseline, request(), inspection).available, false)
  const missing = structuredClone(native.target)
  missing.series.find(r => r.keyword === 'WWIR').points.pop()
  assert.equal(waterInjectionPlanResponse(missing, native.baseline, request(), inspection).available, false)
})
