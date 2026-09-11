<script setup>
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { pipelineCapacityApi as api } from '@/api/pipelineCapacity'
import { pipelinePvtSourceStamp } from '@/utils/pipelinePvtModel'
import { isCurrentPipelineFlowResult } from '@/utils/pipelineFlowResults'
import { activeBoundaryCase } from '@/utils/pipelineBoundary'
import PipelineNumber from './PipelineNumber.vue'
import { temperatureDrafts, normalizeTemperatureSegments, normalizeTemperatureSettings, normalizeTemperatureDraft, temperatureSettingsStamp, temperatureContextKey, packTemperatureResults, restoreTemperatureResults, temperatureResultPanel, temperatureRestorePlan } from '@/utils/pipelineTemperatureState'
import { pipelineTemperaturePages } from '@/utils/pipelineNavigation'
import { temperatureCalculationKinds, temperatureResultTabs, temperatureCoefficientStamp } from '@/utils/pipelineTemperatureCalculations'
import PipelineChart from './PipelineChart.vue'
import PipelineTemperatureReadOnlyTable from './PipelineTemperatureReadOnlyTable.vue'
import { temperatureLayerInputRows, temperatureOuterGeometryRows, temperatureEffectiveInputs, temperatureViscosityInputRows } from '@/utils/pipelineTemperatureInputs'
import PipelineTemperatureImportDialog from './PipelineTemperatureImportDialog.vue'
import { innerInputColumns, innerPropertyColumns, gasConductivityInputColumn } from '@/utils/pipelineInnerImport'
import { wallImportColumns } from '@/utils/pipelineWallImport'
import { outerInputColumns, overallInputColumns, externalHeatMethods } from '@/utils/pipelineThermalParameterImport'
const props=defineProps({context:Object,input:Object,initialTab:{type:String,default:'properties'},commandKey:{type:[Number,String],default:0}})
const emit=defineEmits(['saved'])
const cacheKey=temperatureContextKey(props.context),cached=normalizeTemperatureDraft(temperatureDrafts.get(cacheKey))
let active=true,gasRequest=0,propertyRequest=0,propertyTimer=null,recordRequest=0
const busy=ref(false),ready=ref(false),error=ref(''),notice=ref(''),revision=ref(0),topologyRevision=ref(0)
const graph=ref(null),settings=ref({segments:[],tolerance:0.001,maxIterations:30}),saved=ref('')
const savedResultsMark=ref('')
const selected=ref(''),bottom=ref('input'),solution=ref(null),solutionMark=ref(''),calculationMode=ref('coefficient')
const paramsCollapsed=ref(false),importVisible=ref(false),importGraph=ref(null),importTopologyRevision=ref(0),importedFileName=ref('')
const calculated=ref({inner:{},wall:{},outer:{},overall:{}})
const coupledResults=ref({})
const gasConfig=ref(null),gasError=ref(''),gasBusy=ref(false)
const pvtModelError=ref(''),propertyPreviews=ref({}),propertyErrors=ref({}),propertyBusy=ref(false)
const propertyPointKey=c=>JSON.stringify([c?.pvtId??null,c?.propertyPressureMpa??null,c?.propertyTemperatureC??null,pipelinePvtSourceStamp(gasConfig.value)])
const propertyPreview=id=>{const c=settings.value.segments.find(c=>c.edgeId===id),preview=propertyPreviews.value[id];return preview?.key===propertyPointKey(c)?preview.detail:null}
const propertySourcesStamp=computed(()=>JSON.stringify(settings.value.segments.map(c=>[c.edgeId,propertyPreview(c.edgeId)?.sourceRevision??null])))
const gasStamp=computed(()=>JSON.stringify(gasConfig.value && {revision:gasConfig.value.revision,compositionRevision:gasConfig.value.compositionRevision,compositionChanged:gasConfig.value.compositionChanged}))
const flowStamp=computed(()=>JSON.stringify({settings:{...settings.value,segments:normalizeTemperatureSegments(settings.value.segments).map(({innerDiameterMm,propertySource,densityKgM3,viscosityMpaS,cpJkgK,...parameters})=>parameters)},topologyRevision:topologyRevision.value,input:props.input,gas:gasStamp.value,pvt:propertySourcesStamp.value}))
const dirty=computed(()=>saved.value!==temperatureSettingsStamp(settings.value))
const solutionStale=computed(()=>gasBusy.value||!!gasError.value||!isCurrentPipelineFlowResult(solution.value?.result)||solutionMark.value!==flowStamp.value)
const edge=computed(()=>graph.value?.edges.find(e=>e.id===selected.value))
const config=computed(()=>settings.value.segments.find(c=>c.edgeId===selected.value))
const page=computed(()=>pipelineTemperaturePages.find(item=>item.tab===props.initialTab) || pipelineTemperaturePages[0])
const tab=computed(()=>page.value.tab)
const kind=computed(()=>temperatureCalculationKinds[tab.value])
const activeResults=computed(()=>kind.value==='overall' && calculationMode.value==='coupled'?coupledResults.value:calculated.value[kind.value])
const rows=computed(()=>Object.values(activeResults.value))
const resultTabs=computed(()=>temperatureResultTabs(kind.value))
const isInner=computed(()=>kind.value==='inner')
const isWall=computed(()=>kind.value==='wall')
const isOuter=computed(()=>kind.value==='outer')
const isOverall=computed(()=>kind.value==='overall')
const usesPvt=computed(()=>isInner.value||isOverall.value)
const pvtId=computed(()=>gasConfig.value?.pvtId??null)
const pvtName=computed(()=>gasConfig.value?.pvtName||'尚未保存 PVT 模型')
const parameterColumns=computed(()=>isOuter.value?outerInputColumns:overallInputColumns)
const inputColumns=innerInputColumns
const wallColumns=wallImportColumns.map(col=>({...col,label:col.label.split('（')[0],unit:col.label.includes('（')?col.label.slice(col.label.indexOf('（')+1,-1):''}))
const inputRows=computed(()=>graph.value?.edges.map(e=>({edge:e,config:settings.value.segments.find(c=>c.edgeId===e.id)})) || [])
const wallInputRows=computed(()=>inputRows.value.flatMap(({edge:e,config:c},pipeIndex)=>{
 const layers=Array.isArray(c?.layers)?c.layers:[]
 return (layers.length?layers:[null]).map((layer,layerIndex)=>({edge:e,config:c,pipeIndex,layer,layerIndex,rowSpan:Math.max(1,layers.length)}))
}))
const layerReferenceRows=computed(()=>temperatureLayerInputRows(graph.value,settings.value))
const fluidColumns=[innerInputColumns.find(column=>column.key==='actualFlowM3s'),...innerPropertyColumns,gasConductivityInputColumn]
const effectiveInputs=id=>temperatureEffectiveInputs(settings.value.segments.find(c=>c.edgeId===id),activeResults.value[id],{
 preview:propertyPreview(id),coupled:isOverall.value&&calculationMode.value==='coupled',current:!!activeResults.value[id]&&!isStale(activeResults.value[id])
})
const methodName=value=>externalHeatMethods.find(method=>method.value===value)?.label || '—'
const actualProperties=id=>activeResults.value[id]?.pvtProperties&&!isStale(activeResults.value[id])?activeResults.value[id].pvtProperties:null
const propertyDetails=id=>actualProperties(id)||propertyPreview(id)
const propertyIssue=id=>propertyErrors.value[id]||propertyDetails(id)?.issue||(!pvtId.value?'请先保存当前井 PVT 模型。':'')
const propertyReferenceColumns=[{key:'pipeName',label:'管道名称'},{key:'pvtName',label:'PVT 模型'},{key:'name',label:'物性参数'},{key:'value',label:'数值'},{key:'unit',label:'单位'},{key:'source',label:'数据来源'}]
const propertyReferenceRows=computed(()=>inputRows.value.flatMap(({edge:e})=>innerPropertyColumns.map(column=>({pipeName:e.name,
 pvtName:propertyDetails(e.id)?.pvtName||pvtName.value,
 name:column.label,value:effectiveInputs(e.id)[column.key],unit:column.unit,source:propertyDetails(e.id)?.sources?.[column.key]||'当前井 PVT 模型暂无可用数据'}))))
