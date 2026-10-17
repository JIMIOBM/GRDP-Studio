<script setup>
import { ref, reactive, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import {
  PREDICTION_METRICS, resolvePredictionId, fetchPredictionDetail,
  calculatePrediction, fetchGroupChart, parseForecastCharts, buildReservoirCalcInput
} from '@/api/reservoirPredictionApi'
import { getReservoirPredictionContext } from './reservoirPredictionContext'

const props = defineProps({
  projectId: { type: [Number, String], required: true },
  gasReservoirId: { type: [Number, String], required: true },
  storageId: { type: [Number, String], required: true },
  storageName: { type: String, default: '' },
  dynamicPredictionId: { type: [Number, String], default: null },
  dynamicPredictionRequest: { type: Object, default: null }
})
const emit = defineEmits(['solution-generated']) // 本版本没有保存事件
const context = computed(() => getReservoirPredictionContext(
  props.projectId, props.gasReservoirId, props.storageId
))
const params = reactive({ targetProduction: '', exportPressure: '' })
const selectedConstraintId = ref('')
const constraints = computed(() => context.value.constraintDraft
  ? [{ id: 'current', label: '当前已应用的约束参数（未保存）' }] : [])
const busy = ref(false)
const loadingChart = ref(false)
const errorText = ref('')
const statusText = ref('')
const result = ref(null)
const metricKey = ref('gasProduction')
const selectedMetric = computed(() => PREDICTION_METRICS.find(m => m.key === metricKey.value) || PREDICTION_METRICS[0])
const hasPoints = computed(() => !!result.value &&
  PREDICTION_METRICS.some(m => result.value.charts[m.key]?.some(p => p[1] !== null)))
const chartRef = ref(null)
let chartInstance = null
let requestVersion = 0

function syncAppliedForm() {
  const other = context.value.constraintDraft?.otherData
  selectedConstraintId.value = other ? 'current' : ''
  if (other) {
    params.targetProduction = String(other.targetProduction ?? '')
    params.exportPressure = String(other.wellheadExportPressure ?? '')
  }
  result.value = null
  clearChart()
}
function clearChart() {
  if (chartInstance) { chartInstance.dispose(); chartInstance = null }
}
function taskId() {
  return resolvePredictionId(
    props.projectId, props.gasReservoirId,
    props.dynamicPredictionId ?? props.dynamicPredictionRequest?.dynamicPredictionId,
    context.value.predictionId
  )
}
async function loadDetail() {
  const id = taskId()
  const detail = await fetchPredictionDetail(props.projectId, props.gasReservoirId, id)
  if (!detail?.reservoirInput) throw new Error(`动态预测记录 ${id} 不存在或缺少 reservoirInput，不能继续计算`)
  context.value.predictionId = id
  return { id, input: detail.reservoirInput }
}
function ensureAnyPoints(charts) {
  if (!PREDICTION_METRICS.some(m => charts[m.key]?.some(p => p[1] !== null))) {
    throw new Error('图表接口没有返回有效预测点，不能画假曲线')
  }
}
async function setRealChart(payload, input, id, addPreview, version) {
  const charts = parseForecastCharts(payload, {
    forecastStartDate: input.predictionStartTime, onlyForecast: true
  })
  ensureAnyPoints(charts)
  if (version !== requestVersion) return
  const snapshot = {
    predictionId: id,
    charts,
    input: { ...input },
    retrievedAt: new Date().toISOString()
  }
  result.value = snapshot
  if (addPreview) {
    const no = context.value.nextSolutionNo++
    context.value.liveSolutions.push({
      id: `preview-${no}`, name: `本次方案 ${no}`,
      createdAt: snapshot.retrievedAt, charts: JSON.parse(JSON.stringify(charts))
    })
    if (context.value.liveSolutions.length > 20) context.value.liveSolutions.shift()
  }
  await nextTick()
  if (version !== requestVersion) return
  renderChart()
  emit('solution-generated', snapshot)
}
async function generateSolution() {
  if (busy.value || loadingChart.value) return
  if (!context.value.constraintDraft || selectedConstraintId.value !== 'current') {
    ElMessage.warning('请先在“约束条件”页面应用原始输入参数')
    return
  }
  busy.value = true
  errorText.value = ''
  statusText.value = '正在获取原平台预测任务参数...'
  const version = ++requestVersion
  try {
    const { id, input: source } = await loadDetail()
    if (version !== requestVersion) return
    const input = buildReservoirCalcInput(source, context.value.constraintDraft, params)
    statusText.value = `正在调用真实预测 Calc 接口（任务 ${id}）...`
    await calculatePrediction(props.projectId, props.gasReservoirId, id, input)
    if (version !== requestVersion) return
    statusText.value = '计算接口返回成功，正在加载井组预测曲线...'
    const payload = await fetchGroupChart(props.projectId, props.gasReservoirId, id)
    await setRealChart(payload, input, id, true, version)
    if (version !== requestVersion) return
    statusText.value = 'Calc 请求成功，已读取当前服务端曲线（仅显示预测段）；如后台异步计算，请稍后点击“读取已有曲线”核对更新'
    ElMessage.success('Calc 请求成功，已读取真实曲线')
  } catch (error) {
    if (version !== requestVersion) return
    errorText.value = error?.message || '接口失败'
    statusText.value = ''
    ElMessage.error(errorText.value)
  } finally {
    if (version === requestVersion) busy.value = false
  }
}
async function readExistingChart() {
  if (busy.value || loadingChart.value) return
  loadingChart.value = true
  errorText.value = ''
  const version = ++requestVersion
  try {
    const { id, input } = await loadDetail()
    const payload = await fetchGroupChart(props.projectId, props.gasReservoirId, id)
    await setRealChart(payload, input, id, false, version)
    if (version === requestVersion) statusText.value = `已读取任务 ${id} 现有的真实预测曲线（未调用 Calc）`
  } catch (error) {
    if (version === requestVersion) {
      errorText.value = error?.message || '读取曲线失败'
      ElMessage.error(errorText.value)
    }
  } finally {
    if (version === requestVersion) loadingChart.value = false
  }
}
function renderChart() {
  if (!chartRef.value || !result.value) return
  if (chartInstance && chartInstance.getDom() !== chartRef.value) clearChart()
  if (!chartInstance) chartInstance = echarts.init(chartRef.value)
  const metric = selectedMetric.value
  const data = result.value.charts[metric.key] || []
  chartInstance.setOption({
    title: { text: `${props.storageName || '井组'} - ${metric.name}预测`,
      left: 'center', textStyle: { fontSize: 14, fontWeight: 600 } },
    tooltip: { trigger: 'axis', valueFormatter: value =>
      Array.isArray(value) && Number.isFinite(value[1]) ? `${value[1].toFixed(4)} ${metric.unit}` : '—' },
    grid: { left: '3%', right: '5%', bottom: 55, top: 55, containLabel: true },
    dataZoom: [{ type: 'inside', xAxisIndex: 0 }, { type: 'slider', height: 18, bottom: 9 }],
    xAxis: { type: 'time', name: '时间', axisLabel: { formatter: '{yyyy}-{MM}' } },
    yAxis: { type: 'value', name: `${metric.name}(${metric.unit})`, scale: true },
    series: [{
      name: metric.name, type: 'line', data, showSymbol: false,
      connectNulls: false, smooth: false,
      lineStyle: { color: '#d5b900', width: 2 }, itemStyle: { color: '#d5b900' },
      areaStyle: metric.key === 'gasProduction' ? {
        color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
          { offset: 0, color: 'rgba(244, 208, 0, 0.25)' },
          { offset: 1, color: 'rgba(244, 208, 0, 0.05)' }
        ])
      } : undefined
    }]
  }, true)
  chartInstance.resize()
}
function selectMetric(key) {
  metricKey.value = key
  nextTick(renderChart)
}
function onConstraintChange() {
  result.value = null
  clearChart()
}
const handleResize = () => chartInstance?.resize()
watch(() => context.value.appliedAt, syncAppliedForm)
watch(() => [props.projectId, props.gasReservoirId, props.storageId], syncAppliedForm)
watch(params, () => { result.value = null; clearChart() }, { deep: true })
onMounted(() => { syncAppliedForm(); window.addEventListener('resize', handleResize) })
onBeforeUnmount(() => {
  requestVersion++
  window.removeEventListener('resize', handleResize)
  clearChart()
})
</script>

