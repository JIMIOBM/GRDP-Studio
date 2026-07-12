<script setup>
/**
 * WaterInvasionContent.vue
 * 水侵动态分析 —— 右侧内容面板
 * 调用真实接口，左侧参数来自 input，右侧图表来自 outputs[].chartItems
 *
 * 路径：src/views/WellControlInventory/WaterInvasionContent.vue
 */
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import * as echarts from 'echarts'
import dockerRequest from '@/api/docker'

const props = defineProps({ // 父组件传进来的数据
  node:           Object,           // 包含 wellName 字段
  projectId:      [Number, String],
  gasReservoirId: [Number, String]
})

// 通知父组件（IprInterface）刷新左侧树
const emit = defineEmits(['refresh-tree'])

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
const output = computed(() => wellData.value?.outputs?.[activeChartIdx.value]?.output || {})
const outputFields = computed(() => OUTPUT_FIELD_CONFIGS[activeChartIdx.value] || [])
const hasOutputResults = computed(() => outputFields.value.length > 0)
const isWaterActivityTab = computed(() => activeChartIdx.value === 4)
const isDataListTab = computed(() => activeContentTab.value === 'table')
const waterActivityOutput = computed(() => wellData.value?.outputs?.[4]?.output || {})
const currentWellName = computed(() => props.node?.wellName || wellData.value?.input?.wellName || '')
const chartTabTitle = computed(() =>
    `动态分析-水侵动态分析-${currentWellName.value || '当前井'}-分析结果`
)

//用于控制右侧浮动图例的位置
const legendPosition = ref({ x: null, y: null })
const draggingLegend = ref(false)
const legendDragOffset = ref({ x: 0, y: 0 })
const hiddenLegendNames = ref(new Set())

const legendItems = computed(() => {
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

const wgrEnabled = computed(() => (input.value.waterGasRatioLimit ?? -1) > 0)

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

// 只保留有 chartItems 的 outputs，作为图表标签页
const chartTabs = computed(() => {
  if (!wellData.value) return []
  return (wellData.value.outputs || [])
      .slice(0, CHART_TAB_LABELS.length)
      .map((o, index) => ({
        analysisId:  o.analysisId,
        label:       CHART_TAB_LABELS[index],
        chartItems:  o.chartItems || [],
        outputItems: o.outputItems || []
      }))
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

const dataListRows = computed(() => {
  const inputId = input.value?.id
  const matchedOutput = (wellData.value?.outputs || []).find(output => {
    const analysisId = output?.analysisId
    return String(analysisId) === String(inputId)
  })
  const rows = matchedOutput?.outputItems || []
  return rows.map((row, index) => {
    const isDeleted = getRowValue(row, ['isDeleted'], false)
    return {
      index: index + 1,
      date: formatDateValue(getRowValue(row, ['date'])),
      pressure: formatDecimalValue(getRowValue(row, ['pressure']), 4),
      cumulativeGasProduction: formatDecimalValue(getRowValue(row, ['cumulativeGasProduction']), 4),
      cumulativeWaterProduction: formatDecimalValue(getRowValue(row, ['cumulativeWaterProduction']), 4),
      apparentPressure: formatDecimalValue(getRowValue(row, ['apparentPressure']), 4),
      recoveryDegree: formatDecimalValue(getRowValue(row, ['recoveryDegree']), 2),
      selected: isDeleted ? '否' : '是'
    }
  })
})


// ─── ECharts ───
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
  const width = Math.max(180, Math.min(520, event.clientX - leftBoundary))
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
  return (item?.data || [])
      .filter(d => !d.isDeleted)
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
  if (!chart || !activeTab.value) return
  if (isDataListTab.value) {
    chart.clear()
    return
  }
  if (isWaterActivityTab.value) {
    chart.clear()
    return
  }

  const tab = activeTab.value
  if (!tab.chartItems?.length) {
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

// ─── API 调用 ───
async function fetchData() { //请求水侵分析详情
  const wellName = props.node?.wellName
  if (!wellName || !props.projectId || !props.gasReservoirId) return

  loading.value        = true
  activeChartIdx.value = 0
  activeContentTab.value = 'chart'
  activeParamTab.value = 'input'
  wellData.value       = null

  try {
    const res = await dockerRequest.get(
        `/projectanalysis/waterinvasionanalysis/${props.projectId}/${props.gasReservoirId}/well/${encodeURIComponent(wellName)}`
    )
    wellData.value = res.data
    await nextTick()
    renderChartSoon()
    // 数据加载成功后通知父组件刷新左侧树
    emit('refresh-tree')
  } catch (e) {
    console.error('[WaterInvasionContent] 数据加载失败', e)
  } finally {
    loading.value = false
  }
}


//监听井名变化
watch(() => props.node?.wellName, fetchData, { immediate: true })

//监听图表页切换
watch(activeChartIdx, () => {
  if (!hasOutputResults.value && activeParamTab.value === 'output') {
    activeParamTab.value = 'input'
  }
  renderChartSoon()
  renderChartSoon(180)
})

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
        class="params-panel"
        :class="{ collapsed: paramsCollapsed }"
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
          <div class="field field-with-switch">
            <div class="wgr-label-row">
              <span>生产水气比上限(m³/10⁴m³)</span>
              <el-switch
                  :model-value="wgrEnabled"
                  disabled
                  style="--el-switch-on-color:#e8a000;--el-switch-off-color:#ccc"
                  size="small"
              />
            </div>
            <el-input
                size="small"
                readonly
                :disabled="!wgrEnabled"
                :model-value="getInputValue(['waterGasRatioLimit'])"
            />
          </div>
        </div>

        <div class="sec-label">生产数据</div>
        <div class="btn-row">
          <el-button size="small">模版下载</el-button>
          <el-button size="small">导入</el-button>
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

      <div v-if="activeContentTab === 'chart' && chartTabs.length" class="chart-tabs">
        <button
            v-for="(tab, i) in chartTabs"
            :key="tab.analysisId"
            type="button"
            class="chart-tab"
            :class="{ active: i === activeChartIdx }"
            @click="activeChartIdx = i"
        >{{ tab.label }}</button>
      </div>
      <div v-show="activeContentTab === 'chart' && !isWaterActivityTab" ref="chartEl" class="chart-instance"/>

      <div v-if="isDataListTab" class="data-list-panel">
        <el-table :data="dataListRows" size="small" height="100%" border stripe>
          <el-table-column prop="index" label="序号" width="76" sortable />
          <el-table-column prop="date" label="日期" min-width="150" sortable />
          <el-table-column prop="pressure" label="地层压力(MPa)" min-width="160" sortable />
          <el-table-column prop="cumulativeGasProduction" label="累产气量(10⁸m³)" min-width="170" sortable />
          <el-table-column prop="cumulativeWaterProduction" label="累产水量(10⁴m³)" min-width="170" sortable />
          <el-table-column prop="apparentPressure" label="无因次视压力(dless)" min-width="180" sortable />
          <el-table-column prop="recoveryDegree" label="采出程度(%)" min-width="150" sortable />
          <el-table-column
            prop="selected"
            label="是否参与分析"
            min-width="150"
            :filters="[{ text: '是', value: '是' }, { text: '否', value: '否' }]"
            :filter-method="(value, row) => row.selected === value"
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
        <button
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
  span { color: #555; font-size: 12px; }
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
