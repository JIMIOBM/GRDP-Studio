import { theoreticalProductivityApi } from '@/api/theoreticalProductivity'

export const THEORETICAL_CALCULATION_NODE_TYPE = 'theoretical-calculation'
export const THEORETICAL_STABLE_METHOD_NODE_TYPE = 'theoretical-stable-method'
export const THEORETICAL_STABLE_RECORD_NODE_TYPE = 'theoretical-stable-record'
export const THEORETICAL_UNSTABLE_METHOD_NODE_TYPE = 'theoretical-unstable-method'
export const THEORETICAL_UNSTABLE_RECORD_NODE_TYPE = 'theoretical-unstable-record'

const findWellNode = (treeData, wellName) => treeData
  .find(node => node.id === 'g-well')?.children
  ?.find(node => String(node.wellName || node.label) === String(wellName))

/** 初始化时只创建理论计算目录，历史记录在展开“稳定流”后再查询。 */
export const ensureTheoreticalStableTreeNodes = ({ treeData, wellName }) => {
  const wellNode = findWellNode(treeData, wellName)
  const productivityGroup = wellNode?.children?.find(node =>
    node.type === 'single-well-productivity' || node.label === '单井产能'
  )
  if (!wellNode || !productivityGroup) return null

  let theoreticalNode = (productivityGroup.children || []).find(
    node => node.type === THEORETICAL_CALCULATION_NODE_TYPE
  )
  if (!theoreticalNode) {
    theoreticalNode = {
      id: `${wellNode.id}-theoretical-calculation`, label: '理论计算',
      type: THEORETICAL_CALCULATION_NODE_TYPE, wellName,
      defaultExpanded: false, children: []
    }
    const dynamicIndex = (productivityGroup.children || []).findIndex(
      node => node.type === 'dynamic-productivity'
    )
    if (dynamicIndex >= 0) productivityGroup.children.splice(dynamicIndex, 0, theoreticalNode)
    else productivityGroup.children = [...(productivityGroup.children || []), theoreticalNode]
  }

  const ensureMethod = (type, label) => {
    let methodNode = theoreticalNode.children?.find(node => node.type === type)
    if (!methodNode) {
      methodNode = {
        id: `${wellNode.id}-${type}`, label, type, wellName,
        lazy: true, loaded: false, defaultExpanded: false, children: []
      }
      theoreticalNode.children = [...(theoreticalNode.children || []), methodNode]
    }
    methodNode.wellName = wellName
    methodNode.lazy = true
    return methodNode
  }
  const stableNode = ensureMethod(THEORETICAL_STABLE_METHOD_NODE_TYPE, '稳定流')
  const unstableNode = ensureMethod(THEORETICAL_UNSTABLE_METHOD_NODE_TYPE, '不稳定流')
  theoreticalNode.children = [stableNode, unstableNode]
  return { wellNode, productivityGroup, theoreticalNode, stableNode, unstableNode }
}

/** 展开“理论计算/不稳定流”时，只读取理论计算专属记录。 */
export const loadTheoreticalUnstableTreeNodes = async ({
  treeData, projectId, gasReservoirId, wellName, expand = false, force = false
}) => {
  const branch = ensureTheoreticalStableTreeNodes({ treeData, wellName })
  if (!branch) return []
  const { wellNode, productivityGroup, theoreticalNode, unstableNode } = branch
  if (unstableNode.loaded && !force) return unstableNode.children || []

  const response = await theoreticalProductivityApi.listUnstable(projectId, gasReservoirId, wellName)
  const records = Array.isArray(response?.data) ? response.data : []
  unstableNode.children = records.map(record => ({
    id: `${wellNode.id}-theoretical-unstable-${record.unstableId}`,
    label: record.unstableName || `不稳定流${record.unstableNo}`,
    type: THEORETICAL_UNSTABLE_RECORD_NODE_TYPE, wellName,
    unstableId: Number(record.unstableId), raw: record, children: []
  }))
  unstableNode.loaded = true
  if (expand) {
    wellNode.expanded = true
    productivityGroup.expanded = true
    theoreticalNode.expanded = true
    unstableNode.expanded = true
  }
  return unstableNode.children
}

/** 展开“理论计算/稳定流”时，只读取当前井的记录。 */
export const loadTheoreticalStableTreeNodes = async ({
  treeData, projectId, gasReservoirId, wellName, expand = false, force = false
}) => {
  const branch = ensureTheoreticalStableTreeNodes({ treeData, wellName })
  if (!branch) return []
  const { wellNode, productivityGroup, theoreticalNode, stableNode } = branch
  if (stableNode.loaded && !force) return stableNode.children || []

  const response = await theoreticalProductivityApi.listStable(projectId, gasReservoirId, wellName)
  const records = Array.isArray(response?.data) ? response.data : []
  stableNode.children = records.map(record => ({
    id: `${wellNode.id}-theoretical-stable-${record.stableId}`,
    label: record.stableName || `稳定流${record.stableNo}`,
    type: THEORETICAL_STABLE_RECORD_NODE_TYPE, wellName,
    stableId: Number(record.stableId), raw: record, children: []
  }))
  stableNode.loaded = true
  if (expand) {
    wellNode.expanded = true
    productivityGroup.expanded = true
    theoreticalNode.expanded = true
    stableNode.expanded = true
  }
  return stableNode.children
}

/** 初始化阶段只补齐理论计算目录骨架，不触发理论计算接口。 */
export const loadAllTheoreticalStableTreeNodes = async options => {
  const wells = options.treeData.find(node => node.id === 'g-well')?.children || []
  return wells.map(well => {
    ensureTheoreticalStableTreeNodes({
      treeData: options.treeData, wellName: well.wellName || well.label
    })
    return []
  })
}
