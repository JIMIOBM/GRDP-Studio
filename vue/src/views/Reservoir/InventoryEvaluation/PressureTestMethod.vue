<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import { dataManagementApi } from '@/api/docker'
import { storageCatalogApi } from '@/api/storageCatalog'
import { storagePressureGradientApi } from '@/api/storagePressureGradient'
import { normalizePressureRecord, pressureRecordDate, pressureSourceKeys } from '@/utils/formationPressureGradient'
import { assertNoSpreadsheetFormulaCells } from '@/utils/spreadsheetImport'
import { mergePressureTestImportRows, parsePressureTestRows, pressureTestImportColumns } from '@/utils/pressureTestImport'
import { buildPressureTestComparison } from '@/utils/pressureTestMethod'

const props = defineProps({ reservoir: { type: Object, default: null } })
const wells = ref([])
const rows = ref([])
const dateRange = ref('recent3')
const chartAxis = ref('well')
const activeTab = ref('chart')
const loading = ref(false)
const loadError = ref('')
const ready = ref(false)
const databaseLoaded = ref(false)
const chartElement = ref(null)
const importFileInput = ref(null)
const saveState = ref('saved')
let chart
let version = 0
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
const message = error => error?.response?.data?.msg || error?.response?.data?.message || error?.message || '读取实测静压失败'
const rowId = prefix => `${prefix}:${globalThis.crypto?.randomUUID?.() || `${Date.now()}:${Math.random().toString(36).slice(2, 8)}`}`
const draftKey = computed(() => `grdp:pressure-test-method:${props.reservoir?.projectId ?? 'none'}:${props.reservoir?.gasReservoirId ?? 'none'}:${props.reservoir?.storageId ?? 'none'}`)
const readDraft = () => {
  try { return JSON.parse(localStorage.getItem(draftKey.value) || 'null') } catch { return null }
}
const writeDraft = () => {
  try { localStorage.setItem(draftKey.value, JSON.stringify({ version: 1, pendingDatabaseSave: true, rows: rows.value })) } catch (error) {
    console.warn('测试法测点本机草稿暂未能保存', error)
  }
}
const clearDraft = () => {
  try { localStorage.removeItem(draftKey.value) } catch { /* retain the database save result */ }
}
const selectedWells = computed(() => wells.value.map(well => well.wellName))
const chartModel = computed(() => buildPressureTestComparison({
  rows: rows.value, selectedWells: selectedWells.value, dateRange: dateRange.value, chartAxis: chartAxis.value
}))
const chartHasData = computed(() => chartModel.value.series.some(series => series.data.some(Number.isFinite)))
const shownRows = computed(() => rows.value.filter(row => row.deleted !== true))
const saveLabel = computed(() => ({ saving: '正在保存到数据库…', failed: '数据库保存失败' }[saveState.value] || '数据库已保存'))
const format = value => Number.isFinite(value) ? value.toLocaleString('zh-CN', { maximumFractionDigits: 4 }) : '—'

