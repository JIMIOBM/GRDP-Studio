<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import { rockPvtApi } from '@/api/rockPvt'

const props = defineProps({
  projectId: { type: [Number, String], required: true },
  importedRows: { type: Array, default: () => [] },
  importedResultRows: { type: Array, default: () => [] },
  // 已保存的岩石计算状态用于重新进入 PVT 时恢复曲线和“结果分析图”页签。
  initialState: { type: Object, default: null }
})

const emit = defineEmits(['calculation-complete', 'results-cleared'])

const DEFAULT_POROSITY = '25'

const activeCurve = ref('胶结砂岩')
const activeParamTab = ref('input')
const activeContentTab = ref('table')
const paramsCollapsed = ref(false)
const initialImportedPorosity = props.importedRows?.[0]?.[0] ?? props.initialState?.porosity
const porosity = ref(
  initialImportedPorosity !== undefined && initialImportedPorosity !== ''
    ? String(initialImportedPorosity)
    : DEFAULT_POROSITY
)
const calculating = ref(false)
const curveColors = [
  '#1677ff', '#f56c6c', '#67c23a', '#e6a23c',
  '#8b5cf6', '#13c2c2', '#eb2f96', '#fa8c16'
]
const createInitialCurveSeries = curveType => {
  const items = (props.importedResultRows || [])
    .filter(row => String(row?.curveType || '').toLowerCase() === curveType)
    .sort((left, right) => Number(left.pointNo || 0) - Number(right.pointNo || 0))
    .map(row => ({
      porosity: Number(row.porosity),
      compressibilityFactor: Number(row.compressibilityFactor)
    }))
    .filter(row => Number.isFinite(row.porosity) && Number.isFinite(row.compressibilityFactor))
  return items.length ? [{ name: '数据库结果', color: curveColors[0], items }] : []
}
const curveOneSeries = ref(createInitialCurveSeries('cemented'))
const curveTwoSeries = ref(createInitialCurveSeries('carbonate'))
const outputData = ref({})

const chartEl = ref(null)
const paramsPanelEl = ref(null)
let chart = null
let chartRenderFrame = null

const rockCurveOptions = [
  { name: '胶结砂岩', yAxisLabel: '胶结砂岩压缩系数(MPa⁻¹)' },
  { name: '碳酸盐岩', yAxisLabel: '碳酸盐岩压缩系数(MPa⁻¹)' }
]

const activeCurveOption = computed(
  () => rockCurveOptions.find(item => item.name === activeCurve.value) ?? rockCurveOptions[0]
)

const activeCurveHasData = computed(() => {
  if (activeCurve.value === '胶结砂岩') return curveOneSeries.value.length > 0
  if (activeCurve.value === '碳酸盐岩') return curveTwoSeries.value.length > 0
  return false
})

const hasOutputResults = computed(() => {
  return Object.keys(outputData.value).length > 0 && calculating.value === false
})

const currentCurveItems = computed(() => {
  if (activeCurve.value === '胶结砂岩') {
    return curveOneSeries.value.length > 0 ? curveOneSeries.value[0].items : []
  }
  if (activeCurve.value === '碳酸盐岩') {
    return curveTwoSeries.value.length > 0 ? curveTwoSeries.value[0].items : []
  }
  return []
})

const dataListColumns = [
  { prop: 'index', label: '序号', width: 70 },
  { prop: 'porosity', label: '孔隙度(%)', width: 120 },
  { prop: 'compressibilityFactor', label: '压缩系数(MPa⁻¹)', minWidth: 180 }
]

const dataListRows = computed(() =>
  currentCurveItems.value.map((item, idx) => ({
    index: idx + 1,
    porosity: Number(item.porosity).toFixed(2),
    compressibilityFactor: Number(item.compressibilityFactor).toExponential(4)
  }))
)

const dataListDisplayRows = computed(() => {
  if (dataListRows.value.length) return dataListRows.value
  return Array.from({ length: 25 }, (_, index) => ({
    index: index + 1,
    porosity: '',
    compressibilityFactor: ''
  }))
})

const dataListCells = computed(() =>
  dataListDisplayRows.value.flatMap((row, rowIndex) =>
    dataListColumns.map((column, columnIndex) => ({
      key: `${rowIndex}-${column.prop}`,
      value: row[column.prop] ?? '',
      columnIndex
    }))
  )
)

