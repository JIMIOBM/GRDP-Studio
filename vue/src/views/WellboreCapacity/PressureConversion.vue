<script setup>
import { computed, ref, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import * as echarts from 'echarts'
import request from '@/utils/request'
import { workspaceTreeData } from '@/utils/workspaceTreeState'
const props = defineProps({ node: Object, projectId: [Number, String], gasReservoirId: [Number, String] })
const findTemperature = nodes => {
  for (const node of nodes || []) {
    if (node.type === 'wellbore-temperature' && node.wellName === props.node.wellName) return node.temperatureState
    const found = findTemperature(node.children)
    if (found) return found
  }
}
const temperature = computed(() => findTemperature(workspaceTreeData.value))
const ready = computed(() => temperature.value?.result && temperature.value.lastInput === JSON.stringify(temperature.value.input))
const models = ref(['HB', 'MB'])
const roughness = ref(.016)
const busy = ref(false), error = ref(''), result = ref(null), chartEl = ref(null)
let chart, observer, disposed = false
watch([() => temperature.value?.lastInput, () => ready.value, models, roughness], () => {
  result.value = null
  chart?.clear()
}, { deep: true })
const calculate = async () => {
  if (busy.value) return
  if (!ready.value) { error.value = '请先在温度模型中完成当前参数的计算'; return }
  if (!models.value.length) { error.value = '请选择HB或MB折算方法'; return }
  const state = temperature.value
  busy.value = true; error.value = ''
  try {
    const response = await request.post('/wellbore/pressure/calculate', {
      ...state.input, roughness: roughness.value, models: [...models.value],
      profileDepth: state.result.depth, profileTemperature: state.result.temp,
      projectId: Number(props.projectId), gasReservoirId: Number(props.gasReservoirId), wellName: props.node.wellName
    }, { timeout: 600000, headers: { 'Process-Env': 'prod' } })
    if (disposed) return
    result.value = response.data
    await nextTick()
    chart ||= echarts.init(chartEl.value)
    const r = result.value
    chart.setOption({ animation: false, color: ['#0037b5', '#333'],
      title: { text: '压力分布曲线', left: 'center', top: 12, textStyle: { fontSize: 16, color: '#333' } },
      legend: { top: 42 }, tooltip: { trigger: 'axis', axisPointer: { axis: 'y' } },
      grid: { left: 70, right: 28, top: 80, bottom: 58 },
      xAxis: { type: 'value', name: '压力 (MPa)', scale: true, nameLocation: 'middle', nameGap: 34, splitLine: { lineStyle: { color: '#dfe7f2' } } },
      yAxis: { type: 'value', name: '井深 (m)', inverse: true, min: 0, max: r.depth.at(-1), nameLocation: 'middle', nameGap: 48, splitLine: { lineStyle: { color: '#dfe7f2' } } },
      series: Object.entries(r.pressures).map(([name, values]) => ({ name, type: 'line', showSymbol: false, data: values.map((p, i) => [p, r.depth[i]]) }))
    }, true)
  } catch (e) { error.value = e.response?.data?.msg || e.msg || e.message }
  finally { busy.value = false }
}
onMounted(() => { observer = new ResizeObserver(() => chart?.resize()); observer.observe(chartEl.value) })
onBeforeUnmount(() => { disposed = true; observer?.disconnect(); chart?.dispose() })
</script>
<template>
  <div class="pressure-workspace">
    <aside><header>参数设置</header><div class="body">
      <h4>折算方法</h4><el-checkbox-group v-model="models" :disabled="busy"><el-checkbox value="HB">Hagedorn &amp; Brown</el-checkbox><el-checkbox value="MB">Mukherjee &amp; Brill</el-checkbox></el-checkbox-group>
      <h4>井身结构与物性</h4>
      <template v-for="[key, label] in [['depth','测井深度 (m)'],['idTubing','油管内径 (mm)'],['angle','井斜角 (°)'],['gammaG','气体相对密度']]" :key="key"><label>{{ label }}</label><el-input size="small" readonly :model-value="temperature?.input?.[key] ?? ''" /></template>
      <label>管内壁粗糙度 (mm)</label><el-input-number v-model="roughness" size="small" :min="0" :max="100" :controls="false" :disabled="busy" />
      <h4>生产数据</h4>
      <template v-for="[key, label] in [['fWh','边界油压 (MPa)'],['tWh','边界温度 (℃)'],['qGas','日产气量 (×10⁴ m³/d)'],['qLiq','日产水量 (m³/d)']]" :key="key"><label>{{ label }}</label><el-input size="small" readonly :model-value="temperature?.input?.[key] ?? ''" /></template>
      <p>共用当前井温度模型的生产参数和温度剖面。气、水密度与粘度由PVT按局部压力和温度计算。</p>
      <el-alert v-if="!ready" title="请先完成温度模型计算" :closable="false" type="info" />
      <el-alert v-if="error" :title="error" :closable="false" type="error" />
      <el-button size="small" :loading="busy" :disabled="!ready" @click="calculate">计算压力折算</el-button>
    </div></aside>
    <main v-loading="busy"><header>{{ node.wellName }} · 压力折算 · 折算方法</header>
      <div v-if="result" class="summary"><span v-for="(values, model) in result.pressures" :key="model">{{ model }}：井口 {{ values[0].toFixed(3) }} / 井底 {{ values.at(-1).toFixed(3) }} MPa</span></div>
      <div ref="chartEl" class="chart" />
    </main>
  </div>
</template>
<style scoped>
.pressure-workspace { display:flex; height:100%; min-height:0; background:#fff; color:#333; }
aside { width:260px; flex-shrink:0; border-right:1px solid #e0e0e0; display:flex; flex-direction:column; }
header { padding:7px 12px; font-size:13px; border-bottom:1px solid #e4e7ed; }
.body { padding:4px 12px 14px; overflow:auto; }
h4 { font-size:13px; font-weight:500; margin:10px 0 7px; }
label { display:block; color:#555; font-size:12px; margin:9px 0 3px; }
.el-input-number { width:100%; }.el-button { margin-top:12px; }p { font-size:12px; color:#888; line-height:1.6; }
main { display:flex; flex:1; min-width:0; flex-direction:column; }main header { color:#409eff; background:#fafafa; }
.chart { flex:1; min-height:220px; }.summary { font-size:12px; display:flex; flex-wrap:wrap; gap:16px; padding:8px 12px; }
</style>
