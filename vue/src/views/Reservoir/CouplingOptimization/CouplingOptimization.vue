<script setup>
/**
 * 库 → 协同预测 → 一体化耦合优化 → 单目标 / 多目标
 *
 * 调用原平台「动态预测-井组动态预测」（经 /docker-api 代理到 9920）：
 *  1. 左侧勾选本库成员井（与原平台一致，井组至少 2 口）；
 *  2. 点击「启动预测」：若当前会话尚未创建任务，先按面板名称创建井组任务
 *     （dynamicPredictionType=2），再从任务详情回填参数面板（各井参数由平台自动带出）；
 *  3. 用面板参数组装 reservoirInput 提交 Calc，轮询 /result/wells/chart 与计算前指纹
 *     比对，确认出现本次计算的新曲线后绘图；
 *  4. 右侧只展示原平台输出的气产量与累产气量两条曲线（双 Y 轴）。
 *
 * 单目标 = 优化目标只有目标产量；多目标 = 额外提供「井口外输压力」输入框
 * （原平台 reservoirInput.externalWellheadPressure），其余实现完全一致。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import { storageCatalogApi } from '@/api/storageCatalog'
import { numeric, optionalNumber } from '@/api/solutionGeneration'
import { predictionResultApi } from '@/api/predictionResult'
import {
  PRODUCTIVITY_EVALUATION_FORMS,
  buildPredictionResultPayload,
  buildWellGroupCalcRequest,
  evaluateTargetDemand,
  fingerprintWellGroupPoints,
  normalizeWellGroupChart,
  predictionStartTimeToDateText,
  wellGroupPredictionApi
} from '@/api/wellGroupPrediction'
import { pendingCouplingNewPrediction, workspaceActiveNodeId, workspaceTreeData } from '@/utils/workspaceTreeState'
import {
  listCouplingRecords,
  upsertCouplingOptimizationRecordNode
} from '@/utils/couplingOptimizationTree'

const props = defineProps({
  projectId: { type: [Number, String], required: true },
  gasReservoirId: { type: [Number, String], required: true },
  storageId: { type: [Number, String], default: null },
  storageName: { type: String, default: '' },
  // 菜单命令名：单目标 / 多目标；多目标时增加井口外输压力输入。
  mode: { type: String, default: '单目标' },
  // 从「一体化耦合优化-单目标/多目标」目录下的预测记录打开时携带的原平台任务编号。
  predictionId: { type: [Number, String], default: null }
})

const isMultiObjective = computed(() => props.mode === '多目标')

/* ===== 井列表 ===== */
const storageWells = ref([])
const selectedWellNames = ref([])
const wellKeyword = ref('')
const wellError = ref('')
const loadingWells = ref(false)
const filteredWells = computed(() => storageWells.value.filter(well =>
  well.wellName.toLowerCase().includes(wellKeyword.value.trim().toLowerCase())))
const allWellsSelected = computed(() => filteredWells.value.length > 0
  && filteredWells.value.every(well => selectedWellNames.value.includes(well.wellName)))
const someWellsSelected = computed(() => !allWellsSelected.value
  && filteredWells.value.some(well => selectedWellNames.value.includes(well.wellName)))
const toggleAllWells = event => {
  const selected = new Set(selectedWellNames.value)
  filteredWells.value.forEach(well =>
    event.target.checked ? selected.add(well.wellName) : selected.delete(well.wellName))
  selectedWellNames.value = [...selected]
}
let wellVersion = 0
const errorText = error => error?.response?.data?.msg || error?.response?.data?.message ||
  error?.msg || error?.message || '请求失败'
const unwrap = response => response?.data ?? response
// 面板输入委托：井列表勾选与目标量判定区不算"平台参数编辑"，其余输入（含名称）标记为已编辑。
const onPanelInput = event => {
  if (event.target?.closest?.('.wells-section') || event.target?.closest?.('.target-check')) return
  panelTouched.value = true
}

/* ===== 预测任务与参数 ===== */
const taskName = ref('')
const productivityForm = ref(1)
// currentTask：本页面会话内已创建的原平台井组任务；刷新页面后重新创建。
// wellNames 记录创建任务时提交的井集合，用于判断井选择变化后是否需要新任务。
const currentTask = ref(null)
// pendingDetail：最近一次任务详情（含未在面板展示的字段与各井输入），重算时原样带回。
const pendingDetail = ref(null)
// preparing：进页面后自动创建任务并读取平台参数的进行中状态。
const preparing = ref(false)
// panelTouched：面板参数是否被用户改过；改过后重新拉取的平台默认值不再覆盖用户编辑。
const panelTouched = ref(false)
let prepareVersion = 0

// 面板可编辑字段（字符串存储，提交前数值校验）；未展示字段由任务详情原样带回。
const panel = reactive({
  targetProduction: '',
  externalWellheadPressure: '',
  predictionStartTime: '',
  predictedTimeStep: '',
  predictedTimeStepNumber: '',
  economicLimitGasProduction: '',
  specificGravity: '',
  hydrogenSulfide: '',
  carbonDioxide: '',
  nitrogen: '',
  originalFormationPressure: '',
  formationTemperature: '',
  waterSaturation: '',
  rockCompressionCoefficient: '',
  waterCompressionCoefficient: ''
})

const busy = ref(false)
const statusText = ref('')
const errorTextState = ref('')
// 目标需求量（人工输入，判定用；不随计算请求提交）。单位与图表右轴一致（10⁸m³）。
const targetDemand = ref('')
// 目标量判定：计算量 = 预测期首尾累计气产量差，与目标需求量比较；无曲线时不判定。
const targetCheck = computed(() => evaluateTargetDemand(chartPoints.value, targetDemand.value))

/* ===== 图表 ===== */
const chartEl = ref(null)
let chart = null
const chartPoints = ref([])
const chartTitle = computed(() => [props.storageName, '协同预测', '一体化耦合优化', props.mode, '分析结果']
  .filter(Boolean).join('-'))
const chartEmptyText = computed(() => {
  if (busy.value) return statusText.value || '正在计算…'
  if (!chartPoints.value.length) return '请选择井并启动预测'
  return ''
})
const renderNumber = value => {
  const number = optionalNumber(value)
  return number === null ? '—' : number.toLocaleString('zh-CN', { maximumFractionDigits: 4 })
}
const escapeHtml = value => String(value).replace(/[&<>"']/g, letter => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
})[letter])