const unwrapResponse = (response) =>
  response?.data?.data ?? response?.data ?? response ?? {}

function toggleParamsPanel() {
  paramsCollapsed.value = !paramsCollapsed.value
  scheduleRenderChart(180)
}

const interpolateDataPoints = (items) => {
  if (!items || items.length < 2) return []

  const rawPoints = items.map(item => ({
    x: Number(item.porosity),
    y: Number(item.compressibilityFactor)
  })).sort((a, b) => a.x - b.x)

  const interpolatedPoints = []
  const step = 1

  for (let i = 0; i < rawPoints.length - 1; i++) {
    const start = rawPoints[i]
    const end = rawPoints[i + 1]

    interpolatedPoints.push([start.x, start.y])

    const distance = end.x - start.x

    if (distance === 0) continue

    const numInterpolated = Math.floor(distance / step)

    for (let j = 1; j <= numInterpolated; j++) {
      const ratio = (j * step) / distance  // 现在安全了
      const interpX = start.x + j * step
      const interpY = start.y + ratio * (end.y - start.y)

      if (interpX < end.x) {
        interpolatedPoints.push([
          Number(interpX.toFixed(2)),
          interpY
        ])
      }
    }
  }

  const lastPoint = rawPoints[rawPoints.length - 1]
  interpolatedPoints.push([lastPoint.x, lastPoint.y])

  return interpolatedPoints.map(point => [
    Number(point[0]),
    Number(point[1])
  ])
}

const renderChart = () => {
  const element = chartEl.value
  if (!element) return

  if (!chart || chart.getDom() !== element) {
    chart?.dispose()
    chart = echarts.init(element)
  }

  let chartSeries = []
  const items =
    activeCurve.value === '胶结砂岩'
      ? curveOneSeries.value[0]?.items || []
      : curveTwoSeries.value[0]?.items || []

  if (items.length > 0) {
    const curveColor = curveColors[0]

    chartSeries.push({
      name: `压缩系数曲线`,
      type: 'line',
      showSymbol: false,
      smooth: true,
      lineStyle: { color: curveColor, width: 1.5 },
      itemStyle: { color: curveColor },
      data: interpolateDataPoints(items)
    })

    const currentCompressibility =
      activeCurve.value === '胶结砂岩'
        ? outputData.value?.cementedCompressibility
        : outputData.value?.carbonateCompressibility


    if (
      Number.isFinite(Number(outputData.value?.porosity)) &&
      Number.isFinite(Number(currentCompressibility))
    ) {

      const x = Number(outputData.value.porosity)
      const y = Number(currentCompressibility)


      if (Number.isFinite(x) && Number.isFinite(y)) {

        chartSeries.push({
          name: '输出点',
          type: 'scatter',
          symbolSize: 8,
          itemStyle: { color: curveColor },
          data: [[x, y]]
        })

      }
    }
  }
  const allY = chartSeries
    .filter(s => s.name === '压缩系数曲线')
    .flatMap(s => s.data.map(p => Number(p[1])))
  const yMin = allY.length > 0 ? Math.min(...allY) : 0
  const yMax = allY.length > 0 ? Math.max(...allY) : 1
  const yPad = Math.max((yMax - yMin) * 0.1, Math.abs(yMax) * 0.05, 1e-10)

  chart.clear()
  chart.setOption({
    animation: false,
    color: [curveColors[0]],
    title: {
      text: activeCurve.value === '胶结砂岩'
        ? '胶结砂岩压缩系数随孔隙度变化曲线'
        : '碳酸盐岩压缩系数随孔隙度变化曲线',
      left: 'center', top: 8,
      textStyle: { fontSize: 14, fontWeight: 600, color: '#333' }
    },
    legend: {
      show: chartSeries.length > 1,
      type: 'scroll', top: 30, left: 62, right: 92,
      data: chartSeries.map(s => s.name)
    },
    grid: { left: 62, right: 92, top: chartSeries.length > 1 ? 70 : 44, bottom: 56 },
    tooltip: {
      trigger: 'axis',
      axisPointer: {
        type: 'line',
        lineStyle: { color: '#d936d0', type: 'solid', width: 1 }
      },
      formatter: function (params) {
        const items = Array.isArray(params) ? params : [params]
        const item = items.find(i => i && i.value)

        if (!item || !item.value) return ''

        const xValue = item.value[0]
        const yValue = item.value[1]

        const numX = Number(xValue)
        const numY = Number(yValue)

        if (!Number.isFinite(numX) || !Number.isFinite(numY)) return ''

        return [
          `孔隙度：${numX.toFixed(1)} %`,
          `${item.marker}${item.seriesName}：${numY.toExponential(4)} MPa⁻¹`
        ].join('<br/>')
      }
    },
    xAxis: {
      type: 'value',
      min: 0,
      interval: 5,
      name: '岩石孔隙度 φ(%)',
      nameLocation: 'middle',
      nameGap: 34,
      minorTick: { show: true },
      minorSplitLine: { show: true, lineStyle: { color: '#f1f5fb' } },
      splitLine: { show: true, lineStyle: { color: '#dce5f2' } }
    },
    yAxis: [{
      type: 'log',
      name: activeCurveOption.value.yAxisLabel,
      nameLocation: 'middle',
      nameGap: 44,
      min: yMin > 0 ? yMin * 0.5 : 1e-4,
      axisLabel: {
        formatter: v => Number(v).toExponential(1)
      },
      minorTick: { show: true },
      minorSplitLine: { show: true, lineStyle: { color: '#f1f5fb' } },
      splitLine: { show: true, lineStyle: { color: '#dce5f2' } }
    }],
    series: chartSeries
  }, true)


  requestAnimationFrame(() => {
    if (chart && element.clientWidth > 0 && element.clientHeight > 0) {
      chart.resize()
    } else {
      setTimeout(() => {
        if (chart) chart.resize()
      }, 200)
    }
  })
}

