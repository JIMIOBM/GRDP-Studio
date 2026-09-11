import test from 'node:test'
import assert from 'node:assert/strict'
import { gasPropertyContextKey, inspectGasComposition, gasPropertyCompositionIssue, gasPropertyConditionIssues, gasPropertyInputIssues, gasPropertyInputs, gasPropertyResultStamp, gasPropertyResultUnsaved, gasPropertyInitialPanel, gasPropertyCheckpointLabel, reconcileGasPropertyPoints, createGasPropertyRequestScope } from './pipelineGasPropertyState.js'
import { ensurePipelineNavigation, findPipelinePageNode, resolvePipelinePage } from './pipelineNavigation.js'

const composition = remaining => [{ code: 'CH4', moleFraction: .9 }, { code: 'N2', moleFraction: remaining }]

test('reloading saved result labels uses the matching actual source and never an unloaded selector label',()=>{
  const model={pvtId:7,pvtName:'A井完整气体模型',compositionRevision:'new-model-source'}
  const checkpoint={pvtId:7,compositionRevision:'new-model-source',method:'PR'}
  assert.equal(gasPropertyCheckpointLabel(checkpoint,model),'A井完整气体模型')
  assert.equal(gasPropertyCheckpointLabel({...checkpoint,compositionRevision:'legacy-source'},model),'历史 PVT（ID：7）')
  assert.equal(gasPropertyCheckpointLabel({...checkpoint,pvtId:8},model),'历史 PVT（ID：8）')
  assert.equal(gasPropertyCheckpointLabel(checkpoint,null),'历史 PVT（ID：7）')
  assert.equal(checkpoint.compositionRevision,'new-model-source');assert.equal(checkpoint.method,'PR')
})
test('entering either gas page opens an existing result, including a stale draft, without borrowing the other page result', () => {
  const points = { z: { pressureMpa: 5, result: { z: .9 }, resultMark: 'old condition' }, cp: { pressureMpa: 6, result: null } }
  assert.equal(gasPropertyInitialPanel(points.z), 'result')
  assert.equal(gasPropertyInitialPanel(points.cp), 'input')
  assert.equal(gasPropertyInitialPanel(undefined), 'input')
  points.cp.result = { cpJkgK: 1234 }
  assert.equal(gasPropertyInitialPanel(points.cp), 'result')
})
test('original PVT non-hydrocarbon fields expose their actual total without inferring missing hydrocarbons', () => {
  const rows = [{ code: 'H2S', moleFraction: .0462 }, { code: 'CO2', moleFraction: .0396 }, { code: 'N2', moleFraction: 0 }]
  const before = structuredClone(rows)
  const message = gasPropertyCompositionIssue(rows, [], 'PVT性质2')
  assert.match(message, /PVT性质2.*8\.580000%.*不足 100%/)
  assert.match(message, /PVT 模型页补齐真实气体组成/)
  assert.match(message, /不能根据差额推算甲烷/)
  assert.deepEqual(rows, before)
  assert.equal(rows.some(row => row.code === 'CH4'), false)
})

test('composition messages distinguish no source, invalid values and totals above 100 percent', () => {
  assert.match(gasPropertyCompositionIssue([]), /没有已保存/)
  assert.match(gasPropertyCompositionIssue([{ code: 'N2', moleFraction: null }]), /缺失或无效/)
  assert.match(gasPropertyCompositionIssue([{ code: 'CO2', moleFraction: .6 }, { code: 'N2', moleFraction: .6 }]), /120\.000000%.*超过 100%/)
  assert.equal(gasPropertyCompositionIssue([{ code: 'H2S', moleFraction: 0 }, { code: 'CO2', moleFraction: 0 }, { code: 'N2', moleFraction: 1 }]), '')
})

