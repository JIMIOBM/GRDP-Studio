<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { scenarioMetricPairs, scenarioMetricCards, scenarioMetricLabels, formatScenarioValue as formatGridValue } from './eclipseScenarioDisplay.js'
const props = defineProps({ run: Object, baseline: Object, kind: { type: String, default: 'thermal' } })
const pairs = computed(() => scenarioMetricPairs(props.run, props.baseline)), cards = computed(() => scenarioMetricCards(pairs.value, props.kind))
const selectedKey = ref(''), host = ref(null)
const selected = computed(() => pairs.value.find(p => p.key === selectedKey.value) || cards.value[0] || pairs.value[0])
let chart, observer, generation = 0
watch(() => props.run?.id, () => { selectedKey.value = '' })
async function render() {
  const token = ++generation; await nextTick()
  if (token !== generation) return
  if (!host.value || !selected.value) { observer?.disconnect(); chart?.dispose(); chart = null; return }
  if (!chart || chart.getDom() !== host.value) {
    observer?.disconnect(); chart?.dispose(); chart = echarts.init(host.value)
    observer = new ResizeObserver(() => chart?.resize()); observer.observe(host.value)
  }
  const p = selected.value
  chart.setOption({ animation: false, color: ['#92a6be', '#2784d8'], tooltip: { trigger: 'axis', renderMode: 'richText' },
    title: { text: `${p.keyword} · ${p.object} · 原始列${p.vectorIndex + 1}`, left: 12, top: 3, textStyle: { fontSize: 11, fontWeight: 'normal', color: '#7592ac' } },
    legend: { top: 26, data: [`原基线 #${props.baseline.id}`, `当前方案 #${props.run.id}`] },
    grid: { left: 58, right: 28, top: 75, bottom: 58, containLabel: true },
    xAxis: { type: 'value', name: '实际天数', nameLocation: 'middle', nameGap: 30, splitLine: { show: false } },
    yAxis: { type: 'value', name: p.unit, scale: true, splitLine: { lineStyle: { color: '#edf2f8' } } },
    dataZoom: [{ type: 'inside' }],
    series: [{ name: `原基线 #${props.baseline.id}`, type: 'line', showSymbol: false, lineStyle: { type: 'dashed', width: 2 }, data: p.baseline.points.map(point => [point.timeDays, point.value]) },
      { name: `当前方案 #${props.run.id}`, type: 'line', showSymbol: false, lineStyle: { width: 3 }, areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: '#378bdb28' }, { offset: 1, color: '#378bdb00' }]) },
        data: p.current.points.map(point => [point.timeDays, point.value]), markLine: props.run.parameters?.startTimeDays ? { symbol: 'none', label: { formatter: '调参生效后' }, lineStyle: { color: '#dda44b', type: 'dashed' }, data: [{ xAxis: props.run.parameters.startTimeDays }] } : undefined }]
  }, true)
  chart.resize()
}
watch([selected, () => props.run?.id, () => props.baseline?.id], render, { immediate: true })
onBeforeUnmount(() => { generation++; observer?.disconnect(); chart?.dispose() })
const exportChart = () => { if (!chart) return; const a = document.createElement('a'); a.href = chart.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: '#fff' }); a.download = `eclipse-${props.run.id}-vs-${props.baseline.id}-${selected.value.keyword}.png`; a.click() }
</script>
<template>
  <section v-if="pairs.length" class="scenario-dashboard" aria-label="ECLIPSE方案效果看板" :data-run-id="run.id">
    <header><div><span>SCENARIO PERFORMANCE</span><h3>{{ kind === 'thermal' ? '注汽调整带来了什么变化' : '当前参数方案的生产响应' }}</h3><p>原基线 #{{ baseline.id }} → 当前方案 #{{ run.id }} · 直接比较真实计算结果</p></div><el-tag type="success">完整成功结果</el-tag></header>
    <div class="scenario-cards"><button v-for="card in cards" :key="card.key" :class="{ active: selected?.key === card.key }" @click="selectedKey = card.key"><span>{{ scenarioMetricLabels[card.keyword] }} · {{ card.object }} <small>{{ card.keyword }} · 原始列 {{ card.vectorIndex + 1 }}</small></span><strong>{{ formatGridValue(card.value) }} <em>{{ card.unit }}</em></strong><div>基线 {{ formatGridValue(card.baselineValue) }} <b :class="{ negative: card.difference < 0 }">{{ card.difference > 0 ? '+' : '' }}{{ formatGridValue(card.difference) }}</b></div><small>共同末时刻：第{{ card.timeDays }}天</small></button></div>
    <div class="scenario-plot-heading"><el-select v-model="selectedKey" aria-label="方案效果看板指标" :placeholder="selected ? `${selected.keyword} · ${selected.object}` : '选择指标'"><el-option v-for="pair in pairs" :key="pair.key" :value="pair.key" :label="`${pair.keyword} · ${pair.object} · 原始列${pair.vectorIndex + 1} · ${pair.unit}`"/></el-select><el-button @click="exportChart">导出方案对比图</el-button></div>
    <div ref="host" class="scenario-chart" role="img" aria-label="基线与方案真实计算曲线"/>
    <p class="scenario-footnote">同名原始列分别保留；卡片取双方最后一个共同实际时刻，曲线保留各自原生时刻，不补算缺失值。增加或降低是否有利，需要结合产量、约束和成本判断。</p>
  </section>
</template>
<style scoped>
.scenario-dashboard { margin: 20px 0; padding: 22px; border: 1px solid #dce7f1; border-radius: 12px; background: linear-gradient(140deg,#eef6ff,#fff 55%); }.scenario-dashboard header { display: flex; justify-content: space-between; align-items: start; gap: 16px; }.scenario-dashboard header span { color: #3c7bb4; font-size: 10px; font-weight: 700; letter-spacing: .12em; }.scenario-dashboard h3 { color: #173d62; font-size: 19px; margin: 8px 0; }.scenario-dashboard p { font-size: 12px; color: #8395a8; }.scenario-cards { display: grid; grid-template-columns: repeat(4,minmax(0,1fr)); gap: 12px; margin: 20px 0; }.scenario-cards button { min-width: 0; text-align: left; padding: 16px; border: 1px solid #dfe8f2; background: #fff; border-radius: 9px; cursor: pointer; color: #5a7893; }.scenario-cards button.active { border-color: #71ace0; box-shadow: 0 4px 16px #2779bc12; }.scenario-cards button > span { display: block; font-size: 11px; }.scenario-cards small { display: block; margin-top: 7px; font-size: 10px; color: #8fa1b4; }.scenario-cards strong { display: block; color: #214d75; font-size: 25px; margin: 12px 0; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }.scenario-cards em { font-style: normal; font-size: 10px; font-weight: 400; }.scenario-cards button > div { font-size: 10px; display: flex; flex-wrap: wrap; gap: 8px; }.scenario-cards b { color: #b97e34; }.scenario-cards b.negative { color: #367eb7; }.scenario-plot-heading { display: flex; justify-content: space-between; flex-wrap: wrap; gap: 12px; }.scenario-plot-heading .el-select { width: min(400px,100%); }.scenario-chart { height: 330px; margin-top: 15px; background: #ffffffb8; border-radius: 8px; }.scenario-footnote { line-height: 1.7; }
@media(max-width:1100px) { .scenario-cards { grid-template-columns: repeat(2,minmax(0,1fr)); } } @media(max-width:550px) { .scenario-dashboard { padding: 12px; }.scenario-cards strong { font-size: 18px; }.scenario-dashboard h3 { font-size: 16px; } }
</style>