const viscosityReferenceRows=computed(()=>temperatureViscosityInputRows(graph.value,Object.fromEntries(inputRows.value.map(({edge:e})=>[
 e.id,actualProperties(e.id)||(isOverall.value&&calculationMode.value==='coupled'?null:propertyPreview(e.id))
]))))
const formulaRows=computed(()=>[
 ['长度换算',0.001,'m/mm'],
 ...((isInner.value||isOverall.value)?[['动力黏度换算',0.001,'Pa·s / mPa·s'],['圆周率 π',Math.PI,'—'],['Nu 关联式系数',0.021,'—'],['Re 指数',0.8,'—'],['Pr 指数',0.43,'—'],['关联式最低雷诺数',10000,'—']]:[]),
 ...(!isInner.value?[['直径增量 / 单层厚度',2,'—']]:[])
].map(([name,value,unit])=>({name,value,unit})))
const formulaColumns=[{key:'name',label:'固定参数'},{key:'value',label:'数值'},{key:'unit',label:'单位'}]
const layerReferenceColumns=computed(()=>[
 {key:'pipeName',label:'管道名称'},{key:'layerNumber',label:'层序号'},{key:'name',label:'材料层名称'},
 {key:'thicknessMm',label:'材料层厚度',unit:'mm'},...(!isOuter.value?[{key:'conductivityWmK',label:'材料导热系数',unit:'W/(m·K)'}]:[]),
 {key:'innerDiameterMm',label:'本层内径',unit:'mm'},{key:'outerDiameterMm',label:'本层外径',unit:'mm'}
])
const displayedLayers=computed(()=>layerReferenceRows.value.map(row=>({...row,layerNumber:row.layerIndex+1})))
const outerGeometryRows=computed(()=>temperatureOuterGeometryRows(graph.value,settings.value))
const outerGeometryColumns=[{key:'pipeName',label:'管道名称'},{key:'outerDiameterMm',label:'最外层直径',unit:'mm'},
 {key:'depthRatio',label:'h / D外'},{key:'y0M',label:'y₀ = √(h²－D外²/4)',unit:'m'},{key:'acoshRatio',label:'acosh(2h / D外)'}]
const overallFluidColumns=computed(()=>[{key:'pipeName',label:'管道名称'},...fluidColumns.map(column=>({...column,
 label:column.label+'（'+fluidSource(column.key)+'）'}))])
const overallFluidRows=computed(()=>inputRows.value.map(({edge:e,config:c})=>({pipeName:e.name,...effectiveInputs(e.id)})))
const externalReferenceColumns=[{key:'pipeName',label:'管道名称'},...outerInputColumns,{key:'outerDiameterMm',label:'最外层直径',unit:'mm'}]
const externalReferenceRows=computed(()=>inputRows.value.map(({edge:e,config:c})=>({pipeName:e.name,externalMethod:methodName(c.externalMethod),
 burialDepthM:c.burialDepthM,soilConductivityWmK:c.soilConductivityWmK,surfaceCoefficientWm2K:c.externalMethod==='surface-resistance'?c.surfaceCoefficientWm2K:'本方法不使用',outerDiameterMm:outerDiameterMm(e,c)})))
const coupledGeometryColumns=[{key:'name',label:'管道名称'},{key:'lengthM',label:'长度',unit:'m'},{key:'diameterMm',label:'内径',unit:'mm'},
 {key:'roughnessMm',label:'绝对粗糙度',unit:'mm'},{key:'heightM',label:'终点－起点高差',unit:'m'}]
const coupledGeometryRows=computed(()=>graph.value?.edges.map(e=>({name:e.name,...e.parameters,
 heightM:pipeHeight(e)})) || [])
function pipeHeight(edge) {
 const start=graph.value?.nodes.find(node=>node.id===edge.source)?.parameters.elevationM,end=graph.value?.nodes.find(node=>node.id===edge.target)?.parameters.elevationM
 return Number.isFinite(start)&&Number.isFinite(end)?end-start:null
}
const equipmentReferenceColumns=[{key:'name',label:'设备名称'},{key:'type',label:'类型'},{key:'afterPipe',label:'所在管段出口'},
 {key:'lossK',label:'局部阻力系数 K'},{key:'pressureRatio',label:'压比'},{key:'efficiency',label:'效率'},
 {key:'maxPressureMpa',label:'最高压力',unit:'MPa'},{key:'maxPowerKw',label:'最大功率',unit:'kW'}]
const equipmentReferenceRows=computed(()=>(graph.value?.nodes||[]).filter(node=>['valve','compressor'].includes(node.type)).flatMap(node=>[
 {name:node.name,type:node.type,...node.parameters},...(Array.isArray(node.parameters.extraEquipment)?node.parameters.extraEquipment:[])
].map(device=>({...device,afterPipe:graph.value.edges.find(edge=>edge.target===node.id)?.name || '—',type:device.type==='valve'?'阀门':'压缩机',
 lossK:device.type==='valve'?device.lossK:'不使用',pressureRatio:device.type==='compressor'?device.pressureRatio:'不使用',efficiency:device.type==='compressor'?device.efficiency:'不使用',
 maxPowerKw:device.type==='compressor'?device.maxPowerKw:'不使用'}))))
const flowReferenceColumns=[{key:'name',label:'联算设置 / 物性'},{key:'value',label:'数值'},{key:'unit',label:'单位'}]
const flowReferenceRows=computed(()=>{
 const actual=solution.value&&!solutionStale.value&&!gasError.value&&!gasBusy.value?solution.value.input:null,flow=actual||props.input
 return [
 ['参数状态',actual?'实际联算输入快照':'当前管流设置；边界推导值待联算','—'],['计算目标',actual?{rate:'计算流量',outlet:'计算出口压力',inlet:'计算入口压力'}[flow.target]:'由边界条件确定','—'],
 ['摩阻计算方法',flow.frictionMethod,'—'],['入口温度',flow.inletC,'℃'],['气体相对密度',flow.gasGravity,'—'],
 ['压缩因子 Z',flow.z,'—'],['定压比热容 Cp',actual?flow.cpJkgK:null,'J/(kg·K)'],['动力黏度',actual?flow.viscosityMpaS:null,'mPa·s'],
 ['焦耳－汤姆逊系数',flow.jtKmpa,'K/MPa'],['标况压力',flow.standardPressurePa,'Pa'],['标况温度',flow.standardTemperatureK,'K'],
 ['标况压缩因子',flow.standardZ,'—'],['迭代容差',settings.value.tolerance,'—'],['最大迭代次数',settings.value.maxIterations,'次']
 ].map(([name,value,unit])=>({name,value,unit}))
})
const boundaryReferenceColumns=[{key:'name',label:'边界节点'},{key:'supplyRate10k',label:'上采量',unit:'万m³/d'},{key:'withdrawalRate10k',label:'分输量',unit:'万m³/d'},
 {key:'pressureMpa',label:'压力',unit:'MPa绝压'},{key:'temperatureC',label:'温度',unit:'℃'}]
