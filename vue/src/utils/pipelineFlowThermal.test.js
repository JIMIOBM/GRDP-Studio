import test from 'node:test'
import assert from 'node:assert/strict'
import { flowThermalSourceChanged } from './pipelineFlowThermal.js'
import { createPipelineInput, newSegment } from './pipelineDefaults.js'
import { fromInput } from './pipelineTopology.js'

function fixture() {
  const input = { ...createPipelineInput(), segments: [newSegment(0)] }
  const graph = fromInput(input, '井口')
  const detail = { revision: 3, settings: { segments: [{ edgeId: graph.edges[0].id, ambientC: 19,
    gasConductivityWmK: 0.04, layers: [] }], tolerance: 0.001, maxIterations: 30 } }
  const snapshot = { ...structuredClone(detail), topologyRevision: 2 }
  return { input, graph, detail, snapshot }
}

test('thermal source changes invalidate heat results; display snapshots do not change physical inputs', () => {
  const { detail, snapshot } = fixture()
  assert.equal(flowThermalSourceChanged(detail, snapshot, 2), false)
  detail.settings.savedResults = { arbitrary: 'display only' }
  detail.settings.segments[0].densityKgM3 = 999
  detail.settings.segments[0].propertySource = 'obsolete'
  assert.equal(flowThermalSourceChanged(detail, snapshot, 2), false)
  assert.equal(flowThermalSourceChanged(detail, snapshot, 3), true)
  detail.settings.segments[0].gasConductivityWmK = 0.05
  assert.equal(flowThermalSourceChanged(detail, snapshot, 2), true)
  assert.equal(flowThermalSourceChanged(null, snapshot, 2), true)
  assert.equal(flowThermalSourceChanged(detail, null, 2), true)
})

