// Real 5173 ranges through production grid and result setups; no substituted fields.
import assert from 'node:assert/strict'
import { readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { compileScript, parse } from '@vue/compiler-sfc'
import * as Vue from 'vue'
import * as THREE from 'three'
const directory=resolve(process.argv[2]), nativeDirectory=resolve(process.argv[3])
const load=name=>JSON.parse(readFileSync(resolve(directory,name+'.json'),'utf8'))
assert.equal(load('acceptance-summary').apiVerified,true)
const native=JSON.parse(readFileSync(resolve(nativeDirectory,'native-acceptance.json'),'utf8')), baseline=load('run-baseline')
const ranges=[],emitted=[]
const deps={vue:Vue,three:THREE,'three/addons/controls/OrbitControls.js':{OrbitControls:class{}},'element-plus':{ElMessage:{warning(){},success(){},error(){}}},
  '@/api/softwareIntegration':{softwareIntegrationApi:{
    async downloadArtifactRange(runId,artifactId,offset,length){
      const response=await fetch(`http://127.0.0.1:5173/api/software-integration/runs/${runId}/artifacts/${artifactId}/range?offset=${offset}&length=${length}`)
      assert.equal(response.status,206); const content=new Uint8Array(await response.arrayBuffer());assert.equal(content.length,length)
      ranges.push({runId,artifactId,offset,length});return content
    },
    async getRun(id){assert.equal(id,baseline.id);const response=await fetch(`http://127.0.0.1:5173/api/software-integration/runs/${id}`);assert.equal(response.status,200);return response.json()}
  }}}
for(const name of readdirSync(new URL('../src/views/SoftwareIntegration/',import.meta.url)).filter(n=>/^eclipse.*\.js$/.test(n)))deps['./'+name.slice(0,-3)]=await import(new URL('../src/views/SoftwareIntegration/'+name,import.meta.url))
const renderer=Vue.createRenderer({createElement:()=>({}),createText:()=>({}),createComment:()=>({}),insert(){},remove(){},setText(){},setElementText(){},patchProp(){},parentNode:()=>null,nextSibling:()=>null})
function mount(name,props){
  const text=readFileSync(new URL(`../src/views/SoftwareIntegration/${name}.vue`,import.meta.url),'utf8')
  const compiled=compileScript(parse(text).descriptor,{id:name,genDefaultAs:'component'}).content
  const code=compiled.replace(/^import (.+) from ['"](.+)['"]\r?$/gm,(_,bindings,module)=>{
    if(module.endsWith('.vue'))return `const ${bindings} = {}`
    assert.ok(deps[module],module)
    return bindings.startsWith('* as ')?`const ${bindings.slice(5)} = deps[${JSON.stringify(module)}]`:`const ${bindings} = deps[${JSON.stringify(module)}]`
  })
  const component=new Function('deps','cancelAnimationFrame',`${code};return component`)(deps,()=>{})
  const setup=component.setup;let state
  component.setup=(p,c)=>{state=setup(p,c);return state};component.render=()=>null
  const app=renderer.createApp({render:()=>Vue.h(component,{...props,onCreateGridFieldRun:p=>emitted.push(p)})});app.mount({})
  return {app,state}
}
async function wait(test){for(let i=0;i<600;i++){await new Promise(r=>setTimeout(r,20));await Vue.nextTick();if(test())return}throw new Error('Actual component transport timeout')}
const propsFor=run=>({run,runId:run.id,grid:run.result.grid,fields:run.result.fieldIndex.files.flatMap(file=>file.fields.map(f=>({file:file.name,byteOrder:file.byteOrder,...f}))),artifacts:run.artifacts,wellCompletions:[]})
const proof=[]
for(const label of ['permx','poro']){
  const target=load('run-'+label), props=Vue.reactive(propsFor(baseline)), h=mount('EclipseGrid3D',props),s=h.state
  try{
    await wait(()=>s.geometryReady.value&&!s.loading.value&&!s.fieldLoading.value)
    const field=props.fields.find(f=>f.keyword===target.parameters.keyword&&f.file.endsWith('.INIT'))
    s.selectedFieldKey.value=s.fieldKey(field)
    await wait(()=>!s.fieldLoading.value&&s.fieldValues.value.length===225)
    assert.deepEqual(s.fieldValues.value,native.baseline.arrays[target.parameters.keyword])
    s.regionBounds.value=Object.fromEntries(['i1','i2','j1','j2','k1','k2'].map(key=>[key,target.parameters[key]]))
    s.regionMultiplier.value=target.parameters.multiplier;s.runRegionScenario();assert.equal(s.regionError.value,'')
    assert.deepEqual(emitted.at(-1),target.parameters)
    Object.assign(props,propsFor(target));await Vue.nextTick()
    await wait(()=>s.geometryReady.value&&!s.loading.value&&!s.fieldLoading.value&&s.fieldValues.value.length===225)
    assert.deepEqual(s.fieldValues.value,native[label].arrays[target.parameters.keyword])
    assert.equal(s.canCreateRegionRun.value,false)
    for(let j=1;j<=15;j++)for(let i=1;i<=15;i++){
      s.locateI.value=i;s.locateJ.value=j;s.locateK.value=1;s.locateCell()
      assert.equal(s.selectedCell.value.value,native[label].arrays[target.parameters.keyword][(j-1)*15+i-1])
    }
  }finally{h.app.unmount()}
  const r=mount('EclipseRunResult',{run:target,history:[target,baseline],inspection:load('version-original').inspection})
  try{
    await wait(()=>r.state.scheduleBaselineResult.value||r.state.scheduleBaselineError.value)
    assert.equal(r.state.scheduleBaselineError.value,'')
    assert.deepEqual(r.state.result.value,target.result);assert.deepEqual(r.state.scheduleBaselineResult.value,baseline.result)
    assert.deepEqual(r.state.regionScenario.value,target.parameters)
    const response=r.state.regionResponse.value;assert.equal(response.available,true);assert.equal(response.rows.length,72);assert.equal(response.unmatchedCount,0)
    for(const row of response.rows){
      const value=(run)=>run.result.summary.series.find(s=>s.keyword===row.keyword&&s.objectName===row.well).points.find(p=>p.timeDays===row.timeDays).value
      assert.equal(row.baseline,value(baseline));assert.equal(row.current,value(target));assert.equal(row.difference,value(target)-value(baseline))
    }
    proof.push({keyword:target.parameters.keyword,runId:target.id,decodedCells:225,coordinateSelections:225,responseRows:72,changedRows:response.changedCount,unmatchedRows:0})
  }finally{r.app.unmount()}
}
const receipt={automatedComponentVerified:true,manualUiVerified:false,actualRangeRequests:ranges.length,exactProductionRequests:true,scenarios:proof}
writeFileSync(resolve(directory,'automated-region-ui.json'),JSON.stringify(receipt,null,2),{flag:'wx'});console.log(JSON.stringify(receipt))
