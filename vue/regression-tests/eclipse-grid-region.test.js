import assert from 'node:assert/strict'
import test from 'node:test'
import { buildGridRegionParameters, gridRegionUnit, validGridRegionParameters, gridRegionResponse } from '../src/views/SoftwareIntegration/eclipseGridRegion.js'

const fixture = () => ({ run: { id: 1, status: 'SUCCEEDED', resultContract: 'VALID_FULL', parameters: null },
  grid: { nx: 3, ny: 2, nz: 1, fileName: 'CASE.EGRID' }, field: { file: 'CASE.INIT', keyword: 'PERMX', dataType: 'REAL', count: 6 },
  initArtifact: { name: 'eclipse-output-CASE.INIT', contentType: 'application/octet-stream', sha256: 'a'.repeat(64) },
  gridArtifact: { name: 'eclipse-output-CASE.EGRID', contentType: 'application/octet-stream', sha256: 'b'.repeat(64) },
  bounds: { i1: 2, i2: 3, j1: 1, j2: 2, k1: 1, k2: 1 }, values: [10, 20, 30, 40, 50, 60], actnum: [1, 1, 1, 1, 1, 1], multiplier: 0.5 })
test('region request preserves I-fastest selected values, all explicit bounds and independent checksums', () => {
  const input = fixture(), result = buildGridRegionParameters(input)
  assert.deepEqual(result.originalValues, [20, 30, 50, 60])
  assert.equal(result.schemaVersion, 'eclipse-grid-region-parameters/1')
  assert.equal(validGridRegionParameters(result), true)
  assert.equal(result.gridArtifactSha256, 'b'.repeat(64))
  assert.deepEqual(input.values, [10, 20, 30, 40, 50, 60])
  assert.equal(gridRegionUnit('PERMX'), 'mD')
  assert.equal(gridRegionUnit('PORO'), '体积分数（0–1）')
})
test('result comparison retains real values and missing clocks without interpolation; rejects mixed units', () => {
  const summary = (unit, points) => ({ series: [{ keyword: 'WBHP', objectName: 'P', unit, points }] })
  const base = summary('BARSA', [{ timeDays: 0, value: 100 }, { timeDays: 2, value: 110 }])
  const current = summary('BARSA', [{ timeDays: 0, value: 99 }, { timeDays: 1, value: 103 }])
  const result = gridRegionResponse(current, base)
  assert.equal(result.available, true); assert.equal(result.changedCount, 1); assert.equal(result.unmatchedCount, 2)
  assert.deepEqual(result.rows.map(row => row.difference), [-1, null, null])
  assert.equal(gridRegionResponse(summary('PSIA', current.series[0].points), base).available, false)
  assert.equal(gridRegionResponse({ series: [...current.series, ...current.series] }, base).available, false)
  assert.equal(validGridRegionParameters({ ...buildGridRegionParameters(fixture()), multiplier: '0.5' }), false)
})
test('region rejects inactive cells, reversed/overflow/fractional bounds and non-numeric/unchanged multiplier', () => {
  for (const mutate of [f => { f.actnum[1] = 0 }, f => { f.bounds.i1 = 4 }, f => { f.bounds.i2 = 4 },
    f => { f.bounds.j1 = 1.5 }, f => { f.multiplier = '0.5' }, f => { f.multiplier = 1 }, f => { f.values[2] = NaN },
    f => { f.gridArtifact.sha256 = 'bad' }, f => { f.run.parameters = {} }, f => { f.field.timeStep = 1 }]) {
    const f = fixture(); mutate(f); assert.throws(() => buildGridRegionParameters(f))
  }
})
test('PORO physical bounds checked on every selected cell, including last; unselected cells excluded', () => {
  const f = fixture(); f.field.keyword = 'PORO'; f.values = [100, 0.1, 0.2, 100, 0.3, 0.4]; f.multiplier = 2
  assert.deepEqual(buildGridRegionParameters(f).originalValues, [0.1, 0.2, 0.3, 0.4])
  f.values[5] = 0.6; assert.throws(() => buildGridRegionParameters(f), /孔隙度/)
  f.values[5] = 0; assert.throws(() => buildGridRegionParameters(f), /孔隙度/)
})
