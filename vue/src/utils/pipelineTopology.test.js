import test from 'node:test'
import assert from 'node:assert/strict'
import { createPipelineInput, newSegment, newEquipment } from './pipelineDefaults.js'
import { fromInput, checkTopology, createNode, removeObjects } from './pipelineTopology.js'
import { resolveSavedNetworkTopology } from './pipelineBatch.js'
const networkInput = (graph, base) => resolveSavedNetworkTopology({ revision: 1, graph }, base).input

const createPipelineExample = () => ({...createPipelineInput(), inletMpa:6,outletMpa:4,rate10k:8,inletC:45,
  segments:[{...newSegment(0),lengthM:1800,elevationChangeM:20},{...newSegment(1),lengthM:2200,elevationChangeM:-10}],equipment:[newEquipment(0)]})

test('saved network conversion preserves elevations, order, devices and settings after layout changes', () => {
  const input=createPipelineExample()
  input.equipment.push({...input.equipment[0],name:'第二设备'})
  const graph=fromInput(input,'A1-3')
  graph.nodes.reverse();graph.edges.reverse();graph.nodes[0].x=950
  assert.equal(checkTopology(graph).serial,true)
  assert.deepEqual(networkInput(graph,input),{...input,segments:[...input.segments].reverse().map(segment=>({...segment,ambientC:0,heatTransferWm2K:0})),equipment:input.equipment.map(device=>({...device,afterSegment:1}))})
})
test('branch, disconnected cycle and self loops cannot enter the serial solver',()=>{
  const input=createPipelineExample(),graph=fromInput(input,'A1-3')
  const branch=createNode('station',400,400,4);graph.nodes.push(branch)
  graph.edges.push({...graph.edges[0],id:'branch',target:branch.id})
  assert.equal(checkTopology(graph).serial,false)
  const cycle=fromInput(input,'A1-3');cycle.edges.push({...cycle.edges[0],id:'cycle',source:cycle.nodes.at(-1).id,target:cycle.nodes[0].id})
  assert.equal(checkTopology(cycle).serial,false)
  cycle.edges[0].source=cycle.edges[0].target
  assert.ok(checkTopology(cycle).errors.some(e=>e.includes('自身')))
})
test('missing parameters rejected, references do not leak into calculation DTO',()=>{
  const input=createPipelineExample(),graph=fromInput(input,'A1-3')
  graph.edges[0].parameters.lengthM=null
  assert.equal(checkTopology(graph).serial,false)
  graph.edges[0].parameters.lengthM=1800
  graph.edges[0].parameters.pvtId=123
  assert.equal(checkTopology(graph).warnings.length,0)
  assert.equal('pvtId' in networkInput(graph,input).segments[0],false)
})

test('pipe geometry is valid without thermal fields and old topology heat values never enter the flow input',()=>{
  const input=createPipelineExample(),graph=fromInput(input,'A1-3')
  delete graph.edges[0].parameters.ambientC
  delete graph.edges[0].parameters.heatTransferWm2K
  graph.edges[1].parameters.ambientC=null
  graph.edges[1].parameters.heatTransferWm2K=-999
  assert.equal(checkTopology(graph).serial,true)
  assert.deepEqual(checkTopology(graph).errors,[])
  const resolved=networkInput(graph,input)
  for(const segment of resolved.segments){
    assert.equal(segment.ambientC,0)
    assert.equal(segment.heatTransferWm2K,0)
  }
  assert.equal(Object.hasOwn(graph.edges[0].parameters,'ambientC'),false)
  assert.equal(graph.edges[1].parameters.heatTransferWm2K,-999)
  for(const field of ['lengthM','diameterMm','roughnessMm']){
    const old=graph.edges[0].parameters[field]
    delete graph.edges[0].parameters[field]
    assert.equal(checkTopology(graph).serial,false,field)
    graph.edges[0].parameters[field]=old
  }
})

test('topology validates without flow boundaries and never overwrites current calculation settings',()=>{
  const graph=fromInput(createPipelineExample(),'A1-3')
  graph.settings={target:'inlet',inletMpa:null,outletMpa:null,inletC:null,gasGravity:0.8,constraints:{waterState:'dry'}}
  assert.equal(checkTopology(graph).serial,true)
  const base={...createPipelineInput(),target:'rate',inletMpa:8,outletMpa:3,inletC:52,gasGravity:0.62}
  const resolved=networkInput(graph,base)
  for (const key of Object.keys(base).filter(key=>!['segments','equipment'].includes(key))) assert.deepEqual(resolved[key],base[key])
  assert.equal(resolved.segments.length,2)
  assert.equal(resolved.equipment.length,1)
  delete graph.settings
  assert.equal(checkTopology(graph).serial,true)
})

test('additional serial devices are validated and retain their downstream position',()=>{
  const graph=fromInput(createPipelineExample(),'A1-3')
  const deviceNode=graph.nodes.find(node=>node.type==='valve')
  deviceNode.parameters.extraEquipment=[{...newEquipment(1),name:'出口压缩机',type:'compressor',efficiency:null}]
  assert.equal(checkTopology(graph).serial,false)
  assert.ok(checkTopology(graph).errors.some(error=>error.includes('出口压缩机')))
  deviceNode.parameters.extraEquipment[0].efficiency=0.8
  const input=networkInput(graph,createPipelineInput())
  assert.equal(input.equipment[1].name,'出口压缩机')
  assert.equal(input.equipment[1].afterSegment,0)
})

test('current well cannot be deleted and additional well nodes fail validation',()=>{
  const graph=fromInput(createPipelineExample(),'A1-3')
  const well=graph.nodes[0]
  removeObjects(graph,[well.id])
  assert.equal(graph.nodes[0].name,'A1-3')
  assert.equal(graph.edges.length,2)
  graph.nodes.push(createNode('well',100,500,4))
  assert.ok(checkTopology(graph).errors.some(e=>e.includes('一个井口')))
})

test('a new well has only its own wellhead and no fabricated pipeline or boundary data',()=>{
  const input=createPipelineInput(),graph=fromInput(input,'新井')
  assert.equal(graph.nodes.length,1);assert.equal(graph.nodes[0].name,'新井')
  assert.equal(graph.edges.length,0);assert.equal(input.inletMpa,null)
})
