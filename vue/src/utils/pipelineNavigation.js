export const pipelineNavigation = [
  { section: 'topology', label: '管网拓扑结构' },
  { section: 'pvt', label: 'PVT模型' },
  { section: 'temperature', label: '温度模型' },
  { section: 'boundary', label: '边界条件' },
  { section: 'flow', label: '管流计算' },
  { section: 'constraints', label: '约束条件' }
]
export const pipelineTemperaturePages = [
  { section: 'temperature-inner', label: '管内壁放热系数', tab: 'properties' },
  { section: 'temperature-wall', label: '管道导热系数', tab: 'layers' },
  { section: 'temperature-outer', label: '外部放热系数', tab: 'environment' },
  { section: 'temperature-overall', label: '总传热系数', tab: 'settings' },
  { section: 'temperature-z', label: '压缩因子', tab: 'z' },
  { section: 'temperature-cp', label: '定压比热容', tab: 'cp' }
]
const constraints = [
  { section: 'equipment', label: '关键设备' },
  { section: 'hydrate', label: '水合物' },
  { section: 'erosion', label: '冲蚀' },
  { section: 'freeze', label: '冻堵' }
]

export const pipelinePageTitles = {
  flow: '管流计算', boundary: '边界条件', topology: '管网拓扑结构', pvt: 'PVT模型',
  comparison: '管流结果对比',
  ...Object.fromEntries(constraints.map(page => [page.section, page.label])),
  ...Object.fromEntries(pipelineTemperaturePages.map(page => [page.section, page.label]))
}
export const pipelinePageForCommand = name => name === '折算方法' || name === '管流计算' ? 'flow'
  : name === '结果对比' ? 'comparison'
    : Object.keys(pipelinePageTitles).find(page => pipelinePageTitles[page] === name) || 'flow'
export const resolvePipelinePage = section => section === 'calculation' ? 'flow'
  : section === 'temperature' ? 'temperature-inner'
    : pipelinePageTitles[section] ? section : 'flow'

export function findPipelinePageNode(nodes, wellName, section, reveal = false) {
  const target = section === 'comparison' ? 'flow' : resolvePipelinePage(section)
  for (const node of nodes || []) {
    if (node.type === 'pipeline-capacity-page' && node.wellName === wellName && node.section === target) return node
    const match = findPipelinePageNode(node.children, wellName, target, reveal)
    if (match) { if (reveal) node.expanded = true; return match }
  }
  return null
}

// Keep existing objects and expansion state; the PVT page owns the well's complete gas model.
export function ensurePipelineNavigation(nodes) {
  for (const node of nodes || []) {
    if (node.type !== 'pipeline-capacity') {
      ensurePipelineNavigation(node.children)
      continue
    }
    delete node.icon
    const updateChildren = (parent, definitions) => {
      const children = definitions.map(item => {
        const child = parent.children?.find(child => child.section === item.section) || {
          id: `${parent.id}-${item.section}`, children: []
        }
        child.children ||= []
        Object.assign(child, item, { wellName: node.wellName || '',
          type: item.section === 'temperature' ? 'pipeline-temperature-group' : 'pipeline-capacity-page' })
        return child
      })
      if (parent.children?.length !== children.length || children.some((child, i) => parent.children[i] !== child)) parent.children = children
    }
    updateChildren(node, pipelineNavigation)
    updateChildren(node.children.find(child => child.section === 'constraints'), constraints)
    const temperatureGroup = node.children.find(child => child.section === 'temperature')
    delete temperatureGroup.icon
    updateChildren(temperatureGroup, pipelineTemperaturePages)
    const pvtPage = node.children.find(child => child.section === 'pvt')
    delete pvtPage.lazy; delete pvtPage.pvtEntry
    if(pvtPage.children.length)pvtPage.children=[]
  }
}
