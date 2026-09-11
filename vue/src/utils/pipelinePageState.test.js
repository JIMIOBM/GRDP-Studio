import test from 'node:test'
import assert from 'node:assert/strict'
import { createPipelineInput, fingerprint, inactiveConstraintKinds } from './pipelineDefaults.js'
import { activeBoundaryCase, hydrateBoundary, normalizeBoundary } from './pipelineBoundary.js'
const boundarySnapshot = (topologyRevision, operatingAt, nodes) => ({ topologyRevision, activeCaseId: 'case-1', cases: [{ id: 'case-1', operatingAt, nodes }] })

import { mergePipelineSection, normalizePipelineInput, pipelineSectionFields, reconcilePipelinePage, sectionInput } from './pipelinePageState.js'

test('hydrate state defaults to unknown and discarded manual risk curves never survive normalization', () => {
  const legacy = { waterState: 'present', hydratePoints: [{ pressureMpa: 2, temperatureC: 5 }], hydrateSource: '旧曲线' }
  assert.deepEqual(normalizePipelineInput({ constraints: legacy }).constraints, { waterState: 'unknown' })
  assert.deepEqual(normalizePipelineInput({ constraints: { ...legacy, waterState: 'available' } }).constraints, { waterState: 'available' })
  assert.deepEqual(createPipelineInput().constraints, { waterState: 'unknown' })
  assert.equal(createPipelineInput().boundary, null)
  assert.deepEqual(normalizePipelineInput(null), createPipelineInput())
  assert.deepEqual(normalizePipelineInput({ constraints: null, segments: null, equipment: {} }), createPipelineInput())
  assert.deepEqual(mergePipelineSection({ constraints: legacy }, {}, 'boundary').constraints, { waterState: 'unknown' })
  assert.equal(legacy.hydratePoints[0].temperatureC, 5)
})

test('only boundary page exposes editable section fields and omitted null values normalize consistently', () => {
  assert.deepEqual(Object.keys(pipelineSectionFields), ['boundary'])
  assert.deepEqual(pipelineSectionFields.boundary, ['boundary', 'standardPressurePa', 'standardTemperatureK', 'standardZ'])
  assert.deepEqual(sectionInput({}, 'boundary'), sectionInput({ inletMpa: null, outletMpa: undefined }, 'boundary'))
  assert.deepEqual(Object.keys(sectionInput({}, 'boundary')), pipelineSectionFields.boundary)
  for (const page of [...inactiveConstraintKinds, 'unknown']) {
    assert.throws(() => sectionInput({}, page), /不支持/)
    assert.throws(() => mergePipelineSection({}, {}, page), /不支持/)
    assert.throws(() => reconcilePipelinePage({}, {}, {}, page), /不支持/)
  }
})

test('boundary save replaces only node boundary and standard conditions, leaving flow and legacy scalars untouched', () => {
  const current = { ...createPipelineInput(), target: 'rate', inletMpa: 8, outletMpa: 3, rate10k: 12, inletC: 42,
    gasGravity: 0.7, z: 0.93, viscosityMpaS: 0.015, cpJkgK: 2300, jtKmpa: 2,
    frictionMethod: 'haaland', thermalMode: 'isothermal',
    segments: [{ name: '当前管段', lengthM: 2800, ambientC: 21, heatTransferWm2K: 6.5 }],
    equipment: [{ name: '当前设备', lossK: 4 }],
    constraints: { waterState: 'available' },
    boundary: boundarySnapshot(1, null, [{ nodeId: 'well', supplyRate10k: 8 }]),
    draftNote: '保留自定义草稿字段' }
  const snapshot = structuredClone(current)
  const server = { ...createPipelineInput(), inletMpa: 6, gasGravity: 0.6, segments: [{ name: '旧管段' }], equipment: [],
    standardPressurePa: 100000, standardTemperatureK: 288.15, standardZ: 0.98,
    boundary: boundarySnapshot(2, '2026-09-08T12:00', [{ nodeId: 'well', supplyRate10k: 9 }]) }
  const merged = mergePipelineSection(current, server, 'boundary')
  for (const key of pipelineSectionFields.boundary) assert.deepEqual(merged[key], normalizePipelineInput(server)[key], key)
  for (const key of Object.keys(current).filter(key => !pipelineSectionFields.boundary.includes(key))) {
    assert.deepEqual(merged[key], current[key], key)
  }
  assert.notEqual(activeBoundaryCase(merged.boundary).nodes, server.boundary.cases[0].nodes)
  assert.notEqual(merged.segments, current.segments)
  assert.notEqual(merged.constraints, current.constraints)
  assert.deepEqual(current, snapshot)
  assert.equal(mergePipelineSection(current, {}, 'boundary').boundary, null)
  assert.equal(mergePipelineSection(current, {}, 'boundary').inletMpa, 8)
})

