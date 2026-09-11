<script setup>
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PipelineNumber from './PipelineNumber.vue'
import PipelineBoundaryImportDialog from './PipelineBoundaryImportDialog.vue'
import { MAX_BOUNDARY_CASES, boundaryCases, activeBoundaryCase, createBoundaryCase, boundaryRows, boundarySummary, orphanBoundaryRows } from '@/utils/pipelineBoundary'
import { formatOperatingTime } from '@/utils/pipelineTime'

const props = defineProps({ state: { type: Object, required: true } })
const s = computed(() => props.state)
const collapsed = ref(false), importVisible = ref(false), importedFileName = ref('')
const cases = computed(() => boundaryCases(s.value.form.boundary))
const activeCase = computed(() => activeBoundaryCase(s.value.form.boundary))
const rows = computed(() => boundaryRows(s.value.savedGraph, s.value.form.boundary))
const downstreamNodes = computed(() => rows.value.filter(row => row.role !== 'supply'))
const caseRows = computed(() => cases.value.map((record, index) => {
  const nodes = boundaryRows(s.value.savedGraph, { ...s.value.form.boundary, activeCaseId: record.id })
  return { record, index, supply: nodes.find(node => node.role === 'supply')?.record,
    downstream: Object.fromEntries(nodes.filter(node => node.role !== 'supply').map(node => [node.nodeId, node.record])) }
}))
const orphans = computed(() => orphanBoundaryRows(s.value.form.boundary, s.value.savedGraph))
const summary = computed(() => boundarySummary(s.value.form.boundary, s.value.savedGraph))
const disabled = computed(() => s.value.busy || s.value.topologyLoading)
const conditionFields = ['supplyRate10k', 'withdrawalRate10k', 'pressureMpa', 'temperatureC']
function hasCaseData(record) {
  if (record.operatingAt) return true
  return (record.nodes || []).some(node => conditionFields.some(field => node[field] != null))
}
function number(record, field, event) { if (record) record[field] = event.target.value === '' ? null : Number(event.target.value) }
function selectCase(record) { if (!disabled.value && s.value.form.boundary) s.value.form.boundary.activeCaseId = record.id }
function addCase() {
  if (disabled.value || !rows.value.length) return
  if (cases.value.length >= MAX_BOUNDARY_CASES) { ElMessage.warning(`最多支持 ${MAX_BOUNDARY_CASES} 组工况。`); return }
  const record = createBoundaryCase(s.value.savedGraph)
  if (!s.value.form.boundary) s.value.form.boundary = { topologyRevision: s.value.topologyRevision, activeCaseId: record.id, cases: [] }
  s.value.form.boundary.cases.push(record)
  selectCase(record)
}
function removeCase(record) {
  if (disabled.value) return
  const boundary = s.value.form.boundary, index = boundary.cases.findIndex(item => item.id === record.id)
  if (index < 0) return
  boundary.cases.splice(index, 1)
  if (boundary.activeCaseId === record.id) boundary.activeCaseId = boundary.cases[Math.min(index, boundary.cases.length - 1)]?.id ?? null
}
function removeOrphan(record) {
  if (activeCase.value) activeCase.value.nodes = activeCase.value.nodes.filter(node => node !== record)
}
async function acceptImport({ cases: imported, fileName }) {
  if (!imported?.length || disabled.value) return
  const previous = s.value.form.boundary, scope = JSON.stringify(s.value.context), topologyRevision = s.value.topologyRevision
  if (cases.value.some(hasCaseData)) {
    try { await ElMessageBox.confirm(`导入将替换当前 ${cases.value.length} 组工况。`, '导入边界条件', { confirmButtonText: '替换工况', cancelButtonText: '保留当前数据' }) }
    catch { return }
  }
  if (previous !== s.value.form.boundary || scope !== JSON.stringify(s.value.context) || topologyRevision !== s.value.topologyRevision) {
    ElMessage.warning('当前边界数据已变化，请重新导入。'); return
  }
  s.value.form.boundary = { topologyRevision: s.value.topologyRevision, activeCaseId: imported[0].id, cases: imported }
  importedFileName.value = fileName
  ElMessage.success(`已导入 ${imported.length} 组工况，点击保存写入数据库`)
}
</script>

