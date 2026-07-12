<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { fissureNpiTypeCurves, npiTypeCurves } from '@/constants/npiTypeCurves'
import { NODETYPE } from '@/constants/nodeType'

const props = defineProps({
  node: Object,
  projectId: [Number, String],
  gasReservoirId: [Number, String]
})

const activePanelTab = ref('input')
const activeChartTab = ref('chart')
const chartEl = ref(null)
const chartAreaEl = ref(null)
const paramsPanelEl = ref(null)
const paramsPanelWidth = ref(238)
const paramsCollapsed = ref(false)
const resizingParamsPanel = ref(false)
const legendPosition = ref({ x: null, y: null })
const draggingLegend = ref(false)
const legendDragOffset = ref({ x: 0, y: 0 })
const hiddenLegendNames = ref(new Set())
let chart = null

const raw = computed(() => props.node?.raw || {})
const nodeType = computed(() => props.node?.type ?? props.node?.treeNode?.nodeType ?? props.node?.raw?.nodeType)
const isFracturedNpi = computed(() => [
  NODETYPE.NodeType_FracturedVerticalWellTypicalCurveNPI,
  NODETYPE.NodeType_FracturedHorizontalWellTypicalCurveNPI
].includes(Number(nodeType.value)))
const activeTypeCurves = computed(() => isFracturedNpi.value ? fissureNpiTypeCurves : npiTypeCurves)
const input = computed(() => raw.value?.input || raw.value?.inputs || raw.value?.parameter || {})
const result = computed(() => ({
  ...(raw.value?.output || {}),
  ...(raw.value?.result || {}),
  ...(raw.value?.analysis || {})
}))
const chartItems = computed(() => raw.value?.chartItems || raw.value?.outputs?.[0]?.chartItems || [])
const records = computed(() => raw.value?.items || raw.value?.outputItems || raw.value?.records || [])

const getValue = (source, keys, fallback = '') => {
  for (const key of keys) {
    const value = source?.[key]
    if (value !== undefined && value !== null && value !== '') return value
  }
  return fallback
}
const inputValue = (keys, fallback = '') => getValue(input.value, keys, fallback)
const resultValue = (keys, fallback = '') => getValue(result.value, keys, fallback)
const methodLabel = (value, labels, fallback) => {
  if (value === undefined || value === null || value === '') return fallback
  if (typeof value === 'number' || /^\d+$/.test(String(value))) return labels[Number(value)] || String(value)
  return value
}

const gasType = computed(() => methodLabel(inputValue(['gasType'], 2), ['', '', '干气'], '干气'))
const fittingMode = computed(() => inputValue(['isSkipFitting'], false) ? '跳过拟合' : '自动拟合')
const waterGasRatioLimit = computed(() => inputValue(['minimumWaterGasRatio', 'waterGasRatioLimit'], -1))
const waterGasRatioEnabled = computed(() => Number(waterGasRatioLimit.value) > 0)

const inputGroups = computed(() => [
  {
    title: '气体性质',
    fields: [
      { label: '天然气类型', value: gasType.value, select: true },
      { label: '天然气比重(dless)', value: inputValue(['specificGravity']) },
      { label: 'H₂S摩尔百分含量(%)', value: inputValue(['hydrogenSulfide']) },
      { label: 'CO₂摩尔百分含量(%)', value: inputValue(['carbonDioxide']) },
      { label: 'N₂摩尔百分含量(%)', value: inputValue(['nitrogen']) }
    ]
  },
  {
    title: '计算方法',
    fields: [
      { label: '非烃气体修正方法', value: methodLabel(inputValue(['modificationMethod'], 0), ['Wichert-Aziz 修正方法', 'Carr-Kobayashi-Burrous 修正方法'], 'Wichert-Aziz 修正方法'), select: true },
      { label: '天然气偏差系数计算方法', value: methodLabel(inputValue(['deviationFactorMethod'], 0), ['Dranchuk-Abu-Kassem 方法', 'Dranchuk-Purvis-Robinson 方法', 'Hall-Yarborough 方法'], 'Dranchuk-Abu-Kassem 方法'), select: true },
      { label: '天然气粘度计算方法', value: methodLabel(inputValue(['viscosityMethod'], 0), ['Lee-Gonzalez-Eakin 方法', 'Carr-Kobayashi-Burrous 方法', 'Sutton 方法'], 'Lee-Gonzalez-Eakin 方法'), select: true }
    ]
  },
  {
    title: '控制参数',
    fields: [
      { label: '拟合方式', value: fittingMode.value, select: true },
      { label: '抽稀点数', value: inputValue(['dataSize'], 300) },
      { label: '粗扫数据点数量', value: inputValue(['initScanDataSize'], 10) },
      { label: '精扫数据点数量', value: inputValue(['fineScanDataSize'], 30) }
    ]
  },
  {
    title: '其它数据',
    fields: [
      { label: '原始地层压力(MPa)', value: inputValue(['originalFormationPressure']) },
      { label: '气井地层温度(℃)', value: inputValue(['formationTemperature']) },
      { label: '井筒半径(m)', value: inputValue(['wellboreRadius', 'wellRadius']) },
      { label: '动态地质储量(10⁸m³)', value: inputValue(['originalGasVolume']) },
      { label: '物质平衡方程类型', value: methodLabel(inputValue(['mbEquationType', 'materialBalanceType'], 0), ['封闭气藏', '定容气藏', '页岩气藏'], '封闭气藏'), select: true },
      { label: '储层厚度(m)', value: inputValue(['reservoirThickness']) },
      { label: '储层孔隙度(%)', value: inputValue(['reservoirPorosity']) },
      { label: '束缚水饱和度(%)', value: inputValue(['waterSaturation']) },
      { label: '地层水压缩系数(MPa⁻¹)', value: inputValue(['waterCompressionCoefficient']) },
      { label: '储层岩石压缩系数(MPa⁻¹)', value: inputValue(['rockCompressionCoefficient']) }
    ]
  }
])

