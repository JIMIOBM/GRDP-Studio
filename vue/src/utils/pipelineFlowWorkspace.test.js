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
import * as erosion from './pipelineErosion.js'
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
    cases:input.boundary.cases.map(c=>({caseId:c.id,operatingAt:c.operatingAt,status:'success',pipes:[{edgeId:graph.edges[0].id,name:'管段1',inletMpa:8,outletMpa:7,inletC:35,outletC:30,rate10k:12}],
      erosion:input.constraints?.erosion?.segments?.length && !input.constraints.erosion.liquidPvt?.issue
        ? [{edgeId:graph.edges[0].id,criticalVelocityMs:10}] : [{edgeId:graph.edges[0].id,status:'not_evaluated',criticalVelocityMs:null}]}))})
  const model = {id:1,revision:5,topologyRevision:2,input:copy(input)}
  let currentTemperature=temperature, liquidSources=[], calculated=0, saves=0, lastRequest, pending
  const messages=[]
  let latest={id:9,revision:5,topologyRevision:2,input:copy(input),graph:copy(graph),result:resultFor(input),calculationToken:null}
  const api={
    model:async()=>({data:copy(model)}), topology:async()=>({data:{revision:2,graph:copy(graph)}}),
    pvtModel:async()=>({data:copy(gas)}),temperature:async()=>({data:copy(currentTemperature)}),
    erosionLiquidSources:async()=>({data:copy(liquidSources)}),
    latestBatch:async()=>({data:copy(latest)}),
    calculateBatch:async request=>{calculated++;lastRequest=copy(request);const used=copy(request.input)
      used.thermalModel=used.thermalMode==='heat'?{...copy(currentTemperature),topologyRevision:2}:null
      if (used.constraints?.erosion?.liquidPvtId) used.constraints.erosion.liquidPvt=copy(liquidSources.find(s=>s.pvtId===used.constraints.erosion.liquidPvtId))
      pending={id:null,revision:model.revision,topologyRevision:2,input:used,graph:copy(graph),result:resultFor(used),calculationToken:'token'}
      return {data:copy(pending)}},
    saveBatch:async request=>{assert.equal(request.calculationToken,'token');saves++;model.revision++;model.input=copy(pending.input)
      latest={...copy(pending),id:10,revision:model.revision};return {data:copy(latest)}}
  }
  const deps={computed:Vue.computed,reactive:Vue.reactive,ref:Vue.ref,watch:Vue.watch,...defaults,...navigation,...pages,...boundary,...pvt,...constraints,...comparison,...thermal,...batch,...erosion,
    makeBatchRows:batch.batchDataRows,makeBatchCharts:batch.batchChartSeries,api,onMounted:()=>{},onBeforeUnmount:()=>{},
    ElMessage:{success:text=>messages.push({type:'success',text}),warning:text=>messages.push({type:'warning',text})},ElMessageBox:{confirm:async()=>true}}
  defaults.pipelineDrafts.clear()
  const create=new Function(...Object.keys(deps),source.replace(/^import .*$/gm,'').replace('export function','function')+'\nreturn usePipelineWorkspace;')(...Object.values(deps))
  const state=create(Vue.reactive({projectId:1,gasReservoirId:2,wellName:'井 A',initialSection:'flow'}))
  return {state,model,temperature,api,messages,setTemperature:v=>currentTemperature=v,setLiquidSources:v=>liquidSources=v,calculated:()=>calculated,saves:()=>saves,lastRequest:()=>lastRequest}
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

