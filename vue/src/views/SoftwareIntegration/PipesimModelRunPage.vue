<script setup>
import { computed, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { ElMessage } from 'element-plus'
import { useSoftwareIntegrationStore } from '@/stores/softwareIntegration'
import PipesimNetworkResult from './PipesimNetworkResult.vue'
import PipesimNodalResult from './PipesimNodalResult.vue'
import PipesimProfileResult from './PipesimProfileResult.vue'
import PipesimRunHistory from './PipesimRunHistory.vue'
import EclipseRunResult from './EclipseRunResult.vue'

const props = defineProps({
  eclipsePresentation: { type: Boolean, default: true }
})

const store = useSoftwareIntegrationStore()
const {
  activeModel,
  activeVersion,
  activeVersionId,
  versions,
  isNetworkModel,
  isWellModel,
  isEclipseModel,
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
  cancellingRun,
  runPollingUnavailable
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
  RUNNING_ECLIPSE: ['ECLIPSE 计算中', 'primary'],
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
const isEclipseRunPresentation = computed(() => props.eclipsePresentation && activeVersion.value?.modelKind === 'eclipse_100')
const eclipsePresentationAvailable = computed(() => isEclipseRunPresentation.value &&
  activeVersion.value?.status === 'READY' && Boolean(activeVersion.value?.inspection))
const eclipseRunRequest = computed(() => ({ study: null, runType: 'eclipse', parameters: null }))
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
    result.runTask !== 'network' || !['VALID_FULL', 'VALID_PARTIAL'].includes(result.resultContract) ||
    result.simulationState !== 'Completed') return null
  if (selectedRun.value?.runType !== 'network' || result.resultContract !== selectedRun.value?.resultContract ||
    result.study !== selectedRun.value?.study) return null
  if (result.resultContract === 'VALID_PARTIAL' && selectedRun.value?.status === 'PARTIAL_SUCCEEDED') return result
  if (result.resultContract !== 'VALID_FULL') return null
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
const isNetworkPartial = computed(() => selectedRun.value?.status === 'PARTIAL_SUCCEEDED' &&
  validNetworkResult.value?.resultContract === 'VALID_PARTIAL')
const canRun = computed(() => activeVersion.value?.status === 'READY' &&
  (isNetworkModel.value || isWellModel.value || isEclipseModel.value) &&
  (isEclipseModel.value ? eclipsePresentationAvailable.value : persistedStudies.value.includes(selectedStudy.value)) &&
  !hasActiveRun.value && !submittingRun.value)
const modelTypeLabel = computed(() => {
  if (isNetworkModel.value) return 'PIPESIM 管网模型'
  if (isEclipseModel.value) return 'ECLIPSE 100 模型'
  return isWellModel.value ? 'PIPESIM 井筒模型' : '待识别 PIPESIM 模型'
})
const stages = computed(() => {
  const type = displayRun.value?.runType || runType.value
  const values = ['PREPARING']
  if (type === 'network') values.push('RUNNING_NETWORK')
  else if (type === 'eclipse') values.push('RUNNING_ECLIPSE')
  else {
    if (type === 'nodal' || type === 'combined') values.push('RUNNING_NODAL')
    if (type === 'profile' || type === 'combined') values.push('RUNNING_PROFILE')
  }
  values.push('COLLECTING')
  return values.map(status => ({ status, label: statusMeta[status][0] }))
})
const currentStageIndex = computed(() => stages.value.findIndex(stage => stage.status === displayRun.value?.status))
const selectedError = computed(() => selectedRun.value?.error || null)
const safeCode = value => typeof value === 'string' && /^[A-Z][A-Z0-9_]{0,63}$/.test(value) ? value : null
const safeRunError = computed(() => selectedError.value ? {
  category: safeCode(selectedError.value.category) || 'EXECUTION',
  code: safeCode(selectedError.value.code) || 'RUN_NOT_ACCEPTED',
  retryable: selectedError.value.retryable === true
} : null)
const readyVersionCount = computed(() => versions.value.filter(version => version.status === 'READY').length)
const selectedModelGuidance = computed(() => {
  if (activeVersion.value?.status !== 'READY') return '等待此版本完成验证，或选择一个 READY 版本后再运行。'
  if (isEclipseModel.value) return '该版本使用固定 ECLIPSE 执行契约，不选择 Study，也不覆盖参数。'
  if (!persistedStudies.value.length) return '此 READY 版本未返回可运行的 Study，请重新验证模型。'
  return '选择模型已有 Study 和兼容运行类型后，可创建真实运行任务。'
})
const terminalRunGuidance = computed(() => {
  if (!displayRun.value || !['FAILED', 'CANCELLED', 'TIMED_OUT', 'WORKER_LOST'].includes(displayRun.value.status)) return ''
  if (displayRun.value.status === 'CANCELLED') return '任务已取消。确认 Study 后可重新提交运行。'
  if (displayRun.value.status === 'TIMED_OUT') return '运行已超时。请确认模型与运行环境后重新提交。'
  if (displayRun.value.status === 'WORKER_LOST') return 'Worker 状态已丢失。请确认 Worker 可用后重新提交。'
  return safeRunError.value?.retryable ? '请确认运行环境后重新提交该版本。' : '请检查模型版本和 Study，必要时重新验证后再运行。'
})
const networkContractRejected = computed(() => isNetworkModel.value && !validNetworkResult.value &&
  (Boolean(selectedRun.value?.result) || ['INVALID_NETWORK_RESULT_CONTRACT', 'RESULT_CONTRACT_INVALID'].includes(safeCode(selectedError.value?.code))) &&
  ['SUCCEEDED', 'PARTIAL_SUCCEEDED', 'FAILED'].includes(selectedRun.value?.status))
const historicalSuccessfulNetworkRun = computed(() => runHistory.value.find(run => run.id !== selectedRun.value?.id &&
  run.runType === 'network' && run.status === 'SUCCEEDED' && run.resultContract === 'VALID_FULL'))

const formatElapsed = value => {
  const total = Math.max(0, Math.floor(Number(value || 0) / 1000))
  const hours = Math.floor(total / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  const seconds = total % 60
  return hours ? `${hours}:${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}` : `${minutes}:${String(seconds).padStart(2, '0')}`
}
const errorMessage = () => '请求失败，请稍后重试'
const eclipseErrorCategories = new Set(['MODEL', 'ENVIRONMENT', 'EXECUTION', 'SOLVER', 'CLEANUP'])
const eclipseErrorCodes = new Set([
  'ECLIPSE_UNAVAILABLE',
  'ECLIPSE_VERSION_MISMATCH',
  'ECLIPSE_INCLUDE_UNSUPPORTED',
  'ECLIPSE_CLEANUP_FAILED',
  'ECLIPSE_RUN_FAILED',
  'ECLIPSE_SOLVER_FAILED'
])
const eclipseRequestErrorMessage = (error, message) => {
  const tokens = [
    eclipseErrorCategories.has(error?.category) ? error.category : null,
    eclipseErrorCodes.has(error?.code) ? error.code : null
  ].filter(Boolean)
  return tokens.length ? `${message}（${tokens.join(' / ')}）` : message
}
const isEclipseVersion = versionId => {
  const version = versions.value.find(item => item.id === versionId)
  return version?.modelKind === 'eclipse_100' || /\.data$/i.test(version?.originalName || '')
}

const changeVersion = async versionId => {
  try { await store.selectVersion(versionId) } catch (error) {
    ElMessage.error(isEclipseVersion(versionId)
      ? eclipseRequestErrorMessage(error, '切换 ECLIPSE 模型版本失败，请稍后重试')
      : errorMessage(error))
  }
}
const submitRun = async () => {
  if (!canRun.value) return
  try {
    const detail = await store.createRun()
    if (!detail) return
    activeTab.value = isEclipseModel.value ? 'eclipse' : (isNetworkModel.value ? 'network' : (runType.value === 'profile' ? 'profile' : 'nodal'))
    ElMessage.success('运行任务已创建')
  } catch (error) {
    ElMessage.error(isEclipseModel.value
      ? eclipseRequestErrorMessage(error, '创建 ECLIPSE 运行失败，请稍后重试')
      : errorMessage(error))
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
    else if (detail.runType === 'eclipse') activeTab.value = 'eclipse'
    else if (detail.runType === 'profile') activeTab.value = 'profile'
  } catch (error) {
    const historyRun = runHistory.value.find(run => run.id === runId)
    ElMessage.error(historyRun?.runType === 'eclipse'
      ? eclipseRequestErrorMessage(error, '加载 ECLIPSE 运行记录失败，请稍后重试')
      : errorMessage(error))
  }
}
const selectHistoricalSuccessfulNetworkRun = () => {
  if (historicalSuccessfulNetworkRun.value) selectHistoryRun(historicalSuccessfulNetworkRun.value.id)
  else activeTab.value = 'history'
}

watch(() => selectedRun.value?.id, () => {
  if (selectedRun.value?.runType === 'network') activeTab.value = 'network'
  else if (selectedRun.value?.runType === 'eclipse') activeTab.value = 'eclipse'
  else if (selectedRun.value?.runType === 'profile') activeTab.value = 'profile'
  else if (activeTab.value === 'profile' && selectedRun.value?.runType === 'nodal') activeTab.value = 'nodal'
})
watch([isNetworkModel, isWellModel, isEclipseModel], ([networkModel, wellModel, eclipseModel]) => {
  if (networkModel) {
    runType.value = 'network'
    activeTab.value = 'network'
    return
  }
  if (eclipseModel) {
    runType.value = 'eclipse'
    selectedStudy.value = null
    activeTab.value = 'eclipse'
    return
  }
  if (wellModel && runType.value === 'network') runType.value = 'nodal'
  else if (!wellModel) runType.value = ''
  if (activeTab.value === 'network') activeTab.value = selectedRun.value?.runType === 'profile' ? 'profile' : 'nodal'
}, { immediate: true })

const reloadEclipseRunHistory = async () => {
  if (!eclipsePresentationAvailable.value || !activeVersionId.value) return []
  return store.loadRunHistory(activeVersionId.value)
}

watch([isEclipseRunPresentation, eclipsePresentationAvailable, activeVersionId], ([eclipsePresentation, available]) => {
  if (eclipsePresentation && available) reloadEclipseRunHistory()
}, { immediate: true })

defineExpose({ eclipseRunRequest, reloadEclipseRunHistory })
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

    <section class="model-readiness" aria-label="模型就绪状态">
      <div><span>版本</span><strong>{{ versions.length }}</strong></div>
      <div><span>READY</span><strong>{{ readyVersionCount }}</strong></div>
      <p>{{ selectedModelGuidance }}</p>
    </section>

      <div class="run-controls" :class="{ 'eclipse-run-controls': isEclipseRunPresentation }">
        <label>
        <span>模型版本</span>
        <el-select :model-value="activeVersionId" :disabled="hasActiveRun" @change="changeVersion">
          <el-option v-for="version in versions" :key="version.id" :value="version.id" :label="`v${version.versionNo} · ${version.status}`" />
        </el-select>
      </label>
        <label v-if="!isEclipseRunPresentation">
        <span>Study</span>
        <el-select v-model="selectedStudy" :disabled="hasActiveRun || activeVersion?.status !== 'READY'" placeholder="请选择已有 Study">
          <el-option v-for="study in persistedStudies" :key="study" :value="study" :label="study" />
        </el-select>
      </label>
        <div v-if="!isEclipseRunPresentation" class="run-type-control">
        <span>运行类型</span>
        <el-radio-group v-model="runType" :disabled="hasActiveRun">
          <el-radio-button v-for="option in runTypeOptions" :key="option.value" :value="option.value">{{ option.label }}</el-radio-button>
        </el-radio-group>
      </div>
        <div v-if="!isEclipseRunPresentation || eclipsePresentationAvailable" class="control-actions">
          <el-button type="primary" :loading="submittingRun" :disabled="!canRun" @click="submitRun">运行</el-button>
          <el-button type="danger" plain :loading="cancellingRun" :disabled="!activeRun?.cancellable" @click="cancelRun">取消</el-button>
        </div>
      </div>

      <el-alert
        v-if="isEclipseRunPresentation && !eclipsePresentationAvailable"
        class="eclipse-unavailable"
        title="ECLIPSE 运行不可用"
        :description="activeVersion?.status !== 'READY' ? '请等待模型版本验证为 READY。' : '当前 READY 版本缺少 DATA 检查信息，不能展示或创建 ECLIPSE 运行。'"
        type="warning"
        :closable="false"
        show-icon
      />
      <div v-else-if="isEclipseRunPresentation" class="eclipse-request-summary">
        <span>运行类型：ECLIPSE</span><span>Study：不适用</span><span>参数：不覆盖</span>
      </div>

    <el-alert
      v-if="runPollingUnavailable"
      class="run-state-alert"
      title="运行状态暂时无法刷新"
      description="已停止自动刷新，避免持续加载。请稍后在运行记录中重新选择该运行查看持久状态。"
      type="warning"
      :closable="false"
      show-icon
    />
    <section v-if="displayRun" class="run-provenance" aria-label="真实运行来源">
      <span>模型：{{ displayRun.modelName || activeModel.name }}</span><span>版本：v{{ displayRun.versionNo || activeVersion?.versionNo || '-' }}</span><span>Study：{{ displayRun.study || '不适用' }}</span><span>运行 ID：{{ displayRun.id }}</span><span>创建：{{ displayRun.createdAt || '-' }}</span><span>用时：{{ formatElapsed(displayRun.elapsedMillis) }}</span>
    </section>
    <el-alert
      v-if="terminalRunGuidance"
      class="run-state-alert"
      :title="statusMeta[displayRun.status]?.[0] || displayRun.status"
      :description="terminalRunGuidance"
      type="warning"
      :closable="false"
      show-icon
    />

    <div v-if="(!isEclipseRunPresentation || eclipsePresentationAvailable) && displayRun && hasActiveRun" class="stage-strip" aria-label="真实运行阶段">
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
    <el-alert
      v-if="isNetworkPartial"
      class="network-partial-alert"
      title="部分真实计算结果"
      description="计算已完成，但完整展示校验未通过。仅展示实际返回的数据；未返回的拓扑、表格、图表或剖面字段会标记为不可用。"
      type="warning"
      :closable="false"
      show-icon
    />

    <div v-if="safeRunError && !isEclipseModel" class="structured-error">
      <dl>
        <div><dt>类别</dt><dd>{{ safeRunError.category }}</dd></div>
        <div><dt>代码</dt><dd>{{ safeRunError.code }}</dd></div>
        <div><dt>消息</dt><dd>运行失败详情已隐藏。</dd></div>
        <div><dt>可重试</dt><dd>{{ safeRunError.retryable ? '是' : '否' }}</dd></div>
      </dl>
    </div>
    <el-alert
      v-if="networkContractRejected"
      class="run-state-alert"
      title="模拟器已返回数据，但结果未通过展示契约"
      description="该数据未被当作已计算结果展示，因此不会绘制图表或结果表。请查看运行记录，或选择一条历史成功运行。"
      type="warning"
      :closable="false"
      show-icon
    >
      <template #default><el-button link type="primary" @click="selectHistoricalSuccessfulNetworkRun">{{ historicalSuccessfulNetworkRun ? '选择历史成功运行' : '查看运行记录' }}</el-button></template>
    </el-alert>

    <el-tabs v-if="!isEclipseRunPresentation || eclipsePresentationAvailable" v-model="activeTab" class="result-tabs">
       <el-tab-pane v-if="isWellModel" label="节点分析" name="nodal">
        <PipesimNodalResult :result="validWellResult" />
      </el-tab-pane>
       <el-tab-pane v-if="isWellModel" label="PT 剖面" name="profile">
        <PipesimProfileResult :result="validWellResult" :partial="isPartial" />
      </el-tab-pane>
      <el-tab-pane v-if="isNetworkModel" label="管网结果" name="network">
        <PipesimNetworkResult :result="validNetworkResult" :partial="isNetworkPartial" />
      </el-tab-pane>
      <el-tab-pane v-if="isEclipseModel" label="ECLIPSE 结果" name="eclipse">
        <EclipseRunResult :run="selectedRun" />
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
.model-readiness, .run-provenance { display: flex; flex-wrap: wrap; align-items: center; gap: 10px 22px; margin-top: 14px; padding: 11px 14px; border: 1px solid #e4e9f0; background: #f8fafc; color: #606266; font-size: 12px; }.model-readiness div { display: flex; align-items: baseline; gap: 5px; }.model-readiness strong { color: #2b3d52; font-size: 16px; }.model-readiness p { flex: 1 1 300px; margin: 0; }.run-provenance span { overflow-wrap: anywhere; }.run-state-alert { margin-bottom: 14px; }
.run-controls { display: grid; grid-template-columns: minmax(150px, 210px) minmax(170px, 240px) auto auto; align-items: end; gap: 14px; padding: 18px 0; }
.run-controls.eclipse-run-controls { grid-template-columns: minmax(150px, 210px) auto; }
.run-controls label, .run-type-control { min-width: 0; }
.run-controls label > span, .run-type-control > span { display: block; margin-bottom: 6px; color: #606266; font-size: 12px; }
.run-controls .el-select { width: 100%; }
.control-actions { display: flex; gap: 8px; }
.eclipse-unavailable { margin-bottom: 14px; }.eclipse-request-summary { display: flex; flex-wrap: wrap; gap: 8px 20px; margin: 0 0 14px; padding: 11px 14px; border: 1px solid #e4e9f0; background: #f8fafc; color: #606266; font-size: 12px; }
.stage-strip { display: flex; align-items: center; gap: 0; min-height: 48px; margin-bottom: 14px; padding: 0 18px; border: 1px solid #e4e9f0; background: #f8fafc; }
.stage { position: relative; min-width: 120px; display: flex; align-items: center; gap: 7px; color: #909399; font-size: 12px; }
.stage:not(:last-of-type)::after { content: ''; width: 48px; height: 1px; margin: 0 10px; background: #d7dee8; }
.stage i { width: 8px; height: 8px; border: 2px solid #c0c4cc; border-radius: 50%; background: #fff; }
.stage.active { color: #2b6cb3; font-weight: 600; }.stage.active i { border-color: #2b6cb3; background: #2b6cb3; }
.stage.done { color: #67c23a; }.stage.done i { border-color: #67c23a; background: #67c23a; }
.queue-stage { margin-left: auto; color: #606266; }
.partial-alert, .network-partial-alert { margin-bottom: 14px; }.network-partial-alert { border: 2px solid #d97706; background: #fff7e6; }.network-partial-alert :deep(.el-alert__title) { color: #9a4d00; font-size: 16px; font-weight: 700; }.network-partial-alert :deep(.el-alert__description) { color: #7a430a; font-weight: 600; }
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