const toDbRows = () => rows.value.map((row, index) => {
  const numberOrNull = (value, label) => {
    if (value == null || String(value).trim() === '') return null
    const parsed = Number(value)
    if (!Number.isFinite(parsed)) throw new Error(`${label}必须是有效数字`)
    return parsed
  }
  const line = index + 1
  const measuredPressure = numberOrNull(row.measuredPressure, `第 ${line} 行实测静压`)
  if (measuredPressure !== null && measuredPressure < 0) throw new Error(`第 ${line} 行实测静压不能为负数`)
  if (row.date && pressureRecordDate({ date: row.date }) !== row.date) throw new Error(`第 ${line} 行日期无效`)
  return {
    id: String(row.id || row.sourceKey || rowId('manual')),
    sourceKey: row.sourceKey || null,
    wellName: String(row.wellName || ''),
    date: /^\d{4}-\d{2}-\d{2}$/.test(String(row.date || '')) ? row.date : null,
    measuredPressure,
    measuredCoordinate: numberOrNull(row.measuredCoordinate, '测压坐标'),
    gradient: numberOrNull(row.gradient, '压力梯度'),
    manual: row.manual === true,
    deleted: row.deleted === true
  }
})
const saveDatabase = (notify = false) => {
  if (!ready.value) return Promise.resolve()
  if (saveTimer) { clearTimeout(saveTimer); saveTimer = 0 }
  if (!databaseLoaded.value) {
    saveState.value = 'failed'
    if (notify) ElMessage.warning('先成功读取数据库数据，再保存以免覆盖已有测点')
    return Promise.reject(new Error('数据库读取未完成，保存已阻止'))
  }
  const currentSave = ++saveVersion
  let data
  try { data = toDbRows() } catch (error) {
    saveState.value = 'failed'
    if (notify) ElMessage.error(error.message)
    return Promise.reject(error)
  }
  writeDraft()
  const scope = { projectId: props.reservoir?.projectId, gasReservoirId: props.reservoir?.gasReservoirId, storageId: props.reservoir?.storageId }
  saveState.value = 'saving'
  const task = saveQueue.catch(() => {}).then(() => storagePressureGradientApi.save(scope, { rows: data }))
  saveQueue = task
  return task.then(() => {
    if (currentSave === saveVersion) {
      saveState.value = 'saved'
      clearDraft()
    }
    if (notify) ElMessage.success(`已保存 ${data.length} 个测点到数据库`)
  }).catch(error => {
    if (currentSave === saveVersion) saveState.value = 'failed'
    if (notify) ElMessage.error(message(error))
    throw error
  })
}
const scheduleSave = () => {
  if (!ready.value) return
  saveVersion++
  writeDraft()
  saveState.value = 'saving'
  if (saveTimer) clearTimeout(saveTimer)
  saveTimer = window.setTimeout(() => { saveTimer = 0; saveDatabase().catch(() => {}) }, 900)
}
const addPoint = () => {
  if (!databaseLoaded.value) return ElMessage.warning('数据库读取成功后才能新增测点')
  if (!wells.value.length) return ElMessage.warning('当前储气库没有成员井')
  const now = new Date()
  rows.value.push({
    id: rowId('manual'), sourceKey: null,
    wellName: wells.value[0].wellName,
    date: `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`,
    measuredPressure: '', measuredCoordinate: '', gradient: '', manual: true
  })
}
const removePoint = row => {
  if (row.sourceKey) row.deleted = true
  else rows.value = rows.value.filter(item => item.id !== row.id)
}

