<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import {
  cloneJson,
  createCalcRequest,
  createWellPredictionTask,
  dynamicPredictionApi,
  fingerprintWellResults,
  listWellConstraintOptions,
  normalizePlatformChart,
  numeric,
  optionalNumber,
  resolveChartQuery,
  sameScope,
  scopeKey
} from '@/api/solutionGeneration'

const props = defineProps({
  wellName: { type: String, default: '' },
  projectId: { type: [Number, String], required: true },
  gasReservoirId: { type: [Number, String], required: true },
  constraintsResult: { type: Object, default: null },
  // 未来从数据库加载后，父组件直接传入本井的约束方案列表即可。
  // [{ id, name, projectId, gasReservoirId, wellName, inputs }]
  constraintOptions: { type: Array, default: () => [] },
  fetchChartData: { type: Function, default: null },
  savedResult: { type: Object, default: null },
  // 兼容现有 IprInterface.vue 的属性，不再暴露预测任务选择等复杂操作。
  dynamicPredictionRequest: { type: Object, default: null },
  predictionContextWarning: { type: String, default: '' },
  predictionContextLoading: { type: Boolean, default: false },
  predictionTasks: { type: Array, default: () => [] },
  selectedPredictionTaskId: { type: [Number, String], default: '' },
  chartWellName: { type: String, default: '' },
  resultPredictionId: { type: [Number, String], default: null },
  resultKind: { type: [Number, String], default: 0 }
})

const emit = defineEmits(['solution-generated'])
const params = reactive({ targetProduction: '', exportPressure: '' })
const edited = reactive({ targetProduction: false, exportPressure: false })
const selectedConstraintId = ref('')
const userChoseConstraint = ref(false)
const cachedConstraints = ref(null)
const chartRef = ref(null)
const result = ref(null)
const busy = ref(false)
const statusText = ref('')
const errorText = ref('')
const pendingTask = ref(null)
let chartInstance = null
let runVersion = 0
let destroyed = false

const currentScope = computed(() => ({
  projectId: props.projectId,
  gasReservoirId: props.gasReservoirId,
  wellName: props.wellName
}))
const currentKey = computed(() => scopeKey(currentScope.value))
const snapshot = computed(() => {
  if (sameScope(props.constraintsResult, currentScope.value)) return props.constraintsResult
  return sameScope(cachedConstraints.value, currentScope.value) ? cachedConstraints.value : null
})
const constraints = computed(() => listWellConstraintOptions(
  currentScope.value, snapshot.value, props.constraintOptions
))
const selectedRecord = computed(() =>
  constraints.value.find(option => option.id === selectedConstraintId.value) || null
)
const selectedInputs = computed(() => selectedRecord.value?.inputs ?? null)

// 四项约束必须来源于“当前选中”的方案，而不是其它方案的全局 props。
const boundaryItems = [
  { key: 'erosionLimit', label: '冲蚀气量上限' },
  { key: 'sandProductionLimit', label: '出砂气量上限' },
  { key: 'liquidLoadingLimit', label: '积液气量下限' },
  { key: 'wellheadExportPressure', label: '井口外输压力' }
]
const hasBoundaryField = data => data && typeof data === 'object' &&
  boundaryItems.some(item => data[item.key] !== undefined)

function unwrapBoundaryRecord(record) {
  if (!record || typeof record !== 'object') return null
  const possibilities = [
    record.constraintResults,
    record.constraintsResult?.constraintResults,
    record.calculationResults,
    record.results?.constraintResults,
    record.boundaryConditions,
    record.constraintsResult?.boundaryConditions,
    record
  ]
  const body = possibilities.find(hasBoundaryField)
  return body ? { record, body } : null
}

const selectedBoundarySource = computed(() => {
  const option = selectedRecord.value
  if (!option) return null
  for (const candidate of [
    option, option.record, option.snapshot, option.payload, option.raw,
    option.constraintsResult, option.results
  ]) {
    const resolved = unwrapBoundaryRecord(candidate)
    if (resolved) return resolved
  }
  const live = snapshot.value
  if (!live?.inputs || !selectedInputs.value) return null
  const bothHaveIds = live.id != null && option.id != null
  const matchingInputs = JSON.stringify(live.inputs) === JSON.stringify(selectedInputs.value)
  // 两侧均有方案 ID 时必须匹配；只有临时计算快照没有 ID 时才比较完整输入。
  const matches = bothHaveIds
    ? String(live.id) === String(option.id)
    : matchingInputs
  return matches ? unwrapBoundaryRecord(live) : null
})

