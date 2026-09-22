<script setup>
/** 库水侵：选择已保存单井批次 → 日期并集逐日加权 → 旧算法 → 新库历史。与单井页面独立。 */
import { computed, ref, watch, nextTick, onBeforeUnmount } from 'vue'
import * as echarts from 'echarts'
import { ElMessage, ElTable, ElTableColumn, ElPagination, ElInput } from 'element-plus'
import request from '@/utils/request'

const props = defineProps({ reservoir: { type: Object, default: null } })
const ROOT = '/storage-water-invasion'
const scope = () => ({ projectId: Number(props.reservoir?.projectId), gasReservoirId: Number(props.reservoir?.gasReservoirId), storageId: Number(props.reservoir?.storageId) })
const unwrap = response => response?.data ?? response
const apiGet = async (path, params = scope()) => unwrap(await request.get(ROOT + path, { params, silentError: true, timeout: 40000 }))
const apiPost = async (path, body) => unwrap(await request.post(ROOT + path, body, { silentError: true, timeout: 40000 }))
const errorText = e => e?.response?.data?.msg || e?.response?.data?.message || e?.msg || e?.message || '请求失败，请检查后端服务'
const finite = v => v !== null && v !== undefined && String(v).trim() !== '' && Number.isFinite(Number(v))
const format = v => finite(v) ? Number(v).toLocaleString('zh-CN', { maximumFractionDigits: 4 }) : '—'
const stamp = value => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '时间未知'
const statusNames = { RUNNING: '计算中', SAVING: '保存中', COMPLETED: '已保存', FAILED: '失败', TIMED_OUT: '待检查' }
const sources = ref([]), choices = ref({}), records = ref([]), selected = ref(null), detail = ref(null), preview = ref(null)
const busy = ref(false), loading = ref(false), error = ref(''), limit = ref(-1), page = ref(1), view = ref('production'), category = ref(0)
const chartEl = ref(null), chartHost = ref(null)
const hiddenLegendNames = ref(new Set()), legendPosition = ref(null), draggingLegend = ref(false)
let legendOffset = null
let disposed = false, generation = 0, detailGeneration = 0, timer, chart, observer
const libraryName = computed(() => detail.value?.record?.storageName || props.reservoir?.label || '当前库')
const selectedSources = computed(() => sources.value.filter(w => choices.value[w.wellId]?.enabled && choices.value[w.wellId]?.recordId).map(w => ({ wellId: w.wellId, recordId: Number(choices.value[w.wellId].recordId) })))
const running = computed(() => records.value.some(r => ['RUNNING', 'SAVING'].includes(r.taskStatus)))
const blocked = computed(() => records.value.some(r => r.carrierBlocked))
const record = computed(() => records.value.find(r => r.id === Number(selected.value)))
const payload = computed(() => preview.value?.aggregate?.payload || detail.value?.input || null)
const result = computed(() => detail.value?.result || null)
const rules = computed(() => preview.value?.aggregate?.rules || detail.value?.rules || null)
const names = ['水侵识别', '水体大小', '水侵量', '驱动机制', '水体活跃性']
const types = ['IDENTIFICATION', 'AQUIFER_SIZE', 'WATER_INFLUX', 'DRIVE_MECHANISM', 'WATER_ACTIVITY']
const available = index => result.value?.storedSections?.some(s => s.result_type === types[index] && s.availability === 'HAS_DATA') === true
const section = computed(() => result.value?.outputs?.[category.value] || {})
const labels = {
  date: '日期', pressure: '井底压力 (MPa)', dailyGasProduction: '气产量 (10⁴m³/d)', cumulativeGasProduction: '累产气量 (10⁸m³)', cumulativeWaterProduction: '累产水量 (10⁴m³)',
  apparentPressure: '无因次视压力', recoveryDegree: '采出程度 (%)', deviationFactor: '偏差系数', waterInflux: '水侵量 (10⁴m³)',
  gasDriveIndex: '天然气驱动指数', reservoirVolumetricDriveIndex: '气藏容积驱动指数', waterInvasionEnergyDriveIndex: '水侵能量驱动指数'
}
const summaryLabels = [
  { waterInvasionStateDesc: '水侵识别结果', originalGasVolume: '库动态地质储量 (10⁸m³)' },
  { waterBodySize: '水体大小 (10⁴m³)', undergroundGasVolume: '地下气体体积 (10⁸m³)', waterBodySizeMultiple: '水体倍数' }, {}, {},
  { waterActivenessDesc: '水体活跃性', abandonWaterInflux: '废弃时水侵量 (10⁴m³)', reservoirOriginalVolume: '原始条件下气藏容积 (10⁸m³)', waterInvasionReplacementCoefficient: '水侵替换系数' }
]
const summary = computed(() => Object.entries(summaryLabels[category.value]).filter(([key]) => section.value.output?.[key] !== null && section.value.output?.[key] !== undefined))
const rows = computed(() => view.value === 'table' ? (section.value.outputItems || []) : (payload.value?.influxInputItems || []))
const columns = computed(() => view.value !== 'table'
  ? ['date', 'pressure', 'dailyGasProduction', 'cumulativeGasProduction', 'cumulativeWaterProduction']
  : ['date', ...(category.value < 3 ? ['pressure'] : []), 'cumulativeGasProduction', 'cumulativeWaterProduction', ...(category.value < 2 ? ['apparentPressure', 'recoveryDegree', 'isDeleted'] : category.value === 2 ? ['waterInflux'] : ['gasDriveIndex', 'reservoirVolumetricDriveIndex', 'waterInvasionEnergyDriveIndex'])])
