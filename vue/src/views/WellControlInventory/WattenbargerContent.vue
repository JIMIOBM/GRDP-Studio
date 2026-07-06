<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { wattenbargerApi } from '@/api/docker'

const props = defineProps({
  node: Object,
  projectId: [Number, String],
  gasReservoirId: [Number, String]
})

const loading = ref(false)
const resultData = ref(null)
const activeTab = ref('chart')
const chartEl = ref(null)
let chart = null

const resultId = computed(() => {
  const raw = props.node?.raw || {}
  return raw.resultId || raw.analysisId || raw.result?.id || raw.result?.resultId || props.node?.resultId || ''
})

const input = computed(() => resultData.value?.input || {})
const result = computed(() => resultData.value?.analysis || resultData.value?.result || resultData.value?.output || {})
const fields = computed(() => resultData.value?.fields || [])
const chartItems = computed(() => resultData.value?.chartItems || resultData.value?.charts || resultData.value?.outputs?.[0]?.chartItems || [])

const resultFields = computed(() => [
  { label: '井型', value: result.value.wellType || input.value.wellType || 'vertical' },
  { label: '曲线类型', value: result.value.curveType || input.value.curveType || 'Wattenbarger' },
  { label: '渗透率', value: withUnit('permeability', result.value.permeability) },
  { label: '表皮系数', value: withUnit('skinFactor', result.value.skinFactor) },
  { label: '动态储量', value: withUnit('originalGasVolume', result.value.originalGasVolume) },
  { label: '拟合误差', value: result.value.fittingError ?? result.value.error }
].filter(item => item.value !== undefined && item.value !== null && item.value !== ''))

const inputFields = computed(() => [
  { label: '参与数据点数', value: input.value.dataSize ?? 30 },
  { label: '生产水气比下限', value: input.value.minimumWaterGasRatio ?? -1 },
  { label: '井名', value: props.node?.wellName },
  { label: '气藏 ID', value: props.gasReservoirId },
  { label: '项目 ID', value: props.projectId }
].filter(item => item.value !== undefined && item.value !== null && item.value !== ''))

const tableRows = computed(() => {
  const first = chartItems.value.find(item => Array.isArray(item?.data) && item.data.length)
  return (first?.data || []).map(row => ({
    x: row.xValue ?? row.date ?? row.x,
    y: row.yValue ?? row.value ?? row.y,
    name: first.name || first.yAxisField || first.fieldName || '曲线'
  }))
})

function withUnit(name, value) {
  if (value === undefined || value === null || value === '') return ''
  const unit = fields.value.find(item => item.name === name)?.unit_label
  return unit ? `${value} ${unit}` : value
}

function normalizePayload(res) {
  return res?.data?.data ?? res?.data ?? res
}

function getPoint(row) {
  const x = row.xValue ?? row.date ?? row.x
  const y = row.yValue ?? row.value ?? row.y
  return [x, Number(y)]
}

function renderChart() {
  if (!chart) return
  const items = chartItems.value.filter(item => Array.isArray(item?.data) && item.data.length)
  if (!items.length) {
    chart.clear()
    return
  }

  const firstX = items[0].data[0]?.xValue ?? items[0].data[0]?.date ?? items[0].data[0]?.x
  const isDateAxis = Number.isNaN(Number(firstX))
  const categories = isDateAxis
    ? items[0].data.map(row => String(row.xValue ?? row.date ?? row.x).slice(0, 10))
    : null

  chart.setOption({
    animation: false,
    tooltip: { trigger: 'axis' },
    legend: { type: 'scroll', top: 8, left: 12, right: 12 },
    grid: { left: 64, right: 34, top: 54, bottom: 54 },
    xAxis: {
      type: isDateAxis ? 'category' : 'value',
      data: categories,
      name: isDateAxis ? '日期' : 'X',
      nameLocation: 'middle',
      nameGap: 30,
      boundaryGap: false,
      splitLine: { show: true, lineStyle: { color: '#e7edf5' } }
    },
    yAxis: {
      type: 'value',
      name: 'Y',
      nameLocation: 'middle',
      nameGap: 44,
      splitLine: { lineStyle: { color: '#e7edf5' } }
    },
    series: items.map(item => ({
      name: item.name || item.yAxisField || item.fieldName || '曲线',
      type: item.type || 'line',
      showSymbol: item.type === 'scatter',
      smooth: item.type !== 'scatter',
      data: isDateAxis
        ? item.data.map(row => Number(row.yValue ?? row.value ?? row.y))
        : item.data.map(getPoint)
    }))
  }, true)
}

