<script setup>
import {computed,onBeforeUnmount,onMounted,ref,watch} from 'vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {pipelineCapacityApi as api} from '@/api/pipelineCapacity'
import {gasPropertyContextKey} from '@/utils/pipelineGasPropertyState'
import {pvtModelColumns,pvtModelRows,pvtModelComposition,pvtModelIssues,pipelinePvtMethods,pipelinePvtDrafts} from '@/utils/pipelinePvtModel'
import PipelineTemperatureImportDialog from './PipelineTemperatureImportDialog.vue'
const props=defineProps({context:{type:Object,required:true},commandKey:{type:[Number,String],default:0}})
const emit=defineEmits(['saved'])
const catalog=ref([]),rows=ref([]),name=ref(''),method=ref('PR'),revision=ref(0),busy=ref(false),ready=ref(false),error=ref(''),savedMark=ref('')
const collapsed=ref(false),importVisible=ref(false),fileName=ref('')
let generation=0,active=true,currentContext=''
const values=()=>({pvtName:name.value,method:method.value,composition:pvtModelComposition(rows.value)})
const dirty=computed(()=>ready.value&&JSON.stringify(values())!==savedMark.value)
const total=computed(()=>rows.value.some(row=>row.percent==null||row.percent==='')?null:rows.value.reduce((sum,row)=>sum+Number(row.percent),0))
const issues=computed(()=>pvtModelIssues(rows.value,method.value))
const f=value=>value==null?'—':Number(value).toLocaleString('zh-CN',{maximumFractionDigits:9,useGrouping:false})
function cache(){if(ready.value&&currentContext)pipelinePvtDrafts.set(currentContext,{...values(),revision:revision.value,savedMark:savedMark.value,dirty:dirty.value})}
async function load(){cache();currentContext=gasPropertyContextKey(props.context);const token=++generation;busy.value=true;error.value='';ready.value=false
 try{const responses=await Promise.all([api.pvtModel(props.context),api.gasPropertyComponents()]);if(!active||token!==generation)return
  const model=responses[0].data;catalog.value=responses[1].data||[];rows.value=pvtModelRows(catalog.value,model?.composition||[])
  name.value=model?.pvtName||`${props.context.wellName} 气体物性模型`;method.value=model?.method||'PR';revision.value=model?.revision||0
  savedMark.value=JSON.stringify(values())
  const cached=pipelinePvtDrafts.get(currentContext)
  const cachedValues=cached?JSON.stringify({pvtName:cached.pvtName,method:cached.method,composition:pvtModelComposition(pvtModelRows(catalog.value,cached.composition))}):''
  if(cached?.dirty&&cachedValues!==savedMark.value){
   if(cached.revision!==revision.value)error.value='已保留未保存的气体组成，但服务器模型已有新版本。请核对后重新加载。'
   name.value=cached.pvtName;method.value=cached.method;revision.value=cached.revision;rows.value=pvtModelRows(catalog.value,cached.composition);savedMark.value=cached.savedMark
  }
  ready.value=true
 }catch(e){if(active&&token===generation)error.value=e.msg||e.message}finally{if(active&&token===generation)busy.value=false}}
async function reload(){
 if(dirty.value){try{await ElMessageBox.confirm('重新加载会覆盖本页未保存的气体组成。','重新加载 PVT 模型',{confirmButtonText:'重新加载',cancelButtonText:'返回编辑'})}catch{return}}
 pipelinePvtDrafts.delete(currentContext);ready.value=false;await load()
}
async function save(){error.value='';if(!name.value.trim()){error.value='请填写 PVT 模型名称。';return}if(issues.value.length){error.value=issues.value.join('\n');return}
 const token=++generation,submission={...props.context,revision:revision.value,...values()};busy.value=true
 try{const {data}=await api.savePvtModel(submission);if(!active||token!==generation)return
  revision.value=data.revision;name.value=data.pvtName;method.value=data.method;rows.value=pvtModelRows(catalog.value,data.composition)
  savedMark.value=JSON.stringify(values());emit('saved',data);ElMessage.success('当前井 PVT 模型已保存，可供物性与管流计算使用')
 }catch(e){if(active&&token===generation)error.value=e.msg||e.message}finally{if(active&&token===generation)busy.value=false}}
