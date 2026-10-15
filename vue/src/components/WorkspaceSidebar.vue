<script setup>
/**
 * 解析融合工作台公共左侧目录。
 *
 * IprInterface.vue 与 SingleWellProductivityInterface.vue 共用本组件，
 * 以保证井目录的搜索、折叠、树节点样式和交互方式完全一致。
 * 具体树数据与节点业务仍由各工作台页面负责。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import TreeNode from '@/views/TreeNode.vue'
import { ensurePipelineNavigation } from '@/utils/pipelineNavigation'
import { ensureNodalNavigation } from '@/utils/nodalNavigation'
import { ensureWellboreNavigation } from '@/utils/wellboreNavigation'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { storageCatalogApi } from '@/api/storageCatalog'
import StorageCreateDialog from './StorageCreateDialog.vue'
import StorageWellsDialog from './StorageWellsDialog.vue'
import { workspaceTreeData, refreshWorkspaceStorages, removeWorkspaceStorage } from '@/utils/workspaceTreeState'

const route = useRoute()
const router = useRouter()
const deletingStorage = ref(false)

const storageDialog = ref(null)
const storageWellsDialog = ref(null)
const storageScope = computed(() => {
  const root = workspaceTreeData.value.find(node => node.id === 'g-reservoir')
  return root ? { projectId: root.projectId, gasReservoirId: root.gasReservoirId } : null
})
const createStorage = () => {
  closeStorageMenu()
  if (!storageScope.value?.projectId || !storageScope.value?.gasReservoirId) {
    ElMessage.warning('请先加载项目后再创建储气库')
    return
  }
  storageDialog.value?.open()
}
const storageCreated = async (storage, scope) => {
  ElMessage.success(`储气库“${storage.name}”已建立`)
  const root = workspaceTreeData.value.find(node => node.id === 'g-reservoir')
  if (Number(root?.projectId) !== scope.projectId || Number(root?.gasReservoirId) !== scope.gasReservoirId) return
  try {
    await refreshWorkspaceStorages()
    if (Number(root.projectId) === scope.projectId && Number(root.gasReservoirId) === scope.gasReservoirId) root.expanded = true
  } catch {
    ElMessage.warning('储气库已保存，但目录刷新失败，请刷新页面查看，不要重复创建')
  }
}

const props = defineProps({
  nodes: { type: Array, default: () => [] },
  activeId: { type: [String, Number], default: '' },
  keyword: { type: String, default: '' },
  collapsed: { type: Boolean, default: false },
  loading: { type: Boolean, default: false }
})

const emit = defineEmits([
  'update:keyword',
  'update:collapsed',
  'select',
  'expand',
  'node-contextmenu'
])
const storageMenu = ref({ visible: false, x: 0, y: 0, node: null })
const closeStorageMenu = () => { storageMenu.value.visible = false }
const viewStorageWells = () => {
  const node = storageMenu.value.node
  closeStorageMenu()
  if (node?.type === 'reservoir') storageWellsDialog.value?.open(node)
}
const deleteStorage = async () => {
  const node = storageMenu.value.node
  closeStorageMenu()
  if (deletingStorage.value || node?.type !== 'reservoir') return
  // 固定右击目标，不使用当前高亮井/库；等待确认时切换目录也不能改变删除对象。
  const target = { storageId: Number(node.storageId), projectId: Number(node.projectId), gasReservoirId: Number(node.gasReservoirId) }
  if (!Object.values(target).every(id => Number.isSafeInteger(id) && id > 0)) return
  deletingStorage.value = true
  try {
    try {
      await ElMessageBox.confirm(
        `确定永久删除储气库“${node.label}”吗？该库的库容设计、损耗评价、水侵分析历史及明细、包含单井的关联将一并删除，无法撤销。井本身、单井数据和单井计算结果以及其他库的数据不会删除。`,
        '删除库', { type: 'warning', confirmButtonText: '永久删除', cancelButtonText: '取消',
          confirmButtonClass: 'el-button--danger', autofocus: false, distinguishCancelAndClose: true })
    } catch { return }
    try {
      await storageCatalogApi.delete(target.storageId, target.projectId, target.gasReservoirId)
    } catch { return } // 请求层统一显示失败原因；失败时保留目录和当前页面。
    removeWorkspaceStorage(target)
    ElMessage.success(`储气库“${node.label}”及其库级数据已删除，单井数据已保留`)
    if (route.query.scope === 'reservoir' && Object.keys(target).every(key => Number(route.query[key]) === target[key])) {
      await router.replace({ name: 'IprInterface', query: { projectId: target.projectId, gasReservoirId: target.gasReservoirId } })
    }
  } finally { deletingStorage.value = false }
}
const handleNodeContextMenu = (node, event) => {
  closeStorageMenu()
  // 保留原有记录的右键操作，也让父页面关闭之前打开的菜单。
  emit('node-contextmenu', node, event)
  if (node.id !== 'g-reservoir' && !(node.type === 'reservoir' && Number(node.storageId) > 0)) return
  storageMenu.value = {
    visible: true,
    node,
    x: Math.max(8, Math.min(event.clientX, window.innerWidth - 198)),
    y: Math.max(8, Math.min(event.clientY, window.innerHeight - (node.id === 'g-reservoir' ? 54 : 86)))
  }
}
const handleMenuKeydown = event => {
  if (event.key === 'Escape') closeStorageMenu()
}
watch(() => props.collapsed, closeStorageMenu)
watch(() => props.nodes, nodes => {
  ensurePipelineNavigation(nodes)
  ensureWellboreNavigation(nodes)
  ensureNodalNavigation(nodes)
}, { deep: true, immediate: true })

const panelEl = ref(null)
const minWidth = 262
const panelWidth = ref(minWidth)
const dragging = ref(false)
const maxWidth = ref(520)
const panelStyle = computed(() => {
  const width = props.collapsed ? 22 : panelWidth.value
  return { width: width + 'px', minWidth: width + 'px' }
})
let parentObserver
let startX = 0
let startWidth = minWidth
let previousCursor = ''
let previousSelect = ''
const setWidth = value => { panelWidth.value = Math.max(minWidth, Math.min(maxWidth.value, value)) }
function updateWidthLimit () {
  const available = panelEl.value?.parentElement?.clientWidth || window.innerWidth
  maxWidth.value = Math.max(minWidth, Math.min(520, available - 320))
  setWidth(panelWidth.value)
}
function moveResize (event) {
  if (dragging.value) setWidth(startWidth + event.clientX - startX)
}
function stopResize () {
  if (!dragging.value) return
  dragging.value = false
  window.removeEventListener('pointermove', moveResize)
  window.removeEventListener('pointerup', stopResize)
  window.removeEventListener('pointercancel', stopResize)
  window.removeEventListener('blur', stopResize)
  document.body.style.cursor = previousCursor
  document.body.style.userSelect = previousSelect
}
function startResize (event) {
  if (props.collapsed || event.button !== 0) return
  event.preventDefault()
  stopResize()
  updateWidthLimit()
  startX = event.clientX
  startWidth = panelWidth.value
  previousCursor = document.body.style.cursor
  previousSelect = document.body.style.userSelect
  dragging.value = true
  document.body.style.cursor = 'col-resize'
  document.body.style.userSelect = 'none'
  window.addEventListener('pointermove', moveResize)
  window.addEventListener('pointerup', stopResize)
  window.addEventListener('pointercancel', stopResize)
  window.addEventListener('blur', stopResize)
}
watch(() => props.collapsed, stopResize)
onMounted(() => {
  // 新建目录由节点的 defaultExpanded 控制默认收起。
  // 工作台路由切换也会重新挂载侧栏，不能因此清除用户已展开的公共目录状态。
  window.addEventListener('pointerdown', closeStorageMenu)
  window.addEventListener('keydown', handleMenuKeydown)
  window.addEventListener('resize', closeStorageMenu)
  window.addEventListener('scroll', closeStorageMenu, true)
  refreshWorkspaceStorages().catch(error => {
    const response = error?.response
    const detail = response?.data?.msg || response?.data?.message
    const reason = typeof detail === 'string' && detail.trim()
      ? detail.trim().slice(0, 120)
      : response?.status ? `HTTP ${response.status}` : '连接失败'
    ElMessage.error(`储气库目录加载失败：${reason}`)
  })
  updateWidthLimit()
  parentObserver = new ResizeObserver(updateWidthLimit)
  if (panelEl.value?.parentElement) parentObserver.observe(panelEl.value.parentElement)
})
onBeforeUnmount(() => {
  window.removeEventListener('pointerdown', closeStorageMenu)
  window.removeEventListener('keydown', handleMenuKeydown)
  window.removeEventListener('resize', closeStorageMenu)
  window.removeEventListener('scroll', closeStorageMenu, true)
  stopResize()
  parentObserver?.disconnect()
})
</script>

<template>
  <aside ref="panelEl" class="workspace-side-panel" :class="{ collapsed, resizing: dragging }" :style="panelStyle">
    <button
      v-if="collapsed"
      class="workspace-side-collapsed-tab"
      type="button"
      title="展开目录"
      @click="emit('update:collapsed', false)"
    >
      目录
    </button>

    <div v-show="!collapsed" class="workspace-side-search">
      <el-input
        :model-value="keyword"
        size="small"
        clearable
        placeholder="搜索井名"
        @update:model-value="emit('update:keyword', $event)"
      />
      <button
        class="workspace-side-toggle"
        type="button"
        title="收起目录"
        @click="emit('update:collapsed', true)"
      >
        <svg width="14" height="14" viewBox="0 0 24 24" fill="#777" aria-hidden="true">
          <path d="M16,12V4H17V2H7V4H8V12L6,14V16H11.2V22H12.8V16H18V14L16,12Z" />
        </svg>
      </button>
    </div>

    <div v-show="!collapsed" v-loading="loading" class="workspace-side-tree">
      <TreeNode
        v-for="node in nodes"
        :key="node.id"
        :node="node"
        :active-id="activeId"
        @select="emit('select', $event)"
        @expand="emit('expand', $event)"
        @node-contextmenu="handleNodeContextMenu"
      />
    </div>
    <div v-if="!collapsed" class="workspace-side-resizer" role="separator" tabindex="0"
      aria-label="调整井目录宽度" aria-orientation="vertical"
      :aria-valuemin="minWidth" :aria-valuemax="maxWidth" :aria-valuenow="panelWidth"
      title="左右拖动调整目录宽度"
      @pointerdown="startResize"
      @keydown.left.prevent="setWidth(panelWidth - 10)"
      @keydown.right.prevent="setWidth(panelWidth + 10)"
      @keydown.home.prevent="setWidth(minWidth)"
      @keydown.end.prevent="setWidth(maxWidth)"
    />
  </aside>
  <Teleport to="body">
    <div v-if="storageMenu.visible" class="storage-context-menu" role="menu" aria-label="库目录操作"
      :style="{ left: `${storageMenu.x}px`, top: `${storageMenu.y}px` }"
      @pointerdown.stop @click.stop @contextmenu.prevent.stop>
      <button v-if="storageMenu.node?.id === 'g-reservoir'" type="button" role="menuitem" @click="createStorage">新建储气库</button>
      <template v-else>
        <button type="button" role="menuitem" @click="viewStorageWells">查看包含单井</button>
        <button type="button" role="menuitem" class="danger" :disabled="deletingStorage" @click="deleteStorage">删除库</button>
      </template>
    </div>
  </Teleport>
  <StorageCreateDialog ref="storageDialog" :scope="storageScope" @created="storageCreated" />
  <StorageWellsDialog ref="storageWellsDialog" :scope="storageScope" />
</template>

<style lang="scss" scoped>
.storage-context-menu {
  position: fixed;
  z-index: 4000;
  width: 190px;
  box-sizing: border-box;
  padding: 6px;
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  box-shadow: 0 8px 22px rgba(0, 0, 0, 0.18);

  button {
    width: 100%;
    height: 32px;
    padding: 0 10px;
    border: 0;
    border-radius: 5px;
    background: transparent;
    color: #333;
    text-align: left;
    font-size: 13px;
    cursor: pointer;
    &:hover, &:focus-visible { background: #f5f7fa; }
    &.danger { color: #bc504b; }
    &:disabled { opacity: .45; cursor: not-allowed; }
  }
}

.workspace-side-panel {
  width: 262px;
  min-width: 262px;
  display: flex;
  flex-direction: column;
  border-right: 1px solid #e0e0e0;
  background: #fff;
  position: relative;
  flex-shrink: 0;
  box-sizing: border-box;
  transition: width 0.16s ease, min-width 0.16s ease;
  &.resizing { transition: none; }

  &.collapsed {
    width: 22px;
    min-width: 22px;
    border-right: 0;
  }
}

.workspace-side-resizer {
  position: absolute;
  top: 0;
  right: -3px;
  width: 6px;
  height: 100%;
  z-index: 10;
  cursor: col-resize;
  touch-action: none;
  &:hover, &:focus-visible { background: rgba(244, 208, 0, .25); outline: none; }
}
.resizing .workspace-side-resizer { background: rgba(244, 208, 0, .25); }

.workspace-side-search {
  padding: 6px 6px 4px;
  border-bottom: 1px solid #e0e0e0;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 4px;
}

.workspace-side-tree {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 6px 4px;
}

.workspace-side-toggle {
  width: 20px;
  height: 20px;
  padding: 0;
  border: 0;
  border-radius: 2px;
  background: transparent;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  flex-shrink: 0;

  &:hover { background: #eef4ff; }
}

.workspace-side-collapsed-tab {
  width: 22px;
  height: 54px;
  padding: 0;
  border: 0;
  background: transparent;
  color: #333;
  cursor: pointer;
  writing-mode: vertical-rl;
  text-orientation: mixed;
  font-size: 13px;
  line-height: 1;
  display: flex;
  align-items: center;
  justify-content: center;

  &:hover {
    background: #eef4ff;
    color: #1677ff;
  }
}
</style>