const renderChart = () => {
  if (!chartEl.value) return
  chart ??= echarts.init(chartEl.value)
  const points = chartPoints.value
  if (!points.length) {
    chart.clear()
    chart.setOption({
      title: {
        text: chartEmptyText.value, left: 'center', top: 'middle',
        textStyle: { fontSize: 14, fontWeight: 400, color: '#909399' }
      }
    }, { notMerge: true })
    chartEl.value.setAttribute('aria-label', '预测曲线：暂无')
    return
  }
  const colors = { gas: '#e9c400', cumulative: '#3c86d3', formation: '#c7a747', recovery: '#269c71' }
  const units = { '气产量': '10⁴m³/d', '累产气量': '10⁸m³', '地层压力': 'MPa', '采出程度': '%' }
  // 多目标输出与原平台「井组动态预测」一致：气产量、累产气量、地层压力、采出程度。
  // 平台未返回的序列跳过绘制；单目标只展示气产量与累产气量。
  const hasFormation = isMultiObjective.value && points.some(point => point.formation !== null)
  const hasRecovery = isMultiObjective.value && points.some(point => point.recovery !== null)
  const axes = [{
    type: 'value', name: '气产量(10⁴m³/d)', position: 'left',
    axisLine: { show: true },
    splitLine: { show: true, lineStyle: { color: '#e6ecf2' } }
  }]
  let formationAxis = -1
  if (hasFormation) {
    formationAxis = axes.length
    axes.push({
      type: 'value', name: '地层压力(MPa)', position: 'left', offset: 58,
      axisLine: { show: true }, splitLine: { show: false }
    })
  }
  const cumulativeAxis = axes.length
  axes.push({
    type: 'value', name: '累产气量(10⁸m³)', position: 'right',
    axisLine: { show: true }, splitLine: { show: false }
  })
  let recoveryAxis = -1
  if (hasRecovery) {
    recoveryAxis = axes.length
    axes.push({
      type: 'value', name: '采出程度(%)', position: 'right', offset: 58,
      axisLine: { show: true }, splitLine: { show: false }
    })
  }
  const makeSeries = (name, axisIndex, field, color, width) => ({
    name, type: 'line', yAxisIndex: axisIndex,
    data: points.map(point => [point.date, point[field]]),
    showSymbol: false, connectNulls: false, animation: false,
    itemStyle: { color }, lineStyle: { color, width }
  })
  const series = [
    makeSeries('气产量', 0, 'gas', colors.gas, 2.1),
    ...(formationAxis >= 0
      ? [makeSeries('地层压力', formationAxis, 'formation', colors.formation, 2.1)] : []),
    makeSeries('累产气量', cumulativeAxis, 'cumulative', colors.cumulative, 2.3),
    ...(recoveryAxis >= 0
      ? [makeSeries('采出程度', recoveryAxis, 'recovery', colors.recovery, 2.1)] : [])
  ]
  chart.setOption({
    title: {
      text: `${props.storageName || ''} 井组动态预测（${props.mode}）`.trim(),
      left: 'center', top: 12, textStyle: { fontSize: 16, fontWeight: 600 }
    },
    legend: { top: 45, data: series.map(item => item.name), icon: 'circle', itemWidth: 11, itemHeight: 9 },
    tooltip: {
      trigger: 'axis', confine: true, axisPointer: { type: 'cross' },
      formatter: items => {
        const date = escapeHtml(items?.[0]?.data?.[0] ?? '')
        return `<strong>${date}</strong>` + (items || []).map(item =>
          `<div>${item.marker} ${escapeHtml(item.seriesName)}：` +
          `${renderNumber(item.data?.[1])} ${units[item.seriesName] || ''}</div>`).join('')
      }
    },
    grid: { left: hasFormation ? 152 : 96, right: hasRecovery ? 156 : 100, top: 91, bottom: 52 },
    dataZoom: [{ type: 'inside', xAxisIndex: 0 }],
    xAxis: {
      type: 'time', name: '日期', nameLocation: 'middle', nameGap: 43,
      axisLabel: { hideOverlap: true, formatter: '{yyyy}' },
      splitLine: { show: true, lineStyle: { color: '#e6ecf2' } }
    },
    yAxis: axes,
    series
  }, { notMerge: true })
  // 无障碍标签同时用于自动化测试断言当前绘制的指标集合。
  chartEl.value.setAttribute('aria-label', `预测曲线：${series.map(item => item.name).join('、')}`)
  chart.resize()
}

/* ===== 面板宽度与折叠（与库级产能对比、井间对比一致） ===== */
const paramsCollapsed = ref(false)
const panelWidth = ref(258)
const workspaceEl = ref(null)
let resizeObserver = null
let resizeFrame = 0
const scheduleResize = () => {
  if (resizeFrame) cancelAnimationFrame(resizeFrame)
  resizeFrame = requestAnimationFrame(() => {
    resizeFrame = 0
    if (panelWidth.value > maxPanelWidth()) setPanelWidth(panelWidth.value)
    chart?.resize()
  })
}
let dragStartX = 0
let dragStartWidth = 0
const maxPanelWidth = () => Math.max(258, Math.min(560, (workspaceEl.value?.clientWidth || 780) - 260))
const setPanelWidth = width => { panelWidth.value = Math.max(258, Math.min(maxPanelWidth(), width)) }
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
  setPanelWidth(event.key === 'Home' ? 258 : event.key === 'End' ? maxPanelWidth()
    : panelWidth.value + (event.key === 'ArrowRight' ? 20 : -20))
}

/* ===== 数据加载与重置 ===== */
let runVersion = 0
const clearResult = () => {
  runVersion += 1
  chartPoints.value = []
  statusText.value = ''
  errorTextState.value = ''
  renderChart()
}
const loadWells = async () => {
  const version = ++wellVersion
  storageWells.value = []
  selectedWellNames.value = []
  wellError.value = ''
  clearResult()
  if (![props.projectId, props.gasReservoirId, props.storageId].every(id => Number(id) > 0)) {
    wellError.value = '请先选择具体储气库'
    loadingWells.value = false
    return
  }
  loadingWells.value = true
  try {
    const data = unwrap(await storageCatalogApi.wells(props.storageId, props.projectId, props.gasReservoirId))
    if (version !== wellVersion) return
    if (!Array.isArray(data)) throw new Error('单井列表格式不正确')
    storageWells.value = data.map(well => ({
      id: Number(well.id),
      wellName: String(well.wellName || '')
    })).filter(well => well.wellName)
    // 与库级产能对比一致：库成员井默认全部参与，可再逐井取消。
    selectedWellNames.value = storageWells.value.map(well => well.wellName)
  } catch (error) {
    if (version === wellVersion) wellError.value = errorText(error)
  } finally {
    if (version === wellVersion) loadingWells.value = false
  }
}
// 取平台顺延的默认名称（如"井组动态预测 8"，平台按已存在任务自动编号）；失败返回空串由调用方兜底。
const fetchDefaultName = async () => {
  try {
    const payload = unwrap(await wellGroupPredictionApi.getDefaultName(props.projectId, props.gasReservoirId))
    return String(payload?.predictionName || '').trim()
  } catch (error) {
    return ''
  }
}
const loadDefaultName = async () => {
  if (![props.projectId, props.gasReservoirId].every(id => Number(id) > 0)) return
  const name = await fetchDefaultName()
  if (name && !currentTask.value) taskName.value = name
  else if (!taskName.value) taskName.value = '井组动态预测1'
}

