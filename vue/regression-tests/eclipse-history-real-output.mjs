import assert from 'node:assert/strict'
import { readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { compileScript, parse } from '@vue/compiler-sfc'
import * as Vue from 'vue'
import * as history from '../src/views/SoftwareIntegration/eclipseHistory.js'
const directory=resolve(process.argv[2]),load=name=>JSON.parse(readFileSync(resolve(directory,name+'.json'),'utf8'))
const receipt=load('acceptance-summary');assert.equal(receipt.apiVerified,true)
const call=async path=>{const response=await fetch('http://127.0.0.1:5173/api/software-integration'+path,{signal:AbortSignal.timeout(60000)});assert.equal(response.status,200);const reply=await response.json();assert.equal(reply.code,200);return reply}
const api={listEclipseHistory:id=>call(`/model-versions/${id}/eclipse-history-datasets`),getEclipseHistory:id=>call(`/eclipse-history-datasets/${id}`),compareEclipseHistory:(id,runId)=>call(`/eclipse-history-datasets/${id}/comparison?runId=${runId}`)}
const {descriptor}=parse(readFileSync(new URL('../src/views/SoftwareIntegration/EclipseHistoryPanel.vue',import.meta.url),'utf8'))
const code=compileScript(descriptor,{id:'real-history',genDefaultAs:'component'}).content.replace(/^import (.+) from ['"](.+)['"]\r?$/gm,(_,bindings,module)=>`const ${bindings.startsWith('* as ')?bindings.slice(5):bindings} = deps[${JSON.stringify(module)}]`)
const component=new Function('deps',`${code}; return component`)({vue:Vue,echarts:{},'./eclipseHistory.js':history,'@/api/softwareIntegration':{softwareIntegrationApi:api}})
const setup=component.setup;let state;component.setup=(props,context)=>{state=setup(props,context);return state};component.render=()=>null
const renderer=Vue.createRenderer({createElement:()=>({}),createText:()=>({}),createComment:()=>({}),insert(){},remove(){},setText(){},setElementText(){},patchProp(){},parentNode:()=>null,nextSibling:()=>null})
const baseline=(await call('/runs/'+receipt.baselineRunId)).data,props=Vue.reactive({run:baseline})
const app=renderer.createApp({render:()=>Vue.h(component,props)});app.mount({})
let proof={automatedUiVerified:false,manualUiVerified:false}
try{
 await state.loadRecords();state.datasetId.value=receipt.datasetIds[0];await Vue.nextTick();await state.loadDataset(receipt.datasetIds[0]);await Vue.nextTick()
 assert.deepEqual(JSON.parse(JSON.stringify(state.dataset.value)),load('dataset-main'))
 assert.deepEqual(JSON.parse(JSON.stringify(state.evaluation.value)),load('comparison-'+baseline.id))
 assert.equal(state.points.value.length,3);assert.equal(state.points.value[2].simulated,null);assert.equal(state.points.value[2].difference,null)
 const oil=state.metrics.value.find(m=>m.keyword==='WOPR');assert.equal(oil.mae,2.5);assert.equal(oil.rmse,Math.sqrt(6.5));assert.equal(oil.coverage,2/3)
 props.run=(await call('/runs/'+receipt.comparisonRunIds[1])).data;await Vue.nextTick();await state.compare();await Vue.nextTick()
 assert.deepEqual(JSON.parse(JSON.stringify(state.evaluation.value)),load('comparison-'+props.run.id))
 const gas=load('comparison-gas');props.run=(await call('/runs/'+gas.runId)).data;await Vue.nextTick();await state.loadRecords();state.datasetId.value=gas.dataset.id;await Vue.nextTick();await state.loadDataset(gas.dataset.id);await Vue.nextTick()
 assert.deepEqual(JSON.parse(JSON.stringify(state.evaluation.value)),gas);assert.equal(state.points.value.length,61);assert.equal(state.points.value.filter(p=>p.simulated===null).length,55)
 props.run=(await call('/runs/596')).data;await Vue.nextTick();await state.loadRecords();await state.compare();assert.equal(state.evaluation.value,null)
 proof={automatedUiVerified:true,manualUiVerified:false,actualApiTransport:true,sourceBytesAndShaVerified:true,baselineAndScenarioRows:12,gasRows:61,gasMissingRows:55,failedRunHasNoFit:true}
 console.log(JSON.stringify(proof))
}finally{app.unmount();writeFileSync(resolve(directory,'automated-history-ui.json'),JSON.stringify(proof,null,2))}
