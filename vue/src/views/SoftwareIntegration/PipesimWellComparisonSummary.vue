<script setup>
import { computed } from 'vue'
import { wellComparisonDeltaRows, wellScenarioLabel } from './wellResultPresentation'

const props = defineProps({
  result: { type: Object, default: null },
  comparisonResult: { type: Object, default: null },
  currentRun: { type: Object, default: null },
  comparisonRun: { type: Object, default: null },
  currentLabel: { type: String, default: '当前运行' },
  comparisonLabel: { type: String, default: '对比运行' }
})

const rows = computed(() => wellComparisonDeltaRows(props.result, props.comparisonResult))
const formatUnit = (name, unit) => unit ? `${name} (${unit})` : name
const formatValue = value => value === null || value === undefined ? '-' : String(value)
</script>

<template>
  <section class="well-comparison-summary" aria-label="井筒方案对比摘要">
    <div class="comparison-run-cards">
      <div class="comparison-run-card current">
        <span>当前运行</span>
        <strong>{{ currentLabel }}</strong>
        <small>{{ wellScenarioLabel(currentRun) }}</small>
      </div>
      <div class="comparison-run-card comparison">
        <span>对比运行</span>
        <strong>{{ comparisonLabel }}</strong>
        <small>{{ wellScenarioLabel(comparisonRun) }}</small>
      </div>
    </div>
    <p class="comparison-summary-note">差值 = 当前 − 对比；仅统计流量或深度坐标完全相同的返回点，不插值、不换算单位。</p>
    <el-table :data="rows" border size="small">
      <el-table-column prop="label" label="结果序列" min-width="130" />
      <el-table-column prop="coordinateLabel" label="对齐坐标" min-width="110">
        <template #default="{ row }">{{ formatUnit(row.coordinateLabel, row.coordinateUnit) }}</template>
      </el-table-column>
      <el-table-column prop="valueUnit" label="结果单位" min-width="100" />
      <el-table-column prop="count" label="对齐点数" width="95" align="right" />
      <el-table-column label="差值范围" min-width="170" align="right">
        <template #default="{ row }">{{ row.count ? `${formatValue(row.min)} ～ ${formatValue(row.max)}` : '无相同坐标' }}</template>
      </el-table-column>
    </el-table>
  </section>
</template>

<style lang="scss" scoped>
.well-comparison-summary { display: flex; flex-direction: column; gap: 8px; margin: 0 0 12px; padding: 10px; border: 1px solid #d9e2ec; background: #fbfcfe; }
.comparison-run-cards { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
.comparison-run-card { min-width: 0; padding: 8px 10px; border-left: 3px solid #2b6cb3; background: #f2f7fc; }
.comparison-run-card.comparison { border-left-color: #e88a1a; background: #fff8ee; }
.comparison-run-card span, .comparison-run-card small { display: block; color: #737a84; font-size: 11px; }
.comparison-run-card strong { display: block; margin: 3px 0; overflow: hidden; color: #303133; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.comparison-summary-note { margin: 0; color: #737a84; font-size: 12px; }
@media (max-width: 760px) { .comparison-run-cards { grid-template-columns: 1fr; } }
</style>
