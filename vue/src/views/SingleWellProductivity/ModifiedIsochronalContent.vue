<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import { NODETYPE } from '@/constants/nodeType'
import { nodeApi, productivityEvaluationApi } from '@/api/docker'

const props = defineProps({
  projectId: { type: [Number, String], required: true },
  gasReservoirId: { type: [Number, String], required: true },
  wellName: { type: String, default: '' },
  pvtTableOptions: { type: Array, default: () => [] },
  dataTableOptions: { type: Array, default: () => [] },
  selectedPvtTable: { type: String, default: '' },
  selectedDataTable: { type: String, default: '' },
  maximumFormationPressure: { type: String, default: '56.34' },
  formationTemperature: { type: String, default: '120' },
  calculationMethod: { type: String, default: '拟压力' },
  calculationResult: { type: String, default: '二项式' }
})

const emit = defineEmits([
  'update:selectedPvtTable', 'update:selectedDataTable',
  'update:maximumFormationPressure', 'update:formationTemperature',
  'update:calculationMethod', 'update:calculationResult'
])

const loading = ref(false)
const loadError = ref('')
const resultData = ref(null)
const paramsCollapsed = ref(false)
const activePanelTab = ref('input')
const activeContentTab = ref('chart')
const chartEl = ref(null)
let chart = null

const METHOD_NODE_TYPES = {
  拟压力: NODETYPE.NodeType_ProductivityEvaluationByPseudoPressure,
  压力平方方法: NODETYPE.NodeType_ProductivityEvaluationByPressureSquared,
  压力法: NODETYPE.NodeType_ProductivityEvaluationByPressure
}

const inputItems = computed(() => Array.isArray(resultData.value?.inputItems) ? resultData.value.inputItems : [])
const fields = computed(() => Array.isArray(resultData.value?.fields) ? resultData.value.fields : [])
const chartItems = computed(() => Array.isArray(resultData.value?.chartItems) ? resultData.value.chartItems : [])
const iprChartItems = computed(() => Array.isArray(resultData.value?.iprChartItems) ? resultData.value.iprChartItems : [])
const output = computed(() => ({ ...(resultData.value?.output || {}), ...(resultData.value?.evaluation || {}) }))

const unwrapResult = response => {
  const payload = response?.data ?? response ?? {}
  return payload?.data && !payload.input && !payload.chartItems ? payload.data : payload
}

const getChildren = node => {
  for (const key of ['subNodes', 'children', 'nodes', 'analysisNodes', 'analyses']) {
    if (Array.isArray(node?.[key])) return node[key]
  }
  return []
}
const nodeTitle = node => String(node?.nodeTitle ?? node?.wellName ?? node?.name ?? node?.label ?? '').trim()
const primitiveId = value => {
  if (value === null || value === undefined || value === '') return null
  if (typeof value === 'object') return primitiveId(value.id ?? value.resultId ?? value.nodeId)
  return value
}

const resolveNodeIds = (response, targetMethodType = null) => {
  const payload = response?.data?.data ?? response?.data ?? response ?? {}
  // node 接口若直接返回 { id, result }，二者即结果接口末尾的两个 ID。
  const directResultId = primitiveId(payload?.result)
  if (directResultId !== null) {
    return { gasReservoirId: primitiveId(payload?.id) ?? props.gasReservoirId, resultId: directResultId }
  }

  const root = payload?.node ?? payload
  const candidates = []
  const visit = (node, currentWell = '', currentMethodType = null) => {
    if (!node || typeof node !== 'object') return
    const type = Number(node.nodeType ?? node.type)
    const title = nodeTitle(node)
    const nextWell = type === NODETYPE.NodeType_Well || title === props.wellName ? title : currentWell
    const nextMethodType = [
      NODETYPE.NodeType_ProductivityEvaluationByPressure,
      NODETYPE.NodeType_ProductivityEvaluationByPressureSquared,
      NODETYPE.NodeType_ProductivityEvaluationByPseudoPressure
    ].includes(type) ? type : currentMethodType
    if (type === NODETYPE.NodeType_ProductivityEvaluationModifiedIsochronalWellTest || title.includes('修正等时')) {
      candidates.push({ node, wellName: nextWell, methodType: nextMethodType })
    }
    getChildren(node).forEach(child => visit(child, nextWell, nextMethodType))
  }
  visit(root)

  const matched = candidates.find(item =>
    item.wellName === props.wellName &&
    (targetMethodType === null || item.methodType === targetMethodType)
  ) || candidates.find(item => targetMethodType === null || item.methodType === targetMethodType)
  if (!matched) throw new Error('node接口未返回当前压力形式的修正等时结果')
  const resultId = primitiveId(matched?.node?.resultId ?? matched?.node?.result ?? matched?.node?.nodeId ?? matched?.node?.id)
  if (resultId === null) throw new Error('node接口未返回修正等时结果ID')
  const gasReservoirId = primitiveId(
    matched?.node?.gasReservoirId ?? matched?.node?.reservoirId ?? payload?.gasReservoirId
  ) ?? props.gasReservoirId
  return { gasReservoirId, resultId }
}

