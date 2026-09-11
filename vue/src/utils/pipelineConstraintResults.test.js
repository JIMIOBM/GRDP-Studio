import test from 'node:test'
import assert from 'node:assert/strict'
import { constraintDataRows, constraintChartSeries, topologyEquipment } from './pipelineConstraintResults.js'
import { PIPELINE_BATCH_VERSION, batchInputMark } from './pipelineBatch.js'

function fixture() {
  const graph={nodes:[{id:'well',type:'well',name:'井口'}, {id:'device',type:'valve',name:'同名设备',parameters:{lossK:8,maxPressureMpa:10,extraEquipment:[{name:'同名设备',type:'compressor',pressureRatio:1.2,maxPressureMpa:12,maxPowerKw:100}]}}],
    edges:[{id:'pipe',name:'管道 A',source:'well',target:'device'}]}
  const devices=[{id:'device:0',name:'同名设备',status:'pass',inletMpa:8,outletMpa:7.9,inletC:30,outletC:29.9,maxPressureMpa:10},
    {id:'device:1',name:'同名设备',status:'fail',inletMpa:7.9,outletMpa:9.4,inletC:29.9,outletC:36,powerKw:101,maxPowerKw:100,maxPressureMpa:12}]
  return {graph,result:{algorithmVersion:PIPELINE_BATCH_VERSION,cases:[
    {caseId:'later',operatingAt:'2026-09-11T08:49',status:'error',error:'该工况管流失败',equipment:[],hydrate:[]},
    {caseId:'first',operatingAt:'2026-09-11T08:17',status:'success',equipment:devices,
      hydrate:[{edgeId:'pipe',name:'管道 A',status:'conditional',distanceM:700,temperatureC:5,equilibriumC:9,marginC:-4,pressureMpa:8}]}]}}
}

test('all-time device rows retain ordered stable IDs, stored limits, and failed-case gaps',()=>{
  const detail=fixture(), snapshot=structuredClone(detail)
  const catalog=topologyEquipment(detail.graph);assert.deepEqual(catalog.map(row=>row.id),['device:0','device:1'])
  const rows=constraintDataRows(detail,'equipment',true);assert.equal(rows.length,4)
  assert.equal(rows[0].operatingAt,'2026-09-11T08:17');assert.equal(rows[1].status,'fail')
  assert.equal(rows[1].maxPowerKw,100);assert.equal(rows[2].status,'not_evaluated');assert.equal(rows[2].reason,'该工况管流失败')
  const series=constraintChartSeries(rows,'device:1','equipment','power')
  assert.deepEqual(series.map(row=>row.data.map(point=>point[1])),[[101,null],[100,null]])
  assert.equal(series[0].data[1][0]-series[0].data[0][0],32*60000)
  assert.deepEqual(detail,snapshot)
})

test('hydrate retains same-point temperatures and conditional/equilibrium values without treating missing coverage as safe',()=>{
  const detail=fixture(), rows=constraintDataRows(detail,'hydrate',true)
  assert.equal(rows.length,2);assert.equal(rows[0].distanceM,700)
  assert.equal(rows[0].marginC,rows[0].temperatureC-rows[0].equilibriumC)
  let chart=constraintChartSeries(rows,'pipe','hydrate','margin')
  assert.deepEqual(chart.map(row=>row.data.map(point=>point[1])),[[-4,null],[0,null]])
  rows[0].status='equilibrium';rows[0].marginC=0
  assert.equal(constraintChartSeries(rows,'pipe','hydrate','margin')[0].data[0][1],0)
  rows[0].status='not_evaluated'
  assert.equal(constraintChartSeries(rows,'pipe','hydrate','margin')[0].data[0][1],null)
})

test('old or stale batches never expose fabricated constraint values',()=>{
  const detail=fixture()
  assert.deepEqual(constraintDataRows(detail,'equipment',false),[])
  detail.result.algorithmVersion='network-batch-1.0'
  assert.deepEqual(constraintDataRows(detail,'hydrate',true),[])
})

test('available water is a calculation input; omitted water remains unknown rather than assuming free water',()=>{
  const input={constraints:null}, unknown=batchInputMark(input,3)
  assert.equal(unknown,batchInputMark({constraints:{waterState:'unknown'}},3))
  assert.notEqual(unknown,batchInputMark({constraints:{waterState:'available'}},3))
})
