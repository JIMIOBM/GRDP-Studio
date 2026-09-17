import { productivityStorageApi } from '@/api/productivityStorage'
import { ensureProductivityTestTreeNodes } from '@/utils/productivityTestTree'

export const ISOCHRONAL_METHOD_NODE_TYPE = 'productivity-test-isochronal-method'
export const ISOCHRONAL_RECORD_NODE_TYPE = 'productivity-test-isochronal-record'

const findWellNode = (treeData, wellName) => treeData
  .find(node => node.id === 'g-well')?.children
  ?.find(node => (node.wellName || node.label) === wellName)

export const loadIsochronalTreeNodes = async ({
  treeData,
  projectId,
  gasReservoirId,
  wellName,
  expand = false
}) => {
  const branch = ensureProductivityTestTreeNodes(treeData, wellName)
  if (!branch) return []
  const { wellNode, productivityGroup, testGroup, methodGroups } = branch
  const response = await productivityStorageApi.listIsochronal(projectId, gasReservoirId, wellName)
  const records = response?.data ?? []
  const methodGroup = methodGroups.isochronal
  methodGroup.children = records.map(record => ({
    id: `${wellNode.id}-productivity-test-isochronal-${record.testId}`,
    label: `等时试井${record.testNo}`,
    type: ISOCHRONAL_RECORD_NODE_TYPE,
    projectId,
    gasReservoirId,
    wellName,
    testId: record.testId,
    testNo: record.testNo,
    raw: record,
    children: []
  }))
  methodGroup.loaded = true

  if (expand) {
    wellNode.expanded = true
    productivityGroup.expanded = true
    testGroup.expanded = true
    methodGroup.expanded = true
  }
  return methodGroup.children
}

export const loadAllIsochronalTreeNodes = async options => {
  const wells = options.treeData.find(node => node.id === 'g-well')?.children || []
  wells.forEach(well => ensureProductivityTestTreeNodes(options.treeData, well.wellName || well.label))
}
