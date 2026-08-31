<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({
  wellName: { type: String, default: '' },
  maximumFormationPressure: { type: [String, Number], default: '56.34' },
  formationTemperature: { type: [String, Number], default: '120' },
  productivityCoefficientC: { type: [String, Number], default: '1.0877' },
  productivityExponentN: { type: [String, Number], default: '3.8453' },
  correctedCoefficientC: { type: [String, Number], default: '2.099' },
  correctedExponentN: { type: [String, Number], default: '6.096' },
  fittedFormationPressure: { type: [String, Number], default: '28.99' },
  openFlowRate: { type: [String, Number], default: '5' },
  pvtRecord: { type: Object, default: null },
  projectId: { type: [Number, String], required: true },
  gasReservoirId: { type: [Number, String], required: true },
   methodType: { 
    type: String, 
    default: '指数式',
    validator: value => ['指数式', '二项式'].includes(value)
  }
})

const emit = defineEmits([
  'update:coefficient-c',
  'update:exponent-n',
  'update:corrected-c',
  'update:corrected-n',
  'update:fitted-pressure',
  'update:open-flow-rate'
])

const chartEl = ref(null)
const chartType = ref('ipr-curve')
const calculationMethod = ref('拟压力')
const darcyCoefficient = ref('')
const nonDarcyCoefficient = ref('')

const binomialCoefficientA = ref('1.0877')
const binomialCoefficientB = ref('3.8453')
const correctedBinomialA = ref('2.099')
const correctedBinomialB = ref('6.096')
let chart = null

const scientific = value => {
  const number = Number(value)
  if (!Number.isFinite(number)) return ''
  return number === 0 ? '0.0000' : number.toExponential(4).replace('e', 'E')
}

const displayFittedPressure = computed(() => Number(props.fittedFormationPressure) || 25.99)

const parseScientific = (val, fallback) => {
  const num = Number(val)
  return Number.isFinite(num) && num > 0 ? num : fallback
}

const generateIPRData = () => {
  const pR = Number(props.maximumFormationPressure) || 56.34
  const points = []
  const steps = 500
  
  for (let i = 0; i <= steps; i++) {
    const t = i / steps
    const qsc = 120 * t
    const pwf = pR * (1 - t * t)  
    points.push([qsc, pwf])
  }
  
  return points
}

const generateFitPoint = () => {
  const pR = Number(props.maximumFormationPressure) || 56.34
  const fittedP = Number(props.fittedFormationPressure) || 25.99
  const t = Math.sqrt(1 - fittedP / pR)  
  const qsc = 120 * t
  return [[qsc, fittedP]]
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
      name: 'qsc(10⁴m³/d)',
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
    series: [
      {
        name: 'IPR曲线',
        type: 'line',
        data: generateIPRData(),
        smooth: true,
        symbol: 'none',
        lineStyle: { color: '#d946ef', width: 2.8 },
        itemStyle: { color: '#d946ef' }
      },
      {
        name: '拟合点',
        type: 'scatter',
        data: generateFitPoint(),
        symbolSize: 10,
        itemStyle: { color: '#d946ef', borderColor: '#fff', borderWidth: 2 }
      }
    ]
  }
  
  chart.setOption(option)
}

