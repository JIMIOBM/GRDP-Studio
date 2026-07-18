﻿<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { dynamicBalanceApi } from '@/api/docker'

const props = defineProps({
  node: Object,
  projectId: [Number, String],
  gasReservoirId: [Number, String]
})

const loading = ref(false)
const chartEl = ref(null)
const chartAreaEl = ref(null)
const paramsPanelEl = ref(null)
const resultData = ref(null)
const noData = ref(false)
const activeTab = ref('input')
const activeChartTab = ref('chart')
const tableLoading = ref(false)
const tableOutputItems = ref([])
const panelCollapsed = ref(false)
const legendPosition = ref({ x: null, y: null })
const draggingLegend = ref(false)
const legendDragOffset = ref({ x: 0, y: 0 })
const paramsPanelWidth = ref(238)
const resizingParamsPanel = ref(false)
const equationGraphicPosition = ref(null)

let chart = null

const currentWellName = computed(() =>
    props.node?.wellName ||
    props.node?.label ||
    ''
)

const resultId = computed(() =>
    props.node?.resultId ||
    props.node?.raw?.nodeId ||
    props.node?.nodeId ||
    props.node?.id ||
    ''
)

const output = computed(() => {
  if (!resultData.value?.result) return {}
  return resultData.value.result
})

const reliability = computed(() => {
  return output.value.reliablity ||
      output.value.reliability ||
      output.value.reliabilityDescription ||
      ''
})

const inputParams = computed(() => {
  return resultData.value?.input || {}
})

const fieldLabels = {
  gasType: '天然气类型',
  relativeDensity: '天然气比重(dless)',
  h2sContent: 'H₂S摩尔百分含量(%)',
  co2Content: 'CO₂摩尔百分含量(%)',
  nitrogenContent: 'N₂摩尔百分含量(%)',
  nonHydrocarbonCorrectionMethod: '非烃气体修正方法',
  zFactorCalculationMethod: '天然气偏差系数计算方法',
  viscosityCalculationMethod: '天然气粘度计算方法',
  initialReservoirPressure: '原始地层压力(MPa)',
  reservoirTemperature: '地层温度(℃)',
  equationType: '物质平衡方程类型',
  unstableFlowTime: '不稳定流动段时间(d)',
  samplingPoints: '抽稀点数',
  waterGasRatioLimit: '生产水气比上限(m³/10⁴m³)',
  gasReservoirVolume: '气藏地质储量(10⁸m³)',
  connateWaterSaturation: '束缚水饱和度(%)',
  rockCompressibility: '储层岩石压缩系数(MPa⁻¹)',
  waterCompressibility: '地层水压缩系数(MPa⁻¹)'
}

const gasTypeOptions = [
  { label: '干气', value: '干气' },
  { label: '湿气', value: '湿气' },
  { label: '凝析气', value: '凝析气' }
]

const nonHydrocarbonCorrectionOptions = [
  { label: 'Wichert-Aziz 修正方法', value: 'Wichert-Aziz修正方法' },
  { label: '其他方法', value: '其他方法' }
]

const zFactorCalculationOptions = [
  { label: 'Dranchuk-Abu-Kassem 方法', value: 'Dranchuk-Abu-Kassem方法' },
  { label: '其他方法', value: '其他方法' }
]

const viscosityCalculationOptions = [
  { label: 'Lee-Gonzalez-Eakin 方法', value: 'Lee-Gonzalez-Eakin方法' },
  { label: 'Carr-Kobayashi-Burrows 方法', value: 'Carr-Kobayashi-Burrows方法' },
  { label: 'Sutton 方法', value: 'Sutton方法' }
]

const equationTypeOptions = [
  { label: '封闭气藏', value: '封闭气藏' },
  { label: '水侵气藏', value: '水侵气藏' }
]

const formatNumber = (value) => {
  const num = parseFloat(value)
  if (isNaN(num)) return String(value)

  if (/[eE]/.test(String(value))) {
    const fixed = num.toFixed(10)//1
    return fixed.replace(/\.?0+$/, '')
  }

  return String(value)
}

