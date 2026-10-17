<script setup>
import { ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import { PREDICTION_METRICS, lastValidValue } from '@/api/reservoirPredictionApi'
import { getReservoirPredictionContext } from './reservoirPredictionContext'

const props = defineProps({
  projectId: { type: [Number, String], required: true },
  gasReservoirId: { type: [Number, String], required: true },
  storageId: { type: [Number, String], required: true },
  storageName: { type: String, default: '' }
})
const context = computed(() => getReservoirPredictionContext(
  props.projectId, props.gasReservoirId, props.storageId
))
// 不再请求报 500 的 /node 列表。这里只比选本次真实计算的内存方案，不做保存。
const solutions = computed(() => context.value.liveSolutions)
const selectedSolutions = ref([])
const metricKey = ref('gasProduction')
const loading = ref(false)
const errorText = ref('')
const comparisonChartRef = ref(null)
const colors = ['#d5b900', '#555555', '#8e9bb2', '#e09000', '#619a82']
let comparisonChart = null
const currentMetric = computed(() =>
  PREDICTION_METRICS.find(metric => metric.key === metricKey.value) || PREDICTION_METRICS[0]
)
function getSeries(id, key) {
  return solutions.value.find(s => s.id === id)?.charts?.[key] || []
}
function finalValue(id, key) {
  const value = lastValidValue(getSeries(id, key))
  return value === null ? '—' : value.toFixed(key === 'reservoirRecoveryDegree' ? 2 : 4)
}
async function toggleSolution(id) {
  const i = selectedSolutions.value.indexOf(id)
  if (i >= 0) selectedSolutions.value.splice(i, 1)
  else {
    if (selectedSolutions.value.length >= 5) {
      ElMessage.warning('最多同时比较5个方案')
      return
    }
    selectedSolutions.value.push(id)
  }
  await nextTick()
  renderComparisonChart()
}
function selectMetric(key) {
  metricKey.value = key
  nextTick(renderComparisonChart)
}
function renderComparisonChart() {
  if (!comparisonChartRef.value || !selectedSolutions.value.length) {
    comparisonChart?.clear()
    return
  }
  if (comparisonChart && comparisonChart.getDom() !== comparisonChartRef.value) {
    comparisonChart.dispose()
    comparisonChart = null
  }
  if (!comparisonChart) comparisonChart = echarts.init(comparisonChartRef.value)
  const metric = currentMetric.value
  comparisonChart.setOption({
    title: { text: `多方案${metric.name}对比`, left: 'center', textStyle: { fontSize: 14, fontWeight: 600 } },
    tooltip: { trigger: 'axis', valueFormatter: v =>
      Array.isArray(v) && Number.isFinite(v[1]) ? `${v[1].toFixed(4)} ${metric.unit}` : '—' },
    legend: { bottom: 33, type: 'scroll' },
    grid: { left: '3%', right: '5%', bottom: 90, top: 55, containLabel: true },
    dataZoom: [{ type: 'inside' }, { type: 'slider', height: 18, bottom: 8 }],
    xAxis: { type: 'time', name: '时间', axisLabel: { formatter: '{yyyy}-{MM}' } },
    yAxis: { type: 'value', name: `${metric.name}(${metric.unit})`, scale: true },
    series: selectedSolutions.value.map((id, index) => ({
      name: solutions.value.find(s => s.id === id)?.name || id,
      type: 'line', showSymbol: false, smooth: false, connectNulls: false,
      data: getSeries(id, metric.key),
      lineStyle: { color: colors[index], width: 2 },
      itemStyle: { color: colors[index] }
    }))
  }, true)
  comparisonChart.resize()
}
const handleResize = () => comparisonChart?.resize()
watch(() => [props.projectId, props.gasReservoirId, props.storageId], () => {
  selectedSolutions.value = []
  comparisonChart?.clear()
})
watch(() => solutions.value.map(s => s.id).join(','), () => {
  selectedSolutions.value = selectedSolutions.value.filter(id => solutions.value.some(s => s.id === id))
  nextTick(renderComparisonChart)
})
onMounted(() => window.addEventListener('resize', handleResize))
onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  comparisonChart?.dispose()
  comparisonChart = null
})
</script>

