<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { storageNetworkApi } from '@/api/storageNetwork'
import { correlationMatrix, filterStorageNetworkSamples, linearRegression,
  storageNetworkVariables, strongestTargetCorrelations } from '@/utils/storageNetwork'

const props = defineProps({ reservoir: { type: Object, default: null } })
const projectId = computed(() => Number(props.reservoir?.projectId))
const gasReservoirId = computed(() => Number(props.reservoir?.gasReservoirId))
const storageId = computed(() => Number(props.reservoir?.storageId))
const storageName = computed(() => props.reservoir?.label || '未选择库')

const loading = ref(false)
const error = ref('')
const source = ref({ samples: [], coverage: [] })
const selectedWellIds = ref([])
const method = ref('pearson')
const targetKey = ref('compressionPowerKw')
const xKey = ref('flowRate10k')
const yKey = ref('compressionPowerKw')
const threshold = ref(.5)
const startDate = ref('')
const endDate = ref('')
const activeView = ref('matrix')
const chartEl = ref(null)
let chart = null
let resizeObserver = null
let requestVersion = 0

const unwrap = response => response?.data ?? response
const errorText = value => value?.response?.data?.msg || value?.msg || value?.message || '请求失败'
const variable = key => storageNetworkVariables.find(item => item.key === key) || { key, label: key, unit: '' }
const readyCoverage = computed(() => source.value.coverage.filter(row => row.status === 'ready'))
const selectedWellSet = computed(() => new Set(selectedWellIds.value.map(Number)))
const filteredSamples = computed(() => filterStorageNetworkSamples(source.value.samples, {
  wellIds: selectedWellIds.value, startDate: startDate.value, endDate: endDate.value
}))
const matrix = computed(() => correlationMatrix(filteredSamples.value, method.value))
const regression = computed(() => linearRegression(filteredSamples.value, xKey.value, yKey.value))
const targetCorrelations = computed(() => strongestTargetCorrelations(filteredSamples.value, targetKey.value,
  method.value, Number(threshold.value) || 0))
const selectedWellCount = computed(() => new Set(filteredSamples.value.map(row => row.wellId)).size)
const hasEnoughSamples = computed(() => filteredSamples.value.length >= 3)
const resultTitle = computed(() => `${storageName.value}-地面管网相关性分析`)
const insight = computed(() => {
  if (!hasEnoughSamples.value) return '至少需要3条完整工况样本后才能计算相关系数。'
  const strongest = targetCorrelations.value[0]
  if (!strongest) return `当前阈值下，没有变量与${variable(targetKey.value).label}形成显著相关关系。`
  const direction = strongest.value > 0 ? '同向' : '反向'
  return `${strongest.label}与${variable(targetKey.value).label}呈${direction}关系（${method.value === 'pearson' ? 'Pearson' : 'Spearman'} ${strongest.value.toFixed(3)}）。调度方案可优先围绕该变量做情景约束和敏感性复算。`
})

async function load() {
  const version = ++requestVersion
  loading.value = true; error.value = ''; source.value = { samples: [], coverage: [] }
  try {
    const data = unwrap(await storageNetworkApi.correlationSource({ projectId: projectId.value,
      gasReservoirId: gasReservoirId.value, storageId: storageId.value }))
    if (version !== requestVersion) return
    if (!Array.isArray(data?.samples) || !Array.isArray(data?.coverage)) throw new Error('相关性数据源返回格式不正确')
    source.value = data
    selectedWellIds.value = data.coverage.filter(row => row.status === 'ready').map(row => Number(row.wellId))
    const dates = data.samples.map(row => String(row.operatingAt || '').slice(0, 10)).filter(Boolean).sort()
    startDate.value = dates[0] || ''; endDate.value = dates.at(-1) || ''
  } catch (value) { if (version === requestVersion) error.value = errorText(value) }
  finally { if (version === requestVersion) loading.value = false }
}

function toggleWell(wellId) {
  const id = Number(wellId), selected = new Set(selectedWellIds.value.map(Number))
  selected.has(id) ? selected.delete(id) : selected.add(id)
  selectedWellIds.value = [...selected]
}

