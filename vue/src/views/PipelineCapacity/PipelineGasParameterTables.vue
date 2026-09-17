<script setup>
import { computed } from 'vue'

const props = defineProps({ snapshot: { type: Object, default: null }, kind: { type: String, default: 'z' }, sourceLabel: { type: String, default: '当前井 PVT 模型' } })
const species = computed(() => props.snapshot?.species || [])
function valueText(value) {
  if (value == null) return '—'
  if (Array.isArray(value)) return value.map(valueText).join('，')
  if (typeof value !== 'number') return String(value)
  if (!Number.isFinite(value)) return '—'
  return Number(value.toPrecision(12)).toString()
}
</script>

<template>
  <div class="gas-parameter-tables">
    <section v-if="species.length" class="parameter-section">
      <div class="section-title">气体组成<span>读取{{ sourceLabel }}，物性常数由计算模型提供；均为只读</span></div>
      <table aria-label="计算采用的气体组成"><thead><tr><th>组分名称</th><th>组分代码</th><th>原始摩尔含量（%）</th><th>计算采用摩尔分数</th><th>来源</th></tr></thead>
        <tbody><tr v-for="row in species" :key="row.code"><td>{{ row.name }}</td><td>{{ row.code }}</td><td class="numeric">{{ valueText(row.moleFraction == null ? null : row.moleFraction * 100) }}</td><td class="numeric">{{ valueText(row.usedMoleFraction) }}</td><td>{{ sourceLabel }}</td></tr></tbody>
      </table>
      <p class="source-note">摩尔分数合计必须为 1；仅允许 ±10⁻⁶ 的数值误差，并在该容差内归一化用于计算，不会补齐缺失组分。</p>
    </section>
    <section v-for="group in snapshot?.groups || []" :key="group.key" class="parameter-section">
      <div class="section-title">{{ group.label }}<span>只读</span></div>
      <table :aria-label="group.label"><thead><tr><th>参数</th><th>数值</th><th>单位</th><th>来源</th></tr></thead>
        <tbody><tr v-for="row in group.rows" :key="row.key"><td>{{ row.label }}</td><td class="numeric">{{ valueText(row.value) }}</td><td>{{ row.unit || '—' }}</td><td>{{ row.source }}</td></tr></tbody>
      </table>
    </section>
    <template v-for="item in species" :key="item.code">
      <section v-for="group in item.groups" :key="group.key" class="parameter-section">
        <div class="section-title">{{ item.name }}（{{ item.code }}）－{{ group.label }}<span>只读</span></div>
        <table :aria-label="item.name + group.label"><thead><tr><th>参数</th><th>数值</th><th>单位</th><th>来源</th></tr></thead>
          <tbody><tr v-for="row in group.rows" :key="row.key"><td>{{ row.label }}</td><td class="numeric">{{ valueText(row.value) }}</td><td>{{ row.unit || '—' }}</td><td>{{ row.source }}</td></tr></tbody>
        </table>
      </section>
    </template>
  </div>
</template>

<style scoped>
.parameter-section { margin-top:16px; }
.source-note { margin:8px 12px;color:#666;font-size:12px;line-height:1.8; }
.section-title { display:flex;align-items:center;gap:16px;min-height:32px;padding:0 12px;border-top:1px solid #ddd;border-bottom:1px solid #ddd;background:#fafafa;font-size:12px;color:#333; }
.section-title span { color:#777;font-size:12px; }
table { width:100%;min-width:680px;border-collapse:separate;border-spacing:0;table-layout:fixed;font-size:13px; }
th,td { padding:7px 10px;min-height:34px;box-sizing:border-box;border-right:1px solid #d4d7db;border-bottom:1px solid #d4d7db;text-align:center;line-height:1.6;overflow-wrap:anywhere; }
th { background:#f4f4f4;color:#333;font-weight:400; }
td { background:#fafafa;color:#555; }
th:first-child { width:28%; }th:last-child { width:28%; }
.numeric { text-align:right;font-variant-numeric:tabular-nums; }
</style>
