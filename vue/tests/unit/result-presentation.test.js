import test from 'node:test'
import assert from 'node:assert/strict'
import { wellComparisonDeltaRows, wellComparisonIssue, wellCsv, wellScenarioLabel } from '../../src/views/SoftwareIntegration/wellResultPresentation.js'
import { branchComparison, matchedBranches, nodeResultRows, networkCsv, qualityLocation, validatedLayout } from '../../src/views/SoftwareIntegration/networkResultInteraction.js'

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

test('well comparison summary only aligns exact coordinates and preserves the scenario label', () => {
  const result = {
    units: { flow: { displayUnit: 'STB/D' }, pressure: { displayUnit: 'psia' }, depth: { displayUnit: 'ft' }, temperature: { displayUnit: 'degF' } },
    ipr: [{ flow: 0, pressure: 200 }, { flow: 10, pressure: 180 }, { flow: 20, pressure: 160 }],
    vlp: [{ flow: 0, pressure: 100 }, { flow: 10, pressure: 120 }],
    profile: [{ depth: 0, pressure: 200, temperature: 70 }, { depth: 100, pressure: 210, temperature: 80 }]
  }
  const comparison = {
    ...result,
    ipr: [{ flow: 0, pressure: 190 }, { flow: 10, pressure: 175 }, { flow: 30, pressure: 150 }],
    vlp: [{ flow: 0, pressure: 95 }, { flow: 10, pressure: 119 }],
    profile: [{ depth: 0, pressure: 198, temperature: 68 }, { depth: 100, pressure: 207, temperature: 79 }]
  }
  const rows = wellComparisonDeltaRows(result, comparison)
  assert.deepEqual(rows.map(row => [row.key, row.count, row.min, row.max]), [
    ['ipr-pressure', 2, 5, 10], ['vlp-pressure', 2, 1, 5], ['profile-pressure', 2, 2, 3], ['profile-temperature', 2, 1, 2]
  ])
  assert.equal(wellScenarioLabel({ parameters: null }), '原模型参数（未覆盖）')
  assert.equal(wellScenarioLabel({ parameters: { schemaVersion: 'pipesim-well-parameters/1', reservoirPressurePsi: 4000 } }), '地层压力方案：4000 psia')
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

test('quality location resolves only exact returned node/profile identifiers', () => {
  const context = {
    nodes: [{ id: 'N1' }, { id: 'N1.branch' }, { id: 'N2' }],
    nodeResults: [{ variable: 'Pressure', values: [{ name: 'N1' }, { name: 'N1.branch' }, { name: 'N2' }] }],
    systemResults: [{ variable: 'OutletPressure', values: [{ name: 'Network' }] }],
    profiles: [{ branch: 'N1.branch', variables: [{ variable: 'Pressure', values: [90, 80] }] }]
  }
  assert.deepEqual(qualityLocation({ path: 'node.Pressure.N1.branch' }, context), {
    kind: 'node', id: 'N1.branch', variable: 'Pressure', label: '节点 N1.branch'
  })
  assert.deepEqual(qualityLocation({ path: 'profiles.N1.branch.Pressure[1]' }, context), {
    kind: 'profile', id: 'N1.branch', variable: 'Pressure', pointIndex: 1, label: '支路 N1.branch'
  })
  assert.deepEqual(qualityLocation({ path: 'system.OutletPressure.Network' }, context), {
    kind: 'system', variable: 'OutletPressure', label: '系统结果'
  })
  assert.equal(qualityLocation({ path: 'node.Temperature.N1' }, context).kind, 'unknown')
  assert.equal(qualityLocation({ path: 'profiles.N1.branch.Temperature[0]' }, context).kind, 'unknown')
})

test('CSV retains numeric precision and null gaps and escapes spreadsheet text', () => {
  for (const serialize of [wellCsv, networkCsv]) {
    const csv = serialize([['=formula', 'a,"b', 1.1234567890123, -2.5, null]])
    assert.ok(csv.includes('"\'=formula"'))
    assert.ok(csv.includes('"a,""b"'))
    assert.ok(csv.includes('"1.1234567890123","-2.5",""'))
  }
})