const outputFields = computed(() => [
  { label: '渗透率(mD)', value: resultValue(['permeability']) },
  { label: '表皮系数(dless)', value: resultValue(['skinFactor']) },
  { label: '动态地质储量(10⁸m³)', value: resultValue(['originalGasVolume']) },
  { label: '井控面积(km²)', value: resultValue(['wellControlArea']) },
  { label: '井控半径(m)', value: resultValue(['wellControlRadius']) }
])

const SERIES_CONFIG = {
  pD: { name: 'pD-实际数据', color: '#7ee000' },
  regularizedPressure: { name: 'pD-实际数据', color: '#7ee000' },
  pDi: { name: 'pDi-实际数据', color: '#00a020' },
  regularizedPressureIntegral: { name: 'pDi-实际数据', color: '#00a020' },
  pDid: { name: 'pDid-实际数据', color: '#e60000' },
  regularizedPressureIntegralDerivative: { name: 'pDid-实际数据', color: '#e60000' }
}
const normalizedSeries = computed(() => {
  const series = new Map()
  chartItems.value.forEach(item => {
    const field = item.yAxisField || item.yField || item.field || ''
    const config = SERIES_CONFIG[field]
    if (!config) return
    const data = (item.data || item.items || []).map(point => {
      const x = Number(point.xValue ?? point.pseudotime ?? point.tcaDd ?? point.x)
      const y = Number(point.yValue ?? point[field] ?? point.y)
      return x > 0 && y > 0 ? [x, y] : null
    }).filter(Boolean)
    series.set(config.name, { ...config, data })
  })
  records.value.forEach(row => {
    const x = Number(row.pseudotime ?? row.tcaDd ?? row.xValue)
    if (!(x > 0)) return
    Object.entries(SERIES_CONFIG).forEach(([field, config]) => {
      const y = Number(row[field])
      if (!(y > 0)) return
      if (!series.has(config.name)) series.set(config.name, { ...config, data: [] })
      series.get(config.name).data.push([x, y])
    })
  })
  return [...series.values()]
})
const legendItems = computed(() => normalizedSeries.value.map(({ name, color }) => ({ name, color })))
const theoreticalSeries = computed(() => {
  const colors = ['#5470c6', '#91cc75', '#fac858', '#ee6666', '#73c0de', '#3ba272', '#fc8452', '#9a60b4', '#ea7ccc']
  const typeCurves = activeTypeCurves.value
  return Object.entries(typeCurves)
      .filter(([key, values]) => !['pD', 'pDi', 'pDid', 'tcaDd'].includes(key) && Array.isArray(values))
      .map(([name, values], index) => ({
        name,
        family: name.startsWith('pDid') ? 'pDid-实际数据' : name.startsWith('pDi') ? 'pDi-实际数据' : 'pD-实际数据',
        type: 'line',
        data: values.map((value, pointIndex) => [typeCurves.tcaDd[pointIndex], value]),
        showSymbol: false,
        silent: true,
        lineStyle: { width: 1, color: colors[index % colors.length], opacity: 0.8 }
      }))
})
const legendStyle = computed(() => legendPosition.value.x === null
    ? { top: '42px', right: '18px' }
    : { left: `${legendPosition.value.x}px`, top: `${legendPosition.value.y}px` })
