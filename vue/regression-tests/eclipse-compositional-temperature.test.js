import test from 'node:test'
import assert from 'node:assert/strict'
import { compositionalTemperatureFixture } from '../tests/fixtures/eclipseCompositionalTemperature.js'
import { validCompositionalInspection, compositionalResult } from '../src/views/SoftwareIntegration/eclipseCompositional.js'
import { validCompositionalTemperatureContext, validCompositionalFluidProperty, buildCompositionalFluidProperty,
  matchesCompositionalFluidProperty, compositionalFluidPropertyResponse } from '../src/views/SoftwareIntegration/eclipseCompositionalFluidProperty.js'

test('official TCRIT C1 binds116 Rankine and leaves pressure capability independent', () => {
 const f = compositionalTemperatureFixture()
 assert.ok(validCompositionalInspection(f.inspection)); assert.ok(validCompositionalTemperatureContext(f.inspection.compositionalTemperatureContext,f.inspection))
 assert.equal(f.parameters.field,'TCRIT'); assert.equal(f.parameters.unit,'degR'); assert.equal(f.parameters.originalValue,343.08)
 assert.equal(f.parameters.lineNumber,116); assert.equal(f.parameters.componentIndex,3); assert.equal(f.parameters.componentName,'C1')
 assert.ok(matchesCompositionalFluidProperty(f.parameters,f.inspection))
 const pressure=buildCompositionalFluidProperty({inspection:f.inspection,baselineRunId:f.baseline.id,componentIndex:3,targetValue:700})
 assert.equal(pressure.field,'PCRIT'); assert.equal(pressure.unit,'psia'); assert.equal(pressure.lineNumber,122); assert.ok(matchesCompositionalFluidProperty(pressure,f.inspection))
})
test('temperature rejects other absolute scales wrong schema coercion missing and foreign fields', () => {
 const f=compositionalTemperatureFixture()
 for(const [key,value] of [['unit','degF'],['unit','degC'],['unit','K'],['unit','psia'],['field','PCRIT'],
  ['schemaVersion','eclipse-compositional-fluid-property-parameters/1'],['targetValue','350'],['targetValue',0],['targetValue',-1],['targetValue',343.08],['extra',true]])
  assert.equal(validCompositionalFluidProperty({...f.parameters,[key]:value}),false,`${key}:${value}`)
 for(const [key,value] of [['sourceSha256','0'.repeat(64)],['lineNumber',122],['componentName','C2'],['componentIndex',2],['originalValue',343]])
  assert.equal(matchesCompositionalFluidProperty({...f.parameters,[key]:value},f.inspection),false,key)
})
test('inspection19 requires both independently bound property contexts and older roots stay closed', () => {
 const f=compositionalTemperatureFixture()
 for(const version of ['14','16','17','18'])assert.equal(validCompositionalInspection({...f.inspection,schemaVersion:'eclipse-data-inspection/'+version}),false)
 for(const key of ['compositionalTemperatureContext','compositionalFluidPropertyContext']){
  const i=structuredClone(f.inspection);delete i[key];assert.equal(validCompositionalInspection(i),false,key)
 }
 const bad=structuredClone(f.inspection);bad.compositionalTemperatureContext.records[2].unit='degF';assert.equal(validCompositionalInspection(bad),false)
})
test('native temperature response retains330 columns seven independent metrics and96807STB difference', () => {
 const f=compositionalTemperatureFixture(); assert.ok(compositionalResult(f.property,f.inspection))
 const r=compositionalFluidPropertyResponse(f.property.result.summary,f.baseline.result.summary,f.parameters,f.inspection)
 assert.ok(r.verified);assert.equal(r.rawVectorCount,330);assert.equal(r.metrics.length,7);assert.equal(r.changedMetrics,7)
 const oil=r.metrics.filter(m=>m.keyword==='FOPT');assert.equal(oil.length,2);assert.notEqual(oil[0].vectorIndex,oil[1].vectorIndex)
 assert.ok(oil.every(m=>m.currentFinal.value===2690409.25&&m.finalDifference===96807));assert.equal(r.endDays,5475)
})
test('wrong units or incomplete native period cannot claim a complete temperature comparison', () => {
 const f=compositionalTemperatureFixture(),s=structuredClone(f.property.result.summary)
 s.series.find(v=>v.keyword==='FOPT').unit='SM3';assert.equal(compositionalFluidPropertyResponse(s,f.baseline.result.summary,f.parameters,f.inspection),null)
 const partial=structuredClone(f.property.result.summary);for(const v of partial.series)v.points.pop()
 assert.equal(compositionalFluidPropertyResponse(partial,f.baseline.result.summary,f.parameters,f.inspection).completePeriod,false)
})
