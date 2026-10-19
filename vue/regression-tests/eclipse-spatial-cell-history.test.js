import test from 'node:test'
import assert from 'node:assert/strict'
import { loadSpatialCellHistory, spatialElementOffset, spatialCellHistoryCsv } from '../src/views/SoftwareIntegration/eclipseSpatialCellHistory.js'

function fixture({ fullCurrent = false, little = false, withInitial = false } = {}) {
  const grid={nx:2,ny:2,nz:2,activeCells:6,fileName:'CASE.EGRID'},mask=[1,0,1,1,0,1,1,1],buffers=new Map(),requests=[],valueRanges=[]
  function build(id){
    const artifacts=['EGRID','UNRST'].map((ext,i)=>({id:id*10+i,name:`eclipse-output-CASE.${ext}`,contentType:'application/octet-stream',sizeBytes:100000,sha256:(ext==='EGRID'?'a':'b').repeat(64)}))
    const files=['EGRID','UNRST'].map(ext=>({name:`CASE.${ext}`,byteOrder:little?'LITTLE':'BIG',fields:[]}));let offset=128
    for(const a of artifacts)buffers.set(`${id}:${a.id}`,new ArrayBuffer(a.sizeBytes))
    function field(file,keyword,type,numbers,report){
      const elementSize=type==='DOUB'?8:4,artifact=artifacts[file],view=new DataView(buffers.get(`${id}:${artifact.id}`)),segments=[],split=numbers.length>2?2:numbers.length
      for(const [start,end] of [[0,split],[split,numbers.length]])if(start<end){
        const at=offset;for(let n=start;n<end;n++){if(type==='INTE')view.setInt32(offset,numbers[n],little);else if(type==='DOUB')view.setFloat64(offset,numbers[n],little);else view.setFloat32(offset,numbers[n],little);offset+=elementSize}
        segments.push({offset:at,length:(end-start)*elementSize});offset+=16
      }
      offset+=64;const f={keyword,dataType:type,elementSize,count:numbers.length,timeStep:report,segments};files[file].fields.push(f)
      if(keyword==='TEMP')valueRanges.push({id,artifact:artifact.id,field:f})
      return f
    }
    field(0,'ACTNUM','INTE',mask)
    for(const [report,time] of [...(withInitial?[[0,0]]:[]),[2,365],[5,1825],[9,3650]]){
      let values=[10,20,30,40,50,60].map(v=>v+(id===2&&report===9?10:0))
      if(id===2&&fullCurrent){let active=0;values=mask.map(v=>v>0?values[active++]:999)}
      const step=report===0?undefined:report
      field(1,'TEMP','REAL',values,step);field(1,'DOUBHEAD','DOUB',[time,0,0],step)
      const header=Array(100).fill(0);header[2]=2;header[8]=2;header[9]=2;header[10]=2;header[11]=6;header[64]=1;header[65]=1;header[66]=2024;header[67]=10;header[94]=500
      field(1,'INTEHEAD','INTE',header,step);field(1,'SEQNUM','INTE',[report],step)
    }
    return {id,modelId:7,modelVersionId:8,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:id===1?null:{baselineRunId:1},artifacts,result:{grid:{...grid},fieldIndex:{files}}}
  }
  const baseline=build(1),run=build(2)
  const request=async(id,artifact,offset,length)=>{requests.push({id,artifact,offset,length});return new Blob([buffers.get(`${id}:${artifact}`).slice(offset,offset+length)])}
  return {run,baseline,index:5,keyword:'TEMP',unitSystem:'FIELD',request,buffers,requests,valueRanges}
}
test('global active cell selects the correct compressed ordinal across native record segments',async()=>{
  const f=fixture(),data=await loadSpatialCellHistory(f)
  assert.deepEqual([data.i,data.j,data.k],[2,1,2]);assert.deepEqual(data.rows.map(r=>[r.report,r.timeDays,r.baselineValue,r.value,r.difference]),[[2,365,40,40,0],[5,1825,40,40,0],[9,3650,40,50,10]])
  const valueReads=f.requests.filter(r=>f.valueRanges.some(v=>v.id===r.id&&v.artifact===r.artifact&&v.field.segments.some(s=>r.offset>=s.offset&&r.offset<s.offset+s.length)))
  assert.equal(valueReads.length,6);assert.ok(valueReads.every(r=>r.length===4))
  assert.ok(spatialCellHistoryCsv(data,'°F').includes('"1","2","8","TEMP","°F","2","1","2","5","9","3650","40","50","10"'))
})
test('full-grid current data and compressed baseline map identically in either byte order',async()=>{
  for(const little of [false,true]){const data=await loadSpatialCellHistory(fixture({fullCurrent:true,little}));assert.deepEqual(data.rows.map(r=>r.difference),[0,0,10])}
})
test('initial unnumbered index block requires native SEQNUM0 and actual zero elapsed time',async()=>{
  const f=fixture({withInitial:true}),data=await loadSpatialCellHistory(f)
  assert.deepEqual(data.rows.map(r=>[r.report,r.timeDays]),[[0,0],[2,365],[5,1825],[9,3650]])
  const field=f.run.result.fieldIndex.files[1].fields.find(v=>v.keyword==='SEQNUM'&&v.timeStep===undefined)
  new DataView(f.buffers.get('2:21')).setInt32(field.segments[0].offset,1)
  await assert.rejects(loadSpatialCellHistory(f),/实际时间.*SEQNUM/)
})
test('element offset validates every original segment rather than treating index as a byte offset',()=>{
  const field={count:5,elementSize:4,segments:[{offset:100,length:8},{offset:140,length:12}]}
  assert.equal(spatialElementOffset(field,0,200),100);assert.equal(spatialElementOffset(field,3,200),144)
  for(const bad of [{...field,count:4},{...field,segments:[{offset:100,length:9}]},{...field,segments:[{offset:100,length:8},{offset:104,length:12}]}])assert.throws(()=>spatialElementOffset(bad,1,200))
  assert.throws(()=>spatialElementOffset(field,5,200));assert.throws(()=>spatialElementOffset(field,3,149))
})
test('inactive or out-of-grid cells and foreign geometry cannot become histories',async()=>{
  for(const index of [-1,1,8,1.5])await assert.rejects(loadSpatialCellHistory({...fixture(),index}))
  const f=fixture();f.run.artifacts[0].sha256='c'.repeat(64);await assert.rejects(loadSpatialCellHistory(f),/EGRID不同/);assert.equal(f.requests.length,0)
  await assert.rejects(loadSpatialCellHistory({...fixture(),keyword:'UNKNOWN'}),/仅支持/)
})
test('native elapsed time, SEQNUM and E300 solver/units are verified independently per report',async()=>{
  for(const [keyword,index,value,type] of [['DOUBHEAD',0,366,'DOUB'],['SEQNUM',0,7,'INTE'],['INTEHEAD',2,1,'INTE'],['INTEHEAD',94,100,'INTE']]){
    const f=fixture(),field=f.run.result.fieldIndex.files[1].fields.find(v=>v.keyword===keyword&&v.timeStep===2),offset=spatialElementOffset(field,index,100000),view=new DataView(f.buffers.get('2:21'))
    if(type==='DOUB')view.setFloat64(offset,value);else view.setInt32(offset,value)
    await assert.rejects(loadSpatialCellHistory(f),/实际时间.*SEQNUM/)
  }
})
test('only common real reports are retained; absent reports do not receive fabricated points',async()=>{
  const f=fixture();f.baseline.result.fieldIndex.files[1].fields=f.baseline.result.fieldIndex.files[1].fields.filter(v=>!(v.keyword==='TEMP'&&v.timeStep===5))
  assert.deepEqual((await loadSpatialCellHistory(f)).rows.map(r=>r.report),[2,9])
})
test('late or truncated transports cannot return a current complete history',async()=>{
  const f=fixture();assert.equal(await loadSpatialCellHistory({...f,isCurrent:()=>false}),null);assert.equal(f.requests.length,0)
  await assert.rejects(loadSpatialCellHistory({...fixture(),request:async()=>new Blob([new Uint8Array(1)])}),/长度/)
  let active=true;const g=fixture(),request=async(...args)=>{const r=await g.request(...args);if(g.requests.length>3)active=false;return r}
  assert.equal(await loadSpatialCellHistory({...g,request,isCurrent:()=>active}),null)
})
