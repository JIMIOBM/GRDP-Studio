<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import {
  calculateExponentialCoefficientCurve,
  normalizeCoefficientFitPoint
} from '@/utils/productivityCoefficientCalculation'

const props = defineProps({
  wellName: { type: String, default: '' },
  maximumFormationPressure: { type: [String, Number], default: '56.34' },
  formationTemperature: { type: [String, Number], default: '120' },
  productivityCoefficientC: { type: [String, Number], default: '' },
  productivityExponentN: { type: [String, Number], default: '' },
  correctedCoefficientC: { type: [String, Number], default: '' },
  correctedExponentN: { type: [String, Number], default: '' },
  fittedFormationPressure: { type: [String, Number], default: '' },
  fittedFlowRate: { type: [String, Number], default: '' },
  openFlowRate: { type: [String, Number], default: '' },
  pvtRecord: { type: Object, default: null },
  pvtTableOptions: { type: Array, default: () => [] },
  selectedPvtTable: { type: String, default: '' },
  pvtLoading: { type: Boolean, default: false },
  operationType: {
    type: String,
    default: 'production',
    validator: value => ['production', 'injection'].includes(value)
  },
  projectId: { type: [Number, String], required: true },
  gasReservoirId: { type: [Number, String], required: true },
   methodType: { 
    type: String, 
    default: '指数式',
    validator: value => ['指数式', '二项式'].includes(value)
  }
})

const emit = defineEmits([
  'select-pvt',
  'update:coefficient-c',
  'update:exponent-n',
  'update:corrected-c',
  'update:corrected-n',
  'update:fitted-pressure',
  'update:fitted-flow-rate',
  'update:open-flow-rate',
  'update:operation-type'
])

const chartEl = ref(null)
const paramsCollapsed = ref(false)
let chartResizeObserver = null
const chartType = ref('ipr-curve')
const calculationMethod = ref('拟压力')
const darcyCoefficient = ref('')
const nonDarcyCoefficient = ref('')
const maximumInjectionPressure = ref('')
const calculatedRequest = ref(null)

const binomialCoefficientA = ref('1.0877')
const binomialCoefficientB = ref('3.8453')
const correctedBinomialA = ref('2.099')
const correctedBinomialB = ref('6.096')
let chart = null

const isInjection = computed(() => props.operationType === 'injection')
const operationLabel = computed(() => isInjection.value ? '注气' : '采气')

const calculateCurve = (coefficient, exponent) => calculateExponentialCoefficientCurve({
  reservoirPressure: props.maximumFormationPressure,
  coefficient,
  exponent,
  calculationMethod: calculationMethod.value,
  operationType: props.operationType,
  maximumFlowingPressure: maximumInjectionPressure.value,
  pvtResultRows: props.pvtRecord?.gasResultRows || []
})

const legacyIPRData = () => {
  const reservoirPressure = Number(props.maximumFormationPressure) || 56.34
  return Array.from({ length: 501 }, (_, index) => {
    const ratio = index / 500
    return [120 * ratio, reservoirPressure * (1 - ratio * ratio)]
  })
}

const curveData = curve => curve?.points?.map(point => [point.flowRate, point.flowingPressure]) || []

const generateFitPoint = () => {
  if (props.methodType !== '指数式') {
    const fittedPressureText = String(props.fittedFormationPressure ?? '').trim()
    const fittedPressure = Number(fittedPressureText || 28.99)
    if (!Number.isFinite(fittedPressure)) return []
    const reservoirPressure = Number(props.maximumFormationPressure) || 56.34
    const ratio = Math.sqrt(1 - fittedPressure / reservoirPressure)
    return Number.isFinite(ratio) ? [[120 * ratio, fittedPressure]] : []
  }
  const point = calculatedRequest.value?.fitPoint
  return point ? [[point.flowRate, point.flowingPressure]] : []
}

const initChart = () => {
  if (!chartEl.value) return
  
  if (chart) {
    chart.dispose()
  }
  
  chart = echarts.init(chartEl.value)
  updateChart()
}

