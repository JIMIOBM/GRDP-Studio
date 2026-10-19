import test from 'node:test'
import assert from 'node:assert/strict'
import { compositionalInjectionPressureFixture } from '../tests/fixtures/eclipseCompositionalInjectionPressure.js'
import { compositionalResult } from '../src/views/SoftwareIntegration/eclipseCompositional.js'
import { buildCompositionalInjectionPressure, matchesCompositionalInjectionPressure, compositionalInjectionPressureResponse } from '../src/views/SoftwareIntegration/eclipseCompositionalInjectionPressure.js'
const response = f => compositionalInjectionPressureResponse(f.pressure.result.summary, f.baseline.result.summary, f.parameters, f.inspection)

test('injector BHP upper limit binds I and row457 independently of producer lower pressure, sales and reinjection controls', () => {
  const f = compositionalInjectionPressureFixture()
  assert.equal(matchesCompositionalInjectionPressure(f.parameters, f.inspection), true)
  assert.equal(f.parameters.lineNumber, 457); assert.equal(f.parameters.originalBhpUpperLimit, 4000)
  assert.equal(buildCompositionalInjectionPressure({ inspection: f.inspection, baselineRunId: 1, well: 'I', targetBhpUpperLimit: 3550 }).unit, 'psia')
  for (const [key, value] of [['lineNumber', 433], ['lineNumber', 439], ['lineNumber', 450], ['well', 'P'], ['sourceSha256', '0'.repeat(64)], ['sourceFile', '../SPE3_GASCOND.DATA'],
    ['unit', 'barsa'], ['originalBhpUpperLimit', 3999], ['targetBhpUpperLimit', 4000], ['targetBhpUpperLimit', 0], ['targetBhpUpperLimit', -1], ['targetBhpUpperLimit', 1e8 + 1],
    ['targetBhpUpperLimit', '3550'], ['baselineRunId', '1'], ['targetFraction', .5], ['schemaVersion', 'eclipse-compositional-pressure-parameters/1']]) {
    assert.equal(matchesCompositionalInjectionPressure({ ...f.parameters, [key]: value }, f.inspection), false, key)
  }
})
test('actual 3550 cap limits four active points and retains 81 attained points, original shut and native cumulative', () => {
  const f = compositionalInjectionPressureFixture(), r = response(f)
  assert.equal(r.verified, true); assert.equal(r.injectionBhpUpperLimit, 3550); assert.equal(r.limited, 4)
  assert.equal(r.attained, 81); assert.equal(r.closed, 40); assert.equal(r.final.cumulativeInjection, 17144826)
  assert.equal(r.baselineCumulativeInjection, 17155000); assert.equal(r.final.actualInjection, 0)
  assert.equal(compositionalResult(f.pressure, f.inspection).summary.series.length, 330)
})
test('nonbinding 3900 cap leaves audited rate/pressure curves unchanged while preserving eight real raw-vector differences', () => {
  const f = compositionalInjectionPressureFixture('inactive'), r = response(f)
  assert.equal(r.verified, true); assert.equal(r.limited, 0); assert.equal(r.changed, 0); assert.equal(r.final.cumulativeInjection, 17155000)
  assert.equal(r.rawVectorDifferences.length, 8)
  assert.equal(r.rawVectorDifferences.filter(v => v.keyword === 'FGOR').length, 2)
  assert.equal(new Set(r.rawVectorDifferences.map(v => v.vectorIndex)).size, 8)
  assert.equal(compositionalResult(f.pressure, f.inspection).summary.series.length, 330)
})
test('exceeding the new injector limit, incorrect gas units, wrong source or nonzero shut injection cannot verify', () => {
  for (const fault of ['pressure', 'units', 'source', 'shut']) {
    const f = compositionalInjectionPressureFixture(), summary = f.pressure.result.summary
    if (fault === 'pressure') summary.series.find(v => v.keyword === 'WBHP' && v.objectName === 'I').points[0].value = 3560
    if (fault === 'units') summary.series.find(v => v.keyword === 'FGIR').unit = 'SM3/DAY'
    if (fault === 'source') f.inspection.compositionalReinjectionContext.sourceSha256 = '0'.repeat(64)
    if (fault === 'shut') {
      summary.series.find(v => v.keyword === 'FGIR').points.at(-1).value = 10
      summary.series.find(v => v.keyword === 'WGIR').points.at(-1).value = 10
    }
    assert.notEqual(response(f)?.verified, true, fault)
  }
})
test('missing final original period cannot become complete injection-pressure verification', () => {
  const f = compositionalInjectionPressureFixture()
  f.pressure.result.summary.series.forEach(v => v.points.pop())
  const r = response(f); assert.equal(r.completePeriod, false); assert.equal(r.verified, false)
})
