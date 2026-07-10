<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({
  node: Object,
  projectId: [Number, String],
  gasReservoirId: [Number, String]
})

const emit = defineEmits(['refresh-tree'])

const loading = ref(false)
const chartEl = ref(null)

let chart = null

const currentWellName = computed(() =>
  props.node?.wellName ||
  props.node?.label ||
  ''
)

const wellDataMap = {
  'X-1': {
    data: [
      [600, 0.32], [630, 0.34], [660, 0.36], [690, 0.38], [720, 0.40],
      [750, 0.42], [780, 0.43], [810, 0.44], [840, 0.46], [870, 0.45],
      [900, 0.48], [930, 0.44], [960, 0.47], [990, 0.42], [1020, 0.49],
      [1050, 0.45], [1080, 0.47], [1110, 0.43], [1140, 0.48], [1170, 0.46],
      [1200, 0.44], [1450, 0.85]
    ],
    result: {
      dynamicReserves: 29.2298,
      intercept: '1.5670E-1',
      slope: '3.1330E-4',
      rsquared: 0.6323,
      reliability: '分析结果可靠性偏低'
    }
  },
  'X-4': {
    data: [
      [360, 0.34], [390, 0.36], [420, 0.38], [450, 0.41], [480, 0.43],
      [510, 0.45], [540, 0.48], [570, 0.51], [600, 0.46], [630, 0.49],
      [660, 0.53], [690, 0.56], [720, 0.50], [750, 0.55], [780, 0.62],
      [810, 0.58], [840, 0.64], [870, 0.60], [900, 0.63], [930, 0.59],
      [960, 0.65], [990, 0.61], [1020, 0.66], [1050, 0.62], [1080, 0.64],
      [1110, 0.60], [1140, 0.63], [1170, 0.61], [1200, 0.65],[1350, 0.72], [1500, 0.90], [1700, 1.14]
    ],
    result: {
      dynamicReserves: 19.8548,
      intercept: '1.1150E-1',
      slope: '4.6424E-4',
      rsquared: 0.8844,
      reliability: '分析结果可靠性较高'
    }
  },
  'X-2': {
    data: [
      // [200, 0.5], [300, 0.45], [400, 0.52], [500, 0.48], [600, 0.55],
      // [700, 0.58], [800, 0.62], [900, 0.65], [1000, 0.7], [1100, 0.72],
      // [1200, 0.75], [1300, 0.8], [1400, 0.82], [1500, 0.85], [1600, 0.88],
      // [1700, 0.9], [1800, 0.92], [1900, 0.95], [2000, 0.98], [2100, 1.0],
      // [2200, 1.02], [2300, 1.05], [2400, 1.08], [2500, 1.1]
    ],
    result: {
      dynamicReserves: 25.6780,
      intercept: '1.3200E-1',
      slope: '3.8500E-4',
      rsquared: 0.9250,
      reliability: '分析结果可靠性较高'
    }
  },
  'X-3': {
    data: [
      // [200, 0.38], [300, 0.35], [400, 0.4], [500, 0.42], [600, 0.45],
      // [700, 0.48], [800, 0.52], [900, 0.55], [1000, 0.58], [1100, 0.62],
      // [1200, 0.65], [1300, 0.68], [1400, 0.72], [1500, 0.75], [1600, 0.78],
      // [1700, 0.8], [1800, 0.82], [1900, 0.85], [2000, 0.88], [2100, 0.9]
    ],
    result: {
      dynamicReserves: 22.4560,
      intercept: '1.2500E-1',
      slope: '3.6800E-4',
      rsquared: 0.8960,
      reliability: '分析结果可靠性较高'
    }
  },
  'X-5': {
    data: [
      [300, 0.37], [330, 0.38], [360, 0.39], [390, 0.40], [420, 0.41],
      [450, 0.39], [480, 0.42], [510, 0.43], [540, 0.41], [570, 0.44],
      [600, 0.42], [630, 0.45], [660, 0.46], [690, 0.44], [720, 0.47],
      [750, 0.48], [780, 0.46], [810, 0.49], [840, 0.50], [870, 0.48],
      [900, 0.51], [930, 0.52], [960, 0.50], [990, 0.53], [1020, 0.54],
      [1050, 0.52], [1080, 0.55], [1110, 0.56], [1140, 0.54], [1170, 0.57],
      [1200, 0.58], [1230, 0.56], [1260, 0.60], [1290, 0.61], [1320, 0.59],
      [1350, 0.62], [1380, 0.63], [1410, 0.61], [1440, 0.64], [1470, 0.65],
      [1500, 0.63], [1530, 0.66], [1560, 0.67], [1590, 0.65], [1620, 0.71],
      [1680, 0.68], [1740, 0.70], [1800, 0.73], [1860, 0.75], [1920, 0.77],
      [1980, 0.86], [2040, 0.87], [2400, 0.99], [2640, 1.11], [2940, 1.21],
      [3000, 1.22], [3120, 1.27]
    ],
    result: {
      dynamicReserves: 33.0492,
      intercept: '2.3200E-1',
      slope: '2.7744E-4',
      rsquared: 0.8873,
      reliability: '分析结果可靠性较高'
    }
  }
}

