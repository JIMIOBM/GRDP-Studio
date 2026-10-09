<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { storageMaterialBalanceApi } from '@/api/storageMaterialBalance'
import { createStorageMaterialBalanceState, storageMaterialBalanceChart } from '@/utils/storageMaterialBalance'
import { storageMaterialBalanceDemoResult, storageMaterialBalanceDemoWells } from '@/utils/storageMaterialBalanceDemo'
import MaterialBalanceContent from '@/views/WellControlInventory/MaterialBalanceContent.vue'
import { toSharedMaterialBalanceResult } from '@/utils/storageMaterialBalanceSource'

const props = defineProps({ reservoir: { type: Object, default: null } })
const selectedWellIds = ref([])
const demoMode = ref(false)
const saving = ref(false)
const savedSnapshot = ref(null)
const isSavedResult = ref(false)
const databaseError = ref('')
const demoWells = storageMaterialBalanceDemoWells()
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
const detailsVisible = ref(false)
const sourceVisible = ref(false)
const detailsTab = ref('wells')
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
const canSave = computed(() => !demoMode.value && !!result.value && !loading.value && !saving.value
  && !isSavedResult.value && selectedWellIds.value.length > 0)
const source = computed(() => result.value?.wells.find(well => well.wellId === sourceId.value))
const memberWells = computed(() => demoMode.value ? demoWells : (availability.value || []).filter(well => well.inStorage))
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
  demoMode.value = false
  isSavedResult.value = false
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
      && Array.isArray(savedResult?.wells) && Array.isArray(savedResult?.rows) && Array.isArray(savedResult?.partialDates)) {
      selectedWellIds.value = savedIds
      result.value = savedResult
      savedSnapshot.value = snapshot
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
  isSavedResult.value = false
  savedSnapshot.value = null
  databaseError.value = ''
  selectedWellIds.value = (ids || []).map(Number)
  page.value = 1; skippedPage.value = 1; sourceId.value = null
  sourceVisible.value = false
  clearDetail()
  if (demoMode.value) {
    clear()
    result.value = storageMaterialBalanceDemoResult(selectedWellIds.value)
    return
  }
  if (!selectedWellIds.value.length) { clear(); return }
  return load(scope())
}
const loadDemo = () => {
  reloadVersion++
  saving.value = false
  demoMode.value = true
  isSavedResult.value = false
  savedSnapshot.value = null
  databaseError.value = ''
  selectedWellIds.value = demoWells.map(well => well.wellId)
  page.value = 1; skippedPage.value = 1; sourceId.value = null
  sourceVisible.value = false
  clear(); clearDetail()
  result.value = storageMaterialBalanceDemoResult(selectedWellIds.value)
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
    result.value = snapshot.result
    savedSnapshot.value = snapshot
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
  detailsVisible.value = false; detailsTab.value = 'wells'; tab.value = 'chart'
  reload()
}, { immediate: true })
const viewSource = well => {
  if (!well?.resultId) { sourceId.value = null; clearDetail(); return }
  sourceId.value = well.wellId
  detailsVisible.value = false
  sourceVisible.value = true
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
    <aside class="mb-params">
      <div class="params-head"><span>参数设置</span><button type="button" @click="reload">重新读取</button></div>
      <div class="params-body">
        <div class="field">
          <label>当前储气库</label>
          <el-input size="small" readonly :model-value="reservoir?.label || '未选择储气库'" />
        </div>
        <div class="formula-card">
          <div>加权地层压力 = Σ(Gᵢ × Pᵢ) / ΣGᵢ</div>
        </div>

        <div class="section-heading">参与井 <span>{{ selectedWellIds.length }} / {{ memberWells.length }}</span></div>
        <div v-if="memberWells.length" class="well-list">
          <label v-for="well in memberWells" :key="well.wellId" class="well-item">
            <el-checkbox :model-value="selectedWellSet.has(String(well.wellId))" :disabled="loading && !demoMode"
              @change="value => setWellSelected(well.wellId, value)" />
            <span class="well-name">{{ well.wellName }}</span>
            <span class="well-count">{{ demoMode ? `${well.measuredCount} 个测点` : (well.measuredCount ? `${well.measuredCount} 个实测结果` : '无实测结果') }}</span>
          </label>
        </div>
        <div v-else-if="loading" class="subtle-message">正在读取当前库成员井…</div>
        <div v-else class="subtle-message">当前储气库没有成员井</div>
        <div v-if="error || availabilityError || databaseError" class="load-notice" role="status">{{ error || availabilityError || databaseError }}</div>
      </div>
    </aside>

    <main class="pressure-results">
      <div class="result-tabs">
        <button :class="{ active: tab === 'chart' }" type="button" @click="tab = 'chart'">压力对比图</button>
        <button :class="{ active: tab === 'data' }" type="button" @click="tab = 'data'">汇总数据</button>
      </div>

      <div v-show="tab === 'chart'" class="chart-view">
        <div class="chart-toolbar">
          <span class="chart-summary">{{ demoMode ? '演示数据 · ' : '' }}已选 {{ selectedWellIds.length }} / {{ memberWells.length }} 口井 · {{ rows.length }} 个日期</span>
          <el-button size="small" @click="demoMode ? reload() : loadDemo()">{{ demoMode ? '返回真实数据' : '演示数据' }}</el-button>
          <span v-if="isSavedResult" class="save-status">已保存到数据库</span>
          <el-button size="small" type="primary" plain :loading="saving" :disabled="!canSave" @click="saveResult">保存到数据库</el-button>
          <el-button size="small" :disabled="!result && !error" @click="detailsVisible = true">查看明细</el-button>
        </div>
        <div class="chart-frame">
          <div v-if="rows.length" ref="chartEl" class="pressure-chart" aria-label="库物质平衡压力对比图" />
          <div v-else class="chart-empty">
            <span>{{ loading ? '正在读取压力数据…' : error || result?.message || '暂无可绘制数据' }}</span>
            <el-button v-if="!loading && !demoMode" size="small" type="primary" plain @click="loadDemo">载入演示数据</el-button>
          </div>
        </div>
      </div>

      <div v-show="tab === 'data'" class="table-view">
        <div class="table-toolbar">
          <span class="chart-summary">{{ demoMode ? '演示数据' : '当前库实测汇总' }} · {{ rows.length }} 个日期</span>
          <span v-if="isSavedResult" class="save-status">已保存到数据库</span>
          <el-button size="small" type="primary" plain :loading="saving" :disabled="!canSave" @click="saveResult">保存到数据库</el-button>
          <el-button size="small" :disabled="!result && !error" @click="detailsVisible = true">查看明细</el-button>
        </div>
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
    </main>

    <el-dialog v-model="detailsVisible" title="库物质平衡 · 数据明细" width="88%" top="6vh" append-to-body class="storage-mb-details">
      <el-tabs v-model="detailsTab">
        <el-tab-pane label="井与数据来源" name="wells">
          <el-table :data="result?.wells || []" border max-height="420" empty-text="该库没有可显示的成员数据">
            <el-table-column prop="wellName" label="井名" width="90" />
            <el-table-column prop="resultId" label="实测结果ID" width="105" />
            <el-table-column label="动态储量 (10⁸m³)" width="155"><template #default="{ row }">{{ format(row.gasVolume) }}</template></el-table-column>
            <el-table-column label="权重" width="90"><template #default="{ row }">{{ Number.isFinite(row.weight) ? `${format(row.weight * 100)}%` : '—' }}</template></el-table-column>
            <el-table-column label="R²" width="85"><template #default="{ row }">{{ format(row.rSquared) }}</template></el-table-column>
            <el-table-column label="状态 / 原因" min-width="240"><template #default="{ row }">
              <span>{{ row.reason }}</span>
              <div v-if="row.warning" class="excluded">{{ row.warning }}</div>
              <div v-if="row.discardedRows">跳过 {{ row.discardedRows }} 条已排除、重复日期或无效输入行</div>
            </template></el-table-column>
            <el-table-column label="智慧气藏来源" width="165"><template #default="{ row }">
              <el-button link type="primary" :disabled="!row.resultId" @click="viewSource(row)">查看参数、数据和图</el-button>
            </template></el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane :label="`日期覆盖 (${partialDates.length})`" name="dates">
          <el-table :data="partialRows" border max-height="420" empty-text="日期完整">
            <el-table-column prop="date" label="日期" width="140" />
            <el-table-column label="该日无有效数据的井"><template #default="{ row }">{{ row.missingWells.join('、') }}</template></el-table-column>
          </el-table>
          <el-pagination v-model:current-page="skippedPage" :total="partialDates.length" :page-size="20" layout="total, prev, pager, next" />
        </el-tab-pane>
        <el-tab-pane label="项目数据覆盖" name="coverage">
          <p v-if="availabilityError" class="excluded">{{ availabilityError }}</p>
          <el-table :data="availability || []" border max-height="420" empty-text="暂无覆盖信息">
            <el-table-column prop="wellName" label="井名" width="100" />
            <el-table-column label="当前库成员" width="120"><template #default="{ row }">{{ row.inStorage ? '是' : '否' }}</template></el-table-column>
            <el-table-column prop="measuredCount" label="实测静压结果数" />
            <el-table-column prop="calculatedCount" label="计算静压结果数（不参与汇总）" />
          </el-table>
        </el-tab-pane>
      </el-tabs>
      <template #footer><el-button @click="detailsVisible = false">关闭</el-button></template>
    </el-dialog>

    <el-dialog v-model="sourceVisible" :title="`智慧气藏实测来源 · ${source?.wellName || ''}`" width="94%" top="4vh" append-to-body destroy-on-close class="storage-mb-source">
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
.storage-mb-workspace { display:flex; flex:1; min-width:0; min-height:0; height:100%; overflow:hidden; background:#fff; color:#303133; font:14px Arial,"Microsoft YaHei",sans-serif; }
.mb-params { width:300px; min-width:270px; max-width:360px; flex:0 0 300px; display:flex; flex-direction:column; min-height:0; border-right:1px solid #dcdfe6; }
.params-head { height:42px; flex:0 0 42px; display:flex; align-items:center; justify-content:space-between; padding:0 14px; border-bottom:1px solid #ebeef5; font-weight:600; }
.params-head button { border:0; background:none; color:#409eff; cursor:pointer; font-size:12px; }
.params-body { flex:1; min-height:0; overflow:auto; padding:14px 16px; }
.field { display:flex; flex-direction:column; gap:6px; margin-bottom:14px; color:#606266; font-size:13px; }
.field :deep(.el-input), .field :deep(.el-select) { width:100%; }
.formula-card { margin:4px 0 18px; padding:10px 11px; background:#f4f7fb; color:#336699; line-height:1.7; font-size:13px; }
.formula-card small { display:block; margin-top:3px; color:#7d8da3; font-size:11px; }
.section-heading { display:flex; align-items:center; justify-content:space-between; margin:14px 0 8px; padding-bottom:8px; border-bottom:1px solid #ebeef5; color:#303133; font-weight:600; }
.section-heading span,.well-count { color:#909399; font-size:12px; font-weight:400; }
.well-list { max-height:calc(100% - 270px); overflow:auto; border:1px solid #ebeef5; border-radius:3px; }
.well-item { display:flex; align-items:center; gap:7px; min-height:42px; padding:0 9px; border-bottom:1px solid #ebeef5; cursor:pointer; }
.well-item:last-child { border-bottom:0; }
.well-name { flex:1; min-width:0; overflow:hidden; color:#409eff; text-overflow:ellipsis; white-space:nowrap; }
.subtle-message { padding:12px 4px; color:#909399; font-size:12px; }
.load-notice { margin-top:8px; color:#d9822b; font-size:12px; line-height:1.6; }
.pressure-results { flex:1; min-width:0; min-height:0; display:flex; flex-direction:column; overflow:hidden; }
.result-tabs { display:flex; flex:0 0 44px; height:44px; align-items:stretch; border-bottom:1px solid #dcdfe6; background:#fafafa; }
.result-tabs button { min-width:100px; padding:0 16px; border:0; border-bottom:2px solid transparent; background:transparent; color:#606266; font-size:14px; cursor:pointer; }
.result-tabs button.active { border-bottom-color:#409eff; color:#409eff; font-weight:600; }
.chart-view,.table-view { flex:1; min-width:0; min-height:0; display:flex; flex-direction:column; overflow:hidden; }
.chart-toolbar { display:flex; align-items:center; gap:8px; min-height:50px; padding:6px 16px; color:#606266; font-size:13px; }
.chart-summary { margin-right:auto; color:#8a96a8; font-size:12px; white-space:nowrap; }
.save-status { color:#67c23a; font-size:12px; white-space:nowrap; }
.chart-frame { position:relative; flex:1; min-height:220px; overflow:hidden; }
.pressure-chart { width:100%; height:100%; }
.chart-empty { position:absolute; inset:90px 0 0; display:flex; align-items:center; justify-content:center; flex-direction:column; gap:12px; color:#909399; pointer-events:none; }
.chart-empty :deep(.el-button) { pointer-events:auto; }
.table-toolbar { display:flex; align-items:center; justify-content:space-between; gap:12px; min-height:54px; padding:7px 16px; color:#738198; font-size:12px; }
.table-holder { flex:1; min-width:0; min-height:0; padding:0 14px; overflow:hidden; }
.table-footer { display:flex; align-items:center; justify-content:space-between; min-height:38px; flex:0 0 38px; padding:0 16px; border-top:1px solid #ebeef5; color:#8a96a8; font-size:12px; }
.el-pagination { flex:0 0 38px; padding:8px 16px; }
.detail-note { color:#606266; font-size:12px; line-height:1.7; }
.excluded { color:#a45c08; }
.source-content { height:65vh; min-width:0; }
@media (max-width:900px) { .mb-params { width:250px; min-width:220px; flex-basis:250px; } }
</style>
