import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import * as Vue from 'vue'
import * as defaults from './pipelineDefaults.js'
import * as navigation from './pipelineNavigation.js'
import * as pages from './pipelinePageState.js'
import * as boundary from './pipelineBoundary.js'
import * as pvt from './pipelinePvtModel.js'
import * as constraints from './pipelineConstraintResults.js'
import * as comparison from './pipelineBoundaryComparison.js'
import * as thermal from './pipelineFlowThermal.js'
import * as batch from './pipelineBatch.js'
import { fromInput } from './pipelineTopology.js'
const source = fs.readFileSync(new URL('../composables/usePipelineWorkspace.js', import.meta.url), 'utf8')
const copy = value => JSON.parse(JSON.stringify(value))
function fixture() {
  const input = { ...defaults.createPipelineInput(), jtKmpa: 0.3, segments: [{...defaults.newSegment(0),ambientC:0,heatTransferWm2K:0}] }
  const graph = fromInput(input, '井 A'), first = boundary.createBoundaryCase(graph), second = boundary.createBoundaryCase(graph)
  first.operatingAt = '2026-09-11T01:00'; second.operatingAt = '2026-09-11T02:00'
  for (const c of [first,second]) Object.assign(c.nodes[0], {pressureMpa:8,temperatureC:35,supplyRate10k:12})
  input.boundary = {topologyRevision:2,activeCaseId:first.id,cases:[first,second]}
  const gas = {pvtId:4,revision:1,compositionRevision:'sample',method:'PR'}
  input.gasModel = {...gas}
  const temperature = {revision:3,settings:{segments:[{edgeId:graph.edges[0].id,ambientC:17,layers:[],gasConductivityWmK:0.04}],tolerance:0.001,maxIterations:30}}
  input.thermalModel = {...copy(temperature),topologyRevision:2}
  const resultFor = input => ({algorithmVersion:batch.PIPELINE_BATCH_VERSION,successCount:input.boundary.cases.length,failureCount:0,
    cases:input.boundary.cases.map(c=>({caseId:c.id,operatingAt:c.operatingAt,status:'success',pipes:[{edgeId:graph.edges[0].id,name:'管段1',inletMpa:8,outletMpa:7,inletC:35,outletC:30,rate10k:12}]}))})
  const model = {id:1,revision:5,topologyRevision:2,input:copy(input)}
  let currentTemperature=temperature, calculated=0, saves=0, lastRequest, pending
  let latest={id:9,revision:5,topologyRevision:2,input:copy(input),graph:copy(graph),result:resultFor(input),calculationToken:null}
  const api={
    model:async()=>({data:copy(model)}), topology:async()=>({data:{revision:2,graph:copy(graph)}}),
    pvtModel:async()=>({data:copy(gas)}),temperature:async()=>({data:copy(currentTemperature)}),
    latestBatch:async()=>({data:copy(latest)}),
    calculateBatch:async request=>{calculated++;lastRequest=copy(request);const used=copy(request.input)
      used.thermalModel=used.thermalMode==='heat'?{...copy(currentTemperature),topologyRevision:2}:null
      pending={id:null,revision:model.revision,topologyRevision:2,input:used,graph:copy(graph),result:resultFor(used),calculationToken:'token'}
      return {data:copy(pending)}},
    saveBatch:async request=>{assert.equal(request.calculationToken,'token');saves++;model.revision++;model.input=copy(pending.input)
      latest={...copy(pending),id:10,revision:model.revision};return {data:copy(latest)}}
  }
  const deps={computed:Vue.computed,reactive:Vue.reactive,ref:Vue.ref,watch:Vue.watch,...defaults,...navigation,...pages,...boundary,...pvt,...constraints,...comparison,...thermal,...batch,
    makeBatchRows:batch.batchDataRows,makeBatchCharts:batch.batchChartSeries,api,onMounted:()=>{},onBeforeUnmount:()=>{},
    ElMessage:{success:()=>{},warning:()=>{}},ElMessageBox:{confirm:async()=>true}}
  defaults.pipelineDrafts.clear()
  const create=new Function(...Object.keys(deps),source.replace(/^import .*$/gm,'').replace('export function','function')+'\nreturn usePipelineWorkspace;')(...Object.values(deps))
  const state=create(Vue.reactive({projectId:1,gasReservoirId:2,wellName:'井 A',initialSection:'flow'}))
  return {state,model,temperature,api,setTemperature:v=>currentTemperature=v,calculated:()=>calculated,saves:()=>saves,lastRequest:()=>lastRequest}
}