const activeBoundaries = computed(() => {
  if (!selectedRecord.value) return null
  const { record, body } = selectedBoundarySource.value || {}
  const read = key => {
    const item = body?.[key] ?? record?.boundaryConditions?.[key] ??
      record?.constraintResults?.[key] ??
      (key === 'wellheadExportPressure'
        ? record?.outletPressureMpa ?? record?.inputs?.otherData?.wellheadExportPressure
        : null)
    if (item && typeof item === 'object' &&
      (item.available === false || item.verified === false)) return null
    const value = optionalNumber(item && typeof item === 'object' ? item.value : item)
    return value !== null && (key === 'wellheadExportPressure' ? value > 0 : value >= 0)
      ? value : null
  }
  const erosion = read('erosionLimit')
  const sand = read('sandProductionLimit')
  const liquid = read('liquidLoadingLimit')
  const pressure = read('wellheadExportPressure')
  const hasErosion = erosion !== null
  // 必填项：出砂、积液、压力；冲蚀为可选项
  const requiredKeys = ['sandProductionLimit', 'liquidLoadingLimit', 'wellheadExportPressure']
  const requiredLabels = ['出砂气量上限', '积液气量下限', '井口外输压力']
  const requiredValues = [sand, liquid, pressure]
  const missing = requiredValues
    .map((v, i) => v === null ? requiredLabels[i] : null)
    .filter(Boolean)
  // 上限：冲蚀和出砂都有的情况下取更严格值；冲蚀缺失时回退到仅用出砂
  const upper = hasErosion && sand !== null ? Math.min(erosion, sand)
    : sand !== null ? sand
    : null
  const feasible = upper !== null && liquid !== null && liquid <= upper
  return {
    erosion, sand, liquid, pressure, upper, lower: liquid,
    hasErosion, missing, feasible, usable: missing.length === 0 && feasible
  }
})

const boundaryError = computed(() => {
  if (!activeBoundaries.value || activeBoundaries.value.usable) return ''
  const boundary = activeBoundaries.value
  if (boundary.missing.length) {
    return `当前约束方案缺少有效的必填结果：${boundary.missing.join('、')}。请重新计算约束条件。`
  }
  return '气量边界冲突：积液下限高于出砂/冲蚀上限，无法生成可行方案。'
})

const tolerance = value => Math.max(1e-6, Math.abs(value) * 1e-8)
function gasBoundaryProblems(gasValue, boundary) {
  if (!boundary) return []
  const gas = optionalNumber(gasValue)
  if (gas === null) return []
  const problems = []
  if (boundary.erosion !== null && gas > boundary.erosion + tolerance(boundary.erosion)) problems.push('超过冲蚀上限')
  if (boundary.sand !== null && gas > boundary.sand + tolerance(boundary.sand)) problems.push('超过出砂上限')
  if (boundary.liquid !== null && gas < boundary.liquid - tolerance(boundary.liquid)) problems.push('低于积液携液下限')
  return problems
}

function inspectPredictionBoundaries(points, boundary) {
  if (!boundary) return null
  const report = { count: 0, violatingPoints: 0, erosion: 0, sand: 0, liquid: 0 }
  for (const point of points) {
    if (optionalNumber(point.gas) === null) continue
    report.count += 1
    const problems = gasBoundaryProblems(point.gas, boundary)
    if (!problems.length) continue
    report.violatingPoints += 1
    if (problems.includes('超过冲蚀上限')) report.erosion += 1
    if (problems.includes('超过出砂上限')) report.sand += 1
    if (problems.includes('低于积液携液下限')) report.liquid += 1
  }
  return report
}

// 仅展示后端明确返回的短错误说明；HTTP 5xx 不等于本次计算没有落库。
const reportError = error => {
  const body = error?.response?.data
  const raw = typeof body === 'string'
    ? body
    : body?.message ?? body?.msg ?? body?.error ?? error?.message
  const detail = String(raw ?? '调用原平台预测接口失败').trim()
  if (/<!doctype\s+html|<html\b/i.test(detail)) return '原平台返回HTML错误页，请查看服务端日志'
  return detail.slice(0, 300)
}
const mayHaveWrittenResult = error => {
  const status = Number(error?.response?.status)
  return (status >= 500 && status < 600) || error?.code === 'ECONNABORTED'
}
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
const renderNumber = value => {
  const number = optionalNumber(value)
  return number === null ? '—' : number.toLocaleString('zh-CN', { maximumFractionDigits: 4 })
}
const escapeHtml = value => String(value).replace(/[&<>"']/g, letter => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
})[letter])

