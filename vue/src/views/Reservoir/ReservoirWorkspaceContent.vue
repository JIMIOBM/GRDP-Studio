<script setup>
/**
 * 库级工作区入口：根据菜单命令选择要显示的页面，本文件不负责损耗计算。
 * LossEvaluation/：损耗评价，微观、逸散、井筒、地面损耗分别使用独立 Vue 页面。
 * ProductivityEvaluation/：产能评价，独立管理库的多周期、多方法、注采对比，不引用井的页面。
 * 新增其他板块时，在 Reservoir 下建立对应板块文件夹，本入口只负责分发页面。
 * 其他尚未接入专用页面的库级功能，显示下方的通用入口占位界面。
 */
import { computed } from 'vue'
import MicroscopicLoss from './LossEvaluation/MicroscopicLoss.vue'
import EscapeLoss from './LossEvaluation/EscapeLoss.vue'
import WellboreLoss from './LossEvaluation/WellboreLoss.vue'
import SurfaceLoss from './LossEvaluation/SurfaceLoss.vue'
import MultiPeriodComparison from './ProductivityEvaluation/MultiPeriodComparison.vue'
import MultiMethodComparison from './ProductivityEvaluation/MultiMethodComparison.vue'
import InjectionProductionComparison from './ProductivityEvaluation/InjectionProductionComparison.vue'
import MaterialBalance from './InventoryEvaluation/MaterialBalance.vue'

const lossPages = { '微观损耗': MicroscopicLoss, '逸散性损耗': EscapeLoss, '井筒损耗': WellboreLoss, '地面损耗': SurfaceLoss }
const comparisonPages = { '多周期': MultiPeriodComparison, '多方法': MultiMethodComparison, '注采对比': InjectionProductionComparison }

// reservoir 是当前储气库节点（包含项目范围和 storageId）；command 是菜单功能及其层级路径。
const props = defineProps({
  reservoir: { type: Object, default: null },
  command: { type: Object, default: null }
})

const reservoirLabel = computed(() => props.reservoir?.label || '未选择库')
// 优先使用完整菜单路径；旧入口未提供 path 时，用分组、父菜单和功能名拼接标题。
const commandPath = computed(() => {
  const path = props.command?.path
  if (Array.isArray(path) && path.length) return path.filter(Boolean)
  return [props.command?.group, props.command?.parent, props.command?.name]
    .filter((item, index, items) => item && item !== items[index - 1])
})
const title = computed(() => commandPath.value.at(-1) || '库级工作区')
const tabTitle = computed(() => [reservoirLabel.value, ...commandPath.value].join(' · '))
const isGeologicalLoss = computed(() =>
  props.command?.group === '损耗评价'
  && props.command?.parent === '地质损耗'
  && ['微观损耗', '逸散性损耗'].includes(props.command?.name)
)
const isVentLoss = computed(() =>
  props.command?.group === '损耗评价' && ['井筒损耗', '地面损耗'].includes(props.command?.name)
)
const isPeriodComparison = computed(() => props.command?.group === '产能评价'
  && props.command?.parent === '产能对比' && ['多周期', '多方法', '注采对比'].includes(props.command?.name))
const isMaterialBalance = computed(() => props.command?.group === '库存评估'
  && props.command?.parent === '物质平衡法' && props.command?.name === '物质平衡')

// 库级功能有独立的数据范围；入口页不复用单井接口，也不触发计算或保存。
// 下方组件key同时包含库ID和方法，切库或切方法时重建表单，避免沿用上一库的参数及计算结果。
</script>

<template>
  <!-- 每个损耗功能都有独立页面；项目、库或功能变化时销毁旧页面状态。 -->
  <component :is="lossPages[command.name]"
    v-if="isGeologicalLoss || isVentLoss"
    :key="`${reservoir?.projectId}-${reservoir?.gasReservoirId}-${reservoir?.storageId}-${command.name}`"
    :reservoir="reservoir"
  />
  <!-- 与单井页面一致：先显示模块页签，再显示参数栏和右侧分析结果页签。 -->
  <section v-else-if="isPeriodComparison" class="storage-comparison-page" :aria-label="`库产能对比-${command.name}`">
    <div class="comparison-module-tabs">
      <div class="comparison-module-tab"><span>产能对比-{{ command.name }}</span></div>
    </div>
    <component :is="comparisonPages[command.name]"
      :key="`${reservoir?.projectId}-${reservoir?.gasReservoirId}-${reservoir?.storageId}-${command.name}`"
      :project-id="reservoir?.projectId" :gas-reservoir-id="reservoir?.gasReservoirId"
      :storage-id="reservoir?.storageId" :storage-name="reservoirLabel" />
  </section>
  <MaterialBalance v-else-if="isMaterialBalance"
    :key="`${reservoir?.projectId}-${reservoir?.gasReservoirId}-${reservoir?.storageId}-material-balance`"
    :reservoir="reservoir" />
  <!-- 其他尚未接入的库级功能仍保留占位入口。 -->
  <section v-else class="reservoir-workspace" :aria-label="title">
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
/* 对齐 SingleWellProductivityInterface 的 productivity-content、test-tabs 和 test-tab。
   仅作用于库产能对比，不修改损耗评价或其他库级页面。 */
.storage-comparison-page {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #fff;
}
.comparison-module-tabs {
  height: 34px;
  flex: 0 0 34px;
  display: flex;
  align-items: center;
  border-bottom: 1px solid transparent;
  background: #fafafa;
  overflow-x: auto;
  overflow-y: hidden;
}
.comparison-module-tab {
  height: 34px;
  max-width: 340px;
  padding: 0 12px;
  background: #f4d000;
  display: flex;
  align-items: center;
  box-sizing: border-box;
  color: #202020;
  font: 600 14px Arial, sans-serif;
  border-right: 1px solid #e4e7ed;
  border-bottom: 2px solid transparent;
  white-space: nowrap;
}
.comparison-module-tab > span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 14px;
  font-weight: 600;
  line-height: normal;
  white-space: nowrap;
}

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
