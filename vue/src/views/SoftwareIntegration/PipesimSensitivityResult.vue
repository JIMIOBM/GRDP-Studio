<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({ result: { type: Object, default: null } })
const chartElement = ref(null)
let chart
let resizeObserver

const cases = computed(() => Array.isArray(props.result?.cases) ? props.result.cases : [])
const variableLabels = {
  reservoirPressure: '地层压力',
  waterCut: '含水率',
  gor: 'GOR',
  tubingInnerDiameter: '油管内径'
}
const targetLabel = computed(() => variableLabels[props.result?.targetVariable] || props.result?.targetVariable || '敏感性变量')
const flowUnit = computed(() => props.result?.units?.flow?.displayUnit || '')
const pressureUnit = computed(() => props.result?.units?.pressure?.displayUnit || '')
const selectedCaseIndex = ref(0)
const curveKind = ref('ipr')
const pointPage = ref(1)
const pointPageSize = ref(20)
const selectedCase = computed(() => cases.value[selectedCaseIndex.value] || cases.value[0] || null)
const selectedPoints = computed(() => selectedCase.value?.[curveKind.value] || [])
const pagedPoints = computed(() => {
  const start = (pointPage.value - 1) * pointPageSize.value
  return selectedPoints.value.slice(start, start + pointPageSize.value).map((point, index) => ({
    index: start + index + 1,
    flow: point.flow,
    pressure: point.pressure
  }))
})
const csvCell = value => {
  const text = String(value ?? '')
  const safe = typeof value === 'string' && /^[=+\-@\t\r]/.test(text) ? `'${text}` : text
  return `"${safe.replaceAll('"', '""')}"`
}
const download = (url, name) => {
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = name
  anchor.click()
}
const exportCsv = () => {
  if (!cases.value.length) return
  const rows = [['敏感性变量值', '曲线', '点序号', '流量', '压力']]
  cases.value.forEach(item => {
    for (const kind of ['ipr', 'vlp']) item[kind].forEach((point, index) => rows.push([item.value, kind.toUpperCase(), index + 1, point.flow, point.pressure]))
  })
  const url = URL.createObjectURL(new Blob(['\uFEFF', rows.map(row => row.map(csvCell).join(',')).join('\r\n')], { type: 'text/csv;charset=utf-8' }))
  download(url, `pipesim-sensitivity-${props.result?.targetVariable || 'result'}.csv`)
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
    title: { text: `${targetLabel.value}敏感性 · IPR / VLP`, left: 'center', top: 8, textStyle: { color: '#333', fontSize: 15, fontWeight: 600 } },
    tooltip: { trigger: 'axis' },
    legend: { top: 38, type: 'scroll' },
    grid: { left: 72, right: 28, top: 76, bottom: 70, containLabel: true },
    xAxis: { type: 'value', name: flowUnit.value ? `流量 (${flowUnit.value})` : '流量', nameLocation: 'middle', nameGap: 42 },
    yAxis: { type: 'value', name: pressureUnit.value ? `压力 (${pressureUnit.value})` : '压力', nameLocation: 'middle', nameGap: 52 },
    dataZoom: [{ type: 'inside', xAxisIndex: 0 }, { type: 'slider', xAxisIndex: 0, bottom: 18 }],
    series: cases.value.flatMap(item => [
      { name: `${item.value} · IPR`, type: 'line', showSymbol: false, data: item.ipr.map(point => [point.flow, point.pressure]) },
      { name: `${item.value} · VLP`, type: 'line', showSymbol: false, lineStyle: { type: 'dashed' }, data: item.vlp.map(point => [point.flow, point.pressure]) }
    ])
  }, true)
  chart.resize()
  resizeObserver?.disconnect()
  resizeObserver?.observe(chartElement.value)
}
const resizeChart = () => chart?.resize()
watch(() => props.result, renderChart, { deep: true })
watch([selectedCaseIndex, curveKind, pointPageSize], () => { pointPage.value = 1 })
watch(() => cases.value.length, total => {
  if (!total) selectedCaseIndex.value = 0
  else if (selectedCaseIndex.value >= total) selectedCaseIndex.value = total - 1
})
watch(() => selectedPoints.value.length, total => {
  const lastPage = Math.max(1, Math.ceil(total / pointPageSize.value))
  if (pointPage.value > lastPage) pointPage.value = lastPage
})
watch(() => props.result, () => {
  selectedCaseIndex.value = 0
  curveKind.value = 'ipr'
  pointPage.value = 1
  pointPageSize.value = 20
}, { deep: true })
onMounted(() => {
  resizeObserver = new ResizeObserver(resizeChart)
  window.addEventListener('resize', resizeChart)
  renderChart()
})
onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  window.removeEventListener('resize', resizeChart)
  chart?.dispose()
  chart = null
})
</script>

