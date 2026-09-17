<script setup>
import { computed, ref, watch } from 'vue'
import { branchOverview, branchOverviewCsvRows } from './networkBranchOverview'
import { downloadWellCsv } from './wellResultPresentation'

const props = defineProps({ profiles: { type: Array, default: () => [] }, runId: { type: [Number, String], default: null }, study: { type: String, default: '' } })
const emit = defineEmits(['select'])
const search = ref('')
const missingOnly = ref(false)
watch(() => [props.runId, props.profiles], () => { search.value = ''; missingOnly.value = false })
const rows = computed(() => branchOverview(props.profiles).filter(row => row.branch.toLowerCase().includes(search.value.trim().toLowerCase()) && (!missingOnly.value || row.missing || row.unavailable)))
const display = value => value === null ? '—' : typeof value === 'number' && Number.isFinite(value) ? Number(value.toFixed(6)) : value
const exportRows = () => downloadWellCsv(branchOverviewCsvRows(rows.value, props.runId, props.study), `network-${props.runId ?? 'result'}-branch-overview.csv`)
</script>

<template>
  <section class="branch-overview" aria-label="管网支路工况总览">
    <header><h3>支路工况总览</h3><span>运行 #{{ runId }} · {{ rows.length }} 条支路</span><el-button size="small" :disabled="!rows.length" @click="exportRows">导出工况 CSV</el-button></header>
    <div class="filters"><el-input v-model="search" clearable placeholder="搜索支路" aria-label="搜索工况支路" /><el-checkbox v-model="missingOnly">仅看缺失数据</el-checkbox></div>
    <p>首末点按模拟器返回顺序，不代表实际流向。压差为首点减末点；单位未知或端点缺失时不计算。范围仅统计有效压力点，不填补缺失值。</p>
    <el-table :data="rows" border size="small" max-height="300" empty-text="没有符合条件的支路工况">
      <el-table-column prop="branch" label="支路" min-width="140" fixed />
      <el-table-column label="单位" width="90"><template #default="{ row }">{{ row.unit || '未提供' }}</template></el-table-column>
      <el-table-column prop="pointCount" label="点数" width="70" />
      <el-table-column v-for="column in [{ key: 'first', label: '首点压力' }, { key: 'last', label: '末点压力' }, { key: 'difference', label: '首末点压差' }, { key: 'minimum', label: '最低压力' }, { key: 'maximum', label: '最高压力' }]" :key="column.key" :label="column.label" min-width="130"><template #default="{ row }">{{ display(row[column.key]) }}</template></el-table-column>
      <el-table-column label="缺失点" width="90"><template #default="{ row }">{{ row.unavailable ? '无压力数据' : row.missing }}</template></el-table-column>
      <el-table-column label="操作" width="95" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="emit('select', row.branch)">查看剖面</el-button></template></el-table-column>
    </el-table>
  </section>
</template>

<style scoped>
.branch-overview { margin-bottom: 16px; border: 1px solid #deded9; padding: 12px; }
header, .filters { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
h3 { margin: 0; font-size: 15px; } header span, p { color: #73777d; font-size: 12px; } header .el-button { margin-left: auto; }
.filters { margin-top: 10px; } .filters .el-input { width: 240px; } p { line-height: 1.6; margin: 8px 0; }
</style>
