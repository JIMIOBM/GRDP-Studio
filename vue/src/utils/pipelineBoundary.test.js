import test from 'node:test'
import assert from 'node:assert/strict'
import { activeBoundaryCase, boundaryCases, createBoundaryCase, boundaryRows, boundarySummary, hydrateBoundary, normalizeBoundary, orphanBoundaryRows } from './pipelineBoundary.js'

const graph = () => ({ nodes: [
  { id: 'well', type: 'well', name: '采气井' },
  { id: 'valve', type: 'valve', name: '中间阀门' },
  { id: 'out', type: 'station', name: '下载站' }
], edges: [{ source: 'well', target: 'valve' }, { source: 'valve', target: 'out' }] })
const node = (boundary, id) => activeBoundaryCase(boundary).nodes.find(item => item.nodeId === id)
const assertCanonicalNodes = boundary => {
  for (const condition of boundaryCases(boundary)) for (const row of condition.nodes) {
    assert.deepEqual(Object.keys(row), ['nodeId', 'supplyRate10k', 'withdrawalRate10k', 'pressureMpa', 'temperatureC'])
  }
}

test('sparse boundary normalization restores nulls in canonical order without inventing conditions', () => {
  const sparse = { activeCaseId: 'case-1', cases: [{ id: 'case-1', nodes: [{ temperatureC: 0, nodeId: 'well', supplyRate10k: 0 }] }], topologyRevision: 3 }
  const snapshot = structuredClone(sparse)
  const normalized = normalizeBoundary(sparse)
  assert.equal(normalizeBoundary(null), null)
  assert.equal(normalizeBoundary(undefined), null)
  assert.deepEqual(Object.keys(normalized), ['topologyRevision', 'activeCaseId', 'cases'])
  assert.deepEqual(normalized, { topologyRevision: 3, activeCaseId: 'case-1', cases: [{ id: 'case-1', operatingAt: null, nodes: [{
    nodeId: 'well', supplyRate10k: 0, withdrawalRate10k: null, pressureMpa: null, temperatureC: 0
  }] }] })
  assert.equal(JSON.stringify(normalizeBoundary(normalized)), JSON.stringify(normalized))
  activeBoundaryCase(normalized).nodes[0].supplyRate10k = 5
  assert.deepEqual(sparse, snapshot)
  const hydrated = hydrateBoundary({ boundary: sparse }, graph(), 3)
  assertCanonicalNodes(hydrated)
})

test('new node boundaries contain no fabricated operating date, pressure, flow or temperature', () => {
  const topology = graph(), snapshot = structuredClone(topology)
  const boundary = hydrateBoundary({}, topology, 3)
  assert.equal(boundary.topologyRevision, 3)
  assert.equal(activeBoundaryCase(boundary).operatingAt, null)
  assert.deepEqual(activeBoundaryCase(boundary).nodes.map(row => row.nodeId), ['well', 'valve', 'out'])
  for (const row of activeBoundaryCase(boundary).nodes) {
    for (const key of ['supplyRate10k', 'withdrawalRate10k', 'pressureMpa', 'temperatureC']) assert.equal(row[key], null)
    assert.equal(Object.hasOwn(row, 'name'), false)
    assert.equal(Object.hasOwn(row, 'role'), false)
  }
  assertCanonicalNodes(boundary)
  assertCanonicalNodes({ cases: [createBoundaryCase(topology)] })
  assert.deepEqual(topology, snapshot)
})

test('new drafts never infer node measurements from scalar calculation snapshots', () => {
  const input = { inletMpa: 6, outletMpa: 4, rate10k: 8, inletC: 40 }
  const boundary = hydrateBoundary(input, graph(), 1)
  for (const item of activeBoundaryCase(boundary).nodes)
    for (const field of ['supplyRate10k', 'withdrawalRate10k', 'pressureMpa', 'temperatureC']) assert.equal(item[field], null)
  assert.deepEqual(boundaryCases({ nodes: [{ nodeId: 'well', pressureMpa: 8 }] }), [])
  assert.equal(activeBoundaryCase({ nodes: [] }), null)
})