function resetOutput() {
  if (busy.value) return
  result.value = null
  statusText.value = ''
  errorText.value = ''
  chartInstance?.clear()
}
function autofill() {
  const other = selectedInputs.value?.otherData || {}
  if (!edited.targetProduction) {
    const amount = optionalNumber(other.targetProduction)
    params.targetProduction = amount !== null && amount > 0 ? String(amount) : ''
  }
  const pressureBoundary = activeBoundaries.value?.pressure
  if (selectedRecord.value && pressureBoundary !== null && pressureBoundary !== undefined) {
    if (!edited.exportPressure) {
      params.exportPressure = String(pressureBoundary)
    }
  } else if (!edited.exportPressure) {
    const pressure = optionalNumber(other.wellheadExportPressure)
    params.exportPressure = pressure !== null && pressure > 0 ? String(pressure) : ''
  }
}
function syncConstraintSelection() {
  if (selectedConstraintId.value && !constraints.value.some(option => option.id === selectedConstraintId.value)) {
    selectedConstraintId.value = ''
    // 约束记录被删除或不再属于当前井时，不能擅自切换到另一条。
    userChoseConstraint.value = true
    resetOutput()
  }
  if (!userChoseConstraint.value && !selectedConstraintId.value && constraints.value.length) {
    selectedConstraintId.value = constraints.value[0].id
  }
  autofill()
}
function loadSessionConstraint() {
  if (typeof window === 'undefined') return
  try {
    const key = `grdp:constraints:v1:${currentKey.value}`
    const loaded = JSON.parse(window.sessionStorage.getItem(key) || 'null')
    cachedConstraints.value = sameScope(loaded, currentScope.value) ? loaded : null
  } catch (error) {
    cachedConstraints.value = null
    console.warn('[方案生成] 读取当前井约束快照失败', error)
  }
}
function onConstraintUpdated(event) {
  if (event?.detail?.scopeKey && event.detail.scopeKey !== currentKey.value) return
  loadSessionConstraint()
  resetOutput()
}
function onConstraintChange() {
  userChoseConstraint.value = true
  edited.targetProduction = false
  edited.exportPressure = false
  autofill()
  resetOutput()
}
function onEdit(field) {
  edited[field] = true
  resetOutput()
}

watch(constraints, syncConstraintSelection, { immediate: true })
watch(() => JSON.stringify(selectedInputs.value ?? null), () => {
  autofill()
  resetOutput()
})
watch(() => JSON.stringify(activeBoundaries.value ?? null), () => {
  autofill()
  // 后台计算期间若约束结果被更新，禁止把旧请求曲线误标为新边界结果。
  if (busy.value) {
    runVersion += 1
    busy.value = false
    pendingTask.value = null
  }
  resetOutput()
})
watch(() => [props.projectId, props.gasReservoirId, props.wellName], () => {
  runVersion += 1
  pendingTask.value = null
  cachedConstraints.value = null
  selectedConstraintId.value = ''
  userChoseConstraint.value = false
  edited.targetProduction = false
  edited.exportPressure = false
  params.targetProduction = ''
  params.exportPressure = ''
  busy.value = false
  result.value = null
  loadSessionConstraint()
  chartInstance?.clear()
})