<template>
  <section class="sensitivity-result">
    <div v-if="cases.length" class="result-tools"><span>{{ targetLabel }} · {{ cases.length }} 个真实计算工况</span><el-button size="small" @click="exportCsv">导出全部敏感性点 CSV</el-button></div>
    <el-empty v-if="!cases.length" description="当前运行没有有效的敏感性结果" :image-size="72" />
    <div v-else ref="chartElement" class="result-chart" />
    <el-table v-if="cases.length" :data="cases" border size="small" max-height="300" :row-class-name="({ rowIndex }) => rowIndex === selectedCaseIndex ? 'selected-sensitivity-case' : ''">
      <el-table-column prop="value" :label="targetLabel" min-width="130" />
      <el-table-column label="IPR 点数" width="110"><template #default="scope">{{ scope.row.ipr.length }}</template></el-table-column>
      <el-table-column label="VLP 点数" width="110"><template #default="scope">{{ scope.row.vlp.length }}</template></el-table-column>
      <el-table-column label="操作" width="130"><template #default="scope"><el-button link type="primary" @click="selectedCaseIndex = scope.$index">查看原始点</el-button></template></el-table-column>
    </el-table>
    <section v-if="selectedCase" class="point-panel" aria-label="敏感性原始点">
      <div class="point-toolbar">
        <strong>工况 {{ selectedCase.value }} · 原始点</strong>
        <el-select v-model="selectedCaseIndex" aria-label="选择敏感性工况"><el-option v-for="(item, index) in cases" :key="`${item.value}-${index}`" :value="index" :label="`${targetLabel} ${item.value}`" /></el-select>
        <el-select v-model="curveKind" aria-label="选择敏感性曲线"><el-option value="ipr" label="IPR" /><el-option value="vlp" label="VLP" /></el-select>
      </div>
      <el-table :data="pagedPoints" border size="small" max-height="280">
        <el-table-column prop="index" label="#" width="70" />
        <el-table-column prop="flow" :label="`流量${flowUnit ? ` (${flowUnit})` : ''}`" />
        <el-table-column prop="pressure" :label="`压力${pressureUnit ? ` (${pressureUnit})` : ''}`" />
      </el-table>
      <el-pagination v-if="selectedPoints.length > pointPageSize" v-model:current-page="pointPage" v-model:page-size="pointPageSize" class="point-pagination" background layout="total, sizes, prev, pager, next" :page-sizes="[20, 50, 100]" :total="selectedPoints.length" />
    </section>
  </section>
</template>

<style lang="scss" scoped>
.sensitivity-result { min-height: 0; display: flex; flex-direction: column; gap: 12px; }
.result-tools, .point-toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }.result-tools { color: #666; font-size: 13px; }.result-tools span { margin-right: auto; }
.result-chart { height: 430px; min-height: 300px; border: 1px solid #e4e9f0; background: #fff; }
.point-panel { padding: 12px; border: 1px solid #e1e7ef; background: #fff; }.point-toolbar { margin-bottom: 10px; }.point-toolbar strong { margin-right: auto; font-size: 13px; }.point-toolbar .el-select { width: min(220px, 100%); }.point-pagination { justify-content: flex-end; margin-top: 10px; }
:deep(.selected-sensitivity-case > td.el-table__cell) { background: #fff7bf !important; }
</style>
