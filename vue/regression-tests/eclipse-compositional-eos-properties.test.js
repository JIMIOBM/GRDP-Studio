import test from 'node:test'
import assert from 'node:assert/strict'
import { compositionalEosPropertyFixture,eosPropertyCases } from '../tests/fixtures/eclipseCompositionalEosProperties.js'
import {validCompositionalInspection,compositionalResult} from '../src/views/SoftwareIntegration/eclipseCompositional.js'
import {validCompositionalEosPropertyContexts,validCompositionalFluidProperty,matchesCompositionalFluidProperty,buildCompositionalFluidProperty,compositionalFluidPropertyResponse} from '../src/views/SoftwareIntegration/eclipseCompositionalFluidProperty.js'
for(const c of eosPropertyCases){
 test(`official ${c.field} binds C1 original row and its own unit`,()=>{
  const f=compositionalEosPropertyFixture(c.field);assert.ok(validCompositionalInspection(f.inspection));assert.ok(validCompositionalEosPropertyContexts(f.inspection.compositionalEosPropertyContexts,f.inspection))
  assert.deepEqual([f.parameters.field,f.parameters.originalValue,f.parameters.targetValue,f.parameters.lineNumber,f.parameters.unit],[c.field,c.original,c.target,c.line,c.unit]);assert.ok(matchesCompositionalFluidProperty(f.parameters,f.inspection))
  for(const [k,v] of [['field','ACF'],['unit',c.field==='MW'?'dimensionless':'lb/lb-M'],['targetValue',0],['targetValue',-1],['targetValue',c.original],['targetValue',String(c.target)],['schemaVersion','eclipse-compositional-acentric-factor-parameters/1'],['extra',true]])assert.equal(validCompositionalFluidProperty({...f.parameters,[k]:v}),false,`${k}:${v}`)
 })
 test(`${c.field} complete native summary preserves330 columns and independent duplicate oil values`,()=>{
  const f=compositionalEosPropertyFixture(c.field);assert.ok(compositionalResult(f.property,f.inspection))
  const response=compositionalFluidPropertyResponse(f.property.result.summary,f.baseline.result.summary,f.parameters,f.inspection);assert.ok(response.verified);assert.equal(response.rawVectorCount,330);assert.equal(response.endDays,5475);assert.equal(response.metrics.length,7)
  const oil=response.metrics.filter(m=>m.keyword==='FOPT');assert.equal(oil.length,2);assert.ok(oil.every(m=>m.currentFinal.value===c.oil&&m.finalDifference===c.difference))
 })
}
test('inspection22 requires every source-bound EOS array while all older roots remain closed',()=>{
 const f=compositionalEosPropertyFixture();for(const version of [14,16,17,18,19,20,21])assert.equal(validCompositionalInspection({...f.inspection,schemaVersion:'eclipse-data-inspection/'+version}),false)
 for(const key of ['compositionalEosPropertyContexts','compositionalAcentricFactorContext','compositionalCriticalZContext','compositionalTemperatureContext','compositionalFluidPropertyContext']){const i=structuredClone(f.inspection);delete i[key];assert.equal(validCompositionalInspection(i),false)}
 for(let j=0;j<3;j++){const i=structuredClone(f.inspection);i.compositionalEosPropertyContexts.splice(j,1);assert.equal(validCompositionalInspection(i),false)}
 const i=structuredClone(f.inspection);i.compositionalEosPropertyContexts[0].sourceSha256='0'.repeat(64);assert.equal(validCompositionalInspection(i),false)
})
test('inspection22 retains independent signed ACF and positive Pc Tc and Z inputs',()=>{
 const f=compositionalEosPropertyFixture();for(const [field,target] of [['ACF',0],['ACF',-.01],['PCRIT',700],['TCRIT',350],['ZCRIT',.3]])assert.ok(matchesCompositionalFluidProperty(buildCompositionalFluidProperty({inspection:f.inspection,baselineRunId:f.baseline.id,componentIndex:3,targetValue:target,field}),f.inspection))
})
