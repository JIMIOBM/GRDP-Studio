<!-- d:\shiyou\GRDP-Studio\vue\src\views\DataManagement\RockProperties.vue -->
<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
// import { rockPvtApi } from '@/api/rockPvt'

const props = defineProps({
  importedRows: { type: Array, default: () => [] },
  importedResultRows: { type: Array, default: () => [] },
  projectId: { type: [Number, String], required: true }
})

const emit = defineEmits(['result-tab-change'])

const DEFAULT_POROSITY = '25'

const activeResultTab = ref('数据列表')
const activeCurve = ref('曲线1')
const analysisTableCollapsed = ref(false)
const porosity = ref(DEFAULT_POROSITY)
const cementedSandstoneCompressibility = ref('0')
const carbonateCompressibility = ref('0')
const calculating = ref(false)
const calculationMode = ref('')
const curveOneSeries = ref([])
const selectedCurveOneSourceRow = ref(null)
const curveTwoSeries = ref([])
const selectedCurveTwoSourceRow = ref(null)
const curveColors = [
  '#1677ff', '#f56c6c', '#67c23a', '#e6a23c',
  '#8b5cf6', '#13c2c2', '#eb2f96', '#fa8c16'
]

const chartEl = ref(null)
let chart = null
let chartRenderFrame = null
let chartResizeTimer = null

const rockPropertyColumns = [
  '岩石孔隙度（%）'
]

const rockTableColumns = ['序号', ...rockPropertyColumns]

const rockCurveOptions = [
  {
    name: '曲线1',
    leftYAxis: '胶结砂岩压缩系数(MPa⁻¹)',
    leftTableColumn: '胶结砂岩压缩系数(MPa⁻¹)',
  },
  {
    name: '曲线2',
    leftYAxis: '碳酸盐岩压缩系数(MPa⁻¹)',
    leftTableColumn: '碳酸盐岩压缩系数(MPa⁻¹)',
  }
]

const activeCurveOption = computed(
  () => rockCurveOptions.find(item => item.name === activeCurve.value) ?? rockCurveOptions[0]
)

const analysisTableColumns = computed(() => [
  '序号',
  '岩石孔隙度（%）',
  activeCurveOption.value.leftTableColumn
])

const analysisDataCellCount = computed(() => analysisTableColumns.value.length * 25)

const rockGridTemplateColumns = computed(
  () => `48px repeat(${rockPropertyColumns.length}, minmax(145px, 1fr))`
)

const analysisGridTemplateColumns = computed(
  () => `48px repeat(${analysisTableColumns.value.length - 1}, minmax(0, 1fr))`
)

const analysisDataCells = computed(() => {
  const columnCount = analysisTableColumns.value.length
  const rowCount = Math.max(25, activeAnalysisRows.value.length)
  return Array.from({ length: columnCount * rowCount }, (_, cellIndex) => {
    const rowIndex = Math.floor(cellIndex / columnCount)
    const columnIndex = cellIndex % columnCount
    const row = activeAnalysisRows.value[rowIndex] ?? null
    const values = [
      String(rowIndex + 1),
      ...(row
        ? [
          Number(row.porosity).toFixed(2),
          Number(row.compressibility).toExponential(6)
        ]
        : [])
    ]
    return {
      key: `${activeCurve.value}-${rowIndex}-${columnIndex}`,
      value: values[columnIndex] ?? '',
      columnIndex
    }
  })
})

const rockDataCells = computed(() => {
  const rowCount = 28
  return Array.from({ length: rowCount * rockTableColumns.length }, (_, cellIndex) => {
    const rowIndex = Math.floor(cellIndex / rockTableColumns.length)
    const columnIndex = cellIndex % rockTableColumns.length
    const importedValue = columnIndex === 0
      ? undefined
      : props.importedRows[rowIndex]?.[columnIndex - 1]
    return {
      key: `${rowIndex}-${columnIndex}`,
      value: columnIndex === 0 ? String(rowIndex + 1) : (importedValue ?? ''),
      columnIndex,
      imported: importedValue !== undefined && importedValue !== null && importedValue !== ''
    }
  })
})

