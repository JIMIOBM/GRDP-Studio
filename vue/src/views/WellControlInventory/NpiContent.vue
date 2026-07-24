<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import fissureNpiTypeCurves from '@/constants/typeCurves/fissureNormalizedPressureInteral.json'
import npiTypeCurves from '@/constants/typeCurves/normalizedPressureInteral.json'
import fissureTransientTypeCurves from '@/constants/typeCurves/fissureTransient.json'
import transientTypeCurves from '@/constants/typeCurves/transient.json'
import { NODETYPE } from '@/constants/nodeType'
import { ElMessage } from 'element-plus'

const props = defineProps({
  node: Object,
  projectId: [Number, String],
  gasReservoirId: [Number, String],
  recalculating: Boolean,
  method: {
    type: String,
    default: 'npi'
  }
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
let chart = null

const raw = computed(() => props.node?.raw || {})
const isTransient = computed(() => props.method === 'transient')
const wellName = computed(() => props.node?.wellName || raw.value?.wellName || raw.value?.input?.wellName || raw.value?.input?.wellNames?.[0] || '')
const methodName = computed(() => isTransient.value ? 'Transient' : 'NPI')
const chartTabTitle = computed(() => `诊断曲线-${methodName.value}-${wellName.value || '当前井'}-分析结果`)
const nodeType = computed(() => props.node?.type ?? props.node?.treeNode?.nodeType ?? props.node?.raw?.nodeType)
const normalizeBoolean = value =>
    value === true || value === 1 || value === '1' || value === '是'
const isFractured = computed(() => (isTransient.value
    ? [NODETYPE.NodeType_FracturedVerticalWellTypicalCurveTransient, NODETYPE.NodeType_FracturedHorizontalWellTypicalCurveTransient]
    : [NODETYPE.NodeType_FracturedVerticalWellTypicalCurveNPI, NODETYPE.NodeType_FracturedHorizontalWellTypicalCurveNPI]
).includes(Number(nodeType.value)) || normalizeBoolean(raw.value?.analysis?.isFractured))
const activeTypeCurves = computed(() => {
  if (isTransient.value) return isFractured.value ? fissureTransientTypeCurves : transientTypeCurves
  return isFractured.value ? fissureNpiTypeCurves : npiTypeCurves
})
const input = computed(() => raw.value?.input || raw.value?.inputs || raw.value?.parameter || {})
const result = computed(() => ({
  ...(raw.value?.output || {}),
  ...(raw.value?.result || {}),
  ...(raw.value?.analysis || {})
}))
const chartItems = computed(() => raw.value?.chartItems || raw.value?.outputs?.[0]?.chartItems || [])
const records = computed(() => raw.value?.items || raw.value?.outputItems || raw.value?.records || [])

const getValue = (source, keys, fallback = '') => {
  for (const key of keys) {
    const value = source?.[key]
    if (value !== undefined && value !== null && value !== '') return value
  }
  return fallback
}
const inputValue = (keys, fallback = '') => getValue(input.value, keys, fallback)
const resultValue = (keys, fallback = '') => getValue(result.value, keys, fallback)
const methodLabel = (value, labels, fallback) => {
  if (value === undefined || value === null || value === '') return fallback
  if (typeof value === 'number' || /^\d+$/.test(String(value))) return labels[Number(value)] || String(value)
  return value
}

const gasType = computed(() => methodLabel(inputValue(['gasType'], 2), ['', '', '干气'], '干气'))
const fittingMode = computed(() => inputValue(['isSkipFitting'], false) ? '跳过拟合' : '自动拟合')
const waterGasRatioLimit = computed(() => inputValue(['minimumWaterGasRatio', 'waterGasRatioLimit'], -1))
const waterGasRatioEnabled = computed(() => Number(waterGasRatioLimit.value) > 0)
const fittingModeValue = ref('automatic')
const dataSizeValue = ref(300)
const initScanDataSizeValue = ref(10)
const fineScanDataSizeValue = ref(30)
const waterGasRatioLimitEnabled = ref(true)
const waterGasRatioLimitValue = ref(0.0602)

watch(input, (value) => {
  fittingModeValue.value = value?.isSkipFitting ? 'manual' : 'automatic'
  dataSizeValue.value = Number(value?.dataSize) || 300
  initScanDataSizeValue.value = Number(value?.initScanDataSize) || 10
  fineScanDataSizeValue.value = Number(value?.fineScanDataSize) || 30

  const limit = Number(value?.minimumWaterGasRatio ?? value?.waterGasRatioLimit)
  waterGasRatioLimitEnabled.value = Number.isFinite(limit) ? limit > 0 : true
  waterGasRatioLimitValue.value = limit > 0 ? limit : 0.0602
}, { immediate: true })

const isPositiveInteger = value => Number.isInteger(Number(value)) && Number(value) > 0

const handleRecalculate = () => {
  if (![dataSizeValue.value, initScanDataSizeValue.value, fineScanDataSizeValue.value].every(isPositiveInteger)) {
    ElMessage.warning('抽稀点数、粗扫点数和精扫点数必须为正整数')
    return
  }

  const minimumWaterGasRatio = Number(waterGasRatioLimitValue.value)
  if (waterGasRatioLimitEnabled.value && (!Number.isFinite(minimumWaterGasRatio) || minimumWaterGasRatio <= 0)) {
    ElMessage.warning('生产水气比上限必须大于 0')
    return
  }

  emit('recalculate', {
    wellName: wellName.value,
    isSkipFitting: fittingModeValue.value === 'manual',
    dataSize: Number(dataSizeValue.value),
    initScanDataSize: Number(initScanDataSizeValue.value),
    fineScanDataSize: Number(fineScanDataSizeValue.value),
    minimumWaterGasRatio: waterGasRatioLimitEnabled.value ? minimumWaterGasRatio : -1
  })
}

const inputGroups = computed(() => [
  {
    title: '气体性质',
    fields: [
      { label: '天然气类型', value: gasType.value, select: true },
      { label: '天然气比重(dless)', value: inputValue(['specificGravity']) },
      { label: 'H₂S摩尔百分含量(%)', value: inputValue(['hydrogenSulfide']) },
      { label: 'CO₂摩尔百分含量(%)', value: inputValue(['carbonDioxide']) },
      { label: 'N₂摩尔百分含量(%)', value: inputValue(['nitrogen']) }
    ]
  },
  {
    title: '计算方法',
    fields: [
      { label: '非烃气体修正方法', value: methodLabel(inputValue(['modificationMethod'], 0), ['Wichert-Aziz 修正方法', 'Carr-Kobayashi-Burrous 修正方法'], 'Wichert-Aziz 修正方法'), select: true },
      { label: '天然气偏差系数计算方法', value: methodLabel(inputValue(['deviationFactorMethod'], 0), ['Dranchuk-Abu-Kassem 方法', 'Dranchuk-Purvis-Robinson 方法', 'Hall-Yarborough 方法'], 'Dranchuk-Abu-Kassem 方法'), select: true },
      { label: '天然气粘度计算方法', value: methodLabel(inputValue(['viscosityMethod'], 0), ['Lee-Gonzalez-Eakin 方法', 'Carr-Kobayashi-Burrous 方法', 'Sutton 方法'], 'Lee-Gonzalez-Eakin 方法'), select: true }
    ]
  },
  {
    title: '控制参数',
    fields: [
      { label: '拟合方式', value: fittingMode.value, select: true },
      { label: '抽稀点数', value: inputValue(['dataSize'], 300) },
      { label: '粗扫数据点数量', value: inputValue(['initScanDataSize'], 10) },
      { label: '精扫数据点数量', value: inputValue(['fineScanDataSize'], 30) }
    ]
  },
  {
    title: '其它数据',
    fields: [
      { label: '原始地层压力(MPa)', value: inputValue(['originalFormationPressure']) },
      { label: '气井地层温度(℃)', value: inputValue(['formationTemperature']) },
      { label: '井筒半径(m)', value: inputValue(['wellboreRadius', 'wellRadius']) },
      { label: '动态地质储量(10⁸m³)', value: inputValue(['originalGasVolume']) },
      { label: '物质平衡方程类型', value: methodLabel(inputValue(['mbEquationType', 'materialBalanceType'], 0), ['封闭气藏', '定容气藏', '页岩气藏'], '封闭气藏'), select: true },
      { label: '储层厚度(m)', value: inputValue(['reservoirThickness']) },
      { label: '储层孔隙度(%)', value: inputValue(['reservoirPorosity']) },
      { label: '束缚水饱和度(%)', value: inputValue(['waterSaturation']) },
      { label: '地层水压缩系数(MPa⁻¹)', value: inputValue(['waterCompressionCoefficient']) },
      { label: '储层岩石压缩系数(MPa⁻¹)', value: inputValue(['rockCompressionCoefficient']) }
    ]
  }
])

const outputFields = computed(() => {
  const fields = [
    { label: '渗透率(mD)', value: resultValue(['permeability']) }
  ]

  if (isFractured.value) {
    fields.push({
      label: '裂缝半长(m)',
      value: resultValue(['effectiveFractureHalfLength', 'fractureHalfLength'])
    })
  }

  fields.push(
      { label: '表皮系数(dless)', value: resultValue(['skinFactor']) },
      { label: '动态地质储量(10⁸m³)', value: resultValue(['originalGasVolume']) },
      { label: '井控面积(km²)', value: resultValue(['wellControlArea']) },
      { label: '井控半径(m)', value: resultValue(['wellControlRadius']) }
  )
  return fields
})

const seriesConfig = computed(() => isTransient.value ? {
  qD: { name: 'qD-实际数据', color: '#7ee000', tableField: 'primaryValue' },
  regularizedProduction: { name: 'qD-实际数据', color: '#7ee000', tableField: 'primaryValue' },
  pDi: { name: '1/pDi-实际数据', color: '#00a020', tableField: 'integralValue' },
  regularizedPressureIntegralReciprocal: { name: '1/pDi-实际数据', color: '#00a020', tableField: 'integralValue' },
  pDid: { name: '1/pDid-实际数据', color: '#e60000', tableField: 'derivativeValue' },
  regularizedPressureIntegralDerivativeReciprocal: { name: '1/pDid-实际数据', color: '#e60000', tableField: 'derivativeValue' }
} : {
  pD: { name: 'pD-实际数据', color: '#7ee000', tableField: 'primaryValue' },
  regularizedPressure: { name: 'pD-实际数据', color: '#7ee000', tableField: 'primaryValue' },
  pDi: { name: 'pDi-实际数据', color: '#00a020', tableField: 'integralValue' },
  regularizedPressureIntegral: { name: 'pDi-实际数据', color: '#00a020', tableField: 'integralValue' },
  pDid: { name: 'pDid-实际数据', color: '#e60000', tableField: 'derivativeValue' },
  regularizedPressureIntegralDerivative: { name: 'pDid-实际数据', color: '#e60000', tableField: 'derivativeValue' }
})
const normalizedSeries = computed(() => {
  const series = new Map()
  chartItems.value.forEach(item => {
    const field = item.yAxisField || item.yField || item.field || ''
    const config = seriesConfig.value[field]
    if (!config) return
    const data = (item.data || item.items || []).map(point => {
      const x = Number(point.xValue ?? point.pseudotime ?? point.tcaDd ?? point.x)
      const y = Number(point.yValue ?? point[field] ?? point.y)
      return x > 0 && y > 0 ? [x, y] : null
    }).filter(Boolean)
    series.set(config.name, { ...config, data })
  })
  records.value.forEach(row => {
    const x = Number(row.pseudotime ?? row.tcaDd ?? row.xValue)
    if (!(x > 0)) return
    Object.entries(seriesConfig.value).forEach(([field, config]) => {
      const y = Number(row[field])
      if (!(y > 0)) return
      if (!series.has(config.name)) series.set(config.name, { ...config, data: [] })
      series.get(config.name).data.push([x, y])
    })
  })
  return [...series.values()]
})
const legendItems = computed(() => normalizedSeries.value.map(({ name, color }) => ({ name, color })))
const typeCurveColors = [
  '#333', '#5470c6', '#91cc75', '#fac858', '#ee6666', '#73c0de', '#3ba272', '#fc8452',
  '#9a60b4', '#ea7ccc', '#2ec7c9', '#b6a2de', '#5ab1ef', '#ffb980', '#d87a80', '#8d98b3',
  '#e5cf0d', '#97b552', '#95706d', '#dc69aa', '#07a2a4', '#9a7fd1', '#588dd5', '#f5994e',
  '#c05050', '#59678c', '#c9ab00', '#7eb00a', '#6f5553', '#c14089', '#a5e7f0'
]
const theoreticalSeries = computed(() => {
  const colors = ['#5470c6', '#91cc75', '#fac858', '#ee6666', '#73c0de', '#3ba272', '#fc8452', '#9a60b4', '#ea7ccc']
  const typeCurves = activeTypeCurves.value
  const familyIndexes = { qD: 0, pDi: 0, pDid: 0 }
  const transientColorOffsets = isFractured.value
    ? { qD: 2, pDi: 11, pDid: 20 }
    : { qD: 2, pDi: 12, pDid: 22 }
  return Object.entries(typeCurves)
      .filter(([key, values]) => !['pD', 'qD', 'pDi', 'pDid', 'tcaDd'].includes(key) && Array.isArray(values))
      .map(([name, values], index) => {
        const familyKey = name.startsWith('pDid') ? 'pDid' : name.startsWith('pDi') ? 'pDi' : 'qD'
        const family = familyKey === 'pDid'
          ? (isTransient.value ? '1/pDid-实际数据' : 'pDid-实际数据')
          : familyKey === 'pDi'
            ? (isTransient.value ? '1/pDi-实际数据' : 'pDi-实际数据')
            : (isTransient.value ? 'qD-实际数据' : 'pD-实际数据')
        const color = isTransient.value
          ? typeCurveColors[transientColorOffsets[familyKey] + familyIndexes[familyKey]++]
          : colors[index % colors.length]

        return {
          name,
          family,
          type: 'line',
          data: values.map((value, pointIndex) => [typeCurves.tcaDd[pointIndex], value]),
          showSymbol: false,
          silent: true,
          lineStyle: { width: 1, color, opacity: isTransient.value ? 1 : 0.8 }
        }
      })
})
const legendStyle = computed(() => legendPosition.value.x === null
    ? { top: '42px', right: '18px' }
    : { left: `${legendPosition.value.x}px`, top: `${legendPosition.value.y}px` })
const isLegendItemHidden = name => hiddenLegendNames.value.has(name)
const toggleLegendItem = name => {
  const next = new Set(hiddenLegendNames.value)
  next.has(name) ? next.delete(name) : next.add(name)
  hiddenLegendNames.value = next
  renderChartSoon()
}

const tableRows = computed(() => {
  if (records.value.length) {
    return records.value.map((row, index) => ({
      index: index + 1,
      pseudotime: row.pseudotime ?? row.tcaDd ?? row.xValue,
      primaryValue: isTransient.value ? (row.regularizedProduction ?? row.qD) : (row.regularizedPressure ?? row.pD),
      integralValue: isTransient.value ? (row.regularizedPressureIntegralReciprocal ?? row.pDi) : (row.regularizedPressureIntegral ?? row.pDi),
      derivativeValue: isTransient.value ? (row.regularizedPressureIntegralDerivativeReciprocal ?? row.pDid) : (row.regularizedPressureIntegralDerivative ?? row.pDid)
    }))
  }

  const rows = new Map()
  normalizedSeries.value.forEach(series => {
    const field = [...Object.values(seriesConfig.value)].find(config => config.name === series.name)?.tableField
    if (!field) return
    series.data.forEach(([x, y]) => {
      const key = String(x)
      if (!rows.has(key)) rows.set(key, { pseudotime: x })
      rows.get(key)[field] = y
    })
  })
  return [...rows.values()]
      .sort((a, b) => Number(a.pseudotime) - Number(b.pseudotime))
      .map((row, index) => ({ index: index + 1, ...row }))
})

const tableColumns = computed(() => isTransient.value ? [
  { prop: 'primaryValue', label: '重整产量(qD)', minWidth: 160 },
  { prop: 'integralValue', label: '规整化压力积分倒数(1/pDi)', minWidth: 210 },
  { prop: 'derivativeValue', label: '规整化压力积分导数倒数(1/pDid)', minWidth: 250 }
] : [
  { prop: 'primaryValue', label: '重整压力(pD)', minWidth: 160 },
  { prop: 'integralValue', label: '规整化压力积分(pDi)', minWidth: 190 },
  { prop: 'derivativeValue', label: '规整化压力积分导数(pDid)', minWidth: 220 }
])

function renderChart() {
  if (!chart) return
  const visibleTheoreticalSeries = theoreticalSeries.value.filter(item => !hiddenLegendNames.value.has(item.family))
  const actualSeries = normalizedSeries.value
      .filter(item => !hiddenLegendNames.value.has(item.name))
      .map(item => ({
        name: item.name,
        type: 'scatter',
        data: item.data,
        symbolSize: 5,
        itemStyle: { color: item.color }
      }))
  const series = [...visibleTheoreticalSeries, ...actualSeries]
  chart.setOption({
    animation: false,
    title: { text: isTransient.value ? 'Transient' : 'Normalized Pressure Integral', left: 'center', top: 8, textStyle: { fontSize: 14, fontWeight: 600 } },
    tooltip: { trigger: 'axis', axisPointer: { type: 'cross', lineStyle: { color: '#de39cd' } } },
    legend: { show: false },
    grid: { left: 72, right: 52, top: 60, bottom: 58 },
    xAxis: {
      type: 'log', min: isTransient.value ? 0.1 : 0.0001, max: isTransient.value ? 1000000 : 100,
      name: isTransient.value ? '{main|t}{sub|caDd}' : 'tcaDA', nameLocation: 'middle', nameGap: 34,
      nameTextStyle: isTransient.value ? { rich: { main: { fontSize: 14 }, sub: { fontSize: 10, padding: [7, 0, 0, 0] } } } : undefined,
      splitLine: { show: true, lineStyle: { color: '#dfe7f2' } },
      minorSplitLine: { show: true, lineStyle: { color: '#f0f4fa' } }
    },
    yAxis: {
      type: 'log', min: isTransient.value ? 0.001 : 0.01, max: isTransient.value ? 100 : 1000, name: isTransient.value ? 'qD,1/pDi,1/pDid' : 'pD,pDi,pDid', nameLocation: 'middle', nameGap: 48,
      splitLine: { show: true, lineStyle: { color: '#dfe7f2' } },
      minorSplitLine: { show: true, lineStyle: { color: '#f0f4fa' } }
    },
    series
  }, true)
}

const renderChartSoon = () => nextTick(() => requestAnimationFrame(() => {
  chart?.resize()
  renderChart()
}))
const toggleParamsPanel = () => {
  paramsCollapsed.value = !paramsCollapsed.value
  renderChartSoon()
}
function onParamsPanelResize(event) {
  if (!resizingParamsPanel.value) return
  const left = paramsPanelEl.value?.getBoundingClientRect().left || 0
  paramsPanelWidth.value = Math.max(238, Math.min(520, event.clientX - left))
  chart?.resize()
}
function stopParamsPanelResize() {
  resizingParamsPanel.value = false
  document.body.style.cursor = ''
  document.body.style.userSelect = ''
  window.removeEventListener('mousemove', onParamsPanelResize)
  window.removeEventListener('mouseup', stopParamsPanelResize)
}
function startParamsPanelResize(event) {
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
  legendPosition.value = {
    x: Math.max(0, Math.min(rect.width - 80, event.clientX - rect.left - legendDragOffset.value.x)),
    y: Math.max(0, Math.min(rect.height - 30, event.clientY - rect.top - legendDragOffset.value.y))
  }
}
function stopLegendDrag() {
  draggingLegend.value = false
  document.body.style.cursor = ''
  document.body.style.userSelect = ''
  window.removeEventListener('mousemove', onLegendDrag)
  window.removeEventListener('mouseup', stopLegendDrag)
}
function startLegendDrag(event) {
  const legendRect = event.currentTarget.getBoundingClientRect()
  const areaRect = chartAreaEl.value.getBoundingClientRect()
  legendPosition.value = {
    x: legendPosition.value.x ?? legendRect.left - areaRect.left,
    y: legendPosition.value.y ?? legendRect.top - areaRect.top
  }
  legendDragOffset.value = { x: event.clientX - legendRect.left, y: event.clientY - legendRect.top }
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
  stopParamsPanelResize()
  stopLegendDrag()
  chart?.dispose()
})
</script>

<template>
  <div class="npi-wrap">
    <aside ref="paramsPanelEl" class="params-panel" :class="{ collapsed: paramsCollapsed }"
           :style="{ width: paramsCollapsed ? '22px' : `${paramsPanelWidth}px`, minWidth: paramsCollapsed ? '22px' : `${paramsPanelWidth}px` }">
      <div v-if="paramsCollapsed" class="panel-collapsed-tab" @click="toggleParamsPanel">参数设置</div>
      <template v-else>
        <div class="panel-head">
          <span>参数设置</span>
          <button class="panel-toggle" type="button" title="收起参数设置" @click="toggleParamsPanel">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor">
              <path d="M16,12V4H17V2H7V4H8V12L6,14V16H11.2V22H12.8V16H18V14L16,12Z" />
            </svg>
          </button>
        </div>
        <div v-if="activePanelTab === 'input'" class="panel-body">
          <template v-for="group in inputGroups" :key="group.title">
            <div class="sec-label">{{ group.title }}</div>
            <div class="field-grid">
              <div v-for="field in group.fields" :key="field.label" class="field">
                <label>{{ field.label }}</label>
                <el-select v-if="field.select" size="small" disabled :model-value="field.value" style="width:100%">
                  <el-option :label="field.value" :value="field.value" />
                </el-select>
                <el-input v-else size="small" readonly :model-value="field.value" />
              </div>
              <div v-if="group.title === '控制参数'" class="field">
                <div class="wgr-label-row"><span>生产水气比上限(m³/10⁴m³)</span><el-switch :model-value="waterGasRatioEnabled" disabled size="small" /></div>
                <el-input size="small" readonly :disabled="!waterGasRatioEnabled" :model-value="waterGasRatioLimit" />
              </div>
            </div>
          </template>
          <div class="sec-label">计算条件</div>
          <div class="condition-panel">
            <div class="field">
              <label>拟合方式</label>
              <el-select v-model="fittingModeValue" size="small" style="width:100%">
                <el-option label="自动拟合" value="automatic" />
                <el-option label="手动拟合" value="manual" />
              </el-select>
            </div>
            <div class="field">
              <label>抽稀点数</label>
              <el-input-number v-model="dataSizeValue" :min="1" :precision="0" :controls="false" size="small" />
            </div>
            <div class="field">
              <label>粗扫数据点数量</label>
              <el-input-number v-model="initScanDataSizeValue" :min="1" :precision="0" :controls="false" size="small" />
            </div>
            <div class="field">
              <label>精扫数据点数量</label>
              <el-input-number v-model="fineScanDataSizeValue" :min="1" :precision="0" :controls="false" size="small" />
            </div>
            <div class="field">
              <div class="wgr-label-row">
                <span :class="{ 'condition-muted': !waterGasRatioLimitEnabled }">生产水气比上限(m³/10⁴m³)</span>
                <el-switch v-model="waterGasRatioLimitEnabled" size="small" />
              </div>
              <el-input-number
                  v-model="waterGasRatioLimitValue"
                  :min="0"
                  :controls="false"
                  :disabled="!waterGasRatioLimitEnabled"
                  size="small"
              />
            </div>
            <el-button
                size="small"
                type="primary"
                :loading="recalculating"
                :disabled="recalculating"
                @click="handleRecalculate"
            >
              重新计算
            </el-button>
          </div>
          <div class="sec-label">生产数据</div>
          <div class="btn-row"><el-button size="small">模板下载</el-button><el-button size="small">导入</el-button></div>
        </div>
        <div v-else class="panel-body">
          <div class="sec-label">输出结果</div>
          <div class="field-grid">
            <div v-for="field in outputFields" :key="field.label" class="field">
              <label>{{ field.label }}</label>
              <el-input size="small" readonly :model-value="field.value" />
            </div>
          </div>
        </div>
        <div class="param-tabs">
          <div class="param-tab" :class="{ active: activePanelTab === 'input' }" @click="activePanelTab = 'input'">输入</div>
          <div class="param-tab" :class="{ active: activePanelTab === 'output' }" @click="activePanelTab = 'output'">输出</div>
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
      <div v-show="activeChartTab === 'chart'" ref="chartEl" class="chart-instance"></div>
      <div v-if="activeChartTab === 'chart' && legendItems.length" class="floating-chart-legend" :style="legendStyle" @mousedown="startLegendDrag">
        <div v-for="item in legendItems" :key="item.name" class="floating-legend-item" :class="{ hidden: isLegendItemHidden(item.name) }"
             @mousedown.stop @click.stop="toggleLegendItem(item.name)">
          <span class="legend-dot" :style="{ backgroundColor: isLegendItemHidden(item.name) ? 'transparent' : item.color, borderColor: item.color }"></span>
          <span>{{ item.name }}</span>
        </div>
      </div>
      <div v-if="activeChartTab === 'table'" class="data-list-panel">
        <el-table :data="tableRows" size="small" height="100%" border>
          <el-table-column prop="index" label="序号" width="70" />
          <el-table-column prop="pseudotime" label="物质平衡拟时间" min-width="160" />
          <el-table-column
              v-for="column in tableColumns"
              :key="column.prop"
              :prop="column.prop"
              :label="column.label"
              :min-width="column.minWidth"
          />
        </el-table>
      </div>
      <div class="bottom-chart-tabs">
        <button type="button" class="bottom-chart-tab" :class="{ active: activeChartTab === 'table' }" @click="activeChartTab = 'table'">数据列表</button>
        <button type="button" class="bottom-chart-tab" :class="{ active: activeChartTab === 'chart' }" :title="chartTabTitle" @click="activeChartTab = 'chart'">结果分析图</button>
      </div>
    </main>
  </div>
</template>

<style lang="scss" scoped>
.npi-wrap { display:flex; height:100%; min-height:0; background:#fff; overflow:hidden; }
.params-panel { min-width:238px; border-right:1px solid #e0e0e0; display:flex; flex-direction:column; position:relative; overflow:hidden; }
.params-panel.collapsed { border-right:0; }
.panel-head { height:34px; padding:0 12px; border-bottom:1px solid #eee; display:flex; align-items:center; justify-content:space-between; font-size:13px; }
.panel-toggle { width:22px; height:22px; border:0; background:transparent; color:#777; cursor:pointer; }
.panel-collapsed-tab { width:22px; height:76px; writing-mode:vertical-rl; display:flex; align-items:center; justify-content:center; border:1px solid #ddd; border-left:0; font-size:13px; cursor:pointer; }
.panel-body { flex:1; overflow:auto; padding:4px 12px 14px; }
.sec-label { margin:10px 0 7px; color:#333; font-size:13px; font-weight:500; }
.field-grid { display:grid; grid-template-columns:repeat(auto-fit,minmax(190px,1fr)); gap:0 24px; }
.field { margin-bottom:9px; }
.field label,.wgr-label-row span { display:block; margin-bottom:3px; color:#555; font-size:12px; }
.wgr-label-row { display:flex; align-items:center; justify-content:space-between; }
.condition-panel { padding:9px; border:1px solid #e4e7ed; background:#fafafa; }
.condition-panel :deep(.el-input-number) { width:100%; }
.condition-muted { color:#aaa !important; }
.btn-row { display:flex; gap:8px; }
.param-tabs { display:flex; height:30px; border-top:1px solid #ddd; }
.param-tab { flex:1; display:flex; align-items:center; justify-content:center; border-right:1px solid #ddd; font-size:13px; cursor:pointer; }
.param-tab.active { background:#f4d000; color:#1a1a1a; font-weight:600; }
.params-resizer { position:absolute; top:0; right:-3px; width:6px; height:100%; cursor:col-resize; z-index:4; }
.chart-area { flex:1; min-width:0; min-height:0; display:flex; flex-direction:column; position:relative; overflow:hidden; }
.dynamic-result-tabs { height:34px; flex-shrink:0; display:flex; align-items:center; border-bottom:1px solid #e4e7ed; background:#fafafa; overflow-x:auto; overflow-y:hidden; }
.dynamic-result-tab { height:34px; max-width:320px; padding:0 12px; border:0; border-right:1px solid #e4e7ed; border-bottom:2px solid #409eff; background:#fff; color:#409eff; font-size:14px; font-weight:600; display:flex; align-items:center; cursor:default; white-space:nowrap; }
.dynamic-result-tab-text { min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.chart-instance,.data-list-panel { flex:1; min-height:0; width:100%; }
.bottom-chart-tabs { display:flex; align-items:flex-end; height:30px; flex-shrink:0; background:#fff; border-top:1px solid #e4e7ed; }
.bottom-chart-tab { min-width:88px; height:30px; border:0; border-right:1px solid #e4e7ed; border-top:2px solid transparent; background:#fff; color:#333; font-size:13px; cursor:pointer; white-space:nowrap; }
.bottom-chart-tab:hover { color:#409eff; }
.bottom-chart-tab.active { color:#409eff; border-top-color:#409eff; font-weight:600; }
.floating-chart-legend { position:absolute; z-index:6; display:flex; flex-direction:column; gap:4px; padding:6px 10px; border:1px solid #e4e7ed; background:rgba(255,255,255,.92); font-size:12px; cursor:move; user-select:none; }
.floating-legend-item { display:flex; align-items:center; gap:6px; line-height:18px; cursor:pointer; white-space:nowrap; }
.floating-legend-item.hidden { color:#999; opacity:.55; }
.legend-dot { width:10px; height:10px; border-radius:50%; border:1px solid transparent; }
</style>