const isLegendItemHidden = name => hiddenLegendNames.value.has(name)
const toggleLegendItem = name => {
  const next = new Set(hiddenLegendNames.value)
  next.has(name) ? next.delete(name) : next.add(name)
  hiddenLegendNames.value = next
  renderChartSoon()
}

const tableRows = computed(() => {
  if (records.value.length) {
    return records.value.map((row, index) => ({
      index: index + 1,
      pseudotime: row.pseudotime ?? row.tcaDd ?? row.xValue,
      regularizedPressure: row.regularizedPressure ?? row.pD,
      regularizedPressureIntegral: row.regularizedPressureIntegral ?? row.pDi,
      regularizedPressureIntegralDerivative: row.regularizedPressureIntegralDerivative ?? row.pDid
    }))
  }

  const rows = new Map()
  normalizedSeries.value.forEach(series => {
    const field = series.name === 'pD-实际数据'
        ? 'regularizedPressure'
        : series.name === 'pDi-实际数据'
          ? 'regularizedPressureIntegral'
          : 'regularizedPressureIntegralDerivative'
    series.data.forEach(([x, y]) => {
      const key = String(x)
      if (!rows.has(key)) rows.set(key, { pseudotime: x })
      rows.get(key)[field] = y
    })
  })
  return [...rows.values()]
      .sort((a, b) => Number(a.pseudotime) - Number(b.pseudotime))
      .map((row, index) => ({ index: index + 1, ...row }))
})

function renderChart() {
  if (!chart) return
  const visibleTheoreticalSeries = theoreticalSeries.value.filter(item => !hiddenLegendNames.value.has(item.family))
  const actualSeries = normalizedSeries.value
      .filter(item => !hiddenLegendNames.value.has(item.name))
      .map(item => ({
        name: item.name,
        type: 'scatter',
        data: item.data,
        symbolSize: 5,
        itemStyle: { color: item.color }
      }))
  const series = [...visibleTheoreticalSeries, ...actualSeries]
  chart.setOption({
    animation: false,
    title: { text: 'Normalized Pressure Integral', left: 'center', top: 8, textStyle: { fontSize: 14, fontWeight: 600 } },
    tooltip: { trigger: 'axis', axisPointer: { type: 'cross' } },
    legend: { show: false },
    grid: { left: 72, right: 52, top: 60, bottom: 58 },
    xAxis: {
      type: 'log', min: 0.0001, max: 100, name: 'tcaDA', nameLocation: 'middle', nameGap: 34,
      splitLine: { show: true, lineStyle: { color: '#dfe7f2' } },
      minorSplitLine: { show: true, lineStyle: { color: '#f0f4fa' } }
    },
    yAxis: {
      type: 'log', min: 0.01, max: 1000, name: 'pD,pDi,pDid', nameLocation: 'middle', nameGap: 48,
      splitLine: { show: true, lineStyle: { color: '#dfe7f2' } },
      minorSplitLine: { show: true, lineStyle: { color: '#f0f4fa' } }
    },
    series
  }, true)
}

