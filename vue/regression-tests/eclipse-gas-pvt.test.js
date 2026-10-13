import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
import { buildGasPvt, validGasPvt, gasPvtRecord, gasPvtResponse, gasVolumeUnit, gasPressureUnit } from '../src/views/SoftwareIntegration/eclipseGasPvt.js'
const root = new URL('../tests/fixtures/eclipseGasPvt/', import.meta.url)
const load = name => JSON.parse(readFileSync(new URL(name + '.json', root), 'utf8'))
const inspection = load('inspection'), native = load('native-acceptance')
const run = { id: 1, runType: 'eclipse', status: 'SUCCEEDED', resultContract: 'VALID_FULL', parameters: null }
test('dry gas binds zero-pressure table, complete source and FIELD Bg rb/Mscf units', () => {
  const p = buildGasPvt({ run, inspection, multiplier: 2 })
  assert.ok(validGasPvt(p)); assert.equal(Object.keys(p).length, 8)
  assert.equal(p.keywordLineNumber, 82); assert.equal(p.rows.length, 2)
  assert.equal(p.sourceSha256, native.sourceSha256)
  assert.deepEqual(p.rows, inspection.gasPvtContext.rows)
  assert.notEqual(p.rows, inspection.gasPvtContext.rows)
  assert.equal(gasVolumeUnit('FIELD'), 'rb/Mscf'); assert.equal(gasPressureUnit('FIELD'), 'psia')
})
test('invalid multiplier, default table values, units and overridden baseline cannot submit', () => {
  const p = buildGasPvt({ run, inspection, multiplier: 2 })
  for (const change of [{ multiplier: '2' }, { multiplier: 1 }, { multiplier: 0 }, { unit: 'Pa.s' }, { extra: 1 }]) assert.equal(validGasPvt({ ...p, ...change }), false)
  assert.throws(() => buildGasPvt({ run: { ...run, parameters: p }, inspection, multiplier: 2 }))
  const bad = structuredClone(inspection); bad.gasPvtContext.rows[1].values[1] = '1.3'
  assert.equal(gasPvtRecord(bad), null)
  bad.gasPvtContext.rows[1].values[1] = '1*'; assert.equal(gasPvtRecord(bad), null)
})
test('actual adaptive reports align by timestamp and preserve missing values without interpolation', () => {
  assert.equal(native.nativeVerified, true)
  const diff = gasPvtResponse({ series: native.target.series }, { series: native.baseline.series })
  assert.equal(diff.available, true); assert.equal(diff.rows.length, 952)
  assert.equal(diff.unmatchedCount, 904); assert.equal(diff.changedCount, 15)
  const pressure = diff.rows.find(r => r.keyword === 'WBHP' && r.well === 'I' && r.timeDays === 6000)
  assert.equal(pressure.baseline, 4138.58984375); assert.equal(pressure.current, 4145.6728515625)
  assert.equal(diff.rows.find(r => r.keyword === 'WGIT' && r.timeDays === 6000).current, 600000)
  for (const row of diff.rows.filter(r => r.current == null || r.baseline == null)) assert.equal(row.difference, null)
  assert.deepEqual(native.target.arrays, native.baseline.arrays)
})
test('mismatched or empty physical units reject gas response instead of fabricating a difference', () => {
  const current = { series: structuredClone(native.target.series) }, baseline = { series: native.baseline.series }
  const gas = current.series.find(s => s.keyword === 'WGIT')
  gas.unit = 'SM3'; assert.equal(gasPvtResponse(current, baseline).available, false)
  gas.unit = ''; assert.equal(gasPvtResponse(current, baseline).available, false)
})
