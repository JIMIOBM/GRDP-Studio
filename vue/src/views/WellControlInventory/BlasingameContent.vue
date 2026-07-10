<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({
  node: Object,
  projectId: [Number, String],
  gasReservoirId: [Number, String]
})

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
let chart = null

const raw = computed(() => props.node?.raw || {})
const result = computed(() => raw.value?.result || raw.value?.output || raw.value?.outputs?.[0]?.output || {})
const input = computed(() => raw.value?.input || raw.value?.inputs || raw.value?.parameter || {})

const asArray = (value) => Array.isArray(value) ? value : []

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
  const arrays = collectCandidateArrays(raw.value, ['data', 'items', 'productionData', 'rows', 'records'])
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
const fittingMode = computed(() => {
  const isSkip = inputValue(['isSkipFitting'], false)
  return isSkip ? '跳过拟合' : '自动拟合'
})
const waterGasRatioLimit = computed(() => inputValue(['waterGasRatioLimit'], ''))
const waterGasRatioEnabled = computed(() => Number(waterGasRatioLimit.value) > 0)

const inputFields = computed(() => ({
  gas: [
    { label: '天然气类型', value: gasType.value, select: true },
    { label: '天然气比重(dless)', value: inputValue(['specificGravity'], 0.58) },
    { label: 'H₂S摩尔百分含量(%)', value: inputValue(['hydrogenSulfide'], 4.62) },
    { label: 'CO₂摩尔百分含量(%)', value: inputValue(['carbonDioxide'], 3.96) },
    { label: 'N₂摩尔百分含量(%)', value: inputValue(['nitrogen'], 0) }
  ],
  method: [
    { label: '非烃气体修正方法', value: modificationMethod.value, select: true },
    { label: '天然气偏差系数计算方法', value: deviationMethod.value, select: true },
    { label: '天然气粘度计算方法', value: viscosityMethod.value, select: true }
  ],
  control: [
    { label: '拟合方式', value: fittingMode.value, select: true },
    { label: '抽样点数', value: inputValue(['dataSize'], 300) },
    { label: '粗扫数据点数量', value: inputValue(['initScanDataSize'], 10) },
    { label: '精扫数据点数量', value: inputValue(['fineSandDataSize'], 30) }
  ],
  other: [
    { label: '原始地层压力(MPa)', value: inputValue(['originalFormationPressure'], 50) },
    { label: '气井地层温度(℃)', value: inputValue(['formationTemperature'], 80) },
    { label: '井筒半径(m)', value: inputValue(['wellboreRadius', 'wellRadius'], 0.06) },
    { label: '动态地质储量(10⁸m³)', value: resultValue(['originalGasVolume'], inputValue(['originalGasVolume'], '')) },
    { label: '物质平衡方程类型', value: inputValue(['materialBalanceTypeDesc', 'dynamicOriginalGasInplaceMethodDescription'], '封闭气藏'), select: true },
    { label: '储层厚度(m)', value: inputValue(['reservoirThickness'], 128.33) },
    { label: '储层孔隙度(%)', value: inputValue(['reservoirPorosity', 'porosity'], 4.23) },
    { label: '束缚水饱和度(%)', value: inputValue(['waterSaturation'], 26.16) },
    { label: '地层水压缩系数(MPa⁻¹)', value: inputValue(['waterCompressionCoefficient'], '3.7445E-4') },
    { label: '储层岩石压缩系数(MPa⁻¹)', value: inputValue(['rockCompressionCoefficient'], '1.0000E-4') }
  ]
}))

const outputFields = computed(() => [
  { label: '渗透率(mD)', value: resultValue(['permeability'], '') },
  { label: '裂缝半长(m)', value: resultValue(['effectiveFractureHalfLength'], '') },
  { label: '表皮系数(dless)', value: resultValue(['skinFactor'], '') },
  { label: '动态地质储量(10⁸m³)', value: resultValue(['originalGasVolume'], '') },
  { label: '井控面积(km²)', value: resultValue(['wellControlArea'], '') },
  { label: '井控半径(m)', value: resultValue(['wellControlRadius'], '') }
])
const hasOutputResults = computed(() => outputFields.value.length > 0)

