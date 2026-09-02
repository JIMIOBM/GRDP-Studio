<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({
  result: { type: Object, default: null }
})

const chartElement = ref(null)
let chart

const ipr = computed(() => Array.isArray(props.result?.ipr) ? props.result.ipr : [])
const vlp = computed(() => Array.isArray(props.result?.vlp) ? props.result.vlp : [])
const hasData = computed(() => ipr.value.length > 0 && vlp.value.length > 0)
const flowUnit = computed(() => props.result?.units?.flow?.displayUnit || '')
const pressureUnit = computed(() => props.result?.units?.pressure?.displayUnit || '')
const axisName = (name, unit) => unit ? `${name} (${unit})` : name

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
      data: ['IPR', 'VLP'],
      itemWidth: 18,
      itemHeight: 10
    },
    tooltip: {
      trigger: 'axis',
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
    series: [
      {
        name: 'IPR',
        type: 'line',
        showSymbol: false,
        lineStyle: { width: 2, color: '#2B6CB3' },
        data: ipr.value.map(point => [point.flow, point.pressure])
      },
      {
        name: 'VLP',
        type: 'line',
        showSymbol: false,
        lineStyle: { width: 2, color: '#E88A1A' },
        data: vlp.value.map(point => [point.flow, point.pressure])
      }
    ]
  }, true)
  chart.resize()
}

const resizeChart = () => chart?.resize()

watch(() => props.result, renderChart, { deep: true })
onMounted(() => {
  window.addEventListener('resize', resizeChart)
  renderChart()
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeChart)
  chart?.dispose()
  chart = null
})
</script>

<template>
  <section class="nodal-result">
    <div v-if="hasData" ref="chartElement" class="result-chart" />
    <el-empty v-else description="当前运行没有有效的节点分析结果" :image-size="72" />
    <div v-if="hasData" class="curve-tables">
      <el-table :data="ipr" border size="small" max-height="260">
        <el-table-column type="index" label="#" width="54" align="center" />
        <el-table-column prop="flow" :label="axisName('流量', flowUnit)" min-width="130" />
        <el-table-column prop="pressure" :label="axisName('压力', pressureUnit)" min-width="130" />
      </el-table>
      <el-table :data="vlp" border size="small" max-height="260">
        <el-table-column type="index" label="#" width="54" align="center" />
        <el-table-column prop="flow" :label="axisName('流量', flowUnit)" min-width="130" />
        <el-table-column prop="pressure" :label="axisName('压力', pressureUnit)" min-width="130" />
      </el-table>
    </div>
  </section>
</template>

<style lang="scss" scoped>
.nodal-result { min-height: 0; display: flex; flex-direction: column; gap: 14px; }
.result-chart { height: 410px; min-height: 300px; border: 1px solid #e4e9f0; background: #fff; }
.curve-tables { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.curve-tables :deep(.el-table:first-child::before) { background-color: #2b6cb3; }
.curve-tables :deep(.el-table:last-child::before) { background-color: #e88a1a; }
@media (max-width: 900px) {
  .result-chart { height: 340px; }
  .curve-tables { grid-template-columns: 1fr; }
}
</style>