/**
 * 创建井组任务；名称被平台占用时自动换用平台顺延名称重试一次。
 * 原平台对动态预测任务的唯一约束是「项目+气藏+名称+类型」——同名任务会返回"分析已存在"，
 * 常见于"改井选择后重建任务"复用本会话旧名称的场景。
 */
const createTaskWithFreshName = async (preferredName, wellNames) => {
  const attempt = name => wellGroupPredictionApi.createTask({
    projectId: props.projectId,
    gasReservoirId: props.gasReservoirId,
    wellNames,
    name,
    productivityEvaluationForm: productivityForm.value
  })
  try {
    return unwrap(await attempt(preferredName))
  } catch (error) {
    if (!/已存在/.test(reportError(error))) throw error
    const fresh = await fetchDefaultName()
    if (!fresh || fresh === preferredName) throw error
    taskName.value = fresh
    return unwrap(await attempt(fresh))
  }
}

function resetPanel() {
  for (const key of Object.keys(panel)) panel[key] = ''
}
function fillPanelFromDetail(detailPayload) {
  const reservoirInput = detailPayload?.reservoirInput || {}
  const read = key => {
    const value = reservoirInput[key]
    return value === undefined || value === null ? '' : String(value)
  }
  panel.targetProduction = read('targetProduction')
  panel.externalWellheadPressure = read('externalWellheadPressure')
  panel.predictionStartTime = predictionStartTimeToDateText(reservoirInput.predictionStartTime)
  panel.predictedTimeStep = read('predictedTimeStep')
  panel.predictedTimeStepNumber = read('predictedTimeStepNumber')
  panel.economicLimitGasProduction = read('economicLimitGasProduction')
  panel.specificGravity = read('specificGravity')
  panel.hydrogenSulfide = read('hydrogenSulfide')
  panel.carbonDioxide = read('carbonDioxide')
  panel.nitrogen = read('nitrogen')
  panel.originalFormationPressure = read('originalFormationPressure')
  panel.formationTemperature = read('formationTemperature')
  panel.waterSaturation = read('waterSaturation')
  panel.rockCompressionCoefficient = read('rockCompressionCoefficient')
  panel.waterCompressionCoefficient = read('waterCompressionCoefficient')
}

/**
 * 载入平台上已有任务：同步井勾选、回填参数、显示该任务平台上已有的计算结果（不重新计算），
 * 并把记录归入当前模式的目录（本机归属 + 目录节点).
 * 返回 { hasChart }：平台没有该任务的计算结果时为 false。
 */
const loadExistingChart = async predictionId => {
  try {
    const parsed = normalizeWellGroupChart(
      await wellGroupPredictionApi.getChart(props.projectId, props.gasReservoirId, predictionId),
      { dynamicPredictionId: predictionId })
    chartPoints.value = parsed.points
    await nextTick()
    renderChart()
    return true
  } catch (error) {
    chartPoints.value = []
    renderChart()
    return false
  }
}

// 预测结果留存成功后把记录插入目录（目录已加载时立即可见），并高亮到该记录。
// 目录数据源是留存表：只有留存过结果的预测才作为记录出现；新建任务本身不进目录。
const registerCouplingRecord = (predictionId, name) => {
  const nodeId = upsertCouplingOptimizationRecordNode({
    treeData: workspaceTreeData,
    projectId: props.projectId,
    gasReservoirId: props.gasReservoirId,
    storageId: props.storageId,
    mode: props.mode,
    record: { predictionId: Number(predictionId), predictionName: name }
  })
  if (nodeId) workspaceActiveNodeId.value = nodeId
}

// 预测完成后把本次结果留存到本项目数据库（project_prediction_result 表）；
// 失败只提示不阻断展示，用户可再次启动预测重试（同一任务按覆盖保存）。历史任务不迁移。
const persistPredictionResult = async ({ task, calcRequest, points }) => {
  const payload = buildPredictionResultPayload({
    scope: { projectId: props.projectId, gasReservoirId: props.gasReservoirId, storageId: props.storageId },
    task,
    mode: props.mode,
    wellNames: task?.wellNames,
    points,
    request: calcRequest
  })
  if (!payload) return
  try {
    await predictionResultApi.save(payload)
    // 留存成功即成为一条"预测记录"：插入目录并高亮（目录数据源=留存表）。
    registerCouplingRecord(task.dynamicPredictionId, task.name)
  } catch (error) {
    console.warn('[一体化耦合优化] 预测结果留存失败', error)
    ElMessage.warning('预测结果留存到本项目数据库失败（不影响本次展示）；可再次启动预测重试')
  }
}

// 「新建预测」动作节点的一次性请求：属于当前库与模式才消费。
const consumePendingNewPrediction = () => {
  const request = pendingCouplingNewPrediction.value
  if (!request || request.mode !== props.mode ||
      Number(request.projectId) !== Number(props.projectId) ||
      Number(request.gasReservoirId) !== Number(props.gasReservoirId) ||
      Number(request.storageId) !== Number(props.storageId)) return false
  pendingCouplingNewPrediction.value = null
  return true
}

const loadExistingTask = async (task, version) => {
  const detail = unwrap(await wellGroupPredictionApi.getDetail(
    props.projectId, props.gasReservoirId, task.dynamicPredictionId))
  if (version !== prepareVersion) return { hasChart: false }
  // 把井勾选同步为任务实际包含的成员井，避免面板井集与任务不一致。
  const taskWellNames = Array.isArray(detail?.wellInputs)
    ? [...new Set(detail.wellInputs.map(item => String(item?.wellName || '').trim()).filter(Boolean))]
    : []
  if (taskWellNames.length) {
    const memberNames = new Set(storageWells.value.map(well => well.wellName))
    const checked = taskWellNames.filter(wellName => memberNames.has(wellName))
    if (checked.length) selectedWellNames.value = checked
  }
  taskName.value = task.name
  currentTask.value = {
    dynamicPredictionId: task.dynamicPredictionId,
    name: task.name,
    wellNames: [...selectedWellNames.value]
  }
  pendingDetail.value = detail
  if (!panelTouched.value) fillPanelFromDetail(detail)
  const hasChart = await loadExistingChart(task.dynamicPredictionId)
  return { hasChart }
}

/**
 * 进页面（默认名称与成员井就绪）后自动准备：
 *  1. 从预测记录打开（predictionId）→ 载入该记录（参数 + 已有曲线）；
 *  2. 否则复用归属为当前模式的最新记录（单目标/多目标各自维护记录集合）；
 *  3. 本模式还没有记录时才新建任务（成功后立即归入本模式目录）。
 * 所有路径都把平台带出的参数回填面板，用户只需修改要调整的参数即可启动预测。
 */