const activeAnalysisRows = computed(() => {
  if (activeCurve.value === '曲线1') return curveOneRows.value
  if (activeCurve.value === '曲线2') return curveTwoRows.value
  return []
})

const selectedCurveOneSeries = computed(
  () => curveOneSeries.value.find(
    curve => curve.sourceRow === selectedCurveOneSourceRow.value
  ) ?? curveOneSeries.value[0] ?? null
)
const curveOneRows = computed(
  () => selectedCurveOneSeries.value?.items ?? []
)
const selectedCurveTwoSeries = computed(
  () => curveTwoSeries.value.find(
    curve => curve.sourceRow === selectedCurveTwoSourceRow.value
  ) ?? curveTwoSeries.value[0] ?? null
)
const curveTwoRows = computed(
  () => selectedCurveTwoSeries.value?.items ?? []
)

const activeCurveHasData = computed(() => {
  if (activeCurve.value === '曲线1') return curveOneSeries.value.length > 0
  if (activeCurve.value === '曲线2') return curveTwoSeries.value.length > 0
  return false
})

const unwrapResponse = (response) =>
  response?.data?.data ?? response?.data ?? response ?? {}

const renderChart = () => {
  const element = chartEl.value
  if (activeResultTab.value !== '结果分析图' || !element) return
  if (element.clientWidth <= 0 || element.clientHeight <= 0) return
  if (!chart || chart.getDom() !== element) {
    chart?.dispose()
    chart = echarts.init(element)
  }

  let chartSeries = []
  if (activeCurve.value === '曲线1') {
    chartSeries = curveOneSeries.value.map((curve, index) => ({
      name: curve.name,
      type: 'line',
      showSymbol: false,
      smooth: true,
      lineStyle: { color: curve.color || curveColors[index], width: 1.5 },
      itemStyle: { color: curve.color || curveColors[index] },
      data: curve.items.map(row => [Number(row.porosity), Number(row.compressibility)])
    }))
  } else if (activeCurve.value === '曲线2') {
    chartSeries = curveTwoSeries.value.map((curve, index) => ({
      name: curve.name,
      type: 'line',
      showSymbol: false,
      smooth: true,
      lineStyle: { color: curve.color || curveColors[index], width: 1.5 },
      itemStyle: { color: curve.color || curveColors[index] },
      data: curve.items.map(row => [Number(row.porosity), Number(row.compressibility)])
    }))
  }

  chart.setOption({
    animation: false,
    color: chartSeries.map(s => s.lineStyle.color),
    title: {
      text: activeCurve.value === '曲线1'
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
      axisPointer: { type: 'line', lineStyle: { color: '#d936d0', width: 1 } },
      formatter: params => {
        const items = (Array.isArray(params) ? params : [params]).filter(i => i?.value)
        if (!items.length) return ''
        return [
          `孔隙度：${Number(items[0].value[0]).toFixed(2)} %`,
          ...items.map(item =>
            `${item.marker}${item.seriesName}：${Number(items[0].value[1]).toExponential(6)} MPa⁻¹`
          )
        ].join('<br/>')
      }
    },
    xAxis: {
      type: 'value',
      name: '岩石孔隙度 φ(%)', nameLocation: 'middle', nameGap: 34,
      min: 0, max: 40,
      splitLine: { show: true, lineStyle: { color: '#dce5f2' } }
    },
    yAxis: [{
      type: 'value',
      name: activeCurveOption.value.leftYAxis,
      nameLocation: 'middle', nameGap: 44,
      axisLabel: { formatter: v => Number(v).toExponential(2) },
      splitLine: { lineStyle: { color: '#dce5f2' } }
    }],
    series: chartSeries
  }, true)
  chart.resize()
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

const handleCalculate = async (calculateAnalysis = false) => {
  if (calculating.value) return

  const calcPorosity = Number(porosity.value)
  if (!Number.isFinite(calcPorosity)) {
    ElMessage.error('请检查岩石孔隙度是否为有效数字')
    return
  }

  calculating.value = true
  calculationMode.value = calculateAnalysis ? 'analysis' : 'point'

  try {
    if (!calculateAnalysis) {
      // 单点计算 - 使用默认方法计算两个结果
      // TODO: 替换为真实API调用
      cementedSandstoneCompressibility.value = (1.5e-3).toExponential(6)
      carbonateCompressibility.value = (0.8e-3).toExponential(6)
      ElMessage.success('单点计算完成')
      return
    }

    // 整体分析计算
    const calculatedCurveOne = []
    const calculatedCurveTwo = []

    for (let i = 0; i < props.importedRows.length; i++) {
      const row = props.importedRows[i]
      const rowPorosity = Number(row?.[0] ?? porosity.value)

      // 模拟生成曲线数据（TODO: 替换为真实API调用）
      const items = []
      for (let p = 5; p <= 40; p += 2) {
        items.push({
          porosity: p,
          compressibility: (1.5e-3 * Math.exp(-p / 30))
        })
      }

      calculatedCurveOne.push({
        sourceRow: i + 1,
        name: `序号${i + 1}`,
        color: curveColors[i % curveColors.length],
        items
      })

      calculatedCurveTwo.push({
        sourceRow: i + 1,
        name: `序号${i + 1}`,
        color: curveColors[i % curveColors.length],
        items: items.map(item => ({
          ...item,
          compressibility: item.compressibility * 0.53
        }))
      })
    }

    curveOneSeries.value = calculatedCurveOne
    selectedCurveOneSourceRow.value = calculatedCurveOne[0]?.sourceRow ?? null
    curveTwoSeries.value = calculatedCurveTwo
    selectedCurveTwoSourceRow.value = calculatedCurveTwo[0]?.sourceRow ?? null
    activeCurve.value = '曲线1'
    hasAnalysisResult.value = true
    ElMessage.success(`${calculatedCurveOne.length} 条岩石数据计算完成`)
  } catch (error) {
    ElMessage.error(error.message || '岩石性质计算失败')
  } finally {
    calculating.value = false
    calculationMode.value = ''
  }
}

const handleReset = () => {
  porosity.value = DEFAULT_POROSITY
  cementedSandstoneCompressibility.value = '0'
  carbonateCompressibility.value = '0'
  curveOneSeries.value = []
  selectedCurveOneSourceRow.value = null
  curveTwoSeries.value = []
  selectedCurveTwoSourceRow.value = null
  activeCurve.value = '曲线1'
  analysisTableCollapsed.value = false
}

watch(
  () => props.importedResultRows,
  (rows) => {
    if (!Array.isArray(rows) || !rows.length) return

    const items = rows.map(row => ({
      porosity: Number(row.porosity),
      compressibility: Number(row.compressibility)
    }))
    const importedSeries = {
      sourceRow: 1,
      name: '导入结果',
      color: curveColors[0],
      items
    }

    curveOneSeries.value = [{ ...importedSeries }]
    selectedCurveOneSourceRow.value = 1
    curveTwoSeries.value = [{ ...importedSeries }]
    selectedCurveTwoSourceRow.value = 1
    activeCurve.value = '曲线1'
    scheduleRenderChart()
  },
  { deep: true }
)

watch(activeResultTab, (value) => {
  emit('result-tab-change', value)
  if (value !== '结果分析图') { disposeChart(); return }
  scheduleRenderChart()
}, { immediate: true })

watch([
  activeCurve,
  curveOneRows,
  curveOneSeries,
  curveTwoRows,
  curveTwoSeries
], () => {
  if (activeResultTab.value === '结果分析图') scheduleRenderChart()
})

watch(analysisTableCollapsed, () => {
  if (activeResultTab.value !== '结果分析图') return
  scheduleRenderChart()
  if (chartResizeTimer !== null) clearTimeout(chartResizeTimer)
  chartResizeTimer = setTimeout(() => { chartResizeTimer = null; scheduleRenderChart() }, 180)
})

const handleResize = () => { if (activeResultTab.value === '结果分析图') scheduleRenderChart() }
window.addEventListener('resize', handleResize)

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  if (chartRenderFrame !== null) cancelAnimationFrame(chartRenderFrame)
  if (chartResizeTimer !== null) clearTimeout(chartResizeTimer)
  disposeChart()
})
</script>

