import { shallowRef } from 'vue'
import { pvtStorageApi } from '@/api/pvtStorage'

// 通知当前已打开的计算面板更新选项，不切换右侧功能或修改其他井的数据。
export const deletedPvtRecord = shallowRef(null)
export const isPvtRecord = node => node?.type === 'well-data-pvt' && Number(node.pvtId) > 0
export const matchesPvtScope = (record, scope) => !!record &&
  record.wellName === scope.wellName &&
  Number(record.projectId) === Number(scope.projectId) &&
  Number(record.gasReservoirId) === Number(scope.gasReservoirId)

export const samePvtRecord = (node, target) => isPvtRecord(node) &&
  Number(node.pvtId) === Number(target.pvtId) && matchesPvtScope({
    ...node,
    projectId: node.projectId ?? target.projectId,
    gasReservoirId: node.gasReservoirId ?? target.gasReservoirId
  }, target)

export async function deletePvtTreeRecord (node, tree, fallbackScope) {
  const target = {
    pvtId: node?.pvtId,
    wellName: node?.wellName,
    projectId: node?.projectId ?? fallbackScope.projectId,
    gasReservoirId: node?.gasReservoirId ?? fallbackScope.gasReservoirId
  }
  if (!isPvtRecord(node) || !target.wellName?.trim() ||
      !(Number(target.projectId) > 0) || !(Number(target.gasReservoirId) > 0)) {
    throw new Error('PVT记录归属信息不完整，无法删除')
  }
  await pvtStorageApi.delete(target.pvtId, target.projectId, target.gasReservoirId, target.wellName)
  // 性质和模型只是同一条数据库记录的两个入口，必须一起移除，保留目录及同类兄弟。
  const removedIds = []
  const remove = nodes => {
    for (let i = nodes.length - 1; i >= 0; i--) {
      if (samePvtRecord(nodes[i], target)) {
        removedIds.push(String(nodes[i].id))
        nodes.splice(i, 1)
      } else if (Array.isArray(nodes[i].children)) remove(nodes[i].children)
    }
  }
  remove(tree)
  deletedPvtRecord.value = target
  return { target, removedIds }
}
