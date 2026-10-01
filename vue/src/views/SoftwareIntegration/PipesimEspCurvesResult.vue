<script setup>
import { computed, ref, watch } from 'vue'

const props = defineProps({ result: { type: Object, default: null } })
const source = ref('pump')
const selectedFrequency = ref(null)
const pump = computed(() => props.result?.[source.value] || null)
const frequencies = computed(() => pump.value?.frequencies || [])
const currentCurve = computed(() => frequencies.value.find(item => item.frequencyHz === selectedFrequency.value) || frequencies.value[0] || null)
const envelope = computed(() => pump.value?.operatingEnvelope || null)
const chartPoints = computed(() => {
  const curve = currentCurve.value
  if (!curve || !curve.flowRate?.length) return ''
  const xs = curve.flowRate
  const ys = curve.head
  const minX = Math.min(...xs)
  const maxX = Math.max(...xs)
  const minY = Math.min(...ys)
  const maxY = Math.max(...ys)
  const dx = maxX - minX || 1
  const dy = maxY - minY || 1
  return xs.map((value, index) => `${40 + ((value - minX) / dx) * 520},${260 - ((ys[index] - minY) / dy) * 220}`).join(' ')
})
const format = value => Number.isFinite(value) ? Number(value).toFixed(3) : '-'
const exportCsv = () => {
  if (!props.result) return
  const rows = [['source', 'pump', 'frequencyHz', 'flowRate', 'flowRateUnit', 'head', 'headUnit']]
  for (const [name, item] of [['PT Profile', props.result.pump], ['Nodal', props.result.nodalPump]]) {
    for (const curve of item?.frequencies || []) {
      curve.flowRate.forEach((flowRate, index) => rows.push([name, item.pumpName, curve.frequencyHz, flowRate, curve.flowRateUnit, curve.head[index], curve.headUnit]))
    }
  }
  const blob = new Blob([rows.map(row => row.map(value => JSON.stringify(value ?? '')).join(',')).join('\n')], { type: 'text/csv;charset=utf-8' })
  const href = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = href
  anchor.download = 'pipesim-esp-curves.csv'
  anchor.click()
  setTimeout(() => URL.revokeObjectURL(href), 0)
}
watch(() => props.result, value => {
  selectedFrequency.value = value?.pump?.frequencies?.[0]?.frequencyHz ?? null
  source.value = 'pump'
}, { immediate: true })
</script>