<template>
  <div class="rock-properties-view">
    <div v-if="activeResultTab === '数据列表'" class="rock-workspace">
      <aside class="rock-parameter-panel">
        <div class="rock-parameter-section">
          <div class="rock-section-heading">
            <span>输入参数</span>
            <span class="rock-section-rule"></span>
          </div>

          <label class="rock-field-group">
            <span>岩石孔隙度（%）</span>
            <input v-model="porosity" type="number" step="any" inputmode="decimal" />
          </label>

          <div class="rock-parameter-actions">
            <button type="button" :disabled="calculating" @click="handleCalculate()">
              {{ calculationMode === 'point' ? '计算中...' : '计算' }}
            </button>
            <button type="button" :disabled="calculating" @click="handleReset">重置</button>
          </div>
        </div>
      </aside>

      <div class="rock-data-grid" aria-label="岩石性质数据表格" :style="{ gridTemplateColumns: rockGridTemplateColumns }">
        <div v-for="column in rockTableColumns" :key="column" class="rock-grid-cell header">
          {{ column }}
        </div>
        <div v-for="(cell, index) in rockDataCells" :key="cell.key" class="rock-grid-cell" :class="{
          imported: cell.imported,
          numeric: cell.columnIndex > 0,
          'row-index': cell.columnIndex === 0
        }">
          {{ cell.value }}
        </div>
      </div>
    </div>

    <div v-else class="rock-analysis-workspace" :class="{ 'table-collapsed': analysisTableCollapsed }">
      <aside class="rock-analysis-panel" :class="{ collapsed: analysisTableCollapsed }">
        <button v-if="analysisTableCollapsed" class="rock-analysis-collapsed-tab" type="button" title="展开分析数据表"
          @click="analysisTableCollapsed = false">
          图表数据
        </button>

        <template v-else>
          <div class="rock-analysis-expanded">
            <div class="rock-analysis-panel-heading">
              <span>图表数据</span>
              <select v-if="activeCurve === '曲线1' && curveOneSeries.length" v-model="selectedCurveOneSourceRow"
                class="rock-analysis-series-select" aria-label="选择曲线1图表数据">
                <option v-for="curve in curveOneSeries" :key="curve.sourceRow" :value="curve.sourceRow">
                  {{ curve.name }}
                </option>
              </select>
              <select v-if="activeCurve === '曲线2' && curveTwoSeries.length" v-model="selectedCurveTwoSourceRow"
                class="rock-analysis-series-select" aria-label="选择曲线2图表数据">
                <option v-for="curve in curveTwoSeries" :key="curve.sourceRow" :value="curve.sourceRow">
                  {{ curve.name }}
                </option>
              </select>
              <button class="rock-analysis-toggle" type="button" title="收起图表数据" @click="analysisTableCollapsed = true">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="#777">
                  <path d="M16,12V4H17V2H7V4H8V12L6,14V16H11.2V22H12.8V16H18V14L16,12Z" />
                </svg>
              </button>
            </div>
            <div class="rock-analysis-grid" aria-label="岩石分析数据表格"
              :style="{ gridTemplateColumns: analysisGridTemplateColumns }">

              <!-- 表头 -->
              <div v-for="column in analysisTableColumns" :key="column" class="rock-analysis-grid-cell header">
                {{ column }}
              </div>

              <!-- 数据单元格 -->
              <div v-for="cell in analysisDataCells" :key="cell.key" class="rock-analysis-grid-cell" :class="{
                numeric: cell.value !== '',
                'row-index': cell.columnIndex === 0
              }">
                {{ cell.value }}
              </div>
            </div>
          </div>
        </template>
      </aside>

      <section class="rock-chart-panel" aria-label="岩石结果分析图">
        <div class="rock-curve-selector">
          <label v-for="curve in rockCurveOptions" :key="curve.name">
            <input v-model="activeCurve" type="radio" :value="curve.name" />
            <span>{{ curve.name }}</span>
          </label>
        </div>

        <div class="rock-chart">
          <div class="rock-chart-plot-shell">
            <div ref="chartEl" class="rock-chart-plot"></div>
            <div v-if="!activeCurveHasData" class="rock-chart-empty">
              暂无计算结果
            </div>
          </div>
        </div>
      </section>
    </div>

    <footer class="rock-result-tabs">
      <button type="button" class="rock-result-tab" :class="{ active: activeResultTab === '数据列表' }"
        @click="activeResultTab = '数据列表'">
        数据列表
      </button>
      <button type="button" class="rock-result-tab" :class="{ active: activeResultTab === '结果分析图' }"
        @click="activeResultTab = '结果分析图'">
        结果分析图
      </button>
    </footer>
  </div>
