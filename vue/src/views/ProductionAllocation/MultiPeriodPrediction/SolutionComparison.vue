d:\shiyou\GRDP-Studio\vue\src\views\ProductionAllocation\MultiPeriodPrediction\SolutionComparison.vue
<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { solutionRecordApi } from '@/api/solutionGeneration'

const props = defineProps({
  wellName: { type: String, default: '' },
  projectId: { type: [Number, String], required: true },
  gasReservoirId: { type: [Number, String], required: true }
})

const COLORS = ['#e6194b', '#3cb44b', '#4363d8', '#f58231', '#911eb4', '#008080', '#800000', '#000075']
const METRICS = [
  { key: 'gas', label: '气产量', unit: '10⁴m³/d' },
  { key: 'cumulative', label: '累产气量', unit: '10⁸m³' },
  { key: 'formation', label: '地层压力', unit: 'MPa' }
]

const solutions = ref([])
const selectedIds = ref([])
const loading = ref(false)
const loadError = ref('')
const activeChartRef = ref(null)
const activeChartIndex = ref(0)
const CHART_LABELS = ['气产量', '累产气量', '地层压力']
let chartInstances = []
let destroyed = false
let loadVersion = 0

const normalizeRow = row => ({
  ...row,
  id: String(row.id ?? ''),
  name: row.recordName || row.record_name || `方案${row.id}`
})
const field = (row, camel, snake) => row?.[camel] ?? row?.[snake]

function parseObject(value) {
  let parsed = value
  for (let attempt = 0; attempt < 2 && typeof parsed === 'string'; attempt += 1) {
    try { parsed = JSON.parse(parsed) } catch { return null }
  }
  return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : null
}

function numberOrNull(value) {
  if (value == null || value === '' || !Number.isFinite(Number(value))) return null
  return Number(value)
}

function assessSolution(row) {
  const chartData = parseObject(field(row, 'chartDataJson', 'chart_data_json'))
  const boundaryReport = parseObject(field(row, 'boundaryReportJson', 'boundary_report_json'))
  const rawPoints = Array.isArray(chartData?.points) ? chartData.points : []
  const points = rawPoints
    .filter(point => point && typeof point.date === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(point.date))
    .map(point => ({
      date: point.date,
      gas: numberOrNull(point.gas),
      cumulative: numberOrNull(point.cumulative),
      formation: numberOrNull(point.formation)
    }))
    .sort((left, right) => left.date.localeCompare(right.date))

  let reason = ''
  const reportCount = Number(boundaryReport?.count)
  const violatingPoints = Number(boundaryReport?.violatingPoints)
  if (!chartData) reason = '缺少预测曲线快照'
  else if (chartData.calcReturnedError === true) reason = 'Calc 曾返回异常'
  else if (chartData.freshChartConfirmed !== true) reason = '曲线未确认更新'
  else if (!boundaryReport || !Number.isFinite(reportCount) || reportCount < 1) reason = '缺少有效气量边界校验'
  else if (!Number.isFinite(violatingPoints) || violatingPoints > 0) reason = `有 ${Number.isFinite(violatingPoints) ? violatingPoints : '未知'} 个气量点超界`
  else if (METRICS.some(metric => !points.some(point => point[metric.key] !== null))) reason = '缺少完整的气量、累产气量或压力曲线'

  const bounds = chartData?.appliedBoundaries || null
const lower = numberOrNull(bounds?.lower)
const upper = numberOrNull(bounds?.upper)
const pressure = numberOrNull(bounds?.pressure)

if (!reason && pressure !== null) {
  const pressureViolations = points.filter(p =>
    p.formation !== null && p.formation < pressure
  ).length
  if (pressureViolations > 0) {
    reason = `有 ${pressureViolations} 个点地层压力低于外输压力要求`
  }
}

return { ...row, chartData, points, boundaryReport, bounds, lower, upper, pressure, eligible: !reason, reason: reason || '气量界限内' }
}

