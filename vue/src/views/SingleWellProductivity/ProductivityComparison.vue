<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import { productivityComparisonApi } from '@/api/productivityComparison'
import { buildComparisonChart, buildPeriodComparisonChart, buildDirectionComparisonChart, comparisonMethods, pressureForms } from '@/utils/productivityComparisonChart'

const props = defineProps({
  wellName: { type: String, default: '' },
  projectId: { type: [Number, String], required: true },
  gasReservoirId: { type: [Number, String], required: true },
  methodType: { type: String, default: '多周期', validator: value => ['多周期', '多方法', '注采对比'].includes(value) }
})
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
const isMultiMethod = computed(() => props.methodType === '多方法')
const isMultiPeriod = computed(() => props.methodType === '多周期')
const isDirectionComparison = computed(() => props.methodType === '注采对比')
const isComparisonEnabled = computed(() => isMultiMethod.value || isMultiPeriod.value || isDirectionComparison.value)
const periodMethod = ref('back-pressure')
const directionMethod = ref('stable')
const singleMethod = computed({ get: () => isDirectionComparison.value ? directionMethod.value : periodMethod.value,
  set: value => { if (isDirectionComparison.value) directionMethod.value = value; else periodMethod.value = value } })
const periodMonths = ref(3)
const pressureMethod = ref('pressure')
const records = ref([])
const selectedRecordKeys = ref([])
const selectableRecordKeys = computed(() => records.value.filter(record => record.available).map(record => record.key))
const selectedAvailableCount = computed(() => {
  const selected = new Set(selectedRecordKeys.value)
  return selectableRecordKeys.value.filter(key => selected.has(key)).length
})
const allRecordsSelected = computed(() => selectableRecordKeys.value.length > 0
  && selectedAvailableCount.value === selectableRecordKeys.value.length)
