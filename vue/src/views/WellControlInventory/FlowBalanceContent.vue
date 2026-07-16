<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({
  node: Object,
  projectId: [Number, String],
  gasReservoirId: [Number, String]
})

const chartElement = ref(null)
let chart = null
let resizeObserver = null

const paramsCollapsed = ref(false)
const paramsPanelWidth = ref(238)
const resizingParamsPanel = ref(false)
const activePanelTab = ref('input')
const activeChartTab = ref(0)
const paramsPanelEl = ref(null)
const chartAreaEl = ref(null)

const raw = computed(() => {
  const node = props.node || {}
  if (node.raw && (node.raw.input || node.raw.result || node.raw.data)) {
    return node.raw
  }
  return node
})

const input = computed(() => raw.value?.input || {})
const result = computed(() => raw.value?.result || {})
const data = computed(() => Array.isArray(raw.value?.data) ? raw.value.data : [])
const validRuntimeResult = computed(() => Boolean(
  raw.value?.resultId &&
  Object.keys(input.value).length && Object.keys(result.value).length && data.value.length
))

const title = computed(() => Number(raw.value?.nodeType) === 57
  ? '流动平衡—井口流压'
  : Number(raw.value?.nodeType) === 58
    ? '流动平衡—计算井底流压'
    : '流动平衡')

const pressureSourceLabel = computed(() => Number(raw.value?.nodeType) === 57
  ? '井口油管压力'
  : Number(raw.value?.nodeType) === 58
    ? '计算井底流压'
    : '—')

const chartTabs = computed(() => [
  { label: title.value }
])

const usedPoints = computed(() => data.value.filter(point => !point.isDeleted))
const regressionLine = computed(() => {
  const slope = Number(result.value?.regression?.slope)
  const intercept = Number(result.value?.regression?.intercept)
  const xs = usedPoints.value.map(point => Number(point.pseudotime)).filter(Number.isFinite)
  if (!Number.isFinite(slope) || !Number.isFinite(intercept) || xs.length < 2) return []
  const min = Math.min(...xs)
  const max = Math.max(...xs)
  return [[min, slope * min + intercept], [max, slope * max + intercept]]
})

const tableRows = computed(() => {
  return data.value.map((point, index) => ({
    index: index + 1,
    pseudotime: point.pseudotime,
    pressure: point.pressure,
    selected: point.isDeleted ? '否' : '是'
  }))
})

const format = (value, digits = 6) => {
  const number = Number(value)
  return Number.isFinite(number) ? number.toLocaleString('zh-CN', { maximumFractionDigits: digits }) : '—'
}

const renderChart = async () => {
  await nextTick()
  if (!chartElement.value || !validRuntimeResult.value) return
  if (!chart) chart = echarts.init(chartElement.value)
  chart.clear()
  chart.setOption({
    animation: false,
    tooltip: {
      trigger: 'item',
      formatter: params => {
        const value = Array.isArray(params.value) ? params.value : params.value?.value
        if (!Array.isArray(value)) return params.seriesName
        return `${params.marker}${params.seriesName}<br/>累计产气量: ${Number(value[0]).toFixed(3)}<br/>Pflow/Z: ${Number(value[1]).toFixed(3)} MPa`
      }
    },
    legend: {
      data: ['数据点', '回归线'],
      top: 0,
      left: 'center'
    },
    grid: { left: 70, right: 28, top: 40, bottom: 58, containLabel: false },
    xAxis: {
      type: 'value',
      name: '累计产气量',
      nameLocation: 'middle',
      nameGap: 34,
      scale: true,
      axisLine: { lineStyle: { color: '#444' } },
      axisTick: { show: true },
      minorTick: { show: true, splitNumber: 5 },
      splitLine: { show: true, lineStyle: { color: '#dfe7f2' } },
      minorSplitLine: { show: true, lineStyle: { color: '#f0f4fa' } },
      nameTextStyle: { color: '#333', fontSize: 14 }
    },
    yAxis: {
      type: 'value',
      name: 'Pflow / Z（MPa）',
      nameLocation: 'middle',
      nameGap: 48,
      scale: true,
      axisLine: { lineStyle: { color: '#444' } },
      axisTick: { show: true },
      minorTick: { show: true, splitNumber: 5 },
      splitLine: { show: true, lineStyle: { color: '#dfe7f2' } },
      minorSplitLine: { show: true, lineStyle: { color: '#f0f4fa' } },
      nameTextStyle: { color: '#333', fontSize: 14 }
    },
    series: [
      {
        name: '数据点',
        type: 'scatter',
        symbolSize: 11,
        itemStyle: { color: '#0037b5', opacity: 0.85 },
        data: usedPoints.value.map(point => [Number(point.pseudotime), Number(point.pressure), point.date])
      },
      {
        name: '回归线',
        type: 'line',
        showSymbol: false,
        lineStyle: { width: 2, color: '#333' },
        data: regressionLine.value,
        tooltip: { show: false }
      }
    ]
  }, { notMerge: true })
}

