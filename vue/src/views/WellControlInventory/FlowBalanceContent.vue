<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import { materialBalanceApi } from '@/api/docker'

const props = defineProps({
  node: Object,
  projectId: [Number, String],
  gasReservoirId: [Number, String],
  recalculating: Boolean
})

const emit = defineEmits(['recalculate'])

const loading = ref(false)
const resultData = ref(null)
const chartEl = ref(null)
const chartAreaEl = ref(null)
const activeParamTab = ref('input')
const activeChartTab = ref('chart')
const paramsPanelEl = ref(null)
const paramsPanelWidth = ref(400)
const paramsCollapsed = ref(false)
const resizingParamsPanel = ref(false)
const equationGraphicPosition = ref(null)
const legendPosition = ref({ x: null, y: null })
const draggingLegend = ref(false)
const legendDragOffset = ref({ x: 0, y: 0 })
const hiddenLegendNames = ref(new Set())
const recalculationForm = ref({
  minimumWaterGasRatioEnabled: false,
  pressurePosition: 1,
  unstableFlowPeriodLength: 180,
  minimumWaterGasRatio: 0.0602
})
const recalculationInitializedKey = ref('')
const getXlsx = async () => import('xlsx')
let chart = null
let requestSeq = 0

const rawNode = computed(() => props.node?.raw || {})
const wellName = computed(() =>
    props.node?.wellName ||
    rawNode.value?.wellName ||
    rawNode.value?.name ||
    props.node?.name ||
    props.node?.label ||
    ''
)
const chartTabTitle = computed(() => `流动平衡-${wellName.value || '当前井'}-分析结果`)

const getRows = (payload) => {
  const rows = payload?.data?.data ?? payload?.data ?? payload?.items ?? payload?.rows ?? payload
  if (Array.isArray(rows)) return rows
  return rows ? [rows] : []
}

const getResultId = (row) =>
    row?.DynamicOriginalGasInPlaceId ??
    row?.dynamicOriginalGasInPlaceId ??
    row?.dynamicOriginalGasInplaceId ??
    row?.dynamicOriginalGasInplaceID ??
    row?.resultId ??
    row?.nodeId ??
    row?.analysisId ??
    row?.id ??
    null

const isFlowBalanceRow = (row) => {
  const type = Number(row?.dynamicOriginalGasInplaceType ?? row?.dynamicOriginalGasInPlaceType)
  const description = String(row?.dynamicOriginalGasInplaceMethodDescription || row?.dynamicOriginalGasInPlaceMethodDescription || '')
  return type === 6 || description.includes('流动物质平衡-基于井底流压') || description.includes('流动物质平衡-基于井口流压')
}

const getChartItems = (value) => {
  const items =
      value?.chartItems ??
      value?.charttItems ??
      value?.charttItem ??
      value?.chartItem ??
      value?.charts ??
      []
  return Array.isArray(items) ? items : []
}

const input = computed(() => resultData.value?.input || {})
const output = computed(() => resultData.value?.output || resultData.value?.result || {})
const chartItems = computed(() => getChartItems(resultData.value))

const toNumber = (value) => {
  const number = Number(value)
  return Number.isFinite(number) ? number : null
}

const getPointFromRow = (row) => {
  if (Array.isArray(row) && row.length >= 2) {
    const x = toNumber(row[0])
    const y = toNumber(row[1])
    return x !== null && y !== null ? [x, y] : null
  }
  if (!row || typeof row !== 'object') return null

  const x = toNumber(
      row.xValue ??
      row.gp ??
      row.Gp ??
      row.cumulativeGasProduction ??
      row.cumulativeGas ??
      row.cumGas ??
      row.x
  )
  const y = toNumber(
      row.yValue ??
      row.pressure ??
      row.pp ??
      row.Pp ??
      row.averageFormationPressure ??
      row.formationPressure ??
      row.bottomHolePressure ??
      row.flowingBottomHolePressure ??
      row.y
  )

  return x !== null && y !== null ? [x, y] : null
}

const isLineItem = (item, index) => {
  const text = `${item?.name || ''} ${item?.type || ''} ${item?.yAxisField || ''}`
  return index > 0 || /line|regression|linear|回归|拟合/i.test(text)
}

const pointItem = computed(() =>
    chartItems.value.find((item, index) => Array.isArray(item?.data) && item.data.length && !isLineItem(item, index)) ||
    chartItems.value.find(item => Array.isArray(item?.data) && item.data.length) ||
    null
)

const lineItems = computed(() =>
    chartItems.value.filter((item, index) => Array.isArray(item?.data) && item.data.length && isLineItem(item, index))
)

const primaryPointRows = computed(() =>
    Array.isArray(pointItem.value?.data) ? pointItem.value.data : []
)

const allChartPoints = computed(() =>
    primaryPointRows.value
        .map(getPointFromRow)
        .filter(Boolean)
)

const hasAnalysisSelectionFlag = computed(() =>
    primaryPointRows.value.some(row => row && typeof row === 'object' && Object.prototype.hasOwnProperty.call(row, 'isDeleted'))
)

