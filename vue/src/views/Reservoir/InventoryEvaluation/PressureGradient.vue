<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import { Document } from '@element-plus/icons-vue'
import { dataManagementApi } from '@/api/docker'
import { storageCatalogApi } from '@/api/storageCatalog'
import { storagePressureGradientApi } from '@/api/storagePressureGradient'
import { buildPressureGradientComparison, calculateDatumPressure, calculatePressureIntercept, isCalculablePressureRecord, normalizePressureRecord, pressureRecordDate, pressureSourceKeys } from '@/utils/formationPressureGradient'
import { cleanPressureGradientImportRows, mergePressureGradientImportRows, parsePressureGradientRows, pressureGradientImportColumns } from '@/utils/pressureGradientImport'
import { assertNoSpreadsheetFormulaCells } from '@/utils/spreadsheetImport'

const props = defineProps({ reservoir: { type: Object, default: null } })
const wells = ref([])
const rows = ref([])
const selectedWells = ref([])
const referenceCoordinate = ref('2000')
const coordinateMode = ref('depth')
const dateRange = ref('recent3')
const chartAxis = ref('well')
const activeTab = ref('chart')
const loading = ref(false)
const loadError = ref('')
const ready = ref(false)
const databaseLoaded = ref(false)
const chartElement = ref(null)
const workspaceElement = ref(null)
const importFileInput = ref(null)
const importDialogVisible = ref(false)
const importDragging = ref(false)
const selectedImportFile = ref(null)
const importingSpreadsheet = ref(false)
const importOptions = ref({ removeEmptyRows: false, removeEmptyColumns: false, removeZeroRows: false })
const databaseSaveState = ref('saved')
let chart = null
let resizeObserver = null
let loadVersion = 0
let resizeFrame = 0
let saveTimer = 0
let saveVersion = 0
let saveQueue = Promise.resolve()

const responseData = response => response?.data?.data ?? response?.data ?? response
const responseRows = response => {
  const payload = responseData(response)
  if (Array.isArray(payload)) return payload
  if (Array.isArray(payload?.items)) return payload.items
  if (Array.isArray(payload?.rows)) return payload.rows
  return []
}
const errorMessage = error => error?.response?.data?.msg || error?.response?.data?.message || error?.message || '读取数据失败'
const createRowId = prefix => `${prefix}:${globalThis.crypto?.randomUUID?.() || `${Date.now()}:${Math.random().toString(36).slice(2, 8)}`}`
const storageScope = computed(() => [props.reservoir?.projectId, props.reservoir?.gasReservoirId, props.reservoir?.storageId].map(value => value ?? 'none').join(':'))
const draftKey = computed(() => `grdp:formation-pressure-gradient:${storageScope.value}`)
const selectedWellSet = computed(() => new Set(selectedWells.value.map(String)))
const referenceValue = computed(() => Number(referenceCoordinate.value))
const format = value => Number.isFinite(value) ? value.toLocaleString('zh-CN', { maximumFractionDigits: 4 }) : '—'
const depthLabel = computed(() => coordinateMode.value === 'elevation' ? '海拔高程' : '测压垂深')
const formulaText = computed(() => coordinateMode.value === 'elevation'
  ? 'P基准 = P实测 + G × (E测压 − E基准)'
  : 'P基准 = P实测 + G × (H基准 − H测压)')
const interceptText = computed(() => coordinateMode.value === 'elevation'
  ? 'P₀ = P实测 + G × E测压'
  : 'P₀ = P实测 − G × H测压')

const rowPressure = row => calculateDatumPressure({
  measuredPressure: row.measuredPressure,
  measuredCoordinate: row.measuredCoordinate,
  gradient: row.gradient,
  referenceCoordinate: referenceValue.value,
  coordinateMode: coordinateMode.value
})
const rowIntercept = row => calculatePressureIntercept({
  measuredPressure: row.measuredPressure,
  measuredCoordinate: row.measuredCoordinate,
  gradient: row.gradient,
  coordinateMode: coordinateMode.value
})
const isRowCalculable = row => isCalculablePressureRecord(row, referenceCoordinate.value, coordinateMode.value)
const validRows = computed(() => rows.value.filter(isRowCalculable))
const selectedRows = computed(() => rows.value.filter(row => selectedWellSet.value.has(String(row.wellName)) && row.deleted !== true))
const calculableSelectedRows = computed(() => validRows.value.filter(row => selectedWellSet.value.has(String(row.wellName))))
const missingSelectedCount = computed(() => Math.max(0, selectedRows.value.length - calculableSelectedRows.value.length))
const chartWells = computed(() => wells.value.map(well => well.wellName).filter(name => selectedWellSet.value.has(String(name))))
const chartModel = computed(() => buildPressureGradientComparison({
  rows: rows.value,
  selectedWells: chartWells.value,
  referenceCoordinate: referenceCoordinate.value,
  coordinateMode: coordinateMode.value,
  dateRange: dateRange.value,
  chartAxis: chartAxis.value
}))
const chartRows = computed(() => chartModel.value.validRows)
const chartHasData = computed(() => chartModel.value.series.some(series => series.data.some(Number.isFinite)))
const displayedRows = computed(() => rows.value.filter(row => row.deleted !== true))
const displayedValidCount = computed(() => displayedRows.value.filter(isRowCalculable).length)
const displayedMissingCount = computed(() => displayedRows.value.length - displayedValidCount.value)
const selectionCountText = computed(() => `已选 ${selectedWells.value.length} / ${wells.value.length} 口井 · ${calculableSelectedRows.value.length} 个有效测点 · ${missingSelectedCount.value} 行待补`)
const databaseSaveLabel = computed(() => ({
  saving: '正在保存到数据库…',
  failed: '数据库保存失败，本机草稿已保留'
}[databaseSaveState.value] || '数据库已保存'))

