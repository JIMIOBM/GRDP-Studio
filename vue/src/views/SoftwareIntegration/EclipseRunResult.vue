<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({
  run: { type: Object, default: null }
})

const chartElement = ref(null)
const selectedSeriesKey = ref('')
let chart

const isFiniteNumber = value => typeof value === 'number' && Number.isFinite(value)
const result = computed(() => {
  const value = props.run?.result
  if (!value || value.schemaVersion !== 'eclipse-summary-result/1' || value.modelKind !== 'eclipse_100' ||
    value.runTask !== 'eclipse' || value.resultContract !== 'VALID_FULL' || props.run?.resultContract !== 'VALID_FULL') return null
  const counts = value.eclEnd
  if (!counts || !['problems', 'errors', 'bugs'].every(key =>
    Number.isInteger(counts[key]) && counts[key] >= 0) ||
    !['comments', 'warnings'].every(key => counts[key] === undefined || counts[key] === null ||
      Number.isInteger(counts[key]) && counts[key] >= 0)) return null
  if (value.summary !== null && (!Array.isArray(value.summary?.series) || !value.summary.series.every(series =>
    typeof series?.keyword === 'string' && (series.objectName === null || typeof series.objectName === 'string') &&
    (series.unit === null || typeof series.unit === 'string') && Array.isArray(series.points) && series.points.every(point =>
      isFiniteNumber(point?.timeDays) && point.timeDays >= 0 && isFiniteNumber(point?.value))))) return null
  return value
})
const counts = computed(() => result.value?.eclEnd || null)
const countItems = computed(() => [
  ['comments', 'Comments'], ['warnings', 'Warnings'], ['problems', 'Problems'], ['errors', 'Errors'], ['bugs', 'Bugs']
].map(([key, label]) => ({ key, label, value: counts.value?.[key] ?? '-' })))
const series = computed(() => result.value?.summary?.series || [])
const seriesKey = (item, index) => `${item.keyword}:${item.objectName || ''}:${index}`
const selectedSeries = computed(() => series.value.find((item, index) => seriesKey(item, index) === selectedSeriesKey.value) || null)
const hasSummary = computed(() => result.value?.summary !== null && Array.isArray(result.value?.summary?.series))
const cleanupEntries = computed(() => Object.entries(props.run?.cleanup || {}).map(([key, value]) => ({ key, value: formatValue(value) })))
const error = computed(() => props.run?.error || null)
const outputFiles = computed(() => result.value?.outputFiles || [])
const artifacts = computed(() => props.run?.artifacts || [])
const seriesLabel = item => item.objectName ? `${item.keyword} · ${item.objectName}` : item.keyword
const formatValue = value => value === null || value === undefined ? '-' : (typeof value === 'object' ? JSON.stringify(value) : String(value))
const formatSize = value => Number.isFinite(Number(value)) ? `${Number(value).toLocaleString()} B` : '-'
const resizeChart = () => chart?.resize()

const renderChart = async () => {
  await nextTick()
  if (!chartElement.value || !selectedSeries.value?.points?.length) {
    chart?.dispose()
    chart = null
    return
  }
  if (chart && chart.getDom() !== chartElement.value) {
    chart.dispose()
    chart = null
  }
  chart ||= echarts.init(chartElement.value)
  const item = selectedSeries.value
  chart.setOption({
    animation: false,
    color: ['#2b6cb3'],
    tooltip: { trigger: 'axis', axisPointer: { type: 'cross' } },
    grid: { left: 72, right: 36, top: 48, bottom: 70, containLabel: true },
    xAxis: { type: 'value', name: '时间 (天)', nameLocation: 'middle', nameGap: 42, splitLine: { lineStyle: { color: '#dfe7f2' } } },
    yAxis: { type: 'value', name: item.unit || item.keyword, nameLocation: 'middle', nameGap: 56, splitLine: { lineStyle: { color: '#dfe7f2' } } },
    series: [{ name: seriesLabel(item), type: 'line', showSymbol: item.points.length <= 80, lineStyle: { width: 2 }, data: item.points.map(point => [point.timeDays, point.value]) }]
  }, true)
  chart.resize()
}

