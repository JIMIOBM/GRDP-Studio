import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { buildCompletionGeometry, completionGeometryRecords, completionGeometryUnit, validCompletionGeometry, completionGeometryResponse } from '../src/views/SoftwareIntegration/eclipseCompletionGeometry.js'
const inspection = JSON.parse(readFileSync(new URL('../../backend/src/test/resources/eclipse-completion-geometry-inspection.json',import.meta.url),'utf8'))
const run = { id:466,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null }
const record = inspection.scheduleMetadata.records.find(r => r.values[0] === 'P       ')
const build = (field='DIAMETER',targetValue=.2, extra={}) => buildCompletionGeometry({run,inspection,record,field,targetValue,...extra})

test('geometry: original padded well and every source token bind the three explicit physical inputs', () => {
  assert.deepEqual(completionGeometryRecords(inspection),[record])
  for (const [field,target,original,unit] of [['DIAMETER',.2,1,'m'],['KH',500,1000,'mD.m'],['SKIN',2,0,'1']]) {
    const p=build(field,target); assert.equal(validCompletionGeometry(p),true); assert.equal(p.well,'P       ')
    assert.equal(p.originalValue,original); assert.equal(p.unit,unit); assert.deepEqual(p.recordValues,record.values)
    assert.notEqual(p.recordValues,record.values); assert.equal(p.lineNumber,157)
  }
  assert.equal(completionGeometryUnit('FIELD','DIAMETER'),'ft'); assert.equal(completionGeometryUnit('LAB','KH'),'mD.cm')
  assert.equal(completionGeometryUnit('PVT-M','KH'),'mD.m'); assert.equal(completionGeometryUnit('unknown','SKIN'),'')
})
test('geometry: wrong JSON types, units, same values, negative skin and positive CF reject', () => {
  for(const [field,target] of [['DIAMETER',0],['DIAMETER',3],['DIAMETER','0.2'],['KH',0],['KH',Infinity],['SKIN',-1],['SKIN',101],['DIAMETER',1]]) assert.throws(()=>build(field,target))
  const p=build(); assert.equal(validCompletionGeometry({...p,unit:'mD'}),false)
  assert.equal(validCompletionGeometry({...p,unknown:1}),false); assert.equal(validCompletionGeometry({...p,well:'P'}),false)
  const values=[...p.recordValues]; values[7]='1.5'; assert.equal(validCompletionGeometry({...p,recordValues:values}),false)
  assert.throws(()=>build('DIAMETER',.2,{run:{...run,parameters:{}}}))
})
test('geometry: source mismatch, implicit KH, multiple connections and later overrides cannot be offered', () => {
  const mutations=[v=>v.scheduleMetadata.records.find(r=>r.lineNumber===157).values[9]='1*',
    v=>v.scheduleMetadata.records.push({...record,lineNumber:158}),v=>v.scheduleTimeline[0].lineNumber=150,
    v=>v.scheduleMetadata.records[0].values[0]='*',v=>v.packageFiles.push({...v.packageFiles[0]}),
    v=>v.dimensions.nx=0,v=>v.unitSystem='UNKNOWN',v=>v.scheduleMetadata.records.find(r=>r.lineNumber===157).values[7]='3']
  for(const mutate of mutations){const v=structuredClone(inspection);mutate(v);assert.deepEqual(completionGeometryRecords(v),[]);assert.throws(()=>build('DIAMETER',.2,{inspection:v}))}
  assert.throws(()=>build('DIAMETER',.2,{record:{...record,lineNumber:158}}))
})
const summary = (points,unit='BARSA',well='P') => ({series:[{keyword:'WBHP',objectName:well,unit,points}]})
test('geometry: target well trimmed only for summary identity, exact clocks/units and missing points retained', () => {
  const old=summary([{timeDays:0,value:10},{timeDays:10,value:9}]), next=summary([{timeDays:0,value:10},{timeDays:20,value:8}])
  const result=completionGeometryResponse(next,old,'P       ');assert.equal(result.available,true);assert.equal(result.unmatchedCount,2)
  assert.deepEqual(result.rows.map(r=>r.difference),[0,null,null])
  assert.equal(completionGeometryResponse(summary([{timeDays:0,value:8}],'PSIA'),old,'P').available,false)
  assert.equal(completionGeometryResponse(summary([{timeDays:0,value:8}],''),old,'P').available,false)
  assert.equal(completionGeometryResponse(next,summary([{timeDays:0,value:9}],'BARSA','I'),'I').available,true)
  assert.equal(completionGeometryResponse(next,old,'I').available,false)
})
