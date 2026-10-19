<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { inputCurveModel, inputCurvePanels, inputCurveCsv } from './eclipseInputCurves.js'
import { exportEclipseCharts } from './eclipseChartExport.js'

const props = defineProps({ tables: { type: Array, default: () => [] }, unitSystem: String, multiplier: Number, field: String, focusKeyword: String, validate: Function, runId: Number, modelVersionId: Number, sourceFile: String, region: { type: Number, default: 1 }, regionKind: { type: String, default: 'PVT' } })
const model = computed(() => inputCurveModel(props)), tableKey = ref(''), branchId = ref('all'), hosts = []
const preferred = computed(() => props.focusKeyword || ({ WATER_KR: ['SWFN','SWOF'], GAS_KR: ['SGFN','SGOF'], OIL_KR: ['SOF3','SWOF'] })[props.field]?.find(k => model.value.tables.some(t => t.keyword === k)))
const selected = computed(() => model.value.tables.find(t => t.keyword === tableKey.value) || model.value.tables.find(t => t.keyword === preferred.value) || model.value.tables[0])
const panels = computed(() => inputCurvePanels(selected.value, branchId.value))
const statusText = computed(() => ({ original: '原表 · 尚未填写乘数', preview: '目标预览 · 尚未计算', invalid: '参数待修正 · 仅显示原表', unavailable: '输入曲线暂不可用' })[model.value.status])
watch([() => props.runId, () => props.modelVersionId, () => props.region, () => props.sourceFile, () => props.field, () => props.focusKeyword], () => { tableKey.value = ''; branchId.value = 'all' })
watch(() => selected.value?.keyword, () => { branchId.value = 'all' })
let charts = [], observer, generation = 0
const colors = ['#257bbb', '#15998f', '#8965bd', '#d69036', '#d66677', '#458b60', '#6575b0', '#a17345']
function dispose() { observer?.disconnect(); charts.forEach(c => c.dispose()); charts = [] }
async function render() {
  const token = ++generation
  await nextTick()
  if (token !== generation) return
  dispose()
  observer = new ResizeObserver(() => charts.forEach(c => c.resize()))
  panels.value.forEach((panel, index) => {
    const host = hosts[index]
    if (!host) return
    const chart = echarts.init(host), groups = [...new Set(panel.series.map(s => `${s.metric}|${s.branch}`))]
    charts.push(chart); observer.observe(host)
    const table = selected.value
    chart.setOption({ animation: false, title: { text: `${table.keyword} · ${panel.labels.join(' / ')} (${panel.unit})`, subtext: `基线 #${props.runId} · ${props.regionKind}区 ${props.region} · ${model.value.status === 'preview' ? '输入表点预览，尚未计算' : '原始输入表点'}`, left: 12, top: 10, textStyle: { fontSize: 13, color: '#25496a' }, subtextStyle: { fontSize: 10 } },
      tooltip: { trigger: 'item', renderMode: 'richText', formatter: p => { const s = panel.series[p.seriesIndex], point = s.points[p.dataIndex]; return `${s.name}\n${table.xLabel}: ${point.x} ${table.xUnit}\n${s.metric}: ${s.target ? point.target : point.original} ${panel.unit}\n源行 ${point.line}${point.state ? ' · ' + point.state : ''}` } },
      legend: { type: 'scroll', top: 60, left: 12, right: 12, textStyle: { fontSize: 10 } }, grid: { left: 18, right: 26, top: 102, bottom: 62, containLabel: true },
      xAxis: { type: 'value', name: `${table.xLabel} (${table.xUnit})`, nameLocation: 'middle', nameGap: 32, scale: true, splitLine: { show: false }, nameTextStyle: { fontSize: 10 } },
      yAxis: { type: 'value', scale: panel.unit !== '无量纲', splitLine: { lineStyle: { color: '#edf3f8' } }, axisLabel: { fontSize: 10 } },
      graphic: [{ type: 'text', left: 14, bottom: 6, style: { text: `${props.sourceFile}:${table.keywordLine ?? ''} · 模型版本 ${props.modelVersionId} · 输入表点，尚非计算结果`, fontSize: 9, fill: '#8b9cad', width: Math.max(100, host.clientWidth - 28), overflow: 'truncate' } }],
      dataZoom: [{ type: 'inside' }], series: panel.series.map(s => ({ name: s.name, type: 'line', smooth: false, connectNulls: false, symbol: s.target ? 'diamond' : 'circle', symbolSize: s.target ? 8 : 5,
        itemStyle: { color: colors[groups.indexOf(`${s.metric}|${s.branch}`) % colors.length], opacity: s.target ? 1 : .65 },
        lineStyle: { width: s.target ? 3 : 2, type: s.target ? 'solid' : 'dashed' }, data: s.points.map(p => [p.x, s.target ? p.target : p.original]) })) }, true)
  })
}
watch([model, selected, panels], render, { immediate: true })
onBeforeUnmount(() => { generation++; dispose() })
const filename = () => `eclipse-input-${props.runId}-${props.regionKind}${props.region}-${selected.value?.keyword || 'tables'}`
function save(href, name) { const a = document.createElement('a'); a.href = href; a.download = name; a.click() }
const exportPng = () => exportEclipseCharts(charts, `${filename()}.png`)
function exportCsv() {
  const url = URL.createObjectURL(new Blob([inputCurveCsv(model.value, props)], { type: 'text/csv;charset=utf-8' }))
  save(url, `${filename()}-all-tables.csv`); setTimeout(() => URL.revokeObjectURL(url), 1000)
}
</script>
<template>
  <section class="input-curves" aria-label="ECLIPSE输入曲线对照" :data-status="model.status" :data-run-id="runId" :data-region="region" :data-keyword="selected?.keyword" :data-points="model.pointCount">
    <header><div><span class="eyebrow">ROCK & FLUID INPUT</span><h3>调参前，先看曲线怎样变化</h3><p>基线 #{{ runId }} · {{ regionKind }}区 {{ region }} · {{ sourceFile }}</p></div><span class="status" :class="model.status">{{ statusText }}</span></header>
    <p v-if="model.error" class="validation" role="status">{{ model.error }}</p>
    <template v-if="model.tables.length">
      <div class="curve-toolbar"><nav aria-label="输入曲线表族"><button v-for="t in model.tables" :key="t.keyword" type="button" :class="{ active: selected?.keyword === t.keyword }" @click="tableKey = t.keyword">{{ t.keyword }}</button></nav><div class="actions"><el-button size="small" @click="exportPng">导出输入曲线PNG</el-button><el-button size="small" @click="exportCsv">导出全部表点CSV</el-button></div></div>
      <div class="curve-facts"><span><b>{{ model.pointCount }}</b> 个源表点</span><span><b>{{ selected?.branches.length }}</b> 个独立分支</span><span>虚线／圆点：原表　实线／菱形：目标预览</span></div>
      <el-select v-if="selected?.branches.length > 1" v-model="branchId" aria-label="输入曲线分支" class="branch-select"><el-option label="全部分支（分别连线）" value="all"/><el-option v-for="b in selected.branches" :key="b.id" :label="b.label" :value="String(b.id)"/></el-select>
      <div class="curve-grid"><div v-for="(panel,index) in panels" :key="panel.unit" :ref="el => { hosts[index] = el }" class="curve-canvas" role="img" :aria-label="`${selected.keyword} ${panel.labels.join('/')}输入表点曲线`"/></div>
      <p class="footnote">表点按源文件顺序连线，各分支独立；不同单位分别显示。连线仅辅助查看输入点，不代表求解器插值曲线或生产结果。点击下方“创建方案并计算”后，再到结果区比较真实响应。</p>
    </template>
  </section>
