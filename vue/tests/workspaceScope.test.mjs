import test, { beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import {
  workspaceTreeData, workspaceRibbonScope, workspaceSelectedReservoir, workspaceSelectedWellName,
  workspaceActiveNodeId, ensureWorkspaceReservoir, selectWorkspaceNodeScope,
  getReservoirCommandLocation, resolveReservoirLocation, activateReservoirCommand
} from '../src/utils/workspaceTreeState.js'

beforeEach(() => {
  workspaceTreeData.value = [
    { id: 'g-well', children: [] },
    { id: 'g-reservoir', scope: 'reservoir', children: [] }
  ]
  workspaceRibbonScope.value = 'well'
  workspaceSelectedReservoir.value = null
  workspaceSelectedWellName.value = 'A1-2H'
  workspaceActiveNodeId.value = 'well-record'
})

test('首次选择库目录即切换范围，保留右侧使用的井名，不执行命令', () => {
  const reservoir = ensureWorkspaceReservoir({ projectId: 7, gasReservoirId: 4 })
  const directory = reservoir.children[0]
  assert.equal(selectWorkspaceNodeScope(directory), 'reservoir')
  assert.equal(workspaceRibbonScope.value, 'reservoir')
  assert.equal(workspaceSelectedWellName.value, 'A1-2H')
  assert.equal(workspaceActiveNodeId.value, 'well-record')
  assert.equal(directory.command, undefined)
  assert.equal(getReservoirCommandLocation({ group: '库容设计', name: '运行压力' }), null)
})

test('井根、井节点、井的功能目录均能直接切回井菜单，库群不串范围', () => {
  for (const node of [{ id: 'g-well' }, { type: 2, label: 'A1-3' }, { wellName: 'A1-3', type: 'single-well-productivity' }]) {
    workspaceRibbonScope.value = 'reservoir'
    assert.equal(selectWorkspaceNodeScope(node), 'well')
    assert.equal(workspaceRibbonScope.value, 'well')
    assert.equal(workspaceSelectedWellName.value, 'A1-2H')
  }
  assert.equal(selectWorkspaceNodeScope({ id: 'g-group' }), null)
})

test('重复初始化不重建库目录，跨页面保留展开与库名称', () => {
  ensureWorkspaceReservoir({ projectId: 7, gasReservoirId: 4 })
  const reservoir = workspaceSelectedReservoir.value
  reservoir.children[0].expanded = true
  ensureWorkspaceReservoir({ projectId: 7, gasReservoirId: 4, label: '测试库' })
  assert.equal(workspaceTreeData.value[1].children.length, 1)
  assert.equal(reservoir.label, '测试库')
  assert.equal(reservoir.children[0].expanded, true)
  assert.equal(reservoir.children[0].reservoirName, '测试库')
})

test('库命令使用库级地址，能刷新恢复并高亮完整目录', () => {
  ensureWorkspaceReservoir({ projectId: 7, gasReservoirId: 4 })
  const location = getReservoirCommandLocation({ group: '库存评估', parent: '地层压力', name: '测试法' })
  assert.equal(location.name, 'IprInterface')
  assert.equal(location.query.scope, 'reservoir')
  assert.equal(location.query.well, undefined)
  const target = resolveReservoirLocation(Object.fromEntries(Object.entries(location.query).map(([key, value]) => [key, String(value)])))
  activateReservoirCommand(target)
  assert.equal(workspaceRibbonScope.value, 'reservoir')
  assert.equal(workspaceTreeData.value[1].expanded, true)
  assert.equal(target.reservoir.expanded, true)
  assert.ok(decodeURIComponent(workspaceActiveNodeId.value).endsWith('/库存评估/地层压力/测试法'))
  assert.equal(workspaceSelectedWellName.value, 'A1-2H')
})

test('禁用、单井同名命令和错误库ID不能误入库功能', () => {
  ensureWorkspaceReservoir({ projectId: 7, gasReservoirId: 4 })
  assert.equal(getReservoirCommandLocation({ group: '井控库存', name: '物质平衡' }), null)
  assert.equal(getReservoirCommandLocation({ group: '库存评估', parent: '图版法', name: 'Wattenbarger' }), null)
  assert.equal(selectWorkspaceNodeScope({ scope: 'reservoir', disabled: true }), null)
  const location = getReservoirCommandLocation({ group: '库容设计', name: '孔隙体积' })
  assert.equal(resolveReservoirLocation({ ...location.query, gasReservoirId: 999 }), null)
  assert.equal(resolveReservoirLocation({ ...location.query, scope: 'well' }), null)
})

test('选择另一库后，同名功能地址与高亮均属于新库', () => {
  ensureWorkspaceReservoir({ projectId: 7, gasReservoirId: 4 })
  const second = ensureWorkspaceReservoir({ projectId: 8, gasReservoirId: 5 })
  selectWorkspaceNodeScope(second.children[0])
  const location = getReservoirCommandLocation({ group: '库容设计', name: '孔隙体积' })
  assert.equal(location.query.projectId, 8)
  assert.equal(location.query.gasReservoirId, 5)
  activateReservoirCommand(resolveReservoirLocation(location.query))
  assert.ok(workspaceActiveNodeId.value.startsWith('reservoir-8-5/'))
  assert.equal(workspaceTreeData.value[1].children[0].expanded, undefined)
})
