import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { pipelineCapacityApi as api } from '@/api/pipelineCapacity'
import { createPipelineInput, fingerprint, pipelineDrafts, statusLabels } from '@/utils/pipelineDefaults'
import { pipelinePageTitles, pipelineTemperaturePages, resolvePipelinePage } from '@/utils/pipelineNavigation'
import { pipelineSectionFields, normalizePipelineInput, sectionInput, reconcilePipelinePage } from '@/utils/pipelinePageState'
import { hydrateBoundary, boundaryTimeIssue } from '@/utils/pipelineBoundary'
import { pipelinePvtChanged } from '@/utils/pipelinePvtModel'
import { batchConstraintSummary, constraintDataRows, topologyEquipment } from '@/utils/pipelineConstraintResults'
import { boundaryComparisonRows } from '@/utils/pipelineBoundaryComparison'
import { flowThermalSourceChanged } from '@/utils/pipelineFlowThermal'
import { PIPELINE_BATCH_VERSION, resolveSavedNetworkTopology, batchInputMark, batchDataRows as makeBatchRows, batchChartSeries as makeBatchCharts, batchExportRows } from '@/utils/pipelineBatch'

export function usePipelineWorkspace(props) {
  const context = computed(() => ({ projectId: Number(props.projectId), gasReservoirId: Number(props.gasReservoirId), wellName: props.wellName || '' }))
  const temperaturePage = computed(() => pipelineTemperaturePages.find(page => page.section === activePage.value))
  const form = ref(createPipelineInput())
  const name = computed(() => props.wellName), revision = ref(0)
  const topologyRevision = ref(0), savedTopologyRevision = ref(0)
  const savedGraph = ref(null), topologyError = ref(''), topologyLoading = ref(false)
  const boundaryTopologyNotice = ref('')
  const gasPropertyConfig = ref(null), gasPropertyError = ref('')
  const thermalConfig = ref(null), thermalSourceError = ref(''), thermalSourceLoading = ref(false)
  const gasPropertyLoading = ref(false)
  let thermalLoadId = 0
  let boundaryLoadId = 0
  let gasLoadId = 0
  let topologyLoadId = 0
  const loaded = ref(false)
  const activePage = ref(resolvePipelinePage(props.initialSection)), panel = ref('data')
  const busy = ref(false), error = ref(''), storageError = ref(''), savedMark = ref('')
  const batchDetail = ref(null)
  const batchResult = computed(() => batchDetail.value?.result || null)
  const batchBusy = computed(() => busy.value || topologyLoading.value || gasPropertyLoading.value || (form.value.thermalMode === 'heat' && thermalSourceLoading.value))
  const batchError = computed(() => error.value)
  const batchStale = computed(() => !!batchDetail.value && (batchResult.value?.algorithmVersion !== PIPELINE_BATCH_VERSION
    || !!topologyError.value || !!gasPropertyError.value
    || batchInputMark(batchDetail.value.input, batchDetail.value.topologyRevision) !== batchInputMark(form.value, topologyRevision.value)
    || pipelinePvtChanged(gasPropertyConfig.value, batchDetail.value.input?.gasModel)
    || (form.value.thermalMode === 'heat' && (!!thermalSourceError.value
      || flowThermalSourceChanged(thermalConfig.value, batchDetail.value.input?.thermalModel, topologyRevision.value)))))
  const currentBatch = computed(() => !!batchResult.value && !batchStale.value && !batchBusy.value)
  const batchDataRows = computed(() => makeBatchRows(savedGraph.value, form.value, batchResult.value, currentBatch.value))
  const batchChartSeries = computed(() => makeBatchCharts(savedGraph.value, batchResult.value, currentBatch.value))
  const canBatchSave = computed(() => currentBatch.value && !!batchDetail.value.calculationToken && !batchDetail.value.id && batchResult.value.successCount > 0)
  const dirty = computed(() => savedTopologyRevision.value !== topologyRevision.value || savedMark.value !== fingerprint(form.value)
    || (!!batchDetail.value?.calculationToken && !batchDetail.value.id && !batchStale.value))
  const canCalculate = computed(() => loaded.value && !busy.value && !topologyLoading.value && !gasPropertyLoading.value && !!gasPropertyConfig.value
    && !!topologyRevision.value && !topologyError.value && !gasPropertyError.value
    && (form.value.thermalMode !== 'heat' || (!thermalSourceLoading.value && !!thermalConfig.value && !thermalSourceError.value))
    && !!form.value.boundary?.cases?.length)
  const tabTitle = computed(() => pipelinePageTitles[activePage.value])
  const f = (value, digits = 3) => value == null || !Number.isFinite(Number(value)) ? '—' : Number(value).toFixed(digits)
  const equipmentCatalog = computed(() => topologyEquipment(savedGraph.value))
  const equipmentRows = computed(() => constraintDataRows(batchDetail.value, 'equipment', currentBatch.value))
  const hydrateRows = computed(() => constraintDataRows(batchDetail.value, 'hydrate', currentBatch.value))
  const summaryChecks = computed(() => batchConstraintSummary(batchDetail.value, currentBatch.value))
  const comparisonRows = computed(() => boundaryComparisonRows(batchDetail.value, currentBatch.value))
  const batchSaved = computed(() => !!batchDetail.value?.id)
  const batchGasModel = computed(() => batchDetail.value?.input?.gasModel || null)
  const resultPages = ['flow', 'equipment', 'hydrate', 'constraints', 'comparison']
  const errorText = e => e?.response?.data?.msg || e?.msg || e?.message || '操作失败'

  async function openSection() {
    activePage.value = resolvePipelinePage(props.initialSection)
    error.value = ''
    if (loaded.value && [...resultPages, 'boundary'].includes(activePage.value)) {
      if (resultPages.includes(activePage.value) && !(await refreshBoundarySource())) return
      await loadTopology()
      if (resultPages.includes(activePage.value)) {
        await refreshThermalSource()
        if (batchResult.value && !batchStale.value) panel.value = 'analysis'
      }
    }
  }
  watch(() => [props.commandKey, props.initialSection], openSection)
  watch(activePage, () => { panel.value = resultPages.includes(activePage.value) && batchResult.value && !batchStale.value ? 'analysis' : 'data' })

  async function mayDiscard() {
    if (!dirty.value) return true
    try { await ElMessageBox.confirm('当前模型有未保存修改，重新加载将覆盖这些修改。', '重新加载', { confirmButtonText: '重新加载', cancelButtonText: '返回编辑' }); return true } catch { return false }
  }
  function resetState() {
    batchDetail.value = null
    error.value = ''
  }
  function acceptTopology(detail) {
    const state = resolveSavedNetworkTopology(detail, form.value)
    form.value = state.input; topologyRevision.value = state.topologyRevision
    savedGraph.value = state.graph; topologyError.value = state.error
    if (state.graph?.nodes?.length && state.topologyRevision) {
      if (form.value.boundary && form.value.boundary.topologyRevision !== state.topologyRevision) {
        boundaryTopologyNotice.value = '已保存拓扑发生变化，边界已按节点重新对应；请核对新增节点及失效记录后保存。'
      }
      form.value.boundary = hydrateBoundary(form.value, state.graph, state.topologyRevision)
    }
  }
  async function loadTopology({ allowNonSerial = false } = {}) {
    const loadId = ++topologyLoadId
    topologyLoading.value = true
    try {
      const { data } = await api.topology(context.value)
      if (loadId !== topologyLoadId) return false
      acceptTopology(data)
      return allowNonSerial ? !!(data?.revision && data?.graph?.nodes?.length) : !topologyError.value
    } catch (e) {
      if (loadId === topologyLoadId) topologyError.value = `已保存拓扑加载失败：${errorText(e)}`
      return false
    } finally { if (loadId === topologyLoadId) topologyLoading.value = false }
  }
  function topologySaved(detail) {
    ++topologyLoadId; topologyLoading.value = false
    acceptTopology(detail)
  }
  function refreshTopologyOnFocus() {
    if (loaded.value && !busy.value) {
      refreshGasProperties()
      refreshThermalSource()
      if (resultPages.includes(activePage.value)) refreshBoundarySource().then(ok => { if (ok) loadTopology() })
      else if (activePage.value === 'boundary') loadTopology()
    }
  }
  async function refreshBoundarySource() {
    const requestId = ++boundaryLoadId
    try {
      const { data } = await api.model(context.value)
      if (requestId !== boundaryLoadId) return false
      if ((data?.revision || 0) === revision.value) return true
      if (!data) { error.value = '已保存的管流模型已变化，请重新加载全部参数。'; return false }
      const values = input => {
        const section = sectionInput(input, 'boundary')
        if (section.boundary) section.boundary.activeCaseId = null
        return fingerprint(section)
      }
      const previous = savedMark.value ? JSON.parse(savedMark.value) : createPipelineInput()
      if (values(form.value) !== values(previous) && values(form.value) !== values(data.input)) {
        error.value = '边界条件已在其他页面更新，当前还有未保存的边界修改，请核对后重新加载全部参数。'
        return false
      }
      const selectedCase = form.value.boundary?.activeCaseId
      acceptPageSave(data, 'boundary')
      if (form.value.boundary?.cases?.some(row => row.id === selectedCase)) form.value.boundary.activeCaseId = selectedCase
      return true
    } catch (e) { if (requestId === boundaryLoadId) error.value = '边界条件加载失败：' + errorText(e); return false }
  }
  async function refreshGasProperties() {
    const requestId = ++gasLoadId
    gasPropertyLoading.value = true
    try {
      const { data } = await api.pvtModel(context.value)
      if (requestId !== gasLoadId) return false
      gasPropertyConfig.value = data || null
      gasPropertyError.value = !data ? '请先在管束能力 PVT 模型页保存当前井的完整气体组成。' : data.issue || ''
      return !gasPropertyError.value
    } catch (e) {
      if (requestId === gasLoadId) gasPropertyError.value = '物性模型加载失败：' + errorText(e)
      return false
    } finally { if (requestId === gasLoadId) gasPropertyLoading.value = false }
  }
  async function refreshThermalSource() {
    const requestId = ++thermalLoadId
    thermalSourceLoading.value = true
    try {
      const { data } = await api.temperature(context.value)
      if (requestId !== thermalLoadId) return false
      thermalConfig.value = data || null
      thermalSourceError.value = data?.revision ? '' : '请先在温度模型中填写并保存各管段的传热参数。'
      return !thermalSourceError.value
    } catch (e) {
      if (requestId === thermalLoadId) thermalSourceError.value = '温度模型加载失败：' + errorText(e)
      return false
    } finally { if (requestId === thermalLoadId) thermalSourceLoading.value = false }
  }
  watch(activePage, () => { if (loaded.value) refreshGasProperties() })
  async function loadModel(manual = false) {
    if (!props.wellName || (manual && !(await mayDiscard()))) return
    busy.value = true; storageError.value = ''
    try {
      const { data } = await api.model(context.value)
      if (!preserveDraft || manual) {
        form.value = normalizePipelineInput(data?.input); revision.value = data?.revision || 0
        topologyRevision.value = data?.topologyRevision || 0; savedTopologyRevision.value = topologyRevision.value
        resetState(); savedMark.value = fingerprint(form.value)
      }
      await loadTopology()
      await refreshGasProperties()
      await refreshThermalSource()
      if (!preserveDraft || manual) batchDetail.value = (await api.latestBatch(context.value)).data || null
      loaded.value = true
      if (resultPages.includes(activePage.value) && batchResult.value && !batchStale.value) panel.value = 'analysis'
    } catch (e) { storageError.value = errorText(e) } finally { busy.value = false }
  }
  async function calculate() {
    busy.value = true; error.value = ''
    try {
      if (!(await refreshBoundarySource())) return
      if (!(await refreshGasProperties())) { error.value = gasPropertyError.value; return }
      if (!(await loadTopology())) { error.value = topologyError.value; return }
      if (form.value.thermalMode === 'heat' && !(await refreshThermalSource())) { error.value = thermalSourceError.value; return }
      const { data } = await api.calculateBatch({ ...context.value, revision: revision.value,
        topologyRevision: topologyRevision.value, input: JSON.parse(fingerprint(form.value)) })
      batchDetail.value = data; form.value = normalizePipelineInput(data.input)
      panel.value = data.result.successCount > 0 ? 'analysis' : 'data'
      ElMessage.success(`全部工况已处理：成功 ${data.result.successCount} 组，失败 ${data.result.failureCount} 组`)
    } catch (e) { error.value = errorText(e) } finally { busy.value = false }
  }
  async function save() {
    if (!canBatchSave.value) { error.value = '请先计算全部工况，再保存有效的本批结果。'; return }
    busy.value = true; error.value = ''
    try {
      if (!(await refreshBoundarySource())) return
      if (!(await refreshGasProperties())) { error.value = gasPropertyError.value; return }
      if (!(await loadTopology())) { error.value = topologyError.value; return }
      if (form.value.thermalMode === 'heat' && !(await refreshThermalSource())) { error.value = thermalSourceError.value; return }
      if (batchStale.value) { error.value = '计算参数或数据来源已变化，请重新计算全部工况后保存。'; return }
      const { data } = await api.saveBatch({ ...context.value, calculationToken: batchDetail.value.calculationToken })
      batchDetail.value = data; form.value = normalizePipelineInput(data.input); revision.value = data.revision
      savedMark.value = fingerprint(form.value); savedTopologyRevision.value = data.topologyRevision
      panel.value = 'analysis'; ElMessage.success('全部工况参数及本批计算结果已保存')
    } catch (e) { error.value = errorText(e) } finally { busy.value = false }
  }
  function exportResult() {
    if (!currentBatch.value) return
    const rows = batchExportRows(batchDetail.value.graph, batchDetail.value.input, batchResult.value, true)
    const csvCell = value => {
      // Keep negative scientific values numeric; neutralize formulas only in user-entered text.
      const text = typeof value === 'number' ? String(value) : String(value).replace(/^[=+@-]/, "'$&")
      return `"${text.replace(/"/g, '""')}"`
    }
    const csv = '\ufeff' + rows.map(row => row.map(csvCell).join(',')).join('\r\n')
    const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' })); const a = document.createElement('a'); a.href = url; a.download = `${name.value}-全工况管流结果.csv`; a.click(); URL.revokeObjectURL(url)
  }
  const cacheKey = fingerprint(context.value)
  const draftFields = { form, revision, panel, savedMark, topologyRevision, savedTopologyRevision, batchDetail }
  const draft = pipelineDrafts.get(cacheKey)
  const preserveDraft = draft && (fingerprint(draft.form) !== draft.savedMark || draft.topologyRevision !== draft.savedTopologyRevision || (draft.batchDetail?.calculationToken && !draft.batchDetail.id))
  if (draft) for (const [key, state] of Object.entries(draftFields)) if (key in draft) state.value = draft[key]
  function protectReload(event) {
    if (dirty.value && props.wellName) { event.preventDefault(); event.returnValue = '' }
  }
  onMounted(() => { openSection(); loadModel(); window.addEventListener('beforeunload', protectReload); window.addEventListener('focus', refreshTopologyOnFocus) })
  onBeforeUnmount(() => {
    if (loaded.value) pipelineDrafts.set(cacheKey, JSON.parse(JSON.stringify(Object.fromEntries(Object.entries(draftFields).map(([key, state]) => [key, state.value])))))
    window.removeEventListener('beforeunload', protectReload)
    window.removeEventListener('focus', refreshTopologyOnFocus); ++topologyLoadId; ++gasLoadId; ++thermalLoadId; ++boundaryLoadId
  })

  const pageDirty = computed(() => {
    if (!pipelineSectionFields[activePage.value]) return ['flow', 'hydrate'].includes(activePage.value) && dirty.value
    const savedInput = savedMark.value ? JSON.parse(savedMark.value) : createPipelineInput()
    return fingerprint(sectionInput(form.value, activePage.value)) !== fingerprint(sectionInput(savedInput, activePage.value))
  })
  function acceptPageSave(data, page) {
    const previousSaved = savedMark.value ? JSON.parse(savedMark.value) : createPipelineInput()
    form.value = reconcilePipelinePage(form.value, previousSaved, data?.input, page)
    if (page === 'boundary' && savedGraph.value?.nodes?.length && topologyRevision.value) {
      form.value.boundary = hydrateBoundary(form.value, savedGraph.value, topologyRevision.value)
    }
    revision.value = data?.revision || 0; savedMark.value = fingerprint(normalizePipelineInput(data?.input)); savedTopologyRevision.value = data?.topologyRevision || 0
  }
  async function reloadPage() {
    const page = activePage.value
    if (resultPages.includes(page)) return loadModel(true)
    if (!pipelineSectionFields[page]) { await loadTopology(); return }
    if (pageDirty.value) {
      try { await ElMessageBox.confirm(`重新加载会覆盖${pipelinePageTitles[page]}的未保存修改。`, '重新加载当前页面', { confirmButtonText: '重新加载', cancelButtonText: '返回编辑' }) } catch { return }
    }
    busy.value = true; error.value = ''
    try {
      const { data } = await api.model(context.value)
      acceptPageSave(data, page)
      if (page === 'boundary') { boundaryTopologyNotice.value = ''; await loadTopology({ allowNonSerial: true }) }
    } catch (e) { error.value = errorText(e) } finally { busy.value = false }
  }
  async function savePage() {
    const page = activePage.value
    if (['flow', 'hydrate'].includes(page)) return save()
    if (!pipelineSectionFields[page]) return
    busy.value = true; error.value = ''
    try {
      if (page === 'boundary') {
        const issue = boundaryTimeIssue(form.value.boundary)
        if (issue) { error.value = issue; return }
      }
      if (page === 'boundary' && !(await loadTopology({ allowNonSerial: true }))) { error.value = topologyError.value; return }
      const { data } = await api.saveSection(page, { ...context.value, revision: revision.value, input: form.value })
      acceptPageSave(data, page)
      if (page === 'boundary') boundaryTopologyNotice.value = ''
      ElMessage.success(pipelinePageTitles[page] + '已保存')
    } catch (e) { error.value = errorText(e) } finally { busy.value = false }
  }

  return reactive({ context, temperaturePage, form, name, revision, topologyRevision, savedTopologyRevision,
    gasPropertyConfig, gasPropertyError, refreshGasProperties, thermalConfig, thermalSourceError, thermalSourceLoading, refreshThermalSource,
    batchResult, batchStale, batchError, batchBusy, batchDataRows, batchChartSeries, canBatchSave,
    batchSaved, batchGasModel, equipmentCatalog, equipmentRows, hydrateRows, comparisonRows,
    savedGraph, topologyError, topologyLoading, boundaryTopologyNotice, loaded, activePage, panel,
    busy, error, storageError, savedMark, dirty, pageDirty, canCalculate, tabTitle, f, summaryChecks,
    mayDiscard, topologySaved, loadTopology, loadModel, calculate, save, savePage, reloadPage, exportResult, statusLabels })
}
