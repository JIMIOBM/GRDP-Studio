<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { storageMaterialBalanceApi } from '@/api/storageMaterialBalance'
import { createStorageMaterialBalanceState, selectStorageMaterialBalanceWells, storageMaterialBalanceChart } from '@/utils/storageMaterialBalance'
import MaterialBalanceContent from '@/views/WellControlInventory/MaterialBalanceContent.vue'
import { toSharedMaterialBalanceResult } from '@/utils/storageMaterialBalanceSource'

const props = defineProps({ reservoir: { type: Object, default: null } })
const selectedWellIds = ref([])
const saving = ref(false)
const savedSnapshot = ref(null)
const isSavedResult = ref(false)
const snapshotSelection = ref(false)
const databaseError = ref('')
const { result, loading, error, load, clear } = createStorageMaterialBalanceState(
  (params, signal) => storageMaterialBalanceApi.aggregate({ ...params, wellIds: [...selectedWellIds.value] }, signal)
)
const { result: detail, loading: detailLoading, error: detailError, load: loadDetail, clear: clearDetail } =
  createStorageMaterialBalanceState(storageMaterialBalanceApi.source, {
    keys: ['projectId', 'gasReservoirId', 'storageId', 'wellId', 'resultId'],
    validate: (data, scope) => data?.wellId === scope.wellId && data?.resultId === scope.resultId
      && Array.isArray(data?.inputRows) && Array.isArray(data?.resultRows) && Array.isArray(data?.regressionLine)
  })
const { result: availability, error: availabilityError, load: loadAvailability, clear: clearAvailability } =
  createStorageMaterialBalanceState(storageMaterialBalanceApi.availability, { validate: Array.isArray })
const tab = ref('chart')
const sourceVisible = ref(false)
const sourceWell = ref(null)
const page = ref(1)
const skippedPage = ref(1)
const sourceId = ref(null)
const chartEl = ref(null)
let chart
let observer
let destroyed = false
let reloadVersion = 0
const rows = computed(() => result.value?.rows || [])
const pageRows = computed(() => rows.value.slice((page.value - 1) * 50, page.value * 50))
const partialDates = computed(() => result.value?.partialDates || [])
const partialRows = computed(() => partialDates.value.slice((skippedPage.value - 1) * 20, skippedPage.value * 20))
const selectedWellSet = computed(() => new Set(selectedWellIds.value.map(String)))
const canSave = computed(() => !!result.value && !loading.value && !saving.value
  && !isSavedResult.value && !snapshotSelection.value && selectedWellIds.value.length > 0)
const source = computed(() => result.value?.wells.find(well => well.wellId === sourceId.value))
const memberWells = computed(() => (availability.value || []).filter(well => well.inStorage))
const sampleCountForWell = wellId => (savedSnapshot.value?.result || result.value)?.wells
  ?.find(well => Number(well.wellId) === Number(wellId))?.samples?.length || 0
