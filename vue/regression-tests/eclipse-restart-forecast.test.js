import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { validRestartSource, validRestartForecast, buildRestartForecast, compareRestartForecasts, restartForecastSeries } from '../src/views/SoftwareIntegration/eclipseRestartForecast.js'

const fixture = () => {
  // Actual C# report/source metadata; small constructed cumulative curves test the maths only.
  const source = JSON.parse(readFileSync(new URL('../../backend/src/test/resources/eclipse-restart-source-catalog.json', import.meta.url), 'utf8'))
  const inspection = { packageFiles: source.forecastInputs.map(f => ({ relativePath: f.relativePath, sha256: f.sha256 })) }
  const historical = { id: 1, modelId: 1, modelVersionId: 1, runType: 'eclipse', status: 'SUCCEEDED', resultContract: 'VALID_FULL', parameters: null,
    artifacts: [{ name: source.catalog.artifactName, sha256: source.catalog.artifactSha256, sizeBytes: source.catalog.artifactSizeBytes }],
    result: { summary: { series: ['FOPT', 'WOPT'].map((keyword, index) => ({ keyword, objectName: index ? 'G1' : null, unit: 'STB',
      points: source.catalog.reports.map(r => ({ timeDays: r.timeDays, value: r.timeDays / 2190 * (index ? 10 : 100) })) })) } } }
  const first = { ...historical, id: 2, parameters: buildRestartForecast({ source, history: historical, inspection, forecastDataFile: 'FOREA.DATA', restartReport: 80 }),
    result: { summary: { series: [{ keyword: 'FOPT', objectName: null, unit: 'STB', points: [{ timeDays: 2556, value: 150 }, { timeDays: 2921, value: 250 }] },
      { keyword: 'WOPT', objectName: 'G1', unit: 'STB', points: [{ timeDays: 2556, value: 20 }, { timeDays: 2921, value: 35 }] }] } } }
  const second = { ...historical, id: 3, parameters: buildRestartForecast({ source, history: historical, inspection, forecastDataFile: 'FOREB.DATA', restartReport: 80 }),
    result: { summary: { series: [{ keyword: 'FOPT', objectName: null, unit: 'STB', points: [{ timeDays: 2556, value: 140 }, { timeDays: 2921, value: 220 }] },
      { keyword: 'WOPT', objectName: 'G1', unit: 'STB', points: [{ timeDays: 2556, value: 18 }, { timeDays: 2921, value: 30 }] }] } } }
  return { source, history: historical, first, second, inspection }
}
test('actual 81-report source binds exact cutoff date and both compatible DATA inputs', () => {
  const f = fixture()
  assert.equal(validRestartSource(f.source), true); assert.equal(validRestartForecast(f.first.parameters), true)
  assert.equal(f.first.parameters.restartDate, '1975-12-31'); assert.equal(f.first.parameters.restartTimeDays, 2190)
  assert.equal(f.second.parameters.forecastDataFile, 'FOREB.DATA')
})
test('all report metadata, ranges, clock and physical units must be consistent', () => {
  for (const key of ['reportNumber', 'timeDays', 'date', 'unitSystem', 'activeCells', 'phaseMask', 'solver', 'byteOffset', 'sizeBytes', 'sha256', 'extra']) {
    const f = fixture(), r = f.source.catalog.reports[80]
    if (key === 'date') r.date = '1975-12-30'
    else if (key === 'unitSystem') r.unitSystem = 'METRIC'
    else if (key === 'sha256') r.sha256 = 'bad'
    else if (key === 'extra') r.extra = 1
    else if (key === 'sizeBytes') r.sizeBytes = 0
    else if (key === 'timeDays') r.timeDays = 2190.5
    else if (key === 'reportNumber') r.reportNumber = 79
    else r[key] += 1
    assert.equal(validRestartSource(f.source), false, key)
  }
})
test('stale input checksum, missing source artifact and incompatible model reject', () => {
  for (const fault of ['input', 'artifact', 'report', 'unknown']) {
    const f = fixture()
    if (fault === 'input') f.inspection.packageFiles[0].sha256 = 'b'.repeat(64)
    if (fault === 'artifact') f.history.artifacts = []
    const p = buildRestartForecast({ ...f, forecastDataFile: fault === 'unknown' ? 'UNKNOWN.DATA' : 'FOREA.DATA', restartReport: fault === 'report' ? 79 : 80 })
    assert.equal(p, null, fault)
  }
})
test('actual cutoff cumulative value gives future increments and exact common-date differences', () => {
  const f = fixture(), result = compareRestartForecasts(f)
  assert.equal(result.valid, true)
  assert.deepEqual(result.rows.filter(r => r.keyword === 'FOPT').map(r => [r.date, r.historical, r.firstIncrement, r.secondIncrement, r.difference]),
    [['1976-12-31', 100, 50, 40, 10], ['1977-12-31', 100, 150, 120, 30]])
  assert.equal(result.series.length, 6)
  assert.equal(result.series.filter(s => s.sourceRunId === 2).some(s => s.points.some(p => p.timeDays === 2190)), false)
  assert.equal(restartForecastSeries({ ...f, run: f.first }).length, 4)
})
test('wrong dates, missing points and guessed time offsets cannot be plotted as valid forecasts', () => {
  for (const fault of ['rebase', 'missing', 'partialGrid', 'duplicate', 'nonfinite', 'unit', 'cumulative', 'origin', 'same']) {
    const f = fixture()
    if (fault === 'rebase') for (const s of f.first.result.summary.series) for (const p of s.points) p.timeDays -= 2190
    if (fault === 'missing') f.first.result.summary.series[0].points.pop()
    if (fault === 'partialGrid') f.first.result.summary.series[1].points[1].timeDays = 2920
    if (fault === 'duplicate') f.first.result.summary.series.push(structuredClone(f.first.result.summary.series[0]))
    if (fault === 'nonfinite') f.first.result.summary.series[0].points[0].value = NaN
    if (fault === 'unit') f.first.result.summary.series[0].unit = 'sm3'
    if (fault === 'cumulative') f.first.result.summary.series[0].points[0].value = 90
    if (fault === 'origin') f.first.parameters.physicsSha256 = 'b'.repeat(64)
    if (fault === 'same') f.second = f.first
    assert.equal(compareRestartForecasts(f).valid, false, fault)
  }
})
test('malformed parameter types, dates and unknown fields fail without coercion', () => {
  for (const fault of ['timeString', 'badDate', 'extra', 'wrongSchema']) {
    const f = fixture(), p = f.first.parameters
    if (fault === 'timeString') p.restartTimeDays = '2190'
    if (fault === 'badDate') p.restartDate = '1975-02-31'
    if (fault === 'extra') p.extra = true
    if (fault === 'wrongSchema') p.schemaVersion = 'eclipse-history-forecast-parameters/1'
    assert.equal(validRestartForecast(p), false, fault)
  }
})
test('wrong historical version or non-baseline history cannot supply a forecast', () => {
  const f = fixture(); f.history.parameters = {}; assert.equal(buildRestartForecast({ ...f, forecastDataFile: 'FOREA.DATA', restartReport: 80 }), null)
  const other = fixture(); other.first.modelVersionId = 2; assert.equal(restartForecastSeries({ ...other, run: other.first }).length, 0)
})