const handleCalculate = () => {
  updateChart()
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

const handleOpenFlowRateChange = e => {
  emit('update:open-flow-rate', e.target.value)
}

watch([
  () => props.maximumFormationPressure,
  () => props.productivityCoefficientC,
  () => props.productivityExponentN,
  () => props.correctedCoefficientC,
  () => props.correctedExponentN,
  () => props.fittedFormationPressure,
  () => chartType
], () => {
  nextTick(() => updateChart())
}, { deep: true })

onMounted(() => {
  nextTick(() => initChart())
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
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
    <aside class="parameter-panel">
      <div class="panel-header">
        <h3 class="panel-title">参数设置</h3>
      </div>
      
      <div class="parameter-form">
        <label class="field-group">
          <span>选择PVT表</span>
          <select disabled>
            <option>请选择</option>
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
            <span>产能系数C</span>
            <input :value="productivityCoefficientC" @input="handleCoefficientCChange" inputmode="decimal" />
          </label>

          <label class="field-group">
            <span>产能指数n</span>
            <input :value="productivityExponentN" @input="handleExponentNChange" inputmode="decimal" />
          </label>

          <label class="field-group">
            <span>修正产能系数C'</span>
            <input :value="correctedCoefficientC" @input="handleCorrectedCChange" inputmode="decimal" />
          </label>

          <label class="field-group">
            <span>修正产能指数n'</span>
            <input :value="correctedExponentN" @input="handleCorrectedNChange" inputmode="decimal" />
          </label>

          <label class="field-group">
            <span>拟合产量的地层压力</span>
            <input :value="fittedFormationPressure" @input="handleFittedPressureChange" inputmode="decimal" />
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
          <span>无阻流量(10⁴m³/d)</span>
          <input :value="openFlowRate" @input="handleOpenFlowRateChange" inputmode="decimal" />
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
            <span>产量拟合</span>
          </label>
          <label class="chart-type-toggle">
            <input 
              type="radio" 
              :value="'ipr-curve'" 
              v-model="chartType"
              checked
            />
            <span>IPR曲线</span>
          </label>
        </div>
      </div>
      
      <div ref="chartEl" class="chart-container"></div>
    </main>
  </div>
</template>

<style lang="scss" scoped>
.exponential-workspace {
  flex: 1;
  display: flex;
  gap: 0;
  min-height: 0;
  background: #fff;
}

.parameter-panel {
  width: 280px;
  flex: 0 0 280px;
  display: flex;
  flex-direction: column;
  border-right: 1px solid #e5e5e5;
  background: #fafafa;
  overflow-y: auto;
}

.panel-header {
  padding: 16px 16px 12px;
  border-bottom: 1px solid #e5e5e5;
  background: #fff;
}

.panel-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: #202020;
}

.parameter-form {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.field-group {
  display: flex;
  flex-direction: column;
  gap: 6px;

  span {
    font-size: 13px;
    color: #555;
    font-weight: 500;
  }

  input, select {
    height: 32px;
    padding: 0 10px;
    border: 1px solid #dcdcdc;
    border-radius: 4px;
    font-size: 13px;
    background: #fff;
    color: #252525;
    
    &:focus {
      outline: none;
      border-color: #f4d000;
      box-shadow: 0 0 0 2px rgba(244, 208, 0, 0.15);
    }
    
    &[readonly] {
      background: #f5f5f5;
      color: #666;
    }
    
    &[disabled] {
      background: #f0f0f0;
      cursor: not-allowed;
    }
  }
}

.section-heading {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
  padding-bottom: 8px;
  border-bottom: 1px solid #e5e5e5;
  
  span {
    font-size: 13px;
    font-weight: 600;
    color: #333;
  }
  
  i {
    flex: 1;
    height: 1px;
    background: #e5e5e5;
  }
}

.radio-group {
  border: 1px solid #e5e5e5;
  padding: 10px 12px;
  border-radius: 4px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  
  legend {
    font-size: 13px;
    font-weight: 600;
    color: #333;
    padding: 0 4px;
  }
  
  label {
    display: flex;
    align-items: center;
    gap: 4px;
    font-size: 13px;
    color: #555;
    cursor: pointer;
    
    input[type="radio"] {
      cursor: pointer;
    }
  }
}

.calculate-button {
  width: 100%;
  height: 36px;
  background: #202020;
  color: #fff;
  border: none;
  border-radius: 4px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
  
  &:hover {
    background: #333;
  }
  
  &:active {
    transform: scale(0.98);
  }
}

.chart-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  background: #fff;
}

.chart-toolbar {
  height: 40px;
  flex: 0 0 40px;
  display: flex;
  align-items: center;
  padding: 0 20px;
  border-bottom: 1px solid #e5e5e5;
  background: #fafafa;
}

.toolbar-left {
  display: flex;
  gap: 16px;
}

.chart-type-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #555;
  cursor: pointer;
  
  input[type="radio"] {
    cursor: pointer;
  }
}

.chart-container {
  flex: 1;
  min-height: 400px;
}
</style>