<template>
  <section class="esp-curves-result" aria-label="PIPESIM ESP 曲线结果">
    <div v-if="!result" class="empty">当前运行未产生可读取的 ESP 曲线结果。</div>
    <template v-else>
      <header class="result-header">
        <div><span class="kicker">PIPESIM ESP CURVES</span><h2>{{ result.producer }} · {{ pump?.pumpName }}</h2><p>官方 PT Profile 与 Nodal 两次任务的只读结果；不写回泵、电机或电缆参数。</p></div>
        <el-button type="primary" plain @click="exportCsv">导出原始点 CSV</el-button>
      </header>
      <div class="summary-grid">
        <div><span>制造商</span><strong>{{ pump?.inputs.manufacturer }}</strong></div>
        <div><span>泵型号</span><strong>{{ pump?.inputs.model }}</strong></div>
        <div><span>频率</span><strong>{{ format(pump?.inputs.frequency) }} {{ pump?.inputs.frequencyUnit }}</strong></div>
        <div><span>级数</span><strong>{{ pump?.inputs.stages }}</strong></div>
        <div><span>Qmin</span><strong>{{ format(pump?.inputs.minFlowRate) }} {{ pump?.operatingEnvelope.qMin.flowRateUnit }}</strong></div>
        <div><span>Qmax</span><strong>{{ format(pump?.inputs.maxFlowRate) }} {{ pump?.operatingEnvelope.qMax.flowRateUnit }}</strong></div>
      </div>
      <div class="toolbar">
        <el-radio-group v-model="source" size="small" aria-label="ESP 曲线来源">
          <el-radio-button label="pump">PT Profile</el-radio-button>
          <el-radio-button label="nodal">Nodal</el-radio-button>
        </el-radio-group>
        <el-select v-model="selectedFrequency" size="small" aria-label="ESP 曲线频率" placeholder="选择频率">
          <el-option v-for="item in frequencies" :key="item.frequencyHz" :value="item.frequencyHz" :label="`${item.frequencyLabel} · ${item.flowRateUnit}`" />
        </el-select>
      </div>
      <div class="chart-card">
        <div class="chart-title">{{ currentCurve?.frequencyLabel }} 流量—扬程原始点</div>
        <svg viewBox="0 0 600 300" role="img" aria-label="ESP 流量扬程曲线">
          <line x1="40" y1="260" x2="560" y2="260" class="axis" /><line x1="40" y1="40" x2="40" y2="260" class="axis" />
          <polyline v-if="chartPoints" :points="chartPoints" fill="none" class="curve" />
        </svg>
        <div class="axis-label">流量 {{ currentCurve?.flowRateUnit || '' }} · 扬程 {{ currentCurve?.headUnit || '' }}</div>
      </div>
      <div class="envelope-grid">
        <div v-for="(curve, name) in envelope" :key="name" class="envelope-card">
          <strong>{{ name === 'qMin' ? 'Qmin' : name === 'qMax' ? 'Qmax' : 'BEP' }}</strong>
          <span>{{ curve.flowRate.length }} 个原始点 · {{ curve.flowRateUnit }} / {{ curve.headUnit }}</span>
          <small>首点 {{ format(curve.flowRate[0]) }} / {{ format(curve.head[0]) }}，末点 {{ format(curve.flowRate[curve.flowRate.length - 1]) }} / {{ format(curve.head[curve.head.length - 1]) }}</small>
        </div>
      </div>
      <el-table :data="currentCurve?.flowRate.map((flowRate, index) => ({ index: index + 1, flowRate, head: currentCurve.head[index] })) || []" size="small" max-height="280">
        <el-table-column prop="index" label="#" width="70" />
        <el-table-column prop="flowRate" :label="`Flowrate (${currentCurve?.flowRateUnit || '-'})`" />
        <el-table-column prop="head" :label="`Head (${currentCurve?.headUnit || '-'})`" />
      </el-table>
    </template>
  </section>
</template>

<style lang="scss" scoped>
.esp-curves-result { min-width: 0; }
.empty { padding: 32px; color: #7a7f87; text-align: center; }
.result-header { display: flex; justify-content: space-between; gap: 20px; align-items: flex-start; margin-bottom: 16px; }
.kicker { color: #2b78c5; font-size: 12px; letter-spacing: .08em; }
h2 { margin: 4px 0; }
.result-header p { color: #7a7f87; margin: 4px 0; }
.summary-grid, .envelope-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; margin-bottom: 16px; }
.summary-grid div, .envelope-card { padding: 12px; background: #f4f6f8; border: 1px solid #e4e7eb; }
.summary-grid span, .envelope-card span, .envelope-card small { display: block; color: #7a7f87; font-size: 12px; }
.summary-grid strong { display: block; margin-top: 5px; }
.toolbar { display: flex; justify-content: space-between; gap: 12px; margin: 12px 0; }
.toolbar .el-select { width: 260px; }
.chart-card { padding: 12px; border: 1px solid #e4e7eb; background: #fff; }
.chart-title { font-weight: 600; }
svg { display: block; width: 100%; height: 300px; }
.axis { stroke: #b8c0ca; stroke-width: 1; }
.curve { stroke: #2f7ed8; stroke-width: 3; stroke-linejoin: round; stroke-linecap: round; }
.axis-label { text-align: center; color: #7a7f87; font-size: 12px; }
@media (max-width: 900px) { .summary-grid, .envelope-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } .result-header { flex-direction: column; } }
</style>
