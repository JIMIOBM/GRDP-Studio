<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { materialBalanceApi } from '@/api/docker'

const props = defineProps({
  node: Object,
  projectId: [Number, String],
  gasReservoirId: [Number, String]
})

const NODE_TYPE_WELLHEAD = 57
const NODE_TYPE_BOTTOMHOLE = 58

const chartElement = ref(null)
const paramsPanelEl = ref(null)
const paramsPanelWidth = ref(258)
const paramsCollapsed = ref(false)
const resizingParamsPanel = ref(false)
const activePanelTab = ref('input')
const activeChartTab = ref('chart')
const loading = ref(false)
const resultPayload = ref(null)
const fetchError = ref(null)
let chart = null
let resizeObserver = null

const resultId = computed(() =>
  props.node?.resultId ||
  props.node?.raw?.resultId ||
  props.node?.raw?.nodeId ||
  props.node?.nodeId ||
  null
)

const embeddedPayload = computed(() => {
  const payload = props.node?.raw || props.node || {}
  return payload?.result && Array.isArray(payload?.data) ? payload : null
})

const raw = computed(() => resultPayload.value || embeddedPayload.value || props.node?.raw || props.node || {})
const input = computed(() => raw.value?.input || {})
const result = computed(() => raw.value?.result || {})
const data = computed(() => Array.isArray(raw.value?.data) ? raw.value.data : [])
const nodeType = computed(() => Number(raw.value?.nodeType ?? props.node?.type))
const wellName = computed(() => raw.value?.wellName || input.value?.wellName || props.node?.wellName || '当前井')

const errorInfo = computed(() => {
  const source = fetchError.value || raw.value?.error || (raw.value?.status === 'error' ? raw.value : null)
  if (!source) return null
  return {
    code: source.code || 'PLATFORM_FLOW_BALANCE_ERROR',
    message: source.message || '原平台流动平衡结果加载失败',
    details: source.details || {},
    httpStatus: source.httpStatus || null
  }
})

const validRuntimeResult = computed(() => Boolean(
  !errorInfo.value &&
  Object.keys(input.value).length &&
  Object.keys(result.value).length &&
  data.value.length
))

const pressureSourceLabel = computed(() => nodeType.value === NODE_TYPE_WELLHEAD
  ? '井口油管流压'
  : nodeType.value === NODE_TYPE_BOTTOMHOLE
    ? '计算井底流压'
    : '流动压力')

const methodTitle = computed(() => nodeType.value === NODE_TYPE_WELLHEAD
  ? '流动物质平衡—基于井口流压'
  : nodeType.value === NODE_TYPE_BOTTOMHOLE
    ? '流动物质平衡—基于计算井底流压'
    : '流动物质平衡')

const chartTabTitle = computed(() => `动态分析-${wellName.value}-${methodTitle.value}-分析结果`)

const finite = (value) => {
  const number = Number(value)
  return Number.isFinite(number) ? number : null
}

const format = (value, digits = 6) => {
  const number = finite(value)
  return number === null ? '—' : number.toLocaleString('zh-CN', { maximumFractionDigits: digits })
}

const formatScientific = (value, digits = 4) => {
  const number = finite(value)
  return number === null ? '—' : number.toExponential(digits).replace('e', 'E')
}

const formatDate = (value) => value ? String(value).slice(0, 10) : '—'
const reliabilityLabel = computed(() =>
  result.value?.reliablity || result.value?.reliability || '—'
)

