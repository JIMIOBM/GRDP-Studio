import test from 'node:test'
import assert from 'node:assert/strict'
import { binaryRestartForecastFixture } from '../tests/fixtures/eclipseBinaryRestart.js'
import { validRestartSource, validRestartForecast, buildRestartForecast, compareRestartForecasts, restartForecastSeries } from '../src/views/SoftwareIntegration/eclipseRestartForecast.js'

test('native binary history and two forecasts bind 81 reports and all 88 business curves', () => {
  const f = binaryRestartForecastFixture()
  assert.equal(validRestartSource(f.source), true)
  for (const run of [f.first, f.second]) {
    assert.equal(validRestartForecast(run.parameters), true)
    assert.equal(run.parameters.schemaVersion, 'eclipse-history-forecast-parameters/3')
    assert.equal(run.parameters.restartArtifactName, 'eclipse-output-EX5.UNRST')
    assert.equal(restartForecastSeries({ ...f, run }).length, 176)
    assert.equal(run.result.summary.series.length, 88)
    assert.ok(run.result.summary.series.every(s => s.points.length === 2 && s.points[0].timeDays === 2556 && s.points[1].timeDays === 2921))
  }
  const compared = compareRestartForecasts(f)
  assert.equal(compared.valid, true)
  assert.equal(compared.series.length, 264)
  const h = f.history.result.summary.series.find(s => s.keyword === 'FOPT').points.at(-1).value
  const a = f.first.result.summary.series.find(s => s.keyword === 'FOPT').points.at(-1).value
  const b = f.second.result.summary.series.find(s => s.keyword === 'FOPT').points.at(-1).value
  assert.deepEqual(compared.rows.filter(r => r.keyword === 'FOPT').at(-1), { keyword: 'FOPT', objectName: '全场', timeDays: 2921, date: '1977-12-31', unit: 'STB', historical: h,
    firstTotal: a, secondTotal: b, firstIncrement: a - h, secondIncrement: b - h, difference: a - b })
})
test('binary and formatted contract versions and extensions cannot be mixed', () => {
  for (const key of ['source', 'catalog', 'extension']) {
    const f = binaryRestartForecastFixture()
    if (key === 'source') f.source.schemaVersion = 'eclipse-restart-source/1'
    if (key === 'catalog') f.source.catalog.schemaVersion = 'eclipse-restart-catalog/1'
    if (key === 'extension') f.source.catalog.artifactName = 'eclipse-output-EX5.FUNRST'
    assert.equal(validRestartSource(f.source), false, key)
  }
  for (const key of ['schemaVersion', 'restartArtifactName']) {
    const p = binaryRestartForecastFixture().first.parameters
    p[key] = key === 'schemaVersion' ? 'eclipse-history-forecast-parameters/2' : 'eclipse-output-EX5.FUNRST'
    assert.equal(validRestartForecast(p), false)
  }
})
test('binary forecasts reject stale package bytes, report, date, source hash and shifted time', () => {
  for (const fault of ['package', 'report', 'source']) {
    const f = binaryRestartForecastFixture()
    if (fault === 'package') f.inspection.packageFiles.find(p => p.relativePath === 'FOREA.DATA').sha256 = 'b'.repeat(64)
    if (fault === 'source') f.history.artifacts[0].sha256 = 'b'.repeat(64)
    assert.equal(buildRestartForecast({ ...f, forecastDataFile: 'FOREA.DATA', restartReport: fault === 'report' ? 79 : 80 }), null)
  }
  for (const fault of ['date', 'physics', 'shift']) {
    const f = binaryRestartForecastFixture()
    if (fault === 'date') f.first.parameters.restartDate = '1975-12-30'
    if (fault === 'physics') f.first.parameters.physicsSha256 = 'b'.repeat(64)
    if (fault === 'shift') for (const s of f.first.result.summary.series) for (const p of s.points) p.timeDays -= 2190
    assert.equal(compareRestartForecasts(f).valid, false)
  }
})