const currentWellData = computed(() => {
  const wellName = currentWellName.value
  return wellDataMap[wellName] || wellDataMap['X-1']
})

const output = computed(() => currentWellData.value.result)

const chartPoints = computed(() => currentWellData.value.data)

const regression = computed(() => {
  const result = output.value
  const slope = parseFloat(result.slope) || 0.0003133
  const intercept = parseFloat(result.intercept) || 0.1567
  const r2 = parseFloat(result.rsquared) || 0.6323
  return { slope, intercept, r2 }
})

const chartXRange = computed(() => ({ xMin: 0, xMax: 3200 }))
const chartYRange = computed(() => ({ yMin: 0.2, yMax: 1.2 }))

const renderChart = () => {
  if (!chart) return
  
  const { slope, intercept } = regression.value
  const { xMin, xMax } = chartXRange.value
  
  const series = [
    {
      name: '数据点(MPa/(10⁴m³/d))',
      type: 'scatter',
      data: chartPoints.value,
      symbolSize: 6,
      itemStyle: { 
        color: '#3b82f6', 
        opacity: 0.8 
      }
    },
    {
      name: '回归线(MPa/(10⁴m³/d))',
      type: 'line',
      data: [
        [xMin, slope * xMin + intercept],
        [xMax, slope * xMax + intercept]
      ],
      symbol: 'none',
      lineStyle: { color: '#1f2937', width: 2, type: 'solid' },
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
      left: 80,
      right: 60,
      top: 80,
      bottom: 60
    },
    xAxis: {
      type: 'value',
      name: '拟时间',
      min: chartXRange.value.xMin,
      max: chartXRange.value.xMax,
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
      min: chartYRange.value.yMin,
      max: chartYRange.value.yMax,
      nameLocation: 'middle',
      nameGap: 60,
      minorTick: { show: true },
      minorSplitLine: { show: true, lineStyle: { color: '#eee' } },
      splitLine: { lineStyle: { color: '#ddd' } },
      axisLabel: { fontSize: 11 }
    },
    series
  }, true)
}

const fetchData = async () => {
  const wellName = currentWellName.value
  if (!wellName) return

  loading.value = true
  try {
    await nextTick()
    renderChart()
    emit('refresh-tree')
  } catch (e) {
    console.error('[DynamicBalanceContent] 数据加载失败', e)
    await nextTick()
    renderChart()
  } finally {
    loading.value = false
  }
}

watch(() => props.node?.wellName, fetchData, { immediate: true })

onMounted(() => {
  chart = echarts.init(chartEl.value)
  window.addEventListener('resize', () => chart?.resize())
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', () => chart?.resize())
  chart?.dispose()
})
</script>

<template>
  <div v-loading="loading" class="db-wrap">
    <div class="chart-area">
      <div ref="chartEl" class="chart-instance" />
    </div>
    
    <div class="params-panel">
      <div class="panel-head">
        <span>输出结果</span>
      </div>
      <div class="panel-body">
        <div class="field">
          <label>动态储量(10⁸m³)</label>
          <el-input size="small" readonly :model-value="output.dynamicReserves" />
        </div>
        <div class="field">
          <label>回归分析截距(1/(10⁴m³/d))</label>
          <el-input size="small" readonly :model-value="output.intercept" />
        </div>
        <div class="field">
          <label>回归分析斜率(MPa/(10⁴m³/d)/d)</label>
          <el-input size="small" readonly :model-value="output.slope" />
        </div>
        <div class="field">
          <label>R²(dless)</label>
          <el-input size="small" readonly :model-value="output.rsquared" />
        </div>
        <div class="field">
          <label>结果可靠性</label>
          <el-input size="small" readonly :model-value="output.reliability" />
        </div>
      </div>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.db-wrap {
  display: flex;
  height: 100%;
  background: #fff;
  overflow: hidden;
}

.chart-area {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-right: 1px solid #e0e0e0;
}

.chart-instance {
  flex: 1;
  min-height: 0;
  width: 100%;
}

.params-panel {
  width: 280px;
  min-width: 280px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
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

.panel-body {
  flex: 1;
  overflow-y: auto;
  padding: 10px 12px;
}

.field {
  margin-bottom: 12px;
  label {
    display: block;
    color: #555;
    font-size: 12px;
    margin-bottom: 4px;
  }
}
</style>