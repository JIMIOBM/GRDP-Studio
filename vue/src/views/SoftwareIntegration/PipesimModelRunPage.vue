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
const isNetworkVariable = entry => entry && isSafeTopologyText(entry.variable) &&
  (entry.unit === null || isSafeTopologyText(entry.unit)) && Array.isArray(entry.values)
const isNumericValue = value => value === null || isFiniteNumber(value) ||
  (Array.isArray(value) && value.length > 0 && value.every(isNumericValue)) ||
  (value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length > 0 &&
    Object.values(value).every(isNumericValue))
const copyNumericValue = value => Array.isArray(value)
  ? value.map(copyNumericValue)
  : value && typeof value === 'object'
    ? Object.fromEntries(Object.entries(value).map(([key, item]) => [key, copyNumericValue(item)]))
    : value
const copyNetworkTableVariable = entry => {
  if (!isNetworkVariable(entry) || !entry.values.length) return null
  const values = []
  for (const value of entry.values) {
    if (!value || !isSafeTopologyText(value.name) || !Object.prototype.hasOwnProperty.call(value, 'value')) return null
    if (!isNumericValue(value.value)) return null
    values.push({ name: value.name, value: copyNumericValue(value.value) })
  }
  return { variable: entry.variable, unit: entry.unit, values }
}
const copyNetworkProfile = profile => {
  if (!profile || !isSafeTopologyText(profile.branch) || !Number.isInteger(profile.pointCount) ||
    profile.pointCount < 0 || !Array.isArray(profile.variables) ||
    !profile.variables.every(isNetworkVariable)) return null
  const variables = profile.variables.map(variable => {
    return isNumericValue(variable.values)
      ? { variable: variable.variable, unit: variable.unit, values: copyNumericValue(variable.values) }
      : null
  })
  if (variables.some(variable => !variable)) return null
  const distance = variables.find(variable => variable.variable === 'TotalDistance')?.values
  const pressure = variables.find(variable => variable.variable === 'Pressure')?.values
  const finiteOrGap = value => value === null || isFiniteNumber(value)
  if (!Array.isArray(distance) || !distance.length || !Array.isArray(pressure) ||
    distance.length !== pressure.length || profile.pointCount < distance.length ||
    !distance.every(finiteOrGap) || !pressure.every(finiteOrGap)) return null
  return { branch: profile.branch, pointCount: profile.pointCount, variables }
}
const hasExactFields = (value, fields) => value && typeof value === 'object' && !Array.isArray(value) &&
  Object.keys(value).length === fields.length && fields.every(field => Object.prototype.hasOwnProperty.call(value, field))
const containsUnsafeLocalPath = value => {
  let decoded = value
  try { decoded = decodeURIComponent(value) } catch { /* Keep malformed URI text in its original form. */ }
  return /(?:^|[^a-z0-9])(?:[a-z]:(?:[\\/]|[^\s])|\\\\[^\\/\s]+[\\/]|file:|net\.pipe:\/\/)|(?:^|[^a-z0-9:])\/\/[^/\s]+\//i.test(decoded)
}
const isSafeTopologyText = (value, allowEmpty = false) => typeof value === 'string' && value.length <= 1000 &&
  (allowEmpty ? value.length === 0 || value.trim().length > 0 : value.trim().length > 0) &&
  !/[\u0000-\u001f\u007f-\u009f]/.test(value) &&
  !containsUnsafeLocalPath(value)
