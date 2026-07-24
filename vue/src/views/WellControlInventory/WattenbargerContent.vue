<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import { typicalCurveApi } from '@/api/docker'
import wattenbargerTypeCurveData from '@/constants/typeCurves/wattenbarger.json'

const props = defineProps({
  node: Object,
  projectId: [Number, String],
  gasReservoirId: [Number, String],
  recalculating: Boolean
})
const emit = defineEmits(['recalculate'])

const activePanelTab = ref('input')
const activeChartTab = ref('chart')
const chartEl = ref(null)
const chartAreaEl = ref(null)
const paramsPanelEl = ref(null)
const paramsPanelWidth = ref(238)
const paramsCollapsed = ref(false)
const resizingParamsPanel = ref(false)
const legendPosition = ref({ x: null, y: null })
const draggingLegend = ref(false)
const legendDragOffset = ref({ x: 0, y: 0 })
const hiddenLegendNames = ref(new Set())
const fetchedRaw = ref(null)
const readonlySelectKey = ref(0)
let chart = null

const TYPE_CURVE_COLORS = ['#4fa3ff', '#66c2a5', '#f6c85f', '#f2711c', '#9b59b6', '#5dade2', '#e67e22', '#8dd3c7', '#d98880', '#7f8c8d', '#c39bd3', '#76d7c4']

const WATTENBARGER_XF_VALUES = [2, 4, 8, 16, 32, 64]

const zipCurvePoints = (xValues, yValues) => asArray(yValues).map((y, index) => {
  const x = Number(xValues[index])
  const value = Number(y)
  if (!Number.isFinite(x) || !Number.isFinite(value) || x <= 0 || value <= 0) return null
  return [x, value]
}).filter(Boolean)

const normalizePayload = (res) => res?.data?.data ?? res?.data ?? res
const raw = computed(() => fetchedRaw.value || props.node?.raw || {})
const result = computed(() => ({
  ...(raw.value?.output || raw.value?.outputs?.[0]?.output || {}),
  ...(raw.value?.analysis || {}),
  ...(raw.value?.result || {})
}))
const input = computed(() => raw.value?.input || raw.value?.inputs || raw.value?.parameter || {})
const chartItems = computed(() => raw.value?.chartItems || raw.value?.outputs?.[0]?.chartItems || [])
const isRealResultId = (id) => Boolean(id) && !/^wattenbarger-/i.test(String(id))
const resultId = computed(() =>
    [props.node?.resultId, props.node?.raw?.resultId, props.node?.raw?.nodeId, props.node?.raw?.id, props.node?.id]
        .find(isRealResultId)
)
const wellName = computed(() =>
    props.node?.wellName || props.node?.raw?.wellName || raw.value?.wellName || input.value?.wellName || ''
)
const resultTabTitle = computed(() => `诊断曲线-Wattenbarger-${wellName.value || '当前井'}-分析结果`)

const PRODUCTION_TEMPLATE_COLUMNS = [
  { label: '日期', unit: '无' },
  { label: '井底流压', unit: 'MPa' },
  { label: '气产量', unit: '10^4m3/d' },
  { label: '累产气量', unit: '10^8m3' },
  { label: '累产水量', unit: '10^4m3' }
]

const asArray = (value) => Array.isArray(value) ? value : []

const getXlsx = async () => import('xlsx')

const saveWorkbook = (XLSX, workbook, filename) => {
  XLSX.writeFile(workbook, filename)
}

const downloadProductionTemplate = async () => {
  const XLSX = await getXlsx()
  const rows = [
    PRODUCTION_TEMPLATE_COLUMNS.map(column => column.label),
    PRODUCTION_TEMPLATE_COLUMNS.map(column => column.unit)
  ]
  const sheet = XLSX.utils.aoa_to_sheet(rows)
  const workbook = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(workbook, sheet, '生产数据')
  saveWorkbook(XLSX, workbook, `Wattenbarger生产数据模板-${wellName.value || 'well'}.xlsx`)
}

const collectCandidateArrays = (source, keys) => {
  const list = []
  const seen = new Set()

  const add = (value) => {
    if (!Array.isArray(value) || seen.has(value)) return
    seen.add(value)
    list.push(value)
  }

  const visit = (value, depth = 0) => {
    if (!value || depth > 3) return
    keys.forEach(key => add(value[key]))
    ;['result', 'output', 'graph', 'chart', 'dataSet', 'payload'].forEach(key => visit(value[key], depth + 1))
    if (Array.isArray(value.outputs)) value.outputs.forEach(item => visit(item, depth + 1))
  }

  visit(source)
  return list
}

