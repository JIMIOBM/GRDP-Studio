<script setup>
import { computed, ref, watch } from 'vue'
import PipelineNumber from './PipelineNumber.vue'
import PipelineTimeChart from './PipelineTimeChart.vue'

const props = defineProps({ state: { type: Object, required: true } })
const s = computed(() => props.state)
const collapsed = ref(false), dataPage = ref(1), pageSize = 50
const heatMode = computed(() => s.value.form.thermalMode === 'heat')
const working = computed(() => !!(s.value.batchBusy || s.value.busy))
const locked = computed(() => working.value || s.value.topologyLoading || (heatMode.value && s.value.thermalSourceLoading))
const activeTab = computed(() => s.value.panel === 'analysis' ? 'analysis' : 'data')
const dataRows = computed(() => s.value.batchStale ? [] : s.value.batchDataRows || [])
const pagedRows = computed(() => dataRows.value.slice((dataPage.value - 1) * pageSize, dataPage.value * pageSize))
const chartSeries = computed(() => s.value.batchResult && !s.value.batchStale && !working.value
  ? s.value.batchChartSeries || {} : {})
const emptyChartText = computed(() => working.value ? '正在计算全部工况…' : s.value.batchStale ? '计算条件已变化，请重新计算。'
  : s.value.batchResult ? '暂无成功计算的数据。' : '请先点击左侧“计算”。')
const charts = [
  { key: 'temperature', label: '温度/时间', title: '出口温度—时间', unit: '℃' },
  { key: 'pressure', label: '压力/时间', title: '出口压力—时间', unit: 'MPa（绝压）' },
  { key: 'flow', label: '流量/时间', title: '标况流量—时间', unit: '10⁴m³/d' }
]
const activeChart = ref('temperature')
const selectedChart = computed(() => charts.find(chart => chart.key === activeChart.value) || charts[0])
const resultColumns = [
  { key: 'inletMpa', label: '入口压力（MPa，绝压）' }, { key: 'outletMpa', label: '出口压力（MPa，绝压）' },
  { key: 'inletC', label: '入口温度（℃）' }, { key: 'outletC', label: '出口温度（℃）' },
  { key: 'rate10k', label: '标况流量（10⁴m³/d）' }
]
function statusLabel(row) {
  if (row.error) return '计算失败'
  return ({ success: '计算完成', calculated: '计算完成', failed: '计算失败', error: '计算失败', pending: '待计算' })[row.status]
    || row.status || '待计算'
}
watch(dataRows, () => { dataPage.value = 1 })
</script>

