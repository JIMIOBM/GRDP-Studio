<script setup>
import { computed, ref } from 'vue'

const props = defineProps({ result: { type: Object, default: null } })
const source = ref('vlp')
const baseline = computed(() => props.result?.baseline || null)
const target = computed(() => props.result?.target || null)
const parameterField = computed(() => props.result?.schemaVersion === 'pipesim-esp-parameter-result/1' ? 'frequencyHz' : 'parameterValue')
const valueFor = item => item?.[parameterField.value]
const parameterUnit = computed(() => props.result?.unit === 'Hz' ? 'Hz' : '无量纲（1）')
const parameterTitle = computed(() => props.result?.parameter === 'headFactor' ? 'ESP 扬程系数 Head Factor' : 'ESP 运行频率')
const points = computed(() => {
  const field = source.value
  return { baseline: baseline.value?.[field] || [], target: target.value?.[field] || [] }
})
const chartPoints = computed(() => {
  const values = [...points.value.baseline, ...points.value.target]
  if (!values.length) return { baseline: '', target: '' }
  const xs = values.map(item => item.flow)
  const ys = values.map(item => item.pressure)
  const minX = Math.min(...xs)
  const maxX = Math.max(...xs)
  const minY = Math.min(...ys)
  const maxY = Math.max(...ys)
  const dx = maxX - minX || 1
  const dy = maxY - minY || 1
  const project = valuesForCase => valuesForCase.map(item => `${40 + ((item.flow - minX) / dx) * 520},${260 - ((item.pressure - minY) / dy) * 220}`).join(' ')
  return { baseline: project(points.value.baseline), target: project(points.value.target) }
})
const delta = computed(() => props.result?.comparison || {})
const format = value => typeof value === 'number' && Number.isFinite(value) ? value.toFixed(3) : '-'
const exportCsv = () => {
  if (!props.result) return
  const rows = [['case', 'parameter', 'value', 'unit', 'curve', 'index', 'flow', 'pressure']]
  for (const [name, item] of [['baseline', baseline.value], ['target', target.value]]) {
    for (const curve of ['ipr', 'vlp']) {
      ;(item?.[curve] || []).forEach((point, index) => rows.push([name, props.result.parameter, valueFor(item), props.result.unit, curve, index + 1, point.flow, point.pressure]))
    }
  }
  const csv = rows.map(row => row.map(value => JSON.stringify(value ?? '')).join(',')).join('\n')
  const href = URL.createObjectURL(new Blob([`\ufeff${csv}`], { type: 'text/csv;charset=utf-8' }))
  const anchor = document.createElement('a')
  anchor.href = href
  anchor.download = 'pipesim-esp-parameter.csv'
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  setTimeout(() => URL.revokeObjectURL(href), 0)
}
</script>