function toggleReadyWells() {
  const readyIds = readyCoverage.value.map(row => Number(row.wellId))
  const all = readyIds.length && readyIds.every(id => selectedWellSet.value.has(id))
  selectedWellIds.value = all ? [] : readyIds
}

function heatColor(value) {
  if (value === null) return '#f2f2f2'
  const strength = Math.abs(value)
  return value >= 0 ? `rgba(224, 61, 61, ${.12 + strength * .78})` : `rgba(51, 116, 190, ${.12 + strength * .78})`
}

function matrixOption() {
  const labels = storageNetworkVariables.map(item => item.label)
  return {
    animation: false,
    tooltip: { formatter: item => {
      const row = item.data?.raw
      if (!row || row.value === null) return `${labels[item.value[1]]} × ${labels[item.value[0]]}<br/>有效样本不足`
      return `${labels[item.value[1]]} × ${labels[item.value[0]]}<br/><strong>${row.value.toFixed(4)}</strong><br/>配对样本：${row.count}`
    } },
    grid: { left: 94, right: 76, top: 44, bottom: 84 },
    xAxis: { type: 'category', data: labels, splitArea: { show: true }, axisLabel: { interval: 0, rotate: 24 } },
    yAxis: { type: 'category', data: labels, splitArea: { show: true } },
    visualMap: { min: -1, max: 1, calculable: false, orient: 'vertical', right: 10, top: 'center',
      text: ['强正相关', '强负相关'], inRange: { color: ['#3274b9', '#eef2f5', '#df4242'] } },
    series: [{ type: 'heatmap', data: matrix.value.map(row => ({ value: [row.xIndex, row.yIndex, row.value ?? 0],
      raw: row, itemStyle: row.value === null ? { color: '#f2f2f2' } : undefined,
      label: { show: true, formatter: row.value === null ? '—' : row.value.toFixed(2), color: Math.abs(row.value || 0) > .62 ? '#fff' : '#333' } })),
      emphasis: { itemStyle: { shadowBlur: 6, shadowColor: 'rgba(0,0,0,.25)' } } }]
  }
}

function scatterOption() {
  const x = variable(xKey.value), y = variable(yKey.value), result = regression.value
  const xs = result.pairs.map(row => row[0])
  const line = result.slope === null || !xs.length ? [] : [Math.min(...xs), Math.max(...xs)]
    .map(value => [value, result.slope * value + result.intercept])
  const points = filteredSamples.value.map(row => {
    const vx = Number(row[xKey.value]), vy = Number(row[yKey.value])
    return Number.isFinite(vx) && Number.isFinite(vy) ? { value: [vx, vy], raw: row } : null
  }).filter(Boolean)
  return {
    animation: false,
    title: { text: result.r2 === null ? '' : `线性拟合 R² = ${result.r2.toFixed(4)}`, left: 'center', top: 8,
      textStyle: { fontSize: 12, fontWeight: 400, color: '#666' } },
    tooltip: { trigger: 'item', formatter: item => item.seriesName === '线性趋势'
      ? '最小二乘趋势线' : `${item.data.raw.wellName}<br/>${item.data.raw.operatingAt}<br/>${x.label}：${item.value[0]} ${x.unit}<br/>${y.label}：${item.value[1]} ${y.unit}` },
    grid: { left: 88, right: 36, top: 50, bottom: 72 },
    xAxis: { type: 'value', name: `${x.label}（${x.unit}）`, nameLocation: 'middle', nameGap: 44, scale: true },
    yAxis: { type: 'value', name: `${y.label}（${y.unit}）`, nameLocation: 'middle', nameGap: 62, scale: true },
    series: [
      { name: '工况样本', type: 'scatter', symbolSize: 8, data: points, itemStyle: { color: '#cca900', opacity: .78 } },
      { name: '线性趋势', type: 'line', showSymbol: false, silent: true, data: line,
        lineStyle: { color: '#333', width: 1.5, type: 'dashed' } }
    ]
  }
}

function renderChart() {
  if (!chartEl.value || activeView.value === 'data') return
  if (!chart) {
    chart = echarts.init(chartEl.value)
    chart.on('click', event => {
      if (activeView.value !== 'matrix' || !event.data?.raw) return
      xKey.value = event.data.raw.xKey; yKey.value = event.data.raw.yKey
    })
  }
  chart.setOption(activeView.value === 'matrix' ? matrixOption() : scatterOption(), true)
  chart.resize()
}