</template>

<style lang="scss" scoped>
.rock-properties-view {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.rock-workspace {
  flex: 1;
  min-height: 0;
  display: flex;
  gap: 0;
  padding: 10px 12px;
  box-sizing: border-box;
  overflow: hidden;
}

.rock-analysis-workspace {
  flex: 1;
  min-height: 0;
  display: flex;
  gap: 0;
  padding: 10px 12px;
  box-sizing: border-box;
  overflow: hidden;
}

.rock-analysis-workspace.table-collapsed {
  padding: 10px 12px;
}

.rock-analysis-panel {
  width: 760px;
  flex: 0 0 760px;
  height: 100%;
  min-height: 0;
  align-self: stretch;
  display: flex;
  position: relative;
  transition: width 0.16s ease, flex-basis 0.16s ease;

  &.collapsed {
    width: 34px;
    flex-basis: 34px;
    height: 100%;
    border: 1px solid #d4d7db;
    border-right: 0;
    box-sizing: border-box;
    background: #fff;
  }
}

.rock-analysis-expanded {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  border: 1px solid #d4d7db;
  border-right: 0;
  background: #fff;
  overflow: hidden;
}

.rock-analysis-panel-heading {
  height: 36px;
  flex: 0 0 36px;
  padding: 0 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  box-sizing: border-box;
  border-bottom: 1px solid #e2e6ea;
  color: #222;
  font-weight: 400;
}

.rock-analysis-series-select {
  height: 26px;
  padding: 0 6px;
  border: 1px solid #aeb6bf;
  border-radius: 3px;
  background: #fff;
  color: #333;
  font-size: 13px;
  outline: none;

  &:focus {
    border-color: #4c81b6;
    box-shadow: 0 0 0 1px rgba(76, 129, 182, 0.18);
  }
}

.rock-analysis-grid {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: grid;
  grid-template-columns: repeat(var(--analysis-column-count), minmax(0, 1fr));
  grid-template-rows: 42px repeat(25, minmax(30px, 1fr));
  overflow: hidden;
}

.rock-analysis-toggle {
  width: 22px;
  height: 22px;
  padding: 0;
  border: 0;
  border-radius: 2px;
  background: transparent;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;

  &:hover {
    background: #eef4ff;
  }
}

.rock-analysis-collapsed-tab {
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
    color: #1677ff;
  }
}