watch(series, values => {
  if (!values.some((item, index) => seriesKey(item, index) === selectedSeriesKey.value)) {
    selectedSeriesKey.value = values[0] ? seriesKey(values[0], 0) : ''
  }
}, { immediate: true })
watch([selectedSeries, () => props.run?.result], renderChart)
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
  <section class="eclipse-result">
    <section class="result-panel">
      <div class="panel-heading"><div><span class="kicker">ECLIPSE 100</span><h2>ECLEND 计数</h2></div><el-tag :type="run?.status === 'SUCCEEDED' ? 'success' : 'info'">{{ run?.status || '-' }}</el-tag></div>
      <div v-if="counts" class="count-strip"><div v-for="item in countItems" :key="item.key"><span>{{ item.label }}</span><strong :class="{ failure: ['problems', 'errors', 'bugs'].includes(item.key) && item.value > 0 }">{{ item.value }}</strong></div></div>
      <el-empty v-else description="当前运行没有可用的 ECLEND 计数" :image-size="56" />
    </section>

    <section v-if="error || cleanupEntries.length" class="result-panel state-panel">
      <div class="panel-heading"><div><h2>错误与清理状态</h2></div></div>
      <dl v-if="error" class="error-grid"><div><dt>类别</dt><dd>{{ error.category || '-' }}</dd></div><div><dt>代码</dt><dd>{{ error.code || '-' }}</dd></div><div><dt>消息</dt><dd>{{ error.message || error.msg || '-' }}</dd></div><div><dt>可重试</dt><dd>{{ error.retryable ? '是' : '否' }}</dd></div></dl>
      <el-table v-if="cleanupEntries.length" :data="cleanupEntries" border size="small"><el-table-column prop="key" label="清理项" min-width="200" /><el-table-column prop="value" label="状态" min-width="280" show-overflow-tooltip /></el-table>
    </section>

    <section v-if="hasSummary" class="result-panel">
      <div class="panel-heading"><div><h2>Summary</h2><p>仅展示本次 RSM 解析出的原始序列。</p></div><span>{{ series.length }} 个序列</span></div>
      <div v-if="series.length" class="summary-controls"><el-select v-model="selectedSeriesKey"><el-option v-for="(item, index) in series" :key="seriesKey(item, index)" :value="seriesKey(item, index)" :label="seriesLabel(item)" /></el-select></div>
      <div v-if="selectedSeries?.points?.length" ref="chartElement" class="summary-chart" />
      <el-table v-if="selectedSeries?.points?.length" :data="selectedSeries.points" border size="small" max-height="300"><el-table-column prop="timeDays" label="时间 (天)" min-width="160" /><el-table-column prop="value" :label="selectedSeries.unit ? `${selectedSeries.keyword} (${selectedSeries.unit})` : selectedSeries.keyword" min-width="180" /></el-table>
      <el-empty v-else description="RSM 未提供可绘制的 Summary 点" :image-size="56" />
    </section>

    <section class="result-panel">
      <div class="panel-heading"><div><h2>ECLIPSE 输出文件</h2><p>ECLIPSE 结果契约中的本次输出文件元数据。</p></div><span>{{ outputFiles.length }} 个文件</span></div>
      <el-table v-if="outputFiles.length" :data="outputFiles" border size="small" max-height="320"><el-table-column prop="name" label="文件名" min-width="210" show-overflow-tooltip /><el-table-column label="大小" width="120"><template #default="{ row }">{{ formatSize(row.sizeBytes) }}</template></el-table-column><el-table-column prop="sha256" label="SHA-256" min-width="280" show-overflow-tooltip /></el-table>
      <el-empty v-else description="当前运行没有 ECLIPSE 输出文件元数据" :image-size="56" />
    </section>

    <section class="result-panel">
      <div class="panel-heading"><div><h2>Artifact</h2><p>平台持久化的通用运行 Artifact。</p></div><span>{{ artifacts.length }} 个文件</span></div>
      <el-table v-if="artifacts.length" :data="artifacts" border size="small" max-height="320"><el-table-column prop="name" label="文件名" min-width="210" show-overflow-tooltip /><el-table-column prop="type" label="类型" min-width="120" /><el-table-column label="大小" width="120"><template #default="{ row }">{{ formatSize(row.sizeBytes) }}</template></el-table-column><el-table-column prop="sha256" label="SHA-256" min-width="280" show-overflow-tooltip /><el-table-column prop="expiresAt" label="到期时间" min-width="170" /></el-table>
      <el-empty v-else description="当前运行没有已发布的 Artifact" :image-size="56" />
    </section>
  </section>
</template>

<style lang="scss" scoped>
.eclipse-result { display: flex; flex-direction: column; gap: 16px; }.result-panel { min-width: 0; padding: 16px; border: 1px solid #e1e7ef; background: #fff; }.panel-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; margin-bottom: 12px; }.kicker { color: #2b6cb3; font-size: 11px; font-weight: 700; letter-spacing: .08em; }.panel-heading h2 { margin: 3px 0 0; font-size: 15px; }.panel-heading p { margin: 4px 0 0; color: #909399; font-size: 12px; }.panel-heading > span { color: #737a84; font-size: 12px; }.count-strip { display: grid; grid-template-columns: repeat(5, minmax(90px, 1fr)); border: 1px solid #e8edf3; }.count-strip div { padding: 12px 14px; border-right: 1px solid #e8edf3; }.count-strip div:last-child { border-right: 0; }.count-strip span { display: block; color: #737a84; font-size: 12px; }.count-strip strong { display: block; margin-top: 3px; color: #2b3d52; font-size: 20px; }.count-strip .failure { color: #c45656; }.error-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 9px 22px; margin: 0 0 14px; }.error-grid div { display: flex; gap: 7px; min-width: 0; font-size: 12px; }.error-grid dt { color: #909399; }.error-grid dd { margin: 0; overflow-wrap: anywhere; }.summary-controls { margin-bottom: 12px; }.summary-controls .el-select { width: min(420px, 100%); }.summary-chart { height: 390px; min-height: 280px; margin-bottom: 14px; border: 1px solid #e5eaf1; } @media (max-width: 760px) { .panel-heading { flex-direction: column; }.count-strip { grid-template-columns: repeat(2, 1fr); }.count-strip div { border-bottom: 1px solid #e8edf3; }.count-strip div:nth-child(2n) { border-right: 0; }.error-grid { grid-template-columns: 1fr; }.summary-chart { height: 320px; } }
</style>