const sharedSourceResult = computed(() => toSharedMaterialBalanceResult(detail.value))
const sourceNode = computed(() => detail.value ? { wellName: detail.value.wellName } : null)
const format = value => Number.isFinite(value) ? value.toLocaleString('zh-CN', { maximumFractionDigits: 4 }) : '—'
const scope = () => ({ projectId: props.reservoir?.projectId, gasReservoirId: props.reservoir?.gasReservoirId, storageId: props.reservoir?.storageId })
const setWellSelected = (wellId, checked) => {
  const next = new Set(selectedWellIds.value)
  if (checked) next.add(Number(wellId))
  else next.delete(Number(wellId))
  selectionChanged([...next])
}
const reload = async () => {
  const current = ++reloadVersion
  saving.value = false
  isSavedResult.value = false
  snapshotSelection.value = false
  savedSnapshot.value = null
  databaseError.value = ''
  selectedWellIds.value = []
  page.value = 1; skippedPage.value = 1; sourceId.value = null
  sourceVisible.value = false
  clear(); clearDetail(); clearAvailability()
  await loadAvailability(scope())
  if (current !== reloadVersion || destroyed) return
  let restored = false
  try {
    const response = await storageMaterialBalanceApi.latest(scope())
    if (current !== reloadVersion || destroyed) return
    const snapshot = response?.data ?? response
    const memberIds = new Set(memberWells.value.map(well => Number(well.wellId)))
    const savedIds = Array.isArray(snapshot?.selectedWellIds) ? snapshot.selectedWellIds.map(Number) : []
    const savedResult = snapshot?.result
    if (snapshot?.id && savedIds.length && savedIds.every(id => Number.isSafeInteger(id) && memberIds.has(id))
      && Array.isArray(savedResult?.wells) && savedResult.wells.every(well => Array.isArray(well?.samples))
      && Array.isArray(savedResult?.rows) && Array.isArray(savedResult?.partialDates)) {
      selectedWellIds.value = savedIds
      result.value = selectStorageMaterialBalanceWells(savedResult, savedIds)
      savedSnapshot.value = snapshot
      snapshotSelection.value = true
      isSavedResult.value = true
      restored = true
    }
  } catch (cause) {
    databaseError.value = cause?.response?.data?.msg || cause?.msg || cause?.message || '数据库保存记录读取失败'
  }
  if (current !== reloadVersion || destroyed) return
  if (restored) return
  selectedWellIds.value = memberWells.value.map(well => Number(well.wellId))
  await load(scope())
  if (current !== reloadVersion || destroyed) return
  isSavedResult.value = false
  if (!selectedWellIds.value.length && result.value?.wells.length) {
    selectedWellIds.value = result.value.wells.map(well => well.wellId)
  }
}
const selectionChanged = ids => {
  reloadVersion++
  saving.value = false
  databaseError.value = ''
  selectedWellIds.value = (ids || []).map(Number)
  page.value = 1; skippedPage.value = 1; sourceId.value = null
  sourceVisible.value = false
  clearDetail()
  const snapshotIds = Array.isArray(savedSnapshot.value?.selectedWellIds)
    ? savedSnapshot.value.selectedWellIds.map(Number) : []
  if (savedSnapshot.value?.result && selectedWellIds.value.every(id => snapshotIds.includes(id))) {
    clear()
    snapshotSelection.value = true
    isSavedResult.value = selectedWellIds.value.length === snapshotIds.length
      && snapshotIds.every(id => selectedWellIds.value.includes(id))
    result.value = selectStorageMaterialBalanceWells(savedSnapshot.value.result, selectedWellIds.value)
    return
  }
  savedSnapshot.value = null
  snapshotSelection.value = false
  isSavedResult.value = false
  if (!selectedWellIds.value.length) { clear(); return }
  return load(scope())
}
const saveResult = async () => {
  if (!canSave.value) return
  const current = reloadVersion
  const requestScope = scope()
  const wellIds = [...selectedWellIds.value]
  saving.value = true
  databaseError.value = ''
  try {
    const response = await storageMaterialBalanceApi.save({ ...requestScope, wellIds })
    if (current !== reloadVersion || destroyed) return
    const snapshot = response?.data ?? response
    if (!snapshot?.id || !Array.isArray(snapshot.selectedWellIds)
      || !Array.isArray(snapshot.result?.wells) || !Array.isArray(snapshot.result?.rows)
      || !Array.isArray(snapshot.result?.partialDates)) {
      throw new Error('数据库返回的保存结果格式不正确')
    }
    selectedWellIds.value = snapshot.selectedWellIds.map(Number)
    result.value = selectStorageMaterialBalanceWells(snapshot.result, snapshot.selectedWellIds)
    savedSnapshot.value = snapshot
    snapshotSelection.value = true
    isSavedResult.value = true
  } catch (cause) {
    if (current === reloadVersion && !destroyed) {
      databaseError.value = cause?.response?.data?.msg || cause?.msg || cause?.message || '数据库保存失败，请重试'
    }
  } finally {
    if (current === reloadVersion || destroyed) saving.value = false
  }
}
watch(() => [props.reservoir?.projectId, props.reservoir?.gasReservoirId, props.reservoir?.storageId], () => {
  tab.value = 'chart'
  reload()
}, { immediate: true })
const viewSource = well => {
  if (!well) return
  sourceVisible.value = true
  sourceWell.value = well
  sourceId.value = well.resultId ? well.wellId : null
  clearDetail()
  if (!well.resultId) return
  return loadDetail({ ...scope(), wellId: well.wellId, resultId: well.resultId })
}
const disposeChart = () => { observer?.disconnect(); observer = null; chart?.dispose(); chart = null }
const renderChart = async () => {
  await nextTick()
  if (destroyed) return
  if (tab.value !== 'chart' || !chartEl.value || !rows.value.length) { disposeChart(); return }
  if (!chart) {
    chart = echarts.init(chartEl.value)
    observer = new ResizeObserver(() => chart?.resize())
    observer.observe(chartEl.value)
  }
  chart.setOption(storageMaterialBalanceChart(rows.value), true)
  chart.resize()
}
watch([tab, result, chartEl], renderChart)
onBeforeUnmount(() => { destroyed = true; reloadVersion++; clear(); clearDetail(); clearAvailability(); disposeChart() })
</script>