test('original PVT source fingerprints invalidate both results without requiring numeric versions', () => {
  const source = calculatedModel('PR', 'sha256-first-source')
  for (const kind of ['z', 'cp']) {
    assert.equal(source.points[kind].resultMark, gasPropertyResultStamp(source, kind, 'sha256-first-source'))
    assert.notEqual(source.points[kind].resultMark, gasPropertyResultStamp(source, kind, 'sha256-updated-source'))
  }
})

test('a 70 MPa PVT point is accepted while incomplete composition still explains why calculation is unavailable', () => {
  const issues = gasPropertyInputIssues({ pvtId: 2, method: 'PR', pressureMpa: 70, temperatureC: 39.35, compositionIssue: '该 PVT 尚未保存完整气体组成。' })
  assert.equal(issues.length, 1)
  assert.match(issues[0], /气体组成/)
})

test('positive pressure and temperature above absolute zero are accepted without an artificial EOS range', () => {
  for (const method of ['PR', 'SRK', 'BWRS']) for (const pressureMpa of [.0000001, 5, 50, 70, 200]) for (const temperatureC of [-100, -73.15, 0, 226.85, 300]) {
    assert.deepEqual(gasPropertyInputIssues({ pvtId: 10, method, pressureMpa, temperatureC, compositionIssue: '' }), [])
  }
})

test('empty and nonfinite fields report which parameters need input', () => {
  for (const value of [null, undefined, '', ' ', NaN, Infinity]) {
    const issues = gasPropertyConditionIssues({ pressureMpa: value, temperatureC: value })
    assert.equal(issues.length, 2)
    assert.match(issues[0], /填写计算压力/)
    assert.match(issues[1], /填写计算温度/)
  }
})

test('nonphysical PVT points still report absolute-pressure and absolute-zero requirements', () => {
  for (const pressureMpa of [0, -1]) assert.match(gasPropertyConditionIssues({ pressureMpa, temperatureC: 20 })[0], /绝对压力.*大于 0/)
  for (const temperatureC of [-273.15, -300]) assert.match(gasPropertyConditionIssues({ pressureMpa: 5, temperatureC })[0], /绝对零度/)
  assert.equal(gasPropertyConditionIssues({ pressureMpa: 0, temperatureC: -273.15 }).length, 2)
})

test('missing PVT selection and unknown calculation method have actionable messages', () => {
  const issues = gasPropertyInputIssues({ pvtId: null, method: 'unknown', pressureMpa: 5, temperatureC: 0, compositionIssue: '请选择PVT' })
  assert.equal(issues.length, 2)
  assert.match(issues[0], /PVT 模型页保存/)
  assert.match(issues[1], /PR.*SRK.*BWRS/)
})

const model = () => ({ pvtId: 10, method: 'PR', points: {
  z: { pressureMpa: 8, temperatureC: 35, result: { z: .91 }, resultMark: '' },
  cp: { pressureMpa: 5, temperatureC: 50, result: { cpJkgK: 2345 }, resultMark: '' }
} })
function calculatedModel(method = 'PR', revision = 3) {
  const value = model(); value.method = method
  for (const kind of ['z', 'cp']) value.points[kind].resultMark = gasPropertyResultStamp(value, kind, revision)
  return value
}

test('rounded PVT fractions accepted by the backend remain calculable in the page', () => {
  assert.equal(inspectGasComposition(composition(.1000005)).issue, '')
  assert.equal(inspectGasComposition(composition(.0999995)).issue, '')
  assert.equal(inspectGasComposition(composition(.100002)).issue, 'total')
  assert.equal(inspectGasComposition(composition(.099998)).issue, 'total')
})

test('invalid PVT composition cannot pass by having a total of one', () => {
  assert.equal(inspectGasComposition([{ code: 'CH4', moleFraction: 1 }, { code: 'N2', moleFraction: null }]).issue, 'fraction')
  assert.equal(inspectGasComposition([{ code: 'CH4', moleFraction: .9 }, { code: 'CH4', moleFraction: .1 }]).issue, 'duplicate')
  assert.equal(inspectGasComposition([{ code: 'CH4', moleFraction: 1.1 }, { code: 'N2', moleFraction: -.1 }]).issue, 'fraction')
  assert.equal(inspectGasComposition([{ code: 'unknown', moleFraction: 1 }], [{ code: 'CH4' }]).issue, 'unsupported')
})

