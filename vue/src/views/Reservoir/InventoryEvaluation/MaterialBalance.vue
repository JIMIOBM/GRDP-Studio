<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { storageMaterialBalanceApi } from '@/api/storageMaterialBalance'
import { createStorageMaterialBalanceState, storageMaterialBalanceChart } from '@/utils/storageMaterialBalance'
import MaterialBalanceContent from '@/views/WellControlInventory/MaterialBalanceContent.vue'
import { toSharedMaterialBalanceResult } from '@/utils/storageMaterialBalanceSource'

const props = defineProps({ reservoir: { type: Object, default: null } })
const { result, loading, error, load, clear } = createStorageMaterialBalanceState(storageMaterialBalanceApi.aggregate)
const { result: detail, loading: detailLoading, error: detailError, load: loadDetail, clear: clearDetail } =
  createStorageMaterialBalanceState(storageMaterialBalanceApi.source, {
    keys: ['projectId', 'gasReservoirId', 'storageId', 'wellId', 'resultId'],
    validate: (data, scope) => data?.wellId === scope.wellId && data?.resultId === scope.resultId
      && Array.isArray(data?.inputRows) && Array.isArray(data?.resultRows) && Array.isArray(data?.regressionLine)
  })
const { result: availability, error: availabilityError, load: loadAvailability, clear: clearAvailability } =
  createStorageMaterialBalanceState(storageMaterialBalanceApi.availability, { validate: Array.isArray })
