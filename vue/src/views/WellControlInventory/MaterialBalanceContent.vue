<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { materialBalanceApi } from '@/api/docker'

const props = defineProps({
  node: Object,
  projectId: [Number, String],
  gasReservoirId: [Number, String]
})

const emit = defineEmits(['refresh-tree'])

const loading = ref(false)
const resultData = ref(null)
const chartEl = ref(null)
let chart = null

const resultId = computed(() =>
  props.node?.raw?.resultId ||
  props.node?.raw?.analysisId ||
  props.node?.raw?.id ||
  props.node?.raw?.nodeId ||
  props.node?.id
)

const payload = computed(() => resultData.value?.data ?? resultData.value ?? {})
const input = computed(() => payload.value?.input || payload.value?.inputs || {})
const result = computed(() => payload.value?.result || payload.value?.output || payload.value?.summary || {})
const fields = computed(() => payload.value?.fields || [])

const unitOf = (name) =>
  fields.value.find(field => field.name === name || field.field === name)?.unit_label ||
  fields.value.find(field => field.name === name || field.field === name)?.unitLabel ||
  ''

const formatValue = (value) => {
  if (value === null || typeof value === 'undefined' || value === '') return '-'
  if (typeof value === 'number') return Number.isFinite(value) ? Number(value.toFixed(6)).toString() : '-'
  return String(value)
}

const metricItems = computed(() => {
  const source = result.value || {}
  const known = [
    ['originalGasVolume', '动态储量'],
    ['gfi', '游离气动态储量'],
    ['gai', '吸附气动态储量'],
    ['gradient', '斜率'],
    ['intercept', '截距'],
    ['rsquared', 'R²'],
    ['reliablity', '可靠性']
  ]

  const picked = known
    .filter(([key]) => typeof source[key] !== 'undefined')
    .map(([key, label]) => ({
      key,
      label,
      value: source[key],
      unit: unitOf(key)
    }))

  if (picked.length) return picked

  return Object.entries(source)
    .filter(([, value]) => ['string', 'number', 'boolean'].includes(typeof value))
    .slice(0, 8)
    .map(([key, value]) => ({ key, label: key, value, unit: unitOf(key) }))
})

const inputItems = computed(() =>
  Object.entries(input.value || {})
    .filter(([, value]) => ['string', 'number', 'boolean'].includes(typeof value))
    .slice(0, 10)
    .map(([key, value]) => ({ key, value }))
)

const toPoint = (item) => {
  if (Array.isArray(item) && item.length >= 2) return [+item[0], +item[1]]
  if (!item || typeof item !== 'object') return null
  const x = item.xValue ?? item.x ?? item.gp ?? item.cumulativeGasProduction ?? item.recoveryDegree
  const y = item.yValue ?? item.y ?? item.pressure ?? item.apparentPressure ?? item.pz
  if (x === null || typeof x === 'undefined' || y === null || typeof y === 'undefined') return null
  return [+x, +y]
}

const collectSeries = () => {
  const source = payload.value || {}
  const series = []

  const chartItems = [
    ...(source.chartItems || []),
    ...((source.outputs || []).flatMap(item => item.chartItems || []))
  ]

  chartItems.forEach(item => {
    const data = (item.data || []).map(toPoint).filter(Boolean)
    if (data.length) {
      series.push({
        name: item.name || item.label || '计算曲线',
        data,
        type: item.name?.includes('拟合') ? 'line' : 'scatter'
      })
    }
  })

  const seriesData = source.seriesData || source.chartData || source.data
  if (seriesData && typeof seriesData === 'object' && !Array.isArray(seriesData)) {
    Object.entries(seriesData).forEach(([key, value]) => {
      const raw = value?.data || value
      if (!Array.isArray(raw)) return
      const data = raw.map(toPoint).filter(Boolean)
      if (data.length) {
        series.push({
          name: key === 'pressure' ? '压力' : key === 'linearRegressionPressure' ? '拟合线' : key,
          data,
          type: key.toLowerCase().includes('linear') ? 'line' : 'scatter'
        })
      }
    })
  }

  return series
}

