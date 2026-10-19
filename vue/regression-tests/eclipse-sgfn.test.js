import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
import { buildRelperm, validRelperm, relpermResponse } from '../src/views/SoftwareIntegration/eclipseRelperm.js'
import { batchChoices, buildBatchCase, buildBatchRequest } from '../src/views/SoftwareIntegration/eclipseBatch.js'
const root=new URL('../tests/fixtures/eclipseSgfn/',import.meta.url),load=name=>JSON.parse(readFileSync(new URL(name+'.json',root),'utf8'))
const inspection=load('inspection'),native=load('native-acceptance'),run={id:1,modelVersionId:2,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null,result:{schemaVersion:'eclipse-summary-result/1',modelKind:'eclipse_100',eclEnd:{problems:0,errors:0,bugs:0}}}
test('gas relative permeability uses separate version and complete original saturation and capillary tokens',()=>{
  const p=buildRelperm({run,inspection,keyword:'SGFN',multiplier:.5})
  assert.ok(validRelperm(p));assert.equal(p.schemaVersion,'eclipse-relperm-parameters/2');assert.equal(p.rows.length,13)
  assert.equal(p.unit,'1');assert.equal(p.sourceSha256,native.sourceSha256);assert.deepEqual(p.rows,inspection.relpermContext.tables[1].rows)
  for(const change of [{schemaVersion:'eclipse-relperm-parameters/1'},{keyword:'SWFN'},{multiplier:2},{multiplier:'0.5'},{unit:'mD'},{extra:1}])assert.equal(validRelperm({...p,...change}),false)
  for(const [row,column,value] of [[1,2,'-1'],[2,0,'.04'],[0,1,'.01'],[1,1,'1*']]){const rows=structuredClone(p.rows);rows[row].values[column]=value;assert.equal(validRelperm({...p,rows}),false)}
})
test('gas injection response uses independent native pressure rate and cumulatives while production pressure remains constrained',()=>{
  assert.equal(native.nativeVerified,true);const response=relpermResponse({series:native.target.series},{series:native.baseline.series})
  assert.equal(response.available,true);assert.equal(response.rows.length,231)
  assert.equal(response.rows.find(r=>r.keyword==='WBHP'&&r.well==='I1'&&r.timeDays===100).current,3114.256591796875)
  assert.equal(response.rows.find(r=>r.keyword==='WBHP'&&r.well==='P1'&&r.timeDays===100).difference,0)
  assert.equal(response.rows.find(r=>r.keyword==='WGIT'&&r.timeDays===100).current,140881.484375)
  assert.equal(response.rows.find(r=>r.keyword==='WGPR'&&r.timeDays===100).current,0)
  assert.deepEqual(native.target.arrays,native.baseline.arrays)
})
test('gas multiplier cases bind independent original baseline and retain existing water and paired oil choices',()=>{
  assert.deepEqual(batchChoices(inspection).map(c=>c.kind),['PVDO','PVDO_BO','PVDG','PVDG_BG','SWFN','SGFN','SOF3'])
  const cases=[.5,.75].map(value=>buildBatchCase({run,inspection,kind:'SGFN',value,name:'Krg '+value})),p=buildBatchRequest({run,name:'注气相渗',cases,requestKey:'12345678-1234-1234-1234-123456789abc'})
  assert.ok(cases.every(c=>c.parameters.schemaVersion==='eclipse-relperm-parameters/2'));assert.deepEqual(p.cases[0].parameters.rows,p.cases[1].parameters.rows)
})
