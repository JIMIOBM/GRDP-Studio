<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { typicalCurveApi } from '@/api/docker'

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

const formatValue = (value) => {
  if (value === null || typeof value === 'undefined' || value === '') return '-'
  if (typeof value === 'number') return Number.isFinite(value) ? Number(value.toFixed(6)).toString() : '-'
  if (typeof value === 'boolean') return value ? '是' : '否'
  return String(value)
}

const inputItems = computed(() => {
  const preferred = [
    ['fittingType', '拟合类型'],
    ['isSkipFitting', '跳过拟合'],
    ['dataSize', '数据规模'],
    ['initScanDataSize', '初始扫描点数'],
    ['fineScanDataSize', '精细扫描点数'],
    ['minimumWaterGasRatio', '最小水气比']
  ]
  const source = input.value || {}
  const picked = preferred
    .filter(([key]) => typeof source[key] !== 'undefined')
    .map(([key, label]) => ({ key, label, value: source[key] }))

  if (picked.length) return picked

  return Object.entries(source)
    .filter(([, value]) => ['string', 'number', 'boolean'].includes(typeof value))
    .slice(0, 10)
    .map(([key, value]) => ({ key, label: key, value }))
})

const resultItems = computed(() =>
  Object.entries(result.value || {})
    .filter(([, value]) => ['string', 'number', 'boolean'].includes(typeof value))
    .slice(0, 10)
    .map(([key, value]) => ({ key, value }))
)

const toPoint = (item) => {
  if (Array.isArray(item) && item.length >= 2) return [+item[0], +item[1]]
  if (!item || typeof item !== 'object') return null
  const x = item.xValue ?? item.x ?? item.tcaDd ?? item.time ?? item.materialBalanceTime
  const y = item.yValue ?? item.y ?? item.value ?? item.qDd ?? item.der ?? item.deri
  if (x === null || typeof x === 'undefined' || y === null || typeof y === 'undefined') return null
  const px = +x
  const py = +y
  if (!Number.isFinite(px) || !Number.isFinite(py) || px <= 0 || py <= 0) return null
  return [px, py]
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
        name: item.name || item.label || '曲线',
        data
      })
    }
  })

  const seriesData = source.seriesData || source.chartData || source.data
  if (seriesData && typeof seriesData === 'object' && !Array.isArray(seriesData)) {
    const xValues = seriesData.tcaDd?.data || seriesData.tcaDd
    Object.entries(seriesData).forEach(([key, value]) => {
      if (key === 'tcaDd') return
      const raw = value?.data || value
      if (!Array.isArray(raw)) return

      let data = raw.map(toPoint).filter(Boolean)
      if (!data.length && Array.isArray(xValues)) {
        data = raw
          .map((y, index) => [Number(xValues[index]), Number(y)])
          .filter(([x, y]) => Number.isFinite(x) && Number.isFinite(y) && x > 0 && y > 0)
      }

      if (data.length) {
        series.push({ name: key, data })
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
    tooltip: {
      trigger: 'axis',
      formatter: params => params
        .map(item => `${item.marker}${item.seriesName}: ${formatValue(item.data?.[1])}`)
        .join('<br/>')
    },
    legend: { bottom: 4, type: 'scroll', textStyle: { fontSize: 11 } },
    grid: { left: 62, right: 24, top: 28, bottom: 64 },
    xAxis: {
      type: 'log',
      name: 'tcaDA',
      nameLocation: 'middle',
      nameGap: 30,
      minorSplitLine: { show: true },
      axisLabel: { fontSize: 11, color: '#777' },
      splitLine: { lineStyle: { color: '#eeeeee' } }
    },
    yAxis: {
      type: 'log',
      name: 'qDd / DER',
      nameLocation: 'middle',
      nameGap: 44,
      minorSplitLine: { show: true },
      axisLabel: { fontSize: 11, color: '#777' },
      splitLine: { lineStyle: { color: '#eeeeee' } }
    },
    series: series.slice(0, 12).map((item, index) => ({
      name: item.name,
      type: 'line',
      data: item.data,
      showSymbol: false,
      lineStyle: { width: index < 3 ? 2 : 1.2 },
      emphasis: { focus: 'series' }
    }))
  }, true)
}

const fetchData = async () => {
  if (!props.projectId || !props.gasReservoirId || !resultId.value) return

  loading.value = true
  resultData.value = null
  try {
    const res = await typicalCurveApi.getResult(props.projectId, props.gasReservoirId, resultId.value)
    resultData.value = res.data ?? res
    await nextTick()
    renderChart()
    emit('refresh-tree')
  } catch (error) {
    console.error('[WattenbargerContent] 数据加载失败', error)
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
  <div v-loading="loading" class="wb-wrap">
    <aside class="params-panel">
      <div class="panel-head">Wattenbarger</div>
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

        <div class="sec-label">拟合参数</div>
        <div v-if="!inputItems.length" class="empty-text">暂无参数字段</div>
        <div v-for="item in inputItems" :key="item.key" class="field">
          <label>{{ item.label }}</label>
          <el-input size="small" readonly :model-value="formatValue(item.value)" />
        </div>

        <div v-if="resultItems.length" class="sec-label">输出结果</div>
        <div v-for="item in resultItems" :key="item.key" class="field">
          <label>{{ item.key }}</label>
          <el-input size="small" readonly :model-value="formatValue(item.value)" />
        </div>
      </div>
    </aside>

    <main class="chart-area">
      <div class="chart-head">Wattenbarger 典型曲线</div>
      <div ref="chartEl" class="chart-instance" />
    </main>
  </div>
</template>

<style lang="scss" scoped>
.wb-wrap {
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
