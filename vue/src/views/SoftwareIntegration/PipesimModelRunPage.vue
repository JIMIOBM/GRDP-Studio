<script setup>
import { computed, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { ElMessage } from 'element-plus'
import { useSoftwareIntegrationStore } from '@/stores/softwareIntegration'
import PipesimNetworkResult from './PipesimNetworkResult.vue'
import PipesimNodalResult from './PipesimNodalResult.vue'
import PipesimProfileResult from './PipesimProfileResult.vue'
import PipesimRunHistory from './PipesimRunHistory.vue'

const store = useSoftwareIntegrationStore()
const {
  activeModel,
  activeVersion,
  activeVersionId,
  versions,
  isNetworkModel,
  isWellModel,
  persistedStudies,
  selectedStudy,
  runType,
  runHistory,
  selectedRun,
  activeRun,
  hasActiveRun,
  activeElapsedMillis,
  loadingHistory,
  submittingRun,
  cancellingRun
} = storeToRefs(store)

const activeTab = ref('nodal')
const statusMeta = {
  CREATED: ['已创建', 'info'],
  QUEUED: ['排队中', 'info'],
  CLAIMED: ['已领取', 'primary'],
  PREPARING: ['准备模型', 'primary'],
  RUNNING_NODAL: ['节点分析', 'primary'],
  RUNNING_PROFILE: ['PT 剖面', 'primary'],
  RUNNING_NETWORK: ['管网模拟中', 'primary'],
  COLLECTING: ['收集结果', 'primary'],
  CANCEL_REQUESTED: ['正在取消', 'warning'],
  SUCCEEDED: ['运行成功', 'success'],
  PARTIAL_SUCCEEDED: ['部分成功', 'warning'],
  FAILED: ['运行失败', 'danger'],
  CANCELLED: ['已取消', 'info'],
  TIMED_OUT: ['运行超时', 'danger'],
  WORKER_LOST: ['Worker 失联', 'danger']
}
const wellRunTypeOptions = [
  { value: 'nodal', label: '节点分析' },
  { value: 'profile', label: 'PT 剖面' },
  { value: 'combined', label: '组合运行' }
]
const runTypeOptions = computed(() => {
  if (isNetworkModel.value) return [{ value: 'network', label: '管网模拟' }]
  return isWellModel.value ? wellRunTypeOptions : []
})
const displayRun = computed(() => activeRun.value || selectedRun.value)
const isFiniteNumber = value => typeof value === 'number' && Number.isFinite(value)
const validWellResult = computed(() => {
  if (!isWellModel.value) return null
  const result = selectedRun.value?.result
  if (!result || result.schemaVersion !== 'pipesim-well-result/1') return null
  if (!['VALID_FULL', 'VALID_PARTIAL'].includes(result.resultContract)) return null
  if (result.resultContract !== selectedRun.value?.resultContract || result.runTask !== selectedRun.value?.runType) return null
  if (!['black_oil_liquid', 'basic_gas'].includes(result.model_kind)) return null
  if (!result.units || !['flow', 'pressure', 'depth', 'temperature'].every(field => result.units[field] &&
    (result.units[field].displayUnit === null || typeof result.units[field].displayUnit === 'string'))) return null
  if (!Array.isArray(result.ipr) || !Array.isArray(result.vlp) || !Array.isArray(result.profile)) return null
  if (!result.ipr.every(point => isFiniteNumber(point?.flow) && isFiniteNumber(point?.pressure))) return null
  if (!result.vlp.every(point => isFiniteNumber(point?.flow) && isFiniteNumber(point?.pressure))) return null
  if (!result.profile.every(point => isFiniteNumber(point?.depth) && point.depth >= 0 &&
    isFiniteNumber(point?.pressure) && isFiniteNumber(point?.temperature))) return null
  return result
})
const isNetworkVariable = entry => entry && typeof entry.variable === 'string' &&
  (entry.unit === null || typeof entry.unit === 'string') && Array.isArray(entry.values)
const isNetworkTableVariable = entry => isNetworkVariable(entry) && entry.values.every(value =>
  value && typeof value.name === 'string' && Object.prototype.hasOwnProperty.call(value, 'value'))
const isNetworkProfile = profile => {
  if (!profile || typeof profile.branch !== 'string' || !Number.isInteger(profile.pointCount) ||
    profile.pointCount < 0 || !Array.isArray(profile.variables) ||
    !profile.variables.every(isNetworkVariable)) return false
  const distance = profile.variables.find(variable => variable.variable === 'TotalDistance')?.values
  const pressure = profile.variables.find(variable => variable.variable === 'Pressure')?.values
  const finiteOrGap = value => value === null || isFiniteNumber(value)
  return Array.isArray(distance) && distance.length > 0 && Array.isArray(pressure) &&
    distance.length === pressure.length && profile.pointCount >= distance.length &&
    distance.every(finiteOrGap) && pressure.every(finiteOrGap)
}
const validNetworkResult = computed(() => {
  if (!isNetworkModel.value) return null
  const result = selectedRun.value?.result
  const topology = result?.topology
  const counts = topology?.counts
  if (!result || result.schemaVersion !== 'pipesim-network-result/1' || result.model_kind !== 'network' ||
    result.runTask !== 'network' || result.resultContract !== 'VALID_FULL' || result.simulationState !== 'Completed') return null
  if (selectedRun.value?.runType !== 'network' || selectedRun.value?.resultContract !== 'VALID_FULL' ||
    result.study !== selectedRun.value?.study) return null
  if (!Array.isArray(topology?.nodes) || !topology.nodes.every(node =>
    typeof node?.id === 'string' && typeof node.componentType === 'string')) return null
  if (!Array.isArray(topology?.edges) || !topology.edges.every(edge =>
    typeof edge?.source === 'string' && typeof edge.destination === 'string' &&
    (edge.sourcePort === null || typeof edge.sourcePort === 'string'))) return null
  if (!counts || !['nodes', 'edges', 'sources', 'sinks', 'flowlines'].every(field =>
    Number.isInteger(counts[field]) && counts[field] >= 0)) return null
  if (!Array.isArray(result.system) || !result.system.every(isNetworkTableVariable) ||
    !Array.isArray(result.node) || !result.node.every(isNetworkTableVariable) ||
    !Array.isArray(result.profiles) || !result.profiles.every(isNetworkProfile)) return null
  if (!result.summary || !['info', 'warnings', 'errors'].every(field => Array.isArray(result.summary[field])) ||
    !Array.isArray(result.messages) || !Array.isArray(result.quality) || !result.quality.every(item =>
      typeof item?.path === 'string' && typeof item.code === 'string')) return null
  return result
})
const isPartial = computed(() => selectedRun.value?.status === 'PARTIAL_SUCCEEDED' &&
  validWellResult.value?.resultContract === 'VALID_PARTIAL')
const canRun = computed(() => activeVersion.value?.status === 'READY' &&
  (isNetworkModel.value || isWellModel.value) && persistedStudies.value.includes(selectedStudy.value) &&
  !hasActiveRun.value && !submittingRun.value)
const modelTypeLabel = computed(() => {
  if (isNetworkModel.value) return 'PIPESIM 管网模型'
  return isWellModel.value ? 'PIPESIM 井筒模型' : '待识别 PIPESIM 模型'
})
const stages = computed(() => {
  const type = displayRun.value?.runType || runType.value
  const values = ['PREPARING']
  if (type === 'network') values.push('RUNNING_NETWORK')
  else {
    if (type === 'nodal' || type === 'combined') values.push('RUNNING_NODAL')
    if (type === 'profile' || type === 'combined') values.push('RUNNING_PROFILE')
  }
  values.push('COLLECTING')
  return values.map(status => ({ status, label: statusMeta[status][0] }))
})
const currentStageIndex = computed(() => stages.value.findIndex(stage => stage.status === displayRun.value?.status))
const selectedError = computed(() => selectedRun.value?.error || null)

const formatElapsed = value => {
  const total = Math.max(0, Math.floor(Number(value || 0) / 1000))
  const hours = Math.floor(total / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  const seconds = total % 60
  return hours ? `${hours}:${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}` : `${minutes}:${String(seconds).padStart(2, '0')}`
}
const errorMessage = error => error?.msg || error?.message || '请求失败'

const changeVersion = async versionId => {
  try { await store.selectVersion(versionId) } catch (error) { ElMessage.error(errorMessage(error)) }
}
const submitRun = async () => {
  try {
    const detail = await store.createRun()
    if (!detail) return
    activeTab.value = isNetworkModel.value ? 'network' : (runType.value === 'profile' ? 'profile' : 'nodal')
    ElMessage.success('运行任务已创建')
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}
const cancelRun = async () => {
  try {
    await store.cancelRun()
    ElMessage.success('取消请求已提交')
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}
const selectHistoryRun = async runId => {
  try {
    const detail = await store.selectRun(runId)
    if (!detail) return
    if (detail.runType === 'network') activeTab.value = 'network'
    else if (detail.runType === 'profile') activeTab.value = 'profile'
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

watch(() => selectedRun.value?.id, () => {
  if (selectedRun.value?.runType === 'network') activeTab.value = 'network'
  else if (selectedRun.value?.runType === 'profile') activeTab.value = 'profile'
  else if (activeTab.value === 'profile' && selectedRun.value?.runType === 'nodal') activeTab.value = 'nodal'
})
watch([isNetworkModel, isWellModel], ([networkModel, wellModel]) => {
  if (networkModel) {
    runType.value = 'network'
    activeTab.value = 'network'
    return
  }
  if (wellModel && runType.value === 'network') runType.value = 'nodal'
  else if (!wellModel) runType.value = ''
  if (activeTab.value === 'network') activeTab.value = selectedRun.value?.runType === 'profile' ? 'profile' : 'nodal'
}, { immediate: true })
</script>

<template>
  <section v-if="activeModel" class="model-run-page">
    <header class="model-header">
      <div>
        <div class="title-line">
          <h1>{{ activeModel.name }}</h1>
          <el-tag :type="activeVersion?.status === 'READY' ? 'success' : 'warning'">{{ activeVersion?.status || '无版本' }}</el-tag>
        </div>
        <p>{{ modelTypeLabel }} · v{{ activeVersion?.versionNo || '-' }}</p>
      </div>
      <div v-if="displayRun" class="run-summary">
        <el-tag :type="statusMeta[displayRun.status]?.[1] || 'info'">{{ statusMeta[displayRun.status]?.[0] || displayRun.status }}</el-tag>
        <span>已用时间 {{ formatElapsed(activeElapsedMillis) }}</span>
      </div>
    </header>

    <div class="run-controls">
      <label>
        <span>模型版本</span>
        <el-select :model-value="activeVersionId" :disabled="hasActiveRun" @change="changeVersion">
          <el-option v-for="version in versions" :key="version.id" :value="version.id" :label="`v${version.versionNo} · ${version.status}`" />
        </el-select>
      </label>
      <label>
        <span>Study</span>
        <el-select v-model="selectedStudy" :disabled="hasActiveRun || activeVersion?.status !== 'READY'" placeholder="请选择已有 Study">
          <el-option v-for="study in persistedStudies" :key="study" :value="study" :label="study" />
        </el-select>
      </label>
      <div class="run-type-control">
        <span>运行类型</span>
        <el-radio-group v-model="runType" :disabled="hasActiveRun">
          <el-radio-button v-for="option in runTypeOptions" :key="option.value" :value="option.value">{{ option.label }}</el-radio-button>
        </el-radio-group>
      </div>
      <div class="control-actions">
        <el-button type="primary" :loading="submittingRun" :disabled="!canRun" @click="submitRun">运行</el-button>
        <el-button type="danger" plain :loading="cancellingRun" :disabled="!activeRun?.cancellable" @click="cancelRun">取消</el-button>
      </div>
    </div>

    <div v-if="displayRun && hasActiveRun" class="stage-strip" aria-label="真实运行阶段">
      <div v-for="(stage, index) in stages" :key="stage.status" class="stage" :class="{ active: currentStageIndex === index, done: currentStageIndex > index }">
        <i />
        <span>{{ stage.label }}</span>
      </div>
      <span v-if="currentStageIndex < 0" class="queue-stage">{{ statusMeta[displayRun.status]?.[0] || displayRun.status }}</span>
    </div>

    <el-alert
      v-if="isPartial"
      class="partial-alert"
      title="组合运行部分成功：节点分析结果可用，PT 剖面失败。"
      type="warning"
      :closable="false"
      show-icon
    />

    <div v-if="selectedError" class="structured-error">
      <dl>
        <div><dt>类别</dt><dd>{{ selectedError.category }}</dd></div>
        <div><dt>代码</dt><dd>{{ selectedError.code }}</dd></div>
        <div><dt>消息</dt><dd>{{ selectedError.message }}</dd></div>
        <div><dt>可重试</dt><dd>{{ selectedError.retryable ? '是' : '否' }}</dd></div>
      </dl>
    </div>

    <el-tabs v-model="activeTab" class="result-tabs">
       <el-tab-pane v-if="isWellModel" label="节点分析" name="nodal">
        <PipesimNodalResult :result="validWellResult" />
      </el-tab-pane>
       <el-tab-pane v-if="isWellModel" label="PT 剖面" name="profile">
        <PipesimProfileResult :result="validWellResult" :partial="isPartial" />
      </el-tab-pane>
      <el-tab-pane v-if="isNetworkModel" label="管网结果" name="network">
        <PipesimNetworkResult :result="validNetworkResult" />
      </el-tab-pane>
      <el-tab-pane label="运行记录" name="history">
        <PipesimRunHistory
          :runs="runHistory"
          :selected-run-id="selectedRun?.id"
          :loading="loadingHistory"
          @select="selectHistoryRun"
        />
      </el-tab-pane>
    </el-tabs>
  </section>
</template>

<style lang="scss" scoped>
.model-run-page { min-width: 0; min-height: 0; padding: 22px 28px 30px; color: #303133; overflow: auto; }
.model-header { display: flex; align-items: center; justify-content: space-between; gap: 20px; padding-bottom: 16px; border-bottom: 1px solid #e4e7ed; }
.title-line { display: flex; align-items: center; gap: 10px; }
h1 { margin: 0; font-size: 19px; font-weight: 600; }
.model-header p { margin: 5px 0 0; color: #909399; font-size: 12px; }
.run-summary { display: flex; align-items: center; gap: 12px; color: #606266; font-size: 13px; }
.run-controls { display: grid; grid-template-columns: minmax(150px, 210px) minmax(170px, 240px) auto auto; align-items: end; gap: 14px; padding: 18px 0; }
.run-controls label, .run-type-control { min-width: 0; }
.run-controls label > span, .run-type-control > span { display: block; margin-bottom: 6px; color: #606266; font-size: 12px; }
.run-controls .el-select { width: 100%; }
.control-actions { display: flex; gap: 8px; }
.stage-strip { display: flex; align-items: center; gap: 0; min-height: 48px; margin-bottom: 14px; padding: 0 18px; border: 1px solid #e4e9f0; background: #f8fafc; }
.stage { position: relative; min-width: 120px; display: flex; align-items: center; gap: 7px; color: #909399; font-size: 12px; }
.stage:not(:last-of-type)::after { content: ''; width: 48px; height: 1px; margin: 0 10px; background: #d7dee8; }
.stage i { width: 8px; height: 8px; border: 2px solid #c0c4cc; border-radius: 50%; background: #fff; }
.stage.active { color: #2b6cb3; font-weight: 600; }.stage.active i { border-color: #2b6cb3; background: #2b6cb3; }
.stage.done { color: #67c23a; }.stage.done i { border-color: #67c23a; background: #67c23a; }
.queue-stage { margin-left: auto; color: #606266; }
.partial-alert { margin-bottom: 14px; }
.structured-error { margin-bottom: 14px; padding: 12px 14px; border-left: 3px solid #d94b4b; background: #fff3f3; color: #8b2525; }
.structured-error dl { display: flex; flex-wrap: wrap; gap: 8px 24px; margin: 0; font-size: 12px; }
.structured-error dl div { display: flex; gap: 5px; }.structured-error dt { color: #a85b5b; }.structured-error dd { margin: 0; }
.result-tabs { min-height: 0; }.result-tabs :deep(.el-tabs__header) { margin-bottom: 14px; }.result-tabs :deep(.el-tabs__active-bar) { background: #f4d000; }.result-tabs :deep(.el-tabs__item.is-active) { color: #303133; font-weight: 600; }
@media (max-width: 1120px) {
  .run-controls { grid-template-columns: 1fr 1fr; }
  .control-actions { align-self: end; }
}
@media (max-width: 760px) {
  .model-run-page { padding: 16px; }
  .model-header { align-items: flex-start; flex-direction: column; }
  .run-controls { grid-template-columns: 1fr; }
  .stage-strip { overflow-x: auto; }
}
</style>