test('boundary reconciliation adopts server boundary and concurrent untouched values while retaining real flow drafts', () => {
  const previous = normalizePipelineInput({ inletMpa: 6, inletC: 30, gasGravity: 0.65,
    boundary: boundarySnapshot(1, null, [{ nodeId: 'well', supplyRate10k: 8 }]),
    segments: [{ name: '当前拓扑管段', heatTransferWm2K: 4 }], equipment: [{ name: '当前拓扑设备', lossK: 8 }] })
  const current = structuredClone(previous)
  current.inletC = 42
  current.thermalMode = 'isothermal'
  current.z = 0.92
  current.draftNote = '未保存'
  activeBoundaryCase(current.boundary).nodes[0].supplyRate10k = 9
  const next = structuredClone(previous)
  next.inletMpa = 7
  next.inletC = 35
  next.gasGravity = 0.7
  next.z = 0.88
  next.frictionMethod = 'haaland'
  next.boundary = boundarySnapshot(1, '2026-09-08T13:00', [{ nodeId: 'well', supplyRate10k: 10 }])
  next.segments = [{ name: '服务器模型旧管段' }]
  next.equipment = [{ name: '服务器模型旧设备' }]
  const snapshots = [current, previous, next].map(value => structuredClone(value))
  const reconciled = reconcilePipelinePage(current, previous, next, 'boundary')
  assert.deepEqual(reconciled.boundary, normalizeBoundary(next.boundary))
  assert.notEqual(activeBoundaryCase(reconciled.boundary).nodes, next.boundary.cases[0].nodes)
  assert.equal(reconciled.inletMpa, 7)
  assert.equal(reconciled.inletC, 42)
  assert.equal(reconciled.z, 0.92)
  assert.equal(reconciled.gasGravity, 0.7)
  assert.equal(reconciled.frictionMethod, 'haaland')
  assert.equal(reconciled.thermalMode, 'isothermal')
  assert.equal(reconciled.draftNote, '未保存')
  assert.deepEqual(reconciled.constraints, { waterState: 'unknown' })
  assert.deepEqual(reconciled.segments, current.segments)
  assert.deepEqual(reconciled.equipment, current.equipment)
  assert.notEqual(reconciled.segments, current.segments)
  assert.notEqual(reconciled.equipment, current.equipment)
  assert.deepEqual([current, previous, next], snapshots)
})

test('nested boundary snapshots normalize as detached values without inventing a topology or operating hour', () => {
  const input = { boundary: boundarySnapshot(2, null, [{ nodeId: 'well', supplyRate10k: 8 }]) }
  const normalized = normalizePipelineInput(input)
  assert.deepEqual(normalized.boundary, normalizeBoundary(input.boundary))
  assert.equal(activeBoundaryCase(normalized.boundary).operatingAt, null)
  assert.deepEqual(Object.keys(activeBoundaryCase(normalized.boundary).nodes[0]),
    ['nodeId', 'supplyRate10k', 'withdrawalRate10k', 'pressureMpa', 'temperatureC'])
  activeBoundaryCase(normalized.boundary).nodes[0].supplyRate10k = 10
  assert.equal(input.boundary.cases[0].nodes[0].supplyRate10k, 8)
  assert.deepEqual(sectionInput({}, 'boundary'), sectionInput({ boundary: null }, 'boundary'))
})

