import assert from 'node:assert/strict'
import test from 'node:test'
import {readFileSync} from 'node:fs'
import {multiWaterPvtContext,buildMultiWaterPvt,validMultiWaterPvt,waterPvtValue} from '../src/views/SoftwareIntegration/eclipseMultiWaterPvt.js'
import {batchChoices,buildBatchCase,buildBatchRequest} from '../src/views/SoftwareIntegration/eclipseBatch.js'
const fields=['REFERENCE_PRESSURE','FORMATION_VOLUME_FACTOR','COMPRESSIBILITY','VISCOSITY','VISCOSIBILITY'],run={id:1,modelVersionId:2,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null,result:{schemaVersion:'eclipse-summary-result/1',modelKind:'eclipse_100',eclEnd:{problems:0,errors:0,bugs:0}}}
const load=(kind,name)=>JSON.parse(readFileSync(new URL(`../tests/fixtures/eclipseMultiWaterDefaults/${kind}/${name}.json`,import.meta.url),'utf8'))
for(const kind of ['three','pure'])test(`${kind} original four water tokens bind official omitted Cv without inventing an explicit fifth source item`,()=>{
 const inspection=load(kind,'inspection'),native=load(kind,'native-acceptance'),targets=kind==='three'?[4000,1.0506,6e-5,.8,1e-6]:[4000,1.02,6.06e-6,1,1e-6]
 assert.equal(multiWaterPvtContext(inspection).schemaVersion,'eclipse-multi-water-pvt-context/2');assert.equal(waterPvtValue(inspection.multiWaterPvtContext.regions[1].recordValues,'VISCOSIBILITY'),0);assert.equal(native.nativeVerified,true)
 const cases=fields.map((field,k)=>{const p=buildMultiWaterPvt({run,inspection,regionNumber:2,field,targetValue:targets[k]});assert.equal(validMultiWaterPvt(p),true);assert.equal(p.schemaVersion,'eclipse-multi-pvtw-parameters/2');assert.equal(p.recordValues.length,4);assert.equal(p.sourceSha256,native.sourceSha256);assert.equal(validMultiWaterPvt({...p,schemaVersion:'eclipse-multi-pvtw-parameters/1'}),false);return buildBatchCase({run,inspection,regionNumber:2,kind:'REGION_PVTW_'+field,value:targets[k],name:field})})
 assert.equal(cases[4].parameters.originalValue,0);assert.equal(validMultiWaterPvt({...cases[4].parameters,originalValue:1e-6}),false);assert.equal(batchChoices(inspection).filter(c=>c.kind.startsWith('REGION_PVTW_')).length,5);assert.equal(buildBatchRequest({run,name:'默认Cv',cases,requestKey:'12345678-1234-1234-1234-123456789abc'}).cases.length,5)
})
test('only omitted fifth item is supported; star defaults, missing earlier items and false schema claims are rejected',()=>{
 const inspection=load('three','inspection')
 for(const fault of ['star','short','old','fakeExplicit']){const bad=structuredClone(inspection),c=bad.multiWaterPvtContext;if(fault==='star')c.regions[1].recordValues[3]='1*';if(fault==='short')c.regions[1].recordValues.pop();if(fault==='old')c.schemaVersion='eclipse-multi-water-pvt-context/1';if(fault==='fakeExplicit')c.regions.forEach(r=>r.recordValues.push('0'));assert.equal(multiWaterPvtContext(bad),null)}
 const mixed=structuredClone(inspection);mixed.multiWaterPvtContext.regions[1].recordValues.push('0');assert.ok(multiWaterPvtContext(mixed));assert.equal(buildMultiWaterPvt({run,inspection:mixed,regionNumber:2,field:'VISCOSITY',targetValue:.8}).recordValues.length,5)
})
