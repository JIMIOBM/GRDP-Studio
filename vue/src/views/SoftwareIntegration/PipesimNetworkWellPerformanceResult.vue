<script setup>
import { computed, ref, watch } from 'vue'

const props = defineProps({
  result: { type: Object, default: null },
  artifact: { type: Object, default: null }
})
const emit = defineEmits(['download-artifact'])

const selectedCaseIndex = ref(0)
const cases = computed(() => Array.isArray(props.result?.cases) ? props.result.cases : [])
const selectedCase = computed(() => cases.value[selectedCaseIndex.value] || cases.value[0] || null)
const rows = series => (Array.isArray(series) ? series : []).flatMap(item =>
  (Array.isArray(item.values) ? item.values : []).map(value => ({
    variable: item.variable,
    unit: item.unit,
    name: value.name,
    value: value.value
  })))
const systemRows = computed(() => rows(props.result?.system))
const nodeRows = computed(() => rows(selectedCase.value?.node))
const messages = computed(() => [
  ...(props.result?.summary?.errors || []).map(message => ({ type: 'error', message })),
  ...(props.result?.summary?.warnings || []).map(message => ({ type: 'warning', message })),
  ...(props.result?.summary?.info || []).map(message => ({ type: 'info', message })),
  ...(props.result?.messages || []).map(message => ({ type: 'info', message }))
])
const displayValue = value => {
  if (value === null || value === undefined) return '—'
  if (typeof value === 'boolean') return value ? '是' : '否'
  if (typeof value === 'number') return Number.isFinite(value) ? value.toLocaleString(undefined, { maximumFractionDigits: 5 }) : '—'
  return String(value)
}

watch(() => props.result?.cases, () => { selectedCaseIndex.value = 0 })
</script>

<template>
  <section class="network-well-performance" aria-label="PIPESIM 井性能曲线结果" data-testid="pipesim-network-well-performance-result">
    <el-empty v-if="!result" description="尚未选择成功的井性能曲线运行，或结果未通过展示合同校验" :image-size="72" />
    <template v-else>
      <header class="result-head">
        <div>
          <strong>PIPESIM Well Performance Curves</strong>
          <span>Study：{{ result.study }} · 生产井：{{ result.producerContext }} · Flowline：{{ result.branchTerminator }}</span>
          <span>变量：InnerDiameter (in) · {{ result.flowratePoints }} 个流量计算点</span>
        </div>
        <div class="head-actions">
          <el-tag type="success">{{ result.simulationState }} · VALID_FULL</el-tag>
          <el-button size="small" type="primary" plain :disabled="!artifact?.id" @click="emit('download-artifact', artifact)">下载官方 PWI</el-button>
        </div>
      </header>

      <div class="case-controls">
        <span>Flowline 内径工况</span>
        <el-radio-group v-model="selectedCaseIndex" size="small">
          <el-radio-button v-for="(item, index) in cases" :key="item.caseId" :value="index">
            {{ item.sensitivityValueIn }} in
          </el-radio-button>
        </el-radio-group>
        <span class="muted">官方 Case：{{ selectedCase?.caseId }}</span>
      </div>

      <div class="metric-grid">
        <div class="metric-card"><span>敏感性工况</span><strong>{{ cases.length }}</strong><small>实际返回 Case 数</small></div>
        <div class="metric-card"><span>系统结果变量</span><strong>{{ systemRows.length }}</strong><small>官方 System 输出</small></div>
        <div class="metric-card"><span>当前 Case 节点结果</span><strong>{{ nodeRows.length }}</strong><small>官方 Node 输出</small></div>
        <div class="metric-card"><span>质量标记</span><strong>{{ result.quality.length }}</strong><small>不可用值审计项</small></div>
      </div>

      <el-alert v-for="(item, index) in messages" :key="`${item.type}-${index}`" :type="item.type" :title="item.message" :closable="false" class="result-message" />

      <section class="result-panel">
        <h3>System 结果</h3>
        <el-table :data="systemRows" border size="small" max-height="300" empty-text="官方未返回系统量">
          <el-table-column prop="variable" label="变量" min-width="190" show-overflow-tooltip />
          <el-table-column prop="name" label="结果项" min-width="160" show-overflow-tooltip />
          <el-table-column label="值" min-width="130"><template #default="{ row }">{{ displayValue(row.value) }}</template></el-table-column>
          <el-table-column prop="unit" label="单位" width="110" />
        </el-table>
      </section>
      <section class="result-panel">
        <h3>Node 结果 · Case {{ selectedCase?.caseId }} · {{ selectedCase?.sensitivityValueIn }} in</h3>
        <el-table :data="nodeRows" border size="small" max-height="420" empty-text="官方未返回节点量">
          <el-table-column prop="variable" label="变量" min-width="190" show-overflow-tooltip />
          <el-table-column prop="name" label="节点 / 结果项" min-width="180" show-overflow-tooltip />
          <el-table-column label="值" min-width="130"><template #default="{ row }">{{ displayValue(row.value) }}</template></el-table-column>
          <el-table-column prop="unit" label="单位" width="110" />
        </el-table>
      </section>
    </template>
  </section>
</template>

<style lang="scss" scoped>
.network-well-performance { min-width: 0; }
.result-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; padding: 10px 12px; background: #f5f7fa; border: 1px solid #e4e7ed; }
.result-head strong, .result-head span { display: block; }
.result-head strong { color: #2b6cb3; font-size: 15px; }
.result-head span { margin-top: 4px; color: #73777d; font-size: 12px; }
.head-actions { display: flex; align-items: center; gap: 8px; }
.case-controls { display: flex; flex-wrap: wrap; align-items: center; gap: 9px; padding: 10px 2px; }
.case-controls > span:first-child { color: #606266; font-size: 12px; }
.muted { color: #909399; font-size: 12px; }
.metric-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 8px; margin-bottom: 10px; }
.metric-card { padding: 9px 11px; border: 1px solid #e4e7ed; background: #fff; }
.metric-card span, .metric-card small { display: block; color: #909399; font-size: 12px; }
.metric-card strong { display: block; margin: 3px 0; color: #303133; font-size: 18px; }
.result-message { margin-bottom: 6px; }
.result-panel { min-width: 0; margin-top: 10px; padding: 10px; border: 1px solid #e4e7ed; }
.result-panel h3 { margin: 0 0 8px; color: #303133; font-size: 13px; }
@media (max-width: 720px) { .result-head { flex-direction: column; } .head-actions { flex-wrap: wrap; } }
</style>