watch(() => [projectId.value, gasReservoirId.value, storageId.value], load, { immediate: true })
watch([filteredSamples, method, xKey, yKey, activeView], async () => { await nextTick(); renderChart() }, { deep: true })
onMounted(async () => {
  await nextTick(); renderChart()
  resizeObserver = new ResizeObserver(() => chart?.resize())
  if (chartEl.value) resizeObserver.observe(chartEl.value)
})
onBeforeUnmount(() => { requestVersion++; resizeObserver?.disconnect(); chart?.dispose(); chart = null })
</script>

<template>
  <section class="correlation-workspace" aria-label="库级地面管网相关性分析">
    <header class="module-tabs"><div class="module-tab">地面管网-相关性分析</div></header>
    <div class="correlation-body">
      <aside class="params-panel water-parameter-theme">
        <div class="panel-head"><span>分析设置</span><button type="button" title="刷新真实管流数据" :disabled="loading" @click="load">刷新</button></div>
        <div class="panel-body">
          <fieldset class="section"><legend>数据来源</legend>
            <p class="source-note">读取当前库各井最近一次已保存的管流批量计算；失败工况与缺失值不补零、不插值。</p>
          </fieldset>
          <fieldset class="section"><legend>相关方法</legend>
            <label class="radio"><input v-model="method" type="radio" value="pearson" />Pearson（线性）</label>
            <label class="radio"><input v-model="method" type="radio" value="spearman" />Spearman（单调）</label>
          </fieldset>
          <fieldset class="section"><legend>分析范围</legend>
            <label class="field"><span>开始日期</span><input v-model="startDate" type="date" /></label>
            <label class="field"><span>结束日期</span><input v-model="endDate" type="date" /></label>
            <label class="field"><span>调度目标</span><select v-model="targetKey"><option v-for="item in storageNetworkVariables" :key="item.key" :value="item.key">{{ item.label }}</option></select></label>
            <label class="field"><span>显著阈值 |r|</span><input v-model.number="threshold" type="number" min="0" max="1" step="0.05" /></label>
          </fieldset>
          <fieldset class="section"><legend>散点变量</legend>
            <label class="field"><span>横轴</span><select v-model="xKey"><option v-for="item in storageNetworkVariables" :key="item.key" :value="item.key">{{ item.label }}</option></select></label>
            <label class="field"><span>纵轴</span><select v-model="yKey"><option v-for="item in storageNetworkVariables" :key="item.key" :value="item.key">{{ item.label }}</option></select></label>
          </fieldset>
          <fieldset class="section coverage"><legend>参与分析的井（{{ selectedWellIds.length }}）</legend>
            <button class="select-all" type="button" :disabled="!readyCoverage.length" @click="toggleReadyWells">全选/清空可用井</button>
            <div v-if="loading" class="record-status">正在读取管流工况…</div>
            <div v-else-if="error" class="record-status error" role="alert">{{ error }}</div>
            <div v-else-if="!source.coverage.length" class="record-status">当前储气库暂无成员井</div>
            <label v-for="row in source.coverage" :key="row.wellId" class="well-row" :class="row.status">
              <input type="checkbox" :checked="selectedWellSet.has(Number(row.wellId))" :disabled="row.status !== 'ready'" @change="toggleWell(row.wellId)" />
              <span><b>{{ row.wellName }}</b><small v-if="row.status === 'ready'">{{ row.validSampleCount }} 条有效工况</small><small v-else>{{ row.reason }}</small></span>
            </label>
          </fieldset>
        </div>
      </aside>
      <main class="result-area">
        <div class="result-tabs"><div class="result-tab" :title="resultTitle">{{ resultTitle }}</div></div>
        <div class="summary-strip">
          <span><b>{{ filteredSamples.length }}</b> 条样本</span><span><b>{{ selectedWellCount }}</b> 口有效井</span>
          <span><b>{{ source.coverage.length - readyCoverage.length }}</b> 口缺少可用结果</span>
          <span class="method-note">相关性用于筛选调度变量，不代表因果关系</span>
        </div>
        <div class="analysis-tabs">
          <button :class="{ active: activeView === 'matrix' }" @click="activeView = 'matrix'">相关矩阵</button>
          <button :class="{ active: activeView === 'scatter' }" @click="activeView = 'scatter'">散点回归</button>
          <button :class="{ active: activeView === 'data' }" @click="activeView = 'data'">工况样本</button>
        </div>
        <div class="result-content">
          <div v-show="activeView !== 'data'" class="chart-pane">
            <div v-if="!hasEnoughSamples" class="empty-state">{{ loading ? '正在读取管流工况…' : insight }}<small v-if="!loading">请先在单井“管束能力-管流计算”中保存至少3条成功工况。</small></div>
            <div v-show="hasEnoughSamples" ref="chartEl" class="chart" />
          </div>
          <div v-show="activeView === 'data'" class="table-pane">
            <el-table :data="filteredSamples" border height="100%" empty-text="没有符合当前范围的完整工况">
              <el-table-column prop="wellName" label="井名" min-width="90" fixed />
              <el-table-column prop="operatingAt" label="工况时间" min-width="150" />
              <el-table-column prop="inletPressureMpa" label="入口压力(MPa)" min-width="115" />
              <el-table-column prop="outletPressureMpa" label="出口压力(MPa)" min-width="115" />
              <el-table-column prop="pressureDropMpa" label="系统压差(MPa)" min-width="115" />
              <el-table-column prop="flowRate10k" label="输气量(10⁴m³/d)" min-width="125" />
              <el-table-column prop="outletTemperatureC" label="出口温度(℃)" min-width="105" />
              <el-table-column prop="compressionPowerKw" label="压缩功率(kW)" min-width="110" />
            </el-table>
          </div>
          <aside class="insight-panel">
            <h3>调度启示</h3><p>{{ insight }}</p>
            <div class="rank-list">
              <div v-for="item in targetCorrelations.slice(0, 5)" :key="item.key" class="rank-row">
                <span>{{ item.label }}</span><b :class="item.value >= 0 ? 'positive' : 'negative'">{{ item.value.toFixed(3) }}</b>
                <i><em :style="{ width: Math.abs(item.value) * 100 + '%', background: heatColor(item.value) }" /></i>
              </div>
              <div v-if="!targetCorrelations.length" class="rank-empty">当前范围没有达到阈值的变量</div>
            </div>
            <p class="caution">建议将高相关变量带入后续单目标/多目标优化做约束和敏感性分析，并结合压力、输量及设备能力上限复核。</p>
          </aside>
        </div>
      </main>
    </div>
  </section>
