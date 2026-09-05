<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { calculateWellboreTemperature } from '@/api/wellboreTemperature'
import { loadTemperatureSources } from '@/api/temperatureSources'
import { pvtStorageApi } from '@/api/pvtStorage'
import { numberOf, productionValues, unpack } from '@/utils/temperatureSources'

const props = defineProps({ node: { type: Object, required: true }, projectId: { type: [Number, String], required: true }, gasReservoirId: { type: [Number, String], required: true } })
const defaults = {
  tempModel: 'alves', depth: 3100, idTubing: 62, tGrad: 3, angle: 0, step: 50,
  gammaG: 0.65, rhoL: 1000, tSurf: 20, uTo: 8, wallMm: 6.35, muJt: 9,
  cpGas: 2200, formationK: 2.5, formationRhoCp: 2.3, tWh: null, fWh: null, qGas: null, qLiq: null,
  calculationPosition: 'wellhead', roughness: 0.016, muL: null, pvtId: null
}
const saved = props.node.temperatureState
const form = reactive({ ...defaults, ...saved?.input, tempModel: 'alves' })
const result = ref(saved?.result?.tempModel === 'linear' ? null : saved?.result || null)
const displayedPosition = ref(form.calculationPosition)
const positionName = computed(() => displayedPosition.value === 'bottomhole' ? '井底' : '井口')
const sourceLoading = ref(false)
const sourceData = ref(null)
const sourceErrors = ref([])
const selectedTubing = ref('')
const pvtReady = ref(false)
const sourceFields = new Set(['depth', 'idTubing', 'angle', 'gammaG', 'rhoL'])
const lastInput = ref(props.node.temperatureState?.lastInput || '')
const busy = ref(false)
const error = ref('')
const chartElement = ref(null)
let chart, resizeObserver
let disposed = false
const dirty = computed(() => result.value && JSON.stringify(form) !== lastInput.value)
const parameterPanel = ref(null)
const panelWidth = ref(238)
let previousCursor = '', previousSelection = ''
const resizePanel = event => {
  const rect = parameterPanel.value?.getBoundingClientRect()
  if (rect) panelWidth.value = Math.max(238, Math.min(620, parameterPanel.value.parentElement.clientWidth - 320, event.clientX - rect.left))
}
const stopResize = () => {
  window.removeEventListener('pointermove', resizePanel)
  window.removeEventListener('pointerup', stopResize)
  document.body.style.cursor = previousCursor
  document.body.style.userSelect = previousSelection
}
const startResize = event => {
  event.preventDefault()
  previousCursor = document.body.style.cursor
  previousSelection = document.body.style.userSelect
  document.body.style.cursor = 'col-resize'
  document.body.style.userSelect = 'none'
  window.addEventListener('pointermove', resizePanel)
  window.addEventListener('pointerup', stopResize, { once: true })
}
// Keep independent edited production values for each boundary position.
const productionDrafts = new Map()
watch(() => form.calculationPosition, (position, previous) => {
  productionDrafts.set(previous, Object.fromEntries(['fWh', 'tWh', 'qGas', 'qLiq'].map(key => [key, form[key]])))
  Object.assign(form, productionDrafts.get(position) ?? productionValues(sourceData.value?.production, position, sourceData.value?.productionFields))
  displayedPosition.value = position
}, { flush: 'sync' })
const groups = computed(() => [
  { title: '井身结构与物性', fields: [
    ['depth', '测井深度 (m)', 0.001, 100000], ['idTubing', '油管内径 (mm)', 0.001, 2000],
    ['tGrad', '地温梯度 (℃/100m)', 0, 100], ['angle', '井斜角 (°)', 0, 90],
    ['step', '计算步长 (m)', 0.001, 100000], ['gammaG', '气体相对密度', 0.001, 10],
    ['rhoL', '液体密度 (kg/m³)', 0.001, 10000]
  ] },
  { title: '温度模型参数', fields: [
    ['tSurf', '地表温度 (℃)', -273.14, 1000], ['uTo', '总传热系数 (W/m²·K)', 0.001, 100000],
    ['wallMm', '油管壁厚 (mm)', 0, 500], ['muJt', '焦耳–汤姆逊系数 (K/MPa)', -1000, 1000],
    ['cpGas', '气体定压比热 (J/kg·K)', 0.001, 100000], ['formationK', '地层导热系数 (W/m·K)', 0.001, 1000],
    ['formationRhoCp', '地层体积热容 (MJ/m³·K)', 0.001, 1000]
  ] },
  { title: '生产数据', fields: [
      ['fWh',`${positionName.value}油压 (MPa)`,0.000001,1000],
      ['tWh', `${positionName.value}温度 (℃)`, -273.14, 1000],
      ['qGas', `${positionName.value}日产气量 (×10⁴ m³/d)`, 0, 1000000],
      ['qLiq', `${positionName.value}日产水量 (m³/d)`, 0, 1000000]
  ] }
])
const selectTubing = () => { form.idTubing = sourceData.value?.tubings.find(row => row.key === selectedTubing.value)?.diameter ?? null }
const selectPvt = async () => {
  error.value = ''
  pvtReady.value = false
  form.rhoL = 1000
  form.muL = null
  if (!form.pvtId) return
  const id = form.pvtId
  try {
    const detail = unpack(await pvtStorageApi.getDetail(id, props.projectId, props.gasReservoirId, props.node.wellName))
    if (disposed || form.pvtId !== id) return
    form.gammaG = numberOf(detail.gasInput?.specificGravity) ?? sourceData.value?.input.gammaG ?? null
    if (detail.waterInput?.salinity == null || detail.waterInput?.formationPressure == null) throw new Error('请先在PVT中保存地层水矿化度和原始压力')
    pvtReady.value = true
  } catch (e) { if (!disposed) error.value = e.msg || e.message || 'PVT读取失败' }
}
const refreshSources = async () => {
  sourceLoading.value = true
  pvtReady.value = false
  error.value = ''
  sourceErrors.value = []
  sourceData.value = null
  for (const key of sourceFields) form[key] = key === 'rhoL' ? 1000 : null
  try {
    const data = await loadTemperatureSources(props.projectId, props.gasReservoirId, props.node.wellName)
    if (disposed) return
    sourceData.value = data
    sourceErrors.value = data.errors
    Object.assign(form, data.input, productionValues(data.production, displayedPosition.value, data.productionFields))
    if (saved?.input) for (const key of ['fWh', 'tWh', 'qGas', 'qLiq']) form[key] = saved.input[key] ?? form[key]
    selectedTubing.value = data.tubings[0]?.key ?? ''
    form.pvtId = data.pvtRecords.some(p => p.pvtId === form.pvtId) ? form.pvtId : data.pvtRecords[0]?.pvtId ?? null
    await selectPvt()
  } catch (e) { if (!disposed) error.value = e.message || '源数据加载失败' }
  finally { sourceLoading.value = false }
}
const persist = () => {
  props.node.temperatureState = { input: { ...form }, result: result.value, lastInput: lastInput.value }
}
watch(form, persist)
const drawChart = async () => {
  await nextTick()
  if (disposed || !chartElement.value) return
  chart ||= echarts.init(chartElement.value)
  chart.clear()
  if (!result.value) return
  const r = result.value
  const axisStyle = {
    axisLine: { lineStyle: { color: '#444' } },
    axisTick: { show: true },
    minorTick: { show: true, splitNumber: 5 },
    splitLine: { show: true, lineStyle: { color: '#dfe7f2' } },
    minorSplitLine: { show: true, lineStyle: { color: '#f0f4fa' } },
    nameTextStyle: { color: '#333', fontSize: 14 }
  }
  chart.setOption({
    animation: false,
    color: ['#0037b5', '#333'],
    title: { text: '温度分布曲线', left: 'center', top: 12, textStyle: { color: '#333', fontSize: 16, fontWeight: 600 } },
    tooltip: { trigger: 'axis', axisPointer: { axis: 'y' }, formatter: points => {
      if (!points.length) return ''
      return `井深：${points[0].value[1].toFixed(1)} m<br/>` + points.map(p => `${p.marker}${p.seriesName}：${p.value[0].toFixed(2)} ℃`).join('<br/>')
    } },
    legend: {
      right: 38, top: 68, orient: 'vertical', itemWidth: 18, itemHeight: 2,
      icon: 'rect', itemGap: 8, padding: [7, 10], borderColor: '#eeeeee', borderWidth: 1,
      backgroundColor: 'rgba(255,255,255,0.9)', textStyle: { color: '#333', fontSize: 12 },
      data: ['流体温度', '地层温度']
    },
    grid: { left: 70, right: 28, top: 58, bottom: 58, containLabel: false },
    xAxis: { ...axisStyle, type: 'value', name: '温度 (℃)', nameLocation: 'middle', nameGap: 34, scale: true, min: Math.min(r.temp[0], r.tempFormation[0]) },
    yAxis: { ...axisStyle, type: 'value', name: '井深 (m)', nameLocation: 'middle', nameGap: 48, inverse: true, min: 0, max: r.depth.at(-1) },
    series: [
      { name: '流体温度', type: 'line', showSymbol: false, lineStyle: { width: 2 }, data: r.depth.map((d, i) => [r.temp[i], d]) },
      { name: '地层温度', type: 'line', showSymbol: false, lineStyle: { width: 2, type: 'dashed' }, data: r.depth.map((d, i) => [r.tempFormation[i], d]) }
    ]
  })
}
const calculate = async () => {
  error.value = ''
  if (form.qLiq > 0 && !pvtReady.value) { error.value = '请先在PVT性质中完善当前井的地层水参数'; return }
  let candidate
  try { candidate = { ...form } }
  catch (e) { error.value = e.message; return }
  for (const group of groups.value) {
    for (const [key, label, min, max] of group.fields) {
      if (key === 'muL') continue
      if (typeof candidate[key] !== 'number' || !Number.isFinite(candidate[key]) || candidate[key] < min || candidate[key] > max) {
        const location = form.calculationPosition === 'bottomhole' ? '井底' : '井口'
        error.value = sourceFields.has(key) ? `${location}计算所需的${label.replace(/井口|井底/g, '')}源数据缺失或无效，请检查原平台` : `${label}请输入 ${min} 至 ${max} 之间的数值`
        return
      }
    }
  }
  const snapshot = JSON.stringify(candidate)
  if (busy.value) return
  busy.value = true
  try {
    const response = await calculateWellboreTemperature({ ...JSON.parse(snapshot), projectId: Number(props.projectId), gasReservoirId: Number(props.gasReservoirId), wellName: props.node.wellName })
    if (disposed) return
    result.value = response.data ?? response
    Object.assign(form, candidate, { rhoL: result.value.liquidDensity, muL: result.value.liquidViscosity })
    displayedPosition.value = candidate.calculationPosition
    lastInput.value = JSON.stringify(form)
    persist()
    await drawChart()
  } catch (e) {
    if (!disposed) error.value = e?.response?.data?.msg || e?.msg || e?.message || '温度计算失败'
  } finally { busy.value = false }
}
const reset = () => {
  productionDrafts.clear()
  for (const [key, value] of Object.entries(defaults)) if (!sourceFields.has(key) && key !== 'pvtId') form[key] = value
  displayedPosition.value = 'wellhead'
  if (sourceData.value) Object.assign(form, productionValues(sourceData.value.production, 'wellhead', sourceData.value.productionFields))
  result.value = null
  lastInput.value = ''
  error.value = ''
  persist()
  chart?.clear()
}
onMounted(() => {
  drawChart()
  refreshSources()
  resizeObserver = new ResizeObserver(() => chart?.resize())
  resizeObserver.observe(chartElement.value)
})
onBeforeUnmount(() => { stopResize(); disposed = true; resizeObserver?.disconnect(); chart?.dispose() })
</script>