const inputSections = computed(() => [
  {
    title: '气体性质',
    fields: [
      { label: '天然气类型', value: input.value?.gasType || '—' },
      { label: '天然气比重（dless）', value: format(input.value?.specificGravity) },
      { label: 'H₂S 摩尔百分含量（%）', value: format(input.value?.hydrogenSulfide) },
      { label: 'CO₂ 摩尔百分含量（%）', value: format(input.value?.carbonDioxide) },
      { label: 'N₂ 摩尔百分含量（%）', value: format(input.value?.nitrogen) }
    ]
  },
  {
    title: '计算参数',
    fields: [
      { label: '压力来源', value: pressureSourceLabel.value },
      { label: '非烃修正方法', value: Number(input.value?.modificationMethod) === 0 ? 'Wichert-Aziz 方法' : format(input.value?.modificationMethod) },
      { label: '偏差系数方法', value: Number(input.value?.deviationFactorMethod) === 0 ? 'Dranchuk-Abu-Kassem 方法' : format(input.value?.deviationFactorMethod) },
      { label: '黏度方法', value: Number(input.value?.viscosityMethod) === 0 ? 'Lee-Gonzalez-Eakin 方法' : format(input.value?.viscosityMethod) },
      { label: '不稳定流动段时间（d）', value: format(input.value?.unstableFlowPeriodLength) },
      { label: '生产水气比上限（m³/10⁴m³）', value: format(input.value?.minimumWaterGasRatio) }
    ]
  },
  {
    title: '地层条件',
    fields: [
      { label: '原始地层压力（MPa）', value: format(input.value?.originalPressure) },
      { label: '地层温度（℃）', value: format(input.value?.temperature) }
    ]
  }
])

const slope = computed(() => finite(result.value?.gradient ?? result.value?.slope))
const usedPointCount = computed(() => data.value.filter(point => point.isDeleted !== true).length)
const excludedPointCount = computed(() => data.value.filter(point => point.isDeleted === true).length)

const outputFields = computed(() => [
  { label: '动态储量（10⁸ m³）', value: format(result.value?.originalGasVolume, 6) },
  { label: '游离气动态储量（10⁸ m³）', value: format(result.value?.gfi, 6) },
  { label: '吸附气动态储量（10⁸ m³）', value: format(result.value?.gai, 6) },
  { label: '回归斜率（MPa/10⁸ m³）', value: formatScientific(slope.value, 6) },
  { label: '回归截距（MPa）', value: format(result.value?.intercept, 8) },
  { label: 'R²（dless）', value: format(result.value?.rsquared, 8) },
  { label: '结果可靠性', value: reliabilityLabel.value },
  { label: '参与回归点数', value: format(usedPointCount.value, 0) },
  { label: '未参与回归点数', value: format(excludedPointCount.value, 0) },
  { label: '总数据点数', value: format(data.value.length, 0) }
])

const usedPoints = computed(() => data.value.filter(point =>
  point.isDeleted !== true && finite(point.cumulativeGasProduction) !== null && finite(point.pressure) !== null
))
const excludedPoints = computed(() => data.value.filter(point =>
  point.isDeleted === true && finite(point.cumulativeGasProduction) !== null && finite(point.pressure) !== null
))

const getOfficialLine = (yAxisField) => {
  const item = (raw.value?.chartItems || []).find(chartItem => chartItem.yAxisField === yAxisField)
  return (item?.data || [])
    .map(point => [Number(point.xValue), Number(point.yValue)])
    .filter(([x, y]) => Number.isFinite(x) && Number.isFinite(y))
}

const regressionLine = computed(() => {
  const officialLine = getOfficialLine('linearRegressionPressure')
  if (officialLine.length) return officialLine
  if (slope.value === null || !usedPoints.value.length) return []
  const intercept = finite(result.value?.intercept)
  if (intercept === null) return []
  const xs = usedPoints.value.map(point => Number(point.cumulativeGasProduction))
  const minX = Math.min(...xs)
  const maxX = Math.max(...xs)
  return [[minX, slope.value * minX + intercept], [maxX, slope.value * maxX + intercept]]
})

const shiftedLine = computed(() => getOfficialLine('shiftLinearRegressionPressure'))

const tableRows = computed(() => data.value.map((point, index) => ({
  index: point.index ?? index + 1,
  date: formatDate(point.date),
  cumulativeGas: format(point.cumulativeGasProduction, 8),
  cumulativeWater: format(point.cumulativeWaterProduction, 8),
  formationPressure: format(point.formationPressure, 8),
  pressure: format(point.pressure, 8),
  selected: point.isDeleted ? '否' : '是'
})))