const boundaryReferenceRows=computed(()=>props.input.boundary?(activeBoundaryCase(props.input.boundary)?.nodes||[]).map(record=>({...record,name:graph.value?.nodes.find(n=>n.id===record.nodeId)?.name || record.nodeId})):[
 {name:'入口',supplyRate10k:props.input.target==='rate'?null:props.input.rate10k,pressureMpa:props.input.target==='inlet'?null:props.input.inletMpa,temperatureC:props.input.inletC},
 {name:'出口',pressureMpa:props.input.target==='outlet'?null:props.input.outletMpa}
])
function fluidSource(key) {
 if(key==='actualFlowM3s'&&calculationMode.value==='coupled')return '温压联算自动换算'
 if(key==='gasConductivityWmK')return '手动输入'
 return key==='actualFlowM3s'?'管内壁放热系数页':'当前井 PVT 模型'
}
function captureResults() {return packTemperatureResults({context:props.context,topologyRevision:topologyRevision.value,calculated:calculated.value,coupledResults:coupledResults.value,solution:solution.value,solutionMark:solutionMark.value,calculationMode:calculationMode.value})}
const resultsStamp=value=>JSON.stringify({...value,calculationMode:undefined})
const resultsDirty=computed(()=>ready.value&&resultsStamp(captureResults())!==savedResultsMark.value)
function selectResultPanel() {bottom.value=temperatureResultPanel(kind.value,calculated.value,coupledResults.value,calculationMode.value)}
const propertyKey=computed(()=>JSON.stringify({kind:kind.value,points:settings.value.segments.map(c=>[c.edgeId,propertyPointKey(c)])}))
function queueProperties() {
 clearTimeout(propertyTimer);++propertyRequest;propertyErrors.value={};propertyBusy.value=false
 if(!active||!ready.value||!usesPvt.value)return
 propertyBusy.value=true;propertyTimer=setTimeout(refreshProperties,250)
}
async function refreshProperties() {
 clearTimeout(propertyTimer);const token=++propertyRequest
 if(!active||!ready.value||!usesPvt.value)return
 propertyBusy.value=true
 const entries=await Promise.all(settings.value.segments.map(async c=>{
  const key=propertyPointKey(c)
  if(!c.pvtId)return [c.edgeId,key,null,null]
  try {const {data}=await api.temperatureProperties({...props.context,pvtId:c.pvtId,
    pressureMpa:c.propertyPressureMpa??undefined,temperatureC:c.propertyTemperatureC??undefined});return [c.edgeId,key,data,null]}
  catch(e){return [c.edgeId,key,null,e.msg||e.message]}
 }))
 if(!active||token!==propertyRequest)return
 propertyPreviews.value=Object.fromEntries(entries.filter(e=>e[2]).map(([id,key,detail])=>[id,{key,detail}]))
 propertyErrors.value=Object.fromEntries(entries.filter(e=>e[3]).map(([id,,,message])=>[id,message]));propertyBusy.value=false
}
async function refreshPvtModel() {
 const token=++recordRequest
 try {const {data}=await api.pvtModel(props.context)
  if(active&&token===recordRequest){gasConfig.value=data||null;pvtModelError.value=!data?'请先在管束能力 PVT 模型页保存当前井的完整气体组成。':data.issue||'';gasError.value=pvtModelError.value
   for(const c of settings.value.segments){c.pvtId=data?.pvtId??null;clearLegacyProperties(c)}
  }
 }catch(e){if(active&&token===recordRequest)pvtModelError.value='PVT 模型加载失败：'+(e.msg||e.message)}
}
async function refreshSources() {
 if(!active||!ready.value||!usesPvt.value)return
 await refreshPvtModel();await refreshProperties()
}
function refreshOnFocus() {
 if(!busy.value)refreshSources()
}
function clearLegacyProperties(c) {
 for(const column of innerPropertyColumns)delete c[column.key]
 delete c.propertySource
}
function outerDiameterMm(e,c) {
 const layers=c?.layers
 if(!layers?.length || layers.some(l=>!Number.isFinite(l.thicknessMm)||!(l.thicknessMm>0)))return null
 return Number(e?.parameters.diameterMm)+layers.reduce((d,l)=>d+2*l.thicknessMm,0)
}
function parameterDisabled(c,key) {
 return key==='surfaceCoefficientWm2K'?c.externalMethod!=='surface-resistance':
  ['propertyPressureMpa','propertyTemperatureC'].includes(key)&&calculationMode.value==='coupled'
}
function coefficientStamp(scope,id) {
 const c=settings.value.segments.find(c=>c.edgeId===id)
 const d=graph.value?.edges.find(e=>e.id===id)?.parameters.diameterMm
 return temperatureCoefficientStamp(scope,c,d,topologyRevision.value,propertyPreview(id)?.sourceRevision??null)
}
function isStale(entry,scope=kind.value) {
 return entry && (entry.stamp!==coefficientStamp(scope,entry.edgeId) || (entry.fromSolve && (gasBusy.value||!!gasError.value||!isCurrentPipelineFlowResult(solution.value?.result)||entry.flowStamp!==flowStamp.value)))
}
const stale=computed(()=>rows.value.some(r=>isStale(r)))
watch(()=>[tab.value,props.commandKey],()=>{selectResultPanel();error.value='';importVisible.value=false;importedFileName.value='';if(ready.value)refreshSources()},{immediate:true})
watch(calculationMode,()=>{if(ready.value){selectResultPanel();if(isOverall.value&&calculationMode.value==='coupled')refreshGas()}})
watch(()=>[ready.value,propertyKey.value],queueProperties)
function rowState(id) {const r=activeResults.value[id];return !r?'未计算':isStale(r)?'待重算':r.error || (r.fromSolve?'温压联算结果':'计算完成')}
const f=(v,d=3)=>v==null||!Number.isFinite(Number(v))?'—':Number(v).toFixed(d)
const metrics={
 reynolds:['雷诺数 Re','—',0],prandtl:['普朗特数 Pr','—'],nusselt:['努塞尔数 Nu','—'],
 alphaInside:['管内壁放热系数 α内','W/(m²·K)'],alphaOutside:['外部放热系数 α外','W/(m²·K)'],
 wallConductance:['平壁等效导热系数 α₃','W/(m²·K)'],documentK:['总传热系数 K（平壁）','W/(m²·K)'],innerAreaU:['总传热系数 U（内表面积）','W/(m²·K)'],
 outerDiameterM:['最外层直径','mm',1,1000],flatResistance:['平壁导热热阻','m²·K/W',6],
 innerResistance:['内壁换热热阻','m²·K/W',6],wallResistance:['圆筒导热热阻（内表面积）','m²·K/W',6],outerResistance:['外部换热热阻（内表面积）','m²·K/W',6]
}
const tableFields={inner:['reynolds','prandtl','nusselt','alphaInside'],wall:['outerDiameterM','wallConductance','flatResistance','wallResistance'],outer:['outerDiameterM','alphaOutside','outerResistance'],overall:['alphaInside','wallConductance','alphaOutside','documentK','innerAreaU']}
const columns=computed(()=>tableFields[kind.value].map(key=>({key,label:metrics[key][0],unit:metrics[key][1]})))
function metricValue(r,key) {
 const v=key==='flatResistance'?(r?.wallConductance?1/r.wallConductance:null):r?.[key]
 return f(v==null?null:v*(metrics[key][3] || 1),metrics[key][2] ?? 3)
}
const chartSeries=computed(()=>solution.value?[{name:'气体温度',data:solution.value.result.points.map(p=>[p.distanceM,p.temperatureC])},
 {name:'环境温度',dashed:true,data:solution.value.result.points.map(p=>[p.distanceM,solution.value.input.segments[p.segmentIndex]?.ambientC])}]:[])