test('each result depends on its own pressure/temperature but both depend on the shared model and composition', () => {
  const value = calculatedModel(), before = structuredClone(value)
  value.points.cp.temperatureC = 65
  assert.equal(value.points.z.resultMark, gasPropertyResultStamp(value, 'z', 3))
  assert.notEqual(value.points.cp.resultMark, gasPropertyResultStamp(value, 'cp', 3))
  for (const changed of [{ ...before, method: 'SRK' }, { ...before, pvtId: 11 }]) {
    for (const kind of ['z', 'cp']) assert.notEqual(before.points[kind].resultMark, gasPropertyResultStamp(changed, kind, 3))
  }
  for (const kind of ['z', 'cp']) assert.notEqual(before.points[kind].resultMark, gasPropertyResultStamp(before, kind, 4))
})

test('saving Z preserves unfinished Cp work and takes the canonical saved Z result', () => {
  const draft = calculatedModel(), saved = calculatedModel()
  draft.points.cp.pressureMpa = 6.5
  saved.points.z.result.z = .88
  const original = structuredClone(draft)
  const next = { ...saved, points: reconcileGasPropertyPoints(saved.points, draft.points, 'z') }
  assert.equal(next.points.z.result.z, .88)
  assert.equal(next.points.cp.pressureMpa, 6.5)
  assert.equal(next.points.cp.temperatureC, 50)
  assert.notEqual(next.points.cp.resultMark, gasPropertyResultStamp(next, 'cp', 3))
  assert.deepEqual(draft, original)
  assert.equal(gasPropertyInputs(next).points.cp.pressureMpa, 6.5)
})

test('saving a shared method change keeps the other preview visibly stale and unsaved', () => {
  const draft = calculatedModel(), saved = calculatedModel('BWRS')
  draft.method = 'BWRS'
  saved.points.cp.result = null; saved.points.cp.resultMark = ''
  const next = { ...saved, points: reconcileGasPropertyPoints(saved.points, draft.points, 'z') }
  assert.equal(next.points.cp.result.cpJkgK, 2345)
  assert.notEqual(next.points.cp.resultMark, gasPropertyResultStamp(next, 'cp', 3))
  assert.equal(gasPropertyResultUnsaved(next.points.cp, saved.points.cp.resultMark), true)
})

test('a freshly calculated Cp preview survives saving Z without being misreported as saved', () => {
  const draft = calculatedModel('SRK'), saved = calculatedModel('SRK')
  saved.points.cp.result = null; saved.points.cp.resultMark = ''
  const next = { ...saved, points: reconcileGasPropertyPoints(saved.points, draft.points, 'z') }
  assert.equal(next.points.cp.resultMark, gasPropertyResultStamp(next, 'cp', 3))
  assert.equal(gasPropertyResultUnsaved(next.points.cp, saved.points.cp.resultMark), true)
  assert.equal(gasPropertyResultUnsaved(next.points.z, saved.points.z.resultMark), false)
})

test('saving Cp applies the same independent-draft rules to Z', () => {
  const draft = calculatedModel(), saved = calculatedModel()
  draft.points.z.temperatureC = 70
  saved.points.cp.result.cpJkgK = 2400
  const next = { ...saved, points: reconcileGasPropertyPoints(saved.points, draft.points, 'cp') }
  assert.equal(next.points.z.temperatureC, 70)
  assert.equal(next.points.cp.result.cpJkgK, 2400)
})