<template>
  <div class="solution-generation-workspace">
    <aside class="sg-sidebar">
      <div class="panel-head">参数输入</div>
      <div class="sg-content">
        <label class="field">
          <span>库群目标产量 <small>(万m³/d)</small></span>
          <input v-model="params.targetProduction" type="number" min="0" step="any"
            placeholder="请输入库群目标产量" :disabled="busy || loadingChart">
        </label>
        <label class="field">
          <span>外输压力 <small>(MPa)</small></span>
          <input v-model="params.exportPressure" type="number" min="0" step="any"
            placeholder="请输入外输压力" :disabled="busy || loadingChart">
        </label>
        <label class="field">
          <span>约束条件</span>
          <select v-model="selectedConstraintId" :disabled="busy || loadingChart" @change="onConstraintChange">
            <option value="">请选择约束条件</option>
            <option v-for="item in constraints" :key="item.id" :value="item.id">{{ item.label }}</option>
          </select>
        </label>
        <p v-if="!constraints.length" class="sg-hint">请先在“约束条件”页应用原有参数。本版不新增或保存方案。</p>
        <div class="form-actions">
          <button class="calculate-button" type="button" :disabled="busy || loadingChart" @click="generateSolution">
            {{ busy ? '计算中…' : '生成方案' }}
          </button>
          <button class="calculate-button" type="button" :disabled="busy || loadingChart" @click="readExistingChart">
            {{ loadingChart ? '读取中…' : '读取已有曲线' }}
          </button>
        </div>
        <p v-if="statusText" class="sg-status">{{ statusText }}</p>
        <p v-if="errorText" class="sg-error">{{ errorText }}</p>
      </div>
    </aside>
    <main class="sg-chart-area">
      <div class="result-tabs"><div class="result-tab">{{ storageName ? `${storageName} - 方案生成` : '方案生成' }}</div></div>
      <div class="sg-chart-body">
        <div v-if="!hasPoints" class="sg-empty">先应用约束参数，再点击“生成方案”（真实数据，不画模拟曲线）</div>
        <template v-else>
          <div class="metric-tabs">
            <button v-for="metric in PREDICTION_METRICS" :key="metric.key" type="button"
              :class="{ active: metricKey === metric.key }" @click="selectMetric(metric.key)">
              {{ metric.name }}
            </button>
          </div>
          <div ref="chartRef" class="prediction-chart"></div>
        </template>
      </div>
    </main>
  </div>