const selectedChartPoints = computed(() => {
  const rows = hasAnalysisSelectionFlag.value
      ? primaryPointRows.value.filter(row => row?.isDeleted !== true)
      : primaryPointRows.value

  return rows
      .map(getPointFromRow)
      .filter(Boolean)
})

const regression = computed(() => {
  const slope = toNumber(output.value?.gradient ?? output.value?.slope)
  const intercept = toNumber(output.value?.intercept)
  const r2 = toNumber(output.value?.rsquared ?? output.value?.r2)
  if (slope === null || intercept === null) return null
  return { slope, intercept, r2: r2 ?? 0 }
})

const xRange = computed(() => {
  if (!allChartPoints.value.length) return { min: 0, max: 1 }
  const max = Math.max(...allChartPoints.value.map(([x]) => x))
  return { min: 0, max: Math.ceil(max + Math.max(max * 0.05, 1)) }
})

const regressionLinePoints = computed(() => {
  const line = lineItems.value[0]
  if (line?.data?.length) return line.data.map(getPointFromRow).filter(Boolean)

  if (!regression.value || !selectedChartPoints.value.length) return []
  const xs = selectedChartPoints.value.map(([x]) => x)
  const min = Math.min(...xs)
  const max = Math.max(...xs)
  return [
    [min, regression.value.slope * min + regression.value.intercept],
    [max, regression.value.slope * max + regression.value.intercept]
  ]
})

const shiftLinePoints = computed(() => {
  const line = lineItems.value[1]
  if (line?.data?.length) return line.data.map(getPointFromRow).filter(Boolean)
  return []
})

const chartBounds = computed(() => {
  const points = [...allChartPoints.value, ...regressionLinePoints.value, ...shiftLinePoints.value]
  if (!points.length) return { xMin: 0, xMax: 1, yMin: 0, yMax: 1 }
  const ys = points.map(([, y]) => y)
  const yMin = Math.min(...ys)
  const yMax = Math.max(...ys)
  const padding = Math.max((yMax - yMin) * 0.08, 1)
  return {
    xMin: xRange.value.min,
    xMax: xRange.value.max,
    yMin: Math.floor(yMin - padding),
    yMax: Math.ceil(yMax + padding)
  }
})

const tableRows = computed(() =>
    primaryPointRows.value
        .map((row, index) => {
          const point = getPointFromRow(row)
          if (!point) return null
          return {
            index: index + 1,
            gp: point[0],
            pressure: point[1],
            selected: row?.isDeleted === true ? '否' : '是'
          }
        })
        .filter(Boolean)
)

const formatSci = (value, decimalPlaces = 4) => {
  if (!Number.isFinite(value)) return ''
  if (value === 0) return '0'
  const sign = value < 0 ? '-' : ''
  const abs = Math.abs(value)
  const exponent = Math.floor(Math.log10(abs))
  const coefficient = (abs / 10 ** exponent).toFixed(decimalPlaces)
  return `${sign}${coefficient}E${exponent >= 0 ? '+' : ''}${exponent}`
}

const GAS_TYPES = {
  0: '干气',
  1: '湿气'
}

const MODIFICATION_METHODS = {
  0: 'Wichert-Aziz 修正方法',
  1: 'Carr-Kobayashi-Burrows 修正方法'
}

const DEVIATION_FACTOR_METHODS = {
  0: 'Dranchuk-Abu-Kassem 方法',
  1: 'Dranchuk-Purvis-Robinson 方法',
  2: 'Hall-Yarborough 方法'
}

const toSelectOptions = (map) => Object.values(map).map(value => ({ label: value, value }))

const getInputValue = (keys, fallback = '') => {
  for (const key of keys) {
    const value = input.value?.[key]
    if (value !== undefined && value !== null && value !== '') return value
  }
  return fallback
}

const getMappedInputValue = (keys, map, fallback = '') => {
  const value = getInputValue(keys, fallback)
  if (value === '') return ''
  return map?.[value] ?? map?.[Number(value)] ?? value
}

const getPressurePositionFromResult = () => {
  const value = Number(input.value?.pressurePosition)
  if ([1, 2].includes(value)) return value

  const description = String(
      output.value?.dynamicOriginalGasInplaceMethodDescription ||
      output.value?.dynamicOriginalGasInPlaceMethodDescription ||
      rawNode.value?.dynamicOriginalGasInplaceMethodDescription ||
      rawNode.value?.dynamicOriginalGasInPlaceMethodDescription ||
      ''
  )
  return description.includes('井底流压') ? 2 : 1
}

const productionTemplateColumns = computed(() => {
  const isBottomPressure = getPressurePositionFromResult() === 2
  return [
    { label: '日期', unit: '日期' },
    { label: isBottomPressure ? '井底流压' : '井口流压', unit: 'MPa' },
    { label: '累产气量', unit: '10⁸m³' },
    { label: '累产水量', unit: '10⁴m³' }
  ]
})

