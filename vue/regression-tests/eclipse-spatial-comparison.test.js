import test from 'node:test'
import assert from 'node:assert/strict'
import { assembleSpatialCells, validateSpatialPair, spatialChoices, spatialSnapshot, loadSpatialComparison, differenceColor, spatialCsv } from '../src/views/SoftwareIntegration/eclipseSpatialComparison.js'
import { scenarioMetricPairs, scenarioMetricCards } from '../src/views/SoftwareIntegration/eclipseScenarioDisplay.js'

function fixture() {
  const payloads = new Map(), grid = { fileName: 'CASE.EGRID', nx: 2, ny: 2, nz: 2, activeCells: 6 }
  const mask = [1, 0, 1, 1, 0, 1, 1, 1], before = [10, 20, 30, 40, 50, 60], after = [9, 20, 32, 40, 54, 58]
  function build(id, values) {
    const artifacts = [{ id: id * 10, name: 'eclipse-output-CASE.EGRID', contentType: 'application/octet-stream', sizeBytes: 100000, sha256: 'a'.repeat(64) }, { id: id * 10 + 1, name: 'eclipse-output-CASE.UNRST', contentType: 'application/octet-stream', sizeBytes: 100000, sha256: 'b'.repeat(64) }]
    let offset = 64
    function field(file, keyword, dataType, numbers, step) {
      const elementSize = dataType === 'DOUB' ? 8 : 4, bytes = new ArrayBuffer(numbers.length * elementSize), view = new DataView(bytes)
      numbers.forEach((value, index) => dataType === 'DOUB' ? view.setFloat64(index * 8, value) : dataType === 'REAL' ? view.setFloat32(index * 4, value) : view.setInt32(index * 4, value))
      const result = { keyword, dataType, count: numbers.length, elementSize, timeStep: step, segments: [{ offset, length: bytes.byteLength }] }
      payloads.set(`${id}:${file.endsWith('EGRID') ? id * 10 : id * 10 + 1}:${offset}`, bytes); offset += bytes.byteLength + 48
      return result
    }
    const header = Array(100).fill(0); header[2] = 2; header[8] = 2; header[9] = 2; header[10] = 2; header[11] = 6; header[64] = 1; header[65] = 1; header[66] = 2024; header[67] = 5; header[94] = 500
    return { id, modelId: 7, modelVersionId: 8, runType: 'eclipse', status: 'SUCCEEDED', resultContract: 'VALID_FULL', parameters: id === 1 ? null : { baselineRunId: 1 }, artifacts,
      result: { grid: { ...grid }, fieldIndex: { files: [
        { name: 'CASE.EGRID', byteOrder: 'BIG', fields: [field('CASE.EGRID', 'ACTNUM', 'INTE', mask)] },
        { name: 'CASE.UNRST', byteOrder: 'BIG', fields: [field('CASE.UNRST', 'TEMP', 'REAL', values, 5), field('CASE.UNRST', 'DOUBHEAD', 'DOUB', [1825], 5), field('CASE.UNRST', 'INTEHEAD', 'INTE', header, 5), field('CASE.UNRST', 'SEQNUM', 'INTE', [5], 5)] }
      ] } } }
  }
  const baseline = build(1, before), run = build(2, after)
  const request = async (id, artifact, offset, length) => { const bytes = payloads.get(`${id}:${artifact}:${offset}`); assert.equal(bytes.byteLength, length); return new Blob([bytes]) }
  return { grid, mask, before, after, baseline, run, payloads, request, choice: spatialChoices(run, baseline)[0] }
}

test('compressed ACTNUM values map to global IJK; signed changes and all layers export exactly', async () => {
  const f = fixture()
  const result = await loadSpatialComparison({ ...f, unitSystem: 'FIELD', isCurrent: () => true })
  assert.equal(result.timeDays, 1825); assert.equal(result.changedCells, 4)
  assert.deepEqual(result.cells.map(c => [c.i, c.j, c.k]), [[1,1,1],[1,2,1],[2,2,1],[2,1,2],[1,2,2],[2,2,2]])
  assert.deepEqual(result.cells.map(c => c.difference), [-1,0,2,0,4,-2])
  assert.equal(result.differenceStats.min, -2); assert.equal(result.differenceStats.max, 4)
  const csv = spatialCsv(result, 2, 1, '°F')
  assert.equal(csv.split('\r\n').length, 7)
  assert.ok(csv.includes('"1","2","TEMP","°F","5","1825","2","2","2","60","58","-2"'))
  const full = [10,999,20,30,999,40,50,60]
  assert.deepEqual(assembleSpatialCells(f.grid, f.mask, full, f.after), result.cells)
})

