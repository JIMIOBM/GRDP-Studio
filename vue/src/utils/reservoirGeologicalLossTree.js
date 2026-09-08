import { geologicalLossApi } from '@/api/geologicalLoss'
import { wellboreLossApi } from '@/api/wellboreLoss'
import { surfaceLossApi } from '@/api/surfaceLoss'

export const RESERVOIR_LOSS_METHOD_NODE_TYPE = 'reservoir-geological-loss-method'
export const RESERVOIR_LOSS_RECORD_NODE_TYPE = 'reservoir-geological-loss-record'

const responseData = response => response?.data ?? response

/** 只在用户展开微观、逸散性、井筒或地面损耗目录时加载对应记录，避免刷新页面批量调用接口。 */
export async function loadReservoirLossTreeNodes({ treeData, node }) {
  if (!node || node.type !== RESERVOIR_LOSS_METHOD_NODE_TYPE) return
  const recordResponse = node.lossType === 'microscopic'
    ? await geologicalLossApi.listMicroscopic(node.projectId, node.gasReservoirId)
    : node.lossType === 'escape'
      ? await geologicalLossApi.listEscape(node.projectId, node.gasReservoirId)
      : node.lossType === 'surface'
        ? await surfaceLossApi.list(node.projectId, node.gasReservoirId)
        : await wellboreLossApi.list(node.projectId, node.gasReservoirId)
  const records = responseData(recordResponse) || []
  // 储气库本身就是数据归属层级，计算记录直接挂在方法目录下，不再增加“库1”中间层。
  node.children = records.map(item => ({
    ...node,
    id: `${node.id}/record:${item.id}`,
    label: item.recordName,
    type: RESERVOIR_LOSS_RECORD_NODE_TYPE,
    lazy: false,
    children: [],
    lossType: item.lossType,
    lossRecordId: item.id,
    command: { ...node.command }
  }))
  node.loaded = true
  // 给依赖顶层数组引用的树组件一个可观测赋值点。
  if (treeData?.value) treeData.value = [...treeData.value]
}

export function upsertReservoirLossRecordNode({ treeData, projectId, gasReservoirId, lossType, record }) {
  const visit = nodes => {
    for (const node of nodes || []) {
      if (node.type === RESERVOIR_LOSS_METHOD_NODE_TYPE && node.projectId === projectId
          && node.gasReservoirId === gasReservoirId && node.lossType === lossType) {
        const id = `${node.id}/record:${record.id}`
        const next = { ...node, id, label: record.recordName, type: RESERVOIR_LOSS_RECORD_NODE_TYPE,
          lazy: false, children: [], lossType, lossRecordId: record.id, command: { ...node.command } }
        const index = (node.children || []).findIndex(item => item.lossRecordId === record.id)
        if (index >= 0) node.children.splice(index, 1, next)
        else node.children = [...(node.children || []), next]
        return true
      }
      if (visit(node.children)) return true
    }
    return false
  }
  visit(treeData?.value)
  if (treeData?.value) treeData.value = [...treeData.value]
}