const safeNetworkTopology = topology => {
  const nodes = topology?.nodes
  const edges = topology?.edges
  const counts = topology?.counts
  if (!hasExactFields(topology, ['nodes', 'edges', 'counts']) || !Array.isArray(nodes) || !nodes.length || !nodes.every(node =>
    hasExactFields(node, ['id', 'componentType']) && isSafeTopologyText(node.id) && isSafeTopologyText(node.componentType))) return null
  const nodeIds = new Set(nodes.map(node => node.id))
  if (nodeIds.size !== nodes.length || !Array.isArray(edges) || !edges.length || !edges.every(edge =>
    hasExactFields(edge, ['source', 'destination', 'sourcePort']) && isSafeTopologyText(edge.source) &&
    isSafeTopologyText(edge.destination) && isSafeTopologyText(edge.sourcePort, true) &&
    nodeIds.has(edge.source) && nodeIds.has(edge.destination))) return null
  if (!hasExactFields(counts, ['nodes', 'edges', 'sources', 'sinks', 'flowlines']) || !Object.values(counts).every(value =>
    Number.isInteger(value) && value >= 0)) return null
  const componentCount = componentTypes => nodes.filter(node =>
    componentTypes.includes(node.componentType.toLowerCase())).length
  if (counts.nodes !== nodes.length || counts.edges !== edges.length ||
    counts.sources !== componentCount(['source', 'well']) || counts.sinks !== componentCount(['sink']) ||
    counts.flowlines !== componentCount(['flowline'])) return null
  return {
    nodes: nodes.map(({ id, componentType }) => ({ id, componentType })),
    edges: edges.map(({ source, destination, sourcePort }) => ({ source, destination, sourcePort })),
    counts: { ...counts }
  }
}
const copyPartialNetworkSections = (result, selectedStudy) => {
  const sections = {}
  if (isSafeTopologyText(result.study) && result.study === selectedStudy) sections.study = result.study

  for (const [field, copy] of [
    ['system', copyNetworkTableVariable],
    ['node', copyNetworkTableVariable],
    ['profiles', copyNetworkProfile]
  ]) {
    if (!Array.isArray(result[field])) continue
    const entries = result[field].map(copy)
    if (!entries.some(entry => !entry)) sections[field] = entries
  }
  return sections
}
const validNetworkResult = computed(() => {
  if (!isNetworkModel.value) return null
  const result = selectedRun.value?.result
  const topology = result?.topology
  if (!result || result.schemaVersion !== 'pipesim-network-result/1' || result.model_kind !== 'network' ||
    result.runTask !== 'network' || !['VALID_FULL', 'VALID_PARTIAL'].includes(result.resultContract) ||
    result.simulationState !== 'Completed') return null
  if (selectedRun.value?.runType !== 'network' || result.resultContract !== selectedRun.value?.resultContract) return null
  const safeTopology = safeNetworkTopology(topology)
  if (!safeTopology) return null
  if (result.resultContract === 'VALID_PARTIAL' && selectedRun.value?.status === 'PARTIAL_SUCCEEDED') {
    return {
      schemaVersion: 'pipesim-network-result/1',
      model_kind: 'network',
      runTask: 'network',
      resultContract: 'VALID_PARTIAL',
      simulationState: 'Completed',
      topology: safeTopology,
      ...copyPartialNetworkSections(result, selectedRun.value?.study)
    }
  }
  if (result.resultContract !== 'VALID_FULL') return null
  if (!isSafeTopologyText(result.study) || result.study !== selectedRun.value?.study) return null
  if (!Array.isArray(result.system) || !Array.isArray(result.node) || !Array.isArray(result.profiles)) return null
  const system = result.system.map(copyNetworkTableVariable)
  const node = result.node.map(copyNetworkTableVariable)
  const profiles = result.profiles.map(copyNetworkProfile)
  if (system.some(entry => !entry) || node.some(entry => !entry) || profiles.some(profile => !profile)) return null
  if (!result.summary || !['info', 'warnings', 'errors'].every(field => Array.isArray(result.summary[field])) ||
    !['info', 'warnings', 'errors'].every(field => result.summary[field].every(isSafeTopologyText)) ||
    !Array.isArray(result.messages) || !result.messages.every(isSafeTopologyText) ||
    !Array.isArray(result.quality) || !result.quality.every(item =>
       isSafeTopologyText(item?.path) && isSafeTopologyText(item.code))) return null
  return {
    schemaVersion: 'pipesim-network-result/1',
    model_kind: 'network',
    runTask: 'network',
    resultContract: 'VALID_FULL',
    study: result.study,
    simulationState: 'Completed',
    topology: safeTopology,
    system,
    node,
    profiles,
    summary: {
      info: [...result.summary.info],
      warnings: [...result.summary.warnings],
      errors: [...result.summary.errors]
    },
    messages: [...result.messages],
    quality: result.quality.map(({ path, code }) => ({ path, code }))
  }
})
const isPartial = computed(() => selectedRun.value?.status === 'PARTIAL_SUCCEEDED' &&
  validWellResult.value?.resultContract === 'VALID_PARTIAL')
const isNetworkPartial = computed(() => selectedRun.value?.status === 'PARTIAL_SUCCEEDED' &&
  validNetworkResult.value?.resultContract === 'VALID_PARTIAL')
