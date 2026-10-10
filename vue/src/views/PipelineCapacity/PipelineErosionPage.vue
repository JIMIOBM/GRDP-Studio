<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PipelineParameterPanel from './PipelineParameterPanel.vue'
import PipelineNumber from './PipelineNumber.vue'
import PipelineTimeChart from './PipelineTimeChart.vue'
import PipelineErosionImportDialog from './PipelineErosionImportDialog.vue'
import { boundaryCases } from '@/utils/pipelineBoundary'
import { formatOperatingTime } from '@/utils/pipelineTime'
import { EROSION_INPUT_FIELDS, erosionSegment, setErosionParameter, erosionDataRows, erosionChartSeries, erosionStatusLabel } from '@/utils/pipelineErosion'
import { erosionImportContextStamp } from '@/utils/pipelineErosionImport'

const props = defineProps({ state: { type: Object, required: true } })
const s = computed(() => props.state), selectedId = ref(''), metric = ref('velocity'), importVisible = ref(false), dataPage = ref(1)
const resultRowKey = ref('')
const importedFileName = ref(''), pageSize = 50
const graph = computed(() => s.value.savedGraph)
const pipes = computed(() => graph.value?.edges || [])
const cases = computed(() => boundaryCases(s.value.form.boundary))
const settings = computed(() => s.value.form.constraints?.erosion || {})
const selected = computed(() => pipes.value.find(edge => edge.id === selectedId.value))
const defaults = computed(() => erosionSegment(settings.value, selectedId.value))
const disabled = computed(() => s.value.batchBusy || s.value.busy || s.value.topologyLoading)
const liquidSources = computed(() => s.value.erosionLiquidSources || [])
const liquidSource = computed(() => liquidSources.value.find(item => String(item.id) === String(settings.value.liquidPvtId)))
const waterMethods = computed(() => ({ volume: ['McCain 方法', 'Standing 方法'][liquidSource.value?.volumeFactorMethod] || '—',
  compressibility: ['Meehan 方法', 'Dodson-Standing 方法'][liquidSource.value?.compressibilityMethod] || '—' }))
const rows = computed(() => erosionDataRows(graph.value, cases.value, settings.value, s.value.batchResult, !s.value.batchStale))
const visibleRows = computed(() => selectedId.value ? rows.value.filter(row => row.edgeId === selectedId.value) : rows.value)
const pagedRows = computed(() => visibleRows.value.slice((dataPage.value - 1) * pageSize, dataPage.value * pageSize))
const resultRow = computed(() => rows.value.find(row => row.rowKey === resultRowKey.value))
const series = computed(() => s.value.batchStale ? [] : erosionChartSeries(rows.value, selectedId.value, metric.value))
const emptyText = computed(() => s.value.batchBusy ? '正在处理全部工况…' : s.value.batchStale ? '计算条件已变化，请重新计算全部工况。'
  : !pipes.value.length ? '请先保存管网拓扑。' : !cases.value.length ? '请先保存边界工况。' : '暂无可绘制结果，请查看数据列表中的判断说明。')
function ensureSettings() {
  s.value.form.constraints ||= {}
  return (s.value.form.constraints.erosion ||= { liquidPvtId: null, segments: [], cases: [] })
}
function setDefault(key, value) { if (selectedId.value && !disabled.value) setErosionParameter(ensureSettings(), selectedId.value, key, value) }
function setCase(row, key, value) { if (!disabled.value) setErosionParameter(ensureSettings(), row.edgeId, key, value === '' ? null : Number(value), row.caseId) }
function resetCase(row) {
  if (!disabled.value) ensureSettings().cases = (settings.value.cases || []).filter(item => item.caseId !== row.caseId || item.edgeId !== row.edgeId)
}
function setLiquid(event) { if (!disabled.value) ensureSettings().liquidPvtId = event.target.value === '' ? null : Number(event.target.value) }
function overridden(row, key) { return row.overrideKeys.includes(key) }
async function acceptImport(payload) {
  if (disabled.value) return
  const previous = settings.value, draft = JSON.stringify(previous)
  try { await ElMessageBox.confirm('导入将替换全部管道共用参数和各工况冲蚀参数。', '导入冲蚀参数', { confirmButtonText: '替换参数', cancelButtonText: '保留当前数据' }) }
  catch { return }
  if (disabled.value || previous !== settings.value || draft !== JSON.stringify(settings.value)
    || payload.contextStamp !== erosionImportContextStamp(graph.value, s.value.topologyRevision, s.value.context, cases.value)) {
    ElMessage.warning('当前数据已变化，请重新导入。'); return
  }
  Object.assign(ensureSettings(), { segments: payload.segments, cases: payload.cases })
  importedFileName.value = payload.fileName; s.value.panel = 'data'
  ElMessage.success('冲蚀参数已导入，请计算后保存。')
}
watch(pipes, values => { if (selectedId.value && !values.some(edge => edge.id === selectedId.value)) selectedId.value = '' }, { immediate: true })
watch(() => [selectedId.value, visibleRows.value.length], () => { dataPage.value = 1 })
</script>

