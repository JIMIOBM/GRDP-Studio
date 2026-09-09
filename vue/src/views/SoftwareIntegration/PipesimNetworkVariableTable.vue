<script setup>
import { computed, ref, watch } from 'vue'

const props = defineProps({
  title: { type: String, required: true },
  entries: { type: Array, default: () => [] },
  emptyText: { type: String, default: '当前运行没有结果数据' }
})

const selectedVariable = ref('')
const valueFilter = ref('')

const variables = computed(() => props.entries.filter(entry =>
  typeof entry?.variable === 'string' && Array.isArray(entry.values)))
const selectedEntry = computed(() => variables.value.find(entry => entry.variable === selectedVariable.value) || null)
const rows = computed(() => {
  const keyword = valueFilter.value.trim().toLowerCase()
  const values = selectedEntry.value?.values || []
  return values
    .map((item, index) => ({ ...item, _index: index }))
    .filter(item => !keyword || String(item.name ?? '').toLowerCase().includes(keyword))
})

const withUnit = entry => entry?.unit ? `${entry.variable} (${entry.unit})` : entry?.variable || ''
const displayValue = value => {
  if (value === null || value === undefined) return '-'
  if (typeof value === 'object') return JSON.stringify(value)
  return value
}

watch(variables, entries => {
  if (!entries.some(entry => entry.variable === selectedVariable.value)) {
    const preferred = ['Pressure', 'SystemOutletPressure', 'SystemInletPressure', 'Temperature']
      .map(name => entries.find(entry => entry.variable === name))
      .find(Boolean)
    selectedVariable.value = preferred?.variable || entries[0]?.variable || ''
  }
}, { immediate: true })
</script>

<template>
  <section class="variable-results">
    <div class="variable-toolbar">
      <div>
        <strong>{{ title }}</strong>
        <span>{{ variables.length }} 个变量</span>
      </div>
      <el-select
        v-model="selectedVariable"
        class="variable-select"
        filterable
        placeholder="选择变量"
        :disabled="!variables.length"
      >
        <el-option v-for="entry in variables" :key="entry.variable" :label="withUnit(entry)" :value="entry.variable" />
      </el-select>
      <el-input v-model="valueFilter" class="value-filter" clearable placeholder="筛选对象名称" />
    </div>

    <el-table v-if="selectedEntry" :data="rows" border size="small" max-height="420">
      <el-table-column type="index" label="#" width="54" align="center" />
      <el-table-column label="对象" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">{{ row.name ?? '-' }}</template>
      </el-table-column>
      <el-table-column :label="selectedEntry.unit ? `数值 (${selectedEntry.unit})` : '数值'" min-width="180">
        <template #default="{ row }"><span :class="{ missing: row.value === null || row.value === undefined }">{{ displayValue(row.value) }}</span></template>
      </el-table-column>
    </el-table>
    <el-empty v-else :description="emptyText" :image-size="72" />
  </section>
</template>

<style lang="scss" scoped>
.variable-results { min-height: 250px; }
.variable-toolbar { display: grid; grid-template-columns: minmax(180px, 1fr) minmax(220px, 340px) minmax(180px, 260px); align-items: end; gap: 12px; margin-bottom: 14px; }
.variable-toolbar > div:first-child { min-width: 0; }
.variable-toolbar strong { display: block; font-size: 14px; }
.variable-toolbar span { display: block; margin-top: 4px; color: #909399; font-size: 12px; }
.variable-select, .value-filter { width: 100%; }
.missing { color: #a8abb2; }
@media (max-width: 760px) {
  .variable-toolbar { grid-template-columns: 1fr; align-items: stretch; }
}
</style>