<template>
  <section class="storage-mb-workspace" aria-label="库物质平衡">
    <div class="module-bar"><span>库存评估 · 物质平衡</span></div>
    <div class="workspace">
    <aside class="mb-params">
      <div class="params-head"><span>参数设置</span><button type="button" @click="reload">重新读取</button></div>
      <div class="params-body">
        <div class="field">
          <label>所属储气库</label>
          <el-input size="small" readonly :model-value="reservoir?.label || '未选择储气库'" />
        </div>
        <div class="section-heading">参与井 <span>{{ selectedWellIds.length }} / {{ memberWells.length }}</span></div>
        <div v-if="memberWells.length" class="well-list">
          <label v-for="well in memberWells" :key="well.wellId" class="well-item">
            <el-checkbox :model-value="selectedWellSet.has(String(well.wellId))" :disabled="loading"
              @change="value => setWellSelected(well.wellId, value)" />
            <span class="well-name">{{ well.wellName }}</span>
            <span class="well-count">{{ sampleCountForWell(well.wellId) || well.measuredCount ? `${sampleCountForWell(well.wellId) || well.measuredCount} 个测点` : '—' }}</span>
          </label>
        </div>
        <div v-else-if="loading" class="subtle-message">正在读取当前库成员井…</div>
        <div v-else class="subtle-message">当前储气库没有成员井</div>
        <div class="formula-card">
          <strong>汇总口径</strong>
          <div>压力按当日有效井的动态储量加权；累计产气、产水按当日有效井合计。</div>
          <small>读取已保存的单井实测静压结果，不进行库级储量回归。</small>
        </div>
        <div v-if="error || availabilityError || databaseError" class="load-notice" role="status">{{ error || availabilityError || databaseError }}</div>
      </div>
    </aside>

    <main class="pressure-results">
      <div class="dynamic-result-tabs"><span class="dynamic-result-tab-text">物质平衡-{{ reservoir?.label || '当前库' }}-汇总结果</span></div>
      <div class="result-toolbar">
        <span class="chart-summary">已选 {{ selectedWellIds.length }} / {{ memberWells.length }} 口井 · {{ rows.length }} 个日期</span>
        <span v-if="isSavedResult" class="save-status">已保存到数据库</span>
        <el-button size="small" type="primary" plain :loading="saving" :disabled="!canSave" @click="saveResult">保存到数据库</el-button>
      </div>

      <div v-show="tab === 'chart'" class="chart-view">
        <div class="chart-frame">
          <div v-if="rows.length" ref="chartEl" class="pressure-chart" aria-label="库物质平衡压力对比图" />
          <div v-else class="chart-empty">
            <span>{{ loading ? '正在读取压力数据…' : error || result?.message || '暂无可绘制数据' }}</span>
          </div>
        </div>
      </div>

      <div v-show="tab === 'data'" class="table-view">
        <div class="table-holder">
          <el-table :data="pageRows" border height="100%" empty-text="暂无汇总数据">
            <el-table-column prop="date" label="日期" width="140" />
            <el-table-column label="加权地层压力 (MPa)" min-width="180"><template #default="{ row }">{{ format(row.pressure) }}</template></el-table-column>
            <el-table-column label="累产气量合计 (10⁸m³)" min-width="190"><template #default="{ row }">{{ format(row.gas) }}</template></el-table-column>
            <el-table-column label="累产水量合计 (10⁴m³)" min-width="190"><template #default="{ row }">{{ format(row.water) }}</template></el-table-column>
          </el-table>
        </div>
        <div class="table-footer"><span>{{ partialDates.length }} 个日期有部分参与井缺数据</span><span>共 {{ rows.length }} 条汇总数据</span></div>
        <el-pagination v-model:current-page="page" :total="rows.length" :page-size="50" layout="total, prev, pager, next" />
      </div>

      <div v-show="tab === 'source'" class="source-view">
        <div class="source-heading">参与井及来源结果</div>
        <div class="source-table-holder">
          <el-table :data="result?.wells || []" border height="100%" empty-text="该库没有可显示的成员数据">
            <el-table-column prop="wellName" label="井名" width="90" />
            <el-table-column label="测点数" width="95"><template #default="{ row }">{{ row.samples?.length || 0 }}</template></el-table-column>
            <el-table-column label="动态储量 (10⁸m³)" width="155"><template #default="{ row }">{{ format(row.gasVolume) }}</template></el-table-column>
            <el-table-column label="权重" width="90"><template #default="{ row }">{{ Number.isFinite(row.weight) ? `${format(row.weight * 100)}%` : '—' }}</template></el-table-column>
            <el-table-column label="状态 / 原因" min-width="240"><template #default="{ row }">
              <span>{{ row.reason }}</span>
              <div v-if="row.warning && !/(演示|测试数据|补录)/.test(row.warning)" class="excluded">{{ row.warning }}</div>
              <div v-if="row.discardedRows">跳过 {{ row.discardedRows }} 条已排除、重复日期或无效输入行</div>
            </template></el-table-column>
            <el-table-column label="数据" width="110"><template #default="{ row }">
              <el-button link type="primary" :disabled="!(row.samples?.length) && !row.resultId" @click="viewSource(row)">查看数据</el-button>
            </template></el-table-column>
          </el-table>
        </div>
        <template v-if="partialDates.length">
          <div class="source-heading">日期覆盖 <span>{{ partialDates.length }} 个日期有部分参与井缺数据</span></div>
          <div class="source-table-holder partial-date-holder">
            <el-table :data="partialRows" border height="100%">
              <el-table-column prop="date" label="日期" width="140" />
              <el-table-column label="该日无有效数据的井"><template #default="{ row }">{{ row.missingWells.join('、') }}</template></el-table-column>
            </el-table>
          </div>
          <el-pagination v-if="partialDates.length > 20" v-model:current-page="skippedPage" :total="partialDates.length" :page-size="20" layout="total, prev, pager, next" />
        </template>
        <div v-else class="source-complete">所选参与井的日期完整</div>
      </div>

      <nav class="bottom-chart-tabs" aria-label="物质平衡结果视图">
        <button type="button" :class="{ active: tab === 'data' }" @click="tab = 'data'">汇总数据</button>
        <button type="button" :class="{ active: tab === 'chart' }" @click="tab = 'chart'">结果分析图</button>
        <button type="button" :class="{ active: tab === 'source' }" @click="tab = 'source'">来源明细</button>
      </nav>
    </main>
    </div>

    <el-dialog v-model="sourceVisible" :title="`井数据 · ${sourceWell?.wellName || ''}`" width="94%" top="4vh" append-to-body destroy-on-close class="storage-mb-source">
      <el-table :data="sourceWell?.samples || []" border max-height="38vh" empty-text="当前没有测点数据">
        <el-table-column prop="date" label="日期" width="140" />
        <el-table-column label="静压 (MPa)" min-width="130"><template #default="{ row }">{{ format(row.pressure) }}</template></el-table-column>
        <el-table-column label="累产气量 (10⁸m³)" min-width="160"><template #default="{ row }">{{ format(row.gas) }}</template></el-table-column>
        <el-table-column label="累产水量 (10⁴m³)" min-width="160"><template #default="{ row }">{{ format(row.water) }}</template></el-table-column>
      </el-table>
      <p v-if="detailLoading" role="status">正在读取智慧气藏保存的实测来源…</p>
      <el-alert v-if="detailError" :title="detailError" type="error" :closable="false" show-icon />
      <template v-if="detail">
        <p class="detail-note">{{ detail.message }}</p>
        <div class="source-content">
          <MaterialBalanceContent :key="`${detail.wellId}-${detail.resultId}`" read-only :node="sourceNode" :external-result="sharedSourceResult" />
        </div>
      </template>
      <template #footer><el-button @click="sourceVisible = false">关闭</el-button></template>
    </el-dialog>
  </section>
