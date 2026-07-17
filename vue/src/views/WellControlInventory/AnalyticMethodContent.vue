<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import { analyticMethodApi } from '@/api/docker'

const props = defineProps({
  node: Object,
  projectId: [Number, String],
  gasReservoirId: [Number, String]
})

const MODIFICATION_METHODS = ['Wichert-Aziz 修正方法', 'Carr-Kobayashi-Burrous 修正方法']
const DEVIATION_METHODS = ['Dranchuk-Abu-Kassem 方法', 'Dranchuk-Purvis-Robinson 方法', 'Hall-Yarborough 方法']
const VISCOSITY_METHODS = ['Lee-Gonzalez-Eakin 方法', 'Carr-Kobayashi-Burrous 方法', 'Sutton 方法']
const MATERIAL_BALANCE_METHODS = ['封闭气藏', '定容气藏', '页岩气藏']

const loading = ref(false)
const activeParamTab = ref('input')
const activeChartTab = ref('chart')
const resultData = ref(null)
const chartEl = ref(null)
const chartAreaEl = ref(null)
const importFileInput = ref(null)
const paramsPanelEl = ref(null)
const paramsPanelWidth = ref(238)
const paramsCollapsed = ref(false)
const resizingParamsPanel = ref(false)
const legendPosition = ref({ x: null, y: null })
const draggingLegend = ref(false)
const legendDragOffset = ref({ x: 0, y: 0 })
const hiddenLegendNames = ref(new Set())
let chart = null

const normalizePayload = (res) => res?.data?.data ?? res?.data ?? res

const rawNode = computed(() => props.node?.raw || {})
const wellName = computed(() => props.node?.wellName || rawNode.value?.wellName || rawNode.value?.nodeTitle || '')
const chartTabTitle = computed(() => `解析法-${wellName.value || '当前井'}-分析结果`)
const fields = computed(() => Array.isArray(resultData.value?.fields) ? resultData.value.fields : [])
const input = computed(() =>
    resultData.value?.input ||
    resultData.value?.inputs ||
    resultData.value?.parameter ||
    resultData.value?.params ||
    {}
)
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

const getResultIdFromNode = () => {
  const raw = rawNode.value
  return raw.resultId || raw.analysisId || raw.analysisID || raw.analysis_id ||
      raw.AnalysisMethodsId || raw.analysisMethodsId || raw.id || props.node?.resultId
}

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
const getFieldLabel = (name, fallback = name) => {
  const field = getFieldMeta(name)
  return field?.label || field?.title || field?.description || field?.name_label || fallback
}

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
  if (/^\d+$/.test(String(value))) return labels[Number(value)] || String(value)
  return value
}

const inputValue = (keys, fallback = '') => getValue(input.value, keys, fallback)
const outputValue = (keys, fallback = '') => getValue(output.value, keys, fallback)

const gasType = computed(() => methodLabel(inputValue(['gasType'], '干气'), ['', '', '干气'], '干气'))
const modificationMethod = computed(() =>
    methodLabel(inputValue(['modificationMethod'], 0), MODIFICATION_METHODS, 'Wichert-Aziz 修正方法')
)
const deviationMethod = computed(() =>
    methodLabel(inputValue(['deviationFactorMethod'], 0), DEVIATION_METHODS, 'Dranchuk-Abu-Kassem 方法')
)
const viscosityMethod = computed(() =>
    methodLabel(inputValue(['viscosityMethod'], 0), VISCOSITY_METHODS, 'Lee-Gonzalez-Eakin 方法')
)
const materialBalanceMethod = computed(() =>
    methodLabel(inputValue(['mbEquationType', 'materialBalanceType'], 0), MATERIAL_BALANCE_METHODS, '封闭气藏')
)
const minimumWaterGasRatio = computed(() => inputValue(['minimumWaterGasRatio', 'waterGasRatioLimit'], -1))
const waterGasRatioEnabled = computed(() => Number(minimumWaterGasRatio.value) > 0)
const isDryGas = computed(() => gasType.value === '干气')
const productionColumns = computed(() => {
  const base = [
    { field: 'date', label: '日期' },
    { field: 'bottomPressure', label: '井底压力' },
    { field: 'dailyGasProduction', label: '气产量' }
  ]
  if (!isDryGas.value) base.push({ field: 'dailyOilProduction', label: '油产量' })
  base.push(
      { field: 'cumulativeGasProduction', label: '累产气量' },
      { field: 'cumulativeWaterProduction', label: '累产水量' }
  )
  return base
})

