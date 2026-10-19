import test from 'node:test'
import assert from 'node:assert/strict'
import { compositionalAcentricFactorFixture } from '../tests/fixtures/eclipseCompositionalAcentricFactor.js'
import { validCompositionalInspection, compositionalResult } from '../src/views/SoftwareIntegration/eclipseCompositional.js'
import { validCompositionalAcentricFactorContext, validCompositionalFluidProperty, buildCompositionalFluidProperty,
  matchesCompositionalFluidProperty, compositionalFluidPropertyResponse } from '../src/views/SoftwareIntegration/eclipseCompositionalFluidProperty.js'

test('official ACF C1 binds134 dimensionless and leaves pressure capability independent', () => {
 const f = compositionalAcentricFactorFixture()
 assert.ok(validCompositionalInspection(f.inspection)); assert.ok(validCompositionalAcentricFactorContext(f.inspection.compositionalAcentricFactorContext,f.inspection))
 assert.equal(f.parameters.field,'ACF'); assert.equal(f.parameters.unit,'dimensionless'); assert.equal(f.parameters.originalValue,0.013)
 assert.equal(f.parameters.lineNumber,134); assert.equal(f.parameters.componentIndex,3); assert.equal(f.parameters.componentName,'C1')
 assert.ok(matchesCompositionalFluidProperty(f.parameters,f.inspection))
 const pressure=buildCompositionalFluidProperty({inspection:f.inspection,baselineRunId:f.baseline.id,componentIndex:3,targetValue:700})
 assert.equal(pressure.field,'PCRIT'); assert.equal(pressure.unit,'psia'); assert.equal(pressure.lineNumber,122); assert.ok(matchesCompositionalFluidProperty(pressure,f.inspection))
})
test('acentric-factor rejects other absolute scales wrong schema coercion missing and foreign fields', () => {
 const f=compositionalAcentricFactorFixture()
 for(const [key,value] of [['unit','degF'],['unit','degC'],['unit','K'],['unit','psia'],['field','PCRIT'],
  ['schemaVersion','eclipse-compositional-fluid-property-parameters/1'],['targetValue','350'],['targetValue',1e9],['targetValue',-1e9],['targetValue',0.013],['extra',true]])
  assert.equal(validCompositionalFluidProperty({...f.parameters,[key]:value}),false,`${key}:${value}`)
 for(const [key,value] of [['sourceSha256','0'.repeat(64)],['lineNumber',122],['componentName','C2'],['componentIndex',2],['originalValue',343]])
  assert.equal(matchesCompositionalFluidProperty({...f.parameters,[key]:value},f.inspection),false,key)
})
test('inspection21 requires all four independently bound property contexts and older roots stay closed', () => {
 const f=compositionalAcentricFactorFixture()
 for(const version of ['14','16','17','18','19','20'])assert.equal(validCompositionalInspection({...f.inspection,schemaVersion:'eclipse-data-inspection/'+version}),false)
 for(const key of ['compositionalAcentricFactorContext','compositionalFluidPropertyContext','compositionalTemperatureContext','compositionalCriticalZContext']){
  const i=structuredClone(f.inspection);delete i[key];assert.equal(validCompositionalInspection(i),false,key)
 }
 const bad=structuredClone(f.inspection);bad.compositionalAcentricFactorContext.records[2].unit='degF';assert.equal(validCompositionalInspection(bad),false)
})
test('native acentric-factor response retains330 columns seven independent metrics and-78875.5STB difference', () => {
 const f=compositionalAcentricFactorFixture(); assert.ok(compositionalResult(f.property,f.inspection))
 const r=compositionalFluidPropertyResponse(f.property.result.summary,f.baseline.result.summary,f.parameters,f.inspection)
 assert.ok(r.verified);assert.equal(r.rawVectorCount,330);assert.equal(r.metrics.length,7);assert.equal(r.changedMetrics,7)
 const oil=r.metrics.filter(m=>m.keyword==='FOPT');assert.equal(oil.length,2);assert.notEqual(oil[0].vectorIndex,oil[1].vectorIndex)
 assert.ok(oil.every(m=>m.currentFinal.value===2514726.75&&m.finalDifference===-78875.5));assert.equal(r.endDays,5475)
})
test('wrong units or incomplete native period cannot claim a complete acentric-factor comparison', () => {
 const f=compositionalAcentricFactorFixture(),s=structuredClone(f.property.result.summary)
 s.series.find(v=>v.keyword==='FOPT').unit='SM3';assert.equal(compositionalFluidPropertyResponse(s,f.baseline.result.summary,f.parameters,f.inspection),null)
 const partial=structuredClone(f.property.result.summary);for(const v of partial.series)v.points.pop()
 assert.equal(compositionalFluidPropertyResponse(partial,f.baseline.result.summary,f.parameters,f.inspection).completePeriod,false)
})

test('zero and negative ACF values are numeric source-bound inputs while Pc Tc and Z remain positive', () => {
 const f=compositionalAcentricFactorFixture()
 for(const v of [0,-0.01]) {
  const p=buildCompositionalFluidProperty({inspection:f.inspection,baselineRunId:f.baseline.id,componentIndex:3,targetValue:v,field:'ACF'})
  assert.ok(p);assert.equal(p.targetValue,v);assert.ok(matchesCompositionalFluidProperty(p,f.inspection))
  const i=structuredClone(f.inspection);i.compositionalAcentricFactorContext.records[2].originalValue=v
  assert.ok(validCompositionalInspection(i));assert.ok(validCompositionalAcentricFactorContext(i.compositionalAcentricFactorContext,i))
  for(const field of ['PCRIT','TCRIT','ZCRIT'])assert.equal(buildCompositionalFluidProperty({inspection:f.inspection,baselineRunId:f.baseline.id,componentIndex:3,targetValue:v,field}),null)
 }
})