const fieldMeta = name => fields.value.find(field => field?.name === name) || {}
const fieldUnit = name => fieldMeta(name).unit_label || fieldMeta(name).unitLabel || ''
const dataColumns = computed(() => [
  ['testPointNumber', '测点序号'], ['reserviorPressure', '地层/恢复压力'],
  ['testDailyGasProduction', '测试气产量'], ['testDailyOilProduction', '测试油产量'],
  ['testFlowPressure', '测试流压']
].filter(([key]) => inputItems.value.some(row => row?.[key] !== undefined)))

const scientific = value => {
  const number = Number(value)
  return Number.isFinite(number) ? number.toExponential(4).replace('e', 'E') : ''
}
const fixed = value => {
  const number = Number(value)
  return Number.isFinite(number) ? number.toFixed(4) : ''
}

// 输出面板严格限定为图2中的五项，禁止根据接口字段动态追加。
const outputValues = computed(() => ({
  evaluationTypeDesc: output.value.evaluationTypeDesc || '修正等时试井',
  darcySeepageCoefficient: scientific(output.value.darcySeepageCoefficient),
  nonDarcySeepageCoefficient: scientific(output.value.nonDarcySeepageCoefficient),
  rSquared: fixed(output.value.rSquared),
  reliabilityDesc: output.value.reliabilityDesc || ''
}))

const seriesName = field => ({
  regularizedPressure: '试井数据', linearRegressionPressure: '回归曲线',
  shiftLinearRegressionPressure: '平移回归曲线', stableRegularizedPressure: '稳定点'
}[field] || field)
const toSeries = (items, ipr = false) => items.map((item, index) => ({
  name: ipr ? `IPR曲线${index + 1}` : seriesName(item.yAxisField),
  type: item.yAxisField?.includes('Regression') ? 'line' : (ipr ? 'line' : 'scatter'),
  smooth: ipr,
  showSymbol: !item.yAxisField?.includes('Regression') || ipr,
  symbolSize: ipr ? 5 : 8,
  lineStyle: { width: item.yAxisField?.includes('Regression') ? 2 : 1.8 },
  data: (item.data || []).filter(point => !point?.isDeleted)
    .map(point => [Number(point.xValue), Number(point.yValue)])
    .filter(point => point.every(Number.isFinite))
}))
const axisUnit = (source, axis) => {
  const field = source[0]?.[axis === 'x' ? 'xAxisField' : 'yAxisField']
  const unit = fieldUnit(field)
  return unit ? `(${unit})` : ''
}

const renderChart = async () => {
  if (activeContentTab.value === 'table' || !chartEl.value) return
  await nextTick()
  if (!chart) chart = echarts.init(chartEl.value)
  const isIpr = activeContentTab.value === 'ipr'
  const source = isIpr ? iprChartItems.value : chartItems.value
  chart.setOption({
    animation: false,
    color: ['#e9b600', '#202020', '#ef6c00', '#4c84d4', '#62a55b'],
    title: { text: isIpr ? 'IPR曲线' : '修正等时试井分析图', left: 'center', top: 14,
      textStyle: { fontSize: 15, fontWeight: 600, color: '#303133' } },
    tooltip: { trigger: 'axis' }, legend: { top: 44, type: 'scroll' },
    grid: { left: 76, right: 34, top: 82, bottom: 62, containLabel: true },
    xAxis: { type: 'value', name: `qsc${axisUnit(source, 'x')}`, nameLocation: 'middle', nameGap: 36,
      splitLine: { lineStyle: { color: '#e5e8ec' } } },
    yAxis: { type: 'value', name: isIpr ? `地层压力${axisUnit(source, 'y')}` : `(ψws-ψwf)/qsc${axisUnit(source, 'y')}`,
      nameLocation: 'middle', nameGap: 52, splitLine: { lineStyle: { color: '#e5e8ec' } } },
    dataZoom: [{ type: 'inside', xAxisIndex: 0 }, { type: 'inside', yAxisIndex: 0 }],
    series: toSeries(source, isIpr)
  }, true)
  chart.resize()
}

