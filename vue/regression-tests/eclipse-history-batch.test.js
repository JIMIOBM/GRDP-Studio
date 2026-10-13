import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
import { parse,compileScript } from '@vue/compiler-sfc'
import * as Vue from 'vue'
import * as history from '../src/views/SoftwareIntegration/eclipseHistory.js'
import * as batch from '../src/views/SoftwareIntegration/eclipseHistoryBatch.js'
import { historyBatchBrowserFixture } from '../tests/fixtures/eclipseHistoryBatch.js'
const fixture=()=>historyBatchBrowserFixture(),key=(f,keyword)=>history.historyIdentity(f.dataset.observations.find(o=>o.keyword===keyword))
const comparison=f=>batch.historyBatchComparison({...f,metricKey:key(f,'WOPR')})

test('same observation source ranks actual native RMSE per metric and preserves all missing points',()=>{
 const f=fixture(),result=comparison(f)
 assert.equal(result.canRank,true);assert.deepEqual(result.rows.map(r=>r.runId),[9080,9081,9082])
 assert.deepEqual(result.rows.map(r=>r.metric.matchedCount),[2,2,2]);assert.deepEqual(result.rows.map(r=>r.metric.observedCount),[3,3,3])
 assert.equal(result.rows[0].metric.rmse,Math.sqrt(6.5));assert.equal(result.rows[1].metric.rmse,2.8830032337225497)
 const water=batch.historyBatchComparison({...f,metricKey:key(f,'WWPR')})
 assert.equal(water.canRank,true);assert.deepEqual(water.rows.map(r=>r.runId),[9081,9080,9082]);assert.equal(water.rows[0].metric.rmse,.6931211352348328)
 assert.equal(f.evaluations[9080].comparison.points[2].simulated,null)
})
test('actual failed commercial run has no fit and tie ranks use only complete genuine results',()=>{
 const f=historyBatchBrowserFixture(true),result=batch.historyBatchComparison({...f,metricKey:key(f,'WWPR')})
 assert.equal(result.canRank,true);assert.deepEqual(result.rows.filter(r=>r.metric).map(r=>r.rank),[1,1])
 const failed=result.rows.find(r=>r.runId===9081);assert.equal(failed.status,'FAILED');assert.equal(failed.metric,null);assert.equal(failed.rank,null);assert.equal(failed.reason,f.runs[1].error.message)
})
test('cross-baseline records, made-up metric values, transient API failures and unfinished batches cannot rank',()=>{
 let f=fixture();f.batch.baselineRunId++;assert.equal(comparison(f).rows.length,0)
 f=fixture();f.evaluations[9081].comparison.metrics.find(m=>m.keyword==='WOPR').rmse=0
 assert.equal(comparison(f).canRank,false);assert.match(comparison(f).rows.find(r=>r.runId===9081).reason,/逐点/)
 f=fixture();delete f.evaluations[9082];assert.equal(comparison(f).canRank,false)
 f=fixture();f.batch.status='RUNNING';assert.equal(comparison(f).canRank,false);assert.match(comparison(f).message,/尚未结束/)
})
test('equal coverage counts at different actual clocks cannot be mistaken for the same matched set',()=>{
 const f=fixture()
 for(const [i,run] of f.runs.entries()){
  const removed=i===0?100:5,evaluation=f.evaluations[run.id],metric=evaluation.comparison.metrics.find(m=>m.keyword==='WOPR')
  run.result.summary.series.find(s=>s.keyword==='WOPR').points=run.result.summary.series.find(s=>s.keyword==='WOPR').points.filter(p=>p.timeDays!==removed)
  const point=evaluation.comparison.points.find(p=>p.keyword==='WOPR'&&p.timeDays===removed);point.simulated=null;point.difference=null;point.reason='没有同一原生报告时刻'
  const remaining=evaluation.comparison.points.find(p=>p.keyword==='WOPR'&&p.simulated!=null)
  Object.assign(metric,{matchedCount:1,missingCount:2,coverage:1/3,mae:Math.abs(remaining.difference),rmse:Math.abs(remaining.difference),bias:remaining.difference,mapeCount:1,mapeOverflowCount:0,mape:Math.abs(remaining.difference/remaining.observed)*100})
 }
 const result=comparison(f);assert.equal(result.canRank,false);assert.equal(result.rows.every(r=>r.metric.matchedCount===1),true);assert.match(result.message,/观察时刻不同/)
})
test('comparison CSV preserves source SHA and failed/absent numeric cells without formula injection',()=>{
 const f=historyBatchBrowserFixture(true),r=batch.historyBatchComparison({...f,metricKey:key(f,'WWPR')});r.rows[0].name='=unsafe'
 const csv=batch.historyBatchCsv(r.rows,f.dataset);assert.ok(csv.startsWith('\uFEFF'));assert.ok(csv.includes(f.dataset.metadata.sourceSha256));assert.ok(csv.includes("'=unsafe"));assert.ok(csv.includes('FAILED'));assert.ok(!csv.includes('NaN'));assert.ok(csv.includes('"","",""'))
})
const renderer=Vue.createRenderer({createElement:()=>({}),createText:()=>({}),createComment:()=>({}),insert(){},remove(){},setText(){},setElementText(){},patchProp(){},parentNode:()=>null,nextSibling:()=>null})
const flush=async()=>{for(let i=0;i<10;i++){await Promise.resolve();await Vue.nextTick()}}
const deferred=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return{promise,resolve,reject}}
function mount(api){
 const code=compileScript(parse(readFileSync(new URL('../src/views/SoftwareIntegration/EclipseHistoryBatchPanel.vue',import.meta.url),'utf8')).descriptor,{id:'history-batch',genDefaultAs:'component'}).content.replace(/^import (.+) from ['"](.+)['"]\r?$/gm,(_,bindings,module)=>`const ${bindings} = deps[${JSON.stringify(module)}]`)
 const component=new Function('deps',`${code};return component`)({vue:Vue,'./eclipseHistory.js':history,'./eclipseHistoryBatch.js':batch,'@/api/softwareIntegration':{softwareIntegrationApi:api}})
 const setup=component.setup;let state;component.setup=(p,c)=>{state=setup(p,c);return state};component.render=()=>null
 const props=Vue.reactive({dataset:fixture().dataset}),app=renderer.createApp({render:()=>Vue.h(component,props)});app.mount({});return{props,state,unmount:()=>app.unmount()}
}
test('production setup performs read-only comparisons for eligible runs and retains individual transport failures without false ranking',async()=>{
 const f=fixture(),called=[]
 const h=mount({async listEclipseBatches(){return{data:[f.summary]}},async getEclipseBatch(){return{data:f.batch}},async compareEclipseHistory(id,runId){called.push({id,runId});if(runId===9082)throw new Error('temporary read failure');return{data:f.evaluations[runId]}}})
 try{
  await flush();await h.state.compare();assert.equal(called.length,3);assert.equal(h.state.comparison.value.canRank,false);assert.equal(h.state.comparison.value.rows.length,3);assert.match(h.state.comparison.value.rows.find(r=>r.runId===9082).reason,/temporary/)
  h.state.metricKey.value=key(f,'WWPR');await flush();assert.equal(h.state.comparison.value.canRank,false)
 }finally{h.unmount()}
})
test('old batch results and errors cannot overwrite another observation record or an unmounted component',async()=>{
 const f=fixture(),pending=deferred(),h=mount({async listEclipseBatches(){return{data:[f.summary]}},getEclipseBatch(){return pending.promise}})
 try{
  await flush();const job=h.state.compare();h.props.dataset={...structuredClone(f.dataset),metadata:{...f.dataset.metadata,id:f.dataset.metadata.id+100}};await flush();pending.resolve({data:f.batch});await job;assert.equal(h.state.batch.value,null);assert.equal(h.state.error.value,'')
 }finally{h.unmount()}
 const other=deferred(),g=mount({async listEclipseBatches(){return{data:[f.summary]}},getEclipseBatch(){return other.promise}})
 await flush();const job=g.state.compare();g.unmount();other.reject(new Error('late failure'));await job;assert.equal(g.state.error.value,'')
})
