import assert from 'node:assert/strict'
import test from 'node:test'
import {readFileSync} from 'node:fs'
import {buildMultiFamilyII,validMultiFamilyII,multiFamilyIIContext,multiFamilyIIResponse} from '../src/views/SoftwareIntegration/eclipseMultiSatFamilyII.js'
import {batchChoices,buildBatchCase,buildBatchRequest} from '../src/views/SoftwareIntegration/eclipseBatch.js'
const root=new URL('../tests/fixtures/eclipseMultiSatFamilyII/',import.meta.url),load=name=>JSON.parse(readFileSync(new URL(name+'.json',root),'utf8'))
const inspection=load('inspection'),native=load('native-acceptance'),run={id:1,modelVersionId:2,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null,result:{schemaVersion:'eclipse-summary-result/1',modelKind:'eclipse_100',eclEnd:{problems:0,errors:0,bugs:0}}}
test('multi-region scenarios bind the complete chosen pair and reject invalid region or typed bounds',()=>{
  assert.equal(multiFamilyIIContext(inspection).regionCount,2)
  for(const field of ['WATER_KR','GAS_KR','OIL_KR']){
    const p=buildMultiFamilyII({run,inspection,regionNumber:2,field,multiplier:.5});assert.equal(validMultiFamilyII(p),true);assert.deepEqual(p.tables,inspection.multiSatRelpermContext.regions[1].tables);assert.equal(p.sourceSha256,native.sourceSha256)
    for(const change of [{regionNumber:0},{regionNumber:9},{regionNumber:'2'},{unit:1},{multiplier:3},{extra:1}])assert.equal(validMultiFamilyII({...p,...change}),false)
    assert.throws(()=>buildMultiFamilyII({run,inspection,regionNumber:3,field,multiplier:.5}),/SAT/)
  }
  for(const fault of ['count','cells','number','overlap']){const bad=structuredClone(inspection),c=bad.multiSatRelpermContext;if(fault==='count')c.regionCount=3;if(fault==='cells')c.regions[1].cellCount=4501;if(fault==='number')c.regions[1].number=1;if(fault==='overlap')c.regions[1].tables[0].rows[0].lineNumber=c.regions[0].tables[0].rows.at(-1).lineNumber;assert.equal(multiFamilyIIContext(bad),null)}
})
test('selected region has independent native water gas and linked oil responses with unchanged first tables and map',()=>{
  for(const key of ['nativeVerified','identicalTablesPreserveSingleRegionPhysics','firstRegionUnchanged','onlySelectedRegionColumnsChanged','regionMapUnchanged','oilColumnsLinked'])assert.equal(native[key],true)
  assert.deepEqual(native.cellsPerRegion,[6,7]);assert.deepEqual(native.activeCellsPerRegion,[native.baseline.actnum.slice(0,6).reduce((a,b)=>a+b,0),native.baseline.actnum.slice(6).reduce((a,b)=>a+b,0)]);assert.deepEqual(native.activeCellsPerRegion,[3,7]);assert.deepEqual(native.regionBoundsI,[[1,6],[7,13]])
  for(const label of ['water','gas','oil']){const response=multiFamilyIIResponse({series:native[label].series},{series:native.baseline.series});assert.equal(response.available,true);assert.ok(response.changedCount>0);assert.deepEqual(native[label].arrays,native.baseline.arrays);assert.deepEqual(native[label].actnum,native.baseline.actnum);assert.ok(response.rows.filter(r=>r.difference===null).every(r=>r.baseline===null||r.current===null))}
})
test('batch region choices are mandatory and distinct regions remain distinct independent cases',()=>{
  assert.deepEqual(batchChoices(inspection).map(c=>c.kind),['PVDG','PVDG_BG','FAMILY_II_WATER_KR','FAMILY_II_GAS_KR','FAMILY_II_OIL_KR'])
  const args={run,inspection,kind:'FAMILY_II_OIL_KR',value:.5,name:'岩性2'};assert.throws(()=>buildBatchCase(args),/SAT/)
  const cases=[1,2].map(regionNumber=>buildBatchCase({...args,regionNumber,name:'岩性'+regionNumber})),p=buildBatchRequest({run,name:'分区相渗',cases,requestKey:'12345678-1234-1234-1234-123456789abc'});assert.equal(p.cases.length,2);assert.deepEqual(cases.map(c=>c.parameters.regionNumber),[1,2]);assert.notDeepEqual(cases[0].parameters.tables,cases[1].parameters.tables)
})