<template>
  <div class="workspace-body erosion-body" data-page="erosion">
    <PipelineParameterPanel>
      <label class="field"><span>选择管道</span><select v-model="selectedId" :disabled="disabled || !pipes.length"><option value="">全部管道</option><option v-for="pipe in pipes" :key="pipe.id" :value="pipe.id">{{ pipe.name }}</option></select></label>
      <button type="button" class="import-button" :disabled="disabled || !pipes.length" @click="importVisible = true">本地导入</button>
      <p v-if="importedFileName" class="imported-file" :title="importedFileName">{{ importedFileName }}</p>
      <div class="section-heading">物性来源<i /></div>
      <dl class="source-values"><dt>气相物性</dt><dd>{{ s.gasPropertyConfig?.pvtName || '当前井已保存 PVT 模型' }}</dd></dl>
      <label class="field liquid-source"><span>液相来源 · 地层水 PVT</span><select :value="settings.liquidPvtId ?? ''" :disabled="disabled || s.erosionLiquidLoading" @change="setLiquid"><option value="">{{ s.erosionLiquidLoading ? '正在加载…' : '请选择地层水 PVT' }}</option><option v-if="settings.liquidPvtId && !liquidSource" :value="settings.liquidPvtId">原液相来源不可用，请重新选择</option><option v-for="item in liquidSources" :key="item.id" :value="item.id">{{ item.name }}{{ item.issue ? '（资料待补充）' : '' }}</option></select></label>
      <p v-if="s.erosionLiquidError" class="source-error" role="alert">{{ s.erosionLiquidError }} <button type="button" class="text-button" :disabled="disabled || s.erosionLiquidLoading" @click="s.refreshErosionLiquidSources">重试</button></p>
      <template v-if="liquidSource"><dl class="source-values"><dt>矿化度（mg/L）</dt><dd>{{ s.f(liquidSource.salinityMgL) }}</dd><dt>原始地层压力（MPa，绝压）</dt><dd>{{ s.f(liquidSource.originalPressureMpa) }}</dd><dt>体积系数 / 压缩系数方法</dt><dd>{{ waterMethods.volume }} / {{ waterMethods.compressibility }}</dd></dl><p v-if="liquidSource.issue" class="source-error">{{ liquidSource.issue }}</p></template>
      <template v-if="selected">
        <div class="section-heading">管道共用参数<i /></div>
        <p class="hint">由你填写，供该管道各工况使用，系统不预设数值。表格内可单独填写工况值；留空时使用共用值。</p>
        <PipelineNumber v-for="field in EROSION_INPUT_FIELDS" :key="field.key" :model-value="defaults[field.key] ?? null" :label="`${field.label}（${field.unit}）`" :min="field.min" :max="field.max" :disabled="disabled" @update:model-value="setDefault(field.key, $event)" />
      </template>
      <p v-else class="hint">选择单条管道可填写共用参数，也可在表格逐工况填写。参数由你提供，系统不预设数值。</p>
      <div class="side-actions"><button type="button" class="calculate" :disabled="disabled || !s.canCalculate" @click="s.calculate">{{ s.batchBusy ? '计算中…' : '计算' }}</button><button type="button" class="save" :disabled="disabled || !s.canBatchSave" @click="s.savePage">保存</button></div>
      <p class="hint">计算全部工况、全部管道，并同步更新管流结果。采用井筒冲蚀计算方法，以 P110 含砂模型作筛查；比较流速定义待核实，结果为参考值。</p>
      <p v-if="s.gasPropertyError" class="source-error" role="alert">{{ s.gasPropertyError }}</p>
      <p v-if="s.form.thermalMode === 'heat' && s.thermalSourceError" class="source-error" role="alert">{{ s.thermalSourceError }}</p>
    </PipelineParameterPanel>
    <main class="result-area">
      <div v-if="s.panel !== 'analysis'" class="scroll-content">
        <div class="data-toolbar"><span>{{ selected?.name || '全部管道' }} · 全部工况</span><small>{{ visibleRows.length }} 条数据{{ s.batchResult && !s.batchStale ? (s.batchSaved ? ' · 已保存' : ' · 未保存') : '' }}</small></div>
        <el-table class="erosion-table" :data="pagedRows" row-key="rowKey" border aria-label="全部工况管道冲蚀数据">
          <el-table-column type="index" label="序号" width="46" :index="index => (dataPage - 1) * pageSize + index + 1" />
          <el-table-column label="工况时间" min-width="140"><template #default="scope">{{ formatOperatingTime(scope.row.operatingAt) }}</template></el-table-column>
          <el-table-column prop="name" label="管道名称" min-width="110"><template #default="scope"><span>{{ scope.row.name }}</span><button v-if="scope.row.overridden" type="button" class="text-button reset-default" :disabled="disabled" title="清除本工况值，改用你填写的管道共用参数" @click="resetCase(scope.row)">使用共用参数</button></template></el-table-column>
          <el-table-column v-for="field in EROSION_INPUT_FIELDS" :key="field.key" :label="`${field.label}（${field.unit}）`" min-width="100"><template #header><span>{{ field.label }}</span><br /><span>（{{ field.unit }}）</span></template><template #default="scope"><input class="parameter-input" :class="{ inherited: !overridden(scope.row, field.key) }" type="number" step="any" :min="field.min" :max="field.max" :value="scope.row[field.key]" :title="overridden(scope.row, field.key) ? '当前工况值；清空后使用用户填写的共用值' : scope.row[field.key] == null ? '尚未填写；可在本行填写或设置管道共用参数' : '使用管道共用值；输入后仅覆盖当前工况'" :aria-label="`${formatOperatingTime(scope.row.operatingAt)} ${scope.row.name} ${field.label}`" :disabled="disabled" @change="setCase(scope.row, field.key, $event.target.value)" /></template></el-table-column>
          <el-table-column label="判断结果" min-width="130"><template #default="scope"><button type="button" class="text-button result-status" :class="scope.row.status" :title="scope.row.reason" :aria-label="`${formatOperatingTime(scope.row.operatingAt)} ${scope.row.name} 查看结果`" @click="resultRowKey = scope.row.rowKey">{{ erosionStatusLabel(scope.row.status) }}</button></template></el-table-column>
          <template #empty>{{ emptyText }}</template>
        </el-table>
        <el-pagination v-if="visibleRows.length > pageSize" v-model:current-page="dataPage" :page-size="pageSize" :total="visibleRows.length" layout="prev, pager, next, total" />
        <p class="evaluation-note">仅填写持液率、含砂率和砂粒密度，其余计算数据自动读取。点击判断结果查看流速及说明，曲线见结果分析。</p>
      </div>
      <div v-else class="erosion-analysis">
        <div class="chart-switch" role="radiogroup" aria-label="切换冲蚀结果图"><label><input v-model="metric" type="radio" name="pipeline-erosion-chart" value="velocity" />流速对比</label><label><input v-model="metric" type="radio" name="pipeline-erosion-chart" value="ratio" />利用率</label></div>
        <PipelineTimeChart :series="series" :title="`${selected?.name || '全部管道'} · ${metric === 'ratio' ? '流速利用率' : '流速对比'}`" :unit="metric === 'ratio' ? '利用率（无量纲）' : 'm/s'" :empty-text="emptyText" />
        <p class="analysis-note">含砂模型筛查参考；利用率 = 同点实际流速 / 临界冲蚀流速。低于 1 不代表已确认安全，判断说明见数据列表。</p>
      </div>
      <div class="bottom-tabs"><button :class="{ active: s.panel !== 'analysis' }" @click="s.panel = 'data'">数据列表</button><button :class="{ active: s.panel === 'analysis' }" @click="s.panel = 'analysis'">结果分析</button></div>
    </main>
    <el-dialog :model-value="!!resultRow" title="冲蚀计算结果" width="560px" append-to-body @update:model-value="value => { if (!value) resultRowKey = '' }">
      <template v-if="resultRow">
        <p class="result-caption">{{ resultRow.name }} · {{ formatOperatingTime(resultRow.operatingAt) }}</p>
        <dl class="result-values"><div><dt>实际流速（m/s）</dt><dd>{{ s.f(resultRow.actualVelocityMs) }}</dd></div><div><dt>临界冲蚀流速（m/s）</dt><dd>{{ s.f(resultRow.criticalVelocityMs) }}</dd></div><div><dt>流速利用率</dt><dd>{{ s.f(resultRow.velocityRatio) }}</dd></div></dl>
        <p :class="resultRow.status">{{ erosionStatusLabel(resultRow.status) }}</p>
        <p class="result-reason">{{ resultRow.reason }}</p>
      </template>
    </el-dialog>
    <PipelineErosionImportDialog v-model="importVisible" :graph="graph" :topology-revision="s.topologyRevision" :context="s.context" :cases="cases" :settings="settings" @imported="acceptImport" />
  </div>
