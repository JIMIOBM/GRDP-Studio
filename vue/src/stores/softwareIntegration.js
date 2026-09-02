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
export const isTerminalRunStatus = status => terminalStatuses.has(status)
const isValidationPending = status => status === 'UPLOADED' || status === 'VALIDATING'
const unwrap = response => response?.data ?? response
const byNewestVersion = (left, right) => Number(right.versionNo || 0) - Number(left.versionNo || 0)

export const useSoftwareIntegrationStore = defineStore('software-integration', () => {
  const projects = ref([])
  const projectDetails = ref({})
  const activeProjectId = ref(null)
  const activeModelId = ref(null)
  const activeVersionId = ref(null)
  const selectedStudy = ref('')
  const runType = ref('nodal')
  const runHistory = ref([])
  const selectedRun = ref(null)
  const activeRun = ref(null)
  const loadingProjects = ref(false)
  const loadingHistory = ref(false)
  const submittingRun = ref(false)
  const cancellingRun = ref(false)
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

  const activeProjectDetail = computed(() => projectDetails.value[activeProjectId.value] || null)
  const activeProject = computed(() => activeProjectDetail.value?.project ||
    projects.value.find(project => project.id === activeProjectId.value) || null)
  const activeModel = computed(() => activeProjectDetail.value?.models?.find(model => model.id === activeModelId.value) || null)
  const versions = computed(() => [...(activeModel.value?.versions || [])].sort(byNewestVersion))
  const readyVersions = computed(() => versions.value.filter(version => version.status === 'READY'))
  const activeVersion = computed(() => versions.value.find(version => version.id === activeVersionId.value) || null)
  const persistedStudies = computed(() => activeVersion.value?.status === 'READY' && Array.isArray(activeVersion.value.studies)
    ? activeVersion.value.studies
    : [])
  const hasActiveRun = computed(() => Boolean(activeRun.value && !isTerminalRunStatus(activeRun.value.status)))
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
    invalidateRunRequests()
    return navigationGeneration
  }

  const matchesRunContext = (generation, versionId) =>
    generation === navigationGeneration && activeVersionId.value === versionId

  const setProjectDetail = detail => {
    const projectId = detail?.project?.id
    if (!projectId) return
    projectDetails.value = { ...projectDetails.value, [projectId]: detail }
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
      scheduleValidationPolling()
      return details
    } finally {
      if (requestGeneration === projectsLoadGeneration) loadingProjects.value = false
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

  const uploadModel = async (projectId, file) => {
    const detail = unwrap(await softwareIntegrationApi.uploadModel(projectId, file))
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
    const generation = runPollGeneration
    const expectedNavigation = navigationGeneration
    const expectedVersionId = activeVersionId.value

    const poll = async () => {
      try {
        const detail = unwrap(await softwareIntegrationApi.getRun(runId))
        if (generation !== runPollGeneration || !matchesRunContext(expectedNavigation, expectedVersionId) ||
          detail?.id !== runId || detail?.modelVersionId !== expectedVersionId) return
        activeRun.value = detail
        if (selectedRun.value?.id === runId || !selectedRun.value) selectedRun.value = detail
        updateHistoryFromDetail(detail)
        syncElapsed(detail)
        if (isTerminalRunStatus(detail.status)) {
          activeRun.value = null
          stopRunPolling()
          if (matchesRunContext(expectedNavigation, expectedVersionId)) {
            await loadRunHistory(detail.modelVersionId, false, expectedNavigation)
          }
          return
        }
      } catch {
        if (generation !== runPollGeneration) return
      }
      if (generation === runPollGeneration) runPollTimer = window.setTimeout(poll, 2000)
    }

    runPollTimer = window.setTimeout(poll, 1500)
  }

  const selectRun = async runId => {
    const requestGeneration = ++runDetailGeneration
    const expectedNavigation = navigationGeneration
    const expectedVersionId = activeVersionId.value
    const detail = unwrap(await softwareIntegrationApi.getRun(runId))
    if (requestGeneration !== runDetailGeneration || !matchesRunContext(expectedNavigation, expectedVersionId) ||
      detail?.id !== runId || detail?.modelVersionId !== expectedVersionId) return null
    selectedRun.value = detail
    if (!isTerminalRunStatus(detail.status)) {
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
      if (expectedRunDetailGeneration !== runDetailGeneration) return history
      if (target) {
        const detail = unwrap(await softwareIntegrationApi.getRun(target.id))
        if (requestGeneration !== historyGeneration || expectedRunDetailGeneration !== runDetailGeneration ||
          !matchesRunContext(expectedNavigation, versionId) || detail?.id !== target.id ||
          detail?.modelVersionId !== versionId) return history
        if (running) {
          activeRun.value = detail
          startRunPolling(detail.id)
          syncElapsed(detail)
        }
        if (selectLatest || !selectedRun.value) selectedRun.value = detail
      } else if (!running) {
        activeRun.value = null
        stopElapsedTicker()
        if (!selectedStillExists) selectedRun.value = null
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
    const studies = persistedStudies.value
    selectedStudy.value = studies.includes(selectedStudy.value) ? selectedStudy.value : (studies[0] || '')
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
    const sorted = [...(model?.versions || [])].sort(byNewestVersion)
    const defaultVersion = sorted.find(version => version.status === 'READY') || sorted[0]
    return applyVersionSelection(defaultVersion?.id || null, generation)
  }

  const createRun = async () => {
    const version = activeVersion.value
    if (!version || version.status !== 'READY' || !persistedStudies.value.includes(selectedStudy.value)) {
      throw new Error('请选择 READY 模型版本及其已有 Study')
    }
    const expectedNavigation = navigationGeneration
    const expectedProjectId = activeProjectId.value
    const expectedModelId = activeModelId.value
    const expectedVersionId = version.id
    const detailRequestGeneration = ++runDetailGeneration
    historyGeneration += 1
    loadingHistory.value = false
    submittingRun.value = true
    try {
      const summary = unwrap(await softwareIntegrationApi.createRun(version.id, selectedStudy.value, runType.value))
      if (detailRequestGeneration !== runDetailGeneration || expectedNavigation !== navigationGeneration ||
        activeProjectId.value !== expectedProjectId || activeModelId.value !== expectedModelId ||
        activeVersionId.value !== expectedVersionId || summary?.modelVersionId !== expectedVersionId) return null
      runHistory.value = [summary, ...runHistory.value.filter(run => run.id !== summary.id)]
      const detail = unwrap(await softwareIntegrationApi.getRun(summary.id))
      if (detailRequestGeneration !== runDetailGeneration || !matchesRunContext(expectedNavigation, expectedVersionId) ||
        activeProjectId.value !== expectedProjectId || activeModelId.value !== expectedModelId ||
        detail?.id !== summary.id || detail?.modelVersionId !== expectedVersionId) return null
      activeRun.value = detail
      selectedRun.value = detail
      startRunPolling(detail.id)
      syncElapsed(detail)
      return detail
    } finally {
      submittingRun.value = false
    }
  }

  const cancelRun = async () => {
    if (!activeRun.value?.cancellable) return null
    const runId = activeRun.value.id
    const expectedNavigation = navigationGeneration
    const expectedVersionId = activeVersionId.value
    const detailRequestGeneration = ++runDetailGeneration
    cancellingRun.value = true
    try {
      const summary = unwrap(await softwareIntegrationApi.cancelRun(runId))
      if (detailRequestGeneration !== runDetailGeneration || !matchesRunContext(expectedNavigation, expectedVersionId) ||
        summary?.id !== runId || summary?.modelVersionId !== expectedVersionId) return null
      updateHistoryFromDetail(summary)
      const detail = unwrap(await softwareIntegrationApi.getRun(summary.id))
      if (detailRequestGeneration !== runDetailGeneration || !matchesRunContext(expectedNavigation, expectedVersionId) ||
        detail?.id !== runId || detail?.modelVersionId !== expectedVersionId) return null
      activeRun.value = isTerminalRunStatus(detail.status) ? null : detail
      if (selectedRun.value?.id === detail.id) selectedRun.value = detail
      if (isTerminalRunStatus(detail.status)) {
        stopRunPolling()
        syncElapsed(detail)
      } else {
        startRunPolling(detail.id)
        syncElapsed(detail)
      }
      return detail
    } finally {
      cancellingRun.value = false
    }
  }

  const refreshActiveProject = async () => {
    if (!activeProjectId.value) return null
    const detail = await loadProjectDetail(activeProjectId.value)
    scheduleValidationPolling()
    return detail
  }

  const cleanup = () => {
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
    activeProjectDetail,
    activeProject,
    activeModel,
    versions,
    readyVersions,
    activeVersion,
    persistedStudies,
    hasActiveRun,
    activeElapsedMillis,
    loadProjects,
    loadProjectDetail,
    selectProject,
    createProject,
    deleteProject,
    uploadModel,
    revalidateModel,
    activateModel,
    selectVersion,
    loadRunHistory,
    selectRun,
    createRun,
    cancelRun,
    refreshActiveProject,
    scheduleValidationPolling,
    cleanup
  }
})