const readDraft = () => {
  try { return JSON.parse(localStorage.getItem(draftKey.value) || 'null') || {} } catch { return {} }
}
const saveDraft = pendingDatabaseSave => {
  if (!ready.value) return
  try {
    const previousDraft = readDraft()
    localStorage.setItem(draftKey.value, JSON.stringify({
      version: 1,
      pendingDatabaseSave: typeof pendingDatabaseSave === 'boolean'
        ? pendingDatabaseSave : previousDraft.pendingDatabaseSave === true,
      referenceCoordinate: referenceCoordinate.value,
      coordinateMode: coordinateMode.value,
      dateRange: dateRange.value,
      chartAxis: chartAxis.value,
      selectedWells: [...selectedWells.value],
      rows: rows.value
    }))
  } catch (error) {
    console.warn('压力梯度参数暂未能保存到本机浏览器', error)
  }
}

const numericValue = (value, label) => {
  if (value === null || value === undefined || String(value).trim() === '') return null
  const number = Number(value)
  if (!Number.isFinite(number)) throw new Error(`${label}必须是有效数字`)
  return number
}
const databaseRows = () => rows.value.map((row, index) => {
  const line = index + 1
  const measuredPressure = numericValue(row.measuredPressure, `第 ${line} 行实测静压`)
  const measuredCoordinate = numericValue(row.measuredCoordinate, `第 ${line} 行测压坐标`)
  const gradient = numericValue(row.gradient, `第 ${line} 行压力梯度`)
  if (measuredPressure !== null && measuredPressure < 0) throw new Error(`第 ${line} 行实测静压不能为负数`)
  if (gradient !== null && gradient < 0) throw new Error(`第 ${line} 行压力梯度不能为负数`)
  if (coordinateMode.value === 'depth' && measuredCoordinate !== null && measuredCoordinate < 0) {
    throw new Error(`第 ${line} 行测压垂深不能为负数`)
  }
  if (row.date && pressureRecordDate({ date: row.date }) !== row.date) throw new Error(`第 ${line} 行日期无效`)
  return {
    id: String(row.id || row.sourceKey || `manual:${Date.now()}:${Math.random().toString(36).slice(2, 8)}`),
    sourceKey: row.sourceKey || null,
    wellName: String(row.wellName || ''),
    date: row.date || null,
    measuredPressure,
    measuredCoordinate,
    gradient,
    manual: row.manual === true,
    deleted: row.deleted === true
  }
})
const persistToDatabase = (notify = false) => {
  if (!ready.value) return Promise.resolve()
  if (saveTimer) { clearTimeout(saveTimer); saveTimer = 0 }
  if (!databaseLoaded.value) {
    databaseSaveState.value = 'failed'
    if (notify) ElMessage.warning('先成功读取数据库数据，再保存以免覆盖已有测点')
    return Promise.reject(new Error('数据库读取未完成，保存已阻止'))
  }
  const version = ++saveVersion
  let snapshot
  try { snapshot = databaseRows() } catch (error) {
    databaseSaveState.value = 'failed'
    if (notify) ElMessage.error(error.message || '测点数据无效，未保存到数据库')
    return Promise.reject(error)
  }
  const scope = { projectId: props.reservoir?.projectId, gasReservoirId: props.reservoir?.gasReservoirId, storageId: props.reservoir?.storageId }
  databaseSaveState.value = 'saving'
  saveDraft(true)
  const task = saveQueue.catch(() => {}).then(() => storagePressureGradientApi.save(scope, {
    rows: snapshot,
    referenceCoordinate: numericValue(referenceCoordinate.value, '统一基准坐标'),
    coordinateMode: coordinateMode.value
  }))
  saveQueue = task
  return task.then(() => {
    if (version === saveVersion) {
      databaseSaveState.value = 'saved'
      saveDraft(false)
    }
    if (notify) ElMessage.success(`已保存 ${snapshot.length} 个测点到数据库`)
  }).catch(error => {
    if (version === saveVersion) databaseSaveState.value = 'failed'
    if (notify) ElMessage.error(error?.response?.data?.msg || error?.response?.data?.message || error?.message || '数据库保存失败，本机草稿已保留')
    throw error
  })
}
const scheduleDatabaseSave = () => {
  if (!ready.value) { saveDraft(); return }
  saveVersion++
  saveDraft(true)
  databaseSaveState.value = 'saving'
  if (saveTimer) clearTimeout(saveTimer)
  saveTimer = window.setTimeout(() => { saveTimer = 0; persistToDatabase().catch(() => {}) }, 900)
}
const saveExplicitly = () => persistToDatabase(true).catch(() => {})

