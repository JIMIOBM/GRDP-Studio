<script setup>
import { computed, ref, watch } from 'vue'
import PipelineParameterPanel from './PipelineParameterPanel.vue'
import PipelineTimeChart from './PipelineTimeChart.vue'
import { constraintChartSeries } from '@/utils/pipelineConstraintResults'
import { formatOperatingTime } from '@/utils/pipelineTime'

const props = defineProps({ state: { type: Object, required: true }, kind: { type: String, required: true } })
const emit = defineEmits(['navigate'])
const s = computed(() => props.state)
const isEquipment = computed(() => props.kind === 'equipment')
const enabled = computed(() => ['equipment', 'hydrate'].includes(props.kind))
const rows = computed(() => s.value.batchStale ? [] : (isEquipment.value ? s.value.equipmentRows : s.value.hydrateRows) || [])
const catalog = computed(() => isEquipment.value ? s.value.equipmentCatalog || [] : s.value.savedGraph?.edges || [])
const selectedId = ref(''), metric = ref('temperature'), dataPage = ref(1), pageSize = 50
const selected = computed(() => catalog.value.find(item => item.id === selectedId.value))
const pagedRows = computed(() => rows.value.slice((dataPage.value - 1) * pageSize, dataPage.value * pageSize))
const chartOptions = computed(() => isEquipment.value
  ? [{ key: 'temperature', label: '温度/时间', unit: '℃' }, { key: 'pressure', label: '压力/时间', unit: 'MPa（绝压）' }, ...(selected.value?.type === 'compressor' ? [{ key: 'power', label: '功率/时间', unit: 'kW' }] : [])]
  : [{ key: 'temperature', label: '温度对比', unit: '℃' }, { key: 'margin', label: '温度裕度', unit: '℃' }])
const selectedChart = computed(() => chartOptions.value.find(option => option.key === metric.value) || chartOptions.value[0])
const series = computed(() => constraintChartSeries(rows.value, selectedId.value, props.kind, selectedChart.value.key))
const sourceModel = computed(() => s.value.batchResult && !s.value.batchStale ? s.value.batchGasModel : s.value.gasPropertyConfig)
const emptyText = computed(() => s.value.batchBusy ? '正在处理全部工况…' : s.value.batchStale ? '计算条件已变化，请重新计算全部工况。'
  : !catalog.value.length ? (isEquipment.value ? '已保存拓扑中没有配置关键设备。' : '请先保存管网拓扑。')
    : s.value.batchResult ? '暂无可绘制的评价结果，请查看数据列表中的判断说明。' : '请先完成全部工况的管流计算。')
const equipmentColumns = [
  ['inletMpa', '入口压力（MPa，绝压）'], ['outletMpa', '出口压力（MPa，绝压）'],
  ['inletC', '入口温度（℃）'], ['outletC', '出口温度（℃）'], ['rate10k', '标况流量（10⁴m³/d）'],
  ['powerKw', '功率（kW）'], ['maxPressureMpa', '允许压力（MPa，绝压）'], ['pressureMarginMpa', '压力裕度（MPa）'],
  ['maxPowerKw', '允许功率（kW）'], ['powerMarginKw', '功率裕度（kW）']
]
const hydrateColumns = [
  ['distanceM', '评价位置（m）'], ['pressureMpa', '该处压力（MPa，绝压）'], ['temperatureC', '该处温度（℃）'],
  ['equilibriumC', '水合物平衡温度（℃，经验预测）'], ['marginC', '温度裕度 ΔT（℃）']
]
function statusLabel(row) {
  if (isEquipment.value) return s.value.statusLabels[row.status] || '未评价'
  return ({ pass: '未进入形成区', risk: '进入形成区', equilibrium: '平衡边界', conditional: '需确认含水条件', not_evaluated: '未评价' })[row.status] || '未评价'
}
function chooseRow(row) { selectedId.value = isEquipment.value ? row.id : row.edgeId || row.id }
watch(catalog, values => { if (!values.some(item => item.id === selectedId.value)) selectedId.value = values[0]?.id || '' }, { immediate: true })
watch(rows, () => { dataPage.value = 1 })
</script>