const eligibleSolutions = computed(() => solutions.value.filter(solution => solution.eligible))
const excludedCount = computed(() => solutions.value.length - eligibleSolutions.value.length)
const allSolutionsSelected = computed(() => solutions.value.length > 0 &&
  solutions.value.every(solution => selectedIds.value.includes(solution.id)))
const canCompare = computed(() => selectedIds.value.length >= 2)
const selectedSolutions = computed(() => selectedIds.value
  .map(id => solutions.value.find(solution => solution.id === id))
  .filter(Boolean)
  .map(solution => ({
    ...solution,
    color: solutionColor(solutions.value.findIndex(item => item.id === solution.id))
  })))

function solutionColor(index) {
  if (index < COLORS.length) return COLORS[index]
  const hue = (index * 137.508) % 360
  const saturation = 0.58
  const lightness = 0.44
  const chroma = (1 - Math.abs(2 * lightness - 1)) * saturation
  const secondary = chroma * (1 - Math.abs((hue / 60) % 2 - 1))
  const offset = lightness - chroma / 2
  const rgb = hue < 60 ? [chroma, secondary, 0]
    : hue < 120 ? [secondary, chroma, 0]
      : hue < 180 ? [0, chroma, secondary]
        : hue < 240 ? [0, secondary, chroma]
          : hue < 300 ? [secondary, 0, chroma]
            : [chroma, 0, secondary]
  return `#${rgb.map(value => Math.round((value + offset) * 255).toString(16).padStart(2, '0')).join('')}`
}

async function loadSolutions() {
  const version = ++loadVersion
  if (!props.wellName) {
    solutions.value = []
    selectedIds.value = []
    return
  }
  loading.value = true
  loadError.value = ''
  solutions.value = []
  selectedIds.value = []
  try {
    const response = await solutionRecordApi.list(
      String(props.projectId), String(props.gasReservoirId), props.wellName)
    if (destroyed || version !== loadVersion) return
    const rows = Array.isArray(response?.data) ? response.data
      : Array.isArray(response) ? response : []
    solutions.value = rows
      .filter(row => String(field(row, 'projectId', 'project_id')) === String(props.projectId) &&
        String(field(row, 'gasReservoirId', 'gas_reservoir_id')) === String(props.gasReservoirId) &&
        String(field(row, 'wellName', 'well_name')).trim() === props.wellName.trim())
      .map(normalizeRow)
      .map(assessSolution)
  } catch (error) {
    if (destroyed || version !== loadVersion) return
    loadError.value = error?.message || '方案列表加载失败'
    console.warn('[方案比选] 加载方案列表失败', error)
  } finally {
    if (version === loadVersion) loading.value = false
  }
}

function toggleSelectAll() {
  if (allSolutionsSelected.value) {
    selectedIds.value = []
    return
  }
  selectedIds.value = solutions.value.map(solution => solution.id)
}

function toggleSolution(solution) {
  if (selectedIds.value.includes(solution.id)) {
    selectedIds.value = selectedIds.value.filter(id => id !== solution.id)
    return
  }
  selectedIds.value = [...selectedIds.value, solution.id]
}

function addGasSamplesForMetricDates(solution, metric) {
  if (metric.key === 'gas') return solution

  const dayMs = 24 * 60 * 60 * 1000
  const gasSamples = solution.points.flatMap(point => {
    const time = Date.parse(`${point.date}T00:00:00Z`)
    return point.gas !== null && Number.isFinite(time) ? [{ time, value: point.gas }] : []
  })
  if (gasSamples.length < 2) return solution

  const gaps = gasSamples.slice(1)
    .map((sample, index) => sample.time - gasSamples[index].time)
    .filter(gap => gap > 0)
    .sort((left, right) => left - right)
  const typicalGap = gaps.length ? gaps[Math.floor(gaps.length / 2)] : dayMs
  const maximumGap = Math.max(typicalGap * 3, 30 * dayMs)
  let sampleIndex = 0
  const points = solution.points.map(point => {
    const time = Date.parse(`${point.date}T00:00:00Z`)
    if (point.gas !== null || !Number.isFinite(time)) return point
    while (sampleIndex + 1 < gasSamples.length && gasSamples[sampleIndex + 1].time < time) {
      sampleIndex += 1
    }
    const left = gasSamples[sampleIndex]
    const right = gasSamples[sampleIndex + 1]
    if (!left || !right || time < left.time || time > right.time || right.time - left.time > maximumGap) {
      return point
    }
    const ratio = (time - left.time) / (right.time - left.time)
    return { ...point, gas: left.value + (right.value - left.value) * ratio }
  })
  return { ...solution, points }
}