function resizeChart() { chartInstance?.resize() }
function showChart() {
  if (!result.value || !chartRef.value) return
  if (chartInstance && chartInstance.getDom() !== chartRef.value) {
    chartInstance.dispose()
    chartInstance = null
  }
  if (!chartInstance) chartInstance = echarts.init(chartRef.value)

  const points = result.value.points
  // 使用生成本次预测时固定下来的边界快照，防止切换方案后图形串值。
  const savedBoundary = result.value.appliedBoundaries
  const boundary = savedBoundary &&
    ['sand', 'liquid', 'pressure', 'lower', 'upper']
      .every(key => optionalNumber(savedBoundary[key]) !== null)
    ? savedBoundary : null
  const names = ['气产量', '累产气量', '地层压力']
  const units = { '气产量': '10⁴m³/d', '累产气量': '10⁸m³', '地层压力': 'MPa' }
  const colors = ['#e9c400', '#eda800', '#c7a747']

  const makeBoundaryLine = (value, name, color, position = 'insideEndTop') => ({
    name, yAxis: value,
    lineStyle: { color, width: 1.8, type: 'dashed' },
    label: {
      show: true, position, fontSize: 10, color,
      formatter: `${name} ${renderNumber(value)}`
    }
  })

  const gasLines = boundary ? [
    ...(boundary.erosion !== null
      ? [makeBoundaryLine(boundary.erosion, '冲蚀上限', '#e45a4f', 'insideEndTop')]
      : []),
    makeBoundaryLine(boundary.sand, '出砂上限', '#a26ad1', 'insideStartTop'),
    makeBoundaryLine(boundary.liquid, '积液下限', '#269c71', 'insideEndBottom')
  ] : []
  // 压力在不同测点：井口外输压力只作参考线，绝不拿它截断地层压力。
  const pressureLines = boundary ? [
    makeBoundaryLine(boundary.pressure, '井口外输压力(参考)', '#3c86d3', 'insideEndBottom')
  ] : []

  const violatingPoints = boundary
    ? points.flatMap(point => {
      const problems = gasBoundaryProblems(point.gas, boundary)
      return problems.length ? [[point.date, Number(point.gas), problems.join('、')]] : []
    })
    : []
  const gasData = points.map(point => [point.date, point.gas])
  const productionSeries = {
    name: names[0], type: 'line', yAxisIndex: 0, data: gasData,
    showSymbol: false, connectNulls: false, animation: false,
    itemStyle: { color: colors[0] },
    lineStyle: { color: colors[0], width: 2.1 },
    ...(gasLines.length ? {
      markLine: { silent: true, symbol: 'none', data: gasLines }
    } : {}),
    ...(boundary ? {
      markArea: {
        silent: true,
        itemStyle: { color: 'rgba(38, 156, 113, 0.07)' },
        data: [[{ yAxis: boundary.lower }, { yAxis: boundary.upper }]]
      }
    } : {})
  }
  const series = [
    productionSeries,
    {
      name: names[1], type: 'line', yAxisIndex: 2,
      showSymbol: false, connectNulls: false, animation: false,
      itemStyle: { color: colors[1] }, lineStyle: { color: colors[1], width: 2.3 },
      data: points.map(point => [point.date, point.cumulative])
    },
    {
      name: names[2], type: 'line', yAxisIndex: 1,
      showSymbol: false, connectNulls: false, animation: false,
      itemStyle: { color: colors[2] }, lineStyle: { color: colors[2], width: 2.3 },
      data: points.map(point => [point.date, point.formation]),
      ...(pressureLines.length ? {
        markLine: { silent: true, symbol: 'none', data: pressureLines }
      } : {})
    }
  ]
  if (violatingPoints.length) {
    series.push({
      name: '气量越界点', type: 'scatter', yAxisIndex: 0, z: 12,
      symbolSize: 9, animation: false,
      itemStyle: { color: '#d63f3f', borderColor: '#fff', borderWidth: 1 },
      data: violatingPoints
    })
  }
  const legendNames = violatingPoints.length ? [...names, '气量越界点'] : names

  chartInstance.setOption({
    title: {
      text: `${props.wellName} 单井动态预测`,
      left: 'center', top: 12, textStyle: { fontSize: 16, fontWeight: 600 }
    },
    legend: {
      top: 45, data: legendNames, icon: 'circle', itemWidth: 11, itemHeight: 9
    },
    tooltip: {
      trigger: 'axis', confine: true, axisPointer: { type: 'cross' },
      formatter: items => {
        const date = escapeHtml(items?.[0]?.data?.[0] ?? '')
        return `<strong>${date}</strong>` + (items || []).map(item => {
          if (item.seriesName === '气量越界点') {
            return `<div style="color:#d63f3f">⚠ ${escapeHtml(item.data?.[2] || '气量越界')}</div>`
          }
          return `<div>${item.marker} ${escapeHtml(item.seriesName)}：` +
            `${renderNumber(item.data?.[1])} ${units[item.seriesName] || ''}</div>`
        }).join('')
      }
    },
    grid: { left: 112, right: 90, top: 91, bottom: 52 },
    dataZoom: [{ type: 'inside', xAxisIndex: 0 }],
    xAxis: {
      type: 'time', name: '日期', nameLocation: 'middle', nameGap: 43,
      axisLabel: { hideOverlap: true, formatter: '{yyyy}' },
      splitLine: { show: true, lineStyle: { color: '#e6ecf2' } }
    },
    yAxis: [
      {
        type: 'value', min: 0, name: '气产量(10⁴m³/d)', position: 'left',
        axisLine: { show: true },
        splitLine: { show: true, lineStyle: { color: '#e6ecf2' } }
      },
      {
        type: 'value', min: 0, name: '地层压力(MPa)', position: 'left', offset: 58,
        axisLine: { show: true }, splitLine: { show: false }
      },
      {
        type: 'value', min: 0, name: '累产气量(10⁸m³)', position: 'right',
        axisLine: { show: true }, splitLine: { show: false }
      }
    ],
    series
  }, { notMerge: true })
  resizeChart()
}