test('saving and reloading sparse NON_NULL API responses does not leave boundary or model falsely dirty', () => {
  const graph = { nodes: [{ id: 'well', type: 'well', name: '井口' }, { id: 'out', type: 'station', name: '下载点' }],
    edges: [{ source: 'well', target: 'out' }] }
  const previous = normalizePipelineInput({})
  const draft = normalizePipelineInput({ boundary: hydrateBoundary({ inletMpa: 6, rate10k: 8, inletC: 40 }, graph, 3) })
  // Jackson's NON_NULL drops operatingAt and each null node value; field order is not significant JSON data.
  const response = JSON.parse(JSON.stringify(draft, (key, value) => value === null ? undefined : value))
  response.boundary = { cases: response.boundary.cases.map(condition => ({ nodes: condition.nodes.map(row => Object.fromEntries(Object.entries(row).reverse())), id: condition.id })),
    activeCaseId: response.boundary.activeCaseId, topologyRevision: response.boundary.topologyRevision }
  assert.notEqual(fingerprint(draft.boundary), fingerprint(response.boundary))
  const saved = normalizePipelineInput(response)
  let form = reconcilePipelinePage(draft, previous, response, 'boundary')
  form.boundary = hydrateBoundary(form, graph, 3)
  assert.equal(fingerprint(sectionInput(form, 'boundary')), fingerprint(sectionInput(saved, 'boundary')))
  assert.equal(fingerprint(form), fingerprint(saved))
  form = normalizePipelineInput(response)
  form.boundary = hydrateBoundary(form, graph, 3)
  assert.equal(fingerprint(form), fingerprint(saved))
  activeBoundaryCase(form.boundary).nodes[0].supplyRate10k = 9
  assert.notEqual(fingerprint(sectionInput(form, 'boundary')), fingerprint(sectionInput(saved, 'boundary')))
})

test('old purpose fields never reappear in save payloads, server merges or sparse reloads and original numbers survive', () => {
  const graph = { nodes: [{ id: 'well', type: 'well' }, { id: 'out', type: 'station' }], edges: [{ source: 'well', target: 'out' }] }
  const legacy = { topologyRevision: 3, activeCaseId: 'h2', cases: [
    { id: 'h1', operatingAt: '2026-09-10T08:00', nodes: [
      { nodeId: 'well', supplyRate10k: 8, pressureMpa: 6, temperatureC: 0, flowUse: 'reference', pressureUse: 'reference' },
      { nodeId: 'out', withdrawalRate10k: 8, pressureMpa: 4, temperatureC: -10, flowUse: 'given', pressureUse: 'minimum' }
    ] },
    { id: 'h2', operatingAt: '2026-09-10T09:00', nodes: [
      { nodeId: 'well', supplyRate10k: 0, pressureMpa: 6.2, temperatureC: null, flowUse: null, pressureUse: 'given' },
      { nodeId: 'out', withdrawalRate10k: 0, pressureMpa: null, temperatureC: null, flowUse: 'reference', pressureUse: null }
    ] }
  ] }
  const oldInput = { boundary: legacy, target: 'rate', rate10k: 999, inletMpa: 999 }, before = structuredClone(oldInput)
  const normalized = normalizePipelineInput(oldInput), payload = sectionInput(oldInput, 'boundary')
  const sparseServer = JSON.parse(JSON.stringify(payload, (key, value) => value === null ? undefined : value))
  const merged = mergePipelineSection(oldInput, sparseServer, 'boundary')
  const reconciled = reconcilePipelinePage(oldInput, {}, sparseServer, 'boundary')
  const reloaded = hydrateBoundary(normalizePipelineInput(sparseServer), graph, 3)
  for (const boundary of [normalized.boundary, payload.boundary, merged.boundary, reconciled.boundary, reloaded]) {
    assert.deepEqual(boundary, normalizeBoundary(legacy))
    assert.doesNotMatch(JSON.stringify(boundary), /flowUse|pressureUse/)
  }
  assert.deepEqual(oldInput, before)
  assert.equal(fingerprint(sectionInput(reconciled, 'boundary')), fingerprint(sectionInput(normalizePipelineInput(sparseServer), 'boundary')))
})
