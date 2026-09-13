import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { ensureCoefficientTree, applyCoefficientRecords, COEFFICIENT_GROUP, COEFFICIENT_METHOD, COEFFICIENT_RECORD, coefficientLocation } from './productivityCoefficientTree.js'

test('刷新初始化两种方法目录；重复加载不重复，其他业务记录保留', () => {
  const other = { label: '产能试井', children: [{ id: 'test-1' }] }
  const tree = [{ id: 'g-well', children: [{ id: 'well-1', wellName: 'A1-3', children: [
    { type: 'single-well-productivity', children: [other] }
  ] }] }]
  ensureCoefficientTree(tree); ensureCoefficientTree(tree)
  const groups = tree[0].children[0].children[0].children
  assert.equal(groups.length, 2)
  assert.equal(groups[0], other)
  const group = groups.find(n => n.type === COEFFICIENT_GROUP)
  assert.deepEqual(group.children.map(n => n.label), ['二项式','指数式'])
  const scope = { projectId:7, gasReservoirId:4, wellName:'A1-3' }
  const records = [{ id: 12, name:'指数式1', method:'指数式' }, { id:13, name:'二项式1', method:'二项式' }]
  applyCoefficientRecords(tree, scope, records, true)
  applyCoefficientRecords(tree, scope, records)
  assert.equal(group.children[0].children.length, 1)
  assert.equal(group.children[1].children.length, 1)
  assert.deepEqual(coefficientLocation(group.children[1].children[0]).query,
    { module:'产能系数', method:'指数式', well:'A1-3', projectId:7, gasReservoirId:4, coefficientId:12 })
})

for (const page of ['IprInterface', 'SingleWellProductivityInterface']) {
  test(`${page}点击产能系数目录不改变右侧，只有具体记录才导航`, async () => {
    const source = readFileSync(new URL(`../views/${page}.vue`, import.meta.url), 'utf8')
    const ipr = page === 'IprInterface'
    const start = source.indexOf(ipr ? 'const handleSelect = async' : 'const handleSidebarSelect = async')
    const end = source.indexOf(ipr ? '  const scope = selectWorkspaceNodeScope' : "  if (selectWorkspaceNodeScope(node)", start)
    const prefix = source.slice(start, end)
    const calls = []
    const activeModule = { value: '原页面' }, activeMethod = { value: '原方法' }
    const deps = { COEFFICIENT_GROUP, COEFFICIENT_METHOD, COEFFICIENT_RECORD, coefficientLocation,
      closeTreeContextMenu() {}, PROJECT_ID: 7, GAS_RESERVOIR_ID: 4,
      router: { push: async location => calls.push(location), replace: async location => calls.push(location) },
      selectedWellName: { value: 'A1-3' }, selectWell: async () => { throw new Error('目录不应切井') },
      resetCoefficientWorkspace() {},
      activeModule, activeMethod, activeCoefficientId: { value: null }, workspaceActiveNodeId: { value: 'old-record' }
    }
    const handle = new Function(...Object.keys(deps), `${prefix}\nthrow new Error('不应进入其他页面分支'); }; return ${ipr ? 'handleSelect' : 'handleSidebarSelect'}`)(...Object.values(deps))
    for (const type of [COEFFICIENT_GROUP, COEFFICIENT_METHOD]) await handle({ type, wellName: '另一口井' })
    assert.deepEqual(calls, [])
    assert.equal(activeModule.value, '原页面')
    assert.equal(activeMethod.value, '原方法')
    assert.equal(deps.workspaceActiveNodeId.value, 'old-record')
    await handle({ id: 'record-12', type: COEFFICIENT_RECORD, method: '指数式', coefficientId: 12, wellName: 'A1-3' })
    assert.equal(calls.length, 1)
    assert.equal(calls[0].query.coefficientId, 12)
    assert.equal(calls[0].query.method, '指数式')
  })
}
