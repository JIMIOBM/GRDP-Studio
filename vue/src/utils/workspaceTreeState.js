import { ref } from 'vue'

// 两个解析融合工作台共用的目录数据与选择状态。
// 路由切换不会销毁这些 ref，因此单井产能页面可以延续主工作台的完整目录。
export const workspaceTreeData = ref([
  { id: 'g-well', label: '井', children: [] },
  { id: 'g-reservoir', label: '库', children: [{ id: 'res-1', label: '项目 1', type: 'reservoir' }] },
  { id: 'g-group', label: '库群', children: [{ id: 'grp-1', label: '项目 1', type: 'group' }] }
])

export const workspaceActiveNodeId = ref('')
export const workspaceSelectedWellName = ref('')
export const workspaceTreeKeyword = ref('')
export const workspaceTreeCollapsed = ref(false)
// 单井产能跳回 IPR 工作台时携带首次点击的顶部菜单命令。
export const workspacePendingCommand = ref(null)
// 单井产能通过公共左侧目录跳回 IPR 时，携带本次点击的具体树节点。
export const workspacePendingNode = ref(null)
// 仅真实的 IPR 项目树加载完成后置为 true；单井产能的临时井列表不算初始化完成。
export const workspaceTreeHydrated = ref(false)
