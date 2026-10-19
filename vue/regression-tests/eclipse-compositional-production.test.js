import test from 'node:test'
import assert from 'node:assert/strict'
import { compositionalProductionFixture } from '../tests/fixtures/eclipseCompositionalProduction.js'
import { validCompositionalProductionContext, compositionalProductionRecords, buildCompositionalProduction, matchesCompositionalProduction, compositionalProductionResponse } from '../src/views/SoftwareIntegration/eclipseCompositionalProduction.js'
import { validCompositionalInspection, compositionalResult } from '../src/views/SoftwareIntegration/eclipseCompositional.js'
test('actual Worker context binds official PR9 source and keeps old inspections runnable', () => {
  const f = compositionalProductionFixture()
  assert.equal(validCompositionalInspection(f.inspection), true)
  assert.equal(validCompositionalProductionContext(f.inspection.compositionalProductionContext, f.inspection), true)
  assert.equal(compositionalProductionRecords(f.inspection)[0].bhpLimit, 500)
  const old = structuredClone(f.inspection); delete old.compositionalProductionContext
  assert.equal(validCompositionalInspection(old), true); assert.deepEqual(compositionalProductionRecords(old), [])
  assert.equal(compositionalResult(f.target, f.inspection).summary.series.length, 330)
})
test('gas target rejects stale source binding, original values and untyped inputs', () => {
  const f = compositionalProductionFixture()
  assert.equal(matchesCompositionalProduction(f.parameters, f.inspection), true)
  for (const [k, v] of [['sourceFile', '../SPE3_GASCOND.DATA'], ['sourceSha256', '0'.repeat(64)], ['lineNumber', 431], ['well', 'I'], ['unit', 'sm3/day'], ['originalGasRate', 6199], ['baselineRunId', '9891'], ['targetGasRate', '4500'], ['targetGasRate', 6200]])
    assert.equal(matchesCompositionalProduction({ ...f.parameters, [k]: v }, f.inspection), false, k)
  assert.equal(buildCompositionalProduction({ inspection: f.inspection, baselineRunId: 9891, well: 'P', targetGasRate: 4500 }).targetGasRate, 4500)
})
test('corrupt optional context rejects display rather than silently losing editor binding', () => {
  for (const fault of ['hash', 'size', 'pressure', 'unit', 'count', 'extra', 'duplicate']) {
    const f = compositionalProductionFixture(), c = f.inspection.compositionalProductionContext
    if (fault === 'hash') c.sourceSha256 = '0'.repeat(64)
    if (fault === 'size') c.sourceSizeBytes++
    if (fault === 'pressure') c.records[0].bhpLimit = 0
    if (fault === 'unit') c.records[0].unit = 'sm3/day'
    if (fault === 'count') c.componentCount = 8
    if (fault === 'extra') c.extra = 1
    if (fault === 'duplicate') c.records.push(structuredClone(c.records[0]))
    assert.equal(validCompositionalInspection(f.inspection), false, fault)
  }
})
test('all 125 native reduced-target points attain 4500 and retain actual pressure response', () => {
  const f = compositionalProductionFixture(), r = compositionalProductionResponse(f.native.target.summary, f.native.baseline.summary, f.parameters, f.inspection)
  assert.equal(r.verified, true); assert.equal(r.consistent, true); assert.equal(r.rows.length, 125); assert.equal(r.attained, 125); assert.equal(r.limited, 0); assert.equal(r.changed, 125)
})
test('all 168 high-target native points keep their clock and reveal exactly 15 pressure-limited points', () => {
  const f = compositionalProductionFixture(), r = compositionalProductionResponse(f.native.high.summary, f.native.baseline.summary, f.highParameters, f.inspection)
  assert.equal(r.verified, true); assert.equal(r.consistent, true); assert.equal(r.rows.length, 168); assert.equal(r.limited, 15)
  assert.ok(r.rows.some(p => p.baselineGasRate === null)); assert.equal(r.rows.at(-1).bhp, 500)
  assert.equal(r.rows.at(-1).gasRate, 63.31362533569336)
})
test('ambiguous gas columns, absent pressure and wrong units cannot claim a verified response', () => {
  for (const fault of ['duplicate', 'missing', 'unit', 'pressure']) {
    const f = compositionalProductionFixture(), s = f.native.target.summary, gas = s.series.find(x => x.keyword === 'WGPR' && x.objectName === 'P')
    if (fault === 'duplicate') s.series.push(structuredClone(gas))
    if (fault === 'missing') s.series = s.series.filter(x => !(x.keyword === 'WBHP' && x.objectName === 'P'))
    if (fault === 'unit') gas.unit = 'SM3/DAY'
    if (fault === 'pressure') s.series.find(x => x.keyword === 'WBHP' && x.objectName === 'P').points[0].value = 400
    const r = compositionalProductionResponse(s, f.native.baseline.summary, f.parameters, f.inspection)
    if (fault === 'pressure') { assert.equal(r.consistent, false); assert.equal(r.verified, false) } else assert.equal(r, null)
  }
})
