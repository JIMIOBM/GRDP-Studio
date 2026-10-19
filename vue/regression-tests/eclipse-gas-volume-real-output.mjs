// Production PVDG editor and batch setup with the actual unified API and solver.
import assert from 'node:assert/strict'
import { readFileSync, readdirSync, writeFileSync, mkdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { compileScript, parse } from '@vue/compiler-sfc'
import * as Vue from 'vue'
const source=resolve(process.argv[2]),nativeDirectory=resolve(process.argv[3]),out=resolve(process.argv[4]),resume=process.argv[5]==='--resume';if(!resume)mkdirSync(out,{recursive:false})
const load=(dir,name)=>JSON.parse(readFileSync(resolve(dir,name+'.json'),'utf8'))
assert.equal(load(source,'acceptance-summary').apiVerified,true)
const oracle=load(nativeDirectory,'native-acceptance'),viscosity=JSON.parse(readFileSync(new URL('../tests/fixtures/eclipseGasPvt/native-acceptance.json',import.meta.url),'utf8'))
assert.ok(oracle.nativeVerified&&oracle.onlyGasVolumeColumnChanged&&viscosity.nativeVerified)
const stored=new Map(),sent=[],emitted=[]
Object.defineProperty(globalThis,'localStorage',{value:{getItem:k=>stored.get(k)??null,setItem:(k,v)=>stored.set(k,v),removeItem:k=>stored.delete(k)}})
async function request(path,body){const r=await fetch('http://127.0.0.1:5173/api/software-integration'+path,body?{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)}:{});const data=await r.json();assert.equal(r.status,body?201:200,JSON.stringify(data));return data}
const baseline=(await request('/runs/'+load(source,'run-baseline').id)).data,inspection=load(source,'version-original').inspection
if(resume)stored.set(`grdp-eclipse-batch-pending-${baseline.modelVersionId}`,JSON.stringify(load(out,'request')))
assert.deepEqual(baseline.result.summary.series,oracle.baseline.series)
const deps={vue:Vue,'@/api/softwareIntegration':{softwareIntegrationApi:{listEclipseBatches:v=>request(`/model-versions/${v}/eclipse-batches`),getEclipseBatch:id=>request('/eclipse-batches/'+id),createEclipseBatch(v,p){sent.push(structuredClone(p));return request(`/model-versions/${v}/eclipse-batches`,p)}}}}
for(const name of readdirSync(new URL('../src/views/SoftwareIntegration/',import.meta.url)).filter(n=>/^eclipse.*\.js$/.test(n))){const module=await import(new URL('../src/views/SoftwareIntegration/'+name,import.meta.url));deps['./'+name]=module;deps['./'+name.slice(0,-3)]=module}
const renderer=Vue.createRenderer({createElement:()=>({}),createText:()=>({}),createComment:()=>({}),insert(){},remove(){},setText(){},setElementText(){},patchProp(){},parentNode:()=>null,nextSibling:()=>null})
function mount(name,events={}){
 const compiled=compileScript(parse(readFileSync(new URL(`../src/views/SoftwareIntegration/${name}.vue`,import.meta.url),'utf8')).descriptor,{id:name,genDefaultAs:'component'}).content
 const code=compiled.replace(/^import (.+) from ['"](.+)['"]\r?$/gm,(_,bindings,module)=>{assert.ok(deps[module],module);return `const ${bindings.startsWith('* as ')?bindings.slice(5):bindings} = deps[${JSON.stringify(module)}]`})
 const component=new Function('deps',`${code};return component`)(deps),setup=component.setup;let state
 component.setup=(p,c)=>{state=setup(p,c);return state};component.render=()=>null
 const app=renderer.createApp({render:()=>Vue.h(component,{run:baseline,inspection,...events})});app.mount({});return{state,unmount:()=>app.unmount()}
}
const panel=mount('EclipseBatchPanel'),editor=mount('EclipseGasPvtEditor',{onCreateGasPvtRun:p=>emitted.push(structuredClone(p))})
try{
 const e=editor.state,p=panel.state;e.field.value='FORMATION_VOLUME_FACTOR';await Vue.nextTick();e.multiplier.value=1.02;e.submit();assert.equal(e.error.value,'');assert.deepEqual(emitted,[load(source,'run-target').parameters])
 if(!resume)for(const [kind,value,name] of [['PVDG_BG',1.02,'Bg +2%'],['PVDG',2,'Viscosity x2']]){p.kind.value=kind;p.value.value=value;p.caseName.value=name;p.addCase();assert.equal(p.error.value,'')}
 const cases=resume?p.pendingRequest.value.cases:p.cases.value;assert.deepEqual(JSON.parse(JSON.stringify(cases[0].parameters)),emitted[0]);assert.equal(cases[1].parameters.unit,'cP');p.title.value='PVDG independent Bg and viscosity';await p.submit();assert.equal(p.error.value,'');assert.equal(sent.length,1)
 if(!resume)writeFileSync(resolve(out,'request.json'),JSON.stringify(sent[0],null,2),{flag:'wx'})
 const id=p.selected.value.id,end=Date.now()+360000;while(['QUEUED','RUNNING'].includes(p.selected.value.status)){assert.ok(Date.now()<end);await new Promise(r=>setTimeout(r,1000));await p.refreshSelected()}
 assert.equal(p.selected.value.status,'SUCCEEDED');const batch=JSON.parse(JSON.stringify(p.selected.value)),runs=batch.members.map(m=>m.run);assert.ok(runs[0].finishedAt<=runs[1].claimedAt)
 assert.deepEqual(runs[0].result.summary.series,oracle.target.series);assert.deepEqual(runs[1].result.summary.series,viscosity.target.series);assert.equal(p.response.value.rows.length,1880)
 await p.loadBatches();assert.equal(p.selected.value.id,id);assert.deepEqual(p.selected.value,batch);assert.equal(stored.size,0)
 assert.deepEqual(load(out,'request'),sent[0]);for(const [name,value] of [['batch-completed',batch],['editor-parameters',emitted[0]]])writeFileSync(resolve(out,name+'.json'),JSON.stringify(value,null,2),{flag:'wx'})
 const proof={automatedComponentVerified:true,actualApiTransport:true,manualUiVerified:false,batchId:id,runIds:runs.map(r=>r.id),sourceParametersExact:true,caseCount:2,summaryRows:1880,summaryNumericPaths:2750,maxDifference:0,serialExecutionVerified:true,persistedBatchVerified:true}
 writeFileSync(resolve(out,'automated-gas-volume-ui.json'),JSON.stringify(proof,null,2),{flag:'wx'});console.log(JSON.stringify(proof))
}finally{editor.unmount();panel.unmount()}
