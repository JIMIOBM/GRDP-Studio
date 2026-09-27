<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({
  result: { type: Object, default: null }
})

const chartElement = ref(null)
const selectedCaseIndex = ref(0)
let chart
let resizeObserver

const cases = computed(() => Array.isArray(props.result?.cases) ? props.result.cases : [])
const selectedCase = computed(() => cases.value[selectedCaseIndex.value] || cases.value[0] || null)
const profileVariables = computed(() => selectedCase.value?.profile?.variables || [])
const distance = computed(() => profileVariables.value.find(item => item.variable === 'TotalDistance')?.values || [])
const pressure = computed(() => profileVariables.value.find(item => item.variable === 'Pressure')?.values || [])
const temperature = computed(() => profileVariables.value.find(item => item.variable === 'Temperature')?.values || [])
const systemRows = computed(() => selectedCase.value?.system || [])
const nodeRows = computed(() => (selectedCase.value?.node || []).flatMap(node =>
  (node.variables || []).map(variable => ({ node: node.node, ...variable }))))
const chartAvailable = computed(() => distance.value.length > 0 && pressure.value.length === distance.value.length)
const displayNumber = value => typeof value === 'number' && Number.isFinite(value) ? value.toFixed(3) : '-'
const axisName = (name, unit) => unit ? name + ' (' + unit + ')' : name

const renderChart = async () => {
  await nextTick()
  if (!chartElement.value || !chartAvailable.value) {
    chart?.dispose()
    chart = null
    return
  }
  chart ||= echarts.init(chartElement.value)
  const series = [{
    name: axisName('压力', profileVariables.value.find(item => item.variable === 'Pressure')?.unit),
    type: 'line',
    showSymbol: false,
    smooth: false,
    data: distance.value.map((x, index) => [x, pressure.value[index]]),
    lineStyle: { width: 2, color: '#2B6CB3' },
    itemStyle: { color: '#2B6CB3' }
  }]
  if (temperature.value.length === distance.value.length) {
    series.push({
      name: axisName('温度', profileVariables.value.find(item => item.variable === 'Temperature')?.unit),
      type: 'line',
      showSymbol: false,
      yAxisIndex: 1,
      data: distance.value.map((x, index) => [x, temperature.value[index]]),
      lineStyle: { width: 2, color: '#B32D2D' },
      itemStyle: { color: '#B32D2D' }
    })
  }
  chart.setOption({
    animation: false,
    tooltip: { trigger: 'axis' },
    legend: { top: 4, right: 8, textStyle: { fontSize: 12 } },
    grid: { left: 52, right: 52, top: 34, bottom: 42 },
    xAxis: { type: 'value', name: 'TotalDistance', nameLocation: 'middle', nameGap: 28 },
    yAxis: [
      { type: 'value', name: '压力', nameTextStyle: { color: '#2B6CB3' } },
      { type: 'value', name: '温度', nameTextStyle: { color: '#B32D2D' } }
    ],
    series
  }, true)
}

watch(() => props.result, () => {
  selectedCaseIndex.value = 0
  renderChart()
}, { deep: true })
watch(selectedCaseIndex, renderChart)
onMounted(() => {
  renderChart()
  resizeObserver = new ResizeObserver(() => chart?.resize())
  if (chartElement.value) resizeObserver.observe(chartElement.value)
})
onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  chart?.dispose()
})
</script>

<template>
  <section class="system-analysis-result" aria-label="PIPESIM 系统分析结果">
    <el-empty v-if="!result" description="尚未选择成功的系统分析运行，或结果未通过展示契约校验" :image-size="72" />
    <template v-else>
      <div class="result-head">
        <div>
          <strong>PIPESIM System Analysis</strong>
          <span>Study：{{ result.study }} · BranchTerminator：{{ result.branchTerminator }} · 出口压力：{{ result.outletPressurePsi }} psia</span>
        </div>
        <el-tag type="success">VALID_FULL · {{ result.cases.length }} 个工况</el-tag>
      </div>
      <div class="case-controls">
        <span>LiquidFlowRate 工况</span>
        <el-radio-group v-model="selectedCaseIndex" size="small">
          <el-radio-button v-for="(item, index) in result.cases" :key="item.caseName + '-' + item.scanValue" :value="index">
            {{ item.scanValue }}
          </el-radio-button>
        </el-radio-group>
        <span class="muted">官方 case：{{ selectedCase?.caseName }}</span>
      </div>
      <div class="metric-grid">
        <div v-for="item in systemRows" :key="item.variable" class="metric-card">
          <span>{{ item.variable }}</span>
          <strong>{{ displayNumber(item.value) }}</strong>
          <small>{{ item.unit || '—' }}</small>
        </div>
      </div>
      <div class="result-grid">
        <section class="result-panel">
          <h3>系统量 / 节点量</h3>
          <el-table :data="nodeRows" border size="small" max-height="260">
            <el-table-column prop="node" label="节点" min-width="130" />
            <el-table-column prop="variable" label="变量" min-width="170" />
            <el-table-column label="值" min-width="120"><template #default="{ row }">{{ displayNumber(row.value) }}</template></el-table-column>
            <el-table-column prop="unit" label="单位" width="90" />
          </el-table>
        </section>
        <section class="result-panel">
          <h3>压力 / 温度剖面</h3>
          <div ref="chartElement" class="profile-chart" />
          <p v-if="!chartAvailable" class="muted">当前工况没有可展示的 TotalDistance–Pressure 剖面。</p>
        </section>
      </div>
      <section class="result-panel profile-table-panel">
        <h3>剖面数据（{{ selectedCase.profile.pointCount }} 点）</h3>
        <el-table :data="distance.map((value, index) => ({ distance: value, pressure: pressure[index], temperature: temperature[index] }))" border size="small" max-height="260">
          <el-table-column prop="distance" label="TotalDistance" min-width="140" />
          <el-table-column prop="pressure" label="Pressure" min-width="140" />
          <el-table-column prop="temperature" label="Temperature" min-width="140" />
        </el-table>
      </section>
    </template>
  </section>
</template>

<style lang="scss" scoped>
.system-analysis-result { min-width: 0; }
.result-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; padding: 10px 12px; background: #f5f7fa; border: 1px solid #e4e7ed; }
.result-head strong, .result-head span { display: block; }
.result-head strong { color: #2b6cb3; font-size: 15px; }
.result-head span { margin-top: 4px; color: #73777d; font-size: 12px; }
.case-controls { display: flex; flex-wrap: wrap; align-items: center; gap: 9px; padding: 10px 2px; }
.case-controls > span:first-child { color: #606266; font-size: 12px; }
.muted { color: #909399; font-size: 12px; }
.metric-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 8px; margin-bottom: 10px; }
.metric-card { padding: 9px 11px; border: 1px solid #e4e7ed; background: #fff; }
.metric-card span, .metric-card small { display: block; color: #909399; font-size: 12px; }
.metric-card strong { display: block; margin: 3px 0; color: #303133; font-size: 18px; }
.result-grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1.2fr); gap: 10px; }
.result-panel { min-width: 0; border: 1px solid #e4e7ed; padding: 10px; }
.result-panel h3 { margin: 0 0 8px; color: #303133; font-size: 13px; }
.profile-chart { width: 100%; height: 310px; }
.profile-table-panel { margin-top: 10px; }
@media (max-width: 1000px) { .result-grid { grid-template-columns: 1fr; } }
</style>