async function fetchData() {
  if (!resultId.value || !props.projectId || !props.gasReservoirId) {
    resultData.value = null
    await nextTick()
    renderChart()
    return
  }

  loading.value = true
  try {
    const res = await wattenbargerApi.getResult(props.projectId, props.gasReservoirId, resultId.value)
    resultData.value = normalizePayload(res)
    activeTab.value = 'chart'
    await nextTick()
    renderChart()
  } finally {
    loading.value = false
  }
}

watch(() => resultId.value, fetchData, { immediate: true })
watch(chartItems, () => nextTick(renderChart), { deep: true })
watch(activeTab, () => nextTick(renderChart))

onMounted(() => {
  chart = echarts.init(chartEl.value)
  window.addEventListener('resize', renderChart)
  renderChart()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', renderChart)
  chart?.dispose()
})
</script>

<template>
  <div v-loading="loading" class="wb-wrap">
    <aside class="wb-panel">
      <div class="panel-title">Wattenbarger</div>

      <div v-if="resultFields.length" class="section">
        <div class="section-title">结果参数</div>
        <div v-for="item in resultFields" :key="item.label" class="field">
          <label>{{ item.label }}</label>
          <el-input size="small" readonly :model-value="item.value" />
        </div>
      </div>

      <div v-if="inputFields.length" class="section">
        <div class="section-title">输入参数</div>
        <div v-for="item in inputFields" :key="item.label" class="field">
          <label>{{ item.label }}</label>
          <el-input size="small" readonly :model-value="item.value" />
        </div>
      </div>
    </aside>

    <main class="wb-main">
      <div class="tabs">
        <button :class="{ active: activeTab === 'chart' }" @click="activeTab = 'chart'">曲线图</button>
        <button :class="{ active: activeTab === 'table' }" @click="activeTab = 'table'">数据列表</button>
      </div>

      <div v-show="activeTab === 'chart'" ref="chartEl" class="chart"></div>

      <div v-if="activeTab === 'table'" class="table-wrap">
        <el-table :data="tableRows" size="small" height="100%" border>
          <el-table-column prop="x" label="X" min-width="160" />
          <el-table-column prop="y" label="Y" min-width="120" />
          <el-table-column prop="name" label="曲线" min-width="180" />
        </el-table>
      </div>
    </main>
  </div>
</template>

<style lang="scss" scoped>
.wb-wrap {
  display: flex;
  height: 100%;
  min-height: 0;
  background: #fff;
}

.wb-panel {
  width: 280px;
  min-width: 280px;
  border-right: 1px solid #e0e0e0;
  overflow-y: auto;
  padding: 10px 12px 16px;
}

.panel-title {
  font-size: 15px;
  font-weight: 600;
  color: #222;
  margin-bottom: 12px;
}

.section {
  margin-bottom: 16px;
}

.section-title {
  font-size: 14px;
  color: #333;
  margin-bottom: 8px;
}

.field {
  margin-bottom: 9px;

  label {
    display: block;
    font-size: 12px;
    color: #666;
    margin-bottom: 3px;
  }
}

.wb-main {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.tabs {
  height: 33px;
  display: flex;
  border-bottom: 1px solid #e4e7ed;
  background: #fafafa;

  button {
    border: 0;
    border-right: 1px solid #e4e7ed;
    background: transparent;
    padding: 0 18px;
    color: #555;
    cursor: pointer;

    &.active {
      background: #fff;
      color: #409eff;
      font-weight: 600;
      border-bottom: 2px solid #409eff;
    }
  }
}

.chart {
  flex: 1;
  min-height: 0;
  width: 100%;
}

.table-wrap {
  flex: 1;
  min-height: 0;
  padding: 10px;
}
</style>
