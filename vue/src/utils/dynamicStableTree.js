import { dynamicProductivityApi } from '@/api/dynamicProductivity'

export const DYNAMIC_PRODUCTIVITY_NODE_TYPE = 'dynamic-productivity'
export const DYNAMIC_STABLE_METHOD_NODE_TYPE = 'dynamic-stable-method'
export const DYNAMIC_STABLE_RECORD_NODE_TYPE = 'dynamic-stable-record'
export const DYNAMIC_UNSTABLE_METHOD_NODE_TYPE = 'dynamic-unstable-method'
export const DYNAMIC_UNSTABLE_RECORD_NODE_TYPE = 'dynamic-unstable-record'

const findWellNode = (treeData, wellName) => treeData
  .find(node => node.id === 'g-well')?.children
  ?.find(node => String(node.wellName || node.label) === String(wellName))

/**
 * 只创建动态产能目录骨架，不访问后端。
 * 登录或刷新后目录立即可见，具体记录在展开稳定流/不稳定流时按需读取。
 */
export const ensureDynamicProductivityTreeNodes = ({ treeData, wellName }) => {
  const wellNode = findWellNode(treeData, wellName)
  const productivityGroup = wellNode?.children?.find(node =>
    node.type === 'single-well-productivity' || node.label === '单井产能'
  )
  if (!wellNode || !productivityGroup) return null

  let dynamicNode = (productivityGroup.children || []).find(
    node => node.type === DYNAMIC_PRODUCTIVITY_NODE_TYPE
  )
  if (!dynamicNode) {
    dynamicNode = {
      id: `${wellNode.id}-dynamic-productivity`, label: '动态产能',
      type: DYNAMIC_PRODUCTIVITY_NODE_TYPE, wellName,
      defaultExpanded: false, children: []
    }
    const theoreticalIndex = (productivityGroup.children || []).findIndex(
      node => node.type === 'theoretical-calculation'
    )
    if (theoreticalIndex >= 0) productivityGroup.children.splice(theoreticalIndex + 1, 0, dynamicNode)
    else productivityGroup.children = [...(productivityGroup.children || []), dynamicNode]
  }

  const ensureMethod = (type, label) => {
    let methodNode = dynamicNode.children?.find(node => node.type === type)
    if (!methodNode) {
      methodNode = {
        id: `${wellNode.id}-${type}`, label, type, wellName,
        lazy: true, loaded: false, defaultExpanded: false, children: []
      }
      dynamicNode.children = [...(dynamicNode.children || []), methodNode]
    }
    methodNode.wellName = wellName
    methodNode.lazy = true
    return methodNode
  }

  const stableNode = ensureMethod(DYNAMIC_STABLE_METHOD_NODE_TYPE, '稳定流')
  const unstableNode = ensureMethod(DYNAMIC_UNSTABLE_METHOD_NODE_TYPE, '不稳定流')
  dynamicNode.children = [stableNode, unstableNode]
  return { wellNode, productivityGroup, dynamicNode, stableNode, unstableNode }
}

/** 展开“动态产能/稳定流”时，只读取当前井的稳定流记录。 */
export const loadDynamicStableTreeNodes = async ({
  treeData, projectId, gasReservoirId, wellName, expand = false, force = false
}) => {
  const branch = ensureDynamicProductivityTreeNodes({ treeData, wellName })
  if (!branch) return []
  const { wellNode, productivityGroup, dynamicNode, stableNode } = branch
  if (stableNode.loaded && !force) return stableNode.children || []

  const response = await dynamicProductivityApi.listStable(projectId, gasReservoirId, wellName)
  const records = Array.isArray(response?.data) ? response.data : []
  stableNode.children = records.map(record => ({
    id: `${wellNode.id}-dynamic-stable-${record.stableId}`,
    label: record.stableName || `稳定流${record.stableNo}`,
    type: DYNAMIC_STABLE_RECORD_NODE_TYPE, wellName,
    stableId: Number(record.stableId), raw: record, children: []
  }))
  stableNode.loaded = true
  if (expand) {
    wellNode.expanded = true
    productivityGroup.expanded = true
    dynamicNode.expanded = true
    stableNode.expanded = true
  }
  return stableNode.children
}

/** 展开“动态产能/不稳定流”时，只读取当前井的不稳定流记录。 */
export const loadDynamicUnstableTreeNodes = async ({
  treeData, projectId, gasReservoirId, wellName, expand = false, force = false
}) => {
  const branch = ensureDynamicProductivityTreeNodes({ treeData, wellName })
  if (!branch) return []
  const { wellNode, productivityGroup, dynamicNode, unstableNode } = branch
  if (unstableNode.loaded && !force) return unstableNode.children || []

  const response = await dynamicProductivityApi.listUnstable(projectId, gasReservoirId, wellName)
  const records = Array.isArray(response?.data) ? response.data : []
  unstableNode.children = records.map(record => ({
    id: `${wellNode.id}-dynamic-unstable-${record.unstableId}`,
    label: record.unstableName || `不稳定流${record.unstableNo}`,
    type: DYNAMIC_UNSTABLE_RECORD_NODE_TYPE, wellName,
    unstableId: Number(record.unstableId), raw: record, children: []
  }))
  unstableNode.loaded = true
  if (expand) {
    wellNode.expanded = true
    productivityGroup.expanded = true
    dynamicNode.expanded = true
    unstableNode.expanded = true
  }
  return unstableNode.children
}

/** 初始化阶段只为所有井补齐目录骨架，不触发任何动态产能接口。 */
export const loadAllDynamicStableTreeNodes = async options => {
  const wells = options.treeData.find(node => node.id === 'g-well')?.children || []
  return wells.map(well => {
    ensureDynamicProductivityTreeNodes({
      treeData: options.treeData, wellName: well.wellName || well.label
    })
    return []
  })
}