const load = async () => {
  const current = ++version
  const draft = readDraft()
  if (saveTimer) { clearTimeout(saveTimer); saveTimer = 0 }
  ready.value = false; databaseLoaded.value = false; loading.value = true; loadError.value = ''
  rows.value = []; wells.value = []
  try {
    const { projectId, gasReservoirId, storageId } = props.reservoir || {}
    if (![projectId, gasReservoirId, storageId].every(value => Number(value) > 0)) throw new Error('请先打开一个具体储气库')
    const wellPayload = responseData(await storageCatalogApi.wells(storageId, projectId, gasReservoirId))
    if (current !== version) return
    if (!Array.isArray(wellPayload)) throw new Error('当前储气库井列表返回格式不正确')
    wells.value = wellPayload.map(well => ({ id: Number(well.id), wellName: String(well.wellName || '').trim() })).filter(well => well.wellName)
    let savedRows = []
    try {
      const saved = responseData(await storagePressureGradientApi.list({ projectId, gasReservoirId, storageId }))
      if (!saved || !Array.isArray(saved.rows)) throw new Error('数据库测点返回格式不正确')
      databaseLoaded.value = true
      saveState.value = 'saved'
      savedRows = (Array.isArray(saved?.rows) ? saved.rows : []).map(row => ({
        ...row,
        date: row.date || '',
        measuredPressure: row.measuredPressure == null ? '' : String(row.measuredPressure),
        measuredCoordinate: row.measuredCoordinate == null ? '' : String(row.measuredCoordinate),
        gradient: row.gradient == null ? '' : String(row.gradient)
      }))
    } catch (error) {
      saveState.value = 'failed'
      loadError.value = `数据库测点读取失败：${message(error)}`
    }
    const savedBySource = new Map(savedRows.filter(row => row.sourceKey).map(row => [row.sourceKey, row]))
    const requests = await Promise.allSettled(wells.value.map(well =>
      dataManagementApi.getStaticPressure(projectId, gasReservoirId, well.wellName, { silentError: true })
    ))
    if (current !== version) return
    const fetched = []
    requests.forEach((result, index) => {
      if (result.status !== 'fulfilled') return
      const wellName = wells.value[index].wellName
      responseRows(result.value).forEach((item, itemIndex) => {
        const keys = pressureSourceKeys(item, wellName, itemIndex)
        const normalized = normalizePressureRecord(item, wellName, keys.sourceKey)
        const saved = savedBySource.get(keys.sourceKey) || savedBySource.get(keys.legacySourceKey)
        fetched.push({ ...normalized, ...(saved || {}), id: normalized.id, sourceKey: normalized.sourceKey })
      })
    })
    const failedWells = new Set(requests.flatMap((result, index) => result.status === 'rejected' ? [wells.value[index].wellName] : []))
    const fetchedKeys = new Set(fetched.map(row => row.sourceKey))
    const savedFailed = savedRows.filter(row => row.sourceKey && failedWells.has(row.wellName))
    const manual = savedRows.filter(row => row.manual === true)
    const deletedMissing = savedRows.filter(row => row.deleted && row.sourceKey && !fetchedKeys.has(row.sourceKey))
    rows.value = [...fetched, ...savedFailed, ...manual, ...deletedMissing]
    if (failedWells.size) loadError.value = [loadError.value, `${failedWells.size} 口井的实测静压读取失败`].filter(Boolean).join('；')
    if (!wells.value.length) loadError.value = [loadError.value, '当前储气库没有成员井'].filter(Boolean).join('；')
    if (!fetched.length && !manual.length && !loadError.value) loadError.value = '当前库暂无实测静压'
    if (draft?.pendingDatabaseSave === true && Array.isArray(draft.rows)) {
      rows.value = draft.rows
      saveState.value = 'failed'
      loadError.value = [loadError.value, '已恢复本机未保存的测点修改，请核对后保存'].filter(Boolean).join('；')
    }
  } catch (error) {
    if (current === version) {
      loadError.value = message(error)
      if (draft?.pendingDatabaseSave === true && Array.isArray(draft.rows)) {
        rows.value = draft.rows
        saveState.value = 'failed'
        loadError.value = [loadError.value, '已恢复本机未保存的测点修改，请核对后保存'].filter(Boolean).join('；')
      }
    }
  } finally {
    if (current === version) { loading.value = false; ready.value = true; await nextTick(); renderChart() }
  }
}

const downloadTemplate = async () => {
  const XLSX = await import('xlsx')
  const workbook = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(workbook, XLSX.utils.aoa_to_sheet([
    pressureTestImportColumns, ...wells.value.map(well => [well.wellName, '', ''])
  ]), '测试法测点')
  XLSX.writeFile(workbook, `${props.reservoir?.label || '储气库'}-测试法测点模板.xlsx`)
}
const importFile = async event => {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  if (!databaseLoaded.value) return ElMessage.warning('数据库读取成功后才能导入，以免测点丢失')
  try {
    if (!['xlsx', 'xls', 'csv'].includes(file.name.split('.').pop()?.toLowerCase())) throw new Error('仅支持 .xlsx、.xls、.csv 表格文件')
    const XLSX = await import('xlsx')
    const workbook = XLSX.read(await file.arrayBuffer(), { type: 'array', cellDates: true, cellFormula: true })
    const sheet = workbook.Sheets[workbook.SheetNames[0]]
    assertNoSpreadsheetFormulaCells(XLSX, sheet)
    const parsed = parsePressureTestRows(XLSX.utils.sheet_to_json(sheet, { header: 1, raw: true, defval: '' }), wells.value.map(well => well.wellName))
    rows.value = mergePressureTestImportRows(rows.value, parsed, () => rowId('test-import'))
    scheduleSave()
    ElMessage.success(`已导入 ${parsed.length} 个测点`)
  } catch (error) { ElMessage.error(error.message || '测试法表格导入失败') }
}

