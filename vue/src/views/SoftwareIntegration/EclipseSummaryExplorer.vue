<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({ series: { type: Array, default: () => [] }, runId: { type: [Number, String], default: '' } })
const query = ref('')
const objectFilter = ref('')
const selectedKeys = ref([])
const tableKey = ref('')
const chartElement = ref(null)
let chart
let resizeObserver
const names = { FOPR: '全场产油速率', FWPR: '全场产水速率', FGPR: '全场产气速率', FOPT: '全场累计产油', FWPT: '全场累计产水', FGPT: '全场累计产气', FPR: '全场压力', WOPR: '井产油速率', WWPR: '井产水速率', WGPR: '井产气速率', WBHP: '井底压力', WTHP: '井口压力' }
const entries = computed(() => props.series.map((item, index) => ({ ...item, key: String(index) })))
const objects = computed(() => [...new Set(entries.value.map(item => item.objectName || ''))])
const label = item => `${item.keyword}${item.objectName ? ` · ${item.objectName}` : ''}${item.sourceRunId != null ? ` · #${item.sourceRunId}` : ''}`
const filtered = computed(() => entries.value.filter(item =>
  (!objectFilter.value || (objectFilter.value === '__none__' ? !item.objectName : item.objectName === objectFilter.value)) &&
  `${label(item)} ${names[item.keyword] || ''} ${item.unit || ''}`.toLowerCase().includes(query.value.trim().toLowerCase())))
const selected = computed(() => entries.value.filter(item => selectedKeys.value.includes(item.key)))
// Unknown units are not treated as a shared physical quantity.
const unitKey = item => item.unit || `unknown:${item.key}`
const baseUnit = computed(() => selected.value[0] ? unitKey(selected.value[0]) : null)
const disabled = item => !item.points.length || (!selectedKeys.value.includes(item.key) &&
  (selected.value.length >= 6 || (baseUnit.value !== null && unitKey(item) !== baseUnit.value)))
const tableSeries = computed(() => selected.value.find(item => item.key === tableKey.value) || selected.value[0])
const selectSeries = item => {
  if (disabled(item)) return
  selectedKeys.value = selectedKeys.value.includes(item.key) ? selectedKeys.value.filter(key => key !== item.key) : [...selectedKeys.value, item.key]
}
const download = (url, name) => {
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = name
  anchor.click()
}
const csvCell = value => {
  const text = String(value ?? '')
  const safe = typeof value === 'string' && /^[=+\-@\t\r]/.test(text) ? `'${text}` : text
  return `"${safe.replaceAll('"', '""')}"`
}
const exportCsv = () => {
  const rows = [['运行ID', '指标', '对象', '单位', '时间(天)', '值']]
  selected.value.forEach(item => item.points.forEach(point => rows.push([item.sourceRunId ?? props.runId, item.keyword, item.objectName, item.unit, point.timeDays, point.value])))
  const url = URL.createObjectURL(new Blob(['\uFEFF', rows.map(row => row.map(csvCell).join(',')).join('\r\n')], { type: 'text/csv;charset=utf-8' }))
  download(url, `eclipse-run-${props.runId}-summary.csv`)
  setTimeout(() => URL.revokeObjectURL(url), 0)
}
const exportPng = () => chart && download(chart.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: '#fff' }), `eclipse-run-${props.runId}-summary.png`)
const render = async () => {
  await nextTick()
  if (!chartElement.value) return
  chart ||= echarts.init(chartElement.value)
  chart.setOption({
    animation: false,
    title: { text: `运行 #${props.runId}`, textStyle: { fontSize: 12, fontWeight: 'normal' }, left: 8, top: 5 },
    color: ['#2b6cb3', '#bc6c25', '#54835b', '#7b61a8', '#b34f68', '#278c93'],
    legend: { top: 28, type: 'scroll' },
    tooltip: { trigger: 'axis', renderMode: 'richText' },
    grid: { left: 65, right: 25, top: 82, bottom: 85, containLabel: true },
    xAxis: { type: 'value', name: '时间 (天)', nameLocation: 'middle', nameGap: 26 },
    yAxis: { type: 'value', name: selected.value[0]?.unit || '单位未提供', splitLine: { lineStyle: { color: '#e5e7eb' } } },
    dataZoom: [{ type: 'inside' }, { type: 'slider', bottom: 12, height: 18 }],
    series: selected.value.map(item => ({ id: item.key, name: label(item), type: 'line', lineStyle: { type: item.historical ? 'dashed' : 'solid' }, showSymbol: false, connectNulls: false, data: item.points.map(point => [point.timeDays, point.value]) }))
  }, true)
  chart.resize()
}
watch(() => [props.runId, props.series], () => {
  query.value = ''
  objectFilter.value = ''
  selectedKeys.value = entries.value.find(item => item.points.length)?.key !== undefined ? [entries.value.find(item => item.points.length).key] : []
  const first = entries.value.find(item => selectedKeys.value.includes(item.key))
  const matching = first?.unit && entries.value.find(item => item.historical && item.points.length && item.keyword === first.keyword && item.objectName === first.objectName && item.unit === first.unit)
  if (matching) selectedKeys.value.push(matching.key)
  tableKey.value = ''
}, { immediate: true })
watch(selected, render)
onMounted(() => {
  resizeObserver = new ResizeObserver(() => chart?.resize())
  resizeObserver.observe(chartElement.value)
  render()
})
onBeforeUnmount(() => { resizeObserver?.disconnect(); chart?.dispose(); chart = null })
</script>

