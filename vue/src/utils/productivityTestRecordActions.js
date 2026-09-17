import { productivityTestsApi } from '@/api/productivityTests'
import { NODETYPE } from '@/constants/nodeType'

export const productivityTestRecordMethod = node => {
  if (!(Number(node?.testId) > 0)) return null
  if (node.type === 'owned-productivity-test-record' && ['back-pressure', 'one-point'].includes(node.testMethod)) return node.testMethod
  if (node.type === 'productivity-test-isochronal-record') return 'isochronal'
  if (node.type === NODETYPE.NodeType_ProductivityEvaluationModifiedIsochronalWellTest) return 'modified-isochronal'
  return null
}
export const isProductivityTestRecord = node => !!productivityTestRecordMethod(node)
export const deleteProductivityTestRecord = node => {
  const method = productivityTestRecordMethod(node)
  if (!method || ![node.testId, node.projectId, node.gasReservoirId].every(value => Number.isInteger(Number(value)) && Number(value) > 0) || !node.wellName?.trim()) {
    throw new Error('试井记录的项目、气藏、井或记录编号不完整，无法删除')
  }
  return productivityTestsApi.delete(node.testId, node.projectId, node.gasReservoirId, node.wellName, method)
}
