<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { groupAllocationTree } from './eclipseControlVisuals.js'
const props = defineProps({ inspection: Object, parameters: Object, response: Object, runId: Number })
const time = ref(null), host = ref(null), selected = ref(null)
const timeDays = computed(() => props.response?.rows.some(r => r.timeDays === time.value) ? time.value : props.response?.rows.at(-1)?.timeDays)
const tree = computed(() => groupAllocationTree({ ...props, timeDays: timeDays.value }))
const fmt = v => v == null ? '无同时间值' : v.toLocaleString('zh-CN', { maximumFractionDigits: 3 })
const point = computed(() => props.response?.rows.find(r => r.timeDays === timeDays.value))
const objects = computed(() => tree.value ? [tree.value, ...tree.value.children.flatMap(c => [c, ...c.children])] : [])
watch(() => props.runId, () => { time.value = null; selected.value = null })
watch(timeDays, () => { selected.value = null })
let chart, observer, generation = 0
function display(node) {
  return { ...node, value: node.value, display: `${node.type} ${node.name}\n${fmt(node.value)} ${props.response.flowUnit}${node.type === '子组' ? `\n计入父组 ${fmt(node.details.contribution)}` : ''}`, symbolSize: node.type === '井' ? 13 : 20,
    itemStyle: { color: node.details.atBhpLimit || node.details.atOilLimit ? '#dfa04a' : node.type === '父组' ? '#287fb9' : node.type === '子组' ? '#189d97' : '#77a9cc' }, children: node.children.map(display) }
}
async function render() {
  const token = ++generation; await nextTick(); if (token !== generation) return
  if (!host.value || !tree.value || tree.value.children.some(c => !c)) { observer?.disconnect(); chart?.dispose(); chart = null; return }
  if (!chart || chart.getDom() !== host.value) {
    chart?.dispose(); observer?.disconnect(); chart = echarts.init(host.value)
    observer = new ResizeObserver(() => chart?.resize()); observer.observe(host.value)
    chart.on('click', e => { if (e.data?.details) selected.value = e.data })
  }
  chart.setOption({ animation: false, title: { text: `父组 ${props.parameters.parentGroup} → 子组 → 生产井`, subtext: `实际第 ${timeDays.value} 天 · 方案 #${props.runId} / 基线 #${props.parameters.baselineRunId} · ${props.response.flowUnit}`, left: 14, top: 12, textStyle: { color: '#295570', fontSize: 13 }, subtextStyle: { fontSize: 10 } }, tooltip: { trigger: 'item', renderMode: 'richText', formatter: e => `${e.data.type} ${e.data.name}\n方案实际量 ${fmt(e.data.value)} ${props.response.flowUnit}\n基线实际量 ${fmt(e.data.baseline)} ${props.response.flowUnit}${e.data.type === '子组' ? `\n开工比例 ${fmt(e.data.details.efficiency)}\n计入父组 ${fmt(e.data.details.contribution)} ${props.response.flowUnit}` : ''}` }, series: [{ type: 'tree', data: [display(tree.value)], top: 90, left: 100, right: 180, bottom: 36, orient: 'LR', initialTreeDepth: 2, expandAndCollapse: true, roam: true, edgeShape: 'polyline', edgeForkPosition: '50%', lineStyle: { color: '#b8cddd', width: 1.5 }, label: { position: 'top', distance: 10, formatter: e => e.data.display, color: '#406882', fontSize: 11, lineHeight: 19 }, leaves: { label: { position: 'right', distance: 10 } }, emphasis: { focus: 'descendant' } }] }, true)
  chart.resize()
}
watch([tree, timeDays], render, { immediate: true })
onBeforeUnmount(() => { generation++; observer?.disconnect(); chart?.dispose() })
function exportPng() { if (!chart) return; const a = document.createElement('a'); a.href = chart.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: '#fff' }); a.download = `eclipse-group-${props.runId}-day${timeDays.value}.png`; a.click() }
</script>
<template>
  <section v-if="tree" class="group-allocation" aria-label="ECLIPSE井组分配可视化" :data-run-id="runId" :data-time-days="timeDays">
    <header><div><span>GROUP ALLOCATION</span><h3>井组结构与真实产量分配</h3><p>{{ parameters.keyword }} · {{ parameters.objectName }}：{{ parameters.originalValue }} → {{ parameters.targetValue }} {{ parameters.unit }}</p></div><el-button size="small" @click="exportPng">导出井组结构PNG</el-button></header>
    <div class="allocation-cards"><div><small>父组实际量</small><strong>{{ fmt(point.rate) }}</strong><span>{{ response.flowUnit }} · 基线 {{ fmt(point.baselineRate) }}</span></div><div><small>父组目标</small><strong>{{ fmt(point.target) }}</strong><span>{{ response.flowUnit }}</span></div><div><small>子组折算贡献合计</small><strong>{{ fmt(point.contribution) }}</strong><span>{{ response.flowUnit }}</span></div><div><small>父组累计产油</small><strong>{{ fmt(point.total) }}</strong><span>{{ response.volumeUnit }} · 已计入开工比例</span></div></div>
    <el-select v-model="time" aria-label="井组分配实际报告" :placeholder="`第 ${timeDays} 天`"><el-option v-for="r in response.rows" :key="r.timeDays" :value="r.timeDays" :label="`实际第 ${r.timeDays} 天`"/></el-select>
    <el-select :model-value="selected?.name" aria-label="井组分配对象" placeholder="选择节点查看约束" @change="name => { selected = objects.find(n => n.name === name) }"><el-option v-for="node in objects" :key="node.name" :value="node.name" :label="`${node.type} ${node.name}`"/></el-select>
    <div class="group-scroll"><div ref="host" class="group-tree" :style="{ height: `${Math.max(380, Math.min(900, response.members.filter(m => m.timeDays === timeDays).length * 44))}px` }" role="img" aria-label="父组子组与生产井实际分配结构图"/></div>
    <div v-if="selected" class="node-detail" aria-label="选中井组对象"><b>{{ selected.type }} {{ selected.name }}</b> · 实际 {{ fmt(selected.value) }} {{ response.flowUnit }} · 基线 {{ fmt(selected.baseline) }}<template v-if="selected.type === '井'"> · BHP {{ fmt(selected.details.bhp) }} / 下限 {{ fmt(selected.details.bhpLimit) }} {{ response.pressureUnit }} · 产油上限 {{ fmt(selected.details.oilRateLimit) }} {{ response.flowUnit }}<template v-if="selected.details.guideTarget != null"> · 固定导向 {{ fmt(selected.details.guideRate) }} · 理论分配 {{ fmt(selected.details.guideTarget) }} {{ response.flowUnit }}</template><template v-else> · 当前未启用固定导向分配</template></template><template v-if="selected.type === '子组'"> · 开工比例 {{ fmt(selected.details.efficiency) }} · 折算贡献 {{ fmt(selected.details.contribution) }} {{ response.flowUnit }}</template></div>
    <p class="allocation-note">点选节点查看约束或展开／折叠，滚轮缩放。图示为模型已有井组归属；子组与井显示开工时流量，父组贡献按开工比例折算。累计量已包含开工比例，直接相加；固定导向的理论分配不能替代实际计算量。详细数值保留在下方表格。</p>
  </section>