<template>
  <section class="summary-explorer" aria-label="RSM 曲线工作台">
    <div class="summary-toolbar">
      <strong>Summary 曲线</strong><span>{{ series.length }} 个序列</span>
      <el-button size="small" :disabled="!selected.length" @click="exportCsv">导出曲线 CSV</el-button>
      <el-button size="small" :disabled="!selected.length" @click="exportPng">导出曲线图片</el-button>
    </div>
    <div class="summary-workspace">
      <aside class="series-browser">
        <el-input v-model="query" placeholder="搜索指标、井名或中文说明" clearable aria-label="搜索 RSM 指标" />
        <el-select v-model="objectFilter" filterable placeholder="全部对象" clearable aria-label="筛选 RSM 对象">
          <el-option v-for="object in objects" :key="object || '__none__'" :value="object || '__none__'" :label="object || '无对象限定'" />
        </el-select>
        <div class="selection-actions"><span>已选 {{ selected.length }}/6</span><el-button link size="small" @click="selectedKeys = []">清空选择</el-button></div>
        <p class="selection-help">同图比较相同单位的序列；切换单位请先清空。单位缺失的序列单独显示。</p>
        <div class="series-list" role="group" aria-label="可选 RSM 序列">
          <label v-for="item in filtered" :key="item.key" class="series-row" :class="{ disabled: disabled(item) }">
            <input type="checkbox" :checked="selectedKeys.includes(item.key)" :disabled="disabled(item)" :aria-label="label(item)" @change="selectSeries(item)" />
            <span><strong>{{ label(item) }}</strong><small>{{ names[item.keyword] || '原始指标' }} · {{ item.unit || '单位未提供' }} · {{ item.points.length }} 点</small></span>
          </label>
          <p v-if="!filtered.length" class="selection-help">没有匹配的序列</p>
        </div>
      </aside>
      <div class="summary-content">
        <div class="chart-wrap"><div ref="chartElement" class="summary-chart" /><p v-if="!selected.length" class="chart-empty">选择左侧指标查看曲线</p></div>
        <div v-if="selected.length" class="table-toolbar"><span>原始数据</span><el-select v-model="tableKey" :placeholder="tableSeries && label(tableSeries)" aria-label="选择数据表序列"><el-option v-for="item in selected" :key="item.key" :value="item.key" :label="label(item)" /></el-select></div>
        <el-table v-if="tableSeries" :data="tableSeries.points" border size="small" max-height="260"><el-table-column prop="timeDays" label="时间 (天)" /><el-table-column prop="value" :label="`${label(tableSeries)} (${tableSeries.unit || '单位未提供'})`" /></el-table>
      </div>
    </div>
  </section>
</template>

<style scoped>
.summary-explorer { border: 1px solid #dcdfe6; background: white; min-width: 0; }
.summary-toolbar, .table-toolbar { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; padding: 8px 10px; background: #f3f3f0; font-size: 12px; }
.summary-toolbar strong { font-size: 13px; }.summary-toolbar > span { margin-right: auto; color: #73777d; }
.summary-workspace { display: grid; grid-template-columns: 260px minmax(0, 1fr); }
.series-browser { padding: 10px; border-right: 1px solid #dcdfe6; min-width: 0; }.series-browser > .el-select { width: 100%; margin-top: 8px; }
.selection-actions { display: flex; align-items: center; justify-content: space-between; font-size: 12px; margin-top: 8px; }.selection-help { margin: 8px 0; color: #73777d; font-size: 12px; line-height: 1.6; }
.series-list { max-height: 520px; overflow: auto; }.series-row { display: flex; align-items: flex-start; gap: 7px; padding: 8px 3px; border-bottom: 1px solid #eee; cursor: pointer; }.series-row input { margin-top: 3px; accent-color: #9b8100; }.series-row strong { font-size: 12px; font-weight: 500; overflow-wrap: anywhere; }.series-row small { display: block; margin-top: 3px; color: #73777d; font-size: 11px; }.series-row.disabled { opacity: .55; cursor: default; }
.summary-content { min-width: 0; padding: 10px; }.chart-wrap { position: relative; }.summary-chart { height: 380px; width: 100%; }.chart-empty { position: absolute; top: 40%; left: 0; right: 0; text-align: center; color: #909399; pointer-events: none; }.table-toolbar { margin-top: 8px; }.table-toolbar .el-select { width: min(300px, 100%); }
@media (max-width: 900px) { .summary-workspace { grid-template-columns: 215px minmax(0, 1fr); } }
@media (max-width: 650px) { .summary-workspace { grid-template-columns: 1fr; }.series-browser { border-right: 0; border-bottom: 1px solid #dcdfe6; }.series-list { max-height: 200px; } }
</style>
