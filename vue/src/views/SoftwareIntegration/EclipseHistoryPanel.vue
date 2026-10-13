<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import * as echarts from 'echarts'
import EclipseHistoryBatchPanel from './EclipseHistoryBatchPanel.vue'
import { softwareIntegrationApi } from '@/api/softwareIntegration'
import { buildHistoryRequest, historyBaselineAvailable, historyRunAvailable, historyIdentity, historyCsvExport, validHistoryMetadata, validHistoryDataset, validHistoryEvaluation } from './eclipseHistory.js'
const props = defineProps({ run: { type: Object, default: null } })
const emit = defineEmits(['select-run'])
const records = ref([]), datasetId = ref(null), dataset = ref(null), evaluation = ref(null), error = ref(''), busy = ref(false)
const name = ref(''), selectedFile = ref(null), fileInput = ref(null), metricKey = ref(''), pointPage = ref(1), metricPage = ref(1), chartElement = ref(null)
const canImport = computed(() => historyBaselineAvailable(props.run)), canCompare = computed(() => historyRunAvailable(props.run))
const metrics = computed(() => evaluation.value?.comparison.metrics || [])
const selectedMetric = computed(() => metrics.value.find(metric => historyIdentity(metric) === metricKey.value))
const points = computed(() => (evaluation.value?.comparison.points || []).filter(point => historyIdentity(point) === metricKey.value))
const visiblePoints = computed(() => points.value.slice((pointPage.value - 1) * 100, pointPage.value * 100))
const visibleMetrics = computed(() => metrics.value.slice((metricPage.value - 1) * 20, metricPage.value * 20))
let epoch = 0, listToken = 0, detailToken = 0, comparisonToken = 0, uploadToken = 0, plotToken = 0, disposed = false, chart = null, observer = null
const context = () => ({ epoch, version: props.run?.modelVersionId })
const current = ctx => !disposed && ctx.epoch === epoch && ctx.version === props.run?.modelVersionId
const message = e => e?.message || e?.msg || '实测校核请求失败，请重试。'
async function loadRecords() {
  const ctx = context(), token = ++listToken
  try {
    const reply = await softwareIntegrationApi.listEclipseHistory(ctx.version)
    if (!current(ctx) || token !== listToken) return
    if (!Array.isArray(reply.data) || !reply.data.every(row => validHistoryMetadata(row, ctx.version))) throw new Error('实测记录的模型版本或来源信息不完整。')
    records.value = reply.data
    const nextId = reply.data.some(row => row.id === datasetId.value) ? datasetId.value : reply.data[0]?.id || null
    if (nextId && nextId === datasetId.value) await loadDataset(nextId)
    else datasetId.value = nextId
  } catch (e) { if (current(ctx) && token === listToken) error.value = message(e) }
}
async function loadDataset(id) {
  const ctx = context(), token = ++detailToken
  comparisonToken++; dataset.value = null; evaluation.value = null; error.value = ''
  if (!id) return
  try {
    const reply = await softwareIntegrationApi.getEclipseHistory(id)
    if (!current(ctx) || token !== detailToken || datasetId.value !== id) return
    if (!validHistoryDataset(reply.data, ctx.version) || reply.data.metadata.id !== id) throw new Error('实测原文件或模型版本不一致。')
    const actual = await digest(reply.data.csv)
    if (!current(ctx) || token !== detailToken || datasetId.value !== id) return
    if (actual !== reply.data.metadata.sourceSha256) throw new Error('原 CSV 校验不一致。')
    dataset.value = reply.data
    await compare()
  } catch (e) { if (current(ctx) && token === detailToken) error.value = message(e) }
}
async function compare() {
  const ctx = context(), token = ++comparisonToken, run = props.run, source = dataset.value
  evaluation.value = null; metricKey.value = ''; pointPage.value = 1; metricPage.value = 1
  if (!source || !historyRunAvailable(run)) return
  try {
    const reply = await softwareIntegrationApi.compareEclipseHistory(source.metadata.id, run.id)
    if (!current(ctx) || token !== comparisonToken || props.run?.id !== run.id || datasetId.value !== source.metadata.id) return
    if (!validHistoryEvaluation(reply.data, source, run)) throw new Error('返回值未能与当前实际运行、观察源和报告时刻核对。')
    evaluation.value = reply.data; metricKey.value = historyIdentity(reply.data.comparison.metrics[0]); error.value = ''
  } catch (e) { if (current(ctx) && token === comparisonToken) error.value = message(e) }
}
async function digest(csv) {
  const hash = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(csv))
  return [...new Uint8Array(hash)].map(byte => byte.toString(16).padStart(2, '0')).join('')
}
function selectFile(event) {
  selectedFile.value = event.target.files?.[0] || null
  if (selectedFile.value && !name.value) name.value = selectedFile.value.name.replace(/\.csv$/i, '')
}
async function upload() {
  if (busy.value) return
  const ctx = context(), token = ++uploadToken, run = props.run, file = selectedFile.value, label = name.value
  busy.value = true; error.value = ''; listToken++
  try {
    if (!file || file.size > 1048576) throw new Error('请选择不超过1MiB的UTF-8 CSV。')
    const csv = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(await file.arrayBuffer())
    if (!current(ctx) || token !== uploadToken || props.run?.id !== run?.id) return
    const body = buildHistoryRequest({ run, name: label, sourceFile: file.name, csv }), sourceSha = await digest(csv)
    if (!current(ctx) || token !== uploadToken || props.run?.id !== run?.id) return
    const reply = await softwareIntegrationApi.createEclipseHistory(ctx.version, body)
    if (!current(ctx) || token !== uploadToken) return
    if (!validHistoryDataset(reply.data, ctx.version) || reply.data.csv !== csv || reply.data.metadata.sourceSha256 !== sourceSha) throw new Error('保存的 CSV 与所选原文件不一致，请刷新核查。')
    records.value = [reply.data.metadata, ...records.value.filter(row => row.id !== reply.data.metadata.id)].slice(0, 50)
    datasetId.value = reply.data.metadata.id
    if (dataset.value?.metadata.id === datasetId.value) await loadDataset(datasetId.value)
    selectedFile.value = null; if (fileInput.value) fileInput.value.value = ''
  } catch (e) { if (current(ctx) && token === uploadToken) error.value = e instanceof TypeError && /encod|decode/i.test(e.message) ? '文件必须使用UTF-8编码，请另存为CSV UTF-8后导入。' : message(e) }
  finally { if (current(ctx) && token === uploadToken) busy.value = false }
}
function download(content, filename) {
  const url = URL.createObjectURL(new Blob([content], { type: 'text/csv;charset=utf-8' }))
  const link = document.createElement('a'); link.href = url; link.download = filename; link.click(); setTimeout(() => URL.revokeObjectURL(url), 0)
}
const exportPoints = () => evaluation.value && download(historyCsvExport(evaluation.value), `eclipse-${props.run.id}-history-comparison.csv`)
const downloadSource = () => dataset.value && download(dataset.value.csv, dataset.value.metadata.sourceFile)
async function renderChart() {
  const token = ++plotToken; await nextTick()
  if (disposed || token !== plotToken) return
  if (!chartElement.value || !selectedMetric.value) { observer?.disconnect(); chart?.dispose(); chart = null; return }
  if (!chart || chart.getDom() !== chartElement.value) {
    observer?.disconnect(); chart?.dispose(); chart = echarts.init(chartElement.value)
    observer = new ResizeObserver(() => chart?.resize()); observer.observe(chartElement.value)
  }
  const metric = selectedMetric.value, predicted = props.run.result.summary.series.find(s => s.keyword === metric.keyword && (s.objectName || '') === metric.well && s.unit === metric.unit)
  chart.setOption({ animation: false, tooltip: { trigger: 'axis', renderMode: 'richText' }, legend: { data: ['计算值','观察值'] },
    grid: { left: 70, right: 30, top: 45, bottom: 45, containLabel: true }, xAxis: { type: 'value', name: '时间（天）' }, yAxis: { type: 'value', name: metric.unit },
    series: [{ name: '计算值', type: 'line', showSymbol: false, connectNulls: false, data: (predicted?.points || []).map(p => [p.timeDays,p.value]) },
      { name: '观察值', type: 'scatter', symbolSize: 7, data: points.value.map(p => [p.timeDays,p.observed]) }] }, true)
  chart.resize()
}
watch(() => props.run?.modelVersionId, version => {
  epoch++; listToken++; detailToken++; comparisonToken++; uploadToken++
  records.value = []; datasetId.value = null; dataset.value = null; evaluation.value = null; selectedFile.value = null; name.value = ''; error.value = ''; busy.value = false
  if (fileInput.value) fileInput.value.value = ''
  if (Number.isSafeInteger(version) && version > 0) loadRecords()
}, { immediate: true })
watch(datasetId, loadDataset)
watch(() => [props.run?.id, props.run?.status, props.run?.resultExpired, props.run?.resultContract, props.run?.result], () => { error.value = ''; compare() })
watch([selectedMetric, points, () => props.run?.id], renderChart)
watch(metricKey, () => { pointPage.value = 1 })
onBeforeUnmount(() => { disposed = true; epoch++; plotToken++; observer?.disconnect(); chart?.dispose() })
const format = value => value == null ? '不可计算' : Number(value).toPrecision(8)
</script>

