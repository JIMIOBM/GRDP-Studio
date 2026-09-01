import { productivityStorageApi } from '@/api/productivityStorage'
import { ensureProductivityTestMethodGroups } from '@/utils/productivityTestTree'

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
  const wellNode = findWellNode(treeData, wellName)
  const productivityGroup = wellNode?.children?.find(node =>
    node.type === 'single-well-productivity' || node.label === '单井产能'
  )
  if (!wellNode || !productivityGroup) return []

  let testGroup = productivityGroup.children?.find(node =>
    node.type === 'productivity-test' || node.label === '产能试井'
  )
  if (!testGroup) {
    testGroup = {
      id: `${wellNode.id}-single-well-productivity-productivity-test`,
      label: '产能试井',
      type: 'productivity-test',
      wellName,
      children: []
    }
    productivityGroup.children = [...(productivityGroup.children || []), testGroup]
  }

  const methodGroups = ensureProductivityTestMethodGroups(testGroup, wellNode, wellName)
  const response = await productivityStorageApi.listIsochronal(projectId, gasReservoirId, wellName)
  const records = response?.data ?? []
  const methodGroup = methodGroups.isochronal
  methodGroup.children = records.map(record => ({
    id: `${wellNode.id}-productivity-test-isochronal-${record.testId}`,
    label: `等时试井${record.testNo}`,
    type: ISOCHRONAL_RECORD_NODE_TYPE,
    wellName,
    testId: record.testId,
    testNo: record.testNo,
    raw: record,
    children: []
  }))

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
  const results = await Promise.allSettled(wells.map(well => loadIsochronalTreeNodes({
    ...options,
    wellName: well.wellName || well.label
  })))
  const failures = results.filter(result => result.status === 'rejected')
  if (failures.length) console.warn(`有 ${failures.length} 口井的等时试井记录加载失败`, failures)
}