const fetchOfficialResult = async () => {
  fetchError.value = null
  resultPayload.value = null

  if (props.node?.raw?.error || props.node?.raw?.status === 'error') return

  if (embeddedPayload.value) {
    resultPayload.value = embeddedPayload.value
    return
  }

  if (!resultId.value || !props.projectId || !props.gasReservoirId) {
    fetchError.value = { message: '没有找到原平台流动平衡结果 ID' }
    return
  }

  loading.value = true
  try {
    const response = await materialBalanceApi.getResult(
      props.projectId,
      props.gasReservoirId,
      resultId.value,
      null,
      null,
      { silentError: true }
    )
    const responseBody = response?.data ?? response
    const payload = responseBody?.result && Array.isArray(responseBody?.data)
      ? responseBody
      : responseBody?.data
    if (!payload?.result || !Array.isArray(payload?.data)) {
      throw new Error('原平台返回的流动平衡结果格式不完整')
    }
    resultPayload.value = payload
  } catch (error) {
    fetchError.value = {
      code: error.response?.data?.code,
      message: error.response?.data?.msg || error.response?.data?.message || error.message,
      httpStatus: error.response?.status
    }
  } finally {
    loading.value = false
    renderChartSoon()
  }
}

const errorDetailRows = computed(() => Object.entries(errorInfo.value?.details || {}).map(([key, value]) => ({
  key,
  value: typeof value === 'object' ? JSON.stringify(value) : String(value)
})))

const renderChart = async () => {
  await nextTick()
  if (!chartElement.value || !validRuntimeResult.value || activeChartTab.value !== 'chart') return
  if (chart && chart.getDom() !== chartElement.value) {
    chart.dispose()
    chart = null
  }
  if (!chart) chart = echarts.init(chartElement.value)
  if (resizeObserver) {
    resizeObserver.disconnect()
    resizeObserver.observe(chartElement.value)
  }
  const slopeText = slope.value === null ? '—' : formatScientific(slope.value, 4)
  const interceptText = format(result.value?.intercept, 4)
  const rSquaredText = format(result.value?.rsquared, 4)

  chart.setOption({
    animation: false,
    title: {
      text: methodTitle.value,
      left: 'center',
      top: 10,
      textStyle: { color: '#333', fontSize: 15, fontWeight: 600 }
    },
    tooltip: {
      trigger: 'item',
      formatter: params => {
        const value = params.value
        if (!Array.isArray(value)) return params.seriesName
        return `${params.marker}${params.seriesName}<br/>日期：${value[2] || '—'}<br/>Gp：${format(value[0], 6)} ×10⁸ m³<br/>视压力：${format(value[1], 6)} MPa`
      }
    },
    legend: {
      data: ['数据点', '剔除点', '回归线', '平移线'],
      right: 24,
      top: 44,
      orient: 'vertical',
      backgroundColor: 'rgba(255,255,255,0.92)',
      borderColor: '#dfe4ec',
      borderWidth: 1,
      padding: [8, 10]
    },
    grid: { left: 78, right: 144, top: 58, bottom: 62 },
    xAxis: {
      type: 'value',
      min: 0,
      name: 'Gp（10⁸ m³）',
      nameLocation: 'middle',
      nameGap: 38,
      axisLine: { lineStyle: { color: '#444' } },
      minorTick: { show: true, splitNumber: 5 },
      splitLine: { show: true, lineStyle: { color: '#dfe7f2' } },
      minorSplitLine: { show: true, lineStyle: { color: '#f0f4fa' } }
    },
    yAxis: {
      type: 'value',
      name: '流动物质平衡视压力（MPa）',
      nameLocation: 'middle',
      nameGap: 52,
      scale: true,
      axisLine: { lineStyle: { color: '#444' } },
      minorTick: { show: true, splitNumber: 5 },
      splitLine: { show: true, lineStyle: { color: '#dfe7f2' } },
      minorSplitLine: { show: true, lineStyle: { color: '#f0f4fa' } }
    },
    graphic: [{
      type: 'text',
      right: 154,
      top: 76,
      z: 100,
      silent: true,
      style: {
        text: `Y = ${slopeText} × X + ${interceptText}\nR² = ${rSquaredText}`,
        fill: '#444',
        font: '12px sans-serif',
        lineHeight: 19,
        backgroundColor: '#fff',
        padding: [5, 7]
      }
    }],
    series: [
      {
        name: '数据点',
        type: 'scatter',
        symbolSize: 7,
        itemStyle: { color: '#456fc5', opacity: 0.72 },
        data: usedPoints.value.map(point => [Number(point.cumulativeGasProduction), Number(point.pressure), formatDate(point.date)])
      },
      {
        name: '剔除点',
        type: 'scatter',
        symbolSize: 6,
        itemStyle: { color: '#a7adb7', opacity: 0.72 },
        data: excludedPoints.value.map(point => [Number(point.cumulativeGasProduction), Number(point.pressure), formatDate(point.date)])
      },
      {
        name: '回归线',
        type: 'line',
        showSymbol: false,
        lineStyle: { width: 2, color: '#1e4fa3' },
        data: regressionLine.value,
        tooltip: { show: false }
      },
      {
        name: '平移线',
        type: 'line',
        showSymbol: false,
        lineStyle: { width: 2, color: '#f4aa22' },
        data: shiftedLine.value,
        tooltip: { show: false }
      }
    ]
  }, { notMerge: true })
}

