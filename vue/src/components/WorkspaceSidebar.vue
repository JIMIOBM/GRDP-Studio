<script setup>
/**
 * 解析融合工作台公共左侧目录。
 *
 * IprInterface.vue 与 SingleWellProductivityInterface.vue 共用本组件，
 * 以保证井目录的搜索、折叠、树节点样式和交互方式完全一致。
 * 具体树数据与节点业务仍由各工作台页面负责。
 */
import TreeNode from '@/views/TreeNode.vue'

defineProps({
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
</script>

<template>
  <aside class="workspace-side-panel" :class="{ collapsed }">
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
  transition: width 0.16s ease, min-width 0.16s ease;

  &.collapsed {
    width: 22px;
    min-width: 22px;
    border-right: 0;
  }
}

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
