<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { softwareIntegrationApi } from '@/api/softwareIntegration'
import EclipseSummaryExplorer from './EclipseSummaryExplorer.vue'

const props = defineProps({
  run: { type: Object, default: null },
  history: { type: Array, default: () => [] }
})


const isFiniteNumber = value => typeof value === 'number' && Number.isFinite(value)
const validateResult = run => {
  const value = run?.result
  if (!value || value.schemaVersion !== 'eclipse-summary-result/1' || value.modelKind !== 'eclipse_100' ||
    value.runTask !== 'eclipse' || value.resultContract !== 'VALID_FULL' || run?.resultContract !== 'VALID_FULL') return null
  const counts = value.eclEnd
  if (!counts || !['problems', 'errors', 'bugs'].every(key =>
    Number.isInteger(counts[key]) && counts[key] >= 0) ||
    !['comments', 'warnings'].every(key => counts[key] === undefined || counts[key] === null ||
      Number.isInteger(counts[key]) && counts[key] >= 0)) return null
  if (value.summary !== null && (!Array.isArray(value.summary?.series) || !value.summary.series.every(series =>
    typeof series?.keyword === 'string' && (series.objectName === null || typeof series.objectName === 'string') &&
    (series.unit === null || typeof series.unit === 'string') && Array.isArray(series.points) && series.points.every(point =>
      isFiniteNumber(point?.timeDays) && point.timeDays >= 0 && isFiniteNumber(point?.value))))) return null
  return value
}
const result = computed(() => validateResult(props.run))
const comparisonId = ref(null)
const comparison = ref(null)
const comparisonLoading = ref(false)
const comparisonError = ref('')
let comparisonGeneration = 0
const comparisonOptions = computed(() => props.history.filter(run => run.id !== props.run?.id && run.modelId === props.run?.modelId && run.modelVersionId === props.run?.modelVersionId && run.runType === 'eclipse' && run.status === 'SUCCEEDED'))
watch(() => props.run, () => {
  comparisonGeneration++
  comparisonId.value = null
  comparison.value = null
  comparisonLoading.value = false
  comparisonError.value = ''
})
watch(comparisonId, async id => {
  const generation = ++comparisonGeneration
  comparison.value = null
  comparisonError.value = ''
  comparisonLoading.value = Boolean(id)
  if (!id) return
  const current = props.run
  try {
    const response = await softwareIntegrationApi.getRun(id)
    if (generation !== comparisonGeneration) return
    const candidate = response?.data
    if (response?.code !== 200 || candidate?.id !== id || candidate?.modelId !== current?.modelId || candidate?.modelVersionId !== current?.modelVersionId || candidate?.runType !== 'eclipse' || candidate?.status !== 'SUCCEEDED' || !validateResult(candidate)) {
      comparisonError.value = '历史结果未通过校验，无法比较。'
      return
    }
    if (!candidate.result.summary?.series?.some(item => item.points.length)) {
      comparisonError.value = '历史运行没有可用的 RSM 序列。'
      return
    }
    comparison.value = candidate
  } catch { if (generation === comparisonGeneration) comparisonError.value = '历史结果读取失败，请重新选择。' }
  finally { if (generation === comparisonGeneration) comparisonLoading.value = false }
})
onBeforeUnmount(() => { comparisonGeneration++ })
const explorerSeries = computed(() => comparison.value ? [
  ...series.value.map(item => ({ ...item, sourceRunId: props.run.id, historical: false })),
  ...comparison.value.result.summary.series.map(item => ({ ...item, sourceRunId: comparison.value.id, historical: true }))
] : series.value)
const counts = computed(() => result.value?.eclEnd || null)
const countItems = computed(() => [
  ['comments', 'Comments'], ['warnings', 'Warnings'], ['problems', 'Problems'], ['errors', 'Errors'], ['bugs', 'Bugs']
].map(([key, label]) => ({ key, label, value: counts.value?.[key] ?? '-' })))
const series = computed(() => result.value?.summary?.series || [])
const hasSummary = computed(() => result.value?.summary !== null && Array.isArray(result.value?.summary?.series))
const hasUsableSummary = computed(() => series.value.some(item => item.points.length > 0))
const isTerminalSuccess = computed(() => props.run?.status === 'SUCCEEDED')
const successfulResultUnavailable = computed(() => isTerminalSuccess.value && !result.value)
const summaryNotice = computed(() => {
  if (!isTerminalSuccess.value || !result.value || hasUsableSummary.value) return null
  return result.value.summary === null
    ? '本次 ECLIPSE 运行已成功结束，但未提供 RSM Summary 数据；页面不会补充或推测曲线。'
    : '本次 ECLIPSE 运行已成功结束，但 RSM Summary 没有可用序列；页面不会补充或推测曲线。'
})
const safeCode = value => typeof value === 'string' && /^[A-Z][A-Z0-9_]{0,63}$/.test(value) ? value : null
const cleanupFields = new Map([
  ['processTreeExitConfirmed', '进程树已退出'],
  ['inputDeleted', '输入副本已删除'],
  ['workDirectoryDeleted', '工作目录已删除'],
  ['killUsed', '已执行终止清理']
])
const cleanupEntries = computed(() => {
  const cleanup = props.run?.cleanup
  if (!cleanup || typeof cleanup !== 'object' || Array.isArray(cleanup)) return []
  return [...cleanupFields].flatMap(([key, label]) => typeof cleanup[key] === 'boolean'
    ? [{ key: label, value: cleanup[key] ? '是' : '否' }]
    : [])
})
const allowedErrors = new Map([
  ['ECLIPSE_UNAVAILABLE', 'ENVIRONMENT'],
  ['ECLIPSE_VERSION_MISMATCH', 'ENVIRONMENT'],
  ['ECLIPSE_INCLUDE_UNSUPPORTED', 'MODEL'],
  ['ECLIPSE_CLEANUP_FAILED', 'CLEANUP'],
  ['PROCESS_TREE_EXIT_UNCONFIRMED', 'CLEANUP'],
  ['ECLIPSE_RUN_FAILED', 'EXECUTION'],
  ['ECLIPSE_SOLVER_FAILED', 'SOLVER'],
  ['LICENSE_UNAVAILABLE', 'LICENSE']
])
const error = computed(() => {
  const value = props.run?.error
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null
  const category = safeCode(value.category)
  const code = safeCode(value.code)
  if (!code || allowedErrors.get(code) !== category) return null
  return {
    category,
    code,
    retryable: value.retryable === true
  }
})
const failedRunGuidance = computed(() => error.value?.retryable
  ? '请确认运行环境后，从运行页面重新提交该版本。'
  : '请检查模型版本的验证状态或选择其他已验证版本。')