</template>
<style scoped>
.input-curves { min-width: 0; margin: 18px 0; padding: 22px; border: 1px solid #dce7f1; border-radius: 13px; background: linear-gradient(125deg,#eef7ff 0%,#f7fbff 38%,#fff 85%); color: #58738e; }
.input-curves header { display: flex; justify-content: space-between; flex-wrap: wrap; gap: 14px; align-items: start; }.eyebrow { font-size: 10px; letter-spacing: .13em; font-weight: 700; color: #4083b6; }.input-curves h3 { font-size: 19px; margin: 7px 0 9px; color: #234865; }.input-curves p { font-size: 12px; line-height: 1.8; overflow-wrap: anywhere; margin: 6px 0; }.status { border-radius: 20px; padding: 7px 12px; background: #edf2f8; font-size: 11px; }.status.preview { color: #197d6c; background: #e3f6ef; }.status.invalid,.status.unavailable { color: #9b612b; background: #fff0d9; }.validation { color: #a2612a; padding: 9px 12px; background: #fff8eb; border-radius: 7px; }
.curve-toolbar { display: flex; justify-content: space-between; gap: 14px; flex-wrap: wrap; margin: 20px 0 12px; }.curve-toolbar nav { display: flex; flex-wrap: wrap; gap: 6px; }.curve-toolbar nav button { cursor: pointer; border: 1px solid #d9e5f1; background: #fff; color: #5a7793; padding: 8px 16px; border-radius: 7px; font-weight: 600; }.curve-toolbar nav button.active { color: #fff; background: #2b7fb9; border-color: #2b7fb9; }.actions { display: flex; flex-wrap: wrap; gap: 8px; }.actions .el-button { margin: 0; }.curve-facts { display: flex; gap: 12px 24px; flex-wrap: wrap; font-size: 11px; margin-bottom: 14px; color: #8296a8; }.curve-facts b { color: #315e82; font-size: 17px; font-variant-numeric: tabular-nums; }.branch-select { width: min(380px,100%); margin-bottom: 12px; }.curve-grid { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 14px; }.curve-canvas { height: 340px; min-width: 0; border: 1px solid #e2ebf2; border-radius: 10px; background: #fff; }.curve-canvas:only-child { grid-column: 1 / -1; }.input-curves .footnote { color: #8b9cad; margin-top: 12px; font-size: 11px; }
@media(max-width:950px) { .curve-grid { grid-template-columns: 1fr; } } @media(max-width:550px) { .input-curves { padding: 12px; }.input-curves h3 { font-size: 16px; }.curve-canvas { height: 320px; } }
</style>
