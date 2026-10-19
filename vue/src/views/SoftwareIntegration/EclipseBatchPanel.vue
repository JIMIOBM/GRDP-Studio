<script setup>
import EclipseBatchResponseChart from './EclipseBatchResponseChart.vue'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { softwareIntegrationApi } from '@/api/softwareIntegration'
import { compositionalBatchLabels, batchBaselineAvailable, batchChoices, batchPending, batchResponse, batchStatusLabels, buildBatchCase, buildGridBatchCase, buildBatchRequest, sameBatchCase, validBatchRequest } from './eclipseBatch.js'
import {multiLiveOilPvtContext,multiLiveOilPvtLabel} from './eclipseMultiLiveOilPvt.js'
import {multiWaterPvtContext} from './eclipseMultiWaterPvt.js'
import {pvtwLabels} from './eclipsePvtw.js'
import {multiWetGasPvtContext,multiWetGasPvtLabel} from './eclipseMultiWetGasPvt.js'
import {multiPvtContext,multiPvtLabel} from './eclipseMultiPvt.js'
import {multiFamilyIIContext,multiFamilyIILabels} from './eclipseMultiSatFamilyII.js'
import {multiSatRelpermContext} from './eclipseMultiSatRelperm.js'
import { wetGasPvtLabel } from './eclipseWetGasPvt.js'
import { liveOilPvtLabel } from './eclipseLiveOilPvt.js'
import { oilPvtLabel } from './eclipseOilPvt.js'
import { gasPvtLabel } from './eclipseGasPvt.js'

import {compositionalFluidPropertyRecords} from './eclipseCompositionalFluidProperty.js'
import {coupledRelpermLabels} from './eclipseCoupledRelperm.js'
const props = defineProps({ run: { type: Object, default: null }, inspection: { type: Object, default: null } })
const emit = defineEmits(['select-run'])
const title = ref('物性敏感性方案'), caseName = ref(''), kind = ref(''), value = ref(null), cases = ref([])
const batches = ref([]), selected = ref(null), pendingRequest = ref(null), busy = ref(false), error = ref(''), day = ref(null)
const componentIndex=ref(null),isCompositional=computed(()=>props.run?.result?.modelKind==='eclipse_300'),componentRecords=computed(()=>kind.value.startsWith('E300_')?compositionalFluidPropertyRecords(props.inspection,kind.value.slice(5)):[]),selectedComponent=computed(()=>componentRecords.value.find(r=>r.componentIndex===componentIndex.value))
const regionNumber=ref(null),multiContext=computed(()=>multiSatRelpermContext(props.inspection)||multiFamilyIIContext(props.inspection))
const pvtContext=computed(()=>multiPvtContext(props.inspection)||multiLiveOilPvtContext(props.inspection)||multiWetGasPvtContext(props.inspection)||multiWaterPvtContext(props.inspection))
const choices = computed(() => batchChoices(props.inspection))
const unit = computed(() => choices.value.find(choice => choice.kind === kind.value)?.unit || '')
const canBuild = computed(() => batchBaselineAvailable(props.run) && (choices.value.length > 0 || cases.value.length > 0))
const response = computed(() => batchResponse(selected.value,props.inspection))
const days = computed(() => [...new Set(response.value.rows.map(row => row.timeDays))].sort((a, b) => a - b))
const rows = computed(() => response.value.rows.filter(row => row.timeDays === day.value))
const storageKey = version => `grdp-eclipse-batch-pending-${version}`
let generation = 0, timer = null, disposed = false
const stopTimer = () => { if (timer) clearTimeout(timer); timer = null }
const message = e => e?.message || e?.msg || '批量请求失败，请重试。'
const validateBatch = (batch, version) => batch && Number.isSafeInteger(batch.id) && batch.modelVersionId === version
  && Array.isArray(batch.members) && batch.members.length >= 2 && batch.members.length <= 8
  && batch.members.every(member => Number.isSafeInteger(member?.run?.id) && member.run.modelVersionId === version)