const ensureTaskPrepared = async () => {
  if (currentTask.value || preparing.value || busy.value) return
  const wellNames = [...selectedWellNames.value]
  if (!props.predictionId) {
    if (wellNames.length < 2) {
      if (!statusText.value) statusText.value = '请至少选择 2 口井；任务与平台参数会自动准备'
      return
    }
    if (!taskName.value.trim()) return
  }
  const version = ++prepareVersion
  preparing.value = true
  try {
    // 目录里点了「新建预测」：跳过复用，直接按平台顺延名称创建（刷新/跨页面前来时在此消费）。
    const wantsNew = consumePendingNewPrediction()
    if (!wantsNew) {
      // 记录数据源 = 本项目留存表：只有留存过结果的预测才是记录。
      statusText.value = props.predictionId ? '正在读取预测记录与平台参数…' : '正在读取本模式的预测记录…'
      const records = await listCouplingRecords(props.projectId, props.gasReservoirId, props.storageId, props.mode)
      if (version !== prepareVersion) return

      // 从记录打开：按任务编号精确载入。
      if (props.predictionId) {
        const record = records.find(item => String(item.predictionId) === String(props.predictionId))
        if (!record) throw new Error(`预测记录 ${props.predictionId} 不存在（可能已被删除）；请刷新目录后重试`)
        const target = { dynamicPredictionId: Number(record.predictionId), name: record.predictionName }
        const { hasChart } = await loadExistingTask(target, version)
        if (version !== prepareVersion) return
        statusText.value = hasChart
          ? `已载入预测记录「${target.name}」，参数与平台上已有结果已带出`
          : `已载入预测记录「${target.name}」，该记录暂无计算结果，可调整参数后启动预测`
        return
      }

      // 复用本模式最近留存的记录（列表按最近保存在前）。
      if (records.length) {
        const latest = records[0]
        const target = { dynamicPredictionId: Number(latest.predictionId), name: latest.predictionName }
        const { hasChart } = await loadExistingTask(target, version)
        if (version !== prepareVersion) return
        statusText.value = hasChart
          ? `已载入本模式最近的预测记录「${target.name}」，参数与已有结果已带出，可直接修改后启动预测`
          : `已载入本模式最近的预测记录「${target.name}」，参数已带出，可直接修改后启动预测`
        return
      }

      // 还没有留存记录：不自动创建任务（避免刷新堆积），引导从目录开始。
      // 历史任务不迁移——平台上的旧任务不会出现在记录列表里。
      statusText.value = '当前还没有本模式的预测记录；点击左侧目录「新建预测」创建第一条'
      return
    }

    const name = taskName.value.trim()
    statusText.value = '正在新建井组动态预测任务…'
    // 新建优先用平台顺延名称；失败时沿用面板名称。
    const preferredName = (await fetchDefaultName()) || name
    const created = await createTaskWithFreshName(preferredName, wellNames)
    if (version !== prepareVersion) return
    const id = Number(created?.dynamicPredictionId)
    if (!Number.isSafeInteger(id) || id <= 0) throw new Error('创建井组任务没有返回有效的 dynamicPredictionId')
    taskName.value = String(created?.dynamicPredictionName || preferredName).trim()
    currentTask.value = { dynamicPredictionId: id, name: taskName.value, wellNames }
    const detail = unwrap(await wellGroupPredictionApi.getDetail(props.projectId, props.gasReservoirId, id))
    if (version !== prepareVersion) return
    pendingDetail.value = detail
    if (!panelTouched.value) fillPanelFromDetail(detail)
    statusText.value = `已新建预测「${taskName.value}」，参数已从平台带出；启动预测并留存成功后才会出现在左侧记录目录`
  } catch (error) {
    if (version !== prepareVersion) return
    // 准备失败不阻断页面；点击「启动预测」时会重试。
    statusText.value = `平台参数准备失败：${reportError(error)}；点击「启动预测」将重试`
  } finally {
    if (version === prepareVersion) preparing.value = false
  }
}

/**
 * 「新建预测」：取平台顺延名称（如已有 12 条则为"井组动态预测 13"）按当前井选择创建新任务，
 * 立即归入当前模式目录；当前记录保留在目录中。参数面板：用户改过的值保留，未改过的回填新任务平台默认值。
 */
async function startNewPrediction() {
  if (busy.value || preparing.value || loadingWells.value) return
  clearResult()
  const version = ++runVersion
  errorTextState.value = ''
  preparing.value = true
  try {
    const wellNames = [...selectedWellNames.value]
    if (wellNames.length < 2) throw new Error('井组动态预测至少选择 2 口井')
    statusText.value = '正在新建井组动态预测任务…'
    const fresh = await fetchDefaultName()
    const created = await createTaskWithFreshName(
      fresh || taskName.value.trim() || `${props.storageName || ''}井组动态预测`, wellNames)
    if (version !== runVersion) return
    const id = Number(created?.dynamicPredictionId)
    if (!Number.isSafeInteger(id) || id <= 0) throw new Error('创建井组任务没有返回有效的 dynamicPredictionId')
    taskName.value = String(created?.dynamicPredictionName || fresh).trim()
    currentTask.value = { dynamicPredictionId: id, name: taskName.value, wellNames }
    pendingDetail.value = null
    const detail = unwrap(await wellGroupPredictionApi.getDetail(props.projectId, props.gasReservoirId, id))
    if (version !== runVersion) return
    pendingDetail.value = detail
    if (!panelTouched.value) fillPanelFromDetail(detail)
    statusText.value = `已新建预测「${taskName.value}」，参数已从平台带出；启动预测并留存成功后才会出现在左侧记录目录`
  } catch (error) {
    if (version !== runVersion) return
    errorTextState.value = reportError(error)
    statusText.value = ''
    ElMessage.error(errorTextState.value)
  } finally {
    if (version === runVersion) preparing.value = false
  }
}

// 已在本页面上点击目录里的「新建预测」节点：立即发起新建；忙碌时丢弃该请求（动作即时语义）。
// 跨页面进入（组件尚未挂载）的场景由 ensureTaskPrepared 统一消费。
watch(() => pendingCouplingNewPrediction.value, () => {
  if (!consumePendingNewPrediction()) return
  if (loadingWells.value || preparing.value || busy.value) return
  void startNewPrediction()
})

// 切库/切模式/从记录打开时重置全部状态并重新准备；组件按 key 重建，本 watch 主要覆盖
// 同实例内的 scope 变化（如刷新时先拿到部分 query 再补齐）。
watch(() => [props.projectId, props.gasReservoirId, props.storageId, props.mode, props.predictionId], async () => {
  prepareVersion += 1
  wellKeyword.value = ''
  taskName.value = ''
  productivityForm.value = 1
  currentTask.value = null
  pendingDetail.value = null
  panelTouched.value = false
  resetPanel()
  // 从记录打开时名称由记录覆盖，不需要预取默认名称。
  await Promise.all([loadWells(), props.predictionId ? Promise.resolve() : loadDefaultName()])
  await ensureTaskPrepared()
}, { immediate: true })

