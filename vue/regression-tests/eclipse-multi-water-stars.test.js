import assert from 'node:assert/strict'
import test from 'node:test'
import {readFileSync} from 'node:fs'
import {multiWaterPvtContext,buildMultiWaterPvt,validMultiWaterPvt,waterPvtValue} from '../src/views/SoftwareIntegration/eclipseMultiWaterPvt.js'
import {batchChoices,buildBatchCase,buildBatchRequest} from '../src/views/SoftwareIntegration/eclipseBatch.js'
const fields=['REFERENCE_PRESSURE','FORMATION_VOLUME_FACTOR','COMPRESSIBILITY','VISCOSITY','VISCOSIBILITY'],run={id:1,modelVersionId:2,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null,result:{schemaVersion:'eclipse-summary-result/1',modelKind:'eclipse_100',eclEnd:{problems:0,errors:0,bugs:0}}}
const load=(kind,name)=>JSON.parse(readFileSync(new URL(`../tests/fixtures/eclipseMultiWaterStars/${kind}/${name}.json`,import.meta.url),'utf8'))
for(const kind of ['three','pure'])test(`${kind} explicit fifth 1* retains raw default across all five independent batch fields`,()=>{
 const i=load(kind,'inspection'),n=load(kind,'native-acceptance'),targets=kind==='three'?[4000,1.0506,6e-5,.8,1e-6]:[4000,1.02,6.06e-6,1,1e-6];assert.equal(n.nativeVerified,true);assert.equal(multiWaterPvtContext(i).schemaVersion,'eclipse-multi-water-pvt-context/3');assert.equal(waterPvtValue(i.multiWaterPvtContext.regions[1].recordValues,'VISCOSIBILITY'),0)
 const cases=fields.map((field,k)=>{const p=buildMultiWaterPvt({run,inspection:i,regionNumber:2,field,targetValue:targets[k]});assert.equal(p.schemaVersion,'eclipse-multi-pvtw-parameters/3');assert.equal(p.recordValues[4],'1*');assert.equal(validMultiWaterPvt(p),true);assert.equal(validMultiWaterPvt({...p,schemaVersion:'eclipse-multi-pvtw-parameters/2'}),false);return buildBatchCase({run,inspection:i,regionNumber:2,kind:'REGION_PVTW_'+field,value:targets[k],name:field})});assert.equal(buildBatchRequest({run,name:kind,cases,requestKey:'12345678-1234-1234-1234-123456789abc'}).cases.length,5);assert.ok(batchChoices(i).some(c=>c.kind==='REGION_PVTW_VISCOSIBILITY'))
})
test('Cv star and omitted defaults cannot be reclassified into older or fabricated context versions',()=>{
 for(const kind of ['three','pure']){const i=load(kind,'inspection');for(const version of ['/1','/2']){const bad=structuredClone(i);bad.multiWaterPvtContext.schemaVersion='eclipse-multi-water-pvt-context'+version;assert.equal(multiWaterPvtContext(bad),null)}const mixed=structuredClone(i);mixed.multiWaterPvtContext.regions[0].recordValues.pop();assert.ok(multiWaterPvtContext(mixed));mixed.multiWaterPvtContext.regions[1].recordValues.pop();assert.equal(multiWaterPvtContext(mixed),null);mixed.multiWaterPvtContext.schemaVersion='eclipse-multi-water-pvt-context/2';assert.ok(multiWaterPvtContext(mixed))}
})
test('only the fifth single default marker is supported; earlier and repeat markers never become numerical values',()=>{
 for(const kind of ['three','pure'])for(let column=0;column<5;column++){const i=load(kind,'inspection');for(const r of i.multiWaterPvtContext.regions)r.recordValues[column]=column===4?'2*':'1*';assert.equal(multiWaterPvtContext(i),null)}
})
test('six independent native outputs preserve geology and all five target pressure responses',()=>{
 for(const kind of ['three','pure']){const n=load(kind,'native-acceptance');for(const label of ['pressure','volume','compressibility','viscosity','viscosibility']){assert.deepEqual(n[label].arrays,n.baseline.arrays);assert.deepEqual(n[label].pvtnum,n.baseline.pvtnum);assert.notEqual(n[label].inputSha256,n.baseline.inputSha256);for(const k of ['errors','problems','bugs'])assert.equal(n[label].eclEnd[k],0);assert.notDeepEqual(n[label].series,n.baseline.series)}}
})