const loadResult = async ({ validateSelection = false } = {}) => {
  if (validateSelection && !props.selectedPvtTable) return ElMessage.warning('请选择PVT表')
  if (validateSelection && !props.selectedDataTable) return ElMessage.warning('请选择数据表')
  loading.value = true
  loadError.value = ''
  try {
    const nodeType = METHOD_NODE_TYPES[props.calculationMethod]
    if (!nodeType) throw new Error('不支持的计算方法')
    // 与物质平衡一致：先取分析根节点，再按当前井、压力形式和修正等时类型寻找 result。
    // 兼容原平台只允许按压力形式读取子树的部署版本。
    let ids
    try {
      const rootResponse = await nodeApi.getNode(
        props.projectId,
        props.gasReservoirId,
        NODETYPE.NodeType_ProductivityEvaluation,
        { silentError: true }
      )
      ids = resolveNodeIds(rootResponse, nodeType)
    } catch (rootError) {
      const methodResponse = await nodeApi.getNode(
        props.projectId,
        props.gasReservoirId,
        nodeType,
        { silentError: true }
      )
      ids = resolveNodeIds(methodResponse, nodeType)
    }
    const response = await productivityEvaluationApi.getResult(
      props.projectId, ids.gasReservoirId, ids.resultId, { silentError: true }
    )
    resultData.value = unwrapResult(response)
    const resultInput = resultData.value?.input || {}
    if (resultInput.originalFormationPressure !== undefined) {
      emit('update:maximumFormationPressure', String(resultInput.originalFormationPressure))
    }
    if (resultInput.formationTemperature !== undefined) {
      emit('update:formationTemperature', String(resultInput.formationTemperature))
    }
    await renderChart()
  } catch (error) {
    resultData.value = null
    loadError.value = error.response?.status === 401
      ? '原平台登录状态已失效，登录原平台后可加载修正等时结果。'
      : (error.response?.data?.message || error.message || '修正等时结果加载失败')
  } finally { loading.value = false }
}

const toggleParamsPanel = () => { paramsCollapsed.value = !paramsCollapsed.value; nextTick(() => chart?.resize()) }
const handleResize = () => chart?.resize()
watch(activeContentTab, tab => { if (tab !== 'table') renderChart() })
watch(() => [props.projectId, props.gasReservoirId, props.wellName], () => loadResult())
onMounted(() => { window.addEventListener('resize', handleResize); loadResult() })
onBeforeUnmount(() => { window.removeEventListener('resize', handleResize); chart?.dispose(); chart = null })
</script>