function clippedSegments(solution, metric) {
  const lower = solution.lower
  const upper = solution.upper
  if ((lower === null && upper === null) ||
      (lower !== null && upper !== null && lower > upper)) return []

  const minimumGas = lower === null ? -Infinity : lower - Math.max(1e-6, Math.abs(lower) * 1e-8)
  const maximumGas = upper === null ? Infinity : upper + Math.max(1e-6, Math.abs(upper) * 1e-8)
  const segments = []
  let current = []
  const flush = () => {
    if (current.length > 1 && !samePoint(current[0], current[current.length - 1])) {
      segments.push(current)
    }
    current = []
  }
  const samePoint = (left, right) =>
    Math.abs(left.time - right.time) < 1 && Math.abs(left.value - right.value) < 1e-9

  for (let index = 0; index < solution.points.length - 1; index += 1) {
    const left = solution.points[index]
    const right = solution.points[index + 1]
    const leftTime = Date.parse(`${left.date}T00:00:00Z`)
    const rightTime = Date.parse(`${right.date}T00:00:00Z`)
    const leftValue = left[metric.key]
    const rightValue = right[metric.key]
    if (left.gas === null || right.gas === null || leftValue === null || rightValue === null ||
        !Number.isFinite(leftTime) || !Number.isFinite(rightTime)) {
      flush()
      continue
    }

    const gasDelta = right.gas - left.gas
    let startRatio = 0
    let endRatio = 1
    if (metric.key === 'gas') {
      if (gasDelta === 0) {
        if (left.gas < minimumGas || left.gas > maximumGas) {
          flush()
          continue
        }
      } else {
        if (lower !== null) {
          const crossing = (minimumGas - left.gas) / gasDelta
          if (gasDelta > 0) startRatio = Math.max(startRatio, crossing)
          else endRatio = Math.min(endRatio, crossing)
        }
        if (upper !== null) {
          const crossing = (maximumGas - left.gas) / gasDelta
          if (gasDelta > 0) endRatio = Math.min(endRatio, crossing)
          else startRatio = Math.max(startRatio, crossing)
        }
        startRatio = Math.max(0, startRatio)
        endRatio = Math.min(1, endRatio)
        if (startRatio > endRatio) {
          flush()
          continue
        }
      }
    }

    if (metric.key === 'formation' && solution.pressure !== null) {
      const minimumPressure = solution.pressure - Math.max(1e-6, Math.abs(solution.pressure) * 1e-8)
      if (leftValue === null || rightValue === null) {
        flush()
        continue
      }
      const pressureDelta = rightValue - leftValue
      if (pressureDelta === 0) {
        if (leftValue < minimumPressure) {
          flush()
          continue
        }
      } else {
        const pressureCrossing = (minimumPressure - leftValue) / pressureDelta
        if (pressureDelta > 0) startRatio = Math.max(startRatio, pressureCrossing)
        else endRatio = Math.min(endRatio, pressureCrossing)
        startRatio = Math.max(0, startRatio)
        endRatio = Math.min(1, endRatio)
        if (startRatio > endRatio) {
          flush()
          continue
        }
      }
    }

    const sampleAt = ratio => {
      const interpolatedGas = left.gas + gasDelta * ratio
      let value = leftValue + (rightValue - leftValue) * ratio
      if (metric.key === 'gas') {
        value = Math.max(lower ?? -Infinity, Math.min(upper ?? Infinity, interpolatedGas))
      }
      return {
        time: leftTime + (rightTime - leftTime) * ratio,
        value
      }
    }
    const startPoint = sampleAt(startRatio)
    const endPoint = sampleAt(endRatio)
    if (current.length && samePoint(current[current.length - 1], startPoint)) {
      if (!samePoint(current[current.length - 1], endPoint)) current.push(endPoint)
    } else {
      flush()
      current = [startPoint, endPoint]
    }
    if (endRatio < 1) flush()
  }
  flush()

  return segments
}

