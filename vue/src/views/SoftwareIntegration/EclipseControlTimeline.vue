<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { controlTimeline } from './eclipseControlVisuals.js'
import { exportEclipseCharts } from './eclipseChartExport.js'
const props = defineProps({ inspection: Object, parameters: Object, response: Object, runId: Number, injection: Boolean })
const model = computed(() => controlTimeline(props)), well = ref(''), selectedKey = ref(''), rateHost = ref(null), pressureHost = ref(null)
const selected = computed(() => model.value?.wells.find(w => w.well === well.value) || model.value?.wells[0])
const period = computed(() => selected.value?.periods.find(p => p.key === selectedKey.value))
const fmt = value => value == null ? '无同时间值' : value.toLocaleString('zh-CN', { maximumFractionDigits: 4 })
watch(() => props.runId, () => { well.value = ''; selectedKey.value = '' })
let charts = [], observer, generation = 0
function dispose() { observer?.disconnect(); charts.forEach(c => c.dispose()); charts = [] }
async function render() {
  const token = ++generation; await nextTick(); if (token !== generation) return
  dispose(); if (!selected.value) return
  observer = new ResizeObserver(() => charts.forEach(c => c.resize()))
  const rows = selected.value.rows, actual = (name, column, color, dash = false) => ({ name, type: 'line', showSymbol: true, symbolSize: 5, connectNulls: false, lineStyle: { width: 2, type: dash ? 'dashed' : 'solid' }, itemStyle: { color }, data: rows.map(r => [r.timeDays, r[column]]) })
  for (const [index, host] of [rateHost.value, pressureHost.value].entries()) {
    if (!host) continue
    const chart = echarts.init(host); charts.push(chart); observer.observe(host)
    const title = index === 0 ? `${props.injection ? '注水量 WWIR' : '产油量 WOPR'} · ${model.value.flowUnit}` : `井底压力 WBHP · ${model.value.pressureUnit}`
    const series = index === 0 ? [actual('原基线实际量', 'baselineRate', '#9aaec0', true), actual('方案实际量', 'rate', '#247fc1'), { name: '当期目标（报告点）', type: 'scatter', z: 6, symbol: 'diamond', symbolSize: 8, itemStyle: { color: '#dc9c3f' }, data: rows.map(r => [r.timeDays, r.target]) }] : [actual('实际 BHP', 'bhp', '#169c94'), actual(`原 BHP ${props.injection ? '上限' : '下限'}`, 'bhpLimit', '#d18a63', true)]
    if (period.value) series[0].markArea = { silent: true, itemStyle: { color: '#75b6e51c' }, data: [[{ xAxis: period.value.timeDays }, { xAxis: period.value.nextTimeDays }]] }
    chart.setOption({ animation: false, title: { text: title, subtext: `井 ${selected.value.well} · 方案 #${props.runId} / 基线 #${props.parameters.baselineRunId}`, left: 12, top: 10, textStyle: { fontSize: 12, color: '#2d526e' }, subtextStyle: { fontSize: 10 } }, tooltip: { trigger: 'axis', renderMode: 'richText' }, legend: { top: 57, type: 'scroll', textStyle: { fontSize: 10 } }, grid: { left: 18, right: 25, top: 100, bottom: 50, containLabel: true }, xAxis: { type: 'value', name: '实际天数', nameLocation: 'middle', nameGap: 28, splitLine: { show: false } }, yAxis: { type: 'value', scale: true, splitLine: { lineStyle: { color: '#edf3f8' } } }, dataZoom: [{ type: 'inside' }], series }, true)
  }
}
watch([model, selected, period], render, { immediate: true })
onBeforeUnmount(() => { generation++; dispose() })
const exportPng = () => exportEclipseCharts(charts, `eclipse-control-${props.runId}-${selected.value.well}.png`)
</script>
<template>
  <section v-if="model" class="control-timeline" aria-label="ECLIPSE井控事件可视化" :data-run-id="runId" :data-well="selected?.well">
    <header><div><span>CONTROL EVENTS</span><h3>{{ injection ? '分阶段调注' : '分阶段调产' }} · 目标何时生效</h3><p>基线 #{{ parameters.baselineRunId }} → 方案 #{{ runId }} · {{ model.wells.length }} 口修改井 · 第 0–{{ model.endTimeDays }} 天</p></div><el-button size="small" @click="exportPng">导出井控响应PNG</el-button></header>
    <div class="timeline-facts"><span><b>{{ parameters.edits.length }}</b> 条修改</span><span><b>{{ response.rows.filter(r => r.targetReached).length }}/{{ response.rows.length }}</b> 个报告点达标</span><span><b>{{ response.rows.filter(r => r.atBhpLimit).length }}</b> 个报告点触及原压力限值</span><el-tag :type="response.consistent ? 'success' : 'warning'">{{ response.consistent ? '目标与压力约束核对通过' : '存在不一致，请审阅诊断' }}</el-tag></div>
    <div class="timeline-rows"><div v-for="item in model.wells" :key="item.well" class="timeline-row"><button type="button" :class="{ selected: selected?.well === item.well }" @click="well = item.well; selectedKey = ''">{{ item.well }}</button><div class="period-track"><button v-for="p in item.periods" :key="p.key" type="button" :class="{ modified: p.modified, active: selectedKey === p.key }" :style="{ left: `${100 * p.timeDays / model.endTimeDays}%`, width: `${100 * (p.nextTimeDays - p.timeDays) / model.endTimeDays}%` }" :title="`${p.date} · (${p.timeDays}, ${p.nextTimeDays}] 天 · ${p.target} ${model.flowUnit} · ${p.sourceFile}:${p.lineNumber}`" :aria-label="`${item.well} 第${p.timeDays}天控制事件`" @click="well = item.well; selectedKey = p.key"><strong>{{ fmt(p.target) }}</strong><small>{{ p.timeDays }}–{{ p.nextTimeDays }}天</small></button></div></div></div>
    <p class="legend">蓝色：修改目标；浅色：保留的原控制。点选阶段查看来源和报告响应。单位 {{ model.flowUnit }}。</p>
    <div v-if="period" class="period-detail" aria-label="选中井控事件">{{ selected.well }} · {{ period.date }} · {{ period.sourceFile }}:{{ period.lineNumber }} · 原目标 {{ fmt(period.original) }} → {{ fmt(period.target) }} {{ model.flowUnit }} · 生效报告区间 ({{ period.timeDays }}, {{ period.nextTimeDays }}] 天</div>
    <div class="control-charts"><div ref="rateHost" role="img" aria-label="阶段目标与实际井流量曲线"/><div ref="pressureHost" role="img" aria-label="阶段实际井压与原压力限制曲线"/></div>
    <p class="legend">日期边界报告属于刚结束的控制期；后续记录覆盖前期。曲线保留实际报告点，不补缺失值；菱形是该报告点对应的输入目标，不冒充计算输出。</p>
  </section>
