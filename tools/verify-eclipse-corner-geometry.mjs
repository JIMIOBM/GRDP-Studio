import assert from 'node:assert/strict'
import { zcornCornerIndices } from '../vue/src/views/SoftwareIntegration/eclipseCornerGeometry.js'

// Read-only native-output acceptance. No login bypass, model upload, run creation or DB writes.
const args = process.argv.slice(2)
const base = args[0]
const runId = Number(args[1])
assert(base && Number.isSafeInteger(runId) && runId > 0, 'Usage: node tools/verify-eclipse-corner-geometry.mjs BASE_URL RUN_ID')
const url = new URL(base)
assert(['http:', 'https:'].includes(url.protocol) && !url.username && !url.password && !url.search && !url.hash)
const root = base.replace(/\/$/, '')
const response = await fetch(`${root}/software-integration/runs/${runId}`, { signal: AbortSignal.timeout(30000) })
assert.equal(response.status, 200)
const envelope = await response.json()
assert.equal(envelope.code, 200)
const run = envelope.data
assert.equal(run.status, 'SUCCEEDED'); assert.equal(run.resultContract, 'VALID_FULL')
const { nx, ny, nz, activeCells } = run.result.grid
assert([nx, ny, nz].every(n => Number.isSafeInteger(n) && n > 0))
const total = nx * ny * nz
assert(total <= 2000000)
const file = run.result.fieldIndex.files.find(file => file.name === run.result.grid.fileName)
assert(file && ['BIG', 'LITTLE'].includes(file.byteOrder))
const artifact = run.artifacts.find(a => a.name === `eclipse-output-${file.name}`)
assert(artifact && artifact.downloadable !== false)
let duplicateRangeHeaders = 0
async function values(keyword, count, type) {
  const field = file.fields.find(f => f.keyword === keyword)
  assert(field && field.count === count && field.dataType === type && field.elementSize === 4)
  const result = []
  for (const segment of field.segments) {
    assert(segment.length > 0 && segment.length % 4 === 0 && segment.offset >= 0)
    for (let start = 0; start < segment.length; start += 4 * 1024 * 1024) {
      const length = Math.min(segment.length - start, 4 * 1024 * 1024)
      const offset = segment.offset + start
      const res = await fetch(`${root}/software-integration/runs/${runId}/artifacts/${artifact.id}/range?offset=${offset}&length=${length}`, { signal: AbortSignal.timeout(30000) })
      assert.equal(res.status, 206)
      const ranges = (res.headers.get('content-range') || '').split(',').map(value => value.trim())
      assert(ranges.length && ranges.every(value => value === `bytes ${offset}-${offset + length - 1}/${artifact.sizeBytes}`))
      if (ranges.length > 1) duplicateRangeHeaders++
      const bytes = await res.arrayBuffer(); assert.equal(bytes.byteLength, length)
      const view = new DataView(bytes)
      for (let n = 0; n < length; n += 4) {
        const value = type === 'REAL' ? view.getFloat32(n, file.byteOrder === 'LITTLE') : view.getInt32(n, file.byteOrder === 'LITTLE')
        assert(Number.isFinite(value)); result.push(value)
      }
    }
  }
  assert.equal(result.length, count)
  return result
}
const coord = await values('COORD', 6 * (nx + 1) * (ny + 1), 'REAL')
const zcorn = await values('ZCORN', total * 8, 'REAL')
const actnum = await values('ACTNUM', total, 'INTE')
assert.equal(actnum.filter(n => n > 0).length, activeCells)
const bounds = [[Infinity, -Infinity], [Infinity, -Infinity], [Infinity, -Infinity]]
let changedCorners = 0
let cells = 0
for (let k = 0; k < nz; k++) for (let j = 0; j < ny; j++) for (let i = 0; i < nx; i++) {
  const cell = (k * ny + j) * nx + i
  if (!actnum[cell]) continue
  cells++
  const pillars = [j * (nx + 1) + i, j * (nx + 1) + i + 1, (j + 1) * (nx + 1) + i, (j + 1) * (nx + 1) + i + 1]
  for (const [corner, index] of zcornCornerIndices(nx, ny, i, j, k).entries()) {
    const z = zcorn[index]
    if (z !== zcorn[cell * 8 + corner]) changedCorners++
    const p = pillars[corner % 4] * 6
    const dz = coord[p + 5] - coord[p + 2]
    assert(Math.abs(dz) > 1e-9 || (coord[p] === coord[p + 3] && coord[p + 1] === coord[p + 4]), 'Non-vertical zero-height pillar is unsupported')
    const ratio = Math.abs(dz) > 1e-9 ? (z - coord[p + 2]) / dz : 0
    const point = [coord[p] + (coord[p + 3] - coord[p]) * ratio, coord[p + 1] + (coord[p + 4] - coord[p + 1]) * ratio, z]
    point.forEach((v, axis) => { assert(Number.isFinite(v)); bounds[axis][0] = Math.min(bounds[axis][0], v); bounds[axis][1] = Math.max(bounds[axis][1], v) })
  }
}
console.log(JSON.stringify({ runId, dimensions: [nx, ny, nz], activeCells: cells, coordinateValues: coord.length,
  depthValues: zcorn.length, changedCornersAgainstOldCellMajorMapping: changedCorners, bounds,
  protocolWarning: duplicateRangeHeaders ? `duplicate identical Content-Range in ${duplicateRangeHeaders} responses` : null,
  evidence: 'real API binary ranges + official corner order; not authenticated webpage/WebGL acceptance' }))
