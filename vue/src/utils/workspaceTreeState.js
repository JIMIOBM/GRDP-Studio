import { ref } from 'vue'
import { buildReservoirTreeNodes, resolveReservoirCommand } from '../config/reservoirRibbon.js'

// 两个解析融合工作台共用的目录数据与选择状态。
// 路由切换不会销毁这些 ref，因此单井产能页面可以延续主工作台的完整目录。
export const workspaceTreeData = ref([
  { id: 'g-well', label: '井', expanded: false, defaultExpanded: false, children: [] },
  { id: 'g-reservoir', label: '库', scope: 'reservoir', expanded: false, defaultExpanded: false, children: [] },
  { id: 'g-group', label: '库群', expanded: false, defaultExpanded: false, children: [{ id: 'grp-1', label: '项目 1', type: 'group' }] }
])

export const workspaceActiveNodeId = ref('')
export const workspaceSelectedWellName = ref('')
export const workspaceTreeKeyword = ref('')
export const workspaceTreeCollapsed = ref(false)
// 单井产能跳回 IPR 工作台时携带首次点击的顶部菜单命令。
export const workspacePendingCommand = ref(null)
// 单井产能通过公共左侧目录跳回 IPR 时，携带本次点击的具体树节点。
export const workspacePendingNode = ref(null)
// 理论稳定流计算完成后保存压力、压力平方和拟压力结果，供独立单井产能页面切换绘图。
export const workspaceTheoreticalStableResult = ref(null)
// 仅真实的 IPR 项目树加载完成后置为 true；单井产能的临时井列表不算初始化完成。
export const workspaceTreeHydrated = ref(false)

// 菜单范围独立于右侧已打开的记录；切换井/库目录不能触发计算或销毁编辑内容。
export const workspaceRibbonScope = ref('well')
export const workspaceSelectedReservoir = ref(null)

export function ensureWorkspaceReservoir({ projectId, gasReservoirId, label }) {
  const root = workspaceTreeData.value.find(node => node.id === 'g-reservoir')
  if (!root || !projectId || !gasReservoirId) return null
  let reservoir = root.children.find(node =>
    Number(node.projectId) === Number(projectId) && Number(node.gasReservoirId) === Number(gasReservoirId))
  if (!reservoir) {
    reservoir = buildReservoirTreeNodes({
      id: `reservoir-${projectId}-${gasReservoirId}`,
      label: label || `当前库（${gasReservoirId}）`, projectId, gasReservoirId
    })
    root.children.push(reservoir)
  }
  // 不使用单井名或“项目 1”冒充库名；真实名称随现有项目详情返回时补齐。
  if (label) {
    reservoir.label = label
    const rename = node => {
      node.reservoirName = label
      node.children?.forEach(rename)
    }
    rename(reservoir)
  }
  if (!workspaceSelectedReservoir.value) workspaceSelectedReservoir.value = reservoir
  return reservoir
}

export function setWorkspaceRibbonScope(scope) {
  if (!['well', 'reservoir'].includes(scope)) return
  workspaceRibbonScope.value = scope
}

export function selectWorkspaceNodeScope(node) {
  if (!node || node.disabled) return null
  if (node.scope === 'reservoir' || node.id === 'g-reservoir' || node.type === 'reservoir') {
    const reservoir = workspaceTreeData.value.find(item => item.id === 'g-reservoir')?.children.find(item =>
      Number(item.projectId) === Number(node.projectId) && Number(item.gasReservoirId) === Number(node.gasReservoirId))
    if (reservoir) workspaceSelectedReservoir.value = reservoir
    setWorkspaceRibbonScope('reservoir')
    return 'reservoir'
  }
  if (node.wellName || node.id === 'g-well' || node.type === 2) {
    setWorkspaceRibbonScope('well')
    return 'well'
  }
  return null // 库群尚未设计，不将其误当作单库或单井。
}

// 库命令只能进入库工作区。完整路径区分三处“相关性分析”等同名功能。
export function getReservoirCommandLocation(command) {
  const resolved = resolveReservoirCommand(command)
  const reservoir = workspaceSelectedReservoir.value
  if (!resolved || !reservoir) return null
  return {
    name: 'IprInterface',
    query: {
      scope: 'reservoir', projectId: reservoir.projectId, gasReservoirId: reservoir.gasReservoirId,
      group: resolved.group, parent: resolved.parent, feature: resolved.name
    }
  }
}

export function resolveReservoirLocation(query) {
  if (query.scope !== 'reservoir') return null
  const command = resolveReservoirCommand({ group: query.group, parent: query.parent, name: query.feature })
  const reservoir = workspaceTreeData.value.find(item => item.id === 'g-reservoir')?.children.find(item =>
    Number(item.projectId) === Number(query.projectId) && Number(item.gasReservoirId) === Number(query.gasReservoirId))
  return command && reservoir ? { command, reservoir, lossRecordId: query.lossRecordId ?? null } : null
}

export function activateReservoirCommand({ command, reservoir, lossRecordId = null }) {
  workspaceSelectedReservoir.value = reservoir
  setWorkspaceRibbonScope('reservoir')
  const hasRecord = lossRecordId !== null && lossRecordId !== ''
  const reveal = node => {
    if (node.command?.path.join('/') === command.path.join('/')
        && node.type !== 'reservoir-geological-loss-record') {
      // 方法目录和记录共用功能路径；编辑已有记录时必须再按记录ID定位，
      // 否则路由同步会把刚点击的记录高亮覆盖成父目录。
      if (hasRecord && node.type === 'reservoir-geological-loss-method') {
        node.expanded = true
        const record = node.children?.find(child =>
          child.type === 'reservoir-geological-loss-record'
          && String(child.lossRecordId) === String(lossRecordId))
        // 刷新后明细可能尚未懒加载。只预置稳定的选中ID，不伪造节点或额外调用接口；
        // 目录加载后新节点会自然匹配高亮，且不会抢走用户后续点击的其他节点。
        workspaceActiveNodeId.value = record?.id ?? `${node.id}/record:${lossRecordId}`
        return true
      }
      workspaceActiveNodeId.value = node.id
      return true
    }
    if (node.children?.some(reveal)) {
      node.expanded = true
      return true
    }
    return false
  }
  if (reveal(reservoir)) {
    const root = workspaceTreeData.value.find(node => node.id === 'g-reservoir')
    if (root) root.expanded = true
  }
}
