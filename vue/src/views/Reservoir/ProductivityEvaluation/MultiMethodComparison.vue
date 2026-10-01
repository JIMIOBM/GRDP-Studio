<script setup>
/** 库 → 产能评价 → 产能对比 → 多方法。本文件包含该页面的参数状态、接口请求、模板和样式。 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { comparisonMethods, pressureForms } from '@/utils/productivityComparisonChart'
import { storageCatalogApi } from '@/api/storageCatalog'
import { storageProductivityComparisonApi } from '@/api/storageProductivityComparison'
import PeriodWellChart3D from './PeriodWellChart3D.vue'

const props = defineProps({
  storageId: { type: [Number, String], default: null },
  storageName: { type: String, default: '' },
  projectId: { type: [Number, String], required: true },
  gasReservoirId: { type: [Number, String], required: true },
})
// 当前功能固定在本页面，不通过外部参数切换成其他页面。
const methodType = '多方法'

// 库的三种对比均按周期统计；成员井、参数和请求状态独立于单井页面。
const storageWells = ref([])
const selectedWellIds = ref([])
const wellKeyword = ref('')
const wellError = ref('')
const loadingWells = ref(false)
const filteredWells = computed(() => storageWells.value.filter(well =>
  well.wellName.toLowerCase().includes(wellKeyword.value.trim().toLowerCase())))
const allWellsSelected = computed(() => filteredWells.value.length > 0
  && filteredWells.value.every(well => selectedWellIds.value.includes(well.id)))
const someWellsSelected = computed(() => !allWellsSelected.value
  && filteredWells.value.some(well => selectedWellIds.value.includes(well.id)))
const toggleAllWells = event => {
  const selected = new Set(selectedWellIds.value)
  filteredWells.value.forEach(well => event.target.checked ? selected.add(well.id) : selected.delete(well.id))
  selectedWellIds.value = [...selected]
}
const storageRecordStats = computed(() => new Map((comparisonResult.value?.wells || []).map(well => [String(well.wellId), well])))
let wellVersion = 0
// 三种对比共用已保存的二项式结果；不改动各来源模块的计算和保存逻辑。
const testBackPressure = ref(false)
const testIsochronal = ref(false)
const testModifiedIsochronal = ref(false)
const testSinglePoint = ref(false)
const theoryStableFlow = ref(false)
const theoryUnstableFlow = ref(false)
const startDate = ref('')
const endDate = ref('')
const formationPressure = ref('')
const injectionPressure = ref('')
const isMultiMethod = computed(() => methodType === '多方法')
const isDirectionComparison = computed(() => methodType === '注采对比')
const periodMethod = ref('back-pressure')
const directionMethod = ref('stable')
const singleMethod = computed({ get: () => isDirectionComparison.value ? directionMethod.value : periodMethod.value,
  set: value => { if (isDirectionComparison.value) directionMethod.value = value; else periodMethod.value = value } })
const periodMonths = ref(3)
const pressureMethod = ref('pressure')
const calculating = ref(false)
const comparisonResult = ref(null)
const selectedOperation = ref('production')
const productionEnabled = computed(() => isDirectionComparison.value || selectedOperation.value === 'production')
const injectionEnabled = computed(() => isDirectionComparison.value || selectedOperation.value === 'injection')
const selectedMethods = computed(() => !isMultiMethod.value ? [singleMethod.value] : [
  [testBackPressure.value, 'back-pressure'], [testIsochronal.value, 'isochronal'],
  [testModifiedIsochronal.value, 'modified-isochronal'], [testSinglePoint.value, 'one-point'],
  [theoryStableFlow.value, 'stable'], [theoryUnstableFlow.value, 'unstable']
].filter(([selected]) => selected).map(([, method]) => method))
const errorText = error => error?.response?.data?.msg || error?.msg || error?.message || '请求失败'
const unwrap = response => response?.data ?? response
let calculationVersion = 0
const clearResult = () => {
  calculationVersion++
  calculating.value = false
  comparisonResult.value = null
}
const loadStorageWells = async () => {
  const version = ++wellVersion
  storageWells.value = []; selectedWellIds.value = []; wellError.value = ''; clearResult()
  if (![props.projectId, props.gasReservoirId, props.storageId].every(id => Number(id) > 0)) {
    wellError.value = '请先选择具体储气库'; loadingWells.value = false; return
  }
  loadingWells.value = true
  try {
    const data = unwrap(await storageCatalogApi.wells(props.storageId, props.projectId, props.gasReservoirId))
    if (version !== wellVersion) return
    if (!Array.isArray(data)) throw new Error('单井列表格式不正确')
    storageWells.value = data.map(well => ({ id: Number(well.id), wellName: String(well.wellName || '') }))
    selectedWellIds.value = storageWells.value.map(well => well.id)
  } catch (error) { if (version === wellVersion) wellError.value = errorText(error) }
  finally { if (version === wellVersion) loadingWells.value = false }
}
const paramsCollapsed = ref(false)
const panelWidth = ref(238)
const resultTitle = computed(() => [props.storageName, '产能对比', methodType, '分析结果'].filter(Boolean).join('-'))
const workspaceEl = ref(null)
let resizeObserver = null
let resizeFrame = 0
const scheduleResize = () => {
  if (resizeFrame) cancelAnimationFrame(resizeFrame)
  resizeFrame = requestAnimationFrame(() => {
    resizeFrame = 0
    if (panelWidth.value > maxPanelWidth()) setPanelWidth(panelWidth.value)
  })
}
let dragStartX = 0
let dragStartWidth = 0
const maxPanelWidth = () => Math.max(238, Math.min(520, (workspaceEl.value?.clientWidth || 780) - 260))
const setPanelWidth = width => { panelWidth.value = Math.max(238, Math.min(maxPanelWidth(), width)) }
function startResize(event) {
  if (event.button !== 0) return
  event.preventDefault()
  dragStartX = event.clientX
  dragStartWidth = panelWidth.value
  window.addEventListener('pointermove', resizePanel)
  window.addEventListener('pointerup', stopResize, { once: true })
  window.addEventListener('pointercancel', stopResize, { once: true })
}
function resizePanel(event) { setPanelWidth(dragStartWidth + event.clientX - dragStartX) }
function stopResize() {
  window.removeEventListener('pointermove', resizePanel)
  window.removeEventListener('pointerup', stopResize)
  window.removeEventListener('pointercancel', stopResize)
}
function resizeWithKeyboard(event) {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  setPanelWidth(event.key === 'Home' ? 238 : event.key === 'End' ? maxPanelWidth() : panelWidth.value + (event.key === 'ArrowRight' ? 20 : -20))
}
// 切换库时重新读取成员；切方法时清空压力与旧结果，不复用其他页面的状态。
watch(() => [props.projectId, props.gasReservoirId, props.storageId], () => {
  formationPressure.value = ''; injectionPressure.value = ''; wellKeyword.value = ''
  loadStorageWells()
}, { immediate: true })
watch(() => methodType, () => {
  formationPressure.value = ''; injectionPressure.value = ''; clearResult()
})
// 条件变化立即作废旧请求，防止较晚返回的结果覆盖当前库或参数。
watch(() => [methodType, periodMethod.value, pressureMethod.value, startDate.value, endDate.value,
  periodMonths.value, selectedOperation.value, formationPressure.value, injectionPressure.value,
  ...selectedWellIds.value, ...selectedMethods.value], clearResult, { flush: 'sync' })
watch(selectedOperation, () => {
  formationPressure.value = ''
  injectionPressure.value = ''
})
onMounted(async () => {
  await nextTick()
  resizeObserver = new ResizeObserver(() => {
    if (panelWidth.value > maxPanelWidth()) setPanelWidth(panelWidth.value)
    scheduleResize()
  })
  resizeObserver.observe(workspaceEl.value)
  window.addEventListener('resize', scheduleResize)
})
onBeforeUnmount(() => {
  wellVersion++
  calculationVersion++
  stopResize()
  resizeObserver?.disconnect()
  window.removeEventListener('resize', scheduleResize)
  if (resizeFrame) cancelAnimationFrame(resizeFrame)
})
const handleCalculate = async () => {
  if (loadingWells.value || calculating.value) return
  if (!selectedWellIds.value.length) return ElMessage.warning('请选择参与对比的井')
  if (!selectedMethods.value.length) return ElMessage.warning('请选择参与对比的方法')
  if (startDate.value && endDate.value && startDate.value > endDate.value) return ElMessage.warning('开始日期不能晚于结束日期')
  if (!Number.isInteger(Number(periodMonths.value)) || Number(periodMonths.value) < 1 || Number(periodMonths.value) > 1200)
    return ElMessage.warning('周期时长必须为1至1200的整数（月）')
  // 注采对比同时校验两个输入；其余库对比仍仅使用所选方向的压力。
  const hasProduction = productionEnabled.value, hasInjection = injectionEnabled.value
  const pr = Number(formationPressure.value), pwf = Number(injectionPressure.value)
  if (hasProduction && (!Number.isFinite(pr) || pr <= .1)) return ElMessage.warning('计算地层压力必须大于0.1 MPa')
  if (hasInjection && (!Number.isFinite(pwf) || pwf <= .1)) return ElMessage.warning('计算注气压力必须大于0.1 MPa')
  if (pressureMethod.value === 'pseudo-pressure' && (hasProduction && pr > 200 || hasInjection && pwf > 200))
    return ElMessage.warning('拟压力接口的计算压力不能超过200 MPa')
  const version = ++calculationVersion
  calculating.value = true; comparisonResult.value = null
  try {
    const calculate = isDirectionComparison.value ? storageProductivityComparisonApi.compareDirections
      : isMultiMethod.value ? storageProductivityComparisonApi.calculateMethods : storageProductivityComparisonApi.calculate
    const result = unwrap(await calculate({ projectId: Number(props.projectId),
      gasReservoirId: Number(props.gasReservoirId), storageId: Number(props.storageId), wellIds: [...selectedWellIds.value],
      ...(isMultiMethod.value ? { methods: [...selectedMethods.value] } : { method: singleMethod.value }),
      ...(!isDirectionComparison.value ? { operationType: selectedOperation.value } : {}), pressureMethod: pressureMethod.value,
      ...(hasProduction ? { formationPressure: pr } : {}), ...(hasInjection ? { injectionPressure: pwf } : {}),
      period: { ...(startDate.value ? { startDate: startDate.value } : {}),
        ...(endDate.value ? { endDate: endDate.value } : {}), months: Number(periodMonths.value) } }))
    if (version !== calculationVersion) return
    if (!Array.isArray(result?.periods) || !Array.isArray(result?.wells) || isMultiMethod.value && !Array.isArray(result?.methods))
      throw new Error('库产能对比接口尚未更新，请重启后端服务后重试')
    if (result.periods.some(period => !period || !Array.isArray(period.wells)
      || period.wells.some(well => !well || !Array.isArray(isMultiMethod.value ? well.methods : well.directions))))
      throw new Error('库产能对比接口的分组数据格式不正确，请更新后端服务')
    if (isDirectionComparison.value && result.periods.some(period => !Array.isArray(period.wells)
      || period.wells.some(well => !Array.isArray(well.directions))))
      throw new Error('库注采对比接口返回格式不正确，请更新后端服务')
    comparisonResult.value = result
  } catch (error) { if (version === calculationVersion) ElMessage.error(errorText(error)) }
  finally { if (version === calculationVersion) calculating.value = false }
}
</script>

<template>
  <div ref="workspaceEl" class="comparison-workspace">
    <aside class="params-panel water-parameter-theme" :class="{ collapsed: paramsCollapsed }"
      :style="paramsCollapsed ? undefined : { width: panelWidth + 'px', minWidth: panelWidth + 'px', flexBasis: panelWidth + 'px' }">
      <button v-if="paramsCollapsed" class="panel-collapsed-tab" type="button" aria-label="展开参数设置" @click="paramsCollapsed = false">参数设置</button>
      <template v-else>
        <div class="panel-head">
          <span>参数设置</span>
          <button class="panel-toggle" type="button" title="收起参数设置" aria-label="收起参数设置" @click="paramsCollapsed = true">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="#777" aria-hidden="true"><path d="M16,12V4H17V2H7V4H8V12L6,14V16H11.2V22H12.8V16H18V14L16,12Z" /></svg>
          </button>
        </div>
        <div class="panel-body">

          <fieldset  class="field-section">
            <legend class="sec-label">产能试井</legend>
            <div class="checkbox-grid">
              <label><input v-model="testBackPressure" type="checkbox" />回压试井</label>
              <label><input v-model="testIsochronal" type="checkbox" />等时试井</label>
              <label><input v-model="testModifiedIsochronal" type="checkbox" />修正等时</label>
              <label><input v-model="testSinglePoint" type="checkbox" />一点法</label>
            </div>
          </fieldset>
          <fieldset  class="field-section">
            <legend class="sec-label">理论计算</legend>
            <div class="checkbox-grid">
              <label><input v-model="theoryStableFlow" type="checkbox" />稳定流</label>
              <label><input v-model="theoryUnstableFlow" type="checkbox" />不稳定流</label>
            </div>
          </fieldset>
          <fieldset  class="operation-group">
            <legend>注采类型</legend>
            <div class="operation-options">
              <label ><input v-model="selectedOperation" type="radio" name="comparison-operation" value="production" />采气</label>
              <label ><input v-model="selectedOperation" type="radio" name="comparison-operation" value="injection" />注气</label>
            </div>
          </fieldset>
          <fieldset  class="field-section">
            <legend class="sec-label">计算方法</legend>
            <div class="operation-options pressure-options">
              <label v-for="form in pressureForms" :key="form.value"><input v-model="pressureMethod" type="radio" name="comparison-pressure-method" :value="form.value" />{{ form.label }}</label>
            </div>
            <div class="result-form-label">二项式</div>
          </fieldset>
          <div class="sec-label">其它数据</div>
          <div class="field-grid">
            <label class="field"><span>开始日期</span><input v-model="startDate" type="date" /></label>
            <label class="field"><span>结束日期</span><input v-model="endDate" type="date" /></label>
            <label class="field"><span>周期时长（月）</span><input v-model="periodMonths" type="number" min="1" max="1200" step="1" /></label>
            <label v-if="productionEnabled" class="field pressure-field"><span>计算无阻流量的地层压力（MPa）</span><input v-model="formationPressure" type="text" inputmode="decimal" autocomplete="off" />
            </label>
            <label v-if="injectionEnabled" class="field pressure-field"><span>计算无阻流量的注气压力（MPa）</span><input v-model="injectionPressure" type="text" inputmode="decimal" autocomplete="off" />
            </label>
          </div>
          <fieldset class="field-section records-section storage-wells-section">
            <legend class="sec-label">参与对比的井（{{ selectedWellIds.length }}）
              <label class="select-all-records"><input type="checkbox" :checked="allWellsSelected" :indeterminate="someWellsSelected"
              :disabled="loadingWells || !filteredWells.length" @change="toggleAllWells" />{{ wellKeyword.trim() ? '全选搜索结果' : '全选' }}</label>
            </legend>
            <label class="field"><input v-model="wellKeyword" type="search" placeholder="搜索井名" aria-label="搜索参与对比的井" /></label>
            <div v-if="loadingWells" class="record-status" role="status">正在读取单井…</div>
            <div v-else-if="wellError" class="record-status record-error" role="alert">{{ wellError }} <button type="button" @click="loadStorageWells">重试</button></div>
            <div v-else-if="!storageWells.length" class="record-status">当前储气库暂无单井</div>
            <div v-else-if="!filteredWells.length" class="record-status">没有匹配的井</div>
            <div v-else class="record-list storage-well-list">
              <label v-for="well in filteredWells" :key="well.id" class="record-row">
                <input v-model="selectedWellIds" type="checkbox" :value="well.id" :aria-label="well.wellName" />
                <span class="record-info"><span class="record-name" :title="well.wellName">{{ well.wellName }}</span>
                  <span v-if="storageRecordStats.has(String(well.id))" class="record-meta">
                    有效 {{ storageRecordStats.get(String(well.id)).recordCount }} 条
                    <template v-if="storageRecordStats.get(String(well.id)).excludedCount"> · 排除 {{ storageRecordStats.get(String(well.id)).excludedCount }} 条</template>
                  </span>
                </span>
              </label>
            </div>
          </fieldset>
          <div class="form-actions"><button type="button" class="calculate-button" :disabled="(calculating || loadingWells)" @click="handleCalculate">{{ calculating ? '计算中…' : '计算' }}</button></div>
        </div>
        <div class="params-resizer" role="separator" tabindex="0" aria-label="调整参数栏宽度" aria-orientation="vertical"
          :aria-valuenow="panelWidth" :aria-valuemin="238" :aria-valuemax="maxPanelWidth()" @pointerdown="startResize" @keydown="resizeWithKeyboard" />
      </template>
    </aside>
    <main class="result-area">
      <div class="result-tabs"><div class="result-tab active" :title="resultTitle">{{ resultTitle }}</div></div>
      <PeriodWellChart3D comparison-type="method" :result="comparisonResult" :busy="calculating" :title="resultTitle" :project-id="projectId" :gas-reservoir-id="gasReservoirId" :storage-id="storageId" />
    </main>
  </div>
</template>

<style lang="scss" scoped>
// 当前页面的展示样式，与其他产能对比页面保持一致。
// 238px 初始参数栏、34px 标题栏和黄黑交互色。
.comparison-workspace { flex: 1; min-width: 0; min-height: 0; display: flex; overflow: hidden; background: #fff; color: #252525; font: 13px/1.5 "Microsoft YaHei", "Segoe UI", Arial, sans-serif; }
.params-panel { position: relative; min-height: 0; display: flex; flex-direction: column; overflow: hidden; border-right: 1px solid #d7d7d7; background: #fff; }
.params-panel.collapsed { width: 34px; min-width: 34px; flex: 0 0 34px; }
.panel-collapsed-tab { width: 100%; height: 76px; padding: 8px 0; border: 0; border-bottom: 1px solid #e2e6ea; background: #fff; color: #333; font: inherit; writing-mode: vertical-rl; cursor: pointer; }
.panel-head { height: 34px; padding: 0 12px; display: flex; align-items: center; justify-content: space-between; flex-shrink: 0; border-bottom: 1px solid #d7d7d7; background: #f2f2f2; box-sizing: border-box; }
.panel-toggle { width: 20px; height: 20px; padding: 0; border: 0; display: flex; align-items: center; justify-content: center; background: transparent; cursor: pointer; }
.panel-toggle:hover { background: #fff8d8; }
.panel-body { flex: 1; min-height: 0; padding: 10px 12px 16px; overflow-y: auto; }
.field-section { border: 0; padding: 0; margin: 0 0 9px; min-width: 0; }
.sec-label { width: 100%; height: 22px; padding: 0; margin: 8px 0 7px; display: flex; align-items: center; gap: 8px; font-weight: 500; }
.sec-label::after { content: ''; height: 1px; flex: 1; background: #999; }
.checkbox-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px 12px; padding: 3px 0 8px; }
.checkbox-grid label, .operation-options label { display: inline-flex; align-items: center; gap: 5px; font-size: 13px; font-weight: 400; white-space: nowrap; cursor: pointer; }
.checkbox-grid input, .operation-options input { width: 13px; height: 13px; margin: 0; accent-color: #333; cursor: pointer; }
.operation-group { min-width: 0; margin: 8px 0 12px; padding: 0; border: 0; }
.operation-group legend { margin-bottom: 4px; padding: 0; font-size: 13px; font-weight: 400; }
.operation-options { display: flex; align-items: center; gap: 12px; }
.pressure-options { flex-wrap: wrap; row-gap: 8px; }
.result-form-label { margin-top: 6px; color: #666; font-size: 12px; }
.record-list { border: 1px solid #ddd; }
.storage-well-list { max-height: 240px; overflow-y: auto; margin-top: 6px; }
.select-all-records { display: inline-flex; align-items: center; gap: 4px; white-space: nowrap; font-weight: 400; cursor: pointer; }
.select-all-records input { width: 13px; height: 13px; margin: 0; accent-color: #333; cursor: pointer; }
.select-all-records:has(input:disabled) { color: #999; cursor: default; }
.select-all-records input:disabled { cursor: default; }
.record-row { display: flex; align-items: flex-start; gap: 7px; padding: 7px; border-bottom: 1px solid #eee; cursor: pointer; }
.record-row:last-child { border-bottom: 0; }
.record-row input { accent-color: #333; margin: 3px 0 0; width: 13px; height: 13px; flex-shrink: 0; }
.record-info { min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.record-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.record-meta { color: #777; font-size: 11px; }
.record-status { color: #777; font-size: 12px; padding: 8px 0; }
.record-error { color: #b24b37; font-size: 12px; overflow-wrap: anywhere; }
.record-error button { font: inherit; cursor: pointer; }
.record-row.unavailable { background: #fafafa; cursor: default; }
.calculate-button:disabled { opacity: .6; cursor: wait; }
.field-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(190px, 1fr)); column-gap: 24px; }
.field { display: block; min-width: 0; margin-bottom: 9px; }
.field span { display: block; margin-bottom: 3px; color: #252525; font-size: 13px; }
.field input { display: block; width: 100%; min-width: 0; height: 24px; padding: 0 8px; box-sizing: border-box; border: 1px solid #aaa; border-radius: 3px; background: #fff; color: #333; font: inherit; }
.method-select { width: 100%; height: 24px; padding: 0 8px; border: 1px solid #aaa; border-radius: 3px; background: #fff; color: #333; font: inherit; }
.field input:focus-visible { outline: 1px solid #d5b900; outline-offset: 1px; }
.pressure-field { grid-column: 1 / -1; }
.form-actions { display: flex; align-items: center; gap: 10px; }
.calculate-button { min-width: 86px; height: 30px; margin: 14px 0 4px; padding: 0 22px; border: 1px solid #d5b900; border-radius: 4px; background: #f4d000; color: #222; font: inherit; cursor: pointer; }
.calculate-button:hover { background: #ffe033; }
button:focus-visible { outline: 2px solid #555; outline-offset: -2px; }
.params-resizer { position: absolute; z-index: 4; top: 0; right: 0; width: 5px; height: 100%; cursor: col-resize; touch-action: none; }
.params-resizer:hover, .params-resizer:focus-visible { background: #f4d000; outline: none; }
.result-area { flex: 1; min-width: 0; min-height: 0; display: flex; flex-direction: column; overflow: hidden; background: #fff; }
.result-tabs { height: 34px; flex: 0 0 34px; display: flex; align-items: center; border-bottom: 1px solid #e4e7ed; background: #fafafa; }
.result-tab { height: 34px; line-height: 34px; max-width: min(430px, 100%); padding: 0 12px; border-right: 1px solid #e4e7ed; box-sizing: border-box; background: #f4d000; color: #202020; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
</style>