<template>
  <section v-if="Number.isSafeInteger(run?.modelVersionId)" aria-label="ECLIPSE 历史实测校核" class="eclipse-history-panel">
    <h3>历史实测校核</h3>
    <p>导入现场观察值，对照同模型版本的实际计算结果。误差为计算值减观察值；当前使用原单位及 Summary 累计天数，不按行号对齐。</p>
    <el-alert v-if="error" type="error" :title="error" :closable="false" />
    <el-form v-if="canImport" label-position="top" :disabled="busy">
      <el-form-item label="实测数据名称"><el-input v-model="name" maxlength="100" aria-label="实测数据名称" /></el-form-item>
      <input ref="fileInput" type="file" accept=".csv,text/csv" aria-label="历史实测 CSV 文件" :disabled="busy" @change="selectFile" />
      <p>UTF-8、逗号分隔，最多1MiB/5000点。表头：well,keyword,unit,timeDays,value；全场指标的well留空。井名、指标及单位须与基线曲线一致。日历日期需先换算到模型时间轴。</p>
      <el-button type="primary" :loading="busy" :disabled="!selectedFile" @click="upload">导入并校核</el-button>
    </el-form>
    <p v-else>请先选择完整无参数 E100 基线导入观察数据，再切换成功方案查看同一份数据的拟合指标。</p>
    <el-button :disabled="busy" @click="loadRecords">刷新实测记录</el-button>
    <el-select v-if="records.length" v-model="datasetId" aria-label="实测数据记录" :disabled="busy"><el-option v-for="record in records" :key="record.id" :value="record.id" :label="`${record.name} · ${record.observationCount}点`" /></el-select>
    <template v-if="dataset">
      <p>{{ dataset.metadata.name }} · {{ dataset.metadata.sourceFile }} · {{ dataset.metadata.observationCount }}点</p>
      <details><summary>查看原文件校验值</summary>{{ dataset.metadata.sourceSha256 }}</details>
      <el-button @click="downloadSource">下载原实测 CSV</el-button>
      <p v-if="!canCompare">当前运行无未过期的完整实际结果，不能计算拟合指标。</p>
    </template>
    <template v-if="evaluation">
      <p>运行 #{{ evaluation.runId }}。MAE/RMSE/平均误差采用本行单位；覆盖率为可比点/观察点；MAPE仅使用非零观察值并披露点数，超数值范围时不可计算。</p>
      <el-table :data="visibleMetrics" border size="small" aria-label="历史拟合指标">
        <el-table-column prop="well" label="井/对象" /><el-table-column prop="keyword" label="指标" /><el-table-column prop="unit" label="单位" /><el-table-column prop="observedCount" label="观察点" /><el-table-column prop="matchedCount" label="可比点" />
        <el-table-column label="覆盖率"><template #default="{ row }">{{ (row.coverage * 100).toFixed(2) }}%</template></el-table-column>
        <el-table-column label="MAE"><template #default="{ row }">{{ format(row.mae) }}</template></el-table-column><el-table-column label="RMSE"><template #default="{ row }">{{ format(row.rmse) }}</template></el-table-column><el-table-column label="平均误差"><template #default="{ row }">{{ format(row.bias) }}</template></el-table-column>
        <el-table-column label="MAPE（%）"><template #default="{ row }">{{ format(row.mape) }} · {{ row.mapeCount }}点<span v-if="row.mapeOverflowCount"> · {{ row.mapeOverflowCount }}点超范围</span></template></el-table-column>
      </el-table>
      <el-pagination v-if="metrics.length > 20" v-model:current-page="metricPage" :page-size="20" :total="metrics.length" layout="prev, pager, next" />
      <el-select v-model="metricKey" filterable aria-label="历史校核指标"><el-option v-for="metric in metrics" :key="historyIdentity(metric)" :value="historyIdentity(metric)" :label="`${metric.well || '全场'} · ${metric.keyword} · ${metric.unit}`" /></el-select>
      <div v-if="selectedMetric" ref="chartElement" class="history-chart" role="img" aria-label="观察值与计算值曲线" />
      <el-button @click="exportPoints">导出逐点校核 CSV</el-button>
      <el-table :data="visiblePoints" max-height="350" border size="small" aria-label="历史逐点校核">
        <el-table-column prop="well" label="井/对象" /><el-table-column prop="keyword" label="指标" /><el-table-column prop="timeDays" label="时间（天）" /><el-table-column prop="unit" label="单位" /><el-table-column prop="sourceLine" label="源行" />
        <el-table-column label="观察值"><template #default="{ row }">{{ format(row.observed) }}</template></el-table-column><el-table-column label="计算值"><template #default="{ row }">{{ format(row.simulated) }}</template></el-table-column><el-table-column label="误差"><template #default="{ row }">{{ format(row.difference) }}</template></el-table-column><el-table-column prop="reason" label="缺失原因" />
      </el-table>
      <el-pagination v-if="points.length > 100" v-model:current-page="pointPage" :page-size="100" :total="points.length" layout="prev, pager, next" />
    </template>
    <EclipseHistoryBatchPanel v-if="dataset" :dataset="dataset" @select-run="emit('select-run', $event)" />
  </section>
</template>
<style scoped>
.eclipse-history-panel { margin: 20px 0; }
.eclipse-history-panel .el-form { max-width: 650px; }
.eclipse-history-panel .el-table, .eclipse-history-panel .el-alert { margin: 12px 0; }
.eclipse-history-panel .el-select { min-width: 260px; }
.history-chart { height: 320px; margin: 12px 0; }
</style>
