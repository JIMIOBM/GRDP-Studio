<script setup>
import { ref } from 'vue'
import { ArrowDown, ArrowRight, Folder, Document } from '@element-plus/icons-vue'

const props = defineProps({
  node: { type: Object, required: true },
  activeId: { type: [String, Number], default: '' },
  defaultExpanded: { type: Boolean, default: true }
})

const emit = defineEmits(['select', 'node-contextmenu'])

const expanded = ref(props.node.defaultExpanded ?? props.defaultExpanded)
const hasChildren = () => props.node.children && props.node.children.length > 0

const toggle = () => {
  if (hasChildren()) expanded.value = !expanded.value
}

const handleClick = () => {
  emit('select', props.node)
  if (hasChildren()) {
    toggle()
  }
}

const onChildSelect = (n) => emit('select', n)
const handleContextMenu = (event) => {
  emit('node-contextmenu', props.node, event)
}
const onChildContextMenu = (node, event) => emit('node-contextmenu', node, event)
</script>

<template>
  <div class="tree-node">
    <div
      class="node-label"
      :class="{ active: node.id === activeId }"
      @click="handleClick"
      @contextmenu.prevent.stop="handleContextMenu"
    >
      <el-icon v-if="hasChildren()" class="caret">
        <ArrowDown v-if="expanded" />
        <ArrowRight v-else />
      </el-icon>
      <span v-else class="caret-placeholder"></span>

      <el-icon class="node-icon">
        <Folder v-if="hasChildren()" />
        <Document v-else />
      </el-icon>

      <span class="node-text">{{ node.label }}</span>
    </div>

    <div class="node-children" v-show="expanded" v-if="hasChildren()">
      <TreeNode
        v-for="child in node.children"
        :key="child.id"
        :node="child"
        :active-id="activeId"
        :default-expanded="defaultExpanded"
        @select="onChildSelect"
        @node-contextmenu="onChildContextMenu"
      />
    </div>
  </div>
</template>

<style lang="scss" scoped>
.tree-node {
  font-size: 13px;
}

.node-label {
  display: flex;
  align-items: center;
  height: 24px;
  padding-left: 6px;
  cursor: pointer;
  color: #333;
  white-space: nowrap;

  &:hover {
    background-color: #f0f6ff;
  }

  &.active {
    background-color: #e3effd;
    color: #4084d9;
  }

  .caret {
    font-size: 12px;
    color: #888;
    margin-right: 2px;
  }

  .caret-placeholder {
    display: inline-block;
    width: 14px;
  }

  .node-icon {
    font-size: 14px;
    color: #d9a300;
    margin-right: 5px;
  }

  .node-text {
    line-height: 24px;
  }
}

/* 子节点缩进 */
.node-children {
  padding-left: 16px;
}
</style>
