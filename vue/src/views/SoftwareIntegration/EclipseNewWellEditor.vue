<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { buildNewWellParameters, newWellContext, newWellUnits } from './eclipseNewWell'
import { softwareIntegrationApi } from '@/api/softwareIntegration'
const props=defineProps({run:{type:Object,default:null},inspection:{type:Object,default:null},grid:{type:Object,default:null},fields:{type:Array,default:()=>[]},artifacts:{type:Array,default:()=>[]}})
const emit=defineEmits(['create-new-well-run'])
const context=computed(()=>newWellContext(props.inspection)),units=computed(()=>newWellUnits(props.inspection?.unitSystem)),groups=computed(()=>[...new Set(props.inspection?.scheduleMetadata?.wells?.map(w=>w.group)||[])])
const baseline=computed(()=>props.run?.runType==='eclipse'&&props.run?.status==='SUCCEEDED'&&props.run?.resultContract==='VALID_FULL'&&props.run?.parameters==null)
const draft=ref({}),busy=ref(false),error=ref('');let generation=0
watch([()=>props.run?.id,()=>props.inspection],()=>{draft.value={well:'',group:groups.value[0]||'',i:null,j:null,k:null,referenceDepth:null,diameter:null,kh:null,skin:0,waterRate:null,bhpLimit:null}}, {immediate:true})
watch([draft,()=>props.run,()=>props.inspection,()=>props.grid,()=>props.fields,()=>props.artifacts],()=>{generation++;busy.value=false;error.value=''}, {deep:true,flush:'sync'})
onBeforeUnmount(()=>{generation++})
const numericInputs=computed(()=>[
  ['i','连接I（网格编号）'],['j','连接J（网格编号）'],['k','连接K（网格编号）'],['referenceDepth',`井底压力基准深度（${units.value?.length||''}）`],
  ['diameter',`井径（${units.value?.length||''}）`],['kh',`KH（mD.${units.value?.length||''}）`],['skin','皮肤系数（无量纲）'],
  ['waterRate',`产水目标（${units.value?.rate||''}）`],['bhpLimit',`井底压力下限（${units.value?.pressure||''}）`]])
const submit=async()=>{
  const current=++generation;busy.value=true;error.value=''
  try{
    const fields=props.fields.filter(f=>f.keyword==='ACTNUM'&&f.file===props.grid?.fileName&&f.timeStep===undefined)
    const artifacts=props.artifacts.filter(a=>a.name===`eclipse-output-${props.grid?.fileName}`)
    if(fields.length!==1||artifacts.length!==1)throw new Error('需要唯一完整ACTNUM及同源EGRID。')
    const field=fields[0],artifact=artifacts[0],argumentsSnapshot={run:props.run,inspection:props.inspection,grid:props.grid,field,artifact,draft:{...draft.value}}
    const parameters=buildNewWellParameters({...argumentsSnapshot,activeValue:1})
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
    const verified=buildNewWellParameters({...argumentsSnapshot,activeValue})
    if(current===generation)emit('create-new-well-run',verified)
  }catch(failure){if(current===generation)error.value=failure.message}
  finally{if(current===generation)busy.value=false}
}
</script>
<template>
  <section v-if="baseline && context" class="result-panel" aria-label="ECLIPSE 新增生产井">
    <h2>新增独立产水井</h2>
    <p>在初始时刻新增一口单连接井，保留已有井和控制。井口I/J取所填连接位置；连接按实际EGRID活动单元核对，不由轨迹推测。井容量 {{ context.maxWells }}、每组容量 {{ context.maxWellsPerGroup }}。</p>
    <el-form label-position="top" @submit.prevent="submit">
      <el-form-item label="新井名（最多8字符）"><el-input v-model="draft.well" aria-label="新增井名称" /></el-form-item>
      <el-form-item label="已有直接井组"><el-select v-model="draft.group" aria-label="新增井已有井组"><el-option v-for="g in groups" :key="g" :value="g" :label="g" /></el-select></el-form-item>
      <el-form-item v-for="[key,label] in numericInputs" :key="key" :label="label"><el-input-number v-model="draft[key]" :controls="false" :aria-label="`新增井 ${key}`" /></el-form-item>
      <el-alert v-if="error" type="error" :closable="false" :title="error" />
      <el-button type="primary" :loading="busy" @click="submit">创建新井工程并计算</el-button>
    </el-form>
    <p>CF自动计算；单连接皮肤0–100。WRAT是目标/上限，BHP是下限；油藏供给不足会减产或关井，结果应按实际WSTAT审阅。本期只开放纯水单DATA初始直接组，不支持生产组控、后期新井或多连接井。</p>
  </section>
</template>
