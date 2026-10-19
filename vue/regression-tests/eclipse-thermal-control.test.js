import test from 'node:test'
import assert from 'node:assert/strict'
import { thermalControlFixture } from '../tests/fixtures/eclipseThermalControl.js'
import { matchesThermalInjection, thermalInjectionResponse, buildThermalInjection } from '../src/views/SoftwareIntegration/eclipseThermalInjection.js'
import { thermalResult } from '../src/views/SoftwareIntegration/eclipseThermal.js'

test('control edits use version two and WCONINJE row, preserving closed version one', () => {
  const f = thermalControlFixture()
  for (const p of [f.rateParameters, f.pressureParameters]) {
    assert.equal(p.schemaVersion, 'eclipse-thermal-injection-parameters/2')
    assert.equal(p.lineNumber, 429)
    assert.equal(matchesThermalInjection(p, f.inspection), true)
    for (const [k, v] of [['schemaVersion', 'eclipse-thermal-injection-parameters/1'], ['lineNumber', 433], ['sourceSha256', '0'.repeat(64)], ['unit', 'degF'], ['targetValue', 0], ['targetValue', -1], ['targetValue', '25'], ['originalValue', 1], ['targetValue', p.originalValue]])
      assert.equal(matchesThermalInjection({ ...p, [k]: v }, f.inspection), false, k)
  }
  assert.equal(buildThermalInjection({ inspection: f.inspection, baselineRunId: f.baseline.id, well: 'I', field: 'STEAM_QUALITY', targetValue: .5 }).schemaVersion, 'eclipse-thermal-injection-parameters/1')
})

for (const kind of ['official', 'output']) {
  test(`${kind} native rate response keeps actual WWIR and WWIT without target integration`, () => {
    const f = thermalControlFixture(kind), r = thermalInjectionResponse(f.native.rate.summary, f.native.baseline.summary, f.rateParameters, f.inspection)
    assert.equal(r.verified, true); assert.equal(r.bhpUpperLimit, 1000); assert.equal(r.surfaceInjectionTarget, 25)
    assert.equal(r.metrics.find(m => m.keyword === 'WWIR').finalValue, 25)
    assert.equal(r.metrics.find(m => m.keyword === 'WWIT').finalValue, 91250)
    assert.equal(r.metrics.find(m => m.keyword === 'WBHP').finalValue, 243.79246520996094)
    assert.equal(thermalResult(f.rate, f.inspection).summary.series.length, kind === 'official' ? 338 : 343)
  })
  test(`${kind} native pressure limit changes effective cap and actual injection, not target`, () => {
    const f = thermalControlFixture(kind), r = thermalInjectionResponse(f.native.pressure.summary, f.native.baseline.summary, f.pressureParameters, f.inspection)
    assert.equal(r.verified, true); assert.equal(r.pressureLimited, true); assert.equal(r.bhpUpperLimit, 700); assert.equal(r.surfaceInjectionTarget, 37.5)
    assert.equal(r.metrics.find(m => m.keyword === 'WWIR').finalValue, 27.918764114379883)
    assert.equal(r.metrics.find(m => m.keyword === 'WWIT').finalValue, 135293.03125)
    assert.notEqual(r.metrics.find(m => m.keyword === 'WWIT').finalValue, 37.5 * 3650)
    assert.equal(r.metrics.find(m => m.keyword === 'WBHP').finalValue, 700)
    assert.ok(r.rows.some(v => v.baselineValue === null))
  })
}

test('missing or duplicate actual injection and effective pressure violations cannot claim verification', () => {
  for (const fault of ['missing', 'duplicate', 'unit', 'pressure', 'rate']) {
    const f = thermalControlFixture(), s = f.native.pressure.summary, rate = s.series.find(v => v.keyword === 'WWIR' && v.objectName === 'I')
    if (fault === 'missing') s.series = s.series.filter(v => v.keyword !== 'WWIT')
    if (fault === 'duplicate') s.series.push(structuredClone(rate))
    if (fault === 'unit') rate.unit = 'SM3/DAY'
    if (fault === 'pressure') s.series.find(v => v.keyword === 'WBHP' && v.objectName === 'I').points[0].value = 701
    if (fault === 'rate') rate.points[0].value = 38
    const r = thermalInjectionResponse(s, f.native.baseline.summary, f.pressureParameters, f.inspection)
    if (['pressure', 'rate'].includes(fault)) { assert.equal(r.consistent, false); assert.equal(r.verified, false) } else assert.equal(r, null)
  }
})
