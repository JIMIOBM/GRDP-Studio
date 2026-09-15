import { geologicalLossApi } from '@/api/geologicalLoss'
import { wellboreLossApi } from '@/api/wellboreLoss'
import { surfaceLossApi } from '@/api/surfaceLoss'
import { RESERVOIR_LOSS_RECORD_NODE_TYPE } from '@/utils/reservoirGeologicalLossTree'

const labels = { microscopic: '微观损耗', escape: '逸散性损耗', wellbore: '井筒损耗', surface: '地面损耗' }
export const isLossRecord = node => node?.type === RESERVOIR_LOSS_RECORD_NODE_TYPE && Object.hasOwn(labels, node.lossType)
export const lossRecordLabel = node => labels[node?.lossType] || '损耗'
export const deleteLossRecord = node => {
  // 删除请求沿用目标节点的完整归属，不读取可能已切换到其他库的当前页面状态。
  if (!isLossRecord(node) || ![node.lossRecordId, node.projectId, node.gasReservoirId, node.storageId].every(id => Number(id) > 0)) {
    throw new Error('损耗记录的项目范围、储气库或记录编号不完整，无法删除')
  }
  if (node.lossType === 'wellbore') return wellboreLossApi.delete(node.lossRecordId, node.projectId, node.gasReservoirId, node.storageId)
  if (node.lossType === 'surface') return surfaceLossApi.delete(node.lossRecordId, node.projectId, node.gasReservoirId, node.storageId)
  return geologicalLossApi.deleteRecord(node.lossType, node.lossRecordId, node.projectId, node.gasReservoirId, node.storageId)
}

// 同一井或库、同一类型可有多条记录；只移除目标节点，不能按类型删除遇到的第一条。
export const removeSavedTreeRecord = (nodes, target) => {
  for (let index = 0; index < (nodes || []).length; index++) {
    if (nodes[index] === target || (target.id != null && String(nodes[index].id) === String(target.id))) {
      nodes.splice(index, 1)
      return true
    }
    if (removeSavedTreeRecord(nodes[index].children, target)) return true
  }
  return false
}