const disposeChart = () => { chart?.dispose(); chart = null }

const scheduleRenderChart = async () => {
  await nextTick()
  if (chartRenderFrame !== null) cancelAnimationFrame(chartRenderFrame)
  chartRenderFrame = requestAnimationFrame(() => {
    chartRenderFrame = requestAnimationFrame(() => {
      chartRenderFrame = null
      renderChart()
    })
  })
}

const handleCalculate = async ({ silent = false } = {}) => {
  if (calculating.value) return

  const porosityValue = Number(porosity.value)

  if (!Number.isFinite(porosityValue) || porosityValue < 0 || porosityValue > 50) {
    ElMessage({
      message: `invoke algorithm error: 岩石孔隙度 取值范围 [0, 50]`,
      type: 'error',
      duration: 3000,
      showClose: true,
      customClass: 'rock-error-message'
    })
    return
  }

  const projectId = Number(props.projectId)
  if (!Number.isFinite(projectId) || projectId <= 0) {
    ElMessage.error('项目 ID 无效')
    return
  }

  // 恢复已有 PVT 时沿用数据库保存的计算范围；新计算继续使用默认范围。
  const start = Number(props.initialState?.settings?.porosityStart ?? 0)
  const end = Number(props.initialState?.settings?.porosityEnd ?? 50)
  const step = Number(props.initialState?.settings?.porosityStep ?? 0.5)

  const curveRequest = {
    projectId,
    porosityStart: Number(start.toFixed(2)),
    porosityEnd: Number(end.toFixed(2)),
    porosityStep: Number(step.toFixed(2))
  }

  calculating.value = true
  try {
    const [
      curveOneResponse,
      curveTwoResponse,
      pointOneResponse,
      pointTwoResponse
    ] = await Promise.all([

      rockPvtApi.calculateCurveOne(curveRequest),
      rockPvtApi.calculateCurveTwo(curveRequest),

      rockPvtApi.calculateSingle({
        projectId,
        porosity: porosityValue,
        rockType: 0
      }),

      rockPvtApi.calculateSingle({
        projectId,
        porosity: porosityValue,
        rockType: 1
      })
    ])

    const curveOneResult = unwrapResponse(curveOneResponse)
    const curveOneItems = Array.isArray(curveOneResult?.items) ? curveOneResult.items : []
    if (!curveOneItems.length) throw new Error('胶结砂岩接口未返回数据')

    const curveTwoResult = unwrapResponse(curveTwoResponse)
    const curveTwoItems = Array.isArray(curveTwoResult?.items) ? curveTwoResult.items : []
    if (!curveTwoItems.length) throw new Error('碳酸盐岩接口未返回数据')

    curveOneSeries.value = [{
      name: `孔隙度${porosityValue}%`,
      color: curveColors[0],
      items: curveOneItems
    }]
    curveTwoSeries.value = [{
      name: `孔隙度${porosityValue}%`,
      color: curveColors[0],
      items: curveTwoItems
    }]

    const pointOneResult = unwrapResponse(pointOneResponse)
    const pointTwoResult = unwrapResponse(pointTwoResponse)


    const matchedItem1 =
      Number(pointOneResult.compressibilityFactor)


    const matchedItem2 =
      Number(pointTwoResult.compressibilityFactor)
    outputData.value = {
      porosity: porosityValue,
      cementedCompressibility: matchedItem1,
      cementedCompressibilityDisplay: matchedItem1?.toExponential(4) ?? '',
      carbonateCompressibility: matchedItem2,
      carbonateCompressibilityDisplay: matchedItem2?.toExponential(4) ?? ''
    }

    activeCurve.value = '胶结砂岩'
    activeParamTab.value = 'output'
    activeContentTab.value = 'chart'

    emit('calculation-complete', {
      porosity: porosityValue,
      rockType: '胶结砂岩/碳酸盐岩',
      calculationMethod: 'RockPVT曲线计算',
      settings: {
        porosityStart: curveRequest.porosityStart,
        porosityEnd: curveRequest.porosityEnd,
        porosityStep: curveRequest.porosityStep
      },
      resultRows: [
        ...curveOneItems.map((item, index) => ({
          curveType: 'cemented',
          pointNo: index + 1,
          porosity: Number(item.porosity),
          compressibilityFactor: Number(item.compressibilityFactor)
        })),
        ...curveTwoItems.map((item, index) => ({
          curveType: 'carbonate',
          pointNo: index + 1,
          porosity: Number(item.porosity),
          compressibilityFactor: Number(item.compressibilityFactor)
        }))
      ]
    })

    if (!silent) {
      ElMessage.success('岩石性质计算完成')
    }

    await nextTick()
    scheduleRenderChart()

    if (!silent) setTimeout(() => {
      if (chart) {
        chart.resize()

        const chartElRef = chartEl.value
        if (chartElRef) {
          chartElRef.scrollIntoView({
            behavior: 'smooth',
            block: 'center'
          })
        }
      }
    }, 300)
  } catch (error) {
    console.error('[RockPvt] 计算错误:', error)
    const errorMsg = error?.response?.data?.msg || error?.message || '计算失败'
    ElMessage.error(errorMsg)
  } finally {
    calculating.value = false
  }
}