const downloadProductionTemplate = async () => {
  try {
    const XLSX = await getXlsx()
    const rows = [
      productionTemplateColumns.value.map(column => column.label),
      productionTemplateColumns.value.map(column => column.unit)
    ]
    const sheet = XLSX.utils.aoa_to_sheet(rows)
    const workbook = XLSX.utils.book_new()
    XLSX.utils.book_append_sheet(workbook, sheet, '生产数据')
    XLSX.writeFile(workbook, `流动物质平衡生产数据模板-${wellName.value || 'well'}.xlsx`)
  } catch (error) {
    ElMessage.error(error?.message || '生产数据模板下载失败')
  }
}

const syncRecalculationForm = () => {
  const unstableLength = toNumber(getInputValue(['unstableFlowPeriodLength'], 180))
  const minimumWaterGasRatio = toNumber(getInputValue(['minimumWaterGasRatio', 'waterGasRatioLimit'], -1))
  const resultId = getResultId(rawNode.value) ?? getResultId(input.value) ?? ''
  const initializedKey = `${props.projectId}-${props.gasReservoirId}-${wellName.value}-${resultId}`
  const isFirstOpen = recalculationInitializedKey.value !== initializedKey
  recalculationInitializedKey.value = initializedKey
  recalculationForm.value = {
    minimumWaterGasRatioEnabled: isFirstOpen
      ? minimumWaterGasRatio !== null && minimumWaterGasRatio >= 0
      : recalculationForm.value.minimumWaterGasRatioEnabled,
    pressurePosition: getPressurePositionFromResult(),
    unstableFlowPeriodLength: unstableLength !== null ? unstableLength : 180,
    minimumWaterGasRatio: minimumWaterGasRatio !== null && minimumWaterGasRatio >= 0 ? minimumWaterGasRatio : 0.0602
  }
}

const requestRecalculation = () => {
  const unstableLength = Number(recalculationForm.value.unstableFlowPeriodLength)
  const minimumWaterGasRatio = Number(recalculationForm.value.minimumWaterGasRatio)
  if (!wellName.value) {
    ElMessage.warning('没有找到当前井名')
    return
  }
  if (!Number.isFinite(unstableLength) || unstableLength <= 0) {
    ElMessage.warning('不稳定流动段时间必须大于 0')
    return
  }
  if (recalculationForm.value.minimumWaterGasRatioEnabled && (!Number.isFinite(minimumWaterGasRatio) || minimumWaterGasRatio < 0)) {
    ElMessage.warning('生产水气比上限不能小于 0')
    return
  }

  emit('recalculate', {
    wellName: wellName.value,
    resultId: getResultId(rawNode.value) ?? getResultId(input.value),
    minimumWaterGasRatioEnabled: recalculationForm.value.minimumWaterGasRatioEnabled,
    pressurePosition: Number(recalculationForm.value.pressurePosition),
    unstableFlowPeriodLength: unstableLength,
    minimumWaterGasRatio
  })
}

const flowBalanceInputSections = computed(() => [
  {
    title: '气体性质',
    fields: [
      {
        key: 'gasType',
        label: '天然气类型',
        value: getMappedInputValue(['gasType'], GAS_TYPES),
        options: toSelectOptions(GAS_TYPES)
      },
      { key: 'specificGravity', label: '天然气比重(dless)', value: getInputValue(['specificGravity']) },
      { key: 'hydrogenSulfide', label: 'H₂S摩尔百分含量(%)', value: getInputValue(['hydrogenSulfide', 'h2s', 'H2S']) },
      { key: 'carbonDioxide', label: 'CO₂摩尔百分含量(%)', value: getInputValue(['carbonDioxide', 'co2', 'CO2']) },
      { key: 'nitrogen', label: 'N₂摩尔百分含量(%)', value: getInputValue(['nitrogen', 'n2', 'N2']) }
    ]
  },
  {
    title: '计算方法',
    fields: [
      {
        key: 'modificationMethod',
        label: '非烃气体修正方法',
        value: getMappedInputValue(['modificationMethod'], MODIFICATION_METHODS),
        options: toSelectOptions(MODIFICATION_METHODS)
      },
      {
        key: 'deviationFactorMethod',
        label: '天然气偏差系数计算方法',
        value: getMappedInputValue(['deviationFactorMethod'], DEVIATION_FACTOR_METHODS),
        options: toSelectOptions(DEVIATION_FACTOR_METHODS)
      }
    ]
  },
  {
    title: '其它数据',
    fields: [
      { key: 'originalPressure', label: '原始地层压力(MPa)', value: getInputValue(['originalPressure']) },
      { key: 'temperature', label: '地层温度(℃)', value: getInputValue(['temperature', 'formationTemperature']) }
    ]
  }
])

const hasFlowBalanceInputFields = computed(() =>
    flowBalanceInputSections.value.some(section =>
        section.fields.some(field => field.value !== undefined && field.value !== null && field.value !== '')
    )
)