const getInputValue = (keys, fallback = '') => {
  const keyArray = Array.isArray(keys) ? keys : [keys]
  for (const key of keyArray) {
    const value = inputParams.value?.[key]
    if (value !== undefined && value !== null && value !== '') {
      return formatNumber(value)
    }
  }
  return formatNumber(fallback)
}

const gasProperties = computed(() => [
  { key: 'gasType', label: fieldLabels.gasType, value: getInputValue('gasType'), type: 'select', options: gasTypeOptions },
  { key: 'specificGravity', label: fieldLabels.relativeDensity, value: getInputValue(['specificGravity', 'relativeDensity']), type: 'number' },
  { key: 'hydrogenSulfide', label: fieldLabels.h2sContent, value: getInputValue(['hydrogenSulfide', 'h2sContent']), type: 'number' },
  { key: 'carbonDioxide', label: fieldLabels.co2Content, value: getInputValue(['carbonDioxide', 'co2Content']), type: 'number' },
  { key: 'nitrogen', label: fieldLabels.nitrogenContent, value: getInputValue(['nitrogen', 'nitrogenContent']), type: 'number' }
])

const getMethodValue = (key, legacyKey, options) => {
  const value = inputParams.value?.[key] ?? inputParams.value?.[legacyKey]
  if (value === undefined || value === null || value === '') return ''
  if (typeof value === 'number') return options[value]?.value || ''
  return value
}

const calculationMethods = computed(() => [
  { key: 'modificationMethod', label: fieldLabels.nonHydrocarbonCorrectionMethod, value: getMethodValue('modificationMethod', 'nonHydrocarbonCorrectionMethod', nonHydrocarbonCorrectionOptions), type: 'select', options: nonHydrocarbonCorrectionOptions },
  { key: 'deviationFactorMethod', label: fieldLabels.zFactorCalculationMethod, value: getMethodValue('deviationFactorMethod', 'zFactorCalculationMethod', zFactorCalculationOptions), type: 'select', options: zFactorCalculationOptions },
  { key: 'viscosityMethod', label: fieldLabels.viscosityCalculationMethod, value: getMethodValue('viscosityMethod', 'viscosityCalculationMethod', viscosityCalculationOptions), type: 'select', options: viscosityCalculationOptions }
])

const equationTypeValue = computed(() => {
  const value = inputParams.value?.mbEquationType ?? inputParams.value?.equationType
  if (value === 0) return '封闭气藏'
  if (value === 1) return '水侵气藏'
  return value || ''
})

const otherData = computed(() => [
  { key: 'originalPressure', label: fieldLabels.initialReservoirPressure, value: getInputValue(['originalPressure', 'originalFormationPressure', 'initialReservoirPressure']), type: 'number' },
  { key: 'temperature', label: fieldLabels.reservoirTemperature, value: getInputValue(['temperature', 'formationTemperature', 'reservoirTemperature']), type: 'number' },
  { key: 'mbEquationType', label: fieldLabels.equationType, value: equationTypeValue.value, type: 'select', options: equationTypeOptions },
  { key: 'unstableFlowPeriodLength', label: fieldLabels.unstableFlowTime, value: getInputValue(['unstableFlowPeriodLength', 'unstableFlowTime']), type: 'number' },
  { key: 'dataSize', label: fieldLabels.samplingPoints, value: getInputValue(['dataSize', 'samplingPoints']), type: 'number', hasSwitch: true, switchValue: Number(inputParams.value?.dataSize) > 0 },
  { key: 'waterGasRatioLimit', label: fieldLabels.waterGasRatioLimit, value: getInputValue('waterGasRatioLimit'), type: 'number', hasSwitch: true, switchValue: Number(inputParams.value?.waterGasRatioLimit) >= 0 },
  { key: 'reservoirOriginalGasVolume', label: fieldLabels.gasReservoirVolume, value: getInputValue(['reservoirOriginalGasVolume', 'gasReservoirVolume']), type: 'number' },
  { key: 'waterSaturation', label: fieldLabels.connateWaterSaturation, value: getInputValue(['waterSaturation', 'connateWaterSaturation']), type: 'number' },
  { key: 'rockCompressionCoefficient', label: fieldLabels.rockCompressibility, value: getInputValue(['rockCompressionCoefficient', 'rockCompressibility']), type: 'number' },
  { key: 'waterCompressionCoefficient', label: fieldLabels.waterCompressibility, value: getInputValue(['waterCompressionCoefficient', 'waterCompressibility']), type: 'number' }
])

