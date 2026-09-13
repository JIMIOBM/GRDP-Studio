import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const source = readFileSync(new URL('../views/SingleWellProductivityInterface.vue', import.meta.url), 'utf8')
const inputNames = ['coefficientFormationPressure', 'coefficientFormationTemperature',
  'productivityCoefficientC', 'productivityExponentN', 'correctedCoefficientC',
  'correctedExponentN', 'fittedFormationPressure', 'fittedFlowRate', 'openFlowRate']
function workspace() {
  const calls = []
  const deps = Object.fromEntries(inputNames.map(name => [name, { value: 123 }]))
  Object.assign(deps, {
    activeCoefficientId: { value: 2 }, coefficientWorkspaceKey: { value: 0 },
    operationType: { value: 'injection' },
    MODULES: [{ name: '产能系数', methods: ['二项式', '指数式'] }, { name: '理论计算', methods: ['稳定流'] }],
    activeModule: { value: '产能系数' }, activeMethod: { value: '指数式' },
    activeContentTab: { value: 'chart' }, activeStableId: { value: null },
    isOwnedPressureMethod: { value: false }, autoCalculateStable: { value: false },
    selectedWellName: { value: 'A1-3' }, PROJECT_ID: 7, GAS_RESERVOIR_ID: 4,
    loadPvtOptions: () => calls.push('reload-pvt'),
    router: { replace: location => calls.push(location) }
  })
  const reset = source.slice(source.indexOf('const resetCoefficientWorkspace ='), source.indexOf('const restoreCoefficient ='))
  const select = source.slice(source.indexOf('const selectModule ='), source.indexOf('const loadModifiedIsochronalNodes ='))
  const state = new Function(...Object.keys(deps), `${reset}\n${select}\nreturn { resetCoefficientWorkspace, selectModule }`)(...Object.values(deps))
  return { deps, calls, ...state }
}
for (const method of ['二项式', '指数式']) {
  test(`顶部进入${method}清空父级输入并重建独立新方案`, () => {
    const w = workspace()
    w.selectModule('产能系数', method)
    for (const name of inputNames) assert.equal(w.deps[name].value, '', name)
    assert.equal(w.deps.activeCoefficientId.value, null)
    assert.equal(w.deps.operationType.value, 'production')
    assert.equal(w.deps.activeMethod.value, method)
    assert.equal(w.deps.coefficientWorkspaceKey.value, 1)
    assert.equal(w.calls[0], 'reload-pvt')
    assert.ok(!('coefficientId' in w.calls[1].query))
    // 同方法再次从顶部进入，仍是新方案，A/B等子组件状态也必须重建。
    w.deps.coefficientFormationPressure.value = 56
    w.selectModule('产能系数', method)
    assert.equal(w.deps.coefficientFormationPressure.value, '')
    assert.equal(w.deps.coefficientWorkspaceKey.value, 2)
  })
}
test('指数式和二项式来回切换不恢复上一方法的输入', () => {
  const w = workspace()
  for (const method of ['二项式', '指数式', '二项式']) {
    for (const name of inputNames) w.deps[name].value = 99
    w.selectModule('产能系数', method)
    for (const name of inputNames) assert.equal(w.deps[name].value, '')
  }
  assert.equal(w.deps.coefficientWorkspaceKey.value, 3)
})
test('切换其他模块不执行产能系数重置或PVT重新加载', () => {
  const w = workspace()
  w.selectModule('理论计算', '稳定流')
  assert.equal(w.deps.coefficientFormationPressure.value, 123)
  assert.equal(w.deps.coefficientWorkspaceKey.value, 0)
  assert.equal(w.calls.length, 1)
})
test('打开保存记录前清空旧参数，恢复后只含该记录参数和PVT快照', () => {
  const w = workspace()
  w.resetCoefficientWorkspace()
  const deps = { ...w.deps, selectedPvtDetail: { value: null },
    selectedPvtTable: { value: '' }, databasePvtRecords: { value: [] } }
  const restore = source.slice(source.indexOf('const restoreCoefficient ='), source.indexOf('const coefficientSaved ='))
  const fn = new Function(...Object.keys(deps), `let pvtDetailRequest = 0; ${restore}; return restoreCoefficient`)(...Object.values(deps))
  fn({ parameters: { pressure: 42, temperature: 80, pointPressure: 30, pointRate: 5 },
    operation: 'injection', pvtId: 8, pvtSnapshot: { pvtId: 8, pvtName: '历史PVT', gasResultRows: [[1,2]] } })
  assert.equal(deps.coefficientFormationPressure.value, 42)
  assert.equal(deps.productivityCoefficientC.value, '')
  assert.equal(deps.fittedFlowRate.value, 5)
  assert.equal(deps.operationType.value, 'injection')
  assert.equal(deps.selectedPvtDetail.value.pvtName, '历史PVT')
  assert.ok(source.includes('${selectedWellName}-${activeMethod}-${coefficientWorkspaceKey}'))
  const sidebar = source.slice(source.indexOf('const handleSidebarSelect ='), source.indexOf('  if (selectWorkspaceNodeScope(node)', source.indexOf('const handleSidebarSelect =')))
  assert.ok(sidebar.indexOf('resetCoefficientWorkspace()') < sidebar.indexOf('activeCoefficientId.value = Number(node.coefficientId)'))
})