<template>
  <section class="temperature-workspace">
    <div class="temperature-layout">
      <aside ref="parameterPanel" class="parameter-panel" :style="{ width: panelWidth + 'px', minWidth: panelWidth + 'px' }">
        <div class="panel-head">参数设置</div>
        <div class="panel-body">
        <label class="model-label" for="temperature-model">温度模型</label>
        <el-input id="temperature-model" model-value="Alves分段能量平衡" size="small" readonly aria-label="温度模型" />
        <template v-if="sourceData?.tubings.length > 1"><label class="model-label">油管段</label><el-select v-model="selectedTubing" size="small" :disabled="busy || sourceLoading" @change="selectTubing"><el-option v-for="row in sourceData.tubings" :key="row.key" :value="row.key" :label="row.label" /></el-select></template>
        <p v-for="message in sourceErrors" :key="message" class="source-error">{{ message }}</p>
        <section v-for="group in groups" :key="group.title" class="parameter-group">
          <h4>{{ group.title }}</h4>
          <div class="field-grid">
            <div v-for="[key, label, min, max] in group.fields" :key="key" class="field">
              <label :for="`temperature-${key}`">{{ label }}</label>
              <el-input-number :id="`temperature-${key}`" v-model="form[key]" size="small" :min="min" :max="max" :controls="false"
                :disabled="busy || sourceLoading || sourceFields.has(key)" :aria-label="label" />
            </div>
          </div>
        </section>

        <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
        <div class="position-row"><span>计算位置</span><el-radio-group v-model="form.calculationPosition" :disabled="busy || sourceLoading" aria-label="计算位置"><el-radio value="wellhead">井口</el-radio><el-radio value="bottomhole">井底</el-radio></el-radio-group></div>

        <div class="actions"><el-button size="small" :loading="busy" :disabled="sourceLoading" @click="calculate">计算温度分布</el-button><el-button size="small" :disabled="busy || sourceLoading" @click="reset">重置</el-button></div>
        </div>
        <div class="panel-resizer" role="separator" aria-label="调整参数面板宽度" aria-orientation="vertical" @pointerdown="startResize" />
      </aside>
      <main class="curve-panel" v-loading="busy">
        <div class="result-tabs"><span class="result-tab">{{ node.wellName }} · 温度模型</span></div>
        <el-alert v-if="dirty" title="参数已修改，请重新计算；当前曲线为上次计算结果。" type="warning" :closable="false" show-icon />
        <div class="result-summary" v-if="result">
          <span>计算位置：{{ result.calculationPosition === 'bottomhole' ? '井底' : '井口' }}</span>
          <span>{{ result.depth.length }} 个计算点</span>
          <span>井口温度 <b>{{ result.temp[0].toFixed(2) }} ℃</b></span>
          <span>井底流体温度 <b>{{ result.inferredBottomTemperature.toFixed(2) }} ℃</b></span>
          <span>井底地层温度 <b>{{ result.tempFormation.at(-1).toFixed(2) }} ℃</b></span>
        </div>
        <div class="chart-wrap"><div ref="chartElement" class="temperature-chart" aria-label="温度分布曲线，横轴温度，纵轴井深向下递增" /><div v-if="!result" class="empty-chart">设置左侧参数，点击“计算温度分布”查看曲线</div></div>
        <div v-if="result" class="model-notes"><p v-for="notice in result.notices" :key="notice">{{ notice }}</p></div>
      </main>
    </div>
  </section>