</template>
<style scoped>
.group-allocation { margin: 18px 0; padding: 22px; border: 1px solid #dce8ed; border-radius: 12px; background: linear-gradient(130deg,#eef9f8,#fff 60%); color: #53758a; }.group-allocation header { display: flex; justify-content: space-between; align-items: start; flex-wrap: wrap; gap: 12px; }.group-allocation header span { color: #31918e; font-size: 10px; letter-spacing: .12em; font-weight: 700; }.group-allocation h3 { color: #285669; font-size: 19px; margin: 8px 0; }.group-allocation p { font-size: 11px; line-height: 1.8; }.allocation-cards { display: grid; grid-template-columns: repeat(4,minmax(0,1fr)); gap: 12px; margin: 20px 0; }.allocation-cards>div { padding: 16px; background: #fff; border: 1px solid #dce9ee; border-radius: 9px; min-width: 0; }.allocation-cards small,.allocation-cards span { display: block; color: #7b97a7; font-size: 10px; }.allocation-cards strong { color: #2d6b82; font-size: 23px; display: block; margin: 12px 0; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }.group-allocation .el-select { width: min(300px,100%); }.group-scroll { overflow-x: auto; margin-top: 14px; border: 1px solid #e0ebf0; border-radius: 9px; background: #fff; }.group-tree { min-width: 720px; }.node-detail { margin-top: 12px; background: #e8f4f2; border-radius: 6px; padding: 12px; font-size: 12px; line-height: 1.8; }.allocation-note { color: #849aaa; }@media(max-width:1100px) { .allocation-cards { grid-template-columns: repeat(2,minmax(0,1fr)); } }@media(max-width:550px) { .group-allocation { padding: 12px; }.allocation-cards strong { font-size: 19px; } }
</style>