</template>
<style scoped>
.control-timeline { margin: 18px 0; padding: 22px; border: 1px solid #dce8f1; border-radius: 12px; background: linear-gradient(130deg,#f0f7ff,#fff 65%); color: #56748e; }.control-timeline header { display: flex; justify-content: space-between; align-items: start; flex-wrap: wrap; gap: 12px; }.control-timeline header span { color: #4388b9; font-size: 10px; letter-spacing: .12em; font-weight: 700; }.control-timeline h3 { margin: 8px 0; font-size: 19px; color: #284d6b; }.control-timeline p { font-size: 11px; line-height: 1.8; }.timeline-facts { display: flex; flex-wrap: wrap; gap: 18px; align-items: center; padding: 16px 0 20px; font-size: 11px; }.timeline-facts b { font-size: 19px; color: #2a6189; }.timeline-rows { overflow-x: auto; }.timeline-row { display: flex; min-width: 680px; gap: 12px; margin-bottom: 10px; }.timeline-row>button { width: 100px; flex-shrink: 0; background: #fff; border: 1px solid #cddfec; border-radius: 7px; color: #517b9b; cursor: pointer; }.timeline-row>button.selected { border-color: #3f8bc1; color: #2478b5; font-weight: 700; }.period-track { height: 62px; position: relative; flex: 1; }.period-track button { position: absolute; top: 0; height: 62px; border: 2px solid #fff; border-radius: 6px; background: #e4edf5; color: #5c7c96; cursor: pointer; overflow: hidden; }.period-track button.modified { background: #387fb4; color: #fff; }.period-track button.active { outline: 2px solid #e4ac4f; outline-offset: -4px; }.period-track strong { display: block; font-size: 14px; }.period-track small { display: block; font-size: 9px; margin-top: 5px; }.period-detail { background: #eaf4ff; color: #3c769f; padding: 12px; border-radius: 7px; font-size: 12px; margin: 12px 0; overflow-wrap: anywhere; }.control-charts { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 14px; }.control-charts>div { height: 330px; min-width: 0; background: #fff; border: 1px solid #e1eaf1; border-radius: 9px; }.legend { color: #8296a7; }@media(max-width:950px) { .control-charts { grid-template-columns: 1fr; } }@media(max-width:550px) { .control-timeline { padding: 12px; } }
</style>
