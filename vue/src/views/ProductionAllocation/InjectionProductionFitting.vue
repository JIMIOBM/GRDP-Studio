<script setup>
/**
 * 配产配注-注采拟合（采气端）
 *
 * 计算内核复用原平台"动态分析-解析法"接口（analyticMethodApi）：
 * 参数/生产数据从数据管理自动带出，缺项由用户在本面板补充；
 * 点击"拟合计算"由 IprInterface 触发解析法计算并等待完成日志，
 * 结果通过 getResult 回到本组件渲染（拟合曲线 + 输出参数 + 数据列表）。
 * 注气端后续按同一"储层+井筒"两段式模型反向扩展（material/注采拟合.md）。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import { analyticMethodApi, dataManagementApi, parametersApi } from '@/api/docker'
import InventoryOperationPreview from '@/views/WellControlInventory/InventoryOperationPreview.vue'
import { unpack, rowsOf, read, numberOf, wellRows, latestRow, productionDate } from '@/utils/temperatureSources'
// 与解析法共用一份选项常量（平台产品口径，避免两页面不一致）。
import {
  MODIFICATION_METHODS,
  DEVIATION_METHODS,
  VISCOSITY_METHODS,
  MATERIAL_BALANCE_METHODS,
  GAS_TYPES
} from '@/constants/analyticMethodOptions'

const props = defineProps({
  node: Object,
  projectId: [Number, String],
  gasReservoirId: [Number, String],
  recalculating: Boolean
})

const emit = defineEmits(['recalculate'])

const loading = ref(false)
const sourceLoading = ref(false)
const sourceErrors = ref([])
const activeChartTab = ref('chart')
const resultData = ref(null)
const chartEl = ref(null)
const chartAreaEl = ref(null)
const paramsPanelEl = ref(null)
const paramsPanelWidth = ref(300)
const paramsCollapsed = ref(false)
const resizingParamsPanel = ref(false)
const legendPosition = ref({ x: null, y: null })
const draggingLegend = ref(false)
const legendDragOffset = ref({ x: 0, y: 0 })
const hiddenLegendNames = ref(new Set())
let chart = null

// —— 输入表单：数据管理带出后仍可编辑，缺项由用户输入 ——
const form = reactive({
  gasType: '干气',
  specificGravity: '',
  hydrogenSulfide: '',
  carbonDioxide: '',
  nitrogen: '',
  modificationMethod: MODIFICATION_METHODS[0],
  deviationMethod: DEVIATION_METHODS[0],
  viscosityMethod: VISCOSITY_METHODS[0],
  originalFormationPressure: '',
  formationTemperature: '',
  wellboreRadius: 0.06,
  originalGasVolume: '',
  mbEquationType: '定容气藏',
  reservoirThickness: '',
  reservoirPorosity: '',
  waterSaturation: '',
  waterCompressionCoefficient: '',
  rockCompressionCoefficient: '',
  dataSize: 30
})
const waterGasRatioLimitEnabled = ref(false)
const waterGasRatioLimitValue = ref('')
const productionRows = ref([])
const productionFields = ref([])
const productionLoaded = ref(false)
// 注采数据已成功读取但为空：拟合计算必然在平台侧失败，直接禁用更直观（读取失败时不禁用，让平台报错浮出）。
const productionUnavailable = computed(() => productionLoaded.value && productionRows.value.length === 0)

const normalizePayload = (res) => res?.data?.data ?? res?.data ?? res

const rawNode = computed(() => props.node?.raw || {})
const wellName = computed(() => props.node?.wellName || rawNode.value?.wellName || '')

const resultId = computed(() => {
  const raw = rawNode.value
  return raw.resultId || raw.analysisId || raw.analysisID || raw.analysis_id ||
      raw.AnalysisMethodsId || raw.analysisMethodsId || props.node?.resultId || null
})

const fields = computed(() => Array.isArray(resultData.value?.fields) ? resultData.value.fields : [])
const output = computed(() => ({
  ...(resultData.value?.output || {}),
  ...(resultData.value?.result || {}),
  ...(resultData.value?.analysis || {})
}))
const outputItems = computed(() => {
  const data = resultData.value || {}
  return data.items || data.outputItems || data.resultItems || data.outputs?.[0]?.outputItems || []
})
const chartItems = computed(() => resultData.value?.chartItems || resultData.value?.outputs?.[0]?.chartItems || [])
const hasResult = computed(() => Boolean(resultId.value && resultData.value))
const chartTabTitle = computed(() => `注采拟合-${wellName.value || '当前井'}-分析结果`)

const getFieldMeta = (name) => fields.value.find(item =>
    item?.name === name || item?.field === name || item?.key === name || item?.prop === name
)
const getFieldUnit = (name) => {
  const field = getFieldMeta(name)
  return field?.unit_label || field?.unitLabel || field?.unit || ''
}
const withUnit = (label, name, fallbackUnit = '') => {
  const unit = getFieldUnit(name) || fallbackUnit
  return unit ? `${label}(${unit})` : label
}
const outputValue = (keys, fallback = '') => {
  for (const key of keys) {
    const value = output.value?.[key]
    if (value !== undefined && value !== null && value !== '') return value
  }
  return fallback
}

const resultWellType = computed(() => outputValue(['wellType']))
const resultIsFractured = computed(() => {
  const value = outputValue(['isFractured'])
  return value === true || value === 1 || value === '1' || value === '是'
})
const isFracturedHorizontalWell = computed(() =>
    resultIsFractured.value && resultWellType.value === '水平井'
)

const outputFields = computed(() => {
  const items = [
    {
      label: withUnit(isFracturedHorizontalWell.value ? '基质渗透率' : '渗透率', 'permeability', 'mD'),
      value: outputValue(['permeability'])
    },
    { label: withUnit('表皮系数', 'skinFactor', 'dless'), value: outputValue(['skinFactor']) }
  ]
  if (resultIsFractured.value) {
    items.push({
      label: withUnit('裂缝半长', 'fractureHalfLength', 'm'),
      value: outputValue(['fractureHalfLength', 'effectiveFractureHalfLength'])
    })
  }
  if (isFracturedHorizontalWell.value) {
    items.push({
      label: withUnit('裂缝渗透率', 'fracturePermeability', 'mD'),
      value: outputValue(['fracturePermeability'])
    })
  }
  return items.filter(item => item.value !== undefined && item.value !== null && item.value !== '')
})
const hasOutputResults = computed(() => outputFields.value.length > 0)

// —— 生产数据（数据管理"注采数据"）只读预览 ——
const productionColumns = computed(() => {
  const unit = name => productionFields.value.find(field => field?.name === name)?.unit_label || ''
  const label = (cn, name, fallbackUnit) => {
    const resolved = unit(name) || fallbackUnit
    return resolved ? `${cn}(${resolved})` : cn
  }
  return [
    { prop: 'date', label: '日期', minWidth: 104, value: row => productionDate(row) || String(read(row, 'date', 'production_date') ?? '').slice(0, 10) },
    {
      prop: 'bottomPressure',
      label: label('井底压力', 'measuredBottomHolePressure', 'MPa'),
      minWidth: 118,
      value: row => read(row, 'measuredBottomHolePressure', 'measured_bottom_hole_pressure',
          'calculatedBottomHolePressure', 'calculated_bottom_hole_pressure', 'bottomHolePressure') ?? ''
    },
    {
      prop: 'dailyGasProduction',
      label: label('气产量', 'dailyGasProduction', '10⁴m³/d'),
      minWidth: 126,
      value: row => read(row, 'dailyGasProduction', 'daily_gas_production') ?? ''
    },
    {
      prop: 'cumulativeGasProduction',
      label: label('累产气量', 'cumulativeGasProduction', '10⁴m³'),
      minWidth: 126,
      value: row => read(row, 'cumulativeGasProduction', 'cumulative_gas_production') ?? ''
    },
    {
      prop: 'cumulativeWaterProduction',
      label: label('累产水量', 'cumulativeWaterProduction', '10⁴m³'),
      minWidth: 126,
      value: row => read(row, 'cumulativeWaterProduction', 'cumulative_water_production') ?? ''
    }
  ]
})

// —— 结果数据列表（与解析法同口径）——
const formatDate = (_row, _column, value) => value ? String(value).slice(0, 10) : ''
const tableColumns = [
  { prop: 'date', label: '生产时间', minWidth: 130, formatter: formatDate },
  { prop: 'dailyGasProduction', label: '气产量(10⁴m³/d)', minWidth: 150 },
  { prop: 'bottomPressure', label: '真实井底流压(MPa)', minWidth: 160 },
  { prop: 'theoreticalBottomPressure', label: '理论井底流压(MPa)', minWidth: 170 }
]

// —— 参数面板取值：优先原平台该次拟合的计算记录（结果 input），其次数据管理带出 ——
const ENUM_FIELD_OPTIONS = {
  modificationMethod: MODIFICATION_METHODS,
  deviationMethod: DEVIATION_METHODS,
  viscosityMethod: VISCOSITY_METHODS,
  mbEquationType: MATERIAL_BALANCE_METHODS
}
// 平台 input 键 → 面板字段（与解析法页面同源的字段名，个别保留别名兜底）。
const PANEL_FIELD_INPUT_KEYS = {
  gasType: ['gasType'],
  specificGravity: ['specificGravity'],
  hydrogenSulfide: ['hydrogenSulfide'],
  carbonDioxide: ['carbonDioxide'],
  nitrogen: ['nitrogen'],
  modificationMethod: ['modificationMethod'],
  deviationMethod: ['deviationFactorMethod', 'deviationMethod'],
  viscosityMethod: ['viscosityMethod'],
  originalFormationPressure: ['originalFormationPressure', 'originalPressure'],
  formationTemperature: ['formationTemperature', 'temperature'],
  wellboreRadius: ['wellboreRadius', 'wellRadius'],
  originalGasVolume: ['originalGasVolume'],
  mbEquationType: ['mbEquationType', 'materialBalanceType'],
  reservoirThickness: ['reservoirThickness'],
  reservoirPorosity: ['reservoirPorosity'],
  waterSaturation: ['waterSaturation'],
  waterCompressionCoefficient: ['waterCompressionCoefficient'],
  rockCompressionCoefficient: ['rockCompressionCoefficient']
}

// 数据管理带出的值（平台计算记录缺失时的兜底展示）。
const sourceValues = ref({})

// 平台该次拟合记录的计算参数（getResult 返回的 input；接口已按展示单位返回，原样使用）。
const platformInputValues = computed(() => {
  const data = resultData.value || {}
  const input = data.input || data.inputs || data.parameter || data.params
  return input && typeof input === 'object' ? input : null
})

const normalizeEnumValue = (key, value) => {
  const options = ENUM_FIELD_OPTIONS[key]
  if (!options) return value
  const index = Number(value)
  if (Number.isInteger(index) && index >= 0 && index < options.length) return options[index]
  return String(value)
}

// 统一填充只读面板：平台计算记录优先，缺项回落数据管理带出；都没有时保留空/默认展示。
function applyPanelValues() {
  const platform = platformInputValues.value || {}
  for (const [key, candidates] of Object.entries(PANEL_FIELD_INPUT_KEYS)) {
    let value = null
    for (const candidate of candidates) {
      const candidateValue = platform[candidate]
      if (candidateValue !== null && candidateValue !== undefined && candidateValue !== '') {
        value = candidateValue
        break
      }
    }
    if (value === null) value = sourceValues.value[key]
    if (value === null || value === undefined || value === '') continue
    form[key] = key === 'gasType' ? (normalizeGasType(value) || String(value)) : normalizeEnumValue(key, value)
  }
}

watch([platformInputValues, sourceValues], applyPanelValues)

const normalizeGasType = (value) => {
  if (value === null || value === undefined || value === '') return ''
  const index = Number(value)
  if ([0, 1, 2].includes(index) && String(value).trim().length <= 1) return GAS_TYPES[index] || ''
  return String(value)
}

const applySources = async () => {
  if (!props.projectId || !props.gasReservoirId || !wellName.value) return
  sourceLoading.value = true
  sourceErrors.value = []

  const silent = { silentError: true }
  // 数据管理带出先写入缓存对象，由 applyPanelValues 统一决定面板展示（平台计算记录优先）。
  const draft = {}
  const assign = (key, value) => {
    if (value === null || value === undefined || value === '') return
    draft[key] = value
  }
  const [gasRes, otherRes, prodRes, logRes, limitRes] = await Promise.allSettled([
    dataManagementApi.getGasProperties(props.projectId, props.gasReservoirId, silent),
    dataManagementApi.getOtherData(props.projectId, props.gasReservoirId, silent),
    dataManagementApi.getProductionData(props.projectId, props.gasReservoirId, wellName.value, silent),
    dataManagementApi.getLogInterpretation(props.projectId, props.gasReservoirId, wellName.value, silent),
    parametersApi.getMinWaterGasRatio(props.projectId, props.gasReservoirId, silent)
  ])

  // 天然气性质（气体性质区）
  if (gasRes.status === 'fulfilled') {
    const row = latestRow(wellRows(rowsOf(gasRes.value), wellName.value))
    if (row) {
      assign('gasType', normalizeGasType(read(row, 'gasType', 'naturalGasType', 'gas_type')))
      assign('specificGravity', read(row, 'specificGravity', 'relativeDensity', 'specific_gravity'))
      assign('hydrogenSulfide', read(row, 'hydrogenSulfide', 'h2sContent', 'h2SMoleFraction', 'h2s_mole_fraction'))
      assign('carbonDioxide', read(row, 'carbonDioxide', 'co2Content', 'co2MoleFraction', 'co2_mole_fraction'))
      assign('nitrogen', read(row, 'nitrogen', 'nitrogenContent', 'n2MoleFraction', 'n2_mole_fraction'))
    }
  } else {
    sourceErrors.value.push('天然气性质读取失败')
  }

  // 其他数据（原始地层压力/地层温度/动态地质储量）
  if (otherRes.status === 'fulfilled') {
    const row = latestRow(wellRows(rowsOf(otherRes.value), wellName.value))
    if (row) {
      assign('originalFormationPressure', read(row, 'originalFormationPressure', 'original_formation_pressure'))
      assign('formationTemperature', read(row, 'formationTemperature', 'formation_temperature'))
      assign('originalGasVolume', read(row, 'singleWellOriginalGasInplace', 'single_well_original_gas_inplace'))
    }
  } else {
    sourceErrors.value.push('其他数据读取失败')
  }

  // 注采数据（生产数据预览）
  if (prodRes.status === 'fulfilled') {
    const rows = wellRows(rowsOf(prodRes.value), wellName.value, true)
    productionRows.value = [...rows].sort((a, b) => productionDate(a).localeCompare(productionDate(b)))
    const payload = unpack(prodRes.value)
    productionFields.value = Array.isArray(payload?.fields) ? payload.fields : []
    productionLoaded.value = true
  } else {
    sourceErrors.value.push('注采数据读取失败')
  }

  // 测井解释（储层厚度/孔隙度/束缚水饱和度）：取解释序号最新的一批层位，
  // 厚度求和，孔隙度与束缚水饱和度按厚度加权，避免多层解释时只取单层造成偏差。
  if (logRes.status === 'fulfilled') {
    const rows = wellRows(rowsOf(logRes.value), wellName.value)
    const latestNumber = rows.reduce((max, row) => {
      const value = numberOf(read(row, 'interpretationNumber', 'interpretation_number'))
      return value !== null && value > max ? value : max
    }, -Infinity)
    const batch = Number.isFinite(latestNumber)
        ? rows.filter(row => numberOf(read(row, 'interpretationNumber', 'interpretation_number')) === latestNumber)
        : rows
    if (batch.length) {
      const thicknessTotal = batch.reduce((sum, row) => sum + (numberOf(read(row, 'thickness')) ?? 0), 0)
      if (thicknessTotal > 0) assign('reservoirThickness', thicknessTotal)
      const weighted = (valueKeys) => {
        let weightSum = 0
        let valueSum = 0
        for (const row of batch) {
          const value = numberOf(read(row, ...valueKeys))
          if (value === null) continue
          const thickness = numberOf(read(row, 'thickness'))
          const weight = thickness !== null && thickness > 0 ? thickness : 1
          weightSum += weight
          valueSum += value * weight
        }
        return weightSum > 0 ? valueSum / weightSum : null
      }
      const porosity = weighted(['porosity'])
      if (porosity !== null) assign('reservoirPorosity', Number(porosity.toFixed(4)))
      const waterSaturation = weighted(['irreducibleWaterSaturation', 'irreducible_water_saturation'])
      if (waterSaturation !== null) assign('waterSaturation', Number(waterSaturation.toFixed(4)))
    }
  } else {
    sourceErrors.value.push('测井数据读取失败')
  }

  // 生产水气比上限（后端字段名拼写为 vaule，原样兼容）
  if (limitRes.status === 'fulfilled') {
    const raw = limitRes.value?.data?.vaule ?? limitRes.value?.data?.value
    const limit = numberOf(raw)
    waterGasRatioLimitEnabled.value = limit !== null && limit > 0
    waterGasRatioLimitValue.value = limit !== null && limit > 0 ? limit : ''
  }

  sourceValues.value = draft
  sourceLoading.value = false
}

// —— 结果获取 ——
const fetchData = async () => {
  if (!props.projectId || !props.gasReservoirId || !wellName.value) return
  const id = resultId.value
  if (!id) {
    resultData.value = null
    renderChartSoon()
    return
  }

  loading.value = true
  try {
    const res = await analyticMethodApi.getResult(props.projectId, props.gasReservoirId, id)
    resultData.value = normalizePayload(res)
    await nextTick()
    renderChartSoon()
  } catch (error) {
    console.error('[InjectionProductionFitting] 结果加载失败', error)
  } finally {
    loading.value = false
  }
}

// fitRevision 覆盖"结果 ID 不变但内容已更新"的场景（平台按井重算同一条记录时）。
watch(() => [resultId.value, props.node?.fitRevision, props.node?.wellName, props.node?.id], fetchData, { immediate: true })

// —— 图表渲染（三条白名单曲线：气产量 / 真实井底流压 / 理论井底流压）——
const CHART_FIELDS = new Set(['dailyGasProduction', 'bottomPressure', 'theoreticalBottomPressure'])
const CHART_NAMES = ['气产量', '真实井底流压', '理论井底流压']
const normalizeChartName = (name = '') => String(name).trim().replace(/[（(].*$/, '').trim()
const displayedChartItems = computed(() => chartItems.value.filter(item => {
  const field = item.yAxisField || item.yField || ''
  const name = normalizeChartName(item.name)
  return CHART_FIELDS.has(field) || CHART_NAMES.includes(name)
}))
const CHART_COLORS = ['#ffd400', '#8b3f42', '#bc8c6d']
const legendItems = computed(() => displayedChartItems.value.map((item, index) => ({
  name: item.name || item.yAxisField || item.yField,
  color: CHART_COLORS[index % CHART_COLORS.length]
})))
const legendStyle = computed(() => legendPosition.value.x === null || legendPosition.value.y === null
    ? { top: '36px', right: '18px' }
    : { left: `${legendPosition.value.x}px`, top: `${legendPosition.value.y}px` })
const isLegendItemHidden = name => hiddenLegendNames.value.has(name)
const toggleLegendItem = (name) => {
  const next = new Set(hiddenLegendNames.value)
  next.has(name) ? next.delete(name) : next.add(name)
  hiddenLegendNames.value = next
  renderChartSoon()
}

const getPoint = (item) => {
  const xField = item.xAxisField || item.xField || 'date'
  const yField = item.yAxisField || item.yField
  return (item.data || item.items || [])
      .filter(row => !row?.isDeleted)
      .map(row => {
        const x = row.xValue ?? row[xField]
        const y = Number(row.yValue ?? (yField ? row[yField] : row.y))
        return [x, Number.isFinite(y) ? y : null]
      })
      .filter(point => point[0] !== undefined && point[0] !== null && point[1] !== null)
}

const isPressureSeries = (name = '', field = '') =>
    String(name).includes('压力') || String(field).includes('Pressure') || String(field).includes('pressure')

const formatChartDate = (value, separator = '/') => {
  const text = String(value ?? '')
  const match = text.match(/^(\d{4})[-/]?(\d{2})[-/]?(\d{2})/)
  return match ? `${match[1]}${separator}${match[2]}${separator}${match[3]}` : text
}

const getChartYear = value => formatChartDate(value).slice(0, 4)

const renderChart = () => {
  if (!chart || activeChartTab.value !== 'chart') return
  if (!hasResult.value || !displayedChartItems.value.length) {
    chart.clear()
    return
  }

  const series = displayedChartItems.value.map((item, index) => ({
    name: item.name || item.yAxisField || item.yField,
    type: 'line',
    showSymbol: true,
    symbol: 'circle',
    symbolSize: index === 1 ? 4 : 3,
    smooth: false,
    color: CHART_COLORS[index % CHART_COLORS.length],
    lineStyle: { width: index === 1 ? 1 : 1.5 },
    yAxisIndex: isPressureSeries(item.name, item.yAxisField || item.yField) ? 1 : 0,
    data: getPoint(item)
  })).filter(item => !hiddenLegendNames.value.has(item.name))
  const categoryValues = series[0]?.data?.map(point => point[0]) || []

  chart.setOption({
    animation: false,
    tooltip: {
      trigger: 'axis',
      formatter: params => {
        const rows = Array.isArray(params) ? params : [params]
        if (!rows.length) return ''
        const date = formatChartDate(rows[0]?.axisValue ?? rows[0]?.value?.[0])
        return [date, ...rows.map(row => {
          const value = Array.isArray(row.value) ? row.value[1] : row.value
          return `${row.marker || ''}${row.seriesName}：${value ?? ''}`
        })].join('<br/>')
      }
    },
    title: {
      text: `井${wellName.value}的注采拟合结果图`,
      left: 'center',
      top: 8,
      textStyle: { fontSize: 14, fontWeight: 600, color: '#303133' }
    },
    legend: { show: false },
    grid: { top: 52, left: 58, right: 66, bottom: 48, containLabel: true },
    xAxis: {
      type: 'category',
      name: '生产时间',
      nameLocation: 'middle',
      nameGap: 30,
      boundaryGap: false,
      axisLine: { lineStyle: { color: '#666' } },
      axisLabel: {
        color: '#666',
        hideOverlap: true,
        interval: (index, value) => index === 0 || getChartYear(value) !== getChartYear(categoryValues[index - 1]),
        formatter: value => getChartYear(value)
      },
      splitLine: { show: true, lineStyle: { color: '#eeeeee', width: 1 } }
    },
    yAxis: [
      {
        type: 'value',
        name: '气产量(10⁴m³/d)',
        nameLocation: 'middle',
        nameGap: 42,
        axisLine: { show: true, lineStyle: { color: '#666' } },
        axisLabel: { color: '#666' },
        splitLine: { show: true, lineStyle: { color: '#e8e8e8', width: 1 } }
      },
      {
        type: 'value',
        name: '压力(MPa)',
        nameLocation: 'middle',
        nameGap: 42,
        position: 'right',
        axisLine: { show: true, lineStyle: { color: '#666' } },
        axisLabel: { color: '#666' },
        splitLine: { show: false }
      }
    ],
    series
  }, true)
}

// function 声明而非 const：fetchData 经 watch(immediate) 在 setup 期间同步执行，
// 需要在声明之前就能引用（函数声明有提升，避免 TDZ）。
function renderChartSoon(delay = 0) {
  setTimeout(() => {
    nextTick(() => {
      requestAnimationFrame(() => {
        chart?.resize()
        renderChart()
      })
    })
  }, delay)
}

// —— 参数面板折叠 / 拖宽、图例拖拽 ——
function toggleParamsPanel() {
  paramsCollapsed.value = !paramsCollapsed.value
  renderChartSoon(180)
}

function onParamsPanelResize(event) {
  if (!resizingParamsPanel.value) return
  const left = paramsPanelEl.value?.getBoundingClientRect().left || 0
  paramsPanelWidth.value = Math.max(270, Math.min(560, event.clientX - left))
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
  legendPosition.value = {
    x: legendPosition.value.x ?? legendRect.left - areaRect.left,
    y: legendPosition.value.y ?? legendRect.top - areaRect.top
  }
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

const handleCalculate = () => {
  const dataSize = Number(form.dataSize)
  if (!Number.isFinite(dataSize) || dataSize <= 0) {
    ElMessage.warning('数据样本数必须大于 0')
    return
  }
  if (waterGasRatioLimitEnabled.value) {
    const limit = Number(waterGasRatioLimitValue.value)
    if (!Number.isFinite(limit) || limit <= 0) {
      ElMessage.warning('生产水气比上限必须大于 0')
      return
    }
  }
  emit('recalculate', {
    wellName: wellName.value,
    dataSize,
    minimumWaterGasRatio: waterGasRatioLimitEnabled.value ? Number(waterGasRatioLimitValue.value) : -1
  })
}

watch(activeChartTab, renderChartSoon)
watch(() => props.node?.wellName, () => { applySources() })

onMounted(() => {
  chart = echarts.init(chartEl.value)
  window.addEventListener('resize', renderChartSoon)
  renderChartSoon()
  applySources()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', renderChartSoon)
  stopParamsPanelResize()
  stopLegendDrag()
  chart?.dispose()
})
</script>

<template>
  <div v-loading="sourceLoading || loading" class="injection-fitting-wrap">
    <aside
        ref="paramsPanelEl"
        class="params-panel water-parameter-theme"
        :class="{ collapsed: paramsCollapsed, narrow: !paramsCollapsed && paramsPanelWidth < 380 }"
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

        <div class="panel-body">
          <div class="sec-label">输入参数</div>
          <InventoryOperationPreview :context-key="JSON.stringify([projectId, gasReservoirId, node?.wellName, node?.id])" />
          <div class="calc-note">以下参数取自原平台该次拟合的计算记录（只读展示）；尚无计算记录时展示数据管理中的已有数据。</div>

          <div class="sec-label">气体性质</div>
          <div class="field-grid">
            <div class="field">
              <label>天然气类型</label>
              <el-select v-model="form.gasType" size="small" style="width:100%" disabled>
                <el-option v-for="option in GAS_TYPES" :key="option" :label="option" :value="option" />
              </el-select>
            </div>
            <div class="field">
              <label>天然气比重(dless)</label>
              <el-input v-model="form.specificGravity" size="small" disabled />
            </div>
            <div class="field">
              <label>H₂S摩尔百分含量(%)</label>
              <el-input v-model="form.hydrogenSulfide" size="small" disabled />
            </div>
            <div class="field">
              <label>CO₂摩尔百分含量(%)</label>
              <el-input v-model="form.carbonDioxide" size="small" disabled />
            </div>
            <div class="field">
              <label>N₂摩尔百分含量(%)</label>
              <el-input v-model="form.nitrogen" size="small" disabled />
            </div>
          </div>

          <div class="sec-label">计算方法</div>
          <div class="field-grid">
            <div class="field">
              <label>非烃气体修正方法</label>
              <el-select v-model="form.modificationMethod" size="small" style="width:100%" disabled>
                <el-option v-for="option in MODIFICATION_METHODS" :key="option" :label="option" :value="option" />
              </el-select>
            </div>
            <div class="field">
              <label>天然气偏差系数计算方法</label>
              <el-select v-model="form.deviationMethod" size="small" style="width:100%" disabled>
                <el-option v-for="option in DEVIATION_METHODS" :key="option" :label="option" :value="option" />
              </el-select>
            </div>
            <div class="field">
              <label>天然气粘度计算方法</label>
              <el-select v-model="form.viscosityMethod" size="small" style="width:100%" disabled>
                <el-option v-for="option in VISCOSITY_METHODS" :key="option" :label="option" :value="option" />
              </el-select>
            </div>
          </div>

          <div class="sec-label">其它数据</div>
          <div class="field-grid">
            <div class="field">
              <label>原始地层压力(MPa)</label>
              <el-input v-model="form.originalFormationPressure" size="small" disabled />
            </div>
            <div class="field">
              <label>气井地层温度(℃)</label>
              <el-input v-model="form.formationTemperature" size="small" disabled />
            </div>
            <div class="field">
              <label>井筒半径(m)</label>
              <el-input v-model="form.wellboreRadius" size="small" disabled />
            </div>
            <div class="field">
              <label>动态地质储量(10⁸m³)</label>
              <el-input v-model="form.originalGasVolume" size="small" disabled />
            </div>
            <div class="field">
              <label>物质平衡方程类型</label>
              <el-select v-model="form.mbEquationType" size="small" style="width:100%" disabled>
                <el-option v-for="option in MATERIAL_BALANCE_METHODS" :key="option" :label="option" :value="option" />
              </el-select>
            </div>
            <div class="field">
              <label>储层厚度(m)</label>
              <el-input v-model="form.reservoirThickness" size="small" disabled />
            </div>
            <div class="field">
              <label>储层孔隙度(%)</label>
              <el-input v-model="form.reservoirPorosity" size="small" disabled />
            </div>
            <div class="field">
              <label>束缚水饱和度(%)</label>
              <el-input v-model="form.waterSaturation" size="small" disabled />
            </div>
            <div class="field">
              <label>地层水压缩系数(MPa⁻¹)</label>
              <el-input v-model="form.waterCompressionCoefficient" size="small" disabled />
            </div>
            <div class="field">
              <label>储层岩石压缩系数(MPa⁻¹)</label>
              <el-input v-model="form.rockCompressionCoefficient" size="small" disabled />
            </div>
          </div>

          <div class="sec-label">计算条件</div>
          <div class="condition-panel">
            <div class="condition-row condition-limit-row">
              <div class="condition-limit-label">
                <span class="condition-text">数据样本数:</span>
              </div>
              <div class="condition-actions">
                <el-input v-model="form.dataSize" size="small" />
              </div>
            </div>
            <div class="condition-row condition-limit-row">
              <div class="condition-limit-label" :class="{ 'condition-muted': !waterGasRatioLimitEnabled }">
                <el-checkbox v-model="waterGasRatioLimitEnabled" class="condition-checkbox" />
                <span class="condition-text">生产水气比上限(m³/10⁴m³):</span>
              </div>
              <div class="condition-actions">
                <el-input v-model="waterGasRatioLimitValue" size="small" :disabled="!waterGasRatioLimitEnabled" />
              </div>
            </div>
            <div class="btn-row">
              <el-button
                  size="small"
                  type="primary"
                  class="condition-calculate"
                  :loading="recalculating"
                  :disabled="recalculating || productionUnavailable"
                  @click="handleCalculate"
              >
                拟合计算
              </el-button>
            </div>
            <div v-if="productionUnavailable" class="calc-note">该井暂无注采数据，无法拟合，请先在数据管理中检查或导入。</div>
            <div v-else class="calc-note">拟合计算复用原平台解析法接口，参数以平台工程库为准。</div>
          </div>

          <div v-if="sourceErrors.length" class="source-errors">
            <div v-for="message in sourceErrors" :key="message">{{ message }}</div>
          </div>

          <div class="output-section">
            <div class="sec-label">输出结果</div>
            <template v-if="hasOutputResults">
              <div v-for="field in outputFields" :key="field.label" class="field">
                <label>{{ field.label }}</label>
                <el-input size="small" readonly :model-value="field.value" />
              </div>
            </template>
            <div v-else class="empty">暂无接口输出结果</div>
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

      <div v-show="activeChartTab === 'chart' && hasResult" ref="chartEl" class="chart"></div>

      <div
          v-if="activeChartTab === 'chart' && hasResult && legendItems.length"
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

      <!-- 尚无拟合结果时展示数据管理带出的注采数据预览，作为空态内容 -->
      <div v-if="activeChartTab === 'chart' && !hasResult" class="production-preview">
        <div class="preview-hint">
          <template v-if="node?.pendingResultLookup">正在读取平台已有拟合结果…</template>
          <template v-else-if="productionRows.length">
            已从数据管理读取 {{ productionRows.length }} 条注采数据，确认左侧参数后点击"拟合计算"生成结果。
          </template>
          <template v-else>未读取到该井注采数据，请先在数据管理中检查或导入。</template>
        </div>
        <div class="table-wrap">
          <el-table :data="productionRows" size="small" height="100%" border>
            <el-table-column type="index" label="序号" width="64" />
            <el-table-column
                v-for="column in productionColumns"
                :key="column.prop"
                :label="column.label"
                :min-width="column.minWidth"
            >
              <template #default="{ row }">{{ column.value(row) }}</template>
            </el-table-column>
          </el-table>
        </div>
      </div>

      <div v-if="activeChartTab === 'table'" class="table-wrap">
        <el-table :data="outputItems" size="small" height="100%" border>
          <el-table-column type="index" label="序号" width="64" />
          <el-table-column
              v-for="column in tableColumns"
              :key="column.prop"
              :prop="column.prop"
              :label="column.label"
              :min-width="column.minWidth"
              :formatter="column.formatter"
          />
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
.injection-fitting-wrap {
  height: 100%;
  display: flex;
  min-height: 0;
  background: #fff;
  overflow: hidden;
}

.params-panel {
  width: 300px;
  min-width: 300px;
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

// 全局 water-parameter-theme 只定义了 panel-body 的内边距；
// 滚动能力需各面板自带（与 AnalyticMethodContent/NodalAnalysis 一致），否则内容超高会被面板裁掉。
.panel-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
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

.btn-row {
  display: flex;
  gap: 8px;
}

.calc-note {
  margin-top: 4px;
  color: #909399;
  font-size: 11px;
  line-height: 1.5;
}

.source-errors {
  margin: 6px 0;
  padding: 6px 8px;
  border: 1px solid #f3d19e;
  border-radius: 4px;
  background: #fdf6ec;
  color: #e6a23c;
  font-size: 12px;
  line-height: 1.5;
}

.empty {
  padding: 12px 0;
  color: #999;
  font-size: 12px;
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

.chart {
  flex: 1;
  min-height: 0;
  width: 100%;
}

.production-preview {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 10px 12px 0;

  .preview-hint {
    flex-shrink: 0;
    margin-bottom: 8px;
    color: #909399;
    font-size: 12px;
    line-height: 1.5;
  }

  .table-wrap {
    padding: 0;
  }
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

.table-wrap {
  flex: 1;
  min-height: 0;
  padding: 8px;
  overflow: hidden;
}
</style>
