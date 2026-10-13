// Read-only real 5173 transport through the production batch-history setup.
import assert from 'node:assert/strict'
import { readFileSync,writeFileSync,mkdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { parse,compileScript } from '@vue/compiler-sfc'
import * as Vue from 'vue'
import * as history from '../src/views/SoftwareIntegration/eclipseHistory.js'
import * as batch from '../src/views/SoftwareIntegration/eclipseHistoryBatch.js'
const out=resolve(process.argv[2]);mkdirSync(out,{recursive:true})
const calls=[]
async function call(path){const r=await fetch('http://127.0.0.1:5173/api/software-integration'+path,{signal:AbortSignal.timeout(20000)});assert.equal(r.status,200);const reply=await r.json();assert.equal(reply.code,200);calls.push(path);return reply}
const api={listEclipseBatches:id=>call(`/model-versions/${id}/eclipse-batches`),getEclipseBatch:id=>call('/eclipse-batches/'+id),compareEclipseHistory:(id,runId)=>call(`/eclipse-history-datasets/${id}/comparison?runId=${runId}`)}
const before={};for(const id of [179,152])before[id]=(await call(`/model-versions/${id}/runs?limit=100`)).data
const code=compileScript(parse(readFileSync(new URL('../src/views/SoftwareIntegration/EclipseHistoryBatchPanel.vue',import.meta.url),'utf8')).descriptor,{id:'actual-history-batch',genDefaultAs:'component'}).content.replace(/^import (.+) from ['"](.+)['"]\r?$/gm,(_,bindings,module)=>`const ${bindings} = deps[${JSON.stringify(module)}]`)
const component=new Function('deps',`${code};return component`)({vue:Vue,'./eclipseHistory.js':history,'./eclipseHistoryBatch.js':batch,'@/api/softwareIntegration':{softwareIntegrationApi:api}})
const setup=component.setup;let state;component.setup=(p,c)=>{state=setup(p,c);return state};component.render=()=>null
const renderer=Vue.createRenderer({createElement:()=>({}),createText:()=>({}),createComment:()=>({}),insert(){},remove(){},setText(){},setElementText(){},patchProp(){},parentNode:()=>null,nextSibling:()=>null})
const props=Vue.reactive({dataset:(await call('/eclipse-history-datasets/1')).data}),app=renderer.createApp({render:()=>Vue.h(component,props)});app.mount({})
try{
 await state.loadBatches();state.batchId.value=1;await Vue.nextTick();await state.compare();assert.equal(state.error.value,'')
 let result=state.comparison.value;assert.equal(result.canRank,true);assert.deepEqual(result.rows.map(r=>r.runId),[570,594,595]);assert.deepEqual(result.rows.map(r=>r.rank),[1,2,3])
 assert.deepEqual(result.rows.map(r=>r.metric.matchedCount),[2,2,2]);assert.equal(result.rows[0].metric.rmse,Math.sqrt(6.5))
 writeFileSync(resolve(out,'comparison-oil.json'),JSON.stringify(result,null,2),{flag:'wx'})
 state.metricKey.value=history.historyIdentity(props.dataset.observations.find(o=>o.keyword==='WWPR'));await Vue.nextTick()
 result=state.comparison.value;assert.deepEqual(result.rows.map(r=>r.runId),[594,570,595]);assert.equal(result.rows[0].metric.rmse,.6931211352348328)
 writeFileSync(resolve(out,'comparison-water.json'),JSON.stringify(result,null,2),{flag:'wx'})
 writeFileSync(resolve(out,'comparison-water.csv'),batch.historyBatchCsv(result.rows,props.dataset),{flag:'wx'})
 props.dataset=(await call('/eclipse-history-datasets/2')).data;await Vue.nextTick();await state.loadBatches();state.batchId.value=2;await Vue.nextTick();await state.compare()
 assert.equal(state.error.value,'');result=state.comparison.value;assert.equal(result.canRank,true)
 const failed=result.rows.find(r=>r.runId===596);assert.equal(failed.status,'FAILED');assert.equal(failed.metric,null);assert.equal(failed.rank,null);assert.ok(!calls.some(path=>path.endsWith('runId=596')))
 assert.deepEqual(result.rows.filter(r=>r.metric).map(r=>r.rank),[1,1])
 writeFileSync(resolve(out,'comparison-failure-isolated.json'),JSON.stringify(result,null,2),{flag:'wx'})
 for(const id of [179,152])assert.deepEqual((await call(`/model-versions/${id}/runs?limit=100`)).data,before[id])
 const proof={automatedUiVerified:true,manualUiVerified:false,actualReadOnlyApi:true,solverRunCountUnchanged:true,batchIds:[1,2],datasetIds:[1,2],sameObservedPointSetsVerified:true,separateMetricRanksVerified:true,failedRunNotRequested:true,oilRmse:[Math.sqrt(6.5),2.8830032337225497,4.726396706132397]}
 writeFileSync(resolve(out,'acceptance-summary.json'),JSON.stringify(proof,null,2),{flag:'wx'});console.log(JSON.stringify(proof))
}finally{app.unmount()}
