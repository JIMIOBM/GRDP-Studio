import test from 'node:test'
import assert from 'node:assert/strict'
import { boundaryComparisonRows, comparisonChartSeries } from './pipelineBoundaryComparison.js'
import { createPipelineInput, newSegment, newEquipment } from './pipelineDefaults.js'
import { createBoundaryCase } from './pipelineBoundary.js'
import { fromInput, createNode } from './pipelineTopology.js'
import { PIPELINE_BATCH_VERSION } from './pipelineBatch.js'

function fixture() {
  const input={...createPipelineInput(),segments:[newSegment(0),newSegment(1)],equipment:[newEquipment(0)]}
  const graph=fromInput(input,'井 A'), extra=createNode('station',300,300,3)
  graph.nodes.push(extra);graph.edges.push({...structuredClone(graph.edges[1]),id:'branch',target:extra.id,name:'分支'})
  const first=createBoundaryCase(graph), second=createBoundaryCase(graph)
  first.operatingAt='2026-09-11T08:17';second.operatingAt='2026-09-11T08:49'
  Object.assign(first.nodes[0],{supplyRate10k:11,pressureMpa:8,temperatureC:30})
  Object.assign(first.nodes[1],{withdrawalRate10k:1,pressureMpa:7.6})
  Object.assign(first.nodes[2],{withdrawalRate10k:5,pressureMpa:7,temperatureC:25})
  Object.assign(first.nodes[3],{withdrawalRate10k:4,pressureMpa:6.8})
  second.nodes=structuredClone(first.nodes)
  input.boundary={topologyRevision:3,activeCaseId:second.id,cases:[second,first]}
  const pipes=graph.edges.map((edge,index)=>({edgeId:edge.id,rate10k:[10,5,4][index],inletMpa:index?7.5:8,outletMpa:[7.8,6.9,6.7][index],inletC:index?27.8:30,outletC:[28,24.5,23][index]}))
  const result={algorithmVersion:PIPELINE_BATCH_VERSION,cases:[{caseId:first.id,operatingAt:first.operatingAt,status:'success',pipes,
    equipment:[{id:`${graph.nodes[1].id}:0`,nodeId:graph.nodes[1].id,sequence:1,outletMpa:7.5,outletC:27.8}]},
    {caseId:second.id,operatingAt:second.operatingAt,status:'error',error:'边界条件不足',pipes:[],equipment:[]}]}
  return {graph,input,topologyRevision:3,result}
}

test('all-time tree-node comparison uses post-equipment state and local withdrawal balance',()=>{
  const detail=fixture(), before=structuredClone(detail), rows=boundaryComparisonRows(detail,true)
  const first=rows.filter(row=>row.caseId===detail.result.cases[0].caseId)
  const find=(index,key)=>first.find(row=>row.nodeId===detail.graph.nodes[index].id&&row.key===key)
  assert.equal(rows.length,20)
  assert.equal(find(0,'flow').calculated,10);assert.equal(find(0,'flow').difference,-1)
  assert.equal(find(1,'pressure').calculated,7.5);assert.equal(find(1,'flow').calculated,1)
  assert.equal(find(2,'flow').calculated,5);assert.equal(find(3,'flow').calculated,4)
  assert.equal(find(2,'temperature').calculated,24.5)
  assert.deepEqual(detail,before)
})

test('failed cases create missing calculated values and real time gaps while measurements remain visible',()=>{
  const detail=fixture(), rows=boundaryComparisonRows(detail,true), failed=rows.filter(row=>row.caseId===detail.result.cases[1].caseId)
  assert.ok(failed.every(row=>row.calculated===null&&row.difference===null&&row.note==='边界条件不足'))
  const chart=comparisonChartSeries(rows,detail.graph.nodes[1].id,'pressure')
  assert.deepEqual(chart.map(row=>row.data.map(point=>point[1])),[[7.5,null],[7.6,7.6]])
  assert.equal(chart[0].data[1][0]-chart[0].data[0][0],32*60000)
})

test('selection is immaterial but stale, old-version and different topology snapshots never compare',()=>{
  const detail=fixture(), expected=boundaryComparisonRows(detail,true)
  detail.input.boundary.activeCaseId=null
  assert.deepEqual(boundaryComparisonRows(detail,true),expected)
  assert.deepEqual(boundaryComparisonRows(detail,false),[])
  detail.topologyRevision=4;assert.deepEqual(boundaryComparisonRows(detail,true),[])
  detail.topologyRevision=3;detail.result.algorithmVersion='network-batch-1.0'
  assert.deepEqual(boundaryComparisonRows(detail,true),[])
})
