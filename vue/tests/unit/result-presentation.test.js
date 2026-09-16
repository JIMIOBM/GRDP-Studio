import test from 'node:test'
import assert from 'node:assert/strict'
import { wellComparisonIssue, wellCsv } from '../../src/views/SoftwareIntegration/wellResultPresentation.js'
import { branchComparison, matchedBranches, nodeResultRows, validatedLayout, networkCsv } from '../../src/views/SoftwareIntegration/networkResultInteraction.js'

test('branch comparison preserves original distances and gaps, rejects ambiguous units and malformed arrays', () => {
  const profile = (unit, distances, values) => ({ variables: [
    { variable: 'TotalDistance', unit: 'm', values: distances },
    { variable: 'Pressure', unit, values }
  ] })
  const base = profile('bara', [0, 100], [90, 80])
  assert.deepEqual(branchComparison(base, profile('bara', [15, 75, 125], [91, null, 79]), 'Pressure'), [
    { distance: 15, value: 91 }, { distance: 75, value: null }, { distance: 125, value: 79 }
  ])
  for (const candidate of [profile('bar', [0], [1]), profile(null, [0], [1]), profile('bara', [0, 1], [1]), profile('bara', [0], [Infinity])]) {
    assert.equal(branchComparison(base, candidate, 'Pressure'), null)
  }
  assert.equal(branchComparison(base, base, 'Temperature'), null)
})

test('well comparison requires explicit equal units and model kind', () => {
  const result = { model_kind: 'basic_gas', units: { flow: { displayUnit: 'Mscf/d' } } }
  assert.equal(wellComparisonIssue(result, result, ['flow']), '')
  assert.ok(wellComparisonIssue(result, { ...result, model_kind: 'black_oil_liquid' }, ['flow']))
  assert.ok(wellComparisonIssue(result, { ...result, units: { flow: { displayUnit: null } } }, ['flow']))
  assert.ok(wellComparisonIssue(result, { ...result, units: { flow: { displayUnit: 'm3/d' } } }, ['flow']))
})

test('network matching uses complete returned identifiers, never substrings', () => {
  const profiles = [{ branch: 'FL-10', variables: [] }, { branch: 'FL-1', variables: [] }]
  assert.deepEqual(matchedBranches(profiles, ['FL-1']), ['FL-1'])
  assert.deepEqual(matchedBranches(profiles, ['FL']), [])
  assert.deepEqual(nodeResultRows([{ variable: 'Pressure', unit: 'bar', values: [{ name: 'FL-1', value: null }, { name: 'FL-10', value: 100 }] }], 'FL-1'), [{ variable: 'Pressure', unit: 'bar', value: null }])
})

test('saved layout rejects stale topology, incomplete positions and nonfinite coordinates', () => {
  const valid = { signature: 'topology-a', positions: [{ id: 'A', x: 5, y: -2 }] }
  assert.ok(validatedLayout(valid, 'topology-a', ['A']))
  assert.equal(validatedLayout(valid, 'topology-b', ['A']), null)
  assert.equal(validatedLayout(valid, 'topology-a', ['A', 'B']), null)
  assert.equal(validatedLayout({ ...valid, positions: [{ id: 'A', x: Infinity, y: 1 }] }, 'topology-a', ['A']), null)
})

test('CSV retains numeric precision and null gaps and escapes spreadsheet text', () => {
  for (const serialize of [wellCsv, networkCsv]) {
    const csv = serialize([['=formula', 'a,"b', 1.1234567890123, -2.5, null]])
    assert.ok(csv.includes('"\'=formula"'))
    assert.ok(csv.includes('"a,""b"'))
    assert.ok(csv.includes('"1.1234567890123","-2.5",""'))
  }
})