const getOutputValue = (name) => {
  const value = output.value || {}
  if (name === 'gradient') return value.gradient ?? value.slope
  if (name === 'rsquared') return value.rsquared ?? value.r2
  if (name === 'reliablity') return value.reliablity ?? value.reliability ?? value.reliabilityDescription
  return value[name]
}

const formatOutputValue = (field, value) => {
  if (value === undefined || value === null || value === '') return ''
  const number = toNumber(value)
  if (number === null) return value

  const decimalPlaces = Number(field?.displayDecimal)
  const precision = Number.isInteger(decimalPlaces) && decimalPlaces >= 0 ? decimalPlaces : null
  if (field?.isScientificNotation) return formatSci(number, precision ?? 4)
  return precision === null ? number : number.toFixed(precision)
}

const outputFields = computed(() => {
  const interfaceFields = Array.isArray(resultData.value?.resultFields)
      ? resultData.value.resultFields
      : []

  if (interfaceFields.length) {
    return interfaceFields.map(field => ({
      key: field.name,
      label: `${field.name_cn || field.name}${field.unit_label ? `(${field.unit_label})` : ''}`,
      value: formatOutputValue(field, getOutputValue(field.name))
    }))
  }

  const fallbackFields = [
    { name: 'originalGasVolume', name_cn: '动态储量', unit_label: '10⁸m³', displayDecimal: 4 },
    { name: 'gfi', name_cn: '游离气动态储量', unit_label: '10⁸m³', displayDecimal: 4 },
    { name: 'gai', name_cn: '吸附气动态储量', unit_label: '10⁸m³', displayDecimal: 4 },
    { name: 'gradient', name_cn: '回归分析斜率', unit_label: 'MPa/10⁸m³', displayDecimal: 2, isScientificNotation: true },
    { name: 'intercept', name_cn: '回归分析截距', unit_label: 'MPa', displayDecimal: 4 },
    { name: 'rsquared', name_cn: 'R²', unit_label: 'dless', displayDecimal: 4 },
    { name: 'reliablity', name_cn: '结果可靠性' }
  ]

  return fallbackFields.map(field => ({
    key: field.name,
    label: `${field.name_cn}${field.unit_label ? `(${field.unit_label})` : ''}`,
    value: formatOutputValue(field, getOutputValue(field.name))
  }))
})

const equationText = computed(() => {
  if (!regression.value) return ''
  return `Y = ${formatSci(regression.value.slope)} * X + ${formatSci(regression.value.intercept)}\nR² = ${Number(regression.value.r2 || 0).toFixed(4)}`
})

const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms))

const createChartSeries = () => [
  {
    name: '全部数据点(MPa)',
    type: 'scatter',
    data: allChartPoints.value,
    symbolSize: 9,
    itemStyle: { color: '#a8a8a8', opacity: 0.72 },
    emphasis: { itemStyle: { opacity: 0.9 } },
    legend: false,
    silent: true,
    z: 1
  },
  {
    name: '数据点(MPa)',
    type: 'scatter',
    data: selectedChartPoints.value,
    symbolSize: 9,
    itemStyle: { color: '#416fc9', opacity: 0.82 },
    emphasis: { itemStyle: { opacity: 0.95 } },
    z: 3
  },
  {
    name: '回归线(MPa)',
    type: 'line',
    data: regressionLinePoints.value,
    symbol: 'none',
    lineStyle: { color: '#333', width: 2 },
    z: 4
  },
  {
    name: '平移线(MPa)',
    type: 'line',
    data: shiftLinePoints.value,
    symbol: 'none',
    lineStyle: { color: '#f4c430', width: 2 },
    z: 2
  }
].filter(item => item.data.length)

const legendItems = computed(() =>
    createChartSeries()
        .filter(item => item.legend !== false)
        .map(item => ({
          name: item.name,
          type: item.type,
          color: item.lineStyle?.color || item.itemStyle?.color || '#416fc9'
        }))
)

const legendStyle = computed(() => {
  if (legendPosition.value.x === null || legendPosition.value.y === null) {
    return { top: '42px', right: '22px' }
  }
  return {
    left: `${legendPosition.value.x}px`,
    top: `${legendPosition.value.y}px`
  }
})

const isLegendItemHidden = (name) => hiddenLegendNames.value.has(name)

function toggleLegendItem(name) {
  const next = new Set(hiddenLegendNames.value)
  if (next.has(name)) {
    next.delete(name)
  } else {
    next.add(name)
  }
  hiddenLegendNames.value = next
  renderChartSoon()
}

function filterLegendSeries(series) {
  return series.filter(item => !hiddenLegendNames.value.has(item.name))
}

function getEquationGraphicPosition() {
  if (equationGraphicPosition.value) return equationGraphicPosition.value
  const width = chart?.getWidth?.() || 800
  const height = chart?.getHeight?.() || 500
  return [Math.max(width - 290, 80), Math.max(height - 120, 80)]
}