const inputFields = computed(() => ({
  gas: [
    { label: '天然气类型', value: gasType.value, select: true },
    { label: withUnit('天然气比重', 'specificGravity', 'dless'), value: inputValue(['specificGravity']) },
    { label: withUnit('H2S摩尔百分含量', 'hydrogenSulfide', '%'), value: inputValue(['hydrogenSulfide']) },
    { label: withUnit('CO2摩尔百分含量', 'carbonDioxide', '%'), value: inputValue(['carbonDioxide']) },
    { label: withUnit('N2摩尔百分含量', 'nitrogen', '%'), value: inputValue(['nitrogen']) }
  ],
  method: [
    { label: '非烃气体修正方法', value: modificationMethod.value, select: true },
    { label: '天然气偏差系数计算方法', value: deviationMethod.value, select: true },
    { label: '天然气粘度计算方法', value: viscosityMethod.value, select: true }
  ],
  other: [
    { label: withUnit('原始地层压力', 'originalFormationPressure', 'MPa'), value: inputValue(['originalFormationPressure']) },
    { label: withUnit('气井地层温度', 'formationTemperature', '℃'), value: inputValue(['formationTemperature']) },
    { label: withUnit('井筒半径', 'wellboreRadius', 'm'), value: inputValue(['wellboreRadius', 'wellRadius']) },
    { label: withUnit('动态地质储量', 'originalGasVolume', '10^8m3'), value: inputValue(['originalGasVolume']) },
    { label: '物质平衡方程类型', value: materialBalanceMethod.value, select: true },
    { label: withUnit('储层厚度', 'reservoirThickness', 'm'), value: inputValue(['reservoirThickness']) },
    { label: withUnit('储层孔隙度', 'reservoirPorosity', '%'), value: inputValue(['reservoirPorosity']) },
    { label: withUnit('束缚水饱和度', 'waterSaturation', '%'), value: inputValue(['waterSaturation']) },
    { label: withUnit('地层水压缩系数', 'waterCompressionCoefficient', 'MPa^-1'), value: inputValue(['waterCompressionCoefficient']) },
    { label: withUnit('储层岩石压缩系数', 'rockCompressionCoefficient', 'MPa^-1'), value: inputValue(['rockCompressionCoefficient']) },
    { label: withUnit('生产水气比上限', 'minimumWaterGasRatio', 'm3/10^4m3'), value: minimumWaterGasRatio.value }
  ]
}))

const displayedFieldGroups = computed(() => {
  const groups = [
    ['气体性质', inputFields.value.gas],
    ['计算方法', inputFields.value.method],
    ['其它数据', inputFields.value.other]
  ]
  return groups.map(([title, items]) => ({
    title,
    items: items.filter(item => item.value !== undefined && item.value !== null && item.value !== '')
  })).filter(group => group.items.length)
})

const outputFields = computed(() => [
  { label: withUnit('渗透率', 'permeability', 'mD'), value: outputValue(['permeability']) },
  { label: withUnit('表皮系数', 'skinFactor', 'dless'), value: outputValue(['skinFactor']) }
].filter(item => item.value !== undefined && item.value !== null && item.value !== ''))
const hasOutputResults = computed(() => outputFields.value.length > 0)

const formatDate = (_row, _column, value) => value ? String(value).slice(0, 10) : ''

const defaultTableColumns = [
  { prop: 'date', label: '生产时间', minWidth: 130, formatter: formatDate },
  { prop: 'dailyGasProduction', label: '气产量(10^4m3/d)', minWidth: 150 },
  { prop: 'bottomPressure', label: '真实井底流压(MPa)', minWidth: 160 },
  { prop: 'theoreticalBottomPressure', label: '理论井底流压(MPa)', minWidth: 170 }
]

