<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { analyticMethodApi } from '@/api/docker'

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
  return raw.resultId || raw.analysisId || raw.nodeId || props.node?.resultId || props.node?.id
})

const input = computed(() => resultData.value?.input || {})
const analysis = computed(() => resultData.value?.analysis || resultData.value?.result || {})
const fields = computed(() => resultData.value?.fields || [])
const chartItems = computed(() => resultData.value?.chartItems || resultData.value?.charts || [])

const tableRows = computed(() => {
  const item = chartItems.value.find(item => Array.isArray(item?.data) && item.data.length)
  return (item?.data || []).map(row => ({
    x: row.xValue ?? row.date ?? row.x,
    y: row.yValue ?? row.value ?? row.y,
    name: item.name || item.yAxisField || item.fieldName || ''
  }))
})

const summaryFields = computed(() => [
  { label: '井型', value: analysis.value.wellType },
  { label: '是否压裂', value: analysis.value.isFractured },
  { label: '渗透率', value: withUnit('permeability', analysis.value.permeability) },
  { label: '表皮系数', value: withUnit('skinFactor', analysis.value.skinFactor) },
  { label: '裂缝半长', value: withUnit('fractureHalfLength', analysis.value.fractureHalfLength) },
  { label: '裂缝渗透率', value: withUnit('fracturePermeability', analysis.value.fracturePermeability) }
].filter(item => item.value !== undefined && item.value !== null && item.value !== ''))

const inputFields = computed(() => [
  { label: '天然气类型', value: input.value.gasType },
  { label: '天然气比重', value: withUnit('specificGravity', input.value.specificGravity) },
  { label: '原始地层压力', value: withUnit('originalFormationPressure', input.value.originalFormationPressure) },
  { label: '地层温度', value: withUnit('formationTemperature', input.value.formationTemperature) },
  { label: '井筒半径', value: withUnit('wellboreRadius', input.value.wellboreRadius) },
  { label: '动态地质储量', value: withUnit('originalGasVolume', input.value.originalGasVolume) },
  { label: '生产水气比上限', value: withUnit('waterGasRatioLimit', input.value.waterGasRatioLimit) }
].filter(item => item.value !== undefined && item.value !== null && item.value !== ''))

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
    legend: {
      type: 'scroll',
      top: 8,
      left: 12,
      right: 12
    },
    grid: {
      left: 64,
      right: 34,
      top: 54,
      bottom: 54
    },
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
      type: 'line',
      showSymbol: false,
      smooth: true,
      data: isDateAxis
        ? item.data.map(row => Number(row.yValue ?? row.value ?? row.y))
        : item.data.map(getPoint)
    }))
  }, true)
}

async function fetchData() {
  if (!resultId.value || !props.projectId || !props.gasReservoirId) return
  loading.value = true
  resultData.value = null
  try {
    const res = await analyticMethodApi.getResult(props.projectId, props.gasReservoirId, resultId.value)
    resultData.value = normalizePayload(res)
    await nextTick()
    renderChart()
  } finally {
    loading.value = false
  }
}

watch(() => resultId.value, fetchData, { immediate: true })
watch(chartItems, () => nextTick(renderChart), { deep: true })

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
  <div v-loading="loading" class="analytic-wrap">
    <aside class="analytic-panel">
      <div class="panel-title">解析法结果</div>

      <div v-if="summaryFields.length" class="section">
        <div class="section-title">结果参数</div>
        <div v-for="item in summaryFields" :key="item.label" class="field">
          <span>{{ item.label }}</span>
          <el-input size="small" readonly :model-value="item.value" />
        </div>
      </div>

      <div v-if="inputFields.length" class="section">
        <div class="section-title">输入参数</div>
        <div v-for="item in inputFields" :key="item.label" class="field">
          <span>{{ item.label }}</span>
          <el-input size="small" readonly :model-value="item.value" />
        </div>
      </div>
    </aside>

    <main class="analytic-main">
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
.analytic-wrap {
  display: flex;
  height: 100%;
  min-height: 0;
  background: #fff;
}

.analytic-panel {
  width: 260px;
  min-width: 260px;
  border-right: 1px solid #e0e0e0;
  overflow-y: auto;
  padding: 10px 12px 16px;
}

.panel-title {
  font-size: 14px;
  font-weight: 600;
  color: #222;
  margin-bottom: 12px;
}

.section {
  margin-bottom: 16px;
}

.section-title {
  font-size: 13px;
  color: #333;
  margin-bottom: 8px;
}

.field {
  margin-bottom: 8px;

  span {
    display: block;
    font-size: 12px;
    color: #666;
    margin-bottom: 3px;
  }
}

.analytic-main {
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
