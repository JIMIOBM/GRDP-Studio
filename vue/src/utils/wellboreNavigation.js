const wellborePages = [
  { type: 'wellbore-structure', label: '井身结构' },
  { type: 'wellbore-pvt-group', label: 'PVT模型' },
  { type: 'wellbore-temperature', label: '温度模型' },
  { type: 'wellbore-boundary', label: '边界条件' },
  { type: 'wellbore-liquid-loading', label: '井筒积液' },
  { type: 'wellbore-hydrate', label: '水合物' },
  { type: 'wellbore-erosion', label: '冲蚀' },
  { type: 'wellbore-pressure-group', label: '压力折算' }
]
const pressurePages = [
  { type: 'wellbore-pressure', label: '折算方法' },
  { type: 'wellbore-pressure-comparison', label: '结果对比' }
]
const hiddenWellborePageTypes = new Set(['wellbore-pvt-group'])

export function createDefaultWellboreNodes(wellName, wellId) {
  return wellborePages.filter(page => !hiddenWellborePageTypes.has(page.type)).map(page => ({
    ...page,
    id: page.type === 'wellbore-pvt-group'
      ? `${wellId || wellName}-${page.type}` : `${page.type}-${wellName}`,
    wellName,
    defaultExpanded: false,
    ...(page.type === 'wellbore-pvt-group' ? { pvtEntry: 'wellbore-capacity' } : {}),
    children: page.type === 'wellbore-pressure-group'
      ? pressurePages.map(child => ({ ...child, id: `${child.type}-${wellName}`, wellName, children: [] }))
      : []
  }))
}

// 补齐共享目录，保留已有节点、PVT记录和用户展开状态。
export function ensureWellboreNavigation(nodes, inheritedWellName = '') {
  for (const node of nodes || []) {
    const wellName = node.wellName || (node.type === 2 ? node.label : '') || inheritedWellName
    if (node.type !== 'wellbore-capacity') {
      ensureWellboreNavigation(node.children, wellName)
      continue
    }
    const existing = node.children || []
    const defaults = createDefaultWellboreNodes(wellName, String(node.id || wellName || '').replace(/-wellbore-capacity$/, ''))
    const children = defaults.map(page => existing.find(child => child.type === page.type) || page)
    // 冲蚀是固定功能入口；旧目录的占位名称/禁用状态不能阻止打开页面。
    const erosion = children.find(page => page.type === 'wellbore-erosion')
    if (erosion.label !== '冲蚀') erosion.label = '冲蚀'
    if (erosion.wellName !== wellName) erosion.wellName = wellName
    if (erosion.disabled) erosion.disabled = false
    const pressureGroup = children.find(child => child.type === 'wellbore-pressure-group')
    const pressureChildren = pressurePages.map(page => {
      const child = existing.find(child => child.type === page.type)
        || pressureGroup.children?.find(child => child.type === page.type)
        || defaults.at(-1).children.find(child => child.type === page.type)
      if (child.label !== page.label) child.label = page.label
      return child
    })
    const extraPressureChildren = (pressureGroup.children || []).filter(child => !pressurePages.some(page => page.type === child.type))
    const nextPressureChildren = [...pressureChildren, ...extraPressureChildren]
    if (pressureGroup.children?.length !== nextPressureChildren.length || nextPressureChildren.some((child, i) => pressureGroup.children[i] !== child)) {
      pressureGroup.children = nextPressureChildren
    }
    const extras = existing.filter(child => !wellborePages.some(page => page.type === child.type) && !pressurePages.some(page => page.type === child.type))
    const nextChildren = [...children, ...extras]
    if (existing.length !== nextChildren.length || nextChildren.some((child, i) => existing[i] !== child)) node.children = nextChildren
  }
}

// 两个工作台共用入口判定；调用方必须传入当前目录选中的井，不能回退到右侧旧页面。
export function resolveErosionCommandTarget(command, selectedWellName = '') {
  if (command?.group !== '井筒能力' || command?.name !== '冲蚀') return null
  const forwardedWell = typeof command.wellName === 'string' ? command.wellName.trim() : ''
  return { type: 'wellbore-erosion', wellName: forwardedWell || String(selectedWellName || '').trim() }
}