const updateChart = () => {
  if (!chart) return
  const exponential = props.methodType === '指数式'
  const flowAxisName = exponential
    ? `${operationLabel.value}量 qsc(10⁴m³/d)`
    : 'qsc(10⁴m³/d)'
  const fittedCurveName = exponential ? `${operationLabel.value}拟合曲线（C、n）` : 'IPR曲线'
  const iprCurveName = `${operationLabel.value}IPR曲线（C′、n′）`
  const fitPointName = exponential ? `${operationLabel.value}拟合点` : '拟合点'

  const option = {
    grid: {
      left: 60,
      right: 30,
      top: 30,
      bottom: 50,
      containLabel: true
    },
    xAxis: {
      type: 'value',
      name: flowAxisName,
      nameLocation: 'middle',
      nameGap: 30,
      nameTextStyle: { fontSize: 12, color: '#555' },
      axisLine: { lineStyle: { color: '#d0d0d0' } },
      axisTick: { show: false },
      axisLabel: { show: false },
      splitLine: { show: false }
    },
    yAxis: {
      type: 'value',
      scale: exponential && isInjection.value,
      name: 'pwf(MPa)',
      nameLocation: 'middle',
      nameGap: 40,
      nameTextStyle: { fontSize: 12, color: '#555' },
      axisLine: { lineStyle: { color: '#d0d0d0' } },
      axisTick: { show: false },
      axisLabel: { show: false },
      splitLine: { show: false }
    },
    tooltip: { show: false },
    legend: exponential && calculatedRequest.value
      ? {
          show: true,
          top: 0,
          right: 30,
          data: [fittedCurveName, iprCurveName, fitPointName]
        }
      : { show: false },
    graphic: exponential && !calculatedRequest.value
      ? [{
          type: 'text',
          left: 'center',
          top: 'middle',
          silent: true,
          style: {
            text: '请填写参数后点击计算',
            fill: '#999',
            fontSize: 14
          }
        }]
      : [],
    series: [
      {
        name: fittedCurveName,
        type: 'line',
        data: exponential
          ? curveData(calculatedRequest.value?.fittedCurve)
          : legacyIPRData(),
        smooth: !exponential,
        symbol: 'none',
        lineStyle: {
          color: '#3b82f6',
          width: exponential && chartType.value === 'production-fit' ? 3.2 : 2.2
        },
        itemStyle: { color: '#3b82f6' }
      },
      ...(exponential ? [{
        name: iprCurveName,
        type: 'line',
        data: curveData(calculatedRequest.value?.iprCurve),
        smooth: false,
        symbol: 'none',
        lineStyle: {
          color: '#d946ef',
          width: chartType.value === 'ipr-curve' ? 3.2 : 2.2
        },
        itemStyle: { color: '#d946ef' }
      }] : []),
      {
        name: fitPointName,
        type: 'scatter',
        data: generateFitPoint(),
        symbolSize: 10,
        itemStyle: { color: '#f59e0b', borderColor: '#fff', borderWidth: 2 },
        z: 5
      }
    ]
  }
  
  chart.setOption(option, true)
}

const handleCalculate = () => {
  if (props.methodType !== '指数式') {
    updateChart()
    return
  }
  try {
    const fittedCurve = calculateCurve(props.productivityCoefficientC, props.productivityExponentN)
    let iprCurve
    try {
      iprCurve = calculateCurve(props.correctedCoefficientC, props.correctedExponentN)
    } catch (error) {
      const message = String(error?.message || '')
        .replace('注气能力系数 C', '修正注气能力系数 C′')
        .replace('产能系数 C', '修正产能系数 C′')
        .replace('注气指数 n', '修正注气指数 n′')
        .replace('产能指数 n', '修正产能指数 n′')
      throw new Error(message || '修正参数无法生成IPR曲线')
    }
    const fitPoint = normalizeCoefficientFitPoint({
      operationType: props.operationType,
      flowRate: props.fittedFlowRate,
      flowingPressure: props.fittedFormationPressure,
      curve: fittedCurve
    })
    calculatedRequest.value = { fittedCurve, iprCurve, fitPoint }
    emit('update:open-flow-rate', Number(iprCurve.limitRate.toFixed(4)).toString())
    updateChart()
  } catch (error) {
    calculatedRequest.value = null
    emit('update:open-flow-rate', '')
    updateChart()
    ElMessage.error(error.message || '当前参数无法生成IPR曲线')
  }
}

