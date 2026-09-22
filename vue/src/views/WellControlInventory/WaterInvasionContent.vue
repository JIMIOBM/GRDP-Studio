<script setup>
/**
 * WaterInvasionContent.vue
 * 水侵动态分析 —— 右侧内容面板
 * 从新数据库读取单井水侵快照；旧算法只由新后端任务调用。
 *
 * 路径：src/views/WellControlInventory/WaterInvasionContent.vue
 */
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import * as echarts from 'echarts'
import { ElMessage, ElMessageBox } from 'element-plus'
import { waterInvasionApi, isWaterInvasionTaskActive, waterInvasionRecordLabel } from '@/api/waterInvasion'

const props = defineProps({ // 父组件传进来的数据
  node:           Object,           // 包含 wellName 字段
  projectId:      [Number, String],
  gasReservoirId: [Number, String]
})

// 通知父组件（IprInterface）刷新左侧树 / 重新计算
const emit = defineEmits(['refresh-tree', 'recalculate'])

// ─── 常量：方法枚举 ───
const MODIFICATION_METHODS = ['Wichert-Aziz 修正方法', 'Carr-Kobayashi-Burrous 修正方法']
const DEVIATION_METHODS    = ['Dranchuk-Abu-Kassem 方法', 'Dranchuk-Purvis-Robinson 方法', 'Hall-Yarborough 方法']
const VISCOSITY_METHODS    = ['Lee-Gonzalez-Eakin 方法', 'Carr-Kobayashi-Burrous 方法', 'Sutton 方法']
const CHART_TAB_LABELS = ['水侵识别', '水体大小', '水侵量', '驱动机制', '水体活跃性']
const OUTPUT_FIELD_CONFIGS = [
  [
    { label: '水侵识别结果', keys: ['waterInvasionStateDesc'] },
    { label: '动态地质储量(10⁸m³)', keys: ['originalGasVolume'] }
  ],
  [
    { label: '水体大小(10⁴m³)', keys: ['waterBodySize'] },
    { label: '天然气地下体积(10⁸m³)', keys: ['undergroundGasVolume'] },
    { label: '水体倍数(dless)', keys: ['waterBodySizeMultiple'] }
  ]
]
const DATA_LIST_COLUMN_CONFIGS = [
  [
    { prop: 'index', label: '序号', width: 76, keys: null },
    { prop: 'date', label: '日期', minWidth: 150, keys: ['date'], type: 'date' },
    { prop: 'pressure', label: '地层压力(MPa)', minWidth: 160, keys: ['pressure'], digits: 4 },
    { prop: 'cumulativeGasProduction', label: '累产气量(10⁸m³)', minWidth: 170, keys: ['cumulativeGasProduction'], digits: 4 },
    { prop: 'cumulativeWaterProduction', label: '累产水量(10⁴m³)', minWidth: 170, keys: ['cumulativeWaterProduction'], digits: 4 },
    { prop: 'apparentPressure', label: '无因次视压力(dless)', minWidth: 180, keys: ['apparentPressure'], digits: 4 },
    { prop: 'recoveryDegree', label: '采出程度(%)', minWidth: 150, keys: ['recoveryDegree'], digits: 2 },
    { prop: 'selected', label: '是否参与分析', minWidth: 150, keys: ['isDeleted'], type: 'selected' }
  ],
  [
    { prop: 'index', label: '序号', width: 76, keys: null },
    { prop: 'date', label: '日期', minWidth: 150, keys: ['date'], type: 'date' },
    { prop: 'pressure', label: '地层压力(MPa)', minWidth: 160, keys: ['pressure'], digits: 4 },
    { prop: 'cumulativeGasProduction', label: '累产气量(10⁸m³)', minWidth: 170, keys: ['cumulativeGasProduction'], digits: 4 },
    { prop: 'cumulativeWaterProduction', label: '累产水量(10⁴m³)', minWidth: 170, keys: ['cumulativeWaterProduction'], digits: 4 },
    { prop: 'apparentPressure', label: '无因次视压力(dless)', minWidth: 180, keys: ['apparentPressure'], digits: 4 },
    { prop: 'recoveryDegree', label: '采出程度(%)', minWidth: 150, keys: ['recoveryDegree'], digits: 2 },
    { prop: 'selected', label: '是否参与分析', minWidth: 150, keys: ['isDeleted'], type: 'selected' }
  ],
  [
    { prop: 'index', label: '序号', width: 76, keys: null },
    { prop: 'date', label: '日期', minWidth: 150, keys: ['date'], type: 'date' },
    { prop: 'pressure', label: '地层压力(MPa)', minWidth: 160, keys: ['pressure'], digits: 4 },
    { prop: 'cumulativeGasProduction', label: '累产气量(10⁸m³)', minWidth: 170, keys: ['cumulativeGasProduction'], digits: 4 },
    { prop: 'cumulativeWaterProduction', label: '累产水量(10⁴m³)', minWidth: 170, keys: ['cumulativeWaterProduction'], digits: 4 },
    { prop: 'waterInflux', label: '水侵量(10⁴m³)', minWidth: 170, keys: ['waterInflux'], digits: 4 }
  ],
  [
    { prop: 'index', label: '序号', width: 76, keys: null },
    { prop: 'date', label: '日期', minWidth: 150, keys: ['date'], type: 'date' },
    { prop: 'cumulativeGasProduction', label: '累产气量(10⁸m³)', minWidth: 170, keys: ['cumulativeGasProduction'], digits: 4 },
    { prop: 'cumulativeWaterProduction', label: '累产水量(10⁴m³)', minWidth: 170, keys: ['cumulativeWaterProduction'], digits: 4 },
    { prop: 'gasDriveIndex', label: '天然气驱动指数(dless)', minWidth: 190, keys: ['gasDriveIndex'], digits: 4 },
    { prop: 'reservoirVolumetricDriveIndex', label: '气藏容积驱动指数(dless)', minWidth: 210, keys: ['reservoirVolumetricDriveIndex'], digits: 4 },
    { prop: 'waterInvasionEnergyDriveIndex', label: '水侵能量驱动指数(dless)', minWidth: 210, keys: ['waterInvasionEnergyDriveIndex'], digits: 4 }
  ]
]

// ─── 状态 ───
const loading        = ref(false)   // 接口加载状态
const wellData       = ref(null)  //接口返回的谁侵分析详细数据
const activeChartIdx = ref(0)  // 选中的图表页签下标
const activeContentTab = ref('chart')
const activeParamTab = ref('input')  //左侧参数面板输入\输出显示
const paramsPanelWidth = ref(238)  //参数面板宽度
const paramsCollapsed = ref(false)  //参数面板是否收起
const resizingParamsPanel = ref(false)  // 用户是否正在拖拽调整参数面板宽度

