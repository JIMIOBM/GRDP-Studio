<script setup>
import { computed, ref, watch } from 'vue'
import PipelineParameterPanel from './PipelineParameterPanel.vue'
import PipelineTimeChart from './PipelineTimeChart.vue'
import { comparisonChartSeries } from '@/utils/pipelineBoundaryComparison'
import { formatOperatingTime } from '@/utils/pipelineTime'
const props = defineProps({ state: { type: Object, required: true } })
const emit = defineEmits(['navigate'])
const s = computed(() => props.state), nodeId = ref(''), metric = ref('pressure'), dataPage = ref(1), pageSize = 50
const rows = computed(() => s.value.batchStale ? [] : s.value.comparisonRows || [])
const nodes = computed(() => [...new Map(rows.value.map(row => [row.nodeId, {id:row.nodeId,name:row.name}])).values()])
const selectedNode = computed(() => nodes.value.find(node => node.id === nodeId.value))
const options = [{key:'pressure',label:'压力对比',unit:'MPa（绝压）'}, {key:'temperature',label:'温度对比',unit:'℃'}, {key:'flow',label:'流量对比',unit:'10⁴m³/d'}]
const selectedChart = computed(() => options.find(option => option.key === metric.value))
const series = computed(() => comparisonChartSeries(rows.value, nodeId.value, metric.value))
const pagedRows = computed(() => rows.value.slice((dataPage.value - 1) * pageSize, dataPage.value * pageSize))
const emptyText = computed(() => s.value.batchStale ? '计算条件已变化，请重新计算全部工况。' : '请先完成全部工况的管流计算，并在边界条件中填写实测数据。')
watch(nodes, values => { if (!values.some(node => node.id === nodeId.value)) nodeId.value = values[0]?.id || '' }, {immediate:true})
watch(rows, () => { dataPage.value = 1 })
</script>

<template>
  <div class="workspace-body comparison-body">
    <PipelineParameterPanel>
      <label class="field"><span>选择节点</span><select v-model="nodeId" :disabled="s.batchBusy || !nodes.length"><option v-for="node in nodes" :key="node.id" :value="node.id">{{ node.name }}</option></select></label>
      <p class="hint">对比本批全部工况的节点计算值与边界实测值，采用该批计算保存的输入快照。</p>
      <p class="hint">差值 = 计算值 − 实测值。供气温度、分输量等也参与了边界求解，因此这些值的吻合不能作为独立的模型准确性验证。</p>
      <p class="hint">节点装有设备时，对比末台串联设备出口的温压；流量对比为井口总供气量或该节点分输量。</p>
      <div class="side-actions"><button type="button" @click="emit('navigate', 'flow')">前往管流计算</button></div>
    </PipelineParameterPanel>
    <main class="result-area">
      <div v-if="s.panel !== 'analysis'" class="scroll-content">
        <div class="data-toolbar"><span>全部工况 · 节点实测对比</span><small>{{ rows.length }} 条数据</small></div>
        <el-table :data="pagedRows" row-key="rowKey" border>
          <el-table-column type="index" label="序号" width="65" :index="index => (dataPage - 1) * pageSize + index + 1" />
          <el-table-column label="工况时间" min-width="175"><template #default="scope">{{ formatOperatingTime(scope.row.operatingAt) }}</template></el-table-column>
          <el-table-column prop="name" label="节点名称" min-width="140" />
          <el-table-column prop="parameter" label="对比参数" min-width="110" />
          <el-table-column prop="unit" label="单位" min-width="130" />
          <el-table-column v-for="column in [['measured','边界实测值'],['calculated','管流计算值'],['difference','差值']]" :key="column[0]" :label="column[1]" min-width="130"><template #default="scope">{{ s.f(scope.row[column[0]]) }}</template></el-table-column>
          <el-table-column prop="note" label="说明" min-width="260" show-overflow-tooltip />
          <template #empty>{{ emptyText }}</template>
        </el-table>
        <el-pagination v-if="rows.length > pageSize" v-model:current-page="dataPage" :page-size="pageSize" :total="rows.length" layout="prev, pager, next, total" />
      </div>
      <div v-else class="comparison-analysis">
        <div class="chart-switch" role="radiogroup" aria-label="切换实测对比图"><label v-for="option in options" :key="option.key"><input v-model="metric" type="radio" name="pipeline-comparison-chart" :value="option.key" />{{ option.label }}</label></div>
        <PipelineTimeChart :series="series" :title="`${selectedNode?.name || ''} · ${selectedChart.label}`" :unit="selectedChart.unit" :empty-text="emptyText" />
      </div>
      <div class="bottom-tabs"><button :class="{active:s.panel !== 'analysis'}" @click="s.panel = 'data'">数据列表</button><button :class="{active:s.panel === 'analysis'}" @click="s.panel = 'analysis'">结果分析</button></div>
    </main>
  </div>
</template>

<style scoped>
.comparison-analysis{display:flex;flex:1;min-height:0;flex-direction:column;background:#fff}.chart-switch{display:flex;align-items:center;flex-wrap:wrap;gap:14px;min-height:38px;padding:0 12px;border-bottom:1px solid #ddd;flex-shrink:0;color:#666}.chart-switch label{display:inline-flex;align-items:center;gap:5px;cursor:pointer}.chart-switch input{margin:0}.data-toolbar small{color:#888}
</style>