async function generateSolution() {
  if (busy.value) return
  const version = ++runVersion
  busy.value = true
  result.value = null
  errorText.value = ''
  statusText.value = '正在准备当前井预测参数…'
  chartInstance?.clear()
  try {
    if (!String(props.wellName || '').trim()) throw new Error('请先选择一口井')
    const boundary = selectedRecord.value ? activeBoundaries.value : null
    if (selectedRecord.value && !boundary?.usable) {
      throw new Error(boundaryError.value || '约束方案没有有效的四项边界数据')
    }
    const appliedBoundaries = boundary ? cloneJson({
      erosion: boundary.erosion,
      sand: boundary.sand,
      liquid: boundary.liquid,
      pressure: boundary.pressure,
      lower: boundary.lower,
      upper: boundary.upper
    }) : null
    const target = numeric(params.targetProduction, '单井目标产量', { min: 0, allowZero: false })
    if (appliedBoundaries &&
      (target < appliedBoundaries.lower - tolerance(appliedBoundaries.lower) ||
       target > appliedBoundaries.upper + tolerance(appliedBoundaries.upper))) {
      throw new Error(`单井目标产量 ${renderNumber(target)} 超出约束区间 ` +
        `[${renderNumber(appliedBoundaries.lower)}, ${renderNumber(appliedBoundaries.upper)}] 10⁴m³/d`)
    }
    const pressure = appliedBoundaries
      ? (edited.exportPressure ? numeric(params.exportPressure, '井口外输压力', { min: 0, allowZero: false }) : appliedBoundaries.pressure)
      : numeric(params.exportPressure, '井口外输压力', { min: 0, allowZero: false })
          if (appliedBoundaries && edited.exportPressure) {
      appliedBoundaries.pressure = pressure
    }
    const inputs = selectedInputs.value ? cloneJson(selectedInputs.value) : null
    const signature = JSON.stringify({
      well: currentKey.value, target, pressure, chosen: selectedConstraintId.value,
      appliedBoundaries, inputs
    })
    let baseRequest
    // 如果新建任务已成功而后续 Calc/chart 失败，重复点击沿用本井同一任务进行重试。
    if (pendingTask.value?.signature === signature) {
      baseRequest = cloneJson(pendingTask.value.calcRequest)
    } else {
      statusText.value = `正在为 ${props.wellName} 创建原平台预测任务…`
      const methodValue = inputs?.otherData?.productivityEvaluationForm ??
        inputs?.reservoirInput?.productivityEvaluationForm ?? 1
      const parsedMethod = Number(methodValue)
      // HAR 中无额外选择时使用原平台默认的产能评价形式 1；
      // 约束记录里若保存了展示文本，不能当作数字任务参数发送。
      const productivityForm = Number.isSafeInteger(parsedMethod) && parsedMethod > 0
        ? parsedMethod : 1
      const task = await createWellPredictionTask(currentScope.value, productivityForm)
      if (destroyed || version !== runVersion) return
      baseRequest = task.calcRequest
      pendingTask.value = { signature, calcRequest: cloneJson(baseRequest) }
    }

    const request = createCalcRequest({
      scope: currentScope.value,
      baseRequest,
      inputs,
      targetProduction: target,
      exportPressure: pressure,
      preferFormInputs: true
    })
    if (appliedBoundaries) {
      if (!request.reservoirInput || typeof request.reservoirInput !== 'object') {
        throw new Error('原平台 Calc 请求缺少 reservoirInput，无法应用边界条件')
      }
      request.reservoirInput.externalWellheadPressure = appliedBoundaries.pressure
      request.reservoirInput.targetProduction = target
      const actualPressure = optionalNumber(request?.reservoirInput?.externalWellheadPressure)
      if (actualPressure === null ||
        Math.abs(actualPressure - appliedBoundaries.pressure) > tolerance(appliedBoundaries.pressure)) {
        throw new Error('生成的 Calc 请求没有使用约束条件中的井口外输压力，请检查请求参数映射')
      }
      const actualTarget = optionalNumber(request?.reservoirInput?.targetProduction)
      if (actualTarget === null || Math.abs(actualTarget - target) > tolerance(target)) {
        throw new Error('生成的 Calc 请求没有使用约束区间内的目标产量，请检查请求参数映射')
      }
    }
    const query = resolveChartQuery({ scope: currentScope.value, request })
    const getChart = () => props.fetchChartData
      ? props.fetchChartData({ ...query, request: cloneJson(request) })
      : dynamicPredictionApi.getPredictionChart(
        query.projectId, query.gasReservoirId, query.dynamicPredictionId, query.wellName
      )

    // 新任务也可能先返回历史数据；对照 Calc 前后的曲线防止误认旧数据。
    let beforeCalc = null
    let beforeCalcState = 'unknown'
    try {
      beforeCalc = fingerprintWellResults(normalizePlatformChart(await getChart(), query).points)
      beforeCalcState = 'present'
    } catch (error) {
      // 只有明确的"无曲线"才可作为计算前为空的证据。
      // 网络失败、500或鉴权失败不意味着计算前没有旧数据。
      const status = Number(error?.response?.status)
      const message = reportError(error)
      if (status === 404 || status === 204 ||
        /为空|缺少.*序列|缺少 chartItems|没有有效/.test(message)) {
        beforeCalcState = 'empty'
      }
    }
    if (destroyed || version !== runVersion) return

    statusText.value = '正在调用原平台 Calc 计算…'
    let calcError = null
    try {
      await dynamicPredictionApi.calculate(request.wellName, request)
    } catch (error) {
      // HTTP 500/超时可能发生在后台部分写入以后；仅核实本次新建任务的曲线。
      // 400/401/403 等明确拒绝仍原样抛出，不尝试用历史任务兜底。
      if (!mayHaveWrittenResult(error)) throw error
      calcError = error
      console.warn('[方案生成] Calc响应异常，核对本次任务曲线', {
        wellName: request.wellName,
        projectId: request.projectId,
        gasReservoirId: request.gasReservoirId,
        dynamicPredictionId: request.dynamicPredictionId,
        chartWellName: query.wellName,
        targetProduction: request.reservoirInput.targetProduction,
        externalWellheadPressure: request.reservoirInput.externalWellheadPressure,
        httpStatus: error?.response?.status ?? null,
        backendMessage: reportError(error)
      })
    }
    if (destroyed || version !== runVersion) return

    let parsed = null
    let queryError = null
    let isFresh = false
    for (let attempt = 0; attempt < 8; attempt += 1) {
      if (destroyed || version !== runVersion) return
      statusText.value = calcError
        ? `Calc 返回异常，正在核实本次任务的新曲线（${attempt + 1}/8）…`
        : `正在获取预测曲线（${attempt + 1}/8）…`
      try {
        const data = normalizePlatformChart(await getChart(), query)
        if (destroyed || version !== runVersion) return
        parsed = data
        isFresh = beforeCalcState === 'present'
          ? fingerprintWellResults(data.points) !== beforeCalc
          : beforeCalcState === 'empty'
        if (isFresh || attempt === 7) break
        await pause(1200)
      } catch (error) {
        queryError = error
        const code = error?.response?.status
        const msg = String(error?.message || '')
        const retryable = [404, 409, 425, 500, 502, 503, 504].includes(code) ||
          /为空|缺少 chartItems|缺少.*序列|没有有效/.test(msg)
        if (!retryable || attempt === 7) break
        await pause(1200)
      }
    }
    // Calc 失败时，严格要求同一新任务的曲线比计算前更新，禁止显示旧图。
    if (calcError && (!parsed || !isFresh)) {
      const status = calcError?.response?.status || '未知'
      const chartDetail = queryError ? `；绘图查询：${reportError(queryError)}` : ''
      throw new Error(
        `${request.wellName} Calc 返回 HTTP ${status}，任务 ${query.dynamicPredictionId} ` +
        `没有查询到可以确认的新曲线。后台提示：${reportError(calcError)}${chartDetail}。` +
        '请在 Network 提供这次 Calc 的 Payload 和 Response，以定位后台500原因。'
      )
    }
    if (!parsed) throw new Error(`Calc 已提交，但当前任务的曲线仍不可用：${reportError(queryError)}`)
    if (destroyed || version !== runVersion) return

    result.value = {
      ...parsed,
      sourceRequest: cloneJson(request),
      freshChartConfirmed: isFresh,
      calcReturnedError: Boolean(calcError),
      calcHttpStatus: calcError?.response?.status ?? null,
      constraintId: selectedConstraintId.value || null,
      appliedBoundaries,
      boundaryReport: inspectPredictionBoundaries(parsed.points, appliedBoundaries)
    }
    pendingTask.value = null
    await nextTick()
    if (destroyed || version !== runVersion) return
    showChart()
    const violationCount = result.value.boundaryReport?.violatingPoints || 0
    statusText.value = calcError
      ? `Calc 返回 HTTP ${calcError?.response?.status || '异常'}，但任务 ${query.dynamicPredictionId} 已返回新曲线；需核实后台计算状态`
      : (isFresh ? '预测完成，已显示原平台结果' : '已显示曲线，尚未确认后台数据是否刷新')
    if (violationCount) statusText.value += `；有 ${violationCount} 个预测时点超出气量边界`
    emit('solution-generated', {
      ...currentScope.value,
      dynamicPredictionId: query.dynamicPredictionId,
      chartWellName: query.wellName,
      constraintId: selectedConstraintId.value || null,
      appliedBoundaries,
      results: result.value
    })
    if (calcError) ElMessage.warning('Calc 返回异常，但本次任务查询到了新曲线；后台状态待核实')
    else if (isFresh) ElMessage.success('方案生成成功')
    else ElMessage.warning('图表数据与 Calc 前一致，请确认后台结果是否已更新')
  } catch (error) {
    if (destroyed || version !== runVersion) return
    errorText.value = reportError(error)
    statusText.value = ''
    console.error('[方案生成]', error)
    ElMessage.error(errorText.value)
  } finally {
    if (version === runVersion) busy.value = false
  }
}

