import assert from 'node:assert/strict'
import test from 'node:test'
import {readFileSync} from 'node:fs'
import {buildMultiPvt,validMultiPvt,multiPvtContext,multiPvtChoices,multiPvtResponse} from '../src/views/SoftwareIntegration/eclipseMultiPvt.js'
import {batchChoices,buildBatchCase,buildBatchRequest} from '../src/views/SoftwareIntegration/eclipseBatch.js'
const root=new URL('../tests/fixtures/eclipseMultiPvt/',import.meta.url),load=name=>JSON.parse(readFileSync(new URL(name+'.json',root),'utf8'))
const inspection=load('inspection'),native=load('native-acceptance'),run={id:1,modelVersionId:2,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null,result:{schemaVersion:'eclipse-summary-result/1',modelKind:'eclipse_100',eclEnd:{problems:0,errors:0,bugs:0}}}
test('multi PVT binds both complete oil gas tables and rejects unsupported region field units and bounds',()=>{
  assert.equal(multiPvtContext(inspection).regionCount,2)
  for(const c of Object.values(multiPvtChoices)){
    const p=buildMultiPvt({run,inspection,regionNumber:2,...c,multiplier:c.field==='VISCOSITY'?2:1.02});assert.equal(validMultiPvt(p),true);assert.deepEqual(p.tables,inspection.multiPvtContext.regions[1].tables);assert.equal(p.sourceSha256,native.sourceSha256)
    for(const change of [{regionNumber:0},{regionNumber:9},{regionNumber:'2'},{unit:1},{multiplier:101},{multiplier:1},{multiplier:0},{multiplier:'2'},{keyword:'PVTO'},{field:'PRESSURE'},{extra:1}])assert.equal(validMultiPvt({...p,...change}),false)
    assert.throws(()=>buildMultiPvt({run,inspection,regionNumber:3,...c,multiplier:2}),/PVT/)
  }
  for(const fault of ['count','cells','number','overlap','header','order']){const bad=structuredClone(inspection),c=bad.multiPvtContext;if(fault==='count')c.regionCount=3;if(fault==='cells')c.regions[1].cellCount=8;if(fault==='number')c.regions[1].number=1;if(fault==='overlap')c.regions[1].tables[0].rows[0].lineNumber=c.regions[0].tables[0].rows.at(-1).lineNumber;if(fault==='header')c.regions[1].tables[1].keywordLineNumber++;if(fault==='order')c.regions[1].tables.reverse();assert.equal(multiPvtContext(bad),null)}
})
test('four selected region responses retain the map geology and ACTNUM regional activity',()=>{
  for(const k of ['nativeVerified','identicalTablesPreserveSingleRegionPhysics','firstRegionUnchanged','onlySelectedRegionColumnsChanged','regionMapUnchanged','auxiliaryPvtTablesDuplicated'])assert.equal(native[k],true)
  assert.deepEqual(native.cellsPerRegion,[6,7]);assert.deepEqual(native.activeCellsPerRegion,[native.baseline.actnum.slice(0,6).reduce((a,b)=>a+b,0),native.baseline.actnum.slice(6).reduce((a,b)=>a+b,0)]);assert.deepEqual(native.activeCellsPerRegion,[3,7])
  for(const label of ['oil-mu','oil-bo','gas-mu','gas-bg']){const response=multiPvtResponse({series:native[label].series},{series:native.baseline.series});assert.equal(response.available,true);assert.ok(response.changedCount>0);assert.deepEqual(native[label].arrays,native.baseline.arrays);assert.deepEqual(native[label].actnum,native.baseline.actnum);assert.equal(native[label].eclEnd.warnings,1);for(const k of ['problems','errors','bugs'])assert.equal(native[label].eclEnd[k],0)}
  const wrong=structuredClone(native['gas-bg'].series);wrong.find(s=>s.keyword==='WBHP').unit='BARSA';assert.equal(multiPvtResponse({series:wrong},{series:native.baseline.series}).available,false)
})
test('four independent PVT batch choices require an explicit region and retain each fluid field',()=>{
  assert.deepEqual(batchChoices(inspection).map(c=>c.kind),Object.keys(multiPvtChoices))
  const cases=Object.entries(multiPvtChoices).map(([kind,c])=>{const a={run,inspection,kind,value:c.field==='VISCOSITY'?2:1.02,name:c.label};assert.throws(()=>buildBatchCase(a),/PVT/);return buildBatchCase({...a,regionNumber:2})})
  const p=buildBatchRequest({run,name:'PVT区2',cases,requestKey:'12345678-1234-1234-1234-123456789abc'});assert.equal(p.cases.length,4);assert.deepEqual(cases.map(c=>[c.parameters.keyword,c.parameters.field]),Object.values(multiPvtChoices).map(c=>[c.keyword,c.field]))
})