<template>
  <section v-loading="loading" class="modified-workspace">
    <aside class="params-panel" :class="{ collapsed: paramsCollapsed }">
      <div v-if="paramsCollapsed" class="panel-collapsed-tab" @click="toggleParamsPanel">参数设置</div>
      <template v-else>
        <div class="panel-head">
          <span>参数设置</span>
          <button class="panel-toggle" type="button" title="收起参数设置" @click="toggleParamsPanel">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="#777"><path d="M16,12V4H17V2H7V4H8V12L6,14V16H11.2V22H12.8V16H18V14L16,12Z" /></svg>
          </button>
        </div>

        <!-- 输入内容严格对应图1。 -->
        <div v-show="activePanelTab === 'input'" class="panel-body">
          <label class="input-field">
            <span>选择PVT表</span>
            <select :value="selectedPvtTable" @change="emit('update:selectedPvtTable', $event.target.value)">
              <option value="">请选择</option>
              <option v-for="option in pvtTableOptions" :key="option" :value="option">{{ option }}</option>
            </select>
          </label>
          <label class="input-field">
            <span>选择数据表</span>
            <select :value="selectedDataTable" @change="emit('update:selectedDataTable', $event.target.value)">
              <option value="">请选择</option>
              <option v-for="option in dataTableOptions" :key="option" :value="option">{{ option }}</option>
            </select>
          </label>
          <div class="other-title"><span>其他数据</span><i></i></div>
          <label class="input-field">
            <span>计算IPR曲线的最大地层压力（MPa）</span>
            <input :value="maximumFormationPressure" inputmode="decimal" @input="emit('update:maximumFormationPressure', $event.target.value)" />
          </label>
          <label class="input-field">
            <span>地层温度（℃）</span>
            <input :value="formationTemperature" inputmode="decimal" @input="emit('update:formationTemperature', $event.target.value)" />
          </label>
          <fieldset class="radio-section">
            <legend>计算方法</legend>
            <label><input :checked="calculationMethod === '拟压力'" type="radio" @change="emit('update:calculationMethod', '拟压力')" />拟压力</label>
            <label><input :checked="calculationMethod === '压力平方方法'" type="radio" @change="emit('update:calculationMethod', '压力平方方法')" />压力平方方法</label>
            <label><input :checked="calculationMethod === '压力法'" type="radio" @change="emit('update:calculationMethod', '压力法')" />压力法</label>
          </fieldset>
          <fieldset class="radio-section result-radio-section">
            <legend>计算结果</legend>
            <label><input :checked="calculationResult === '二项式'" type="radio" @change="emit('update:calculationResult', '二项式')" />二项式</label>
            <label><input :checked="calculationResult === '指数式'" type="radio" @change="emit('update:calculationResult', '指数式')" />指数式</label>
          </fieldset>
          <button class="calculate-button" type="button" @click="loadResult({ validateSelection: true })">计算</button>
        </div>

        <!-- 输出内容严格对应图2，仅五项。 -->
        <div v-show="activePanelTab === 'output'" class="panel-body">
          <div class="section-title">输出结果</div>
          <div class="field-grid">
            <div class="field"><label>产能评价方法</label><el-input size="small" readonly :model-value="outputValues.evaluationTypeDesc" /></div>
            <div class="field"><label>达西渗流项系数 A[(MPa²/(mPa·s))/(10⁴m³/d)]</label><el-input size="small" readonly :model-value="outputValues.darcySeepageCoefficient" /></div>
            <div class="field"><label>非达西渗流项系数 B[(MPa²/(mPa·s))/(10⁴m³/d)²]</label><el-input size="small" readonly :model-value="outputValues.nonDarcySeepageCoefficient" /></div>
            <div class="field"><label>R²(dless)</label><el-input size="small" readonly :model-value="outputValues.rSquared" /></div>
            <div class="field"><label>结果可靠性</label><el-input size="small" readonly :model-value="outputValues.reliabilityDesc" /></div>
          </div>
        </div>

        <div class="panel-tabs">
          <button :class="{ active: activePanelTab === 'input' }" @click="activePanelTab = 'input'">输入</button>
          <button :class="{ active: activePanelTab === 'output' }" @click="activePanelTab = 'output'">输出</button>
        </div>
      </template>
    </aside>

    <main class="chart-area">
      <div v-if="loadError" class="load-error"><span>{{ loadError }}</span><el-button size="small" @click="loadResult()">重新加载</el-button></div>
      <div v-show="activeContentTab === 'table'" class="data-list-panel">
        <el-table :data="inputItems" size="small" height="100%" border stripe empty-text="暂无产能测试数据">
          <el-table-column v-for="([key, label]) in dataColumns" :key="key" :prop="key"
            :label="fieldUnit(key) ? `${label}(${fieldUnit(key)})` : label" min-width="140" />
        </el-table>
      </div>
      <div v-show="activeContentTab !== 'table'" ref="chartEl" class="chart"></div>
      <div class="chart-tabs">
        <button type="button" class="chart-tab" :class="{ active: activeContentTab === 'table' }" @click="activeContentTab = 'table'">数据列表</button>
        <button type="button" class="chart-tab" :class="{ active: activeContentTab === 'chart' }" @click="activeContentTab = 'chart'">结果分析图</button>
        <button type="button" class="chart-tab" :class="{ active: activeContentTab === 'ipr' }" @click="activeContentTab = 'ipr'">IPR曲线</button>
      </div>
    </main>
  </section>
</template>