const loadData = async () => {
  const version = ++loadVersion
  if (saveTimer) { clearTimeout(saveTimer); saveTimer = 0 }
  ready.value = false
  loading.value = true
  databaseLoaded.value = false
  loadError.value = ''
  const draft = readDraft()
  let storedRows = null
  let storageReadError = ''
  referenceCoordinate.value = draft.referenceCoordinate ?? '2000'
  coordinateMode.value = draft.coordinateMode === 'elevation' ? 'elevation' : 'depth'
  dateRange.value = draft.dateRange === 'all' ? 'all' : 'recent3'
  chartAxis.value = draft.chartAxis === 'time' ? 'time' : 'well'
  try {
    const { projectId, gasReservoirId, storageId } = props.reservoir || {}
    if (![projectId, gasReservoirId, storageId].every(value => Number(value) > 0)) throw new Error('请先打开一个具体储气库')
    const wellResponse = await storageCatalogApi.wells(storageId, projectId, gasReservoirId)
    if (version !== loadVersion) return
    const wellPayload = responseData(wellResponse)
    if (!Array.isArray(wellPayload)) throw new Error('当前储气库井列表返回格式不正确')
    wells.value = wellPayload
      .map(well => ({ id: Number(well.id), wellName: String(well.wellName || '').trim() }))
      .filter(well => well.wellName)
    try {
      const storedPayload = responseData(await storagePressureGradientApi.list({ projectId, gasReservoirId, storageId }))
      if (!storedPayload || (!Array.isArray(storedPayload) && !Array.isArray(storedPayload.rows))) throw new Error('数据库测点返回格式不正确')
      databaseLoaded.value = true
      databaseSaveState.value = 'saved'
      const savedSettings = storedPayload && !Array.isArray(storedPayload) ? storedPayload : null
      if (draft.pendingDatabaseSave !== true && Number.isFinite(Number(savedSettings?.referenceCoordinate))) referenceCoordinate.value = String(savedSettings.referenceCoordinate)
      if (draft.pendingDatabaseSave !== true && ['depth', 'elevation'].includes(savedSettings?.coordinateMode)) coordinateMode.value = savedSettings.coordinateMode
      const sourceStoredRows = Array.isArray(savedSettings?.rows) ? savedSettings.rows : Array.isArray(storedPayload) ? storedPayload : []
      storedRows = sourceStoredRows.map(row => ({
        ...row,
        measuredPressure: row.measuredPressure == null ? '' : String(row.measuredPressure),
        measuredCoordinate: row.measuredCoordinate == null ? '' : String(row.measuredCoordinate),
        gradient: row.gradient == null ? '' : String(row.gradient),
        date: row.date || ''
      }))
    } catch (error) {
      storageReadError = `数据库测点读取失败：${errorMessage(error)}`
      storedRows = Array.isArray(draft.rows) ? draft.rows : []
      databaseSaveState.value = 'failed'
    }
    if (draft.pendingDatabaseSave === true && Array.isArray(draft.rows)) storedRows = draft.rows
    const validNames = new Set(wells.value.map(well => well.wellName))
    const hasSavedSelection = Array.isArray(draft.selectedWells)
    const savedSelection = hasSavedSelection ? draft.selectedWells.map(String).filter(name => validNames.has(name)) : []
    selectedWells.value = hasSavedSelection ? savedSelection : wells.value.map(well => well.wellName)

    const pressureResponses = await Promise.allSettled(wells.value.map(well =>
      dataManagementApi.getStaticPressure(projectId, gasReservoirId, well.wellName, { silentError: true })
    ))
    if (version !== loadVersion) return
    const failures = pressureResponses.filter(result => result.status === 'rejected')
    const savedRows = Array.isArray(storedRows) ? storedRows : []
    const savedBySource = new Map(savedRows.filter(row => row.sourceKey).map(row => [row.sourceKey, row]))
    const fetchedRows = []
    pressureResponses.forEach((result, wellIndex) => {
      if (result.status !== 'fulfilled') return
      const wellName = wells.value[wellIndex].wellName
      const sourceRows = responseRows(result.value)
      sourceRows.forEach((item, index) => {
        const keys = pressureSourceKeys(item, wellName, index)
        const normalized = normalizePressureRecord(item, wellName, keys.sourceKey)
        const saved = savedBySource.get(keys.sourceKey) || savedBySource.get(keys.legacySourceKey)
        fetchedRows.push({ ...normalized, ...(saved || {}), id: normalized.id, sourceKey: normalized.sourceKey })
      })
    })
    const fetchedKeys = new Set(fetchedRows.map(row => row.sourceKey))
    const savedManualRows = savedRows.filter(row => row.manual === true)
    const failedWellNames = new Set(pressureResponses.flatMap((result, index) =>
      result.status === 'rejected' ? [wells.value[index].wellName] : []))
    // Keep locally completed values for wells whose source request failed; do not silently lose edits.
    const savedFailedRows = savedRows.filter(row => row.sourceKey && failedWellNames.has(String(row.wellName)))
    rows.value = [...fetchedRows, ...savedFailedRows, ...savedManualRows]
    if (!wells.value.length) {
      rows.value = savedManualRows
      loadError.value = '当前储气库没有成员井'
    } else if (failures.length === pressureResponses.length && pressureResponses.length > 0) {
      loadError.value = '实测静压读取失败；已保留本机补录数据，可重试或新增测点'
    } else if (failures.length > 0) {
      loadError.value = `${failures.length} 口井的实测静压读取失败；已保留这些井的本机数据，其余数据已读取`
    } else if (fetchedRows.length === 0 && savedManualRows.length === 0) {
      loadError.value = '当前库暂无实测静压；可导入或新增测点'
    }
    if (storageReadError) loadError.value = [loadError.value, storageReadError].filter(Boolean).join('；')
    if (draft.pendingDatabaseSave === true) {
      databaseSaveState.value = 'failed'
      loadError.value = [loadError.value, '已恢复本机未保存的测点修改，请核对后保存'].filter(Boolean).join('；')
    }
    // Keep deleted source rows as tombstones so a refresh does not bring them back.
    const deletedDrafts = savedRows.filter(row => row.deleted === true && row.sourceKey && !fetchedKeys.has(row.sourceKey))
    if (deletedDrafts.length) rows.value.push(...deletedDrafts)
  } catch (error) {
    if (version === loadVersion) {
      loadError.value = errorMessage(error)
      rows.value = Array.isArray(draft.rows) ? draft.rows : []
      wells.value = [...new Set(rows.value.map(row => row.wellName).filter(Boolean))].map(wellName => ({ id: null, wellName }))
      const hasSavedSelection = Array.isArray(draft.selectedWells)
      const savedSelection = hasSavedSelection ? draft.selectedWells.map(String) : []
      selectedWells.value = hasSavedSelection ? savedSelection : wells.value.map(well => well.wellName)
    }
  } finally {
    if (version === loadVersion) {
      loading.value = false
      ready.value = true
      saveDraft()
      await nextTick()
      renderChart()
    }
  }
}