const legendItems = computed(() => [
  { name: 'qDd-实际数据', color: '#7ee000' },
  { name: 'qDdi-实际数据', color: '#00a020' },
  { name: 'qDdid-实际数据', color: '#e60000' }
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

const tableRows = computed(() => {
  if (records.value.length) {
    return records.value.map((item, index) => ({
      index: index + 1,
      date: item.date || item.productionDate || '',
      formationPressure: item.formationPressure ?? '',
      gasProduction: item.cumulativeGasProduction ?? item.gasProduction ?? '',
      waterProduction: item.cumulativeWaterProduction ?? item.waterProduction ?? ''
    }))
  }

  return [
    ['2004-04-21', 27.5262, 0.0013, 0.0004],
    ['2004-04-22', 31.1019, 0.0054, 0.0013],
    ['2004-04-23', 32.6322, 0.0093, 0.0013],
    ['2004-04-24', 30.2160, 0.0098, 0.0014],
    ['2004-04-25', 29.4892, 0.0110, 0.0016],
    ['2004-04-26', 31.1989, 0.0143, 0.0019],
    ['2004-04-27', 31.6176, 0.0178, 0.0024]
  ].map(([date, formationPressure, gasProduction, waterProduction], index) => ({
    index: index + 1,
    date,
    formationPressure,
    gasProduction,
    waterProduction
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

const toSeriesPoints = (item, yKeys = ['yValue', 'y', 'value', 'qDd', 'qDdi', 'qDdid', 'qdd', 'qddi', 'qddid']) =>
    getSeriesPointItems(item)
        .map(point => getPoint(point, ['xValue', 'x', 'tCaDd', 'tcaDd', 'tcaD', 'tD', 'td'], yKeys))
        .filter(Boolean)

const chartSeriesItems = computed(() =>
    collectCandidateArrays(raw.value, [
      'chartItems',
      'typicalCurveItems',
      'curveItems',
      'typeCurves',
      'typicalCurves',
      'templateCurves',
      'series',
      'curves',
      'graphs'
    ])
        .flat()
        .filter(item => item && typeof item === 'object' && getSeriesPointItems(item).length)
)

const actualSeries = computed(() => {
  const qDd = []
  const qDdi = []
  const qDdid = []

  chartSeriesItems.value.forEach((item, index) => {
    const name = getSeriesName(item, index)
    const field = String(item?.yAxisField || item?.field || '').toLowerCase()
    const points = toSeriesPoints(item)
    if (/qddid/i.test(name) || field === 'qddid') qDdid.push(...points)
    else if (/qddi/i.test(name) || field === 'qddi') qDdi.push(...points)
    else if (/qdd/i.test(name) || field === 'qdd') qDd.push(...points)
  })

  records.value.forEach(item => {
    const xKeys = ['tCaDd', 'tcaDd', 'tcaD', 'tD', 'td', 'xValue']
    const qDdPoint = getPoint(item, xKeys, ['qDd', 'qdd', 'yValue'])
    const qDdiPoint = getPoint(item, xKeys, ['qDdi', 'qddi'])
    const qDdidPoint = getPoint(item, xKeys, ['qDdid', 'qddid'])
    if (qDdPoint) qDd.push(qDdPoint)
    if (qDdiPoint) qDdi.push(qDdiPoint)
    if (qDdidPoint) qDdid.push(qDdidPoint)
  })

  return { qDd, qDdi, qDdid }
})

const typeCurves = computed(() => {
  const chartItems = raw.value?.chartItems || raw.value?.typicalCurveItems || raw.value?.curveItems || []
  if (Array.isArray(chartItems) && chartItems.length) {
    return chartItems.map((item, index) => ({
      name: item.name || item.title || `曲线${index + 1}`,
      data: (item.data || item.items || []).map(point => getPoint(point, ['xValue', 'x', 'tCaDd'], ['yValue', 'y', 'qDd'])).filter(Boolean)
    })).filter(item => item.data.length)
  }

  return Array.from({ length: 15 }).map((_, index) => {
    const shift = index * 0.08
    const slope = 0.38 + index * 0.012
    const data = [0.001, 0.003, 0.01, 0.03, 0.1, 0.3, 1, 3, 10, 30, 100].map(x => {
      const bend = 1 + Math.pow(x / (0.9 + index * 0.12), 0.7)
      const y = Math.max(0.01, (1.2 + shift) / bend + slope / Math.sqrt(x + 0.06))
      return [x, y]
    })
    return { name: `典型曲线${index + 1}`, data }
  })
})

const parsedTypeCurves = computed(() =>
    chartSeriesItems.value
        .map((item, index) => {
          const name = getSeriesName(item, index)
          const field = String(item?.yAxisField || item?.field || '').toLowerCase()
          const isActual = /实际|actual|qdd/i.test(name) || ['qdd', 'qddi', 'qddid'].includes(field)
          if (isActual) return null
          return {
            name,
            data: toSeriesPoints(item)
          }
        })
        .filter(item => item?.data?.length)
)

function renderChart() {
  if (!chart) return

  const series = [
    ...parsedTypeCurves.value.map((item, index) => ({
      name: item.name,
      type: 'line',
      data: item.data,
      showSymbol: false,
      smooth: true,
      lineStyle: { width: 1, color: ['#5470c6', '#91cc75', '#fac858', '#ee6666', '#73c0de', '#3ba272', '#fc8452', '#9a60b4'][index % 8] }
    })),
    {
      name: 'qDd-实际数据',
      type: 'scatter',
      data: actualSeries.value.qDd,
      symbolSize: 6,
      itemStyle: { color: '#7ee000' }
    },
    {
      name: 'qDdi-实际数据',
      type: 'scatter',
      data: actualSeries.value.qDdi,
      symbolSize: 6,
      itemStyle: { color: '#00a020' }
    },
    {
      name: 'qDdid-实际数据',
      type: 'scatter',
      data: actualSeries.value.qDdid,
      symbolSize: 6,
      itemStyle: { color: '#e60000' }
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
      text: 'Blasingame',
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
      data: ['qDd-实际数据', 'qDdi-实际数据', 'qDdid-实际数据']
    },
    grid: { left: 72, right: 52, top: 60, bottom: 58 },
    xAxis: {
      type: 'log',
      min: 0.001,
      max: 100,
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
      min: 0.01,
      max: 10,
      name: 'qDd,qDdi,qDdid',
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

function toggleParamsPanel() {
  paramsCollapsed.value = !paramsCollapsed.value
  renderChartSoon(180)
}

function onParamsPanelResize(event) {
  if (!resizingParamsPanel.value) return
  const left = paramsPanelEl.value?.getBoundingClientRect().left || 0
  paramsPanelWidth.value = Math.max(180, Math.min(520, event.clientX - left))
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

watch(() => props.node, renderChartSoon, { deep: true })
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
  <div class="blasingame-wrap">
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
              <el-select v-if="field.select" size="small" disabled :model-value="field.value" style="width:100%">
                <el-option :label="field.value" :value="field.value" />
              </el-select>
              <el-input v-else size="small" readonly :model-value="field.value" />
            </div>
          </div>

          <div class="sec-label">计算方法</div>
          <div class="field-grid">
            <div v-for="field in inputFields.method" :key="field.label" class="field">
              <label>{{ field.label }}</label>
              <el-select size="small" disabled :model-value="field.value" style="width:100%">
                <el-option :label="field.value" :value="field.value" />
              </el-select>
            </div>
          </div>

          <div class="sec-label">控制参数</div>
          <div class="field-grid">
            <div v-for="field in inputFields.control" :key="field.label" class="field">
              <label>{{ field.label }}</label>
              <el-select v-if="field.select" size="small" disabled :model-value="field.value" style="width:100%">
                <el-option :label="field.value" :value="field.value" />
              </el-select>
              <el-input v-else size="small" readonly :model-value="field.value" />
            </div>
            <div class="field field-with-switch">
              <div class="wgr-label-row">
                <span>生产水气比上限(m³/10⁴m³)</span>
                <el-switch
                    :model-value="waterGasRatioEnabled"
                    disabled
                    style="--el-switch-on-color:#e8a000;--el-switch-off-color:#ccc"
                    size="small"
                />
              </div>
              <el-input size="small" readonly :disabled="!waterGasRatioEnabled" :model-value="waterGasRatioLimit" />
            </div>
          </div>

          <div class="sec-label">其它数据</div>
          <div class="field-grid">
            <div v-for="field in inputFields.other" :key="field.label" class="field">
              <label>{{ field.label }}</label>
              <el-select v-if="field.select" size="small" disabled :model-value="field.value" style="width:100%">
                <el-option :label="field.value" :value="field.value" />
              </el-select>
              <el-input v-else size="small" readonly :model-value="field.value" />
            </div>
          </div>

          <div class="sec-label">生产数据</div>
          <div class="btn-row">
            <el-button size="small">模板下载</el-button>
            <el-button size="small">导入</el-button>
          </div>
        </div>

        <div v-else-if="hasOutputResults" class="panel-body">
          <div class="sec-label">输出结果</div>
          <div v-for="field in outputFields" :key="field.label" class="field">
            <label>{{ field.label }}</label>
            <el-input size="small" readonly :model-value="field.value" />
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
      <div class="chart-tabs">
        <button type="button" class="chart-tab" :class="{ active: activeChartTab === 'chart' }" @click="activeChartTab = 'chart'">
          结果分析图
        </button>
        <button type="button" class="chart-tab" :class="{ active: activeChartTab === 'table' }" @click="activeChartTab = 'table'">
          数据列表
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
          <el-table-column prop="date" label="日期" min-width="150" sortable />
          <el-table-column prop="formationPressure" label="井底流压(MPa)" min-width="160" sortable />
          <el-table-column prop="gasProduction" label="气产量(10⁴m³/d)" min-width="170" sortable />
          <el-table-column prop="waterProduction" label="累产水量(10⁴m³)" min-width="170" sortable />
        </el-table>
      </div>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.blasingame-wrap {
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

.chart-area {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  position: relative;
}

.chart-tabs {
  display: flex;
  height: 34px;
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