function savePending(version, request) {
  // Persist before sending so a lost HTTP response can be retried with exactly the same submission key.
  localStorage.setItem(storageKey(version), JSON.stringify(request))
  pendingRequest.value = request
}
function removeSavedRequest(version, requestKey) {
  const saved = localStorage.getItem(storageKey(version))
  if (saved && JSON.parse(saved).requestKey === requestKey) localStorage.removeItem(storageKey(version))
}
function clearPending(version) {
  removeSavedRequest(version, pendingRequest.value?.requestKey)
  pendingRequest.value = null
}
function schedule() {
  stopTimer()
  if (!disposed && batchPending(selected.value)) timer = setTimeout(() => refreshSelected(), 2000)
}
async function refreshSelected(id = selected.value?.id) {
  if (!id) return
  const current = ++generation, version = props.run?.modelVersionId
  stopTimer()
  try {
    const reply = await softwareIntegrationApi.getEclipseBatch(id)
    if (disposed || current !== generation || version !== props.run?.modelVersionId) return
    if (!validateBatch(reply.data, version)) throw new Error('批量记录的模型版本或子运行不一致。')
    selected.value = reply.data
    batches.value = batches.value.map(batch => batch.id === id ? reply.data : batch)
    error.value = ''
  } catch (e) { if (!disposed && current === generation) error.value = message(e) }
  finally { if (!disposed && current === generation) schedule() }
}
async function loadBatches() {
  const current = ++generation, version = props.run?.modelVersionId
  stopTimer()
  try {
    const reply = await softwareIntegrationApi.listEclipseBatches(version)
    if (disposed || current !== generation || version !== props.run?.modelVersionId) return
    if (!Array.isArray(reply.data) || !reply.data.every(batch => Number.isSafeInteger(batch?.id) && batch.modelVersionId === version && Object.hasOwn(batchStatusLabels, batch.status))) throw new Error('批量记录格式不完整。')
    batches.value = reply.data
    const next = reply.data.find(batch => batch.id === selected.value?.id) || reply.data[0]
    error.value = ''
    if (next) await refreshSelected(next.id)
    else selected.value = null
  } catch (e) { if (!disposed && current === generation) error.value = message(e) }
  finally { if (!disposed && current === generation) schedule() }
}
function addCase() {
  try {
    if (cases.value.length >= 8) throw new Error('每批最多8个方案。')
    const candidate = buildBatchCase({ run: props.run, inspection: props.inspection, kind: kind.value, value: value.value, name: caseName.value, regionNumber: regionNumber.value, componentIndex: componentIndex.value })
    if (cases.value.some(old => old.name.toLowerCase() === candidate.name.toLowerCase() || sameBatchCase(old, candidate))) throw new Error('方案名称或计算参数重复。')
    cases.value.push(candidate); caseName.value = ''; value.value = null; error.value = ''
  } catch (e) { error.value = message(e) }
}
function addGridCase(candidate) {
  if (busy.value || pendingRequest.value) throw new Error('请先完成或确认当前批量提交，再加入新方案。')
  if (cases.value.length >= 8) throw new Error('每批最多8个方案。')
  const item = buildGridBatchCase({ run: props.run, name: candidate?.name, parameters: candidate?.parameters })
  if (cases.value.some(old => old.name.toLowerCase() === item.name.toLowerCase() || sameBatchCase(old, item))) throw new Error('方案名称或计算参数重复。')
  cases.value.push(item); error.value = ''
}
defineExpose({ addGridCase })
async function submit() {
  if (busy.value) return
  const version = props.run?.modelVersionId, current = ++generation
  stopTimer(); busy.value = true; error.value = ''
  let sent = false
  try {
    if (!pendingRequest.value) savePending(version, buildBatchRequest({ run: props.run, name: title.value, cases: cases.value, requestKey: crypto.randomUUID() }))
    const request = JSON.parse(JSON.stringify(pendingRequest.value))
    if (!validBatchRequest(request)) throw new Error('保存的批量请求无效。')
    sent = true
    const reply = await softwareIntegrationApi.createEclipseBatch(version, request)
    if (!validateBatch(reply.data, version)) throw new Error('提交响应不完整，请使用原提交标识重试。')
    // Clear this version's persisted request even if navigation changed while the server was responding.
    removeSavedRequest(version, request.requestKey)
    if (disposed || current !== generation || version !== props.run?.modelVersionId) return
    pendingRequest.value = null; cases.value = []; selected.value = reply.data
    batches.value = [reply.data, ...batches.value.filter(batch => batch.id !== reply.data.id)].slice(0, 50)
  } catch (e) {
    if (!disposed && current === generation) {
      error.value = message(e)
      if (sent && [400, 404, 409].includes(e?.code)) clearPending(version)
    }
  } finally { if (!disposed && current === generation) { busy.value = false; schedule() } }
}
async function cancel() {
  if (!selected.value || busy.value) return
  const id = selected.value.id, version = props.run?.modelVersionId, current = ++generation
  stopTimer(); busy.value = true
  try {
    const reply = await softwareIntegrationApi.cancelEclipseBatch(id)
    if (disposed || current !== generation || version !== props.run?.modelVersionId) return
    if (!validateBatch(reply.data, version)) throw new Error('取消响应不完整，请刷新记录。')
    selected.value = reply.data; error.value = ''
  } catch (e) { if (!disposed && current === generation) error.value = message(e) }
  finally { if (!disposed && current === generation) { busy.value = false; schedule() } }
}
watch(() => [props.run?.id, props.inspection], () => {
  componentIndex.value = null; regionNumber.value = null; cases.value = []; caseName.value = ''; value.value = null; kind.value = choices.value[0]?.kind || ''
}, { immediate: true, deep: true })
watch(() => props.run?.modelVersionId, version => {
  generation++; stopTimer(); busy.value = false; batches.value = []; selected.value = null; pendingRequest.value = null; error.value = ''
  if (!Number.isSafeInteger(version) || version <= 0) return
  try {
    const saved = localStorage.getItem(storageKey(version))
    if (saved) { const request = JSON.parse(saved); if (!validBatchRequest(request)) throw new Error('保存的未确认请求无效。'); pendingRequest.value = request }
  } catch (e) { error.value = message(e) }
  loadBatches()
}, { immediate: true })
const regionAxis = item => typeof item !== 'string' ? null : item.startsWith('FAMILY_II_') || item.startsWith('MULTI_') ? 'SAT' : item.startsWith('PVT_') || item.startsWith('REGION_PVT') ? 'PVT' : null
watch(kind,(current,previous)=>{if(multiContext.value && pvtContext.value && regionAxis(current)!==regionAxis(previous)){regionNumber.value=null;value.value=null;error.value=''}})
watch(componentIndex,()=>{value.value=null;error.value=''})
watch(regionNumber,()=>{value.value=null;error.value=''})
watch(days, available => { if (!available.includes(day.value)) day.value = available.at(-1) ?? null })
onBeforeUnmount(() => { disposed = true; generation++; stopTimer() })
const format = v => v == null ? '缺少同时间值' : isCompositional.value ? String(v) : Number(v).toPrecision(8)
const propertyLabel = p => p.componentName && Object.hasOwn(compositionalBatchLabels,p.field) ? `${p.componentName} · ${compositionalBatchLabels[p.field]} (${p.field})` : ['eclipse-multi-pvtw-parameters/1','eclipse-multi-pvtw-parameters/2','eclipse-multi-pvtw-parameters/3'].includes(p.schemaVersion) ? `${pvtwLabels[p.field]} · PVT区 ${p.regionNumber}` : p.schemaVersion === 'eclipse-multi-sat-relperm-parameters/1' && pvtContext.value ? `${coupledRelpermLabels[p.field]} · SAT区 ${p.regionNumber}` : p.schemaVersion === 'eclipse-multi-sat-relperm-parameters/2' && pvtContext.value ? `${multiFamilyIILabels[p.field]} · SAT区 ${p.regionNumber}` : p.schemaVersion === 'eclipse-multi-pvtg-parameters/1' ? `${multiWetGasPvtLabel(p)} · PVT区 ${p.regionNumber}` : p.schemaVersion === 'eclipse-multi-pvto-parameters/1' ? `${multiLiveOilPvtLabel(p)} · PVT区 ${p.regionNumber}` : p.schemaVersion === 'eclipse-multi-pvt-parameters/1' ? `${multiPvtLabel(p)} · PVT区 ${p.regionNumber}` : p.schemaVersion === 'eclipse-pvtg-parameters/1' ? wetGasPvtLabel(p) : p.schemaVersion === 'eclipse-pvto-parameters/1' ? liveOilPvtLabel(p) : p.schemaVersion.includes('pvdo') ? oilPvtLabel(p) : p.schemaVersion.includes('pvdg') ? gasPvtLabel(p) : p.field || p.keyword
</script>

