<script setup>
import { ref, watch } from 'vue'
import { ArrowDown, ArrowRight, Folder, Document } from '@element-plus/icons-vue'

const props = defineProps({
  node: { type: Object, required: true },
  activeId: { type: [String, Number], default: '' },
  // 所有树节点默认关闭，只有用户点击展开后才触发 expand 事件和对应接口。
  defaultExpanded: { type: Boolean, default: false }
})

const emit = defineEmits(['select', 'node-contextmenu', 'expand'])

const expanded = ref(props.node.defaultExpanded ?? props.defaultExpanded)
if (typeof props.node.expanded === 'boolean') {
  expanded.value = props.node.expanded
} else {
  props.node.expanded = expanded.value
}

watch(() => props.node.expanded, value => {
  if (typeof value === 'boolean') expanded.value = value
})
// lazy 节点即使尚无 children 也要显示展开箭头，首次展开时由父页面调用接口填充。
const hasChildren = () => props.node.lazy || (props.node.children && props.node.children.length > 0)

const toggle = () => {
  if (!hasChildren()) return
  expanded.value = !expanded.value
  // 展开状态写回公共树节点，跨工作台重新渲染时保持原状。
  props.node.expanded = expanded.value
  // 公共目录会在两个工作台之间复用。有时节点保留了“已展开”状态，
  // 但其懒加载结果尚未读取；这时用户第一次点击会变成收起操作。
  // 对尚未加载的懒节点，无论本次是展开还是收起，都立即触发一次读取，
  // 避免必须再点第二次才调用对应接口。
  if (expanded.value || (props.node.lazy && !props.node.loaded)) {
    emit('expand', props.node)
  }
}

const handleClick = () => {
  if (props.node.disabled) return
  emit('select', props.node)
  if (hasChildren()) {
    toggle()
  }
}

const onChildSelect = (n) => emit('select', n)
const onChildExpand = (n) => emit('expand', n)
const handleContextMenu = (event) => {
  if (props.node.disabled) return
  emit('node-contextmenu', props.node, event)
}
const onChildContextMenu = (node, event) => emit('node-contextmenu', node, event)
</script>

<template>
  <div class="tree-node">
    <div
      class="node-label"
      :class="{ active: node.id === activeId, disabled: node.disabled }"
      :aria-disabled="Boolean(node.disabled)"
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
        @expand="onChildExpand"
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

  &.disabled {
    opacity: 0.5;
    cursor: not-allowed;
    text-decoration: line-through;
    background: transparent;
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