.rock-analysis-grid-cell {
  min-width: 0;
  border-right: 1px solid #d4d7db;
  border-bottom: 1px solid #d4d7db;
  background: #fff;

  &.header {
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 4px 6px;
    box-sizing: border-box;
    background: #f4f4f4;
    color: #333;
    font-size: inherit;
    font-weight: 400;
    line-height: 1.35;
    text-align: center;
    white-space: nowrap;
  }
}

.rock-chart-panel {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid #d4d7db;
  background: #fff;
}

.rock-curve-selector {
  height: 36px;
  flex: 0 0 36px;
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 0 14px;
  box-sizing: border-box;
  border-bottom: 1px solid #e2e6ea;
  background: #fafbfc;

  label {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    color: #222;
    cursor: pointer;
    white-space: nowrap;
  }

  input {
    width: 14px;
    height: 14px;
    margin: 0;
    accent-color: #1677ff;
  }
}

.rock-chart {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 56px minmax(0, 1fr) 56px;
  grid-template-rows: minmax(0, 1fr) 32px;
  padding: 16px 18px 8px 10px;
  box-sizing: border-box;
}

.rock-chart-plot-shell {
  grid-column: 2;
  position: relative;
  min-height: 0;
}

.rock-chart-plot {
  width: 100%;
  height: 100%;
  min-height: 0;
}

.rock-chart-empty {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #999;
  font-size: 14px;
  background: rgba(255, 255, 255, 0.85);
}