const handleCoefficientCChange = e => {
  emit('update:coefficient-c', e.target.value)
}

const handleExponentNChange = e => {
  emit('update:exponent-n', e.target.value)
}

const handleCorrectedCChange = e => {
  emit('update:corrected-c', e.target.value)
}

const handleCorrectedNChange = e => {
  emit('update:corrected-n', e.target.value)
}

const handleFittedPressureChange = e => {
  emit('update:fitted-pressure', e.target.value)
}

const handleFittedFlowRateChange = e => {
  emit('update:fitted-flow-rate', e.target.value)
}

const handleOpenFlowRateChange = e => {
  emit('update:open-flow-rate', e.target.value)
}

watch([
  () => props.methodType,
  () => props.selectedPvtTable,
  () => props.maximumFormationPressure,
  () => props.productivityCoefficientC,
  () => props.productivityExponentN,
  () => props.correctedCoefficientC,
  () => props.correctedExponentN,
  () => props.fittedFormationPressure,
  () => props.fittedFlowRate,
  () => props.operationType,
  () => props.pvtRecord?.gasResultRows,
  maximumInjectionPressure,
  calculationMethod
], () => {
  if (props.methodType === '指数式') {
    calculatedRequest.value = null
    emit('update:open-flow-rate', '')
  }
  nextTick(updateChart)
}, { deep: true })

watch(chartType, () => nextTick(updateChart))

onMounted(() => {
  nextTick(() => initChart())
  chartResizeObserver = new ResizeObserver(() => chart?.resize())
  if (chartEl.value) chartResizeObserver.observe(chartEl.value)
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  chartResizeObserver?.disconnect()
  if (chart) {
    chart.dispose()
    chart = null
  }
  window.removeEventListener('resize', handleResize)
})

const handleResize = () => {
  chart?.resize()
}
</script>

