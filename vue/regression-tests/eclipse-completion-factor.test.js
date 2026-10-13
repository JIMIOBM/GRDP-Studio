import assert from 'node:assert/strict'
import { test } from 'node:test'
import { completionFactorUnit, completionFactorResponse } from '../src/views/SoftwareIntegration/eclipseCompletionFactor.js'

const curve = (keyword, unit, points, objectName = 'G1') => ({ keyword, unit, objectName, points: points.map(([timeDays, value]) => ({ timeDays, value })) })
test('CF uses connection transmissibility units, independent of KH and flow rate', () => {
  assert.equal(completionFactorUnit('FIELD'), 'cP.rb/day/psi')
  assert.equal(completionFactorUnit('METRIC'), 'cP.rm³/day/bar')
  assert.equal(completionFactorUnit('LAB'), 'cP.rcc/hr/atm')
  assert.equal(completionFactorUnit('PVT-M'), 'cP.rm³/day/atm')
  assert.equal(completionFactorUnit(null), null)
})
test('CF compares actual common points and leaves differing report times empty', () => {
  const baseline = { series: [curve('WOPR', 'STB/day', [[1, 100], [3, 100]]), curve('WBHP', 'PSIA', [[1, 3000], [3, 3000]])] }
  const current = { series: [curve('WOPR', 'STB/day', [[1, 100], [2, 100], [3, 100]]), curve('WBHP', 'PSIA', [[1, 2900], [2, 2900], [3, 2900]])] }
  const result = completionFactorResponse(current, baseline, 'G1')
  assert.equal(result.available, true); assert.equal(result.changedCount, 2); assert.equal(result.unmatchedCount, 2)
  assert.deepEqual(result.rows.filter(r => r.timeDays === 2).map(r => [r.baseline, r.current, r.difference]), [[null, 100, null], [null, 2900, null]])
  assert.ok(result.rows.filter(r => r.keyword === 'WOPR' && r.timeDays !== 2).every(r => r.difference === 0))
})
test('Duplicate, mismatched units, invalid clocks or missing well refuse CF comparison', () => {
  const s = curve('WBHP', 'PSIA', [[1, 3000], [3, 2900]])
  const baseline = { series: [s] }
  for (const current of [{ series: [s, s] }, { series: [{ ...s, unit: 'BARSA' }] },
    { series: [curve('WBHP', 'PSIA', [[1, 1], [1, 2]])] }, { series: [] }]) {
    assert.equal(completionFactorResponse(current, baseline, 'G1').available, false)
  }
  assert.equal(completionFactorResponse(baseline, baseline, 'other').available, false)
})
