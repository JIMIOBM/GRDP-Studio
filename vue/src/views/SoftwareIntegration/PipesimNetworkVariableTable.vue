<script setup>
import { computed, ref, watch } from 'vue'
import { networkCsv } from './networkResultInteraction'

const props = defineProps({
  title: { type: String, required: true },
  entries: { type: Array, default: () => [] },
  comparisonEntries: { type: Array, default: () => [] },
  comparisonLabel: { type: String, default: '' },
  emptyText: { type: String, default: '当前运行没有结果数据' }
})

const selectedVariable = ref('')
const valueFilter = ref('')
const currentPage = ref(1)
const pageSize = ref(20)

const variables = computed(() => props.entries.filter(entry =>
  typeof entry?.variable === 'string' && Array.isArray(entry.values)))
const selectedEntry = computed(() => variables.value.find(entry => entry.variable === selectedVariable.value) || null)
const comparisonEntry = computed(() => props.comparisonEntries.find(entry => entry?.variable === selectedVariable.value) || null)
const comparisonIssue = computed(() => {
  if (!props.comparisonEntries.length || !selectedEntry.value) return ''
  if (!comparisonEntry.value) return `历史结果没有同名变量 ${selectedVariable.value}，无法对齐。`
  if ((selectedEntry.value.unit || '') !== (comparisonEntry.value.unit || '')) return '历史结果单位不同，无法直接比较。'
  return ''
})
const rows = computed(() => {
  const keyword = valueFilter.value.trim().toLowerCase()
  const currentValues = selectedEntry.value?.values || []
  const historicalValues = comparisonEntry.value?.values || []
  const names = [...new Set([...currentValues.map(item => item?.name), ...historicalValues.map(item => item?.name)])]
  const currentByName = new Map(currentValues.map(item => [item?.name, item?.value]))
  const historicalByName = new Map(historicalValues.map(item => [item?.name, item?.value]))
  return names.map((name, index) => ({
    name,
    value: currentByName.get(name),
    comparisonValue: historicalByName.get(name),
    _index: index
  }))
    .filter(item => !keyword || String(item.name ?? '').toLowerCase().includes(keyword))
})
const pageRows = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return rows.value.slice(start, start + pageSize.value)
})

const withUnit = entry => entry?.unit ? `${entry.variable} (${entry.unit})` : entry?.variable || ''
const displayValue = value => {
  if (value === null || value === undefined) return '-'
  if (typeof value === 'object') return JSON.stringify(value)
  return value
}
const comparisonEnabled = computed(() => Boolean(props.comparisonEntries.length && selectedEntry.value))
const displayDelta = row => typeof row.value === 'number' && Number.isFinite(row.value) &&
  typeof row.comparisonValue === 'number' && Number.isFinite(row.comparisonValue)
  ? Number((row.value - row.comparisonValue).toFixed(6)) : null
const exportCsv = () => {
  if (!selectedEntry.value) return
  const comparisonColumns = comparisonEnabled.value && !comparisonIssue.value
    ? ['历史值', '差值（当前−历史）']
    : []
  const data = [['变量', '单位', '对象', '当前值', ...comparisonColumns], ...rows.value.map(row => [
    selectedEntry.value.variable,
    selectedEntry.value.unit,
    row.name,
    row.value,
    ...(comparisonColumns.length ? [row.comparisonValue, displayDelta(row)] : [])
  ])]
  const url = URL.createObjectURL(new Blob([networkCsv(data)], { type: 'text/csv;charset=utf-8' }))
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = 'network-variables.csv'
  anchor.click()
  setTimeout(() => URL.revokeObjectURL(url), 0)
}

watch(variables, entries => {
  if (!entries.some(entry => entry.variable === selectedVariable.value)) {
    const preferred = ['Pressure', 'SystemOutletPressure', 'SystemInletPressure', 'Temperature']
      .map(name => entries.find(entry => entry.variable === name))
      .find(Boolean)
    selectedVariable.value = preferred?.variable || entries[0]?.variable || ''
  }
}, { immediate: true })
watch([selectedVariable, valueFilter, pageSize], () => { currentPage.value = 1 })
watch(() => rows.value.length, total => {
  const lastPage = Math.max(1, Math.ceil(total / pageSize.value))
  if (currentPage.value > lastPage) currentPage.value = lastPage
})
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
      <el-button size="small" :disabled="!rows.length" @click="exportCsv">导出当前表格 CSV</el-button>
    </div>
    <p v-if="comparisonEnabled && !comparisonIssue" class="comparison-note">当前值与{{ comparisonLabel || '历史运行' }}按对象名称和单位对齐；差值 = 当前值 − 历史值。</p>
    <el-alert v-if="comparisonIssue" type="warning" :closable="false" :title="comparisonIssue" />

    <el-table v-if="selectedEntry" :data="pageRows" border size="small" max-height="420">
      <el-table-column label="#" width="54" align="center"><template #default="{ $index }">{{ (currentPage - 1) * pageSize + $index + 1 }}</template></el-table-column>
      <el-table-column label="对象" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">{{ row.name ?? '-' }}</template>
      </el-table-column>
      <el-table-column :label="selectedEntry.unit ? `当前值 (${selectedEntry.unit})` : '当前值'" min-width="180">
        <template #default="{ row }"><span :class="{ missing: row.value === null || row.value === undefined }">{{ displayValue(row.value) }}</span></template>
      </el-table-column>
      <el-table-column v-if="comparisonEnabled && !comparisonIssue" :label="`历史值 (${comparisonEntry.unit || ''})`" min-width="180">
        <template #default="{ row }"><span :class="{ missing: row.comparisonValue === null || row.comparisonValue === undefined }">{{ displayValue(row.comparisonValue) }}</span></template>
      </el-table-column>
      <el-table-column v-if="comparisonEnabled && !comparisonIssue" label="差值（当前−历史）" min-width="180">
        <template #default="{ row }"><span :class="{ missing: displayDelta(row) === null }">{{ displayDelta(row) === null ? '-' : displayDelta(row) }}</span></template>
      </el-table-column>
    </el-table>
    <el-pagination
      v-if="selectedEntry && rows.length > pageSize"
      v-model:current-page="currentPage"
      v-model:page-size="pageSize"
      class="network-pagination"
      background
      layout="total, sizes, prev, pager, next"
      :page-sizes="[20, 50, 100]"
      :total="rows.length"
    />
    <el-empty v-if="!selectedEntry" :description="emptyText" :image-size="72" />
  </section>
</template>

<style lang="scss" scoped>
.variable-toolbar { grid-template-columns: minmax(120px, 1fr) minmax(180px, 260px) minmax(140px, 220px) auto !important; } @media (max-width: 1000px) { .variable-toolbar { grid-template-columns: 1fr 1fr !important; } }
.variable-results { min-height: 250px; }
.variable-toolbar { display: grid; grid-template-columns: minmax(180px, 1fr) minmax(220px, 340px) minmax(180px, 260px); align-items: end; gap: 12px; margin-bottom: 14px; }
.variable-toolbar > div:first-child { min-width: 0; }
.variable-toolbar strong { display: block; font-size: 14px; }
.variable-toolbar span { display: block; margin-top: 4px; color: #909399; font-size: 12px; }
.variable-select, .value-filter { width: 100%; }
.missing { color: #a8abb2; }
.network-pagination { justify-content: flex-end; margin-top: 10px; }
@media (max-width: 760px) {
  .variable-toolbar { grid-template-columns: 1fr; align-items: stretch; }
}
</style>
