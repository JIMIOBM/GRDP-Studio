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
const runTypeLabel = { nodal: '节点分析', profile: 'PT 剖面', combined: '组合运行', sensitivity: '敏感性分析', 'gas-lift-performance': '气举性能', 'gas-lift-diagnostics': '气举诊断', 'vfp-tables': 'VFP 表生成', 'esp-curves': 'ESP 曲线', trajectory: '井轨迹', network: '管网模拟', 'system-analysis': '系统分析', 'network-optimizer': '网络优化', eclipse: 'ECLIPSE 计算' }
const networkFieldLabel = { pressure: '压力', temperature: '温度', gasFlowRate: '气体流量', liquidFlowRate: '液体流量', massFlowRate: '质量流量' }
const rows = computed(() => props.runs || [])
const formatTime = value => value ? String(value).replace('T', ' ') : '-'
const formatElapsed = value => {
  const seconds = Math.max(0, Math.floor(Number(value || 0) / 1000))
  const minutes = Math.floor(seconds / 60)
  return `${minutes}:${String(seconds % 60).padStart(2, '0')}`
}
const formatParameters = row => {
  const parameters = row.parameters
  if (parameters?.schemaVersion === 'pipesim-well-parameters/1' && Number.isFinite(parameters.reservoirPressurePsi)) return `地层压力 ${parameters.reservoirPressurePsi} psia`
  if (parameters?.schemaVersion === 'pipesim-well-sensitivity-parameters/1') return `${parameters.targetVariable} · ${parameters.values?.join(', ')}`
  if (parameters?.schemaVersion === 'pipesim-network-parameters/1') {
    return parameters.boundaries?.map(boundary => {
      const field = Object.keys(networkFieldLabel).find(key => Number.isFinite(boundary?.[key]))
      return field ? `${boundary.node} · ${networkFieldLabel[field]} ${boundary[field]}` : boundary.node
    }).join('；') || '未覆盖边界条件'
  }
  if (parameters?.schemaVersion === 'pipesim-network-choke-bean-size-parameters/1') {
    return `Choke ${parameters.choke || '-'} · Bean Size ${parameters.originalBeanSize} → ${parameters.targetBeanSize} · 基线 #${parameters.baselineRunId || '-'}`
  }
  if (parameters?.schemaVersion === 'pipesim-system-analysis-parameters/1') {
    return 'LiquidFlowRate ' + (parameters.values?.join(', ') || '-') + ' · ' + (parameters.branchTerminator || '-') + ' · 出口 ' + (parameters.outletPressurePsi || '-') + ' psia'
  }
  if (parameters?.schemaVersion === 'pipesim-network-optimizer-parameters/1' || parameters?.schemaVersion === 'pipesim-network-optimizer-parameters/2') {
    return parameters.applyResults ? '官方优化 + 应用到隔离副本' : '官方优化结果展示'
  }
  if (parameters?.schemaVersion === 'pipesim-gas-lift-performance-parameters/1') {
    return `${parameters.producer || '-'} · 注气 ${parameters.valuesMmscfd?.join(', ') || '-'} mmscf/d · 地层压力 ${parameters.reservoirPressurePsi || '-'} psia`
  }
  if (parameters?.schemaVersion === 'pipesim-gas-lift-diagnostics-parameters/1') {
    return `${parameters.producer || '-'} · 目标注气 ${parameters.targetInjectionRateMmscfd || '-'} mmscf/d · 地层压力 ${parameters.reservoirPressurePsi || '-'} psia`
  }
  if (parameters?.schemaVersion === 'pipesim-vfp-tables-parameters/1') {
    return `${parameters.producer || '-'} · VFPPROD #${parameters.tableNumber || '-'} · ${parameters.liquidRatesStbPerDay?.length || 0}×${parameters.outletPressuresPsi?.length || 0} 轴`
  }
  if (parameters?.schemaVersion === 'pipesim-esp-curves-parameters/1') return '官方 PT Profile + Nodal · B-ESP 只读曲线'
  if (parameters?.schemaVersion === 'pipesim-well-trajectory-parameters/1') return '官方 get_trajectory · 只读井轨迹'
  if (parameters?.schemaVersion === 'eclipse-schedule-parameters/1') {
    return `WELOPEN ${parameters.well || '-'} · ${parameters.date || '-'} · ${parameters.status || '-'} · 基线 #${parameters.baselineRunId || '-'}`
  }
  if (parameters?.schemaVersion === 'eclipse-schedule-parameters/3') {
    return `WCONINJE ${parameters.well || '-'} · ${parameters.injectionType || '-'} RATE ${parameters.targetInjectionRate || '-'} · ${parameters.date || '-'} · 基线 #${parameters.baselineRunId || '-'}`
  }
  if (parameters?.schemaVersion === 'eclipse-schedule-parameters/4') {
    return `WCONPROD ${parameters.well || '-'} · ${parameters.status || '-'} ORAT ${parameters.targetOilRate || '-'} · 预测初始段 · 基线 #${parameters.baselineRunId || '-'}`
  }
  return '原模型参数'
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
    <el-table-column v-if="rows.some(row => ['nodal', 'profile', 'combined', 'sensitivity', 'gas-lift-performance', 'gas-lift-diagnostics', 'vfp-tables', 'esp-curves', 'trajectory', 'network', 'system-analysis', 'network-optimizer', 'eclipse'].includes(row.runType) && row.parameters)" label="参数方案" min-width="260" show-overflow-tooltip>
      <template #default="{ row }">{{ formatParameters(row) }}</template>
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