.rock-chart-y-title {
  display: flex;
  align-items: center;
  justify-content: center;
  color: #111;
  white-space: nowrap;
  line-height: 1;
}

.rock-chart-y-title-left {
  transform: rotate(-90deg);
}

.rock-chart-y-title-right {
  grid-column: 3;
  transform: rotate(90deg);
}

.rock-chart-x-title {
  grid-column: 2;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #111;
}

.rock-parameter-panel {
  width: 260px;
  flex: 0 0 260px;
  padding: 12px 14px;
  box-sizing: border-box;
  background: #fff;
  border: 1px solid #d4d7db;
  border-right: 0;
  overflow-y: auto;
}

.rock-parameter-section+.rock-parameter-section {
  margin-top: 16px;
}

.rock-section-heading {
  height: 22px;
  display: flex;
  align-items: center;
  gap: 4px;
  white-space: nowrap;
}

.rock-section-rule {
  height: 1px;
  flex: 1;
  background: #c8cdd3;
}

.rock-field-group {
  display: block;
  margin-top: 10px;
  color: #404040;

  >span {
    display: block;
    margin-bottom: 4px;
    line-height: 18px;
  }

  select,
  input {
    width: 100%;
    height: 30px;
    padding: 2px 8px;
    box-sizing: border-box;
    border: 1px solid #aeb6bf;
    border-radius: 3px;
    background: #fff;
    color: #333;
    font: inherit;
    outline: none;

    &:focus {
      border-color: #4c81b6;
      box-shadow: 0 0 0 1px rgba(76, 129, 182, 0.18);
    }
  }

  &.rock-result-field input {
    background: #f5f5f5;
    color: #666;
  }
}

.rock-parameter-actions {
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
      outline-offset: 2px;
    }

    &:active {
      background: #ebebeb;
    }

    &:disabled {
      border-color: #c8c8c8;
      background: #f3f3f3;
      color: #999;
      cursor: not-allowed;
    }
  }
}

.rock-overall-action {
  margin-top: 16px;
}

.rock-result-field {
  margin-top: 16px;

  input {
    background: #f5f5f5;
    color: #333;
    cursor: default;
  }
}

.rock-data-grid {
  flex: 1;
  min-width: 0;
  display: grid;
  grid-template-columns: 48px repeat(1, minmax(145px, 1fr));
  grid-template-rows: 36px repeat(27, minmax(30px, 1fr));
  margin: 0;
  overflow: hidden;
  border: 1px solid #d4d7db;
}

.rock-grid-cell {
  min-width: 0;
  border-right: 1px solid #d4d7db;
  border-bottom: 1px solid #d4d7db;
  background: #fff;
  padding: 4px 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #333;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;

  &.header {
    background: #f4f4f4;
    color: #333;
    font-weight: 400;
    text-align: center;
    white-space: nowrap;
  }

  &.imported {
    background: #fbfdff;
  }

  &.numeric {
    font-family: "Consolas", "Monaco", monospace;
  }

  &.row-index {
    justify-content: center;
    padding: 0 6px;
    background: #f4f4f4;
    color: #333;
  }
}

.rock-result-tabs {
  height: 32px;
  flex: 0 0 32px;
  display: flex;
  align-items: flex-end;
  padding-left: 12px;
  box-sizing: border-box;
  border-top: 1px solid #e4e7ed;
  background: #fff;
}

.rock-result-tab {
  min-width: 88px;
  height: 32px;
  padding: 0 16px;
  border: 0;
  border-right: 1px solid #e4e7ed;
  border-top: 2px solid #e4e7ed;
  background: #fff;
  color: #555;
  font: inherit;
  cursor: pointer;
  white-space: nowrap;

  &:hover {
    color: #111;
  }

  &.active {
    color: #111;
    border-top-color: #111;
    font-weight: 600;
  }
}

@media (max-width: 950px) {
  .rock-parameter-panel {
    width: 240px;
    flex-basis: 240px;
  }

  .rock-data-grid {
    grid-template-columns: 48px repeat(1, minmax(130px, 1fr));
  }

  .rock-analysis-panel {
    width: 640px;
    flex-basis: 640px;
  }
}
</style>