test('switching wells invalidates a late calculation, save and PVT response', async () => {
  const scope = createGasPropertyRequestScope()
  scope.open({ projectId: 1, gasReservoirId: 2, wellName: 'A' })
  const oldCalculation = scope.capture(), oldSource = scope.source()
  let resolveOld, visibleResult = null
  const previous = new Promise(resolve => { resolveOld = resolve }).then(result => {
    if (scope.current(oldCalculation)) visibleResult = result
  })
  scope.open({ projectId: 1, gasReservoirId: 2, wellName: 'B' })
  const currentCalculation = scope.capture()
  if (scope.current(currentCalculation)) visibleResult = 'well B'
  resolveOld('well A'); await previous
  assert.equal(visibleResult, 'well B')
  assert.equal(scope.current(oldCalculation), false)
  assert.equal(scope.currentSource(oldSource), false)
})

test('reload and component unmount invalidate work even if the well name stays the same', () => {
  const scope = createGasPropertyRequestScope(), context = { projectId: 1, gasReservoirId: 2, wellName: 'A' }
  const beforeReload = scope.open(context)
  scope.open(context)
  assert.equal(scope.current(beforeReload), false)
  const beforeUnmount = scope.capture(); scope.invalidate()
  assert.equal(scope.current(beforeUnmount), false)
})

test('a slower prior PVT selection cannot overwrite the latest composition', () => {
  const scope = createGasPropertyRequestScope()
  scope.open({ projectId: 1, gasReservoirId: 2, wellName: 'A' })
  const first = scope.source(), second = scope.source()
  assert.equal(scope.currentSource(first), false)
  assert.equal(scope.currentSource(second), true)
})

test('draft keys normalize numeric context values but never cross well or reservoir', () => {
  const key = gasPropertyContextKey({ projectId: 1, gasReservoirId: 2, wellName: 'A' })
  assert.equal(key, gasPropertyContextKey({ wellName: 'A', gasReservoirId: '2', projectId: '1' }))
  assert.notEqual(key, gasPropertyContextKey({ projectId: 1, gasReservoirId: 2, wellName: 'B' }))
  assert.notEqual(key, gasPropertyContextKey({ projectId: 1, gasReservoirId: 3, wellName: 'A' }))
})

test('the pipeline PVT page replaces old mirrors and data-management refreshes cannot change it', () => {
  const source = { type: 'well-data-pvt-group', children: [{ id: 'pvt-10', type: 'well-data-pvt', pvtId: 10, label: 'PVT性质1' }] }
  const pipeline = { id: 'pipeline-A', type: 'pipeline-capacity', wellName: 'A', children: [{ id: 'legacy-pvt', section: 'pvt' }] }
  const well = { id: 'well-A', type: 'well', children: [{ type: 'data-management', children: [source] }, pipeline] }
  ensurePipelineNavigation(well.children)
  const z = findPipelinePageNode(well.children, 'A', 'temperature-z'), cp = findPipelinePageNode(well.children, 'A', 'temperature-cp')
  assert.equal(z.label, '压缩因子'); assert.equal(cp.label, '定压比热容')
  const pvt = pipeline.children.find(node => node.section === 'pvt')
  assert.equal(pvt.type, 'pipeline-capacity-page'); assert.equal(pvt.children.length, 0)
  assert.equal(findPipelinePageNode([well],'A','pvt'),pvt)
  source.children = [{ id: 'pvt-11', type: 'well-data-pvt', pvtId: 11, label: '更新后的PVT' }]
  ensurePipelineNavigation([well]); ensurePipelineNavigation([well])
  assert.equal(findPipelinePageNode([well], 'A', 'temperature-z', true), z)
  assert.equal(findPipelinePageNode([well], 'A', 'temperature-cp', true), cp)
  assert.equal(pipeline.children.find(node => node.section === 'temperature').children.length, 6)
  assert.equal(pvt.children.length, 0); assert.equal(pvt.lazy, undefined)
  assert.equal(well.expanded, true); assert.equal(pipeline.expanded, true)
  assert.equal(resolvePipelinePage('temperature-z'), 'temperature-z'); assert.equal(resolvePipelinePage('temperature-cp'), 'temperature-cp')
})
