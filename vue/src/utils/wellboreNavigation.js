// 两个工作台共用井筒目录；只补齐导航，不触发计算或覆盖已加载的记录。
export function createDefaultWellboreNodes(wellName, wellId) {
  const leaf = (type, label) => ({
    id: `${type}-${wellName}`, type, label, wellName, children: []
  })
  return [
    leaf('wellbore-structure', '井身结构'),
    {
      id: `${wellId || wellName}-wellbore-pvt-group`, type: 'wellbore-pvt-group',
      label: 'PVT模型', wellName, pvtEntry: 'wellbore-capacity',
      defaultExpanded: false, children: []
    },
    leaf('wellbore-temperature', '温度模型'),
    leaf('wellbore-boundary', '边界条件'),
    leaf('wellbore-liquid-loading', '井筒积液'),
    leaf('wellbore-hydrate', '水合物'),
    {
      ...leaf('wellbore-pressure-group', '压力折算'), defaultExpanded: false,
      children: [leaf('wellbore-pressure', '折算方法'), leaf('wellbore-pressure-comparison', '结果对比')]
    }
  ]
}

export function ensureWellboreNavigation(nodes) {
  for (const node of nodes || []) {
    if (node.type !== 'wellbore-capacity') {
      ensureWellboreNavigation(node.children)
      continue
    }
    const existing = node.children || []
    const wellId = String(node.id || '').replace(/-wellbore-capacity$/, '')
    const defaults = createDefaultWellboreNodes(node.wellName || '', wellId)
    const children = defaults.map(definition => {
      const child = existing.find(item => item.type === definition.type) || definition
      if (definition.type === 'wellbore-pressure-group') {
        const oldChildren = child === definition ? [] : (child.children || [])
        const pressureChildren = definition.children.map(item => {
          // 旧目录的折算方法可能直接挂在井筒能力下，迁移时保留其ID和状态。
          const match = oldChildren.find(old => old.type === item.type)
            || existing.find(old => old.type === item.type) || item
          if (match.label !== item.label) match.label = item.label
          return match
        })
        pressureChildren.push(...oldChildren.filter(item => !definition.children.some(next => next.type === item.type)))
        if (oldChildren.length !== pressureChildren.length || pressureChildren.some((item, i) => item !== oldChildren[i])) {
          child.children = pressureChildren
        }
      }
      return child
    })
    // 不丢弃其他模块增加的节点；仅移除已归入压力折算分组的旧平级入口。
    children.push(...existing.filter(item => !defaults.some(next => next.type === item.type)
      && !['wellbore-pressure', 'wellbore-pressure-comparison'].includes(item.type)))
    if (existing.length !== children.length || children.some((item, i) => item !== existing[i])) {
      node.children = children
    }
  }
}