const renderChartSoon = () => nextTick(() => requestAnimationFrame(() => {
  chart?.resize()
  renderChart()
}))

const toggleParamsPanel = () => {
  paramsCollapsed.value = !paramsCollapsed.value
  renderChartSoon()
}

const onParamsPanelResize = (event) => {
  if (!resizingParamsPanel.value) return
  const left = paramsPanelEl.value?.getBoundingClientRect().left || 0
  paramsPanelWidth.value = Math.max(238, Math.min(520, event.clientX - left))
  chart?.resize()
}

const stopParamsPanelResize = () => {
  resizingParamsPanel.value = false
  document.body.style.cursor = ''
  document.body.style.userSelect = ''
  window.removeEventListener('mousemove', onParamsPanelResize)
  window.removeEventListener('mouseup', stopParamsPanelResize)
}

const startParamsPanelResize = (event) => {
  event.preventDefault()
  resizingParamsPanel.value = true
  document.body.style.cursor = 'col-resize'
  document.body.style.userSelect = 'none'
  window.addEventListener('mousemove', onParamsPanelResize)
  window.addEventListener('mouseup', stopParamsPanelResize)
}

watch(raw, renderChartSoon, { deep: true })
watch(activeChartTab, renderChartSoon)
watch(
  () => [resultId.value, props.projectId, props.gasReservoirId, embeddedPayload.value],
  fetchOfficialResult,
  { immediate: true }
)

