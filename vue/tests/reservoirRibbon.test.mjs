import test from 'node:test'
import assert from 'node:assert/strict'
import {
  reservoirRibbonGroups,
  resolveReservoirCommand,
  buildReservoirTreeNodes
} from '../src/config/reservoirRibbon.js'

// 从需求层级独立列出期望项，配置漏项或父级漂移都会导致测试失败。
const expectedPaths = [
  ['库容设计', '孔隙体积'],
  ...['上限压力', '下限压力'].map(name => ['库容设计', '运行压力', name]),
  ...['库容量', '工作气量', '垫气量', '补充垫气量'].map(name => ['库容设计', '库容参数', name]),
  ['库存评估', '诊断曲线'],
  ...['测试法', '压力梯度'].map(name => ['库存评估', '地层压力', name]),
  ['库存评估', '物质平衡法', '物质平衡'],
  ['库存评估', '水侵动态分析', '水侵分析'],
  ...['Blasingame', 'Transient', 'AG', 'NPI'].map(name => ['库存评估', '图版法', name]),
  ...['阶段标定', '周期评估', '实时预测'].map(name => ['库存评估', '指标对比', name]),
  ['库存评估', '主控因素分析'],
  ...['微观损耗', '逸散性损耗'].map(name => ['损耗评价', '地质损耗', name]),
  ...['井筒损耗', '地面损耗'].flatMap(parent =>
    ['直接输入', '公式法'].map(name => ['损耗评价', parent, name])),
  ['产能评价', '敏感性分析'],
  ...['多周期', '多方法', '注采对比'].map(name => ['产能评价', '产能对比', name]),
  ['产能评价', '相关性分析'],
  ['井筒折算', '井间对比'],
  ['井筒折算', '相关性分析'],
  ['地面管网', '管网拓扑结构'],
  ['地面管网', '相关性分析'],
  ...['单目标', '多目标'].map(name => ['协同预测', '一体化耦合优化', name]),
  ...['目标函数', '约束条件', '方案生成', '方案比选'].map(name => ['协同预测', '多周期预测', name]),
  ['协同预测', '对比分析']
]

const flatten = node => [node, ...(node.children || []).flatMap(flatten)]
const sampleContext = { id: 'reservoir-4', label: '示例库', projectId: 7, gasReservoirId: 4 }

test('库菜单包含七个有序分组，并使用现有 Ribbon 支持的列结构', () => {
  assert.deepEqual(reservoirRibbonGroups.map(group => group.title), [
    '库容设计', '库存评估', '损耗评价', '产能评价', '井筒折算', '地面管网', '协同预测'
  ])
  for (const group of reservoirRibbonGroups) {
    assert.ok(group.columns.length > 0)
    for (const column of group.columns) {
      assert.ok(['large', 'checks'].includes(column.type))
      if (column.type === 'checks') assert.ok(column.items.length > 0)
      else assert.equal(typeof column.label, 'string')
      if (column.dropdown) assert.ok(column.dropdownItems.length > 0)
    }
  }
})

test('所有有效叶子均能用完整层级解析，且配置与需求无遗漏', () => {
  for (const path of expectedPaths) {
    const input = { group: path[0], name: path.at(-1), parent: path.length === 3 ? path[1] : '' }
    assert.deepEqual(resolveReservoirCommand(input), { ...input, path })
  }
  const actualPaths = flatten(buildReservoirTreeNodes(sampleContext))
    .filter(node => node.command)
    .map(node => node.command.path)
  assert.equal(actualPaths.length, 42)
  assert.deepEqual(actualPaths, expectedPaths)
})

test('重复名称按分组和父级隔离，不把目录当作叶子命令', () => {
  const wellLoss = resolveReservoirCommand({ group: '损耗评价', parent: '井筒损耗', name: '公式法' })
  const surfaceLoss = resolveReservoirCommand({ group: '损耗评价', parent: '地面损耗', name: '公式法' })
  assert.notDeepEqual(wellLoss.path, surfaceLoss.path)
  const correlations = ['产能评价', '井筒折算', '地面管网'].map(group =>
    resolveReservoirCommand({ group, name: '相关性分析' }))
  assert.equal(new Set(correlations.map(command => JSON.stringify(command.path))).size, 3)
  for (const input of [
    { group: '损耗评价', name: '公式法' },
    { group: '损耗评价', parent: '地质损耗', name: '公式法' },
    { group: '井筒能力', name: '相关性分析' },
    { group: '库容设计', name: '运行压力' },
    { group: '协同预测', name: '多周期预测' },
    { group: '库容设计', parent: '不存在', name: '孔隙体积' },
    {}, null
  ]) assert.equal(resolveReservoirCommand(input), null)
})

test('Wattenbarger 可见但禁用，不能生成可执行命令', () => {
  const chart = reservoirRibbonGroups.find(group => group.title === '库存评估')
    .columns.find(column => column.label === '图版法')
  assert.deepEqual(chart.dropdownItems.find(item => typeof item === 'object'), {
    label: 'Wattenbarger', disabled: true
  })
  assert.equal(resolveReservoirCommand({ group: '库存评估', parent: '图版法', name: 'Wattenbarger' }), null)
  const disabledNodes = flatten(buildReservoirTreeNodes(sampleContext)).filter(node => node.disabled)
  assert.equal(disabledNodes.length, 1)
  assert.equal(disabledNodes[0].label, 'Wattenbarger')
  assert.equal(disabledNodes[0].command, null)
})

test('库树所有节点带正确上下文，目录不自动执行、节点 ID 唯一且与其他库隔离', () => {
  const root = buildReservoirTreeNodes(sampleContext)
  assert.equal(root.id, sampleContext.id)
  assert.equal(root.type, 'reservoir')
  assert.equal(root.label, '示例库')
  const nodes = flatten(root)
  for (const node of nodes) {
    assert.equal(node.scope, 'reservoir')
    assert.equal(node.reservoirName, '示例库')
    assert.equal(node.projectId, 7)
    assert.equal(node.gasReservoirId, 4)
    assert.equal(node.expanded, undefined)
    if (node.type !== 'reservoir-command') assert.equal(node.command, undefined)
    else assert.equal(node.children.length, 0)
  }
  const ids = new Set(nodes.map(node => node.id))
  assert.equal(ids.size, nodes.length)
  const other = buildReservoirTreeNodes({ id: 'reservoir-5', label: '另一个库', projectId: 7, gasReservoirId: 5 })
  assert.ok(flatten(other).every(node => !ids.has(node.id)))
  assert.equal(buildReservoirTreeNodes({ label: '默认 ID 库', projectId: 7, gasReservoirId: 4 }).id, 'reservoir:7:4')
})

test('解析结果和库树之间不共享可变命令数组', () => {
  const query = { group: '库容设计', name: '孔隙体积' }
  const first = resolveReservoirCommand(query)
  first.path.push('误修改')
  assert.deepEqual(resolveReservoirCommand(query).path, ['库容设计', '孔隙体积'])
  const treeA = buildReservoirTreeNodes(sampleContext)
  const treeB = buildReservoirTreeNodes(sampleContext)
  treeA.children[0].children[0].command.path.push('误修改')
  assert.deepEqual(treeB.children[0].children[0].command.path, ['库容设计', '孔隙体积'])
})