const findChartItem = (fields) => {
  const fieldSet = new Set(fields)
  return resultData.value?.chartItems?.find(item => fieldSet.has(item.yAxisField))
}

const getPointYValue = (item) =>
  item?.yValue ??
  item?.pressure ??
  item?.normalizedPressure ??
  item?.normalisedPressure ??
  item?.regularizedPressure

const mapChartPoint = (item) => {
  const x = Number(item?.xValue ?? item?.pseudotime)
  const y = Number(getPointYValue(item))
  return [x, y]
}

const chartPoints = computed(() => {
  const chartItem = findChartItem(['pressure', 'normalizedPressure', 'normalisedPressure', 'regularizedPressure'])
  if (chartItem?.data?.length) {
    return chartItem.data
        .filter(item => item.isDeleted !== true)
        .map(mapChartPoint)
        .filter(([x, y]) => Number.isFinite(x) && Number.isFinite(y))
  }
  if (!Array.isArray(resultData.value?.data)) return []
  return resultData.value.data
      .filter(item => item.isDeleted !== true)
      .map(mapChartPoint)
      .filter(([x, y]) => x > 0 && y > 0)
})

const allPoints = computed(() => {
  const chartItem = findChartItem(['pressure', 'normalizedPressure', 'normalisedPressure', 'regularizedPressure'])
  if (chartItem?.data?.length) {
    return chartItem.data
        .filter(item => item.isDeleted === true)
        .map(mapChartPoint)
        .filter(([x, y]) => Number.isFinite(x) && Number.isFinite(y))
  }
  if (!Array.isArray(resultData.value?.data)) return []
  return resultData.value.data
      .filter(item => item.isDeleted === true)
      .map(mapChartPoint)
      .filter(([x, y]) => x > 0 && y > 0)
})

const regression = computed(() => {
  const result = output.value
  const slope = Number(result.gradient ?? result.slope)
  const intercept = Number(result.intercept)
  const r2 = Number(result.rsquared)
  return { slope, intercept, r2 }
})

const regressionPoints = computed(() => {
  const chartItem = findChartItem(['linearRegressionPressure', 'linearRegressionNormalizedPressure', 'linearRegressionRegularizedPressure'])
  if (chartItem?.data?.length) {
    return chartItem.data
        .map(mapChartPoint)
        .filter(([x, y]) => Number.isFinite(x) && Number.isFinite(y))
  }
  const { slope, intercept } = regression.value
  const xs = chartPoints.value.map(([x]) => x)
  if (!xs.length || !Number.isFinite(slope) || !Number.isFinite(intercept)) return []
  const minX = Math.min(...xs)
  const maxX = Math.max(...xs)
  return [[minX, slope * minX + intercept], [maxX, slope * maxX + intercept]]
})

const legendStyle = computed(() => {
  if (legendPosition.value.x === null || legendPosition.value.y === null) {
    return { top: '42px', right: '22px' }
  }
  return {
    left: `${legendPosition.value.x}px`,
    top: `${legendPosition.value.y}px`
  }
})

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

const getEquationGraphicPosition = () => {
  if (equationGraphicPosition.value) return equationGraphicPosition.value
  const width = chart?.getWidth?.() || 900
  const height = chart?.getHeight?.() || 520
  return [Math.max(width - 330, 80), Math.max(height - 130, 60)]
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

  chart?.resize()
}

function startParamsPanelResize(event) {
  if (panelCollapsed.value) return

  event.preventDefault()

  resizingParamsPanel.value = true
  document.body.style.cursor = 'col-resize'
  document.body.style.userSelect = 'none'

  window.addEventListener('mousemove', onParamsPanelResize)
  window.addEventListener('mouseup', stopParamsPanelResize)
}