<template>
  <div class="comparison-workspace">
    <aside class="comparison-sidebar">
      <div class="panel-head">方案列表</div>
      <div class="solution-list">
        <div v-for="solution in solutions" :key="solution.id" class="solution-item"
          :class="{ selected: selectedSolutions.includes(solution.id) }"
          role="button" tabindex="0" :aria-pressed="selectedSolutions.includes(solution.id)"
          @click="toggleSolution(solution.id)" @keydown.enter.prevent="toggleSolution(solution.id)"
          @keydown.space.prevent="toggleSolution(solution.id)">
          <label class="checkbox-wrapper" @click.stop>
            <input type="checkbox" :checked="selectedSolutions.includes(solution.id)"
              @change="toggleSolution(solution.id)">
            <span class="checkmark"></span>
          </label>
          <div class="solution-info">
            <div class="solution-name">{{ solution.name }}</div>
            <div class="solution-date">临时方案编号：{{ solution.id }}</div>
          </div>
        </div>
        <div v-if="!solutions.length" class="empty-hint">
          本次尚未生成方案。请先到“方案生成”调用真实接口。
        </div>
        <p v-if="errorText" class="comparison-error">{{ errorText }}</p>
      </div>
      <div class="selection-info">
        {{ loading ? '加载中...' : `已选择 ${selectedSolutions.length} / 5 个方案` }}
      </div>
    </aside>

    <main class="comparison-main">
      <div class="result-tabs">
        <div class="result-tab">{{ storageName ? `${storageName} - 方案比选` : '方案比选' }}</div>
      </div>
      <div class="comparison-content">
        <div v-if="selectedSolutions.length === 0" class="empty-state">
          <p>请从左侧选择本次生成的方案（最多5个），对比真实预测曲线</p>
        </div>
        <template v-else>
          <div class="metric-tabs">
            <button v-for="metric in PREDICTION_METRICS" :key="metric.key"
              :class="{ active: metricKey === metric.key }"
              type="button" @click="selectMetric(metric.key)">
              {{ metric.name }}
            </button>
          </div>
          <div class="chart-container">
            <div ref="comparisonChartRef" class="comparison-chart"></div>
            <p v-if="loading" class="comparison-status">正在更新曲线...</p>
          </div>

          <div v-if="selectedSolutions.length > 1" class="metrics-table">
            <div class="metrics-title">预测末期关键指标对比（数据来自真实接口结果，不计算经济效率）</div>
            <table>
              <thead>
                <tr>
                  <th>方案名称</th>
                  <th>末期日产气(10⁴m³/d)</th>
                  <th>末期累产气(10⁸m³)</th>
                  <th>末期地层压力(MPa)</th>
                  <th>末期采出程度(%)</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="solId in selectedSolutions" :key="solId">
                  <td>{{ solutions.find(s => s.id === solId)?.name }}</td>
                  <td>{{ finalValue(solId, 'gasProduction') }}</td>
                  <td>{{ finalValue(solId, 'cumulativeGasProduction') }}</td>
                  <td>{{ finalValue(solId, 'formationPressure') }}</td>
                  <td>{{ finalValue(solId, 'reservoirRecoveryDegree') }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </template>
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

.comparison-sidebar {
  flex: 0 0 260px;
  display: flex;
  flex-direction: column;
  border-right: 1px solid #d7d7d7;
  background: #fff;
}

.panel-head {
  display: flex;
  flex: 0 0 34px;
  align-items: center;
  padding: 0 12px;
  border-bottom: 1px solid #d7d7d7;
  background: #f2f2f2;
  font-weight: 600;
}

.solution-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.solution-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px;
  margin-bottom: 4px;
  border-bottom: 1px solid #eee;
  cursor: pointer;
}

.solution-item:hover {
  background: #fafafa;
}

.solution-item.selected {
  background: #fffdf0;
  border-bottom-color: #d5b900;
}

.checkbox-wrapper {
  position: relative;
  display: inline-block;
  width: 13px;
  height: 13px;
  flex-shrink: 0;
}

.checkbox-wrapper input {
  position: absolute;
  opacity: 0;
  width: 0;
  height: 0;
}

.checkmark {
  position: absolute;
  top: 0;
  left: 0;
  width: 13px;
  height: 13px;
  background: #fff;
  border: 1px solid #aaa;
  border-radius: 2px;
  accent-color: #333;
}

.checkbox-wrapper input:checked ~ .checkmark::after {
  content: '';
  position: absolute;
  left: 3px;
  top: 1px;
  width: 4px;
  height: 8px;
  border: solid #333;
  border-width: 0 2px 2px 0;
  transform: rotate(45deg);
}

.solution-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.solution-name {
  font-size: 13px;
  font-weight: 400;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.solution-date {
  font-size: 11px;
  color: #909399;
}

.empty-hint {
  text-align: center;
  color: #909399;
  padding: 24px 12px;
  font-size: 12px;
  line-height: 1.6;
}

.selection-info {
  flex: 0 0 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f2f2f2;
  border-top: 1px solid #d7d7d7;
  font-size: 12px;
  color: #606266;
}

.comparison-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.result-tabs {
  height: 34px;
  flex: 0 0 34px;
  display: flex;
  align-items: center;
  border-bottom: 1px solid #e4e7ed;
  background: #fafafa;
}

.result-tab {
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

.comparison-content {
  flex: 1;
  overflow-y: auto;
  padding: 16px 24px 24px;
}

.empty-state {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #909399;
  font-size: 13px;
}

.chart-container {
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 3px;
  padding: 12px;
  margin-bottom: 16px;
}

.comparison-chart {
  width: 100%;
  height: 380px;
}

.metrics-table {
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 3px;
  padding: 12px;
}

.metrics-title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
  margin: 0 0 12px 0;
  padding-bottom: 8px;
  border-bottom: 1px solid #ebeef5;
}

table {
  width: 100%;
  border-collapse: collapse;
}

thead th {
  background: #fafafa;
  padding: 8px 10px;
  text-align: left;
  font-weight: 600;
  font-size: 12px;
  color: #606266;
  border-bottom: 1px solid #e4e7ed;
}

tbody td {
  padding: 8px 10px;
  font-size: 12px;
  color: #303133;
  border-bottom: 1px solid #f0f0f0;
}

tbody tr:hover {
  background: #fafafa;
}

.metric-tabs { display: flex; gap: 6px; margin-bottom: 12px; flex-wrap: wrap; }
.metric-tabs button {
  border: 1px solid #dedede; background: #f6f6f6;
  border-radius: 3px; padding: 6px 12px; cursor: pointer; color: #444;
}
.metric-tabs button.active { border-color: #d5b900; background: #fff6b8; color: #222; }
.comparison-error { font-size: 12px; color: #d33c3c; line-height: 1.6; padding: 8px; }
.comparison-status { font-size: 12px; color: #606266; text-align: center; }
</style>