const renderChartSoon = () => nextTick(() => requestAnimationFrame(() => {
  chart?.resize()
  renderChart()
}))
const toggleParamsPanel = () => {
  paramsCollapsed.value = !paramsCollapsed.value
  renderChartSoon()
}
function onParamsPanelResize(event) {
  if (!resizingParamsPanel.value) return
  const left = paramsPanelEl.value?.getBoundingClientRect().left || 0
  paramsPanelWidth.value = Math.max(238, Math.min(520, event.clientX - left))
  chart?.resize()
}
function stopParamsPanelResize() {
  resizingParamsPanel.value = false
  document.body.style.cursor = ''
  document.body.style.userSelect = ''
  window.removeEventListener('mousemove', onParamsPanelResize)
  window.removeEventListener('mouseup', stopParamsPanelResize)
}
function startParamsPanelResize(event) {
  event.preventDefault()
  resizingParamsPanel.value = true
  document.body.style.cursor = 'col-resize'
  document.body.style.userSelect = 'none'
  window.addEventListener('mousemove', onParamsPanelResize)
  window.addEventListener('mouseup', stopParamsPanelResize)
}
function onLegendDrag(event) {
  if (!draggingLegend.value || !chartAreaEl.value) return
  const rect = chartAreaEl.value.getBoundingClientRect()
  legendPosition.value = {
    x: Math.max(0, Math.min(rect.width - 80, event.clientX - rect.left - legendDragOffset.value.x)),
    y: Math.max(0, Math.min(rect.height - 30, event.clientY - rect.top - legendDragOffset.value.y))
  }
}
function stopLegendDrag() {
  draggingLegend.value = false
  document.body.style.cursor = ''
  document.body.style.userSelect = ''
  window.removeEventListener('mousemove', onLegendDrag)
  window.removeEventListener('mouseup', stopLegendDrag)
}
function startLegendDrag(event) {
  const legendRect = event.currentTarget.getBoundingClientRect()
  const areaRect = chartAreaEl.value.getBoundingClientRect()
  legendPosition.value = {
    x: legendPosition.value.x ?? legendRect.left - areaRect.left,
    y: legendPosition.value.y ?? legendRect.top - areaRect.top
  }
  legendDragOffset.value = { x: event.clientX - legendRect.left, y: event.clientY - legendRect.top }
  draggingLegend.value = true
  document.body.style.cursor = 'move'
  document.body.style.userSelect = 'none'
  window.addEventListener('mousemove', onLegendDrag)
  window.addEventListener('mouseup', stopLegendDrag)
}

watch(() => props.node, renderChartSoon, { deep: true })
watch(activeChartTab, renderChartSoon)
onMounted(() => {
  chart = echarts.init(chartEl.value)
  window.addEventListener('resize', renderChartSoon)
  renderChartSoon()
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', renderChartSoon)
  stopParamsPanelResize()
  stopLegendDrag()
  chart?.dispose()
})
</script>

<template>
  <div class="npi-wrap">
    <aside ref="paramsPanelEl" class="params-panel" :class="{ collapsed: paramsCollapsed }"
           :style="{ width: paramsCollapsed ? '22px' : `${paramsPanelWidth}px`, minWidth: paramsCollapsed ? '22px' : `${paramsPanelWidth}px` }">
      <div v-if="paramsCollapsed" class="panel-collapsed-tab" @click="toggleParamsPanel">参数设置</div>
      <template v-else>
        <div class="panel-head">
          <span>参数设置</span>
          <button class="panel-toggle" type="button" title="收起参数设置" @click="toggleParamsPanel">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor">
              <path d="M16,12V4H17V2H7V4H8V12L6,14V16H11.2V22H12.8V16H18V14L16,12Z" />
            </svg>
          </button>
        </div>
        <div v-if="activePanelTab === 'input'" class="panel-body">
          <template v-for="group in inputGroups" :key="group.title">
            <div class="sec-label">{{ group.title }}</div>
            <div class="field-grid">
              <div v-for="field in group.fields" :key="field.label" class="field">
                <label>{{ field.label }}</label>
                <el-select v-if="field.select" size="small" disabled :model-value="field.value" style="width:100%">
                  <el-option :label="field.value" :value="field.value" />
                </el-select>
                <el-input v-else size="small" readonly :model-value="field.value" />
              </div>
              <div v-if="group.title === '控制参数'" class="field">
                <div class="wgr-label-row"><span>生产水气比上限(m³/10⁴m³)</span><el-switch :model-value="waterGasRatioEnabled" disabled size="small" /></div>
                <el-input size="small" readonly :disabled="!waterGasRatioEnabled" :model-value="waterGasRatioLimit" />
              </div>
            </div>
          </template>
          <div class="sec-label">生产数据</div>
          <div class="btn-row"><el-button size="small">模板下载</el-button><el-button size="small">导入</el-button></div>
        </div>
        <div v-else class="panel-body">
          <div class="sec-label">输出结果</div>
          <div v-for="field in outputFields" :key="field.label" class="field"><label>{{ field.label }}</label><el-input size="small" readonly :model-value="field.value" /></div>
        </div>
        <div class="param-tabs">
          <div class="param-tab" :class="{ active: activePanelTab === 'input' }" @click="activePanelTab = 'input'">输入</div>
          <div class="param-tab" :class="{ active: activePanelTab === 'output' }" @click="activePanelTab = 'output'">输出</div>
        </div>
        <div class="params-resizer" @mousedown="startParamsPanelResize"></div>
      </template>
    </aside>

    <main ref="chartAreaEl" class="chart-area">
      <div class="chart-tabs">
        <button class="chart-tab" :class="{ active: activeChartTab === 'chart' }" @click="activeChartTab = 'chart'">结果分析图</button>
        <button class="chart-tab" :class="{ active: activeChartTab === 'table' }" @click="activeChartTab = 'table'">数据列表</button>
      </div>
      <div v-show="activeChartTab === 'chart'" ref="chartEl" class="chart-instance"></div>
      <div v-if="activeChartTab === 'chart' && legendItems.length" class="floating-chart-legend" :style="legendStyle" @mousedown="startLegendDrag">
        <div v-for="item in legendItems" :key="item.name" class="floating-legend-item" :class="{ hidden: isLegendItemHidden(item.name) }"
             @mousedown.stop @click.stop="toggleLegendItem(item.name)">
          <span class="legend-dot" :style="{ backgroundColor: isLegendItemHidden(item.name) ? 'transparent' : item.color, borderColor: item.color }"></span>
          <span>{{ item.name }}</span>
        </div>
      </div>
      <div v-if="activeChartTab === 'table'" class="data-list-panel">
        <el-table :data="tableRows" size="small" height="100%" border>
          <el-table-column prop="index" label="序号" width="70" />
          <el-table-column prop="pseudotime" label="物质平衡拟时间" min-width="160" />
          <el-table-column prop="regularizedPressure" label="重整压力(pD)" min-width="160" />
          <el-table-column prop="regularizedPressureIntegral" label="规整化压力积分(pDi)" min-width="190" />
          <el-table-column prop="regularizedPressureIntegralDerivative" label="规整化压力积分导数(pDid)" min-width="220" />
        </el-table>
      </div>
    </main>
  </div>