function initial(e) {
 const p=props.input,pressure=p.inletMpa || p.outletMpa,t=p.inletC
 return {edgeId:e.id,externalMethod:'surface-fixed',ambientC:e.parameters.ambientC??null,burialDepthM:null,soilConductivityWmK:null,surfaceCoefficientWm2K:null,
 actualFlowM3s:null,gasConductivityWmK:null,pvtId:null,innerDiameterMm:e.parameters.diameterMm,
 propertyPressureMpa:pressure || null,propertyTemperatureC:t,layers:[{name:'钢管',thicknessMm:null,conductivityWmK:null}]}
}
async function load() {
 busy.value=true;error.value=''
 try {
  const [t,s]=await Promise.all([api.topology(props.context),api.temperature(props.context)])
  if(!active)return
  if(!t.data)throw new Error('请先在管网拓扑结构中保存当前井管段，再设置温度模型。')
  graph.value=t.data.graph;topologyRevision.value=t.data.revision;revision.value=s.data?.revision || 0
  const old=s.data?.settings?.segments || []
  settings.value=normalizeTemperatureSettings({segments:graph.value.edges.map(e=>{const c=old.find(c=>c.edgeId===e.id) || initial(e);return {...c,innerDiameterMm:c.innerDiameterMm??e.parameters.diameterMm}}),tolerance:s.data?.settings?.tolerance || 0.001,maxIterations:s.data?.settings?.maxIterations || 30})
  notice.value=old.some(c=>!graph.value.edges.some(e=>e.id===c.edgeId))?'拓扑已删除部分管段，本页仅保留当前管段设置。':''
  saved.value=temperatureSettingsStamp(settings.value)
  const restorePlan=temperatureRestorePlan(cached,settings.value,revision.value,topologyRevision.value)
  if(restorePlan.restoreDraft){settings.value=cached.settings;for(const c of settings.value.segments)if(!Object.hasOwn(c,'innerDiameterMm'))c.innerDiameterMm=graph.value.edges.find(e=>e.id===c.edgeId)?.parameters.diameterMm;saved.value=cached.saved;revision.value=cached.revision;notice.value='已恢复本次会话未保存的温度设置。'}
  const storedResults=restoreTemperatureResults(s.data?.settings?.savedResults,props.context,graph.value,topologyRevision.value)
  savedResultsMark.value=resultsStamp(packTemperatureResults({context:props.context,topologyRevision:topologyRevision.value,...storedResults}))
  const restored=restoreTemperatureResults(restorePlan.useCachedResults&&cached.results?cached.results:s.data?.settings?.savedResults,props.context,graph.value,topologyRevision.value)
  calculated.value=restored.calculated;coupledResults.value=restored.coupledResults;solution.value=restored.solution;solutionMark.value=restored.solutionMark;calculationMode.value=restored.calculationMode
  selected.value=graph.value.edges[0]?.id || '';ready.value=true;selectResultPanel();await refreshSources()
 }catch(e){if(active)error.value=e.msg || e.message}finally{if(active)busy.value=false}
}
function request(segments=settings.value.segments,includeResults=true) {return {...props.context,revision:revision.value,topologyRevision:topologyRevision.value,settings:{...normalizeTemperatureSettings({...settings.value,segments}),...(includeResults?{savedResults:captureResults()}:{})}}}
function validateManualDiameters(segments) {
 for(const c of segments)if(!Number.isFinite(c.innerDiameterMm)||!(c.innerDiameterMm>0))throw new Error(`${graph.value.edges.find(e=>e.id===c.edgeId)?.name || '当前管道'}：请填写有效的管内径（mm）`)
}
function validateGasConductivity(segments) {
 for(const c of segments)if(!Number.isFinite(c.gasConductivityWmK)||!(c.gasConductivityWmK>0))throw new Error(`${graph.value.edges.find(e=>e.id===c.edgeId)?.name||'当前管道'}：请填写大于 0 的气体导热系数 λ〔W/(m·K)〕。`)
}
async function save() {busy.value=true;error.value='';try{if(isInner.value)validateManualDiameters(settings.value.segments);for(const c of settings.value.segments)clearLegacyProperties(c);const submission=request();const data=(await api.saveTemperature(submission)).data;if(!active)return;revision.value=data.revision;settings.value=normalizeTemperatureSettings(settings.value);saved.value=temperatureSettingsStamp(settings.value);savedResultsMark.value=resultsStamp(submission.settings.savedResults);emit('saved',data);ElMessage.success('当前井温度参数及计算结果已保存')}catch(e){if(active)error.value=e.msg || e.message}finally{if(active)busy.value=false}}
async function calculate(all=false) {
 const scope=kind.value,label=page.value.label
 const segments=normalizeTemperatureSegments(all?settings.value.segments:[config.value])
 busy.value=true;error.value=''
 try {
  if(scope==='inner')validateManualDiameters(segments)
  if(scope==='inner'||scope==='overall') {
   validateGasConductivity(segments)
   await refreshSources()
   if(pvtModelError.value)throw new Error(pvtModelError.value)
   for(const c of segments)c.pvtId=pvtId.value
   for(const c of segments)clearLegacyProperties(c)
  }
  const marks=Object.fromEntries(segments.map(c=>[c.edgeId,coefficientStamp(scope,c.edgeId)]))
  const data=(await api.calculateTemperature({...props.context,topologyRevision:topologyRevision.value,segments},scope)).data
  if(!active)return
  for(const r of data){
   if(r.pvtProperties){
    const c=segments.find(c=>c.edgeId===r.edgeId),d=graph.value.edges.find(e=>e.id===r.edgeId)?.parameters.diameterMm
    propertyPreviews.value[r.edgeId]={key:propertyPointKey(c),detail:r.pvtProperties}
    marks[r.edgeId]=temperatureCoefficientStamp(scope,c,d,topologyRevision.value,r.pvtProperties.sourceRevision??null)
   }
   calculated.value[scope][r.edgeId]={...r,stamp:marks[r.edgeId]}
  }
  if(scope===kind.value)bottom.value='table'
  if(data.some(r=>r.error))ElMessage.warning('部分管段本项参数不完整或超出适用范围，请查看结果提示')
  else ElMessage.success(`${label}计算完成`)
 }catch(e){if(active)error.value=e.msg || e.message}finally{if(active)busy.value=false}
}
async function solve() {
 busy.value=true;error.value=''
 try {
  validateGasConductivity(settings.value.segments)
  await refreshSources();if(pvtModelError.value)throw new Error(pvtModelError.value)
  const mark=JSON.parse(flowStamp.value),submission=request(settings.value.segments,false)
  const marks=Object.fromEntries(submission.settings.segments.map(c=>[c.edgeId,coefficientStamp('overall',c.edgeId)]))
  const data=(await api.solveTemperature({thermal:submission,input:props.input})).data
  if(!active)return
  const revisions=new Map(JSON.parse(mark.pvt))
  for(const r of data.coefficients)if(r.pvtProperties){
   const c=submission.settings.segments.find(c=>c.edgeId===r.edgeId),d=graph.value.edges.find(e=>e.id===r.edgeId)?.parameters.diameterMm
   marks[r.edgeId]=temperatureCoefficientStamp('overall',c,d,submission.topologyRevision,r.pvtProperties.sourceRevision??null)
   revisions.set(r.edgeId,r.pvtProperties.sourceRevision??null)
  }
  mark.pvt=JSON.stringify([...revisions]);const actualMark=JSON.stringify(mark)
  solution.value=data;solutionMark.value=actualMark
  coupledResults.value=Object.fromEntries(data.coefficients.map(r=>[r.edgeId,{...r,stamp:marks[r.edgeId],fromSolve:true,flowStamp:actualMark}]))
  if(kind.value==='overall')bottom.value='table'
  ElMessage.success('温压联算完成')
 }catch(e){if(active)error.value=e.msg || e.message}finally{if(active)busy.value=false}
}
async function refreshGas() {
 if(kind.value!=='overall')return
 const token=++gasRequest;gasBusy.value=true
 try {await refreshPvtModel();if(!active||token!==gasRequest)return;gasError.value=pvtModelError.value}
 catch(e){if(active&&token===gasRequest)gasError.value='物性模型加载失败：'+(e.msg || e.message)}
 finally{if(active&&token===gasRequest)gasBusy.value=false}
}
async function openImport() {
 busy.value=true;error.value=''
 try {
  const {data}=await api.topology(props.context)
  if(!data?.graph?.edges?.length)throw new Error('请先在管网拓扑结构中保存管道，再下载模板或导入数据。')
  importGraph.value=data.graph;importTopologyRevision.value=data.revision;importVisible.value=true
 }catch(e){error.value=e.msg || e.message}finally{busy.value=false}
}
function acceptImport({segments,fileName,kind:importKind='inner'}) {
 if(importKind!==kind.value)return
 const previous=new Map(settings.value.segments.map(c=>[c.edgeId,c]))
 const imported=new Map(segments.map(c=>[c.edgeId,c]))
 const topologyChanged=topologyRevision.value!==importTopologyRevision.value
 graph.value=importGraph.value;topologyRevision.value=importTopologyRevision.value
 const selectedPvt=pvtId.value
 settings.value=normalizeTemperatureSettings({...settings.value,segments:graph.value.edges.map(e=>({...initial(e),pvtId:selectedPvt,...previous.get(e.id),...imported.get(e.id)}))})
 if(topologyChanged){calculated.value={inner:{},wall:{},outer:{},overall:{}};coupledResults.value={};solution.value=null;solutionMark.value=''}
 else calculated.value[importKind]={}
 if(!graph.value.edges.some(e=>e.id===selected.value))selected.value=graph.value.edges[0]?.id || ''
 importedFileName.value=fileName;bottom.value='input';error.value=''
 notice.value=topologyChanged?'已按最新保存的拓扑导入全部管道参数，请计算并保存。':''
 ElMessage.success(`已导入 ${segments.length} 个管道的参数，点击保存写入数据库`)
}
function choose(id) {selected.value=id}
function editInput(c,key,event) {c[key]=event.target.value===''?null:Number(event.target.value)}
function editParameter(c,key,event) {c[key]=event.target.value===''?null:Number(event.target.value)}
function addLayer(target=config.value) {
 if(!target)return
 if(!Array.isArray(target.layers))target.layers=[]
 if(target.layers.length<12)target.layers.push({name:target.layers.length?'保温层':'钢管',thicknessMm:null,conductivityWmK:null})
}
function moveLayer(target,index,direction) {
 const next=index+direction
 if(next>=0 && next<target.layers.length)target.layers.splice(next,0,target.layers.splice(index,1)[0])
}
function editLayer(layer,key,event) {layer[key]=event.target.value===''?null:Number(event.target.value)}
function protect(e){if(ready.value&&(dirty.value||resultsDirty.value)){e.preventDefault();e.returnValue=''}}
onMounted(()=>{load();window.addEventListener('beforeunload',protect);window.addEventListener('focus',refreshOnFocus)})
onBeforeUnmount(()=>{active=false;gasRequest++;propertyRequest++;recordRequest++;clearTimeout(propertyTimer);if(ready.value)temperatureDrafts.set(cacheKey,JSON.parse(JSON.stringify(normalizeTemperatureDraft({settings:settings.value,saved:saved.value,revision:revision.value,topologyRevision:topologyRevision.value,results:captureResults()}))));window.removeEventListener('beforeunload',protect);window.removeEventListener('focus',refreshOnFocus)})
</script>