<template>
  <div v-if="!enabled" class="workspace-body" :data-page="kind"><main class="result-area"><div class="empty-state">本期暂不启用</div></main></div>
  <div v-else class="workspace-body constraint-body" :data-page="kind">
    <PipelineParameterPanel>
      <label class="field"><span>{{ isEquipment ? '选择设备' : '选择管道' }}</span><select v-model="selectedId" :disabled="s.batchBusy || !catalog.length"><option v-for="item in catalog" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
      <template v-if="isEquipment">
        <template v-if="selected">
          <div class="section-heading">设备资料<i /></div>
          <dl class="source-values">
            <dt>设备类型</dt><dd>{{ selected.type === 'valve' ? '阀门 / 局部阻力' : '通用压缩机' }}</dd>
            <dt>安装节点 / 串联顺序</dt><dd>{{ selected.nodeName }} / {{ selected.sequence }}</dd>
            <template v-if="selected.type === 'valve'"><dt>局部阻力系数 K</dt><dd>{{ s.f(selected.lossK) }}</dd></template>
            <template v-else><dt>设定压缩比</dt><dd>{{ s.f(selected.pressureRatio) }}</dd><dt>等熵效率</dt><dd>{{ s.f(selected.efficiency) }}</dd><dt>允许功率（kW）</dt><dd>{{ s.f(selected.maxPowerKw) }}</dd></template>
            <dt>允许压力（MPa，绝压）</dt><dd>{{ s.f(selected.maxPressureMpa) }}</dd>
          </dl>
        </template>
        <p class="hint">设备资料来自已保存拓扑。运行结果、运行限值与管流采用同一批输入，在管流计算页统一计算并保存全部工况。</p>
        <p class="hint">压缩机按固定压缩比、固定效率计算，校核允许压力和功率；暂不评价特性曲线和喘振。</p>
        <div class="side-actions"><button type="button" :disabled="s.batchBusy" @click="emit('navigate', 'topology')">编辑拓扑设备</button></div>
        <div class="side-actions"><button type="button" :disabled="s.batchBusy" @click="emit('navigate', 'flow')">前往管流计算</button></div>
      </template>
      <template v-else>
        <dl class="source-values"><dt>物性来源</dt><dd>{{ sourceModel ? sourceModel.pvtName || '当前井 PVT 模型' : '尚未保存当前井 PVT 模型' }}</dd><dt>平衡温度计算方法</dt><dd>Safamirzaei（2015）天然气经验关联式</dd><dt>气体相对密度</dt><dd>{{ s.f(s.batchStale ? null : s.batchResult?.hydrateModel?.relativeDensity) }}</dd></dl>
        <fieldset class="water-options" :disabled="s.batchBusy"><legend>含水条件</legend>
          <label><input v-model="s.form.constraints.waterState" type="radio" name="pipeline-hydrate-water" value="unknown" />含水条件未知</label>
          <label><input v-model="s.form.constraints.waterState" type="radio" name="pipeline-hydrate-water" value="available" />存在可用水</label>
        </fieldset>
        <p class="hint">按纯水、无盐且未加抑制剂筛查。含水条件未知时仅给出条件性判断；温压进入形成区不代表已经生成水合物或发生堵塞。</p>
        <p class="hint">用完整 PVT 组成计算气体相对密度，再预测各处压力对应的平衡温度。该经验式并非 PR / SRK / BWRS 的水合物相平衡计算，也不是老师资料中的公式。</p>
        <p class="hint">本实现采用原文表 2 数据范围作保守筛查：压力 0.591～62.011 MPa、气体相对密度 0.58～0.80，并检查组分适用范围；范围外显示未评价。<a href="https://gasprocessingnews.com/articles/2015/08/predict-gas-hydrate-formation-temperature-with-a-simple-correlation/" target="_blank" rel="noopener noreferrer">查看方法来源</a></p>
        <p v-if="s.gasPropertyError" class="source-error" role="alert">{{ s.gasPropertyError }}</p>
        <p v-if="s.form.thermalMode === 'heat' && s.thermalSourceError" class="source-error" role="alert">{{ s.thermalSourceError }}</p>
        <div class="side-actions"><button type="button" class="calculate" :disabled="!s.canCalculate" @click="s.calculate">{{ s.batchBusy ? '计算中…' : '计算' }}</button><button type="button" class="save" :disabled="!s.canBatchSave" @click="s.savePage">保存</button></div>
        <p class="hint">计算会同步更新全部工况的管流、设备和水合物筛查结果；保存同一批结果。</p>
      </template>
    </PipelineParameterPanel>
    <main class="result-area">
      <div v-if="s.panel !== 'analysis'" class="scroll-content">
        <div class="data-toolbar"><span>{{ isEquipment ? '全部工况 · 全部设备' : '全部工况 · 全部管道' }}</span><small>{{ rows.length }} 条数据{{ s.batchResult && !s.batchStale ? (s.batchSaved ? ' · 已保存' : ' · 未保存') : '' }}</small></div>
        <el-table :data="pagedRows" row-key="rowKey" border highlight-current-row :aria-label="isEquipment ? '全部工况设备运行校核' : '全部工况水合物筛查'" @row-click="chooseRow">
          <el-table-column type="index" label="序号" width="65" :index="index => (dataPage - 1) * pageSize + index + 1" />
          <el-table-column label="工况时间" min-width="175"><template #default="scope">{{ formatOperatingTime(scope.row.operatingAt) }}</template></el-table-column>
          <el-table-column prop="name" :label="isEquipment ? '设备名称' : '管道名称'" min-width="140" />
          <el-table-column v-if="isEquipment" label="类型 / 串联顺序" min-width="130"><template #default="scope">{{ scope.row.type === 'valve' ? '阀门' : '压缩机' }} / {{ scope.row.sequence }}</template></el-table-column>
          <el-table-column v-for="column in isEquipment ? equipmentColumns : hydrateColumns" :key="column[0]" :label="column[1]" min-width="155"><template #default="scope">{{ isEquipment && scope.row.type !== 'compressor' && ['powerKw','maxPowerKw','powerMarginKw'].includes(column[0]) ? '—' : s.f(scope.row[column[0]]) }}</template></el-table-column>
          <el-table-column v-if="!isEquipment" prop="pointLabel" label="位置说明" min-width="130" />
          <el-table-column label="判断结果" min-width="140"><template #default="scope"><span :class="scope.row.status">{{ statusLabel(scope.row) }}</span></template></el-table-column>
          <el-table-column prop="reason" label="判断说明" min-width="270" show-overflow-tooltip />
          <template #empty>{{ emptyText }}</template>
        </el-table>
        <el-pagination v-if="rows.length > pageSize" v-model:current-page="dataPage" :page-size="pageSize" :total="rows.length" layout="prev, pager, next, total" />
        <p v-if="!isEquipment" class="evaluation-note">ΔT = 管道温度 − 同点水合物平衡温度。按管道沿程计算点取最小裕度，位置从该管道入口起算；任一点无法评价时，定位首个未评价点，整管标为未评价。</p>
      </div>
      <div v-else class="constraint-analysis">
        <div class="chart-switch" role="radiogroup" aria-label="切换约束结果图"><label v-for="option in chartOptions" :key="option.key"><input v-model="metric" type="radio" :name="`pipeline-${kind}-chart`" :value="option.key" />{{ option.label }}</label></div>
        <PipelineTimeChart :series="series" :title="`${selected?.name || ''} · ${selectedChart.label}`" :unit="selectedChart.unit" :empty-text="emptyText" />
      </div>
      <div class="bottom-tabs"><button :class="{ active: s.panel !== 'analysis' }" @click="s.panel = 'data'">数据列表</button><button :class="{ active: s.panel === 'analysis' }" @click="s.panel = 'analysis'">结果分析</button></div>
    </main>
  </div>
</template>

<style scoped>
.source-values{margin:0;font-size:12px;line-height:1.8;overflow-wrap:anywhere}.source-values dt{color:#777;margin-top:8px}.source-values dd{margin:0;color:#333}.water-options{border:0;padding:0;margin:14px 0}.water-options legend{padding:0;margin-bottom:7px}.water-options label{display:flex;align-items:center;gap:6px;margin:8px 0}.water-options input{margin:0}.constraint-analysis{display:flex;flex:1;min-height:0;flex-direction:column;background:#fff}.chart-switch{display:flex;align-items:center;flex-wrap:wrap;gap:14px;min-height:38px;padding:0 12px;border-bottom:1px solid #ddd;flex-shrink:0;color:#666}.chart-switch label{display:inline-flex;align-items:center;gap:5px;cursor:pointer}.chart-switch input{margin:0}.risk,.source-error{color:#c0524a}.conditional,.equilibrium{color:#aa813f}.data-toolbar small{color:#888}a{color:#337ab7}
</style>
