<script>
// Keep unfinished single-point work when switching temperature pages in this session.
const gasPropertyDrafts = new Map()
</script>

<script setup>
import { computed, h, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { pipelineCapacityApi as api } from '@/api/pipelineCapacity'
import { gasPropertyContextKey, inspectGasComposition, gasPropertyCompositionIssue, gasPropertyInputIssues, gasPropertyInputs, gasPropertyResultStamp, gasPropertyResultUnsaved, gasPropertyInitialPanel, gasPropertyCheckpointLabel, reconcileGasPropertyPoints, createGasPropertyRequestScope } from '@/utils/pipelineGasPropertyState'
import PipelineTemperatureImportDialog from './PipelineTemperatureImportDialog.vue'
import PipelineGasParameterTables from './PipelineGasParameterTables.vue'

const props = defineProps({ context: { type: Object, required: true }, kind: { type: String, default: 'z' }, commandKey: { type: [Number, String], default: 0 } })
const emit = defineEmits(['saved'])
const ready = ref(false), busy = ref(false), sourceBusy = ref(false), error = ref(''), sourceError = ref(''), notice = ref('')
const pvtModel = ref(null), components = ref([]), composition = ref(null)
const parameterSnapshot = ref(null), parameterBusy = ref(false), parameterError = ref('')
const revision = ref(0), pvtId = ref(null), method = ref('PR'), panel = ref('input')
const paramsCollapsed = ref(false), importVisible = ref(false), importedFileNames = reactive({ z: '', cp: '' })
const savedMark = ref(''), points = reactive({ z: emptyPoint(), cp: emptyPoint() })
const savedResultMarks = reactive({ z: '', cp: '' }), requests = createGasPropertyRequestScope()
let activeContextKey = ''
let parameterTimer = null, parameterGeneration = 0
const title = computed(() => props.kind === 'cp' ? '定压比热容' : '压缩因子')
const point = computed(() => points[props.kind])
const pvtName = computed(() => pvtModel.value?.pvtName || '尚未保存 PVT 模型')
const compositionRows = computed(() => (composition.value?.composition || []).map(row => ({ ...row, name: components.value.find(item => item.code === row.code)?.name || row.code })))
const compositionCheck = computed(() => inspectGasComposition(compositionRows.value, components.value))
const fractionSum = computed(() => compositionCheck.value.sum)
const compositionIssue = computed(() => {
  if (!pvtId.value) return '请先在管束能力的 PVT 模型页填写并保存完整气体组成。'
  if (sourceBusy.value) return '正在读取当前井 PVT 模型的气体组成…'
  if (sourceError.value) return sourceError.value
  if (composition.value?.issue) return composition.value.issue
  return gasPropertyCompositionIssue(compositionRows.value, components.value, pvtName.value)
})
const dirty = computed(() => ready.value && (savedMark.value !== JSON.stringify(editable()) || ['z', 'cp'].some(kind => gasPropertyResultUnsaved(points[kind], savedResultMarks[kind]))))
const pageDirty = computed(() => {
  if (!ready.value || !savedMark.value) return false
  const saved = JSON.parse(savedMark.value)
  return saved.pvtId !== pvtId.value || saved.method !== method.value || JSON.stringify(saved.points[props.kind]) !== JSON.stringify(editable().points[props.kind]) || gasPropertyResultUnsaved(point.value, savedResultMarks[props.kind])
})
const stale = computed(() => !!point.value.result && (sourceBusy.value || !!compositionIssue.value || point.value.resultMark !== inputStamp(props.kind)))
const actionBusy = computed(() => !ready.value || busy.value || sourceBusy.value)
const result = computed(() => point.value.result)
const displayedParameters = computed(() => result.value?.parameterSnapshot && !stale.value ? result.value.parameterSnapshot : parameterSnapshot.value)
const parameterKey = computed(() => JSON.stringify({ context: gasPropertyContextKey(props.context), pvtId: pvtId.value,
  method: method.value, pressureMpa: point.value.pressureMpa, temperatureC: point.value.temperatureC, compositionRevision: composition.value?.revision }))
const resultColumns = computed(() => [
  { key: props.kind === 'cp' ? 'cpJkgK' : 'z', label: props.kind === 'cp' ? '定压比热容 Cp' : '压缩因子 Z', unit: props.kind === 'cp' ? 'J/(kg·K)' : '无量纲', digits: props.kind === 'cp' ? 3 : 6 },
  ...(props.kind === 'cp' ? [{ key: 'z', label: '同工况压缩因子 Z', unit: '无量纲', digits: 6 }] : []),
  { key: 'densityKgM3', label: '气体密度', unit: 'kg/m³', digits: 6 },
  { key: 'molarMassKgMol', label: '混合气摩尔质量', unit: 'kg/mol', digits: 8 }
])

function emptyPoint() { return { pressureMpa: null, temperatureC: null, result: null, resultMark: '', resultMethod: '', resultPvtName: '', resultPressure: null, resultTemperature: null, resultCompositionRevision: 0 } }
function f(value, digits = 3) { return value == null || !Number.isFinite(Number(value)) ? '—' : Number(value).toFixed(digits) }
function errorText(e) { return e?.response?.data?.msg || e?.msg || e?.message || '操作失败' }
function editable() { return gasPropertyInputs({ pvtId: pvtId.value, method: method.value, points }) }
function inputStamp(kind, sourceRevision = composition.value?.revision) {
  return gasPropertyResultStamp(editable(), kind, sourceRevision)
}
function acceptResult(kind, value, sourceRevision) {
  Object.assign(points[kind], { result: value, resultMark: value ? inputStamp(kind, sourceRevision) : '', resultMethod: method.value, resultPvtName: pvtName.value,
    resultPressure: points[kind].pressureMpa, resultTemperature: points[kind].temperatureC, resultCompositionRevision: sourceRevision || 0 })
}
function acceptSaved(data) {
  revision.value = data?.revision || 0; pvtId.value = data?.pvtId == null ? null : Number(data.pvtId); method.value = data?.method || 'PR'
  for (const kind of ['z', 'cp']) {
    const entry = data?.points?.[kind]
    Object.assign(points[kind], emptyPoint(), { pressureMpa: entry?.pressureMpa ?? null, temperatureC: entry?.temperatureC ?? null })
    acceptResult(kind, entry?.result ? { ...entry.result, ...(entry.parameterSnapshot ? { parameterSnapshot: entry.parameterSnapshot } : {}) } : null, data?.compositionRevision)
    if(entry?.result)points[kind].resultPvtName=gasPropertyCheckpointLabel(data,pvtModel.value)
    savedResultMarks[kind] = points[kind].resultMark
  }
  savedMark.value = JSON.stringify(editable())
}
async function load(manual = false) {
  const requestedContext = gasPropertyContextKey(props.context), beforeConfirmation = requests.capture()
  if (manual && dirty.value) {
    try { await ElMessageBox.confirm('重新加载会覆盖这两个物性节点未保存的参数。', '重新加载物性模型', { confirmButtonText: '重新加载', cancelButtonText: '返回编辑' }) } catch { return }
    if (!requests.current(beforeConfirmation) || requestedContext !== gasPropertyContextKey(props.context)) return
  }
  const context = { ...props.context }, token = requests.open(context), key = gasPropertyContextKey(context)
  activeContextKey = key; busy.value = true; error.value = ''; sourceError.value = ''; notice.value = ''; ready.value = false
  sourceBusy.value = false; composition.value = null; importVisible.value = false; panel.value = 'input'; importedFileNames.z = ''; importedFileNames.cp = ''
  pvtModel.value = null; components.value = []; acceptSaved(null)
  try {
    const responses = await Promise.allSettled([
      api.gasProperties(context), api.pvtModel(context), api.gasPropertyComponents()
    ])
    if (!requests.current(token)) return
    for (const response of responses) if (response.status === 'rejected') throw response.reason
    const [config, model, catalog] = responses.map(response => response.value.data)
    components.value = catalog || [];pvtModel.value=model||null
    acceptSaved(config)
    if (config?.compositionChanged) notice.value = '当前 PVT 模型已更新，已保存的检查点结果需要重新计算；后续计算直接使用最新 PVT 模型。'
    const cached = !manual && gasPropertyDrafts.get(key)
    if (cached && (cached.revision === revision.value || cached.dirty)) {
      const conflict = cached.revision !== revision.value
      revision.value = cached.revision; savedMark.value = cached.savedMark
      Object.assign(points.z, cached.points.z); Object.assign(points.cp, cached.points.cp)
      Object.assign(savedResultMarks, cached.savedResultMarks)
      if (cached.dirty) notice.value = conflict ? '已保留本次会话的物性草稿，但服务器配置已更新。请重新加载并核对后保存。' : '已恢复本次会话未保存的物性参数与计算结果。'
    }
    acceptPvtModel(model)
    if (!requests.current(token)) return
    ready.value = true
    panel.value = gasPropertyInitialPanel(point.value)
  } catch (e) { if (requests.current(token)) error.value = errorText(e) }
  finally { if (requests.current(token)) busy.value = false }
}
function acceptPvtModel(model) {
  pvtModel.value=model||null;pvtId.value=model?.pvtId??null;method.value=model?.method||'PR'
  composition.value=model?{...model,revision:model.compositionRevision}:null
  if(savedMark.value){const saved=JSON.parse(savedMark.value);saved.pvtId=pvtId.value;saved.method=method.value;savedMark.value=JSON.stringify(saved)}
}
async function refreshComposition() {
  if (!ready.value || sourceBusy.value) return
  const token = requests.source()
  sourceBusy.value = true; sourceError.value = ''
  try {
    const { data } = await api.pvtModel(props.context)
    if (requests.currentSource(token)) acceptPvtModel(data)
  } catch (e) {
    if (requests.currentSource(token)) sourceError.value = `气体组成读取失败：${errorText(e)}`
  } finally { if (requests.currentSource(token)) sourceBusy.value = false }
}
function refreshOnFocus() {
  if (!busy.value) refreshComposition()
}
function queueParameters() {
  clearTimeout(parameterTimer)
  const generation = ++parameterGeneration
  parameterSnapshot.value = null; parameterError.value = ''; parameterBusy.value = false
  if (!ready.value || !pvtId.value || sourceBusy.value) return
  const key = parameterKey.value, token = requests.capture()
  const input = { ...props.context, pvtId: pvtId.value, method: method.value, pressureMpa: point.value.pressureMpa, temperatureC: point.value.temperatureC }
  parameterBusy.value = true
  parameterTimer = setTimeout(async () => {
    const current = () => generation === parameterGeneration && requests.current(token) && key === parameterKey.value
    try {
      const { data } = await api.gasPropertyParameters(input)
      if (!current()) return
      if (data.compositionRevision !== composition.value?.revision) { await refreshComposition(); return }
      parameterSnapshot.value = data.parameters
    } catch (e) { if (current()) parameterError.value = '计算参数读取失败：' + errorText(e) }
    finally { if (current()) parameterBusy.value = false }
  }, 200)
}
function editCondition(key, event) { point.value[key] = event.target.value === '' ? null : Number(event.target.value) }
function acceptImport({ point: imported, fileName, kind }) {
  if (kind !== props.kind || !ready.value) return
  Object.assign(points[kind], { pressureMpa: imported.pressureMpa, temperatureC: imported.temperatureC })
  importedFileNames[kind] = fileName; panel.value = 'input'; error.value = ''
  ElMessage.success('温压数据已导入，可计算并保存')
}
async function calculate(save = false) {
  if (actionBusy.value) return
  busy.value = true; error.value = ''; notice.value = ''
  const token = requests.capture(), kind = props.kind, draftPoints = JSON.parse(JSON.stringify(points)), label = title.value
  try {
    await refreshComposition()
    if (!requests.current(token)) return
    const request = { ...props.context, revision: revision.value, pvtId: pvtId.value, method: method.value, kind, pressureMpa: points[kind].pressureMpa, temperatureC: points[kind].temperatureC }
    const issues = gasPropertyInputIssues({ ...request, compositionIssue: compositionIssue.value })
    if (issues.length) {
      try {
        await ElMessageBox.alert(h('div', { style: { whiteSpace: 'pre-line', lineHeight: '1.8' } }, issues.join('\n\n')),
          `${label}${save ? '保存' : '计算'}条件不满足`, { confirmButtonText: '知道了', type: 'warning' })
      } catch { /* Closing the validation message does not start a calculation. */ }
      return
    }
    if (save) {
      const data = (await api.saveGasProperties(request)).data
      if (!requests.current(token)) return
      acceptSaved(data)
      Object.assign(points, reconcileGasPropertyPoints(points, draftPoints, kind))
      await refreshComposition()
      if (!requests.current(token)) return
      emit('saved', data)
      ElMessage.success(`${label}检查点结果已保存`)
    } else {
      const data = (await api.calculateGasProperty(request)).data
      if (!requests.current(token)) return
      acceptResult(kind, data, data.compositionRevision ?? composition.value?.revision)
      ElMessage.success(`${label}计算完成`)
    }
    if (props.kind === kind) panel.value = 'result'
  } catch (e) { if (requests.current(token)) error.value = errorText(e) }
  finally { if (requests.current(token)) busy.value = false }
}
function cache() {
  if (ready.value) gasPropertyDrafts.set(activeContextKey, JSON.parse(JSON.stringify({ revision: revision.value, editable: editable(), savedMark: savedMark.value, savedResultMarks, dirty: dirty.value, points })))
}
function protect(event) { if (dirty.value) { event.preventDefault(); event.returnValue = '' } }
watch(() => gasPropertyContextKey(props.context), () => { cache(); load() }, { immediate: true, flush: 'sync' })
watch(() => [props.kind, props.commandKey], () => { panel.value = gasPropertyInitialPanel(point.value); importVisible.value = false; error.value = ''; if (!busy.value) refreshComposition() })
watch(() => [ready.value, sourceBusy.value, parameterKey.value], queueParameters)
onMounted(() => { window.addEventListener('beforeunload', protect); window.addEventListener('focus', refreshOnFocus) })
onBeforeUnmount(() => { cache(); requests.invalidate(); clearTimeout(parameterTimer); ++parameterGeneration; window.removeEventListener('beforeunload', protect); window.removeEventListener('focus', refreshOnFocus) })
defineExpose({ dirty })
</script>

<template>
  <section class="gas-property-page" :aria-label="title">
    <header class="workspace-toolbar">
      <strong class="workspace-title">温度模型－{{ title }}</strong><span class="well-name">{{ context.wellName }}</span>
      <span class="save-state">{{ pageDirty ? '有未保存修改' : point.result && stale ? '上次结果待重算' : revision && point.result ? '已保存' : '参数待填写' }}</span>
    </header>
    <div v-if="error" class="error-strip" role="alert">{{ error }}<button v-if="ready" :disabled="actionBusy" @click="load(true)">重新加载</button></div>
    <div v-if="sourceError" class="error-strip" role="alert">{{ sourceError }}</div>
    <div v-if="notice" class="notice">{{ notice }}<button v-if="ready && !error && notice.includes('重新加载')" :disabled="actionBusy" @click="load(true)">重新加载</button></div>
    <div v-if="stale && panel === 'result'" class="notice">PVT、计算方法、气体组成或温压已变化，以下为上次计算结果，请重新计算。</div>
    <div v-if="!ready" class="empty-state"><span>{{ busy ? '正在加载当前井物性配置…' : '物性配置未加载。' }}</span><button v-if="!busy" @click="load()">重新加载</button></div>
    <div v-else class="workspace-body">
      <aside class="parameter-panel" :class="{ collapsed: paramsCollapsed }">
        <button v-if="paramsCollapsed" class="parameter-collapsed-tab" type="button" title="展开参数设置" aria-label="展开参数设置" :aria-expanded="false" @click="paramsCollapsed = false">参数设置</button>
        <template v-else>
          <div class="panel-heading"><span>参数设置</span><button class="parameter-toggle" type="button" title="收起参数设置" aria-label="收起参数设置" :aria-expanded="true" @click="paramsCollapsed = true"><svg width="14" height="14" viewBox="0 0 24 24" fill="#777" aria-hidden="true"><path d="M16,12V4H17V2H7V4H8V12L6,14V16H11.2V22H12.8V16H18V14L16,12Z" /></svg></button></div>
          <fieldset class="parameter-form" :disabled="busy">
            <label class="field"><span>当前井 PVT 模型</span><input :value="pvtName" readonly aria-label="当前井 PVT 模型" /></label>
            <p v-if="!pvtId" class="hint">请先在管束能力的 PVT 模型页填写并保存完整气体组成。</p>
            <div class="field"><span>选择数据</span><button type="button" class="local-import-button" :disabled="actionBusy" @click="importVisible = true">本地导入</button><small v-if="importedFileNames[kind]" class="imported-data-name" :title="importedFileNames[kind]">{{ importedFileNames[kind] }}</small></div>
            <label class="field"><span>计算方法（来自 PVT 模型）</span><input :value="pvtId ? method : '—'" readonly aria-label="计算方法" /></label>
            <div class="parameter-actions">
              <button type="button" class="calculate-button" :disabled="actionBusy" @click="calculate(false)">计算</button>
              <button type="button" :disabled="actionBusy" @click="calculate(true)">保存</button>
            </div>
            <p class="hint">自动使用当前井已保存的完整组成与计算方法；本页独立保存温压及计算结果。</p>
            <p v-if="method === 'BWRS'" class="hint">BWRS 二元交互系数取 0（未标定）。</p>
          </fieldset>
        </template>
      </aside>
      <main class="result-panel">
        <div class="result-toolbar"><span>{{ panel === 'input' ? '数据列表' : '结果分析' }}</span><span class="context-label">{{ panel === 'result' && result ? point.resultPvtName + ' · ' + point.resultMethod : pvtName + ' · ' + method }}</span></div>
        <div class="table-scroll">
          <template v-if="panel === 'input'">
            <table class="data-table input-table" :aria-label="title + '输入参数'">
              <thead><tr><th class="index-column">序号</th><th>PVT 模型<small>自动读取</small></th><th>计算方法<small>来自 PVT 模型</small></th><th>压力<small>MPa绝压 · 可编辑</small></th><th>温度<small>℃ · 可编辑</small></th></tr></thead>
              <tbody><tr><td class="index-column">1</td><td class="readonly-cell">{{ pvtName }}</td><td class="readonly-cell">{{ method }}</td><td class="editable-cell"><input type="number" step="any" :value="point.pressureMpa" :disabled="busy" aria-label="压力（MPa绝压）" @input="editCondition('pressureMpa', $event)" /></td><td class="editable-cell"><input type="number" step="any" :value="point.temperatureC" :disabled="busy" aria-label="温度（℃）" @input="editCondition('temperatureC', $event)" /></td></tr></tbody>
            </table>
            <p class="table-note">黄色单元格可编辑，灰色单元格为读取或自动计算的参数。压力采用绝对压力，导入模板仅包含可填写的温压。</p>
            <p v-if="parameterBusy" class="table-note">正在读取计算采用的组分、物性常数和方法参数…</p>
            <p v-else-if="parameterError" class="table-note parameter-error" role="alert">{{ parameterError }}</p>
            <PipelineGasParameterTables v-if="displayedParameters" :snapshot="displayedParameters" :kind="kind" />
            <p v-if="displayedParameters?.issue" class="table-note">{{ displayedParameters.issue }}</p>
            <template v-if="!displayedParameters && compositionRows.length">
              <div class="section-heading">气体组成<span>当前井 PVT 模型 · 只读</span></div>
              <table class="data-table composition-table" aria-label="当前井PVT模型气体组成"><thead><tr><th>组分名称</th><th>组分代码</th><th>摩尔分数</th><th>摩尔百分比（%）</th></tr></thead><tbody><tr v-for="row in compositionRows" :key="row.code"><td>{{ row.name }}</td><td>{{ row.code }}</td><td class="numeric">{{ f(row.moleFraction, 8) }}</td><td class="numeric">{{ f(row.moleFraction * 100, 6) }}</td></tr></tbody></table>
            </template>
            <p v-if="pvtId" class="table-note">气体摩尔含量合计：{{ sourceBusy ? '读取中…' : compositionRows.length ? f(fractionSum * 100, 6) + '%' : '—' }}。计算前会核对当前井已保存的 PVT 模型。</p>
            <p v-else class="table-note">保存管束能力 PVT 模型后，本页自动加载参与计算的气体组成及组分常数。</p>
          </template>
          <template v-else>
            <table class="data-table result-table" :aria-label="title + '计算结果'">
              <thead><tr><th class="index-column">序号</th><th>PVT 模型</th><th>计算方法</th><th>压力<small>MPa绝压</small></th><th>温度<small>℃</small></th><th v-for="col in resultColumns" :key="col.key">{{ col.label }}<small>{{ col.unit }}</small></th><th>计算状态</th></tr></thead>
              <tbody><tr><td class="index-column">1</td><td>{{ result ? point.resultPvtName : '—' }}</td><td>{{ result ? point.resultMethod : '—' }}</td><td class="numeric">{{ result ? f(point.resultPressure, 6) : '—' }}</td><td class="numeric">{{ result ? f(point.resultTemperature, 3) : '—' }}</td><td v-for="col in resultColumns" :key="col.key" class="numeric">{{ f(result?.[col.key], col.digits) }}</td><td>{{ !result ? '未计算' : stale ? '待重算' : '计算完成' }}</td></tr></tbody>
            </table>
            <p v-if="!result" class="table-note">在数据列表中填写压力、温度后点击“计算”；气体组成与计算方法自动读取当前井 PVT 模型。</p>
            <p v-else class="table-note">{{ kind === 'cp' ? '对应资料 8（SRK）、9（BWRS）、10（PR）。计算中间密度由所选状态方程求解，无需先在压缩因子节点计算。' : '对应资料 5（PR）、6（SRK）、7（BWRS）。使用关联 PVT 的气体组分及指定温压计算。' }}</p>
            <template v-if="result?.parameterSnapshot">
              <div class="section-heading">本次计算采用的参数<span>对应上方结果，保留计算时的数值</span></div>
              <PipelineGasParameterTables :snapshot="result.parameterSnapshot" :kind="kind" :source-label="point.resultPvtName" />
            </template>
          </template>
        </div>
        <nav class="result-tabs" aria-label="物性计算结果"><button :class="{ active: panel === 'input' }" :aria-pressed="panel === 'input'" @click="panel = 'input'">数据列表</button><button :class="{ active: panel === 'result' }" :aria-pressed="panel === 'result'" @click="panel = 'result'">结果分析</button></nav>
      </main>
    </div>
    <PipelineTemperatureImportDialog v-model="importVisible" :kind="kind" :point="point" :well-name="context.wellName" @imported="acceptImport" />
  </section>
</template>

<style scoped>
.gas-property-page { display:flex;flex-direction:column;width:100%;height:100%;min-width:0;min-height:0;overflow:hidden;background:#fff;color:#252525;font:13px "Microsoft YaHei","Segoe UI",sans-serif; }
button,input,select { font:inherit;color:inherit;box-sizing:border-box; }
button { min-height:28px;padding:0 12px;border:1px solid #777;border-radius:4px;background:#fff;cursor:pointer; }
button:hover:not(:disabled) { border-color:#333;background:#f5f5f5; }
button:disabled { border-color:#ccc;color:#999;cursor:not-allowed; }
button:focus-visible { outline:2px solid #b99500;outline-offset:-2px; }
input,select { width:100%;min-width:0;height:28px;border:1px solid #aaa;border-radius:3px;background:#fff;padding:0 8px; }
input:focus,select:focus { border-color:#b99500;box-shadow:0 0 0 2px rgba(242,200,17,.16);outline:none; }
.workspace-toolbar { display:flex;align-items:center;flex-shrink:0;min-height:35px;border-bottom:1px solid #ddd;background:#fafafa;gap:12px; }
.workspace-title { display:flex;align-items:center;align-self:stretch;padding:0 14px;background:#f4d000;color:#202020;font-size:14px;font-weight:600; }
.well-name,.save-state { font-size:12px; }.save-state { color:#777; }
.workspace-body { display:flex;flex:1;min-height:0; }
.parameter-panel { display:flex;flex-direction:column;flex:0 0 280px;width:280px;min-width:280px;min-height:0;border-right:1px solid #ddd;background:#fff;overflow:hidden;transition:width .16s ease,min-width .16s ease,flex-basis .16s ease; }
.parameter-panel.collapsed { width:34px;min-width:34px;flex-basis:34px;height:100%;border:1px solid #d4d7db;border-right:0;box-sizing:border-box; }
.panel-heading { display:flex;align-items:center;justify-content:space-between;flex:0 0 34px;padding:0 12px;border-bottom:1px solid #ddd;background:#f2f2f2; }
.parameter-toggle { width:20px;height:20px;min-height:20px;padding:0;border:0;border-radius:2px;background:transparent;display:flex;align-items:center;justify-content:center; }
.parameter-toggle:hover { background:#fff8d8; }
.parameter-collapsed-tab { width:100%;height:76px;padding:8px 0 0;border:0;border-bottom:1px solid #e2e6ea;border-radius:0;writing-mode:vertical-rl;text-orientation:upright;line-height:1.05;display:flex;align-items:center;justify-content:flex-start; }
.parameter-collapsed-tab:hover { background:#fff8d8;box-shadow:inset -2px 0 0 #f4d000; }
.parameter-form { flex:1;min-height:0;min-width:0;margin:0;padding:10px 12px 14px;border:0;overflow:auto; }
.field { display:block;margin-bottom:11px;font-size:12px; }.field>span { display:block;margin-bottom:5px; }
.local-import-button { width:100%;height:26px;min-height:26px;padding:0 8px;border:1px solid #aaa;border-radius:3px;text-align:left; }
.imported-data-name { display:block;margin-top:4px;color:#777;font-size:11px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis; }
.radio-group { margin:12px 0;padding:0;border:0; }.radio-group legend { margin-bottom:7px;padding:0;font-size:12px; }
.radio-group label { display:inline-flex;align-items:center;gap:4px;margin-right:10px;font-size:12px;white-space:nowrap;cursor:pointer; }
.radio-group input { width:14px;height:14px;margin:0;accent-color:#333; }
.parameter-actions { display:flex;align-items:center;gap:8px;margin-top:12px; }
.parameter-actions button { min-width:86px;height:32px;padding:0 22px;border-radius:5px;font-size:13px;font-weight:600;border-color:#252525; }
.parameter-actions .calculate-button { background:#202020;border-color:#202020;color:#fff; }.parameter-actions .calculate-button:hover:not(:disabled) { background:#333; }
.parameter-actions .calculate-button:disabled { opacity:.5; }.hint { margin:12px 0;color:#777;font-size:12px;line-height:1.8; }
.result-panel { display:flex;flex-direction:column;flex:1;min-width:0;min-height:0;background:#fff; }
.result-toolbar { display:flex;align-items:center;flex-shrink:0;min-height:38px;padding:0 12px;gap:12px;border-bottom:1px solid #ddd;font-size:12px; }.context-label { color:#777; }
.table-scroll { flex:1;min-height:0;overflow:auto; }
.data-table { width:100%;border-collapse:separate;border-spacing:0;font-size:13px; }
.data-table th,.data-table td { padding:7px 10px;height:36px;box-sizing:border-box;border-right:1px solid #d4d7db;border-bottom:1px solid #d4d7db;text-align:center;line-height:1.5; }
.data-table th { position:sticky;top:0;z-index:1;background:#f4f4f4;color:#333;font-weight:400;white-space:nowrap; }
.data-table th small { display:block;font-size:12px;font-weight:400; }.data-table .index-column { width:54px;background:#f4f4f4; }.data-table .numeric { text-align:right;font-variant-numeric:tabular-nums; }
.input-table { min-width:750px;table-layout:fixed; }.input-table .editable-cell { padding:0;background:#fff8d8; }.readonly-cell,.composition-table td { background:#fafafa;color:#555; }
.input-table input { height:36px;border:1px solid transparent;border-radius:0;background:transparent;text-align:right;font-variant-numeric:tabular-nums; }
.input-table input:focus { border-color:#b99500;box-shadow:inset 0 0 0 1px #b99500; }.input-table input:disabled { color:#999; }
.result-table { min-width:1060px; }
.table-note { margin:12px;color:#666;font-size:12px;line-height:1.8; }
.section-heading { margin-top:16px;padding:8px 12px;border-block:1px solid #ddd;background:#fafafa;font-size:12px; }.section-heading span { margin-left:16px;color:#777; }.composition-table { min-width:500px; }.parameter-error { color:#b4463a; }
.result-tabs { display:flex;justify-content:center;flex:0 0 36px;border-top:1px solid #ddd; }
.result-tabs button { min-width:120px;border:0;border-right:1px solid #ddd;border-radius:0;font-size:13px; }.result-tabs button.active { background:#f2c811;color:#111;font-weight:600; }.result-tabs button:hover:not(.active) { background:#fff8d8; }
.error-strip,.notice { padding:7px 12px;font-size:12px;line-height:1.6;border-bottom:1px solid #ddd; }.error-strip { background:#fff1ef;color:#b4463a; }.notice { background:#fff8d8;color:#735c17; }
.error-strip button,.notice button { margin-left:12px;min-height:25px;font-size:12px; }
.empty-state { display:flex;align-items:center;justify-content:center;gap:12px;flex:1;margin:0;padding:24px;color:#777;font-size:13px;line-height:1.8; }
</style>
