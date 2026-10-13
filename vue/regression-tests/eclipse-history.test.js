import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { compileScript, parse } from '@vue/compiler-sfc'
import * as Vue from 'vue'
import * as history from '../src/views/SoftwareIntegration/eclipseHistory.js'
const root=new URL('../tests/fixtures/eclipseHistory/',import.meta.url),load=name=>JSON.parse(readFileSync(new URL(name+'.json',root),'utf8'))
const baseline=load('baseline'),dataset=load('dataset'),evaluation=load('comparison-baseline'),water=load('water')

test('history request binds full baseline and preserves original BOM/CRLF bytes',()=>{
 const request=history.buildHistoryRequest({run:baseline,name:' 样例 ',sourceFile:dataset.metadata.sourceFile,csv:dataset.csv})
 assert.equal(request.baselineRunId,baseline.id);assert.equal(request.name,'样例');assert.equal(request.csv,dataset.csv)
 assert.equal(createHash('sha256').update(request.csv).digest('hex'),dataset.metadata.sourceSha256)
 for(const run of [{...baseline,resultExpired:true},{...baseline,parameters:{}},{...baseline,status:'FAILED'}])assert.throws(()=>history.buildHistoryRequest({run,name:'样例',sourceFile:'a.csv',csv:dataset.csv}))
 assert.throws(()=>history.buildHistoryRequest({run:baseline,name:'样例',sourceFile:'../a.csv',csv:dataset.csv}))
 assert.throws(()=>history.buildHistoryRequest({run:baseline,name:'样例',sourceFile:'a.csv',csv:'x'.repeat(1048577)}))
})
test('independent native point binding retains preset arithmetic and missing report times',()=>{
 assert.ok(history.validHistoryDataset(dataset,baseline.modelVersionId));assert.ok(history.validHistoryEvaluation(evaluation,dataset,baseline))
 const oil=evaluation.comparison.metrics.find(m=>m.keyword==='WOPR'),pressure=evaluation.comparison.metrics.find(m=>m.keyword==='WBHP'),zero=evaluation.comparison.metrics.find(m=>m.keyword==='WWPR')
 assert.equal(oil.coverage,2/3);assert.equal(oil.mae,2.5);assert.equal(oil.rmse,Math.sqrt(6.5));assert.equal(oil.bias,-.5)
 assert.equal(pressure.mae,15);assert.equal(pressure.rmse,Math.sqrt(250));assert.equal(pressure.bias,5);assert.equal(zero.mape,null);assert.equal(zero.mapeCount,0)
 const gap=evaluation.comparison.points.find(p=>p.timeDays===102.5);assert.equal(gap.simulated,null);assert.equal(gap.difference,null)
 assert.ok(history.validHistoryEvaluation(load('comparison-water'),dataset,water))
})
test('another run/version, modified native values, wrong units and nonfinite metrics cannot enter the view',()=>{
 for(const change of [e=>e.runId++,e=>e.dataset.modelVersionId++,e=>e.dataset.sourceSha256='0'.repeat(64),e=>e.comparison.points[0].simulated++,e=>e.comparison.points[0].unit='SM3/DAY',e=>e.comparison.metrics[0].rmse=Infinity,e=>e.comparison.metrics[0].matchedCount=3,e=>e.comparison.points[2].simulated=0]){
  const e=structuredClone(evaluation);change(e);assert.equal(history.validHistoryEvaluation(e,dataset,baseline),false)
 }
 assert.equal(history.validHistoryEvaluation(evaluation,dataset,{...baseline,status:'FAILED',result:null}),false)
})
test('CSV export keeps missing numeric values empty and protects text cells',()=>{
 const e=structuredClone(evaluation);e.comparison.points[0].well='=unsafe';const csv=history.historyCsvExport(e)
 assert.ok(csv.startsWith('\uFEFF'));assert.ok(csv.includes("'=unsafe"));assert.ok(csv.includes('"102.5","800","",""'));assert.ok(!csv.includes('NaN'))
})

