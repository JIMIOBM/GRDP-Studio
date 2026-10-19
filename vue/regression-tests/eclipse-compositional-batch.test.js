import test from 'node:test'
import assert from 'node:assert/strict'
import { compositionalBatchFixture } from '../tests/fixtures/eclipseCompositionalBatch.js'
import { batchBaselineAvailable,batchChoices,buildBatchCase,buildBatchRequest,validBatchRequest,batchResponse } from '../src/views/SoftwareIntegration/eclipseBatch.js'
test('qualified E300 baseline offers seven independent component properties and creates an exact four-case request',()=>{
 const f=compositionalBatchFixture();assert.ok(batchBaselineAvailable(f.baseline));assert.equal(batchChoices(f.inspection).filter(c=>c.kind.startsWith('E300_')).length,7);assert.ok(validBatchRequest(f.request));assert.equal(f.cases.length,4)
 assert.deepEqual(f.cases.map(c=>[c.parameters.field,c.parameters.componentName,c.parameters.componentIndex,c.parameters.baselineRunId]),['ACF','MW','OMEGAA','OMEGAB'].map(field=>[field,'C1',3,10101]))
})
test('component and field choices bind source values instead of reusing another component original',()=>{
 const f=compositionalBatchFixture();const c=buildBatchCase({run:f.baseline,inspection:f.inspection,kind:'E300_MW',componentIndex:4,value:31,name:'C2 MW'})
 assert.equal(c.parameters.componentName,'C2');assert.equal(c.parameters.originalValue,30.07);assert.equal(c.parameters.unit,'lb/lb-M');assert.equal(c.parameters.lineNumber,140)
 assert.throws(()=>buildBatchCase({run:f.baseline,inspection:f.inspection,kind:'E300_MW',value:17,name:'No component'}))
})
test('ACF zero and negative cases are valid independent cases but MW zero is rejected',()=>{
 const f=compositionalBatchFixture();const cases=[0,-.01].map((value,j)=>buildBatchCase({run:f.baseline,inspection:f.inspection,kind:'E300_ACF',componentIndex:3,value,name:'ACF '+j}));assert.ok(validBatchRequest(buildBatchRequest({run:f.baseline,name:'Signed ACF',cases,requestKey:f.request.requestKey})))
 assert.throws(()=>buildBatchCase({run:f.baseline,inspection:f.inspection,kind:'E300_MW',componentIndex:3,value:0,name:'Invalid MW'}))
})
test('case duplicates, baseline mismatches wrong units and unknown keys are rejected before submission',()=>{
 const f=compositionalBatchFixture();for(const mutation of [r=>r.cases[1].name=r.cases[0].name,r=>r.cases[1].parameters=structuredClone(r.cases[0].parameters),r=>r.cases[1].parameters.baselineRunId++,r=>r.cases[1].parameters.unit='dimensionless',r=>r.cases[1].parameters.targetValue='17',r=>r.cases[1].parameters.extra=1]){const request=structuredClone(f.request);mutation(request);assert.equal(validBatchRequest(request),false)}
})
test('all four native results preserve seven raw columns across all125 reports',()=>{
 const f=compositionalBatchFixture(),r=batchResponse(f.batch,f.inspection);assert.equal(r.message,'');assert.equal(r.rows.length,3500)
 const last=r.rows.filter(r=>r.timeDays===5475);assert.equal(last.length,28);const oil=last.filter(r=>r.keyword==='FOPT');assert.equal(oil.length,8)
 const expected=[2514726.75,2591593,2624349.5,2586853.75];for(let j=0;j<4;j++){const rows=oil.filter(r=>r.runId===10111+j);assert.equal(rows.length,2);assert.notEqual(rows[0].vectorIndex,rows[1].vectorIndex);assert.ok(rows.every(r=>r.current===expected[j]&&r.difference===expected[j]-2593602.25))}
})
test('a failed or partial case is never filled with successful curves',()=>{
 const f=compositionalBatchFixture();f.batch.members[0].run.status='FAILED';f.batch.members[1].run.status='PARTIAL_SUCCEEDED';f.batch.members[1].run.resultContract='VALID_PARTIAL'
 const r=batchResponse(f.batch,f.inspection);assert.equal(r.rows.length,1750);assert.ok(r.rows.every(r=>[10113,10114].includes(r.runId)))
})
test('expired foreign or unbound results cannot contribute to a compositional batch comparison',()=>{
 const f=compositionalBatchFixture();f.batch.members[0].run.modelId=999;f.batch.members[1].run.parameters.sourceSha256='0'.repeat(64);f.batch.members[2].run.resultExpired=true
 const r=batchResponse(f.batch,f.inspection);assert.equal(r.rows.length,875);assert.ok(r.rows.every(r=>r.runId===10114));assert.ok(r.message)
 assert.equal(batchResponse(f.batch).rows.length,0)
})
test('different actual report times stay blank instead of interpolating or inventing matched values',()=>{
 const f=compositionalBatchFixture();for(const v of f.batch.members[0].run.result.summary.series)v.points[1].timeDays+=.25
 const r=batchResponse(f.batch,f.inspection),affected=r.rows.filter(r=>r.runId===10111);assert.equal(affected.length,882);assert.equal(affected.filter(r=>r.baseline===null||r.current===null).length,14);assert.ok(affected.filter(r=>r.difference===null).every(r=>r.baseline===null||r.current===null))
})
test('thermal forecast and parameterized runs cannot be used to build a new component batch',()=>{
 const f=compositionalBatchFixture();for(const mutate of [r=>r.parameters=f.cases[0].parameters,r=>r.result.simulation.modelType='THERMAL',r=>r.resultExpired=true,r=>r.resultContract='VALID_PARTIAL']){const run=structuredClone(f.baseline);mutate(run);assert.equal(batchBaselineAvailable(run),false)}
})

test('a batch member must bind the actual collection baseline identity',()=>{
 const f=compositionalBatchFixture();f.batch.members[0].run.parameters.baselineRunId++;f.batch.members[1].run.modelId=undefined
 const r=batchResponse(f.batch,f.inspection);assert.equal(r.rows.length,1750);assert.ok(r.rows.every(r=>[10113,10114].includes(r.runId)))
})