const records = computed(() => {
  const arrays = collectCandidateArrays(raw.value, ['items', 'outputItems', 'records', 'data', 'productionData', 'rows'])
  return arrays.find(item => item.length && !item[0]?.data && !item[0]?.items) || []
})

const getValue = (source, keys, fallback = '') => {
  for (const key of keys) {
    const value = source?.[key]
    if (value !== undefined && value !== null && value !== '') return value
  }
  return fallback
}

const methodLabel = (value, labels, fallback = '') => {
  if (value === undefined || value === null || value === '') return fallback
  if (typeof value === 'number') return labels[value] || String(value)
  return value
}

const inputValue = (keys, fallback = '') => getValue(input.value, keys, fallback)
const resultValue = (keys, fallback = '') => getValue(result.value, keys, fallback)

const gasType = computed(() => methodLabel(inputValue(['gasType'], 2), ['', '', '干气'], '干气'))
const modificationMethod = computed(() =>
    methodLabel(inputValue(['modificationMethod'], 0), ['Wichert-Aziz 修正方法', 'Carr-Kobayashi-Burrous 修正方法'], 'Wichert-Aziz 修正方法')
)
const deviationMethod = computed(() =>
    methodLabel(inputValue(['deviationFactorMethod'], 0), ['Dranchuk-Abu-Kassem 方法', 'Dranchuk-Purvis-Robinson 方法', 'Hall-Yarborough 方法'], 'Dranchuk-Abu-Kassem 方法')
)
const viscosityMethod = computed(() =>
    methodLabel(inputValue(['viscosityMethod'], 0), ['Lee-Gonzalez-Eakin 方法', 'Carr-Kobayashi-Burrous 方法', 'Sutton 方法'], 'Lee-Gonzalez-Eakin 方法')
)
const fittingModeValue = ref('automatic')
const dataSizeValue = ref('300')
const initScanDataSizeValue = ref('10')
const fineScanDataSizeValue = ref('30')
const waterGasRatioEnabled = ref(true)
const waterGasRatioLimitValue = ref('0.0602')

watch(input, (value) => {
  fittingModeValue.value = value?.isSkipFitting ? 'manual' : 'automatic'
  dataSizeValue.value = String(value?.dataSize ?? 300)
  initScanDataSizeValue.value = String(value?.initScanDataSize ?? 10)
  fineScanDataSizeValue.value = String(value?.fineScanDataSize ?? value?.fineSandDataSize ?? 30)

  const limit = value?.minimumWaterGasRatio ?? value?.waterGasRatioLimit
  waterGasRatioEnabled.value = limit === undefined ? true : Number(limit) > 0
  waterGasRatioLimitValue.value = Number(limit) > 0 ? String(limit) : '0.0602'
}, { immediate: true })

const handleRecalculate = () => {
  if (props.recalculating) return

  const dataSize = Number(dataSizeValue.value)
  const initScanDataSize = Number(initScanDataSizeValue.value)
  const fineScanDataSize = Number(fineScanDataSizeValue.value)
  const minimumWaterGasRatio = waterGasRatioEnabled.value
    ? Number(waterGasRatioLimitValue.value)
    : -1

  if (![dataSize, initScanDataSize, fineScanDataSize].every(value => Number.isInteger(value) && value > 0)) {
    ElMessage.warning('抽稀点数、粗扫点数和精扫点数必须是大于 0 的整数')
    return
  }
  if (waterGasRatioEnabled.value && !(minimumWaterGasRatio > 0)) {
    ElMessage.warning('生产水气比上限必须大于 0')
    return
  }

  emit('recalculate', {
    wellName: wellName.value,
    isSkipFitting: fittingModeValue.value === 'manual',
    dataSize,
    initScanDataSize,
    fineScanDataSize,
    minimumWaterGasRatio
  })
}

const GAS_TYPE_OPTIONS = ['干气', '湿气', '凝析气']
const MODIFICATION_METHOD_OPTIONS = ['Wichert-Aziz 修正方法', 'Carr-Kobayashi-Burrous 修正方法']
const DEVIATION_METHOD_OPTIONS = ['Dranchuk-Abu-Kassem 方法', 'Dranchuk-Purvis-Robinson 方法', 'Hall-Yarborough 方法']
const VISCOSITY_METHOD_OPTIONS = ['Lee-Gonzalez-Eakin 方法', 'Carr-Kobayashi-Burrous 方法', 'Sutton 方法']
const MATERIAL_BALANCE_TYPE_OPTIONS = ['封闭气藏', '定容气藏', '页岩气藏']