const setWellSelected = (wellName, checked) => {
  const next = new Set(selectedWells.value.map(String))
  if (checked) next.add(String(wellName))
  else next.delete(String(wellName))
  selectedWells.value = [...next]
}
const addPoint = () => {
  if (!wells.value.length) return ElMessage.warning('当前没有可选井，请先加入储气库成员井')
  const now = new Date()
  const today = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
  rows.value.push({
    id: createRowId('manual'),
    sourceKey: null,
    wellName: selectedWells.value[0] || wells.value[0].wellName,
    date: today,
    measuredPressure: '',
    measuredCoordinate: '',
    gradient: '',
    manual: true
  })
}
const deletePoint = row => {
  if (row.sourceKey) {
    row.deleted = true
    saveDraft()
  } else {
    rows.value = rows.value.filter(item => item.id !== row.id)
  }
}
const onInput = () => scheduleDatabaseSave()
const reload = () => loadData()

const downloadTemplate = async () => {
  const XLSX = await import('xlsx')
  const sheetRows = [pressureGradientImportColumns, ...wells.value.map(well => [well.wellName, '', '', '', ''])]
  const workbook = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(workbook, XLSX.utils.aoa_to_sheet(sheetRows), '压力梯度测点')
  XLSX.writeFile(workbook, `${props.reservoir?.label || '储气库'}-压力梯度测点模板.xlsx`)
}
const openImportDialog = () => {
  selectedImportFile.value = null
  importDragging.value = false
  importOptions.value = { removeEmptyRows: false, removeEmptyColumns: false, removeZeroRows: false }
  importDialogVisible.value = true
}
const chooseImportFile = () => importFileInput.value?.click()
const setImportFile = file => {
  if (!file) return
  const extension = file.name.split('.').pop()?.toLowerCase()
  if (!['xlsx', 'xls', 'csv'].includes(extension)) {
    ElMessage.error('仅支持 .xlsx、.xls、.csv 表格文件')
    return
  }
  selectedImportFile.value = file
}
const handleImportFileChange = event => {
  setImportFile(event.target.files?.[0])
  event.target.value = ''
}
const handleImportDrop = event => {
  importDragging.value = false
  setImportFile(event.dataTransfer?.files?.[0])
}
const clearImportFile = event => {
  event.stopPropagation()
  selectedImportFile.value = null
}
const importSpreadsheet = async () => {
  const file = selectedImportFile.value
  if (!file || importingSpreadsheet.value) return
  importingSpreadsheet.value = true
  try {
    const XLSX = await import('xlsx')
    const workbook = XLSX.read(await file.arrayBuffer(), { type: 'array', cellDates: true, cellFormula: true })
    const sheet = workbook.Sheets[workbook.SheetNames[0]]
    assertNoSpreadsheetFormulaCells(XLSX, sheet)
    const sourceRows = cleanPressureGradientImportRows(
      XLSX.utils.sheet_to_json(sheet, { header: 1, raw: true, defval: '' }),
      importOptions.value
    )
    const imported = parsePressureGradientRows(sourceRows, wells.value.map(well => well.wellName))
    rows.value = mergePressureGradientImportRows(rows.value, imported, () => createRowId('import'))
    scheduleDatabaseSave()
    importDialogVisible.value = false
    ElMessage.success(`已导入 ${imported.length} 个测点`)
  } catch (error) {
    ElMessage.error(error.message || '压力梯度表格导入失败')
  } finally {
    importingSpreadsheet.value = false
  }
}