<style lang="scss" scoped>
.modified-workspace { display:flex; flex:1; min-height:0; overflow:hidden; background:#fff; }
.params-panel {
  width:238px; min-width:238px; display:flex; flex-direction:column; position:relative;
  border-right:1px solid #e0e0e0; overflow:hidden; transition:width .16s ease,min-width .16s ease;
  &.collapsed { width:22px; min-width:22px; background:transparent; border-right:0; }
}
.panel-head {
  display:flex; justify-content:space-between; align-items:center; padding:7px 12px 6px;
  border-bottom:1px solid #f0f0f0; flex-shrink:0; font-size:13px; color:#333;
}
.panel-toggle {
  width:20px; height:20px; padding:0; border:0; background:transparent; display:flex;
  align-items:center; justify-content:center; cursor:pointer; border-radius:2px;
  &:hover { background:#eef4ff; }
}
.panel-collapsed-tab {
  width:22px; height:76px; display:flex; align-items:center; justify-content:center;
  writing-mode:vertical-rl; text-orientation:mixed; font-size:13px; color:#333; cursor:pointer;
  background:#fff; border:1px solid #e0e0e0; border-left:0;
}
.panel-body { flex:1; min-height:0; overflow-y:auto; padding:4px 12px 14px; }
.input-field {
  display:block; margin-bottom:9px; color:#333;
  > span { display:block; margin-bottom:3px; font-size:12px; line-height:18px; }
  select,input {
    width:100%; height:26px; padding:0 8px; border:1px solid #bfc3c8; border-radius:3px;
    background:#fff; box-sizing:border-box; color:#333; font:inherit; font-size:13px; outline:none;
  }
}
.other-title {
  display:flex; align-items:center; gap:8px; height:22px; margin:8px 0 7px; font-size:13px;
  i { flex:1; height:1px; background:#999; }
}
.radio-section {
  margin:0 0 9px; padding:0; border:0;
  legend { margin-bottom:7px; padding:0; color:#333; font-size:13px; }
  label { display:inline-flex; align-items:center; gap:3px; margin-right:9px; color:#555; font-size:13px; white-space:nowrap; cursor:pointer; }
  input { width:13px; height:13px; margin:0; accent-color:#303133; }
}
.result-radio-section { margin-bottom:9px; }
.calculate-button { min-width:64px; height:27px; padding:0 18px; border:0; border-radius:4px; background:#050505; color:#fff; font-size:13px; font-weight:700; cursor:pointer; }
.section-title {
  font-weight:500; color:#333; font-size:13px; margin:10px 0 7px;
  &:first-child { margin-top:4px; }
}
.field-grid { display:grid; grid-template-columns:repeat(auto-fit,minmax(190px,1fr)); column-gap:24px; }
.field {
  margin-bottom:9px;
  label { display:block; margin-bottom:3px; color:#555; font-size:12px; }
}
/* 与物质平衡左侧页签完全一致。 */
.panel-tabs {
  height:36px; display:flex; border-top:1px solid #e0e0e0; flex-shrink:0;
  button {
    flex:1; border:0; border-right:1px solid #e0e0e0; background:#fff; color:#333; font-size:14px; cursor:pointer;
    &:last-child { border-right:0; }
    &.active { background:#f4d000; color:#111; font-weight:600; }
  }
}
.chart-area { position:relative; flex:1; min-width:0; min-height:0; display:flex; flex-direction:column; background:#fff; overflow:hidden; }
.chart,.data-list-panel { flex:1; min-height:0; width:100%; }
.data-list-panel { overflow:hidden; }
/* 与物质平衡右侧页签完全一致。 */
.chart-tabs { display:flex; height:34px; border-top:1px solid #e4e7ed; flex-shrink:0; background:#fafafa; }
.chart-tab {
  border:0; border-right:1px solid #e4e7ed; background:transparent; padding:0 16px; color:#555;
  cursor:pointer; border-bottom:2px solid transparent; white-space:nowrap;
  &:hover { color:#409eff; }
  &.active { color:#409eff; border-bottom-color:#409eff; background:#fff; font-weight:600; }
}
.load-error {
  position:absolute; top:50%; left:50%; z-index:4; width:min(440px,calc(100% - 48px)); padding:18px;
  border:1px solid #ead48a; border-radius:4px; background:#fff9df; color:#66520b; text-align:center;
  transform:translate(-50%,-50%); .el-button { margin-left:10px; }
}
</style>