</template>

<style scoped>
.erosion-table :deep(.cell){padding:0 7px;overflow-wrap:anywhere}.reset-default{display:block;font-size:11px;line-height:20px}.result-status{text-align:left;text-decoration:underline;text-underline-offset:3px;white-space:normal;line-height:1.8}.result-caption{margin:0 0 18px;color:#666}.result-values{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}.result-values dt{font-size:12px;color:#777}.result-values dd{margin:8px 0;font-size:20px;color:#333}.result-reason{line-height:1.8;overflow-wrap:anywhere}
.import-button{width:100%;height:30px;border:1px solid #bbb;background:#fff;color:#333;cursor:pointer}.imported-file{font-size:11px;color:#777;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.source-values{margin:0;font-size:12px;line-height:1.8;overflow-wrap:anywhere}.source-values dt{color:#777;margin-top:8px}.source-values dd{margin:0;color:#333}.liquid-source{margin-top:12px}.text-button{padding:0;border:0;background:transparent;color:#337ab7;font:inherit;cursor:pointer}.parameter-input{width:100%;height:28px;padding:0 5px;box-sizing:border-box;border:1px solid #bbb;border-radius:2px;background:#fff;color:#333;font:inherit}.parameter-input.inherited{color:#777}.parameter-input:focus{outline:1px solid #409eff;border-color:#409eff}.parameter-input:disabled{background:#f7f7f7}.erosion-analysis{display:flex;flex:1;min-height:0;flex-direction:column;background:#fff}.chart-switch{display:flex;align-items:center;flex-wrap:wrap;gap:14px;min-height:38px;padding:0 12px;border-bottom:1px solid #ddd;flex-shrink:0;color:#666}.chart-switch label{display:inline-flex;align-items:center;gap:5px;cursor:pointer}.chart-switch input{margin:0}.source-error,.reference_above{color:#c0524a}.reference_below,.reference_at,.not_applicable{color:#aa813f}.not_evaluated{color:#888}.data-toolbar small{color:#888}.analysis-note{margin:0;padding:6px 12px;color:#888;font-size:11px;line-height:1.7;border-top:1px solid #eee}.evaluation-note{font-size:11px;line-height:1.8;color:#888;padding:0 12px}button:disabled{cursor:not-allowed;opacity:.55}
</style>