onMounted(async () => {
  await nextTick()
  renderChart()
  resizeObserver = new ResizeObserver(() => {
    if (panelWidth.value > maxPanelWidth()) setPanelWidth(panelWidth.value)
    scheduleResize()
  })
  if (chartEl.value) resizeObserver.observe(chartEl.value)
  window.addEventListener('resize', scheduleResize)
})
onBeforeUnmount(() => {
  wellVersion += 1
  runVersion += 1
  stopResize()
  resizeObserver?.disconnect()
  window.removeEventListener('resize', scheduleResize)
  if (resizeFrame) cancelAnimationFrame(resizeFrame)
  chart?.dispose()
  chart = null
})

/* ===== 启动预测 ===== */
const sameWellSet = (a, b) => {
  const left = [...(a || [])].map(String).sort()
  const right = [...(b || [])].map(String).sort()
  return left.length === right.length && left.every((name, index) => name === right[index])
}
// 井选择与当前任务的井集不一致时给出提示：启动预测会创建新任务。
const wellSelectionChanged = computed(() =>
  Boolean(currentTask.value) && !sameWellSet(currentTask.value.wellNames, selectedWellNames.value))
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
const reportError = error => {
  const raw = error?.response?.data?.message ?? error?.response?.data?.msg ?? error?.message
  const detail = String(raw ?? '调用原平台井组预测接口失败').trim()
  if (/<!doctype\s+html|<html\b/i.test(detail)) return '原平台返回HTML错误页，请检查原平台会话或服务端日志'
  return detail.slice(0, 300)
}
const mayHaveWrittenResult = error => {
  const status = Number(error?.response?.status)
  return (status >= 500 && status < 600) || error?.code === 'ECONNABORTED'
}

// 面板字段 → Calc 的 reservoirInput 覆盖值；全部做数值校验，空值不覆盖（沿用详情值）。
function collectPanelOverrides() {
  const overrides = {}
  const put = (key, label, options) => {
    const raw = panel[key]
    if (raw === '' || raw === null || raw === undefined) return
    overrides[key] = numeric(raw, label, options)
  }
  put('targetProduction', '目标产量', { min: 0, allowZero: false })
  if (isMultiObjective.value || panel.externalWellheadPressure !== '') {
    put('externalWellheadPressure', '井口外输压力', { min: 0, allowZero: false })
  }
  put('predictedTimeStep', '预测时间步长', { min: 1 })
  put('predictedTimeStepNumber', '预测时间步数', { min: 1 })
  put('economicLimitGasProduction', '废弃产量', { min: 0 })
  put('specificGravity', '天然气比重', { min: 0, allowZero: false })
  put('hydrogenSulfide', 'H₂S含量', { min: 0 })
  put('carbonDioxide', 'CO₂含量', { min: 0 })
  put('nitrogen', 'N₂含量', { min: 0 })
  put('originalFormationPressure', '原始地层压力', { min: 0, allowZero: false })
  put('formationTemperature', '地层温度', { min: -273.149 })
  put('waterSaturation', '束缚水饱和度', { min: 0 })
  put('rockCompressionCoefficient', '岩石压缩系数', { min: 0 })
  put('waterCompressionCoefficient', '地层水压缩系数', { min: 0 })
  if (panel.predictionStartTime) {
    const text = String(panel.predictionStartTime).trim()
    if (!/^\d{4}-\d{2}-\d{2}$/.test(text)) throw new Error('预测开始日期格式不正确')
    overrides.predictionStartTime = text
  }
  if (!Number.isSafeInteger(overrides.predictedTimeStep) && overrides.predictedTimeStep !== undefined) {
    throw new Error('预测时间步长必须是整数天')
  }
  if (!Number.isSafeInteger(overrides.predictedTimeStepNumber) && overrides.predictedTimeStepNumber !== undefined) {
    throw new Error('预测时间步数必须是整数')
  }
  return overrides
}

