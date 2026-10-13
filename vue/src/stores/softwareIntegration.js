import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { softwareIntegrationApi } from '@/api/softwareIntegration'

export const SOFTWARE_INTEGRATION_TERMINAL_STATUSES = Object.freeze([
  'SUCCEEDED',
  'PARTIAL_SUCCEEDED',
  'FAILED',
  'CANCELLED',
  'TIMED_OUT',
  'WORKER_LOST'
])

const terminalStatuses = new Set(SOFTWARE_INTEGRATION_TERMINAL_STATUSES)
const wellRunTypes = new Set(['nodal', 'profile', 'combined', 'sensitivity', 'gas-lift-performance', 'gas-lift-diagnostics', 'vfp-tables', 'esp-curves', 'esp-parameter', 'trajectory'])
const networkRunTypes = new Set(['network', 'system-analysis', 'network-optimizer', 'network-well-performance-curves'])
const wellModelKinds = new Set(['black_oil_liquid', 'basic_gas', 'legacy_well'])
export const isTerminalRunStatus = status => terminalStatuses.has(status)
const isValidationPending = status => status === 'UPLOADED' || status === 'VALIDATING'
const unwrap = response => response?.data ?? response
const byNewestVersion = (left, right) => Number(right.versionNo || 0) - Number(left.versionNo || 0)
const capabilityReasonCodes = new Set([
  'WORKER_UNREACHABLE', 'WORKER_BUSY', 'PIPESIM_UNAVAILABLE', 'PIPESIM_VERSION_MISMATCH',
  'ECLIPSE_UNAVAILABLE', 'ECLIPSE_VERSION_MISMATCH'
])
const capabilityTasks = new Set(['nodal', 'profile', 'combined', 'sensitivity', 'gas-lift-performance', 'gas-lift-diagnostics', 'vfp-tables', 'esp-curves', 'esp-parameter', 'trajectory', 'network', 'system-analysis', 'network-optimizer', 'network-well-performance-curves', 'eclipse'])
const unavailableCapability = reasonCode => ({
  version: null,
  status: 'UNAVAILABLE',
  reasonCode,
  runTasks: [],
  maxTimeoutSeconds: null
})
const normalizeSimulatorCapability = (value, fallbackReason) => {
  if (!value || typeof value !== 'object' || !['AVAILABLE', 'UNAVAILABLE'].includes(value.status)) {
    return unavailableCapability(fallbackReason)
  }
  return {
    version: typeof value.version === 'string' && value.version.length <= 32 ? value.version : null,
    status: value.status,
    reasonCode: capabilityReasonCodes.has(value.reasonCode) ? value.reasonCode : (value.status === 'AVAILABLE' ? null : fallbackReason),
    runTasks: Array.isArray(value.runTasks) ? value.runTasks.filter(task => capabilityTasks.has(task)) : [],
    maxTimeoutSeconds: Number.isInteger(value.maxTimeoutSeconds) && value.maxTimeoutSeconds > 0 ? value.maxTimeoutSeconds : null
  }
}
const unavailableCapabilities = () => ({
  worker: { status: 'UNAVAILABLE', idle: false, reasonCode: 'WORKER_UNREACHABLE' },
  pipesimWell: unavailableCapability('WORKER_UNREACHABLE'),
  pipesimNetwork: unavailableCapability('WORKER_UNREACHABLE'),
  eclipse100: unavailableCapability('WORKER_UNREACHABLE'),
  eclipse300: unavailableCapability('WORKER_UNREACHABLE')
})

