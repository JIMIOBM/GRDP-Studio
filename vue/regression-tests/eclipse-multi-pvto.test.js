import assert from 'node:assert/strict'
import test from 'node:test'
import {readFileSync} from 'node:fs'
import {buildMultiLiveOilPvt,validMultiLiveOilPvt,multiLiveOilPvtContext,multiLiveOilChoices,multiLiveOilPvtResponse} from '../src/views/SoftwareIntegration/eclipseMultiLiveOilPvt.js'
import {batchChoices,buildBatchCase,buildBatchRequest} from '../src/views/SoftwareIntegration/eclipseBatch.js'
const root=new URL('../tests/fixtures/eclipseMultiLiveOilPvt/',import.meta.url),load=name=>JSON.parse(readFileSync(new URL(name+'.json',root),'utf8'))
const inspection=load('inspection'),native=load('native-acceptance'),run={id:1,modelVersionId:2,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null,result:{schemaVersion:'eclipse-summary-result/1',modelKind:'eclipse_100',eclEnd:{problems:0,errors:0,bugs:0}}}
test('multi live oil binds all selected branches and dry gas rows with typed regional bounds',()=>{
  assert.equal(multiLiveOilPvtContext(inspection).regionCount,2)
  for(const field of ['VISCOSITY','FORMATION_VOLUME_FACTOR']){
    const p=buildMultiLiveOilPvt({run,inspection,regionNumber:2,field,multiplier:field==='VISCOSITY'?2:1.02});assert.equal(validMultiLiveOilPvt(p),true);assert.deepEqual(p.records,inspection.multiLiveOilPvtContext.regions[1].records);assert.deepEqual(p.gasTable,inspection.multiLiveOilPvtContext.regions[1].gasTable);assert.equal(p.sourceSha256,native.sourceSha256)
    for(const change of [{regionNumber:0},{regionNumber:9},{regionNumber:'2'},{unit:1},{multiplier:101},{multiplier:1},{multiplier:0},{multiplier:'2'},{field:'RS'},{extra:1}])assert.equal(validMultiLiveOilPvt({...p,...change}),false)
    assert.throws(()=>buildMultiLiveOilPvt({run,inspection,regionNumber:3,field,multiplier:2}),/PVTO/)
  }
  for(const fault of ['count','cells','number','overlap','header','lastBranch']){const bad=structuredClone(inspection),c=bad.multiLiveOilPvtContext;if(fault==='count')c.regionCount=3;if(fault==='cells')c.regions[1].cellCount=151;if(fault==='number')c.regions[1].number=1;if(fault==='overlap')c.regions[1].records[0].rows[0].lineNumber=c.regions[0].records.at(-1).rows.at(-1).lineNumber;if(fault==='header')c.regions[1].gasTable.keywordLineNumber++;if(fault==='lastBranch')c.regions[1].records.at(-1).rows.pop();assert.equal(multiLiveOilPvtContext(bad),null)}
})
test('selected live oil has native responses and matched regional maps while preserving all source warnings',()=>{
  for(const k of ['nativeVerified','identicalTablesPreserveSingleRegionPhysics','firstRegionUnchanged','onlySelectedRegionColumnsChanged','regionMapUnchanged','equilibrationSourcePreserved','auxiliaryPvtTablesDuplicated'])assert.equal(native[k],true)
  assert.deepEqual(native.cellsPerRegion,[150,150]);assert.deepEqual(native.activeCellsPerRegion,[150,150]);assert.equal(native.branchCountPerRegion,9);assert.equal(native.rowCountPerRegion,12)
  const activity=native.baseline.actnum;assert.equal(activity.length,300);assert.ok(activity.every(v=>v===1));assert.deepEqual(native.baseline.pvtnum,Array.from({length:30},()=>[...Array(5).fill(1),...Array(5).fill(2)]).flat())
  for(const label of ['viscosity','volume']){const response=multiLiveOilPvtResponse({series:native[label].series},{series:native.baseline.series});assert.equal(response.available,true);assert.ok(response.changedCount>0);assert.deepEqual(native[label].arrays,native.baseline.arrays);assert.deepEqual(native[label].actnum,native.baseline.actnum);assert.deepEqual(native[label].pvtnum,native.baseline.pvtnum);assert.equal(native[label].eclEnd.warnings,31);for(const k of ['problems','errors','bugs'])assert.equal(native[label].eclEnd[k],0)}
  const wrong=structuredClone(native.volume.series);wrong.find(s=>s.keyword==='WBHP').unit='BARSA';assert.equal(multiLiveOilPvtResponse({series:wrong},{series:native.baseline.series}).available,false)
})
test('multi PVTO viscosity and Bo independent batch requests require the original PVT region',()=>{
  assert.deepEqual(batchChoices(inspection).map(c=>c.kind),Object.keys(multiLiveOilChoices))
  const cases=Object.entries(multiLiveOilChoices).map(([kind,c])=>{const a={run,inspection,kind,value:c.field==='VISCOSITY'?2:1.02,name:c.label};assert.throws(()=>buildBatchCase(a),/PVTO/);return buildBatchCase({...a,regionNumber:2})})
  const p=buildBatchRequest({run,name:'PVTO区2',cases,requestKey:'12345678-1234-1234-1234-123456789abc'});assert.equal(p.cases.length,2);assert.deepEqual(cases.map(c=>c.parameters.field),['VISCOSITY','FORMATION_VOLUME_FACTOR']);assert.ok(cases.every(c=>c.parameters.regionNumber===2))
})
