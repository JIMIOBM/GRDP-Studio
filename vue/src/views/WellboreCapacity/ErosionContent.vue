<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { erosionApi } from '@/api/wellboreErosion'
import { loadTemperatureSources } from '@/api/temperatureSources'
import { productionValues } from '@/utils/temperatureSources'
import { getWellboreBoundaryState } from '@/utils/wellboreBoundaryState'
import { createErosionResultState, emptyErosionInput, erosionFields, validateErosionInput, erosionRangeHint, displayErosionValue, erosionStatus } from '@/utils/erosionState'

const props = defineProps({ node: { type: Object, required: true }, projectId: { type: [String, Number], required: true }, gasReservoirId: { type: [String, Number], required: true } })
const context = () => ({ projectId: Number(props.projectId), gasReservoirId: Number(props.gasReservoirId), wellName: props.node.wellName })
const form = reactive(emptyErosionInput())
const state = reactive(createErosionResultState())
const history = ref([]), pvtRecords = ref([]), historyVisible = ref(false)
const busy = ref(false), sourceBusy = ref(false), pvtBusy = ref(false), error = ref(''), sourceNote = ref('')
const rangeHint = computed(() => erosionRangeHint(form))
const basicKeys = ['pressureMpa', 'temperatureC', 'actualGasRate1e4M3d', 'tubingInnerDiameterMm']
const propertyKeys = ['gasDensityKgM3', 'liquidDensityKgM3', 'gasVolumeFactor']
const modelKeys = ['liquidHoldupPercent', 'sandContentPercent', 'sandDensityKgM3']
const metrics = [
  ['actualVelocity', '当前气相表观流速（m/s）'], ['criticalVelocity', '公式临界冲蚀流速（m/s）'],
  ['velocityRatio', '流速利用率（实际/临界）'], ['criticalGasRate', '临界日产气量（万m³/d）'],
  ['criticalErosionCoefficient', '临界冲蚀系数 C'], ['mixtureDensity', '三相混合密度（kg/m³）'],
  ['sandFactor', '含砂修正系数 k'], ['liquidHoldupFactor', '持液修正系数 f']
]
let disposed = false, sourceSequence = 0, pvtSequence = 0, historySequence = 0, recordSequence = 0
let applying = false
watch(form, () => { if (!applying) state.invalidate() }, { deep: true, flush: 'sync' })
watch(() => [form.pressureMpa, form.temperatureC, form.pvtId], () => {
  if (applying) return
  ++pvtSequence
  pvtBusy.value = false
  form.pvtSnapshot = null
  for (const key of propertyKeys) form[key] = null
}, { flush: 'sync' })
function applyInput(input) {
  applying = true
  try { for (const key of Object.keys(emptyErosionInput())) form[key] = input[key] ?? null }
  finally { applying = false }
}
const payload = () => ({ ...context(), ...JSON.parse(JSON.stringify(form)) })
const messageOf = e => e?.msg || e?.message || '请求失败，请检查服务与数据来源'
const parse = value => typeof value === 'string' ? JSON.parse(value) : value

