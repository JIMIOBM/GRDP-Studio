<script setup>
defineProps({ title: String, columns: { type: Array, default: () => [] }, rows: { type: Array, default: () => [] } })
function valueText(value) {
  if (value == null || (typeof value === 'number' && !Number.isFinite(value))) return '—'
  return typeof value === 'number' ? Number(value.toPrecision(12)).toString() : String(value)
}
</script>
<template>
  <section class="reference-section">
    <h3>{{ title }}<span>只读</span></h3>
    <table :aria-label="title"><thead><tr><th v-for="column in columns" :key="column.key">{{ column.label }}<small v-if="column.unit">{{ column.unit }}</small></th></tr></thead>
      <tbody><tr v-for="(row,index) in rows" :key="index"><td v-for="column in columns" :key="column.key" :class="{numeric:typeof row[column.key]==='number'}">{{ valueText(row[column.key]) }}</td></tr></tbody>
    </table>
  </section>
</template>
<style scoped>
.reference-section{margin-top:16px}h3{display:flex;align-items:center;gap:16px;min-height:32px;margin:0;padding:0 12px;border-top:1px solid #ddd;border-bottom:1px solid #ddd;background:#fafafa;font-size:12px;font-weight:400}h3 span{color:#777}table{width:100%;min-width:680px;border-collapse:separate;border-spacing:0;font-size:13px}th,td{padding:7px 10px;border-right:1px solid #d4d7db;border-bottom:1px solid #d4d7db;text-align:center;line-height:1.6}th{background:#f4f4f4;font-weight:400}th small{display:block;font-size:12px}td{background:#fafafa;color:#555;overflow-wrap:anywhere}.numeric{text-align:right;font-variant-numeric:tabular-nums}
</style>