const renderer=Vue.createRenderer({createElement:()=>({}),createText:()=>({}),createComment:()=>({}),insert(){},remove(){},setText(){},setElementText(){},patchProp(){},parentNode:()=>null,nextSibling:()=>null})
const flush=async()=>{for(let i=0;i<12;i++){await Promise.resolve();await Vue.nextTick();await new Promise(setImmediate)}}
const deferred=()=>{let resolve,reject;const promise=new Promise((yes,no)=>{resolve=yes;reject=no});return{promise,resolve,reject}}
function mount(api){
 const {descriptor}=parse(readFileSync(new URL('../src/views/SoftwareIntegration/EclipseHistoryPanel.vue',import.meta.url),'utf8'))
 const code=compileScript(descriptor,{id:'history',genDefaultAs:'component'}).content.replace(/^import (.+) from ['"](.+)['"]\r?$/gm,(_,bindings,module)=>`const ${bindings.startsWith('* as ')?bindings.slice(5):bindings} = deps[${JSON.stringify(module)}]`)
 const component=new Function('deps',`${code}; return component`)({vue:Vue,echarts:{},'./eclipseHistory.js':history,'@/api/softwareIntegration':{softwareIntegrationApi:api}})
 const setup=component.setup;let state;component.setup=(props,context)=>{state=setup(props,context);return state};component.render=()=>null
 const props=Vue.reactive({run:structuredClone(baseline)});const app=renderer.createApp({render:()=>Vue.h(component,props)});app.mount({})
 return{state,props,unmount:()=>app.unmount()}
}
test('production upload preserves UTF-8 BOM and original bytes through actual browser decoding',async()=>{
 let sent=null
 const h=mount({async listEclipseHistory(){return{data:[]}},async createEclipseHistory(version,body){assert.equal(version,baseline.modelVersionId);sent=body;return{data:dataset}},async getEclipseHistory(){return{data:dataset}},async compareEclipseHistory(){return{data:evaluation}}})
 try{
  await flush();const bytes=new TextEncoder().encode(dataset.csv);h.state.selectedFile.value={name:dataset.metadata.sourceFile,size:bytes.length,async arrayBuffer(){return bytes.buffer}};h.state.name.value='验算样例'
  await h.state.upload();await flush();await h.state.loadDataset(dataset.metadata.id)
  assert.equal(sent.csv,dataset.csv);assert.equal(createHash('sha256').update(sent.csv).digest('hex'),dataset.metadata.sourceSha256)
  assert.equal(h.state.evaluation.value.runId,baseline.id);assert.equal(h.state.visiblePoints.value.length,3)
 }finally{h.unmount()}
})
test('invalid UTF-8 is rejected before upload and leaves no dataset',async()=>{
 let sent=0;const h=mount({async listEclipseHistory(){return{data:[]}},async createEclipseHistory(){sent++;return{data:dataset}}})
 try{
  await flush();const bytes=new Uint8Array([0xff,0xfe,0x00]);h.state.selectedFile.value={name:'a.csv',size:bytes.length,async arrayBuffer(){return bytes.buffer}};h.state.name.value='观察'
  await h.state.upload();assert.equal(sent,0);assert.equal(h.state.dataset.value,null);assert.match(h.state.error.value,/UTF-8/)
 }finally{h.unmount()}
})
test('same run becoming unavailable clears previously loaded fit values',async()=>{
 const h=mount({async listEclipseHistory(){return{data:[]}},async getEclipseHistory(){return{data:dataset}},async compareEclipseHistory(){return{data:evaluation}}})
 try{
  await flush();h.state.datasetId.value=dataset.metadata.id;await flush();await h.state.loadDataset(dataset.metadata.id);assert.equal(h.state.evaluation.value.runId,baseline.id)
  h.props.run={...baseline,resultContract:'VALID_PARTIAL',result:null};await flush();assert.equal(h.state.evaluation.value,null);assert.equal(h.state.metrics.value.length,0)
 }finally{h.unmount()}
})
test('changing models during file reading sends no request to the new model',async()=>{
 const read=deferred();let sent=0;const h=mount({async listEclipseHistory(){return{data:[]}},async createEclipseHistory(){sent++;return{data:dataset}}})
 try{
  await flush();h.state.selectedFile.value={name:'a.csv',size:100,arrayBuffer:()=>read.promise};h.state.name.value='验算';const pending=h.state.upload()
  h.props.run={...baseline,modelVersionId:baseline.modelVersionId+1};await flush();read.resolve(new TextEncoder().encode(dataset.csv).buffer);await pending
  assert.equal(sent,0);assert.equal(h.state.busy.value,false);assert.equal(h.state.dataset.value,null)
 }finally{h.unmount()}
})
test('late baseline comparison cannot replace the newly selected actual scenario',async()=>{
 const calls=[];const h=mount({async listEclipseHistory(){return{data:[]}},async getEclipseHistory(){return{data:dataset}},compareEclipseHistory(id,runId){const pending=deferred();calls.push({runId,...pending});return pending.promise}})
 try{
  await flush();h.state.datasetId.value=dataset.metadata.id
  for(let i=0;i<100&&!calls.length;i++)await flush();assert.equal(calls.length,1)
  h.props.run=structuredClone(water);await flush();assert.equal(calls.length,2);assert.equal(h.state.evaluation.value,null)
  calls[0].resolve({data:evaluation});await flush();assert.equal(h.state.evaluation.value,null)
  calls[1].resolve({data:load('comparison-water')});await flush();assert.equal(h.state.evaluation.value.runId,water.id)
 }finally{h.unmount()}
})
test('stale record errors after a model switch or unmount do not pollute the active view',async()=>{
 const old=deferred();let count=0;const h=mount({listEclipseHistory:()=>++count===1?old.promise:Promise.resolve({data:[]})})
 try{
  h.props.run={...baseline,modelVersionId:baseline.modelVersionId+1};await flush();old.reject(new Error('old model unavailable'));await flush();assert.equal(h.state.error.value,'');assert.deepEqual(h.state.records.value,[])
  const pending=deferred(),closed=mount({listEclipseHistory:()=>pending.promise});closed.unmount();pending.reject(new Error('closed view unavailable'));await flush();assert.equal(closed.state.error.value,'');assert.equal(closed.state.dataset.value,null)
 }finally{h.unmount()}
})