const handleReset = () => {
  porosity.value = DEFAULT_POROSITY
  curveOneSeries.value = []
  curveTwoSeries.value = []
  outputData.value = {}
  activeCurve.value = '胶结砂岩'
  activeParamTab.value = 'input'
  activeContentTab.value = 'chart'
  disposeChart()
  emit('results-cleared')
}

watch([activeCurve, curveOneSeries, curveTwoSeries], () => {
  scheduleRenderChart()
})

watch(activeContentTab, async (newVal) => {
  if (newVal === 'chart') {
    await nextTick()
    scheduleRenderChart()
  }
})

const handleResize = () => scheduleRenderChart()
window.addEventListener('resize', handleResize)

onMounted(() => {
  // 新结构优先直接恢复数据库结果，不再重复调用岩石算法。
  if (props.importedResultRows?.length) {
    const porosityValue = Number(porosity.value)
    const findCurrentValue = series => series.value[0]?.items.find(
      item => Number(item.porosity) === porosityValue
    )?.compressibilityFactor
    const cemented = Number(findCurrentValue(curveOneSeries))
    const carbonate = Number(findCurrentValue(curveTwoSeries))
    outputData.value = {
      porosity: porosityValue,
      cementedCompressibility: cemented,
      cementedCompressibilityDisplay: Number.isFinite(cemented) ? cemented.toExponential(4) : '',
      carbonateCompressibility: carbonate,
      carbonateCompressibilityDisplay: Number.isFinite(carbonate) ? carbonate.toExponential(4) : ''
    }
    activeParamTab.value = 'output'
    activeContentTab.value = 'chart'
    scheduleRenderChart()
    return
  }

  // 兼容旧 PVT：只有输入和 settings、尚未写入新结果表时静默重算一次，
  // 用户下次点击保存后就会正式写入 project_well_pvt_rock_result。
  if (props.initialState?.calculationMethod === 'RockPVT曲线计算') {
    handleCalculate({ silent: true })
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  if (chartRenderFrame !== null) cancelAnimationFrame(chartRenderFrame)
  disposeChart()
})
</script>

<template>
  <div class="rock-properties-view">
    <!-- 左侧参数面板 -->
    <div ref="paramsPanelEl" class="params-panel" :class="{ collapsed: paramsCollapsed }">
      <div v-if="paramsCollapsed" class="panel-collapsed-tab" @click="toggleParamsPanel">
        参数设置
      </div>

      <div v-show="!paramsCollapsed" class="panel-head">
        <span>参数设置</span>
        <button class="panel-toggle" type="button" title="收起参数设置" @click="toggleParamsPanel">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="#777">
            <path d="M16,12V4H17V2H7V4H8V12L6,14V16H11.2V22H12.8V16H18V14L16,12Z" />
          </svg>
        </button>
      </div>

      <div v-if="!paramsCollapsed && activeParamTab === 'input'" class="panel-body">
        <div class="parameter-section">
          <div class="section-heading">
            <span>输入参数</span>
            <span class="section-rule"></span>
          </div>

          <label class="field-group">
            <span>岩石孔隙度（%）</span>
            <input v-model.number="porosity" inputmode="decimal" />
          </label>
        </div>

        <div class="parameter-actions">
          <button type="button" :disabled="calculating" @click="handleCalculate">
            {{ calculating ? '计算中...' : '计算' }}
          </button>
          <button type="button" :disabled="calculating" @click="handleReset">重置</button>
        </div>
      </div>

      <div v-else-if="!paramsCollapsed && hasOutputResults" class="panel-body">
        <div class="parameter-section">
          <div class="section-heading">
            <span>输出结果</span>
            <span class="section-rule"></span>
          </div>

          <label class="field-group">
            <span>当前孔隙度(%)</span>
            <input readonly :value="outputData.porosity ?? ''" />
          </label>

          <label class="field-group">
            <span>胶结砂岩压缩系数(MPa⁻¹)</span>
            <input readonly :value="outputData.cementedCompressibilityDisplay ?? ''" />
          </label>

          <label class="field-group">
            <span>碳酸盐岩压缩系数(MPa⁻¹)</span>
            <input readonly :value="outputData.carbonateCompressibilityDisplay ?? ''" />
          </label>
        </div>
      </div>

      <div v-show="!paramsCollapsed" class="param-tabs">
        <div class="rock-param-tab" :class="{ active: activeParamTab === 'input' }" @click="activeParamTab = 'input'">
          输入
        </div>
        <div v-if="hasOutputResults" class="rock-param-tab" :class="{ active: activeParamTab === 'output' }"
          @click="activeParamTab = 'output'">
          输出
        </div>
      </div>
    </div>

    <!-- 右侧图表区域 -->
    <div class="chart-area">
      <div class="dynamic-result-tabs">
        <button type="button" class="dynamic-result-tab active" title="岩石性质分析结果">
          <span class="dynamic-result-tab-text">岩石性质分析-分析结果</span>
        </button>
      </div>

      <div class="rock-curve-toolbar">
        <label class="rock-curve-field">
          <span>岩石类型</span>
          <!-- 与天然气、地层水的数据列表保持一致，使用下拉框切换当前数据/曲线。 -->
          <select v-model="activeCurve" aria-label="选择岩石类型">
            <option v-for="curve in rockCurveOptions" :key="curve.name" :value="curve.name">
              {{ curve.name }}
            </option>
          </select>
        </label>
      </div>

      <div v-show="activeContentTab === 'chart'" class="rock-chart">
        <div class="rock-chart-plot-shell">
          <div ref="chartEl" class="chart-instance"></div>
          <div v-if="currentCurveItems.length === 0" class="rock-chart-empty">暂无计算结果</div>
        </div>
      </div>

      <div v-show="activeContentTab === 'table'" class="data-list-panel">
        <div class="rock-data-grid" aria-label="岩石性质数据列表">
          <div v-for="column in dataListColumns" :key="column.prop" class="rock-data-grid-cell header">
            {{ column.label }}
          </div>
          <div v-for="cell in dataListCells" :key="cell.key" class="rock-data-grid-cell"
            :class="{ numeric: cell.columnIndex > 0 && cell.value !== '', 'row-index': cell.columnIndex === 0 }">
            {{ cell.value }}
          </div>
        </div>
      </div>

      <div v-if="currentCurveItems.length > 0" class="bottom-chart-tabs">
        <button type="button" class="bottom-chart-tab" :class="{ active: activeContentTab === 'table' }"
          @click="activeContentTab = 'table'">
          数据列表
        </button>
        <button type="button" class="bottom-chart-tab" :class="{ active: activeContentTab === 'chart' }" title="结果分析图"
          @click="activeContentTab = 'chart'">
          结果分析图
        </button>
      </div>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.rock-properties-view {
  flex: 1;
  min-height: 0;
  display: flex;
  gap: 0;
  padding: 10px 12px;
  box-sizing: border-box;
  overflow: hidden;
  background: #fff;
}

/* ─── 左侧参数面板 ─── */
.params-panel {
  width: 260px;
  flex: 0 0 260px;
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  position: relative;
  transition: width 0.18s ease, flex-basis 0.18s ease;
  box-sizing: border-box;
  border: 1px solid #d4d7db;
  border-right: 0;
  background: #fff;
  overflow: hidden;

  &.collapsed {
    width: 34px !important;
    min-width: 34px !important;
    flex-basis: 34px;
    background: #fff;
  }
}

.panel-collapsed-tab {
  width: 100%;
  height: 76px;
  padding: 0;
  border: 0;
  border-bottom: 1px solid #e2e6ea;
  background: #fff;
  color: #222;
  cursor: pointer;
  font: inherit;
  display: flex;
  align-items: center;
  justify-content: flex-start;
  padding-top: 8px;
  box-sizing: border-box;
  writing-mode: vertical-rl;
  text-orientation: upright;
  line-height: 1.05;
  letter-spacing: 0;

  &:hover {
    background: #eef4ff;
    color: #409eff;
  }
}

.panel-head {
  height: 36px;
  flex: 0 0 36px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 12px;
  box-sizing: border-box;
  border-bottom: 1px solid #e2e6ea;
  font-size: 14px;
  font-weight: 500;
  color: #303133;
}

.panel-toggle {
  background: none;
  border: none;
  cursor: pointer;
  padding: 4px;
  border-radius: 4px;
  display: flex;
  align-items: center;
  justify-content: center;

  &:hover {
    background: #f5f7fa;
  }
}

.panel-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 12px 14px;
  box-sizing: border-box;
}