<template>
  <div class="exponential-workspace">
    <aside class="parameter-panel" :class="{ collapsed: paramsCollapsed }">
      <button v-if="paramsCollapsed" class="parameter-collapsed-tab" type="button" title="展开参数设置" @click="paramsCollapsed = false">参数设置</button>
      <div v-show="!paramsCollapsed" class="panel-header">
        <h3 class="panel-title">参数设置</h3>
        <button class="parameter-toggle" type="button" title="收起参数设置" aria-label="收起参数设置" @click="paramsCollapsed = true">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="#777" aria-hidden="true"><path d="M16,12V4H17V2H7V4H8V12L6,14V16H11.2V22H12.8V16H18V14L16,12Z" /></svg>
        </button>
      </div>
      
      <div v-show="!paramsCollapsed" class="parameter-form">
        <label class="field-group">
          <span>选择PVT表</span>
          <select :value="selectedPvtTable" :disabled="pvtLoading || !pvtTableOptions.length" @change="emit('select-pvt', $event.target.value)">
            <option value="" disabled>{{ pvtLoading ? '正在加载PVT…' : pvtTableOptions.length ? '请选择PVT性质' : '当前井暂无已保存PVT性质' }}</option>
            <option v-for="option in pvtTableOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
          </select>
        </label>

        <div class="section-heading">
          <span>其他数据</span>
          <i></i>
        </div>

        <label class="field-group">
          <span>计算IPR曲线的最大地层压力(MPa)</span>
          <input :value="maximumFormationPressure" readonly />
        </label>

        <label class="field-group">
          <span>地层温度(℃)</span>
          <input :value="formationTemperature" readonly />
        </label>

                <!-- ========== 指数式========== -->
        <template v-if="methodType === '指数式'">
          <label class="field-group">
            <span>{{ isInjection ? '注气能力系数C' : '产能系数C' }}</span>
            <input :value="productivityCoefficientC" placeholder="请输入实际系数" @input="handleCoefficientCChange" inputmode="decimal" />
          </label>

          <label class="field-group">
            <span>{{ isInjection ? '注气指数n' : '产能指数n' }}</span>
            <input :value="productivityExponentN" placeholder="请输入实际指数" @input="handleExponentNChange" inputmode="decimal" />
          </label>

          <label class="field-group">
            <span>{{ isInjection ? "修正注气能力系数C'" : "修正产能系数C'" }}</span>
            <input :value="correctedCoefficientC" placeholder="请输入实际修正系数" @input="handleCorrectedCChange" inputmode="decimal" />
          </label>

          <label class="field-group">
            <span>{{ isInjection ? "修正注气指数n'" : "修正产能指数n'" }}</span>
            <input :value="correctedExponentN" placeholder="请输入实际修正指数" @input="handleCorrectedNChange" inputmode="decimal" />
          </label>

          <label class="field-group">
            <span>{{ isInjection ? '注气拟合点井底压力(MPa)' : '采气拟合点井底流压(MPa)' }}</span>
            <input :value="fittedFormationPressure" placeholder="可选" @input="handleFittedPressureChange" inputmode="decimal" />
          </label>

        </template>

        <!-- ========== 二项式 ========== -->
        <template v-else>
          <label class="field-group">
            <span>产能系数A</span>
            <input v-model="binomialCoefficientA" placeholder="例如：1.0877" inputmode="decimal" />
          </label>

          <label class="field-group">
            <span>产能系数B</span>
            <input v-model="binomialCoefficientB" placeholder="例如：3.8453" inputmode="decimal" />
          </label>

          <label class="field-group">
            <span>修正产能系数A'</span>
            <input v-model="correctedBinomialA" placeholder="例如：2.099" inputmode="decimal" />
          </label>

          <label class="field-group">
            <span>修正产能系数B'</span>
            <input v-model="correctedBinomialB" placeholder="例如：6.096" inputmode="decimal" />
          </label>
          <label class="field-group">
            <span>拟合产量的地层压力</span>
            <input :value="fittedFormationPressure" @input="handleFittedPressureChange" inputmode="decimal" />
          </label>
        </template>

        <fieldset v-if="methodType === '指数式'" class="radio-group">
          <legend>注采类型</legend>
          <label>
            <input
              :checked="operationType === 'production'"
              type="radio"
              value="production"
              @change="emit('update:operation-type', 'production')"
            />采气
          </label>
          <label>
            <input
              :checked="operationType === 'injection'"
              type="radio"
              value="injection"
              @change="emit('update:operation-type', 'injection')"
            />注气
          </label>
        </fieldset>

        <label v-if="methodType === '指数式' && isInjection" class="field-group">
          <span>最大井底注入压力(MPa)</span>
          <input v-model="maximumInjectionPressure" :placeholder="`必须大于 ${maximumFormationPressure} MPa`" inputmode="decimal" />
        </label>

        <fieldset class="radio-group">
          <legend>计算方法</legend>
          <label>
            <input v-model="calculationMethod" type="radio" value="拟压力" />拟压力
          </label>
          <label>
            <input v-model="calculationMethod" type="radio" value="压力平方法" />压力平方法
          </label>
          <label>
            <input v-model="calculationMethod" type="radio" value="压力法" />压力法
          </label>
        </fieldset>

        <button type="button" class="calculate-button" @click="handleCalculate">
          计算
        </button>

        <label class="field-group">
          <span>{{ methodType === '指数式' && isInjection ? '最大注气量' : '无阻流量' }}(10⁴m³/d)</span>
          <input
            v-if="methodType === '指数式'"
            :value="calculatedRequest ? openFlowRate : ''"
            readonly
            inputmode="decimal"
          />
          <input
            v-else
            :value="openFlowRate || '5'"
            inputmode="decimal"
            @input="handleOpenFlowRateChange"
          />
        </label>

        <label v-if="methodType === '指数式'" class="field-group">
          <span>{{ isInjection ? '注气拟合点注气量(10⁴m³/d)' : '采气拟合点采气量(10⁴m³/d)' }}</span>
          <input :value="fittedFlowRate" placeholder="可选" @input="handleFittedFlowRateChange" inputmode="decimal" />
        </label>
      </div>
    </aside>

    <main class="chart-panel">
      <div class="chart-toolbar">
        <div class="toolbar-left">
          <label class="chart-type-toggle">
            <input 
              type="radio" 
              :value="'production-fit'" 
              v-model="chartType"
            />
            <span>{{ methodType === '指数式' ? `${operationLabel}量拟合` : '产量拟合' }}</span>
          </label>
          <label class="chart-type-toggle">
            <input 
              type="radio" 
              :value="'ipr-curve'" 
              v-model="chartType"
              checked
            />
            <span>{{ methodType === '指数式' ? `${operationLabel}IPR曲线` : 'IPR曲线' }}</span>
          </label>
        </div>
      </div>
      
      <div ref="chartEl" class="chart-container"></div>
    </main>
  </div>