function onLegendDrag(event) {
  if (!draggingLegend.value || !chartAreaEl.value) return
  const rect = chartAreaEl.value.getBoundingClientRect()
  const width = 190
  const height = 60
  legendPosition.value = {
    x: Math.max(0, Math.min(event.clientX - rect.left - legendDragOffset.value.x, rect.width - width)),
    y: Math.max(34, Math.min(event.clientY - rect.top - legendDragOffset.value.y, rect.height - height))
  }
}

function stopLegendDrag() {
  if (!draggingLegend.value) return
  draggingLegend.value = false
  document.body.style.cursor = ''
  document.body.style.userSelect = ''
  window.removeEventListener('mousemove', onLegendDrag)
  window.removeEventListener('mouseup', stopLegendDrag)
}

function startLegendDrag(event) {
  if (!chartAreaEl.value) return
  const target = event.currentTarget
  const rect = target.getBoundingClientRect()
  const areaRect = chartAreaEl.value.getBoundingClientRect()
  event.preventDefault()
  legendPosition.value = {
    x: rect.left - areaRect.left,
    y: rect.top - areaRect.top
  }
  legendDragOffset.value = {
    x: event.clientX - rect.left,
    y: event.clientY - rect.top
  }
  draggingLegend.value = true
  document.body.style.cursor = 'move'
  document.body.style.userSelect = 'none'
  window.addEventListener('mousemove', onLegendDrag)
  window.addEventListener('mouseup', stopLegendDrag)
}

const resolveFlowBalanceResultRequest = async () => {
  const directId = getResultId(rawNode.value)
  if (directId) {
    return {
      id: directId,
      row: isFlowBalanceRow(rawNode.value) ? rawNode.value : null
    }
  }

  const pressureRes = await materialBalanceApi.getAverageFormationPressure(
      props.projectId,
      props.gasReservoirId,
      wellName.value,
      { silentError: true }
  )
  const flowRow = getRows(pressureRes.data).find(isFlowBalanceRow)
  const id = getResultId(flowRow)
  if (!id) throw new Error('未找到流动平衡结果 ID')
  return { id, row: flowRow }
}

async function loadFlowBalanceResult() {
  const requestId = ++requestSeq

  if (!props.projectId || !props.gasReservoirId || !wellName.value) return

  loading.value = true
  try {
    let nextResultData = null
    let lastError = null

    for (let attempt = 0; attempt < 10; attempt++) {
      if (requestId !== requestSeq) return

      try {
        const { id, row } = await resolveFlowBalanceResultRequest()
        const resultRes = await materialBalanceApi.getResult(
            props.projectId,
            props.gasReservoirId,
            id,
            null,
            null,
            { silentError: true }
        )
        const result = resultRes.data || {}
        nextResultData = {
          ...result,
          output: {
            ...(row || rawNode.value || {}),
            ...(result.output || result.result || {})
          }
        }
        break
      } catch (error) {
        lastError = error
        if (attempt === 9) break
        await sleep(1000)
      }
    }

    if (!nextResultData) throw lastError || new Error('流动平衡结果加载失败')
    if (requestId !== requestSeq) return

    equationGraphicPosition.value = null
    legendPosition.value = { x: null, y: null }
    hiddenLegendNames.value = new Set()
    resultData.value = nextResultData
    syncRecalculationForm()
    await nextTick()
    renderChart()
  } catch (error) {
    if (requestId !== requestSeq) return
    console.error('[FlowBalanceContent] load failed', error)
  } finally {
    if (requestId === requestSeq) loading.value = false
  }
}

function renderChart() {
  if (!chart || activeChartTab.value !== 'chart') return

  const bounds = chartBounds.value
  const series = filterLegendSeries(createChartSeries())

  chart.clear()
  chart.setOption({
    animation: false,
    title: {
      text: output.value?.dynamicOriginalGasInplaceMethodDescription || '流动物质平衡-基于井底流压',
      left: 'center',
      top: 10,
      textStyle: { color: '#333', fontSize: 15, fontWeight: 600 }
    },
    tooltip: {
      trigger: 'item',
      formatter: params => {
        const value = Array.isArray(params.value) ? params.value : []
        return `${params.marker}${params.seriesName}<br/>Gp: ${value[0] ?? ''}<br/>Pwf: ${value[1] ?? ''} MPa`
      }
    },
    legend: { show: false },
    grid: { left: 70, right: 35, top: 72, bottom: 56 },
    xAxis: {
      type: 'value',
      name: 'Gp(10⁸m³)',
      nameLocation: 'middle',
      nameGap: 32,
      min: bounds.xMin,
      max: bounds.xMax,
      splitLine: { show: true, lineStyle: { color: '#dfe7f2' } },
      minorSplitLine: { show: true, lineStyle: { color: '#f0f4fa' } }
    },
    yAxis: {
      type: 'value',
      name: 'Pwf / Zwf(MPa)',
      nameLocation: 'middle',
      nameGap: 48,
      min: bounds.yMin,
      max: bounds.yMax,
      splitLine: { show: true, lineStyle: { color: '#dfe7f2' } },
      minorSplitLine: { show: true, lineStyle: { color: '#f0f4fa' } }
    },
    graphic: equationText.value ? [{
      id: 'flow-balance-equation',
      type: 'group',
      z: 1000,
      zlevel: 10,
      draggable: true,
      cursor: 'move',
      position: getEquationGraphicPosition(),
      ondrag: function () {
        equationGraphicPosition.value = [
          Number.isFinite(this.x) ? this.x : this.position?.[0] || 0,
          Number.isFinite(this.y) ? this.y : this.position?.[1] || 0
        ]
      },
      children: [
        {
          type: 'rect',
          shape: { x: 0, y: 0, width: 220, height: 54, r: 3 },
          style: {
            fill: 'rgba(255,255,255,0.9)',
            stroke: '#dcdfe6',
            lineWidth: 1,
            shadowBlur: 4,
            shadowColor: 'rgba(0,0,0,0.08)'
          }
        },
        {
          type: 'text',
          left: 10,
          top: 7,
          style: {
            text: equationText.value,
            fill: '#333',
            font: '13px Arial',
            lineHeight: 20
          }
        }
      ]
    }] : [],
    series
  }, true)
}