const tab = ref('data')
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
const rows = computed(() => result.value?.rows || [])
const pageRows = computed(() => rows.value.slice((page.value - 1) * 50, page.value * 50))
const skipped = computed(() => result.value?.skippedDates || [])
const skippedRows = computed(() => skipped.value.slice((skippedPage.value - 1) * 20, skippedPage.value * 20))
const source = computed(() => result.value?.wells.find(well => well.wellId === sourceId.value))
const sharedSourceResult = computed(() => toSharedMaterialBalanceResult(detail.value))
const sourceNode = computed(() => detail.value ? { wellName: detail.value.wellName } : null)
const outsideMeasured = computed(() => (availability.value || []).filter(well => !well.inStorage && well.measuredCount > 0).map(well => well.wellName))
const format = value => Number.isFinite(value) ? value.toLocaleString('zh-CN', { maximumFractionDigits: 4 }) : '—'
const scope = () => ({ projectId: props.reservoir?.projectId, gasReservoirId: props.reservoir?.gasReservoirId, storageId: props.reservoir?.storageId })
const reload = () => {
  page.value = 1; skippedPage.value = 1; sourceId.value = null
  sourceVisible.value = false
  clearDetail(); clearAvailability()
  return Promise.all([load(scope()), loadAvailability(scope())])
}
watch(() => [props.reservoir?.projectId, props.reservoir?.gasReservoirId, props.reservoir?.storageId], () => {
  detailsVisible.value = false; detailsTab.value = 'wells'; tab.value = 'data'
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
onBeforeUnmount(() => { destroyed = true; clear(); clearDetail(); clearAvailability(); disposeChart() })
</script>

<template>
  <section class="storage-mb" aria-label="库物质平衡">
    <div class="module-tabs"><div class="module-title">物质平衡</div></div>
    <div class="diagnostic-workspace">
      <!-- 沿用库诊断曲线的左参数 / 右结果布局与现有参数栏主题。 -->
      <aside class="params-panel water-parameter-theme">
        <div class="panel-head">参数设置</div>
        <div class="panel-body">
          <label class="field"><span>当前储气库</span><input :value="reservoir?.label || '未选择库'" readonly /></label>
          <label class="field"><span>物质平衡方法</span><input value="定容气藏 · 实测静压" readonly /></label>
          <div class="field">
            <span>井数据状态</span>
            <div class="coverage-status">
              <el-tag v-if="loading" type="info" size="small">读取中…</el-tag>
              <el-tag v-else-if="result" :type="rows.length ? 'success' : 'warning'" size="small">参与井 {{ result.includedWellCount }} / {{ result.wells.length }}</el-tag>
              <el-tag v-else type="info" size="small">尚未读取</el-tag>
            </div>
            <small class="field-hint">仅使用有效实测静压，无有效来源的井整口排除。</small>
          </div>
          <dl v-if="result" class="summary">
            <div><dt>共同日期</dt><dd>{{ rows.length }} 条</dd></div>
            <div><dt>未对齐日期</dt><dd>{{ skipped.length }} 条</dd></div>
            <div><dt>来源储量之和 (10⁸m³)</dt><dd>{{ format(result.sourceGasVolume) }}</dd></div>
          </dl>
          <p class="field-hint">来源储量之和不是库级回归结果。</p>
          <div class="action-buttons">
            <button type="button" class="calculate" :disabled="loading" @click="reload">{{ loading ? '读取中…' : '读取并汇总' }}</button>
            <el-button :disabled="!result && !error" @click="detailsVisible = true">查看明细</el-button>
          </div>
          <div class="section-label">智慧气藏实测来源</div>
          <label class="field">
            <span>库内来源井</span>
            <el-select v-model="sourceId" placeholder="选择实测来源井" :disabled="!result || loading" style="width: 100%">
              <el-option v-for="well in (result?.wells || []).filter(well => well.resultId)" :key="well.wellId" :value="well.wellId" :label="well.wellName" />
            </el-select>
          </label>
          <el-button class="source-button" :disabled="!source?.resultId || loading" @click="viewSource(source)">查看参数、数据和图</el-button>
          <p class="field-hint">查看原平台保存的单井来源，只读，不改原始数据。</p>
        </div>
      </aside>

      <main class="result-area">
        <div class="result-tabs"><div class="result-tab">{{ reservoir?.label || '当前库' }} · 物质平衡输入汇总</div></div>
        <div v-if="error" class="status-message"><el-alert :title="error" type="error" :closable="false" show-icon /></div>
        <div v-else-if="loading" class="status-message" role="status">正在读取库内井的实测静压结果…</div>
        <div v-else-if="result && !rows.length" class="status-message"><el-alert :title="result.message" type="warning" :closable="false" show-icon /></div>
        <div v-else class="result-note">压力按来源动态储量加权，气、水量求和；当前展示汇总输入，非库级回归结果。</div>

        <div v-show="tab === 'data'" class="data-view">
          <el-table :data="pageRows" border height="100%" empty-text="暂无汇总输入，请查看明细中的井来源与日期检查">
            <el-table-column prop="date" label="日期" width="140" />
            <el-table-column label="加权地层压力 (MPa)" min-width="180"><template #default="{ row }">{{ format(row.pressure) }}</template></el-table-column>
            <el-table-column label="累产气量合计 (10⁸m³)" min-width="190"><template #default="{ row }">{{ format(row.gas) }}</template></el-table-column>
            <el-table-column label="累产水量合计 (10⁴m³)" min-width="190"><template #default="{ row }">{{ format(row.water) }}</template></el-table-column>
          </el-table>
          <el-pagination v-model:current-page="page" :total="rows.length" :page-size="50" layout="total, prev, pager, next" />
        </div>
        <div v-if="tab === 'chart'" class="analysis-view">
          <div v-if="rows.length" ref="chartEl" class="pressure-chart" />
          <el-empty v-else description="暂无共同日期数据，未绘制曲线" />
        </div>
        <nav class="bottom-tabs" aria-label="物质平衡结果视图">
          <button type="button" class="bottom-chart-tab" :class="{ active: tab === 'data' }" :aria-pressed="tab === 'data'" @click="tab = 'data'">汇总输入数据</button>
          <button type="button" class="bottom-chart-tab" :class="{ active: tab === 'chart' }" :aria-pressed="tab === 'chart'" @click="tab = 'chart'">汇总压力曲线</button>
        </nav>
      </main>
    </div>

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
        <el-tab-pane :label="`日期检查 (${skipped.length})`" name="dates">
          <p class="detail-note">参与井缺少同日有效输入的日期整日跳过，不插值、不补零。</p>
          <el-table :data="skippedRows" border max-height="420" empty-text="暂无未对齐日期">
            <el-table-column prop="date" label="日期" width="140" />
            <el-table-column label="缺少同日有效输入的井"><template #default="{ row }">{{ row.missingWells.join('、') }}</template></el-table-column>
          </el-table>
          <el-pagination v-model:current-page="skippedPage" :total="skipped.length" :page-size="20" layout="total, prev, pager, next" />
        </el-tab-pane>
        <el-tab-pane label="项目数据覆盖" name="coverage">
          <p v-if="availabilityError" class="excluded">{{ availabilityError }}</p>
          <p v-if="outsideMeasured.length" class="detail-note">{{ outsideMeasured.join('、') }} 有实测静压结果，但未加入当前库，不会自动计入。</p>
          <el-table :data="availability || []" border max-height="420" empty-text="暂无覆盖信息">
            <el-table-column prop="wellName" label="井名" width="100" />
            <el-table-column label="当前库成员" width="120"><template #default="{ row }">{{ row.inStorage ? '是' : '否' }}</template></el-table-column>
            <el-table-column prop="measuredCount" label="实测静压结果数" />
            <el-table-column prop="calculatedCount" label="计算静压结果数（不参与汇总）" />
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="汇总规则" name="rules">
          <p class="detail-note">地层压力 = Σ(单井动态储量 × 同日地层压力) / Σ单井动态储量；累产气量、累产水量求和。</p>
          <p class="detail-note">只用实测静压，无有效来源的井整口排除，产气和产水也不计入。参与井仅取共同日期，不插值、不补零。</p>
          <p class="detail-note">来源储量之和不是库级回归结果；汇总压力曲线不是 p/Z 回归图，不据此直接计算库动态储量。读取不会更改原平台数据。</p>
        </el-tab-pane>
      </el-tabs>
      <template #footer><el-button @click="detailsVisible = false">关闭</el-button></template>
    </el-dialog>

    <el-dialog v-model="sourceVisible" :title="`智慧气藏实测来源 · ${source?.wellName || ''}`" width="94%" top="4vh" append-to-body destroy-on-close class="storage-mb-source">
      <p v-if="detailLoading" role="status">正在读取智慧气藏保存的实测来源…</p>
      <el-alert v-if="detailError" :title="detailError" type="error" :closable="false" show-icon />
      <template v-if="detail">
        <p class="detail-note">{{ detail.message }}。蓝点为回归点，灰点为原平台排除点。</p>
        <div class="source-content">
          <MaterialBalanceContent :key="`${detail.wellId}-${detail.resultId}`" read-only :node="sourceNode" :external-result="sharedSourceResult" />
        </div>
      </template>
      <template #footer><el-button @click="sourceVisible = false">关闭</el-button></template>
    </el-dialog>
  </section>
</template>

<style scoped>
/* 复用库诊断曲线 / 库产能对比的布局结构、参数栏主题与底部页签规格；只作用于本页。 */
.storage-mb { flex: 1; height: 100%; min-width: 0; min-height: 0; display: flex; flex-direction: column; overflow: hidden; background: #fff; color: #303133; }
.module-tabs { display: flex; flex-shrink: 0; border-bottom: 1px solid #e4e7ed; }
.module-title { background: #f4d000; padding: 8px 14px; font-weight: 600; }
.diagnostic-workspace { flex: 1; display: flex; min-width: 0; min-height: 0; }
.params-panel { width: 280px; min-width: 238px; max-width: 32%; display: flex; flex-direction: column; border-right: 1px solid #ddd; }
.panel-head { height: 34px; padding: 0 12px; display: flex; align-items: center; flex-shrink: 0; background: #f2f2f2; border-bottom: 1px solid #ddd; font-size: 13px; }
.panel-body { flex: 1; min-height: 0; overflow: auto; padding: 10px 14px; }
.field { display: block; margin-bottom: 12px; font-size: 13px; }
.field > span { display: block; margin-bottom: 6px; }
.field input { width: 100%; height: 30px; box-sizing: border-box; border: 1px solid #dcdfe6; border-radius: 3px; padding: 0 8px; color: #303133; background: #fafafa; }
.coverage-status { margin-bottom: 6px; }
.field-hint, .detail-note { color: #606266; font-size: 12px; line-height: 1.7; }
.field-hint { display: block; margin: 6px 0; }
.summary { margin: 12px 0 4px; font-size: 13px; }
.summary > div { display: flex; justify-content: space-between; gap: 8px; margin-bottom: 10px; }
.summary dd { margin: 0; font-variant-numeric: tabular-nums; }
.action-buttons { display: flex; flex-direction: column; gap: 8px; margin-top: 14px; }
.calculate { width: 100%; height: 32px; border: 0; border-radius: 3px; background: #111; color: #fff; cursor: pointer; }
.calculate:disabled { opacity: .6; cursor: not-allowed; }
.section-label { margin: 22px 0 12px; padding-bottom: 8px; border-bottom: 1px solid #ddd; font-size: 13px; }
.source-button { width: 100%; }
.result-area { flex: 1; min-width: 0; min-height: 0; display: flex; flex-direction: column; overflow: hidden; }
.result-tabs { height: 34px; flex-shrink: 0; display: flex; border-bottom: 1px solid #e4e7ed; }
.result-tab { min-width: 0; padding: 6px 14px; color: #409eff; border-bottom: 2px solid #409eff; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; }
.result-note, .status-message { flex-shrink: 0; padding: 8px 14px; color: #606266; font-size: 12px; }
.data-view { flex: 1; min-height: 0; display: flex; flex-direction: column; overflow: hidden; padding: 0 12px; }
.data-view > .el-table { flex: 1; min-height: 0; }
.el-pagination { flex-shrink: 0; padding: 8px 0; }
.analysis-view { flex: 1; min-height: 0; display: flex; flex-direction: column; overflow: hidden; }
.pressure-chart { flex: 1; min-height: 0; width: 100%; }
.bottom-tabs { display: flex; min-height: 34px; flex-shrink: 0; border-top: 1px solid #e4e7ed; }
.bottom-chart-tab { padding: 6px 18px; border: 0; border-right: 1px solid #e4e7ed; background: #fafafa; color: #606266; cursor: pointer; font-size: 13px; }
.bottom-chart-tab.active { color: #409eff; background: #fff; border-bottom: 2px solid #409eff; }
.excluded { color: #a45c08; }
.source-content { height: 65vh; min-width: 0; }
</style>
