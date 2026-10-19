import test from 'node:test'
import assert from 'node:assert/strict'
import { includeControlPlanFixture } from '../tests/fixtures/eclipseIncludeControlPlan.js'
import { controlPlanRecords, controlPlanPeriods, buildControlPlan, validControlPlan, controlPlanResponse } from '../src/views/SoftwareIntegration/eclipseControlPlan.js'

test('complete INCLUDE execution order keeps ten controls, source hashes and physical rows', () => {
  const f = includeControlPlanFixture()
  assert.equal(f.records.length, 10); assert.equal(validControlPlan(f.parameters), true)
  assert.equal(f.parameters.schemaVersion, 'eclipse-control-plan-parameters/2')
  assert.deepEqual(f.parameters.edits.map(e => [e.well, e.timeDays, e.sourceFile, e.lineNumber, e.targetOilRate]), [
    ['PRODUCR1', 365, 'controls/YEAR1.INC', 2, 250], ['PRODUCR2', 730, 'controls/YEAR2.INC', 3, 350], ['PRODUCR1', 1095, 'controls/YEAR3.INC', 2, 450]])
  assert.ok(f.parameters.edits.every(e => /^[a-f0-9]{64}$/.test(e.sourceSha256)))
  const periods = controlPlanPeriods(f.inspection)
  assert.equal(periods.find(r => r.well === 'PRODUCR1' && r.timeDays === 365).nextTimeDays, 730)
  assert.equal(periods.find(r => r.well === 'PRODUCR1' && r.timeDays === 1095).nextTimeDays, 3650)
})
test('all real native points honor dated targets and subsequent unedited overrides', () => {
  const f = includeControlPlanFixture(), r = controlPlanResponse(f.target.result.summary, f.baseline.result.summary, f.parameters, f.inspection)
  assert.equal(r.available, true); assert.equal(r.consistent, true); assert.equal(r.verified, true); assert.equal(r.edits.length, 3)
  for (const [well, time, target] of [['PRODUCR1', 365, 500], ['PRODUCR1', 730, 250], ['PRODUCR1', 1095, 500], ['PRODUCR2', 1095, 350], ['PRODUCR1', 1460, 450], ['PRODUCR2', 1460, 500]]) {
    assert.equal(r.rows.find(row => row.well === well && row.timeDays === time)?.target, target)
  }
  assert.equal(r.rows.length, 2 * f.target.result.summary.series.find(s => s.keyword === 'WOPR' && s.objectName === 'PRODUCR1').points.filter(p => p.timeDays > 0).length)
})
test('native continuous high-rate plan shows BHP limitations and leaves adaptive gaps empty', () => {
  const f = includeControlPlanFixture(), r = controlPlanResponse(f.limited.result.summary, f.baseline.result.summary, f.limitedParameters, f.inspection)
  assert.equal(r.available, true); assert.equal(r.consistent, true); assert.equal(r.verified, false); assert.equal(r.constrained, true)
  assert.ok(r.rows.some(row => !row.targetReached && row.atBhpLimit))
  const missing = r.rows.filter(row => !row.compared)
  assert.ok(missing.length > 0); assert.ok(missing.every(row => row.baselineRate === null))
})
test('forged physical metadata and incomplete or inconsistent control chains never become editable', () => {
  for (const fault of ['hash', 'line', 'date', 'time', 'unit', 'original', 'bhp', 'missing', 'completion', 'mode', 'extra']) {
    const f = includeControlPlanFixture(), p = f.inspection.packageProductionControlPlanContext, r = p.records[4]
    if (fault === 'hash') r.sourceSha256 = '0'.repeat(64)
    if (fault === 'line') r.lineNumber++
    if (fault === 'date') r.date = '1983-10-20'
    if (fault === 'time') r.timeDays++
    if (fault === 'unit') r.unit = 'sm3/day'
    if (fault === 'original') r.originalOilRate++
    if (fault === 'bhp') r.bhpLimit--
    if (fault === 'missing') p.records.pop()
    if (fault === 'completion') f.inspection.scheduleMetadata.completions[0].status = 'SHUT'
    if (fault === 'mode') f.inspection.scheduleMetadata.records.find(x => x.keyword === 'WCONPROD').values[2] = 'LRAT'
    if (fault === 'extra') p.extra = 1
    assert.deepEqual(controlPlanRecords(f.inspection), [], fault)
  }
})
test('stale edit bindings, reversed execution order, duplicate physical rows and wrong types reject', () => {
  for (const fault of ['source', 'hash', 'date', 'time', 'unit', 'original', 'order', 'duplicate', 'string', 'extra', 'downgrade']) {
    const f = includeControlPlanFixture(), p = f.parameters, e = p.edits[1]
    if (fault === 'source') e.sourceFile = 'controls/YEAR1.INC'
    if (fault === 'hash') e.sourceSha256 = '0'.repeat(64)
    if (fault === 'date') e.date = '1984-10-19'
    if (fault === 'time') e.timeDays++
    if (fault === 'unit') e.unit = 'sm3/day'
    if (fault === 'original') e.originalOilRate++
    if (fault === 'order') p.edits.reverse()
    if (fault === 'duplicate') p.edits.push(structuredClone(e))
    if (fault === 'string') e.targetOilRate = '350'
    if (fault === 'extra') p.extra = 1
    if (fault === 'downgrade') p.schemaVersion = 'eclipse-control-plan-parameters/1'
    assert.equal(controlPlanResponse(f.target.result.summary, f.baseline.result.summary, p, f.inspection).available, false, fault)
  }
})
test('unsafe source paths, untouched plans and malformed package contexts have no legacy fallback', () => {
  const f = includeControlPlanFixture()
  assert.equal(buildControlPlan({ inspection: f.inspection, baselineRunId: 9771, edits: f.records.map(c => ({ ...c, targetOilRate: c.originalOilRate })) }), null)
  for (const path of ['../YEAR1.INC', 'C:/YEAR1.INC', 'controls//YEAR1.INC', 'controls/./YEAR1.INC', 'controls\\YEAR1.INC']) {
    const p = structuredClone(f.parameters); p.edits[0].sourceFile = path; assert.equal(validControlPlan(p), false)
  }
  f.inspection.packageProductionControlPlanContext = null
  assert.deepEqual(controlPlanRecords(f.inspection), [])
})

test('actual native convergence problem remains partial with unaltered raw diagnosis', () => {
  const f = includeControlPlanFixture()
  assert.equal(f.partial.status, 'PARTIAL_SUCCEEDED'); assert.equal(f.partial.result.resultContract, 'VALID_PARTIAL')
  assert.equal(f.partial.result.eclEnd.problems, 1); assert.equal(f.partial.result.eclEnd.errors, 0)
  assert.ok(f.partial.result.messages.some(m => m.severity === 'PROBLEM' && /NON-LINEAR/.test(m.message)))
  const r = controlPlanResponse(f.partial.result.summary, f.baseline.result.summary, f.partialParameters, f.inspection)
  assert.equal(r.verified, false)
})