</template>

<style lang="scss" scoped>
.correlation-workspace { width: 100%; height: 100%; min-width: 0; min-height: 0; display: flex; flex-direction: column; overflow: hidden; background: #fff; color: #252525; font: 13px/1.45 "Microsoft YaHei", "Segoe UI", Arial, sans-serif; }
.module-tabs { height: 34px; flex: 0 0 34px; display: flex; align-items: center; border-bottom: 1px solid #e4e7ed; background: #fafafa; }.module-tab { height: 34px; line-height: 34px; padding: 0 12px; border-right: 1px solid #e4e7ed; box-sizing: border-box; background: #f4d000; font-weight: 600; }
.correlation-body { flex: 1; min-height: 0; display: flex; overflow: hidden; }.params-panel { width: 270px; min-width: 270px; display: flex; flex-direction: column; border-right: 1px solid #d7d7d7; background: #fff; }
.panel-head { height: 34px; padding: 0 10px 0 12px; display: flex; align-items: center; justify-content: space-between; flex: 0 0 34px; border-bottom: 1px solid #d7d7d7; background: #f2f2f2; box-sizing: border-box; }.panel-head button, .select-all { height: 24px; padding: 0 8px; border: 1px solid #aaa; border-radius: 3px; background: #fff; font: inherit; cursor: pointer; }.panel-body { flex: 1; min-height: 0; padding: 8px 12px 16px; overflow-y: auto; }
.section { min-width: 0; margin: 0 0 9px; padding: 0; border: 0; }.section legend { width: 100%; height: 24px; margin: 4px 0 6px; padding: 0; display: flex; align-items: center; gap: 7px; font-weight: 500; }.section legend::after { content: ''; height: 1px; flex: 1; background: #999; }.source-note { margin: 0 0 7px; color: #666; font-size: 11px; line-height: 1.7; }
.radio { margin-right: 10px; display: inline-flex; align-items: center; gap: 4px; cursor: pointer; }.radio input, .well-row input { accent-color: #333; }.field { display: block; margin-bottom: 7px; }.field span { display: block; margin-bottom: 3px; }.field input, .field select { width: 100%; height: 26px; padding: 0 7px; box-sizing: border-box; border: 1px solid #aaa; border-radius: 3px; background: #fff; color: #333; font: inherit; }.select-all { margin-bottom: 6px; }
.well-row { padding: 6px 4px; display: flex; align-items: flex-start; gap: 6px; border-bottom: 1px solid #eee; cursor: pointer; }.well-row.missing, .well-row.invalid { background: #fafafa; color: #888; cursor: default; }.well-row > span { min-width: 0; display: flex; flex-direction: column; }.well-row b { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 500; }.well-row small { color: #777; font-size: 10px; line-height: 1.4; }.record-status { padding: 7px 0; color: #777; font-size: 11px; }.record-status.error { color: #b04431; }
.result-area { flex: 1; min-width: 0; min-height: 0; display: flex; flex-direction: column; overflow: hidden; }.result-tabs { height: 34px; flex: 0 0 34px; display: flex; border-bottom: 1px solid #e4e7ed; background: #fafafa; }.result-tab { max-width: 430px; height: 34px; padding: 0 12px; overflow: hidden; box-sizing: border-box; border-right: 1px solid #e4e7ed; background: #f4d000; font-weight: 600; line-height: 34px; text-overflow: ellipsis; white-space: nowrap; }
.summary-strip { min-height: 38px; padding: 5px 12px; display: flex; align-items: center; gap: 18px; border-bottom: 1px solid #e2e2e2; background: #fff; box-sizing: border-box; }.summary-strip span { color: #666; }.summary-strip b { color: #222; font-size: 16px; }.summary-strip .method-note { margin-left: auto; color: #8a6f00; font-size: 11px; }
.analysis-tabs { height: 32px; flex: 0 0 32px; display: flex; border-bottom: 1px solid #ddd; background: #f7f7f7; }.analysis-tabs button { min-width: 90px; padding: 0 14px; border: 0; border-right: 1px solid #ddd; background: transparent; color: #444; font: inherit; cursor: pointer; }.analysis-tabs button.active { background: #fff; box-shadow: inset 0 3px 0 #f4d000; font-weight: 600; }
.result-content { flex: 1; min-height: 0; display: flex; overflow: hidden; }.chart-pane, .table-pane { position: relative; flex: 1; min-width: 0; min-height: 0; }.chart { width: 100%; height: 100%; min-height: 400px; }.empty-state { position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px; color: #777; text-align: center; }.empty-state small { color: #999; }.table-pane { padding: 8px; box-sizing: border-box; }
.insight-panel { width: 245px; min-width: 245px; padding: 12px; overflow-y: auto; border-left: 1px solid #ddd; background: #fafafa; box-sizing: border-box; }.insight-panel h3 { margin: 0 0 8px; font-size: 14px; }.insight-panel p { margin: 0 0 12px; color: #555; font-size: 12px; line-height: 1.75; }.rank-row { margin-bottom: 9px; display: grid; grid-template-columns: 1fr auto; gap: 3px 8px; align-items: center; }.rank-row > span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.rank-row b { font-variant-numeric: tabular-nums; }.rank-row b.positive { color: #b83f3f; }.rank-row b.negative { color: #2f6cae; }.rank-row i { grid-column: 1 / -1; height: 4px; display: block; overflow: hidden; border-radius: 2px; background: #e5e5e5; }.rank-row em { height: 100%; display: block; }.rank-empty { padding: 12px 0; color: #888; font-size: 11px; }.insight-panel .caution { margin-top: 16px; padding-top: 10px; border-top: 1px solid #ddd; color: #777; font-size: 11px; }
:deep(.el-table) { font-size: 12px; }:deep(.el-table .cell) { padding: 0 7px; text-align: center; }
@media (max-width: 980px) { .params-panel { width: 240px; min-width: 240px; }.insight-panel { width: 210px; min-width: 210px; } }
</style>