// 右侧表格沿用单井的表头、单位与显示精度，原始数值仍用于导出和计算。
const productionLabels = { date: '日期', pressure: '压力', dailyGasProduction: '气产量', cumulativeGasProduction: '累产气量', cumulativeWaterProduction: '累产水量' }
const productionUnits = { date: '无', pressure: 'MPa', dailyGasProduction: '10⁴m³/d', cumulativeGasProduction: '10⁸m³', cumulativeWaterProduction: '10⁴m³' }
const resultLabels = { ...labels, pressure: '地层压力(MPa)', apparentPressure: '无因次视压力(dless)', recoveryDegree: '采出程度(%)', gasDriveIndex: '天然气驱动指数(dless)', reservoirVolumetricDriveIndex: '气藏容积驱动指数(dless)', waterInvasionEnergyDriveIndex: '水侵能量驱动指数(dless)', isDeleted: '是否参与分析' }
const isInputView = computed(() => view.value === 'production')
const pageRows = computed(() => rows.value.slice((page.value - 1) * 100, page.value * 100).map((row, index) => ({ ...row, rowNumber: (page.value - 1) * 100 + index + 1 })))
const dataListRows = computed(() => rows.value.map((row, index) => ({ ...row, rowNumber: index + 1, selectedText: row.isDeleted === true || row.isDeleted === 'true' || row.isDeleted === 1 || row.isDeleted === '1' ? '否' : '是' })))
function cellValue(row, key, inputTable = view.value !== 'table') {
  if (key === 'date') return inputTable ? String(row[key] ?? '').slice(0, 10) : String(row[key] ?? '').slice(0, 10).replace(/-/g, '/')
  if (key === 'isDeleted') return row.selectedText
  if (!finite(row[key])) return ''
  const number = Number(row[key]).toFixed(key === 'recoveryDegree' ? 2 : 4)
  return inputTable ? number : number.replace(/\.?0+$/, '')
}
const dateWeights = computed(() => new Map((rules.value?.rowCounts?.influxInputItems?.dateWeights || []).map(day => [day.date, day])))
const sourceNames = computed(() => Object.fromEntries([...(detail.value?.sources || []).map(s => [s.well_id, s.well_name_snapshot]), ...sources.value.map(s => [s.wellId, s.wellName])]))
function contributors(row, field) {
  // 日期是权重记录的索引字符串，不是参与井数组；只给数值列生成权重提示。
  if (view.value === 'table' || field === 'date') return ''
  const day = dateWeights.value.get(row.date)
  const weights = day?.[field] || day?.allFields
  return Array.isArray(weights) ? weights.map(w => `${sourceNames.value[w.wellId] || '来源井'}：${(w.weight * 100).toFixed(2)}%`).join('；') : ''
}
function newRequest() { return { ...scope(), requestId: crypto.randomUUID(), sources: selectedSources.value, waterGasRatioLimit: Number(limit.value) } }
function invalidatePreview() { preview.value = null; generation++ }
watch([choices, limit], invalidatePreview, { deep: true })
watch([view, category, payload], () => { page.value = 1 })
async function loadDetail(id) {
  const token = ++detailGeneration
  detail.value = null; preview.value = null
  if (!id) return
  try {
    const data = await apiGet(`/records/${id}`)
    if (disposed || token !== detailGeneration) return
    detail.value = data
    const first = names.findIndex((_, index) => available(index))
    category.value = first < 0 ? 0 : first
    if (data.result) view.value = 'chart'
  } catch (e) { if (!disposed && token === detailGeneration) error.value = errorText(e) }
}
watch(selected, loadDetail)
async function refreshRecords() {
  const data = await apiGet('/records')
  if (disposed) return
  const before = records.value.find(r => r.id === Number(selected.value))
  records.value = data
  const after = data.find(r => r.id === Number(selected.value))
  if (after && before && (after.taskStatus !== before.taskStatus || after.errorMessage !== before.errorMessage)) await loadDetail(after.id)
}
function poll() {
  clearTimeout(timer)
  if (disposed || !running.value) return
  timer = setTimeout(async () => { try { await refreshRecords() } catch (e) { if (!disposed) error.value = errorText(e) } finally { poll() } }, 2500)
}
async function reload() {
  loading.value = true; error.value = ''
  try {
    const [wells, history] = await Promise.all([apiGet('/sources'), apiGet('/records')])
    if (disposed) return
    sources.value = wells; records.value = history
    choices.value = Object.fromEntries(wells.map(w => {
      const saved = w.records.find(r => Number(r.dynamic_gas_volume) > 0)
      return [w.wellId, { enabled: !!saved, recordId: saved?.id || null }]
    }))
    if (!selected.value && history.length) selected.value = history[0].id
    poll()
  } catch (e) { if (!disposed) error.value = errorText(e) } finally { if (!disposed) loading.value = false }
}
async function previewInput() {
  busy.value = true; error.value = ''; const token = ++generation
  try {
    const data = await apiPost('/preview', newRequest())
    if (disposed || token !== generation) return
    preview.value = data; view.value = 'production'
  } catch (e) { if (!disposed && token === generation) error.value = errorText(e) } finally { if (!disposed) busy.value = false }
}
async function calculate() {
  if (disposed || !preview.value || busy.value || blocked.value) return
  const body = newRequest()
  busy.value = true; error.value = ''
  try {
    const task = await apiPost('/tasks', body)
    if (disposed) return
    await refreshRecords(); selected.value = task.id; poll()
  } catch (e) { if (!disposed) { error.value = errorText(e); await refreshRecords().catch(() => {}) } } finally { if (!disposed) { busy.value = false; poll() } }
}
async function reconcile() {
  busy.value = true; error.value = ''
  try { await apiPost(`/tasks/${selected.value}/reconcile`, scope()); if (!disposed) { await refreshRecords(); await loadDetail(selected.value); poll() } }
  catch (e) { if (!disposed) error.value = errorText(e) } finally { if (!disposed) busy.value = false }
}
async function exportRows() {
  const data = rows.value, keys = columns.value
  try {
    const XLSX = await import('xlsx')
    const headers = view.value === 'table' ? [keys.map(k => resultLabels[k])] : [keys.map(k => productionLabels[k]), keys.map(k => productionUnits[k])]
    const sheet = XLSX.utils.aoa_to_sheet([...headers, ...data.map(row => keys.map(k => row[k] ?? ''))])
    const book = XLSX.utils.book_new(); XLSX.utils.book_append_sheet(book, sheet, '水侵分析')
    XLSX.writeFile(book, `${libraryName.value}-水侵分析.xlsx`)
  } catch { ElMessage.error('导出失败，请重试') }
}
function disposeChart() { observer?.disconnect(); observer = null; chart?.dispose(); chart = null }
const legendDefinitions = [
  [{ name: '无因次视压力PFD(dless)', field: 'apparentPressure', color: '#5470c6' }],
  [{ name: '无因次视压力PHD(dless)', field: 'apparentPressure', color: '#5470c6' }],
  [{ name: '累计水侵量(10⁴m³)', field: 'waterInflux', color: '#1677ff' }],
  [{ name: '天然气驱动指数(dless)', field: 'gasDriveIndex', color: '#ffff33' }, { name: '气藏容积驱动指数(dless)', field: 'reservoirVolumetricDriveIndex', color: '#b84a4a' }, { name: '水侵能量驱动指数(dless)', field: 'waterInvasionEnergyDriveIndex', color: '#2f80ed' }], []
]
const legendItems = computed(() => available(category.value) ? legendDefinitions[category.value].filter((item, index) => chartPoints(item.field, index).length) : [])
const legendStyle = computed(() => legendPosition.value ? { left: `${legendPosition.value.x}px`, top: `${legendPosition.value.y}px` } : { top: '56px', right: '104px' })
function toggleLegendItem(name) {
  const hidden = new Set(hiddenLegendNames.value)
  if (hidden.has(name)) hidden.delete(name); else hidden.add(name)
  hiddenLegendNames.value = hidden
}
function moveLegend(event) {
  if (!legendOffset || !chartHost.value) return
  const rect = chartHost.value.getBoundingClientRect()
  legendPosition.value = { x: Math.max(0, Math.min(rect.width - legendOffset.width, event.clientX - rect.left - legendOffset.x)), y: Math.max(0, Math.min(rect.height - legendOffset.height, event.clientY - rect.top - legendOffset.y)) }
}
function stopLegendDrag() {
  if (!legendOffset) return
  legendOffset = null; draggingLegend.value = false
  window.removeEventListener('mousemove', moveLegend); window.removeEventListener('mouseup', stopLegendDrag)
}
function startLegendDrag(event) {
  if (event.button !== 0 || !chartHost.value) return
  event.preventDefault()
  const rect = event.currentTarget.getBoundingClientRect(), host = chartHost.value.getBoundingClientRect()
  legendPosition.value = { x: rect.left - host.left, y: rect.top - host.top }
  legendOffset = { x: event.clientX - rect.left, y: event.clientY - rect.top, width: rect.width, height: rect.height }; draggingLegend.value = true
  window.addEventListener('mousemove', moveLegend); window.addEventListener('mouseup', stopLegendDrag)
}
function chartPoints(field, index) {
  const items = section.value.chartItems || []
  const item = items.find(item => item.yAxisField === field) || (!items[index]?.yAxisField ? items[index] : null)
  const isDate = category.value >= 2
  const points = (item?.data || []).filter(p => !p.isDeleted && finite(p.yValue) && (isDate ? !Number.isNaN(Date.parse(p.xValue)) : finite(p.xValue)))
    .map(p => [isDate ? String(p.xValue).slice(0, 10) : Number(p.xValue), Number(p.yValue)])
  if (points.length) return points
  return (section.value.outputItems || []).filter(p => !p.isDeleted && finite(p[field]) && (isDate ? !Number.isNaN(Date.parse(p.date)) : finite(p.recoveryDegree)))
    .map(p => [isDate ? String(p.date).slice(0, 10) : Number(p.recoveryDegree), Number(p[field])])
}
// 只统一绘图方式，不重新计算或改写旧平台返回的分析数值。
function chartOption() {
  const index = category.value, definitions = legendDefinitions[index], scatter = index < 2
  const data = definitions.map((item, i) => chartPoints(item.field, i))
  const dates = scatter ? [] : [...new Set(data.flatMap(points => points.map(p => p[0])))].sort()
  const series = definitions.map((item, i) => {
    const byDate = new Map(data[i])
    return { name: item.name, type: scatter ? 'scatter' : 'line', data: scatter ? data[i] : dates.map(date => byDate.get(date) ?? null),
      ...(scatter ? { symbolSize: 8, itemStyle: { color: item.color, opacity: 0.85 } }
        : { showSymbol: false, smooth: true, ...(index === 3 ? { stack: 'drive' } : {}), lineStyle: { color: item.color, width: index === 3 ? 1 : 1.5 }, itemStyle: { color: item.color }, areaStyle: { color: index === 2 ? 'rgba(22,119,255,0.78)' : item.color, opacity: index === 3 ? 0.9 : 1 } }) }
  }).filter(item => !hiddenLegendNames.value.has(item.name))
  if (scatter) series.push({ name: '理论线', type: 'line', data: [[0, 1], [100, 0]], symbol: 'none', lineStyle: { color: '#111', width: 1.8 }, tooltip: { show: false } })
  const axis = { nameLocation: 'middle', minorTick: { show: true }, minorSplitLine: { show: true, lineStyle: { color: '#f1f5fb' } }, splitLine: { show: true, lineStyle: { color: '#dce5f2' } } }
  return { animation: false, title: { text: ['PFD-Rg关系图', 'PHD-Rg关系图', '水侵量随时间变化曲线', '驱动指数随时间变化曲线'][index], left: 'center', top: 8, textStyle: { fontSize: 14, fontWeight: 600, color: '#333' } },
    tooltip: { trigger: scatter ? 'item' : 'axis', renderMode: 'richText', axisPointer: scatter ? { type: 'cross', crossStyle: { color: '#d936d0', type: 'dashed', width: 1 }, label: { backgroundColor: '#d936d0' } } : { type: 'line', lineStyle: { color: '#d936d0', width: 1 } } },
    legend: { show: false }, grid: { left: 62, right: 92, top: 44, bottom: 56 },
    xAxis: { ...axis, ...(scatter ? { type: 'value', name: 'Rg(%)', min: values => Math.min(0, values.min), max: values => Math.max(100, values.max), nameGap: 30 } : { type: 'category', name: '日期', data: dates, boundaryGap: false, nameGap: 34, axisLabel: { formatter: value => String(value).slice(0, 4) } }) },
    yAxis: { ...axis, type: 'value', name: ['PFD(dless)', 'PHD(dless)', '水侵量(10⁴m³)', '驱动指数(dless)'][index], nameGap: 44,
      // 常规范围与单井一致；异常正负值仍完整显示，不能因样式调整截掉已有数据。
      ...(index !== 2 ? { min: values => Math.min(0, values.min), max: values => Math.max(1, values.max) } : {}) }, series }
}
async function renderChart() {
  await nextTick()
  if (disposed) return
  if (view.value !== 'chart' || category.value === 4 || !chartEl.value || !available(category.value)) { disposeChart(); return }
  if (!chart) { chart = echarts.init(chartEl.value); observer = new ResizeObserver(() => chart?.resize()); observer.observe(chartEl.value) }
  if (!legendItems.value.length) { chart.clear(); return }
  chart.setOption(chartOption(), true)
  chart.resize()
}
watch([view, category, result, chartEl, hiddenLegendNames], renderChart)
watch([category, result], () => { hiddenLegendNames.value = new Set(); legendPosition.value = null; stopLegendDrag() })
onBeforeUnmount(() => { disposed = true; generation++; detailGeneration++; clearTimeout(timer); stopLegendDrag(); disposeChart() })
reload()
</script>