async function startPrediction() {
  if (busy.value || preparing.value || loadingWells.value) return
  const version = ++runVersion
  busy.value = true
  errorTextState.value = ''
  chartPoints.value = []
  renderChart()
  try {
    const wellNames = [...selectedWellNames.value]
    if (wellNames.length < 2) throw new Error('井组动态预测至少选择 2 口井')
    const name = taskName.value.trim()
    if (!currentTask.value && !name) throw new Error('请填写井组动态预测名称')

    // 会话内没有任务、或井集合发生变化时创建新任务；同名不同井的任务不能复用。
    // （进页面时通常已自动准备；此处覆盖"准备失败后重试"与"改井选择"两种情况。）
    let task = currentTask.value
    if (!task || !sameWellSet(task.wellNames, wellNames)) {
      statusText.value = '正在创建原平台井组动态预测任务…'
      let targetName = name || `${props.storageName || ''}井组动态预测`
      // 沿用本会话任务的名称会撞原平台唯一约束（项目+气藏+名称+类型），先取平台顺延名称。
      if (task && targetName === String(task.name)) {
        const fresh = await fetchDefaultName()
        if (fresh) {
          targetName = fresh
          taskName.value = fresh
        }
      }
      const created = await createTaskWithFreshName(targetName, wellNames)
      if (version !== runVersion) return
      const id = Number(created?.dynamicPredictionId)
      if (!Number.isSafeInteger(id) || id <= 0) throw new Error('创建井组任务没有返回有效的 dynamicPredictionId')
      const createdName = String(created?.dynamicPredictionName || targetName).trim()
      taskName.value = createdName
      task = { dynamicPredictionId: id, name: createdName, wellNames }
      currentTask.value = task
      pendingDetail.value = null
    }

    // 任务详情：进页面时已自动带出；重建任务或准备失败重试时在此补齐。
    // 仅当用户尚未编辑过面板时才用平台默认值回填，避免覆盖手改的参数。
    let detail = pendingDetail.value
    if (!detail) {
      statusText.value = '正在读取平台带出的井组预测参数…'
      detail = unwrap(await wellGroupPredictionApi.getDetail(
        props.projectId, props.gasReservoirId, task.dynamicPredictionId))
      if (version !== runVersion) return
      pendingDetail.value = detail
      if (!panelTouched.value) fillPanelFromDetail(detail)
    }
    // 面板值（平台带出的 + 用户修改的）覆盖详情；空值不覆盖，未展示字段原样带回。
    const overrides = collectPanelOverrides()
    const calcRequest = buildWellGroupCalcRequest(detail, {
      projectId: props.projectId,
      gasReservoirId: props.gasReservoirId,
      dynamicPredictionId: task.dynamicPredictionId
    }, overrides)
    const wellInputCount = Array.isArray(detail?.wellInputs) ? detail.wellInputs.length : 0
    if (wellInputCount && wellInputCount < wellNames.length) {
      throw new Error(`任务详情只带出 ${wellInputCount} 口井的参数，少于所选 ${wellNames.length} 口井`)
    }

    const fetchChart = async () => normalizeWellGroupChart(
      await wellGroupPredictionApi.getChart(props.projectId, props.gasReservoirId, task.dynamicPredictionId),
      { dynamicPredictionId: task.dynamicPredictionId }
    )

    // 计算前曲线指纹：区分“本次计算产生的新曲线”与“任务中的历史结果”。
    let beforeCalc = null
    let beforeCalcState = 'unknown'
    try {
      beforeCalc = fingerprintWellGroupPoints((await fetchChart()).points)
      beforeCalcState = 'present'
    } catch (error) {
      const status = Number(error?.response?.status)
      const message = String(error?.message || '')
      if (status === 404 || status === 204 || /缺少|没有有效|为空/.test(message)) {
        beforeCalcState = 'empty'
      }
    }
    if (version !== runVersion) return

    statusText.value = '正在调用原平台启动预测…'
    let calcError = null
    try {
      await wellGroupPredictionApi.calculate(calcRequest)
    } catch (error) {
      if (!mayHaveWrittenResult(error)) throw error
      calcError = error
      console.warn('[一体化耦合优化] Calc 响应异常，核对本次任务曲线', {
        task: task.dynamicPredictionId,
        httpStatus: error?.response?.status ?? null,
        backendMessage: reportError(error)
      })
    }
    if (version !== runVersion) return

    let parsed = null
    let queryError = null
    let isFresh = false
    for (let attempt = 0; attempt < 8; attempt += 1) {
      if (version !== runVersion) return
      statusText.value = `正在获取井组预测曲线（${attempt + 1}/8）…`
      try {
        const data = await fetchChart()
        if (version !== runVersion) return
        parsed = data
        isFresh = beforeCalcState === 'present'
          ? fingerprintWellGroupPoints(data.points) !== beforeCalc
          : beforeCalcState === 'empty'
        if (isFresh || attempt === 7) break
        await pause(1200)
      } catch (error) {
        queryError = error
        const code = error?.response?.status
        const message = String(error?.message || '')
        const retryable = [404, 409, 425, 500, 502, 503, 504].includes(code) ||
          /为空|缺少|没有有效/.test(message)
        if (!retryable || attempt === 7) break
        await pause(1200)
      }
    }
    if (calcError && (!parsed || !isFresh)) {
      const status = calcError?.response?.status || '未知'
      const chartDetail = queryError ? `；绘图查询：${reportError(queryError)}` : ''
      throw new Error(
        `原平台 Calc 返回 HTTP ${status}，任务 ${task.dynamicPredictionId} ` +
        `没有查询到可以确认的新曲线。后台提示：${reportError(calcError)}${chartDetail}`)
    }
    if (!parsed) throw new Error(`Calc 已提交，但任务 ${task.dynamicPredictionId} 的曲线仍不可用：${reportError(queryError)}`)
    if (version !== runVersion) return

    chartPoints.value = parsed.points
    await nextTick()
    if (version !== runVersion) return
    renderChart()
    statusText.value = calcError
      ? `Calc 返回 HTTP ${calcError?.response?.status || '异常'}，但任务 ${task.dynamicPredictionId} 已返回新曲线；需核实后台计算状态`
      : (isFresh ? `预测完成：任务「${task.name}」共 ${parsed.points.length} 个预测点`
        : '已显示曲线，尚未确认后台数据是否刷新')
    // 结果留存：把本次结果保存到本项目数据库（不迁移历史记录；失败不影响本次展示）。
    void persistPredictionResult({ task, calcRequest, points: parsed.points })
    if (calcError) ElMessage.warning('Calc 返回异常，但本次任务查询到了新曲线；后台状态待核实')
    else if (isFresh) ElMessage.success('井组动态预测完成')
    else ElMessage.warning('图表数据与计算前一致，请确认后台结果是否已更新')
  } catch (error) {
    if (version !== runVersion) return
    errorTextState.value = reportError(error)
    statusText.value = ''
    console.error('[一体化耦合优化]', error)
    ElMessage.error(errorTextState.value)
  } finally {
    if (version === runVersion) busy.value = false
  }
}

</script>