<template>
  <div class="workspace-body flow-body">
    <aside class="parameter-panel" :class="{ collapsed }">
      <button v-if="collapsed" class="collapsed-tab" type="button" aria-label="展开参数设置" :aria-expanded="false" @click="collapsed = false">参数设置</button>
      <template v-else>
        <div class="panel-heading"><span>参数设置</span><button class="collapse-button" type="button" title="收起参数设置" aria-label="收起参数设置" :aria-expanded="true" @click="collapsed = true"><svg width="14" height="14" viewBox="0 0 24 24" fill="#777" aria-hidden="true"><path d="M16,12V4H17V2H7V4H8V12L6,14V16H11.2V22H12.8V16H18V14L16,12Z" /></svg></button></div>
        <fieldset class="parameter-form" :disabled="working">
          <fieldset class="batch-settings" :disabled="locked">
            <fieldset class="method-options">
              <legend>温度模型</legend>
              <label><input v-model="s.form.thermalMode" type="radio" name="pipeline-thermal-mode" value="heat" /><span>分段传热（含焦耳—汤姆逊效应）</span></label>
              <label><input v-model="s.form.thermalMode" type="radio" name="pipeline-thermal-mode" value="isothermal" /><span>管段等温（设备温升单独计算）</span></label>
            </fieldset>
            <fieldset class="method-options">
              <legend>Darcy 摩阻系数</legend>
              <label><input v-model="s.form.frictionMethod" type="radio" name="pipeline-friction-method" value="colebrook" /><span>Colebrook 迭代法</span></label>
              <label><input v-model="s.form.frictionMethod" type="radio" name="pipeline-friction-method" value="haaland" /><span>Haaland 显式近似</span></label>
            </fieldset>
            <PipelineNumber v-if="heatMode" v-model="s.form.jtKmpa" label="焦耳—汤姆逊系数（K/MPa）" />
            <div class="source-lines">
              <p><span>物性来源：</span>{{ s.gasPropertyConfig ? `${s.gasPropertyConfig.method} · ${s.gasPropertyConfig.pvtName}` : '尚未保存当前井 PVT 模型' }}</p>
              <p v-if="heatMode"><span>温度来源：</span>{{ s.thermalSourceLoading ? '正在读取已保存温度参数…' : s.thermalConfig?.revision ? '已保存温度模型参数，各工况自动联算传热与管流温压。' : '尚未保存温度模型参数' }}</p>
              <p v-else>管段等温计算不使用环境温度、总传热系数和焦耳—汤姆逊项；设备温升单独计算。</p>
              <p v-if="s.gasPropertyError" class="source-error" role="alert">{{ s.gasPropertyError }}</p>
              <p v-if="heatMode && s.thermalSourceError" class="source-error" role="alert">{{ s.thermalSourceError }}</p>
            </div>
          </fieldset>
          <div class="side-actions">
            <button type="button" class="calculate" :disabled="!s.canCalculate" @click="s.calculate">{{ working ? '计算中…' : '计算' }}</button>
            <button type="button" class="save" :disabled="!s.canBatchSave" @click="s.savePage">保存</button>
          </div>
        </fieldset>
      </template>
    </aside>
    <main class="result-area">
      <div v-if="activeTab === 'data'" class="flow-data scroll-content">
        <div class="data-toolbar"><span>全部工况 · 全部管段</span><small>{{ dataRows.length }} 条数据</small></div>
        <p v-if="s.batchStale" class="batch-notice">计算条件已变化，重新计算后显示批量结果。</p>
        <el-table :data="pagedRows" row-key="rowKey" border aria-label="全部工况管流数据">
          <el-table-column label="工况时间" min-width="170"><template #default="scope">{{ scope.row.operatingAt?.replace('T', ' ') || '—' }}</template></el-table-column>
          <el-table-column prop="name" label="管段名称" min-width="130" />
          <el-table-column label="长度（m）" min-width="95"><template #default="scope">{{ s.f(scope.row.lengthM) }}</template></el-table-column>
          <el-table-column label="内径（mm）" min-width="95"><template #default="scope">{{ s.f(scope.row.diameterMm) }}</template></el-table-column>
          <el-table-column v-for="column in resultColumns" :key="column.key" :label="column.label" min-width="155"><template #default="scope">{{ s.f(scope.row[column.key]) }}</template></el-table-column>
          <el-table-column label="状态" min-width="100"><template #default="scope"><span :class="{ 'source-error': !!scope.row.error }">{{ statusLabel(scope.row) }}</span></template></el-table-column>
          <el-table-column prop="message" label="计算提示" min-width="220" show-overflow-tooltip><template #default="scope">{{ scope.row.message || scope.row.error || '' }}</template></el-table-column>
          <template #empty>{{ s.batchStale ? '请重新计算' : working ? '正在计算全部工况…' : '请先保存边界条件和管网拓扑，再计算全部工况。' }}</template>
        </el-table>
        <el-pagination v-if="dataRows.length > pageSize" v-model:current-page="dataPage" :page-size="pageSize" :total="dataRows.length" layout="prev, pager, next, total" />
      </div>
      <div v-else class="batch-analysis">
        <div class="chart-switch" role="radiogroup" aria-label="切换管流结果图">
          <label v-for="chart in charts" :key="chart.key"><input v-model="activeChart" type="radio" name="pipeline-flow-chart" :value="chart.key" />{{ chart.label }}</label>
        </div>
        <PipelineTimeChart :series="chartSeries[selectedChart.key] || []" :title="selectedChart.title" :unit="selectedChart.unit" :empty-text="emptyChartText" />
      </div>
      <div class="bottom-tabs">
        <button :class="{ active: activeTab === 'data' }" @click="s.panel = 'data'">数据列表</button>
        <button :class="{ active: activeTab === 'analysis' }" @click="s.panel = 'analysis'">结果分析</button>
      </div>
    </main>
  </div>
</template>

<style scoped>
.flow-body .parameter-panel{width:260px;min-width:260px}.flow-body .parameter-panel.collapsed{width:38px;min-width:38px;padding:0}.panel-heading{justify-content:space-between}.collapse-button{display:flex;align-items:center;justify-content:center;width:24px;height:26px;padding:0;border:0;background:transparent;cursor:pointer}.collapsed-tab{width:100%;padding:15px 8px;border:0;background:transparent;writing-mode:vertical-rl;letter-spacing:4px;color:#555;cursor:pointer;font:13px/1.8 "Microsoft YaHei",sans-serif}.flow-body .side-actions{margin-top:15px}.flow-body .side-actions button{min-width:88px}.batch-settings{min-width:0;border:0;padding:0;margin:0}.method-options{min-width:0;border:0;padding:0;margin:0 0 14px}.method-options legend{margin-bottom:7px;padding:0;font-size:12px}.method-options label{display:flex;align-items:flex-start;gap:6px;margin:0 0 7px;font-size:12px;line-height:1.7;cursor:pointer}.method-options input{flex-shrink:0;margin:4px 0 0}.method-options span{min-width:0}.source-lines{font-size:11px;line-height:1.8;color:#777;overflow-wrap:anywhere}.source-lines p{margin:3px 0}.source-error{color:#b64d48}.batch-notice{margin:0;padding:9px 12px;background:#fff8e9;color:#9d783b;font-size:12px}.data-toolbar small{font-size:11px;color:#888}.batch-analysis{flex:1;min-height:0;display:flex;flex-direction:column;overflow:hidden;background:#fff}.chart-switch{min-height:38px;padding:0 12px;display:flex;align-items:center;gap:14px;flex-wrap:wrap;flex-shrink:0;border-bottom:1px solid #ddd;color:#666;font-size:12px}.chart-switch label{display:inline-flex;align-items:center;gap:5px;cursor:pointer;white-space:nowrap}.chart-switch input{margin:0}
</style>