<template>
  <section class="esp-parameter-result" aria-label="PIPESIM ESP 单参数方案结果">
    <el-empty v-if="!result" description="尚未形成有效 ESP 参数方案结果" :image-size="72" />
    <template v-else>
      <header class="result-header">
        <div><span class="kicker">PIPESIM ESP PARAMETER</span><h2>{{ result.producer }} · {{ result.espContext }}</h2><p>官方 {{ parameterTitle }} 读写回读；原值基线与目标值均来自隔离副本的真实 PT Profile + Nodal 计算。</p></div>
        <el-button type="primary" plain @click="exportCsv">导出对比 CSV</el-button>
      </header>
      <div class="summary-grid">
        <div><span>参数</span><strong>{{ parameterTitle }}</strong></div>
        <div><span>原值</span><strong>{{ format(result.originalValue) }} {{ parameterUnit }}</strong></div>
        <div><span>目标值</span><strong>{{ format(result.targetValue) }} {{ parameterUnit }}</strong></div>
        <div><span>读回值</span><strong>{{ format(result.appliedValue) }} {{ parameterUnit }}</strong></div>
        <div><span>隔离副本</span><strong>{{ result.isolatedCopy && result.sourceModelUnchanged ? '是 · 原模型未改' : '校验失败' }}</strong></div>
      </div>
      <div class="delta-grid">
        <div><span>IPR 最大压力变化</span><strong>{{ format(delta.maxIprPressureDelta) }}</strong></div>
        <div><span>VLP 最大压力变化</span><strong>{{ format(delta.maxVlpPressureDelta) }}</strong></div>
        <div><span>剖面最大压力变化</span><strong>{{ format(delta.maxProfilePressureDelta) }}</strong></div>
        <div><span>剖面最大温度变化</span><strong>{{ format(delta.maxProfileTemperatureDelta) }}</strong></div>
      </div>
      <p class="comparison-note">差值在基线与目标结果共有的流量或深度范围内线性插值计算；显示“—”表示两组数据没有可比较的重叠范围。</p>
      <div class="toolbar">
        <span>基线 {{ format(valueFor(baseline)) }} {{ parameterUnit }} → 目标 {{ format(valueFor(target)) }} {{ parameterUnit }}</span>
        <el-radio-group v-model="source" size="small" aria-label="ESP 参数方案曲线">
          <el-radio-button label="vlp">VLP 曲线</el-radio-button>
          <el-radio-button label="ipr">IPR 曲线</el-radio-button>
        </el-radio-group>
      </div>
      <div class="chart-card">
        <div class="chart-title">{{ source === 'vlp' ? 'VLP' : 'IPR' }} 流量—压力对比</div>
        <svg viewBox="0 0 600 300" role="img" aria-label="ESP 参数方案基线与目标曲线对比">
          <line x1="40" y1="260" x2="560" y2="260" class="axis" /><line x1="40" y1="40" x2="40" y2="260" class="axis" />
          <polyline v-if="chartPoints.baseline" :points="chartPoints.baseline" fill="none" class="baseline-curve" />
          <polyline v-if="chartPoints.target" :points="chartPoints.target" fill="none" class="target-curve" />
        </svg>
        <div class="legend"><span class="baseline-dot" />原值基线 <span class="target-dot" />目标值</div>
      </div>
      <el-table :data="target?.vlp?.slice(0, 24) || []" border stripe size="small" aria-label="ESP 目标 VLP 结果">
        <el-table-column type="index" label="#" width="70" />
        <el-table-column prop="flow" label="流量" />
        <el-table-column prop="pressure" label="目标压力" />
      </el-table>
    </template>
  </section>
</template>

<style lang="scss" scoped>
.esp-parameter-result { min-width: 0; }
.result-header { display: flex; justify-content: space-between; gap: 20px; align-items: flex-start; margin-bottom: 16px; }
.kicker { color: #2b78c5; font-size: 12px; letter-spacing: .08em; }
h2 { margin: 4px 0; }
.result-header p { color: #7a7f87; margin: 4px 0; }
.summary-grid, .delta-grid { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 10px; margin-bottom: 10px; }
.delta-grid { grid-template-columns: repeat(4, minmax(0, 1fr)); margin-bottom: 16px; }
.comparison-note { margin: -6px 0 12px; color: #7a7f87; font-size: 12px; }
.summary-grid div, .delta-grid div { padding: 12px; background: #f4f6f8; border: 1px solid #e4e7eb; }
.summary-grid span, .delta-grid span { display: block; color: #7a7f87; font-size: 12px; }
.summary-grid strong, .delta-grid strong { display: block; margin-top: 5px; }
.toolbar { display: flex; justify-content: space-between; gap: 12px; align-items: center; margin: 12px 0; color: #606770; font-size: 12px; }
.chart-card { padding: 12px; border: 1px solid #e4e7eb; background: #fff; }
.chart-title { font-weight: 600; }
svg { display: block; width: 100%; height: 300px; }
.axis { stroke: #b8c0ca; stroke-width: 1; }
.baseline-curve { stroke: #7d8794; stroke-width: 3; stroke-linejoin: round; stroke-linecap: round; }
.target-curve { stroke: #2563eb; stroke-width: 3; stroke-linejoin: round; stroke-linecap: round; }
.legend { display: flex; align-items: center; justify-content: center; gap: 6px; color: #697586; font-size: 12px; }
.baseline-dot, .target-dot { width: 10px; height: 3px; display: inline-block; margin-left: 12px; background: #7d8794; }
.target-dot { background: #2563eb; }
@media (max-width: 900px) { .summary-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } .delta-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } .result-header { flex-direction: column; } }
</style>
