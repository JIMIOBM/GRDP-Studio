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

const panelEl = ref(null)
const panelWidth = ref(230)
const dragging = ref(false)
const minWidth = 180
const maxWidth = ref(520)
const panelStyle = computed(() => {
  const width = props.collapsed ? 22 : panelWidth.value
  return { width: width + 'px', minWidth: width + 'px' }
})
let parentObserver
let startX = 0
let startWidth = 230
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
  updateWidthLimit()
  parentObserver = new ResizeObserver(updateWidthLimit)
  if (panelEl.value?.parentElement) parentObserver.observe(panelEl.value.parentElement)
})
onBeforeUnmount(() => {
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
        @node-contextmenu="(node, event) => emit('node-contextmenu', node, event)"
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
</template>

<style lang="scss" scoped>
.workspace-side-panel {
  width: 230px;
  min-width: 230px;
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
