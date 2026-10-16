/**
 * 库 → 协同预测 → 一体化耦合优化 的目录树支持。
 *
 * 单目标/多目标目录（coupling-optimization-method）懒加载预测记录
 * （coupling-optimization-record）：记录来自**本项目数据库的留存表**
 * （GET /prediction-results，见 predictionResult.js），只显示留存过结果的预测；
 * 归属（单目标/多目标）直接用留存记录自身的 mode 字段，不依赖本机状态。
 * 原平台任务树（nodeType 79）仅用于计算链路，不再作为目录数据源。
 *
 * 与损耗评价的记录模式（reservoirGeologicalLossTree.js）保持一致：
 * 展开时加载、保存后 upsert，不在刷新时批量请求。
 */
import { predictionResultApi } from '@/api/predictionResult'
import {
  COUPLING_METHOD_NODE_TYPE,
  COUPLING_NEW_NODE_TYPE,
  COUPLING_RECORD_NODE_TYPE
} from '@/config/reservoirRibbon'

export { COUPLING_METHOD_NODE_TYPE, COUPLING_NEW_NODE_TYPE, COUPLING_RECORD_NODE_TYPE }

const methodNodesOf = (nodes, { projectId, gasReservoirId, storageId, mode }, visit) => {
  for (const node of nodes || []) {
    if (node.type === COUPLING_METHOD_NODE_TYPE &&
        Number(node.projectId) === Number(projectId) &&
        Number(node.gasReservoirId) === Number(gasReservoirId) &&
        Number(node.storageId) === Number(storageId) &&
        node.couplingMode === mode) {
      if (visit(node)) return true
    }
    if (methodNodesOf(node.children, { projectId, gasReservoirId, storageId, mode }, visit)) return true
  }
  return false
}

/** 读取某模式下已留存的预测记录（最近保存在前）。 */
export async function listCouplingRecords(projectId, gasReservoirId, storageId, mode) {
  const response = await predictionResultApi.list(projectId, gasReservoirId, storageId)
  const payload = response?.data?.data ?? response?.data ?? response
  return (Array.isArray(payload) ? payload : []).filter(item =>
    item && item.mode === mode && Number.isSafeInteger(Number(item.predictionId)) && Number(item.predictionId) > 0)
}

const recordNodeOf = (node, record) => ({
  ...node,
  id: `${node.id}/record:${record.predictionId}`,
  label: record.predictionName,
  type: COUPLING_RECORD_NODE_TYPE,
  lazy: false,
  children: [],
  predictionId: Number(record.predictionId),
  couplingMode: node.couplingMode,
  command: { ...node.command }
})

/**
 * 展开单目标/多目标目录时加载该模式下的留存记录。
 * "新建预测"动作节点固定在记录列表顶部。
 */
export async function loadCouplingOptimizationTreeNodes({ treeData, node }) {
  if (!node || node.type !== COUPLING_METHOD_NODE_TYPE) return
  const records = await listCouplingRecords(node.projectId, node.gasReservoirId, node.storageId, node.couplingMode)
  const newActionNode = {
    ...node,
    id: `${node.id}/new`,
    label: '新建预测',
    type: COUPLING_NEW_NODE_TYPE,
    lazy: false,
    children: [],
    couplingMode: node.couplingMode,
    command: { ...node.command }
  }
  node.children = [newActionNode, ...records.map(record => recordNodeOf(node, record))]
  node.loaded = true
  // 给依赖顶层数组引用的树组件一个可观测赋值点。
  if (treeData?.value) treeData.value = [...treeData.value]
}

/**
 * 预测结果留存成功后，把记录即时插入对应模式的目录（无需收起再展开）。
 * 目录尚未加载（loaded=false）时不预置子节点，留给展开时统一按留存表加载。
 * 返回插入记录节点的 id（供高亮），目录未加载时返回空串。
 */
export function upsertCouplingOptimizationRecordNode({ treeData, projectId, gasReservoirId, storageId, mode, record }) {
  const methodNode = { node: null }
  methodNodesOf(treeData?.value, { projectId, gasReservoirId, storageId, mode }, node => {
    methodNode.node = node
    return true
  })
  if (!methodNode.node || !methodNode.node.loaded) return ''
  const node = methodNode.node
  const next = recordNodeOf(node, record)
  const index = (node.children || []).findIndex(item => item.predictionId === next.predictionId)
  if (index >= 0) node.children.splice(index, 1, next)
  else node.children = [...(node.children || []), next]
  if (treeData?.value) treeData.value = [...treeData.value]
  return next.id
}

/**
 * 删除预测记录后从目录摘除对应节点（平台任务与留存删除成功后调用）。
 * 返回被删除记录所属模式目录节点的 id（供高亮回退）；未找到记录时返回空串。
 */
export function removeCouplingRecordNode({ treeData, projectId, gasReservoirId, storageId, mode, predictionId }) {
  const methodNode = { node: null }
  methodNodesOf(treeData?.value, { projectId, gasReservoirId, storageId, mode }, node => {
    methodNode.node = node
    return true
  })
  const node = methodNode.node
  if (!node || !Array.isArray(node.children)) return ''
  const index = node.children.findIndex(item => item.type === COUPLING_RECORD_NODE_TYPE &&
    Number(item.predictionId) === Number(predictionId))
  if (index < 0) return ''
  node.children.splice(index, 1)
  if (treeData?.value) treeData.value = [...treeData.value]
  return node.id
}