</template>
<style lang="scss" scoped>
.solution-generation-workspace {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  overflow: hidden;
  background: #fff;
  color: #252525;
  font: 13px/1.5 "Microsoft YaHei", "Segoe UI", Arial, sans-serif;
}

.sg-sidebar {
  flex: 0 0 260px;
  display: flex;
  flex-direction: column;
  border-right: 1px solid #d7d7d7;
  background: #fff;
  overflow-y: auto;
}

.panel-head {
  display: flex;
  flex: 0 0 34px;
  align-items: center;
  padding: 0 12px;
  border-bottom: 1px solid #d7d7d7;
  background: #f2f2f2;
  font-weight: 600;
}

.sg-content {
  padding: 10px 12px 16px;
  flex: 1;
}

.field {
  display: block;
  min-width: 0;
  margin-bottom: 9px;
}

.field > span {
  display: block;
  margin-bottom: 3px;
  color: #252525;
  font-size: 12px;
}

.field small {
  color: #999;
  font-size: 11px;
}

.field select,
.field input {
  display: block;
  width: 100%;
  min-width: 0;
  height: 24px;
  padding: 0 8px;
  box-sizing: border-box;
  border: 1px solid #aaa;
  border-radius: 3px;
  background: #fff;
  color: #333;
  font: inherit;
  font-size: 12px;
}

.field select:focus-visible,
.field input:focus-visible {
  outline: 1px solid #d5b900;
  outline-offset: 1px;
}

.sg-hint {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
  margin: 8px 0;
  padding: 6px 8px;
  background: #f4f4f5;
  border-radius: 3px;
}

.form-actions {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 14px;
}

.calculate-button,
.save-button {
  min-width: 86px;
  height: 30px;
  padding: 0 22px;
  border: 1px solid #d5b900;
  border-radius: 4px;
  background: #f4d000;
  color: #222;
  font: inherit;
  font-weight: 500;
  cursor: pointer;
}

.calculate-button:hover:not(:disabled),
.save-button:hover:not(:disabled) {
  background: #ffe033;
}

.calculate-button:disabled,
.save-button:disabled {
  opacity: 0.6;
  cursor: wait;
}

.sg-status {
  margin-top: 10px;
  padding: 6px 8px;
  background: #f4f4f5;
  border-radius: 3px;
  font-size: 12px;
  color: #606266;
}

.sg-error {
  margin-top: 10px;
  padding: 6px 8px;
  background: #fef0f0;
  border: 1px solid #fbc4c4;
  border-radius: 3px;
  font-size: 12px;
  color: #f56c6c;
}

.sg-chart-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.result-tabs {
  height: 34px;
  flex: 0 0 34px;
  display: flex;
  align-items: center;
  border-bottom: 1px solid #e4e7ed;
  background: #fafafa;
}

.result-tab {
  height: 34px;
  line-height: 34px;
  max-width: min(430px, 100%);
  padding: 0 12px;
  border-right: 1px solid #e4e7ed;
  box-sizing: border-box;
  background: #f4d000;
  color: #202020;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 13px;
}

.sg-chart-body {
  flex: 1;
  overflow: hidden;
  padding: 16px;
}

.prediction-chart {
  width: 100%;
  height: 100%;
}

.sg-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #909399;
  font-size: 13px;
}
</style>
<style scoped>
.sg-chart-body { display: flex; flex-direction: column; }
.metric-tabs { display: flex; gap: 6px; flex-wrap: wrap; padding: 0 0 10px; }
.metric-tabs button { padding: 6px 12px; border: 1px solid #dadada; border-radius: 3px;
  background: #f6f6f6; color: #444; cursor: pointer; }
.metric-tabs button.active { border-color: #d5b900; background: #fff6b8; color: #242424; }
.prediction-chart { flex: 1; min-height: 300px; height: auto; }
</style>