export const useSoftwareIntegrationStore = defineStore('software-integration', () => {
  const projects = ref([])
  const recycleBinProjects = ref([])
  const projectDetails = ref({})
  const activeProjectId = ref(null)
  const activeModelId = ref(null)
  const activeVersionId = ref(null)
  const selectedStudy = ref('')
  const runType = ref('nodal')
  const runHistory = ref([])
  const selectedRun = ref(null)
  const activeRun = ref(null)
  // 已受理任务的 Worker 占用不随页面导航或详情读取失败而消失。
  const acceptedRun = ref(null)
  const loadingProjects = ref(false)
  const loadingHistory = ref(false)
  const submittingRun = ref(false)
  const cancellingRun = ref(false)
  const runPollingUnavailable = ref(false)
  const capabilities = ref(null)
  const loadingCapabilities = ref(false)
  const capabilitiesUnavailable = ref(false)
  const elapsedClock = ref(Date.now())

  let validationPollTimer
  let runPollTimer
  let elapsedTicker
  // 每类异步响应只能提交到发起时的导航/选择上下文，旧响应不得恢复旧页面或轮询。
  let projectsLoadGeneration = 0
  let navigationGeneration = 0
  let historyGeneration = 0
  let runDetailGeneration = 0
  let runPollGeneration = 0
  let validationPollingEnabled = false
  let elapsedBase = 0
  let elapsedSyncedAt = Date.now()
  let capabilityGeneration = 0
  let selectedRunId = null

  const activeProjectDetail = computed(() => projectDetails.value[activeProjectId.value] || null)
  const activeProject = computed(() => activeProjectDetail.value?.project ||
    projects.value.find(project => project.id === activeProjectId.value) || null)
  const activeModel = computed(() => activeProjectDetail.value?.models?.find(model => model.id === activeModelId.value) || null)
  const versions = computed(() => [...(activeModel.value?.versions || [])].sort(byNewestVersion))
  const readyVersions = computed(() => versions.value.filter(version => version.status === 'READY'))
  const activeVersion = computed(() => versions.value.find(version => version.id === activeVersionId.value) || null)
  const activeModelKind = computed(() => activeVersion.value?.modelKind || '')
  const isNetworkModel = computed(() => activeModelKind.value === 'network')
  const isWellModel = computed(() => wellModelKinds.has(activeModelKind.value))
  const hasNetworkCapability = computed(() => {
    const inspection = activeVersion.value?.inspection
    const capability = inspection?.networkCapability
    return isWellModel.value && ['pipesim-well-inspection/7', 'pipesim-well-inspection/8', 'pipesim-well-inspection/9'].includes(inspection?.schemaVersion) &&
      capability?.schemaVersion === 'pipesim-network-capability/1' &&
      Array.isArray(capability.studies) && capability.studies.length > 0 &&
      capability.networkInspection?.schemaVersion === 'pipesim-network-inspection/3'
  })
  const isNetworkCapable = computed(() => hasNetworkCapability.value &&
    activeVersion.value?.inspection?.networkCapability?.studies?.includes(selectedStudy.value))
  const isEclipseModel = computed(() => ['eclipse_100', 'eclipse_300'].includes(activeModelKind.value) || /\.data$/i.test(activeVersion.value?.originalName || ''))
  const persistedStudies = computed(() => activeVersion.value?.status === 'READY' && Array.isArray(activeVersion.value.studies)
    ? activeVersion.value.studies
    : [])
  const hasActiveRun = computed(() => Boolean(activeRun.value && !isTerminalRunStatus(activeRun.value.status)))
  const workerBusy = computed(() => hasActiveRun.value || Boolean(acceptedRun.value) ||
    (capabilities.value?.worker?.status === 'AVAILABLE' && !capabilities.value.worker.idle))
  const activeSimulatorCapability = computed(() => {
    if (networkRunTypes.has(runType.value) && (isNetworkModel.value || isNetworkCapable.value)) return capabilities.value?.pipesimNetwork || null
    if (isNetworkModel.value) return capabilities.value?.pipesimNetwork || null
    if (isEclipseModel.value) return activeModelKind.value === 'eclipse_300' ? capabilities.value?.eclipse300 || null : capabilities.value?.eclipse100 || null
    if (isWellModel.value) return capabilities.value?.pipesimWell || null
    return null
  })
  const activeSimulatorAvailable = computed(() => activeSimulatorCapability.value?.status === 'AVAILABLE')
  const activeRunTaskSupported = computed(() => {
    const task = isNetworkModel.value || isWellModel.value || isEclipseModel.value ? runType.value : ''
    if (networkRunTypes.has(task) && isWellModel.value && !isNetworkModel.value && !isNetworkCapable.value) return false
    return Array.isArray(activeSimulatorCapability.value?.runTasks) && activeSimulatorCapability.value.runTasks.includes(task)
  })
  const canCreateRunByCapability = computed(() => Boolean(capabilities.value) && !capabilitiesUnavailable.value &&
    capabilities.value.worker?.status === 'AVAILABLE' && !workerBusy.value && activeSimulatorAvailable.value && activeRunTaskSupported.value)
  const activeElapsedMillis = computed(() => {
    if (hasActiveRun.value) {
      return Math.max(0, elapsedBase + elapsedClock.value - elapsedSyncedAt)
    }
    return Math.max(0, Number(selectedRun.value?.elapsedMillis || 0))
  })

  const stopElapsedTicker = () => {
    window.clearInterval(elapsedTicker)
    elapsedTicker = undefined
  }

  const syncElapsed = run => {
    elapsedBase = Math.max(0, Number(run?.elapsedMillis || 0))
    elapsedSyncedAt = Date.now()
    elapsedClock.value = elapsedSyncedAt
    stopElapsedTicker()
    if (run && !isTerminalRunStatus(run.status)) {
      elapsedTicker = window.setInterval(() => { elapsedClock.value = Date.now() }, 1000)
    }
  }

  const stopRunPolling = () => {
    runPollGeneration += 1
    window.clearTimeout(runPollTimer)
    runPollTimer = undefined
    stopElapsedTicker()
  }

  const stopValidationPolling = () => {
    window.clearTimeout(validationPollTimer)
    validationPollTimer = undefined
  }

  const invalidateRunRequests = () => {
    historyGeneration += 1
    runDetailGeneration += 1
    loadingHistory.value = false
    stopRunPolling()
  }

  const beginNavigation = () => {
    navigationGeneration += 1
    selectedRunId = null
    invalidateRunRequests()
    runPollingUnavailable.value = false
    return navigationGeneration
  }

  const matchesRunContext = (generation, versionId) =>
    generation === navigationGeneration && activeVersionId.value === versionId

  const setProjectDetail = detail => {
    const projectId = detail?.project?.id
    if (!projectId) return
    projectDetails.value = { ...projectDetails.value, [projectId]: detail }
  }

  const syncRunTypeForModel = () => {
    if (!activeModel.value) return
    if (isNetworkModel.value && !networkRunTypes.has(runType.value)) runType.value = 'network'
    else if (isEclipseModel.value) runType.value = 'eclipse'
    else if (isWellModel.value && !wellRunTypes.has(runType.value) &&
      !(isNetworkCapable.value && networkRunTypes.has(runType.value))) runType.value = 'nodal'
    else if (!isNetworkModel.value && !isWellModel.value && !isEclipseModel.value) runType.value = ''
  }

  const loadProjectDetail = async projectId => {
    const detail = unwrap(await softwareIntegrationApi.getProject(projectId))
    setProjectDetail(detail)
    return detail
  }

  const hasPendingValidation = () => Object.values(projectDetails.value).some(detail =>
    detail?.models?.some(model => model.versions?.some(version => isValidationPending(version.status))))

  const scheduleValidationPolling = () => {
    stopValidationPolling()
    if (!validationPollingEnabled || !hasPendingValidation()) return
    validationPollTimer = window.setTimeout(async () => {
      try {
        const pendingProjectIds = Object.values(projectDetails.value)
          .filter(detail => detail?.models?.some(model => model.versions?.some(version => isValidationPending(version.status))))
          .map(detail => detail.project.id)
        await Promise.all(pendingProjectIds.map(loadProjectDetail))
      } catch {
        // 短暂网络错误不改变持久验证状态，下一轮继续读取后端真值。
      } finally {
        scheduleValidationPolling()
      }
    }, 2000)
  }

  const loadProjects = async () => {
    const requestGeneration = ++projectsLoadGeneration
    const expectedNavigation = navigationGeneration
    validationPollingEnabled = true
    loadingProjects.value = true
    try {
      const summaries = unwrap(await softwareIntegrationApi.listProjects()) || []
      const details = await Promise.all(summaries.map(project => softwareIntegrationApi.getProject(project.id).then(unwrap)))
      if (requestGeneration !== projectsLoadGeneration || expectedNavigation !== navigationGeneration) return null
      projects.value = summaries
      projectDetails.value = Object.fromEntries(details.filter(Boolean).map(detail => [detail.project.id, detail]))
      if (activeProjectId.value && !projectDetails.value[activeProjectId.value]) {
        beginNavigation()
        activeProjectId.value = null
        activeModelId.value = null
        activeVersionId.value = null
      }
      if (!activeProjectId.value && projects.value[0]) activeProjectId.value = projects.value[0].id
      syncRunTypeForModel()
      scheduleValidationPolling()
      return details
    } finally {
      if (requestGeneration === projectsLoadGeneration) loadingProjects.value = false
    }
  }

  const loadCapabilities = async () => {
    const generation = ++capabilityGeneration
    loadingCapabilities.value = true
    capabilitiesUnavailable.value = false
    capabilities.value = null
    try {
      const value = unwrap(await softwareIntegrationApi.getCapabilities())
      if (generation !== capabilityGeneration) return null
      const workerStatus = value?.worker?.status === 'AVAILABLE' ? 'AVAILABLE' : 'UNAVAILABLE'
      capabilities.value = {
        worker: {
          status: workerStatus,
          idle: workerStatus === 'AVAILABLE' && value?.worker?.idle === true,
          reasonCode: capabilityReasonCodes.has(value?.worker?.reasonCode) ? value.worker.reasonCode : (workerStatus === 'AVAILABLE' ? null : 'WORKER_UNREACHABLE')
        },
        pipesimWell: normalizeSimulatorCapability(value?.pipesimWell, 'PIPESIM_UNAVAILABLE'),
        pipesimNetwork: normalizeSimulatorCapability(value?.pipesimNetwork, 'PIPESIM_UNAVAILABLE'),
        eclipse100: normalizeSimulatorCapability(value?.eclipse100, 'ECLIPSE_UNAVAILABLE'),
        eclipse300: normalizeSimulatorCapability(value?.eclipse300, 'ECLIPSE_UNAVAILABLE')
      }
      return capabilities.value
    } catch (error) {
      if (generation !== capabilityGeneration) return null
      capabilities.value = unavailableCapabilities()
      capabilitiesUnavailable.value = true
      throw error
    } finally {
      if (generation === capabilityGeneration) loadingCapabilities.value = false
    }
  }

  const selectProject = async projectId => {
    const generation = beginNavigation()
    activeRun.value = null
    selectedRun.value = null
    runHistory.value = []
    activeProjectId.value = projectId
    activeModelId.value = null
    activeVersionId.value = null
    selectedStudy.value = ''
    const detail = projectDetails.value[projectId] || await loadProjectDetail(projectId)
    if (generation !== navigationGeneration || activeProjectId.value !== projectId) return null
    return detail
  }

  const createProject = async data => {
    const expectedNavigation = navigationGeneration
    const project = unwrap(await softwareIntegrationApi.createProject(data))
    await loadProjects()
    if (expectedNavigation === navigationGeneration) await selectProject(project.id)
    return project
  }

  const deleteProject = async projectId => {
    await softwareIntegrationApi.deleteProject(projectId)
    if (activeProjectId.value === projectId) {
      beginNavigation()
      activeProjectId.value = null
      activeModelId.value = null
      activeVersionId.value = null
    }
    await loadProjects()
  }

  const deleteModel = async (projectId, modelId) => {
    await softwareIntegrationApi.deleteModel(projectId, modelId)
    if (activeProjectId.value === projectId && activeModelId.value === modelId) {
      beginNavigation()
      activeModelId.value = null
      activeVersionId.value = null
      activeRun.value = null
      selectedRun.value = null
      runHistory.value = []
    }
    await loadProjectDetail(projectId)
    await loadProjects()
  }

  const loadRecycleBin = async () => {
    recycleBinProjects.value = unwrap(await softwareIntegrationApi.listDeletedProjects()) || []
    return recycleBinProjects.value
  }

  const restoreProject = async projectId => {
    const detail = unwrap(await softwareIntegrationApi.restoreProject(projectId))
    setProjectDetail(detail)
    recycleBinProjects.value = recycleBinProjects.value.filter(project => project.id !== projectId)
    await loadProjects()
    return detail
  }

  const inspectModelArchive = async (projectId, file) =>
    unwrap(await softwareIntegrationApi.inspectModelArchive(projectId, file))

  const uploadModel = async (projectId, file, mainFile = null) => {
    const detail = unwrap(await softwareIntegrationApi.uploadModel(projectId, file, mainFile))
    setProjectDetail(detail)
    await loadProjects()
    scheduleValidationPolling()
    return projectDetails.value[projectId] || detail
  }

  const revalidateModel = async (projectId, versionId) => {
    const detail = unwrap(await softwareIntegrationApi.revalidateModel(projectId, versionId))
    setProjectDetail(detail)
    scheduleValidationPolling()
    return detail
  }

  const updateHistoryFromDetail = detail => {
    if (!detail || detail.modelVersionId !== activeVersionId.value) return
    const index = runHistory.value.findIndex(run => run.id === detail.id)
    const summary = {
      id: detail.id,
      projectId: detail.projectId,
      modelId: detail.modelId,
      modelVersionId: detail.modelVersionId,
      modelName: detail.modelName,
      versionNo: detail.versionNo,
      study: detail.study,
      runType: detail.runType,
      parameters: detail.parameters,
      status: detail.status,
      createdAt: detail.createdAt,
      queuedAt: detail.queuedAt,
      startedAt: detail.startedAt,
      finishedAt: detail.finishedAt,
      elapsedMillis: detail.elapsedMillis,
      cancellable: detail.cancellable
    }
    if (index >= 0) runHistory.value.splice(index, 1, summary)
    else runHistory.value.unshift(summary)
  }

  const startRunPolling = runId => {
    stopRunPolling()
    runPollingUnavailable.value = false
    const generation = runPollGeneration
    const expectedNavigation = navigationGeneration
    const expectedVersionId = activeVersionId.value

    let consecutiveFailures = 0
    const poll = async () => {
      try {
        const detail = unwrap(await softwareIntegrationApi.getRun(runId))
        if (generation !== runPollGeneration || !matchesRunContext(expectedNavigation, expectedVersionId) ||
          detail?.id !== runId || detail?.modelVersionId !== expectedVersionId) return
        activeRun.value = detail
        if (selectedRunId === runId || (!selectedRunId && (selectedRun.value?.id === runId || !selectedRun.value))) selectedRun.value = detail
        updateHistoryFromDetail(detail)
        syncElapsed(detail)
        if (isTerminalRunStatus(detail.status)) {
          if (acceptedRun.value?.id === runId) acceptedRun.value = null
          activeRun.value = null
          stopRunPolling()
          if (matchesRunContext(expectedNavigation, expectedVersionId)) {
            try {
              await loadRunHistory(detail.modelVersionId, false, expectedNavigation)
            } finally {
              if (matchesRunContext(expectedNavigation, expectedVersionId)) loadCapabilities().catch(() => {})
            }
          }
          return
        }
        consecutiveFailures = 0
      } catch {
        if (generation !== runPollGeneration) return
        consecutiveFailures += 1
        if (consecutiveFailures >= 3) {
          runPollingUnavailable.value = true
          stopRunPolling()
          return
        }
      }
      if (generation === runPollGeneration) runPollTimer = window.setTimeout(poll, 2000)
    }

    runPollTimer = window.setTimeout(poll, 1500)
  }

  const selectRun = async runId => {
    const requestGeneration = ++runDetailGeneration
    selectedRunId = runId
    // 在详情请求失败时，也允许重新选择已受理任务恢复轮询。
    if (activeRun.value?.id === runId) {
      startRunPolling(runId)
      syncElapsed(activeRun.value)
    }
    const expectedNavigation = navigationGeneration
    const expectedVersionId = activeVersionId.value
    const expectedPollGeneration = runPollGeneration
    const wasActiveRun = activeRun.value?.id === runId
    const detail = unwrap(await softwareIntegrationApi.getRun(runId))
    if (requestGeneration !== runDetailGeneration || !matchesRunContext(expectedNavigation, expectedVersionId) ||
      detail?.id !== runId || detail?.modelVersionId !== expectedVersionId) return null
    if ((wasActiveRun || !isTerminalRunStatus(detail.status)) && expectedPollGeneration !== runPollGeneration) return null
    selectedRun.value = detail
    if (isTerminalRunStatus(detail.status)) {
      const wasAcceptedRun = acceptedRun.value?.id === runId
      if (wasAcceptedRun) acceptedRun.value = null
      if (activeRun.value?.id === runId) {
        activeRun.value = null
        runPollingUnavailable.value = false
        stopRunPolling()
        loadCapabilities().catch(() => {})
      } else if (wasAcceptedRun) loadCapabilities().catch(() => {})
      if (!hasActiveRun.value) syncElapsed(detail)
    } else {
      activeRun.value = detail
      startRunPolling(detail.id)
      syncElapsed(detail)
    }
    return detail
  }

  const loadRunHistory = async (
    versionId = activeVersionId.value,
    selectLatest = true,
    expectedNavigation = navigationGeneration
  ) => {
    const requestGeneration = ++historyGeneration
    const expectedRunDetailGeneration = runDetailGeneration
    if (!versionId) {
      if (requestGeneration === historyGeneration && expectedNavigation === navigationGeneration) runHistory.value = []
      return []
    }
    loadingHistory.value = true
    try {
      const history = unwrap(await softwareIntegrationApi.listRuns(versionId, 50)) || []
      if (requestGeneration !== historyGeneration || !matchesRunContext(expectedNavigation, versionId)) return history
      runHistory.value = history
      const running = history.find(run => !isTerminalRunStatus(run.status))
      const selectedStillExists = history.some(run => run.id === selectedRun.value?.id)
      const target = running || (selectLatest && !selectedStillExists ? history[0] : null)
      if (target) {
        if (running && !hasActiveRun.value) {
          activeRun.value = running
          startRunPolling(running.id)
          syncElapsed(running)
        }
        const expectedPollGeneration = runPollGeneration
        const detail = unwrap(await softwareIntegrationApi.getRun(target.id))
        if (requestGeneration !== historyGeneration || expectedPollGeneration !== runPollGeneration ||
          !matchesRunContext(expectedNavigation, versionId) || detail?.id !== target.id ||
          detail?.modelVersionId !== versionId) return history
        if (running) trackRun(detail)
        else if (isTerminalRunStatus(detail.status) && acceptedRun.value?.id === detail.id) {
          acceptedRun.value = null
          loadCapabilities().catch(() => {})
        }
        if (expectedRunDetailGeneration === runDetailGeneration && !selectedRunId &&
          ((selectLatest && !selectedStillExists) || !selectedRun.value)) selectedRun.value = detail
      } else if (!running) {
        // 列表可能滞后于刚受理的 POST；活动生命周期由详情终态结束。
        if (!hasActiveRun.value) stopElapsedTicker()
        if (expectedRunDetailGeneration === runDetailGeneration && !selectedRunId && !selectedStillExists) selectedRun.value = null
      }
      return history
    } finally {
      if (requestGeneration === historyGeneration) loadingHistory.value = false
    }
  }

  const selectVersion = async versionId => {
    const generation = beginNavigation()
    return applyVersionSelection(versionId, generation)
  }

  const applyVersionSelection = async (versionId, generation) => {
    if (generation !== navigationGeneration) return null
    activeRun.value = null
    selectedRun.value = null
    runHistory.value = []
    activeVersionId.value = versionId
    syncRunTypeForModel()
    const studies = persistedStudies.value
    selectedStudy.value = isEclipseModel.value ? '' : (studies.includes(selectedStudy.value) ? selectedStudy.value : (studies[0] || ''))
    await loadRunHistory(versionId, true, generation)
    if (!matchesRunContext(generation, versionId)) return null
    return activeVersion.value
  }

  const activateModel = async (projectId, modelId) => {
    const generation = beginNavigation()
    const detail = projectDetails.value[projectId] || await loadProjectDetail(projectId)
    if (generation !== navigationGeneration) return null
    const model = detail?.models?.find(item => item.id === modelId)
    if (!model) return null
    activeProjectId.value = projectId
    activeModelId.value = modelId
    syncRunTypeForModel()
    const sorted = [...(model?.versions || [])].sort(byNewestVersion)
    const defaultVersion = sorted.find(version => version.status === 'READY') || sorted[0]
    return applyVersionSelection(defaultVersion?.id || null, generation)
  }

  const createRun = async (parameters = null) => {
    if (submittingRun.value || workerBusy.value) throw new Error('已有运行正在提交或执行')
    if (!canCreateRunByCapability.value) throw new Error('当前模拟器执行能力不可用')
    const version = activeVersion.value
    if (!version || version.status !== 'READY' || (!isEclipseModel.value && !persistedStudies.value.includes(selectedStudy.value))) {
      throw new Error('请选择 READY 模型版本及其已有 Study')
    }
    if (!isNetworkModel.value && !isWellModel.value && !isEclipseModel.value) throw new Error('模型版本缺少已验证类型，请重新验证')
    syncRunTypeForModel()
    const requestedRunType = isNetworkModel.value ? runType.value : (isEclipseModel.value ? 'eclipse' : runType.value)
    const expectedNavigation = navigationGeneration
    const expectedVersionId = version.id
    const detailRequestGeneration = ++runDetailGeneration
    historyGeneration += 1
    loadingHistory.value = false
    const study = isEclipseModel.value ? null : selectedStudy.value
    return submitRun(() => softwareIntegrationApi.createRun(version.id, study, requestedRunType, parameters),
      expectedNavigation, expectedVersionId, detailRequestGeneration)
  }

  const trackRun = (detail, refreshHistory = false) => {
    updateHistoryFromDetail(detail)
    if (selectedRunId === detail.id || (!selectedRunId && selectedRun.value?.id === detail.id)) selectedRun.value = detail
    if (isTerminalRunStatus(detail.status)) {
      if (acceptedRun.value?.id === detail.id) acceptedRun.value = null
      activeRun.value = null
      stopRunPolling()
      syncElapsed(detail)
      loadCapabilities().catch(() => {})
      if (refreshHistory) loadRunHistory(detail.modelVersionId, false, navigationGeneration).catch(() => {})
    } else {
      activeRun.value = detail
      startRunPolling(detail.id)
      syncElapsed(detail)
    }
  }

  const submitRun = async (post, expectedNavigation, expectedVersionId, detailRequestGeneration) => {
    submittingRun.value = true
    try {
      const summary = unwrap(await post())
      acceptedRun.value = isTerminalRunStatus(summary.status) ? null : summary
      if (capabilities.value?.worker?.status === 'AVAILABLE') capabilities.value.worker.idle = false
      if (!matchesRunContext(expectedNavigation, expectedVersionId) || summary?.modelVersionId !== expectedVersionId) return null
      if (detailRequestGeneration === runDetailGeneration) {
        selectedRunId = summary.id
        selectedRun.value = summary
      }
      trackRun(summary, true)
      const expectedPollGeneration = runPollGeneration
      try {
        const detail = unwrap(await softwareIntegrationApi.getRun(summary.id))
        if (!matchesRunContext(expectedNavigation, expectedVersionId) || expectedPollGeneration !== runPollGeneration ||
          detail?.id !== summary.id || detail?.modelVersionId !== expectedVersionId) return null
        trackRun(detail, true)
        return detail
      } catch {
        // POST 已成功，详情失败只交给轮询恢复，不能作为提交失败重发。
        return matchesRunContext(expectedNavigation, expectedVersionId) ? summary : null
      }
    } finally {
      submittingRun.value = false
    }
  }

  const cancelRun = async () => {
    if (cancellingRun.value || !activeRun.value?.cancellable) return null
    const runId = activeRun.value.id
    const expectedNavigation = navigationGeneration
    const expectedVersionId = activeVersionId.value
    stopRunPolling()
    cancellingRun.value = true
    try {
      const summary = unwrap(await softwareIntegrationApi.cancelRun(runId))
      if (!matchesRunContext(expectedNavigation, expectedVersionId) || activeRun.value?.id !== runId ||
        summary?.id !== runId || summary?.modelVersionId !== expectedVersionId) return null
      trackRun(summary, true)
      const detailPollGeneration = runPollGeneration
      const detail = unwrap(await softwareIntegrationApi.getRun(summary.id))
      if (detailPollGeneration !== runPollGeneration || !matchesRunContext(expectedNavigation, expectedVersionId) ||
        detail?.id !== runId || detail?.modelVersionId !== expectedVersionId) return null
      trackRun(detail, true)
      return detail
    } finally {
      if (matchesRunContext(expectedNavigation, expectedVersionId) && activeRun.value?.id === runId && !runPollTimer) {
        startRunPolling(runId)
        syncElapsed(activeRun.value)
      }
      cancellingRun.value = false
    }
  }

  const retryRun = async () => {
    if (submittingRun.value || workerBusy.value) throw new Error('已有运行正在提交或执行')
    const original = selectedRun.value
    if (!original || !['FAILED', 'TIMED_OUT', 'WORKER_LOST'].includes(original.status)) return null
    const expectedNavigation = navigationGeneration
    const expectedVersionId = activeVersionId.value
    const detailRequestGeneration = ++runDetailGeneration
    historyGeneration += 1
    loadingHistory.value = false
    return submitRun(() => softwareIntegrationApi.retryRun(original.id),
      expectedNavigation, expectedVersionId, detailRequestGeneration)
  }

  const refreshActiveProject = async () => {
    if (!activeProjectId.value) return null
    const detail = await loadProjectDetail(activeProjectId.value)
    scheduleValidationPolling()
    return detail
  }

  const cleanup = () => {
    capabilityGeneration += 1
    projectsLoadGeneration += 1
    navigationGeneration += 1
    historyGeneration += 1
    runDetailGeneration += 1
    validationPollingEnabled = false
    stopValidationPolling()
    stopRunPolling()
  }

  return {
    projects,
    recycleBinProjects,
    projectDetails,
    activeProjectId,
    activeModelId,
    activeVersionId,
    selectedStudy,
    runType,
    runHistory,
    selectedRun,
    activeRun,
    loadingProjects,
    loadingHistory,
    submittingRun,
    cancellingRun,
    runPollingUnavailable,
    capabilities,
    loadingCapabilities,
    capabilitiesUnavailable,
    activeProjectDetail,
    activeProject,
    activeModel,
    activeModelKind,
    isNetworkModel,
    isWellModel,
    hasNetworkCapability,
    isNetworkCapable,
    isEclipseModel,
    versions,
    readyVersions,
    activeVersion,
    persistedStudies,
    hasActiveRun,
    workerBusy,
    activeSimulatorCapability,
    activeSimulatorAvailable,
    activeRunTaskSupported,
    canCreateRunByCapability,
    activeElapsedMillis,
    loadProjects,
    loadCapabilities,
    loadProjectDetail,
    selectProject,
    createProject,
    deleteProject,
    deleteModel,
    loadRecycleBin,
    restoreProject,
    uploadModel,
    inspectModelArchive,
    revalidateModel,
    activateModel,
    selectVersion,
    loadRunHistory,
    selectRun,
    createRun,
    cancelRun,
    retryRun,
    refreshActiveProject,
    scheduleValidationPolling,
    cleanup
  }
})