const renderChart = () => {
  if (!chart) return

  const { slope, intercept, r2 } = regression.value

  const series = [
    {
      name: '背景数据点',
      type: 'scatter',
      data: allPoints.value,
      symbolSize: 6,
      itemStyle: {
        color: '#ccc',
        opacity: 0.6
      }
    },
    {
      name: '数据点(MPa/(10⁴m³/d))',
      type: 'scatter',
      data: chartPoints.value,
      symbolSize: 10,
      itemStyle: {
        color: '#0037b5',
        opacity: 0.85
      }
    },
    {
      name: '回归线(MPa/(10⁴m³/d))',
      type: 'line',
      data: regressionPoints.value,
      symbol: 'none',
      lineStyle: { color: '#333', width: 2.5 },
      tooltip: { show: false }
    }
  ]

  chart.setOption({
    animation: false,
    title: {
      text: '动态物质平衡',
      left: 'center',
      top: 8,
      textStyle: { fontSize: 16, fontWeight: 600, color: '#333' }
    },
    tooltip: {
      trigger: 'item',
      axisPointer: {
        type: 'cross',
        crossStyle: { color: '#999', type: 'dashed', width: 1 },
        label: { backgroundColor: '#999' }
      },
      formatter: p => `${p.marker}${p.seriesName}: ${Number(p.value?.[1] ?? p.value).toFixed(4)}`
    },
    legend: { show: false },
    grid: {
      left: 100,
      right: 80,
      top: 80,
      bottom: 80
    },
    xAxis: {
      type: 'value',
      name: 'tca(d)',
      scale: true,
      nameLocation: 'middle',
      nameGap: 35,
      minorTick: { show: true },
      minorSplitLine: { show: true, lineStyle: { color: '#eee' } },
      splitLine: { lineStyle: { color: '#ddd' } },
      axisLabel: { fontSize: 11 }
    },
    yAxis: {
      type: 'value',
      name: '(Pi - Pwf)/qsc(MPa/(10⁴m³/d))',
      scale: true,
      nameLocation: 'middle',
      nameGap: 55,
      minorTick: { show: true },
      minorSplitLine: { show: true, lineStyle: { color: '#eee' } },
      splitLine: { lineStyle: { color: '#ddd' } },
      axisLabel: { fontSize: 11 }
    },
    series,
    graphic: [
      {
        id: 'regression-equation',
        type: 'group',
        z: 1000,
        zlevel: 10,
        draggable: true,
        cursor: 'move',
        position: getEquationGraphicPosition(),
        ondrag: function () {
          equationGraphicPosition.value = [
            Number.isFinite(this.x) ? this.x : this.position?.[0],
            Number.isFinite(this.y) ? this.y : this.position?.[1]
          ]
        },
        children: [
          {
            type: 'rect',
            z: 1000,
            zlevel: 10,
            shape: { x: 0, y: 0, width: 260, height: 58, r: 3 },
            style: {
              fill: 'rgba(255,255,255,0.9)',
              stroke: '#dcdfe6',
              lineWidth: 1
            }
          },
          {
            type: 'text',
            z: 1001,
            zlevel: 10,
            left: 10,
            top: 8,
            style: {
              text: Number.isFinite(slope) && Number.isFinite(intercept)
                  ? `Y = ${slope.toExponential(4)}*X + ${intercept.toExponential(4)}`
                  : '',
              fill: '#333',
              font: '14px Arial',
              lineHeight: 22
            }
          },
          {
            type: 'text',
            z: 1001,
            zlevel: 10,
            left: 10,
            top: 30,
            style: {
              text: Number.isFinite(r2) ? `R² = ${r2.toFixed(4)}` : '',
              fill: '#333',
              font: '14px Arial',
              lineHeight: 22
            }
          }
        ]
      }
    ]
  }, true)
}

const handleResize = () => chart?.resize()

const ensureChart = () => {
  if (!chart && chartEl.value) chart = echarts.init(chartEl.value)
}