const someRecordsSelected = computed(() => selectedAvailableCount.value > 0 && !allRecordsSelected.value)
const toggleAllRecords = event => {
  selectedRecordKeys.value = event.target.checked ? [...selectableRecordKeys.value] : []
}
const loadingRecords = ref(false)
const recordError = ref('')
const calculating = ref(false)
const comparisonResult = ref(null)
const selectedOperation = ref('production')
const productionEnabled = computed(() => isDirectionComparison.value || selectedOperation.value === 'production')
const injectionEnabled = computed(() => isDirectionComparison.value || selectedOperation.value === 'injection')
const selectedOperationTypes = computed(() => isDirectionComparison.value ? ['production', 'injection'] : [selectedOperation.value])
const operationLabel = record => record.operationType === 'injection' ? '注气' : '采气'
const selectedMethods = computed(() => !isMultiMethod.value ? [singleMethod.value] : [
  [testBackPressure.value, 'back-pressure'], [testIsochronal.value, 'isochronal'],
  [testModifiedIsochronal.value, 'modified-isochronal'], [testSinglePoint.value, 'one-point'],
  [theoryStableFlow.value, 'stable'], [theoryUnstableFlow.value, 'unstable']
].filter(([selected]) => selected).map(([, method]) => method))
const scope = () => ({ projectId: Number(props.projectId), gasReservoirId: Number(props.gasReservoirId), wellName: props.wellName })
const scopeValid = () => Number(props.projectId) > 0 && Number(props.gasReservoirId) > 0 && !!props.wellName?.trim()
const errorText = error => error?.response?.data?.msg || error?.msg || error?.message || '请求失败'
const unwrap = response => response?.data ?? response
let recordVersion = 0
let calculationVersion = 0
let loadTimer
const clearResult = () => {
  calculationVersion++
  calculating.value = false
  comparisonResult.value = null
  if (isComparisonEnabled.value) renderChart()
}
const loadRecords = async () => {
  const version = ++recordVersion
  const previousKeys = selectedRecordKeys.value.slice()
  records.value = []
  recordError.value = ''
  clearResult()
  if (!isComparisonEnabled.value || !scopeValid() || !selectedMethods.value.length || !selectedOperationTypes.value.length) {
    selectedRecordKeys.value = []; loadingRecords.value = false; return
  }
  if (startDate.value && endDate.value && startDate.value > endDate.value) {
    recordError.value = '开始日期不能晚于结束日期'; selectedRecordKeys.value = []; loadingRecords.value = false; return
  }
  loadingRecords.value = true
  try {
    const data = unwrap(await productivityComparisonApi.records({ ...scope(), methods: selectedMethods.value, operationTypes: selectedOperationTypes.value,
      pressureMethod: pressureMethod.value, ...(startDate.value ? { startDate: startDate.value } : {}),
      ...(endDate.value ? { endDate: endDate.value } : {}) }))
    if (version !== recordVersion) return
    records.value = (Array.isArray(data) ? data : []).map(record => !isMultiMethod.value && !record.date
      ? { ...record, available: false, unavailableReason: '缺少计算日期，请先重新计算并保存' } : record)
    const available = new Set(records.value.filter(item => item.available).map(item => item.key))
    selectedRecordKeys.value = previousKeys.filter(key => available.has(key))
  } catch (error) {
    if (version !== recordVersion) return
    recordError.value = errorText(error); selectedRecordKeys.value = []
  } finally { if (version === recordVersion) loadingRecords.value = false }
}
const paramsCollapsed = ref(false)
const panelWidth = ref(300)
const resultTitle = computed(() => [props.wellName, '产能对比', props.methodType, '分析结果'].filter(Boolean).join('-'))
const workspaceEl = ref(null)
const chartEl = ref(null)
let chart = null
let resizeObserver = null
let resizeFrame = 0
const scheduleResize = () => {
  if (resizeFrame) cancelAnimationFrame(resizeFrame)
  resizeFrame = requestAnimationFrame(() => { resizeFrame = 0; chart?.resize() })
}
const renderChart = () => {
  if (!chartEl.value) return
  chart ||= echarts.init(chartEl.value)
  if (isComparisonEnabled.value) {
    const builder = isDirectionComparison.value ? buildDirectionComparisonChart
      : isMultiPeriod.value ? buildPeriodComparisonChart : buildComparisonChart
    chart.setOption(builder(comparisonResult.value, pressureMethod.value,
      calculating.value ? '正在计算…' : '请选择记录并计算'), true)
    scheduleResize()
    return
  }
  // 对齐稳定流的图表留白、标题和细网格；尚未接入结果，不绘制虚构曲线和数值。
  const axis = {
    type: 'value', min: 0, max: 1, nameLocation: 'middle',
    nameTextStyle: { fontFamily: 'Microsoft YaHei, Segoe UI, sans-serif', fontSize: 13, color: '#555' },
    axisLine: { show: true, lineStyle: { color: '#555' } },
    axisTick: { show: false }, axisLabel: { show: false },
    splitLine: { show: true, lineStyle: { color: '#dfe6f1' } },
    minorTick: { show: true }, minorSplitLine: { show: true, lineStyle: { color: '#f2f5fa' } }
  }
  chart.setOption({
    animation: false,
    title: { text: props.methodType + '产能对比图', left: 'center', top: 10, textStyle: { fontFamily: 'Microsoft YaHei, Segoe UI, sans-serif', fontSize: 14, fontWeight: 600, color: '#333' } },
    grid: { left: 82, right: 48, top: 64, bottom: 64 },
    xAxis: { ...axis, name: '回压试井', nameGap: 40 },
    yAxis: { ...axis, name: '无阻流量', nameGap: 52 },
    series: []
  }, true)
  scheduleResize()
}
let dragStartX = 0
let dragStartWidth = 0
const maxPanelWidth = () => Math.max(280, Math.min(480, (workspaceEl.value?.clientWidth || 780) - 260))
const setPanelWidth = width => { panelWidth.value = Math.max(280, Math.min(maxPanelWidth(), width)) }
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
  setPanelWidth(event.key === 'Home' ? 280 : event.key === 'End' ? maxPanelWidth() : panelWidth.value + (event.key === 'ArrowRight' ? 20 : -20))
}
watch(() => props.methodType, async () => { await nextTick(); renderChart() })
watch(() => [props.projectId, props.gasReservoirId, props.wellName, props.methodType], () => {
  selectedRecordKeys.value = []; formationPressure.value = ''; injectionPressure.value = ''; clearResult()
})
watch(() => [props.projectId, props.gasReservoirId, props.wellName, props.methodType,
  pressureMethod.value, startDate.value, endDate.value, productionEnabled.value, injectionEnabled.value, ...selectedMethods.value], () => {
  // Invalidate immediately, including the debounce interval, so a late response cannot restore another well's records.
  recordVersion++; clearResult(); clearTimeout(loadTimer)
  records.value = []; loadingRecords.value = isComparisonEnabled.value && selectedMethods.value.length > 0
  loadTimer = setTimeout(loadRecords, 150)
}, { immediate: true })
watch([formationPressure, injectionPressure, selectedRecordKeys], clearResult, { deep: true })
watch(periodMonths, clearResult)
watch(selectedOperation, () => {
  selectedRecordKeys.value = []
  formationPressure.value = ''
  injectionPressure.value = ''
})
watch([paramsCollapsed, panelWidth], async () => { await nextTick(); scheduleResize() })
onMounted(async () => {
  await nextTick()
  renderChart()
  resizeObserver = new ResizeObserver(() => {
    if (panelWidth.value > maxPanelWidth()) setPanelWidth(panelWidth.value)
    scheduleResize()
  })
  resizeObserver.observe(chartEl.value)
  resizeObserver.observe(workspaceEl.value)
  window.addEventListener('resize', scheduleResize)
})
onBeforeUnmount(() => {
  recordVersion++; calculationVersion++; clearTimeout(loadTimer)
  stopResize()
  resizeObserver?.disconnect()
  window.removeEventListener('resize', scheduleResize)
  if (resizeFrame) cancelAnimationFrame(resizeFrame)
  chart?.dispose()
  chart = null
})
const handleCalculate = async () => {
  if (!isComparisonEnabled.value) return
  if (!scopeValid()) return ElMessage.warning('请先选择当前项目、气藏和井')
  if (isMultiPeriod.value) {
    if (startDate.value && endDate.value && startDate.value > endDate.value) return ElMessage.warning('开始日期不能晚于结束日期')
    if (!Number.isInteger(Number(periodMonths.value)) || Number(periodMonths.value) < 1 || Number(periodMonths.value) > 1200)
      return ElMessage.warning('周期时长必须为1至1200的整数（月）')
  }
  const selected = records.value.filter(item => item.available && selectedRecordKeys.value.includes(item.key))
  if (!selectedOperationTypes.value.length || !selected.length) return ElMessage.warning('请选择参与对比的采气或注气记录')
  const hasProduction = selected.some(item => item.operationType !== 'injection')
  const hasInjection = selected.some(item => item.operationType === 'injection')
  if (isDirectionComparison.value && (!hasProduction || !hasInjection))
    return ElMessage.warning('请同时选择采气和注气记录进行对比')
  const pr = Number(formationPressure.value), pwf = Number(injectionPressure.value)
  if (hasProduction && (!Number.isFinite(pr) || pr <= .1)) return ElMessage.warning('计算地层压力必须大于0.1 MPa')
  if (hasInjection && (!Number.isFinite(pwf) || pwf <= .1)) return ElMessage.warning('计算注气压力必须大于0.1 MPa')
  if (pressureMethod.value === 'pseudo-pressure' && (hasProduction && pr > 200 || hasInjection && pwf > 200))
    return ElMessage.warning('拟压力接口的计算压力不能超过200 MPa')
  if (selected.length > 100) return ElMessage.warning('一次最多对比100条记录')
  const version = ++calculationVersion
  calculating.value = true; comparisonResult.value = null; renderChart()
  try {
    const calculate = isDirectionComparison.value ? productivityComparisonApi.compareDirections : productivityComparisonApi.calculate
    const result = unwrap(await calculate({ ...scope(), pressureMethod: pressureMethod.value,
      ...(isMultiPeriod.value ? { period: { ...(startDate.value ? { startDate: startDate.value } : {}),
        ...(endDate.value ? { endDate: endDate.value } : {}), months: Number(periodMonths.value) } } : {}),
      ...(hasProduction ? { formationPressure: pr } : {}), ...(hasInjection ? { injectionPressure: pwf } : {}),
      records: selected.map(item => ({ method: item.method, recordId: item.recordId, operationType: item.operationType || 'production' })) }))
    if (version !== calculationVersion) return
    if (isMultiPeriod.value && !Array.isArray(result?.periods))
      throw new Error('多周期接口尚未更新，请重启后端服务后重试')
    if (isDirectionComparison.value && (!Array.isArray(result?.groups) || !Array.isArray(result?.calculation?.results)))
      throw new Error('注采对比接口尚未更新，请重启后端服务后重试')
    comparisonResult.value = isDirectionComparison.value ? { ...result.calculation, directionGroups: result.groups } : result
  } catch (error) {
    if (version === calculationVersion) ElMessage.error(errorText(error))
  } finally {
    if (version === calculationVersion) { calculating.value = false; renderChart() }
  }
}
</script>