const chartOption = () => {
  const model = chartModel.value
  const byTime = chartAxis.value === 'time'
  return {
    animation: false,
    color: ['#568df5', '#ff9b21', '#f05252', '#31a6a0', '#9a6de8', '#4aaf5d'],
    title: { text: '地层压力测试法对比', left: 'center', top: 12, textStyle: { fontSize: 17, fontWeight: 600 } },
    tooltip: { trigger: 'axis', formatter: params => params.filter(item => item.value !== null && item.value !== undefined && Number.isFinite(Number(item.value))).map(item => `${item.axisValue}<br/>${item.marker}${item.seriesName}：${format(Number(item.value))} MPa`).join('<br/>') },
    legend: { top: 42, type: 'scroll' },
    grid: { top: 88, left: 76, right: 34, bottom: 68 },
    xAxis: { type: 'category', name: byTime ? '日期' : '井号', nameLocation: 'middle', nameGap: 40, data: model.categories },
    yAxis: { type: 'value', name: '实测静压 (MPa)', nameLocation: 'middle', nameGap: 54, min: 0, max: Math.max(5, Math.ceil(model.maxPressure / 5) * 5), interval: 5, splitLine: { lineStyle: { color: '#e4e9f2' } } },
    series: model.series.map(({ key, data }) => ({ name: byTime ? key : key, type: 'line', data, connectNulls: false, symbol: 'circle', symbolSize: 8, lineStyle: { width: 2 } }))
  }
}
const renderChart = async () => {
  await nextTick()
  if (!chartElement.value || activeTab.value !== 'chart') return
  if (!chart) chart = echarts.init(chartElement.value)
  chart.setOption(chartHasData.value ? chartOption() : {
    animation: false,
    title: { text: '地层压力测试法对比', left: 'center', top: 12, textStyle: { fontSize: 17, fontWeight: 600 } },
    xAxis: { type: 'category', data: [] }, yAxis: { type: 'value', name: '实测静压 (MPa)' }, series: []
  }, true)
  chart.resize()
}
watch(() => [props.reservoir?.projectId, props.reservoir?.gasReservoirId, props.reservoir?.storageId], load, { immediate: true })
watch(rows, scheduleSave, { deep: true })
watch([dateRange, chartAxis, activeTab, chartModel], renderChart)
onBeforeUnmount(() => {
  if (saveTimer) {
    clearTimeout(saveTimer)
    saveTimer = 0
    saveDatabase().catch(() => {})
  }
  version++
  chart?.dispose()
  chart = null
})
</script>

