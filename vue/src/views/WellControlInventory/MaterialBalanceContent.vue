<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { materialBalanceApi } from '@/api/docker'

const props = defineProps({
  node: Object,
  projectId: [Number, String],
  gasReservoirId: [Number, String]
})

const DEFAULT_INPUT = {
  gasType: '干气',
  specificGravity: 0.58,
  hydrogenSulfide: 4.62,
  carbonDioxide: 3.96,
  nitrogen: 0,
  modificationMethod: 'Wichert-Aziz 修正方法',
  deviationFactorMethod: 'Dranchuk-Abu-Kassem 方法',
  viscosityMethod: 'Lee-Gonzalez-Eakin 方法',
  originalFormationPressure: 50,
  formationTemperature: 80,
  waterGasRatioLimit: 0.0602
}

const loading = ref(false)
const resultData = ref(null)
const activePanelTab = ref('input')
const activeChartTab = ref(0)
const chartEl = ref(null)
let chart = null

const resultId = computed(() => {
  const raw = props.node?.raw || {}
  return raw.resultId || raw.analysisId || raw.result?.id || raw.result?.resultId || props.node?.resultId || ''
})

const input = computed(() => ({
  ...DEFAULT_INPUT,
  ...(resultData.value?.input || {})
}))

const output = computed(() => {
  const activeOutput = resultData.value?.outputs?.[activeChartTab.value]?.output
  return activeOutput || resultData.value?.output || resultData.value?.result || resultData.value?.analysis || {}
})

const chartItems = computed(() => {
  const outputs = resultData.value?.outputs
  if (Array.isArray(outputs) && outputs.length) {
    return outputs.map((item, index) => ({
      label: item.name || item.title || item.label || `图表 ${index + 1}`,
      chartItems: item.chartItems || item.charts || []
    }))
  }

  const items = resultData.value?.chartItems || resultData.value?.charts || []
  return items.length ? [{ label: '分析结果', chartItems: items }] : []
})

const activeChartItems = computed(() => chartItems.value[activeChartTab.value]?.chartItems || [])

const outputFields = computed(() => {
  const value = output.value || {}
  return Object.entries(value)
    .filter(([, fieldValue]) => fieldValue !== undefined && fieldValue !== null && fieldValue !== '')
    .map(([key, fieldValue]) => ({ key, label: key, value: fieldValue }))
})

const tableRows = computed(() => {
  const first = activeChartItems.value.find(item => Array.isArray(item?.data) && item.data.length)
  return (first?.data || []).map(row => ({
    x: row.xValue ?? row.date ?? row.x,
    y: row.yValue ?? row.value ?? row.y,
    name: first.name || first.yAxisField || first.fieldName || '曲线'
  }))
})

function getPoint(row) {
  const x = row.xValue ?? row.date ?? row.x
  const y = row.yValue ?? row.value ?? row.y
  return [x, Number(y)]
}

function normalizePayload(res) {
  return res?.data?.data ?? res?.data ?? res
}

function renderChart() {
  if (!chart) return
  const items = activeChartItems.value.filter(item => Array.isArray(item?.data) && item.data.length)
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
    const res = await materialBalanceApi.getResult(props.projectId, props.gasReservoirId, resultId.value)
    resultData.value = normalizePayload(res)
    activeChartTab.value = 0
    activePanelTab.value = 'input'
    await nextTick()
    renderChart()
  } finally {
    loading.value = false
  }
}

