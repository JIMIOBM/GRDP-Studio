<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { thermalInjectionRecords, thermalStageDays } from './eclipseThermalInjection.js'
import { buildThermalPlan, matchesThermalPlan, thermalPlanOriginal, thermalPlanPeriods, thermalPlanResponse, thermalPlanCsv } from './eclipseThermalPlan.js'
import { exportEclipseCharts } from './eclipseChartExport.js'

const props = defineProps({ run: Object, inspection: Object, baseline: Object, disabled: Boolean })
const emit = defineEmits(['run-scenario'])
const records = computed(() => thermalInjectionRecords(props.inspection)), days = computed(() => thermalStageDays(props.inspection))
const scenario = computed(() => matchesThermalPlan(props.run?.parameters, props.inspection) ? props.run.parameters : null)
const available = computed(() => props.run?.status === 'SUCCEEDED' && props.run?.resultContract === 'VALID_FULL' && props.run.parameters == null && days.value.length >= 2)
const well = ref(''), stages = ref([]), error = ref(''), selected = ref(0), exporting = ref(false)
const record = computed(() => records.value.find(r => r.well === well.value))
const plan = computed(() => scenario.value || buildThermalPlan({ inspection: props.inspection, baselineRunId: props.run?.id, well: well.value, stages: stages.value }))
const periods = computed(() => plan.value ? thermalPlanPeriods(plan.value, props.inspection) : [])
const period = computed(() => periods.value[selected.value] || periods.value[0])
const response = computed(() => scenario.value && props.baseline ? thermalPlanResponse(props.run?.result?.summary, props.baseline.result?.summary, scenario.value, props.inspection) : null)
const rateUnit = computed(() => scenario.value?.rateUnit || record.value?.rateUnit || 'STB/day')
const tempUnit = computed(() => (scenario.value?.temperatureUnit || record.value?.temperatureUnit) === 'degC' ? '°C' : '°F')
const pressureUnit = computed(() => rateUnit.value === 'STB/day' ? 'psia' : 'barsa')
const fmt = value => typeof value === 'number' && Number.isFinite(value) ? value.toLocaleString('zh-CN', { maximumFractionDigits: 6 }) : '—'
function resetStages() {
  error.value = ''; selected.value = 0
  const r = record.value
  stages.value = r ? days.value.slice(0, 2).map(startTimeDays => ({ startTimeDays, ...thermalPlanOriginal(r) })) : []
}
watch([() => props.run?.id, () => props.inspection], () => { well.value = ''; stages.value = []; error.value = ''; selected.value = 0 }, { immediate: true })
watch(well, resetStages)
function addStage() {
  if (props.disabled || stages.value.length >= 8) return
  const last = stages.value.at(-1), day = days.value.find(d => d > (last?.startTimeDays || 0))
  if (day && record.value) stages.value.push({ ...(last || thermalPlanOriginal(record.value)), startTimeDays: day })
}
function submit() {
  if (!available.value || props.disabled) return
  if (!plan.value) { error.value = '请设置 2–8 个递增的原报告时刻，每阶段至少改变一项；流量和压力须大于零，干度为 0–1，温度须高于绝对零度。'; return }
  error.value = ''; emit('run-scenario', plan.value)
}
const targetHost = ref(null), flowHost = ref(null), thermalHost = ref(null)
let charts = [], observer, generation = 0
function dispose() { observer?.disconnect(); observer = null; charts.forEach(c => c.dispose()); charts = [] }
const palette = ['#849ab0', '#2586bd', '#e69932', '#17a6a0', '#7766b8', '#da7482', '#5392cc', '#7da656', '#b08362']
async function render() {
  const current = ++generation
  await nextTick(); if (current !== generation) return
  dispose()
  if (!plan.value || !targetHost.value || !periods.value.length) return
  const make = (host, title, axes, series) => {
    const chart = echarts.init(host); charts.push(chart)
    chart.setOption({ animation: false, color: palette, title: { text: title, left: 16, top: 15, textStyle: { fontSize: 13, color: '#34566c' } },
      tooltip: { trigger: 'axis', valueFormatter: fmt }, legend: { top: 43, type: 'scroll', textStyle: { fontSize: 10 } },
      grid: { left: 72, right: 74, top: 95, bottom: 60 }, xAxis: { type: 'value', name: '模拟天数', nameLocation: 'middle', nameGap: 30, min: 0 },
      yAxis: axes, dataZoom: [{ type: 'inside', filterMode: 'none' }], series })
    return chart
  }
  const target = field => periods.value.map((s, index) => ({ name: s.label, type: 'line', showSymbol: false, lineStyle: { width: selected.value === index ? 5 : 3 },
    data: [[s.startTimeDays, s[field]], [s.endTimeDays, s[field]]], itemStyle: { color: palette[index] } }))
  const targetChart = make(targetHost.value, `阶段注入目标 · ${plan.value.well}`, [{ type: 'value', name: rateUnit.value }], target('surfaceInjectionRate'))
  targetChart.on('click', event => { if (Number.isInteger(event.seriesIndex)) selected.value = event.seriesIndex })
  if (response.value && flowHost.value && thermalHost.value) {
    const actual = (keyword, name, yAxisIndex, color, original = false) => ({ name, type: 'line', yAxisIndex, showSymbol: false, connectNulls: false,
      lineStyle: { width: original ? 1.5 : 2.5, type: original ? 'dashed' : 'solid' }, itemStyle: { color },
      data: response.value.rows.filter(r => r.keyword === keyword && (!original || r.baselineValue !== null)).map(r => [r.timeDays, original ? r.baselineValue : r.value]) })
    const limits = field => periods.value.map((s, index) => ({ ...target(field)[index], name: field === 'bhpUpperLimit' ? '阶段压力上限' : '阶段注入目标',
      yAxisIndex: field === 'bhpUpperLimit' ? 1 : 0, lineStyle: { type: 'dotted', width: 1 }, itemStyle: { color: field === 'bhpUpperLimit' ? '#d99c3c' : '#8cb8cf' } }))
    make(flowHost.value, '实际注入与压力约束', [{ type: 'value', name: rateUnit.value }, { type: 'value', name: pressureUnit.value }],
      [actual('WWIR', '方案实际注入', 0, '#2586bd'), actual('WWIR', '基线实际注入', 0, '#a6b8c6', true), actual('WBHP', '方案实际井压', 1, '#e69932'), ...limits('surfaceInjectionRate'), ...limits('bhpUpperLimit')])
    const heat = response.value.metrics.find(m => m.keyword === 'FHLR')
    const temperature = response.value.metrics.find(m => m.keyword === 'WTEMP')
    make(thermalHost.value, temperature ? '热损失与实际井温' : '实际热损失响应', [{ type: 'value', name: heat?.unit || '' }, { type: 'value', name: temperature?.unit || '', show: !!temperature }],
      [actual('FHLR', '方案热损失', 0, '#17a6a0'), actual('FHLR', '基线热损失', 0, '#a6b8c6', true), ...(temperature ? [actual('WTEMP', '方案实际井温', 1, '#da7482')] : [])])
  }
  observer = new ResizeObserver(() => charts.forEach(c => c.resize()))
  for (const host of [targetHost.value, flowHost.value, thermalHost.value]) if (host) observer.observe(host)
}
watch([plan, response, selected], render, { immediate: true })
onBeforeUnmount(() => { generation++; dispose() })
async function png() {
  if (exporting.value || !charts.length) return
  exporting.value = true
  try { await exportEclipseCharts(charts, `E300-多阶段注汽-${props.run.id}.png`) } catch { error.value = '图片导出失败，请重试。' } finally { exporting.value = false }
}
function csv() {
  if (!response.value) return
  const url = URL.createObjectURL(new Blob(['\uFEFF' + thermalPlanCsv(response.value, props.run.id, scenario.value.baselineRunId)], { type: 'text/csv;charset=utf-8' }))
  const a = document.createElement('a'); a.href = url; a.download = `E300-多阶段注汽-${props.run.id}.csv`; a.click(); URL.revokeObjectURL(url)
}
</script>