const networkTopologyUnavailable = computed(() => isNetworkModel.value &&
  ['SUCCEEDED', 'PARTIAL_SUCCEEDED'].includes(selectedRun.value?.status) &&
  ['VALID_FULL', 'VALID_PARTIAL'].includes(selectedRun.value?.result?.resultContract) &&
  !validNetworkResult.value)
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
const executionEventTypes = new Set(['STATE', 'REQUEUED', 'WORKER'])
const executionArtifactTypes = new Set(['manifest', 'normalized-result', 'raw-response', 'request', 'log', 'output'])
const executionCleanupFields = new Map([
  ['processTreeExitConfirmed', '进程树已退出'],
  ['inputDeleted', '输入副本已删除'],
  ['workDirectoryDeleted', '工作目录已删除'],
  ['killUsed', '已执行终止清理']
])
const safeExecutionText = value => isSafeTopologyText(value) && value.length <= 240 ? value : null
const safeTimestamp = value => typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?$/.test(value) ? value : null
const safeArtifactName = value => {
  if (typeof value !== 'string' || value.length > 256) return null
  const name = value.split(/[\\/]/).pop()
  return /^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}$/.test(name) ? name : null
}
const safeSha256 = value => typeof value === 'string' && /^[a-f0-9]{64}$/i.test(value) ? value.toLowerCase() : null
const isExecutionPanelRun = computed(() => displayRun.value && statusMeta[displayRun.value.status] &&
  ['nodal', 'profile', 'combined', 'network', 'eclipse'].includes(displayRun.value.runType))