<template>
  <div class="workspace-body boundary-body">
    <aside class="parameter-panel" :class="{ collapsed }">
      <button v-if="collapsed" class="collapsed-tab" type="button" aria-label="展开参数设置" :aria-expanded="false" @click="collapsed = false">参数设置</button>
      <template v-else>
        <div class="panel-heading"><span>参数设置</span><button class="collapse-button" type="button" title="收起参数设置" aria-label="收起参数设置" :aria-expanded="true" @click="collapsed = true"><svg width="14" height="14" viewBox="0 0 24 24" fill="#777" aria-hidden="true"><path d="M16,12V4H17V2H7V4H8V12L6,14V16H11.2V22H12.8V16H18V14L16,12Z" /></svg></button></div>
        <fieldset class="parameter-form" :disabled="disabled">
          <button type="button" class="import-button" :disabled="!rows.length" @click="importVisible = true">本地导入</button>
          <p v-if="importedFileName" class="imported-file" :title="importedFileName">{{ importedFileName }}</p>
          <div class="section-heading">标准状态<i /></div>
          <PipelineNumber v-model="s.form.standardPressurePa" label="标况压力（Pa，绝压）" :min="0" />
          <PipelineNumber v-model="s.form.standardTemperatureK" label="标况温度（K）" :min="0" />
          <label v-if="s.gasPropertyConfig" class="field"><span>标况压缩因子 Z</span><input value="由已保存物性模型计算" readonly /></label>
          <PipelineNumber v-else v-model="s.form.standardZ" label="标况压缩因子 Z" :min="0" />
          <p class="hint">供气量、分输量均为上述标准状态下的体积流量。</p>
          <div class="side-actions">
            <button type="button" class="save" :disabled="!rows.length || !cases.length" @click="s.savePage">保存</button>
            <button type="button" @click="s.reloadPage">重新加载</button>
          </div>
        </fieldset>
      </template>
    </aside>
    <main class="result-area boundary-data">
      <div class="data-toolbar"><span>边界工况 · {{ cases.length }} 组</span><button type="button" :disabled="disabled || !rows.length || cases.length >= MAX_BOUNDARY_CASES" :title="cases.length >= MAX_BOUNDARY_CASES ? `最多支持 ${MAX_BOUNDARY_CASES} 组工况` : ''" @click="addCase">新增工况</button></div>
      <div v-if="!rows.length" class="empty-state">{{ s.topologyError || '请先保存当前井的管网拓扑，再填写入口和下载点的边界条件。' }}</div>
      <div v-else class="scroll-content">
        <div v-if="s.boundaryTopologyNotice" class="boundary-notice" role="status">{{ s.boundaryTopologyNotice }}</div>
        <fieldset class="boundary-tables" :disabled="disabled">
          <table class="boundary-table" aria-label="边界条件工况数据">
            <thead>
              <tr><th rowspan="2" class="number-column">序号</th><th rowspan="2" class="time-column">工况时间<small>YYYY/MM/DD hh:mm</small></th><th colspan="3" class="node-header">井口</th><th v-for="node in downstreamNodes" :key="node.nodeId" colspan="2" class="node-header">{{ node.name }}</th><th rowspan="2" class="action-column">操作</th></tr>
              <tr><th class="condition-column">供气量<small>10⁴m³/d</small></th><th class="condition-column">进入管网压力<small>MPa，绝压</small></th><th class="condition-column">入口温度<small>℃</small></th><template v-for="node in downstreamNodes" :key="node.nodeId"><th class="condition-column">分输量<small>10⁴m³/d</small></th><th class="condition-column">压力<small>MPa，绝压</small></th></template></tr>
            </thead>
            <tbody><tr v-for="item in caseRows" :key="item.record.id" :class="{ selected: item.record.id === activeCase?.id }" :aria-selected="item.record.id === activeCase?.id" tabindex="0" @click="selectCase(item.record)" @focusin="selectCase(item.record)" @keydown.enter.self="selectCase(item.record)">
              <td class="number-column">{{ item.index + 1 }}</td>
              <td class="time-column"><input type="text" :aria-label="`第 ${item.index + 1} 组工况时间`" :value="formatOperatingTime(item.record.operatingAt)" @input="item.record.operatingAt = $event.target.value" /></td>
              <td><input type="number" step="any" min="0" :aria-label="`第 ${item.index + 1} 组井口供气量`" :value="item.supply?.supplyRate10k" :disabled="!item.supply" @input="number(item.supply, 'supplyRate10k', $event)" /></td>
              <td><input type="number" step="any" min="0" :aria-label="`第 ${item.index + 1} 组进入管网压力`" :value="item.supply?.pressureMpa" :disabled="!item.supply" @input="number(item.supply, 'pressureMpa', $event)" /></td>
              <td><input type="number" step="any" :aria-label="`第 ${item.index + 1} 组入口温度`" :value="item.supply?.temperatureC" :disabled="!item.supply" @input="number(item.supply, 'temperatureC', $event)" /></td>
              <template v-for="node in downstreamNodes" :key="node.nodeId">
                <td><input type="number" step="any" min="0" :aria-label="`第 ${item.index + 1} 组 ${node.name} 分输量`" :value="item.downstream[node.nodeId]?.withdrawalRate10k" :disabled="!item.downstream[node.nodeId]" @input="number(item.downstream[node.nodeId], 'withdrawalRate10k', $event)" /></td>
                <td><input type="number" step="any" min="0" :aria-label="`第 ${item.index + 1} 组 ${node.name} 压力`" :value="item.downstream[node.nodeId]?.pressureMpa" :disabled="!item.downstream[node.nodeId]" @input="number(item.downstream[node.nodeId], 'pressureMpa', $event)" /></td>
              </template>
              <td><button type="button" class="delete-case" :aria-label="`删除第 ${item.index + 1} 组工况`" @focusin.stop @click.stop="removeCase(item.record)">删除</button></td>
            </tr></tbody>
          </table>
          <div v-if="!cases.length" class="empty-cases">暂无工况，请新增或本地导入。</div>
          <div v-if="orphans.length" class="orphan-records" role="alert">
            <p>当前工况以下记录无法对应现有拓扑，请核对数值后移除失效记录再保存。</p>
            <div v-for="(row, index) in orphans" :key="index"><span>{{ row.name }} · 供气 {{ s.f(row.supplyRate10k) }} / 分输 {{ s.f(row.withdrawalRate10k) }} · 压力 {{ s.f(row.pressureMpa) }} MPa</span><button type="button" @click="removeOrphan(row.record)">移除此记录</button></div>
          </div>
        </fieldset>
        <div class="boundary-totals"><span>当前工况实测供气量：<b>{{ s.f(summary.supplyRate10k) }}</b></span><span>实测分输量：<b>{{ s.f(summary.withdrawalRate10k) }}</b></span><span>供需差：<b>{{ s.f(summary.differenceRate10k) }}</b></span><span>10⁴m³/d</span></div>
        <div class="boundary-notes"><p>填写各节点的实测温度、压力和流量，未知值可留空；管流计算根据已填数据确定求解条件，其余实测数据用于结果对比。</p><p>普通连接节点未发生取气时，分输量留空即可。</p></div>
      </div>
    </main>
    <PipelineBoundaryImportDialog v-model="importVisible" :graph="s.savedGraph" :topology-revision="s.topologyRevision" :context="s.context" @imported="acceptImport" />
  </div>
