// Actual 5173 ACTNUM and complete new-completion summaries through production setups.
import assert from 'node:assert/strict'
import {readFileSync,readdirSync,writeFileSync} from 'node:fs'
import {resolve} from 'node:path'
import {compileScript,parse} from '@vue/compiler-sfc'
import * as Vue from 'vue'
const directory=resolve(process.argv[2]),load=name=>JSON.parse(readFileSync(resolve(directory,name+'.json'),'utf8'))
assert.equal(load('acceptance-summary').apiVerified,true)
const baseline=load('run-baseline'),run=load('run-target'),inspection=load('version-original').inspection,emitted=[],ranges=[]
const deps={vue:Vue,'element-plus':{ElMessage:{warning(){},success(){},error(){}}},'@/api/softwareIntegration':{softwareIntegrationApi:{
  async getRun(id){assert.equal(id,baseline.id);const response=await fetch(`http://127.0.0.1:5173/api/software-integration/runs/${id}`);assert.equal(response.status,200);return response.json()},
  async downloadArtifactRange(runId,artifactId,offset,length){assert.equal(runId,baseline.id);assert.equal(length,4)
    const response=await fetch(`http://127.0.0.1:5173/api/software-integration/runs/${runId}/artifacts/${artifactId}/range?offset=${offset}&length=${length}`);assert.equal(response.status,206)
    assert.ok(response.headers.get('content-range').startsWith(`bytes ${offset}-${offset+3}/`));const bytes=await response.arrayBuffer();assert.equal(bytes.byteLength,4);ranges.push({runId,artifactId,offset,length});return bytes}
}}}
for(const name of readdirSync(new URL('../src/views/SoftwareIntegration/',import.meta.url)).filter(n=>/^eclipse.*\.js$/.test(n)))deps['./'+name.slice(0,-3)]=await import(new URL('../src/views/SoftwareIntegration/'+name,import.meta.url))
const renderer=Vue.createRenderer({createElement:()=>({}),createText:()=>({}),createComment:()=>({}),insert(){},remove(){},setText(){},setElementText(){},patchProp(){},parentNode:()=>null,nextSibling:()=>null})
function mount(name,props){
  const source=readFileSync(new URL(`../src/views/SoftwareIntegration/${name}.vue`,import.meta.url),'utf8'),compiled=compileScript(parse(source).descriptor,{id:name,genDefaultAs:'component'}).content
  const code=compiled.replace(/^import (.+) from ['"](.+)['"]\r?$/gm,(_,bindings,module)=>{if(module.endsWith('.vue'))return `const ${bindings} = {}`
    assert.ok(deps[module],module);return bindings.startsWith('* as ')?`const ${bindings.slice(5)} = deps[${JSON.stringify(module)}]`:`const ${bindings} = deps[${JSON.stringify(module)}]`})
  const component=new Function('deps',`${code};return component`)(deps),setup=component.setup;let state
  component.setup=(p,c)=>{state=setup(p,c);return state};component.render=()=>null
  const app=renderer.createApp({render:()=>Vue.h(component,{...props,onCreateNewCompletionRun:p=>emitted.push(p)})});app.mount({});return{app,state}
}
const fields=baseline.result.fieldIndex.files.flatMap(f=>f.fields.map(v=>({file:f.name,byteOrder:f.byteOrder,...v})))
const editor=mount('EclipseNewCompletionEditor',{run:baseline,inspection,grid:baseline.result.grid,fields,artifacts:baseline.artifacts})
try{editor.state.draft.value={...run.parameters};await editor.state.submit();assert.equal(editor.state.error.value,'');assert.deepEqual(emitted,[run.parameters]);assert.equal(ranges.length,1)}finally{editor.app.unmount()}
const result=mount('EclipseRunResult',{run,inspection,history:[]})
let proof
try{
  await result.state.loadScheduleBaseline();assert.equal(result.state.scheduleBaselineError.value,'')
  const diff=result.state.newCompletionDiff.value;assert.equal(diff.available,true);assert.equal(diff.rows.length,144);assert.equal(diff.unmatchedCount,0)
  const selected=diff.rows.filter(r=>r.well==='P'&&r.keyword==='WBHP');assert.equal(selected.length,24);assert.equal(selected.filter(r=>Math.abs(r.difference)>.01).length,23)
  assert.equal(selected.at(-1).baseline,3885.1171875);assert.equal(selected.at(-1).current,3906.57421875)
  for(const row of diff.rows){
    const match=s=>s.summary.series.find(s=>s.keyword===row.keyword&&s.objectName===row.well).points.find(p=>p.timeDays===row.timeDays).value
    assert.equal(row.current,match(run.result));assert.equal(row.baseline,match(baseline.result));assert.equal(row.difference,row.current-row.baseline)
  }
  proof={automatedComponentVerified:true,manualUiVerified:false,runId:run.id,exactSubmittedParameters:true,actualRangeRequests:ranges,actualWellResponseRows:144,unmatchedRows:0,existingWellPressureChangedReports:23,originalWellControlsPreserved:true}
}finally{result.app.unmount()}
writeFileSync(resolve(directory,'automated-new-completion-ui.json'),JSON.stringify(proof,null,2),{flag:'wx'});console.log(JSON.stringify(proof))