const executionEvents = computed(() => {
  if (!isExecutionPanelRun.value || !Array.isArray(displayRun.value.events)) return []
  return displayRun.value.events.map(event => ({
    type: executionEventTypes.has(event?.type) ? event.type : '受控事件',
    status: statusMeta[event?.status]?.[0] || '状态不可用',
    message: safeExecutionText(event?.message) || '受控事件消息不可用',
    occurredAt: safeTimestamp(event?.occurredAt) || '时间不可用'
  }))
})
const executionCleanup = computed(() => {
  const cleanup = displayRun.value?.cleanup
  if (!isExecutionPanelRun.value || !cleanup || typeof cleanup !== 'object' || Array.isArray(cleanup)) return []
  return [...executionCleanupFields].flatMap(([key, label]) => typeof cleanup[key] === 'boolean'
    ? [{ key: label, value: cleanup[key] ? '是' : '否' }]
    : [])
})
const executionArtifacts = computed(() => {
  if (!isExecutionPanelRun.value || !Array.isArray(displayRun.value.artifacts)) return []
  return displayRun.value.artifacts.map(artifact => ({
    name: safeArtifactName(artifact?.name) || '文件名不可用',
    type: executionArtifactTypes.has(artifact?.type) ? artifact.type : '类型不可用',
    size: Number.isSafeInteger(artifact?.sizeBytes) && artifact.sizeBytes >= 0 ? `${artifact.sizeBytes.toLocaleString()} B` : '大小不可用',
    sha256: safeSha256(artifact?.sha256) || '校验值不可用',
    expiresAt: artifact?.expiresAt === null || artifact?.expiresAt === undefined ? '-' : (safeTimestamp(artifact.expiresAt) || '到期时间不可用')
  }))
})
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
  !networkTopologyUnavailable.value &&
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
const manualRefreshing = ref(false)
const eclipseErrorCategories = new Set(['MODEL', 'ENVIRONMENT', 'EXECUTION', 'SOLVER', 'CLEANUP'])
const eclipseErrorCodes = new Set([
  'ECLIPSE_UNAVAILABLE',
  'ECLIPSE_VERSION_MISMATCH',
  'ECLIPSE_INCLUDE_UNSUPPORTED',
  'ECLIPSE_CLEANUP_FAILED',
  'PROCESS_TREE_EXIT_UNCONFIRMED',
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
const refreshRunManually = async () => {
  const runId = displayRun.value?.id
  if (!runId || manualRefreshing.value) return
  manualRefreshing.value = true
  try {
    await store.selectRun(runId)
    // Reload the persisted summary so a newly terminal run clears the stale active run.
    await store.loadRunHistory(activeVersionId.value, false)
    ElMessage.success('运行状态已刷新')
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    manualRefreshing.value = false
  }
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
      description="已停止自动刷新，避免持续加载。可手动读取一次持久状态，或稍后在运行记录中重新选择该运行。"
      type="warning"
      :closable="false"
      show-icon
    ><template #default><el-button link type="primary" :loading="manualRefreshing" @click="refreshRunManually">手动刷新</el-button></template></el-alert>
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
    <el-alert
      v-if="networkTopologyUnavailable"
      class="network-partial-alert"
      title="管网结果不可用"
      description="返回的管网拓扑或统计信息未通过安全展示校验。为避免展示错误结果，已隐藏管网图表和结果数据。"
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
    <details v-if="isExecutionPanelRun" class="execution-panel" aria-label="持久化执行详情">
      <summary>
        <span><strong>执行详情</strong><small>持久化事件、清理结果与 Artifact 元数据</small></span>
        <span class="execution-count">事件 {{ executionEvents.length }} · Artifact {{ executionArtifacts.length }}</span>
      </summary>
      <div class="execution-panel-content">
        <div class="execution-section">
          <h3>事件 <span>{{ executionEvents.length }}</span></h3>
          <div v-if="executionEvents.length" class="technical-table-scroll">
            <el-table :data="executionEvents" border size="small" max-height="220" class="technical-table">
              <el-table-column prop="type" label="类型" min-width="100" />
              <el-table-column prop="status" label="状态" min-width="110" />
              <el-table-column prop="message" label="消息" min-width="220" show-overflow-tooltip />
              <el-table-column prop="occurredAt" label="时间" min-width="180" />
            </el-table>
          </div>
          <p v-else class="execution-empty">当前运行没有持久化事件。</p>
        </div>
        <div v-if="executionCleanup.length" class="execution-section">
          <h3>清理结果</h3>
          <dl class="cleanup-grid"><div v-for="entry in executionCleanup" :key="entry.key"><dt>{{ entry.key }}</dt><dd>{{ entry.value }}</dd></div></dl>
        </div>
        <div class="execution-section">
          <h3>Artifact <span>{{ executionArtifacts.length }}</span></h3>
          <div v-if="executionArtifacts.length" class="technical-table-scroll">
            <el-table :data="executionArtifacts" border size="small" max-height="220" class="technical-table">
              <el-table-column prop="name" label="文件名" min-width="150" show-overflow-tooltip />
              <el-table-column prop="type" label="类型" min-width="120" />
              <el-table-column prop="size" label="大小" width="120" />
              <el-table-column prop="sha256" label="SHA-256" min-width="260" show-overflow-tooltip />
              <el-table-column prop="expiresAt" label="到期时间" min-width="180" />
            </el-table>
          </div>
          <p v-else class="execution-empty">当前运行没有已发布的 Artifact。</p>
        </div>
      </div>
    </details>
  </section>
</template>

<style lang="scss" scoped>
.model-run-page { min-width: 0; min-height: 0; padding: 20px 24px 28px; color: #303133; overflow: auto; background: #fafafa; }
.model-header { display: flex; align-items: center; justify-content: space-between; gap: 20px; padding: 0 0 14px; border-bottom: 1px solid #dcdfe6; }
.title-line { display: flex; align-items: center; gap: 10px; }
h1 { margin: 0; font-size: 19px; font-weight: 600; }
.model-header p { margin: 5px 0 0; color: #909399; font-size: 12px; }
.run-summary { display: flex; align-items: center; gap: 12px; color: #606266; font-size: 13px; }
.model-readiness, .run-provenance { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 20px; margin-top: 12px; padding: 9px 12px; border: 1px solid #e1e3e6; background: #f5f5f4; color: #606266; font-size: 12px; }.model-readiness div { display: flex; align-items: baseline; gap: 5px; }.model-readiness strong { color: #303133; font-size: 15px; }.model-readiness p { flex: 1 1 300px; margin: 0; }.run-provenance span { overflow-wrap: anywhere; }.run-state-alert { margin-bottom: 12px; }
.execution-panel { min-width: 0; margin-top: 14px; border: 1px solid #dcdfe6; background: #fff; }.execution-panel summary { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 10px 12px; cursor: pointer; list-style: none; color: #303133; font-size: 13px; }.execution-panel summary::-webkit-details-marker { display: none; }.execution-panel summary::before { content: '+'; margin-right: 8px; color: #606266; font-weight: 600; }.execution-panel[open] summary { border-bottom: 1px solid #e4e7ed; background: #f5f5f4; }.execution-panel[open] summary::before { content: '-'; }.execution-panel summary > span:first-of-type { display: flex; align-items: baseline; gap: 9px; min-width: 0; }.execution-panel summary small, .execution-count { color: #909399; font-size: 12px; font-weight: 400; }.execution-count { white-space: nowrap; }.execution-panel-content { padding: 12px; }.execution-section + .execution-section { margin-top: 14px; }.execution-section h3 { margin: 0 0 7px; color: #303133; font-size: 13px; }.execution-section h3 span { color: #909399; font-weight: 400; }.technical-table-scroll { overflow-x: auto; }.technical-table { min-width: 680px; }.execution-empty { margin: 0; padding: 8px 10px; color: #909399; font-size: 12px; background: #f5f5f4; }.cleanup-grid { display: flex; flex-wrap: wrap; gap: 6px 18px; margin: 0; padding: 9px 10px; background: #f5f5f4; font-size: 12px; }.cleanup-grid div { display: flex; gap: 6px; }.cleanup-grid dt { color: #737a84; }.cleanup-grid dd { margin: 0; color: #303133; }
.run-controls { display: grid; grid-template-columns: minmax(150px, 210px) minmax(170px, 240px) auto auto; align-items: end; gap: 14px; padding: 18px 0; }
.run-controls.eclipse-run-controls { grid-template-columns: minmax(150px, 210px) auto; }
.run-controls label, .run-type-control { min-width: 0; }
.run-controls label > span, .run-type-control > span { display: block; margin-bottom: 6px; color: #606266; font-size: 12px; }
.run-controls .el-select { width: 100%; }
.control-actions { display: flex; gap: 8px; }
.eclipse-unavailable { margin-bottom: 14px; }.eclipse-request-summary { display: flex; flex-wrap: wrap; gap: 8px 20px; margin: 0 0 14px; padding: 11px 14px; border: 1px solid #e4e9f0; background: #f8fafc; color: #606266; font-size: 12px; }
.stage-strip { display: flex; align-items: center; gap: 0; min-height: 44px; margin-bottom: 12px; padding: 0 14px; border: 1px solid #dcdfe6; background: #f5f5f4; }
.stage { position: relative; min-width: 120px; display: flex; align-items: center; gap: 7px; color: #909399; font-size: 12px; }
.stage:not(:last-of-type)::after { content: ''; width: 48px; height: 1px; margin: 0 10px; background: #d7dee8; }
.stage i { width: 8px; height: 8px; border: 2px solid #c0c4cc; border-radius: 50%; background: #fff; }
.stage.active { color: #303133; font-weight: 600; }.stage.active i { border-color: #d9a300; background: #f4d000; box-shadow: 0 0 0 3px #fff3bf; }
.stage.done { color: #606266; }.stage.done i { border-color: #606266; background: #606266; }
.queue-stage { margin-left: auto; color: #606266; }
.partial-alert, .network-partial-alert { margin-bottom: 14px; }.network-partial-alert { border: 2px solid #d97706; background: #fff7e6; }.network-partial-alert :deep(.el-alert__title) { color: #9a4d00; font-size: 16px; font-weight: 700; }.network-partial-alert :deep(.el-alert__description) { color: #7a430a; font-weight: 600; }
.structured-error { margin-bottom: 14px; padding: 12px 14px; border-left: 3px solid #d94b4b; background: #fff3f3; color: #8b2525; }
.structured-error dl { display: flex; flex-wrap: wrap; gap: 8px 24px; margin: 0; font-size: 12px; }
.structured-error dl div { display: flex; gap: 5px; }.structured-error dt { color: #a85b5b; }.structured-error dd { margin: 0; }
.result-tabs { min-height: 0; margin-top: 4px; padding: 0 12px 12px; border: 1px solid #dcdfe6; background: #fff; }.result-tabs :deep(.el-tabs__header) { margin: 0 -12px 12px; padding: 0 12px; border-bottom: 1px solid #e4e7ed; background: #f5f5f4; }.result-tabs :deep(.el-tabs__nav-wrap::after) { background: transparent; }.result-tabs :deep(.el-tabs__active-bar) { height: 3px; background: #f4d000; }.result-tabs :deep(.el-tabs__item) { height: 40px; color: #606266; font-size: 13px; }.result-tabs :deep(.el-tabs__item.is-active) { color: #303133; font-weight: 600; }
@media (max-width: 1120px) {
  .run-controls { grid-template-columns: 1fr 1fr; }
  .control-actions { align-self: end; }
}
@media (max-width: 760px) {
  .model-run-page { padding: 14px; }
  .model-header { align-items: flex-start; flex-direction: column; }
  .run-controls { grid-template-columns: 1fr; }
  .stage-strip { overflow-x: auto; }
  .execution-panel summary { align-items: flex-start; }.execution-panel summary > span:first-of-type { flex-direction: column; gap: 2px; }.execution-count { display: none; }.execution-panel-content { padding: 10px; }.result-tabs { padding: 0 10px 10px; }.result-tabs :deep(.el-tabs__header) { margin-right: -10px; margin-left: -10px; padding: 0 10px; overflow-x: auto; }
}
</style>
