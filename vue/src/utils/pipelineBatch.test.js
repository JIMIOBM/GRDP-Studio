import test from 'node:test'
import assert from 'node:assert/strict'
import { createPipelineInput, newSegment } from './pipelineDefaults.js'
import { fromInput, createNode } from './pipelineTopology.js'
import { createBoundaryCase } from './pipelineBoundary.js'
import { normalizePipelineInput } from './pipelinePageState.js'
import { networkOrder, resolveSavedNetworkTopology, batchInputMark, conditionTime, batchDataRows, batchChartSeries, batchExportRows } from './pipelineBatch.js'

function fixture() {
  const input = { ...createPipelineInput(), segments: [newSegment(0)] }
  const graph = fromInput(input, '井 A')
  const extra = createNode('station', 200, 300, 2)
  graph.nodes.push(extra)
  graph.edges.push({ ...structuredClone(graph.edges[0]), id: 'branch', name: '分支管道', target: extra.id })
  const first = createBoundaryCase(graph, '2026-09-11T01:00'), second = createBoundaryCase(graph, '2026-09-11T04:00')
  first.operatingAt = '2026-09-11T01:00'; second.operatingAt = '2026-09-11T04:00'
  input.boundary = { topologyRevision: 3, activeCaseId: first.id, cases: [second, first] }
  const pipes = graph.edges.map((edge, index) => ({ edgeId: edge.id, name: edge.name, inletMpa: 8, outletMpa: 7-index,
    inletC: 40, outletC: 30-index, rate10k: index+2 }))
  const result = { cases: [{caseId:first.id,operatingAt:first.operatingAt,status:'success',pipes},
    {caseId:second.id,operatingAt:second.operatingAt,status:'error',error:'入口压力缺失',pipes:[]}], successCount:1,failureCount:1 }
  return { graph, input, first, second, result }
}

test('a saved tree with multiple outlets supplies every pipe and ignores topology thermal defaults', () => {
  const { graph, input } = fixture()
  assert.equal(networkOrder(graph).length, 2)
  const resolved = resolveSavedNetworkTopology({ graph, revision: 3 }, input)
  assert.equal(resolved.error, '')
  assert.equal(resolved.input.segments.length, 2)
  assert.ok(resolved.input.segments.every(segment => segment.heatTransferWm2K === 0 && segment.ambientC === 0))
  graph.edges.push({ ...graph.edges[0], id: 'loop', source: graph.nodes[1].id, target: graph.nodes[2].id })
  assert.match(resolveSavedNetworkTopology({ graph, revision: 4 }, input).error, /汇合|环路/)
})

test('batch fingerprint covers every time while ignoring active-row selection and derived scalar outputs', () => {
  const { input, second } = fixture(), mark = batchInputMark(input, 3)
  input.boundary.activeCaseId = second.id; input.inletMpa = 999; input.segments[0].heatTransferWm2K = 99
  assert.equal(batchInputMark(input, 3), mark)
  input.boundary.cases[0].nodes[0].pressureMpa = 9
  assert.notEqual(batchInputMark(input, 3), mark)
})

test('charts use true Beijing timestamps, one series per pipe, and gaps at failed times', () => {
  const { graph, result } = fixture(), charts = batchChartSeries(graph, result, true)
  assert.equal(charts.temperature.length, 2)
  const [first, second] = charts.pressure[0].data
  assert.equal(first[0], Date.UTC(2026,8,10,17))
  assert.equal(second[0]-first[0], 3*3600000)
  assert.equal(first[1], 7)
  assert.equal(second[1], null)
  assert.deepEqual(batchChartSeries(graph, result, false), {temperature:[],pressure:[],flow:[]})
  assert.equal(conditionTime('2026-02-30T12:00'), null)
  assert.equal(conditionTime(null), null)
  assert.equal(conditionTime('2026-09-11T01:30'), Date.UTC(2026, 8, 10, 17, 30))
  assert.equal(conditionTime('2026/09/11 01:30'), Date.UTC(2026, 8, 10, 17, 30))
})

test('server-omitted empty measurements match the editable form without treating zero as missing', () => {
  const { input } = fixture()
  const server = JSON.parse(JSON.stringify(input, (key, value) => value === null ? undefined : value))
  const form = normalizePipelineInput(server)
  assert.equal(batchInputMark(server, 3), batchInputMark(form, 3))
  form.boundary.cases[0].nodes[1].withdrawalRate10k = 0
  assert.notEqual(batchInputMark(server, 3), batchInputMark(form, 3))
})

test('minute-level batch results retain actual spacing and do not become stale on display-format normalization', () => {
  const { graph, input, result, first, second } = fixture()
  first.operatingAt = '2026/09/11 08:17'; second.operatingAt = '2026/09/11 08:49'
  result.cases[0].operatingAt = '2026-09-11T08:17'; result.cases[1].operatingAt = '2026-09-11T08:49'
  assert.equal(batchInputMark(input, 3), batchInputMark(normalizePipelineInput(input), 3))
  const points = batchChartSeries(graph, result, true).pressure[0].data
  assert.equal(points[0][0], Date.UTC(2026, 8, 11, 0, 17))
  assert.equal(points[1][0] - points[0][0], 32 * 60000)
  assert.equal(points[1][1], null)
  assert.equal(batchDataRows(graph, input, result, true).length, 4)
})

test('table and CSV include all times and all pipes, preserve failures, and hide obsolete values', () => {
  const { graph, input, result } = fixture()
  const rows = batchDataRows(graph, input, result, true)
  assert.equal(rows.length, 4)
  assert.equal(rows[0].operatingAt, '2026-09-11T01:00')
  assert.equal(rows[2].error, '入口压力缺失')
  assert.equal(rows[2].outletMpa, null)
  assert.ok(batchDataRows(graph, input, result, false).every(row => row.status === 'pending' && row.outletMpa == null))
  const csv = batchExportRows(graph, input, result, true)
  assert.equal(csv.length, 5)
  assert.equal(csv[3].at(-1), '入口压力缺失')
})
