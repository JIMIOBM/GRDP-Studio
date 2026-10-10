import test from 'node:test'
import assert from 'node:assert/strict'
import { erosionParameters, erosionDataRows, erosionChartSeries, setErosionParameter, erosionStatusLabel, erosionInputIssue } from './pipelineErosion.js'

const graph = { edges: [{ id: 'pipe', name: '井口至阀组', parameters: { diameterMm: 100 } }] }
const cases = [{ id: 'b', operatingAt: '2026-09-01T01:35' }, { id: 'a', operatingAt: '2026-09-01T00:20' }]
const settings = () => ({ segments: [{ edgeId: 'pipe', liquidHoldupPercent: 0.5, sandContentPercent: 0.001, sandDensityKgM3: 2650 }],
  cases: [{ caseId: 'b', edgeId: 'pipe', liquidHoldupPercent: 0, sandContentPercent: null }] })

test('erosion requires all three effective inputs for every time and pipe, without preset values', () => {
  const absent = erosionInputIssue(graph, cases, {})
  assert.match(absent, /2026\/09\/01 00:20.*井口至阀组.*持液率.*含砂率.*砂粒密度/)
  assert.deepEqual(erosionParameters({}, 'a', 'pipe'), { liquidHoldupPercent: null, sandContentPercent: null, sandDensityKgM3: null })
  assert.equal(erosionInputIssue(graph, cases, settings()), '')
  const extraPipe = { edges: [...graph.edges, { id: 'outlet', name: '出口管道' }] }
  assert.match(erosionInputIssue(extraPipe, cases, settings()), /出口管道/)
  const onlyOneTime = { cases: [{ caseId: 'a', edgeId: 'pipe', liquidHoldupPercent: 0, sandContentPercent: 0, sandDensityKgM3: 2600 }] }
  assert.match(erosionInputIssue(graph, cases, onlyOneTime), /2026\/09\/01 01:35/)
  const input = settings()
  input.segments[0].sandDensityKgM3 = null
  assert.match(erosionInputIssue(graph, cases, input), /请填写砂粒密度/)
  input.segments[0].sandDensityKgM3 = 0
  assert.match(erosionInputIssue(graph, cases, input), /砂粒密度须大于 0/)
  input.segments[0].sandDensityKgM3 = 2650
  input.segments[0].sandContentPercent = 100
  assert.match(erosionInputIssue(graph, cases, input), /小于 100%/)
})
test('case override inherits field-by-field, retains measured zero and can return to defaults', () => {
  const input = settings()
  assert.deepEqual(erosionParameters(input, 'b', 'pipe'), { liquidHoldupPercent: 0, sandContentPercent: 0.001, sandDensityKgM3: 2650 })
  setErosionParameter(input, 'pipe', 'sandContentPercent', 0, 'a')
  assert.equal(erosionParameters(input, 'a', 'pipe').sandContentPercent, 0)
  setErosionParameter(input, 'pipe', 'sandContentPercent', null, 'a')
  assert.equal(erosionParameters(input, 'a', 'pipe').sandContentPercent, 0.001)
  assert.equal(input.cases.length, 1)
  setErosionParameter(input, 'pipe', 'sandDensityKgM3', 2600)
  assert.equal(erosionParameters(input, 'b', 'pipe').sandDensityKgM3, 2600)
})
test('draft table preserves all cases and hides stale results without losing editable values', () => {
  const result = { cases: [{ caseId: 'a', status: 'success', erosion: [{ edgeId: 'pipe', status: 'reference_below', actualVelocityMs: 2, criticalVelocityMs: 8, velocityRatio: 0.25, distanceM: 120, gasDensityKgM3: 62 }] },
    { caseId: 'b', status: 'error', error: '边界压力不足', erosion: [{ edgeId: 'pipe', velocityRatio: 0.1 }] }] }
  const rows = erosionDataRows(graph, cases, settings(), result, true)
  assert.deepEqual(rows.map(row => row.caseId), ['a', 'b'])
  assert.equal(rows[0].distanceM, 120)
  assert.equal(rows[1].velocityRatio, undefined)
  assert.equal(rows[1].reason, '边界压力不足')
  assert.equal(rows[1].liquidHoldupPercent, 0)
  const stale = erosionDataRows(graph, cases, settings(), result, false)
  assert.ok(stale.every(row => row.gasDensityKgM3 === undefined && row.criticalVelocityMs === undefined))
  assert.equal(stale[0].sandDensityKgM3, 2650)
})
test('charts use same-point velocity and critical value and retain gaps for unavailable cases', () => {
  const rows = [{ edgeId: 'pipe', name: '井口至阀组', operatingAt: cases[0].operatingAt, status: 'not_applicable', actualVelocityMs: 20, criticalVelocityMs: 5, velocityRatio: 4 },
    { edgeId: 'pipe', name: '井口至阀组', operatingAt: cases[1].operatingAt, status: 'reference_above', actualVelocityMs: 9, criticalVelocityMs: 8, velocityRatio: 1.125 }]
  assert.deepEqual(erosionChartSeries(rows, 'pipe', 'velocity').map(series => series.data.map(point => point[1])), [[9, null], [8, null]])
  assert.deepEqual(erosionChartSeries(rows, '', 'ratio').map(series => series.data.map(point => point[1])), [[1.125, null], [1, 1]])
  assert.match(erosionStatusLabel('reference_below'), /参考/)
  assert.equal(erosionStatusLabel('not_applicable'), '模型不适用')
  assert.ok(erosionChartSeries(rows.slice(0, 1), '', 'ratio').every(series => series.id !== 'erosion:threshold'))
})
