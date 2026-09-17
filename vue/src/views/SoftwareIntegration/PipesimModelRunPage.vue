<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { softwareIntegrationApi } from '@/api/softwareIntegration'
import { storeToRefs } from 'pinia'
import { ElMessage } from 'element-plus'
import { useSoftwareIntegrationStore } from '@/stores/softwareIntegration'
import PipesimNetworkResult from './PipesimNetworkResult.vue'
import PipesimNodalResult from './PipesimNodalResult.vue'
import PipesimProfileResult from './PipesimProfileResult.vue'
import PipesimRunHistory from './PipesimRunHistory.vue'
import EclipseRunResult from './EclipseRunResult.vue'
import EclipseDataInspectionOverview from './EclipseDataInspectionOverview.vue'
import { sourceReservoirPressure, supportsPressureScenario } from './wellParameterPreview'

const props = defineProps({
  eclipsePresentation: { type: Boolean, default: true }
})

const store = useSoftwareIntegrationStore()
const {
  activeProjectId,
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
  runPollingUnavailable,
  capabilities,
  activeSimulatorCapability,
  workerBusy,
  canCreateRunByCapability
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
const validateWellRun = run => {
  const result = run?.result
  if (!result || result.schemaVersion !== 'pipesim-well-result/1') return null
  if (!['VALID_FULL', 'VALID_PARTIAL'].includes(result.resultContract)) return null
  if (!['SUCCEEDED', 'PARTIAL_SUCCEEDED'].includes(run.status)) return null
  if (result.resultContract !== run.resultContract || result.runTask !== run.runType) return null
  if (!['black_oil_liquid', 'basic_gas'].includes(result.model_kind)) return null
  if (!result.units || !['flow', 'pressure', 'depth', 'temperature'].every(field => result.units[field] &&
    (result.units[field].displayUnit === null || typeof result.units[field].displayUnit === 'string'))) return null
  if (!Array.isArray(result.ipr) || !Array.isArray(result.vlp) || !Array.isArray(result.profile)) return null
  if (!result.ipr.every(point => isFiniteNumber(point?.flow) && isFiniteNumber(point?.pressure))) return null
  if (!result.vlp.every(point => isFiniteNumber(point?.flow) && isFiniteNumber(point?.pressure))) return null
  if (!result.profile.every(point => isFiniteNumber(point?.depth) && point.depth >= 0 &&
    isFiniteNumber(point?.pressure) && isFiniteNumber(point?.temperature))) return null
  return result
}
const validWellResult = computed(() => isWellModel.value ? validateWellRun(selectedRun.value) : null)
const comparisonId = ref(null)
const comparisonRun = ref(null)
const comparisonLoading = ref(false)
const comparisonError = ref('')
let comparisonGeneration = 0
const comparisonOptions = computed(() => runHistory.value.filter(run => run.id !== selectedRun.value?.id &&
  ['SUCCEEDED', 'PARTIAL_SUCCEEDED'].includes(run.status) && ['nodal', 'profile', 'combined'].includes(run.runType) &&
  run.modelVersionId === activeVersionId.value && run.modelId === activeModel.value?.id))
const comparisonResult = computed(() => validateWellRun(comparisonRun.value))
const runLabel = run => run ? `运行 #${run.id} · v${run.versionNo || activeVersion.value?.versionNo} · ${run.study || '无 Study'}${run.parameters?.schemaVersion === 'pipesim-well-parameters/1' && Number.isFinite(run.parameters.reservoirPressurePsi) ? ` · 地层压力 ${run.parameters.reservoirPressurePsi} psia` : ''}` : ''
watch(comparisonId, async id => {
  const generation = ++comparisonGeneration
  comparisonRun.value = null
  comparisonError.value = ''
  comparisonLoading.value = Boolean(id)
  if (!id) return
  const versionId = activeVersionId.value
  const modelId = activeModel.value?.id
  try {
    const response = await softwareIntegrationApi.getRun(id)
    if (generation !== comparisonGeneration) return
    const run = response?.data
    if (response?.code !== 200 || run?.id !== id || run?.modelVersionId !== versionId || run?.modelId !== modelId || !validateWellRun(run)) {
      comparisonError.value = '所选历史结果不可用于比较。'
      return
    }
    comparisonRun.value = run
  } catch { if (generation === comparisonGeneration) comparisonError.value = '历史结果读取失败，请重新选择。' }
  finally { if (generation === comparisonGeneration) comparisonLoading.value = false }
})
watch([activeVersionId, () => selectedRun.value?.id], () => {
  comparisonGeneration++
  comparisonId.value = null
  comparisonRun.value = null
  comparisonLoading.value = false
  comparisonError.value = ''
})
onBeforeUnmount(() => { comparisonGeneration++ })
const isNetworkVariable = entry => entry && isSafeTopologyText(entry.variable) &&
  (entry.unit === null || isSafeTopologyText(entry.unit, true)) && Array.isArray(entry.values)
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
    if (variable.variable === 'BranchEquipment') {
      return variable.values.length === profile.pointCount && variable.values.every(value =>
        value === null || isSafeTopologyText(value, true))
        ? { variable: variable.variable, unit: variable.unit, values: [...variable.values] }
        : null
    }
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
const canRun = computed(() => !previewPending.value && activeVersion.value?.status === 'READY' &&
  (isNetworkModel.value || isWellModel.value || isEclipseModel.value) &&
  (isEclipseModel.value ? eclipsePresentationAvailable.value : persistedStudies.value.includes(selectedStudy.value)) &&
  !hasActiveRun.value && !submittingRun.value && canCreateRunByCapability.value)
const capabilityReasonLabels = Object.freeze({
  WORKER_UNREACHABLE: 'Worker 无法连接',
  WORKER_BUSY: 'Worker 正在执行其他任务',
  PIPESIM_UNAVAILABLE: 'PIPESIM 执行环境不可用',
  PIPESIM_VERSION_MISMATCH: 'PIPESIM 版本不匹配',
  ECLIPSE_UNAVAILABLE: 'ECLIPSE 执行环境不可用',
  ECLIPSE_VERSION_MISMATCH: 'ECLIPSE 版本不匹配'
})
const runCapabilityMessage = computed(() => {
  if (!capabilities.value) return '正在检测执行环境；检测完成前不能创建新运行。'
  if (capabilities.value.worker?.status !== 'AVAILABLE') return capabilityReasonLabels.WORKER_UNREACHABLE
  if (workerBusy.value) return capabilityReasonLabels.WORKER_BUSY
  if (activeSimulatorCapability.value?.status !== 'AVAILABLE') {
    return capabilityReasonLabels[activeSimulatorCapability.value?.reasonCode] || '当前模拟器执行环境不可用'
  }
  return ''
})
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
const scenarioEnabled = ref(false)
const scenarioPressure = ref(null)
const supportsCurrentScenario = computed(() => supportsPressureScenario(activeVersion.value?.modelKind, runType.value))
const sourcePressure = computed(() => sourceReservoirPressure(activeVersion.value))
const readingVersionId = ref(null)
const previewPending = computed(() => readingVersionId.value === activeVersionId.value || ['UPLOADED', 'VALIDATING'].includes(activeVersion.value?.status))
const canReadSource = computed(() => isWellModel.value && !hasActiveRun.value && !submittingRun.value && !previewPending.value && !workerBusy.value)
const canUseSource = computed(() => isWellModel.value && !hasActiveRun.value && !submittingRun.value && !previewPending.value && sourcePressure.value > 0 && sourcePressure.value <= 100000)
const readSourceParameters = async () => {
  if (!canReadSource.value) return
  const projectId = activeProjectId.value
  const versionId = activeVersionId.value
  readingVersionId.value = versionId
  try {
    await store.revalidateModel(projectId, versionId)
    if (activeProjectId.value === projectId && activeVersionId.value === versionId) ElMessage.success('已提交重新验证，完成后显示原模型参数')
  } catch {
    if (activeProjectId.value === projectId && activeVersionId.value === versionId) ElMessage.error('原模型参数读取请求失败，请稍后重试')
  } finally {
    if (readingVersionId.value === versionId) readingVersionId.value = null
  }
}
const useSourcePressure = () => {
  if (!canUseSource.value) return
  const pressure = sourcePressure.value
  if (!supportsCurrentScenario.value) runType.value = 'nodal'
  scenarioPressure.value = pressure
  scenarioEnabled.value = true
}
watch([activeVersionId, runType], () => { scenarioEnabled.value = false; scenarioPressure.value = null }, { flush: 'sync' })
const scenarioSnapshot = computed(() => selectedRun.value?.parameters?.schemaVersion === 'pipesim-well-parameters/1'
  && Number.isFinite(selectedRun.value.parameters.reservoirPressurePsi) ? selectedRun.value.parameters.reservoirPressurePsi : null)
const canReuseScenario = computed(() => isWellModel.value && activeVersion.value?.status === 'READY' && !hasActiveRun.value && !submittingRun.value &&
  selectedRun.value?.modelVersionId === activeVersionId.value && supportsPressureScenario(activeVersion.value?.modelKind, selectedRun.value?.runType) && scenarioSnapshot.value > 0 && scenarioSnapshot.value <= 100000)
const reuseScenario = () => {
  if (!canReuseScenario.value) return
  const pressure = scenarioSnapshot.value
  runType.value = selectedRun.value.runType
  scenarioPressure.value = pressure
  scenarioEnabled.value = true
  ElMessage.success('已载入方案参数，可修改后点击运行；不会修改历史记录')
}
const submitRun = async () => {
  if (!canRun.value) return
  if (scenarioEnabled.value && (!supportsCurrentScenario.value || !Number.isFinite(scenarioPressure.value) || scenarioPressure.value <= 0 || scenarioPressure.value > 100000)) {
    ElMessage.error('当前模型或运行类型不支持压力方案，或压力不在大于 0 且不超过 100000 psia 的范围内')
    return
  }
  try {
    const detail = await store.createRun(scenarioEnabled.value ? { schemaVersion: 'pipesim-well-parameters/1', reservoirPressurePsi: scenarioPressure.value } : null)
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

defineExpose({ eclipseRunRequest })
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

      <section v-if="isWellModel" class="well-scenario" aria-label="原模型参数">
        <strong>原模型参数 · v{{ activeVersion?.versionNo }}</strong>
        <span v-if="sourcePressure !== null" data-testid="source-reservoir-pressure">地层压力：{{ sourcePressure }} psia</span>
        <span v-else>{{ previewPending ? '正在验证并读取原模型参数…' : '地层压力：暂无可用原值（尚未读取、读取失败或单位不支持）' }}</span>
        <el-button link type="primary" :disabled="!canReadSource" :loading="previewPending" @click="readSourceParameters">重新验证并读取</el-button>
        <el-button v-if="sourcePressure !== null" link type="primary" :disabled="!canUseSource" @click="useSourcePressure">以原值创建方案</el-button>
        <small>从上传版本的隔离副本读取；重新验证期间暂不可计算，不修改原文件。</small>
        <small v-if="sourcePressure > 100000">原值超出当前方案编辑范围，仅供查看。</small>
      </section>
      <section v-if="supportsCurrentScenario" class="well-scenario" aria-label="井筒压力方案">
        <el-checkbox v-model="scenarioEnabled" :disabled="hasActiveRun || submittingRun">使用地层压力方案</el-checkbox>
        <template v-if="scenarioEnabled">
          <label>地层压力 (psia) <el-input-number v-model="scenarioPressure" :min="0.000001" :max="100000" :disabled="hasActiveRun || submittingRun" aria-label="方案地层压力" /></label>
          <span>仅修改本次计算副本；模型单位不匹配或设置失败时停止计算。</span>
          <span v-if="activeVersion?.modelKind === 'basic_gas' && runType !== 'nodal'">基础气井方案同时将该值用于 PT 入口压力，沿用桌面端计算方式。</span>
          <span v-if="sourcePressure !== null && Number.isFinite(scenarioPressure)" data-testid="pressure-scenario-comparison">原值 {{ sourcePressure }} → 方案值 {{ scenarioPressure }} psia</span>
        </template>
        <span v-else>使用上传模型的原始参数</span>
      </section>
      <div v-if="isWellModel && selectedRun" class="inline-notice"><span>当前结果 #{{ selectedRun.id }}：{{ scenarioSnapshot !== null ? `压力方案 · 地层压力 ${scenarioSnapshot} psia` : '原模型参数' }}</span>
        <el-button v-if="scenarioSnapshot !== null" link type="primary" :disabled="!canReuseScenario" @click="reuseScenario">载入此方案参数</el-button>
      </div>
      <p v-if="runCapabilityMessage" class="inline-notice warning" :title="`${runCapabilityMessage}；历史运行与已有结果仍可查看。`">
        新运行不可用：{{ runCapabilityMessage }}
      </p>

      <p
        v-if="isEclipseRunPresentation && !eclipsePresentationAvailable"
        class="inline-notice warning"
        :title="activeVersion?.status !== 'READY' ? '请等待模型版本验证为 READY。' : '当前 READY 版本缺少 DATA 检查信息。'"
      >ECLIPSE 运行不可用：{{ activeVersion?.status !== 'READY' ? '模型尚未 READY' : '缺少有效 DATA 检查' }}</p>
      <div v-else-if="isEclipseRunPresentation" class="eclipse-request-summary">
        <span>运行类型：ECLIPSE</span><span>Study：不适用</span><span>参数：不覆盖</span>
      </div>

    <p v-if="runPollingUnavailable" class="inline-notice warning" title="自动刷新已停止，可手动读取一次持久状态。">
      运行状态暂时无法刷新 <el-button link type="primary" :loading="manualRefreshing" @click="refreshRunManually">手动刷新</el-button>
    </p>
    <p v-if="terminalRunGuidance" class="inline-notice warning" :title="terminalRunGuidance">
      {{ statusMeta[displayRun.status]?.[0] || displayRun.status }}：{{ terminalRunGuidance }}
    </p>

    <div v-if="(!isEclipseRunPresentation || eclipsePresentationAvailable) && displayRun && hasActiveRun" class="stage-strip" aria-label="真实运行阶段">
      <div v-for="(stage, index) in stages" :key="stage.status" class="stage" :class="{ active: currentStageIndex === index, done: currentStageIndex > index }">
        <i />
        <span>{{ stage.label }}</span>
      </div>
      <span v-if="currentStageIndex < 0" class="queue-stage">{{ statusMeta[displayRun.status]?.[0] || displayRun.status }}</span>
    </div>

    <p v-if="isPartial" class="inline-notice warning">组合运行部分成功：节点分析结果可用，PT 剖面失败。</p>
    <p v-if="isNetworkPartial" class="inline-notice warning" title="仅展示实际返回并通过安全校验的数据。">部分真实计算结果：部分管网结果不可用。</p>
    <p v-if="networkTopologyUnavailable" class="inline-notice danger" title="返回的拓扑或统计信息未通过安全展示校验。">管网结果不可用：已隐藏未通过校验的数据。</p>

    <div v-if="safeRunError && !isEclipseModel" class="structured-error">
      <dl>
        <div><dt>类别</dt><dd>{{ safeRunError.category }}</dd></div>
        <div><dt>代码</dt><dd>{{ safeRunError.code }}</dd></div>
        <div><dt>消息</dt><dd>运行失败详情已隐藏。</dd></div>
        <div><dt>可重试</dt><dd>{{ safeRunError.retryable ? '是' : '否' }}</dd></div>
      </dl>
    </div>
    <p v-if="networkContractRejected" class="inline-notice warning" title="返回数据未通过展示契约，未绘制图表或结果表。">
      结果未通过展示契约 <el-button link type="primary" @click="selectHistoricalSuccessfulNetworkRun">{{ historicalSuccessfulNetworkRun ? '选择历史成功运行' : '查看运行记录' }}</el-button>
    </p>

    <div v-if="isWellModel && validWellResult" class="comparison-controls">
      <span>{{ runLabel(selectedRun) }}</span>
      <el-select v-model="comparisonId" filterable clearable :loading="comparisonLoading" placeholder="选择同版本历史结果对比" aria-label="井筒历史结果对比">
        <el-option v-for="run in comparisonOptions" :key="run.id" :value="run.id" :label="`${runLabel(run)} · ${run.runType}`" />
      </el-select>
      <span v-if="comparisonError" role="status">{{ comparisonError }}</span>
    </div>
    <el-tabs v-model="activeTab" class="result-tabs">
       <el-tab-pane v-if="isWellModel" label="节点分析" name="nodal">
        <PipesimNodalResult v-if="activeTab === 'nodal'" :result="validWellResult" :comparison-result="comparisonResult" :source-label="runLabel(selectedRun)" :comparison-label="runLabel(comparisonRun)" />
      </el-tab-pane>
       <el-tab-pane v-if="isWellModel" label="PT 剖面" name="profile">
        <PipesimProfileResult v-if="activeTab === 'profile'" :result="validWellResult" :partial="isPartial" :comparison-result="comparisonResult" :source-label="runLabel(selectedRun)" :comparison-label="runLabel(comparisonRun)" />
      </el-tab-pane>
      <el-tab-pane v-if="isWellModel && validWellResult?.runTask === 'combined'" label="综合结果" name="combined">
        <section v-if="activeTab === 'combined'" class="combined-results" aria-label="井筒综合结果">
          <PipesimNodalResult :result="validWellResult" :comparison-result="comparisonResult" :source-label="runLabel(selectedRun)" :comparison-label="runLabel(comparisonRun)" />
          <PipesimProfileResult :result="validWellResult" :partial="isPartial" :comparison-result="comparisonResult" :source-label="runLabel(selectedRun)" :comparison-label="runLabel(comparisonRun)" />
        </section>
      </el-tab-pane>
      <el-tab-pane v-if="isNetworkModel" label="管网结果" name="network">
        <PipesimNetworkResult :result="validNetworkResult" :partial="isNetworkPartial" :run-id="selectedRun?.id" />
      </el-tab-pane>
      <el-tab-pane v-if="isEclipseModel" label="ECLIPSE 结果" name="eclipse">
        <EclipseRunResult :run="selectedRun" :history="runHistory" />
      </el-tab-pane>
      <el-tab-pane v-if="isEclipseModel" label="DATA 检查" name="inspection">
        <EclipseDataInspectionOverview :embedded="true" />
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
        <dl class="execution-provenance">
          <div><dt>模型</dt><dd>{{ displayRun.modelName || activeModel.name }}</dd></div>
          <div><dt>版本</dt><dd>v{{ displayRun.versionNo || activeVersion?.versionNo || '-' }}</dd></div>
          <div><dt>Study</dt><dd>{{ displayRun.study || '不适用' }}</dd></div>
          <div><dt>运行 ID</dt><dd>{{ displayRun.id }}</dd></div>
          <div><dt>创建时间</dt><dd>{{ displayRun.createdAt || '-' }}</dd></div>
          <div><dt>用时</dt><dd>{{ formatElapsed(displayRun.elapsedMillis) }}</dd></div>
        </dl>
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
.combined-results { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.combined-results > * { min-width: 0; }
@media (max-width: 1200px) { .combined-results { grid-template-columns: 1fr; } }
.well-scenario { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 16px; padding: 8px 10px; border-bottom: 1px solid #deded9; font-size: 12px; }
.well-scenario > span { color: #73777d; }
.well-scenario > small { flex-basis: 100%; color: #73777d; }
.comparison-controls { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; padding: 7px 10px; background: #f3f3f0; font-size: 12px; }.comparison-controls .el-select { width: 340px; max-width: 100%; }
.model-run-page { min-width: 0; min-height: 0; padding: 14px 18px 22px; color: #303133; overflow: auto; background: #fff; }
.model-header { min-height: 38px; display: flex; align-items: center; justify-content: space-between; gap: 20px; padding: 0 0 8px; border-bottom: 1px solid #dcdfe6; }
.title-line { display: flex; align-items: center; gap: 10px; }
h1 { margin: 0; font-size: 19px; font-weight: 600; }
.model-header p { margin: 2px 0 0; color: #909399; font-size: 12px; }
.run-summary { display: flex; align-items: center; gap: 12px; color: #606266; font-size: 13px; }
.execution-panel { min-width: 0; margin-top: 14px; border: 1px solid #dcdfe6; background: #fff; }.execution-panel summary { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 10px 12px; cursor: pointer; list-style: none; color: #303133; font-size: 13px; }.execution-panel summary::-webkit-details-marker { display: none; }.execution-panel summary::before { content: '+'; margin-right: 8px; color: #606266; font-weight: 600; }.execution-panel[open] summary { border-bottom: 1px solid #e4e7ed; background: #f5f5f4; }.execution-panel[open] summary::before { content: '-'; }.execution-panel summary > span:first-of-type { display: flex; align-items: baseline; gap: 9px; min-width: 0; }.execution-panel summary small, .execution-count { color: #909399; font-size: 12px; font-weight: 400; }.execution-count { white-space: nowrap; }.execution-panel-content { padding: 12px; }.execution-section + .execution-section { margin-top: 14px; }.execution-section h3 { margin: 0 0 7px; color: #303133; font-size: 13px; }.execution-section h3 span { color: #909399; font-weight: 400; }.technical-table-scroll { overflow-x: auto; }.technical-table { min-width: 680px; }.execution-empty { margin: 0; padding: 8px 10px; color: #909399; font-size: 12px; background: #f5f5f4; }.cleanup-grid { display: flex; flex-wrap: wrap; gap: 6px 18px; margin: 0; padding: 9px 10px; background: #f5f5f4; font-size: 12px; }.cleanup-grid div { display: flex; gap: 6px; }.cleanup-grid dt { color: #737a84; }.cleanup-grid dd { margin: 0; color: #303133; }
.execution-provenance { display: flex; flex-wrap: wrap; gap: 6px 20px; margin: 0 0 12px; padding: 0 0 10px; border-bottom: 1px solid #e4e7ed; font-size: 12px; }.execution-provenance div { display: flex; gap: 5px; }.execution-provenance dt { color: #909399; }.execution-provenance dd { margin: 0; overflow-wrap: anywhere; }
.run-controls { display: grid; grid-template-columns: minmax(150px, 210px) minmax(170px, 240px) auto auto; align-items: end; gap: 8px 12px; margin: 8px 0; padding: 7px 10px; border-bottom: 1px solid #deded9; background: #f3f3f0; }
.run-controls.eclipse-run-controls { grid-template-columns: minmax(150px, 210px) auto; }
.run-controls label, .run-type-control { min-width: 0; }
.run-controls label > span, .run-type-control > span { display: block; margin-bottom: 6px; color: #606266; font-size: 12px; }
.run-controls .el-select { width: 100%; }
.control-actions { display: flex; gap: 8px; }
.eclipse-request-summary { display: flex; flex-wrap: wrap; gap: 8px 20px; margin: 0 0 8px; padding: 4px 8px; color: #606266; font-size: 12px; }
.inline-notice { min-height: 26px; display: flex; align-items: center; gap: 6px; margin: 0 0 6px; padding: 0 8px; overflow: hidden; border-left: 2px solid #c99b00; background: #fff9dd; color: #695600; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; }.inline-notice.danger { border-left-color: #c45656; background: #fff2f2; color: #8b3030; }.inline-notice .el-button { flex: 0 0 auto; }
.stage-strip { display: flex; align-items: center; gap: 0; min-height: 44px; margin-bottom: 12px; padding: 0 14px; border: 1px solid #dcdfe6; background: #f5f5f4; }
.stage { position: relative; min-width: 120px; display: flex; align-items: center; gap: 7px; color: #909399; font-size: 12px; }
.stage:not(:last-of-type)::after { content: ''; width: 48px; height: 1px; margin: 0 10px; background: #d7dee8; }
.stage i { width: 8px; height: 8px; border: 2px solid #c0c4cc; border-radius: 50%; background: #fff; }
.stage.active { color: #303133; font-weight: 600; }.stage.active i { border-color: #d9a300; background: #f4d000; box-shadow: 0 0 0 3px #fff3bf; }
.stage.done { color: #606266; }.stage.done i { border-color: #606266; background: #606266; }
.queue-stage { margin-left: auto; color: #606266; }
.structured-error { margin-bottom: 14px; padding: 12px 14px; border-left: 3px solid #d94b4b; background: #fff3f3; color: #8b2525; }
.structured-error dl { display: flex; flex-wrap: wrap; gap: 8px 24px; margin: 0; font-size: 12px; }
.structured-error dl div { display: flex; gap: 5px; }.structured-error dt { color: #a85b5b; }.structured-error dd { margin: 0; }
.result-tabs { min-height: 0; margin-top: 2px; }.result-tabs :deep(.el-tabs__header) { margin: 0 0 8px; border-bottom: 1px solid #dcdfe6; }.result-tabs :deep(.el-tabs__nav-wrap::after) { height: 1px; background: #dcdfe6; }.result-tabs :deep(.el-tabs__active-bar) { height: 2px; background: #f4d000; }.result-tabs :deep(.el-tabs__item) { height: 34px; color: #606266; font-size: 13px; }.result-tabs :deep(.el-tabs__item.is-active) { color: #303133; font-weight: 600; }
@media (max-width: 1120px) {
  .run-controls { grid-template-columns: 1fr 1fr; }
  .control-actions { align-self: end; }
}
@media (max-width: 760px) {
  .model-run-page { padding: 14px; }
  .model-header { align-items: flex-start; flex-direction: column; }
  .run-controls { grid-template-columns: 1fr; }
  .stage-strip { overflow-x: auto; }
  .inline-notice { overflow-x: auto; text-overflow: clip; }.execution-panel summary { align-items: flex-start; }.execution-panel summary > span:first-of-type { flex-direction: column; gap: 2px; }.execution-count { display: none; }.execution-panel-content { padding: 10px; }.result-tabs :deep(.el-tabs__header) { overflow-x: auto; }
}
</style>