const outputFiles = computed(() => result.value?.outputFiles || [])
const artifacts = computed(() => props.run?.artifacts || [])
const formatSize = value => Number.isFinite(Number(value)) ? `${Number(value).toLocaleString()} B` : '-'
</script>

<template>
  <section class="eclipse-result">
    <el-result v-if="!result" icon="info" :title="successfulResultUnavailable ? '本次 ECLIPSE 运行已成功结束，但可验证运行结果不可用' : '尚无已验证真实计算结果'" :sub-title="successfulResultUnavailable ? '未接收到符合结果契约的运行结果，因此无法确认是否存在 RSM Summary，也不会展示推测数据。' : (error ? failedRunGuidance : '尚未选择成功运行；仅在真实运行通过结果契约后展示 ECLEND、Summary 和 Artifact。')">
      <template v-if="error" #extra>
        <el-tag type="danger">{{ error.category }} / {{ error.code }}</el-tag>
      </template>
    </el-result>
    <template v-else>
    <el-alert v-if="summaryNotice" type="info" :closable="false" :title="summaryNotice" />
    <div v-if="hasUsableSummary" class="eclipse-comparison">
      <span>当前运行 #{{ run.id }}</span>
      <el-select v-model="comparisonId" filterable clearable :loading="comparisonLoading" placeholder="选择同版本历史计算" aria-label="ECLIPSE 历史结果对比">
        <el-option v-for="item in comparisonOptions" :key="item.id" :value="item.id" :label="`运行 #${item.id} · ${item.createdAt || ''}`" />
      </el-select>
      <span v-if="comparisonError" role="status">{{ comparisonError }}</span>
      <span v-else-if="comparison">实线：当前运行；虚线：历史运行。仅同单位序列可叠加。</span>
    </div>
    <EclipseSummaryExplorer v-if="hasSummary" :series="explorerSeries" :run-id="run?.id" />
    <details class="eclipse-audit"><summary>计算信息与输出文件</summary>
    <section class="result-panel">
      <div class="panel-heading"><div><span class="kicker">ECLIPSE 100</span><h2>ECLEND 计数</h2></div><el-tag :type="run?.status === 'SUCCEEDED' ? 'success' : 'info'">{{ run?.status || '-' }}</el-tag></div>
      <div v-if="counts" class="count-strip"><div v-for="item in countItems" :key="item.key"><span>{{ item.label }}</span><strong :class="{ failure: ['problems', 'errors', 'bugs'].includes(item.key) && item.value > 0 }">{{ item.value }}</strong></div></div>
      <el-empty v-else description="当前运行没有可用的 ECLEND 计数" :image-size="56" />
    </section>

    <section v-if="error || cleanupEntries.length" class="result-panel state-panel">
      <div class="panel-heading"><div><h2>错误与清理状态</h2></div></div>
      <dl v-if="error" class="error-grid"><div><dt>类别</dt><dd>{{ error.category || '-' }}</dd></div><div><dt>代码</dt><dd>{{ error.code || '-' }}</dd></div><div><dt>消息</dt><dd>运行失败详情已隐藏。</dd></div><div><dt>可重试</dt><dd>{{ error.retryable ? '是' : '否' }}</dd></div></dl>
      <el-table v-if="cleanupEntries.length" :data="cleanupEntries" border size="small"><el-table-column prop="key" label="清理项" min-width="200" /><el-table-column prop="value" label="状态" min-width="280" show-overflow-tooltip /></el-table>
    </section>


    <section class="result-panel">
      <div class="panel-heading"><div><h2>ECLIPSE 输出文件</h2><p>ECLIPSE 结果契约中的本次输出文件元数据。</p></div><span>{{ outputFiles.length }} 个文件</span></div>
      <el-table v-if="outputFiles.length" :data="outputFiles" border size="small" max-height="320"><el-table-column prop="name" label="文件名" min-width="210" show-overflow-tooltip /><el-table-column label="大小" width="120"><template #default="{ row }">{{ formatSize(row.sizeBytes) }}</template></el-table-column><el-table-column prop="sha256" label="SHA-256" min-width="280" show-overflow-tooltip /></el-table>
      <el-empty v-else description="当前运行没有 ECLIPSE 输出文件元数据" :image-size="56" />
    </section>

    <section class="result-panel">
      <div class="panel-heading"><div><h2>Artifact</h2><p>平台持久化的通用运行 Artifact。</p></div><span>{{ artifacts.length }} 个文件</span></div>
      <el-table v-if="artifacts.length" :data="artifacts" border size="small" max-height="320"><el-table-column prop="name" label="文件名" min-width="210" show-overflow-tooltip /><el-table-column prop="type" label="类型" min-width="120" /><el-table-column label="大小" width="120"><template #default="{ row }">{{ formatSize(row.sizeBytes) }}</template></el-table-column><el-table-column prop="sha256" label="SHA-256" min-width="280" show-overflow-tooltip /><el-table-column prop="expiresAt" label="到期时间" min-width="170" /></el-table>
      <el-empty v-else description="当前运行没有已发布的 Artifact" :image-size="56" />
    </section>
    </details>
    </template>
  </section>
