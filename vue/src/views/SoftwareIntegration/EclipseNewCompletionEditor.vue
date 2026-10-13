<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { newWellContext, newWellUnits } from './eclipseNewWell'
import { buildNewCompletionParameters, completionProducerWells } from './eclipseNewCompletion'
import { softwareIntegrationApi } from '@/api/softwareIntegration'
const props=defineProps({run:{type:Object,default:null},inspection:{type:Object,default:null},grid:{type:Object,default:null},fields:{type:Array,default:()=>[]},artifacts:{type:Array,default:()=>[]}})
const emit=defineEmits(['create-new-completion-run'])
const context=computed(()=>newWellContext(props.inspection)),units=computed(()=>newWellUnits(props.inspection?.unitSystem)),wells=computed(()=>completionProducerWells(props.inspection))
const baseline=computed(()=>props.run?.runType==='eclipse'&&props.run?.status==='SUCCEEDED'&&props.run?.resultContract==='VALID_FULL'&&props.run?.parameters==null)
const draft=ref({}),busy=ref(false),error=ref('');let generation=0
watch([()=>props.run?.id,()=>props.inspection],()=>{draft.value={well:wells.value[0]||'',i:null,j:null,k:null,diameter:null,kh:null,skin:0}}, {immediate:true})
watch([draft,()=>props.run,()=>props.inspection,()=>props.grid,()=>props.fields,()=>props.artifacts],()=>{generation++;busy.value=false;error.value=''}, {deep:true,flush:'sync'})
onBeforeUnmount(()=>{generation++})
const numericInputs=computed(()=>[
  ['i','连接I（网格编号）'],['j','连接J（网格编号）'],['k','连接K（网格编号）'],
  ['diameter',`井径（${units.value?.length||''}）`],['kh',`KH（mD.${units.value?.length||''}）`],['skin','皮肤系数（无量纲）']])
const submit=async()=>{
  const current=++generation;busy.value=true;error.value=''
  try{
    const fields=props.fields.filter(f=>f.keyword==='ACTNUM'&&f.file===props.grid?.fileName&&f.timeStep===undefined)
    const artifacts=props.artifacts.filter(a=>a.name===`eclipse-output-${props.grid?.fileName}`)
    if(fields.length!==1||artifacts.length!==1)throw new Error('需要唯一完整ACTNUM及同源EGRID。')
    const field=fields[0],artifact=artifacts[0],argumentsSnapshot={run:props.run,inspection:props.inspection,grid:props.grid,field,artifact,draft:{...draft.value}}
    const parameters=buildNewCompletionParameters({...argumentsSnapshot,activeValue:1})
    let remaining=(parameters.k-1)*parameters.nx*parameters.ny+(parameters.j-1)*parameters.nx+parameters.i-1,offset=null
    if(!['BIG','LITTLE'].includes(field.byteOrder)||!Array.isArray(field.segments))throw new Error('ACTNUM字节序或分段索引无效。')
    for(const segment of field.segments){if(!Number.isSafeInteger(segment.offset)||segment.offset<0||!Number.isSafeInteger(segment.length)||segment.length<=0||segment.length%4)throw new Error('ACTNUM分段索引无效。')
      const count=segment.length/4;if(remaining>=count){remaining-=count;continue;}offset=segment.offset+remaining*4;break}
    if(offset===null)throw new Error('连接坐标超出ACTNUM索引。')
    const response=await softwareIntegrationApi.downloadArtifactRange(parameters.baselineRunId,artifact.id,offset,4)
    if(current!==generation)return
    const blob=response instanceof Blob?response:new Blob([response]),bytes=await blob.arrayBuffer()
    if(current!==generation)return
    if(bytes.byteLength!==4)throw new Error('ACTNUM响应长度错误。')
    const activeValue=new DataView(bytes).getInt32(0,field.byteOrder==='LITTLE')
    const verified=buildNewCompletionParameters({...argumentsSnapshot,activeValue})
    if(current===generation)emit('create-new-completion-run',verified)
  }catch(failure){if(current===generation)error.value=failure.message}
  finally{if(current===generation)busy.value=false}
}
</script>
<template>
  <section v-if="baseline && context && wells.length" class="result-panel" aria-label="ECLIPSE 已有井新增完井">
    <h2>给已有生产井新增完井连接</h2>
    <p>初始时刻为已有OPEN/WRAT产水井新增一个活动连接，原井口、压力基准、组及井控保留。连接按实际EGRID核对，每井连接容量 {{ context.maxConnections }}。</p>
    <el-form label-position="top" @submit.prevent="submit">
      <el-form-item label="已有生产井"><el-select v-model="draft.well" aria-label="新完井已有生产井"><el-option v-for="well in wells" :key="well" :value="well" :label="well" /></el-select></el-form-item>
      <el-form-item v-for="[key,label] in numericInputs" :key="key" :label="label"><el-input-number v-model="draft[key]" :controls="false" :aria-label="`新完井 ${key}`" /></el-form-item>
      <el-alert v-if="error" type="error" :closable="false" :title="error" />
      <el-button type="primary" :loading="busy" @click="submit">新增完井连接并计算</el-button>
    </el-form>
    <p>CF自动计算，皮肤0–100；本期纯水单DATA初始单连接新增，不支持后期射孔、多层批量、注入井或复杂工程。真实影响取决于原井控和油藏供给，不保证新增连接就增产。</p>
  </section>
</template>