function renderChartSoon() {
  nextTick(() => {
    requestAnimationFrame(() => {
      chart?.resize()
      renderChart()
    })
  })
}

function toggleParamsPanel() {
  paramsCollapsed.value = !paramsCollapsed.value
  renderChartSoon()
}

function onParamsPanelResize(event) {
  if (!resizingParamsPanel.value) return
  const left = paramsPanelEl.value?.getBoundingClientRect().left || 0
  paramsPanelWidth.value = Math.max(400, Math.min(620, event.clientX - left))
  chart?.resize()
}

function stopParamsPanelResize() {
  if (!resizingParamsPanel.value) return
  resizingParamsPanel.value = false
  document.body.style.cursor = ''
  document.body.style.userSelect = ''
  window.removeEventListener('mousemove', onParamsPanelResize)
  window.removeEventListener('mouseup', stopParamsPanelResize)
  renderChartSoon()
}

function startParamsPanelResize(event) {
  if (paramsCollapsed.value) return
  event.preventDefault()
  resizingParamsPanel.value = true
  document.body.style.cursor = 'col-resize'
  document.body.style.userSelect = 'none'
  window.addEventListener('mousemove', onParamsPanelResize)
  window.addEventListener('mouseup', stopParamsPanelResize)
}

watch(() => [props.node, props.node?.id, wellName.value, props.projectId, props.gasReservoirId], loadFlowBalanceResult, { immediate: true })
watch(activeChartTab, renderChartSoon)

onMounted(() => {
  chart = echarts.init(chartEl.value)
  window.addEventListener('resize', renderChartSoon)
  renderChartSoon()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', renderChartSoon)
  stopParamsPanelResize()
  stopLegendDrag()
  chart?.dispose()
  chart = null
  requestSeq++
})
</script>

