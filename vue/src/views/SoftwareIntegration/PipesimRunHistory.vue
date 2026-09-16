<script setup>
import { computed } from 'vue'

const props = defineProps({
  runs: { type: Array, default: () => [] },
  selectedRunId: { type: [Number, String], default: null },
  loading: { type: Boolean, default: false }
})

const emit = defineEmits(['select'])

const statusMeta = {
  SUCCEEDED: ['成功', 'success'],
  PARTIAL_SUCCEEDED: ['部分成功', 'warning'],
  FAILED: ['失败', 'danger'],
  CANCELLED: ['已取消', 'info'],
  TIMED_OUT: ['已超时', 'danger'],
  WORKER_LOST: ['Worker 失联', 'danger'],
  CANCEL_REQUESTED: ['取消中', 'warning'],
  CREATED: ['已创建', 'info'],
  QUEUED: ['排队中', 'info'],
  CLAIMED: ['已领取', 'primary'],
  PREPARING: ['准备中', 'primary'],
  RUNNING_NODAL: ['节点分析中', 'primary'],
  RUNNING_PROFILE: ['PT 剖面中', 'primary'],
  RUNNING_NETWORK: ['管网模拟中', 'primary'],
  RUNNING_ECLIPSE: ['ECLIPSE 计算中', 'primary'],
  COLLECTING: ['收集结果', 'primary']
}
const runTypeLabel = { nodal: '节点分析', profile: 'PT 剖面', combined: '组合运行', network: '管网模拟', eclipse: 'ECLIPSE 计算' }
const rows = computed(() => props.runs || [])
const formatTime = value => value ? String(value).replace('T', ' ') : '-'
const formatElapsed = value => {
  const seconds = Math.max(0, Math.floor(Number(value || 0) / 1000))
  const minutes = Math.floor(seconds / 60)
  return `${minutes}:${String(seconds % 60).padStart(2, '0')}`
}
</script>

<template>
  <el-table
    v-loading="loading"
    :data="rows"
    row-key="id"
    class="history-table"
    :row-class-name="({ row }) => row.id === selectedRunId ? 'selected-run-row' : ''"
    @row-click="row => emit('select', row.id)"
  >
    <el-table-column prop="id" label="运行 ID" width="100" />
    <el-table-column label="版本" width="82"><template #default="{ row }">v{{ row.versionNo }}</template></el-table-column>
    <el-table-column prop="study" label="Study" min-width="150" show-overflow-tooltip><template #default="{ row }">{{ row.study || '-' }}</template></el-table-column>
    <el-table-column label="类型" width="100"><template #default="{ row }">{{ runTypeLabel[row.runType] || row.runType }}</template></el-table-column>
    <el-table-column v-if="rows.some(row => ['nodal', 'profile', 'combined'].includes(row.runType))" label="参数方案" min-width="190" show-overflow-tooltip>
      <template #default="{ row }">{{ row.parameters?.schemaVersion === 'pipesim-well-parameters/1' && Number.isFinite(row.parameters.reservoirPressurePsi) ? `地层压力 ${row.parameters.reservoirPressurePsi} psia` : '原模型参数' }}</template>
    </el-table-column>
    <el-table-column label="状态" width="120">
      <template #default="{ row }"><el-tag :type="statusMeta[row.status]?.[1] || 'info'">{{ statusMeta[row.status]?.[0] || row.status }}</el-tag></template>
    </el-table-column>
    <el-table-column label="创建时间" min-width="170"><template #default="{ row }">{{ formatTime(row.createdAt) }}</template></el-table-column>
    <el-table-column label="用时" width="90"><template #default="{ row }">{{ formatElapsed(row.elapsedMillis) }}</template></el-table-column>
  </el-table>
  <el-empty v-if="!loading && !rows.length" description="该模型版本暂无运行记录" :image-size="72" />
</template>

<style lang="scss" scoped>
.history-table { width: 100%; cursor: pointer; border: 1px solid #e1e3e6; }
:deep(.history-table th.el-table__cell) { background: #f5f5f2; }
:deep(.selected-run-row > td.el-table__cell) { background: #fff7bf !important; }
</style>