onMounted(() => {
  loadSessionConstraint()
  if (props.savedResult?.sourceRequest?.wellName === props.wellName &&
    Array.isArray(props.savedResult?.points)) {
    result.value = props.savedResult
    nextTick().then(showChart)
  }
  window.addEventListener('grdp:constraints-updated', onConstraintUpdated)
  window.addEventListener('focus', loadSessionConstraint)
  window.addEventListener('resize', resizeChart)
})
onBeforeUnmount(() => {
  destroyed = true
  runVersion += 1
  window.removeEventListener('grdp:constraints-updated', onConstraintUpdated)
  window.removeEventListener('focus', loadSessionConstraint)
  window.removeEventListener('resize', resizeChart)
  chartInstance?.dispose()
  chartInstance = null
})
</script>

<template>
  <div class="solution-generation-workspace">
    <aside class="sg-sidebar">
      <div class="section-title">参数输入</div>
      <div class="sg-content">
        <label class="field">
          <span>单井目标产量 <small>(万m³/d)</small></span>
          <input v-model="params.targetProduction" type="number" min="0" step="any" placeholder="请输入目标产量"
            :disabled="busy" @input="onEdit('targetProduction')">
        </label>
        <label class="field">
          <span>井口外输压力 <small>(MPa)</small></span>
          <input v-model="params.exportPressure" type="number" min="0" step="any"
            placeholder="请输入外输压力"
            :disabled="busy"
            @input="onEdit('exportPressure')">
        </label>
        <label class="field">
          <span>约束条件</span>
          <select v-model="selectedConstraintId" :disabled="busy" @change="onConstraintChange">
            <option value="">不选择（使用当前井原平台参数）</option>
            <option v-for="option in constraints" :key="option.id" :value="option.id">
              {{ option.label }}
            </option>
          </select>
        </label>
        <p v-if="!constraints.length" class="sg-hint">暂无已保存的约束方案，可先填写两个参数直接生成。</p>
        <div v-if="selectedRecord && activeBoundaries" class="sg-boundary-panel">
          <div class="sg-boundary-title">本方案边界条件</div>
          <div class="sg-boundary-row">
            <span>冲蚀上限</span><strong>{{ renderNumber(activeBoundaries.erosion) }} <small>10⁴m³/d</small></strong>
            <span v-if="!activeBoundaries.hasErosion && activeBoundaries.usable" class="sg-optional-tag">未取得</span>
          </div>
          <div class="sg-boundary-row">
            <span>出砂上限</span><strong>{{ renderNumber(activeBoundaries.sand) }} <small>10⁴m³/d</small></strong>
          </div>
          <div class="sg-boundary-row">
            <span>积液下限</span><strong>{{ renderNumber(activeBoundaries.liquid) }} <small>10⁴m³/d</small></strong>
          </div>
          <div class="sg-boundary-row">
            <span>井口外输压力</span><strong>{{ renderNumber(edited.exportPressure && params.exportPressure !== '' ? Number(params.exportPressure) : activeBoundaries.pressure) }} <small>MPa</small></strong>
          </div>
          <p v-if="activeBoundaries.usable" class="sg-boundary-ok">
            允许目标产量：{{ renderNumber(activeBoundaries.lower) }} ~ {{ renderNumber(activeBoundaries.upper) }} 10⁴m³/d
          </p>
          <p v-else class="sg-boundary-error">{{ boundaryError }}</p>
        </div>
        <div class="form-actions">
          <button class="calculate-button" type="button" :disabled="busy" @click="generateSolution">
            {{ busy ? '正在生成…' : '生成方案' }}
          </button>
        </div>
        <p v-if="errorText" class="sg-error" role="alert">{{ errorText }}</p>
        <p v-if="statusText" class="sg-status">{{ statusText }}</p>
      </div>
    </aside>
    <main class="sg-chart-area">
      <div class="sg-tab">{{ wellName ? `${wellName} - 方案生成` : '方案生成' }}</div>
      <div v-if="!result" class="sg-chart-body"></div>
      <div v-else class="sg-chart-body">
        <div ref="chartRef" class="prediction-chart"></div>
        <p v-if="result.calcReturnedError" class="sg-chart-notice">
          Calc 返回 HTTP {{ result.calcHttpStatus || '异常' }}，但本次任务有新曲线；已展示数据，后台计算状态仍需核实。
        </p>
        <p v-else-if="result.freshChartConfirmed === false" class="sg-chart-notice">
          本次返回的曲线与计算前一致，暂不能确认后台结果已刷新。
        </p>
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

