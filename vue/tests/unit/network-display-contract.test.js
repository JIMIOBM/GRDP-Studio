import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

// Exercise the actual page boundary, not a duplicate implementation.
const source = readFileSync(new URL('../../src/views/SoftwareIntegration/PipesimModelRunPage.vue', import.meta.url), 'utf8')
const boundary = source.slice(source.indexOf('const isNetworkVariable'), source.indexOf('const validNetworkResult ='))
const read = new Function('entry', 'isFiniteNumber', `${boundary}; return { profile: copyNetworkProfile(entry), table: copyNetworkTableVariable(entry) }`)
const check = entry => read(entry, value => typeof value === 'number' && Number.isFinite(value))
const profile = () => ({ branch: 'B_C', pointCount: 2, variables: [
  { variable: 'TotalDistance', unit: 'ft', values: [0, 100] },
  { variable: 'Pressure', unit: 'psia', values: [1200, 1190] },
  { variable: 'BranchEquipment', unit: '', values: ['Line5', null] },
  { variable: 'ZFactorGasInSitu', unit: '', values: [0.77, 0.78] }
] })

test('official Network profile preserves empty units and textual equipment identifiers', () => {
  const value = profile()
  assert.deepEqual(check(value).profile, value)
  const table = { variable: 'CaseNumber', unit: '', values: [{ name: 'B_C', value: 1 }] }
  assert.deepEqual(check(table).table, table)
})

test('Network boundary still rejects paths, textual numeric values and misaligned equipment', () => {
  for (const invalid of ['C:\\private\\model.pips', 'file:///private/model', 'net.pipe://localhost/pipe/private']) {
    const value = profile()
    value.variables[2].values[0] = invalid
    assert.equal(check(value).profile, null)
  }
  const numeric = profile()
  numeric.variables[1].values[0] = '1200'
  assert.equal(check(numeric).profile, null)
  const mismatch = profile()
  mismatch.variables[2].values.pop()
  assert.equal(check(mismatch).profile, null)
})