</template>

<style scoped>
.boundary-table .number-column{width:54px;min-width:54px;color:#666}
.boundary-body .parameter-panel{width:260px;min-width:260px}.boundary-body .parameter-panel.collapsed{width:38px;min-width:38px;padding:0}.panel-heading{display:flex;align-items:center;justify-content:space-between}.collapse-button{display:flex;align-items:center;justify-content:center;width:24px;height:26px;padding:0;border:0;background:transparent;cursor:pointer}.collapsed-tab{width:100%;padding:15px 8px;border:0;background:transparent;writing-mode:vertical-rl;letter-spacing:4px;color:#555;cursor:pointer;font:13px/1.8 "Microsoft YaHei",sans-serif}.boundary-data{background:#fff;min-width:0}.import-button{width:100%;height:30px;border:1px solid #bbb;background:#fff;color:#333;cursor:pointer}.imported-file{font-size:11px;color:#777;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.boundary-tables{border:0;padding:0;margin:0;min-width:100%}.boundary-table{border-collapse:collapse;width:max-content;min-width:100%;table-layout:fixed;font-size:12px}.boundary-table th,.boundary-table td{border:1px solid #ddd;padding:6px 8px;text-align:center;font-weight:normal}.boundary-table th{background:#f5f5f5;color:#555;height:30px}.boundary-table th:first-child,.boundary-table td:first-child{border-left:0}.boundary-table small{display:block;font-size:11px;color:#888;line-height:1.6}.boundary-table .time-column{width:226px;min-width:226px}.boundary-table .condition-column{width:132px;min-width:132px}.boundary-table .action-column{width:66px;min-width:66px}.boundary-table td{min-width:132px}.boundary-table td:last-child{min-width:66px}.boundary-table input{width:100%;height:28px;padding:0 5px;box-sizing:border-box;border:1px solid #bbb;border-radius:2px;background:#fff;color:#333}.boundary-table input:focus{outline:1px solid #409eff;border-color:#409eff}.boundary-table input:disabled{background:#f7f7f7}.boundary-table tr.selected td{background:#edf5ff}.boundary-table tbody tr:focus{outline:1px solid #a8c9ec;outline-offset:-1px}.delete-case{padding:4px 7px;border:0;background:transparent;color:#777;cursor:pointer}.delete-case:hover{color:#b34f4f}.empty-cases{padding:30px 12px;text-align:center;color:#999}.boundary-totals{display:flex;flex-wrap:wrap;gap:10px 26px;padding:12px;border-bottom:1px solid #ddd;color:#666;font-size:12px}.boundary-totals b{font-weight:500;color:#333}.boundary-notes{padding:4px 14px;color:#777;font-size:11px;line-height:1.9}.boundary-notes p{margin:8px 0}.boundary-notice,.orphan-records{padding:8px 12px;background:#fff8e9;color:#956c2c;line-height:1.8;font-size:12px}.orphan-records p{margin:0 0 7px}.orphan-records>div{display:flex;justify-content:space-between;align-items:center;gap:12px;padding:5px 0}.boundary-body .side-actions button{min-width:88px}
</style>