const buildChartOption = () => {
  const categoryWells = chartWells.value
  const model = chartModel.value
  const activeDates = model.dates
  const byTime = chartAxis.value === 'time'
  const categories = model.categories
  const series = model.series.map(({ key, data }) => {
    return {
      name: byTime ? key : `${key.slice(0, 4)}.${Number(key.slice(5, 7))} 折算地层压力`,
      type: 'line',
      data,
      connectNulls: false,
      symbol: 'circle',
      symbolSize: 8,
      lineStyle: { width: 2 },
      emphasis: { focus: 'series' }
    }
  })
  return {
    animation: false,
    color: ['#568df5', '#ff9b21', '#f05252', '#31a6a0', '#9a6de8', '#4aaf5d'],
    title: { text: '统一基准深度地层压力对比', left: 'center', top: 12, textStyle: { fontSize: 17, fontWeight: 600, color: '#303133' } },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'line' },
      formatter: params => {
        const first = params?.[0]
        if (!first) return ''
        const date = byTime ? activeDates[first.dataIndex] : activeDates[first.seriesIndex]
        const wellName = byTime ? model.seriesKeys[first.seriesIndex] : categoryWells[first.dataIndex]
        const sourceRows = chartRows.value.filter(row => row.date === date && row.wellName === wellName)
        const pointLines = sourceRows.map(row => `实测 ${format(Number(row.measuredPressure))} MPa（截距 ${format(rowIntercept(row))} MPa）`)
        const averageNote = sourceRows.length > 1 ? `同井同日 ${sourceRows.length} 个有效测点取折算压力均值` : ''
        return [`<strong>${wellName}</strong>`, `${date} 折算压力：${format(Number(first.value))} MPa`, averageNote, ...pointLines].filter(Boolean).join('<br/>')
      }
    },
    legend: { top: 42, type: 'scroll' },
    grid: { top: 88, left: 76, right: 34, bottom: 68, containLabel: false },
    xAxis: {
      type: 'category',
      name: byTime ? '时间' : '井号',
      nameLocation: 'middle',
      nameGap: byTime ? 34 : 42,
      data: categories,
      axisTick: { alignWithLabel: true },
      axisLabel: { interval: byTime ? 'auto' : 0, rotate: byTime && categories.length > 8 ? 30 : 0 }
    },
    yAxis: {
      type: 'value',
      name: '统一基准地层压力 (MPa)',
      nameLocation: 'middle',
      nameGap: 54,
      min: 0,
      max: Math.max(5, Math.ceil(model.maxPressure / 5) * 5),
      interval: 5,
      splitLine: { lineStyle: { color: '#e4e9f2' } }
    },
    series
  }
}
const renderChart = () => {
  if (!chartElement.value) return
  if (!chart) chart = echarts.init(chartElement.value)
  chart.setOption(chartHasData.value ? buildChartOption() : {
    animation: false,
    title: { text: '统一基准深度地层压力对比', left: 'center', top: 12, textStyle: { fontSize: 17, fontWeight: 600 } },
    xAxis: { type: 'category', data: [] },
    yAxis: { type: 'value', name: '压力 (MPa)' },
    series: []
  }, true)
  chart.resize()
}
const scheduleResize = () => {
  if (resizeFrame) cancelAnimationFrame(resizeFrame)
  resizeFrame = requestAnimationFrame(() => {
    resizeFrame = 0
    chart?.resize()
  })
}

watch(() => [props.reservoir?.projectId, props.reservoir?.gasReservoirId, props.reservoir?.storageId], loadData, { immediate: true })
watch(rows, scheduleDatabaseSave, { deep: true })
watch([selectedWells, referenceCoordinate, coordinateMode, dateRange, chartAxis], () => {
  saveDraft()
  nextTick(renderChart)
}, { deep: true })
watch(activeTab, async tab => {
  if (tab === 'chart') {
    await nextTick()
    renderChart()
  }
})
onMounted(async () => {
  await nextTick()
  renderChart()
  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(scheduleResize)
    if (workspaceElement.value) resizeObserver.observe(workspaceElement.value)
    if (chartElement.value) resizeObserver.observe(chartElement.value)
  }
  window.addEventListener('resize', scheduleResize)
})
onBeforeUnmount(() => {
  if (saveTimer) {
    clearTimeout(saveTimer)
    saveTimer = 0
    persistToDatabase().catch(() => {})
  }
  loadVersion++
  resizeObserver?.disconnect()
  window.removeEventListener('resize', scheduleResize)
  if (resizeFrame) cancelAnimationFrame(resizeFrame)
  chart?.dispose()
  chart = null
})
</script>