const fetchData = async () => {
  const wellName = currentWellName.value
  const currentResultId = resultId.value

  if (props.node?.loading) {
    loading.value = true
    resultData.value = null
    noData.value = false
    return
  }

  if (!currentResultId || !props.projectId || !props.gasReservoirId) {
    noData.value = true
    return
  }

  loading.value = true
  resultData.value = null
  noData.value = false

  try {
    const nodePayload = props.node?.raw
    if (nodePayload?.result && Array.isArray(nodePayload?.data)) {
      resultData.value = nodePayload
    } else {
      const res = await dynamicBalanceApi.getResult(
          props.projectId,
          props.gasReservoirId,
          currentResultId,
          { silentError: true }
      )
      resultData.value = res.data?.result ? res.data : (res.data?.data ?? res.data)
    }

    noData.value = !Array.isArray(resultData.value?.data) || resultData.value.data.length === 0

  } catch (e) {
    console.error(`[DynamicBalanceContent] Failed to fetch data for ${wellName}`, e)
    noData.value = true
  } finally {
    await nextTick()
    ensureChart()
    renderChart()
    loading.value = false
  }
}

const unwrapResponseData = (response) => response?.data?.data ?? response?.data ?? response

const extractTableDataFromResult = (payload) => {
  const chartItems = payload?.chartItems
  if (Array.isArray(chartItems) && chartItems.length > 0) {
    const pressureItem = chartItems.find(item => item.yAxisField === 'pressure' || item.yAxisField === 'normalizedPressure')
    const normalizedPressureItem = chartItems.find(item => item.yAxisField === 'normalizedPressure' || item.yAxisField === 'normalisedPressure')
    const cumulativeGasProductionItem = chartItems.find(item =>
      item.yAxisField === 'cumulativeGasProduction' ||
      item.yAxisField === 'cumulativeProduction' ||
      item.name?.includes('累产') ||
      item.title?.includes('累产')
    )

    if (pressureItem?.data?.length) {
      return pressureItem.data.filter(item => item && typeof item === 'object').map((item, index) => ({
        index: index + 1,
        cumulativeGasProduction:
          item.cumulativeGasProduction ??
          cumulativeGasProductionItem?.data?.[index]?.yValue ??
          cumulativeGasProductionItem?.data?.[index]?.value ??
          payload?.data?.[index]?.cumulativeGasProduction ??
          '',
        normalizedPressure: normalizedPressureItem?.data?.[index]?.yValue ?? item.normalizedPressure ?? item.normalisedPressure ?? item.regularizedPressure ?? item.yValue ?? '',
        isParticipateAnalysis: item.isDeleted !== true,
        pseudotime: item.pseudotime ?? item.xValue ?? ''
      })).filter(item => item.pseudotime !== '' || item.normalizedPressure !== '')
    }
  }

  const data = payload?.data
  if (!Array.isArray(data)) return []

  return data.filter(item => item && typeof item === 'object')
      .map((item, index) => ({
        index: index + 1,
        cumulativeGasProduction: item.cumulativeGasProduction ?? '',
        normalizedPressure: item.normalizedPressure ?? item.normalisedPressure ?? item.regularizedPressure ?? item.yValue ?? '',
        isParticipateAnalysis: item.isDeleted !== true,
        pseudotime: item.pseudotime ?? item.xValue ?? ''
      }))
      .filter(item => item.pseudotime !== '' || item.normalizedPressure !== '')
}

const tableRows = computed(() => {
  if (tableOutputItems.value.length) {
    return tableOutputItems.value.map((item, index) => ({
      index: index + 1,
      cumulativeGasProduction: item.cumulativeGasProduction ?? '',
      normalizedPressure: item.normalizedPressure ?? '',
      isParticipateAnalysis: item.isDeleted !== true,
      pseudotime: item.pseudotime ?? ''
    }))
  }

  return extractTableDataFromResult(resultData.value).map((item, index) => ({
    ...item,
    index: index + 1
  }))
})

async function fetchTableData() {
  const currentResultId = resultId.value
  if (!currentResultId || !props.projectId || !props.gasReservoirId) return

  tableLoading.value = true
  try {
    const response = await dynamicBalanceApi.getResult(props.projectId, props.gasReservoirId, currentResultId, { silentError: true })
    const result = unwrapResponseData(response)
    tableOutputItems.value = extractTableDataFromResult(result)
  } catch (error) {
    console.error('动态平衡数据列表加载失败', error)
  } finally {
    tableLoading.value = false
  }
}

function showTableTab() {
  activeChartTab.value = 'table'
  fetchTableData()
}