function makeSeries(metric) {
  return selectedSolutions.value
    .map(solution => {
      const boundarySamples = addGasSamplesForMetricDates(solution, metric)
      const segments = clippedSegments(boundarySamples, metric)
      if (segments.length === 0) return null
      const data = []
      for (let i = 0; i < segments.length; i++) {
        if (i > 0) data.push(null)
        for (const point of segments[i]) {
          data.push([new Date(point.time).toISOString(), point.value])
        }
      }
      return {
        name: solution.name,
        type: 'line',
        data,
        showSymbol: false,
        smooth: false,
        connectNulls: false,
        lineStyle: { color: solution.color, width: 2 },
        itemStyle: { color: solution.color },
        emphasis: { focus: 'series', lineStyle: { width: 3 } }
      }
    })
    .filter(Boolean)
}

const hasVisibleSegments = computed(() => makeSeries(METRICS[activeChartIndex.value]).length > 0)

function renderComparisonCharts() {
  if (!canCompare.value || destroyed) {
    chartInstances.forEach(chart => chart?.clear())
    return
  }
  const element = activeChartRef.value
  if (!element) return

  const allDates = selectedSolutions.value.flatMap(solution => solution.points.map(point => point.date)).sort()
  let minimumTime = Date.parse(`${allDates[0]}T00:00:00Z`)
  let maximumTime = Date.parse(`${allDates[allDates.length - 1]}T00:00:00Z`)
  if (minimumTime === maximumTime) {
    minimumTime -= 12 * 60 * 60 * 1000
    maximumTime += 12 * 60 * 60 * 1000
  }

  const metric = METRICS[activeChartIndex.value]
  if (!chartInstances[0] || chartInstances[0].getDom() !== element) {
    chartInstances[0]?.dispose()
    chartInstances[0] = echarts.init(element)
  }
  chartInstances[0].setOption({
    animationDuration: 250,
    color: selectedSolutions.value.map(solution => solution.color),
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'line' },
      valueFormatter: value => value == null ? '—' : `${Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 4 })} ${metric.unit}`
    },
    grid: { left: 70, right: 22, top: 14, bottom: 34, containLabel: true },
    xAxis: {
      type: 'time',
      min: minimumTime,
      max: maximumTime,
      boundaryGap: false,
      axisLabel: { hideOverlap: true, formatter: '{yyyy}-{MM}' },
      splitLine: { show: false }
    },
    yAxis: {
      type: 'value',
      name: metric.unit,
      min: 0,
      nameTextStyle: { padding: [0, 0, 0, 8] },
      axisLabel: { hideOverlap: true },
      splitLine: { lineStyle: { color: '#e8edf2' } }
    },
    series: makeSeries(metric)
  }, true)
}

function handleResize() {
  chartInstances.forEach(chart => chart?.resize())
}

watch(() => selectedIds.value.slice(), async () => {
  await nextTick()
  renderComparisonCharts()
})

watch(activeChartIndex, async () => {
  await nextTick()
  renderComparisonCharts()
})

watch(() => [props.projectId, props.gasReservoirId, props.wellName], loadSolutions)

onMounted(() => {
  void loadSolutions()
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  destroyed = true
  ++loadVersion
  window.removeEventListener('resize', handleResize)
  chartInstances.forEach(chart => chart?.dispose())
  chartInstances = []
})
</script>

