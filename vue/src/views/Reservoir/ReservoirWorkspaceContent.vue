<script setup>
import { computed } from 'vue'

const props = defineProps({
  reservoir: { type: Object, default: null },
  command: { type: Object, default: null }
})

const reservoirLabel = computed(() => props.reservoir?.label || '未选择库')
const commandPath = computed(() => {
  const path = props.command?.path
  if (Array.isArray(path) && path.length) return path.filter(Boolean)
  return [props.command?.group, props.command?.parent, props.command?.name]
    .filter((item, index, items) => item && item !== items[index - 1])
})
const title = computed(() => commandPath.value.at(-1) || '库级工作区')
const tabTitle = computed(() => [reservoirLabel.value, ...commandPath.value].join(' · '))

// 库级功能有独立的数据范围；入口页不复用单井接口，也不触发计算或保存。
</script>

<template>
  <section class="reservoir-workspace" :aria-label="title">
    <div class="workspace-tabs">
      <div class="workspace-tab" :title="tabTitle">{{ tabTitle }}</div>
    </div>

    <div class="workspace-content">
      <div class="reservoir-context">
        <span class="scope-label">库级</span>
        <span class="reservoir-name">{{ reservoirLabel }}</span>
      </div>

      <nav v-if="commandPath.length" class="command-path" aria-label="当前库功能路径">
        <template v-for="(item, index) in commandPath" :key="`${index}-${item}`">
          <span v-if="index" class="path-separator" aria-hidden="true">/</span>
          <span :aria-current="index === commandPath.length - 1 ? 'page' : undefined">{{ item }}</span>
        </template>
      </nav>

      <h1>{{ title }}</h1>
      <p class="workspace-description">此处为库级功能入口，参数设置与计算结果将在接入对应功能后展示。</p>
      <p v-if="!commandPath.length" class="workspace-hint">请从顶部菜单选择要进入的库功能。</p>
    </div>
  </section>
</template>

<style scoped>
.reservoir-workspace {
  display: flex;
  flex-direction: column;
  width: 100%;
  min-width: 0;
  min-height: 100%;
  background: #fff;
  color: #303133;
}

.workspace-tabs {
  display: flex;
  flex: 0 0 34px;
  min-width: 0;
  height: 34px;
  border-bottom: 1px solid #e4e7ed;
  background: #fafafa;
}

.workspace-tab {
  box-sizing: border-box;
  max-width: 100%;
  padding: 0 12px;
  overflow: hidden;
  border-right: 1px solid #e4e7ed;
  background: #f4d000;
  color: #202020;
  font-size: 12px;
  font-weight: 600;
  line-height: 34px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workspace-content {
  padding: 24px 28px;
  overflow-wrap: anywhere;
}

.reservoir-context {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 20px;
  font-size: 13px;
}

.scope-label {
  padding: 2px 6px;
  border: 1px solid #dcdfe6;
  color: #606266;
  font-size: 12px;
}

.reservoir-name {
  font-weight: 600;
}

.command-path {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
  color: #606266;
  font-size: 12px;
  line-height: 1.7;
}

.path-separator {
  color: #c0c4cc;
}

h1 {
  margin: 0 0 12px;
  color: #202020;
  font-size: 18px;
  font-weight: 600;
  line-height: 1.5;
}

.workspace-description,
.workspace-hint {
  margin: 0 0 8px;
  color: #606266;
  font-size: 13px;
  line-height: 1.8;
}

.workspace-hint {
  color: #909399;
}

@media (max-width: 640px) {
  .workspace-content {
    padding: 20px 16px;
  }
}
</style>