</template>

<style scoped>
.storage-mb-workspace { display:flex; flex:1; flex-direction:column; min-width:0; min-height:0; height:100%; overflow:hidden; background:#fff; color:#303133; font:12px Arial,"Microsoft YaHei",sans-serif; }
.module-bar { display:flex; flex:0 0 34px; align-items:stretch; background:#fafafa; border-bottom:1px solid #e4e7ed; }
.module-bar span { padding:0 12px; background:#f4d000; line-height:34px; font-weight:600; }
.workspace { display:flex; flex:1; min-width:0; min-height:0; overflow:hidden; }
.mb-params { width:258px; min-width:220px; max-width:360px; flex:0 0 258px; display:flex; flex-direction:column; min-height:0; border-right:1px solid #dcdfe6; }
.params-head { height:34px; flex:0 0 34px; display:flex; align-items:center; justify-content:space-between; padding:0 12px; border-bottom:1px solid #ebeef5; font-weight:600; }
.params-head button { border:0; background:none; color:#409eff; cursor:pointer; font-size:12px; }
.params-body { flex:1; min-height:0; overflow:auto; padding:12px; }
.field { display:flex; flex-direction:column; gap:6px; margin-bottom:14px; color:#606266; font-size:13px; }
.field :deep(.el-input), .field :deep(.el-select) { width:100%; }
.formula-card { margin:14px 0; padding:10px 11px; background:#f8f9fb; color:#606266; line-height:1.7; font-size:12px; }
.formula-card strong { display:block; margin-bottom:3px; color:#303133; }
.formula-card small { display:block; margin-top:3px; color:#7d8da3; font-size:11px; }
.section-heading { display:flex; align-items:center; justify-content:space-between; margin:14px 0 8px; padding-bottom:8px; border-bottom:1px solid #ebeef5; color:#303133; font-weight:600; }
.section-heading span,.well-count { color:#909399; font-size:12px; font-weight:400; }
.well-list { max-height:52vh; overflow:auto; border:1px solid #ebeef5; border-radius:3px; }
.well-item { display:flex; align-items:center; gap:7px; min-height:36px; padding:0 9px; border-bottom:1px solid #ebeef5; cursor:pointer; }
.well-item:last-child { border-bottom:0; }
.well-name { flex:1; min-width:0; overflow:hidden; color:#409eff; text-overflow:ellipsis; white-space:nowrap; }
.subtle-message { padding:12px 4px; color:#909399; font-size:12px; }
.load-notice { margin-top:8px; color:#d9822b; font-size:12px; line-height:1.6; }
.pressure-results { flex:1; min-width:0; min-height:0; display:flex; flex-direction:column; overflow:hidden; }
.dynamic-result-tabs { display:flex; flex:0 0 34px; align-items:stretch; border-bottom:1px solid #e4e7ed; background:#fafafa; overflow:hidden; }
.dynamic-result-tab-text { display:flex; align-items:center; padding:0 12px; background:#f4d000; font-weight:600; white-space:nowrap; }
.result-toolbar { display:flex; align-items:center; gap:10px; min-height:40px; padding:5px 12px; border-bottom:1px solid #e5e5e5; }
.chart-summary { margin-right:auto; color:#606266; font-size:12px; }
.save-status { color:#67c23a; font-size:12px; white-space:nowrap; }
.chart-view,.table-view,.source-view { flex:1; min-width:0; min-height:0; display:flex; flex-direction:column; overflow:hidden; }
.chart-frame { position:relative; flex:1; min-height:220px; overflow:hidden; }
.pressure-chart { width:100%; height:100%; }
.chart-empty { position:absolute; inset:0; display:flex; align-items:center; justify-content:center; flex-direction:column; gap:12px; color:#909399; pointer-events:none; }
.chart-empty :deep(.el-button) { pointer-events:auto; }
.table-holder { flex:1; min-width:0; min-height:0; overflow:hidden; }
.table-footer { display:flex; align-items:center; justify-content:space-between; min-height:38px; flex:0 0 38px; padding:0 16px; border-top:1px solid #ebeef5; color:#8a96a8; font-size:12px; }
.el-pagination { flex:0 0 38px; padding:8px 16px; }
.source-view { padding:0 12px 8px; }
.source-heading { display:flex; align-items:center; gap:8px; flex:0 0 36px; font-weight:600; }
.source-heading span { color:#909399; font-size:12px; font-weight:400; }
.source-table-holder { flex:1; min-height:100px; overflow:hidden; }
.partial-date-holder { flex:0 1 32%; }
.source-complete { padding:10px 0; color:#909399; }
.source-view .el-pagination { align-self:flex-end; }
.bottom-chart-tabs { display:flex; align-items:flex-end; flex:0 0 30px; border-top:1px solid #e4e7ed; background:#fff; overflow-x:auto; }
.bottom-chart-tabs button { height:30px; min-width:82px; padding:0 14px; border:0; border-right:1px solid #e4e7ed; background:#fff; color:#333; font-size:13px; cursor:pointer; white-space:nowrap; }
.bottom-chart-tabs button:hover { background:#fff8d8; }
.bottom-chart-tabs button.active { font-weight:600; box-shadow:inset 0 3px 0 #f2c811; }
.detail-note { color:#606266; font-size:12px; line-height:1.7; }
.excluded { color:#a45c08; }
.source-content { height:65vh; min-width:0; }
@media (max-width:900px) { .mb-params { width:220px; flex-basis:220px; } .result-toolbar { flex-wrap:wrap; } }
</style>