const inputFields = computed(() => ({
  gas: [
    { label: '天然气类型', value: gasType.value, select: true, options: GAS_TYPE_OPTIONS },
    { label: '天然气比重(dless)', value: inputValue(['specificGravity'], 0.58) },
    { label: 'H₂S摩尔百分含量(%)', value: inputValue(['hydrogenSulfide'], 4.62) },
    { label: 'CO₂摩尔百分含量(%)', value: inputValue(['carbonDioxide'], 3.96) },
    { label: 'N₂摩尔百分含量(%)', value: inputValue(['nitrogen'], 0) }
  ],
  method: [
    { label: '非烃气体修正方法', value: modificationMethod.value, select: true, options: MODIFICATION_METHOD_OPTIONS },
    { label: '天然气偏差系数计算方法', value: deviationMethod.value, select: true, options: DEVIATION_METHOD_OPTIONS },
    { label: '天然气粘度计算方法', value: viscosityMethod.value, select: true, options: VISCOSITY_METHOD_OPTIONS }
  ],
  other: [
    { label: '原始地层压力(MPa)', value: inputValue(['originalFormationPressure'], 50) },
    { label: '气井地层温度(℃)', value: inputValue(['formationTemperature'], 80) },
    { label: '井筒半径(m)', value: inputValue(['wellboreRadius', 'wellRadius'], 0.06) },
    { label: '动态地质储量(10⁸m³)', value: resultValue(['originalGasVolume'], inputValue(['originalGasVolume'], '')) },
    { label: '物质平衡方程类型', value: inputValue(['materialBalanceTypeDesc', 'dynamicOriginalGasInplaceMethodDescription'], '封闭气藏'), select: true, options: MATERIAL_BALANCE_TYPE_OPTIONS },
    { label: '储层厚度(m)', value: inputValue(['reservoirThickness'], 128.33) },
    { label: '储层孔隙度(%)', value: inputValue(['reservoirPorosity', 'porosity'], 4.23) },
    { label: '束缚水饱和度(%)', value: inputValue(['waterSaturation'], 26.16) },
    { label: '地层水压缩系数(MPa⁻¹)', value: inputValue(['waterCompressionCoefficient'], '3.7445E-4') },
    { label: '储层岩石压缩系数(MPa⁻¹)', value: inputValue(['rockCompressionCoefficient'], '1.0000E-4') }
  ]
}))

const outputFields = computed(() => [
  { label: '√KXf(m·mD¹/²)', value: resultValue(['fractureConductivityCoefficient'], '') },
  { label: '有效裂缝半长(m)', value: resultValue(['effectiveFractureHalfLength'], '') },
  { label: '储层长度(m)', value: resultValue(['reservoirLength'], '') },
  { label: '储层宽度(m)', value: resultValue(['reservoirWidth'], '') },
  { label: '井控面积(km²)', value: resultValue(['wellControlArea'], '') },
  { label: '偏心距离(m)', value: resultValue(['eccentricDistance'], '') }
])
const hasOutputResults = computed(() => outputFields.value.length > 0)

const legendItems = computed(() => [
  { name: 'qDd-实际数据', color: '#7ee000' },
  { name: '1/DER-实际数据', color: '#00a020' },
])

const legendStyle = computed(() => {
  if (legendPosition.value.x === null || legendPosition.value.y === null) {
    return { top: '36px', right: '18px' }
  }
  return {
    left: `${legendPosition.value.x}px`,
    top: `${legendPosition.value.y}px`
  }
})

function isLegendItemHidden(name) {
  return hiddenLegendNames.value.has(name)
}

function isLegendSeriesVisible(name) {
  const hidden=hiddenLegendNames.value
  //qDd同时控制散点+曲线
  if(name==='qDd-实际数据'||/^qDd-/i.test(name))  return !hidden.has('qDd-实际数据')
  //DER同时控制散点+曲线
  if(name==='1/DER-实际数据'||/^DER-/i.test(name))  return !hidden.has('1/DER-实际数据')

  return true
  // if (!legendItems.value.some(item => item.name === name)) return true
  // return !hiddenLegendNames.value.has(name)
}

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

function applyLegendVisibility(series) {
  return series.filter(item => isLegendSeriesVisible(item.name))
}

