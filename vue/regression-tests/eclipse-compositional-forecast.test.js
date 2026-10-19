import test from 'node:test'
import assert from 'node:assert/strict'
import { compositionalForecastFixture } from '../tests/fixtures/eclipseCompositionalForecast.js'
import { validCompositionalInspection, compositionalResult } from '../src/views/SoftwareIntegration/eclipseCompositional.js'
import { validCompositionalSaveCatalog, validCompositionalForecast, compositionalForecastMatches, buildCompositionalForecast,
  validCompositionalPortableContext, compositionalForecastEvidence } from '../src/views/SoftwareIntegration/eclipseCompositionalForecast.js'

test('native SAVE report binds actual source components grid date and complete native state', () => {
  const f = compositionalForecastFixture()
  assert.ok(validCompositionalSaveCatalog(f.catalog)); assert.equal(f.catalog.reportNumber, 10); assert.equal(f.catalog.timeDays, 3285)
  assert.equal(f.catalog.date, '1998-12-30'); assert.equal(f.catalog.files.length, 3)
  assert.equal(f.inherited.parameters.targetGasRate, null); assert.equal(f.lower.parameters.targetGasRate, 4500)
  assert.ok(compositionalForecastMatches(f.lower.parameters, f.catalog, f.inspection))
})
test('typed forecast controls reject incorrect sources reports rates and unknown parameters', () => {
  const f = compositionalForecastFixture()
  for (const [key, value] of [['historyRunId', '1'], ['sourceFile', '../SPE3.DATA'], ['sourceSha256', 'bad'], ['saveCatalogSha256', 'bad'],
    ['saveReport', 0], ['targetGasRate', '4500'], ['targetGasRate', 0], ['targetGasRate', -1], ['targetGasRate', 1e8 + 1], ['unknown', true]])
    assert.equal(validCompositionalForecast({ ...f.lower.parameters, [key]: value }), false, key)
  for (const target of [6200, 0, -1, '4500']) assert.equal(buildCompositionalForecast({ inspection: f.inspection, historyRun: f.history, catalog: f.catalog, artifact: f.artifact, targetGasRate: target }), null)
  assert.equal(compositionalForecastMatches({ ...f.lower.parameters, saveReport: 11 }, f.catalog, f.inspection), false)
})
test('portable inspection18 preserves six bound files and cannot reopen original-source editors', () => {
  const f = compositionalForecastFixture()
  assert.equal(validCompositionalInspection(f.portableInspection), true)
  assert.ok(validCompositionalPortableContext(f.portableInspection.compositionalRestartContext, f.portableInspection))
  assert.ok(compositionalResult(f.portable, f.portableInspection)); assert.equal(f.portableInspection.packageFiles.length, 6)
  for (const version of ['14', '16', '17']) assert.equal(validCompositionalInspection({ ...f.portableInspection, schemaVersion: 'eclipse-data-inspection/' + version }), false)
  const invalid = structuredClone(f.portableInspection); invalid.packageFiles[2].sha256 = '0'.repeat(64)
  assert.equal(validCompositionalInspection(invalid), false)
  invalid.packageFiles[2] = f.portableInspection.packageFiles[2]; invalid.compositionalProductionContext = f.inspection.compositionalProductionContext
  assert.equal(validCompositionalInspection(invalid), false)
})
test('both qualified futures keep all330 columns64 actual times and historical cumulative oil', () => {
  const f = compositionalForecastFixture()
  for (const [run, oil] of [[f.inherited, 2593395], [f.lower, 2497045]]) {
    assert.ok(compositionalResult(run, f.inspection))
    const e = compositionalForecastEvidence(run.result, f.catalog, f.inspection.wellNames)
    assert.ok(e.complete); assert.equal(e.rawVectorCount, 330); assert.equal(e.actualTimes, 64); assert.equal(e.startDays, 3286); assert.equal(e.endDays, 5475)
    const oils = e.metrics.filter(v => v.keyword === 'FOPT'); assert.equal(oils.length, 2)
    assert.ok(oils.every(v => v.first.value > 2e6 && v.last.value === oil)); assert.notEqual(oils[0].vectorIndex, oils[1].vectorIndex)
  }
})
test('rebased missing-final or duplicate native vectors cannot imply complete forecast coverage', () => {
  const f = compositionalForecastFixture()
  const rebased = structuredClone(f.lower.result)
  for (const v of rebased.summary.series) for (const p of v.points) p.timeDays -= 3285
  assert.equal(compositionalForecastEvidence(rebased, f.catalog), null)
  const missing = structuredClone(f.lower.result); for (const v of missing.summary.series) v.points.pop()
  assert.equal(compositionalForecastEvidence(missing, f.catalog).complete, false)
  const duplicate = structuredClone(f.lower.result); duplicate.summary.series[1].vectorIndex = duplicate.summary.series[0].vectorIndex
  assert.equal(compositionalForecastEvidence(duplicate, f.catalog), null)
})