// ─── 从接口数据派生 ───
const input = computed(() => wellData.value?.input || {})
const output = computed(() => chartTabs.value[activeChartIdx.value]?.output || {})
const outputFields = computed(() => OUTPUT_FIELD_CONFIGS[activeChartIdx.value] || [])
const hasOutputResults = computed(() => activeTab.value?.hasOutput && outputFields.value.length > 0)
const isWaterActivityTab = computed(() => activeChartIdx.value === 4)
const hasDataListForActiveTab = computed(() => activeChartIdx.value >= 0 && activeChartIdx.value < 4)
const isDataListTab = computed(() => activeContentTab.value === 'table')
const isProductionTab = computed(() => activeContentTab.value === 'production')
const waterActivityOutput = computed(() => chartTabs.value[4]?.output || {})
const currentWellName = computed(() => props.node?.wellName || wellData.value?.input?.wellName || '')
const PRODUCTION_COLUMNS = [
  { prop: 'date', label: '日期', unit: '无', minWidth: 150 },
  // 原接口字段为 pressure，截图与接口对压力的称谓不同，这里不擅自改变物理含义。
  { prop: 'pressure', label: '压力', unit: 'MPa', minWidth: 145 },
  { prop: 'dailyGasProduction', label: '气产量', unit: '10⁴m³/d', minWidth: 150 },
  { prop: 'cumulativeGasProduction', label: '累产气量', unit: '10⁸m³', minWidth: 160 },
  { prop: 'cumulativeWaterProduction', label: '累产水量', unit: '10⁴m³', minWidth: 160 }
]
// 生产数据来自当前批次的新库快照，不能混用 identifyInputItems 或 outputs 的分析明细。
const productionItems = computed(() => Array.isArray(wellData.value?.influxInputItems) ? wellData.value.influxInputItems : [])
const productionPage = ref(1)
const PRODUCTION_PAGE_SIZE = 100
const productionPageRows = computed(() => {
  const offset = (productionPage.value - 1) * PRODUCTION_PAGE_SIZE
  return productionItems.value.slice(offset, offset + PRODUCTION_PAGE_SIZE).map((row, index) => ({
    ...row, rowNumber: offset + index + 1
  }))
})
const productionExportRows = computed(() => [
  PRODUCTION_COLUMNS.map(column => column.label),
  PRODUCTION_COLUMNS.map(column => column.unit),
  ...productionItems.value.map(row => PRODUCTION_COLUMNS.map(column => column.prop === 'date'
    ? String(row?.date ?? '').slice(0, 10) : row?.[column.prop] ?? ''))
])
function formatProductionValue(row, column) {
  const value = row?.[column.prop]
  if (column.prop === 'date') return String(value ?? '').slice(0, 10)
  // 缺失值留空，实际 0 显示为 0.0000；仅展示四位小数，不改动存储和导出精度。
  return hasResultNumber(value) ? Number(value).toFixed(4) : ''
}
const chartTabTitle = computed(() =>
    `水侵分析-${currentWellName.value || '当前井'}-分析结果`
)

//用于控制右侧浮动图例的位置
const legendPosition = ref({ x: null, y: null })
const draggingLegend = ref(false)
const legendDragOffset = ref({ x: 0, y: 0 })
const hiddenLegendNames = ref(new Set())

const getXlsx = async () => import('xlsx')

const saveWorkbook = (XLSX, workbook, filename) => {
  XLSX.writeFile(workbook, filename)
}

const downloadProductionTemplate = async () => {
  const XLSX = await getXlsx()
  const rows = [
    PRODUCTION_COLUMNS.map(column => column.label),
    PRODUCTION_COLUMNS.map(column => column.unit)
  ]
  const sheet = XLSX.utils.aoa_to_sheet(rows)
  const workbook = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(workbook, sheet, '生产数据')
  saveWorkbook(XLSX, workbook, `水侵分析生产数据模板-${currentWellName.value || 'well'}.xlsx`)
}

const downloadProductionData = async () => {
  if (!productionItems.value.length) return
  const rows = productionExportRows.value
  const wellName = currentWellName.value
  try {
    const XLSX = await getXlsx()
    const sheet = XLSX.utils.aoa_to_sheet(rows)
    sheet['!cols'] = PRODUCTION_COLUMNS.map(() => ({ wch: 20 }))
    const workbook = XLSX.utils.book_new()
    XLSX.utils.book_append_sheet(workbook, sheet, '生产数据')
    saveWorkbook(XLSX, workbook, `水侵分析生产数据-${wellName || 'well'}.xlsx`)
  } catch {
    ElMessage.error('生产数据导出失败，请重试')
  }
}

