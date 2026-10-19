<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { thermalRockRecords, thermalRockLabels, thermalRockUnit, buildThermalRock, matchesThermalRock, thermalRockResponse, thermalRockCsv } from './eclipseThermalRock.js'
import { thermalObject } from './eclipseThermal.js'
import { exportEclipseCharts } from './eclipseChartExport.js'

const props = defineProps({ run: Object, inspection: Object, baseline: Object, disabled: Boolean })
const emit = defineEmits(['run-scenario'])
const records = computed(() => thermalRockRecords(props.inspection)), field = ref('HEATCR'), target = ref(null), error = ref(''), selected = ref(''), exporting = ref(false)
const scenario = computed(() => matchesThermalRock(props.run?.parameters, props.inspection) ? props.run.parameters : null)
const available = computed(() => props.run?.status === 'SUCCEEDED' && props.run.resultContract === 'VALID_FULL' && props.run.parameters == null && records.value.length === 2)
const record = computed(() => records.value.find(r => r.field === (scenario.value?.field || field.value)))
const plan = computed(() => scenario.value || buildThermalRock({ inspection: props.inspection, baselineRunId: props.run?.id, field: field.value, targetValue: target.value }))
const response = computed(() => scenario.value && props.baseline ? thermalRockResponse(props.run, props.baseline, props.inspection) : null)
const metric = computed(() => response.value?.metrics.find(m => m.key === selected.value) || response.value?.metrics.find(m => m.keyword === 'WTEMP') || response.value?.metrics.find(m => m.keyword === 'BTEMP') || response.value?.metrics.find(m => m.keyword === 'FHLR'))
const context = computed(() => props.inspection?.thermalRockContext)
const object = m => thermalObject(m, props.inspection.dimensions)
const fmt = n => typeof n === 'number' && Number.isFinite(n) ? n.toLocaleString('zh-CN', { maximumFractionDigits: 6 }) : '—'
watch([() => props.run?.id, () => props.inspection], () => { field.value = 'HEATCR'; target.value = null; selected.value = ''; error.value = '' }, { immediate: true })
watch(field, () => { target.value = null; error.value = '' })
function submit() {
  if (!available.value || props.disabled) return
  if (!plan.value) { error.value = '新值须不同于原值；岩石热容须大于0，综合导热率允许为0，数值须有限且不超过1000000000。'; return }
  error.value = ''; emit('run-scenario', plan.value)
}
const inputHost = ref(null), curveHost = ref(null), differenceHost = ref(null)
let charts = [], observer, generation = 0
function dispose() { observer?.disconnect(); observer = null; charts.forEach(c => c.dispose()); charts = [] }
async function render() {
  const token = ++generation; await nextTick(); if (token !== generation) return
  dispose(); if (!inputHost.value || !record.value) return
  const c = echarts.init(inputHost.value); charts.push(c)
  const r = record.value, input = [{ name: '原工程', value: r.originalValue, itemStyle: { color: '#86a8bd' } }, ...(plan.value ? [{ name: '本方案', value: plan.value.targetValue, itemStyle: { color: '#e7a24b' } }] : [])]
  c.setOption({ animation: false, title: { text: `${thermalRockLabels[r.field]} · 输入对比`, left: 18, top: 16, textStyle: { color: '#365c70', fontSize: 13 } },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, valueFormatter: fmt }, grid: { left: 80, right: 80, top: 75, bottom: 65 },
    xAxis: { type: 'value', name: thermalRockUnit(r.unit), nameLocation: 'middle', nameGap: 32, min: 0 }, yAxis: { type: 'category', data: input.map(d => d.name), inverse: true, axisTick: { show: false } },
    series: [{ type: 'bar', data: input, barMaxWidth: 36, label: { show: true, position: 'right', formatter: p => fmt(p.value), color: '#567184' }, itemStyle: { borderRadius: [0, 6, 6, 0] } }] })
  const m = metric.value
  if (m && curveHost.value && differenceHost.value) {
    const common = new Map(m.baseline.points.map(p => [p.timeDays, p.value]))
    const make = (host, title, series, yName) => {
      const chart = echarts.init(host); charts.push(chart)
      chart.setOption({ animation: false, title: { text: title, left: 16, top: 14, textStyle: { color: '#365c70', fontSize: 12 } },
        tooltip: { trigger: 'axis', valueFormatter: fmt }, legend: { type: 'scroll', top: 44, textStyle: { fontSize: 10 } }, grid: { left: 26, right: 28, top: 95, bottom: 55, containLabel: true },
        xAxis: { type: 'value', name: '实际天数', nameLocation: 'middle', nameGap: 30 }, yAxis: { type: 'value', name: yName, scale: true }, dataZoom: [{ type: 'inside' }], series })
    }
    make(curveHost.value, `${m.keyword} · ${object(m)} · 原始列${m.vectorIndex + 1}`, [
      { name: `基线 #${props.baseline.id}`, type: 'line', showSymbol: false, lineStyle: { type: 'dashed', width: 2 }, itemStyle: { color: '#94acbe' }, data: m.baseline.points.map(p => [p.timeDays, p.value]) },
      { name: `本方案 #${props.run.id}`, type: 'line', showSymbol: false, lineStyle: { width: 3 }, itemStyle: { color: '#258ba7' }, data: m.current.points.map(p => [p.timeDays, p.value]) }
    ], m.unit)
    make(differenceHost.value, '相同实际时刻 · 方案减基线', [{ name: `${m.keyword}差值`, type: 'line', showSymbol: true, symbolSize: 4,
      itemStyle: { color: '#df9b43' }, areaStyle: { opacity: .09 }, data: m.current.points.filter(p => common.has(p.timeDays)).map(p => [p.timeDays, p.value - common.get(p.timeDays)]) }], `Δ ${m.unit}`)
  }
  observer = new ResizeObserver(() => charts.forEach(c => c.resize()))
  for (const host of [inputHost.value, curveHost.value, differenceHost.value]) if (host) observer.observe(host)
}
watch([record, plan, metric], render, { immediate: true })
onBeforeUnmount(() => { generation++; dispose() })
async function png() {
  if (exporting.value || !charts.length) return
  exporting.value = true
  try { await exportEclipseCharts(charts, `E300-岩石热物性-${props.run.id}.png`) } catch { error.value = '图片导出失败，请重试。' } finally { exporting.value = false }
}
function csv() {
  if (!response.value) return
  const url = URL.createObjectURL(new Blob(['\uFEFF' + thermalRockCsv(response.value, props.run)], { type: 'text/csv;charset=utf-8' }))
  const a = document.createElement('a'); a.href = url; a.download = `E300-岩石热物性-${props.run.id}.csv`; a.click(); URL.revokeObjectURL(url)
}
</script>

