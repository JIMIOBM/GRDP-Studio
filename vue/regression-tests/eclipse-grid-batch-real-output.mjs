// Real production grid -> production batch setup -> 5173 -> commercial queue.
import assert from 'node:assert/strict'
import { readFileSync, readdirSync, writeFileSync, mkdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { compileScript, parse } from '@vue/compiler-sfc'
import * as Vue from 'vue'
import * as THREE from 'three'
const source=resolve(process.argv[2]),out=resolve(process.argv[3]);mkdirSync(out,{recursive:true})
const saved=name=>JSON.parse(readFileSync(resolve(source,name+'.json'),'utf8'))
assert.equal(saved('acceptance-summary').apiVerified,true)
const receipts=[],rangeRequests=[],sent=[],errors=[],stored=new Map()
Object.defineProperty(globalThis,'localStorage',{value:{getItem:key=>stored.get(key)??null,setItem:(key,value)=>stored.set(key,value),removeItem:key=>stored.delete(key)}})
async function request(path,body){
 const r=await fetch('http://127.0.0.1:5173/api/software-integration'+path,body?{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)}:{})
 const reply=await r.json();assert.equal(r.status,body?201:200,JSON.stringify(reply));assert.equal(reply.code,body?201:200);return reply
}
const baseline=(await request('/runs/'+saved('run-baseline').id)).data
assert.deepEqual(baseline.result,saved('run-baseline').result)
const deps={vue:Vue,three:THREE,'three/addons/controls/OrbitControls.js':{OrbitControls:class{}},'element-plus':{ElMessage:{warning:m=>errors.push(m),success:m=>receipts.push(m),error:m=>errors.push(m)}},'@/api/softwareIntegration':{softwareIntegrationApi:{
 async downloadArtifactRange(runId,id,offset,length){
  const r=await fetch(`http://127.0.0.1:5173/api/software-integration/runs/${runId}/artifacts/${id}/range?offset=${offset}&length=${length}`)
  assert.equal(r.status,206);const bytes=new Uint8Array(await r.arrayBuffer());assert.equal(bytes.length,length);rangeRequests.push({runId,id,offset,length});return bytes
 },
 listEclipseBatches:version=>request(`/model-versions/${version}/eclipse-batches`),
 getEclipseBatch:id=>request('/eclipse-batches/'+id),
 createEclipseBatch(version,p){sent.push(structuredClone(p));return request(`/model-versions/${version}/eclipse-batches`,p)}
}}}
for(const name of readdirSync(new URL('../src/views/SoftwareIntegration/',import.meta.url)).filter(n=>/^eclipse.*\.js$/.test(n))){
 const module=await import(new URL('../src/views/SoftwareIntegration/'+name,import.meta.url));deps['./'+name]=module;deps['./'+name.slice(0,-3)]=module
}
const renderer=Vue.createRenderer({createElement:()=>({}),createText:()=>({}),createComment:()=>({}),insert(){},remove(){},setText(){},setElementText(){},patchProp(){},parentNode:()=>null,nextSibling:()=>null})
function mount(name,props,events={}){
 const compiled=compileScript(parse(readFileSync(new URL(`../src/views/SoftwareIntegration/${name}.vue`,import.meta.url),'utf8')).descriptor,{id:name,genDefaultAs:'component'}).content
 const code=compiled.replace(/^import (.+) from ['"](.+)['"]\r?$/gm,(_,bindings,module)=>{
  if(module.endsWith('.vue'))return `const ${bindings} = {}`
  assert.ok(deps[module],module);return bindings.startsWith('* as ')?`const ${bindings.slice(5)} = deps[${JSON.stringify(module)}]`:`const ${bindings} = deps[${JSON.stringify(module)}]`
 })
 const component=new Function('deps','cancelAnimationFrame',`${code};return component`)(deps,()=>{}),setup=component.setup;let state
 component.setup=(p,c)=>{state=setup(p,c);return state};component.render=()=>null
 const app=renderer.createApp({render:()=>Vue.h(component,{...props,...events})});app.mount({});return{state,unmount:()=>app.unmount()}
}
async function wait(test,limit=12000){const end=Date.now()+limit;while(Date.now()<end){await new Promise(r=>setTimeout(r,30));await Vue.nextTick();if(test())return}throw new Error('Production component timed out')}
const panel=mount('EclipseBatchPanel',{run:baseline,inspection:{}})
const grid=mount('EclipseGrid3D',{run:baseline,runId:baseline.id,grid:baseline.result.grid,fields:baseline.result.fieldIndex.files.flatMap(file=>file.fields.map(f=>({file:file.name,byteOrder:file.byteOrder,...f}))),artifacts:baseline.artifacts,wellCompletions:[]},
 {onAddGridBatchCase:item=>panel.state.addGridCase(item),onCreateGridFieldRun:()=>{throw new Error('Batch draft started an independent run')}})
try{
 const s=grid.state,p=panel.state;await wait(()=>s.geometryReady.value&&!s.loading.value&&!s.fieldLoading.value)
 for(const label of ['permx','poro']){
  const expected=saved('run-'+label).parameters,field=s.gridFields.value.find(f=>f.keyword===expected.keyword&&f.file.endsWith('.INIT'))
  s.selectedFieldKey.value=s.fieldKey(field);await wait(()=>!s.fieldLoading.value&&s.fieldValues.value.length===225)
  s.regionBounds.value=Object.fromEntries(['i1','i2','j1','j2','k1','k2'].map(k=>[k,expected[k]]));s.regionMultiplier.value=expected.multiplier;s.regionBatchName.value=expected.keyword
  s.runRegionScenario(true);assert.equal(s.regionError.value,'');assert.deepEqual(p.cases.value.at(-1).parameters,expected)
 }
 assert.equal(p.choices.value.length,0);assert.equal(p.canBuild.value,true)
 p.title.value='实际页面区域两方案';await p.submit();assert.equal(p.error.value,'');assert.equal(sent.length,1);assert.equal(stored.size,0)
 const id=p.selected.value.id
 writeFileSync(resolve(out,'request.json'),JSON.stringify(sent[0],null,2),{flag:'wx'})
 await wait(()=>!['QUEUED','RUNNING'].includes(p.selected.value?.status),360000)
 assert.equal(p.selected.value.status,'SUCCEEDED');assert.equal(p.response.value.rows.length,144)
 for(const m of p.selected.value.members){const original=saved('run-'+m.name.toLowerCase());assert.deepEqual(m.run.result.summary.series,original.result.summary.series)}
 writeFileSync(resolve(out,'batch-completed.json'),JSON.stringify(p.selected.value,null,2),{flag:'wx'})
 await p.loadBatches();assert.equal(p.selected.value.id,id);assert.equal(p.response.value.rows.length,144)
 assert.deepEqual(errors,[])
 const proof={automatedComponentVerified:true,actualApiTransport:true,manualUiVerified:false,batchId:id,runIds:p.selected.value.members.map(m=>m.run.id),decodedRangeRequests:rangeRequests.length,sourceParametersExact:true,caseCount:2,summaryRows:144,sourceSummaryDifference:0}
 writeFileSync(resolve(out,'automated-grid-batch-ui.json'),JSON.stringify(proof,null,2),{flag:'wx'});console.log(JSON.stringify(proof))
}finally{grid.unmount();panel.unmount()}