const legendItems = computed(() => {
  if (!activeTab.value?.hasChart) return []
  if (isWaterActivityTab.value) return []
  if (activeChartIdx.value === 0) return [{ name: '无因次视压力PFD(dless)', color: '#5470c6' }]
  if (activeChartIdx.value === 1) return [{ name: '无因次视压力PHD(dless)', color: '#5470c6' }]
  if (activeChartIdx.value === 2) return [{ name: '累计水侵量(10⁴m³)', color: '#1677ff' }]
  if (activeChartIdx.value === 3) {
    return [
      { name: '天然气驱动指数(dless)', color: '#ffff33' },
      { name: '气藏容积驱动指数(dless)', color: '#b84a4a' },
      { name: '水侵能量驱动指数(dless)', color: '#2f80ed' }
    ]
  }
  return []
})
const legendStyle = computed(() => {
  if (legendPosition.value.x === null || legendPosition.value.y === null) {
    // 默认落在坐标网格右上角内侧；保留用户拖动后的自定义位置。
    const grid = baseGrid()
    return { top: `${34 + (chartTabs.value.length ? 34 : 0) + grid.top + 12}px`, right: `${grid.right + 12}px` }
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
  if (!legendItems.value.some(item => item.name === name)) return true
  return !hiddenLegendNames.value.has(name)
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

const preferActualStaticPressure = ref(true)
const enableWaterGasRatioLimit = ref(false)
const waterGasRatioLimitValue = ref('0.0602')

watch(input, (value) => {
  preferActualStaticPressure.value = value?.isUseActualStaticPressure !== false
  const limit = value?.waterGasRatioLimit
  enableWaterGasRatioLimit.value = Number(limit) > 0
  waterGasRatioLimitValue.value = Number(limit) > 0 ? String(limit) : '0.0602'
}, { immediate: true })

function handleRecalculate() {
  if (hasRunningTask.value) return
  emit('recalculate', {
    wellName: props.node?.wellName,
    isUseActualStaticPressure: preferActualStaticPressure.value,
    waterGasRatioLimit: enableWaterGasRatioLimit.value ? Number(waterGasRatioLimitValue.value) : -1
  })
}

//获取输入值
const getInputValue = (keys, fallback = '') => {
  for (const key of keys) {
    const value = input.value?.[key]
    if (value !== undefined && value !== null && value !== '') return value
  }
  return fallback
}


//获取输出值
const getOutputValue = (keys, fallback = '') => {
  for (const key of keys) {
    const value = output.value?.[key]
    if (value !== undefined && value !== null && value !== '') return value
  }
  return fallback
}


//方法编号转换
const getMethodValue = (methods, key) => {
  const value = input.value?.[key]
  if (value === undefined || value === null || value === '') return ''
  if (typeof value === 'number') return methods[value] || String(value)
  return value
}

// 空对象、空数组和只有日期等基础字段的记录不代表分析成功；0 是有效结果。
function hasResultNumber(value) {
  return (typeof value === 'number' || (typeof value === 'string' && value.trim() !== ''))
    && Number.isFinite(Number(value))
}

function hasResultField(record, key) {
  const value = record?.[key]
  return key.endsWith('Desc')
    ? typeof value === 'string' && value.trim() !== ''
    : hasResultNumber(value)
}

const ANALYSIS_ROW_KEYS = [
  ['apparentPressure', 'recoveryDegree'],
  ['apparentPressure', 'recoveryDegree'],
  ['waterInflux'],
  ['gasDriveIndex', 'reservoirVolumetricDriveIndex', 'waterInvasionEnergyDriveIndex']
]
const ACTIVITY_OUTPUT_KEYS = ['abandonWaterInflux', 'reservoirOriginalVolume', 'waterInvasionReplacementCoefficient', 'waterActivenessDesc']

// 按接口原有顺序保留全部五项，缺失项置灰，不过滤数组以免页签错位。
const chartTabs = computed(() => {
  return CHART_TAB_LABELS.map((label, index) => {
    const result = wellData.value?.outputs?.[index] || {}
    const chartItems = Array.isArray(result.chartItems) ? result.chartItems : []
    const outputItems = Array.isArray(result.outputItems) ? result.outputItems : []
    const output = result.output || {}
    const hasChart = index < 4 && chartItems.slice(0, index === 3 ? 3 : 1).some(item =>
      getChartData(item).some(point => index < 2 ? hasResultNumber(point.x) : String(point.x).trim() !== '')
    )
    const hasRows = outputItems.some(row => (ANALYSIS_ROW_KEYS[index] || []).some(key => hasResultField(row, key)))
    const outputKeys = index === 4 ? ACTIVITY_OUTPUT_KEYS : (OUTPUT_FIELD_CONFIGS[index] || []).flatMap(field => field.keys)
    const hasOutput = outputKeys.some(key => hasResultField(output, key))
    const types = ['IDENTIFICATION', 'AQUIFER_SIZE', 'WATER_INFLUX', 'DRIVE_MECHANISM', 'WATER_ACTIVITY']
    const stored = wellData.value?.storedSections?.find(item => item.result_type === types[index])
    return { label, chartItems, output, outputItems,
      hasChart: stored ? Boolean(stored.has_chart) : hasChart,
      hasRows: stored ? Boolean(stored.has_detail) : hasRows,
      hasOutput: stored ? Boolean(stored.has_summary) : hasOutput,
      available: stored ? stored.availability === 'HAS_DATA' : hasChart || hasRows || hasOutput }
  })
})

const activeTab = computed(() => chartTabs.value[activeChartIdx.value] || null)

const getRowValue = (row, keys, fallback = '') => {
  for (const key of keys) {
    const value = row?.[key]
    if (value !== undefined && value !== null && value !== '') return value
  }
  return fallback
}

const formatDateValue = (value) => {
  if (!value) return ''
  return String(value).slice(0, 10).replace(/-/g, '/')
}

const formatDecimalValue = (value, digits) => {
  if (value === undefined || value === null || value === '') return ''
  const num = Number(value)
  if (!Number.isFinite(num)) return value
  return num.toFixed(digits).replace(/\.?0+$/, '')
}

const dataListColumns = computed(() => DATA_LIST_COLUMN_CONFIGS[activeChartIdx.value] || [])

const YES_TEXT = '是'
const NO_TEXT = '否'

const isTruthyValue = (value) => value === true || value === 'true' || value === 1 || value === '1'

const formatDataListValue = (row, column) => {
  if (!column.keys) return ''
  const value = getRowValue(row, column.keys)
  if (column.type === 'date') return formatDateValue(value)
  if (column.type === 'selected') return isTruthyValue(value) ? NO_TEXT : YES_TEXT
  if (column.digits !== undefined) return formatDecimalValue(value, column.digits)
  return value ?? ''
}

const dataListRows = computed(() => {
  if (!hasDataListForActiveTab.value) return []
  const rows = activeTab.value?.outputItems || []
  return rows.map((row, index) =>
    dataListColumns.value.reduce((record, column) => {
      record[column.prop] = column.prop === 'index'
        ? index + 1
        : formatDataListValue(row, column)
      return record
    }, {})
  )
})


// ECharts
const chartEl = ref(null)
const chartAreaEl = ref(null)
const paramsPanelEl = ref(null)
let chart = null
let chartRenderTimer = null
const onResize = () => chart?.resize()

const renderChartSoon = (delay = 0) => {  //延迟渲染图表
  if (chartRenderTimer) clearTimeout(chartRenderTimer)
  chartRenderTimer = setTimeout(() => {
    nextTick(() => {
      requestAnimationFrame(() => {
        chart?.resize()
        renderChart()
      })
    })
  }, delay)
}


//参数面板收起和拖拽
function toggleParamsPanel() {
  paramsCollapsed.value = !paramsCollapsed.value
  renderChartSoon(180)
}


//拖拽改变宽度
function onParamsPanelResize(event) {
  if (!resizingParamsPanel.value) return
  const leftBoundary = paramsPanelEl.value?.getBoundingClientRect().left || 0
  const width = Math.max(238, Math.min(520, event.clientX - leftBoundary))
  paramsPanelWidth.value = width
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

/** 简单最小二乘线性回归 */
function linearRegression(pts) {
  const n = pts.length
  if (n < 2) return null
  let sx = 0, sy = 0, sxy = 0, sxx = 0
  for (const [x, y] of pts) { sx += x; sy += y; sxy += x * y; sxx += x * x }
  const denom = n * sxx - sx * sx
  if (Math.abs(denom) < 1e-14) return null
  const slope     = (n * sxy - sx * sy) / denom
  const intercept = (sy - slope * sx) / n
  const yBar = sy / n
  let ssTot = 0, ssRes = 0
  for (const [x, y] of pts) {
    ssTot += (y - yBar) ** 2
    ssRes += (y - (slope * x + intercept)) ** 2
  }
  const r2 = ssTot < 1e-14 ? 0 : 1 - ssRes / ssTot
  return { slope, intercept, r2 }
}

/** 格式化为科学计数法字符串，如 -7.9748E-1 */
function fmtSci(v) {
  if (v === 0) return '0'
  const sign  = v < 0 ? '-' : ''
  const abs   = Math.abs(v)
  const exp   = Math.floor(Math.log10(abs))
  const coeff = (abs / Math.pow(10, exp)).toFixed(4)
  return `${sign}${coeff}E${exp >= 0 ? '+' : ''}${String(exp).padStart(1, '0')}`
}

//图表数据处理
function getChartData(item) {
  return (Array.isArray(item?.data) ? item.data : [])
      .filter(d => d && !d.isDeleted && d.xValue !== null && d.xValue !== undefined
        && String(d.xValue).trim() !== '' && hasResultNumber(d.yValue))
      .map(d => ({
        x: d.xValue,
        y: Number(d.yValue),
        raw: d
      }))
}

function getNumericPoints(item) {
  return getChartData(item).map(d => [Number(d.x), d.y])
}

function getDatePoints(item) {
  return getChartData(item).map(d => [String(d.x).slice(0, 10), d.y])
}

function baseGrid() {
  return {
    left: 62,
    right: 92,
    top: 44,
    bottom: 56
  }
}


//渲染水侵识别/水体大小图
function renderPressureRecoveryChart(tab, index) {
  const primary = tab.chartItems[0]
  const scatter = getNumericPoints(primary)
  const isPHD = index === 1
  const yName = isPHD ? 'PHD(dless)' : 'PFD(dless)'
  const title = isPHD ? 'PHD-Rg关系图' : 'PFD-Rg关系图'
  const legendName = isPHD ? '无因次视压力PHD(dless)' : '无因次视压力PFD(dless)'
  const series = [
    {
      name: legendName,
      type: 'scatter',
      data: scatter,
      symbolSize: 8,
      itemStyle: { color: '#5470c6', opacity: 0.85 }
    },
    {
      name: '理论线',
      type: 'line',
      data: [[0, 1], [100, 0]],
      symbol: 'none',
      lineStyle: { color: '#111', width: 1.8 },
      tooltip: { show: false }
    }
  ]

  chart.setOption({
    animation: false,
    title: {
      text: title,
      left: 'center',
      top: 8,
      textStyle: { fontSize: 14, fontWeight: 600, color: '#333' }
    },
    tooltip: {
      trigger: 'item',
      axisPointer: {
        type: 'cross',
        crossStyle: { color: '#d936d0', type: 'dashed', width: 1 },
        label: { backgroundColor: '#d936d0' }
      },
      formatter: p => `${p.marker}${p.seriesName}：${Number(p.value?.[1] ?? p.value).toFixed(4)}`
    },
    legend: { show: false },
    grid: baseGrid(),
    xAxis: {
      type: 'value',
      name: 'Rg(%)',
      min: 0,
      max: 100,
      nameLocation: 'middle',
      nameGap: 30,
      minorTick: { show: true },
      minorSplitLine: { show: true, lineStyle: { color: '#f1f5fb' } },
      splitLine: { lineStyle: { color: '#dce5f2' } }
    },
    yAxis: {
      type: 'value',
      name: yName,
      min: 0,
      max: 1,
      nameLocation: 'middle',
      nameGap: 44,
      minorTick: { show: true },
      minorSplitLine: { show: true, lineStyle: { color: '#f1f5fb' } },
      splitLine: { lineStyle: { color: '#dce5f2' } }
    },
    series: applyLegendVisibility(series)
  }, true)
}


//渲染水侵量曲线
function renderWaterAmountChart(tab) {
  const primary = tab.chartItems[0]
  const data = getDatePoints(primary)
  const name = '累计水侵量(10⁴m³)'

  chart.setOption({
    animation: false,
    title: {
      text: '水侵量随时间变化曲线',
      left: 'center',
      top: 8,
      textStyle: { fontSize: 14, fontWeight: 600, color: '#333' }
    },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'line', lineStyle: { color: '#d936d0', type: 'solid', width: 1 } }
    },
    legend: { show: false },
    grid: baseGrid(),
    xAxis: {
      type: 'category',
      name: '日期',
      nameLocation: 'middle',
      nameGap: 34,
      boundaryGap: false,
      data: data.map(item => item[0]),
      axisLabel: { formatter: v => String(v).slice(0, 4) },
      minorTick: { show: true },
      minorSplitLine: { show: true, lineStyle: { color: '#f1f5fb' } },
      splitLine: { show: true, lineStyle: { color: '#dce5f2' } }
    },
    yAxis: {
      type: 'value',
      name: '水侵量(10⁴m³)',
      nameLocation: 'middle',
      nameGap: 44,
      minorTick: { show: true },
      minorSplitLine: { show: true, lineStyle: { color: '#f1f5fb' } },
      splitLine: { lineStyle: { color: '#dce5f2' } }
    },
    series: applyLegendVisibility([{
      name,
      type: 'line',
      data,
      showSymbol: false,
      smooth: true,
      lineStyle: { color: '#1677ff', width: 1.5 },
      itemStyle: { color: '#1677ff' },
      areaStyle: { color: 'rgba(22,119,255,0.78)' }
    }])
  }, true)
}


//喧嚷驱动机制图
function renderDriveMechanismChart(tab) {
  const names = ['天然气驱动指数(dless)', '气藏容积驱动指数(dless)', '水侵能量驱动指数(dless)']
  const colors = ['#ffff33', '#b84a4a', '#2f80ed']
  const baseData = getDatePoints(tab.chartItems[0])
  const categories = baseData.map(item => item[0])
  const series = tab.chartItems.slice(0, 3).map((item, index) => ({
    name: names[index] || item.name,
    type: 'line',
    stack: 'drive',
    data: getDatePoints(item).map(point => point[1]),
    showSymbol: false,
    smooth: true,
    lineStyle: { width: 1, color: colors[index] },
    itemStyle: { color: colors[index] },
    areaStyle: { color: colors[index], opacity: 0.9 }
  }))

  chart.setOption({
    animation: false,
    title: {
      text: '驱动指数随时间变化曲线',
      left: 'center',
      top: 8,
      textStyle: { fontSize: 14, fontWeight: 600, color: '#333' }
    },
    tooltip: { trigger: 'axis' },
    legend: { show: false },
    grid: baseGrid(),
    xAxis: {
      type: 'category',
      name: '日期',
      nameLocation: 'middle',
      nameGap: 34,
      boundaryGap: false,
      data: categories,
      axisLabel: { formatter: v => String(v).slice(0, 4) },
      minorTick: { show: true },
      minorSplitLine: { show: true, lineStyle: { color: '#f1f5fb' } },
      splitLine: { show: true, lineStyle: { color: '#dce5f2' } }
    },
    yAxis: {
      type: 'value',
      name: '驱动指数(dless)',
      min: 0,
      max: 1,
      nameLocation: 'middle',
      nameGap: 44,
      minorTick: { show: true },
      minorSplitLine: { show: true, lineStyle: { color: '#f1f5fb' } },
      splitLine: { lineStyle: { color: '#dce5f2' } }
    },
    series: applyLegendVisibility(series)
  }, true)
}

//总渲染入口，决定当前改该画哪种图
function renderChart() {
  if (!chart) return
  if (!activeTab.value?.available) {
    chart.clear()
    return
  }
  if (isDataListTab.value) {
    chart.clear()
    return
  }
  if (isWaterActivityTab.value) {
    chart.clear()
    return
  }

  const tab = activeTab.value
  if (!tab.hasChart) {
    chart.clear()
    return
  }

  if (activeChartIdx.value === 0 || activeChartIdx.value === 1) {
    renderPressureRecoveryChart(tab, activeChartIdx.value)
    return
  }

  if (activeChartIdx.value === 2) {
    renderWaterAmountChart(tab)
    return
  }

  if (activeChartIdx.value === 3) {
    renderDriveMechanismChart(tab)
  }
}

// ─── 新平台记录与后台任务 ───
const records = ref([])
const selectedRecordId = ref('')
const loadError = ref('')
const importing = ref(false)
const completedRecords = computed(() => records.value.filter(item => item.taskStatus === 'COMPLETED'))
const hasRunningTask = computed(() => records.value.some(isWaterInvasionTaskActive))
const latestTask = computed(() => records.value[0])
const taskMessage = computed(() => {
  const task = latestTask.value
  if (!task) return ''
  if (isWaterInvasionTaskActive(task)) return task.taskStatus === 'SAVING' ? '正在保存结果…' : '后台分析处理中…'
  if (['FAILED', 'TIMED_OUT'].includes(task.taskStatus)) return task.errorMessage || '本次分析未成功，已保存结果不受影响'
  return ''
})
let contextVersion = 0
let detailVersion = 0
let recordsTimer = null
const scope = () => ({ projectId: props.projectId, gasReservoirId: props.gasReservoirId, wellName: props.node?.wellName })
const errorText = error => error?.response?.data?.msg || error?.msg || error?.message || '读取水侵记录失败'
// 只重新读取本次任务的日志和结果，不再次提交旧算法，避免迟到计算与库任务串结果。
async function checkCalculationStatus() {
  const version = contextVersion
  importing.value = true
  try {
    await waterInvasionApi.reconcile(latestTask.value.id, scope())
    if (version === contextVersion) await refreshRecords(version)
  } catch (error) { if (version === contextVersion) loadError.value = errorText(error) }
  finally { if (version === contextVersion) importing.value = false }
}

async function selectRecord(id, version = contextVersion) {
  const serial = ++detailVersion
  const params = scope()
  selectedRecordId.value = id
  productionPage.value = 1
  wellData.value = null
  loading.value = true
  loadError.value = ''
  try {
    const response = await waterInvasionApi.detail(id, params)
    if (version !== contextVersion || serial !== detailVersion) return
    wellData.value = response.data.result
    activeChartIdx.value = 0
    await nextTick()
    renderChartSoon()
  } catch (error) {
    if (version === contextVersion && serial === detailVersion) loadError.value = errorText(error)
  } finally {
    if (version === contextVersion && serial === detailVersion) loading.value = false
  }
}

async function refreshRecords(version = contextVersion) {
  clearTimeout(recordsTimer)
  const params = scope()
  try {
    const response = await waterInvasionApi.records(params)
    if (version !== contextVersion) return
    const previousLatest = completedRecords.value[0]?.id
    records.value = response.data || []
    const latest = completedRecords.value[0]
    if (latest && (!selectedRecordId.value || (selectedRecordId.value === previousLatest && latest.id !== previousLatest))) {
      await selectRecord(latest.id, version)
      if (version === contextVersion) emit('refresh-tree')
    }
    if (version === contextVersion && hasRunningTask.value) recordsTimer = setTimeout(() => refreshRecords(version), 3000)
  } catch (error) {
    if (version === contextVersion) loadError.value = errorText(error)
  } finally {
    if (version === contextVersion) loading.value = false
  }
}

async function fetchData() {
  const version = ++contextVersion
  ++detailVersion
  clearTimeout(recordsTimer)
  records.value = []
  selectedRecordId.value = ''
  productionPage.value = 1
  wellData.value = null
  loadError.value = ''
  activeChartIdx.value = -1
  if (!props.node?.wellName || !props.projectId || !props.gasReservoirId) return
  loading.value = true
  await refreshRecords(version)
}

async function importLegacyResult() {
  if (hasRunningTask.value || importing.value) return
  const version = contextVersion
  const params = scope()
  try {
    await ElMessageBox.confirm(`将 ${params.wellName} 在旧平台已有的结果导入新数据库，不重新计算。`, '导入旧结果', { type: 'warning' })
    if (version !== contextVersion) return
    importing.value = true
    await waterInvasionApi.importLegacy({ ...params, requestId: crypto.randomUUID() })
    if (version === contextVersion) await refreshRecords(version)
  } catch (error) {
    if (error !== 'cancel' && error !== 'close' && version === contextVersion) ElMessage.error(errorText(error))
  } finally { importing.value = false }
}


// 井名不变时，重新计算完成也需要主动拉取最新详情。
watch(() => [
  props.node?.wellName,
  props.projectId,
  props.gasReservoirId,
  props.node?.waterInvasionRefreshKey
], () => fetchData(), { immediate: true })

// 重新加载或切换页签时，只保留可用的选中项，并优先展示实际返回的内容。
watch([chartTabs, activeChartIdx], ([tabs]) => {
  if (!tabs[activeChartIdx.value]?.available) {
    activeChartIdx.value = tabs.findIndex(tab => tab.available)
  }
  const tab = activeTab.value
  if (!isProductionTab.value && tab?.hasRows && !tab.hasChart) activeContentTab.value = 'table'
  if (tab?.hasOutput && !tab.hasChart && !tab.hasRows) activeParamTab.value = 'output'
  if (!hasOutputResults.value && activeParamTab.value === 'output') {
    activeParamTab.value = 'input'
  }
  if ((!hasDataListForActiveTab.value || !tab?.hasRows) && activeContentTab.value === 'table') {
    activeContentTab.value = 'chart'
  }
  renderChartSoon()
  renderChartSoon(180)
}, { immediate: true })

watch(activeContentTab, () => {
  renderChartSoon()
  renderChartSoon(180)
})

onMounted(() => {
  chart = echarts.init(chartEl.value)
  window.addEventListener('resize', onResize)
  if (wellData.value) renderChartSoon()
})

onBeforeUnmount(() => {
  ++contextVersion
  ++detailVersion
  clearTimeout(recordsTimer)
  window.removeEventListener('resize', onResize)
  stopParamsPanelResize()
  stopLegendDrag()
  if (chartRenderTimer) clearTimeout(chartRenderTimer)
  chart?.dispose()
})
</script>

<template>
  <div v-loading="loading" class="wia-wrap">

    <!-- 左侧参数面板 -->
    <div
        ref="paramsPanelEl"
        class="params-panel water-parameter-theme"
        :class="{ collapsed: paramsCollapsed, narrow: !paramsCollapsed && paramsPanelWidth < 380 }"
        :style="{ width: paramsCollapsed ? '22px' : `${paramsPanelWidth}px`, minWidth: paramsCollapsed ? '22px' : `${paramsPanelWidth}px` }"
    >
      <div v-if="paramsCollapsed" class="panel-collapsed-tab" @click="toggleParamsPanel">
        参数设置
      </div>

      <div v-show="!paramsCollapsed" class="panel-head">
        <span>参数设置</span>
        <button class="panel-toggle" type="button" title="收起参数设置" @click="toggleParamsPanel">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="#777">
            <path d="M16,12V4H17V2H7V4H8V12L6,14V16H11.2V22H12.8V16H18V14L16,12Z"/>
          </svg>
        </button>
      </div>

      <div v-if="!paramsCollapsed && activeParamTab === 'input'" class="panel-body">
        <div class="sec-label">气体性质</div>
        <div class="field-grid">
          <div class="field">
            <label>天然气类型</label>
            <el-select size="small" :model-value="getInputValue(['gasType'])" style="width:100%">
              <el-option label="干气" value="干气" />
              <el-option label="湿气" value="湿气" />
            </el-select>
          </div>
          <div class="field">
            <label>天然气比重(dless)</label>
            <el-input size="small" readonly :model-value="getInputValue(['specificGravity'])" />
          </div>
          <div class="field">
            <label>H₂S摩尔百分含量(%)</label>
            <el-input size="small" readonly :model-value="getInputValue(['hydrogenSulfide'])" />
          </div>
          <div class="field">
            <label>CO₂摩尔百分含量(%)</label>
            <el-input size="small" readonly :model-value="getInputValue(['carbonDioxide'])" />
          </div>
          <div class="field">
            <label>N₂摩尔百分含量(%)</label>
            <el-input size="small" readonly :model-value="getInputValue(['nitrogen'])" />
          </div>
        </div>

        <div class="sec-label">计算方法</div>
        <div class="field-grid">
          <div class="field">
            <label>非烃气体修正方法</label>
            <el-select size="small" :model-value="getMethodValue(MODIFICATION_METHODS, 'modificationMethod')" style="width:100%">
              <el-option v-for="m in MODIFICATION_METHODS" :key="m" :label="m" :value="m" />
            </el-select>
          </div>
          <div class="field">
            <label>天然气偏差系数计算方法</label>
            <el-select size="small" :model-value="getMethodValue(DEVIATION_METHODS, 'deviationFactorMethod')" style="width:100%">
              <el-option v-for="m in DEVIATION_METHODS" :key="m" :label="m" :value="m" />
            </el-select>
          </div>
          <div class="field">
            <label>天然气粘度计算方法</label>
            <el-select size="small" :model-value="getMethodValue(VISCOSITY_METHODS, 'viscosityMethod')" style="width:100%">
              <el-option v-for="m in VISCOSITY_METHODS" :key="m" :label="m" :value="m" />
            </el-select>
          </div>
        </div>

        <div class="sec-label">其它数据</div>
        <div class="field-grid">
          <div class="field">
            <label>原始地层压力(MPa)</label>
            <el-input size="small" readonly :model-value="getInputValue(['originalFormationPressure'])" />
          </div>
          <div class="field">
            <label>气井地层温度(°C)</label>
            <el-input size="small" readonly :model-value="getInputValue(['formationTemperature'])" />
          </div>
          <div class="field">
            <label>气藏地质储量(10⁸m³)</label>
            <el-input size="small" readonly :model-value="getInputValue(['reservoirOriginalGasVolume'])" />
          </div>
          <div class="field">
            <label>束缚水饱和度(%)</label>
            <el-input size="small" readonly :model-value="getInputValue(['waterSaturation'])" />
          </div>
          <div class="field">
            <label>储层岩石压缩系数(MPa⁻¹)</label>
            <el-input size="small" readonly :model-value="getInputValue(['rockCompressionCoefficient'])" />
          </div>
          <div class="field">
            <label>地层水压缩系数(MPa⁻¹)</label>
            <el-input size="small" readonly :model-value="getInputValue(['waterCompressionCoefficient'])" />
          </div>
          <div class="field">
            <label>地层水体积系数(dless)</label>
            <el-input size="small" readonly :model-value="getInputValue(['waterVolumeCoefficient'])" />
          </div>
          <div class="field">
            <label>当前累产气量(10⁸m³)</label>
            <el-input size="small" readonly :model-value="getInputValue(['currCumulativeGasProduction'])" />
          </div>
          <div class="field">
            <label>气藏废弃压力(MPa)</label>
            <el-input size="small" readonly :model-value="getInputValue(['reservoirAbandonmentPressure'])" />
          </div>
        </div>

        <div class="sec-label">计算条件</div>
        <div class="condition-panel">
          <div class="condition-row">
            <el-checkbox
                v-model="preferActualStaticPressure"
                class="condition-checkbox"
                :class="{ 'condition-muted': !preferActualStaticPressure }"
            >
              优先使用实测静压
            </el-checkbox>
          </div>
          <div class="condition-row condition-limit-row">
            <div class="condition-limit-label" :class="{ 'condition-muted': !enableWaterGasRatioLimit }">
              <el-checkbox v-model="enableWaterGasRatioLimit" class="condition-checkbox" />
              <span class="condition-text">生产水气比上限(m³/10⁴m³):</span>
            </div>
            <div class="condition-actions">
              <el-input
                  v-model="waterGasRatioLimitValue"
                  size="small"
                  :disabled="!enableWaterGasRatioLimit"
              />
              <el-button size="small" class="condition-recalculate" :disabled="hasRunningTask || importing" @click="handleRecalculate">
                重新计算
              </el-button>
            </div>
          </div>
        </div>

        <div class="sec-label">生产数据</div>
        <div class="btn-row">
          <el-button size="small" @click="downloadProductionTemplate">模版下载</el-button>
          <el-button size="small" @click="activeContentTab = 'production'">查看数据</el-button>
        </div>
      </div>
      <div v-else-if="!paramsCollapsed && hasOutputResults" class="panel-body">
        <div class="sec-label">输出结果</div>
        <div v-for="field in outputFields" :key="field.label" class="field">
          <label>{{ field.label }}</label>
          <el-input size="small" readonly :model-value="getOutputValue(field.keys)" />
        </div>
      </div>

      <div v-show="!paramsCollapsed" class="param-tabs">
        <div
          class="param-tab"
          :class="{ active: activeParamTab === 'input' }"
          @click="activeParamTab = 'input'"
        >
          输入
        </div>
        <div
          v-if="hasOutputResults"
          class="param-tab"
          :class="{ active: activeParamTab === 'output' }"
          @click="activeParamTab = 'output'"
        >
          输出
        </div>
      </div>
      <div v-if="!paramsCollapsed" class="params-resizer" @mousedown="startParamsPanelResize"></div>
    </div>

    <!-- 右侧图表区域 -->
    <div ref="chartAreaEl" class="chart-area">
      <div class="dynamic-result-tabs">
        <button type="button" class="dynamic-result-tab active" :title="chartTabTitle">
          <span class="dynamic-result-tab-text">{{ chartTabTitle }}</span>
        </button>
      </div>

      <div class="water-record-toolbar">
        <label v-if="completedRecords.length">历史记录
          <select :value="selectedRecordId" @change="selectRecord(Number($event.target.value))">
            <option v-for="record in completedRecords" :key="record.id" :value="record.id">
              {{ waterInvasionRecordLabel(record) }}{{ record.resultCompleteness === 'PARTIAL' ? '（部分结果）' : '' }}
            </option>
          </select>
        </label>
        <span v-else>新数据库暂无已保存结果</span>
        <el-button v-if="!completedRecords.length" size="small" :loading="importing" :disabled="hasRunningTask" @click="importLegacyResult">导入旧结果</el-button>
        <el-button v-if="['FAILED','TIMED_OUT'].includes(latestTask?.taskStatus)" size="small" :loading="importing" @click="checkCalculationStatus">检查计算状态</el-button>
        <el-button size="small" @click="refreshRecords()">刷新</el-button>
        <span v-if="taskMessage" class="water-task-message">{{ taskMessage }}</span>
      </div>
      <div v-if="loadError" class="water-load-error" role="alert">{{ loadError }}</div>
      <div v-if="!isProductionTab && chartTabs.length" class="chart-tabs">
        <button
            v-for="(tab, i) in chartTabs"
            :key="tab.label"
            type="button"
            class="chart-tab"
            :class="{ active: i === activeChartIdx }"
            :disabled="!tab.available"
            :title="tab.available ? tab.label : '暂无分析结果'"
            @click="activeChartIdx = i"
        >{{ tab.label }}</button>
      </div>
      <div v-if="!isProductionTab && !loading && activeChartIdx === -1" class="analysis-empty">暂无可用的分析结果</div>
      <div v-show="activeContentTab === 'chart' && !isWaterActivityTab" ref="chartEl" class="chart-instance"/>

      <section v-if="isProductionTab" class="production-data-panel" aria-label="生产数据">
        <div class="production-data-toolbar">
          <span class="production-data-title">生产数据</span>
          <span class="production-data-count">共 {{ productionItems.length }} 条</span>
          <el-button size="small" @click="downloadProductionTemplate">模板下载</el-button>
          <el-button size="small" :disabled="!productionItems.length" @click="downloadProductionData">导出数据</el-button>
        </div>
        <el-table :data="productionPageRows" size="small" height="100%" border class="production-data-table"
                  empty-text="当前批次暂无生产数据">
          <el-table-column prop="rowNumber" label="序号" width="64" align="center" />
          <el-table-column v-for="column in PRODUCTION_COLUMNS" :key="column.prop" :prop="column.prop"
                           :min-width="column.minWidth" align="center">
            <template #header>
              <div class="production-column-name">{{ column.label }}</div>
              <div class="production-column-unit">{{ column.unit }}</div>
            </template>
            <template #default="{ row }">
              <span :title="String(row[column.prop] ?? '')">{{ formatProductionValue(row, column) }}</span>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination v-if="productionItems.length > PRODUCTION_PAGE_SIZE" v-model:current-page="productionPage"
                       :page-size="PRODUCTION_PAGE_SIZE" :total="productionItems.length" small
                       layout="prev, pager, next, jumper" class="production-data-pagination" />
      </section>

      <div v-if="isDataListTab && hasDataListForActiveTab" class="data-list-panel">
        <el-table :data="dataListRows" size="small" height="100%" border stripe>
          <el-table-column
            v-for="column in dataListColumns"
            :key="column.prop"
            :prop="column.prop"
            :label="column.label"
            :width="column.width"
            :min-width="column.minWidth"
            sortable
            :filters="column.type === 'selected' ? [{ text: YES_TEXT, value: YES_TEXT }, { text: NO_TEXT, value: NO_TEXT }] : undefined"
            :filter-method="column.type === 'selected' ? ((value, row) => row[column.prop] === value) : undefined"
          />
        </el-table>
      </div>

      <div
          v-if="activeContentTab === 'chart' && legendItems.length"
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
          <span class="legend-dot" :style="{ backgroundColor: isLegendItemHidden(item.name) ? 'transparent' : item.color, borderColor: item.color }"></span>
          <span>{{ item.name }}</span>
        </div>
      </div>

      <div v-if="activeContentTab === 'chart' && isWaterActivityTab" class="water-activity-panel">
        <div class="activity-form">
          <div class="activity-field">
            <label>气藏废弃时的水侵量(10⁴m³):</label>
            <el-input size="small" readonly :model-value="waterActivityOutput.abandonWaterInflux ?? ''" />
          </div>
          <div class="activity-field">
            <label>原始条件下气藏容积(10⁸m³):</label>
            <el-input size="small" readonly :model-value="waterActivityOutput.reservoirOriginalVolume ?? ''" />
          </div>
          <div class="activity-field">
            <label>气藏废弃时的水侵替换系数(dless):</label>
            <el-input size="small" readonly :model-value="waterActivityOutput.waterInvasionReplacementCoefficient ?? ''" />
          </div>
          <div class="activity-field">
            <label>边底水活跃程度:</label>
            <el-input size="small" readonly :model-value="waterActivityOutput.waterActivenessDesc ?? ''" />
          </div>
        </div>

        <div class="activity-table-title">边底水活跃程度划分标准(水侵替换系数法)</div>
        <table class="activity-table">
          <tbody>
            <tr>
              <th rowspan="2">评价指标</th>
              <th colspan="3">边底水活跃程度</th>
            </tr>
            <tr>
              <th>活跃</th>
              <th>较活跃</th>
              <th>不活跃</th>
            </tr>
            <tr>
              <td>水侵替换系数</td>
              <td>&gt; 0.4</td>
              <td>0.15 ~ 0.4</td>
              <td>&lt; 0.15</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="bottom-chart-tabs">
        <button type="button" class="bottom-chart-tab" :class="{ active: isProductionTab }"
                @click="activeContentTab = 'production'">生产数据</button>
        <button
          v-if="hasDataListForActiveTab"
          type="button"
          class="bottom-chart-tab"
          :class="{ active: activeContentTab === 'table' }"
          @click="activeContentTab = 'table'"
        >
          数据列表
        </button>
        <button
          type="button"
          class="bottom-chart-tab"
          :class="{ active: activeContentTab === 'chart' }"
          :title="chartTabTitle"
          @click="activeContentTab = 'chart'"
        >
          结果分析图
        </button>
      </div>
    </div>

  </div>
</template>

<style lang="scss" scoped>
.production-data-panel {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  padding: 0 12px;
  overflow: hidden;
}
.production-data-toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding: 10px 0;
  font-size: 12px;
  flex-shrink: 0;
}
.production-data-title { color: #303133; font-weight: 500; }
.production-data-count { color: #909399; margin-right: auto; }
.production-data-table {
  flex: 1;
  min-height: 0;
  :deep(.el-table__cell) { padding: 3px 0; }
  :deep(th.el-table__cell) { background: #fafafa; color: #303133; font-weight: 400; }
}
.production-column-unit { font-size: 12px; color: #606266; font-weight: 400; }
.production-data-pagination { padding: 8px 0; align-self: flex-end; flex-shrink: 0; }
.water-record-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  padding: 6px 12px;
  border-bottom: 1px solid #e5e5e5;
  font-size: 12px;
  color: #555;
  label { display: flex; align-items: center; gap: 8px; }
  select { max-width: 320px; height: 25px; border: 1px solid #dcdcdc; background: #fff; color: #333; }
}
.water-task-message { color: #756321; }
.water-load-error { padding: 6px 12px; color: #a43b32; font-size: 12px; }
.wia-wrap {
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
  &:first-child { margin-top: 4px; }
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

.field-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  column-gap: 24px;
}

.condition-panel {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 5px;
  padding: 1px 0 9px;
  color: #303133;

  :deep(.el-checkbox) {
    height: 24px;
    margin-right: 0;
  }

  :deep(.el-checkbox__label) {
    font-size: 13px;
    color: #303133;
  }

  :deep(.el-checkbox__inner) {
    border-color: #c0c4cc;
  }

  :deep(.el-checkbox__input.is-checked .el-checkbox__inner),
  :deep(.el-checkbox__input.is-indeterminate .el-checkbox__inner) {
    background-color: #303133;
    border-color: #303133;
  }

  :deep(.el-checkbox__input.is-checked + .el-checkbox__label) {
    color: #303133;
  }

}

.analysis-empty {
  padding: 36px 16px;
  color: #909399;
  text-align: center;
}

.condition-row {
  display: flex;
  align-items: center;
  min-height: 24px;
}

.condition-limit-row {
  width: 100%;
  gap: 8px;
}

.condition-limit-label,
.condition-actions {
  display: flex;
  align-items: center;
}

.condition-limit-label {
  gap: 8px;
  flex-shrink: 0;

  .condition-text {
    font-size: 13px;
    color: #303133;
    white-space: nowrap;
  }
}

.condition-actions {
  gap: 4px;
  min-width: 0;

  .el-input {
    width: 135px;
  }
}

.condition-muted {
  :deep(.el-checkbox__label),
  .condition-text {
    color: #a8abb2;
  }
}

.condition-recalculate {
  flex-shrink: 0;
}

.params-panel.narrow {
  .condition-limit-row {
    flex-wrap: wrap;
    row-gap: 5px;
  }

  .condition-actions {
    width: 100%;
    padding-left: 22px;

    .el-input {
      flex: 1;
      width: auto;
      min-width: 0;
    }
  }
}

.btn-row { display: flex; gap: 8px; }

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
  max-width: 340px;
  border: 0;
  border-right: 1px solid #e4e7ed;
  border-bottom: 2px solid transparent;
  background: transparent;
  color: #409eff;
  font-size: 14px;
  font-weight: 600;
  display: flex;
  align-items: center;
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
  height: 34px;
  display: flex;
  border-bottom: 1px solid #e4e7ed;
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

.chart-tab:disabled,
.chart-tab:disabled:hover {
  color: #b0b0b0;
  background: #fafafa;
  border-bottom-color: transparent;
  cursor: not-allowed;
  font-weight: normal;
}

.data-list-panel {
  flex: 1;
  min-height: 0;
  width: 100%;
  overflow: hidden;
  background: #fff;
}

.bottom-chart-tabs {
  display: flex;
  align-items: flex-end;
  height: 30px;
  flex-shrink: 0;
  border-top: 1px solid #e4e7ed;
  background: #fff;
}

.bottom-chart-tab {
  height: 30px;
  min-width: 82px;
  padding: 0 14px;
  border: 0;
  border-right: 1px solid #e4e7ed;
  background: #fff;
  color: #333;
  font-size: 13px;
  cursor: pointer;

  &.active {
    color: #409eff;
    font-weight: 600;
    background: #fff;
  }
}

.floating-chart-legend {
  position: absolute;
  z-index: 5;
  display: flex;
  flex-direction: column;
  gap: 5px;
  max-width: 280px;
  padding: 7px 10px;
  border: 1px solid #eeeeee;
  background: rgba(255, 255, 255, 0.9);
  color: #333;
  font-size: 12px;
  line-height: 1.2;
  cursor: move;
  user-select: none;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);

  &.dragging {
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.14);
  }
}

.floating-legend-item {
  display: flex;
  align-items: center;
  gap: 5px;
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

.water-activity-panel {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 14px 18px;
  background: #fff;
}

.activity-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(260px, 1fr));
  column-gap: 22px;
  row-gap: 8px;
  margin-bottom: 24px;
}

.activity-field {
  label {
    display: block;
    font-size: 13px;
    color: #333;
    margin-bottom: 4px;
  }
}

.activity-table-title {
  text-align: center;
  font-size: 14px;
  color: #333;
  margin-bottom: 8px;
}

.activity-table {
  width: 100%;
  border-collapse: collapse;
  table-layout: fixed;
  font-size: 13px;
  color: #333;

  th,
  td {
    border: 1px solid #333;
    height: 44px;
    text-align: center;
    font-weight: 400;
  }
}
</style>
