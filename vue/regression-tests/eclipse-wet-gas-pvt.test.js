import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
import { buildWetGasPvt, validWetGasPvt, wetGasPvtRecord, wetGasPvtResponse, wetGasRvUnit } from '../src/views/SoftwareIntegration/eclipseWetGasPvt.js'
import { batchChoices, buildBatchCase, buildBatchRequest } from '../src/views/SoftwareIntegration/eclipseBatch.js'
const root=new URL('../tests/fixtures/eclipseWetGasPvt/',import.meta.url),load=name=>JSON.parse(readFileSync(new URL(name+'.json',root),'utf8'))
const inspection=load('inspection'),native=load('native-acceptance'),run={id:1,modelVersionId:2,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null,result:{schemaVersion:'eclipse-summary-result/1',modelKind:'eclipse_100',eclEnd:{problems:0,errors:0,bugs:0}}}

test('wet gas binds all pressure branches and original saturated and undersaturated tokens',()=>{
  for(const field of ['VISCOSITY','FORMATION_VOLUME_FACTOR']){
    const p=buildWetGasPvt({run,inspection,multiplier:field==='VISCOSITY'?2:1.02,field})
    assert.ok(validWetGasPvt(p));assert.equal(p.records.length,13);assert.equal(p.records.flatMap(r=>r.rows).length,29)
    assert.deepEqual(p.records,inspection.wetGasPvtContext.records);assert.notEqual(p.records,inspection.wetGasPvtContext.records)
    assert.equal(p.sourceSha256,native.sourceSha256);assert.equal(p.unit,field==='VISCOSITY'?'cP':'rm3/sm3')
  }
  assert.equal(wetGasRvUnit('FIELD'),'stb/Mscf');assert.equal(wetGasRvUnit('METRIC'),'sm3/sm3')
})
test('pressure ordering decreasing Rv highest pressure undersaturation and typed target limits reject unsafe requests',()=>{
  const p=buildWetGasPvt({run,inspection,multiplier:2})
  for(const change of [{multiplier:1},{multiplier:'2'},{multiplier:101},{field:'PRESSURE'},{unit:'Pa.s'},{extra:1}])assert.equal(validWetGasPvt({...p,...change}),false)
  for(const change of [records=>records.at(-1).rows.splice(1),records=>records[1].rows[0].values[0]='30',records=>records[0].rows[1].values[0]='.000132',records=>records.at(-1).rows[1].values[1]='-1',records=>records.at(-1).rows[1].values[2]='1*',records=>records[0].extra=1]){
    const records=structuredClone(p.records);change(records);assert.equal(validWetGasPvt({...p,records}),false)
  }
  const invalid=structuredClone(inspection);invalid.wetGasPvtContext.records.at(-1).rows.splice(1);assert.equal(wetGasPvtRecord(invalid),null)
  assert.throws(()=>buildWetGasPvt({run:{...run,resultExpired:true},inspection,multiplier:2}))
  const records=structuredClone(p.records);records[0].rows[0].values[2]='100000'
  assert.equal(validWetGasPvt({...p,field:'FORMATION_VOLUME_FACTOR',unit:'rm3/sm3',multiplier:1.02,records}),false)
})
test('official wet gas permits both directions of Bg and viscosity versus Rv and retains fixed gas production',()=>{
  assert.equal(native.nativeVerified,true)
  for(const [label,pressure] of [['viscosity',252.50262451171875],['volume',253.55612182617188]]){
    const response=wetGasPvtResponse({series:native[label].series},{series:native.baseline.series});assert.equal(response.available,true)
    assert.equal(response.rows.find(r=>r.keyword==='WBHP'&&r.timeDays===2000).current,pressure)
    assert.equal(response.rows.find(r=>r.keyword==='WGPR'&&r.timeDays===2000).current,500000)
    assert.equal(response.rows.find(r=>r.keyword==='WGPT'&&r.timeDays===2000).current,1000000000)
    assert.deepEqual(native[label].arrays,native.baseline.arrays)
  }
})
test('wet gas Bg and viscosity form independent batch children from one baseline',()=>{
  assert.deepEqual(batchChoices(inspection).map(c=>c.kind),['PVTG','PVTG_BG'])
  const cases=['PVTG','PVTG_BG'].map(kind=>buildBatchCase({run,inspection,kind,value:kind==='PVTG'?2:1.02,name:kind}))
  const request=buildBatchRequest({run,name:'湿气物性',cases,requestKey:'12345678-1234-1234-1234-123456789abc'})
  assert.equal(request.cases[0].parameters.field,'VISCOSITY');assert.equal(request.cases[1].parameters.field,'FORMATION_VOLUME_FACTOR')
  assert.deepEqual(cases[0].parameters.records,cases[1].parameters.records)
})