<template>
  <section v-if="available || scenario" class="thermal-plan" aria-label="E300多阶段注汽计划">
    <header><div><span>THERMAL INJECTION PLAN</span><h3>{{ scenario ? '多阶段注汽 · 计算响应' : '编排多阶段注汽方案' }}</h3><p>先调注，再降温，或恢复原注汽条件；在原报告时刻后依次生效。</p></div><div class="actions"><el-button v-if="plan" :loading="exporting" @click="png">导出计划图</el-button><el-button v-if="response" @click="csv">导出实际响应CSV</el-button></div></header>
    <template v-if="available">
      <el-form label-position="top" :disabled="disabled">
        <el-form-item label="注入井"><el-select v-model="well" aria-label="多阶段注汽注入井" placeholder="选择注入井"><el-option v-for="r in records" :key="r.well" :value="r.well" :label="r.well"/></el-select></el-form-item>
        <template v-if="record">
          <div class="original"><b>原注汽条件</b><span>{{ fmt(record.surfaceInjectionRate) }} {{ rateUnit }}</span><span>{{ fmt(record.originalTemperature) }} {{ tempUnit }}</span><span>干度 {{ fmt(record.originalSteamQuality) }}</span><span>压力上限 {{ fmt(record.bhpUpperLimit) }} {{ pressureUnit }}</span></div>
          <div v-for="(stage, index) in stages" :key="index" class="stage-editor" :aria-label="`注汽阶段${index + 1}`">
            <b class="stage-number">{{ String(index + 1).padStart(2, '0') }}</b>
            <el-form-item label="原报告后生效"><el-select v-model="stage.startTimeDays" :aria-label="`阶段${index + 1}生效天数`"><el-option v-for="day in days" :key="day" :value="day" :label="`第 ${day} 天后`"/></el-select></el-form-item>
            <el-form-item :label="`地面注入目标 · ${rateUnit}`"><el-input-number v-model="stage.surfaceInjectionRate" :controls="false" :aria-label="`阶段${index + 1}注入目标`"/></el-form-item>
            <el-form-item :label="`输入温度 · ${tempUnit}`"><el-input-number v-model="stage.injectionTemperature" :controls="false" :aria-label="`阶段${index + 1}输入温度`"/></el-form-item>
            <el-form-item label="蒸汽干度 · 0–1"><el-input-number v-model="stage.steamQuality" :controls="false" :aria-label="`阶段${index + 1}蒸汽干度`"/></el-form-item>
            <el-form-item :label="`BHP上限 · ${pressureUnit}`"><el-input-number v-model="stage.bhpUpperLimit" :controls="false" :aria-label="`阶段${index + 1}压力上限`"/></el-form-item>
            <el-button :disabled="stages.length <= 2" :aria-label="`删除注汽阶段${index + 1}`" @click="stages.splice(index, 1)">删除</el-button>
          </div>
          <div class="actions"><el-button :disabled="stages.length >= 8 || !days.some(d => d > stages.at(-1)?.startTimeDays)" @click="addStage">添加注汽阶段</el-button><el-button type="primary" @click="submit">按多阶段计划真实计算</el-button></div>
          <p v-if="!plan">每阶段填写完整参数；时间递增，每阶段至少改变一项。可以恢复原值。支持 2–8 个阶段。</p>
        </template>
      </el-form>
    </template>
    <el-alert v-if="error" :title="error" type="error" :closable="false"/>
    <template v-if="plan">
      <div class="facts"><span><b>{{ plan.stages.length }}</b> 次阶段调整</span><span><b>{{ plan.well }}</b> 注入井</span><span><b>{{ fmt(periods.at(-1).endTimeDays) }}</b> 天完整计算</span><span>基线 #{{ plan.baselineRunId }}</span></div>
      <div class="stage-track"><button v-for="s in periods" :key="s.index" :class="{ selected: selected === s.index }" :style="{ flexGrow: s.endTimeDays - s.startTimeDays, borderTopColor: palette[s.index] }" @click="selected = s.index"><b>{{ s.label }}</b><small>{{ s.startTimeDays }}–{{ s.endTimeDays }} 天</small><strong>{{ fmt(s.surfaceInjectionRate) }} <small>{{ rateUnit }}</small></strong></button></div>
      <div v-if="period" class="period-detail"><b>{{ period.label }}</b><span>区间 ({{ period.startTimeDays }}, {{ period.endTimeDays }}] 天</span><span>输入温度 {{ fmt(period.injectionTemperature) }} {{ tempUnit }}</span><span>干度 {{ fmt(period.steamQuality) }}</span><span>BHP上限 {{ fmt(period.bhpUpperLimit) }} {{ pressureUnit }}</span></div>
      <div ref="targetHost" class="target-chart" role="img" aria-label="多阶段注汽目标时间轴"/>
      <template v-if="scenario">
        <el-alert v-if="response" :type="run.resultContract === 'VALID_FULL' && response.verified ? 'success' : 'warning'" :closable="false" :title="run.resultContract === 'VALID_FULL' && response.verified ? '阶段前结果与基线一致，完整计算及分阶段注入约束已核对' : '阶段前结果、完整终点或实际约束核对未通过，请检查诊断'"/>
        <el-alert v-else type="warning" :closable="false" title="等待完整同源基线；缺失或不匹配时不展示实际响应对比。"/>
        <template v-if="response">
          <div class="result-kpis"><article v-for="m in response.metrics.filter(m => ['WWIT', 'WBHP', 'WTEMP', 'FHLT'].includes(m.keyword))" :key="m.keyword"><small>{{ m.label }}</small><b>{{ fmt(m.finalValue) }}</b><span>{{ m.unit }} · 第 {{ fmt(m.finalTime) }} 天</span></article></div>
          <div class="response-charts"><div ref="flowHost" role="img" aria-label="多阶段实际注入和井压"/><div ref="thermalHost" role="img" aria-label="多阶段热损失与井温"/></div>
          <p v-if="response.pressureLimited">出现压力限注：实际注入低于设定目标。累计量采用原生 WWIT。</p><p v-if="!response.temperatureAvailable">原模型未请求实际井温；此处保留已计算的热损失，不补造温度曲线。</p>
        </template>
      </template>
    </template>
    <p class="source-note">报告边界值属于刚完成的阶段，调整作用于其后时段。地面注入为冷水当量；输入温度和干度用于定义焓，实际井温取原生 WTEMP。原报告间隔与终点保留，生成的完整模型包可下载后直接复算。</p>
  </section>
