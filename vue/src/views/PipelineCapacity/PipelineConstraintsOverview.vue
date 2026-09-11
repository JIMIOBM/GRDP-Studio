<script setup>
import { computed } from 'vue'

const props = defineProps({ state: { type: Object, required: true } })
const emit = defineEmits(['navigate'])
const s = computed(() => props.state)
const descriptions = {
  equipment: '设备允许压力、压缩机功率及运行裕度',
  hydrate: 'PVT 组成与全部工况沿程温压，按纯水无抑制剂经验模型筛查',
  erosion: '本期暂不启用',
  freeze: '本期暂不启用'
}
function label(row) {
  if (row.status === 'inactive') return s.value.statusLabels[row.status]
  if (!s.value.batchResult || s.value.batchStale) return '尚未计算'
  if (row.kind === 'hydrate') return ({ pass: '未进入形成区', fail: '存在形成区工况', equilibrium: '存在平衡边界', conditional: '需确认含水条件', not_evaluated: '未评价' })[row.status] || '未评价'
  return s.value.statusLabels[row.status]
}
</script>

<template>
  <div class="workspace-body" data-page="constraints">
    <main class="result-area">
      <div class="data-toolbar">
        <span>约束条件总览</span>
        <button @click="emit('navigate', 'flow')">前往管流计算</button>
      </div>
      <div class="scroll-content">
        <el-table :data="s.summaryChecks" border>
          <el-table-column prop="name" label="约束项目" min-width="130" />
          <el-table-column label="评价依据" min-width="270">
            <template #default="scope">{{ descriptions[scope.row.kind] }}</template>
          </el-table-column>
          <el-table-column label="当前状态" min-width="150">
            <template #default="scope">
              <span :class="scope.row.status">{{ label(scope.row) }}</span>
              <small v-if="['equipment', 'hydrate'].includes(scope.row.kind) && s.batchStale" class="row-note">计算条件已变化，需重新计算</small>
            </template>
          </el-table-column>
          <el-table-column label="已评价 / 总记录" min-width="140"><template #default="scope">{{ scope.row.status === 'inactive' ? '—' : `${scope.row.evaluatedCount} / ${scope.row.totalCount}` }}</template></el-table-column>
          <el-table-column label="操作" width="120">
            <template #default="scope"><button @click="emit('navigate', scope.row.kind)">进入页面</button></template>
          </el-table-column>
        </el-table>
        <p class="evaluation-note">关键设备校核和水合物经验筛查随全部工况的管流结果统一计算、保存。水合物状态仅反映形成条件，不代表已经生成或堵塞；冲蚀、冻堵本期暂不启用。</p>
      </div>
    </main>
  </div>
</template>

<style scoped>
.conditional,.equilibrium{color:#aa813f}
</style>