test('wrong baseline, geometry, expiry and dimensions never become spatial differences', () => {
  for (const mutate of [f => f.run.modelVersionId++, f => f.run.result.grid.activeCells--, f => f.run.artifacts[0].sha256 = 'c'.repeat(64), f => f.run.resultExpired = true, f => f.run.parameters.baselineRunId = 99]) {
    const f = fixture(); mutate(f); assert.throws(() => validateSpatialPair(f.run, f.baseline))
  }
  const f = fixture(), fields = f.run.result.fieldIndex.files[1].fields
  fields.push(structuredClone(fields[0])); assert.deepEqual(spatialChoices(f.run, f.baseline), [])
})

test('actual clock mismatch and truncated transport are rejected; stale loads return no data', async () => {
  const f = fixture()
  const clock = f.run.result.fieldIndex.files[1].fields.find(x => x.keyword === 'DOUBHEAD')
  new DataView(f.payloads.get(`2:21:${clock.segments[0].offset}`)).setFloat64(0, 1826)
  await assert.rejects(loadSpatialComparison({ ...f, unitSystem: 'FIELD', isCurrent: () => true }), /实际时间/)
  const g = fixture()
  await assert.rejects(loadSpatialComparison({ ...g, request: async () => new Blob([new Uint8Array(1)]), unitSystem: 'FIELD', isCurrent: () => true }), /长度/)
  assert.equal(await loadSpatialComparison({ ...g, unitSystem: 'FIELD', isCurrent: () => false }), null)
  const source = spatialSnapshot(g.run); g.run.artifacts[0].id = 99
  assert.equal(source.artifacts[0].id, 20)
})

test('same time is insufficient when native unit or solver/grid headers disagree', async () => {
  for (const [index, value] of [[2,1],[8,3],[94,100]]) {
    const f = fixture(), field = f.run.result.fieldIndex.files[1].fields.find(x => x.keyword === 'INTEHEAD')
    new DataView(f.payloads.get(`2:21:${field.segments[0].offset}`)).setInt32(index * 4, value)
    await assert.rejects(loadSpatialComparison({ ...f, unitSystem: 'FIELD', isCurrent: () => true }), /模型单位不同/)
  }
  assert.equal(differenceColor(0, 5), '#f5f7fb')
  assert.notEqual(differenceColor(-1, 5), differenceColor(1, 5))
  const f = fixture(), seq = f.run.result.fieldIndex.files[1].fields.find(x => x.keyword === 'SEQNUM')
  new DataView(f.payloads.get(`2:21:${seq.segments[0].offset}`)).setInt32(0, 6)
  await assert.rejects(loadSpatialComparison({ ...f, unitSystem: 'FIELD', isCurrent: () => true }), /实际时间/)
})

test('dashboard preserves duplicate original columns and last common actual time instead of interpolating', () => {
  const f = fixture()
  const series = (vectorIndex, points) => ({ keyword: 'FOPT', objectName: '', unit: 'STB', vectorIndex, points: points.map(([timeDays,value]) => ({ timeDays,value })) })
  f.baseline.result.summary = { series: [series(0, [[0,0],[2,20],[5,50]]), series(1, [[0,0],[2,30],[5,60]])] }
  f.run.result.summary = { series: [series(0, [[0,0],[2,18],[4,40]]), series(1, [[0,0],[2,29],[4,50]])] }
  const pairs = scenarioMetricPairs(f.run, f.baseline)
  assert.equal(pairs.length, 2); assert.deepEqual(pairs.map(p => p.difference), [-2,-1]); assert.equal(pairs[0].timeDays, 2)
  assert.equal(scenarioMetricCards(pairs, 'compositional').length, 1)
  f.baseline.result.summary.series.push(f.baseline.result.summary.series[0]); assert.equal(scenarioMetricPairs(f.run, f.baseline).length, 1)
  f.run.result.summary.startDate = '2024-01-02'; f.baseline.result.summary.startDate = '2024-01-01'
  assert.deepEqual(scenarioMetricPairs(f.run, f.baseline), [])
})
