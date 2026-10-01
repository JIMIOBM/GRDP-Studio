<script setup>
import { ref, reactive, computed, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as echarts from 'echarts'
import { nodalApi } from '@/api/nodal'
import { pvtStorageApi } from '@/api/pvtStorage'
import { wellborePressureApi } from '@/api/wellborePressure'
import { erosionApi } from '@/api/wellboreErosion'
import { nodalChartOption, nodalStatus, nodalEnvelope } from '@/utils/nodalChart'
import router from '@/router'
import { coefficientLocation } from '@/utils/productivityCoefficientTree'
import { nodalDefaults as defaults } from '@/utils/nodalDefaults'

const props = defineProps({ node: { type: Object, required: true }, projectId: { type: [String, Number], required: true }, gasReservoirId: { type: [String, Number], required: true } })
const emit = defineEmits(['saved', 'history-updated', 'restored'])
const scope = () => ({ projectId: Number(props.projectId), gasReservoirId: Number(props.gasReservoirId), wellName: props.node.wellName })
const recordId = ref(props.node.nodalId || null)
const form = reactive(defaults()), result = ref(null), acceptedInput = ref(null), name = ref('2026-9-1-节点分析'), error = ref(''), busy = ref(false)
const sources = ref([]), pvts = ref([]), pressureSources = ref([]), pressureId = ref(null), history = ref([])
const chartElement = ref(null), historyVisible = ref(false)
const activeContentTab = ref('chart')
const envelopeNote = computed(() => !result.value ? '' : !result.value.regionScenarios?.length
  ? '历史快照未包含范围校核数据，保留原结果；需要新版阴影时请手动重新计算。'
  : nodalEnvelope(result.value).length ? '网格阴影为所选约束下的合理能力范围（场景插值）；红点为最大合理能力。'
  : '当前未形成可确认的连续合理能力范围，请在数据列表查看区间与约束校核结果。')
watch(activeContentTab, async () => { await nextTick(); chart?.resize() })
const paramsCollapsed = ref(false), panelWidth = ref(286), panelElement = ref(null)
let stopResize = () => {}
function resizePanel(event) {
  if (event.button !== 0) return
  event.preventDefault()
  stopResize()
  const left = panelElement.value.getBoundingClientRect().left
  const maximum = Math.min(520, Math.max(238, panelElement.value.parentElement.clientWidth - 280))
  const move = e => { panelWidth.value = Math.max(238, Math.min(maximum, e.clientX - left)) }
  stopResize = () => {
    window.removeEventListener('pointermove', move)
    window.removeEventListener('pointerup', stopResize)
    window.removeEventListener('pointercancel', stopResize)
  }
  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', stopResize)
  window.addEventListener('pointercancel', stopResize)
}
let chart, observer, disposed = false, revision = 0, sourceSequence = 0, historySequence = 0, operationSequence = 0, applying = false
const injection = computed(() => form.operationMode === 'injection')
const selectedSource = computed(() => sources.value.find(s => s.id === form.coefficientId))
const curveWarnings = computed(() => {
  if (!result.value) return []
  const failed = result.value.wellboreCurve.filter(p => p.rate > 0 && p.pressure == null)
  const notices = []
  if (failed.length) notices.push(`井筒曲线有 ${failed.length} 个工况无法计算：${[...new Set(failed.map(p => p.reason).filter(Boolean))].join('；')}。断线区间不作能力判定。`)
  for (const s of result.value.scenarios) {
    const reasons = [...new Set(s.candidates.map(p => p.reason).filter(Boolean))]
    if (reasons.length) notices.push(`Pr=${s.reservoirPressure} MPa：${reasons.join('；')}`)
  }
  return notices
})
const sourcesLoading = ref(false), sourceError = ref('')
const erosionSourceNote = ref('')
const erosionFields = [['liquidHoldupPercent', '持液率 (%)'], ['sandContentPercent', '含砂率 (%)'], ['sandDensity', '砂粒密度 (kg/m³)'], ['erosionLiquidDensity', '冲蚀液相密度 (kg/m³)']]
const controlLabel = value => String(value || '未确定').replaceAll('liquidLoading', '携液下限').replaceAll('erosion', '冲蚀上限').replaceAll('hydrate', '水合物限制')
function scenarioReason(s) {
  if (s.maximum) {
    const atMax = (s.candidates || []).find(p => Math.abs(p.rate - s.maximum.rate) < 1e-6)
    const extrapolated = atMax?.checks?.some(c => c.code === 'erosion' && c.reason?.includes('超出标定范围'))
    return [s.status === 'PARTIAL_RESULT' ? '低气量局部缺口不影响已确认的最大值' : '已确认', extrapolated ? '冲蚀为标定范围外推结果' : ''].filter(Boolean).join('；')
  }
  if (!s.intervals.length && s.status === 'NO_FEASIBLE_RANGE') return '井筒边界与所选约束无共同可行区间'
  return (s.notes || []).filter(n => !n.startsWith('零气量')).join('；') || nodalStatus(s.status)
}
const coefficientEntry = computed(() => coefficientLocation({ ...scope(), method: '二项式' }))
const fields = [ ['boundaryPressure', '井口边界压力 (MPa)'], ['depth', '井深 (m)'], ['step', '井深步长 (m)'],
  ['idTubing', '油管内径 (mm)'], ['roughness', '粗糙度 (mm)'], ['angle', '井斜角 (°)'], ['tWh', '井口温度 (℃)'], ['tGrad', '温度梯度 (℃/100m)'], ['qLiq', '液量 (m³/d)'] ]
const message = e => e?.msg || e?.message || '节点分析请求失败'
const clone = x => JSON.parse(JSON.stringify(x))
const display = x => x == null ? '未确定' : Number(x).toFixed(4)
const modes = { production: null, injection: null }
watch(() => { const input = clone(form); delete input.constraints.sanding; return JSON.stringify(input) },
  () => { if (!applying) { revision++; result.value = null; acceptedInput.value = null } }, { flush: 'sync' })
watch(() => form.constraints.sanding, value => {
  if (!applying && acceptedInput.value) acceptedInput.value.constraints.sanding = value
}, { flush: 'sync' })
watch(() => form.operationMode, (mode, old) => {
  if (applying) return
  // Preserve complete independent drafts, including the previous mode's model and sources.
  modes[old] = { ...clone(form), operationMode: old }
  applying = true
  const next = modes[mode] || defaults(mode)
  if (mode === 'injection') { next.wellbore.models = ['GAS']; next.wellbore.qLiq = 0; next.constraints.liquidLoading = false }
  Object.assign(form, next); applying = false; pressureId.value = null
  loadSources()
}, { flush: 'sync' })
function chooseCoefficient() {
  const source = selectedSource.value
  if (!source) return
  fillCoefficients()
  // 临时测试使用多地层压力场景，选择系数时不覆盖用户已填的场景。
  if (!form.reservoirText.trim()) form.reservoirText = String(source.parameters.pressure)
  if (source.pvtId && pvts.value.some(p => p.pvtId === source.pvtId)) form.wellbore.pvtId = source.pvtId
}
function fillCoefficients() {
  const p = selectedSource.value?.parameters
  if (!p) return
  form.coefficientA = (form.coefficientSet === 'corrected' ? p.correctedA : p.a) ?? null
  form.coefficientB = (form.coefficientSet === 'corrected' ? p.correctedB : p.b) ?? null
}
async function loadErosion(context, current, applyDefaults) {
  const signature = JSON.stringify(erosionFields.map(([key]) => form.constraints[key]))
  try {
    const rows = await erosionApi.list(context)
    if (!current()) return
    if (!rows.length) { erosionSourceNote.value = '当前井暂无冲蚀记录，请填写参数'; return }
    if (!applyDefaults || erosionFields.some(([key]) => form.constraints[key] != null && form.constraints[key] !== '')) return
    const row = await erosionApi.detail(rows[0].id, context)
    if (!current() || result.value || signature !== JSON.stringify(erosionFields.map(([key]) => form.constraints[key]))) return
    const input = typeof row.input_json === 'string' ? JSON.parse(row.input_json) : row.input_json
    if (!input) throw new Error('记录缺少输入参数')
    Object.assign(form.constraints, { liquidHoldupPercent: input.liquidHoldupPercent ?? null,
      sandContentPercent: input.sandContentPercent ?? null, sandDensity: input.sandDensityKgM3 ?? null,
      erosionLiquidDensity: input.liquidDensityKgM3 ?? null })
    erosionSourceNote.value = `已读取冲蚀记录：${row.calculationName || rows[0].calculationName}`
  } catch (e) { if (current()) erosionSourceNote.value = `冲蚀记录读取失败：${message(e)}` }
}
async function loadSources({ applyDefaults = !result.value } = {}) {
  const seq = ++sourceSequence, context = scope(), mode = form.operationMode
  sources.value = []; pvts.value = []; pressureSources.value = []
  error.value = ''; sourceError.value = ''; sourcesLoading.value = true
  const current = () => !disposed && seq === sourceSequence
  const load = async (request, target, label, coefficient = false) => {
    try {
      const response = await request, rows = response?.data ?? response
      if (!Array.isArray(rows)) throw new Error(`${label}返回格式不正确`)
      if (current()) {
        target.value = rows
        if (applyDefaults && coefficient && !form.coefficientId && rows.length) {
          form.coefficientId = rows[0].id
          chooseCoefficient()
        }
        if (applyDefaults && target === pvts && !form.wellbore.pvtId && rows.length) {
          form.wellbore.pvtId = rows.find(p => p.pvtId === selectedSource.value?.pvtId)?.pvtId ?? rows[0].pvtId
        }
      }
    } catch (e) {
      if (current()) {
        const detail = `${label}加载失败：${message(e)}`
        if (coefficient) sourceError.value = detail
        else error.value = [error.value, detail].filter(Boolean).join('；')
      }
    } finally {
      if (coefficient && current()) sourcesLoading.value = false
    }
  }
  await Promise.all([
    loadErosion(context, current, applyDefaults),
    load(nodalApi.sources({ ...context, operationMode: mode }), sources, '二项式方案', true),
    load(pvtStorageApi.list(context.projectId, context.gasReservoirId, context.wellName), pvts, 'PVT方案'),
    load(wellborePressureApi.list(context.projectId, context.gasReservoirId, context.wellName).then(response => {
      const rows = response?.data ?? response
      if (!Array.isArray(rows)) throw new Error('压力方案返回格式不正确')
      return rows.filter(p => (p.operationMode || 'production') === mode)
    }), pressureSources, '压力方案')
  ])
}
async function importPressure() {
  if (!pressureId.value) return
  const rev = revision, seq = ++operationSequence, s = scope(); busy.value = true
  try {
    const raw = await wellborePressureApi.detail(pressureId.value, s.projectId, s.gasReservoirId, s.wellName)
    if (disposed || rev !== revision || seq !== operationSequence) return
    const d = raw?.data ?? raw, record = d.record
    const input = typeof record?.inputJson === 'string' ? JSON.parse(record.inputJson) : record?.inputJson
    if (!input || input.operationMode !== form.operationMode && !(form.operationMode === 'production' && !input.operationMode)) throw new Error('压力方案缺少兼容的输入快照')
    if (input.boundaryPosition !== 'wellhead') throw new Error('请选择井口边界的压力方案；井底边界方案不能直接用作井口能力边界')
    Object.keys(form.wellbore).forEach(key => { if (input[key] != null) form.wellbore[key] = clone(input[key]) })
    form.wellbore.models = [injection.value ? 'GAS' : (input.models?.[0] || 'HB')]
    form.pressureSourceId = pressureId.value
    ElMessage.success('已读取压力方案参数，请确认该井口压力是最低采气/最高注气边界')
  } catch (e) { if (!disposed && seq === operationSequence) error.value = message(e) }
  finally { if (seq === operationSequence) busy.value = false }
}
function payload() {
  const input = clone(form)
  input.reservoirPressures = input.reservoirText.split(/[,，;；\s]+/).filter(Boolean).map(Number)
  input.constraints.composition = input.constraints.hydrate ? JSON.parse(input.compositionText) : {}
  for (const key of ['coefficientA', 'coefficientB']) {
    if (input[key] == null || String(input[key]).trim() === '') throw new Error('请填写产能系数A和B')
    input[key] = Number(input[key])
    if (!Number.isFinite(input[key]) || input[key] < 0) throw new Error('产能系数必须为非负数')
  }
  for (const [key, label] of erosionFields) {
    const value = input.constraints[key]
    input.constraints[key] = value == null || String(value).trim() === '' ? null : Number(value)
    if (input.constraints.erosion && (input.constraints[key] == null || !Number.isFinite(input.constraints[key]))) throw new Error(`请填写${label}`)
  }
  delete input.reservoirText; delete input.compositionText
  return { ...input, ...scope() }
}
async function calculate() {
  error.value = ''; result.value = null; acceptedInput.value = null
  const rev = revision, seq = ++operationSequence; busy.value = true
  try {
    const input = payload()
    if (!input.coefficientId || !input.wellbore.pvtId) throw new Error('请选择二项式系数方案和PVT方案')
    if (fields.some(([key]) => input.wellbore[key] == null || !Number.isFinite(input.wellbore[key]))) throw new Error('请补齐井筒参数')
    const output = await nodalApi.calculate(input)
    if (disposed || rev !== revision || seq !== operationSequence) return
    input.constraints.sanding = form.constraints.sanding
    result.value = output; acceptedInput.value = input
  } catch (e) { if (!disposed && rev === revision && seq === operationSequence) error.value = message(e) }
  finally { if (seq === operationSequence) busy.value = false }
}
async function loadHistory() {
  const seq = ++historySequence
  try { const rows = await nodalApi.list(scope()); if (!disposed && seq === historySequence) { history.value = rows; emit('history-updated', { scope: scope(), records: rows }) } }
  catch (e) { if (!disposed && seq === historySequence) error.value = message(e) }
}
async function save() {
  if (busy.value) return
  if (!acceptedInput.value || !name.value.trim()) { error.value = '请先计算并填写方案名称'; return }
  const rev = revision, seq = ++operationSequence; busy.value = true
  const savedName = name.value.trim(), savedInput = clone(acceptedInput.value)
  try {
    const record = await nodalApi.save({ id: recordId.value, name: savedName, input: savedInput })
    if (disposed || seq !== operationSequence) return
    recordId.value = record.id
    if (rev === revision) result.value = record.result
    emit('saved', { scope: scope(), record: { id: record.id, name: record.name || savedName, operationMode: savedInput.operationMode } })
    ElMessage.success('方案已保存'); await loadHistory()
  } catch (e) { if (!disposed && seq === operationSequence) error.value = message(e) }
  finally { if (seq === operationSequence) busy.value = false }
}
async function restore(id) {
  const rev = revision, seq = ++operationSequence; busy.value = true
  try {
    const row = await nodalApi.detail(id, scope())
    if (disposed || seq !== operationSequence || rev !== revision) return
    recordId.value = row.id
    applying = true
    Object.assign(form, defaults(), row.input, { constraints: { ...defaults(row.input.operationMode).constraints, ...row.input.constraints }, reservoirText: row.input.reservoirPressures.join(', '), compositionText: JSON.stringify(row.input.constraints.composition || {}, null, 2) })
    const savedCoefficients = row.result?.coefficientSnapshot
    form.coefficientA ??= savedCoefficients?.effectiveA ?? (form.coefficientSet === 'corrected' ? savedCoefficients?.parameters?.correctedA : savedCoefficients?.parameters?.a) ?? null
    form.coefficientB ??= savedCoefficients?.effectiveB ?? (form.coefficientSet === 'corrected' ? savedCoefficients?.parameters?.correctedB : savedCoefficients?.parameters?.b) ?? null
    delete form.projectId; delete form.gasReservoirId; delete form.wellName; delete form.reservoirPressures
    applying = false; revision++; result.value = row.result; acceptedInput.value = row.result ? clone(row.input) : null; name.value = row.name
    historyVisible.value = false
    emit('restored', { scope: scope(), record: { id: row.id, name: row.name, operationMode: row.input.operationMode } })
    // 来源列表仅用于后续编辑，不覆盖快照，也不阻塞历史图形展示。
    void loadSources({ applyDefaults: false })
  } catch (e) { applying = false; if (!disposed && seq === operationSequence) error.value = message(e) }
  finally { if (seq === operationSequence) busy.value = false }
}
async function remove(row) {
  try { await ElMessageBox.confirm(`删除方案“${row.name}”？`, '删除方案'); await nodalApi.delete(row.id, scope()); await loadHistory() }
  catch (e) { if (e !== 'cancel' && e !== 'close') error.value = message(e) }
}
watch(result, async () => {
  await nextTick()
  if (disposed || !chartElement.value) return
  chart ||= echarts.init(chartElement.value)
  if (result.value) chart.setOption(nodalChartOption(result.value, form.operationMode), true); else chart.clear()
})
watch(() => [props.projectId, props.gasReservoirId, props.node.wellName], () => {
  revision++; operationSequence++; historySequence++; busy.value = false
  Object.assign(form, defaults()); result.value = null; acceptedInput.value = null; history.value = []
  recordId.value = props.node.nodalId || null
  modes.production = modes.injection = null; loadSources()
})
onMounted(() => { if (props.node.nodalId) restore(props.node.nodalId); else loadSources(); observer = new ResizeObserver(() => chart?.resize()); observer.observe(chartElement.value) })
onBeforeUnmount(() => { stopResize(); disposed = true; sourceSequence++; operationSequence++; historySequence++; observer?.disconnect(); chart?.dispose() })
</script>

<template>
  <section class="nodal-workspace">
    <aside ref="panelElement" class="params-panel water-parameter-theme" :class="{ collapsed: paramsCollapsed }"
      :style="{ width: paramsCollapsed ? '22px' : `${panelWidth}px`, minWidth: paramsCollapsed ? '22px' : `${panelWidth}px` }">
      <button v-if="paramsCollapsed" class="panel-collapsed-tab" type="button" title="展开参数设置" aria-label="展开参数设置" @click="paramsCollapsed = false">参数设置</button>
      <div v-show="!paramsCollapsed" class="panel-head">
        <span>参数设置</span>
        <button class="panel-toggle" type="button" title="收起参数设置" aria-label="收起参数设置" @click="paramsCollapsed = true">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="#777"><path d="M16,12V4H17V2H7V4H8V12L6,14V16H11.2V22H12.8V16H18V14L16,12Z"/></svg>
        </button>
      </div>
      <div v-show="!paramsCollapsed" class="panel-body">
    <div class="toolbar mode-selector">
      <el-radio-group v-model="form.operationMode"><el-radio-button value="production">采气</el-radio-button><el-radio-button value="injection">注气</el-radio-button></el-radio-group>
<!--      <el-button @click="historyVisible = true; loadHistory()">历史方案</el-button>-->
    </div>
    <el-alert v-if="error" :title="error" type="warning" :closable="false" show-icon />
    <section class="parameter-section"><div class="section-title">地层参数</div>
      <div class="parameter-grid">
        <label>系数方案<el-select v-model="form.coefficientId" :loading="sourcesLoading" :no-data-text="sourceError ? '方案加载失败，请重试' : '当前井暂无对应方向的已保存方案'" placeholder="选择当前方向的二项式方案" @change="chooseCoefficient"><el-option v-for="s in sources" :key="s.id" :value="s.id" :label="s.name" /></el-select></label>
        <div class="coefficient-source-help">
          <p v-if="sourceError" role="alert">{{ sourceError }}</p>
          <p v-else-if="!sourcesLoading && !sources.length">{{ node.wellName }} 暂无已保存的{{ injection ? '注气' : '采气' }}二项式系数方案。请在“单井产能 → 产能系数 → 二项式”选择对应注采方向，计算并保存方案，再返回刷新。产能试井结果不等同于这里的系数方案。</p>
          <el-button size="small" :loading="sourcesLoading" @click="loadSources">刷新系数方案</el-button>
<!--          <el-button size="small" @click="router.push(coefficientEntry)">前往二项式产能系数</el-button>-->
        </div>
        <label>系数取值<el-select v-model="form.coefficientSet" @change="fillCoefficients"><el-option label="修正系数 A′、B′" value="corrected" /><el-option label="原始系数 A、B" value="original" /></el-select></label>
        <label>产能系数 A<el-input v-model="form.coefficientA" inputmode="decimal" placeholder="支持科学计数法" /></label>
        <label>产能系数 B<el-input v-model="form.coefficientB" inputmode="decimal" placeholder="支持科学计数法" /></label>
        <label>地层压力 (MPa)<el-input v-model="form.reservoirText" placeholder="例如：30, 25, 20, 15" /></label>
        <label>{{ injection ? '最高井底流压' : '最低井底流压' }} (MPa)<el-input-number size="small" v-if="injection" v-model="form.maximumPressure" :controls="false" /><el-input-number size="small" v-else v-model="form.minimumPressure" :controls="false" /></label>
      </div>
<!--      <p v-if="selectedSource">压力口径：{{ selectedSource.pressureMethod }}；A={{ form.coefficientSet === 'corrected' ? selectedSource.parameters.correctedA : selectedSource.parameters.a }}，B={{ form.coefficientSet === 'corrected' ? selectedSource.parameters.correctedB : selectedSource.parameters.b }}</p>-->
    </section>
    <section class="parameter-section"><div class="section-title">井筒参数 · {{ injection ? '最高注气井口压力' : '最低采气井口压力' }}边界</div>
      <div class="toolbar source-selector"><el-select v-model="pressureId" aria-label="读取同井压力方案" placeholder="可选：读取同井压力方案" clearable><el-option v-for="p in pressureSources" :key="p.id" :value="p.id" :label="p.pressureName" /></el-select><el-button :disabled="busy || !pressureId" @click="importPressure">读取参数</el-button></div>
      <div class="parameter-grid">
        <label>PVT方案<el-select v-model="form.wellbore.pvtId"><el-option v-for="p in pvts" :key="p.pvtId" :value="p.pvtId" :label="p.pvtName" /></el-select></label>
        <label>压力折算方法<el-select v-model="form.wellbore.models[0]"><el-option v-if="injection" label="GAS 单相注气" value="GAS" /><template v-else><el-option label="HB" value="HB" /><el-option label="MB" value="MB" /></template></el-select></label>
        <label v-for="[key, label] in fields" :key="key">{{ label }}<el-input-number size="small" v-model="form.wellbore[key]" :controls="false" :disabled="injection && key === 'qLiq'" /></label>
      </div>
    </section>
    <section class="parameter-section"><div class="section-title">井筒约束条件</div>
      <div class="constraint-options">
        <el-checkbox v-model="form.constraints.liquidLoading" :disabled="injection">井筒积液{{ injection ? '（注气不适用）' : '' }}</el-checkbox>
<!--        <el-checkbox v-model="form.constraints.hydrate">水合物</el-checkbox>-->
        <el-checkbox v-model="form.constraints.erosion">冲蚀</el-checkbox>
<!--        <el-checkbox v-model="form.constraints.sanding">出砂（暂未接入计算）</el-checkbox>-->
      </div>
      <label v-if="form.constraints.liquidLoading">气液表面张力 (mN/m)<el-input-number size="small" v-model="form.constraints.surfaceTension" :min="0.01" /></label>
      <div v-if="form.constraints.erosion" class="parameter-grid">
        <p class="note">{{ erosionSourceNote || '冲蚀参数无历史记录，请手动填写' }}</p>
        <label v-for="[key, label] in erosionFields" :key="key">{{ label }}<el-input v-model="form.constraints[key]" inputmode="decimal" placeholder="请输入" /></label>
      </div>
      <div v-if="form.constraints.hydrate" class="parameter-grid"><label>完整气体组成（摩尔百分数JSON，合计100%）<el-input v-model="form.compositionText" type="textarea" :rows="3" placeholder='{"C1": 95, "C2": 3, "N2": 2}' /></label><label>要求温度裕量 (℃)<el-input-number size="small" v-model="form.constraints.hydrateMargin" :min="0" /></label><label>逸度缩放系数<el-input-number size="small" v-model="form.constraints.fugacityScale" :min="0.01" /></label></div>
    </section>
      </div>
      <div v-show="!paramsCollapsed" class="panel-actions">
        <el-input v-model="name" placeholder="方案名称" maxlength="100" size="small" />
        <div class="actions"><el-button class="calculate-button" size="small" :loading="busy" @click="calculate">计算</el-button><el-button size="small" :disabled="busy || !acceptedInput" @click="save">保存</el-button></div>
      </div>
      <div v-if="!paramsCollapsed" class="resizer" title="拖动调整参数栏宽度" @pointerdown="resizePanel" />
    </aside>
    <main class="result-panel" v-loading="busy">
      <div class="dynamic-result-tabs"><div class="dynamic-result-tab active" :title="`${node.wellName}-节点分析-${injection ? '注气' : '采气'}-分析结果`">{{ node.wellName }}-节点分析-{{ injection ? '注气' : '采气' }}-分析结果</div></div>
    <div v-show="activeContentTab === 'chart'" class="chart-title">{{ injection ? 'E.2 单井合理注气' : 'E.1 单井合理采气' }}能力确定图</div>
    <div v-show="activeContentTab === 'chart'" class="chart-container">
      <div ref="chartElement" class="nodal-chart" />
      <div v-if="!result" class="chart-empty">请选择左侧参数并计算节点分析</div>
    </div>
<!--    <p v-show="activeContentTab === 'chart'" class="chart-note">{{ envelopeNote }}</p>-->
    <div v-show="activeContentTab === 'table'" class="result-details">
      <p v-if="!result">暂无计算结果</p>
      <template v-if="result">
      <p class="note">气量：10⁴ m³/d；压力：MPa。出砂未参与计算。</p>
      <el-table :data="result.scenarios" border size="small">
        <el-table-column prop="reservoirPressure" label="地层压力 MPa" width="125" />
        <el-table-column label="可行气量区间"><template #default="{ row }">{{ row.intervals.map(i => `${display(i.from.rate)}～${display(i.to.rate)}`).join('；') || '无' }}</template>
        </el-table-column>
        <el-table-column label="最大可行气量"><template #default="{ row }">{{ display(row.maximum?.rate) }}</template></el-table-column>
        <el-table-column label="对应井底流压"><template #default="{ row }">{{ display(row.maximum?.pressure) }}</template></el-table-column>
        <el-table-column label="控制条件"><template #default="{ row }">{{ controlLabel(row.controllingCondition) }}</template></el-table-column>
        <el-table-column label="状态"><template #default="{ row }">{{ nodalStatus(row.status) }}</template></el-table-column>
        <el-table-column label="结果说明" min-width="220"><template #default="{ row }">{{ scenarioReason(row) }}</template></el-table-column>
      </el-table>
      </template>
    </div>
    <div class="bottom-chart-tabs"><button class="bottom-chart-tab" :class="{ active: activeContentTab === 'table' }" @click="activeContentTab = 'table'">数据列表</button><button class="bottom-chart-tab" :class="{ active: activeContentTab === 'chart' }" @click="activeContentTab = 'chart'">结果分析图</button></div>
    </main>
    <el-dialog v-model="historyVisible" title="节点分析历史方案" width="720px"><el-table :data="history"><el-table-column prop="name" label="名称" /><el-table-column label="工况"><template #default="{ row }">{{ row.operationMode === 'injection' ? '注气' : '采气' }}</template></el-table-column><el-table-column label="操作"><template #default="{ row }"><el-button :disabled="busy" @click="restore(row.id)">恢复</el-button><el-button type="danger" :disabled="busy" @click="remove(row)">删除</el-button></template></el-table-column></el-table></el-dialog>
  </section>
</template>

<style scoped>
.nodal-workspace { display: flex; height: 100%; min-height: 0; overflow: hidden; background: #fff; color: #333; font-family: Arial, sans-serif; font-size: 13px; }
.params-panel { position: relative; display: flex; flex-shrink: 0; flex-direction: column; min-height: 0; border-right: 1px solid #e0e0e0; transition: width .16s ease, min-width .16s ease; }
.panel-head { display: flex; align-items: center; justify-content: space-between; flex: 0 0 34px; box-sizing: border-box; padding: 7px 12px 6px; border-bottom: 1px solid #f0f0f0; }
.panel-body { flex: 1; min-height: 0; overflow: auto; padding: 4px 12px 14px; }
.panel-toggle { width: 20px; height: 20px; padding: 0; border: 0; background: transparent; display: flex; align-items: center; justify-content: center; cursor: pointer; }
.panel-collapsed-tab { width: 22px; height: 76px; padding: 0; writing-mode: vertical-rl; border: 1px solid #e0e0e0; border-left: 0; background: #fff; color: #333; font: inherit; cursor: pointer; }
.params-panel.collapsed { border-right: 0; }
.params-panel .mode-selector { justify-content: center; padding: 4px 0 4px; }
.mode-selector :deep(.el-radio-group) { display: flex; width: 100%; }
.mode-selector :deep(.el-radio-button) { flex: 1; }
.mode-selector :deep(.el-radio-button__inner) { width: 100%; padding: 8px 16px; font-size: 13px; font-weight: 600; }
.panel-toggle:hover, .panel-collapsed-tab:hover { background: #fff8d8; }
.parameter-section { margin: 0; padding: 0; border: 0; }
.params-panel .section-title {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 15px 0 9px;
  color: #1f2937;
  font-size: 15px;
  font-weight: 700;
  line-height: 22px;
  letter-spacing: .2px;
}
.params-panel .section-title::before {
  width: 4px;
  height: 15px;
  flex: 0 0 4px;
  border-radius: 2px;
  background: #d5b900;
  content: '';
}
.parameter-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(190px, 100%), 1fr)); column-gap: 24px; }
.coefficient-source-help { grid-column: 1 / -1; margin-bottom: 10px; font-size: 12px; line-height: 1.6; color: #666; }
.coefficient-source-help p { margin: 0 0 6px; }
.coefficient-source-help .el-button + .el-button { margin-left: 4px; }
.parameter-grid > label, .parameter-section > label { display: flex; flex-direction: column; gap: 3px; min-width: 0; margin-bottom: 9px; font-size: 13px; color: #555; line-height: 18px;}
.params-panel :deep(.el-select), .params-panel :deep(.el-input-number) { width: 100%; }
.params-panel :deep(.el-input__wrapper), .params-panel :deep(.el-select__wrapper) { box-sizing: border-box; min-height: 24px; height: 24px; padding: 0 6px; font-size: 12px; }
.params-panel :deep(.el-input__inner) { text-align: left; font-size: 12px; }
.toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin: 8px 0 12px; }
.params-panel :deep(.el-button) { font-size: 12px; padding: 5px 9px; }
.mode-selector :deep(.el-radio-button__original-radio:checked + .el-radio-button__inner) { background: #f4d000; border-color: #d5b900; color: #202020; box-shadow: -1px 0 0 #d5b900; }
.constraint-options { display: flex; flex-direction: column; align-items: flex-start; }
.constraint-options :deep(.el-checkbox) { height: 26px; margin: 0; max-width: 100%; }
.constraint-options :deep(.el-checkbox__label) { font-size: 12px; white-space: normal; }
p { margin: 8px 0; font-size: 12px; line-height: 1.5; color: #777; overflow-wrap: anywhere; }
.panel-actions { flex-shrink: 0; padding: 10px 12px; border-top: 1px solid #e0e0e0; background: #fff; }
.actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 8px; }
.actions :deep(.el-button) { margin-left: 0; color: #202020; background: #fff; border-color: #c9cdd3; }
.actions :deep(.calculate-button) { background: #f4d000; border-color: #d5b900; }
.resizer { position: absolute; z-index: 2; top: 34px; right: -3px; width: 6px; bottom: 0; cursor: col-resize; touch-action: none; }
.resizer:hover { background: rgba(64,132,217,.18); }
.result-panel { display: flex; flex: 1; min-width: 0; min-height: 0; flex-direction: column; overflow: hidden; }
.dynamic-result-tabs { display: flex; height: 34px; flex-shrink: 0; border-bottom: 1px solid #e4e7ed; background: #fafafa; }
.dynamic-result-tab { display: block; max-width: 100%; padding: 0 12px; background: #f4d000; color: #202020; font-size: 13px; font-weight: 600; line-height: 34px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.chart-title { flex-shrink: 0; text-align: center; font-size: 14px; font-weight: 600; padding: 12px 10px 4px; }
.chart-container { position: relative; flex: 1; min-height: 180px; overflow: hidden; }
.nodal-chart { position: absolute; inset: 0; width: 100%; height: 100%; }
.chart-empty { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; color: #999; font-size: 13px; pointer-events: none; }
.result-details { flex: 1; min-height: 0; overflow: auto; padding: 12px; }
.bottom-chart-tabs { display: flex; flex-shrink: 0; border-top: 1px solid #dfe3e8; }
.bottom-chart-tab { background: white; border: 0; border-right: 1px solid #e4e7ed; border-top: 3px solid transparent; padding: 8px 18px; cursor: pointer; }
.bottom-chart-tab.active { border-top-color: #ffd800; font-weight: 700; }
.result-details :deep(.el-table) { width: 100%; }
.chart-note { margin: 4px 12px 8px; font-size: 12px; color: #687480; }
.note { margin: 4px 0; }
@media (max-height: 600px) { .chart-container { min-height: 120px; } }
</style>