function renderChartSoon() {
  nextTick(() => {
    requestAnimationFrame(() => {
      chart?.resize()
      renderChart()
    })
  })
}

function toggleParamsPanel() {
  paramsCollapsed.value = !paramsCollapsed.value
  renderChartSoon()
}

function startParamsPanelResize(event) {
  resizingParamsPanel.value = true
  const onMouseMove = (e) => {
    if (!resizingParamsPanel.value) return
    const left = paramsPanelEl.value?.getBoundingClientRect().left || 0
    paramsPanelWidth.value = Math.max(238, Math.min(520, e.clientX - left))
    chart?.resize()
  }
  const onMouseUp = () => {
    resizingParamsPanel.value = false
    document.body.style.cursor = ''
    document.body.style.userSelect = ''
    document.removeEventListener('mousemove', onMouseMove)
    document.removeEventListener('mouseup', onMouseUp)
  }
  document.body.style.cursor = 'col-resize'
  document.body.style.userSelect = 'none'
  document.addEventListener('mousemove', onMouseMove)
  document.addEventListener('mouseup', onMouseUp)
}

watch(raw, renderChart, { deep: true })

onMounted(() => {
  renderChart()
  resizeObserver = new ResizeObserver(() => chart?.resize())
  if (chartElement.value) resizeObserver.observe(chartElement.value)
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div class="fb-wrap">
    <aside
      ref="paramsPanelEl"
      class="params-panel"
      :class="{ collapsed: paramsCollapsed, resizing: resizingParamsPanel }"
      :style="{
        width: paramsCollapsed ? '22px' : `${paramsPanelWidth}px`,
        minWidth: paramsCollapsed ? '22px' : `${paramsPanelWidth}px`
      }"
    >
      <div
        v-if="paramsCollapsed"
        class="panel-collapsed-tab"
        @click="toggleParamsPanel"
      >
        参数设置
      </div>

      <template v-if="!paramsCollapsed">
        <div class="panel-head">
          <span>参数设置</span>
          <button
            class="panel-toggle"
            type="button"
            title="收起参数设置"
            @click="toggleParamsPanel"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="#777">
              <path d="M16,12V4H17V2H7V4H8V12L6,14V16H11.2V22H12.8V16H18V14L16,12Z" />
            </svg>
          </button>
        </div>

        <div v-show="activePanelTab === 'input'" class="panel-body">
          <div v-if="!validRuntimeResult" class="empty">暂无数据</div>
          <template v-else>
            <div class="section-title">天然气性质</div>
            <div class="field-grid">
              <div class="field">
                <label>天然气相对密度</label>
                <el-input size="small" readonly :model-value="format(input.specificGravity)" />
              </div>
              <div class="field">
                <label>初始 Z 因子</label>
                <el-input size="small" readonly :model-value="format(input.initialZFactor)" />
              </div>
              <div class="field">
                <label>H₂S 摩尔分数</label>
                <el-input size="small" readonly :model-value="format(input.hydrogenSulfide)" />
              </div>
              <div class="field">
                <label>CO₂ 摩尔分数</label>
                <el-input size="small" readonly :model-value="format(input.carbonDioxide)" />
              </div>
              <div class="field">
                <label>N₂ 摩尔分数</label>
                <el-input size="small" readonly :model-value="format(input.nitrogen)" />
              </div>
            </div>

            <div class="section-title">地层条件</div>
            <div class="field-grid">
              <div class="field">
                <label>原始地层压力 (MPa)</label>
                <el-input size="small" readonly :model-value="format(input.originalFormationPressureMpa)" />
              </div>
              <div class="field">
                <label>地层温度 (K)</label>
                <el-input size="small" readonly :model-value="format(input.formationTemperatureK)" />
              </div>
            </div>

            <div class="section-title">流动压力</div>
            <div class="field-grid">
              <div class="field">
                <label>压力来源</label>
                <el-input size="small" readonly :model-value="pressureSourceLabel" />
              </div>
            </div>
          </template>
        </div>

        <div v-show="activePanelTab === 'output'" class="panel-body">
          <div class="section-title">输出结果</div>
          <div v-if="!validRuntimeResult" class="empty">暂无数据</div>
          <div v-else class="field-grid">
            <div class="field">
              <label>动态储量</label>
              <el-input size="small" readonly :model-value="format(result.originalGasVolume)" />
            </div>
            <div class="field">
              <label>回归斜率</label>
              <el-input size="small" readonly :model-value="format(result.regression.slope, 10)" />
            </div>
            <div class="field">
              <label>回归截距</label>
              <el-input size="small" readonly :model-value="format(result.regression.intercept, 10)" />
            </div>
            <div class="field">
              <label>R²</label>
              <el-input size="small" readonly :model-value="format(result.regression.rSquared, 8)" />
            </div>
          </div>
        </div>

        <div class="panel-tabs">
          <button :class="{ active: activePanelTab === 'input' }" @click="activePanelTab = 'input'">输入</button>
          <button :class="{ active: activePanelTab === 'output' }" @click="activePanelTab = 'output'">输出</button>
        </div>

        <div
          class="params-resizer"
          @mousedown="startParamsPanelResize"
        />
      </template>
    </aside>

    <main ref="chartAreaEl" class="chart-area">
      <div class="chart-tabs">
        <button
          v-for="(tab, index) in chartTabs"
          :key="tab.label"
          :class="{ active: activeChartTab === index }"
          @click="activeChartTab = index"
        >
          {{ tab.label }}
        </button>
      </div>
      <div ref="chartElement" class="chart"></div>
      <div class="table-wrap">
        <el-table :data="tableRows" size="small" height="150" border>
          <el-table-column prop="index" label="序号" width="70" />
          <el-table-column prop="pseudotime" label="累计产气量" min-width="150" />
          <el-table-column prop="pressure" label="Pflow/Z(MPa)" min-width="130" />
          <el-table-column prop="selected" label="回归点" min-width="90" />
        </el-table>
      </div>
    </main>
  </div>
</template>

<style lang="scss" scoped>
.fb-wrap {
  display: flex;
  height: 100%;
  min-height: 0;
  background: #fff;
  overflow: hidden;
}

.params-panel {
  width: 238px;
  min-width: 238px;
  display: flex;
  flex-direction: column;
  position: relative;
  border-right: 1px solid #e0e0e0;
  overflow: hidden;
  transition: width 0.16s ease, min-width 0.16s ease;

  &.collapsed {
    background: transparent;
    border-right: 0;
  }

  &.resizing {
    transition: none;
  }
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

.panel-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 4px 12px 14px;
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

.field-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  column-gap: 24px;
}

.section-title {
  font-size: 14px;
  color: #333;
  margin: 10px 0 7px;
  font-weight: 600;
}

.field {
  margin-bottom: 9px;

  label {
    display: block;
    margin-bottom: 3px;
    color: #555;
    font-size: 12px;
  }
}

.empty {
  color: #888;
  font-size: 13px;
  line-height: 1.5;
  padding: 4px 0 10px;
}

.panel-tabs {
  height: 36px;
  display: flex;
  border-top: 1px solid #e0e0e0;
  flex-shrink: 0;

  button {
    flex: 1;
    border: 0;
    border-right: 1px solid #e0e0e0;
    background: #fff;
    color: #333;
    font-size: 14px;
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
  position: relative;
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: #fff;
  overflow: hidden;
}

.chart-tabs {
  height: 34px;
  display: flex;
  border-bottom: 1px solid #e4e7ed;
  background: #fafafa;
  flex-shrink: 0;
  overflow-x: auto;
  overflow-y: hidden;

  button {
    border: 0;
    border-right: 1px solid #e4e7ed;
    background: transparent;
    padding: 0 16px;
    color: #555;
    cursor: pointer;
    flex-shrink: 0;

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
  height: 170px;
  padding: 10px;
  border-top: 1px solid #eeeeee;
  flex-shrink: 0;
}
</style>