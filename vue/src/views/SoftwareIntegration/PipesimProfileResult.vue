<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({
  result: { type: Object, default: null },
  partial: { type: Boolean, default: false }
})

const chartElement = ref(null)
let chart

const rows = computed(() => Array.isArray(props.result?.profile) ? props.result.profile : [])
const hasData = computed(() => rows.value.length > 0)
const unit = field => props.result?.units?.[field]?.displayUnit || ''
const axisName = (name, displayUnit) => displayUnit ? `${name} (${displayUnit})` : name

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
    color: ['#2B6CB3', '#B32D2D'],
    title: {
      text: '压力温度剖面',
      left: 'center',
      top: 8,
      textStyle: { color: '#333', fontSize: 15, fontWeight: 600 }
    },
    legend: { top: 38, data: ['压力', '温度'], itemWidth: 18, itemHeight: 10 },
    tooltip: { trigger: 'axis', axisPointer: { type: 'cross' } },
    grid: { left: 88, right: 60, top: 92, bottom: 48, containLabel: true },
    xAxis: [
      {
        type: 'value',
        name: axisName('压力', unit('pressure')),
        nameLocation: 'middle',
        nameGap: 34,
        position: 'bottom',
        axisLine: { lineStyle: { color: '#2B6CB3' } },
        axisLabel: { color: '#2B6CB3' },
        splitLine: { show: true, lineStyle: { color: '#dfe7f2' } }
      },
      {
        type: 'value',
        name: axisName('温度', unit('temperature')),
        nameLocation: 'middle',
        nameGap: 34,
        position: 'top',
        axisLine: { lineStyle: { color: '#B32D2D' } },
        axisLabel: { color: '#B32D2D' },
        splitLine: { show: false }
      }
    ],
    yAxis: {
      type: 'value',
      inverse: true,
      name: axisName('深度', unit('depth')),
      nameLocation: 'middle',
      nameGap: 58,
      splitLine: { show: true, lineStyle: { color: '#dfe7f2' } },
      minorSplitLine: { show: true, lineStyle: { color: '#f1f5fa' } }
    },
    dataZoom: [{ type: 'inside', yAxisIndex: 0 }],
    series: [
      {
        name: '压力',
        type: 'line',
        xAxisIndex: 0,
        showSymbol: false,
        lineStyle: { width: 2, color: '#2B6CB3' },
        data: rows.value.map(point => [point.pressure, point.depth])
      },
      {
        name: '温度',
        type: 'line',
        xAxisIndex: 1,
        showSymbol: false,
        lineStyle: { width: 2, color: '#B32D2D' },
        data: rows.value.map(point => [point.temperature, point.depth])
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
  <section class="profile-result">
    <div v-if="hasData" ref="chartElement" class="result-chart" />
    <el-result
      v-else-if="partial"
      icon="warning"
      title="PT 剖面运行失败"
      sub-title="节点分析结果已保留；本次组合运行没有有效的 PT 剖面数据。"
    />
    <el-empty v-else description="当前运行没有有效的 PT 剖面结果" :image-size="72" />
    <el-table v-if="hasData" :data="rows" border size="small" max-height="300">
      <el-table-column type="index" label="#" width="54" align="center" />
      <el-table-column prop="depth" :label="axisName('深度', unit('depth'))" min-width="140" />
      <el-table-column prop="pressure" :label="axisName('压力', unit('pressure'))" min-width="140" />
      <el-table-column prop="temperature" :label="axisName('温度', unit('temperature'))" min-width="140" />
    </el-table>
  </section>
</template>

<style lang="scss" scoped>
.profile-result { min-height: 0; display: flex; flex-direction: column; gap: 14px; }
.result-chart { height: 430px; min-height: 310px; border: 1px solid #e4e9f0; background: #fff; }
@media (max-width: 900px) { .result-chart { height: 350px; } }
</style>
