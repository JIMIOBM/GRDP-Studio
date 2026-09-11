<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
const props = defineProps({ series: { type: Array, default: () => [] }, unit: { type: String, default: 'MPa' }, title: { type: String, default: '' } })
const host = ref(null)
let chart, observer
const render = async () => {
  await nextTick()
  if (!host.value) return
  chart ||= echarts.init(host.value)
  chart.setOption({ animation: false, color: ['#336da8', '#bf6b45', '#6b9277', '#8773a1', '#789fa3'],
    title: { text: props.title, left: 'center', top: 12, textStyle: { fontSize: 14, fontWeight: 'normal', color: '#333' } },
    legend: { bottom: 8, type: 'scroll', textStyle: { fontSize: 11 } },
    grid: { left: 72, right: 35, top: 65, bottom: 72 },
    tooltip: { trigger: 'axis', valueFormatter: value => value == null ? '未评价' : Number(value).toFixed(3) },
    xAxis: { type: 'value', name: '距离（m）', nameLocation: 'middle', nameGap: 30, splitLine: { show: false } },
    yAxis: { type: 'value', name: props.unit, scale: true, splitLine: { lineStyle: { color: '#e9e9e9', type: 'dashed' } } },
    series: props.series.map(s => ({ name: s.name, data: s.data, type: 'line', showSymbol: false,
      connectNulls: false, lineStyle: { width: 2, type: s.dashed ? 'dashed' : 'solid' } })) }, true)
  chart.resize()
}
watch(() => [props.series, props.unit, props.title], render, { deep: true })
onMounted(() => { render(); observer = new ResizeObserver(() => chart?.resize()); observer.observe(host.value) })
onBeforeUnmount(() => { observer?.disconnect(); chart?.dispose() })
</script>
<template><div ref="host" class="pipeline-chart" :aria-label="title" role="img" /></template>
<style scoped>.pipeline-chart { flex: 1; width: 100%; min-height: 160px; }</style>