const productionUnitRow = computed(() =>
    productionColumns.value.map(column => getFieldUnit(column.field) || '无')
)

const tableColumns = computed(() => defaultTableColumns)

const ANALYTIC_CHART_FIELDS = new Set(['dailyGasProduction', 'bottomPressure', 'theoreticalBottomPressure'])
const ANALYTIC_CHART_NAMES = ['气产量', '真实井底流压', '理论井底流压']
const normalizeChartName = (name = '') => String(name).trim().replace(/[（(].*$/, '').trim()
const displayedChartItems = computed(() => chartItems.value.filter(item => {
  const field = item.yAxisField || item.yField || ''
  const name = normalizeChartName(item.name)
  return ANALYTIC_CHART_FIELDS.has(field) || ANALYTIC_CHART_NAMES.includes(name)
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
  if (!displayedChartItems.value.length) {
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
      text: `井${wellName.value}的解析法结果图`,
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

const getXlsx = async () => import('xlsx')

const saveWorkbook = (XLSX, workbook, filename) => {
  XLSX.writeFile(workbook, filename)
}

const downloadProductionTemplate = async () => {
  const XLSX = await getXlsx()
  const rows = [
    productionColumns.value.map(column => column.label),
    productionUnitRow.value
  ]
  const sheet = XLSX.utils.aoa_to_sheet(rows)
  const workbook = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(workbook, sheet, '生产数据')
  saveWorkbook(XLSX, workbook, `解析法生产数据模板-${wellName.value || 'well'}.xlsx`)
}

const openProductionImport = () => {
  importFileInput.value?.click()
}

const normalizeExcelDate = (XLSX, value) => {
  if (value instanceof Date) return value.toISOString().slice(0, 10)
  if (typeof value === 'number') {
    const parsed = XLSX.SSF.parse_date_code(value)
    if (parsed) {
      const month = String(parsed.m).padStart(2, '0')
      const day = String(parsed.d).padStart(2, '0')
      return `${parsed.y}-${month}-${day}`
    }
  }
  return String(value || '').slice(0, 10)
}

const parseProductionRows = (XLSX, worksheet) => {
  const table = XLSX.utils.sheet_to_json(worksheet, { header: 1, raw: true, defval: '' })
  const header = table[0] || []
  const expectedHeader = productionColumns.value.map(column => column.label)
  const headerValid = expectedHeader.every((label, index) => String(header[index] || '').trim() === label)
  if (!headerValid) {
    throw new Error('请按模板填写数据')
  }

  return table.slice(2)
      .filter(row => row.slice(0, productionColumns.value.length).some(value => value !== '' && value !== null && value !== undefined))
      .map(row => {
        const item = {}
        productionColumns.value.forEach((column, index) => {
          const value = row[index]
          item[column.field] = column.field === 'date'
              ? normalizeExcelDate(XLSX, value)
              : Number(value || 0)
        })
        item.dailyOilProduction = item.dailyOilProduction || 0
        return item
      })
}

const importProductionData = async (event) => {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return

  try {
    const resultId = getResultIdFromNode()
    if (!resultId) throw new Error('没有找到该井的解析法结果 ID')

    const XLSX = await getXlsx()
    const buffer = await file.arrayBuffer()
    const workbook = XLSX.read(buffer, { type: 'array', cellDates: true })
    const sheetName = workbook.SheetNames[0]
    const worksheet = workbook.Sheets[sheetName]
    const inputItems = parseProductionRows(XLSX, worksheet)

    if (!inputItems.length) throw new Error('生产数据不能为空')
    if (inputItems.some(item => !item.date || !Number.isFinite(item.bottomPressure) || item.bottomPressure <= 0)) {
      throw new Error('请检查生产数据中的日期和井底压力')
    }

    const originalPressure = Number(inputValue(['originalFormationPressure'], 0))
    if (originalPressure > 0 && inputItems.some(item => Number(item.bottomPressure) >= originalPressure)) {
      throw new Error('井底压力不能大于等于原始地层压力')
    }

    loading.value = true
    await analyticMethodApi.historyFittingByWell(wellName.value, {
      wellName: wellName.value,
      projectId: Number(props.projectId),
      gasReservoirId: Number(props.gasReservoirId),
      analysisId: Number(resultId),
      input: {
        ...input.value,
        dataSize: inputValue(['dataSize'], 30),
        waterGasRatioLimit: minimumWaterGasRatio.value
      },
      inputItems
    })

    ElMessage.success('生产数据导入完成，解析法结果已更新')
    await fetchData()
  } catch (error) {
    ElMessage.error(error.response?.data?.msg || error.response?.data?.message || error.message || '生产数据导入失败')
    console.error('[AnalyticMethodContent] 生产数据导入失败', error)
  } finally {
    loading.value = false
  }
}

const renderChartSoon = (delay = 0) => {
  setTimeout(() => {
    nextTick(() => {
      requestAnimationFrame(() => {
        chart?.resize()
        renderChart()
      })
    })
  }, delay)
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

const fetchData = async () => {
  if (!props.projectId || !props.gasReservoirId || !wellName.value) return
  loading.value = true
  activeParamTab.value = 'input'
  activeChartTab.value = 'chart'
  resultData.value = null

  try {
    const resultId = getResultIdFromNode()
    if (!resultId) throw new Error('没有找到该井的解析法结果 ID')

    const res = await analyticMethodApi.getResult(props.projectId, props.gasReservoirId, resultId)
    resultData.value = normalizePayload(res)
    await nextTick()
    renderChartSoon()
  } catch (error) {
    console.error('[AnalyticMethodContent] 数据加载失败', error)
  } finally {
    loading.value = false
  }
}

watch(() => [props.node?.id, props.node?.wellName], fetchData, { immediate: true })
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
})
</script>

<template>
  <div v-loading="loading" class="analytic-wrap">
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

        <div v-if="activeParamTab === 'input'" class="panel-body">
          <div v-if="!displayedFieldGroups.length" class="empty">暂无接口输入参数</div>
          <template v-for="group in displayedFieldGroups" :key="group.title">
            <div class="sec-label">{{ group.title }}</div>
            <div class="field-grid">
              <div v-for="field in group.items" :key="`${group.title}-${field.label}`" class="field">
                <label>{{ field.label }}</label>
                <el-select v-if="field.select" size="small" disabled :model-value="field.value" style="width:100%">
                  <el-option :label="field.value" :value="field.value" />
                </el-select>
                <el-input v-else size="small" readonly :model-value="field.value" />
              </div>
            </div>
          </template>

          <div class="sec-label">生产数据</div>
          <div class="btn-row">
            <el-button size="small" @click="downloadProductionTemplate">模板下载</el-button>
            <el-button size="small" @click="openProductionImport">导入</el-button>
          </div>
          <input
              ref="importFileInput"
              class="hidden-file-input"
              type="file"
              accept=".xlsx,.xls"
              @change="importProductionData"
          />
        </div>

        <div v-else-if="hasOutputResults" class="panel-body">
          <div class="sec-label">输出结果</div>
          <div v-for="field in outputFields" :key="field.label" class="field">
            <label>{{ field.label }}</label>
            <el-input size="small" readonly :model-value="field.value" />
          </div>
        </div>
        <div v-else class="panel-body">
          <div class="empty">暂无接口输出结果</div>
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
              class="legend-dot"
              :style="{ backgroundColor: isLegendItemHidden(item.name) ? 'transparent' : item.color, borderColor: item.color }"
          ></span>
          <span>{{ item.name }}</span>
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
.analytic-wrap {
  height: 100%;
  display: flex;
  min-height: 0;
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

.field {
  margin-bottom: 9px;

  label {
    display: block;
    color: #555;
    font-size: 12px;
    margin-bottom: 3px;
  }
}

.field-with-switch {
  .el-input {
    margin-top: 3px;
  }
}

.wgr-label-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 3px;

  span {
    color: #555;
    font-size: 12px;
  }
}

.btn-row {
  display: flex;
  gap: 8px;
}

.hidden-file-input {
  display: none;
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