<template>
  <section class="temperature-editor" :class="{ busy }" :inert="busy">
    <header class="workspace-toolbar">
      <strong class="workspace-title">温度模型－{{ page.label }}</strong>
      <span class="well-name">{{ context.wellName }}</span>
      <span class="save-state">{{ dirty ? '有未保存修改' : resultsDirty ? '计算结果未保存' : revision ? '已保存' : '参数待补充' }}</span>
    </header>
    <div v-if="error" class="error-strip" role="alert">{{ error }}</div>
    <div v-if="notice" class="notice">{{ notice }}</div>
    <div v-if="gasError && isOverall && calculationMode === 'coupled'" class="error-strip">{{ gasError }}</div>
    <div v-if="usesPvt && pvtModelError" class="error-strip">{{ pvtModelError }}</div>
    <div v-if="bottom === 'table' && stale" class="notice">本项相关参数已修改，标记为待重算的结果需要重新计算。</div>
    <div v-if="isOverall && calculationMode === 'coupled' && bottom === 'table' && solution && solutionStale" class="notice">温压联算条件已修改，以下为上次温度曲线，请重新联算。</div>
    <div v-if="!ready" class="empty">{{ busy ? '正在加载当前井管段…' : '请完善已保存的管网拓扑后重新加载。' }}</div>

    <div v-else class="thermal-workspace">
      <aside class="parameter-panel manual-controls" :class="{ collapsed: paramsCollapsed }">
        <button v-if="paramsCollapsed" class="parameter-collapsed-tab" type="button" title="展开参数设置" aria-label="展开参数设置" :aria-expanded="false" @click="paramsCollapsed = false">参数设置</button>
        <template v-else>
        <div class="panel-heading"><span>参数设置</span><button class="parameter-toggle" type="button" title="收起参数设置" aria-label="收起参数设置" :aria-expanded="true" @click="paramsCollapsed = true"><svg width="14" height="14" viewBox="0 0 24 24" fill="#777" aria-hidden="true"><path d="M16,12V4H17V2H7V4H8V12L6,14V16H11.2V22H12.8V16H18V14L16,12Z" /></svg></button></div>
        <div class="manual-parameter-form">
          <label v-if="usesPvt" class="field pvt-field"><span>当前井 PVT 模型</span><input :value="pvtName" readonly aria-label="当前井 PVT 模型" /></label>
          <div class="field import-field"><span>选择数据</span><button type="button" class="local-import-button" @click="openImport">本地导入</button><small v-if="importedFileName" class="imported-data-name" :title="importedFileName">{{ importedFileName }}</small></div>
          <fieldset v-if="isOverall" class="calculation-method">
            <legend>计算方式</legend>
            <label><input v-model="calculationMode" type="radio" value="coefficient" />总传热系数</label>
            <label><input v-model="calculationMode" type="radio" value="coupled" />温压联算</label>
          </fieldset>
          <template v-if="isOverall && calculationMode === 'coupled'">
            <PipelineNumber v-model="settings.tolerance" label="联算容差（相对流量 / 温差℃）" :min="0.00001" :max="0.1" />
            <PipelineNumber v-model="settings.maxIterations" label="最大迭代次数" :min="2" :max="80" step="1" />
            <p class="hint">沿用当前管流边界，迭代更新温度、压力和传热系数；当前支持串联管线。</p>
          </template>
          <div class="parameter-actions manual-actions">
            <button type="button" class="calculate-button" :disabled="!settings.segments.length" @click="isOverall && calculationMode === 'coupled' ? solve() : calculate(true)">计算</button>
            <button type="button" :disabled="!ready || !settings.segments.length" @click="save">保存</button>
          </div>
          <p v-if="isOverall" class="hint">保存后，管流计算会按所选边界工况自动读取这些参数并重新联算。</p>
        </div>
        </template>
      </aside>

      <main class="result-panel">
        <div class="result-toolbar">
          <span>{{ bottom === 'input' ? '数据列表' : '结果分析' }}</span>
          <span class="context-label">共 {{ graph.edges.length }} 个管段</span>
          <span v-if="bottom === 'input'" class="input-legend"><i class="editable-key" />本页输入可编辑 <i class="readonly-key" />自动读取 / 计算值只读</span>
          <span v-if="isOverall && calculationMode === 'coupled' && solution && bottom === 'table'">{{ solution.iterations }} 次联算迭代</span>
        </div>

        <div v-if="isInner && bottom === 'input'" class="table-scroll">
          <table class="data-table input-table inner-input-table" aria-label="内壁放热系数输入参数">
            <thead><tr><th class="index-column">序号</th><th class="pipe-name-column">管道名称</th><th v-for="col in inputColumns" :key="col.key">{{ col.label }}<small>{{ col.unit }}{{ col.key === 'gasConductivityWmK' ? ' · 必填' : '' }}</small></th><th v-for="col in innerPropertyColumns" :key="col.key">{{ col.label }}<small>{{ col.unit }} · PVT 只读</small></th></tr></thead>
            <tbody><tr v-for="({edge: e, config: c}, i) in inputRows" :key="e.id" :class="{ selected: selected === e.id }" @click="choose(e.id)">
              <td class="index-column">{{ i + 1 }}</td><td><button class="cell-button" @click="choose(e.id)">{{ e.name }}</button></td>
              <td v-for="col in inputColumns" :key="col.key" class="editable-cell"><input :value="c[col.key]" type="number" :min="col.key === 'propertyTemperatureC' ? -273.15 : 0" step="any" :aria-label="`${e.name} ${col.label}（${col.unit}）`" @focus="choose(e.id)" @input="editInput(c, col.key, $event)" /></td>
              <td v-for="col in innerPropertyColumns" :key="col.key" class="numeric property-cell" :title="propertyDetails(e.id)?.sources?.[col.key] || '当前井 PVT 模型暂无可用数据'">{{ f(effectiveInputs(e.id)[col.key], col.key === 'viscosityMpaS' || col.key === 'gasConductivityWmK' ? 6 : 3) }}</td>
            </tr></tbody>
          </table>
          <p v-if="!graph.edges.length" class="empty">当前拓扑暂无管段。</p>
          <p v-else class="table-note">管内径、工况体积流量 Q、物性计算温压及气体导热系数 λ 可编辑；λ 必填且大于 0，与总传热页共享。密度、Cp 和 Standing（资料原式）黏度根据当前井 PVT 模型及温压自动计算。管内径编辑值仅用于本页内壁系数试算。</p>
          <p v-if="propertyBusy" class="table-note">正在读取 PVT 物性…</p>
          <template v-for="({edge:e}) in inputRows" :key="e.id"><p v-if="propertyIssue(e.id)" class="table-note error-text">{{ e.name }}：{{ propertyIssue(e.id) }}</p></template>
          <PipelineTemperatureReadOnlyTable title="PVT 物性来源" :columns="propertyReferenceColumns" :rows="propertyReferenceRows" />
          <PipelineTemperatureReadOnlyTable title="Standing（资料原式）黏度计算参数" :columns="propertyReferenceColumns" :rows="viscosityReferenceRows" />
          <PipelineTemperatureReadOnlyTable title="内壁换热关联式固定参数" :columns="formulaColumns" :rows="formulaRows" />
          <p class="table-note">Re = 4Qρ / (πDμ)，Pr = μCp / λ，Nu = 0.021 Re^0.8 Pr^0.43，α内 = Nuλ / D。</p>
        </div>

        <div v-else-if="isWall && bottom === 'input'" class="table-scroll">
          <table class="data-table wall-input-table" aria-label="管道导热系数材料层参数">
            <thead><tr><th v-for="col in wallColumns" :key="col.key" :class="col.key + '-column'">{{ col.label }}<small v-if="col.unit">{{ col.unit }}</small></th><th class="operations-column">操作</th></tr></thead>
            <tbody><tr v-for="r in wallInputRows" :key="r.edge.id + '-' + r.layerIndex" :class="{ selected: selected === r.edge.id }" @click="choose(r.edge.id)">
              <td v-if="r.layerIndex === 0" :rowspan="r.rowSpan" class="index-column">{{ r.pipeIndex + 1 }}</td>
              <td v-if="r.layerIndex === 0" :rowspan="r.rowSpan" class="wall-pipe-name"><button class="cell-button" @click="choose(r.edge.id)">{{ r.edge.name }}</button><button class="add-layer-button" :aria-label="r.edge.name + ' 添加材料层'" :disabled="(r.config.layers?.length || 0) >= 12" @click.stop="addLayer(r.config)">添加材料层</button></td>
              <td v-if="r.layerIndex === 0" :rowspan="r.rowSpan" class="numeric geometry-value">{{ r.edge.parameters.diameterMm }}</td>
              <template v-if="r.layer">
                <td>{{ r.layerIndex + 1 }}</td>
                <td class="editable-cell"><input v-model="r.layer.name" :aria-label="r.edge.name + ' 第' + (r.layerIndex + 1) + '层名称'" maxlength="80" /></td>
                <td class="editable-cell"><input type="number" min="0" step="any" :value="r.layer.thicknessMm" :aria-label="r.edge.name + ' 第' + (r.layerIndex + 1) + '层厚度（mm）'" @input="editLayer(r.layer, 'thicknessMm', $event)" /></td>
                <td class="editable-cell"><input type="number" min="0" step="any" :value="r.layer.conductivityWmK" :aria-label="r.edge.name + ' 第' + (r.layerIndex + 1) + '层导热系数（W/(m·K)）'" @input="editLayer(r.layer, 'conductivityWmK', $event)" /></td>
                <td class="row-actions"><button :disabled="r.layerIndex === 0" :aria-label="r.edge.name + ' 第' + (r.layerIndex + 1) + '层上移'" @click.stop="moveLayer(r.config, r.layerIndex, -1)">上移</button><button :disabled="r.layerIndex === r.config.layers.length - 1" :aria-label="r.edge.name + ' 第' + (r.layerIndex + 1) + '层下移'" @click.stop="moveLayer(r.config, r.layerIndex, 1)">下移</button><button :aria-label="r.edge.name + ' 第' + (r.layerIndex + 1) + '层删除'" @click.stop="r.config.layers.splice(r.layerIndex, 1)">删除</button></td>
              </template>
              <template v-else><td colspan="4" class="empty-layer">暂无材料层，请点击该管道的“添加材料层”。</td><td>—</td></template>
            </tr></tbody>
          </table>
          <p v-if="!graph.edges.length" class="empty">当前拓扑暂无管段。</p>
          <p v-else class="table-note">每个管道的材料层按从内到外排列，包含钢管壁，最多 12 层。管内径取自已保存拓扑；材料层可直接编辑，修改后点击“计算”重算全部管道。</p>
          <PipelineTemperatureReadOnlyTable title="材料层几何（保存拓扑内径与本页层厚）" :columns="layerReferenceColumns" :rows="displayedLayers" />
          <PipelineTemperatureReadOnlyTable title="管壁导热固定参数" :columns="formulaColumns" :rows="formulaRows" />
          <p class="table-note">D外,i = D内,i + 2δi；平壁热阻为 Σ(δi/λi)，内表面积基准的圆筒热阻为 Σ[D内·ln(D外,i/D内,i)/(2λi)]。</p>
        </div>

        <div v-else-if="bottom === 'input'" class="table-scroll">
          <table class="data-table input-table thermal-parameter-table" :class="{ 'outer-parameter-table': isOuter }" :aria-label="page.label + '输入参数'">
            <thead><tr><th class="index-column">序号</th><th class="pipe-name-column">管道名称</th><th class="diameter-column">管内径<small>mm</small></th><th v-for="col in parameterColumns" :key="col.key" :class="col.key + '-column'">{{ col.label }}<small v-if="col.unit">{{ col.unit }}{{ col.key === 'gasConductivityWmK' ? ' · 必填' : '' }}</small></th></tr></thead>
            <tbody><tr v-for="({edge: e, config: c}, i) in inputRows" :key="e.id" :class="{ selected: selected === e.id }" @click="choose(e.id)">
              <td class="index-column">{{ i + 1 }}</td><td><button class="cell-button" @click="choose(e.id)">{{ e.name }}</button></td><td class="numeric">{{ e.parameters.diameterMm }}</td>
              <td v-for="col in parameterColumns" :key="col.key" class="editable-cell">
                <select v-if="col.key === 'externalMethod'" v-model="c.externalMethod" :aria-label="e.name + ' ' + col.label"><option v-for="method in externalHeatMethods" :key="method.value" :value="method.value">{{ method.label }}</option></select>
                <input v-else type="number" step="any" :value="c[col.key]" :disabled="parameterDisabled(c, col.key)" :aria-label="e.name + ' ' + col.label + '（' + col.unit + '）'" @input="editParameter(c, col.key, $event)" />
              </td>
            </tr></tbody>
          </table>
          <p v-if="!graph.edges.length" class="empty">当前拓扑暂无管段。</p>
          <template v-else-if="isOuter">
            <PipelineTemperatureReadOnlyTable title="材料层厚度及逐层外径（来自管道导热系数页）" :columns="layerReferenceColumns" :rows="displayedLayers" />
            <PipelineTemperatureReadOnlyTable title="埋地管道换热几何（由层厚和埋深计算）" :columns="outerGeometryColumns" :rows="outerGeometryRows" />
            <p class="table-note">第一类边界不使用地表综合放热系数；第二类边界计入地表热阻，当前支持 h/D外 ≤ 2。各管道参数可直接编辑，点击“计算”计算全部管道。</p>
            <PipelineTemperatureReadOnlyTable title="外部换热固定参数" :columns="formulaColumns" :rows="formulaRows" />
            <p class="table-note">第一类：α外 = 2λ土 / [D外·acosh(2h/D外)]。第二类：y₀ = √(h²－D外²/4)，b = α地表·y₀/λ土，α外 = 2λ土b / {D外·[1＋b·acosh(2h/D外)]}。</p>
          </template>
          <template v-else>
            <PipelineTemperatureReadOnlyTable title="总传热实际采用的气体参数" :columns="overallFluidColumns" :rows="overallFluidRows" />
            <p class="table-note">密度、Cp 及 Standing（资料原式）黏度由当前井 PVT 模型按温压计算。气体导热系数 λ 必填且大于 0，与内壁页共享；工况体积流量沿用内壁页输入，温压联算时自动换算。环境温度仅用于温压联算。</p>
            <p v-if="propertyBusy" class="table-note">正在读取 PVT 物性…</p>
            <template v-for="({edge:e}) in inputRows" :key="e.id"><p v-if="propertyIssue(e.id)" class="table-note error-text">{{ e.name }}：{{ propertyIssue(e.id) }}</p></template>
            <PipelineTemperatureReadOnlyTable title="PVT 物性来源" :columns="propertyReferenceColumns" :rows="propertyReferenceRows" />
            <PipelineTemperatureReadOnlyTable title="Standing（资料原式）黏度计算参数" :columns="propertyReferenceColumns" :rows="viscosityReferenceRows" />
            <PipelineTemperatureReadOnlyTable title="材料层参数及几何（来自管道导热系数页）" :columns="layerReferenceColumns" :rows="displayedLayers" />
            <PipelineTemperatureReadOnlyTable title="敷设参数（来自外部放热系数页）" :columns="externalReferenceColumns" :rows="externalReferenceRows" />
            <PipelineTemperatureReadOnlyTable title="埋地管道换热几何（由层厚和埋深计算）" :columns="outerGeometryColumns" :rows="outerGeometryRows" />
            <PipelineTemperatureReadOnlyTable title="总传热关联式固定参数" :columns="formulaColumns" :rows="formulaRows" />
            <p class="table-note">K = 1/(1/α内 + Σδi/λi + 1/α外)。管流使用内表面积基准 U = 1/(1/α内 + R壁,内 + D内/(D外·α外))；本页采用保存拓扑的管内径。</p>
            <template v-if="calculationMode === 'coupled'">
              <PipelineTemperatureReadOnlyTable title="联算管段几何（来自保存拓扑）" :columns="coupledGeometryColumns" :rows="coupledGeometryRows" />
              <PipelineTemperatureReadOnlyTable v-if="equipmentReferenceRows.length" title="联算关键设备（来自保存拓扑）" :columns="equipmentReferenceColumns" :rows="equipmentReferenceRows" />
              <PipelineTemperatureReadOnlyTable v-if="boundaryReferenceRows.length" title="联算节点边界（来自边界条件）" :columns="boundaryReferenceColumns" :rows="boundaryReferenceRows" />
              <PipelineTemperatureReadOnlyTable title="联算固定设置与标况参数（来自管流设置）" :columns="flowReferenceColumns" :rows="flowReferenceRows" />
              <PipelineTemperatureReadOnlyTable title="本次系数采用的迭代温压" :columns="[{key:'pipeName',label:'管道名称'},{key:'propertyPressureMpa',label:'迭代平均压力',unit:'MPa绝压'},{key:'propertyTemperatureC',label:'迭代平均温度',unit:'℃'}]" :rows="overallFluidRows" />
              <p class="table-note">联算自动换算工况体积流量，并按上一轮沿程点更新系数所需平均温压；表中平均温压和气体参数来自实际计算响应。</p>
            </template>
          </template>
        </div>

        <div v-else-if="bottom === 'table'" class="table-scroll">
          <table class="data-table result-table">
            <thead><tr><th class="index-column">序号</th><th>管段名称</th><th v-for="col in columns" :key="col.key">{{ col.label }}<small v-if="col.unit !== '—'">{{ col.unit }}</small></th><th>计算状态</th></tr></thead>
            <tbody><tr v-for="(e, i) in graph.edges" :key="e.id" :class="{ selected: selected === e.id }" @click="choose(e.id)">
              <td class="index-column">{{ i + 1 }}</td><td><button class="cell-button" @click.stop="choose(e.id)">{{ e.name }}</button></td>
              <td v-for="col in columns" :key="col.key" class="numeric">{{ metricValue(activeResults[e.id]?.result, col.key) }}</td>
              <td :class="{ 'error-text': rows.find(r => r.edgeId === e.id)?.error }">{{ rowState(e.id) }}</td>
            </tr></tbody>
          </table>
          <p v-if="!graph.edges.length" class="empty">当前拓扑暂无管段。</p>
          <div v-if="isOverall && calculationMode === 'coupled' && solution" class="coupled-chart"><PipelineChart :series="chartSeries" unit="温度（℃）" title="管线温度分布" /></div>
        </div>

        <nav class="result-tabs" aria-label="温度计算结果">
          <button v-for="t in resultTabs" :key="t[0]" :class="{ active: bottom === t[0] }" :aria-pressed="bottom === t[0]" @click="bottom = t[0]">{{ t[1] }}</button>
        </nav>
      </main>
    </div>
    <PipelineTemperatureImportDialog v-model="importVisible" :kind="kind" :settings="settings" :graph="importGraph" :well-name="context.wellName" @imported="acceptImport" />
  </section>
