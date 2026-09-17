import { productivityTestsApi } from '@/api/productivityTests'
import { NODETYPE } from '@/constants/nodeType'
import { ensureProductivityTestTreeNodes } from '@/utils/productivityTestTree'

export const loadModifiedIsochronalTreeNodes = async ({
  treeData,
  projectId,
  gasReservoirId,
  wellName,
  expand = false
}) => {
  const branch = ensureProductivityTestTreeNodes(treeData, wellName)
  if (!branch) return []
  const { wellNode, productivityGroup, testGroup, methodGroups } = branch
  const response = await productivityTestsApi.list(
    projectId, gasReservoirId, wellName, 'modified-isochronal'
  )
  const records = response?.data ?? response ?? []
  const resultNodes = records.map(record => ({
    id: `${wellNode.id}-modified-isochronal-${record.id}`,
    label: record.testName || `修正等时${record.testNo}`,
    type: NODETYPE.NodeType_ProductivityEvaluationModifiedIsochronalWellTest,
    wellName,
    testId: record.id,
    testNo: record.testNo,
    resultId: record.id,
    projectId,
    gasReservoirId,
    pressureMethods: record.pressureMethods || [],
    children: []
  }))
  methodGroups['modified-isochronal'].children = resultNodes
  methodGroups['modified-isochronal'].loaded = true

  if (expand) {
    wellNode.expanded = true
    productivityGroup.expanded = true
    testGroup.expanded = true
    methodGroups['modified-isochronal'].expanded = true
  }
  return resultNodes
}

export const loadAllModifiedIsochronalTreeNodes = async options => {
  const wells = options.treeData.find(node => node.id === 'g-well')?.children || []
  wells.forEach(well => ensureProductivityTestTreeNodes(options.treeData, well.wellName || well.label))
}
