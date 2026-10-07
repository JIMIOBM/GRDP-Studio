import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
import * as THREE from 'three'
import { zcornCornerIndices } from '../src/views/SoftwareIntegration/eclipseCornerGeometry.js'

test('2x2x2 corner order crosses rows and planes, not eight consecutive cell values', () => {
  assert.deepEqual(zcornCornerIndices(2, 2, 0, 0, 0), [0, 1, 4, 5, 16, 17, 20, 21])
  assert.deepEqual(zcornCornerIndices(2, 2, 1, 0, 0), [2, 3, 6, 7, 18, 19, 22, 23])
  assert.deepEqual(zcornCornerIndices(2, 2, 0, 1, 0), [8, 9, 12, 13, 24, 25, 28, 29])
  assert.deepEqual(zcornCornerIndices(2, 2, 1, 1, 1), [42, 43, 46, 47, 58, 59, 62, 63])
})

test('official BRILLIG dimensions cover every ZCORN entry exactly once', () => {
  const indices = []
  for (let k = 0; k < 8; k++) for (let j = 0; j < 15; j++) for (let i = 0; i < 20; i++) {
    indices.push(...zcornCornerIndices(20, 15, i, j, k))
  }
  assert.equal(indices.length, 19200)
  assert.equal(new Set(indices).size, 19200)
  assert.equal(Math.min(...indices), 0)
  assert.equal(Math.max(...indices), 19199)
})

test('single-cell input preserves the original eight-corner convention', () => {
  assert.deepEqual(zcornCornerIndices(1, 1, 0, 0, 0), [0, 1, 2, 3, 4, 5, 6, 7])
})

test('production geometry respects multi-row/layer corners and depth-interpolated tilted pillars', () => {
  const source = readFileSync(new URL('../src/views/SoftwareIntegration/EclipseGrid3D.vue', import.meta.url), 'utf8')
  const pillar = source.slice(source.indexOf('const normalizePoint ='), source.indexOf('const valueForCell ='))
  const builder = source.slice(source.indexOf('const buildCells ='), source.indexOf('const addWellMarkers ='))
  const build = new Function('THREE', 'zcornCornerIndices', 'props', 'coordinateValues', 'zcornValues', 'actnumValues',
    `const totalCells = {value: 8}; const MAX_CELL_COUNT = 2000000; ${pillar}\n${builder}\nreturn buildCells()`)
  const coord = []
  for (let j = 0; j < 3; j++) for (let i = 0; i < 3; i++) coord.push(i * 10, j * 10, 0, i * 10 + 20, j * 10, 200)
  const depths = []
  for (let z = 0; z < 4; z++) for (let y = 0; y < 4; y++) for (let x = 0; x < 4; x++) {
    depths.push(100 + Math.floor(z / 2) * 20 + (z % 2) * 10 + x + y)
  }
  const geometry = build(THREE, zcornCornerIndices, {grid: {nx: 2, ny: 2, nz: 2}}, coord, depths, Array(8).fill(1))
  assert.equal(geometry.cells.length, 8)
  const last = geometry.cells[7]
  assert.deepEqual([last.i, last.j, last.k], [2, 2, 2])
  const expected = [124, 125, 125, 126, 134, 135, 135, 136]
  last.points.forEach((point, index) => {
    assert.equal(point.y + geometry.center.z, expected[index])
    const rawX = (index % 2 ? 20 : 10) + expected[index] * 0.1
    assert(Math.abs(point.x + geometry.center.x - rawX) < 1e-10)
  })
  const corners = geometry.cells.flatMap(cell => cell.points)
  const bounds = new THREE.Box3().setFromPoints(corners)
  assert(Math.abs((bounds.min.x + bounds.max.x) / 2) < 1e-10)
  assert(Math.abs((bounds.min.y + bounds.max.y) / 2) < 1e-10)
})