function keepReadonlySelectValue() {
  readonlySelectKey.value += 1
}

const tableRows = computed(() => {
  if (records.value.length) {
    return records.value.map((item, index) => ({
      index: index + 1,
      pseudotime: item.pseudotime ?? item.tcaDd ?? item.tCaDd ?? item.xValue ?? '',
      regularizedProduction: item.regularizedProduction ?? item.qDd ?? item.qdd ?? '',
      regularizedPressureDerivativeReciprocal: item.regularizedPressureDerivativeReciprocal ?? item.DER ?? '',
      regularizedPressureIntegralDerivativeReciprocal: item.regularizedPressureIntegralDerivativeReciprocal ?? item.DERd ?? ''
    }))
  }

  return [
    [0.001, 1.318, 0.648, 0.512],
    [0.003, 1.176, 0.592, 0.468],
    [0.01, 0.934, 0.511, 0.416],
    [0.03, 0.742, 0.438, 0.364],
    [0.1, 0.516, 0.351, 0.302],
    [0.3, 0.372, 0.284, 0.251],
    [1, 0.248, 0.221, 0.206]
  ].map(([pseudotime, regularizedProduction, regularizedPressureDerivativeReciprocal, regularizedPressureIntegralDerivativeReciprocal], index) => ({
    index: index + 1,
    pseudotime,
    regularizedProduction,
    regularizedPressureDerivativeReciprocal,
    regularizedPressureIntegralDerivativeReciprocal
  }))
})

const getPoint = (item, xKeys, yKeys) => {
  const x = getValue(item, xKeys, null)
  const y = getValue(item, yKeys, null)
  if (x === null || y === null || Number(x) <= 0 || Number(y) <= 0) return null
  return [Number(x), Number(y)]
}

const getSeriesPointItems = (item) =>
    asArray(item?.data || item?.items || item?.points || item?.values || item?.children || item?.chartData)

const getSeriesName = (item, index = 0) =>
    item?.name || item?.title || item?.label || item?.legendName || item?.yAxisLabel || item?.yAxisField || `曲线${index + 1}`

const X_KEYS = ['xValue', 'x', 'pseudotime', 'tCaDd', 'tcaDd', 'tcaD', 'tD', 'td']
const toSeriesPoints = (item, yKeys = ['yValue', 'y', 'value', 'regularizedProduction', 'regularizedPressureDerivativeReciprocal', 'regularizedPressureIntegralDerivativeReciprocal', 'qDd', 'qdd', 'DER', 'DERd']) =>
    getSeriesPointItems(item)
        .map(point => getPoint(point, X_KEYS, yKeys))
        .filter(Boolean)

const chartSeriesItems = computed(() =>
    (chartItems.value.length ? [chartItems.value] : collectCandidateArrays(raw.value, [
      'typicalCurveItems', 'curveItems', 'typeCurves', 'typicalCurves', 'templateCurves', 'series', 'curves', 'graphs'
    ]))
        .flat()
        .filter(item => item && typeof item === 'object' && getSeriesPointItems(item).length)
)

const actualSeries = computed(() => {
  const qDd = []
  const DER = []
  const DERd = []

  chartSeriesItems.value.forEach((item, index) => {
    const name = getSeriesName(item, index)
    const field = String(item?.yAxisField || item?.yField || item?.field || '').toLowerCase()
    const points = toSeriesPoints(item, [item?.yAxisField, item?.yField, item?.field, 'yValue', 'y', 'value'].filter(Boolean))
    if (/DERd/i.test(name) || field === 'derd' || field === 'regularizedpressureintegralderivativereciprocal') DERd.push(...points)
    else if (/DER/i.test(name) || field === 'der' || field === 'regularizedpressurederivativereciprocal') DER.push(...points)
    else if (/qdd/i.test(name) || field === 'qdd' || field === 'regularizedproduction') qDd.push(...points)
  })

  records.value.forEach(item => {
    const qDdPoint = getPoint(item, X_KEYS, ['regularizedProduction', 'qDd', 'qdd', 'yValue'])
    const DERPoint = getPoint(item, X_KEYS, ['regularizedPressureDerivativeReciprocal', 'DER'])
    const DERdPoint = getPoint(item, X_KEYS, ['regularizedPressureIntegralDerivativeReciprocal', 'DERd'])
    if (qDdPoint) qDd.push(qDdPoint)
    if (DERPoint) DER.push(DERPoint)
    if (DERdPoint) DERd.push(DERdPoint)
  })

  return { qDd, DER, DERd }
})