</template>

<style scoped>
.temperature-editor {
  --thermal-accent: #f2c811;
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100%;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  background: #fff;
  color: #252525;
  font-family: "Microsoft YaHei", "Segoe UI", sans-serif;
  font-size: 13px;
}
.busy { opacity: .7; }
button, input, select, textarea { font: inherit; color: inherit; box-sizing: border-box; }
button { min-height: 28px; padding: 0 12px; border: 1px solid #777; border-radius: 4px; background: #fff; cursor: pointer; }
button:hover:not(:disabled) { border-color: #333; background: #f5f5f5; }
button:disabled { border-color: #ccc; color: #999; cursor: not-allowed; }
button:focus-visible { outline: 2px solid #b99500; outline-offset: -2px; }
input, select, textarea { width: 100%; min-width: 0; border: 1px solid #aaa; border-radius: 3px; background: #fff; padding: 0 8px; }
input, select { height: 28px; }
textarea { padding: 6px 8px; resize: vertical; line-height: 1.6; }
input:focus, select:focus, textarea:focus, :deep(.pipeline-field input:focus) { border-color: #b99500; box-shadow: 0 0 0 2px rgba(242, 200, 17, .16); outline: none; }
input:read-only, select:disabled { background: #f5f5f5; color: #666; }
.workspace-toolbar { display: flex; align-items: center; flex-shrink: 0; min-height: 35px; border-bottom: 1px solid #ddd; background: #fafafa; gap: 12px; }
.workspace-title { display: flex; align-items: center; align-self: stretch; padding: 0 14px; background: #f4d000; color: #202020; font-size: 14px; font-weight: 600; }
.well-name { font-size: 12px; }
.save-state { color: #777; font-size: 12px; }
.thermal-workspace { display: flex; flex: 1; min-height: 0; }
.parameter-panel { display: flex; flex-direction: column; flex: 0 0 300px; width: 300px; min-height: 0; border-right: 1px solid #ddd; background: #fff; }
.parameter-panel.manual-controls { flex-basis: 280px; width: 280px; min-width: 280px; overflow: hidden; transition: width .16s ease, min-width .16s ease, flex-basis .16s ease; }
.parameter-panel.manual-controls.collapsed { width: 34px; min-width: 34px; flex-basis: 34px; height: 100%; border: 1px solid #d4d7db; border-right: 0; box-sizing: border-box; }
.panel-heading { display: flex; align-items: center; justify-content: space-between; flex: 0 0 34px; padding: 0 12px; border-bottom: 1px solid #ddd; background: #f2f2f2; }
.parameter-toggle { width: 20px; height: 20px; min-height: 20px; padding: 0; border: 0; border-radius: 2px; background: transparent; display: flex; align-items: center; justify-content: center; }
.parameter-toggle:hover { background: #fff8d8; }
.parameter-collapsed-tab { width: 100%; height: 76px; padding: 8px 0 0; border: 0; border-bottom: 1px solid #e2e6ea; border-radius: 0; writing-mode: vertical-rl; text-orientation: upright; line-height: 1.05; display: flex; align-items: center; justify-content: flex-start; }
.parameter-collapsed-tab:hover { background: #fff8d8; box-shadow: inset -2px 0 0 #f4d000; }
.manual-parameter-form { flex: 1; min-height: 0; overflow-y: auto; padding: 4px 12px 14px; }
.manual-parameter-form .field { margin-bottom: 9px; line-height: 18px; }
.local-import-button { width: 100%; height: 26px; min-height: 26px; padding: 0 8px; border: 1px solid #aaa; border-radius: 3px; text-align: left; }
.imported-data-name { display: block; margin-top: 4px; color: #777; font-size: 11px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.field { display: block; margin-bottom: 11px; font-size: 12px; }
.field > span { display: block; margin-bottom: 4px; }
.hint { margin: 10px 0; color: #666; font-size: 12px; line-height: 1.8; }
.parameter-actions { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; padding: 10px 12px 12px; border-top: 1px solid #e5e5e5; flex-shrink: 0; }
.parameter-actions button { height: 30px; padding: 0 6px; font-size: 12px; }
.parameter-actions .calculate-button { background: #202020; border-color: #202020; color: #fff; font-weight: 600; }
.parameter-actions .calculate-button:hover:not(:disabled) { background: #333; }
.parameter-actions .calculate-button:disabled { opacity: .5; }
.parameter-actions.manual-actions { display: flex; align-items: center; gap: 8px; padding: 0; border: 0; margin-top: 12px; }
.manual-actions button { min-width: 86px; height: 32px; padding: 0 22px; border-radius: 5px; font-size: 13px; font-weight: 600; border-color: #252525; }
.calculation-method { margin: 12px 0; padding: 0; border: 0; display: flex; flex-wrap: wrap; gap: 10px; }
.calculation-method legend { margin-bottom: 8px; font-size: 12px; }
.calculation-method label { display: inline-flex; align-items: center; gap: 5px; font-size: 12px; cursor: pointer; }
.calculation-method input { width: 14px; height: 14px; margin: 0; accent-color: #333; }
.thermal-parameter-table { min-width: 780px; }
.thermal-parameter-table .diameter-column { width: 95px; }
.thermal-parameter-table.outer-parameter-table { min-width: 1000px; }
.thermal-parameter-table .externalMethod-column { width: 280px; }
.thermal-parameter-table select { height: 36px; border: 1px solid transparent; border-radius: 0; background: transparent; font-size: 12px; }
.thermal-parameter-table input:disabled { background: #f4f4f4; color: #999; cursor: not-allowed; }
.coupled-chart { display: flex; height: 350px; min-height: 350px; border-top: 1px solid #ddd; }
.result-panel { display: flex; flex-direction: column; flex: 1; min-width: 0; min-height: 0; background: #fff; }
.result-toolbar { display: flex; align-items: center; flex-shrink: 0; min-height: 38px; padding: 0 12px; gap: 12px; border-bottom: 1px solid #ddd; font-size: 12px; }
.context-label { color: #777; }
.table-scroll { flex: 1; min-height: 0; overflow: auto; }
.data-table { width: 100%; border-collapse: separate; border-spacing: 0; font-size: 13px; }
.data-table th, .data-table td { padding: 7px 10px; height: 36px; box-sizing: border-box; border-right: 1px solid #d4d7db; border-bottom: 1px solid #d4d7db; text-align: center; line-height: 1.5; }
.data-table th { position: sticky; top: 0; z-index: 1; background: #f4f4f4; color: #333; font-weight: 400; white-space: nowrap; }
.data-table th small { display: block; font-size: 12px; font-weight: 400; }
.data-table .index-column { width: 54px; background: #f4f4f4; }
.data-table .numeric { text-align: right; font-variant-numeric: tabular-nums; }
.result-table { min-width: 720px; }
.input-table { min-width: 1000px; table-layout: fixed; }
.inner-input-table { min-width: 1450px; }
.input-table .property-cell { background: #f4f4f4; color: #555; }
.input-table .pipe-name-column { width: 150px; }
.input-table .editable-cell { padding: 0; }
.input-table input { height: 36px; border: 1px solid transparent; border-radius: 0; background: transparent; text-align: right; font-variant-numeric: tabular-nums; }
.input-table input:focus { border-color: #b99500; box-shadow: inset 0 0 0 1px #b99500; }
.input-table tr.selected > td:not(.index-column) { background: #fff8d8; }
.result-table tbody tr { cursor: pointer; }
.result-table tbody tr.selected > td:not(.index-column) { background: #fff8d8; }
.result-table tbody tr:hover > td { background: #f5f5f5; }
.cell-button { padding: 0; min-height: 22px; border: 0; border-radius: 0; background: transparent; }
.wall-input-table { min-width: 864px; table-layout: fixed; }
.wall-input-table .pipeSequence-column { width: 72px; }
.wall-input-table .pipeName-column { width: 140px; }
.wall-input-table .diameterMm-column { width: 100px; }
.wall-input-table .layerSequence-column { width: 64px; }
.wall-input-table .name-column { width: 118px; }
.wall-input-table .thicknessMm-column { width: 95px; }
.wall-input-table .conductivityWmK-column { width: 150px; }
.wall-input-table .operations-column { width: 125px; }
.wall-input-table .editable-cell { padding: 0; }
.wall-input-table input { height: 36px; border: 1px solid transparent; border-radius: 0; padding: 0 8px; background: transparent; }
.wall-input-table input[type="number"] { text-align: right; font-variant-numeric: tabular-nums; }
.wall-input-table input:focus { border-color: #b99500; box-shadow: inset 0 0 0 1px #b99500; }
.wall-input-table tr.selected > td:not(.index-column) { background: #fff8d8; }
.wall-input-table .wall-pipe-name { vertical-align: top; padding-top: 8px; }
.wall-input-table .geometry-value { vertical-align: top; padding-top: 8px; }
.wall-input-table th.operations-column, .wall-input-table td.row-actions { position: sticky; right: 0; z-index: 2; background: #fff; border-left: 1px solid #d4d7db; }
.wall-input-table th.operations-column { z-index: 3; background: #f4f4f4; }
.add-layer-button { display: block; margin: 7px auto 3px; min-height: 25px; padding: 0 8px; font-size: 12px; border-color: #aaa; }
.empty-layer { color: #888; font-size: 12px; }
.row-actions { white-space: nowrap; }
.row-actions button { padding: 0 5px; border: 0; font-size: 12px; }
.table-note { margin: 12px; color: #666; font-size: 12px; }
.empty { display: flex; align-items: center; justify-content: center; flex: 1; margin: 0; padding: 24px; color: #777; font-size: 13px; line-height: 1.8; }
.result-tabs { display: flex; justify-content: center; flex: 0 0 36px; border-top: 1px solid #ddd; }
.result-tabs button { min-width: 120px; border: 0; border-right: 1px solid #ddd; border-radius: 0; font-size: 13px; }
.result-tabs button.active { background: var(--thermal-accent); color: #111; font-weight: 600; }
.result-tabs button:hover:not(.active) { background: #fff8d8; }
.error-strip, .notice { padding: 7px 12px; font-size: 12px; line-height: 1.6; border-bottom: 1px solid #ddd; }
.error-strip { background: #fff1ef; color: #b4463a; }
.notice { background: #fff8d8; color: #735c17; }
.error-text { color: #b4463a; }
.input-legend{display:flex;align-items:center;gap:5px;margin-left:auto;color:#777;font-size:11px}.input-legend i{display:inline-block;width:12px;height:12px;border:1px solid #ddd}.editable-key{background:#fffbe8}.readonly-key{background:#f4f4f4}
.data-table tbody td,.data-table tbody tr.selected>td:not(.editable-cell){background:#fafafa}
.input-table input:not(:disabled),.wall-input-table input,.thermal-parameter-table select{background:#fffbe8}
.thermal-parameter-table input:disabled{background:#f4f4f4;color:#777}
.reference-heading{display:flex;align-items:center;gap:12px;margin:16px 0 0;padding:9px 12px;background:#f2f2f2;font-size:13px;font-weight:500;border-top:1px solid #ddd;border-bottom:1px solid #ddd}.reference-heading span{font-size:12px;color:#777;font-weight:400}
@media (max-width: 1150px) { .parameter-panel { flex-basis: 280px; width: 280px; } .result-tabs button { min-width: 95px; } }
</style>
