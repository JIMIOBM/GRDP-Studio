import test from 'node:test'
import assert from 'node:assert/strict'
import { thermalInjectionFixture } from '../tests/fixtures/eclipseThermalInjection.js'
import { validThermalInspection, thermalResult } from '../src/views/SoftwareIntegration/eclipseThermal.js'
import { thermalInjectionRecords, matchesThermalInjection, buildThermalInjection, thermalInjectionResponse } from '../src/views/SoftwareIntegration/eclipseThermalInjection.js'
test('actual official and output-enriched thermal inspections preserve source metadata and old contracts', () => {
  for (const kind of ['official', 'output']) {
    const f = thermalInjectionFixture(kind)
    assert.equal(validThermalInspection(f.inspection), true)
    const r = thermalInjectionRecords(f.inspection)[0]
    assert.equal(r.originalSteamQuality, .7); assert.equal(r.originalTemperature, 450); assert.equal(r.surfaceInjectionRate, 37.5); assert.equal(r.bhpUpperLimit, 1000)
    assert.equal(thermalResult(f.baseline, f.inspection).summary.series.length, kind === 'official' ? 338 : 343)
    const old = structuredClone(f.inspection); delete old.thermalInjectionContext
    assert.equal(validThermalInspection(old), true); assert.deepEqual(thermalInjectionRecords(old), [])
  }
})
test('quality and temperature build dedicated parameters and reject stale or untyped edits', () => {
  const f = thermalInjectionFixture()
  assert.equal(matchesThermalInjection(f.qualityParameters, f.inspection), true); assert.equal(matchesThermalInjection(f.temperatureParameters, f.inspection), true)
  for (const [key, v] of [['sourceFile', '../THERM3.DATA'], ['sourceSha256', '0'.repeat(64)], ['lineNumber', 1], ['well', 'PNEAR'], ['originalValue', .6], ['unit', 'degF'], ['field', 'BHP'], ['targetValue', 1.01], ['targetValue', '0.5'], ['targetValue', .7]])
    assert.equal(matchesThermalInjection({ ...f.qualityParameters, [key]: v }, f.inspection), false, key)
  assert.equal(buildThermalInjection({ inspection: f.inspection, baselineRunId: f.baseline.id, well: 'I', field: 'INJECTION_TEMPERATURE', targetValue: 400 }).unit, 'degF')
})
test('corrupt thermal source, original properties and constraints cannot become an editable inspection', () => {
  for (const fault of ['hash', 'size', 'quality', 'unit', 'rate', 'pressure', 'line', 'extra', 'duplicate']) {
    const f = thermalInjectionFixture(), c = f.inspection.thermalInjectionContext, r = c.records[0]
    if (fault === 'hash') c.sourceSha256 = '0'.repeat(64)
    if (fault === 'size') c.sourceSizeBytes++
    if (fault === 'quality') r.originalSteamQuality = 1.01
    if (fault === 'unit') r.temperatureUnit = 'degC'
    if (fault === 'rate') r.surfaceInjectionRate = 0
    if (fault === 'pressure') r.bhpUpperLimit = 0
    if (fault === 'line') r.controlLineNumber = r.lineNumber
    if (fault === 'extra') c.extra = 1
    if (fault === 'duplicate') c.records.push(structuredClone(r))
    assert.equal(validThermalInspection(f.inspection), false, fault)
  }
})
test('official raw outputs verify quality and temperature physics without fabricating a WTEMP curve', () => {
  const f = thermalInjectionFixture()
  for (const [name, p, points, pressure] of [['quality', f.qualityParameters, 116, 355.03997802734375], ['temperature', f.temperatureParameters, 137, 686.3251953125]]) {
    const r = thermalInjectionResponse(f.native[name].summary, f.native.baseline.summary, p, f.inspection)
    assert.equal(r.verified, true); assert.equal(r.consistent, true); assert.equal(r.temperatureAvailable, false)
    assert.equal(r.metrics.find(m => m.keyword === 'WBHP').pointCount, points); assert.equal(r.metrics.find(m => m.keyword === 'WBHP').finalValue, pressure)
    assert.ok(r.rows.some(x => x.baselineValue === null)); assert.ok(r.metrics.find(m => m.keyword === 'FHLR').changed > 0)
  }
})
test('requested actual temperature differs from input temperature and all enriched raw vectors remain', () => {
  const f = thermalInjectionFixture('output')
  for (const [name, p, temperature] of [['quality', f.qualityParameters, 433.8408508300781], ['temperature', f.temperatureParameters, 501.1626892089844]]) {
    const r = thermalInjectionResponse(f.native[name].summary, f.native.baseline.summary, p, f.inspection)
    assert.equal(r.verified, true); assert.equal(r.temperatureAvailable, true)
    assert.equal(r.metrics.find(m => m.keyword === 'WTEMP').finalValue, temperature)
    assert.equal(thermalResult(f[name], f.inspection).summary.series.length, 343)
  }
})
test('missing energy, duplicate pressure, wrong units and a pressure violation cannot claim verified physics', () => {
  for (const fault of ['missing', 'duplicate', 'unit', 'pressure']) {
    const f = thermalInjectionFixture(), s = f.native.quality.summary, pressure = s.series.find(s => s.keyword === 'WBHP' && s.objectName === 'I')
    if (fault === 'missing') s.series = s.series.filter(s => s.keyword !== 'FHLR')
    if (fault === 'duplicate') s.series.push(structuredClone(pressure))
    if (fault === 'unit') pressure.unit = 'BARSA'
    if (fault === 'pressure') pressure.points[0].value = 1001
    const r = thermalInjectionResponse(s, f.native.baseline.summary, f.qualityParameters, f.inspection)
    if (fault === 'pressure') { assert.equal(r.consistent, false); assert.equal(r.verified, false) } else assert.equal(r, null)
  }
})