async function refreshProperties() {
  if (form.pvtId == null || form.pressureMpa == null || form.pressureMpa === '' || form.temperatureC == null || form.temperatureC === '') {
    error.value = '请选择PVT方案并填写当前压力、温度'; return false
  }
  const sequence = ++pvtSequence, scope = JSON.stringify(context())
  const input = { ...context(), pvtId: form.pvtId, pressureMpa: form.pressureMpa, temperatureC: form.temperatureC }
  state.invalidate()
  for (const key of propertyKeys) form[key] = null
  form.pvtSnapshot = null
  pvtBusy.value = true; error.value = ''
  try {
    const p = await erosionApi.properties(input)
    if (disposed || sequence !== pvtSequence || scope !== JSON.stringify(context())) return false
    Object.assign(form, p)
    return true
  } catch (e) { if (!disposed && sequence === pvtSequence) error.value = messageOf(e); return false }
  finally { if (sequence === pvtSequence) pvtBusy.value = false }
}
async function loadSources() {
  const sequence = ++sourceSequence, revision = state.revision
  sourceBusy.value = true; error.value = ''
  try {
    const source = await loadTemperatureSources(...Object.values(context()))
    if (disposed || sequence !== sourceSequence || revision !== state.revision) return
    pvtRecords.value = source.pvtRecords
    const boundary = getWellboreBoundaryState(context()).values
    const production = productionValues(source.production, 'wellhead', source.productionFields, 'tubing')
    // Read a coherent P/T pair from existing shared boundary, otherwise the same tubing production row.
    const shared = boundary.pressure != null && boundary.temperature != null
    form.pressureMpa = shared ? boundary.pressure : production.fWh
    form.temperatureC = shared ? boundary.temperature : production.tWh
    form.actualGasRate1e4M3d = shared && boundary.qGas != null ? boundary.qGas : production.qGas
    form.tubingInnerDiameterMm = source.input.idTubing ?? null
    form.pvtId = source.pvtRecords.find(row => Number(row.pvtId) === Number(boundary.pvtId))?.pvtId ?? source.pvtRecords[0]?.pvtId ?? null
    sourceNote.value = `${shared ? '温压来自当前井共享边界条件' : '温压来自最新注采记录的油管通道'}；管径来自完井油管。请确认所有参数对应同一评价位置。${source.errors.join('；')}`
    if (form.pvtId && form.pressureMpa != null && form.temperatureC != null) await refreshProperties()
  } catch (e) { if (!disposed && sequence === sourceSequence) error.value = messageOf(e) }
  finally { if (sequence === sourceSequence) sourceBusy.value = false }
}
async function calculate() {
  error.value = ''; state.invalidate()
  const inputIssue = validateErosionInput(form, false)
  if (inputIssue) { error.value = inputIssue; return }
  if (!await refreshProperties()) return
  const issue = validateErosionInput(form)
  if (issue) { error.value = issue; return }
  const revision = state.revision, input = payload()
  busy.value = true
  try {
    const response = await erosionApi.calculate(input)
    if (disposed || revision !== state.revision) return
    applyInput(response.input)
    state.accept(revision, response.input, response.result)
  } catch (e) { if (!disposed && revision === state.revision) error.value = messageOf(e) }
  finally { busy.value = false }
}
function resetResult() {
  ++pvtSequence; ++recordSequence; ++sourceSequence
  pvtBusy.value = sourceBusy.value = false
  state.invalidate()
}
async function loadHistory() {
  const sequence = ++historySequence, scope = context()
  try {
    const rows = await erosionApi.list(scope)
    if (!disposed && sequence === historySequence) history.value = rows || []
  } catch (e) { if (!disposed && sequence === historySequence) error.value = `历史记录读取失败：${messageOf(e)}` }
}
async function save() {
  if (!state.calculatedInput) return
  const revision = state.revision, input = JSON.parse(JSON.stringify(state.calculatedInput))
  try {
    const { value } = await ElMessageBox.prompt('请输入方案名称', '保存冲蚀计算', { inputValue: `冲蚀方案${history.value.length + 1}`, inputValidator: value => value?.trim().length > 0 && value.trim().length <= 100 || '名称须为1～100字' })
    if (disposed || revision !== state.revision) return
    busy.value = true
    await erosionApi.save({ calculationName: value, calculation: input })
    if (!disposed) { await loadHistory(); ElMessage.success('冲蚀方案已保存，结果由后端重新计算') }
  } catch (e) { if (e !== 'cancel' && e !== 'close' && !disposed) error.value = messageOf(e) }
  finally { busy.value = false }
}
async function openRecord(row) {
  const sequence = ++recordSequence, revision = state.revision
  try {
    const detail = await erosionApi.detail(row.id, context())
    if (disposed || sequence !== recordSequence || revision !== state.revision) return
    ++pvtSequence; ++sourceSequence; pvtBusy.value = sourceBusy.value = false
    const input = parse(detail.input_json), result = parse(detail.result_json)
    state.invalidate(); applyInput(input)
    if (!pvtRecords.value.some(p => Number(p.pvtId) === Number(input.pvtId))) {
      pvtRecords.value.push({ pvtId: input.pvtId, pvtName: `${input.pvtSnapshot?.pvtName || 'PVT'}（历史快照，重算时校验来源）` })
    }
    state.result = result
    // Historic output remains viewable, but must be recalculated against current PVT before saving anew.
    sourceNote.value = '已恢复历史输入及PVT物性快照；再次保存前须重新计算。'
    historyVisible.value = false; error.value = ''
  } catch (e) { if (!disposed && sequence === recordSequence) error.value = messageOf(e) }
}
async function removeRecord(row) {
  const scope = context()
  try {
    await ElMessageBox.confirm(`确认删除“${row.calculationName}”？`, '删除冲蚀方案', { type: 'warning' })
    if (disposed || JSON.stringify(scope) !== JSON.stringify(context())) return
    await erosionApi.delete(row.id, scope)
    if (!disposed) await loadHistory()
  } catch (e) { if (e !== 'cancel' && e !== 'close' && !disposed) error.value = messageOf(e) }
}
function resetContext() {
  ++pvtSequence; ++sourceSequence; ++historySequence; ++recordSequence
  state.invalidate(); applyInput(emptyErosionInput()); history.value = []; pvtRecords.value = []
  historyVisible.value = false; sourceNote.value = ''; error.value = ''
  loadSources(); loadHistory()
}
watch(() => [props.projectId, props.gasReservoirId, props.node.wellName], resetContext)
onMounted(resetContext)
onBeforeUnmount(() => { disposed = true; state.invalidate(); ++sourceSequence; ++pvtSequence })
</script>

