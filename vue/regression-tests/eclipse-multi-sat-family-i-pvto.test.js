import assert from 'node:assert/strict'
import test from 'node:test'
import {readFileSync} from 'node:fs'
import {buildMultiLiveOilPvt,multiLiveOilPvtContext,multiLiveOilPvtResponse} from '../src/views/SoftwareIntegration/eclipseMultiLiveOilPvt.js'
import {buildMultiSatRelperm,multiSatRelpermContext,multiSatRelpermResponse} from '../src/views/SoftwareIntegration/eclipseMultiSatRelperm.js'
import {batchChoices,buildBatchCase,buildBatchRequest} from '../src/views/SoftwareIntegration/eclipseBatch.js'
const root=new URL('../tests/fixtures/eclipseMultiSatFamilyIPvto/',import.meta.url),load=n=>JSON.parse(readFileSync(new URL(n+'.json',root),'utf8')),inspection=load('inspection'),native=load('native-acceptance')
const run={id:1,modelVersionId:2,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null,result:{schemaVersion:'eclipse-summary-result/1',modelKind:'eclipse_100',eclEnd:{problems:0,errors:0,bugs:0}}}
test('combined live-oil branches and two linked saturation tables bind separate full regions without flattening equilibrium',()=>{
 assert.equal(multiLiveOilPvtContext(inspection).regionCount,2);assert.equal(multiSatRelpermContext(inspection).regionCount,2)
 const p=buildMultiLiveOilPvt({run,inspection,regionNumber:2,field:'FORMATION_VOLUME_FACTOR',multiplier:1.02});assert.equal(p.records.length,9);assert.equal(p.records.reduce((n,r)=>n+r.rows.length,0),12);assert.deepEqual(p.gasTable,inspection.multiLiveOilPvtContext.regions[1].gasTable);assert.equal(p.sourceSha256,native.sourceSha256)
 const s=buildMultiSatRelperm({run,inspection,regionNumber:2,field:'OIL_KR',multiplier:.5});assert.deepEqual(s.tables,inspection.multiSatRelpermContext.regions[1].tables)
})
test('five independent batch cases mix PVTO and saturation axes with their original table records',()=>{
 const entries=[['REGION_PVTO_MU',2],['REGION_PVTO_BO',1.02],['MULTI_WATER_KR',.5],['MULTI_GAS_KR',.5],['MULTI_OIL_KR',.5]];assert.deepEqual(new Set(batchChoices(inspection).map(c=>c.kind)),new Set(entries.map(e=>e[0])))
 const cases=entries.map(([kind,value])=>buildBatchCase({run,inspection,regionNumber:2,kind,value,name:kind}));assert.equal(buildBatchRequest({run,name:'两图独立',cases,requestKey:'12345678-1234-1234-1234-123456789abc'}).cases.length,5)
})
test('native original two-table outputs retain geology and report unchanged water honestly',()=>{
 assert.equal(native.nativeVerified,true);const pvt=[...Array(30)].flatMap(()=>[...Array(5).fill(1),...Array(5).fill(2)]),sat=[...Array(3)].flatMap(()=>[...Array(50).fill(1),...Array(50).fill(2)]);assert.deepEqual(native.baseline.pvtnum,pvt);assert.deepEqual(native.baseline.satnum,sat);assert.notDeepEqual(pvt,sat)
 for(const label of ['viscosity','volume','water-kr','gas-kr','oil-kr']){assert.deepEqual(native[label].arrays,native.baseline.arrays);assert.deepEqual(native[label].pvtnum,pvt);assert.deepEqual(native[label].satnum,sat);assert.equal(native[label].eclEnd.warnings,30);for(const k of ['errors','problems','bugs'])assert.equal(native[label].eclEnd[k],0);const response=(label==='viscosity'||label==='volume'?multiLiveOilPvtResponse:multiSatRelpermResponse)({series:native[label].series},{series:native.baseline.series});assert.equal(response.available,true);if(label==='water-kr'){assert.equal(response.changedCount,0);assert.notEqual(native[label].inputSha256,native.baseline.inputSha256);assert.deepEqual(native[label].series,native.baseline.series)}else assert.ok(response.changedCount>0)}
})
