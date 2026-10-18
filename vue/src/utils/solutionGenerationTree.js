/**
 * 库 → 协同预测 → 多周期预测 → 方案生成 的目录树支持。
 *
 * 方案生成目录（solution-generation-method）懒加载已保存的方案记录
 * （solution-generation-record）：记录来自 storage_prediction_scheme 表。
 * 与一体化耦合优化的记录模式（couplingOptimizationTree.js）保持一致：
 * 展开时加载、保存后 upsert，不在刷新时批量请求。
 */
import { nextTick } from 'vue'
import { storagePredictionSchemeApi } from '@/api/storagePredictionSchemeApi'
import {
  SOLUTION_METHOD_NODE_TYPE,
  SOLUTION_RECORD_NODE_TYPE
} from '@/config/reservoirRibbon'

export { SOLUTION_METHOD_NODE_TYPE, SOLUTION_RECORD_NODE_TYPE }

const methodNodesOf = (nodes, { projectId, gasReservoirId, storageId }, visit) => {
  for (const node of nodes || []) {
    if (node.type === SOLUTION_METHOD_NODE_TYPE &&
        Number(node.projectId) === Number(projectId) &&
        Number(node.gasReservoirId) === Number(gasReservoirId) &&
        Number(node.storageId) === Number(storageId)) {
      if (visit(node)) return true
    }
    if (methodNodesOf(node.children, { projectId, gasReservoirId, storageId }, visit)) return true
  }
  return false
}

const recordNodeOf = (node, record) => ({
  ...node,
  id: `${node.id}/record:${record.id}`,
  label: record.title || `方案${record.id}`,
  type: SOLUTION_RECORD_NODE_TYPE,
  lazy: false,
  children: [],
  schemeId: Number(record.id),
  command: { ...node.command }
})

/**
 * 展开"方案生成"目录时加载该库下所有已保存的方案记录。
 */
export async function loadSolutionGenerationNodes({ treeData, node }) {
  if (!node || node.type !== SOLUTION_METHOD_NODE_TYPE) return
  const data = await storagePredictionSchemeApi.list(
    node.projectId, node.gasReservoirId, node.storageId
  )
  const records = Array.isArray(data) ? data : (data?.records || [])
  node.children = records.map(record => recordNodeOf(node, record))
  node.loaded = true
  if (treeData?.value) {
    await nextTick()
    treeData.value = [...treeData.value]
  }
}

/**
 * 保存方案后，把记录即时插入"方案生成"目录（无需收起再展开）。
 * 目录尚未加载时（loaded=false）不预置子节点，留给展开时按接口加载。
 * 返回插入记录节点的 id。
 */
export async function upsertSolutionGenerationRecordNode({ treeData, projectId, gasReservoirId, storageId, record }) {
  const methodNode = { node: null }
  methodNodesOf(treeData?.value, { projectId, gasReservoirId, storageId }, node => {
    methodNode.node = node
    return true
  })
  if (!methodNode.node || !methodNode.node.loaded) return ''
  const node = methodNode.node
  const next = recordNodeOf(node, record)
  const index = (node.children || []).findIndex(item => item.schemeId === next.schemeId)
  if (index >= 0) node.children.splice(index, 1, next)
  else node.children = [...(node.children || []), next]
  if (treeData?.value) {
    await nextTick()
    treeData.value = [...treeData.value]
  }
  return next.id
}