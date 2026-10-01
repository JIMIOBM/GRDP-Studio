<script setup>
import { computed } from 'vue'

const props = defineProps({
  result: { type: Object, default: null }
})

const sections = [
  { key: 'wells', label: '井优化结果' },
  { key: 'flowlines', label: '流线结果' },
  { key: 'sinks', label: '汇点结果' }
]
const variables = computed(() => Array.isArray(props.result?.variables) ? props.result.variables : [])
const qualityByPath = computed(() => new Map((props.result?.quality || []).map(item => [item.path, item.code])))
const rows = key => (Array.isArray(props.result?.[key]) ? props.result[key] : []).map(group => {
  const values = Object.fromEntries((group.values || []).map(item => [item.key, item.value]))
  return { _groupKey: key, _valueKeys: new Set((group.values || []).map(item => item.key)), name: group.name, ...values }
})
const displayValue = value => {
  if (value === null || value === undefined) return '—'
  if (typeof value === 'boolean') return value ? '是' : '否'
  return typeof value === 'number' && Number.isFinite(value) ? value.toFixed(3) : '—'
}
const unavailableCount = computed(() => Array.isArray(props.result?.quality) ? props.result.quality.length : 0)
const application = computed(() => props.result?.application || null)
const messageItems = computed(() => [
  ...(props.result?.summary?.errors || []).map(message => ({ type: 'danger', message })),
  ...(props.result?.summary?.warnings || []).map(message => ({ type: 'warning', message })),
  ...(props.result?.summary?.info || []).map(message => ({ type: 'info', message })),
  ...(props.result?.messages || []).map(message => ({ type: 'info', message }))
])
const qualityCode = (row, variable) => qualityByPath.value.get(`${row._groupKey}.${row.name}.${variable.key}`) || ''
const csvEscape = value => `"${String(value ?? '').replaceAll('"', '""')}"`
const downloadCsv = key => {
  const header = ['对象', '变量 key', '变量', '单位', '值', '质量标记']
  const body = rows(key).flatMap(row => variables.value
    .filter(variable => row._valueKeys.has(variable.key))
    .map(variable => [row.name, variable.key, variable.label, variable.unit, displayValue(row[variable.key]), qualityCode(row, variable)]))
  const csv = [header, ...body].map(line => line.map(csvEscape).join(',')).join('\r\n')
  const blob = new Blob([`\uFEFF${csv}`], { type: 'text/csv;charset=utf-8' })
  const href = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = href
  anchor.download = `pipesim-network-optimizer-${key}.csv`
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  URL.revokeObjectURL(href)
}
</script>

<template>
  <section class="network-optimizer-result" aria-label="PIPESIM Network Optimizer 结果">
    <el-empty v-if="!result" description="尚未选择成功的网络优化运行，或结果未通过展示契约校验" :image-size="72" />
    <template v-else>
      <div class="result-head">
        <div>
          <strong>PIPESIM Network Optimizer</strong>
          <span>官方模型内置优化目标与约束 · {{ result.simulationState }}</span>
        </div>
        <el-tag type="success">VALID_FULL</el-tag>
      </div>
      <div class="metric-grid">
        <div class="metric-card"><span>对象统计</span><strong>{{ result.wells.length }} / {{ result.flowlines.length }} / {{ result.sinks.length }}</strong><small>井 / 流线 / 汇点</small></div>
        <div class="metric-card"><span>优化变量</span><strong>{{ variables.length }}</strong><small>官方返回变量</small></div>
        <div class="metric-card"><span>不可用值</span><strong>{{ unavailableCount }}</strong><small>NaN → null · UNAVAILABLE</small></div>
        <div v-if="application" class="metric-card"><span>应用审计</span><strong>已应用</strong><small>隔离副本 · {{ application.changes.length }} 项 GasRate 回读</small></div>
      </div>
      <section v-if="application" class="application-panel" aria-label="优化结果应用审计">
        <div class="panel-title"><h3>优化结果应用审计</h3><el-tag type="success">源模型未修改</el-tag></div>
        <p>官方 <code>apply_results()</code> 已在隔离副本执行。应用后的模型以 Artifact <code>{{ application.artifactName }}</code> 发布，可在“执行详情 → Artifact”下载。</p>
        <el-table v-if="application.changes.length" :data="application.changes" border size="small" max-height="220">
          <el-table-column prop="context" label="上下文" min-width="230" show-overflow-tooltip />
          <el-table-column prop="parameter" label="参数" width="110" />
          <el-table-column prop="unit" label="单位" width="100" />
          <el-table-column label="应用前" width="120"><template #default="{ row }">{{ displayValue(row.before) }}</template></el-table-column>
          <el-table-column label="应用后" width="120"><template #default="{ row }">{{ displayValue(row.after) }}</template></el-table-column>
        </el-table>
        <el-empty v-else description="官方已完成应用，但当前模型没有可回读的 GasRate 控制项" :image-size="42" />
      </section>
      <el-alert v-for="item in messageItems" :key="item.type + item.message" :type="item.type" :title="item.message" :closable="false" class="result-message" />
      <section v-for="section in sections" :key="section.key" class="result-panel">
        <div class="panel-title"><h3>{{ section.label }}</h3><el-button size="small" plain @click="downloadCsv(section.key)">导出 CSV</el-button></div>
        <el-table :data="rows(section.key)" border size="small" max-height="360">
          <el-table-column prop="name" label="对象" fixed min-width="130" />
          <el-table-column v-for="variable in variables" :key="variable.key" :label="variable.unit ? `${variable.label} (${variable.unit})` : variable.label" min-width="135">
            <template #default="{ row }">{{ displayValue(row[variable.key]) }}</template>
          </el-table-column>
        </el-table>
      </section>
    </template>
  </section>
</template>

<style lang="scss" scoped>
.network-optimizer-result { min-width: 0; }
.result-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; padding: 10px 12px; background: #f5f7fa; border: 1px solid #e4e7ed; }
.result-head strong, .result-head span { display: block; }
.result-head strong { color: #2b6cb3; font-size: 15px; }
.result-head span { margin-top: 4px; color: #73777d; font-size: 12px; }
.metric-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 8px; margin: 10px 0; }
.metric-card { padding: 9px 11px; border: 1px solid #e4e7ed; background: #fff; }
.metric-card span, .metric-card small { display: block; color: #909399; font-size: 12px; }
.metric-card strong { display: block; margin: 3px 0; color: #303133; font-size: 18px; }
.result-message { margin-bottom: 6px; }
.application-panel { margin-top: 10px; padding: 10px; border: 1px solid #c6e7c6; background: #f3fbf3; }
.application-panel p { margin: 0 0 8px; color: #5f6b5f; font-size: 12px; line-height: 1.6; }
.application-panel code { color: #276749; }
.result-panel { margin-top: 10px; padding: 10px; border: 1px solid #e4e7ed; }
.panel-title { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 8px; }
.panel-title h3 { margin: 0; color: #303133; font-size: 13px; }
</style>