<template>
  <div class="comparison-workspace">
    <aside class="cmp-sidebar">
      <div class="panel-head">方案列表</div>
      <div class="cmp-sidebar-body">
        <div v-if="loading" class="cmp-loading">加载中…</div>
        <div v-else-if="loadError" class="cmp-error">{{ loadError }}</div>
        <div v-else-if="solutions.length === 0" class="cmp-empty">
          <p>暂无已保存的方案</p>
          <p class="cmp-empty-hint">请先在「方案生成」中保存方案</p>
        </div>
        <template v-else>
          <div class="cmp-select-all">
            <label>
              <input type="checkbox" :checked="allSolutionsSelected" @change="toggleSelectAll" />
              <span>全选 / 取消全选</span>
            </label>
          </div>
          <div class="cmp-list">
            <label v-for="sol in solutions" :key="sol.id" class="cmp-item"
              :class="{ 'cmp-item--checked': selectedIds.includes(sol.id), 'cmp-item--warn': !sol.eligible }"
              :title="sol.eligible ? sol.name : `${sol.name}：${sol.reason}`">
              <input type="checkbox" :checked="selectedIds.includes(sol.id)" @change="toggleSolution(sol)" />
              <span class="cmp-item-swatch"
                :style="{ backgroundColor: selectedSolutions.find(item => item.id === sol.id)?.color || '#c8cdd2' }"></span>
              <span class="cmp-item-content">
                <span class="cmp-item-name">{{ sol.name }}</span>
                <span class="cmp-item-status" :class="sol.eligible ? 'status-eligible' : 'status-excluded'">
                  {{ sol.reason }}
                </span>
              </span>
            </label>
          </div>
        </template>
      </div>
    </aside>
    <main class="cmp-chart-area">
      <div class="cmp-tab">{{ wellName ? `${wellName} - 方案比选` : '方案比选' }}</div>
      <div class="cmp-chart-body">
        <div v-if="selectedIds.length < 2" class="chart-placeholder">
          <strong>{{ solutions.length < 2 ? '至少需要两个已保存方案' : '勾选至少两个方案' }}</strong>
              <p>选中后会自动更新三幅指标曲线</p>
        </div>
        <div v-show="canCompare">
          <div class="cmp-shared-legend" aria-label="所选方案图例">
            <span v-for="solution in selectedSolutions" :key="solution.id">
              <i :style="{ backgroundColor: solution.color }"></i>{{ solution.name }}
            </span>
          </div>
          <nav class="chart-switch-tabs">
            <button v-for="(label, index) in CHART_LABELS" :key="label" class="chart-switch-tab"
              :class="{ active: activeChartIndex === index }" @click="activeChartIndex = index">{{ label }}</button>
          </nav>
          <div class="comparison-chart-shell">
            <div ref="activeChartRef" class="comparison-chart"></div>
            <div v-if="!hasVisibleSegments" class="chart-empty-overlay">
              所选方案没有可显示的界限内曲段，或缺少有效气量上下限。
            </div>
          </div>
        </div>
      </div>
    </main>
  </div>
</template>

<style lang="scss" scoped>
.comparison-workspace {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  overflow: hidden;
  background: #fff;
  color: #252525;
  font: 13px/1.5 "Microsoft YaHei", "Segoe UI", Arial, sans-serif;
}

.cmp-tab {
  height: 34px;
  line-height: 34px;
  max-width: min(430px, 100%);
  padding: 0 12px;
  border-right: 1px solid #e4e7ed;
  box-sizing: border-box;
  background: #f4d000;
  color: #202020;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 13px;
}

.cmp-sidebar {
  flex: 0 0 260px;
  border-right: 1px solid #d7d7d7;
  background: #fff;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.panel-head {
  display: flex;
  flex: 0 0 34px;
  align-items: center;
  padding: 0 12px;
  border-bottom: 1px solid #d7d7d7;
  background: #f2f2f2;
  font-weight: 600;
  font-size: 13px;
}

.cmp-sidebar-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 8px 10px;
}

.cmp-loading {
  text-align: center;
  color: #909399;
  padding: 20px 0;
}

.cmp-empty {
  text-align: center;
  color: #909399;
  padding: 20px 0;

  p {
    margin: 4px 0;
    font-size: 12px;
  }
}

.cmp-empty-hint {
  font-size: 11px;
  color: #c0c4cc;
}

