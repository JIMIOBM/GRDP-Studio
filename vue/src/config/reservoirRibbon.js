/**
 * 库级功能菜单的唯一配置源：顶部 Ribbon 和左侧库目录共用，避免两边入口不一致。
 * 下拉父项只负责展开；只有叶子项才能解析为功能命令。
 */
export const reservoirRibbonGroups = [
  {
    title: '库容设计',
    columns: [
      { type: 'large', label: '孔隙体积' },
      { type: 'large', label: '运行压力', dropdown: true, dropdownItems: ['上限压力', '下限压力'] },
      { type: 'large', label: '库容参数', dropdown: true, dropdownItems: ['库容量', '工作气量', '垫气量', '补充垫气量'] }
    ]
  },
  {
    title: '库存评估',
    columns: [
      { type: 'large', label: '诊断曲线' },
      { type: 'large', label: '地层压力', dropdown: true, dropdownItems: ['测试法', '压力梯度'] },
      { type: 'large', label: '物质平衡法', dropdown: true, dropdownItems: ['物质平衡'] },
      { type: 'large', label: '水侵动态分析', dropdown: true, dropdownItems: ['水侵分析'] },
      { type: 'large', label: '主控因素分析' }
    ]
  },
  {
    title: '损耗评价',
    columns: [
      { type: 'large', label: '地质损耗', dropdown: true, dropdownItems: ['微观损耗', '逸散性损耗'] },
      // 直接输入和公式计算共用一个页面，由页面内的计算方式切换。
      { type: 'large', label: '井筒损耗' },
      { type: 'large', label: '地面损耗' }
    ]
  },
  {
    title: '产能评价',
    columns: [
      { type: 'large', label: '产能对比', dropdown: true, dropdownItems: ['多周期', '多方法', '注采对比'] }
    ]
  },
  {
    title: '井筒折算',
    columns: [{ type: 'checks', items: ['井间对比'] }]
  },
  {
    title: '地面管网',
    columns: [{ type: 'checks', items: ['管网拓扑结构', '相关性分析'] }]
  },
  {
    title: '协同预测',
    columns: [
      { type: 'large', label: '一体化耦合优化', dropdown: true, dropdownItems: ['单目标', '多目标'] },
      { type: 'large', label: '多周期预测', dropdown: true, dropdownItems: ['目标函数', '约束条件', '方案生成', '方案比选'] },
      { type: 'large', label: '对比分析' }
    ]
  }
]

const itemLabel = item => typeof item === 'string' ? item : item.label
const itemDisabled = item => typeof item === 'object' && item.disabled === true

const makeCommand = (group, name, parent = '') => ({
  group,
  name,
  parent,
  path: parent ? [group, parent, name] : [group, name]
})

const commandKey = ({ group, name, parent = '' }) => JSON.stringify([group, parent, name])

const commandIndex = new Map()
for (const group of reservoirRibbonGroups) {
  for (const column of group.columns) {
    const parent = column.dropdown ? column.label : ''
    const items = column.type === 'checks'
      ? column.items
      : column.dropdown ? column.dropdownItems : [column.label]
    for (const item of items) {
      if (itemDisabled(item)) continue
      const command = makeCommand(group.title, itemLabel(item), parent)
      commandIndex.set(commandKey(command), command)
    }
  }
}

/** 按完整层级查找，避免不同分组的“相关性分析”、不同损耗的“公式法”串入口。 */
export function resolveReservoirCommand(input = {}) {
  if (!input || typeof input !== 'object') return null
  const command = commandIndex.get(commandKey(input))
  return command ? { ...command, path: [...command.path] } : null
}

/** 为一个库生成目录。只设置目录层级，不自动选中、展开或调用计算接口。 */
export function buildReservoirTreeNodes({ id, label, projectId, gasReservoirId }) {
  const rootId = id ?? `reservoir:${projectId}:${gasReservoirId}`
  const metadata = { scope: 'reservoir', reservoirName: label, projectId, gasReservoirId }
  const nodeId = path => `${rootId}/${path.map(part => encodeURIComponent(part)).join('/')}`

  const makeLeaf = (group, item, parent = '') => {
    const name = itemLabel(item)
    const descriptor = makeCommand(group, name, parent)
    const disabled = itemDisabled(item)
    const isGeologicalLossMethod = group === '损耗评价'
      && ((parent === '地质损耗' && ['微观损耗', '逸散性损耗'].includes(name))
        || (!parent && ['井筒损耗', '地面损耗'].includes(name)))
    return {
      ...metadata,
      id: nodeId(descriptor.path),
      label: name,
      type: isGeologicalLossMethod ? 'reservoir-geological-loss-method' : 'reservoir-command',
      lazy: isGeologicalLossMethod,
      lossType: name === '微观损耗' ? 'microscopic'
        : name === '逸散性损耗' ? 'escape'
          : name === '井筒损耗' ? 'wellbore' : name === '地面损耗' ? 'surface' : undefined,
      disabled,
      command: disabled ? null : resolveReservoirCommand(descriptor),
      children: []
    }
  }

  return {
    ...metadata,
    id: rootId,
    label,
    type: 'reservoir',
    children: reservoirRibbonGroups.map(group => ({
      ...metadata,
      id: nodeId([group.title]),
      label: group.title,
      type: 'reservoir-directory',
      children: group.columns.flatMap(column => {
        if (column.type === 'checks') {
          return column.items.map(item => makeLeaf(group.title, item))
        }
        if (!column.dropdown) return [makeLeaf(group.title, column.label)]
        return [{
          ...metadata,
          id: nodeId([group.title, column.label]),
          label: column.label,
          type: 'reservoir-directory',
          children: column.dropdownItems.map(item => makeLeaf(group.title, item, column.label))
        }]
      })
    }))
  }
}