</template>

<style lang="scss" scoped>
.exponential-workspace { flex: 1; display: flex; min-width: 0; min-height: 0; background: #fff; color: #333; font-size: 13px; }
.parameter-panel { width: 280px; min-width: 280px; flex: 0 0 280px; min-height: 0; display: flex; flex-direction: column; border-right: 1px solid #d7d7d7; background: #fff; overflow: hidden; }
.parameter-panel.collapsed { width: 34px; min-width: 34px; flex-basis: 34px; }
.panel-header { height: 34px; flex: 0 0 34px; padding: 0 12px; display: flex; align-items: center; justify-content: space-between; box-sizing: border-box; border-bottom: 1px solid #d7d7d7; background: #f2f2f2; }
.panel-title { margin: 0; font: inherit; font-size: 13px; font-weight: 400; }
.parameter-toggle { width: 20px; height: 20px; padding: 0; border: 0; background: transparent; display: flex; align-items: center; justify-content: center; cursor: pointer; }
.parameter-toggle:hover, .parameter-collapsed-tab:hover { background: #fff8d9; }
.parameter-collapsed-tab { width: 100%; height: 76px; padding: 8px 0 0; border: 0; border-bottom: 1px solid #e2e6ea; background: #fff; color: #222; font: inherit; writing-mode: vertical-rl; text-orientation: upright; display: flex; align-items: center; cursor: pointer; }
.parameter-form { flex: 1; min-height: 0; overflow-y: auto; padding: 4px 12px 14px; }
.field-group { display: block; margin-bottom: 9px; color: #333;
  > span { display: block; margin-bottom: 3px; font-size: 12px; line-height: 18px; }
  input, select { width: 100%; height: 24px; padding: 0 8px; border: 1px solid #aaa; border-radius: 3px; background: #fff; color: #333; box-sizing: border-box; font: inherit; font-size: 13px; outline: none;
    &:focus { border-color: #b99500; box-shadow: 0 0 0 2px rgba(242,200,17,.16); }
    &[readonly] { background: #f5f5f5; }
    &:disabled { color: #999; background: #f5f5f5; cursor: not-allowed; }
  }
}
.section-heading { height: 22px; margin: 10px 0 7px; display: flex; align-items: center; gap: 8px; font-size: 13px; font-weight: 500;
  i { flex: 1; height: 1px; background: #999; }
}
.radio-group { margin: 0 0 10px; padding: 0; border: 0;
  legend { margin-bottom: 7px; padding: 0; font-size: 13px; font-weight: 500; }
  label { display: inline-flex; align-items: center; gap: 4px; margin-right: 10px; font-size: 13px; white-space: nowrap; cursor: pointer; }
}
.radio-group input, .chart-type-toggle input { width: 14px; height: 14px; margin: 0; accent-color: #303133; }
.calculate-button { min-width: 86px; height: 32px; margin-bottom: 10px; padding: 0 22px; border: 0; border-radius: 5px; background: #252525; color: #fff; font: inherit; font-size: 13px; font-weight: 700; cursor: pointer;
  &:hover { background: #050505; }
}
.chart-panel { flex: 1; display: flex; flex-direction: column; min-width: 0; min-height: 0; background: #fff; }
.chart-toolbar { min-height: 40px; flex: 0 0 auto; display: flex; align-items: center; padding: 0 12px; background: #fff; box-sizing: border-box; }
.toolbar-left { display: flex; flex-wrap: wrap; gap: 12px; }
.chart-type-toggle { display: inline-flex; align-items: center; gap: 4px; font-size: 13px; color: #333; cursor: pointer; }
.chart-container { flex: 1; min-height: 0; }
</style>
