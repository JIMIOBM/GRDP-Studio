// Actual 5173 summaries through production editor and result setups.
import assert from 'node:assert/strict'
import { readFileSync,readdirSync,writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { compileScript,parse } from '@vue/compiler-sfc'
import * as Vue from 'vue'
const directory=resolve(process.argv[2]),load=name=>JSON.parse(readFileSync(resolve(directory,name+'.json'),'utf8'))
assert.equal(load('acceptance-summary').apiVerified,true)
const baseline=load('run-baseline'),inspection=load('version-original').inspection,emitted=[]
const deps={vue:Vue,'element-plus':{ElMessage:{warning(){},success(){},error(){}}},'@/api/softwareIntegration':{softwareIntegrationApi:{
  async getRun(id){assert.equal(id,baseline.id);const response=await fetch(`http://127.0.0.1:5173/api/software-integration/runs/${id}`);assert.equal(response.status,200);return response.json()}
}}}
for(const name of readdirSync(new URL('../src/views/SoftwareIntegration/',import.meta.url)).filter(n=>/^eclipse.*\.js$/.test(n)))deps['./'+name.slice(0,-3)]=await import(new URL('../src/views/SoftwareIntegration/'+name,import.meta.url))
const renderer=Vue.createRenderer({createElement:()=>({}),createText:()=>({}),createComment:()=>({}),insert(){},remove(){},setText(){},setElementText(){},patchProp(){},parentNode:()=>null,nextSibling:()=>null})
function mount(name,props){
  const source=readFileSync(new URL(`../src/views/SoftwareIntegration/${name}.vue`,import.meta.url),'utf8'),compiled=compileScript(parse(source).descriptor,{id:name,genDefaultAs:'component'}).content
  const code=compiled.replace(/^import (.+) from ['"](.+)['"]\r?$/gm,(_,bindings,module)=>{
    if(module.endsWith('.vue'))return `const ${bindings} = {}`
    assert.ok(deps[module],module);return bindings.startsWith('* as ')?`const ${bindings.slice(5)} = deps[${JSON.stringify(module)}]`:`const ${bindings} = deps[${JSON.stringify(module)}]`
  })
  const component=new Function('deps',`${code};return component`)(deps),setup=component.setup;let state
  component.setup=(p,c)=>{state=setup(p,c);return state};component.render=()=>null
  const app=renderer.createApp({render:()=>Vue.h(component,{...props,onCreateOilPvtRun:p=>emitted.push(p)})});app.mount({});return{app,state}
}
const proof=[]
for(const label of ['target']){
  const run=load('run-'+label),h=mount('EclipseOilPvtEditor',{run:baseline,inspection})
  try{h.state.multiplier.value=run.parameters.multiplier;h.state.submit();assert.equal(h.state.error.value,'');assert.deepEqual(emitted.at(-1),run.parameters)}finally{h.app.unmount()}
  const r=mount('EclipseRunResult',{run,inspection,history:[]})
  try{
    await r.state.loadScheduleBaseline();assert.equal(r.state.scheduleBaselineError.value,'');assert.deepEqual(r.state.oilPvtScenario.value,run.parameters)
    const diff=r.state.oilPvtDiff.value;assert.equal(diff.available,true);assert.equal(diff.rows.length,84);assert.equal(diff.changedCount,61);assert.equal(diff.unmatchedCount,0)
    for(const row of diff.rows){const next=run.result.summary.series.find(s=>s.keyword===row.keyword&&s.objectName===row.well),old=baseline.result.summary.series.find(s=>s.keyword===row.keyword&&s.objectName===row.well)
      assert.equal(row.current,next.points.find(p=>p.timeDays===row.timeDays).value);assert.equal(row.baseline,old.points.find(p=>p.timeDays===row.timeDays).value);assert.equal(row.difference,row.current-row.baseline);assert.equal(row.unit,next.unit)}
    proof.push({label,runId:run.id,exactSubmittedParameters:true,actualResponseRows:diff.rows.length,changedRows:diff.changedCount,unmatchedRows:diff.unmatchedCount})
  }finally{r.app.unmount()}
}
const receipt={automatedComponentVerified:true,manualUiVerified:false,scenarios:proof}
writeFileSync(resolve(directory,'automated-pvdo-ui.json'),JSON.stringify(receipt,null,2),{flag:'wx'});console.log(JSON.stringify(receipt))
