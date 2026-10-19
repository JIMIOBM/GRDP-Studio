import assert from 'node:assert/strict'
import test from 'node:test'
import {readFileSync} from 'node:fs'
import {multiWaterPvtContext,buildMultiWaterPvt,validMultiWaterPvt,multiWaterPvtResponse} from '../src/views/SoftwareIntegration/eclipseMultiWaterPvt.js'
import {batchChoices,buildBatchCase,buildBatchRequest} from '../src/views/SoftwareIntegration/eclipseBatch.js'
const root=new URL('../tests/fixtures/eclipseMultiWaterPvt/',import.meta.url),load=n=>JSON.parse(readFileSync(new URL(n+'.json',root),'utf8'))
const inspection=load('inspection'),native=load('native-acceptance'),fields=['REFERENCE_PRESSURE','FORMATION_VOLUME_FACTOR','COMPRESSIBILITY','VISCOSITY','VISCOSIBILITY'],targets=[4000,1.0506,6e-5,.8,1e-6]
const run={id:1,modelVersionId:2,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null,result:{schemaVersion:'eclipse-summary-result/1',modelKind:'eclipse_100',eclEnd:{problems:0,errors:0,bugs:0}}}
test('all five absolute water targets bind complete selected records with units and mandatory region',()=>{
  assert.equal(multiWaterPvtContext(inspection).regionCount,2)
  for(const [k,field] of fields.entries()){
    const args={run,inspection,field,targetValue:targets[k]};assert.throws(()=>buildMultiWaterPvt(args),/PVT区/)
    const p=buildMultiWaterPvt({...args,regionNumber:2});assert.equal(validMultiWaterPvt(p),true);assert.deepEqual(p.recordValues,inspection.multiWaterPvtContext.regions[1].recordValues);assert.equal(p.sourceSha256,native.sourceSha256)
    for(const change of [{regionNumber:'2'},{regionNumber:9},{field:'RS'},{unit:1},{targetValue:'2'},{targetValue:p.originalValue},{recordValues:['1*']},{extra:1}])assert.equal(validMultiWaterPvt({...p,...change}),false)
    assert.throws(()=>buildMultiWaterPvt({...args,regionNumber:2,run:{...run,resultExpired:true}}),/未过期/)
  }
})
test('water context rejects default, missing, excessive, overlapping and wrong region source records',()=>{
  for(const fault of ['default','missing','bounds','line','cells','source','count']){const bad=structuredClone(inspection),c=bad.multiWaterPvtContext,r=c.regions[1];if(fault==='default')r.recordValues[4]='1*';if(fault==='missing')r.recordValues.pop();if(fault==='bounds')r.recordValues[2]='.001';if(fault==='line')r.lineNumber=bad.multiPvtContext.regions[1].tables[1].rows[0].lineNumber;if(fault==='cells')r.cellCount=8;if(fault==='source')c.sourceFile='OTHER.DATA';if(fault==='count')c.regionCount=3;assert.equal(multiWaterPvtContext(bad),null)}
})
test('five regional water batch cases use absolute values alongside oil and gas choices',()=>{
  const choices=batchChoices(inspection).filter(c=>c.kind.startsWith('REGION_PVTW_'));assert.deepEqual(choices.map(c=>c.kind),fields.map(f=>'REGION_PVTW_'+f));assert.deepEqual(choices.map(c=>c.unit),['psia','rb/stb','1/psi','cP','1/psi'])
  const cases=fields.map((field,k)=>buildBatchCase({run,inspection,kind:'REGION_PVTW_'+field,value:targets[k],regionNumber:2,name:field}));assert.deepEqual(cases.map(c=>c.parameters.targetValue),targets);assert.equal(buildBatchRequest({run,name:'水区2',cases,requestKey:'12345678-1234-1234-1234-123456789abc'}).cases.length,5)
})
test('native water targets produce responses with preserved geology and one source warning',()=>{
  assert.equal(native.nativeVerified,true);assert.equal(native.explicitZeroViscosibilityPreservesDefault,true)
  for(const label of ['pressure','volume','compressibility','viscosity','viscosibility']){assert.deepEqual(native[label].arrays,native.baseline.arrays);assert.deepEqual(native[label].actnum,native.baseline.actnum);assert.equal(native[label].eclEnd.warnings,1);for(const k of ['errors','problems','bugs'])assert.equal(native[label].eclEnd[k],0);assert.ok(multiWaterPvtResponse({series:native[label].series},{series:native.baseline.series}).changedCount>0)}
})