<template>
  <section ref="workspaceElement" class="pressure-gradient-workspace" aria-label="压力梯度折算">
    <aside class="pressure-params">
      <div class="params-head"><span>参数设置</span><button type="button" @click="reload">重新读取</button></div>
      <div class="params-body">
        <div class="field">
          <label>当前储气库</label>
          <el-input size="small" readonly :model-value="reservoir?.label || '未选择储气库'" />
        </div>
        <div class="field">
          <label>统一基准坐标 (m)</label>
          <el-input v-model="referenceCoordinate" size="small" inputmode="decimal" @input="onInput" />
        </div>
        <div class="field">
          <label>坐标类型</label>
          <el-select v-model="coordinateMode" size="small" @change="onInput">
            <el-option label="测压垂深（向下为正）" value="depth" />
            <el-option label="海拔高程（向上为正）" value="elevation" />
          </el-select>
        </div>
        <div class="formula-card">
          <div>{{ formulaText }}</div>
          <small>{{ interceptText }} · 梯度 G 单位 MPa/m</small>
        </div>

        <div class="section-heading">参与井 <span>{{ selectedWells.length }} / {{ wells.length }}</span></div>
        <div v-if="wells.length" class="well-list">
          <label v-for="well in wells" :key="well.id ?? well.wellName" class="well-item">
            <el-checkbox :model-value="selectedWellSet.has(well.wellName)" @change="value => setWellSelected(well.wellName, value)" />
            <span class="well-name">{{ well.wellName }}</span>
            <span class="well-count">{{ rows.filter(row => row.wellName === well.wellName && !row.deleted).length }} 个测点</span>
          </label>
        </div>
        <div v-else-if="loading" class="subtle-message">正在读取当前库成员井…</div>
        <div v-else class="subtle-message">当前储气库没有成员井</div>
        <div v-if="loadError" class="load-notice" role="status">{{ loadError }}</div>
      </div>
    </aside>

    <main class="pressure-results">
      <div class="result-tabs">
        <button :class="{ active: activeTab === 'chart' }" type="button" @click="activeTab = 'chart'">压力对比图</button>
        <button :class="{ active: activeTab === 'table' }" type="button" @click="activeTab = 'table'">折算数据</button>
      </div>

      <div v-show="activeTab === 'chart'" class="chart-view">
        <div class="chart-toolbar">
          <label>显示测压期</label>
          <el-select v-model="dateRange" size="small" style="width: 150px">
            <el-option label="最近 3 期" value="recent3" />
            <el-option label="全部日期" value="all" />
          </el-select>
          <label>横轴</label>
          <el-select v-model="chartAxis" size="small" style="width: 128px">
            <el-option label="井号（按日期对比）" value="well" />
            <el-option label="时间（按井趋势）" value="time" />
          </el-select>
          <span class="chart-summary">{{ selectionCountText }}</span>
        </div>
        <div class="chart-frame">
          <div ref="chartElement" class="pressure-chart" aria-label="统一基准深度地层压力对比图" />
          <div v-if="!chartHasData" class="chart-empty">
            <span>{{ loading ? '正在读取压力数据…' : '暂无可绘制测点' }}</span>
            <button v-if="!loading" type="button" @click="activeTab = 'table'">录入测点参数</button>
          </div>
        </div>
      </div>

      <div v-show="activeTab === 'table'" class="table-view">
        <div class="table-toolbar">
          <span class="save-status" :class="`save-${databaseSaveState}`" role="status">{{ databaseSaveLabel }}</span>
          <div class="table-actions">
            <el-button size="small" @click="openImportDialog">导入表格</el-button>
            <el-button size="small" type="primary" plain @click="addPoint">新增测点</el-button>
            <el-button size="small" type="primary" :disabled="!databaseLoaded" :loading="databaseSaveState === 'saving'" @click="saveExplicitly">保存</el-button>
          </div>
        </div>
        <div class="table-holder">
          <el-table :data="displayedRows" row-key="id" stripe border height="100%" empty-text="当前库没有实测静压，请新增测点录入">
            <el-table-column label="井号" min-width="116">
              <template #default="scope">
                <el-select v-model="scope.row.wellName" size="small" @change="onInput">
                  <el-option v-for="well in wells" :key="well.id ?? well.wellName" :label="well.wellName" :value="well.wellName" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="日期" min-width="132">
              <template #default="scope"><el-input v-model="scope.row.date" type="date" size="small" @input="onInput" /></template>
            </el-table-column>
            <el-table-column label="实测静压 (MPa)" min-width="142">
              <template #default="scope"><el-input v-model="scope.row.measuredPressure" size="small" inputmode="decimal" @input="onInput" /></template>
            </el-table-column>
            <el-table-column :label="`${depthLabel} (m)`" min-width="142">
              <template #default="scope"><el-input v-model="scope.row.measuredCoordinate" size="small" inputmode="decimal" @input="onInput" /></template>
            </el-table-column>
            <el-table-column label="压力梯度 (MPa/m)" min-width="150">
              <template #default="scope"><el-input v-model="scope.row.gradient" size="small" inputmode="decimal" @input="onInput" /></template>
            </el-table-column>
            <el-table-column label="截距 P₀ (MPa)" min-width="130">
              <template #default="scope">{{ format(rowIntercept(scope.row)) }}</template>
            </el-table-column>
            <el-table-column label="统一基准压力 (MPa)" min-width="155">
              <template #default="scope"><span :class="{ 'missing-value': !isRowCalculable(scope.row) }">{{ format(rowPressure(scope.row)) }}</span></template>
            </el-table-column>
            <el-table-column label="操作" fixed="right" width="80">
              <template #default="scope"><el-button link type="danger" @click="deletePoint(scope.row)">删除</el-button></template>
            </el-table-column>
          </el-table>
        </div>
        <div class="table-footer">
          <span :class="{ 'load-notice': displayedMissingCount > 0 }">{{ displayedMissingCount ? `${displayedMissingCount} 行待补测点数据，暂不参与图表` : `已折算 ${displayedValidCount} 个测点` }}</span>
          <span>共 {{ displayedRows.length }} 条测点</span>
        </div>
      </div>
    </main>

    <Teleport to="body">
      <div v-if="importDialogVisible" class="pressure-import-overlay" role="presentation" @mousedown.self="importDialogVisible = false">
        <section class="pressure-import-dialog" role="dialog" aria-modal="true" aria-labelledby="pressure-import-title">
          <header class="pressure-import-header">
            <h2 id="pressure-import-title">导入压力梯度测点</h2>
            <button type="button" class="pressure-import-close" aria-label="关闭导入窗口" @click="importDialogVisible = false">×</button>
          </header>
          <div class="pressure-import-body">
            <div class="pressure-import-downloads">
              <button type="button" @click="downloadTemplate">数据模板下载</button>
            </div>
            <div class="pressure-import-options" aria-label="导入清洗选项">
              <label><input v-model="importOptions.removeEmptyRows" type="checkbox" />移除空行</label>
              <label><input v-model="importOptions.removeEmptyColumns" type="checkbox" />移除空列</label>
              <label><input v-model="importOptions.removeZeroRows" type="checkbox" />移除测量值全为 0 的行</label>
            </div>
            <div
              class="pressure-import-dropzone"
              :class="{ dragging: importDragging, selected: selectedImportFile }"
              role="button"
              tabindex="0"
              aria-label="选择或拖拽上传文件"
              @click="chooseImportFile"
              @keydown.enter.prevent="chooseImportFile"
              @keydown.space.prevent="chooseImportFile"
              @dragenter.prevent="importDragging = true"
              @dragover.prevent="importDragging = true"
              @dragleave.prevent="importDragging = false"
              @drop.prevent="handleImportDrop"
            >
              <input ref="importFileInput" class="pressure-import-file-input" type="file" accept=".xlsx,.xls,.csv" @change="handleImportFileChange" />
              <el-icon class="pressure-import-icon"><Document /></el-icon>
              <template v-if="selectedImportFile">
                <p class="pressure-import-file-name">{{ selectedImportFile.name }}</p>
                <button type="button" class="pressure-import-clear" @click="clearImportFile">重新选择</button>
              </template>
              <template v-else>
                <p class="pressure-import-prompt">点击或将文件拖拽到这里上传</p>
                <p class="pressure-import-hint">支持扩展名：.xlsx .xls .csv</p>
              </template>
            </div>
          </div>
          <footer class="pressure-import-footer">
            <button type="button" class="pressure-import-cancel" @click="importDialogVisible = false">取消</button>
            <button type="button" class="pressure-import-confirm" :disabled="!selectedImportFile || importingSpreadsheet" @click="importSpreadsheet">{{ importingSpreadsheet ? '导入中…' : '确定' }}</button>
          </footer>
        </section>
      </div>
    </Teleport>
  </section>