const renderChart = () => {
  if (!chart) return

  const series = collectSeries()
  if (!series.length) {
    chart.clear()
    return
  }

  chart.setOption({
    animation: false,
    tooltip: { trigger: 'axis' },
    legend: { bottom: 4, textStyle: { fontSize: 11 } },
    grid: { left: 58, right: 24, top: 28, bottom: 58 },
    xAxis: {
      type: 'value',
      name: 'Gp',
      nameLocation: 'middle',
      nameGap: 28,
      axisLabel: { fontSize: 11, color: '#777' },
      splitLine: { lineStyle: { color: '#eeeeee' } }
    },
    yAxis: {
      type: 'value',
      name: 'P/Z',
      nameLocation: 'middle',
      nameGap: 42,
      axisLabel: { fontSize: 11, color: '#777' },
      splitLine: { lineStyle: { color: '#eeeeee' } }
    },
    series: series.map((item, index) => ({
      name: item.name,
      type: item.type,
      data: item.data,
      symbolSize: item.type === 'scatter' ? 7 : 0,
      lineStyle: { width: 2 },
      itemStyle: { color: ['#5470c6', '#2a2a2a', '#91cc75', '#ee6666'][index % 4] }
    }))
  }, true)
}

const fetchData = async () => {
  if (!props.projectId || !props.gasReservoirId || !resultId.value) return

  loading.value = true
  resultData.value = null
  try {
    const res = await materialBalanceApi.getResult(props.projectId, props.gasReservoirId, resultId.value)
    resultData.value = res.data ?? res
    await nextTick()
    renderChart()
    emit('refresh-tree')
  } catch (error) {
    console.error('[MaterialBalanceContent] 数据加载失败', error)
  } finally {
    loading.value = false
  }
}

const onResize = () => chart?.resize()

watch(() => [props.node?.id, props.node?.raw?.nodeId], fetchData, { immediate: true })

onMounted(() => {
  chart = echarts.init(chartEl.value)
  window.addEventListener('resize', onResize)
  if (resultData.value) renderChart()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  chart?.dispose()
})
</script>

<template>
  <div v-loading="loading" class="mb-wrap">
    <aside class="params-panel">
      <div class="panel-head">物质平衡</div>
      <div class="panel-body">
        <div class="sec-label">基础信息</div>
        <div class="field">
          <label>井名</label>
          <el-input size="small" readonly :model-value="node?.wellName || '-'" />
        </div>
        <div class="field">
          <label>结果编号</label>
          <el-input size="small" readonly :model-value="resultId || '-'" />
        </div>

        <div v-if="inputItems.length" class="sec-label">输入参数</div>
        <div v-for="item in inputItems" :key="item.key" class="field">
          <label>{{ item.key }}</label>
          <el-input size="small" readonly :model-value="formatValue(item.value)" />
        </div>

        <div class="sec-label">输出结果</div>
        <div v-if="!metricItems.length" class="empty-text">暂无结果字段</div>
        <div v-for="item in metricItems" :key="item.key" class="field">
          <label>{{ item.label }}{{ item.unit ? `(${item.unit})` : '' }}</label>
          <el-input size="small" readonly :model-value="formatValue(item.value)" />
        </div>
      </div>
    </aside>

    <main class="chart-area">
      <div class="chart-head">Gp-P/Z 关系曲线</div>
      <div ref="chartEl" class="chart-instance" />
    </main>
  </div>
</template>

<style lang="scss" scoped>
.mb-wrap {
  display: flex;
  height: 100%;
  background: #fff;
  overflow: hidden;
}

.params-panel {
  width: 246px;
  min-width: 246px;
  border-right: 1px solid #e0e0e0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.panel-head {
  padding: 8px 12px;
  border-bottom: 1px solid #f0f0f0;
  font-size: 13px;
  color: #333;
}

.panel-body {
  flex: 1;
  overflow-y: auto;
  padding: 6px 12px 14px;
}

.sec-label {
  font-weight: 500;
  color: #333;
  font-size: 13px;
  margin: 10px 0 7px;
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

.empty-text {
  color: #999;
  font-size: 12px;
  padding: 4px 0;
}

.chart-area {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.chart-head {
  height: 32px;
  line-height: 32px;
  padding: 0 14px;
  border-bottom: 1px solid #e4e7ed;
  background: #fafafa;
  font-size: 13px;
  color: #333;
}

.chart-instance {
  flex: 1;
  min-height: 0;
  width: 100%;
}
</style>
