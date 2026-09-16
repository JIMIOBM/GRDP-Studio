<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { downloadWellChart, downloadWellCsv, wellComparisonIssue } from './wellResultPresentation'

const props = defineProps({
  result: { type: Object, default: null },
  comparisonResult: { type: Object, default: null },
  sourceLabel: { type: String, default: '当前运行' },
  comparisonLabel: { type: String, default: '历史运行' }
})

const chartElement = ref(null)
let chart
let resizeObserver

const ipr = computed(() => Array.isArray(props.result?.ipr) ? props.result.ipr : [])
const vlp = computed(() => Array.isArray(props.result?.vlp) ? props.result.vlp : [])
const hasData = computed(() => ipr.value.length > 0 && vlp.value.length > 0)
const flowUnit = computed(() => props.result?.units?.flow?.displayUnit || '')
const pressureUnit = computed(() => props.result?.units?.pressure?.displayUnit || '')
const axisName = (name, unit) => unit ? `${name} (${unit})` : name
const comparisonIssue = computed(() => wellComparisonIssue(props.result, props.comparisonResult, ['flow', 'pressure']) ||
  (props.comparisonResult && (!props.comparisonResult.ipr?.length || !props.comparisonResult.vlp?.length) ? '所选运行没有节点分析曲线。' : ''))
const sources = computed(() => [
  { result: props.result, label: props.sourceLabel, history: false },
  ...(props.comparisonResult && !comparisonIssue.value ? [{ result: props.comparisonResult, label: props.comparisonLabel, history: true }] : [])
])
const selectedTable = ref('ipr')
const tableRows = computed(() => sources.value.flatMap(source => (source.result?.[selectedTable.value] || []).map(point => ({ ...point, source: source.label }))))
const exportCsv = () => downloadWellCsv([
  ['来源', '曲线', axisName('流量', flowUnit.value), axisName('压力', pressureUnit.value)],
  ...sources.value.flatMap(source => ['ipr', 'vlp'].flatMap(key => (source.result?.[key] || []).map(point => [source.label, key.toUpperCase(), point.flow, point.pressure])))
], 'pipesim-nodal.csv')

const renderChart = async () => {
  await nextTick()
  if (!chartElement.value || !hasData.value) {
    chart?.dispose()
    chart = null
    return
  }
  if (chart && chart.getDom() !== chartElement.value) {
    chart.dispose()
    chart = null
  }
  chart ||= echarts.init(chartElement.value)
  chart.setOption({
    animation: false,
    color: ['#2B6CB3', '#E88A1A'],
    title: {
      text: '节点分析曲线',
      left: 'center',
      top: 8,
      textStyle: { color: '#333', fontSize: 15, fontWeight: 600 }
    },
    legend: {
      top: 38,
      type: 'scroll',
      itemWidth: 18,
      itemHeight: 10
    },
    tooltip: {
      trigger: 'axis',
      renderMode: 'richText',
      axisPointer: { type: 'cross' }
    },
    grid: { left: 78, right: 34, top: 72, bottom: 76, containLabel: true },
    xAxis: {
      type: 'value',
      name: axisName('流量', flowUnit.value),
      nameLocation: 'middle',
      nameGap: 42,
      splitLine: { show: true, lineStyle: { color: '#dfe7f2' } },
      minorSplitLine: { show: true, lineStyle: { color: '#f1f5fa' } }
    },
    yAxis: {
      type: 'value',
      name: axisName('压力', pressureUnit.value),
      nameLocation: 'middle',
      nameGap: 54,
      splitLine: { show: true, lineStyle: { color: '#dfe7f2' } },
      minorSplitLine: { show: true, lineStyle: { color: '#f1f5fa' } }
    },
    dataZoom: [
      { type: 'inside', xAxisIndex: 0 },
      { type: 'slider', xAxisIndex: 0, height: 18, bottom: 18 }
    ],
    series: sources.value.flatMap(source => ['ipr', 'vlp'].map((key, index) => ({
      name: `${source.history ? '对比' : '当前'} · ${source.label} · ${key.toUpperCase()}`,
      type: 'line', showSymbol: false,
      lineStyle: { width: 2, type: source.history ? 'dashed' : 'solid', color: ['#2B6CB3', '#E88A1A'][index] },
      itemStyle: { color: ['#2B6CB3', '#E88A1A'][index] },
      data: source.result[key].map(point => [point.flow, point.pressure])
    })))
  }, true)
  chart.resize()
  resizeObserver?.disconnect()
  resizeObserver?.observe(chartElement.value)
}

const resizeChart = () => chart?.resize()

watch(() => [props.result, props.comparisonResult, props.sourceLabel, props.comparisonLabel], renderChart, { deep: true })
onMounted(() => {
  resizeObserver = new ResizeObserver(resizeChart)
  window.addEventListener('resize', resizeChart)
  renderChart()
})
onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  window.removeEventListener('resize', resizeChart)
  chart?.dispose()
  chart = null
})
</script>

<template>
  <section class="nodal-result">
    <div v-if="hasData" class="result-tools">
      <span>IPR {{ ipr.length }} 点 · VLP {{ vlp.length }} 点</span>
      <el-button id="well-nodal-export-png" size="small" @click="downloadWellChart(chart, 'pipesim-nodal.png')">导出图片</el-button>
      <el-button id="well-nodal-export-csv" size="small" @click="exportCsv">导出曲线 CSV</el-button>
    </div>
    <p v-if="comparisonIssue" class="comparison-note" role="status">{{ comparisonIssue }}</p>
    <div v-if="hasData" ref="chartElement" class="result-chart" />
    <el-empty v-else description="当前运行没有有效的节点分析结果" :image-size="72" />
    <div v-if="hasData" class="curve-tables">
      <el-radio-group v-model="selectedTable" size="small" aria-label="节点曲线数据表">
        <el-radio-button value="ipr">IPR 数据</el-radio-button>
        <el-radio-button value="vlp">VLP 数据</el-radio-button>
      </el-radio-group>
      <el-table :data="tableRows" border size="small" max-height="260">
        <el-table-column type="index" label="#" width="54" align="center" />
        <el-table-column prop="source" label="来源" min-width="170" />
        <el-table-column prop="flow" :label="axisName('流量', flowUnit)" min-width="130" />
        <el-table-column prop="pressure" :label="axisName('压力', pressureUnit)" min-width="130" />
      </el-table>
    </div>
  </section>
</template>

<style lang="scss" scoped>
.nodal-result { min-height: 0; display: flex; flex-direction: column; gap: 14px; }
.result-chart { height: 410px; min-height: 300px; border: 1px solid #e4e9f0; background: #fff; }
.curve-tables { display: flex; flex-direction: column; gap: 8px; }
.result-tools { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; color: #666; font-size: 13px; }
.result-tools > span { margin-right: auto; }
.result-tools :deep(.el-button + .el-button) { margin-left: 0; }
.comparison-note { margin: 0; color: #986319; font-size: 13px; }
.curve-tables :deep(.el-table:first-child::before) { background-color: #2b6cb3; }
.curve-tables :deep(.el-table:last-child::before) { background-color: #e88a1a; }
@media (max-width: 900px) {
  .result-chart { height: 340px; }
  .curve-tables { grid-template-columns: 1fr; }
}
</style>