</template>

<style scoped>
.temperature-workspace { height: 100%; min-height: 0; background: #fff; overflow: hidden; color: #303133; }
.temperature-layout { display: flex; height: 100%; min-height: 0; }
.parameter-panel { position: relative; width: 238px; min-width: 238px; display: flex; flex-direction: column; border-right: 1px solid #e0e0e0; overflow: hidden; }
.panel-head { padding: 7px 12px 6px; border-bottom: 1px solid #f0f0f0; flex-shrink: 0; font-size: 13px; color: #333; }
.panel-body { flex: 1; min-height: 0; overflow-y: auto; padding: 4px 12px 14px; }
h4, .model-label { font-weight: 500; color: #333; font-size: 13px; margin: 10px 0 7px; }
.model-label { display: block; margin-top: 4px; }
.field-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(190px, 1fr)); column-gap: 24px; }
.field { min-width: 0; margin-bottom: 9px; }
.field label { display: block; margin-bottom: 3px; color: #555; font-size: 12px; }
.field :deep(.el-input-number), .parameter-panel :deep(.el-select) { width: 100%; }
.field :deep(.el-input__inner) { text-align: left; }
.actions { display: flex; gap: 8px; margin-top: 10px; }
.actions .el-button + .el-button { margin-left: 0; }
.panel-resizer { position: absolute; right: 0; top: 0; width: 6px; height: 100%; cursor: col-resize; z-index: 6; touch-action: none; }
.panel-resizer:hover { background: rgba(64,132,217,.18); }
.source-toolbar { margin-top: 8px; }
.source-hint { display: block; color: #888; font-size: 11px; margin-top: 2px; }
.source-error { color: #c45656; font-size: 12px; line-height: 1.5; margin-top: 5px; }
.position-row { margin-top: 8px; font-size: 13px; color: #333; }
.position-row :deep(.el-radio) { margin-right: 12px; height: 28px; }
.position-row :deep(.el-radio__label) { font-size: 13px; }
.position-row > span { margin-right: 12px; }
.parameter-note { color: #888; font-size: 13px; line-height: 1.5; padding: 4px 0 10px; margin: 0; }
.curve-panel { position: relative; flex: 1; min-width: 0; min-height: 0; display: flex; flex-direction: column; background: #fff; overflow: hidden; }
.result-tabs { height: 34px; flex-shrink: 0; display: flex; align-items: center; border-bottom: 1px solid #e4e7ed; background: #fafafa; }
.result-tab { height: 34px; max-width: 100%; display: block; line-height: 32px; padding: 0 12px; border-right: 1px solid #e4e7ed; border-bottom: 2px solid #409eff; background: #fff; color: #409eff; font-size: 14px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.result-summary { display: flex; flex-wrap: wrap; flex-shrink: 0; gap: 6px 20px; padding: 7px 12px; font-size: 12px; color: #555; border-bottom: 1px solid #f0f0f0; }
.result-summary b { color: #333; font-weight: 500; margin-left: 4px; }
.chart-wrap { position: relative; flex: 1; min-height: 220px; }
.temperature-chart { width: 100%; height: 100%; }
.empty-chart { position: absolute; inset: 0; display: flex; justify-content: center; align-items: center; color: #888; font-size: 13px; padding: 12px; text-align: center; }
.model-notes { flex-shrink: 0; border-top: 1px solid #e4e7ed; padding: 5px 12px; color: #888; font-size: 12px; line-height: 1.5; }
.model-notes p { margin: 0; }
.curve-panel > :deep(.el-alert) { flex-shrink: 0; border-radius: 0; }
@media (max-width: 650px) {
  .temperature-workspace { overflow: auto; }
  .temperature-layout { flex-direction: column; height: auto; }
  .parameter-panel { position: relative; width: 100%; min-width: 0; max-height: 380px; border-right: 0; border-bottom: 1px solid #e0e0e0; }
  .curve-panel { min-height: 540px; flex: none; }
  .chart-wrap { min-height: 380px; }
}
</style>