.parameter-section+.parameter-section {
  margin-top: 16px;
}

.section-heading {
  height: 22px;
  display: flex;
  align-items: center;
  gap: 4px;
  white-space: nowrap;
  font-size: 14px;
  /* ← 统一为14px */
  color: #404040;
}

.section-rule {
  height: 1px;
  flex: 1;
  background: #c8cdd3;
}

.field-group {
  display: block;
  margin-top: 10px;
  color: #404040;

  >span {
    display: block;
    margin-bottom: 4px;
    /* ← 统一为4px */
    line-height: 18px;
    /* ← 添加行高 */
    font-size: inherit;
    /* ← 继承父级字体大小 */
  }

  input,
  select {
    width: 100%;
    height: 30px;
    /* ← 统一为30px */
    padding: 2px 8px;
    box-sizing: border-box;
    border: 1px solid #aeb6bf;
    border-radius: 3px;
    /* ← 统一圆角 */
    background: #fff;
    color: #333;
    font: inherit;
    outline: none;

    &:focus {
      border-color: #4c81b6;
      box-shadow: 0 0 0 1px rgba(76, 129, 182, 0.18);
    }

    &[readonly] {
      background: #f5f7fa;
      cursor: not-allowed;
    }
  }
}

.parameter-actions {
  display: flex;
  gap: 10px;
  margin-top: 22px;

  button {
    flex: 1;
    height: 32px;
    padding: 0 12px;
    border: 1px solid #777;
    border-radius: 5px;
    background: #fff;
    color: #222;
    font: inherit;
    cursor: pointer;

    &:hover {
      border-color: #333;
      background: #f5f5f5;
    }

    &:focus-visible {
      outline: 2px solid rgba(47, 116, 192, 0.25);
      outline-offset: 1px;
    }

    &:disabled {
      opacity: 0.55;
      cursor: not-allowed;
    }
  }
}