function imported({composition,fileName:filename}){rows.value=pvtModelRows(catalog.value,composition);fileName.value=filename;error.value='';ElMessage.success('气体组成已导入，核对合计后保存')}
function protect(e){if(dirty.value){e.preventDefault();e.returnValue=''}}
watch(()=>gasPropertyContextKey(props.context),load,{immediate:true})
watch(()=>props.commandKey,()=>{if(ready.value&&!busy.value&&!dirty.value)load()})
onMounted(()=>window.addEventListener('beforeunload',protect))
onBeforeUnmount(()=>{cache();active=false;++generation;window.removeEventListener('beforeunload',protect)})
defineExpose({dirty})
</script>
<template>
 <section class="pvt-model-page" :inert="busy">
  <header class="workspace-toolbar"><strong>PVT模型</strong><span>{{context.wellName}}</span><span class="muted">{{dirty?'有未保存修改':revision?'已保存':'参数待填写'}}</span></header>
  <div v-if="error" class="error-strip" role="alert">{{error}}<button v-if="!busy" @click="reload">重新加载</button></div>
  <p v-if="!ready" class="empty">{{busy?'正在加载气体组成及组分常数…':'模型未加载'}}</p>
  <div v-else class="workspace-body">
   <aside class="parameter-panel" :class="{collapsed}">
    <button v-if="collapsed" class="collapsed-tab" aria-label="展开参数设置" @click="collapsed=false">参数设置</button>
    <template v-else><div class="panel-heading">参数设置<button class="collapse-button" aria-label="收起参数设置" @click="collapsed=true"><svg width="14" height="14" viewBox="0 0 24 24" fill="#777" aria-hidden="true"><path d="M16,12V4H17V2H7V4H8V12L6,14V16H11.2V22H12.8V16H18V14L16,12Z" /></svg></button></div>
    <div class="parameter-form">
     <label class="field"><span>PVT 模型名称</span><input v-model="name" maxlength="100" /></label>
     <div class="field"><span>选择数据</span><button class="import-button" @click="importVisible=true">本地导入</button><small v-if="fileName">{{fileName}}</small></div>
     <fieldset class="radio-group"><legend>计算方法</legend><label v-for="option in pipelinePvtMethods" :key="option"><input v-model="method" type="radio" name="pvt-method" :value="option" />{{option}}</label></fieldset>
     <button class="save-button" @click="save">保存</button>
     <p class="hint">当前井共用一份气体组成与状态方程。压缩因子、定压比热容及管流计算自动读取已保存模型。</p>
     <p class="hint">本模型提供 Z、密度、Cp 及 Standing（资料原式）黏度；气体导热系数 λ 在内壁和总传热页手动填写。</p>
    </div></template>
   </aside>
   <main class="result-panel"><div class="result-toolbar"><span>数据列表</span><span class="muted">{{rows.length}} 个纯组分</span><span :class="{'invalid':issues.length}">摩尔含量合计：{{total==null?'有缺失项':f(total)+'%'}}</span></div>
    <div class="table-scroll"><table class="data-table" aria-label="PVT 完整气体组成"><thead><tr><th v-for="column in pvtModelColumns" :key="column.key">{{column.label}}</th></tr></thead><tbody><tr v-for="row in rows" :key="row.code"><td>{{row.sequence}}</td><td>{{row.code}}</td><td>{{row.name}}</td><td class="editable-cell"><input type="number" min="0" max="100" step="any" :value="row.percent" :aria-label="row.name+'摩尔含量（%）'" @input="row.percent=$event.target.value===''?null:Number($event.target.value)" /></td><td>{{f(row.criticalTemperatureK)}}</td><td>{{f(row.criticalPressureMpa)}}</td><td>{{f(row.acentricFactor)}}</td><td>{{f(row.molarMassKgMol)}}</td></tr></tbody></table>
    <p class="table-note">黄色单元格为摩尔百分含量，不含的组分填写 0，全部组分合计为 100%。临界温压、偏心因子与摩尔质量由内置组分库提供，只读。</p>
    <p class="table-note">NC6～NC10 分别为正己烷、正庚烷、正辛烷、正壬烷、正癸烷；不能将 C₇⁺ 等混合馏分直接填为某一种纯组分。</p>
    <p v-for="issue in issues" :key="issue" class="table-note invalid">{{issue}}</p>
    </div>
   </main>
  </div>
  <PipelineTemperatureImportDialog v-model="importVisible" kind="pvt" :catalog="catalog" :composition="pvtModelComposition(rows)" :well-name="context.wellName" @imported="imported" />
 </section>
