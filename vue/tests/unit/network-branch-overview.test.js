import test from 'node:test'
import assert from 'node:assert/strict'
import { branchOverview, branchOverviewCsvRows } from '../../src/views/SoftwareIntegration/networkBranchOverview.js'
const profile = (values, unit = 'psia') => ({ branch: 'A', variables: [{ variable: 'Pressure', values, unit }] })
test('branch summary retains original endpoints, signed delta and null gaps', () => {
  const [row] = branchOverview([profile([20, null, 30, 25])])
  assert.equal(row.first, 20)
  assert.equal(row.last, 25)
  assert.equal(row.difference, -5)
  assert.equal(row.minimum, 20)
  assert.equal(row.maximum, 30)
  assert.equal(row.missing, 1)
  assert.deepEqual(branchOverviewCsvRows([row], 56, 'Study 1')[1], [56, 'Study 1', 'A', 'psia', 4, 20, 25, -5, 20, 30, 1])
})
test('unknown units and missing endpoints never produce a guessed pressure delta', () => {
  for (const item of [profile([20, 10], ''), profile([null, 10]), profile([]), profile([Infinity, 20]), profile([1e308, -1e308])]) assert.equal(branchOverview([item])[0].difference, null)
  assert.equal(branchOverview([profile([null, null])])[0].minimum, null)
})