<template>
  <section v-if="available || scenario" class="thermal-rock" aria-label="E300岩石热物性与温度响应">
    <header><div><span>THERMAL ROCK PROPERTIES</span><h3>{{ scenario ? '岩石热物性 · 温度与产量响应' : '调整热容与导热率，观察热量如何传播' }}</h3><p>岩石热容影响储热能力，综合导热率影响热传导；修改后以真实计算结果查看温度、热损失和产量。</p></div><div class="actions"><el-button :loading="exporting" @click="png">导出热物性对比图</el-button><el-button v-if="response" @click="csv">导出热物性响应CSV</el-button></div></header>
    <div class="property-workbench">
      <div class="property-controls">
        <template v-if="available">
          <div class="property-tabs"><button v-for="r in records" :key="r.field" :class="{ active: field === r.field }" :disabled="disabled" @click="field = r.field"><b>{{ thermalRockLabels[r.field] }}</b><small>{{ r.field }} · {{ thermalRockUnit(r.unit) }}</small></button></div>
          <el-form label-position="top" :disabled="disabled"><el-form-item :label="`新${thermalRockLabels[field]} · ${thermalRockUnit(record?.unit)}`"><el-input-number v-model="target" :controls="false" aria-label="岩石热物性新值"/></el-form-item><el-button type="primary" @click="submit">修改热物性并真实计算</el-button></el-form>
        </template>
        <template v-else><small class="scenario-kind">{{ scenario.field }}</small><h4>{{ thermalRockLabels[scenario.field] }}</h4><div class="property-change">{{ fmt(scenario.originalValue) }} <span>→</span> {{ fmt(scenario.targetValue) }}</div><p>{{ thermalRockUnit(scenario.unit) }} · 基线 #{{ scenario.baselineRunId }}</p></template>
        <div class="grid-scope"><b>{{ context.activeCellCount }}</b> 个活动网格统一应用<span>原数组 {{ context.cellCount }} 个网格；非活动格原零值覆盖保留。</span></div>
        <p>只修改选中的热物性，原注汽条件、生产井控和模拟终点保留。</p>
      </div>
      <div ref="inputHost" class="property-input" role="img" aria-label="岩石热物性原值与目标值对比"/>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false"/>
    <template v-if="scenario">
      <el-alert v-if="!response" type="warning" :closable="false" title="正在核对完整同版本基线；来源或原始列不一致时不展示响应。"/>
      <template v-else>
        <div class="rock-cards"><article v-for="m in response.cards" :key="m.key"><small>{{ m.label }} · {{ object(m) }}</small><strong>{{ fmt(m.value) }}</strong><span>{{ m.unit }} · 共同第 {{ fmt(m.finalTime) }} 天</span><p>基线 {{ fmt(m.baselineValue) }} <b :class="{ negative: m.difference < 0 }">{{ m.difference > 0 ? '+' : '' }}{{ fmt(m.difference) }}</b></p></article></div>
        <div class="metric-picker"><el-select v-model="selected" aria-label="热物性实际响应指标" :placeholder="metric ? `${metric.keyword} · ${object(metric)}` : '选择原始指标'"><el-option v-for="m in response.metrics" :key="m.key" :value="m.key" :label="`${m.keyword} · ${object(m)} · 原始列${m.vectorIndex + 1} · ${m.unit}`"/></el-select><span>原生单位与实际时刻；重复列分别保留。</span></div>
        <div class="rock-charts"><div ref="curveHost" role="img" aria-label="热物性方案与基线实际曲线"/><div ref="differenceHost" role="img" aria-label="热物性共同实际时刻差值"/></div>
        <p v-if="!response.temperatureAvailable">原工程未请求温度曲线，此处展示已计算的热损失和产量；需要温度展示时，上传带温度输出的原工程。</p>
        <p>下方可继续查看温度层图、真实三维差值与选格历史。热损失、井温和产量的变化需要结合工程目标判断。</p>
      </template>
    </template>
  </section>
