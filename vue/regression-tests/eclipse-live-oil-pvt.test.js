import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
import { buildLiveOilPvt, validLiveOilPvt, liveOilPvtRecord, liveOilPvtResponse } from '../src/views/SoftwareIntegration/eclipseLiveOilPvt.js'
import { batchChoices, buildBatchCase, buildBatchRequest } from '../src/views/SoftwareIntegration/eclipseBatch.js'
const root=new URL('../tests/fixtures/eclipseLiveOilPvt/',import.meta.url),load=name=>JSON.parse(readFileSync(new URL(name+'.json',root),'utf8'))
const inspection=load('inspection'),native=load('native-acceptance'),run={id:1,modelVersionId:2,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null,result:{schemaVersion:'eclipse-summary-result/1',modelKind:'eclipse_100',eclEnd:{problems:0,errors:0,bugs:0}}}
test('live oil binds nine Rs branches and all twelve original row lexemes with source units',()=>{
  for(const field of ['VISCOSITY','FORMATION_VOLUME_FACTOR']){const p=buildLiveOilPvt({run,inspection,multiplier:field==='VISCOSITY'?2:1.02,field});assert.ok(validLiveOilPvt(p));assert.equal(p.records.length,9);assert.equal(p.records.flatMap(r=>r.rows).length,12);assert.deepEqual(p.records,inspection.liveOilPvtContext.records);assert.notEqual(p.records,inspection.liveOilPvtContext.records);assert.equal(p.sourceSha256,native.sourceSha256);assert.equal(p.unit,field==='VISCOSITY'?'cP':'rb/stb')}
})
test('highest Rs needs undersaturation and every branch retains typed physical ordering',()=>{
  const p=buildLiveOilPvt({run,inspection,multiplier:2})
  for(const change of [{multiplier:1},{multiplier:'2'},{multiplier:101},{field:'PRESSURE'},{unit:'Pa.s'},{extra:1}])assert.equal(validLiveOilPvt({...p,...change}),false)
  for(const change of [records=>records.at(-1).rows.pop(),records=>records[1].rows[0].values[0]='0.001',records=>records.at(-1).rows[1].values[1]='1.9',records=>records.at(-1).rows[1].values[2]='1*',records=>records[0].extra=1]){const records=structuredClone(p.records);change(records);assert.equal(validLiveOilPvt({...p,records}),false)}
  const invalid=structuredClone(inspection);invalid.liveOilPvtContext.records.at(-1).rows.pop();assert.equal(liveOilPvtRecord(invalid),null);assert.throws(()=>buildLiveOilPvt({run:{...run,resultExpired:true},inspection,multiplier:2}));assert.throws(()=>buildLiveOilPvt({run,inspection,multiplier:8,field:'FORMATION_VOLUME_FACTOR'}))
})
test('independent native oil gas cumulative and injection pressure responses retain pressure floor',()=>{
  assert.equal(native.nativeVerified,true)
  for(const [field,rate] of [['viscosity',10198.62890625],['volume',16015.4384765625]]){const response=liveOilPvtResponse({series:native[field].series},{series:native.baseline.series});assert.equal(response.available,true);assert.equal(response.rows.find(r=>r.keyword==='WOPR'&&r.timeDays===1200).current,rate);assert.equal(response.rows.find(r=>r.keyword==='WBHP'&&r.well==='PRODUCER'&&r.timeDays===1200).difference,0);assert.equal(response.rows.find(r=>r.keyword==='WGIT'&&r.timeDays===1200).current,120000000);assert.deepEqual(native[field].arrays,native.baseline.arrays)}
})
test('live oil viscosity and volume create separate cases from one original baseline',()=>{
  assert.deepEqual(batchChoices(inspection).map(c=>c.kind),['PVTO','PVTO_BO'])
  const cases=['PVTO','PVTO_BO'].map(kind=>buildBatchCase({run,inspection,kind,value:kind==='PVTO'?2:1.02,name:kind}));const request=buildBatchRequest({run,name:'含溶解气原油物性',cases,requestKey:'12345678-1234-1234-1234-123456789abc'});assert.equal(request.cases[0].parameters.field,'VISCOSITY');assert.equal(request.cases[1].parameters.field,'FORMATION_VOLUME_FACTOR');assert.deepEqual(cases[0].parameters.records,cases[1].parameters.records)
})