</template>

<style lang="scss" scoped>
.npi-wrap { display:flex; height:100%; min-height:0; background:#fff; overflow:hidden; }
.params-panel { min-width:238px; border-right:1px solid #e0e0e0; display:flex; flex-direction:column; position:relative; overflow:hidden; }
.params-panel.collapsed { border-right:0; }
.panel-head { height:34px; padding:0 12px; border-bottom:1px solid #eee; display:flex; align-items:center; justify-content:space-between; font-size:13px; }
.panel-toggle { width:22px; height:22px; border:0; background:transparent; color:#777; cursor:pointer; }
.panel-collapsed-tab { width:22px; height:76px; writing-mode:vertical-rl; display:flex; align-items:center; justify-content:center; border:1px solid #ddd; border-left:0; font-size:13px; cursor:pointer; }
.panel-body { flex:1; overflow:auto; padding:4px 12px 14px; }
.sec-label { margin:10px 0 7px; color:#333; font-size:13px; font-weight:500; }
.field-grid { display:grid; grid-template-columns:repeat(auto-fit,minmax(190px,1fr)); gap:0 24px; }
.field { margin-bottom:9px; }
.field label,.wgr-label-row span { display:block; margin-bottom:3px; color:#555; font-size:12px; }
.wgr-label-row { display:flex; align-items:center; justify-content:space-between; }
.btn-row { display:flex; gap:8px; }
.param-tabs { display:flex; height:30px; border-top:1px solid #ddd; }
.param-tab { flex:1; display:flex; align-items:center; justify-content:center; border-right:1px solid #ddd; font-size:13px; cursor:pointer; }
.param-tab.active { background:#f4d000; color:#1a1a1a; font-weight:600; }
.params-resizer { position:absolute; top:0; right:-3px; width:6px; height:100%; cursor:col-resize; z-index:4; }
.chart-area { flex:1; min-width:0; min-height:0; display:flex; flex-direction:column; position:relative; overflow:hidden; }
.chart-tabs { display:flex; height:34px; border-bottom:1px solid #e4e7ed; background:#fafafa; }
.chart-tab { padding:0 16px; border:0; border-right:1px solid #e4e7ed; border-bottom:2px solid transparent; background:transparent; color:#555; cursor:pointer; }
.chart-tab.active { color:#409eff; border-bottom-color:#409eff; background:#fff; font-weight:600; }
.chart-instance,.data-list-panel { flex:1; min-height:0; width:100%; }
.floating-chart-legend { position:absolute; z-index:6; display:flex; flex-direction:column; gap:4px; padding:6px 10px; border:1px solid #e4e7ed; background:rgba(255,255,255,.92); font-size:12px; cursor:move; user-select:none; }
.floating-legend-item { display:flex; align-items:center; gap:6px; line-height:18px; cursor:pointer; white-space:nowrap; }
.floating-legend-item.hidden { color:#999; opacity:.55; }
.legend-dot { width:10px; height:10px; border-radius:50%; border:1px solid transparent; }
</style>