test('erosion page calculates and saves all cases, and edits or liquid-source changes invalidate that batch', async () => {
  const f=fixture();f.setLiquidSources([{pvtId:8,pvtName:'地层水',inputHash:'v1'}]);await f.state.loadModel()
  f.state.activePage='erosion'
  f.state.form.constraints.erosion={liquidPvtId:8,segments:[{edgeId:f.state.savedGraph.edges[0].id,
    liquidHoldupPercent:0.004,sandContentPercent:0.001,sandDensityKgM3:2650}],cases:[]}
  assert.equal(f.state.batchStale,true)
  await f.state.calculate();assert.equal(f.state.error,'');assert.equal(f.state.batchStale,false)
  assert.deepEqual(f.messages.at(-1), {type:'success',text:'冲蚀计算完成：已评价 2 条，未评价 0 条'})
  assert.equal(f.lastRequest().input.boundary.cases.length,2)
  await f.state.savePage();assert.equal(f.saves(),1);assert.equal(f.state.batchSaved,true)
  await f.state.loadModel(true);assert.equal(f.state.panel,'analysis')
  assert.equal(f.state.form.constraints.erosion.liquidPvt.inputHash,'v1')
  f.setLiquidSources([{pvtId:8,pvtName:'地层水',inputHash:'v2'}]);await f.state.refreshErosionLiquidSources()
  assert.equal(f.state.batchStale,true)
  await f.state.calculate();assert.equal(f.state.batchStale,false)
  f.state.form.constraints.erosion.segments[0].sandContentPercent=0
  assert.equal(f.state.batchStale,true);assert.equal(f.state.canBatchSave,false)
})

test('erosion calculation blocks incomplete inputs while flow remains available without erosion parameters', async () => {
  const f=fixture();await f.state.loadModel();f.state.activePage='erosion'
  await f.state.calculate()
  assert.equal(f.calculated(),0);assert.equal(f.state.panel,'data')
  assert.match(f.state.error,/请填写持液率.*含砂率.*砂粒密度/)
  assert.equal(f.messages.at(-1).type,'warning')
  const pipe=f.state.savedGraph.edges[0], conditions=f.state.form.boundary.cases
  f.state.form.constraints.erosion={segments:[],cases:[{edgeId:pipe.id,caseId:conditions[0].id,
    liquidHoldupPercent:0.004,sandContentPercent:0.001,sandDensityKgM3:2650}]}
  await f.state.calculate();assert.equal(f.calculated(),0);assert.match(f.state.error,/2026\/09\/11 02:00/)
  f.state.activePage='flow'
  await f.state.calculate();assert.equal(f.calculated(),1);assert.equal(f.state.error,'')
  assert.match(f.messages.at(-1).text,/全部工况已处理/)
})

test('a removed liquid source invalidates old results but its freshly calculated unevaluated batch can still be saved', async () => {
  const f=fixture();await f.state.loadModel()
  f.state.activePage='erosion'
  const issue={pvtId:8,inputHash:'removed-v1',issue:'所选PVT不存在或不属于当前井'}
  f.state.form.constraints.erosion={liquidPvtId:8,segments:[{edgeId:f.state.savedGraph.edges[0].id,
    liquidHoldupPercent:0.004,sandContentPercent:0.001,sandDensityKgM3:2650}],cases:[],liquidPvt:{pvtId:8,inputHash:'old-valid'}}
  f.api.erosionLiquidSource=async(context,id)=>{assert.equal(id,8);return {data:copy(issue)}}
  await f.state.refreshErosionLiquidSources()
  assert.equal(f.state.erosionLiquidError,'')
  assert.equal(f.state.erosionLiquidSources[0].inputHash,'removed-v1')
  f.setLiquidSources([issue]);await f.state.calculate()
  assert.equal(f.state.batchStale,false);assert.equal(f.state.canBatchSave,true)
  assert.equal(f.state.panel,'data', 'show the missing-data reasons instead of an empty chart')
  assert.deepEqual(f.messages.at(-1), {type:'warning',text:'冲蚀计算完成：已评价 0 条，未评价 2 条'})
  f.api.erosionLiquidSources=async()=>{throw new Error('水相列表暂不可用')}
  await f.state.refreshErosionLiquidSources()
  assert.equal(f.state.erosionLiquidError,'');assert.equal(f.state.batchStale,false)
  await f.state.save();assert.equal(f.saves(),1)
})
