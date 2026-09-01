import { productivityTestsApi } from '@/api/productivityTests'
import { NODETYPE } from '@/constants/nodeType'
import { ensureProductivityTestMethodGroups } from '@/utils/productivityTestTree'

export const loadModifiedIsochronalTreeNodes = async ({
  treeData,
  projectId,
  gasReservoirId,
  wellName,
  expand = false
}) => {
  const wellNode = treeData.find(node => node.id === 'g-well')?.children?.find(node =>
    (node.wellName || node.label) === wellName
  )
  if (!wellNode) return

  const productivityGroup = wellNode.children?.find(node =>
    node.type === 'single-well-productivity' || node.label === '单井产能'
  )
  if (!productivityGroup) return

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
  const results = await Promise.allSettled(wells.map(well =>
    loadModifiedIsochronalTreeNodes({
      ...options,
      wellName: well.wellName || well.label
    })
  ))
  const failures = results.filter(result => result.status === 'rejected')
  if (failures.length) {
    console.warn(`有 ${failures.length} 口井的修正等时记录加载失败`, failures)
  }
}