<template>
  <div ref="workspaceEl" class="coupling-workspace">
    <aside class="params-panel" :class="{ collapsed: paramsCollapsed }"
      :style="paramsCollapsed ? undefined : { width: panelWidth + 'px', minWidth: panelWidth + 'px', flexBasis: panelWidth + 'px' }">
      <button v-if="paramsCollapsed" class="panel-collapsed-tab" type="button" aria-label="展开参数设置" @click="paramsCollapsed = false">参数设置</button>
      <template v-else>
        <div class="panel-head">
          <span>参数设置（{{ mode }}）</span>
          <button class="panel-toggle" type="button" title="收起参数设置" aria-label="收起参数设置" @click="paramsCollapsed = true">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="#777" aria-hidden="true"><path d="M16,12V4H17V2H7V4H8V12L6,14V16H11.2V22H12.8V16H18V14L16,12Z" /></svg>
          </button>
        </div>
        <div class="panel-body" @input="onPanelInput">

          <fieldset class="field-section">
            <legend class="sec-label">预测任务</legend>
            <div class="field-grid">
              <label class="field"><span>预测名称</span>
                <input v-model="taskName" type="text" autocomplete="off" :disabled="Boolean(currentTask)" placeholder="井组动态预测名称" /></label>
              <label class="field"><span>产能方程形式</span>
                <select v-model.number="productivityForm" :disabled="Boolean(currentTask)">
                  <option v-for="item in PRODUCTIVITY_EVALUATION_FORMS" :key="item.value" :value="item.value">{{ item.label }}</option>
                </select></label>
            </div>
            <p v-if="currentTask" class="field-hint">
              当前会话任务：{{ currentTask.name }}（编号 {{ currentTask.dynamicPredictionId }}），预测名称与产能方程形式在任务创建后固定；
              左侧目录中可「新建预测」。
              <template v-if="wellSelectionChanged">井选择已改变，启动预测时将创建新任务（名称自动顺延）。</template>
            </p>
          </fieldset>

          <fieldset class="field-section">
            <legend class="sec-label">优化目标</legend>
            <div class="field-grid">
              <label class="field"><span>目标产量 (10⁴m³/d)</span>
                <input v-model="panel.targetProduction" type="text" inputmode="decimal" autocomplete="off" placeholder="请输入目标产量" /></label>
              <label v-if="isMultiObjective" class="field"><span>井口外输压力 (MPa)</span>
                <input v-model="panel.externalWellheadPressure" type="text" inputmode="decimal" autocomplete="off" placeholder="请输入井口外输压力" /></label>
            </div>
            <p class="field-hint">{{ isMultiObjective
              ? '多目标：在目标产量基础上增加井口外输压力作为第二个优化目标；右侧与原平台一致，展示气产量、累产气量、地层压力、采出程度四个输出指标。'
              : '单目标：以目标产量为优化目标，右侧只展示气产量与累产气量；井口外输压力沿用平台任务带出值。' }}</p>
          </fieldset>

          <fieldset class="field-section">
            <legend class="sec-label">预测控制</legend>
            <div class="field-grid">
              <label class="field"><span>预测开始时间</span>
                <input v-model="panel.predictionStartTime" type="date" /></label>
              <label class="field"><span>预测时间步长 (天)</span>
                <input v-model="panel.predictedTimeStep" type="text" inputmode="numeric" autocomplete="off" /></label>
              <label class="field"><span>预测时间步数</span>
                <input v-model="panel.predictedTimeStepNumber" type="text" inputmode="numeric" autocomplete="off" /></label>
              <label class="field"><span>废弃产量 (10⁴m³/d)</span>
                <input v-model="panel.economicLimitGasProduction" type="text" inputmode="decimal" autocomplete="off" /></label>
            </div>
          </fieldset>

          <details class="advanced-group">
            <summary>气体性质</summary>
            <div class="field-grid">
              <label class="field"><span>天然气比重</span>
                <input v-model="panel.specificGravity" type="text" inputmode="decimal" autocomplete="off" /></label>
              <label class="field"><span>H₂S 含量 (mol%)</span>
                <input v-model="panel.hydrogenSulfide" type="text" inputmode="decimal" autocomplete="off" /></label>
              <label class="field"><span>CO₂ 含量 (mol%)</span>
                <input v-model="panel.carbonDioxide" type="text" inputmode="decimal" autocomplete="off" /></label>
              <label class="field"><span>N₂ 含量 (mol%)</span>
                <input v-model="panel.nitrogen" type="text" inputmode="decimal" autocomplete="off" /></label>
            </div>
          </details>

          <details class="advanced-group">
            <summary>储层参数</summary>
            <div class="field-grid">
              <label class="field"><span>原始地层压力 (MPa)</span>
                <input v-model="panel.originalFormationPressure" type="text" inputmode="decimal" autocomplete="off" /></label>
              <label class="field"><span>地层温度 (℃)</span>
                <input v-model="panel.formationTemperature" type="text" inputmode="decimal" autocomplete="off" /></label>
              <label class="field"><span>束缚水饱和度</span>
                <input v-model="panel.waterSaturation" type="text" inputmode="decimal" autocomplete="off" /></label>
              <label class="field"><span>岩石压缩系数 (1/MPa)</span>
                <input v-model="panel.rockCompressionCoefficient" type="text" inputmode="decimal" autocomplete="off" /></label>
              <label class="field"><span>地层水压缩系数 (1/MPa)</span>
                <input v-model="panel.waterCompressionCoefficient" type="text" inputmode="decimal" autocomplete="off" /></label>
            </div>
          </details>

          <fieldset class="field-section wells-section">
            <legend class="sec-label">参与预测的井（{{ selectedWellNames.length }}）
              <label class="select-all-records"><input type="checkbox" :checked="allWellsSelected" :indeterminate="someWellsSelected"
                :disabled="loadingWells || !filteredWells.length" @change="toggleAllWells" />{{ wellKeyword.trim() ? '全选搜索结果' : '全选' }}</label>
            </legend>
            <label class="field"><input v-model="wellKeyword" type="search" placeholder="搜索井名" aria-label="搜索参与预测的井" /></label>
            <div v-if="loadingWells" class="record-status" role="status">正在读取单井…</div>
            <div v-else-if="wellError" class="record-status record-error" role="alert">{{ wellError }} <button type="button" @click="loadWells">重试</button></div>
            <div v-else-if="!storageWells.length" class="record-status">当前储气库暂无单井</div>
            <div v-else-if="!filteredWells.length" class="record-status">没有匹配的井</div>
            <div v-else class="record-list storage-well-list">
              <label v-for="well in filteredWells" :key="well.id" class="record-row">
                <input v-model="selectedWellNames" type="checkbox" :value="well.wellName" :aria-label="well.wellName" />
                <span class="record-info"><span class="record-name" :title="well.wellName">{{ well.wellName }}</span></span>
              </label>
            </div>
            <p class="field-hint">与原平台一致，井组动态预测至少选择 2 口井。</p>
          </fieldset>

          <div class="form-actions">
            <button type="button" class="calculate-button" :disabled="busy || preparing || loadingWells" @click="startPrediction">
              {{ busy ? '计算中…' : preparing ? '准备中…' : '启动预测' }}
            </button>
          </div>
          <div v-if="errorTextState" class="panel-error" role="alert">{{ errorTextState }}</div>
          <div v-else-if="statusText" class="panel-status" role="status">{{ statusText }}</div>

          <!-- 目标量判定：预测出图后自动计算预测期累计气产量差，与输入的需求量比较。 -->
          <section class="target-check" aria-label="目标量判定">
            <div class="target-check-head">目标量判定</div>
            <label class="field target-check-input"><span>目标需求量 (10⁸m³)</span>
              <input v-model="targetDemand" type="text" inputmode="decimal" autocomplete="off" placeholder="输入需求量自动判定" /></label>
            <template v-if="targetCheck">
              <div class="target-check-row"><span>计算量（{{ targetCheck.startDate }} ~ {{ targetCheck.endDate }}）</span><span>{{ renderNumber(targetCheck.computedAmount) }}</span></div>
              <div class="target-check-row"><span>目标量</span><span>{{ targetCheck.target === null ? '—' : renderNumber(targetCheck.target) }}</span></div>
              <div class="target-check-row"><span>累计差值</span><span>{{ targetCheck.difference === null ? '—' : (targetCheck.difference >= 0 ? '+' : '') + renderNumber(targetCheck.difference) }}</span></div>
              <div v-if="targetCheck.satisfied === null" class="target-check-verdict pending">输入目标需求量后自动判定</div>
              <div v-else class="target-check-verdict" :class="targetCheck.satisfied ? 'ok' : 'fail'">
                {{ targetCheck.satisfied ? '✓ 满足需求量' : `✗ 不满足需求量（差 ${renderNumber(Math.abs(targetCheck.difference))}）` }}
              </div>
            </template>
            <div v-else class="target-check-empty">启动预测（或载入已有结果）后自动判定</div>
          </section>
        </div>
        <div class="params-resizer" role="separator" tabindex="0" aria-label="调整参数栏宽度" aria-orientation="vertical"
          :aria-valuenow="panelWidth" :aria-valuemin="258" :aria-valuemax="maxPanelWidth()" @pointerdown="startResize" @keydown="resizeWithKeyboard" />
      </template>
    </aside>
    <main class="result-area">
      <div class="result-tabs"><div class="result-tab active" :title="chartTitle">{{ chartTitle }}</div></div>
      <div class="chart-area"><div ref="chartEl" class="coupling-chart"></div></div>
    </main>
  </div>
