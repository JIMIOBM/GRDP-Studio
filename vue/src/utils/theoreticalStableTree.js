import { theoreticalProductivityApi } from '@/api/theoreticalProductivity'

export const THEORETICAL_CALCULATION_NODE_TYPE = 'theoretical-calculation'
export const THEORETICAL_STABLE_METHOD_NODE_TYPE = 'theoretical-stable-method'
export const THEORETICAL_STABLE_RECORD_NODE_TYPE = 'theoretical-stable-record'

const findWellNode = (treeData, wellName) => treeData
  .find(node => node.id === 'g-well')?.children
  ?.find(node => String(node.wellName || node.label) === String(wellName))

/** 展开“单井产能”时，只挂载新数据库中实际存在的稳定流记录。 */
export const loadTheoreticalStableTreeNodes = async ({
  treeData, projectId, gasReservoirId, wellName, expand = false
}) => {
  const wellNode = findWellNode(treeData, wellName)
  const productivityGroup = wellNode?.children?.find(node =>
    node.type === 'single-well-productivity' || node.label === '单井产能'
  )
  if (!wellNode || !productivityGroup) return []

  const response = await theoreticalProductivityApi.listStable(projectId, gasReservoirId, wellName)
  const records = Array.isArray(response?.data) ? response.data : []
  // 每次都删除旧的理论计算分支后按接口结果重建，避免删除、重命名后留下脏节点。
  productivityGroup.children = (productivityGroup.children || []).filter(node =>
    node.type !== THEORETICAL_CALCULATION_NODE_TYPE
  )
  // 数据库没有已保存记录时，不显示空的“理论计算/稳定流”目录。
  if (!records.length) return []

  const recordNodes = records.map(record => ({
    id: `${wellNode.id}-theoretical-stable-${record.stableId}`,
    label: record.stableName || `稳定流${record.stableNo}`,
    type: THEORETICAL_STABLE_RECORD_NODE_TYPE,
    wellName,
    stableId: Number(record.stableId),
    raw: record,
    children: []
  }))
  const stableNode = {
    id: `${wellNode.id}-theoretical-stable-method`,
    label: '稳定流',
    type: THEORETICAL_STABLE_METHOD_NODE_TYPE,
    wellName,
    defaultExpanded: false,
    children: recordNodes
  }
  const theoreticalNode = {
    id: `${wellNode.id}-theoretical-calculation`,
    label: '理论计算',
    type: THEORETICAL_CALCULATION_NODE_TYPE,
    wellName,
    defaultExpanded: false,
    children: [stableNode]
  }
  // 固定目录顺序：理论计算始终位于动态产能之前，避免刷新某一分支后顺序跳动。
  const dynamicIndex = productivityGroup.children.findIndex(
    node => node.type === 'dynamic-productivity'
  )
  if (dynamicIndex >= 0) productivityGroup.children.splice(dynamicIndex, 0, theoreticalNode)
  else productivityGroup.children.push(theoreticalNode)
  if (expand) {
    wellNode.expanded = true
    productivityGroup.expanded = true
    theoreticalNode.expanded = true
    stableNode.expanded = true
  }
  return recordNodes
}

/** 初始化工作台时，按新库记录为所有井重建理论稳定流目录。 */
export const loadAllTheoreticalStableTreeNodes = async options => {
  const wells = options.treeData.find(node => node.id === 'g-well')?.children || []
  return Promise.all(wells.map(well => loadTheoreticalStableTreeNodes({
    ...options,
    wellName: well.wellName || well.label
  })))
}