watch(() => props.node, () => {
  tableOutputItems.value = []
  if (activeChartTab.value === 'table') fetchTableData()
}, { deep: true })
watch(activeChartTab, () => {
  if (activeChartTab.value === 'chart') {
    nextTick(() => {
      ensureChart()
      renderChart()
    })
  }
})

watch(
    () => [props.node?.wellName, resultId.value, props.node?.raw],
    fetchData,
    { immediate: true }
)

onMounted(() => {
  ensureChart()
  renderChart()
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  chart?.dispose()
})
</script>

<template>
  <div v-loading="loading" class="db-wrap">
    <div
        ref="paramsPanelEl"
        class="params-panel"
        :class="{ collapsed: panelCollapsed, resizing: resizingParamsPanel }"
        :style="{ width: panelCollapsed ? '22px' : `${paramsPanelWidth}px`, minWidth: panelCollapsed ? '22px' : `${paramsPanelWidth}px` }"
    >
      <div v-if="panelCollapsed" class="panel-collapsed-tab" @click="panelCollapsed = false">
        参数设置
      </div>

      <div v-show="!panelCollapsed" class="panel-header">
        <span>参数设置</span>
        <button class="panel-toggle" type="button" title="收起参数设置" @click="panelCollapsed = true">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="#777">
            <path d="M16,12V4H17V2H7V4H8V12L6,14V16H11.2V22H12.8V16H18V14L16,12Z"/>
          </svg>
        </button>
      </div>

      <div v-show="!panelCollapsed" class="panel-body">
        <div v-if="activeTab === 'input'">
          <div class="sec-label">气体性质</div>
          <div class="field-grid">
            <div v-for="item in gasProperties" :key="item.key" class="field">
              <label>{{ item.label }}</label>
              <el-select v-if="item.type === 'select'" size="small" :model-value="item.value" style="width:100%">
                <el-option v-for="opt in item.options" :key="opt.value" :label="opt.label" :value="opt.value" />
              </el-select>
              <el-input v-else size="small" readonly :model-value="item.value" />
            </div>
          </div>

          <div class="sec-label">计算方法</div>
          <div class="field-grid">
            <div v-for="item in calculationMethods" :key="item.key" class="field">
              <label>{{ item.label }}</label>
              <el-select size="small" :model-value="item.value" style="width:100%">
                <el-option v-for="opt in item.options" :key="opt.value" :label="opt.label" :value="opt.value" />
              </el-select>
            </div>
          </div>

          <div class="sec-label">其它数据</div>
          <div class="field-grid">
            <template v-for="item in otherData" :key="item.key">
              <div v-if="item.hasSwitch" class="field field-with-switch">
                <div class="wgr-label-row">
                  <span>{{ item.label }}</span>
                  <el-switch
                      :model-value="item.switchValue"
                      disabled
                      style="--el-switch-on-color:#e8a000;--el-switch-off-color:#ccc"
                      size="small"
                  />
                </div>
                <el-input size="small" readonly :disabled="!item.switchValue" :model-value="item.value" />
              </div>
              <div v-else class="field">
                <label>{{ item.label }}</label>
                <el-select v-if="item.type === 'select'" size="small" :model-value="item.value" style="width:100%">
                  <el-option v-for="opt in item.options" :key="opt.value" :label="opt.label" :value="opt.value" />
                </el-select>
                <el-input v-else size="small" readonly :model-value="item.value" />
              </div>
            </template>
          </div>
        </div>

        <div v-else>
          <div class="sec-label">输出结果</div>
          <div class="field">
            <label>动态储量(10⁸m³)</label>
            <el-input size="small" readonly :model-value="output.originalGasVolume" />
          </div>
          <div class="field">
            <label>回归分析截距(MPa/(10⁴m³/d))</label>
            <el-input size="small" readonly :model-value="output.intercept" />
          </div>
          <div class="field">
            <label>回归分析斜率([MPa/(10⁴m³/d)]/d)</label>
            <el-input size="small" readonly :model-value="output.gradient || output.slope" />
          </div>
          <div class="field">
            <label>R²(无)</label>
            <el-input size="small" readonly :model-value="output.rsquared" />
          </div>
          <div class="field">
            <label>结果可靠性</label>
            <el-input size="small" readonly :model-value="reliability" />
          </div>
        </div>
      </div>

      <div v-show="!panelCollapsed" class="panel-tabs">
        <button
            class="tab-btn"
            :class="{ active: activeTab === 'input' }"
            @click="activeTab = 'input'"
        >
          输入
        </button>
        <button
            class="tab-btn"
            :class="{ active: activeTab === 'output' }"
            @click="activeTab = 'output'"
        >
          输出
        </button>
      </div>
      <div v-if="!panelCollapsed" class="params-resizer" @mousedown="startParamsPanelResize"></div>
    </div>

    <div ref="chartAreaEl" class="chart-area">
      <div v-if="noData && activeChartTab === 'chart'" class="no-data">
        <p>暂无数据</p>
      </div>
      <div v-show="!noData && activeChartTab === 'chart'" ref="chartEl" class="chart-instance" />
      <div
          v-if="activeChartTab === 'chart'"
          class="floating-chart-legend"
          :class="{ dragging: draggingLegend }"
          :style="legendStyle"
          @mousedown="startLegendDrag"
      >
        <div class="floating-legend-item">
          <span class="legend-dot" style="backgroundColor: #0037b5; borderColor: #0037b5"></span>
          <span>数据点(MPa/(10⁴m³/d))</span>
        </div>
        <div class="floating-legend-item">
          <span class="legend-line" style="backgroundColor: #333; borderColor: #333"></span>
          <span>回归线(MPa/(10⁴m³/d))</span>
        </div>
      </div>

      <div v-if="activeChartTab === 'table'" class="data-list-panel">
        <el-table :data="tableRows" :loading="tableLoading" size="small" height="100%" border stripe>
          <el-table-column prop="index" label="序号" width="76" sortable />
          <el-table-column prop="cumulativeGasProduction" label="累产气量(10^8m³)" min-width="170" sortable />
          <el-table-column prop="normalizedPressure" label="重整压力(MPa/(10^4m³/d))" min-width="220" sortable />
          <el-table-column prop="isParticipateAnalysis" label="是否参与分析" width="130">
            <template #default="{ row }">
              {{ row.isParticipateAnalysis ? '是' : '否' }}
            </template>
          </el-table-column>
          <el-table-column prop="pseudotime" label="物质平衡拟时间(d)" min-width="170" sortable />
        </el-table>
      </div>

      <div class="bottom-chart-tabs">
        <button type="button" class="bottom-chart-tab" :class="{ active: activeChartTab === 'table' }" @click="showTableTab">
          数据列表
        </button>
        <button type="button" class="bottom-chart-tab" :class="{ active: activeChartTab === 'chart' }" @click="activeChartTab = 'chart'">
          结果分析图
        </button>
      </div>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.db-wrap {
  display: flex;
  height: 100%;
  background: #fff;
}

