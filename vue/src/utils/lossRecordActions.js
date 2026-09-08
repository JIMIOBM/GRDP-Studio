import { geologicalLossApi } from '@/api/geologicalLoss'
import { wellboreLossApi } from '@/api/wellboreLoss'
import { surfaceLossApi } from '@/api/surfaceLoss'
import { RESERVOIR_LOSS_RECORD_NODE_TYPE } from '@/utils/reservoirGeologicalLossTree'

const labels = { microscopic: '微观损耗', escape: '逸散性损耗', wellbore: '井筒损耗', surface: '地面损耗' }
export const isLossRecord = node => node?.type === RESERVOIR_LOSS_RECORD_NODE_TYPE && Object.hasOwn(labels, node.lossType)
export const lossRecordLabel = node => labels[node?.lossType] || '损耗'
export const deleteLossRecord = node => {
  if (!isLossRecord(node) || ![node.lossRecordId, node.projectId, node.gasReservoirId].every(id => Number(id) > 0)) {
    throw new Error('损耗记录的项目、气藏或记录编号不完整，无法删除')
  }
  if (node.lossType === 'wellbore') return wellboreLossApi.delete(node.lossRecordId, node.projectId, node.gasReservoirId)
  if (node.lossType === 'surface') return surfaceLossApi.delete(node.lossRecordId, node.projectId, node.gasReservoirId)
  return geologicalLossApi.deleteRecord(node.lossType, node.lossRecordId, node.projectId, node.gasReservoirId)
}

// 多条记录可以同井、同类型；必须按节点唯一 ID 删除，不能按类型删除第一条。
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