</template>

<style scoped>
.pressure-gradient-workspace { display:flex; flex:1; min-width:0; min-height:0; height:100%; overflow:hidden; background:#fff; color:#303133; font:14px Arial,"Microsoft YaHei",sans-serif; }
.pressure-params { width:300px; min-width:270px; max-width:360px; flex:0 0 300px; display:flex; flex-direction:column; min-height:0; border-right:1px solid #dcdfe6; }
.params-head { height:42px; flex:0 0 42px; display:flex; align-items:center; justify-content:space-between; padding:0 14px; border-bottom:1px solid #ebeef5; font-weight:600; }
.params-head button { border:0; background:none; color:#409eff; cursor:pointer; font-size:12px; }
.params-body { flex:1; min-height:0; overflow:auto; padding:14px 16px; }
.field { display:flex; flex-direction:column; gap:6px; margin-bottom:14px; color:#606266; font-size:13px; }
.field :deep(.el-select) { width:100%; }
.formula-card { margin:4px 0 18px; padding:10px 11px; background:#f4f7fb; color:#336699; line-height:1.7; font-size:13px; }
.formula-card small { display:block; margin-top:3px; color:#7d8da3; font-size:11px; }
.section-heading { display:flex; align-items:center; justify-content:space-between; margin:14px 0 8px; padding-bottom:8px; border-bottom:1px solid #ebeef5; color:#303133; font-weight:600; }
.section-heading span,.well-count { color:#909399; font-size:12px; font-weight:400; }
.well-list { max-height:300px; overflow:auto; border:1px solid #ebeef5; border-radius:3px; }
.well-item { display:flex; align-items:center; gap:7px; min-height:42px; padding:0 9px; border-bottom:1px solid #ebeef5; cursor:pointer; }
.well-item:last-child { border-bottom:0; }
.well-name { flex:1; min-width:0; overflow:hidden; color:#409eff; text-overflow:ellipsis; white-space:nowrap; }
.subtle-message { padding:12px 4px; color:#909399; font-size:12px; }
.load-notice { color:#d9822b; font-size:12px; }
.pressure-results { flex:1; min-width:0; min-height:0; display:flex; flex-direction:column; overflow:hidden; }
.result-tabs { display:flex; flex:0 0 44px; height:44px; align-items:stretch; border-bottom:1px solid #dcdfe6; background:#fafafa; }
.result-tabs button { min-width:100px; padding:0 16px; border:0; border-bottom:2px solid transparent; background:transparent; color:#606266; font-size:14px; cursor:pointer; }
.result-tabs button.active { border-bottom-color:#409eff; color:#409eff; font-weight:600; }
.chart-view,.table-view { flex:1; min-width:0; min-height:0; display:flex; flex-direction:column; overflow:hidden; }
.chart-toolbar { display:flex; align-items:center; gap:10px; min-height:50px; padding:6px 16px; color:#606266; font-size:13px; }
.chart-summary { margin-left:auto; color:#8a96a8; font-size:12px; white-space:nowrap; }
.chart-frame { position:relative; flex:1; min-height:220px; overflow:hidden; }
.pressure-chart { width:100%; height:100%; }
.chart-empty { position:absolute; inset:90px 0 0; display:flex; align-items:center; justify-content:center; flex-direction:column; gap:12px; color:#909399; pointer-events:none; }
.chart-empty button { padding:6px 12px; border:1px solid #a0cfff; border-radius:3px; background:#ecf5ff; color:#409eff; cursor:pointer; pointer-events:auto; }
.table-footer { display:flex; align-items:center; justify-content:space-between; min-height:38px; padding:0 16px; border-top:1px solid #ebeef5; color:#8a96a8; font-size:12px; }
.table-toolbar { display:flex; align-items:center; justify-content:space-between; gap:12px; min-height:54px; padding:7px 16px; color:#738198; font-size:12px; }
.table-actions { display:flex; align-items:center; gap:8px; }
.save-status { color:#67c23a; white-space:nowrap; }
.save-saving { color:#409eff; }
.save-failed { color:#e6a23c; }
.table-holder { flex:1; min-width:0; min-height:0; padding:0 14px; overflow:hidden; }
.table-footer { flex:0 0 38px; }
.missing-value { color:#b8bdc5; }
.table-footer .load-notice { color:#d9822b; }
.pressure-import-overlay { position:fixed; inset:0; z-index:3000; display:flex; align-items:center; justify-content:center; padding:16px 20px; box-sizing:border-box; background:rgba(0,0,0,.18); font-family:"Microsoft YaHei","Segoe UI",sans-serif; }
.pressure-import-dialog { width:min(742px,calc(100vw - 40px)); height:min(494px,calc(100vh - 32px)); min-height:410px; display:flex; flex-direction:column; background:#fff; color:#333; box-shadow:0 2px 7px rgba(0,0,0,.14); }
.pressure-import-header { height:38px; flex:0 0 38px; display:flex; align-items:center; justify-content:space-between; padding:0 11px 0 13px; box-sizing:border-box; background:#353535; color:#fff; }
.pressure-import-header h2 { margin:0; font-size:15px; font-weight:700; line-height:1; }
.pressure-import-close { width:24px; height:24px; padding:0; border:0; background:transparent; color:#ffe9ef; font:22px/22px Arial,sans-serif; cursor:pointer; }
.pressure-import-close:hover,.pressure-import-close:focus-visible { color:#fff; background:rgba(255,255,255,.1); outline:none; }
.pressure-import-body { flex:1; min-height:0; padding:12px 24px 8px; box-sizing:border-box; }
.pressure-import-downloads { display:flex; gap:8px; }
.pressure-import-downloads button { height:32px; padding:0 12px; border:1px solid #8f8f8f; border-radius:4px; background:#fff; color:#222; font:14px/30px "Microsoft YaHei",sans-serif; cursor:pointer; }
.pressure-import-downloads button:hover,.pressure-import-downloads button:focus-visible { border-color:#409eff; color:#1677d2; outline:none; }
.pressure-import-options { min-height:42px; display:flex; align-items:center; flex-wrap:wrap; gap:5px 12px; color:#333; font-size:14px; }
.pressure-import-options label { display:inline-flex; align-items:center; gap:6px; white-space:nowrap; cursor:pointer; }
.pressure-import-options input { width:14px; height:14px; margin:0; accent-color:#3f55e7; }
.pressure-import-dropzone { height:calc(100% - 74px); min-height:250px; border:1px dashed #dedede; box-sizing:border-box; display:flex; flex-direction:column; align-items:center; justify-content:center; background:#fff; cursor:pointer; transition:border-color .15s ease,background-color .15s ease; }
.pressure-import-dropzone:hover,.pressure-import-dropzone:focus-visible,.pressure-import-dropzone.dragging { border-color:#2495ef; background:#f8fcff; outline:none; }
.pressure-import-file-input { display:none; }
.pressure-import-icon { width:44px; height:44px; color:#2495ef; font-size:44px; }
.pressure-import-prompt,.pressure-import-file-name { margin:51px 0 0; color:#666; font-size:17px; line-height:24px; }
.pressure-import-file-name { margin-top:28px; max-width:80%; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.pressure-import-hint { margin:15px 0 0; color:#999; font-size:14px; line-height:20px; }
.pressure-import-clear { margin-top:12px; padding:4px 12px; border:0; background:transparent; color:#2495ef; font:inherit; cursor:pointer; }
.pressure-import-footer { height:60px; flex:0 0 60px; display:flex; align-items:center; justify-content:flex-end; gap:8px; padding:0 10px; box-sizing:border-box; border-top:1px solid #888; }
.pressure-import-footer button { width:72px; height:30px; border-radius:3px; font:14px "Microsoft YaHei",sans-serif; cursor:pointer; }
.pressure-import-cancel { border:1px solid #9b7278; background:#fff7f7; color:#72222a; }
.pressure-import-confirm { border:1px solid #3d53e5; background:#3d53e5; color:#fff; }
.pressure-import-confirm:disabled { border-color:#c8c9cc; background:#c8c9cc; cursor:not-allowed; }
@media (max-width:680px) { .pressure-import-body { padding-inline:14px; } .pressure-import-options { padding:7px 0; } .pressure-import-dropzone { height:calc(100% - 96px); } .pressure-import-prompt { margin-top:28px; } }
@media (max-width:900px) {
  .pressure-params { width:250px; min-width:220px; flex-basis:250px; }
  .chart-summary { white-space:normal; text-align:right; }
}
</style>