test('topology rename/reorder preserves matching drafts and retains removed or duplicate records for review', () => {
  const boundary = hydrateBoundary({}, graph(), 1)
  activeBoundaryCase(boundary).operatingAt = '2026-09-08T12:00'
  Object.assign(node(boundary, 'out'), { pressureMpa: 4.2, withdrawalRate10k: 7 })
  node(boundary, 'valve').withdrawalRate10k = 1
  activeBoundaryCase(boundary).nodes.push({ ...node(boundary, 'out'), withdrawalRate10k: 3 })
  const input = { boundary }, snapshot = structuredClone(input)
  const topology = { nodes: [{ id: 'out', type: 'station', name: '重命名下载站' },
    { id: 'well', type: 'well', name: '采气井' }, { id: 'new', type: 'junction', name: '新节点' }],
  edges: [{ source: 'well', target: 'new' }, { source: 'new', target: 'out' }] }
  const hydrated = hydrateBoundary(input, topology, 2)
  assert.deepEqual(activeBoundaryCase(hydrated).nodes.map(row => row.nodeId), ['out', 'well', 'new', 'valve', 'out'])
  assert.equal(hydrated.topologyRevision, 2)
  assert.equal(activeBoundaryCase(hydrated).operatingAt, '2026-09-08T12:00')
  assertCanonicalNodes(hydrated)
  assert.equal(node(hydrated, 'valve').withdrawalRate10k, 1)
  assert.deepEqual(orphanBoundaryRows(hydrated, topology).map(row => [row.nodeId, row.reason]), [['valve', 'deleted'], ['out', 'duplicate']])
  assert.equal(boundarySummary(hydrated, topology).differenceRate10k, null)
  node(hydrated, 'out').pressureMpa = 9
  assert.deepEqual(input, snapshot)
})

test('display roles come from graph and edits through record update only the passed boundary draft', () => {
  const topology = graph(), boundary = hydrateBoundary({}, topology, 1)
  const rows = boundaryRows(topology, boundary)
  assert.deepEqual(rows.map(row => row.role), ['supply', 'junction', 'delivery'])
  assert.equal(rows[1].nodeType, 'valve')
  assert.equal(rows[2].name, '下载站')
  assert.equal(rows[2].record, node(boundary, 'out'))
  rows[2].record.withdrawalRate10k = 5
  assert.equal(node(boundary, 'out').withdrawalRate10k, 5)
  assert.equal(Object.hasOwn(node(boundary, 'out'), 'role'), false)
})

test('flow totals remain unknown for missing endpoints and include optional internal withdrawals', () => {
  const topology = graph(), boundary = hydrateBoundary({}, topology, 1)
  let summary = boundarySummary(boundary, topology)
  assert.equal(summary.supplyRate10k, null)
  assert.equal(summary.withdrawalRate10k, null)
  assert.equal(summary.differenceRate10k, null)
  assert.deepEqual(summary.missingSupplyNodeIds, ['well'])
  assert.deepEqual(summary.missingWithdrawalNodeIds, ['out'])
  node(boundary, 'well').supplyRate10k = 8
  node(boundary, 'out').withdrawalRate10k = 6
  summary = boundarySummary(boundary, topology)
  assert.equal(summary.differenceRate10k, 2)
  assert.equal(summary.complete, true)
  node(boundary, 'valve').withdrawalRate10k = 2
  assert.equal(boundarySummary(boundary, topology).differenceRate10k, 0)
  node(boundary, 'out').withdrawalRate10k = null
  assert.equal(boundarySummary(boundary, topology).withdrawalRate10k, null)
  node(boundary, 'out').withdrawalRate10k = 0
  assert.equal(boundarySummary(boundary, topology).withdrawalRate10k, 2)
  activeBoundaryCase(boundary).nodes = activeBoundaryCase(boundary).nodes.filter(row => row.nodeId !== 'out')
  assert.equal(boundarySummary(boundary, topology).withdrawalRate10k, null)
})

test('all entered rates join flow sums and invalid numbers never become zero', () => {
  const topology = graph(), boundary = hydrateBoundary({}, topology, 1)
  node(boundary, 'well').supplyRate10k = 8
  node(boundary, 'out').withdrawalRate10k = 8
  let summary = boundarySummary(boundary, topology)
  assert.equal(summary.supplyRate10k, 8)
  assert.equal(summary.withdrawalRate10k, 8)
  assert.equal(summary.differenceRate10k, 0)
  assert.deepEqual(summary.missingWithdrawalNodeIds, [])
  for (const invalid of [NaN, Infinity, -1, '8', '']) {
    node(boundary, 'out').withdrawalRate10k = invalid
    summary = boundarySummary(boundary, topology)
    assert.equal(summary.withdrawalRate10k, null)
    assert.deepEqual(summary.missingWithdrawalNodeIds, ['out'])
  }
  assert.deepEqual(hydrateBoundary({}, null, 0), { topologyRevision: 0, activeCaseId: 'case-1', cases: [{ id: 'case-1', operatingAt: null, nodes: [] }] })
})