<template>
  <div class="flow-balance-wrap" v-loading="loading">
    <aside
        ref="paramsPanelEl"
        class="params-panel"
        :class="{ collapsed: paramsCollapsed }"
        :style="{
        width: paramsCollapsed ? '22px' : `${paramsPanelWidth}px`,
        minWidth: paramsCollapsed ? '22px' : `${paramsPanelWidth}px`
      }"
    >
      <div v-if="paramsCollapsed" class="panel-collapsed-tab" @click="toggleParamsPanel">
        参数设置
      </div>

      <template v-if="!paramsCollapsed">
        <div class="panel-head">
          <span>参数设置</span>
          <button class="panel-toggle" type="button" title="收起参数设置" @click="toggleParamsPanel">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="#777">
              <path d="M16,12V4H17V2H7V4H8V12L6,14V16H11.2V22H12.8V16H18V14L16,12Z"/>
            </svg>
          </button>
        </div>

        <div v-if="activeParamTab === 'input'" class="panel-body">
          <div v-if="!hasFlowBalanceInputFields" class="empty">暂无接口输入参数</div>
          <template v-else>
            <template v-for="section in flowBalanceInputSections" :key="section.title">
              <div class="sec-label">{{ section.title }}</div>
              <div class="field-grid">
                <div
                    v-for="item in section.fields"
                    v-show="item.value !== undefined && item.value !== null && item.value !== ''"
                    :key="item.key"
                    class="field"
                >
                  <label>{{ item.label }}</label>
                  <el-select v-if="item.options" size="small" disabled :model-value="item.value" style="width: 100%">
                    <el-option
                        v-for="option in item.options"
                        :key="option.value"
                        :label="option.label"
                        :value="option.value"
                    />
                  </el-select>
                  <el-input v-else size="small" readonly :model-value="item.value" />
                </div>
              </div>
            </template>

            <div class="sec-label">计算条件</div>
            <div class="recalculation-condition unstable-condition">
              <span>不稳定流动段时间(d)</span>
              <div class="recalculation-value">
                <el-input-number
                    v-model="recalculationForm.unstableFlowPeriodLength"
                    size="small"
                    :min="0.0001"
                    :step="1"
                    :controls="false"
                    style="width: 100%"
                />
              </div>
            </div>
            <div class="recalculation-condition wgr-condition">
              <el-checkbox v-model="recalculationForm.minimumWaterGasRatioEnabled">
                生产水气比上限(m³/10⁴m³)
              </el-checkbox>
              <div class="recalculation-value">
                <el-input-number
                    v-model="recalculationForm.minimumWaterGasRatio"
                    size="small"
                    :disabled="!recalculationForm.minimumWaterGasRatioEnabled"
                    :min="0"
                    :step="0.0001"
                    :precision="4"
                    :controls="false"
                    style="width: 100%"
                />
              </div>
              <el-button size="small" :loading="props.recalculating" @click="requestRecalculation">
                重新计算
              </el-button>
            </div>

            <div class="sec-label">生产数据</div>
            <div class="btn-row">
              <el-button size="small" @click="downloadProductionTemplate">模版下载</el-button>
              <el-button size="small">导入</el-button>
            </div>
          </template>
        </div>

        <div v-else class="panel-body">
          <div class="sec-label">输出结果</div>
          <div v-if="!outputFields.length" class="empty">暂无接口输出结果</div>
          <div v-else class="field-grid">
            <div v-for="item in outputFields" :key="item.key" class="field">
              <label>{{ item.label }}</label>
              <el-input size="small" readonly :model-value="item.value" />
            </div>
          </div>
        </div>

        <div class="param-tabs">
          <div class="param-tab" :class="{ active: activeParamTab === 'input' }" @click="activeParamTab = 'input'">
            输入
          </div>
          <div class="param-tab" :class="{ active: activeParamTab === 'output' }" @click="activeParamTab = 'output'">
            输出
          </div>
        </div>

        <div class="params-resizer" @mousedown="startParamsPanelResize"></div>
      </template>
    </aside>

    <main ref="chartAreaEl" class="chart-area">
      <div class="dynamic-result-tabs">
        <button type="button" class="dynamic-result-tab active" :title="chartTabTitle">
          <span class="dynamic-result-tab-text">{{ chartTabTitle }}</span>
        </button>
      </div>

      <div v-show="activeChartTab === 'chart'" ref="chartEl" class="chart"></div>
      <div
          v-if="activeChartTab === 'chart' && legendItems.length"
          class="floating-chart-legend"
          :class="{ dragging: draggingLegend }"
          :style="legendStyle"
          @mousedown="startLegendDrag"
      >
        <div
            v-for="item in legendItems"
            :key="item.name"
            class="floating-legend-item"
            :class="{ hidden: isLegendItemHidden(item.name) }"
            title="点击显示/隐藏"
            @mousedown.stop
            @click.stop="toggleLegendItem(item.name)"
        >
          <span
              :class="item.type === 'line' ? 'legend-line' : 'legend-dot'"
              :style="{
              backgroundColor: isLegendItemHidden(item.name) ? 'transparent' : item.color,
              borderColor: item.color
            }"
          ></span>
          <span>{{ item.name }}</span>
        </div>
      </div>

      <div v-if="activeChartTab === 'table'" class="table-wrap">
        <el-table :data="tableRows" size="small" height="100%" border>
          <el-table-column prop="index" label="序号" width="70" />
          <el-table-column prop="gp" label="Gp(10⁸m³)" min-width="150" />
          <el-table-column prop="pressure" label="Pwf / Zwf(MPa)" min-width="150" />
          <el-table-column prop="selected" label="回归点" min-width="90" />
        </el-table>
      </div>

      <div class="bottom-chart-tabs">
        <button type="button" class="bottom-chart-tab" :class="{ active: activeChartTab === 'table' }" @click="activeChartTab = 'table'">
          数据列表
        </button>
        <button type="button" class="bottom-chart-tab" :class="{ active: activeChartTab === 'chart' }" :title="chartTabTitle" @click="activeChartTab = 'chart'">
          结果分析图
        </button>
      </div>
    </main>
  </div>
</template>

<style lang="scss" scoped>
.flow-balance-wrap {
  height: 100%;
  display: flex;
  min-height: 0;
  background: #fff;
  overflow: hidden;
}

.params-panel {
  width: 400px;
  min-width: 400px;
  border-right: 1px solid #e0e0e0;
  display: flex;
  flex-direction: column;
  position: relative;
  overflow: hidden;
  transition: width 0.16s ease, min-width 0.16s ease;

  &.collapsed {
    background: transparent;
    border-right: 0;
  }
}

.panel-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 7px 12px 6px;
  border-bottom: 1px solid #f0f0f0;
  flex-shrink: 0;
  font-size: 13px;
  color: #333;
}

.panel-toggle {
  width: 20px;
  height: 20px;
  padding: 0;
  border: 0;
  background: transparent;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  border-radius: 2px;

  &:hover {
    background: #eef4ff;
  }
}

