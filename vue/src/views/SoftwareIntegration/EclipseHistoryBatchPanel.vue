<script setup>
import EclipseHistoryRanking from './EclipseHistoryRanking.vue'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { softwareIntegrationApi } from '@/api/softwareIntegration'
import { historyIdentity, historyRunAvailable, validHistoryEvaluation } from './eclipseHistory.js'
import { historyBatchCandidates, historyBatchComparison, historyBatchCsv } from './eclipseHistoryBatch.js'
const props=defineProps({dataset:{type:Object,default:null}})
const emit=defineEmits(['select-run'])
const batches=ref([]),batchId=ref(null),batch=ref(null),evaluations=ref({}),failures=ref({}),error=ref(''),busy=ref(false),metricKey=ref('')
const metricChoices=computed(()=>[...new Map((props.dataset?.observations || []).map(o=>[historyIdentity(o),o])).entries()].map(([key,row])=>({key,label:`${row.vectorIndex == null ? '' : '第'+(row.vectorIndex+1)+'列 · '}${row.well || '全场'} · ${row.keyword} · ${row.unit}`})))
const comparison=computed(()=>batch.value?historyBatchComparison({batch:batch.value,dataset:props.dataset,evaluations:evaluations.value,failures:failures.value,metricKey:metricKey.value}):{rows:[],message:'',canRank:false})
let epoch=0,listToken=0,compareToken=0,disposed=false
const context=()=>({epoch,id:props.dataset?.metadata?.id,sha:props.dataset?.metadata?.sourceSha256,version:props.dataset?.metadata?.modelVersionId})
const current=c=>!disposed && c.epoch===epoch && c.id===props.dataset?.metadata?.id && c.sha===props.dataset?.metadata?.sourceSha256 && c.version===props.dataset?.metadata?.modelVersionId
const message=e=>e?.message || e?.msg || '批量历史校核请求失败，请重试。'
async function loadBatches(){
 const c=context(),token=++listToken
 try{
  const reply=await softwareIntegrationApi.listEclipseBatches(c.version)
  if(!current(c) || token!==listToken)return
  if(!Array.isArray(reply.data) || reply.data.some(b=>!Number.isSafeInteger(b?.id) || b.id<=0 || b.modelVersionId!==c.version))throw new Error('批量记录与观察模型版本不一致。')
  batches.value=reply.data.filter(b=>b.baselineRunId===props.dataset.metadata.baselineRunId)
  if(!batches.value.some(b=>b.id===batchId.value))batchId.value=batches.value[0]?.id || null
  error.value=''
 }catch(e){if(current(c) && token===listToken)error.value=message(e)}
}
async function compare(){
 const c=context(),token=++compareToken,id=batchId.value,source=props.dataset
 batch.value=null;evaluations.value={};failures.value={};error.value='';busy.value=false
 if(!id || !source)return
 busy.value=true
 try{
  const reply=await softwareIntegrationApi.getEclipseBatch(id)
  if(!current(c) || token!==compareToken || batchId.value!==id)return
  if(reply.data?.id!==id)throw new Error('返回的批量记录编号不一致。')
  const candidates=historyBatchCandidates(reply.data,source)
  const eligible=candidates.filter(row=>historyRunAvailable(row.run))
  const results=await Promise.allSettled(eligible.map(async row=>{
   const reply=await softwareIntegrationApi.compareEclipseHistory(source.metadata.id,row.run.id)
   if(!validHistoryEvaluation(reply.data,source,row.run))throw new Error('校核值与实际运行或观察源不一致。')
   return reply.data
  }))
  if(!current(c) || token!==compareToken || batchId.value!==id)return
  const accepted={},failed={}
  results.forEach((result,i)=>{const runId=eligible[i].run.id;if(result.status==='fulfilled')accepted[runId]=result.value;else failed[runId]=message(result.reason)})
  batch.value=reply.data;evaluations.value=accepted;failures.value=failed
 }catch(e){if(current(c) && token===compareToken)error.value=message(e)}
 finally{if(current(c) && token===compareToken)busy.value=false}
}
function exportComparison(){
 if(!batch.value || !props.dataset)return
 const url=URL.createObjectURL(new Blob([historyBatchCsv(comparison.value.rows,props.dataset)],{type:'text/csv;charset=utf-8'}))
 const link=document.createElement('a');link.href=url;link.download=`eclipse-batch-${batch.value.id}-history.csv`;link.click();setTimeout(()=>URL.revokeObjectURL(url),0)
}
watch(()=>[props.dataset?.metadata?.id,props.dataset?.metadata?.sourceSha256,props.dataset?.metadata?.modelVersionId,props.dataset?.metadata?.baselineRunId],()=>{
 epoch++;listToken++;compareToken++;batches.value=[];batchId.value=null;batch.value=null;evaluations.value={};failures.value={};error.value='';busy.value=false;metricKey.value=metricChoices.value[0]?.key || ''
 if(props.dataset)loadBatches()
},{immediate:true})
watch(batchId,()=>{compareToken++;batch.value=null;evaluations.value={};failures.value={};error.value='';busy.value=false})
onBeforeUnmount(()=>{disposed=true;epoch++})
const format=v=>v==null?'不可计算':Number(v).toPrecision(8)
</script>
<template>
 <section v-if="dataset" aria-label="ECLIPSE 批量历史校核" class="history-batch-panel">
  <h4>用这份观察数据比较批量方案</h4>
  <p>选择同一原基线的批量任务，一次校核原基线与各成功方案。失败或过期结果保留原因，不参与误差排序。</p>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <el-button :disabled="busy" @click="loadBatches">刷新可校核批次</el-button>
  <el-select v-if="batches.length" v-model="batchId" aria-label="历史校核批次" :disabled="busy"><el-option v-for="b in batches" :key="b.id" :value="b.id" :label="`${b.name} · #${b.id}`" /></el-select>
  <p v-else>没有使用观察记录基线的批量任务，请先建立并计算独立方案。</p>
  <el-button :loading="busy" :disabled="!batchId" @click="compare">校核本批方案</el-button>
  <template v-if="batch">
   <el-select v-model="metricKey" aria-label="批量历史比较指标" filterable><el-option v-for="c in metricChoices" :key="c.key" :value="c.key" :label="c.label" /></el-select>
   <p>{{ comparison.message }}</p>
   <EclipseHistoryRanking :comparison="comparison" :metric-label="metricChoices.find(c => c.key === metricKey)?.label || ''" @select-run="emit('select-run', $event)"/>
   <el-table :data="comparison.rows" border size="small" aria-label="批量方案历史误差">
    <el-table-column prop="name" label="方案" /><el-table-column prop="runId" label="运行" /><el-table-column prop="status" label="状态" />
    <el-table-column label="可比点"><template #default="{row}">{{ row.metric ? `${row.metric.matchedCount}/${row.metric.observedCount}` : '不可计算' }}</template></el-table-column>
    <el-table-column label="MAE"><template #default="{row}">{{ format(row.metric?.mae) }}</template></el-table-column><el-table-column label="RMSE"><template #default="{row}">{{ format(row.metric?.rmse) }}</template></el-table-column><el-table-column label="平均误差"><template #default="{row}">{{ format(row.metric?.bias) }}</template></el-table-column>
    <el-table-column label="本指标排名"><template #default="{row}">{{ row.rank ?? '不排序' }}</template></el-table-column><el-table-column prop="reason" label="原因" />
    <el-table-column label="操作"><template #default="{row}"><el-button link @click="emit('select-run',row.runId)">查看方案逐点结果</el-button></template></el-table-column>
   </el-table>
   <p>当前指标单位：{{ metricChoices.find(c=>c.key===metricKey)?.label }}。指标分别比较，不混合不同单位。</p>
   <el-button @click="exportComparison">下载本指标批量校核 CSV</el-button>
  </template>
 </section>
</template>
<style scoped>
.history-batch-panel { margin: 16px 0; }
.history-batch-panel .el-table, .history-batch-panel .el-alert { margin: 12px 0; }
.history-batch-panel .el-select { min-width: 260px; }
</style>