</template>

<style lang="scss" scoped>
.eclipse-comparison { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; padding: 8px 10px; background: #f3f3f0; font-size: 12px; }
.eclipse-comparison .el-select { width: min(320px, 100%); }
.eclipse-audit { border: 1px solid #dcdfe6; }.eclipse-audit > summary { padding: 9px 12px; font-size: 13px; cursor: pointer; background: #f3f3f0; }.eclipse-audit .result-panel { border: 0; border-top: 1px solid #e5e7eb; padding: 12px; }
.eclipse-result { display: flex; flex-direction: column; gap: 16px; }.result-panel { min-width: 0; padding: 16px; border: 1px solid #e1e7ef; background: #fff; }.panel-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; margin-bottom: 12px; }.kicker { color: #2b6cb3; font-size: 11px; font-weight: 700; letter-spacing: .08em; }.panel-heading h2 { margin: 3px 0 0; font-size: 15px; }.panel-heading p { margin: 4px 0 0; color: #909399; font-size: 12px; }.panel-heading > span { color: #737a84; font-size: 12px; }.count-strip { display: grid; grid-template-columns: repeat(5, minmax(90px, 1fr)); border: 1px solid #e8edf3; }.count-strip div { padding: 12px 14px; border-right: 1px solid #e8edf3; }.count-strip div:last-child { border-right: 0; }.count-strip span { display: block; color: #737a84; font-size: 12px; }.count-strip strong { display: block; margin-top: 3px; color: #2b3d52; font-size: 20px; }.count-strip .failure { color: #c45656; }.error-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 9px 22px; margin: 0 0 14px; }.error-grid div { display: flex; gap: 7px; min-width: 0; font-size: 12px; }.error-grid dt { color: #909399; }.error-grid dd { margin: 0; overflow-wrap: anywhere; }.summary-controls { margin-bottom: 12px; }.summary-controls .el-select { width: min(420px, 100%); }.summary-chart { height: 390px; min-height: 280px; margin-bottom: 14px; border: 1px solid #e5eaf1; } @media (max-width: 760px) { .panel-heading { flex-direction: column; }.count-strip { grid-template-columns: repeat(2, 1fr); }.count-strip div { border-bottom: 1px solid #e8edf3; }.count-strip div:nth-child(2n) { border-right: 0; }.error-grid { grid-template-columns: 1fr; }.summary-chart { height: 320px; } }
</style>