</template>

<style scoped>
.thermal-rock{margin:22px 0;padding:24px;border:1px solid #dce7e9;border-radius:14px;background:linear-gradient(135deg,#f0f9f8,#fff 70%);color:#4d6c7b}.thermal-rock header{display:flex;justify-content:space-between;gap:16px;flex-wrap:wrap}.thermal-rock header>div>span,.scenario-kind{color:#248d94;letter-spacing:.12em;font-size:11px;font-weight:700}.thermal-rock h3{color:#284d5c;font-size:21px;margin:10px 0}.thermal-rock p{font-size:12px;line-height:1.8}.actions{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.property-workbench{display:grid;grid-template-columns:minmax(300px,.9fr) minmax(0,1.4fr);gap:18px;margin:20px 0}.property-controls{padding:20px;border:1px solid #dbe9e9;background:#f7fbfb;border-radius:10px}.property-tabs{display:grid;grid-template-columns:1fr 1fr;gap:9px;margin-bottom:20px}.property-tabs button{padding:14px 10px;border:1px solid #d1e3e6;border-radius:8px;background:#fff;color:#62818b;text-align:left;cursor:pointer}.property-tabs button.active{border-color:#56a5ab;background:#e4f4f3}.property-tabs b,.property-tabs small{display:block}.property-tabs b{font-size:13px;margin-bottom:7px}.property-tabs small{font-size:9px;line-height:1.6}.property-tabs button:disabled{cursor:not-allowed;opacity:.65}.property-input{height:320px;background:#fff;border:1px solid #dce9e9;border-radius:10px;min-width:0}.grid-scope{font-size:12px;padding:18px 0 0}.grid-scope>b{font-size:24px;color:#2c8c91;margin-right:7px}.grid-scope>span{display:block;margin-top:7px;font-size:11px;color:#82969f}.property-controls h4{font-size:18px;margin:16px 0;color:#3d6674}.property-change{font-size:32px;font-weight:700;color:#368b95}.property-change>span{font-size:20px;margin:0 12px;color:#bdcbd2}.rock-cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:12px;margin:22px 0}.rock-cards article{background:#fff;border:1px solid #dce9e9;border-radius:9px;padding:18px}.rock-cards small,.rock-cards strong,.rock-cards span{display:block}.rock-cards strong{font-size:25px;color:#2b8795;margin:12px 0}.rock-cards small{font-size:12px}.rock-cards span{font-size:10px;color:#849ba4}.rock-cards p{font-size:10px;margin-bottom:0}.rock-cards b{color:#b18c4e;margin-left:9px}.rock-cards b.negative{color:#487e9b}.metric-picker{display:flex;gap:14px;align-items:center;flex-wrap:wrap;font-size:11px;margin:16px 0}.metric-picker :deep(.el-select){width:440px;max-width:100%}.rock-charts{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}.rock-charts>div{height:340px;min-width:0;border:1px solid #dce9e9;border-radius:9px;background:#fff}.thermal-rock :deep(.el-alert){margin-top:12px}@media(max-width:1000px){.property-workbench,.rock-charts{grid-template-columns:1fr}}@media(max-width:550px){.thermal-rock{padding:14px}.property-controls{padding:14px}.thermal-rock h3{font-size:18px}.rock-cards{grid-template-columns:1fr 1fr}.rock-cards article{padding:12px}.rock-cards strong{font-size:20px}.property-tabs{grid-template-columns:1fr}.property-input{height:300px}}
</style>