watch(() => resultId.value, fetchData, { immediate: true })
watch(activeChartTab, () => nextTick(renderChart))
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
  <div v-loading="loading" class="mb-wrap">
    <aside class="params-panel">
      <div class="panel-head">参数设置</div>

      <div v-show="activePanelTab === 'input'" class="panel-body">
        <div class="section-title">气体性质</div>
        <div class="field">
          <label>天然气类型</label>
          <el-select size="small" disabled :model-value="input.gasType" style="width: 100%">
            <el-option label="干气" value="干气" />
          </el-select>
        </div>
        <div class="field">
          <label>天然气比重(dless)</label>
          <el-input size="small" readonly :model-value="input.specificGravity" />
        </div>
        <div class="field">
          <label>H₂S摩尔百分含量(%)</label>
          <el-input size="small" readonly :model-value="input.hydrogenSulfide" />
        </div>
        <div class="field">
          <label>CO₂摩尔百分含量(%)</label>
          <el-input size="small" readonly :model-value="input.carbonDioxide" />
        </div>
        <div class="field">
          <label>N₂摩尔百分含量(%)</label>
          <el-input size="small" readonly :model-value="input.nitrogen" />
        </div>

        <div class="section-title">计算方法</div>
        <div class="field">
          <label>非烃气体修正方法</label>
          <el-select size="small" disabled :model-value="input.modificationMethod" style="width: 100%">
            <el-option label="Wichert-Aziz 修正方法" value="Wichert-Aziz 修正方法" />
          </el-select>
        </div>
        <div class="field">
          <label>天然气偏差系数计算方法</label>
          <el-select size="small" disabled :model-value="input.deviationFactorMethod" style="width: 100%">
            <el-option label="Dranchuk-Abu-Kassem 方法" value="Dranchuk-Abu-Kassem 方法" />
          </el-select>
        </div>
        <div class="field">
          <label>天然气粘度计算方法</label>
          <el-select size="small" disabled :model-value="input.viscosityMethod" style="width: 100%">
            <el-option label="Lee-Gonzalez-Eakin 方法" value="Lee-Gonzalez-Eakin 方法" />
          </el-select>
        </div>

        <div class="section-title">其它数据</div>
        <div class="field">
          <label>原始地层压力(MPa)</label>
          <el-input size="small" readonly :model-value="input.originalFormationPressure" />
        </div>
        <div class="field">
          <label>气井地层温度(°C)</label>
          <el-input size="small" readonly :model-value="input.formationTemperature" />
        </div>
        <div class="field">
          <label>生产水气比上限(m³/10⁴m³)</label>
          <el-input size="small" readonly :model-value="input.waterGasRatioLimit" />
        </div>
      </div>

      <div v-show="activePanelTab === 'output'" class="panel-body">
        <div class="section-title">输出结果</div>
        <div v-if="!outputFields.length" class="empty">暂无输出结果</div>
        <div v-for="item in outputFields" :key="item.key" class="field">
          <label>{{ item.label }}</label>
          <el-input size="small" readonly :model-value="item.value" />
        </div>
      </div>

      <div class="panel-tabs">
        <button :class="{ active: activePanelTab === 'input' }" @click="activePanelTab = 'input'">输入</button>
        <button :class="{ active: activePanelTab === 'output' }" @click="activePanelTab = 'output'">输出</button>
      </div>
    </aside>

    <main class="chart-area">
      <div v-if="chartItems.length" class="chart-tabs">
        <button
          v-for="(tab, index) in chartItems"
          :key="tab.label"
          :class="{ active: activeChartTab === index }"
          @click="activeChartTab = index"
        >
          {{ tab.label }}
        </button>
      </div>
      <div ref="chartEl" class="chart"></div>
      <div v-if="tableRows.length" class="table-wrap">
        <el-table :data="tableRows" size="small" height="160" border>
          <el-table-column prop="x" label="X" min-width="160" />
          <el-table-column prop="y" label="Y" min-width="120" />
          <el-table-column prop="name" label="曲线" min-width="180" />
        </el-table>
      </div>
    </main>
  </div>
</template>

<style lang="scss" scoped>
.mb-wrap {
  display: flex;
  height: 100%;
  min-height: 0;
  background: #fff;
}

.params-panel {
  width: 315px;
  min-width: 315px;
  display: flex;
  flex-direction: column;
  border-right: 1px solid #e0e0e0;
  overflow: hidden;
}

.panel-head {
  height: 44px;
  line-height: 44px;
  padding: 0 18px;
  border-bottom: 1px solid #eeeeee;
  font-size: 15px;
  color: #222;
}

.panel-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 12px 18px 18px;
}

.section-title {
  font-size: 15px;
  color: #333;
  margin: 8px 0 10px;
}

.field {
  margin-bottom: 12px;

  label {
    display: block;
    margin-bottom: 5px;
    color: #555;
    font-size: 14px;
  }
}

.empty {
  color: #999;
  font-size: 13px;
  padding: 8px 0;
}

.panel-tabs {
  height: 43px;
  display: flex;
  border-top: 1px solid #e0e0e0;

  button {
    flex: 1;
    border: 0;
    border-right: 1px solid #e0e0e0;
    background: #fff;
    color: #333;
    font-size: 15px;
    cursor: pointer;

    &:last-child {
      border-right: 0;
    }

    &.active {
      background: #f4d000;
      color: #111;
      font-weight: 600;
    }
  }
}

.chart-area {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.chart-tabs {
  height: 34px;
  display: flex;
  border-bottom: 1px solid #e4e7ed;
  background: #fafafa;

  button {
    border: 0;
    border-right: 1px solid #e4e7ed;
    background: transparent;
    padding: 0 16px;
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
  min-height: 260px;
  width: 100%;
}

.table-wrap {
  height: 180px;
  padding: 10px;
  border-top: 1px solid #eeeeee;
}
</style>
