import test from 'node:test'
import assert from 'node:assert/strict'
import { compositionalGasSalesFixture } from '../tests/fixtures/eclipseCompositionalGasSales.js'
import { compositionalResult } from '../src/views/SoftwareIntegration/eclipseCompositional.js'
import { buildCompositionalGasSales, matchesCompositionalGasSales, compositionalGasSalesResponse } from '../src/views/SoftwareIntegration/eclipseCompositionalGasSales.js'
const response = f => compositionalGasSalesResponse(f.sales.result.summary, f.baseline.result.summary, f.parameters, f.inspection)

test('constant sales binds the existing source row rather than the reinjection fraction row and accepts numeric zero', () => {
  const f = compositionalGasSalesFixture()
  assert.equal(matchesCompositionalGasSales(f.parameters, f.inspection), true)
  assert.equal(f.parameters.lineNumber, 439); assert.equal(f.parameters.originalSalesTarget, 1500)
  assert.equal(buildCompositionalGasSales({ inspection: f.inspection, baselineRunId: 1, group: 'FIELD', targetSalesTarget: 0 }).targetSalesTarget, 0)
  for (const [key, value] of [['lineNumber', 450], ['sourceSha256', '0'.repeat(64)], ['sourceFile', '../SPE3_GASCOND.DATA'], ['group', 'OTHER'], ['phase', 'WATER'],
    ['unit', 'fraction'], ['originalSalesTarget', 1499], ['targetSalesTarget', 1500], ['targetSalesTarget', -1], ['targetSalesTarget', 1e8 + 1],
    ['targetSalesTarget', '3000'], ['baselineRunId', '1'], ['targetFraction', .5], ['schemaVersion', 'eclipse-compositional-reinjection-parameters/1']]) {
    assert.equal(matchesCompositionalGasSales({ ...f.parameters, [key]: value }, f.inspection), false, key)
  }
})
test('actual 3000 sales requirement yields 3200 reinjection and native cumulative while retaining all raw vectors', () => {
  const f = compositionalGasSalesFixture(), r = response(f)
  assert.equal(r.verified, true); assert.equal(r.salesTarget, 3000); assert.equal(r.rows[0].actualInjection, 3200)
  assert.equal(r.rows[0].plannedTarget, 3200); assert.equal(r.final.cumulativeInjection, 11680000)
  assert.equal(r.baselineCumulativeInjection, 17155000); assert.equal(r.final.actualInjection, 0)
  assert.equal(compositionalResult(f.sales, f.inspection).summary.series.length, 330)
})
test('unavailable-gas case preserves tiny native rate and separate cumulative totals without rounding them to zero', () => {
  const f = compositionalGasSalesFixture('unavailable'), r = response(f)
  assert.equal(r.verified, true); assert.ok(r.rows.some(v => !v.planShut && v.state === '没有可用气'))
  assert.equal(r.final.cumulativeInjection, 0.0017000738298520446)
  assert.ok(r.rows.some(v => v.actualInjection > 0 && v.actualInjection < 0.000001))
  assert.equal(f.sales.result.eclEnd.warnings, 3); assert.equal(compositionalResult(f.sales, f.inspection).summary.series.length, 330)
})
test('false cumulative, wrong units, post-shut injection and false source cannot become verified sales results', () => {
  for (const fault of ['cumulative', 'units', 'shut', 'source']) {
    const f = compositionalGasSalesFixture(), summary = f.sales.result.summary
    if (fault === 'cumulative') summary.series.find(v => v.keyword === 'WGIT').points.at(-1).value += 1
    if (fault === 'units') summary.series.find(v => v.keyword === 'FGIR').unit = 'SM3/DAY'
    if (fault === 'shut') {
      summary.series.find(v => v.keyword === 'FGIR').points.at(-1).value = 10
      summary.series.find(v => v.keyword === 'WGIR').points.at(-1).value = 10
    }
    if (fault === 'source') f.inspection.compositionalReinjectionContext.sourceSha256 = '0'.repeat(64)
    assert.notEqual(response(f)?.verified, true, fault)
  }
})
test('incomplete sales period remains visible but cannot claim complete verification', () => {
  const f = compositionalGasSalesFixture()
  f.sales.result.summary.series.forEach(v => v.points.pop())
  const r = response(f); assert.equal(r.completePeriod, false); assert.equal(r.verified, false)
})