<template>
  <section class="pressure-test-method" aria-label="地层压力测试法">
    <div class="test-toolbar">
      <button :class="{ active: activeTab === 'chart' }" @click="activeTab = 'chart'">压力对比图</button>
      <button :class="{ active: activeTab === 'table' }" @click="activeTab = 'table'">实测数据</button>
      <span v-if="loadError" class="load-error" role="status">{{ loadError }}</span>
    </div>
    <div v-show="activeTab === 'chart'" class="chart-view">
      <div class="chart-controls">
        <label>测压期</label>
        <el-select v-model="dateRange" size="small" style="width: 140px"><el-option label="最近 3 期" value="recent3" /><el-option label="全部日期" value="all" /></el-select>
        <label>横轴</label>
        <el-select v-model="chartAxis" size="small" style="width: 135px"><el-option label="井号" value="well" /><el-option label="时间" value="time" /></el-select>
        <span>{{ chartModel.validRows.length }} 个有效测点</span>
      </div>
      <div class="chart-frame"><div v-if="loading" class="chart-empty">正在读取智慧气藏实测静压…</div><div v-else-if="!chartHasData" class="chart-empty">暂无可绘制的实测数据</div><div ref="chartElement" class="pressure-chart" /></div>
    </div>
    <div v-show="activeTab === 'table'" class="table-view">
      <div class="table-toolbar">
        <span :class="`save-${saveState}`" role="status">{{ saveLabel }}</span>
        <div class="table-actions">
          <el-button size="small" @click="downloadTemplate">下载模板</el-button>
          <el-button size="small" :disabled="!databaseLoaded" @click="importFileInput?.click()">导入表格</el-button>
          <input ref="importFileInput" class="hidden-file-input" type="file" accept=".xlsx,.xls,.csv" :disabled="!databaseLoaded" @change="importFile" />
          <el-button size="small" type="primary" plain :disabled="!databaseLoaded" @click="addPoint">新增测点</el-button>
          <el-button size="small" type="primary" :disabled="!databaseLoaded" :loading="saveState === 'saving'" @click="saveDatabase(true).catch(() => {})">保存</el-button>
        </div>
      </div>
      <el-table :data="shownRows" row-key="id" stripe border height="100%" empty-text="当前库没有实测静压">
        <el-table-column label="井号" min-width="140"><template #default="{ row }"><el-select v-model="row.wellName" size="small" :disabled="!databaseLoaded"><el-option v-for="well in wells" :key="well.id" :label="well.wellName" :value="well.wellName" /></el-select></template></el-table-column>
        <el-table-column label="日期" min-width="150"><template #default="{ row }"><el-input v-model="row.date" type="date" size="small" :disabled="!databaseLoaded" /></template></el-table-column>
        <el-table-column label="实测静压 (MPa)" min-width="180"><template #default="{ row }"><el-input v-model="row.measuredPressure" size="small" inputmode="decimal" :disabled="!databaseLoaded" /></template></el-table-column>
        <el-table-column label="操作" width="90" fixed="right"><template #default="{ row }"><el-button link type="danger" :disabled="!databaseLoaded" @click="removePoint(row)">删除</el-button></template></el-table-column>
      </el-table>
      <div class="table-footer"><span>{{ shownRows.length }} 个测点</span><span>使用智慧气藏实测静压</span></div>
    </div>
  </section>
</template>

<style scoped>
.pressure-test-method { display:flex; flex-direction:column; min-width:0; min-height:0; height:100%; overflow:hidden; background:#fff; color:#303133; }
.test-toolbar { display:flex; flex:0 0 44px; align-items:stretch; border-bottom:1px solid #dcdfe6; background:#fafafa; }
.test-toolbar button { min-width:100px; padding:0 16px; border:0; border-bottom:2px solid transparent; background:transparent; color:#606266; cursor:pointer; }
.test-toolbar button.active { border-bottom-color:#409eff; color:#409eff; font-weight:600; }
.load-error { align-self:center; margin-left:auto; padding:0 16px; color:#d9822b; font-size:12px; }
.chart-view,.table-view { flex:1; min-width:0; min-height:0; display:flex; flex-direction:column; overflow:hidden; }
.chart-controls,.table-toolbar { display:flex; align-items:center; gap:10px; min-height:50px; padding:6px 16px; color:#606266; font-size:13px; }
.chart-controls span { margin-left:auto; color:#909399; }
.chart-frame { position:relative; flex:1; min-height:220px; }
.pressure-chart { width:100%; height:100%; }
.chart-empty { position:absolute; inset:0; display:flex; align-items:center; justify-content:center; color:#909399; pointer-events:none; }
.table-toolbar { justify-content:space-between; }
.table-actions { display:flex; align-items:center; gap:8px; }
.hidden-file-input { display:none; }
.save-saved { color:#67c23a; }
.save-saving { color:#409eff; }
.save-failed { color:#e6a23c; }
.table-footer { display:flex; align-items:center; justify-content:space-between; min-height:36px; padding:0 16px; color:#909399; font-size:12px; }
</style>