.panel-collapsed-tab {
  width: 22px;
  height: 76px;
  display: flex;
  align-items: center;
  justify-content: center;
  writing-mode: vertical-rl;
  text-orientation: mixed;
  font-size: 13px;
  color: #333;
  cursor: pointer;
  background: #fff;
  border: 1px solid #e0e0e0;
  border-left: 0;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);

  &:hover {
    background: #eef4ff;
    color: #1f6fd6;
  }
}

.params-resizer {
  position: absolute;
  top: 0;
  right: -3px;
  width: 6px;
  height: 100%;
  cursor: col-resize;
  z-index: 4;

  &:hover {
    background: rgba(64, 132, 217, 0.18);
  }
}

.panel-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 4px 12px 14px;
}

.param-tabs {
  display: flex;
  height: 30px;
  border-top: 1px solid #e0e0e0;
  flex-shrink: 0;
}

.param-tab {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  color: #555;
  cursor: pointer;
  border-right: 1px solid #e0e0e0;

  &:last-child {
    border-right: none;
  }

  &.active {
    background-color: #f4d000;
    color: #1a1a1a;
    font-weight: 600;
  }
}

.sec-label {
  font-weight: 500;
  color: #333;
  font-size: 13px;
  margin: 10px 0 7px;

  &:first-child {
    margin-top: 4px;
  }
}

.field-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  column-gap: 24px;
}

.field {
  margin-bottom: 9px;

  label {
    display: block;
    color: #555;
    font-size: 12px;
    margin-bottom: 3px;
  }
}

.recalculation-condition {
  min-height: 26px;
  display: flex;
  align-items: center;
  font-size: 12px;

  :deep(.el-checkbox__label),
  :deep(.el-checkbox.is-checked .el-checkbox__label) {
    padding-left: 4px;
    color: #000;
    font-size: 12px;
  }

  :deep(.el-checkbox__input.is-checked .el-checkbox__inner) {
    background-color: #000;
    border-color: #000;
  }

  :deep(.el-checkbox__input.is-focus .el-checkbox__inner),
  :deep(.el-checkbox__inner:hover) {
    border-color: #000;
  }
}

.unstable-condition {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 66px;
  gap: 6px;
}

.wgr-condition {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 72px 68px;
  gap: 6px;

  :deep(.el-checkbox) {
    min-width: 0;
    margin-right: 0;
  }

  :deep(.el-checkbox__label) {
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
}

.recalculation-value {
  width: 66px;
  min-width: 66px;
}

.btn-row {
  display: flex;
  gap: 8px;
}

.empty {
  color: #888;
  font-size: 13px;
  line-height: 1.5;
  padding: 4px 0 10px;
}

.chart-area {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: #fff;
  overflow: hidden;
  position: relative;
}

.dynamic-result-tabs {
  height: 34px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  border-bottom: 1px solid #e4e7ed;
  background: #fafafa;
  overflow-x: auto;
  overflow-y: hidden;
}

.dynamic-result-tab {
  height: 34px;
  max-width: 360px;
  border: 0;
  border-right: 1px solid #e4e7ed;
  border-bottom: 2px solid #409eff;
  background: #fff;
  color: #409eff;
  font-size: 14px;
  font-weight: 600;
  display: flex;
  align-items: center;
  padding: 0 12px;
  cursor: default;
  white-space: nowrap;
}

.dynamic-result-tab-text {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chart {
  flex: 1;
  min-height: 0;
  width: 100%;
}

.floating-chart-legend {
  position: absolute;
  z-index: 8;
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 150px;
  padding: 8px 10px;
  border: 1px solid #e4e7ed;
  border-radius: 3px;
  background: rgba(255, 255, 255, 0.92);
  color: #333;
  font-size: 12px;
  line-height: 1.2;
  cursor: move;
  user-select: none;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);

  &.dragging {
    opacity: 0.86;
  }
}

.floating-legend-item {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  white-space: nowrap;

  &.hidden {
    color: #aaa;
  }
}

.legend-dot {
  width: 10px;
  height: 10px;
  border: 1px solid;
  border-radius: 50%;
  flex-shrink: 0;
}

.legend-line {
  width: 18px;
  height: 2px;
  border: 0;
  flex-shrink: 0;
}

.table-wrap {
  flex: 1;
  min-height: 0;
  padding: 8px;
  overflow: hidden;
}

.bottom-chart-tabs {
  display: flex;
  align-items: flex-end;
  height: 30px;
  flex-shrink: 0;
  background: #fff;
  border-top: 1px solid #e4e7ed;
}

.bottom-chart-tab {
  min-width: 88px;
  height: 30px;
  border: 0;
  border-right: 1px solid #e4e7ed;
  border-top: 2px solid transparent;
  background: #fff;
  color: #333;
  font-size: 13px;
  cursor: pointer;
  white-space: nowrap;

  &:hover {
    color: #409eff;
  }

  &.active {
    color: #409eff;
    border-top-color: #409eff;
    font-weight: 600;
  }
}
</style>