<template>
  <section class="risk-workspace" v-loading="busy || sourceBusy">
    <header class="result-tabs">
      <div class="result-tab">{{ node.wellName }} · 冲蚀计算结果</div>
      <div class="header-actions">
        <button type="button" :disabled="pvtBusy" @click="loadSources">读取最新井数据</button>
        <button type="button" @click="loadHistory(); historyVisible = true">历史记录（{{ history.length }}）</button>
        <button class="primary" type="button" :disabled="!state.calculatedInput || busy || pvtBusy" @click="save">保存</button>
      </div>
    </header>
    <div class="form-canvas">
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
      <div class="form-title">基础工况 · {{ node.wellName }}</div>
      <p v-if="sourceNote" class="hint">{{ sourceNote }}</p>
      <div class="parameter-grid">
        <label v-for="key in basicKeys" :key="key" class="field"><span>{{ erosionFields[key] }}</span>
          <input v-model.number="form[key]" type="number" step="any" placeholder="请输入" :disabled="sourceBusy" />
        </label>
      </div>
      <h3 class="subheading">物性</h3>
      <div class="parameter-grid">
        <label class="field"><span>同井PVT方案</span>
          <el-select v-model="form.pvtId" placeholder="请选择PVT性质" :disabled="sourceBusy" @change="refreshProperties">
            <el-option v-for="record in pvtRecords" :key="record.pvtId" :value="record.pvtId" :label="record.pvtName || `PVT性质${record.pvtNo}`" />
          </el-select>
        </label>
        <label v-for="key in propertyKeys" :key="key" class="field"><span>{{ erosionFields[key] }}</span>
          <input :value="form[key] == null ? '' : displayErosionValue(form[key], 8)" readonly :placeholder="pvtBusy ? 'PVT计算中…' : '读取当前温压下物性'" />
        </label>
      </div>
      <div class="calculation-actions"><button type="button" :disabled="pvtBusy" @click="refreshProperties">{{ pvtBusy ? '物性读取中…' : '读取PVT物性' }}</button></div>
      <h3 class="subheading">冲蚀模型参数</h3>
      <div class="parameter-grid">
        <label v-for="key in modelKeys" :key="key" class="field">
          <span v-if="key === 'liquidHoldupPercent'" class="parameter-label">持液率 H<sub>L</sub>（%）</span>
          <span v-else-if="key === 'sandContentPercent'" class="parameter-label">含砂率 H<sub>s</sub>（%）</span>
          <span v-else>{{ erosionFields[key] }}</span>
          <input v-model.number="form[key]" type="number" step="any" placeholder="请输入实测参数" />
        </label>
      </div>
      <p class="hint">持液率0.001%～0.01%，含砂率0.0001%～0.02%，气流速17～50 m/s。输入0.01表示0.01%。</p>
      <el-alert v-if="rangeHint" :title="rangeHint" type="warning" :closable="false" show-icon />
      <div class="calculation-actions">
        <button class="calculate" type="button" :disabled="busy || pvtBusy || sourceBusy" @click="calculate">{{ busy ? '计算中…' : '计 算' }}</button>
        <button type="button" @click="resetResult">重 置</button>
      </div>
      <section class="result-card" aria-live="polite">
        <h3>计算结果</h3>
        <p v-if="!state.result" class="empty-result">填写参数后计算；修改任何输入会清空旧结果。</p>
        <template v-else>
          <el-alert :title="erosionStatus(state.result.status)" :type="['NOT_APPLICABLE', 'CALCULATION_ERROR'].includes(state.result.status) ? 'error' : 'warning'" :closable="false" />
          <div class="result-grid">
            <article v-for="[key, label] in metrics" :key="key" class="result-metric">
              <div class="result-label">{{ label }}</div><div class="result-value">{{ displayErosionValue(state.result[key], key === 'velocityRatio' ? 4 : 6) }}</div>
            </article>
          </div>
        </template>
      </section>
    </div>
    <el-drawer v-model="historyVisible" title="冲蚀历史记录" size="620px">
      <el-table :data="history" size="small" border empty-text="暂无保存记录">
        <el-table-column prop="calculationNo" label="编号" width="70" />
        <el-table-column prop="calculationName" label="方案名称" />
        <el-table-column label="判断"><template #default="scope">{{ erosionStatus(scope.row.status) }}</template></el-table-column>
        <el-table-column label="操作" width="130"><template #default="scope">
          <el-button link @click="openRecord(scope.row)">查看</el-button><el-button link type="danger" @click="removeRecord(scope.row)">删除</el-button>
        </template></el-table-column>
      </el-table>
    </el-drawer>
  </section>