test('loading a saved batch opens three-chart analysis and selection alone does not invalidate all-time results',async()=>{
  const f=fixture();await f.state.loadModel()
  assert.equal(f.state.storageError,'');assert.equal(f.state.panel,'analysis');assert.equal(f.state.batchStale,false)
  assert.equal(f.state.batchDataRows.length,2);assert.equal(f.state.batchChartSeries.temperature[0].data.length,2)
  f.state.form.boundary.activeCaseId=f.state.form.boundary.cases[1].id
  assert.equal(f.state.batchStale,false)
  f.state.form.boundary.activeCaseId=null
  assert.equal(f.state.canCalculate,true)
})
test('calculate includes every case and save stores that exact batch then reloads its parameters',async()=>{
  const f=fixture();await f.state.loadModel();await f.state.calculate()
  assert.equal(f.calculated(),1);assert.equal(f.lastRequest().input.boundary.cases.length,2);assert.equal(f.state.canBatchSave,true)
  await f.state.save()
  assert.equal(f.saves(),1);assert.equal(f.state.revision,6);assert.equal(f.state.dirty,false);assert.equal(f.state.canBatchSave,false)
  await f.state.loadModel(true)
  assert.equal(f.state.form.jtKmpa,0.3);assert.equal(f.state.batchStale,false);assert.equal(f.state.panel,'analysis')
})
test('new saved thermal source makes the whole batch stale and fresh calculation captures it',async()=>{
  const f=fixture();await f.state.loadModel();f.setTemperature({...f.temperature,revision:4});await f.state.refreshThermalSource()
  assert.equal(f.state.batchStale,true);assert.equal(f.state.batchChartSeries.pressure.length,0)
  await f.state.calculate();assert.equal(f.state.batchStale,false);assert.equal(f.state.form.thermalModel.revision,4)
})
test('missing thermal source blocks heat but isothermal still calculates all times without JT',async()=>{
  const f=fixture();f.setTemperature(null);await f.state.loadModel();assert.equal(f.state.canCalculate,false)
  await f.state.calculate();assert.equal(f.calculated(),0)
  f.state.form.thermalMode='isothermal';f.state.form.jtKmpa=null;assert.equal(f.state.canCalculate,true)
  await f.state.calculate();assert.equal(f.calculated(),1);assert.equal(f.state.form.thermalModel,null);assert.equal(f.state.batchStale,false)
})
test('external boundary updates refresh all cases without discarding unsaved conflicting measurements',async()=>{
  const f=fixture();await f.state.loadModel();f.model.input.boundary.cases[1].nodes[0].pressureMpa=9;f.model.revision++
  await f.state.calculate();assert.equal(f.lastRequest().input.boundary.cases[1].nodes[0].pressureMpa,9)
  f.state.form.boundary.cases[0].nodes[0].pressureMpa=10;f.model.input.boundary.cases[0].nodes[0].pressureMpa=11;f.model.revision++
  await f.state.calculate();assert.equal(f.calculated(),1);assert.match(f.state.batchError,/边界条件已在其他页面更新/)
  assert.equal(f.state.form.boundary.cases[0].nodes[0].pressureMpa,10)
})

test('boundary time text is validated only on save and minute timestamps survive saving and reloading',async()=>{
  const f=fixture();await f.state.loadModel();f.state.activePage='boundary'
  let saved=0
  f.api.saveSection=async(page,request)=>{
    assert.equal(page,'boundary');saved++;f.model.revision++;f.model.input=pages.normalizePipelineInput(request.input)
    return {data:copy(f.model)}
  }
  f.state.form.boundary.cases[0].operatingAt='2026/09/'
  await Vue.nextTick();assert.equal(f.state.error,'');assert.equal(saved,0)
  await f.state.savePage();assert.equal(saved,0);assert.match(f.state.error,/第 1 行.*YYYY\/MM\/DD/)
  f.state.form.boundary.cases[0].operatingAt='2026/09/11 08:17'
  f.state.form.boundary.cases[1].operatingAt='2026/09/11 08:49'
  await f.state.savePage();assert.equal(f.state.error,'');assert.equal(saved,1)
  assert.deepEqual(f.model.input.boundary.cases.map(row=>row.operatingAt),['2026-09-11T08:17','2026-09-11T08:49'])
  await f.state.reloadPage();assert.equal(f.state.form.boundary.cases[1].operatingAt,'2026-09-11T08:49')
})


test('water conditions invalidate the same batch and hydrate actions calculate and save every condition', async () => {
  const f=fixture();await f.state.loadModel();f.state.activePage='hydrate'
  assert.equal(f.state.form.constraints.waterState,'unknown')
  assert.equal(f.state.batchStale,false)
  f.state.form.constraints.waterState='available'
  assert.equal(f.state.batchStale,true);assert.deepEqual(f.state.hydrateRows,[])
  await f.state.calculate();assert.equal(f.lastRequest().input.constraints.waterState,'available')
  assert.equal(f.state.batchStale,false);assert.equal(f.state.hydrateRows.length,2)
  await f.state.savePage();assert.equal(f.saves(),1);assert.equal(f.state.batchSaved,true)
  assert.equal(f.state.form.constraints.waterState,'available')
  assert.equal('runs' in f.state,false);assert.equal('result' in f.state,false)
})
