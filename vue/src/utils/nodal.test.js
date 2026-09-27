import { test } from 'node:test'
import assert from 'node:assert/strict'
import { ensureNodalNavigation, nodalCommandTarget, applyNodalRecords, upsertNodalRecord, loadNodalRecords, deleteNodalRecord, NODAL_RECORD } from './nodalNavigation.js'
import { nodalChartOption, nodalEnvelope, nodalConstraintBoundary } from './nodalChart.js'
test('nodal directory is lazy and does not create productivity or pressure submodules', () => {
  const nodes = [{ id: 'A-allocation', type: 'production-allocation', wellName: 'A', children: [] }]
  ensureNodalNavigation(nodes); ensureNodalNavigation(nodes)
  assert.equal(nodes[0].children.length, 1)
  const page = nodes[0].children[0]
  assert.equal(page.children.length, 0)
  assert.equal(page.lazy, true)
  assert.equal(page.type, nodalCommandTarget({ group: '配产配注', name: '节点分析' }, 'A').type)
  assert.equal(nodalCommandTarget({ group: '配产配注', name: '节点分析' }).wellName, '')
  assert.equal(nodalCommandTarget({ group: '单井产能', name: '节点分析' }), null)
})
test('saved records are scoped by well, retain same-name schemes by ID, and stale loads cannot erase a save', async () => {
  const tree = ['A','B'].map(wellName => ({ id: wellName, wellName, children: [{ id: wellName+'-allocation', type: 'production-allocation', children: [] }] }))
  ensureNodalNavigation(tree)
  const scope = { projectId: 1, gasReservoirId: 2, wellName: 'A' }
  const parent = applyNodalRecords(tree, scope, [{ id: 1, name: '方案一', operationMode: 'production' }])
  let complete
  const pending = loadNodalRecords(tree, scope, () => new Promise(resolve => { complete = resolve }))
  const saved = upsertNodalRecord(tree, scope, { id: 2, name: '方案一', operationMode: 'injection' })
  complete([]); await pending
  assert.equal(parent.children.length, 2)
  assert.equal(saved.type, NODAL_RECORD)
  assert.equal(saved.nodalId, 2)
  assert.equal(saved.wellName, 'A')
  assert.equal(parent.expanded, true)
  assert.equal(tree[1].children[0].children[0].children.length, 0)
  upsertNodalRecord(tree, scope, { id: 2, name: '方案二', operationMode: 'injection' })
  assert.equal(parent.children.length, 2)
  assert.equal(parent.children[0].label, '方案二')
  applyNodalRecords(tree, scope, [{ id: 1, name: '方案一', operationMode: 'production' }])
  assert.equal(parent.children.length, 1)
})
test('deletion preserves siblings and directory, rejects failure, and invalidates stale history', async () => {
  const tree = [{ id: 'A', wellName: 'A', type: 'production-allocation', children: [] }]
  const scope = { projectId: 1, gasReservoirId: 2, wellName: 'A' }
  const rows = [{ id: 1, name: '同名方案' }, { id: 2, name: '同名方案' }]
  const parent = applyNodalRecords(tree, scope, rows)
  const target = parent.children[0]
  await assert.rejects(deleteNodalRecord(tree, target, async () => { throw new Error('服务器错误') }))
  assert.equal(parent.children.length, 2)
  let complete
  const pending = loadNodalRecords(tree, scope, () => new Promise(resolve => { complete = resolve }))
  await deleteNodalRecord(tree, target, async (id, actualScope) => {
    assert.equal(id, 1); assert.deepEqual(actualScope, scope)
  })
  complete(rows); await pending
  assert.deepEqual(parent.children.map(n => n.nodalId), [2])
  await deleteNodalRecord(tree, parent.children[0], async () => {})
  assert.equal(tree[0].children[0], parent)
  assert.equal(parent.children.length, 0)
  await assert.rejects(deleteNodalRecord(tree, { ...target, projectId: null }, async () => assert.fail()))
})

test('flow roles reverse for injection and invalid wellbore points remain gaps', () => {
  const result = { wellboreCurve: [{rate:0,pressure:null},{rate:1,pressure:2}], scenarios: [
    { reservoirPressure:10, formation:[{rate:0,pressure:10}], candidates:[], intervals:[], intersections:[{rate:1,pressure:9}], maximum:null }
  ] }
  const prod = nodalChartOption(result, 'production'), inj = nodalChartOption(result, 'injection')
  assert.equal(prod.series[0].name,'井筒流出曲线'); assert.equal(inj.series[0].name,'井筒流入曲线')
  assert.match(inj.series[1].name,/地层流出/); assert.equal(inj.series[0].data[0][1],null)
  assert.equal(inj.series[0].connectNulls,false)
  assert.equal(inj.series.some(s => s.name.includes('最大可行点')),false)
})
test('envelope requires connected verified slices and critical curves interpolate signed margins', () => {
  const point = (rate, pressure) => ({ rate, pressure })
  const slice = pressure => ({ reservoirPressure: pressure, status: 'SUCCESS',
    formation: [point(0,pressure),point(2,pressure-1)], intervals: [{from:point(1,pressure-.5),to:point(2,pressure-1)}] })
  const a=slice(10), b=slice(12)
  assert.equal(nodalEnvelope({regionScenarios:[a,b]}).length,1)
  assert.equal(nodalEnvelope({scenarios:[a,b]}).length,0)
  b.status='PARTIAL_RESULT'
  assert.equal(nodalEnvelope({regionScenarios:[a,b]}).length,1)
  b.intervals=[]
  assert.equal(nodalEnvelope({regionScenarios:[a,b]}).length,0)
  const candidates=[{rate:1,pressure:10,checks:[{code:'erosion',margin:1,status:'PASS'}]},
    {rate:3,pressure:8,checks:[{code:'erosion',margin:-1,status:'FAIL'}]}]
  assert.deepEqual(nodalConstraintBoundary({candidates},'erosion'),[[2,9]])
  assert.deepEqual(nodalConstraintBoundary({candidates},'liquidLoading'),[])
})
