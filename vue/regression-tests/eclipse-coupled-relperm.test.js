import assert from 'node:assert/strict'
import test from 'node:test'
import {readFileSync} from 'node:fs'
import {buildCoupledRelperm,validCoupledRelperm,coupledRelpermResponse,coupledRelpermContext} from '../src/views/SoftwareIntegration/eclipseCoupledRelperm.js'
import {batchChoices,buildBatchCase,buildBatchRequest} from '../src/views/SoftwareIntegration/eclipseBatch.js'
const root=new URL('../tests/fixtures/eclipseCoupledRelperm/',import.meta.url),load=name=>JSON.parse(readFileSync(new URL(name+'.json',root),'utf8'))
const inspection=load('inspection'),native=load('native-acceptance'),run={id:1,modelVersionId:2,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null,result:{schemaVersion:'eclipse-summary-result/1',modelKind:'eclipse_100',eclEnd:{problems:0,errors:0,bugs:0}}}
test('two-table three-phase scenarios carry all 40 source rows and linked oil endpoints',()=>{
  for(const field of ['WATER_KR','GAS_KR','OIL_KR']){
    const p=buildCoupledRelperm({run,inspection,field,multiplier:.5});assert.equal(validCoupledRelperm(p),true);assert.deepEqual(p.tables,inspection.coupledRelpermContext.tables);assert.equal(p.sourceSha256,native.sourceSha256)
    for(const change of [{field:'KROW_ONLY'},{unit:1},{unit:'mD'},{multiplier:3},{multiplier:'0.5'},{tables:p.tables.slice(0,1)},{extra:1}])assert.equal(validCoupledRelperm({...p,...change}),false)
    const tables=structuredClone(p.tables);tables[1].rows[0].values[2]='.9';assert.equal(validCoupledRelperm({...p,tables}),false)
  }
  const bad=structuredClone(inspection);bad.coupledRelpermContext.tables[0].rows[1].values[0]='0.15';assert.equal(coupledRelpermContext(bad),null)
})
test('full native outputs preserve geology and have physical responses for water gas and linked oil',()=>{
  assert.equal(native.nativeVerified,true);assert.equal(native.originalPhysicalInputsAndFirst100DayControlsPreserved,true)
  for(const label of ['water','gas','oil']){const response=coupledRelpermResponse({series:native[label].series},{series:native.baseline.series});assert.equal(response.available,true);assert.ok(response.changedCount>0);const a=native.baseline.series.find(s=>s.keyword==='WBHP'),b=native[label].series.find(s=>s.keyword==='WBHP'),union=new Set([...a.points,...b.points].map(p=>p.timeDays)),common=a.points.filter(p=>b.points.some(q=>q.timeDays===p.timeDays)).length;assert.equal(response.rows.length,union.size*260);assert.equal(response.unmatchedCount,(union.size-common)*260);assert.ok(response.rows.filter(r=>r.difference!=null).every(r=>r.baseline!=null&&r.current!=null));assert.deepEqual(native[label].arrays,native.baseline.arrays);assert.deepEqual(native[label].grid,native.baseline.grid);assert.equal(response.rows.find(r=>r.well==='INJE1'&&r.keyword==='WBHP'&&r.timeDays===100).difference,0);assert.notEqual(response.rows.find(r=>r.well==='INJE1'&&r.keyword==='WWIT'&&r.timeDays===100).difference,0)}
})
test('all three table-family choices create independent same-baseline batch cases',()=>{
  assert.deepEqual(batchChoices(inspection).map(c=>c.kind),['PVTO','PVTO_BO','WATER_KR','GAS_KR','OIL_KR'])
  const cases=['WATER_KR','GAS_KR','OIL_KR'].map(kind=>buildBatchCase({run,inspection,kind,value:.5,name:kind})),p=buildBatchRequest({run,name:'岩心相渗比较',cases,requestKey:'12345678-1234-1234-1234-123456789abc'});assert.equal(p.cases.length,3);assert.ok(p.cases.every(c=>c.parameters.baselineRunId===run.id));assert.deepEqual(p.cases[0].parameters.tables,p.cases[2].parameters.tables)
})