const typeCurves = computed(() => {
  const xValues = asArray(wattenbargerTypeCurveData.tcaDd)

  return WATTENBARGER_XF_VALUES.flatMap((xf) => {
    const qDdData = zipCurvePoints(xValues, wattenbargerTypeCurveData[`qDdYexf${xf}`])
    const derData = zipCurvePoints(xValues, wattenbargerTypeCurveData[`DERYexf${xf}`])
    return [
      {name: `qDd-Yexf${xf}`, data: qDdData},
      {name: `DER-Yexf${xf}`, data: derData}
    ].filter(item => item.data.length)
  })
})

const parsedTypeCurves = computed(() =>
    chartSeriesItems.value
        .map((item, index) => {
          const name = getSeriesName(item, index)
          const field = String(item?.yAxisField || item?.yField || item?.field || '').toLowerCase()
          const isActual = /实际|actual|qdd/i.test(name) ||
              ['qdd', 'der', 'derd', 'regularizedproduction', 'regularizedpressurederivativereciprocal', 'regularizedpressureintegralderivativereciprocal'].includes(field)
          if (isActual) return null
          return {
            name,
            data: toSeriesPoints(item)
          }
        })
        .filter(item => item?.data?.length)
)

// const visibleTypeCurves = computed(() => parsedTypeCurves.value.length ? parsedTypeCurves.value : typeCurves.value)

const visibleTypeCurves = computed(() => typeCurves.value)

function renderChart() {
  if (!chart) return

  const series = [
    ...visibleTypeCurves.value.map((item, index) => ({
      name: item.name,
      type: 'line',
      data: item.data,
      showSymbol: false,
      smooth: true,
      silent: true,
      z:1,
      lineStyle: { width: 1, color: TYPE_CURVE_COLORS[index % TYPE_CURVE_COLORS.length] }
    })),
    {
      name: 'qDd-实际数据',
      type: 'scatter',
      data: actualSeries.value.qDd,
      symbolSize: 6,
      itemStyle: { color: '#7ee000' }
    },
    {
      name: '1/DER-实际数据',
      type: 'scatter',
      data: actualSeries.value.DER,
      symbolSize: 6,
      itemStyle: { color: '#00a020' }
    },

    {
      name: '拟合位置',
      type: 'line',
      data: [],
      showSymbol: false,
      lineStyle: { color: '#ff40d8', type: 'dashed', width: 1 },
      tooltip: { show: false }
    }
  ]

  chart.setOption({
    animation: false,
    title: {
      text: 'Wattenbarger',
      left: 'center',
      top: 8,
      textStyle: { color: '#333', fontSize: 14, fontWeight: 600 }
    },
    tooltip: {
      trigger: 'axis',
      axisPointer: {
        type: 'line',
        axis: 'x',
        snap: false,
        lineStyle: { color: '#ff40d8', type: 'dashed', width: 1 },
        label: { backgroundColor: '#d936d0' }
      }
    },
    legend: {
      show: false,
      right: 18,
      top: 42,
      itemWidth: 10,
      itemHeight: 10,
      data: ['qDd-实际数据', '1/DER-实际数据']
    },
    grid: { left: 72, right: 52, top: 60, bottom: 58 },
    xAxis: {
      type: 'log',
      min: 1e-5,
      max: 1e4,
      name: 'tcaDd',
      nameLocation: 'middle',
      nameGap: 34,
      axisPointer: {
        show: true,
        type: 'line',
        snap: false,
        lineStyle: { color: '#ff40d8', type: 'dashed', width: 1 },
        label: { show: true, backgroundColor: '#d936d0' }
      },
      splitLine: { show: true, lineStyle: { color: '#dfe7f2' } },
      minorSplitLine: { show: true, lineStyle: { color: '#f0f4fa' } }
    },
    yAxis: {
      type: 'log',
      min: 0.001,
      max: 100,
      name: 'qDd,1/DER',
      nameLocation: 'middle',
      nameGap: 48,
      splitLine: { show: true, lineStyle: { color: '#dfe7f2' } },
      minorSplitLine: { show: true, lineStyle: { color: '#f0f4fa' } }
    },
    series: applyLegendVisibility(series)
  }, true)
}

const renderChartSoon = () => {
  nextTick(() => {
    requestAnimationFrame(() => {
      chart?.resize()
      renderChart()
    })
  })
}