</template>

<style scoped>
.thermal-plan{margin:22px 0;padding:24px;border:1px solid #dce7ec;border-radius:14px;background:linear-gradient(125deg,#f0f8fb,#fff 65%);color:#4b697d}.thermal-plan header{display:flex;justify-content:space-between;gap:16px;flex-wrap:wrap}.thermal-plan header>div>span{color:#2985a8;font-size:11px;letter-spacing:.12em;font-weight:700}.thermal-plan h3{font-size:22px;color:#244b62;margin:9px 0}.thermal-plan p{font-size:12px;line-height:1.8}.actions,.original,.facts,.period-detail{display:flex;align-items:center;flex-wrap:wrap;gap:16px}.actions{margin:12px 0}.original{padding:14px;background:#e9f2f7;border-radius:8px;font-size:12px;margin-bottom:16px}.stage-editor{display:grid;grid-template-columns:36px repeat(5,minmax(90px,1fr)) 56px;align-items:center;gap:12px;padding:16px 12px 0;border:1px solid #dce8ee;border-radius:10px;margin-bottom:10px;background:#fff}.stage-number{color:#318aad;font-size:23px}.stage-editor :deep(.el-input-number),.stage-editor :deep(.el-select){width:100%}.stage-editor :deep(.el-form-item__label){font-size:11px;white-space:nowrap}.facts{padding:22px 0;font-size:12px;gap:28px}.facts b{font-size:22px;color:#247ca5;margin-right:5px}.stage-track{display:flex;gap:8px;overflow:auto;padding:3px}.stage-track button{flex-basis:0;min-width:130px;border:1px solid #d8e6ed;border-top:4px solid;border-radius:8px;padding:14px 12px;text-align:left;background:#fff;color:#547285;cursor:pointer}.stage-track button.selected{background:#e7f4fb;outline:2px solid #56a0c6;outline-offset:-2px}.stage-track b,.stage-track small,.stage-track strong{display:block;margin-bottom:7px}.stage-track strong{color:#2a789e;font-size:20px;margin:15px 0 0}.stage-track strong small{font-size:10px;font-weight:400;display:inline}.stage-track small{font-size:11px}.period-detail{font-size:12px;background:#eaf2f7;padding:13px;border-radius:7px;margin:16px 0}.target-chart{height:290px;background:#fff;border:1px solid #e1eaf0;border-radius:9px;margin:16px 0}.response-charts{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}.response-charts>div{height:330px;background:#fff;border:1px solid #e1eaf0;border-radius:9px;min-width:0}.result-kpis{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:12px;margin:18px 0}.result-kpis article{padding:18px;border:1px solid #dce8ee;background:#fff;border-radius:9px}.result-kpis small,.result-kpis b,.result-kpis span{display:block}.result-kpis small{font-size:12px}.result-kpis b{font-size:26px;color:#267d9e;margin:12px 0}.result-kpis span{font-size:10px;color:#7c929f}.source-note{color:#8195a3}.thermal-plan :deep(.el-alert){margin-top:14px}@media(max-width:1150px){.stage-editor{grid-template-columns:32px repeat(3,minmax(100px,1fr))}.response-charts{grid-template-columns:1fr}}@media(max-width:600px){.thermal-plan{padding:14px}.stage-editor{grid-template-columns:repeat(2,minmax(0,1fr))}.stage-number{grid-column:1/-1}.stage-editor :deep(.el-form-item__label){white-space:normal}.facts{gap:14px}.thermal-plan h3{font-size:19px}.stage-track button{min-width:122px}.period-detail{gap:10px}}
</style>
