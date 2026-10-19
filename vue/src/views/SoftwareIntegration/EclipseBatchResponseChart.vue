<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import * as echarts from 'echarts'
const props = defineProps({ rows: { type: Array, default: () => [] } })
const keyFor = row => JSON.stringify([row.vectorIndex ?? null, row.keyword, row.well, row.unit, row.timeDays])
const choices = computed(() => [...new Map(props.rows.map(row => [keyFor(row), { key: keyFor(row), label: `${row.keyword} · ${row.well} · ${row.unit}${row.vectorIndex == null ? '' : ` · 原始列${row.vectorIndex + 1}`}` }])).values()])
const key = ref(''), mode = ref('difference'), host = ref(null)
const selected = computed(() => choices.value.some(c => c.key === key.value) ? key.value : choices.value.find(c => c.label.startsWith('FOPT'))?.key || choices.value[0]?.key)
const candidates = computed(() => props.rows.filter(row => keyFor(row) === selected.value))
const plotted = computed(() => candidates.value.filter(row => typeof row.current === 'number' && Number.isFinite(row.current) && typeof row.baseline === 'number' && Number.isFinite(row.baseline) && typeof row.difference === 'number' && Number.isFinite(row.difference)))
let chart, observer, generation = 0
watch(choices, () => { if (!choices.value.some(c => c.key === key.value)) key.value = '' })
async function render() {
  const token = ++generation; await nextTick()
  if (token !== generation) return
  if (!host.value || !plotted.value.length) { chart?.dispose(); chart = null; observer?.disconnect(); return }
  if (!chart || chart.getDom() !== host.value) { chart?.dispose(); observer?.disconnect(); chart = echarts.init(host.value); observer = new ResizeObserver(() => chart?.resize()); observer.observe(host.value) }
  const rows = plotted.value, delta = mode.value === 'difference'
  chart.setOption({ animation: false, tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, renderMode: 'richText' },
    title: { text: `${choices.value.find(c => c.key === selected.value)?.label} · 第${rows[0].timeDays}天`, top: 4, left: 12, textStyle: { fontSize: 11, fontWeight: 'normal', color: '#7894ad' } },
    grid: { left: 20, right: 75, top: delta ? 45 : 80, bottom: 42, containLabel: true }, legend: delta ? { show: false } : { top: 31 },
    xAxis: { type: 'value', name: rows[0].unit, splitLine: { lineStyle: { color: '#edf2f8' } } },
    yAxis: { type: 'category', inverse: true, data: rows.map(row => row.name), axisLine: { show: false }, axisTick: { show: false }, axisLabel: { width: 110, overflow: 'truncate' } },
    series: delta ? [{ name: '相对基线差值', type: 'bar', barMaxWidth: 20, data: rows.map(row => ({ value: row.difference, itemStyle: { color: row.difference < 0 ? '#4b91ce' : '#dfa24e', borderRadius: 4 } })), label: { show: true, position: 'right', fontSize: 10 }, markLine: { symbol: 'none', silent: true, data: [{ xAxis: 0 }], lineStyle: { color: '#ccd9e7', type: 'solid' }, label: { show: false } } }] :
      [{ name: '原基线', type: 'bar', barMaxWidth: 15, itemStyle: { color: '#bdcddd', borderRadius: 3 }, data: rows.map(row => row.baseline) }, { name: '方案结果', type: 'bar', barMaxWidth: 15, itemStyle: { color: '#3489ce', borderRadius: 3 }, data: rows.map(row => row.current) }]
  }, true)
  chart.resize()
}
watch([plotted, mode], render, { immediate: true })
onBeforeUnmount(() => { generation++; observer?.disconnect(); chart?.dispose() })
const exportPng = () => { if (!chart) return; const a = document.createElement('a'); a.href = chart.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: '#fff' }); a.download = 'eclipse-batch-sensitivity.png'; a.click() }
</script>
<template>
  <section v-if="choices.length" class="batch-response-chart" aria-label="批量方案敏感性可视化">
    <header><div><span>SENSITIVITY / SCENARIO RESPONSE</span><h3>同一指标，看清各方案的变化幅度</h3><p>第{{ rows[0]?.timeDays }}天 · {{ plotted.length }}个可绘制方案 · 正负变化分别显示</p></div><el-button :disabled="!plotted.length" @click="exportPng">导出敏感性图</el-button></header>
    <div class="batch-plot-controls"><el-select v-model="key" aria-label="批量敏感性图指标" :placeholder="choices.find(c => c.key === selected)?.label"><el-option v-for="choice in choices" :key="choice.key" :value="choice.key" :label="choice.label"/></el-select><el-radio-group v-model="mode" aria-label="批量敏感性图展示方式"><el-radio-button value="difference">相对基线变化</el-radio-button><el-radio-button value="value">方案数值</el-radio-button></el-radio-group></div>
    <div v-if="plotted.length" ref="host" class="batch-plot-canvas" role="img" aria-label="独立方案真实响应柱状图"/>
    <p v-if="candidates.length > plotted.length">{{ candidates.length - plotted.length }}项缺少该实际时刻的完整对比值，未绘制，详情保留在原表格中。</p>
    <small>相同对象、原始列、单位与实际报告时刻分别绘图。同名列不合并；正负值仅表示变化方向，不自动判定优劣。</small>
  </section>
</template>
<style scoped>
.batch-response-chart { padding: 22px; margin: 18px 0; border: 1px solid #dce7f3; border-radius: 12px; background: linear-gradient(130deg,#f1f7ff,#fff); }.batch-response-chart header { display: flex; align-items: start; justify-content: space-between; gap: 14px; }.batch-response-chart header span { font-size: 10px; font-weight: 700; color: #4081ba; letter-spacing: .1em; }.batch-response-chart h3 { font-size: 18px; color: #254b6d; margin: 8px 0; }.batch-response-chart p, .batch-response-chart > small { font-size: 11px; color: #889db1; line-height: 1.7; }.batch-plot-controls { display: flex; flex-wrap: wrap; gap: 14px; margin-top: 16px; align-items: center; }.batch-plot-controls .el-select { width: min(390px,100%); }.batch-plot-canvas { height: 390px; margin-top: 12px; background: #ffffffa0; border-radius: 8px; } @media(max-width:600px) { .batch-response-chart { padding: 12px; }.batch-response-chart header { flex-direction: column; } }
</style>
