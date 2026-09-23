<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({ result: { type: Object, default: null } })
const chartElement = ref(null)
let chart
let resizeObserver

const cases = computed(() => Array.isArray(props.result?.cases) ? props.result.cases : [])
const selectedIndex = ref(0)
const selectedCase = computed(() => cases.value[selectedIndex.value] || cases.value[0] || null)
const displayNumber = value => typeof value === 'number' && Number.isFinite(value) ? value.toFixed(3) : '-'
const csvCell = value => `"${String(value ?? '').replaceAll('"', '""')}"`

const exportCsv = () => {
  if (!cases.value.length) return
  const rows = [['注气量 (mmscf/d)', '液体产量 (STB/d)', 'PIPESIM case']]
  cases.value.forEach(item => rows.push([item.injectionRateMmscfd, item.liquidRateStbPerDay, item.caseName]))
  const url = URL.createObjectURL(new Blob(['\uFEFF', rows.map(row => row.map(csvCell).join(',')).join('\r\n')], { type: 'text/csv;charset=utf-8' }))
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = 'pipesim-gas-lift-performance.csv'
  anchor.click()
  setTimeout(() => URL.revokeObjectURL(url), 0)
}

const renderChart = async () => {
  await nextTick()
  if (!chartElement.value || !cases.value.length) {
    chart?.dispose()
    chart = null
    return
  }
  chart ||= echarts.init(chartElement.value)
  chart.setOption({
    animation: false,
    title: { text: 'Gas Lift Performance Curve', left: 'center', top: 8, textStyle: { color: '#333', fontSize: 15, fontWeight: 600 } },
    tooltip: { trigger: 'axis' },
    grid: { left: 68, right: 24, top: 52, bottom: 58, containLabel: true },
    xAxis: { type: 'value', name: '注气量 (mmscf/d)', nameLocation: 'middle', nameGap: 38 },
    yAxis: { type: 'value', name: '液体产量 (STB/d)', nameLocation: 'middle', nameGap: 52 },
    series: [{
      name: '液体产量', type: 'line', smooth: false, showSymbol: true,
      data: cases.value.map(item => [item.injectionRateMmscfd, item.liquidRateStbPerDay]),
      lineStyle: { width: 2, color: '#2b6cb3' }, itemStyle: { color: '#2b6cb3' }
    }]
  }, true)
  chart.resize()
}

watch(() => props.result, () => { selectedIndex.value = 0; renderChart() }, { deep: true })
watch(() => cases.value.length, total => { if (!total) selectedIndex.value = 0; else if (selectedIndex.value >= total) selectedIndex.value = total - 1 })
onMounted(() => {
  renderChart()
  resizeObserver = new ResizeObserver(() => chart?.resize())
  if (chartElement.value) resizeObserver.observe(chartElement.value)
})
onBeforeUnmount(() => { resizeObserver?.disconnect(); chart?.dispose(); chart = null })
</script>

<template>
  <section class="gas-lift-performance-result" aria-label="PIPESIM 气举性能结果">
    <el-empty v-if="!cases.length" description="尚未选择成功的气举性能运行，或结果未通过展示契约校验" :image-size="72" />
    <template v-else>
      <div class="result-head">
        <div>
          <strong>PIPESIM Gas Lift Performance</strong>
          <span>井：{{ result.producer }} · 地层压力：{{ result.reservoirPressurePsi }} psia · 出口压力：{{ result.outletPressurePsi }} psia</span>
        </div>
        <div class="head-actions"><el-tag type="success">VALID_FULL · {{ cases.length }} 个真实工况</el-tag><el-button size="small" @click="exportCsv">导出性能曲线 CSV</el-button></div>
      </div>
      <div class="boundary-grid">
        <div><span>注气温度</span><strong>{{ result.surfaceInjectionTemperatureF }} °F</strong></div>
        <div><span>目标注气量</span><strong>{{ result.targetInjectionRateMmscfd }} mmscf/d</strong></div>
        <div><span>GOR</span><strong>{{ result.gorScfPerStb }} scf/STB</strong></div>
        <div><span>含水率</span><strong>{{ result.waterCutPercent }} %</strong></div>
      </div>
      <div ref="chartElement" class="performance-chart" />
      <section class="case-panel">
        <div class="case-toolbar"><strong>真实计算工况</strong><el-select v-model="selectedIndex" size="small" aria-label="选择气举性能工况"><el-option v-for="(item, index) in cases" :key="item.caseName" :value="index" :label="`${item.injectionRateMmscfd} mmscf/d`" /></el-select></div>
        <el-table :data="cases" border size="small" max-height="300" :row-class-name="({ rowIndex }) => rowIndex === selectedIndex ? 'selected-gas-lift-case' : ''">
          <el-table-column prop="injectionRateMmscfd" label="注气量 (mmscf/d)" min-width="150" />
          <el-table-column prop="liquidRateStbPerDay" label="液体产量 (STB/d)" min-width="160" />
          <el-table-column prop="caseName" label="官方 case" min-width="320" show-overflow-tooltip />
          <el-table-column label="操作" width="110"><template #default="scope"><el-button link type="primary" @click="selectedIndex = scope.$index">定位</el-button></template></el-table-column>
        </el-table>
        <p class="muted">当前定位：{{ selectedCase?.injectionRateMmscfd }} mmscf/d → {{ displayNumber(selectedCase?.liquidRateStbPerDay) }} STB/d。页面保留 PTK 返回顺序，不插值、不换算单位。</p>
      </section>
    </template>
  </section>
</template>

<style lang="scss" scoped>
.gas-lift-performance-result { min-width: 0; }
.result-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; padding: 10px 12px; background: #f5f7fa; border: 1px solid #e4e7ed; }
.result-head strong, .result-head span { display: block; }.result-head strong { color: #2b6cb3; font-size: 15px; }.result-head span { margin-top: 4px; color: #73777d; font-size: 12px; }.head-actions { display: flex; gap: 8px; align-items: center; }
.boundary-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 8px; margin: 10px 0; }.boundary-grid div { padding: 9px 11px; border: 1px solid #e4e7ed; background: #fff; }.boundary-grid span, .boundary-grid strong { display: block; }.boundary-grid span { color: #909399; font-size: 12px; }.boundary-grid strong { margin-top: 4px; color: #303133; font-size: 16px; }
.performance-chart { width: 100%; height: 390px; min-height: 300px; border: 1px solid #e4e9f0; background: #fff; }.case-panel { margin-top: 10px; padding: 10px; border: 1px solid #e4e7ed; }.case-toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 9px; }.case-toolbar strong { margin-right: auto; font-size: 13px; }.muted { color: #909399; font-size: 12px; }.selected-gas-lift-case > td.el-table__cell { background: #fff7bf !important; }
@media (max-width: 760px) { .result-head, .head-actions { align-items: flex-start; flex-direction: column; } }
</style>
