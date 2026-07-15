<script setup>
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
const resultData = ref(null)
const noData = ref(false)
const activeTab = ref('input')
const panelCollapsed = ref(false)

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

const chartPoints = computed(() => {
  const chartItem = resultData.value?.chartItems?.find(item => item.yAxisField === 'pressure')
  if (chartItem?.data?.length) {
    return chartItem.data
      .filter(item => item.isDeleted !== true)
      .map(item => [Number(item.xValue), Number(item.yValue)])
      .filter(([x, y]) => Number.isFinite(x) && Number.isFinite(y))
  }
  if (!Array.isArray(resultData.value?.data)) return []
  return resultData.value.data
    .filter(item => item.isDeleted !== true)
    .map(item => {
      const x = Number(item.pseudotime)
      const y = Number(item.pressure)
      return [x, y]
    })
    .filter(([x, y]) => x > 0 && y > 0)
})

const allPoints = computed(() => {
  const chartItem = resultData.value?.chartItems?.find(item => item.yAxisField === 'pressure')
  if (chartItem?.data?.length) {
    return chartItem.data
      .filter(item => item.isDeleted === true)
      .map(item => [Number(item.xValue), Number(item.yValue)])
      .filter(([x, y]) => Number.isFinite(x) && Number.isFinite(y))
  }
  if (!Array.isArray(resultData.value?.data)) return []
  return resultData.value.data
    .filter(item => item.isDeleted === true)
    .map(item => {
      const x = Number(item.pseudotime)
      const y = Number(item.pressure)
      return [x, y]
    })
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
  const chartItem = resultData.value?.chartItems?.find(item => item.yAxisField === 'linearRegressionPressure')
  if (chartItem?.data?.length) {
    return chartItem.data
      .map(item => [Number(item.xValue), Number(item.yValue)])
      .filter(([x, y]) => Number.isFinite(x) && Number.isFinite(y))
  }
  const { slope, intercept } = regression.value
  const xs = chartPoints.value.map(([x]) => x)
  if (!xs.length || !Number.isFinite(slope) || !Number.isFinite(intercept)) return []
  const minX = Math.min(...xs)
  const maxX = Math.max(...xs)
  return [[minX, slope * minX + intercept], [maxX, slope * maxX + intercept]]
})

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
    legend: {
      data: ['数据点(MPa/(10⁴m³/d))', '回归线(MPa/(10⁴m³/d))'],
      top: 35,
      left: 'center'
    },
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
        type: 'group',
        left: 'right',
        bottom: 30,
        children: [
          {
            type: 'text',
            style: {
              text: Number.isFinite(slope) && Number.isFinite(intercept)
                ? `Y = ${slope.toExponential(4)}*X + ${intercept.toExponential(4)}`
                : '',
              fontSize: 11,
              fill: '#666'
            },
            left: 10,
            top: 0
          },
          {
            type: 'text',
            style: {
              text: Number.isFinite(r2) ? `R² = ${r2.toFixed(4)}` : '',
              fontSize: 11,
              fill: '#666'
            },
            left: 10,
            top: 18
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
      class="params-panel"
      :class="{ collapsed: panelCollapsed }"
      :style="{ width: panelCollapsed ? '22px' : '380px', minWidth: panelCollapsed ? '22px' : '380px' }"
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
          <div class="section">
            <div class="section-title">气体性质</div>
            <div class="section-body">
              <div v-for="item in gasProperties" :key="item.key" class="param-item">
                <label>{{ item.label }}</label>
                <el-select v-if="item.type === 'select'" size="small" :model-value="item.value">
                  <el-option v-for="opt in item.options" :key="opt.value" :label="opt.label" :value="opt.value" />
                </el-select>
                <el-input v-else size="small" :model-value="item.value" />
              </div>
            </div>
          </div>

          <div class="section">
            <div class="section-title">计算方法</div>
            <div class="section-body">
              <div v-for="item in calculationMethods" :key="item.key" class="param-item">
                <label>{{ item.label }}</label>
                <el-select size="small" :model-value="item.value">
                  <el-option v-for="opt in item.options" :key="opt.value" :label="opt.label" :value="opt.value" />
                </el-select>
              </div>
            </div>
          </div>

          <div class="section">
            <div class="section-title">其它数据</div>
            <div class="section-body">
              <div v-for="item in otherData" :key="item.key" class="param-item">
                <label>{{ item.label }}</label>
                <div class="field-with-switch" v-if="item.hasSwitch">
                  <el-input size="small" :model-value="item.value" />
                  <el-switch :model-value="item.switchValue" />
                </div>
                <el-select v-else-if="item.type === 'select'" size="small" :model-value="item.value">
                  <el-option v-for="opt in item.options" :key="opt.value" :label="opt.label" :value="opt.value" />
                </el-select>
                <el-input v-else size="small" :model-value="item.value" />
              </div>
            </div>
          </div>
        </div>

        <div v-else>
          <div class="output-section">
            <div class="output-field">
              <label>动态储量(10⁸m³)</label>
              <el-input size="small" readonly :model-value="output.originalGasVolume" />
            </div>
            <div class="output-field">
              <label>回归分析截距(MPa/(10⁴m³/d))</label>
              <el-input size="small" readonly :model-value="output.intercept" />
            </div>
            <div class="output-field">
              <label>回归分析斜率([MPa/(10⁴m³/d)]/d)</label>
              <el-input size="small" readonly :model-value="output.gradient || output.slope" />
            </div>
            <div class="output-field">
              <label>R²(无)</label>
              <el-input size="small" readonly :model-value="output.rsquared" />
            </div>
            <div class="output-field">
              <label>结果可靠性</label>
              <el-input size="small" readonly :model-value="reliability" />
            </div>
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
    </div>

    <div class="chart-area">
      <div v-if="noData" class="no-data">
        <p>暂无数据</p>
      </div>
      <div v-show="!noData" ref="chartEl" class="chart-instance" />
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
  transition: width 0.16s ease, min-width 0.16s ease;

  &.collapsed {
    background: transparent;
    border-right: 0;
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

  &:hover {
    background: #f5f5f5;
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
  padding: 10px;
}

.section {
  margin-bottom: 15px;
}

.section-title {
  padding: 6px 10px;
  background: #f5f5f5;
  font-size: 12px;
  font-weight: 600;
  color: #555;
  margin-bottom: 8px;
}

.section-body {
  padding: 0 5px;
}

.param-item {
  margin-bottom: 8px;

  label {
    display: block;
    font-size: 12px;
    color: #666;
    margin-bottom: 3px;
  }
}

.field-with-switch {
  display: flex;
  gap: 8px;
  align-items: center;

  :deep(.el-input) {
    flex: 1;
  }
}

.panel-tabs {
  display: flex;
  border-top: 1px solid #e0e0e0;
  background: #fafafa;
}

.tab-btn {
  flex: 1;
  padding: 10px;
  border: none;
  background: transparent;
  font-size: 13px;
  color: #666;
  cursor: pointer;

  &.active {
    background: #ffc53d;
    color: #1a1a1a;
  }

  &:hover:not(.active) {
    background: #f0f0f0;
  }
}

.output-section {
  padding: 15px;
}

.output-field {
  margin-bottom: 12px;

  label {
    display: block;
    font-size: 12px;
    color: #555;
    margin-bottom: 4px;
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
</style>