</template>

<style scoped>
/* Reuses the liquid-loading panel's dimensions/colors, scoped to this new page. */
.risk-workspace { width: 100%; height: 100%; min-width: 0; overflow: auto; background: #fff; color: #202020; font: 13px Arial, sans-serif; }
.result-tabs { position: sticky; top: 0; z-index: 2; display: flex; align-items: center; justify-content: space-between; min-height: 34px; padding-right: 8px; border-bottom: 1px solid #e4e7ed; background: #fafafa; box-sizing: border-box; gap: 12px; }
.result-tab { display: flex; align-self: stretch; align-items: center; justify-content: center; min-width: 190px; padding: 0 12px; min-height: 34px; border-right: 1px solid #e4e7ed; background: #f4d000; font-weight: 600; box-sizing: border-box; }
.header-actions, .calculation-actions { display: flex; gap: 10px; flex-wrap: wrap; }
button { height: 27px; padding: 0 16px; border: 1px solid #c9cdd3; border-radius: 4px; background: #fff; color: #292929; font: inherit; cursor: pointer; }
button:hover { border-color: #b49a00; }
button:disabled { cursor: not-allowed; opacity: .45; }
button.primary { min-width: 74px; border-color: #202020; background: #202020; color: #fff; }
.form-canvas { padding: 20px 18px 34px; }
.form-title { margin: 16px 0 20px; font-weight: 600; }
.parameter-grid { display: grid; grid-template-columns: repeat(4, minmax(170px, 1fr)); gap: 20px 24px; }
.field { display: grid; gap: 8px; min-width: 0; color: #333; }
.parameter-label sub { position: relative; top: .18em; font-size: .9em; line-height: 0; font-weight: inherit; }
.field input { width: 100%; height: 34px; padding: 0 11px; border: 1px solid #d4d7dc; border-radius: 4px; background: #fff; box-sizing: border-box; color: #303133; font: inherit; outline: none; }
.field input:focus { border-color: #b49a00; box-shadow: 0 0 0 2px rgba(244,208,0,.14); }
.field input[readonly] { background: #f5f6f7; color: #606266; }
.field input[type="number"] { appearance: textfield; }
.field input::-webkit-inner-spin-button, .field input::-webkit-outer-spin-button { appearance: none; margin: 0; }
.field input::placeholder { color: #a8abb2; }
.field :deep(.el-select__wrapper) { min-height: 34px; border-radius: 4px; box-shadow: 0 0 0 1px #d4d7dc inset; }
.calculation-actions { margin-top: 24px; }
.calculation-actions button { height: 32px; min-width: 72px; }
.calculate { border-color: #d5b900; background: #f4d000; }
.subheading { margin: 26px 0 16px; padding-top: 18px; border-top: 1px solid #eceef1; font-size: 13px; font-weight: 600; }
.hint { margin: 16px 0; color: #777; font-size: 12px; line-height: 1.6; }
.result-card { min-height: 142px; margin-top: 36px; padding: 18px 18px 26px; border: 1px solid #ececec; border-radius: 4px; background: #f5f5f5; box-sizing: border-box; }
.result-card h3 { margin: 0 0 24px; font-size: 13px; }
.result-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 24px; }
.result-value { margin-top: 12px; font-size: 15px; font-weight: 500; }
.empty-result { color: #909399; }
@media (max-width: 1250px) { .parameter-grid { grid-template-columns: repeat(3, minmax(170px, 1fr)); } }
@media (max-width: 920px) { .parameter-grid { grid-template-columns: repeat(2, minmax(170px, 1fr)); } .result-tabs { flex-wrap: wrap; } .header-actions { padding: 4px 8px; } }
@media (max-width: 460px) { .parameter-grid, .result-grid { grid-template-columns: 1fr; } }
</style>