onMounted(() => {
  renderChartSoon()
  resizeObserver = new ResizeObserver(() => chart?.resize())
  if (chartElement.value) resizeObserver.observe(chartElement.value)
  window.addEventListener('resize', renderChartSoon)
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  stopParamsPanelResize()
  window.removeEventListener('resize', renderChartSoon)
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
      :style="{ width: paramsCollapsed ? '22px' : `${paramsPanelWidth}px`, minWidth: paramsCollapsed ? '22px' : `${paramsPanelWidth}px` }"
    >
      <div v-if="paramsCollapsed" class="panel-collapsed-tab" @click="toggleParamsPanel">参数设置</div>

      <template v-if="!paramsCollapsed">
        <div class="panel-head">
          <span>参数设置</span>
          <button class="panel-toggle" type="button" title="收起参数设置" @click="toggleParamsPanel">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="#777">
              <path d="M16,12V4H17V2H7V4H8V12L6,14V16H11.2V22H12.8V16H18V14L16,12Z" />
            </svg>
          </button>
        </div>

        <div v-show="activePanelTab === 'input'" class="panel-body">
          <div v-if="loading" class="empty">正在加载原平台结果...</div>
          <template v-else-if="errorInfo">
            <div class="section-title">原平台接口错误</div>
            <div class="panel-error-message">{{ errorInfo.message }}</div>
          </template>
          <template v-else-if="validRuntimeResult">
            <section v-for="section in inputSections" :key="section.title">
              <div class="section-title">{{ section.title }}</div>
              <div class="field-grid">
                <div v-for="field in section.fields" :key="field.label" class="field">
                  <label>{{ field.label }}</label>
                  <el-input size="small" readonly :model-value="field.value" />
                </div>
              </div>
            </section>
          </template>
          <div v-else class="empty">暂无流动平衡数据</div>
        </div>

        <div v-show="activePanelTab === 'output'" class="panel-body">
          <div class="section-title">输出结果</div>
          <div v-if="loading" class="empty">正在加载原平台结果...</div>
          <template v-else-if="errorInfo">
            <div class="error-code">{{ errorInfo.code }}</div>
            <div class="panel-error-message">{{ errorInfo.message }}</div>
            <div v-for="item in errorDetailRows" :key="item.key" class="error-detail-row">
              <span>{{ item.key }}</span>
              <strong>{{ item.value }}</strong>
            </div>
          </template>
          <div v-else-if="validRuntimeResult" class="field-grid">
            <div v-for="field in outputFields" :key="field.label" class="field">
              <label>{{ field.label }}</label>
              <el-input size="small" readonly :model-value="field.value" />
            </div>
          </div>
          <div v-else class="empty">暂无输出结果</div>
        </div>

        <div class="panel-tabs">
          <button :class="{ active: activePanelTab === 'input' }" @click="activePanelTab = 'input'">输入</button>
          <button :class="{ active: activePanelTab === 'output' }" @click="activePanelTab = 'output'">输出</button>
        </div>
        <div class="params-resizer" @mousedown="startParamsPanelResize"></div>
      </template>
    </aside>

    <main class="chart-area">
      <div class="dynamic-result-tabs">
        <button type="button" class="dynamic-result-tab active" :title="chartTabTitle">
          <span>{{ chartTabTitle }}</span>
        </button>
      </div>

      <div v-show="activeChartTab === 'chart'" class="chart-pane">
        <div v-if="loading" class="empty-canvas">正在加载原平台流动平衡结果...</div>
        <div v-else-if="errorInfo" class="error-canvas">
          <div class="error-icon">!</div>
          <h3>原平台接口错误</h3>
          <p>{{ errorInfo.message }}</p>
          <div class="error-meta">
            <span>错误码：{{ errorInfo.code }}</span>
            <span v-if="errorInfo.httpStatus">HTTP：{{ errorInfo.httpStatus }}</span>
          </div>
          <div v-if="errorDetailRows.length" class="error-details">
            <div v-for="item in errorDetailRows" :key="item.key">
              <strong>{{ item.key }}</strong>
              <span>{{ item.value }}</span>
            </div>
          </div>
        </div>
        <div v-else-if="!validRuntimeResult" class="empty-canvas">暂无可绘制的流动平衡结果</div>
        <div v-else ref="chartElement" class="chart-instance"></div>
      </div>

      <div v-if="activeChartTab === 'table'" class="data-list-panel">
        <el-table v-loading="loading" :data="tableRows" size="small" height="100%" border stripe>
          <el-table-column prop="index" label="序号" width="76" sortable />
          <el-table-column prop="date" label="日期" min-width="120" sortable />
          <el-table-column prop="cumulativeGas" label="Gp（10⁸ m³）" min-width="160" sortable />
          <el-table-column prop="cumulativeWater" label="累产水量（10⁴ m³）" min-width="180" sortable />
          <el-table-column prop="formationPressure" :label="`${pressureSourceLabel}（MPa）`" min-width="180" sortable />
          <el-table-column prop="pressure" label="流动物质平衡视压力（MPa）" min-width="210" sortable />
          <el-table-column prop="selected" label="是否参与分析" min-width="120" />
        </el-table>
      </div>

      <div class="bottom-chart-tabs">
        <button type="button" :class="{ active: activeChartTab === 'table' }" @click="activeChartTab = 'table'">数据列表</button>
        <button type="button" :class="{ active: activeChartTab === 'chart' }" @click="activeChartTab = 'chart'">结果分析图</button>
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

  &:hover { background: #eef4ff; }
}

.panel-collapsed-tab {
  width: 22px;
  height: 76px;
  display: flex;
  align-items: center;
  justify-content: center;
  writing-mode: vertical-rl;
  font-size: 13px;
  color: #333;
  cursor: pointer;
  background: #fff;
  border: 1px solid #e0e0e0;
  border-left: 0;

  &:hover {
    background: #eef4ff;
    color: #1f6fd6;
  }
}

.panel-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 6px 12px 14px;
}

.params-resizer {
  position: absolute;
  top: 0;
  right: -3px;
  width: 6px;
  height: 100%;
  cursor: col-resize;
  z-index: 4;

  &:hover { background: rgba(64, 132, 217, 0.18); }
}

.readonly-note {
  margin: 4px 0 8px;
  padding: 8px 9px;
  border: 1px solid #c8e8d1;
  background: #f1fbf4;
  color: #3b7150;
  font-size: 12px;
  line-height: 1.5;
}

.readonly-dot {
  display: inline-block;
  width: 7px;
  height: 7px;
  margin-right: 6px;
  border-radius: 50%;
  background: #46a66a;
}

.section-title {
  font-size: 13px;
  color: #333;
  margin: 10px 0 7px;
  font-weight: 600;
}

.field-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  column-gap: 24px;
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

.panel-tabs {
  height: 34px;
  display: flex;
  border-top: 1px solid #e0e0e0;
  flex-shrink: 0;

  button {
    flex: 1;
    border: 0;
    border-right: 1px solid #e0e0e0;
    background: #fff;
    color: #333;
    font-size: 13px;
    cursor: pointer;

    &:last-child { border-right: 0; }
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
  overflow: hidden;
}

.dynamic-result-tabs {
  height: 34px;
  display: flex;
  border-bottom: 1px solid #e4e7ed;
  background: #fafafa;
  flex-shrink: 0;
}

.dynamic-result-tab {
  max-width: 100%;
  border: 0;
  border-right: 1px solid #e4e7ed;
  padding: 0 16px;
  background: #fff;
  color: #409eff;
  font-weight: 600;
  border-bottom: 2px solid #409eff;
  overflow: hidden;

  span {
    display: block;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
}

.chart-pane,
.data-list-panel {
  flex: 1;
  min-height: 0;
  position: relative;
}

.chart-instance {
  width: 100%;
  height: 100%;
}

.data-list-panel {
  padding: 8px;
}

.bottom-chart-tabs {
  height: 34px;
  display: flex;
  border-top: 1px solid #e0e0e0;
  flex-shrink: 0;

  button {
    min-width: 110px;
    padding: 0 16px;
    border: 0;
    border-right: 1px solid #e0e0e0;
    background: #fff;
    color: #555;
    cursor: pointer;

    &.active {
      color: #409eff;
      font-weight: 600;
      border-top: 2px solid #409eff;
      background: #f8fbff;
    }
  }
}

.empty,
.empty-canvas {
  color: #8b929c;
  font-size: 13px;
}

.empty-canvas {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #fbfcfe;
}

.panel-error-message {
  padding: 9px;
  border: 1px solid #ffd1d1;
  background: #fff5f5;
  color: #c23934;
  font-size: 12px;
  line-height: 1.55;
  word-break: break-word;
}

.error-code {
  margin-bottom: 8px;
  color: #c23934;
  font-size: 12px;
  font-family: Consolas, monospace;
}

.error-detail-row {
  padding: 7px 0;
  border-bottom: 1px solid #eee;
  font-size: 12px;

  span {
    display: block;
    color: #7b828b;
    margin-bottom: 3px;
  }

  strong {
    color: #333;
    font-weight: 500;
    word-break: break-word;
  }
}

.error-canvas {
  height: 100%;
  padding: 48px 12%;
  display: flex;
  flex-direction: column;
  align-items: center;
  box-sizing: border-box;
  text-align: center;
  background: #fffafa;
  overflow: auto;

  h3 {
    margin: 12px 0 6px;
    color: #b8322e;
    font-size: 18px;
  }

  p {
    max-width: 760px;
    margin: 0;
    color: #5d3331;
    line-height: 1.65;
    word-break: break-word;
  }
}

.error-icon {
  width: 42px;
  height: 42px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #d84a45;
  color: #fff;
  font-size: 26px;
  font-weight: 700;
}

.error-meta {
  display: flex;
  gap: 16px;
  margin-top: 12px;
  color: #9b5a57;
  font-size: 12px;
}

.error-details {
  width: min(760px, 100%);
  margin-top: 18px;
  border: 1px solid #f0cdcb;
  background: #fff;
  text-align: left;

  div {
    display: grid;
    grid-template-columns: 180px 1fr;
    gap: 12px;
    padding: 8px 10px;
    border-bottom: 1px solid #f3e4e3;
    font-size: 12px;

    &:last-child { border-bottom: 0; }
  }

  strong { color: #7d3e3a; }
  span { color: #444; word-break: break-word; }
}
</style>
