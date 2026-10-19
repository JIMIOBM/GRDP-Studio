import assert from 'node:assert/strict'
import test from 'node:test'
import {readFileSync} from 'node:fs'
import {buildMultiLiveOilPvt,multiLiveOilPvtContext,multiLiveOilPvtResponse} from '../src/views/SoftwareIntegration/eclipseMultiLiveOilPvt.js'
import {buildMultiFamilyII,multiFamilyIIContext,multiFamilyIIResponse} from '../src/views/SoftwareIntegration/eclipseMultiSatFamilyII.js'
import {batchChoices,buildBatchCase,buildBatchRequest} from '../src/views/SoftwareIntegration/eclipseBatch.js'
const root=new URL('../tests/fixtures/eclipseMultiSatPvto/',import.meta.url),load=n=>JSON.parse(readFileSync(new URL(n+'.json',root),'utf8')),inspection=load('inspection'),native=load('native-acceptance')
const run={id:1,modelVersionId:2,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null,result:{schemaVersion:'eclipse-summary-result/1',modelKind:'eclipse_100',eclEnd:{problems:0,errors:0,bugs:0}}}
test('combined live-oil branches and three saturation tables bind separate full regions without flattening equilibrium',()=>{
 assert.equal(multiLiveOilPvtContext(inspection).regionCount,2);assert.equal(multiFamilyIIContext(inspection).regionCount,2)
 const p=buildMultiLiveOilPvt({run,inspection,regionNumber:2,field:'FORMATION_VOLUME_FACTOR',multiplier:1.02});assert.equal(p.records.length,9);assert.equal(p.records.reduce((n,r)=>n+r.rows.length,0),12);assert.deepEqual(p.gasTable,inspection.multiLiveOilPvtContext.regions[1].gasTable);assert.equal(p.sourceSha256,native.sourceSha256)
 const s=buildMultiFamilyII({run,inspection,regionNumber:2,field:'OIL_KR',multiplier:.5});assert.deepEqual(s.tables,inspection.multiSatRelpermContext.regions[1].tables)
})
test('five independent batch cases mix PVTO and saturation axes with their original table records',()=>{
 const entries=[['REGION_PVTO_MU',2],['REGION_PVTO_BO',1.02],['FAMILY_II_WATER_KR',.5],['FAMILY_II_GAS_KR',.5],['FAMILY_II_OIL_KR',.5]];assert.deepEqual(new Set(batchChoices(inspection).map(c=>c.kind)),new Set(entries.map(e=>e[0])))
 const cases=entries.map(([kind,value])=>buildBatchCase({run,inspection,regionNumber:2,kind,value,name:kind}));assert.equal(buildBatchRequest({run,name:'两图独立',cases,requestKey:'12345678-1234-1234-1234-123456789abc'}).cases.length,5)
})
test('native independent SAT and matched PVT maps retain geology and actual live-oil responses',()=>{
 assert.equal(native.nativeVerified,true);const pvt=[...Array(30)].flatMap(()=>[...Array(5).fill(1),...Array(5).fill(2)]),sat=[...Array(3)].flatMap(()=>[...Array(50).fill(1),...Array(50).fill(2)]);assert.deepEqual(native.baseline.pvtnum,pvt);assert.deepEqual(native.baseline.satnum,sat);assert.notDeepEqual(pvt,sat)
 for(const label of ['viscosity','volume','water-kr','gas-kr','oil-kr']){assert.deepEqual(native[label].arrays,native.baseline.arrays);assert.deepEqual(native[label].pvtnum,pvt);assert.deepEqual(native[label].satnum,sat);assert.equal(native[label].eclEnd.warnings,32);for(const k of ['errors','problems','bugs'])assert.equal(native[label].eclEnd[k],0);assert.ok((label==='viscosity'||label==='volume'?multiLiveOilPvtResponse:multiFamilyIIResponse)({series:native[label].series},{series:native.baseline.series}).changedCount>0)}
})
