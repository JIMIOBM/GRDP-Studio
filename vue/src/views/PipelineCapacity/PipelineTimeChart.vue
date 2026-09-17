<script>
const timeFormatter = new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })
const finite = value => typeof value === 'number' && Number.isFinite(value)
const chartColors = ['#336da8', '#bf6b45', '#6b9277', '#8773a1', '#789fa3', '#b69345']
const escapeHtml = value => String(value).replace(/[&<>"']/g, character => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[character])
export function batchChartLegendItems(series) {
  return (series || []).map((entry, index) => ({ id: entry.id || String(index), name: entry.name, color: chartColors[index % chartColors.length] }))
}
export function clampBatchLegendPosition(position, bounds, panelSize) {
  const margin = 8
  const maxX = Math.max(margin, bounds.width - panelSize.width - margin)
  const maxY = Math.max(margin, bounds.height - panelSize.height - margin)
  return { x: Math.min(maxX, Math.max(margin, position?.x ?? maxX - margin)),
    y: Math.min(maxY, Math.max(margin, position?.y ?? 62)) }
}
export function formatBatchChartTime(timestamp, multiline = false) {
  if (!finite(timestamp)) return '—'
  const date = new Date(timestamp)
  if (!Number.isFinite(date.getTime())) return '—'
  const parts = Object.fromEntries(timeFormatter.formatToParts(date).map(part => [part.type, part.value]))
  return `${parts.year}-${parts.month}-${parts.day}${multiline ? '\n' : ' '}${parts.hour}:${parts.minute}`
}
export function batchTimeChartOptions(series, title, unit, hiddenSeriesIds = []) {
  const hidden = new Set(hiddenSeriesIds)
  const legendItems = batchChartLegendItems(series)
  return {
    animation: false, useUTC: true, backgroundColor: '#fff', color: chartColors,
    textStyle: { fontFamily: '"Microsoft YaHei", sans-serif' },
    title: { text: title, left: 'center', top: 8, textStyle: { fontSize: 14, fontWeight: 600, color: '#3f3f3f' } },
    legend: { show: false },
    grid: { left: 74, right: 30, top: 40, bottom: 80, show: true, borderColor: '#d7dfeb', borderWidth: 1 },
    tooltip: { trigger: 'axis', confine: true, backgroundColor: 'rgba(255,255,255,0.96)',
      borderColor: '#cfd5dc', borderWidth: 1, textStyle: { color: '#333', fontSize: 12 },
      axisPointer: { type: 'line', axis: 'x', snap: false, lineStyle: { color: '#5f6f82', width: 1, type: 'dashed' } },
      formatter: params => {
      const entries = Array.isArray(params) ? params : [params]
      if (!entries.length) return ''
      const timestamp = entries[0].value?.[0] ?? entries[0].axisValue
      return escapeHtml(formatBatchChartTime(timestamp)) + '<br/>' + entries.map(entry => {
        const value = entry.value?.[1]
        return `${escapeHtml(entry.seriesName || '')}：${finite(value) ? value.toFixed(3) : '—'} ${escapeHtml(unit || '')}`
      }).join('<br/>')
    } },
    xAxis: { type: 'time', name: '时间（北京时间）', nameLocation: 'middle', nameGap: 52,
      nameTextStyle: { color: '#333', fontSize: 14 },
      axisLine: { show: true, onZero: false, lineStyle: { color: '#444', width: 1 } },
      axisTick: { show: true, lineStyle: { color: '#555' } },
      axisLabel: { color: '#444', fontSize: 12, lineHeight: 16, hideOverlap: true, formatter: timestamp => formatBatchChartTime(timestamp, true) },
      splitNumber: 12, minInterval: 60000,
      splitLine: { show: true, lineStyle: { color: '#dbe4f1', width: 1 } },
      // ECharts 6.1 TimeScale provides minor ticks between its calendar-based major ticks.
      minorTick: { show: true, splitNumber: 5, lineStyle: { color: '#777' } },
      minorSplitLine: { show: true, lineStyle: { color: '#edf2f8', width: 1 } } },
    yAxis: { type: 'value', name: unit, scale: true, nameLocation: 'middle', nameGap: 48,
      nameTextStyle: { color: '#333', fontSize: 14 },
      axisLine: { show: true, onZero: false, lineStyle: { color: '#444', width: 1 } },
      axisTick: { show: true, lineStyle: { color: '#555' } },
      axisLabel: { color: '#444', fontSize: 12 }, splitNumber: 10,
      splitLine: { show: true, lineStyle: { color: '#dbe4f1', width: 1 } },
      minorTick: { show: true, splitNumber: 5, lineStyle: { color: '#777' } },
      minorSplitLine: { show: true, lineStyle: { color: '#edf2f8', width: 1 } } },
    series: (series || []).map((entry, index) => {
      const data = (entry.data || []).filter(point => Array.isArray(point) && finite(point[0]))
        .map(point => [point[0], finite(point[1]) ? point[1] : null])
      const hasIsolatedPoint = data.some((point, position) => finite(point[1]) && !finite(data[position - 1]?.[1]) && !finite(data[position + 1]?.[1]))
      const legend = legendItems[index]
      return { id: legend.id, name: entry.name, type: 'line',
        showSymbol: data.filter(point => finite(point[1])).length <= 1 || hasIsolatedPoint, symbolSize: 6,
        connectNulls: false, smooth: false, lineStyle: { width: 2, color: legend.color }, itemStyle: { color: legend.color },
        data: hidden.has(legend.id) ? [] : data }
    })
  }
}
</script>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch, computed } from 'vue'
import * as echarts from 'echarts'
const props = defineProps({ series: { type: Array, default: () => [] }, title: String, unit: String, emptyText: { type: String, default: '暂无计算结果。' } })
const host = ref(null)
const legendHost = ref(null), legendPosition = ref(null), hiddenSeriesIds = ref([])
const legendItems = computed(() => batchChartLegendItems(props.series))
const legendStyle = computed(() => legendPosition.value
  ? { left: `${legendPosition.value.x}px`, top: `${legendPosition.value.y}px` }
  : { right: '16px', top: '62px' })
const hasData = computed(() => props.series.some(entry => (entry.data || []).some(point => Array.isArray(point) && Number.isFinite(point[0]) && typeof point[1] === 'number' && Number.isFinite(point[1]))))
let chart, observer, drag, mounted = false, suppressLegendClick = false
function fitLegend() {
  if (!host.value?.clientWidth || !host.value?.clientHeight || !legendHost.value) return
  legendPosition.value = clampBatchLegendPosition(legendPosition.value,
    { width: host.value.clientWidth, height: host.value.clientHeight },
    { width: legendHost.value.offsetWidth, height: legendHost.value.offsetHeight })
}
function startLegendDrag(event) {
  if (event.button !== 0) return
  fitLegend()
  if (!legendPosition.value) return
  suppressLegendClick = false
  const target = event.target?.closest?.('button') || event.currentTarget
  drag = { pointerId: event.pointerId, ...legendPosition.value,
    startX: event.clientX, startY: event.clientY, target, moved: false }
  target.setPointerCapture(event.pointerId)
}
function moveLegendDrag(event) {
  if (!drag || event.pointerId !== drag.pointerId) return
  if (!drag.moved && Math.hypot(event.clientX - drag.startX, event.clientY - drag.startY) < 4) return
  drag.moved = true
  legendPosition.value = { x: drag.x + event.clientX - drag.startX, y: drag.y + event.clientY - drag.startY }
  fitLegend()
}
function endLegendDrag(event) {
  if (!drag || (event && event.pointerId !== drag.pointerId)) return
  const previous = drag
  drag = null
  suppressLegendClick = previous.moved
  if (previous.target.hasPointerCapture(previous.pointerId)) previous.target.releasePointerCapture(previous.pointerId)
}
function handleLegendClick(event) {
  if (suppressLegendClick && event.detail !== 0) {
    event.preventDefault()
    event.stopPropagation()
  }
  suppressLegendClick = false
}
function toggleSeries(id) {
  hiddenSeriesIds.value = hiddenSeriesIds.value.includes(id) ? hiddenSeriesIds.value.filter(value => value !== id) : [...hiddenSeriesIds.value, id]
}
async function render() {
  await nextTick()
  if (!mounted || !host.value) return
  chart ||= echarts.init(host.value)
  chart.setOption(batchTimeChartOptions(props.series, props.title, props.unit, hiddenSeriesIds.value), true)
  chart.resize()
  fitLegend()
}
watch(() => [props.series, props.title, props.unit, hiddenSeriesIds.value], render, { deep: true })
onMounted(() => { mounted = true; render(); observer = new ResizeObserver(() => { chart?.resize(); fitLegend() }); observer.observe(host.value) })
onBeforeUnmount(() => { mounted = false; endLegendDrag(); observer?.disconnect(); chart?.dispose(); chart = null })
</script>

<template>
  <section class="pipeline-time-chart" :aria-label="title">
    <div ref="host" class="chart-host" role="img" :aria-label="title" />
    <p v-if="!hasData" class="chart-empty">{{ emptyText }}</p>
    <aside v-if="legendItems.length" ref="legendHost" class="curve-legend" :style="legendStyle" aria-label="曲线说明"
      @pointerdown="startLegendDrag" @pointermove="moveLegendDrag" @pointerup="endLegendDrag"
      @pointercancel="endLegendDrag" @lostpointercapture="endLegendDrag" @click.capture="handleLegendClick">
      <div class="legend-list">
        <button v-for="entry in legendItems" :key="entry.id" type="button" class="legend-row"
          :class="{ 'is-hidden': hiddenSeriesIds.includes(entry.id) }" :aria-pressed="!hiddenSeriesIds.includes(entry.id)"
          :title="entry.name" @click="toggleSeries(entry.id)">
          <span class="legend-line" :style="{ backgroundColor: entry.color }" /><span class="legend-name">{{ entry.name }}</span>
        </button>
      </div>
    </aside>
  </section>
</template>

<style scoped>
.pipeline-time-chart{position:relative;flex:1;min-width:0;min-height:0;background:#fff}.chart-host{position:absolute;inset:0}.chart-empty{position:absolute;inset:40px 30px 80px 74px;display:flex;align-items:center;justify-content:center;margin:0;color:#999;font-size:12px;pointer-events:none;text-align:center;line-height:1.8}
.curve-legend{position:absolute;z-index:2;display:flex;flex-direction:column;width:210px;max-width:calc(100% - 16px);max-height:min(280px,calc(100% - 16px));box-sizing:border-box;border:1px solid #cfd5dc;border-radius:2px;background:#fff;box-shadow:0 2px 7px rgba(0,0,0,.14);font:12px "Microsoft YaHei",sans-serif;color:#303030;cursor:move;touch-action:none;user-select:none}.legend-list{min-height:0;overflow-y:auto;padding:4px 0;overscroll-behavior:contain}.legend-row{display:flex;align-items:center;gap:7px;width:100%;min-height:23px;margin:0;padding:3px 9px;border:0;background:#fff;color:inherit;font:inherit;text-align:left;cursor:move}.legend-row:hover,.legend-row:focus-visible{background:#f1f4f7}.legend-line{width:20px;height:2px;flex-shrink:0}.legend-name{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.legend-row.is-hidden{color:#999}.legend-row.is-hidden .legend-line{opacity:.25}
</style>