</template>

<style lang="scss" scoped>
// 布局与库级产能对比、井间对比页面保持一致：左侧参数栏、34px 标题栏和黄黑交互色。
.coupling-workspace { flex: 1; min-width: 0; min-height: 0; display: flex; overflow: hidden; background: #fff; color: #252525; font: 13px/1.5 "Microsoft YaHei", "Segoe UI", Arial, sans-serif; }
.params-panel { position: relative; min-height: 0; display: flex; flex-direction: column; overflow: hidden; border-right: 1px solid #d7d7d7; background: #fff; }
.params-panel.collapsed { width: 34px; min-width: 34px; flex: 0 0 34px; }
.panel-collapsed-tab { width: 100%; height: 76px; padding: 8px 0; border: 0; border-bottom: 1px solid #e2e6ea; background: #fff; color: #333; font: inherit; writing-mode: vertical-rl; cursor: pointer; }
.panel-head { height: 34px; padding: 0 12px; display: flex; align-items: center; justify-content: space-between; flex-shrink: 0; border-bottom: 1px solid #d7d7d7; background: #f2f2f2; box-sizing: border-box; }
.panel-toggle { width: 20px; height: 20px; padding: 0; border: 0; display: flex; align-items: center; justify-content: center; background: transparent; cursor: pointer; }
.panel-toggle:hover { background: #fff8d8; }
.panel-body { flex: 1; min-height: 0; padding: 10px 12px 16px; overflow-y: auto; }
.field-section { border: 0; padding: 0; margin: 0 0 9px; min-width: 0; }
.sec-label { width: 100%; height: 22px; padding: 0; margin: 8px 0 7px; display: flex; align-items: center; gap: 8px; font-weight: 500; font-size: 13px; }
.sec-label::after { content: ''; height: 1px; flex: 1; background: #999; }
.wells-section .sec-label { flex-wrap: wrap; height: auto; }
.select-all-records { display: inline-flex; align-items: center; gap: 4px; white-space: nowrap; font-weight: 400; cursor: pointer; }
.select-all-records input { width: 13px; height: 13px; margin: 0; accent-color: #333; cursor: pointer; }
.select-all-records:has(input:disabled) { color: #999; cursor: default; }
.select-all-records input:disabled { cursor: default; }
.field-grid { display: grid; grid-template-columns: 1fr; row-gap: 0; }
.field { display: block; min-width: 0; margin-bottom: 9px; }
.field span { display: block; margin-bottom: 3px; color: #252525; font-size: 12px; }
.field input, .field select { display: block; width: 100%; min-width: 0; height: 24px; padding: 0 8px; box-sizing: border-box; border: 1px solid #aaa; border-radius: 3px; background: #fff; color: #333; font: inherit; }
.field select:disabled { background: #f5f5f5; color: #777; }
.field input:focus-visible, .field select:focus-visible { outline: 1px solid #d5b900; outline-offset: 1px; }
.field-hint { margin: -2px 0 8px; color: #888; font-size: 11px; line-height: 1.6; overflow-wrap: anywhere; }
.advanced-group { margin: 8px 0; border: 0; }
.advanced-group summary { margin-bottom: 7px; font-weight: 500; font-size: 13px; cursor: pointer; }
.advanced-group[open] summary { margin-bottom: 9px; }
.record-list { border: 1px solid #ddd; }
.storage-well-list { max-height: 240px; overflow-y: auto; margin-top: 6px; }
.record-row { display: flex; align-items: flex-start; gap: 7px; padding: 7px; border-bottom: 1px solid #eee; cursor: pointer; }
.record-row:last-child { border-bottom: 0; }
.record-row input { accent-color: #333; margin: 3px 0 0; width: 13px; height: 13px; flex-shrink: 0; }
.record-info { min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.record-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.record-status { color: #777; font-size: 12px; padding: 8px 0; }
.record-error { color: #b24b37; font-size: 12px; overflow-wrap: anywhere; }
.record-error button { font: inherit; cursor: pointer; }
.form-actions { display: flex; align-items: center; gap: 10px; }
.calculate-button { min-width: 96px; height: 30px; margin: 14px 0 4px; padding: 0 22px; border: 1px solid #d5b900; border-radius: 4px; background: #f4d000; color: #222; font: inherit; cursor: pointer; }
.calculate-button:hover { background: #ffe033; }
.calculate-button:disabled { opacity: .6; cursor: wait; }
.panel-status { margin-top: 6px; color: #666; font-size: 12px; line-height: 1.6; overflow-wrap: anywhere; }
// 目标量判定区：面板底部，输入需求量 + 计算量/差值 + 判定结论。
.target-check { margin-top: 12px; padding: 8px 10px; border: 1px solid #e0e0e0; border-radius: 4px; background: #fafafa; }
.target-check-head { margin-bottom: 6px; font-weight: 600; font-size: 12px; }
.target-check-input span { font-size: 12px; }
.target-check-row { display: flex; justify-content: space-between; gap: 8px; padding: 2px 0; font-size: 12px; }
.target-check-row > span:last-child { font-variant-numeric: tabular-nums; white-space: nowrap; }
.target-check-verdict { margin-top: 6px; padding-top: 6px; border-top: 1px dashed #ddd; font-weight: 600; font-size: 12px; overflow-wrap: anywhere; }
.target-check-verdict.ok { color: #269c71; }
.target-check-verdict.fail { color: #d63f3f; }
.target-check-verdict.pending { color: #909399; font-weight: 400; }
.target-check-empty { color: #999; font-size: 12px; line-height: 1.6; }
.panel-error { margin-top: 6px; color: #b24b37; font-size: 12px; line-height: 1.6; overflow-wrap: anywhere; }
.params-resizer { position: absolute; z-index: 4; top: 0; right: 0; width: 5px; height: 100%; cursor: col-resize; touch-action: none; }
.params-resizer:hover, .params-resizer:focus-visible { background: #f4d000; outline: none; }
button:focus-visible { outline: 2px solid #555; outline-offset: -2px; }
.result-area { flex: 1; min-width: 0; min-height: 0; display: flex; flex-direction: column; overflow: hidden; background: #fff; }
.result-tabs { height: 34px; flex: 0 0 34px; display: flex; align-items: center; border-bottom: 1px solid #e4e7ed; background: #fafafa; }
.result-tab { height: 34px; line-height: 34px; max-width: min(430px, 100%); padding: 0 12px; border-right: 1px solid #e4e7ed; box-sizing: border-box; background: #f4d000; color: #202020; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.chart-area { flex: 1; min-height: 0; position: relative; }
.coupling-chart { position: absolute; inset: 0; }
</style>