<template>
  <section class="storage-water" aria-label="库水侵分析">
    <div class="module-bar"><span>水侵动态分析 · 水侵分析</span></div>
    <div class="workspace">
      <aside class="parameters">
        <div class="panel-head">参数设置</div>
        <div class="parameter-scroll">
          <label>所属储气库<input :value="reservoir?.label || '未选择库'" readonly /></label>
          <div class="field-title">参与井及来源批次 <button :disabled="loading || busy || running" @click="reload">刷新</button></div>
          <div v-if="loading" class="muted">正在读取单井历史…</div>
          <div v-for="well in sources" :key="well.wellId" class="well-source">
            <label class="check"><input v-model="choices[well.wellId].enabled" type="checkbox" :disabled="busy || !well.records.some(r => Number(r.dynamic_gas_volume) > 0)" />{{ well.wellName }}</label>
            <select v-model="choices[well.wellId].recordId" :disabled="busy || !choices[well.wellId].enabled" :aria-label="`${well.wellName} 来源批次`">
              <option v-if="!well.records.length" :value="null">无已保存的单井结果</option>
              <option v-for="r in well.records" :key="r.id" :value="r.id" :disabled="!(Number(r.dynamic_gas_volume) > 0)">{{ stamp(r.saved_at || r.created_at) }} · 储量 {{ format(r.dynamic_gas_volume) }}</option>
            </select>
          </div>
          <div v-if="!loading && !sources.length" class="muted">当前库没有可读取的单井数据。</div>
          <label>生产水气比上限（-1 表示不限）<input v-model="limit" type="number" step="any" :disabled="busy" /></label>
          <div class="buttons"><button :disabled="busy || !selectedSources.length" @click="previewInput">预览加权输入</button><button class="primary" :disabled="busy || !preview || blocked" @click="calculate">开始计算</button></div>
          <div v-if="busy" class="muted" role="status">正在处理请求，请稍候…</div>
          <div v-if="blocked" class="muted">有任务正在计算或尚未确认结束，请先检查历史任务。</div>
        </div>
      </aside>
      <main class="results">
        <div class="dynamic-result-tabs"><button type="button" class="dynamic-result-tab active"><span class="dynamic-result-tab-text">水侵分析-{{ libraryName }}-分析结果</span></button></div>
        <div class="water-record-toolbar"><label>历史记录 <select v-model="selected" :disabled="busy"><option :value="null">请选择历史批次</option><option v-for="r in records" :key="r.id" :value="r.id">{{ stamp(r.createdAt) }} · {{ statusNames[r.taskStatus] || r.taskStatus }}</option></select></label><button v-if="record?.carrierBlocked && !running" :disabled="busy" @click="reconcile">检查计算状态</button><span v-if="record" class="water-task-message">{{ statusNames[record.taskStatus] }} · 承载井 {{ record.carrierWellName }}</span><span v-if="preview && isInputView" class="water-task-message">输入预览</span></div>
        <div v-if="error || record?.errorMessage" class="error" role="alert">{{ error || record.errorMessage }}</div>
        <nav v-if="!isInputView" class="chart-tabs"><button v-for="(name, index) in names" :key="name" class="chart-tab" :disabled="!available(index)" :title="available(index) ? name : '暂无分析结果'" :class="{ active: category === index }" @click="category = index; view = view === 'table' && index < 4 ? 'table' : 'chart'">{{ name }}</button></nav>
        <div v-if="!isInputView && category < 2 && summary.length" class="summaries"><div v-for="([key, label]) in summary" :key="key"><span>{{ label }}</span><strong>{{ key.endsWith('Desc') ? section.output[key] : format(section.output[key]) }}</strong></div></div>
        <div class="content">
          <section v-if="isInputView" class="production-data-panel" aria-label="生产数据">
            <div class="production-data-toolbar">
              <span class="production-data-title">生产数据</span>
              <span class="production-data-count">共 {{ rows.length }} 条</span>
              <button :disabled="!rows.length" @click="exportRows">导出数据</button>
            </div>
            <ElTable :data="pageRows" size="small" height="100%" border class="production-data-table" empty-text="当前批次暂无生产数据">
              <ElTableColumn prop="rowNumber" label="序号" width="64" align="center" />
              <ElTableColumn v-for="key in columns" :key="key" :prop="key" :min-width="key === 'pressure' ? 145 : key.startsWith('cumulative') ? 160 : 150" align="center">
                <template #header><div class="production-column-name">{{ productionLabels[key] }}</div><div class="production-column-unit">{{ productionUnits[key] }}</div></template>
                <template #default="{ row }"><span :title="contributors(row, key) || String(row[key] ?? '')">{{ cellValue(row, key, true) }}</span></template>
              </ElTableColumn>
            </ElTable>
            <ElPagination v-if="rows.length > 100" v-model:current-page="page" :page-size="100" :total="rows.length" small layout="prev, pager, next, jumper" class="production-data-pagination" />
          </section>
          <div v-else-if="view === 'table'" class="data-list-panel">
            <ElTable :data="dataListRows" size="small" height="100%" border stripe empty-text="当前分析项暂无数据">
              <ElTableColumn prop="rowNumber" label="序号" width="76" sortable />
              <ElTableColumn v-for="key in columns" :key="key" :prop="key === 'isDeleted' ? 'selectedText' : key" :label="resultLabels[key]" :min-width="key === 'date' || key === 'recoveryDegree' || key === 'isDeleted' ? 150 : key.endsWith('Index') ? 210 : 170" sortable :filters="key === 'isDeleted' ? [{ text: '是', value: '是' }, { text: '否', value: '否' }] : undefined" :filter-method="key === 'isDeleted' ? ((value, row) => row.selectedText === value) : undefined">
                <template #default="{ row }">{{ cellValue(row, key, false) }}</template>
              </ElTableColumn>
            </ElTable>
          </div>
          <div v-else-if="category === 4 && available(4)" class="water-activity-panel">
            <div class="activity-form">
              <div class="activity-field"><label>气藏废弃时的水侵量(10⁴m³):</label><ElInput size="small" readonly :model-value="section.output?.abandonWaterInflux ?? ''" /></div>
              <div class="activity-field"><label>原始条件下气藏容积(10⁸m³):</label><ElInput size="small" readonly :model-value="section.output?.reservoirOriginalVolume ?? ''" /></div>
              <div class="activity-field"><label>气藏废弃时的水侵替换系数(dless):</label><ElInput size="small" readonly :model-value="section.output?.waterInvasionReplacementCoefficient ?? ''" /></div>
              <div class="activity-field"><label>边底水活跃程度:</label><ElInput size="small" readonly :model-value="section.output?.waterActivenessDesc ?? ''" /></div>
            </div>
            <div class="activity-table-title">边底水活跃程度划分标准(水侵替换系数法)</div>
            <table class="activity-table"><tbody><tr><th rowspan="2">评价指标</th><th colspan="3">边底水活跃程度</th></tr><tr><th>活跃</th><th>较活跃</th><th>不活跃</th></tr><tr><td>水侵替换系数</td><td>&gt; 0.4</td><td>0.15 ~ 0.4</td><td>&lt; 0.15</td></tr></tbody></table>
          </div>
          <div v-else-if="available(category)" ref="chartHost" class="chart-host">
            <div ref="chartEl" class="chart-instance"></div>
            <div v-if="legendItems.length" class="floating-chart-legend" :class="{ dragging: draggingLegend }" :style="legendStyle" @mousedown="startLegendDrag">
              <button v-for="item in legendItems" :key="item.name" class="floating-legend-item" :class="{ hidden: hiddenLegendNames.has(item.name) }" title="点击显示/隐藏" @mousedown.stop @click.stop="toggleLegendItem(item.name)"><span class="legend-dot" :style="{ backgroundColor: hiddenLegendNames.has(item.name) ? 'transparent' : item.color, borderColor: item.color }"></span><span>{{ item.name }}</span></button>
            </div>
          </div>
          <div v-else class="empty">暂无可用的分析结果</div>
        </div>
        <nav class="bottom-chart-tabs"><button class="bottom-chart-tab" :class="{ active: isInputView }" @click="view = 'production'">生产数据</button><button class="bottom-chart-tab" :disabled="category === 4 || !available(category)" :class="{ active: view === 'table' }" @click="view = 'table'">数据列表</button><button class="bottom-chart-tab" :disabled="!available(category)" :class="{ active: view === 'chart' }" @click="view = 'chart'">结果分析图</button></nav>
      </main>
    </div>
  </section>
