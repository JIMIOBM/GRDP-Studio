import assert from 'node:assert/strict'
import test from 'node:test'
import {readFileSync} from 'node:fs'
import {buildMultiWetGasPvt,validMultiWetGasPvt,multiWetGasPvtContext,multiWetGasChoices,multiWetGasPvtResponse} from '../src/views/SoftwareIntegration/eclipseMultiWetGasPvt.js'
import {batchChoices,buildBatchCase,buildBatchRequest} from '../src/views/SoftwareIntegration/eclipseBatch.js'
const root=new URL('../tests/fixtures/eclipseMultiWetGasPvt/',import.meta.url),load=name=>JSON.parse(readFileSync(new URL(name+'.json',root),'utf8'))
const inspection=load('inspection'),native=load('native-acceptance'),run={id:1,modelVersionId:2,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null,result:{schemaVersion:'eclipse-summary-result/1',modelKind:'eclipse_100',eclEnd:{problems:0,errors:0,bugs:0}}}
test('multi wet gas binds all selected branches and live oil branches with typed regional bounds',()=>{
  assert.equal(multiWetGasPvtContext(inspection).regionCount,2)
  for(const field of ['VISCOSITY','FORMATION_VOLUME_FACTOR']){
    const p=buildMultiWetGasPvt({run,inspection,regionNumber:2,field,multiplier:field==='VISCOSITY'?2:1.02});assert.equal(validMultiWetGasPvt(p),true);assert.deepEqual(p.records,inspection.multiWetGasPvtContext.regions[1].records);assert.deepEqual(p.oilTable,inspection.multiWetGasPvtContext.regions[1].oilTable);assert.equal(p.sourceSha256,native.sourceSha256)
    for(const change of [{regionNumber:0},{regionNumber:9},{regionNumber:'2'},{unit:1},{multiplier:101},{multiplier:1},{multiplier:0},{multiplier:'2'},{field:'RS'},{records:null},{records:[]},{records:'bad'},{extra:1}])assert.equal(validMultiWetGasPvt({...p,...change}),false)
    assert.throws(()=>buildMultiWetGasPvt({run,inspection,regionNumber:3,field,multiplier:2}),/PVTG/)
  }
  for(const fault of ['count','cells','number','overlap','header','lastBranch']){const bad=structuredClone(inspection),c=bad.multiWetGasPvtContext;if(fault==='count')c.regionCount=3;if(fault==='cells')c.regions[1].cellCount=82;if(fault==='number')c.regions[1].number=1;if(fault==='overlap')c.regions[1].records[0].rows[0].lineNumber=c.regions[0].records.at(-1).rows.at(-1).lineNumber;if(fault==='header')c.regions[1].oilTable.keywordLineNumber++;if(fault==='lastBranch')c.regions[1].records.at(-1).rows.splice(1);assert.equal(multiWetGasPvtContext(bad),null)}
})
test('selected wet gas has native responses and matched regional maps while preserving all source warnings',()=>{
  for(const k of ['nativeVerified','firstRegionUnchanged','onlySelectedRegionColumnsChanged','regionMapUnchanged','equilibrationSourcePreserved','auxiliaryPvtTablesDuplicated'])assert.equal(native[k],true)
  assert.deepEqual(native.cellsPerRegion,[81,81]);assert.deepEqual(native.activeCellsPerRegion,[81,81]);assert.equal(native.branchCountPerRegion,13);assert.equal(native.rowCountPerRegion,29)
  assert.equal(native.identicalTablesPreserveSingleRegionPhysics,false);assert.equal(native.singleRegionComparison.exactSeriesEqual,false);assert.equal(native.singleRegionComparison.allOutputTimesEqual,false);assert.equal(native.singleRegionComparison.seriesDifferences.find(v=>v.keyword==='WOPT').maximumAbsoluteDifferenceAtCommonTimes,.3125)
  const activity=native.baseline.actnum;assert.equal(activity.length,162);assert.ok(activity.every(v=>v===1));assert.deepEqual(native.baseline.pvtnum,[...Array(81).fill(2),...Array(81).fill(1)])
  for(const label of ['viscosity','volume']){const response=multiWetGasPvtResponse({series:native[label].series},{series:native.baseline.series});assert.equal(response.available,true);assert.ok(response.changedCount>0);assert.deepEqual(native[label].arrays,native.baseline.arrays);assert.deepEqual(native[label].actnum,native.baseline.actnum);assert.deepEqual(native[label].pvtnum,native.baseline.pvtnum);assert.equal(native[label].eclEnd.warnings,15);for(const k of ['problems','errors','bugs'])assert.equal(native[label].eclEnd[k],0)}
  const wrong=structuredClone(native.volume.series);wrong.find(s=>s.keyword==='WBHP').unit='PSIA';assert.equal(multiWetGasPvtResponse({series:wrong},{series:native.baseline.series}).available,false)
})
test('multi PVTG viscosity and Bg independent batch requests require the original PVT region',()=>{
  assert.deepEqual(batchChoices(inspection).map(c=>c.kind),Object.keys(multiWetGasChoices))
  const cases=Object.entries(multiWetGasChoices).map(([kind,c])=>{const a={run,inspection,kind,value:c.field==='VISCOSITY'?2:1.02,name:c.label};assert.throws(()=>buildBatchCase(a),/PVTG/);return buildBatchCase({...a,regionNumber:2})})
  const p=buildBatchRequest({run,name:'PVTG区2',cases,requestKey:'12345678-1234-1234-1234-123456789abc'});assert.equal(p.cases.length,2);assert.deepEqual(cases.map(c=>c.parameters.field),['VISCOSITY','FORMATION_VOLUME_FACTOR']);assert.ok(cases.every(c=>c.parameters.regionNumber===2))
})