.cmp-select-all {
  margin-bottom: 6px;
  padding-bottom: 6px;
  border-bottom: 1px solid #d7d7d7;

  label {
    display: flex;
    align-items: center;
    gap: 4px;
    cursor: pointer;
    font-size: 12px;
    color: #252525;

    input[type="checkbox"] {
      accent-color: #333;
    }
  }
}

.cmp-list {
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.cmp-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 6px;
  cursor: pointer;
  font-size: 12px;
  border-bottom: 1px solid #eee;

  &:hover {
    background: #fafafa;
  }

  &--checked {
    background: #fffdf0;
  }

  input[type="checkbox"] {
    accent-color: #333;
    flex-shrink: 0;
  }
}

.cmp-item--warn {
  border-left: 2px solid #d98b32;
}

.cmp-item-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cmp-item-content {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  gap: 1px;
}

.cmp-item-swatch {
  width: 9px;
  height: 9px;
  flex: 0 0 9px;
  border-radius: 50%;
}

.cmp-item-status {
  overflow: hidden;
  color: #737d86;
  font-size: 11px;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.status-eligible {
  color: #22835d;
}

.status-excluded {
  color: #b45b4b;
}

.cmp-hint {
  margin: 8px 0 0;
  font-size: 11px;
  color: #909399;
  text-align: center;
}

.cmp-excluded-hint {
  margin: 7px 0 0;
  font-size: 11px;
  color: #909399;
  text-align: center;
  line-height: 1.4;
}

.cmp-error {
  color: #a56a15;
  font-size: 12px;
  line-height: 1.5;
  padding: 8px;
  background: #fdf6ec;
  border-radius: 3px;
}

.cmp-chart-area {
  min-width: 0;
  min-height: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
}

.cmp-chart-body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: auto;
  padding: 12px 16px;
}

.chart-placeholder {
  flex: 1;
  min-height: 300px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: #909399;
  text-align: center;

  strong {
    color: #606266;
    font-size: 14px;
  }

  p {
    font-size: 12px;
    margin: 6px 0 0;
  }
}

.chart-switch-tabs {
  display: flex;
  flex: 0 0 auto;
  border-bottom: 1px solid #e4e7ed;
  background: #fafafa;
  overflow-x: auto;
}

.chart-switch-tab {
  height: 34px;
  padding: 0 16px;
  border: 0;
  border-right: 1px solid #e4e7ed;
  border-bottom: 2px solid transparent;
  border-radius: 0;
  background: transparent;
  color: #555;
  font: inherit;
  font-size: 13px;
  cursor: pointer;
  white-space: nowrap;

  &:hover {
    color: #202020;
    background: #fff8d8;
  }

  &.active {
    color: #202020;
    border-bottom-color: #f2c811;
    box-shadow: inset 0 -3px 0 #f2c811;
    background: #fff;
    font-weight: 600;
  }
}

.cmp-shared-legend {
  display: flex;
  flex: 0 0 auto;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 14px;
  padding: 6px 2px 8px;
  border-bottom: 1px solid #e4e7ed;

  span {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    min-width: 0;
    color: #4b5560;
    font-size: 12px;
  }

  i {
    width: 9px;
    height: 9px;
    flex: 0 0 9px;
    border-radius: 50%;
  }
}

.comparison-chart-shell {
  position: relative;
  display: flex;
  flex: 1;
  min-height: 350px;
}

.comparison-chart {
  width: 100%;
  flex: 1;
  min-height: 350px;
}

.chart-empty-overlay {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgb(255 255 255 / 88%);
  color: #737d86;
  text-align: center;
  font-size: 13px;
  pointer-events: none;
}

@media (max-width: 900px) {
  .comparison-workspace {
    flex-direction: column;
    overflow: auto;
  }

  .cmp-sidebar {
    width: 100%;
    max-height: 300px;
    flex: 0 0 auto;
    border-right: 0;
    border-bottom: 1px solid #d7d7d7;
  }

  .cmp-chart-area {
    min-height: 600px;
  }

  .cmp-chart-body {
    padding: 10px;
  }
}
</style>