</template>

<style scoped>
.storage-water{display:flex;flex:1;flex-direction:column;min-width:0;min-height:0;height:100%;background:#fff;color:#303133;font:12px Arial,"Microsoft YaHei",sans-serif}
.module-bar{height:34px;flex-shrink:0;background:#fafafa;border-bottom:1px solid #e4e7ed;display:flex;align-items:stretch}
.module-bar>span{background:#f4d000;padding:0 12px;line-height:34px;font-weight:600}
.workspace{display:flex;flex:1;min-height:0;overflow:hidden}.parameters{width:258px;min-width:220px;max-width:380px;resize:horizontal;overflow:hidden;display:flex;flex-direction:column;border-right:1px solid #ddd}
.panel-head{height:34px;line-height:34px;padding:0 12px;border-bottom:1px solid #eee}.parameter-scroll{overflow:auto;flex:1;padding:12px}
label{display:block;margin-bottom:12px;line-height:1.7}input:not([type=checkbox]),select{display:block;box-sizing:border-box;width:100%;min-width:0;height:26px;padding:2px 6px;border:1px solid #d9dfeb;border-radius:2px;background:#fff;color:#465366;font:12px Arial,"Microsoft YaHei",sans-serif}input[readonly]{background:#fafbfc}
.check{display:flex;align-items:center;gap:5px;margin-bottom:4px}.check input{margin:0;accent-color:#505050}.well-source{padding:8px 0;border-bottom:1px solid #eee;margin-bottom:6px}.well-source select{font-size:11px}
.field-title{margin:14px 0 8px;display:flex;justify-content:space-between;align-items:center;font-weight:600}
button{font:12px Arial,"Microsoft YaHei",sans-serif;background:#fff;color:#465366;border:1px solid #d9dfeb;border-radius:2px;padding:4px 9px;cursor:pointer}button:hover:not(:disabled){border-color:#b79b00}button:disabled{cursor:not-allowed;color:#b4b8c0;background:#fafafa}.primary{background:#f4d000;border-color:#e0be00;color:#222}.buttons{display:flex;gap:8px;margin:14px 0}
.results{display:flex;flex:1;min-width:0;min-height:0;flex-direction:column;overflow:hidden;position:relative;--el-color-primary:#303133;--el-color-primary-light-3:#5d5e60;--el-color-primary-light-5:#858689;--el-color-primary-light-7:#b4b5b7;--el-color-primary-light-8:#d2d3d4;--el-color-primary-light-9:#eeeeef;--el-color-primary-dark-2:#111}
.muted{color:#909399;line-height:1.7}.error{padding:6px 12px;color:#a43b32;font-size:12px}
/* 与单井水侵结果区一致；仅作用于本页面，不修改公共样式或左侧参数栏。 */
.dynamic-result-tabs{height:34px;flex-shrink:0;display:flex;align-items:center;border-bottom:1px solid #e4e7ed;background:#fafafa;overflow-x:auto;overflow-y:hidden}
.dynamic-result-tab{height:34px;max-width:340px;border:0;border-right:1px solid #e4e7ed;border-bottom:2px solid transparent;border-radius:0;background:transparent;color:#409eff;font-size:14px;font-weight:600;display:flex;align-items:center;padding:0 12px;cursor:default;white-space:nowrap}
.dynamic-result-tab.active{border-bottom-color:transparent;background:#f4d000;color:#202020}.dynamic-result-tab-text{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.water-record-toolbar{display:flex;align-items:center;gap:10px;flex-wrap:wrap;padding:6px 12px;border-bottom:1px solid #e5e5e5;font-size:12px;color:#555;flex-shrink:0}
.water-record-toolbar label{display:flex;align-items:center;gap:8px;margin:0;white-space:nowrap}.water-record-toolbar select{width:280px;max-width:320px;height:25px;border:1px solid #dcdcdc;background:#fff;color:#333}.water-task-message{color:#756321}
.chart-tabs{height:34px;display:flex;border-bottom:1px solid #e4e7ed;flex-shrink:0;background:#fafafa;overflow-x:auto}
.chart-tab{border:0;border-right:1px solid #e4e7ed;border-radius:0;background:transparent;padding:0 16px;color:#555;cursor:pointer;border-bottom:2px solid transparent;white-space:nowrap}
.chart-tab:hover{color:#202020;background:#fff8d8}.chart-tab.active{color:#202020;border-bottom-color:#f2c811;box-shadow:inset 0 -3px 0 #f2c811;background:#fff;font-weight:600}
.chart-tab:disabled,.chart-tab:disabled:hover{color:#b0b0b0;background:#fafafa;border-bottom-color:transparent;cursor:not-allowed;font-weight:normal}
.content{flex:1;min-height:0;min-width:0;display:flex;flex-direction:column;overflow:hidden}.chart-host{position:relative;display:flex;flex:1;min-height:0;width:100%}.chart-instance{flex:1;min-height:0;width:100%}
.empty{margin:auto;padding:40px;color:#909399;text-align:center}.summaries{display:flex;gap:24px;flex-wrap:wrap;padding:8px 12px;border-bottom:1px solid #eee}.summaries>div{display:flex;gap:12px;align-items:center}.summaries span{color:#606266}.summaries strong{font-weight:400;color:#303133}
.production-data-panel{display:flex;flex:1;flex-direction:column;min-height:0;padding:0 12px;overflow:hidden}.production-data-toolbar{display:flex;align-items:center;flex-wrap:wrap;gap:8px;padding:10px 0;font-size:12px;flex-shrink:0}
.production-data-title{color:#303133;font-weight:500}.production-data-count{color:#909399;margin-right:auto}.production-data-table{flex:1;min-height:0}.production-data-table :deep(.el-table__cell){padding:3px 0}.production-data-table :deep(th.el-table__cell){background:#fafafa;color:#303133;font-weight:400}.production-column-unit{font-size:12px;color:#606266;font-weight:400}.production-data-pagination{padding:8px 0;align-self:flex-end;flex-shrink:0}
.data-list-panel{flex:1;min-height:0;width:100%;overflow:hidden;background:#fff}
.bottom-chart-tabs{display:flex;align-items:flex-end;height:30px;flex-shrink:0;border-top:1px solid #e4e7ed;background:#fff;overflow-x:auto}
.bottom-chart-tab{height:30px;min-width:82px;padding:0 14px;border:0;border-right:1px solid #e4e7ed;border-radius:0;background:#fff;color:#333;font-size:13px;cursor:pointer;white-space:nowrap}.bottom-chart-tab:hover:not(:disabled){background:#fff8d8;color:#202020}.bottom-chart-tab.active{color:#202020;font-weight:600;background:#fff;box-shadow:inset 0 3px 0 #f2c811}
.floating-chart-legend{position:absolute;z-index:5;display:flex;flex-direction:column;gap:5px;max-width:280px;padding:7px 10px;border:1px solid #eee;background:rgba(255,255,255,.9);color:#333;font-size:12px;line-height:1.2;cursor:move;user-select:none;box-shadow:0 1px 2px rgba(0,0,0,.04)}.floating-chart-legend.dragging{box-shadow:0 4px 12px rgba(0,0,0,.14)}
.floating-legend-item{display:flex;align-items:center;gap:5px;white-space:nowrap;cursor:pointer;border:0;padding:0;background:transparent;line-height:1.2;color:#333}.floating-legend-item.hidden{color:#999;opacity:.55}.legend-dot{width:10px;height:10px;border-radius:50%;flex-shrink:0;border:1px solid transparent}
.water-activity-panel{flex:1;min-height:0;overflow:auto;padding:14px 18px;background:#fff}.activity-form{display:grid;grid-template-columns:repeat(2,minmax(260px,1fr));column-gap:22px;row-gap:8px;margin-bottom:24px}.activity-field label{display:block;font-size:13px;color:#333;margin-bottom:4px}.activity-table-title{text-align:center;font-size:14px;color:#333;margin-bottom:8px}.activity-table{width:100%;border-collapse:collapse;table-layout:fixed;font-size:13px;color:#333}.activity-table th,.activity-table td{border:1px solid #333;height:44px;text-align:center;font-weight:400}
@media(max-width:1000px){.activity-form{grid-template-columns:minmax(0,1fr)}.water-record-toolbar select{max-width:40vw}}
</style>