.sg-tab {
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

.section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 12px;
  padding: 12px 0 8px;
  color: #1f2937;
  font-size: 15px;
  font-weight: 700;
  line-height: 22px;
  letter-spacing: 0.2px;
}

.section-title::before {
  width: 4px;
  height: 15px;
  flex: 0 0 4px;
  border-radius: 2px;
  background: #d5b900;
  content: '';
}

.sg-sidebar {
  width: 280px;
  flex: 0 0 280px;
  border-right: 1px solid #e4e7ed;
  background: #fff;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.sg-content {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 16px 20px 20px;
}

.field {
  display: block;
  min-width: 0;
  margin-bottom: 12px;
}

.field span {
  display: block;
  margin-bottom: 3px;
  color: #252525;
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.field small {
  font-weight: 400;
  color: #909399;
}

.field input,
.field select {
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

.field input:focus-visible,
.field select:focus-visible {
  outline: 1px solid #d5b900;
  outline-offset: 1px;
}

.field input:disabled,
.field select:disabled {
  background: #f5f7fa;
  color: #c0c4cc;
}

.sg-hint {
  margin: -4px 0 12px;
  font-size: 11px;
  color: #909399;
  line-height: 1.5;
}

.form-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 4px;
}

.calculate-button {
  flex: 1;
  height: 30px;
  padding: 0 22px;
  border: 1px solid #d5b900;
  border-radius: 4px;
  background: #f4d000;
  color: #222;
  font: inherit;
  cursor: pointer;
  font-weight: 500;
}

.calculate-button:hover:not(:disabled) {
  background: #ffe033;
}

.calculate-button:disabled {
  opacity: 0.6;
  cursor: wait;
}

.sg-error {
  margin: 10px 0 0;
  font-size: 11px;
  color: #f56c6c;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: 1.5;
}

.sg-boundary-panel {
  margin: 4px 0 14px;
  padding: 9px 10px;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  background: #fafbfc;
}

.sg-boundary-title {
  margin-bottom: 7px;
  font-size: 12px;
  font-weight: 700;
}

.sg-boundary-row {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 6px;
  margin: 5px 0;
  font-size: 11px;
}

.sg-boundary-row strong {
  color: #2c3e50;
  text-align: right;
  white-space: nowrap;
  font-weight: 600;
}

.sg-boundary-row small {
  color: #909399;
  font-size: 10px;
  font-weight: 400;
}

.sg-boundary-ok,
.sg-boundary-error {
  margin: 8px 0 0;
  font-size: 11px;
  line-height: 1.5;
}

.sg-boundary-ok { color: #20845d; }
.sg-boundary-error { color: #d63f3f; }
.sg-optional-tag { font-size: 11px; color: #e6a23c; font-weight: 400; margin-left: 8px; }

.sg-status {
  margin-top: 8px;
  color: #606266;
  font-size: 11px;
  line-height: 1.5;
}

.sg-chart-area {
  min-width: 0;
  min-height: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
}

.chart-placeholder {
  flex: 1;
  min-height: 300px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: #909399;
  text-align: center;
  padding: 20px;
}

.chart-placeholder-icon {
  font-size: 48px;
  color: #d5b900;
  margin-bottom: 8px;
}

.chart-placeholder strong {
  color: #606266;
  font-size: 14px;
}

.chart-placeholder p {
  font-size: 12px;
  margin: 6px 0 0;
}

.sg-chart-body {
  padding: 14px 20px;
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.prediction-chart {
  width: 100%;
  flex: 1;
  min-height: 300px;
}

.sg-chart-notice {
  margin: 0 8px;
  font-size: 11px;
  color: #e6a23c;
  line-height: 1.5;
}

.sg-chart-constraint {
  margin: 4px 8px 8px;
  color: #606266;
  font-size: 11px;
  line-height: 1.6;
}

@media (max-width: 900px) {
  .solution-generation-workspace {
    flex-direction: column;
    overflow: auto;
  }

  .sg-sidebar {
    width: 100%;
    flex: 0 0 auto;
    border-right: 0;
    border-bottom: 1px solid #e4e7ed;
  }

  .sg-content {
    padding: 14px 20px;
  }

  .sg-chart-area {
    flex: 0 0 auto;
  }

  .prediction-chart {
    min-height: 280px;
  }
}
</style>