const loadResult = async () => {
  fetchedRaw.value = null
  const id = resultId.value
  if (!id || !props.projectId || props.gasReservoirId === undefined || props.gasReservoirId === null) return

  try {
    const currentId = id
    const payload = normalizePayload(await typicalCurveApi.getResult(props.projectId, props.gasReservoirId, id))
    if (currentId !== resultId.value) return
    fetchedRaw.value = payload
  } catch (error) {
    console.error('Wattenbarger结果加载失败', error)
  } finally {
    renderChartSoon()
  }
}

function toggleParamsPanel() {
  paramsCollapsed.value = !paramsCollapsed.value
  renderChartSoon(180)
}

function onParamsPanelResize(event) {
  if (!resizingParamsPanel.value) return
  const left = paramsPanelEl.value?.getBoundingClientRect().left || 0
  paramsPanelWidth.value = Math.max(238, Math.min(520, event.clientX - left))
  chart?.resize()
}

function stopParamsPanelResize() {
  if (!resizingParamsPanel.value) return
  resizingParamsPanel.value = false
  document.body.style.cursor = ''
  document.body.style.userSelect = ''
  window.removeEventListener('mousemove', onParamsPanelResize)
  window.removeEventListener('mouseup', stopParamsPanelResize)
  renderChartSoon(180)
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

function onLegendDrag(event) {
  if (!draggingLegend.value || !chartAreaEl.value) return
  const rect = chartAreaEl.value.getBoundingClientRect()
  const x = Math.max(0, Math.min(rect.width - 80, event.clientX - rect.left - legendDragOffset.value.x))
  const y = Math.max(0, Math.min(rect.height - 30, event.clientY - rect.top - legendDragOffset.value.y))
  legendPosition.value = { x, y }
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
  event.preventDefault()
  const legendRect = event.currentTarget.getBoundingClientRect()
  const areaRect = chartAreaEl.value.getBoundingClientRect()
  const currentX = legendPosition.value.x ?? legendRect.left - areaRect.left
  const currentY = legendPosition.value.y ?? legendRect.top - areaRect.top
  legendPosition.value = { x: currentX, y: currentY }
  legendDragOffset.value = {
    x: event.clientX - legendRect.left,
    y: event.clientY - legendRect.top
  }
  draggingLegend.value = true
  document.body.style.cursor = 'move'
  document.body.style.userSelect = 'none'
  window.addEventListener('mousemove', onLegendDrag)
  window.addEventListener('mouseup', stopLegendDrag)
}

watch(() => [
  resultId.value,
  props.projectId,
  props.gasReservoirId,
  props.node?.wattenbargerRefreshKey
], loadResult, { immediate: true })
watch(raw, renderChartSoon, { deep: true })
watch(activeChartTab, renderChartSoon)

onMounted(() => {
  chart = echarts.init(chartEl.value)
  window.addEventListener('resize', renderChartSoon)
  renderChartSoon()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', renderChartSoon)
  stopLegendDrag()
  stopParamsPanelResize()
  chart?.dispose()
})
</script>

<template>
  <div class="wattenbarger-wrap">
    <aside
        ref="paramsPanelEl"
        class="params-panel"
        :class="{ collapsed: paramsCollapsed }"
        :style="{ width: paramsCollapsed ? '22px' : `${paramsPanelWidth}px`, minWidth: paramsCollapsed ? '22px' : `${paramsPanelWidth}px` }"
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

        <div v-if="activePanelTab === 'input'" class="panel-body">
          <div class="sec-label">气体性质</div>
          <div class="field-grid">
            <div v-for="field in inputFields.gas" :key="field.label" class="field">
              <label>{{ field.label }}</label>
              <el-select
                  v-if="field.select"
                  :key="`${readonlySelectKey}-${field.label}`"
                  size="small"
                  :model-value="field.value"
                  style="width:100%"
                  @change="keepReadonlySelectValue"
              >
                <el-option
                    v-for="option in field.options || [field.value]"
                    :key="option"
                    :label="option"
                    :value="option"
                />
              </el-select>
              <el-input v-else size="small" readonly :model-value="field.value" />
            </div>
          </div>

          <div class="sec-label">计算方法</div>
          <div class="field-grid">
            <div v-for="field in inputFields.method" :key="field.label" class="field">
              <label>{{ field.label }}</label>
              <el-select
                  :key="`${readonlySelectKey}-${field.label}`"
                  size="small"
                  :model-value="field.value"
                  style="width:100%"
                  @change="keepReadonlySelectValue"
              >
                <el-option
                    v-for="option in field.options || [field.value]"
                    :key="option"
                    :label="option"
                    :value="option"
                />
              </el-select>
            </div>
          </div>

          <div class="sec-label">控制参数</div>
          <div class="control-panel">
            <div class="fitting-mode-row">
              <span class="control-label">请选择拟合方式：</span>
              <el-radio-group v-model="fittingModeValue" size="small">
                <el-radio value="automatic">自动拟合</el-radio>
                <el-radio value="manual">手动拟合</el-radio>
              </el-radio-group>
            </div>

            <div class="field-grid control-field-grid">
              <div class="field">
                <label>抽稀后的数据点数量</label>
                <el-input v-model="dataSizeValue" size="small" />
              </div>
              <div class="field">
                <label>粗扫数据点数量</label>
                <el-input v-model="initScanDataSizeValue" size="small" />
              </div>
              <div class="field">
                <label>精扫数据点数量</label>
                <el-input v-model="fineScanDataSizeValue" size="small" />
              </div>
              <div
                  class="field field-with-switch"
                  :class="{ 'control-muted': !waterGasRatioEnabled }"
              >
                <div class="wgr-label-row">
                  <el-checkbox v-model="waterGasRatioEnabled">
                    生产水气比上限(m³/10⁴m³)
                  </el-checkbox>
                </div>
                <div class="wgr-input-row">
                  <el-input
                      v-model="waterGasRatioLimitValue"
                      size="small"
                      :disabled="!waterGasRatioEnabled"
                  />
                  <el-button
                      size="small"
                      :loading="recalculating"
                      :disabled="recalculating"
                      @click="handleRecalculate"
                  >重新计算</el-button>
                </div>
              </div>
            </div>
          </div>

          <div class="sec-label">其它数据</div>
          <div class="field-grid">
            <div v-for="field in inputFields.other" :key="field.label" class="field">
              <label>{{ field.label }}</label>
              <el-select
                  v-if="field.select"
                  :key="`${readonlySelectKey}-${field.label}`"
                  size="small"
                  :model-value="field.value"
                  style="width:100%"
                  @change="keepReadonlySelectValue"
              >
                <el-option
                    v-for="option in field.options || [field.value]"
                    :key="option"
                    :label="option"
                    :value="option"
                />
              </el-select>
              <el-input v-else size="small" readonly :model-value="field.value" />
            </div>
          </div>

          <div class="sec-label">生产数据</div>
          <div class="btn-row">
            <el-button size="small" @click="downloadProductionTemplate">模板下载</el-button>
            <el-button size="small">导入</el-button>
          </div>
        </div>

        <div v-else-if="hasOutputResults" class="panel-body">
          <div class="sec-label">输出结果</div>
          <div class="field-grid">
            <div v-for="field in outputFields" :key="field.label" class="field">
              <label>{{ field.label }}</label>
              <el-input size="small" readonly :model-value="field.value" />
            </div>
          </div>
        </div>

        <div class="param-tabs">
          <div class="param-tab" :class="{ active: activePanelTab === 'input' }" @click="activePanelTab = 'input'">
            输入
          </div>
          <div v-if="hasOutputResults" class="param-tab" :class="{ active: activePanelTab === 'output' }" @click="activePanelTab = 'output'">
            输出
          </div>
        </div>

        <div class="params-resizer" @mousedown="startParamsPanelResize"></div>
      </template>
    </aside>

    <div ref="chartAreaEl" class="chart-area">
      <div class="dynamic-result-tabs">
        <button type="button" class="dynamic-result-tab active" :title="resultTabTitle">
          <span class="dynamic-result-tab-text">{{ resultTabTitle }}</span>
        </button>
      </div>

      <div v-show="activeChartTab === 'chart'" ref="chartEl" class="chart-instance"></div>

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
              class="legend-dot"
              :style="{ backgroundColor: isLegendItemHidden(item.name) ? 'transparent' : item.color, borderColor: item.color }"
          ></span>
          <span>{{ item.name }}</span>
        </div>
      </div>

      <div v-if="activeChartTab === 'table'" class="data-list-panel">
        <el-table :data="tableRows" size="small" height="100%" border stripe>
          <el-table-column prop="index" label="序号" width="76" sortable />
          <el-table-column prop="pseudotime" label="物质平衡拟时间" min-width="160" sortable />
          <el-table-column prop="regularizedProduction" label="规整化产量(qDd)" min-width="170" sortable />
          <el-table-column prop="regularizedPressureDerivativeReciprocal" label="压力导数倒数(1/DER)" min-width="190" sortable />
          <el-table-column prop="regularizedPressureIntegralDerivativeReciprocal" label="压力积分导数倒数" min-width="190" sortable />
        </el-table>
      </div>

      <div class="chart-tabs">
        <button type="button" class="chart-tab" :class="{ active: activeChartTab === 'table' }" @click="activeChartTab = 'table'">
          数据列表
        </button>
        <button type="button" class="chart-tab" :class="{ active: activeChartTab === 'chart' }" @click="activeChartTab = 'chart'">
          结果分析图
        </button>
      </div>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.wattenbarger-wrap {
  display: flex;
  height: 100%;
  background: #fff;
  overflow: hidden;
}

.params-panel {
  width: 238px;
  min-width: 238px;
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

.control-panel {
  padding-bottom: 2px;

  :deep(.el-radio__inner),
  :deep(.el-checkbox__inner) {
    border-color: #c0c4cc;
  }

  :deep(.el-radio__input.is-checked .el-radio__inner),
  :deep(.el-checkbox__input.is-checked .el-checkbox__inner),
  :deep(.el-checkbox__input.is-indeterminate .el-checkbox__inner) {
    background-color: #303133;
    border-color: #303133;
  }

  :deep(.el-radio__input.is-checked + .el-radio__label),
  :deep(.el-checkbox__input.is-checked + .el-checkbox__label) {
    color: #303133;
  }
}

.fitting-mode-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px 12px;
  min-height: 28px;
  margin-bottom: 7px;

  .control-label {
    color: #555;
    font-size: 12px;
  }

  :deep(.el-radio) {
    margin-right: 16px;
  }
}

.control-field-grid {
  align-items: end;
}

.wgr-input-row {
  display: flex;
  align-items: center;
  gap: 4px;

  .el-input {
    flex: 1;
    min-width: 0;
    margin-top: 0;
  }

  .el-button {
    flex-shrink: 0;
  }
}

.wgr-label-row {
  margin-bottom: 2px;

  :deep(.el-checkbox) {
    height: 20px;
    line-height: 20px;
  }

  :deep(.el-checkbox__label) {
    font-size: 12px;
    line-height: 20px;
  }
}

.control-muted {
  .wgr-label-row :deep(.el-checkbox__label) {
    color: #a8abb2;
  }
}

.field-with-switch {
  .el-input {
    margin-top: 3px;
  }
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

.wgr-label-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 2px;

  span {
    color: #555;
    font-size: 12px;
  }
}

.btn-row {
  display: flex;
  gap: 8px;
}

.chart-area {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
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
  max-width: 320px;
  border: 0;
  border-right: 1px solid #e4e7ed;
  border-bottom: 2px solid transparent;
  background: transparent;
  color: #409eff;
  font-size: 14px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0 12px;
  cursor: default;
  white-space: nowrap;

  &.active {
    border-bottom-color: #409eff;
    background: #fff;
  }
}

.dynamic-result-tab-text {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chart-tabs {
  display: flex;
  height: 34px;
  border-top: 1px solid #e4e7ed;
  flex-shrink: 0;
  background: #fafafa;
}

.chart-tab {
  border: 0;
  border-right: 1px solid #e4e7ed;
  background: transparent;
  padding: 0 16px;
  color: #555;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  white-space: nowrap;

  &:hover {
    color: #409eff;
  }

  &.active {
    color: #409eff;
    border-bottom-color: #409eff;
    background: #fff;
    font-weight: 600;
  }
}

.chart-instance {
  flex: 1;
  min-height: 0;
  width: 100%;
}

.floating-chart-legend {
  position: absolute;
  z-index: 6;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid #e4e7ed;
  padding: 6px 10px;
  font-size: 12px;
  color: #333;
  cursor: move;
  user-select: none;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.08);

  &.dragging {
    opacity: 0.86;
  }
}

.floating-legend-item {
  display: flex;
  align-items: center;
  gap: 6px;
  line-height: 18px;
  white-space: nowrap;
  cursor: pointer;

  &.hidden {
    color: #999;
    opacity: 0.55;
  }
}

.legend-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
  border: 1px solid transparent;
}

.data-list-panel {
  flex: 1;
  min-height: 0;
  width: 100%;
  overflow: hidden;
  background: #fff;
}
</style>