.param-tabs {
  display: flex;
  height: 30px;
  border-top: 1px solid #e0e0e0;
  flex-shrink: 0;
}

.rock-param-tab {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  color: #555;
  cursor: pointer;
  border-right: 1px solid #e0e0e0;

  &:last-child {
    border-right: none;
  }

  &.active {
    background-color: #f4d000;
    color: #1a1a1a;
    font-weight: 600;
  }
}


/* ─── 右侧图表区域 ─── */
.chart-area {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid #d4d7db;
  background: #fff;
}

.dynamic-result-tabs {
  height: 34px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  border-bottom: 1px solid #e4e7ed;
  background: #fafafa;
  overflow-x: auto;
  overflow-y: hidden;
}

.dynamic-result-tab {
  height: 34px;
  max-width: 340px;
  border: 0;
  border-right: 1px solid #e4e7ed;
  border-bottom: 2px solid transparent;
  background: transparent;
  color: #409eff;
  font-size: 14px;
  font-weight: 600;
  display: flex;
  align-items: center;
  padding: 0 12px;
  cursor: default;
  white-space: nowrap;

  &.active {
    border-bottom-color: #409eff;
    background: #fff;
  }
}

.dynamic-result-tab-text {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rock-curve-toolbar {
  min-height: 42px;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  padding: 5px 12px;
  box-sizing: border-box;
  border-bottom: 1px solid #e4e7ed;
  flex-shrink: 0;
  background: #fafafa;
}

.rock-curve-field {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #555;
  font-size: 13px;

  > span {
    white-space: nowrap;
  }

  select {
    width: 156px;
    height: 30px;
    padding: 2px 28px 2px 8px;
    box-sizing: border-box;
    border: 1px solid #aeb6bf;
    border-radius: 3px;
    background: #fff;
    color: #333;
    font: inherit;
    cursor: pointer;

    &:focus-visible {
      outline: 2px solid #409eff;
      outline-offset: 1px;
    }
  }
}

.rock-chart {
  flex: 1;
  min-height: 0;
  display: flex;
  padding: 8px;
  box-sizing: border-box;
}

.rock-chart-plot-shell {
  flex: 1;
  position: relative;
  min-width: 0;
  min-height: 0;
}

.chart-instance {
  position: absolute;
  inset: 0;
  width: 100%;
}

.rock-chart-empty {
  position: absolute;
  inset: 0;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  background: transparent;
  color: #999;
  pointer-events: none;
}

.data-list-panel {
  flex: 1;
  min-height: 0;
  width: 100%;
  display: flex;
  overflow: hidden;
  background: #fff;
}

.rock-data-grid {
  flex: 1;
  height: 100%;
  width: 100%;
  min-width: 0;
  min-height: 0;
  display: grid;
  grid-template-columns: 70px minmax(140px, 1fr) minmax(180px, 1.4fr);
  /* 第一行是表头，其余数据行统一高度；超过可视区域后由表格自身滚动。 */
  grid-template-rows: 36px;
  grid-auto-rows: 30px;
  align-content: start;
  margin: 0;
  overflow: auto;
  box-sizing: border-box;
  border: 1px solid #d4d7db;
}

.rock-data-grid-cell {
  min-width: 0;
  border-right: 1px solid #d4d7db;
  border-bottom: 1px solid #d4d7db;
  background: #fff;
  padding: 0 8px;
  box-sizing: border-box;
  display: flex;
  align-items: center;
  color: #333;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;

  &.header {
    position: sticky;
    top: 0;
    z-index: 1;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 0 8px;
    box-sizing: border-box;
    background: #f4f4f4;
    color: #333;
    font-size: inherit;
    font-weight: 400;
    text-align: center;
    white-space: nowrap;
  }

  &.numeric {
    justify-content: flex-end;
    font-variant-numeric: tabular-nums;
  }

  &.row-index {
    justify-content: center;
    padding: 0 6px;
    background: #f4f4f4;
    color: #333;
  }
}

.bottom-chart-tabs {
  display: flex;
  align-items: flex-end;
  height: 30px;
  flex-shrink: 0;
  border-top: 1px solid #e4e7ed;
  background: #fff;
}

.bottom-chart-tab {
  height: 30px;
  min-width: 82px;
  padding: 0 14px;
  border: 0;
  border-right: 1px solid #e4e7ed;
  background: #fff;
  color: #333;
  font-size: 13px;
  cursor: pointer;

  &:hover {
    color: #409eff;
  }

  &.active {
    color: #409eff;
    font-weight: 600;
    background: #fff;
  }
}
</style>