.params-panel {
  display: flex;
  flex-direction: column;
  border-right: 1px solid #e0e0e0;
  background: #fff;
  overflow: hidden;
  position: relative;
  transition: width 0.16s ease, min-width 0.16s ease;

  &.collapsed {
    background: transparent;
    border-right: 0;
  }

  &.resizing {
    transition: none;
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

.panel-header {
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

.panel-body {
  flex: 1;
  overflow-y: auto;
  padding: 4px 12px 14px;
}

.sec-label {
  font-weight: 500;
  color: #333;
  font-size: 13px;
  margin: 10px 0 7px;
  &:first-child { margin-top: 4px; }
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
  span { color: #555; font-size: 12px; }
}

.panel-tabs {
  display: flex;
  height: 30px;
  border-top: 1px solid #e0e0e0;
  flex-shrink: 0;
}

.tab-btn {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  color: #555;
  cursor: pointer;
  border: 0;
  background: transparent;
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

.chart-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  position: relative;
}

.chart-instance {
  flex: 1;
  width: 100%;
}

.no-data {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  text-align: center;
  color: #999;
  font-size: 14px;
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
  gap: 6px;

  .legend-dot {
    width: 10px;
    height: 10px;
    border-radius: 50%;
    display: inline-block;
    flex-shrink: 0;
  }

  .legend-line {
    width: 20px;
    height: 2px;
    display: inline-block;
    flex-shrink: 0;
  }
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