<template>
  <div ref="workspaceEl" class="comparison-workspace">
    <aside class="params-panel" :class="{ collapsed: paramsCollapsed }"
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
          <fieldset v-if="!isMultiMethod" class="field-section">
            <legend class="sec-label">对比方法</legend>
            <select v-model="singleMethod" class="method-select" aria-label="对比方法">
              <option v-for="method in comparisonMethods" :key="method.value" :value="method.value">{{ method.label }}</option>
            </select>
          </fieldset>
          <fieldset v-else class="field-section">
            <legend class="sec-label">产能试井</legend>
            <div class="checkbox-grid">
              <label><input v-model="testBackPressure" type="checkbox" />回压试井</label>
              <label><input v-model="testIsochronal" type="checkbox" />等时试井</label>
              <label><input v-model="testModifiedIsochronal" type="checkbox" />修正等时</label>
              <label><input v-model="testSinglePoint" type="checkbox" />一点法</label>
            </div>
          </fieldset>
          <fieldset v-if="isMultiMethod" class="field-section">
            <legend class="sec-label">理论计算</legend>
            <div class="checkbox-grid">
              <label><input v-model="theoryStableFlow" type="checkbox" />稳定流</label>
              <label><input v-model="theoryUnstableFlow" type="checkbox" />不稳定流</label>
            </div>
          </fieldset>
          <fieldset v-if="methodType !== '注采对比'" class="operation-group">
            <legend>注采类型</legend>
            <div class="operation-options">
              <label v-if="isComparisonEnabled"><input v-model="selectedOperation" type="radio" name="comparison-operation" value="production" />采气</label>
              <label v-if="isComparisonEnabled"><input v-model="selectedOperation" type="radio" name="comparison-operation" value="injection" />注气</label>
            </div>
          </fieldset>
          <fieldset v-if="isComparisonEnabled" class="field-section">
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
            <label v-if="isMultiPeriod" class="field"><span>周期时长（月）</span><input v-model="periodMonths" type="number" min="1" max="1200" step="1" /></label>
            <label v-if="!isComparisonEnabled || productionEnabled" class="field pressure-field"><span>计算无阻流量的地层压力{{ isComparisonEnabled ? '（MPa）' : '' }}</span><input v-model="formationPressure" type="text" inputmode="decimal" autocomplete="off" />
            </label>
            <label v-if="isComparisonEnabled && injectionEnabled" class="field pressure-field"><span>计算无阻流量的注气压力（MPa）</span><input v-model="injectionPressure" type="text" inputmode="decimal" autocomplete="off" />
            </label>
          </div>
          <fieldset v-if="isComparisonEnabled" class="field-section records-section">
            <legend class="sec-label">参与对比记录（{{ selectedRecordKeys.length }}）
              <label class="select-all-records">
                <input type="checkbox" :checked="allRecordsSelected" :indeterminate="someRecordsSelected"
                  :disabled="loadingRecords || !selectableRecordKeys.length" @change="toggleAllRecords" />全选
              </label>
            </legend>
            <div v-if="loadingRecords" class="record-status" role="status">正在读取记录…</div>
            <div v-else-if="recordError" class="record-status record-error" role="alert">{{ recordError }} <button type="button" @click="loadRecords">重试</button></div>
            <div v-else-if="!scopeValid()" class="record-status">请先选择井</div>
            <div v-else-if="!selectedOperationTypes.length || !selectedMethods.length" class="record-status">请选择注采类型及对比方法</div>
            <div v-else-if="!records.length" class="record-status">暂无符合所选注采类型和压力方法的已保存二项式记录</div>
            <div v-else class="record-list">
              <label v-for="record in records" :key="record.key" class="record-row" :class="{ unavailable: !record.available }">
                <input v-model="selectedRecordKeys" type="checkbox" :value="record.key" :disabled="!record.available" :aria-label="record.recordName + ' ' + operationLabel(record) + ' ' + (record.date || '')" />
                <span class="record-info"><span class="record-name" :title="record.recordName">{{ record.recordName }}</span>
                  <span class="record-meta">{{ record.methodName }} · <template v-if="isDirectionComparison">{{ operationLabel(record) }} · </template>{{ record.dateKind }} {{ record.date || '未记录' }}</span>
                  <span v-if="!record.available" class="record-error">{{ record.unavailableReason }}</span>
                </span>
              </label>
            </div>
          </fieldset>
          <div class="form-actions"><button type="button" class="calculate-button" :disabled="isComparisonEnabled && (calculating || loadingRecords)" @click="handleCalculate">{{ isComparisonEnabled && calculating ? '计算中…' : '计算' }}</button></div>
        </div>
        <div class="params-resizer" role="separator" tabindex="0" aria-label="调整参数栏宽度" aria-orientation="vertical"
          :aria-valuenow="panelWidth" :aria-valuemin="280" :aria-valuemax="maxPanelWidth()" @pointerdown="startResize" @keydown="resizeWithKeyboard" />
      </template>
    </aside>
    <main class="result-area">
      <div class="result-tabs"><div class="result-tab active" :title="resultTitle">{{ resultTitle }}</div></div>
      <div class="chart-placeholder" :aria-label="methodType + '产能对比结果分析图'"><div ref="chartEl" class="comparison-chart" /></div>
    </main>
  </div>
</template>

<style lang="scss" scoped>
// 沿用理论稳定流的视觉规范：300px 白色参数栏、34px 标题栏和黄黑交互色。
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
.chart-placeholder { flex: 1; min-height: 0; position: relative; overflow: hidden; }
.comparison-chart { width: 100%; height: 100%; }
</style>
