import { dynamicProductivityApi } from '@/api/dynamicProductivity'

export const DYNAMIC_PRODUCTIVITY_NODE_TYPE = 'dynamic-productivity'
export const DYNAMIC_STABLE_METHOD_NODE_TYPE = 'dynamic-stable-method'
export const DYNAMIC_STABLE_RECORD_NODE_TYPE = 'dynamic-stable-record'

const findWellNode = (treeData, wellName) => treeData
  .find(node => node.id === 'g-well')?.children
  ?.find(node => String(node.wellName || node.label) === String(wellName))

/** 展开“单井产能”时，只挂载新数据库中实际存在的稳定流记录。 */
export const loadDynamicStableTreeNodes = async ({
  treeData, projectId, gasReservoirId, wellName, expand = false
}) => {
  const wellNode = findWellNode(treeData, wellName)
  const productivityGroup = wellNode?.children?.find(node =>
    node.type === 'single-well-productivity' || node.label === '单井产能'
  )
  if (!wellNode || !productivityGroup) return []

  const response = await dynamicProductivityApi.listStable(projectId, gasReservoirId, wellName)
  const records = Array.isArray(response?.data) ? response.data : []
  // 每次都删除旧的动态产能分支后按接口结果重建，避免删除、重命名后留下脏节点。
  productivityGroup.children = (productivityGroup.children || []).filter(node =>
    node.type !== DYNAMIC_PRODUCTIVITY_NODE_TYPE
  )
  // 数据库没有已保存记录时，不显示空的“动态产能/稳定流”目录。
  if (!records.length) return []

  const recordNodes = records.map(record => ({
    id: `${wellNode.id}-dynamic-stable-${record.stableId}`,
    label: record.stableName || `稳定流${record.stableNo}`,
    type: DYNAMIC_STABLE_RECORD_NODE_TYPE,
    wellName,
    stableId: Number(record.stableId),
    raw: record,
    children: []
  }))
  const stableNode = {
    id: `${wellNode.id}-dynamic-stable-method`,
    label: '稳定流',
    type: DYNAMIC_STABLE_METHOD_NODE_TYPE,
    wellName,
    defaultExpanded: false,
    children: recordNodes
  }
  const dynamicNode = {
    id: `${wellNode.id}-dynamic-productivity`,
    label: '动态产能',
    type: DYNAMIC_PRODUCTIVITY_NODE_TYPE,
    wellName,
    defaultExpanded: false,
    children: [stableNode]
  }
  // 固定目录顺序：动态产能始终位于理论计算之后，避免刷新某一分支后顺序跳动。
  const theoreticalIndex = productivityGroup.children.findIndex(
    node => node.type === 'theoretical-calculation'
  )
  if (theoreticalIndex >= 0) {
    productivityGroup.children.splice(theoreticalIndex + 1, 0, dynamicNode)
  } else {
    productivityGroup.children.push(dynamicNode)
  }
  if (expand) {
    wellNode.expanded = true
    productivityGroup.expanded = true
    dynamicNode.expanded = true
    stableNode.expanded = true
  }
  return recordNodes
}

/** 初始化工作台时，按新库记录为所有井重建动态产能稳定流目录。 */
export const loadAllDynamicStableTreeNodes = async options => {
  const wells = options.treeData.find(node => node.id === 'g-well')?.children || []
  return Promise.all(wells.map(well => loadDynamicStableTreeNodes({
    ...options,
    wellName: well.wellName || well.label
  })))
}