</template>
<style scoped>
.pvt-model-page{display:flex;flex-direction:column;height:100%;min-height:0;color:#252525;font:13px 'Microsoft YaHei',sans-serif;background:#fff}.workspace-toolbar{display:flex;gap:14px;align-items:center;min-height:35px;background:#fafafa;border-bottom:1px solid #ddd}.workspace-toolbar strong{background:#f4d000;padding:9px 16px;font-size:14px}.workspace-body{display:flex;flex:1;min-height:0}.parameter-panel{flex:0 0 280px;width:280px;border-right:1px solid #ddd;overflow:auto}.parameter-panel.collapsed{flex-basis:34px;width:34px}.panel-heading{display:flex;justify-content:space-between;align-items:center;height:34px;padding:0 12px;background:#f2f2f2;border-bottom:1px solid #ddd}.parameter-form{padding:12px}.field{display:block;margin-bottom:14px}.field>span{display:block;margin-bottom:5px}input,button{font:inherit;box-sizing:border-box;color:inherit}input{width:100%;height:28px;padding:0 8px;border:1px solid #aaa;border-radius:3px}button{height:30px;padding:0 12px;border:1px solid #777;border-radius:4px;background:#fff;cursor:pointer}button:hover{background:#f5f5f5}.import-button{width:100%;text-align:left}.field small{display:block;margin-top:5px;overflow:hidden;text-overflow:ellipsis}.radio-group{border:0;padding:0;display:flex;gap:16px;margin:16px 0}.radio-group legend{margin-bottom:10px}.radio-group label{display:flex;align-items:center;gap:5px}.radio-group input{width:14px;height:14px;margin:0;accent-color:#333}.save-button{height:32px;background:#202020;color:#fff;border-color:#202020;padding:0 25px}.collapse-button{padding:0;width:20px;height:22px;border:0;background:transparent}.collapsed-tab{writing-mode:vertical-rl;width:34px;height:80px;padding:9px 0;letter-spacing:3px;border:0;border-radius:0}.hint,.table-note{color:#666;line-height:1.8;font-size:12px}.hint{margin-top:18px}.result-panel{flex:1;min-width:0;display:flex;flex-direction:column}.result-toolbar{display:flex;gap:16px;padding:11px 12px;border-bottom:1px solid #ddd}.table-scroll{flex:1;min-height:0;overflow:auto}.data-table{width:100%;min-width:1050px;border-collapse:separate;border-spacing:0}.data-table th,.data-table td{padding:8px 10px;text-align:center;border-bottom:1px solid #d4d7db;border-right:1px solid #d4d7db;background:#f4f4f4;white-space:nowrap}.data-table th{position:sticky;top:0;font-weight:400;z-index:1}.data-table .editable-cell{padding:0;background:#fffbe6}.editable-cell input{height:36px;border:1px solid transparent;border-radius:0;background:transparent;text-align:right}.editable-cell input:focus{border-color:#b99500;outline:0}.table-note{margin:10px 12px}.muted{color:#777}.invalid,.error-strip{color:#b44024}.error-strip{padding:10px 12px;background:#fff0ed;white-space:pre-line}.empty{text-align:center;margin:auto}
</style>
