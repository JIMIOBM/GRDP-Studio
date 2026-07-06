<script setup>
import { ref, watch } from "vue";

const props = defineProps({
  node: { type: Object, required: true },
  activeId: { type: [String, Number], default: "" },
  autoExpandIds: { type: Array, default: () => [] },
});

const emit = defineEmits(["select"]);

// 默认折叠，但如果 id 在 autoExpandIds 中就初始展开
const getInitialExpanded = () => props.autoExpandIds.includes(props.node.id);
const expanded = ref(getInitialExpanded());

// 监听 autoExpandIds 变化，如果节点 id 在新列表中就展开
watch(
  () => props.autoExpandIds,
  (newIds) => {
    if (newIds && newIds.includes(props.node.id)) {
      expanded.value = true;
    }
  },
  { deep: true },
);

// 是否有子节点
const hasChildren = () => props.node.children && props.node.children.length > 0;

// 点击箭头：只切换展开/收起
const toggle = (e) => {
  if (hasChildren()) expanded.value = !expanded.value;
  e && e.stopPropagation();
};

// 点击节点：有子节点就切换展开，同时发送选中事件
const handleClick = () => {
  if (hasChildren()) {
    expanded.value = !expanded.value;
  }
  emit("select", props.node);
};

const onChildSelect = (n) => emit("select", n);
</script>

<template>
  <div class="tree-node">
    <div
      class="node-label"
      :class="{ active: node.id === activeId }"
      @click="handleClick"
    >
      <!-- 所有有子节点的都显示 ▸/▾ 箭头 -->
      <span class="caret" @click="toggle" v-if="hasChildren()">
        <span class="caret-icon">{{ expanded ? "▾" : "▸" }}</span>
      </span>
      <span v-else class="caret-placeholder"></span>

      <!-- 所有节点都用相同的文件夹图标 -->
      <span class="node-icon" :class="{ open: expanded && hasChildren() }"></span>

      <span class="node-text">{{ node.label }}</span>
    </div>

    <div class="node-children" v-show="expanded && hasChildren()">
      <TreeNode
        v-for="child in node.children"
        :key="child.id"
        :node="child"
        :active-id="activeId"
        :auto-expand-ids="autoExpandIds"
        @select="onChildSelect"
      />
    </div>
  </div>
</template>

<style lang="scss" scoped>
.tree-node {
  font-size: 11px;
}

.node-label {
  display: flex;
  align-items: center;
  width: max-content;
  min-width: 100%;
  height: 18px;
  padding-left: 1px;
  padding-right: 6px;
  cursor: pointer;
  color: #333333;
  white-space: nowrap;
  border: 1px solid transparent;
  margin: 0;

  &:hover {
    background-color: #eef5ff;
    border-color: #d6e6fb;
  }

  &.active {
    background-color: #dcecff;
    color: #1b1b1b;
    font-weight: 500;
    border-color: #cfe1f7;
  }

  .caret {
    width: 12px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    margin-right: 1px;
    color: #9a9a9a;
    font-size: 10px;
    flex: 0 0 12px;

    &:hover {
      color: #333;
    }

    .caret-icon {
      line-height: 1;
    }
  }

  .caret-placeholder {
    display: inline-block;
    width: 12px;
    margin-right: 1px;
    flex: 0 0 12px;
  }

  .node-icon {
    position: relative;
    width: 13px;
    height: 11px;
    margin-right: 3px;
    flex: 0 0 13px;

    &::before {
      content: "";
      position: absolute;
      left: 1px;
      top: 0;
      width: 6px;
      height: 3px;
      background: #f6d96d;
      border: 1px solid #dfc55f;
      border-bottom: none;
      border-radius: 1px 1px 0 0;
    }

    &::after {
      content: "";
      position: absolute;
      left: 0;
      top: 3px;
      width: 12px;
      height: 8px;
      background: #f1d56d;
      border: 1px solid #d8bd55;
      border-radius: 1px;
      box-sizing: border-box;
    }

    &.open::after {
      background: #f5dc82;
    }
  }

  .node-text {
    flex: 0 0 auto;
    line-height: 18px;
  }
}

/* 子节点缩进 */
.node-children {
  padding-left: 14px;
}
</style>