<template>
  <section v-if="Number.isSafeInteger(run?.modelVersionId)" aria-label="ECLIPSE 批量方案" class="eclipse-batch-panel">
    <h3>批量方案计算</h3>
    <p v-if="isCompositional">从同一无参数基线建立独立组分物性方案，每批2–8项逐项排队计算。不同组分或参数分别加入清单，可查看产油、累计量和压力的实际响应及原始列号。</p>
    <p v-else>同一原模型的独立物性方案逐项排队计算。每批2–8项，单项失败会保留原因并继续后续方案。网格方案在下方三维网格选择静态渗透率或孔隙度区域后加入清单。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert v-if="pendingRequest" title="上次提交尚未确认。重试沿用原方案和提交标识，避免重复计算。" type="warning" :closable="false" />
    <template v-if="canBuild && !pendingRequest">
      <el-form label-position="top" :disabled="busy">
        <el-form-item label="批量任务名称"><el-input v-model="title" maxlength="100" aria-label="批量任务名称" /></el-form-item>
        <template v-if="choices.length">
        <el-form-item label="物性项目"><el-select v-model="kind" aria-label="批量物性项目" @change="value = null"><el-option v-for="choice in choices" :key="choice.kind" :label="choice.label" :value="choice.kind" /></el-select></el-form-item>
        <el-form-item v-if="kind.startsWith('MULTI_') || kind.startsWith('FAMILY_II_')" label="批量SAT区"><el-select v-model="regionNumber" aria-label="批量SAT区" @change="value = null"><el-option v-for="r in multiContext?.regions || []" :key="r.number" :value="r.number" :label="`SAT区 ${r.number} · ${r.cellCount} 格`"/></el-select></el-form-item>
        <el-form-item v-if="kind.startsWith('PVT_') || kind.startsWith('REGION_PVTO_') || kind.startsWith('REGION_PVTG_') || kind.startsWith('REGION_PVTW_')" label="批量PVT区"><el-select v-model="regionNumber" aria-label="批量PVT区" @change="value = null"><el-option v-for="r in pvtContext?.regions || []" :key="r.number" :value="r.number" :label="`PVT区 ${r.number} · ${r.cellCount} 格`"/></el-select></el-form-item>
        <el-form-item v-if="kind.startsWith('E300_')" label="批量组分"><el-select v-model="componentIndex" aria-label="批量组分"><el-option v-for="r in componentRecords" :key="r.componentIndex" :label="`${r.componentName} · #${r.componentIndex}`" :value="r.componentIndex" /></el-select></el-form-item>
        <p v-if="selectedComponent">原{{ selectedComponent.componentName }} {{ selectedComponent.field }}：{{ selectedComponent.originalValue }} {{ selectedComponent.unit }}；来源行{{ selectedComponent.lineNumber }}。</p>
        <el-form-item label="方案名称"><el-input v-model="caseName" maxlength="80" aria-label="批量方案名称" /></el-form-item>
        <el-form-item :label="`新值或乘数（${unit}）`"><el-input-number v-model="value" :controls="false" aria-label="批量方案数值" /><span>{{ unit }}</span></el-form-item>
        <el-button :disabled="cases.length >= 8" @click="addCase">加入方案清单</el-button>
        </template>
      </el-form>
      <el-table :data="cases" border size="small" aria-label="待计算方案清单">
        <el-table-column prop="name" label="方案" /><el-table-column label="改动"><template #default="{ row }">{{ propertyLabel(row.parameters) }}：{{ row.parameters.targetValue ?? row.parameters.multiplier }} {{ row.parameters.targetValue != null ? row.parameters.unit : '倍' }}</template></el-table-column>
        <el-table-column label="网格范围"><template #default="{ row }"><span v-if="row.parameters.schemaVersion === 'eclipse-grid-region-parameters/1'">I {{ row.parameters.i1 }}–{{ row.parameters.i2 }} / J {{ row.parameters.j1 }}–{{ row.parameters.j2 }} / K {{ row.parameters.k1 }}–{{ row.parameters.k2 }}；{{ row.parameters.originalValues.length }} 个单元</span><span v-else-if="row.parameters.componentName">组分 {{ row.parameters.componentName }} · #{{ row.parameters.componentIndex }}</span><span v-else>物性表</span></template></el-table-column>
        <el-table-column label="操作"><template #default="{ $index }"><el-button link :disabled="busy" @click="cases.splice($index, 1)">移除</el-button></template></el-table-column>
      </el-table>
    </template>
    <p v-else-if="!pendingRequest">新建方案需要选择完整无参数 E100 或组分 E300 基线及支持编辑的物性模型；已有批量记录仍可查看。</p>
    <el-button type="primary" :loading="busy" :disabled="!pendingRequest && (!canBuild || cases.length < 2)" @click="submit">{{ pendingRequest ? '重试原批量提交' : '运行全部方案' }}</el-button>
    <el-button :disabled="busy" @click="loadBatches">刷新批量记录</el-button>
    <el-select v-if="batches.length" :model-value="selected?.id" aria-label="批量记录" :disabled="busy" @change="refreshSelected">
      <el-option v-for="batch in batches" :key="batch.id" :value="batch.id" :label="`${batch.name} · ${batchStatusLabels[batch.status] || batch.status}`" />
    </el-select>
    <template v-if="selected">
      <p>{{ selected.name }} · {{ batchStatusLabels[selected.status] || selected.status }} · 基线 #{{ selected.baselineRunId }}</p>
      <el-button v-if="batchPending(selected)" :disabled="busy" @click="cancel">取消本批未完成方案</el-button>
      <el-table :data="selected.members" border size="small" aria-label="批量计算进度">
        <el-table-column prop="name" label="方案" /><el-table-column prop="run.id" label="运行编号" /><el-table-column prop="run.status" label="状态" />
        <el-table-column label="结果或失败原因"><template #default="{ row }">{{ row.run.resultExpired ? '结果已过期' : row.run.error?.message || row.run.error?.code || (row.run.status === 'SUCCEEDED' ? '已产生计算结果' : '等待计算结果') }}</template></el-table-column>
        <el-table-column label="操作"><template #default="{ row }"><el-button link @click="emit('select-run', row.run.id)">查看运行与下载模型</el-button></template></el-table-column>
      </el-table>
      <p v-if="response.message">{{ response.message }}</p>
      <template v-if="days.length">
        <el-select v-model="day" aria-label="批量对比报告时刻"><el-option v-for="time in days" :key="time" :label="`${time} 天`" :value="time" /></el-select>
        <p v-if="isCompositional">按原始列号、对象、指标、单位和实际报告时刻分别比较；同名重复列保留，缺少同时间值时留空。</p>
        <p v-else>按同井、同指标、同单位和同报告时刻对比；缺失值保留为空。</p>
        <EclipseBatchResponseChart :rows="rows"/>
        <el-table :data="rows" max-height="350" border size="small" aria-label="批量方案结果对比">
          <el-table-column v-if="isCompositional" label="原始列（从1计）"><template #default="{row}">{{ row.vectorIndex + 1 }}</template></el-table-column>
          <el-table-column prop="name" label="方案" /><el-table-column prop="well" label="井" /><el-table-column prop="keyword" label="指标" /><el-table-column prop="timeDays" label="天" /><el-table-column prop="unit" label="单位" />
          <el-table-column label="基线"><template #default="{ row }">{{ format(row.baseline) }}</template></el-table-column><el-table-column label="方案结果"><template #default="{ row }">{{ format(row.current) }}</template></el-table-column><el-table-column label="差值"><template #default="{ row }">{{ format(row.difference) }}</template></el-table-column>
        </el-table>
      </template>
    </template>
  </section>
</template>

<style scoped>
.eclipse-batch-panel { margin: 20px 0; }
.eclipse-batch-panel .el-form { max-width: 620px; }
.eclipse-batch-panel .el-alert, .eclipse-batch-panel .el-table { margin: 12px 0; }
.eclipse-batch-panel .el-select { min-width: 230px; }
</style>