test('all operating rows survive normalization, topology changes and active-row selection', () => {
  const topology = graph()
  const first = createBoundaryCase(topology), second = createBoundaryCase(topology)
  assert.notEqual(first.id, second.id)
  first.operatingAt = '2026-09-10T08:00'; second.operatingAt = '2026-09-10T09:00'
  first.nodes[0].supplyRate10k = 8; second.nodes[0].supplyRate10k = 12
  second.nodes[2].pressureMpa = 3
  const original = { topologyRevision: 1, activeCaseId: second.id, cases: [first, second] }
  const snapshot = structuredClone(original)
  const updatedGraph = graph()
  updatedGraph.nodes = updatedGraph.nodes.filter(n => n.id !== 'valve')
  updatedGraph.nodes.push({ id: 'new', name: '新节点', type: 'junction' })
  const hydrated = hydrateBoundary({ boundary: original }, updatedGraph, 2)
  assert.equal(activeBoundaryCase(hydrated).id, second.id)
  assert.equal(boundarySummary(hydrated, updatedGraph).supplyRate10k, 12)
  assert.deepEqual(boundaryCases(hydrated).map(row => row.operatingAt), [first.operatingAt, second.operatingAt])
  for (const row of hydrated.cases) {
    assert.equal(row.nodes.find(n => n.nodeId === 'new').pressureMpa, null)
    assert.ok(row.nodes.find(n => n.nodeId === 'valve'), 'retain removed-node data for explicit review in every hour')
  }
  assertCanonicalNodes(hydrated)
  hydrated.activeCaseId = first.id
  assert.equal(boundarySummary(hydrated, updatedGraph).supplyRate10k, 8)
  assert.equal(JSON.stringify(normalizeBoundary(hydrated)), JSON.stringify(hydrated))
  assert.deepEqual(original, snapshot)
})

test('missing active row never falls back to a different operating hour or fabricates deleted rows', () => {
  const row = createBoundaryCase(graph())
  const boundary = { topologyRevision: 2, activeCaseId: 'missing', cases: [row] }
  const hydrated = hydrateBoundary({ boundary }, graph(), 2)
  assert.equal(activeBoundaryCase(hydrated), null)
  assert.ok(boundaryRows(graph(), hydrated).every(item => item.record === null))
  assert.equal(normalizeBoundary({ ...boundary, activeCaseId: undefined }).activeCaseId, null)
  assert.deepEqual(hydrateBoundary({ boundary: { ...boundary, cases: [], activeCaseId: null } }, graph(), 2),
    { topologyRevision: 2, activeCaseId: null, cases: [] })
})

test('old purpose fields are discarded from every hour and orphan without losing measured values or mutating source data', () => {
  const topology = graph()
  for (const flowUse of ['given', 'reference', null]) {
    for (const pressureUse of ['given', 'reference', 'minimum', null]) {
      const condition = { id: 'hour-a', operatingAt: '2026-09-10T08:00', nodes: [
        { nodeId: 'well', supplyRate10k: 8, withdrawalRate10k: 0, pressureMpa: 6.5, temperatureC: 0, flowUse, pressureUse },
        { nodeId: 'out', supplyRate10k: 0, withdrawalRate10k: 8, pressureMpa: 4.2, temperatureC: -20, flowUse, pressureUse },
        { nodeId: 'removed', supplyRate10k: null, withdrawalRate10k: 0, pressureMpa: 3, temperatureC: null, flowUse, pressureUse }
      ] }
      const original = { topologyRevision: 1, activeCaseId: 'hour-b', cases: [condition, { ...structuredClone(condition), id: 'hour-b', operatingAt: '2026-09-10T09:00' }] }
      const before = structuredClone(original)
      const normalized = normalizeBoundary(original)
      const hydrated = hydrateBoundary({ boundary: original, target: 'rate', rate10k: 99, inletMpa: 100, outletMpa: 100, inletC: 100 }, topology, 2)
      for (const result of [normalized, hydrated]) {
        assertCanonicalNodes(result)
        for (const entry of result.cases) for (const oldNode of condition.nodes) {
          const actual = entry.nodes.find(row => row.nodeId === oldNode.nodeId)
          for (const key of ['supplyRate10k', 'withdrawalRate10k', 'pressureMpa', 'temperatureC']) assert.equal(actual[key], oldNode[key])
        }
      }
      assert.deepEqual(original, before)
    }
  }
})

test('legacy purpose markers no longer exclude measured rates from totals, and finite totals are required', () => {
  const topology = graph(), boundary = hydrateBoundary({}, topology, 1)
  Object.assign(node(boundary, 'well'), { supplyRate10k: 8, flowUse: 'reference' })
  Object.assign(node(boundary, 'out'), { withdrawalRate10k: 8, flowUse: 'reference' })
  assert.equal(boundarySummary(boundary, topology).differenceRate10k, 0)
  for (const id of ['well', 'out']) {
    node(boundary, id)[id === 'well' ? 'supplyRate10k' : 'withdrawalRate10k'] = 0
  }
  const zero = boundarySummary(boundary, topology)
  assert.equal(zero.supplyRate10k, 0)
  assert.equal(zero.withdrawalRate10k, 0)
  assert.equal(zero.complete, true)
  node(boundary, 'valve').withdrawalRate10k = Number.MAX_VALUE
  node(boundary, 'out').withdrawalRate10k = Number.MAX_VALUE
  const overflowing = boundarySummary(boundary, topology)
  assert.equal(overflowing.withdrawalRate10k, null)
  assert.equal(overflowing.differenceRate10k, null)
  assert.equal(overflowing.complete, false)
})
