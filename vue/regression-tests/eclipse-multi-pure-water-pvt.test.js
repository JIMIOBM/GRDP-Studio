import assert from 'node:assert/strict'
import test from 'node:test'
import {readFileSync} from 'node:fs'
import {multiWaterPvtContext,buildMultiWaterPvt,validMultiWaterPvt,multiWaterPvtResponse} from '../src/views/SoftwareIntegration/eclipseMultiWaterPvt.js'
import {batchChoices,buildBatchCase,buildBatchRequest} from '../src/views/SoftwareIntegration/eclipseBatch.js'
const root=new URL('../tests/fixtures/eclipseMultiPureWaterPvt/',import.meta.url),load=n=>JSON.parse(readFileSync(new URL(n+'.json',root),'utf8'))
const inspection=load('inspection'),native=load('native-acceptance'),fields=['REFERENCE_PRESSURE','FORMATION_VOLUME_FACTOR','COMPRESSIBILITY','VISCOSITY','VISCOSIBILITY'],targets=[4000,1.02,6.06e-6,1,1e-6]
const run={id:1,modelVersionId:2,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null,result:{schemaVersion:'eclipse-summary-result/1',modelKind:'eclipse_100',eclEnd:{problems:0,errors:0,bugs:0}}}
test('pure water binds five absolute regional targets with original METRIC units without oil or gas metadata',()=>{
  assert.equal(inspection.multiPvtContext,undefined);assert.deepEqual(multiWaterPvtContext(inspection).regions.map(r=>r.cellCount),[105,120])
  for(const [k,field] of fields.entries()){const p=buildMultiWaterPvt({run,inspection,regionNumber:2,field,targetValue:targets[k]});assert.equal(validMultiWaterPvt(p),true);assert.deepEqual(p.recordValues,inspection.multiWaterPvtContext.regions[1].recordValues);assert.equal(p.sourceSha256,native.sourceSha256)}
  assert.deepEqual(batchChoices(inspection).map(c=>c.unit),['barsa','rm3/sm3','1/bar','cP','1/bar'])
  const cases=fields.map((field,k)=>buildBatchCase({run,inspection,kind:'REGION_PVTW_'+field,value:targets[k],regionNumber:2,name:field}));assert.equal(buildBatchRequest({run,name:'纯水PVT2',cases,requestKey:'12345678-1234-1234-1234-123456789abc'}).cases.length,5)
})
test('pure water requires full grid region counts, unique phase and explicit complete records',()=>{
  for(const fault of ['count','cells','phase','number','default','missing','source']){const bad=structuredClone(inspection),c=bad.multiWaterPvtContext;if(fault==='count')c.regionCount=3;if(fault==='cells')c.regions[1].cellCount=119;if(fault==='phase')bad.phases.push('GAS');if(fault==='number')c.regions[1].number=1;if(fault==='default')c.regions[1].recordValues[4]='1*';if(fault==='missing')c.regions[1].recordValues.pop();if(fault==='source')c.sourceFile='OTHER.DATA';assert.equal(multiWaterPvtContext(bad),null)}
})
test('native pure water responses preserve controlled water rate and cumulative while selected region changes pressure',()=>{
  assert.equal(native.nativeVerified,true);assert.equal(native.physicalEquivalenceVerified,true)
  for(const label of ['pressure','volume','compressibility','viscosity','viscosibility']){assert.deepEqual(native[label].arrays,native.baseline.arrays);assert.deepEqual(native[label].pvtnum,native.baseline.pvtnum);assert.deepEqual(native.baseline.pvtnum,[...Array(15)].flatMap(()=>[...Array(7).fill(1),...Array(8).fill(2)]));assert.ok(multiWaterPvtResponse({series:native[label].series},{series:native.baseline.series}).changedCount>0);for(const k of ['WWPR','WWPT','WSTAT'])assert.deepEqual(native[label].series.filter(s=>s.keyword===k),native.baseline.series.filter(s=>s.keyword===k))}
})
