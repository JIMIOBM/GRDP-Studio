import test from 'node:test'
import assert from 'node:assert/strict'
import { compositionalFluidPropertyFixture } from '../tests/fixtures/eclipseCompositionalFluidProperty.js'
import { compositionalReinjectionFixture } from '../tests/fixtures/eclipseCompositionalReinjection.js'
import { validCompositionalInspection, compositionalResult } from '../src/views/SoftwareIntegration/eclipseCompositional.js'
import { buildCompositionalFluidProperty, matchesCompositionalFluidProperty, compositionalFluidPropertyResponse } from '../src/views/SoftwareIntegration/eclipseCompositionalFluidProperty.js'
import { buildCompositionalReinjection } from '../src/views/SoftwareIntegration/eclipseCompositionalReinjection.js'
import { buildCompositionalGasSales } from '../src/views/SoftwareIntegration/eclipseCompositionalGasSales.js'
import { buildCompositionalInjectionPressure } from '../src/views/SoftwareIntegration/eclipseCompositionalInjectionPressure.js'
const response = f => compositionalFluidPropertyResponse(f.property.result.summary, f.baseline.result.summary, f.parameters, f.inspection)

test('actual /17 root carries all nine PCRIT components and retains legacy group-control editors', () => {
  const f = compositionalFluidPropertyFixture()
  assert.equal(validCompositionalInspection(f.inspection), true); assert.equal(matchesCompositionalFluidProperty(f.parameters, f.inspection), true)
  assert.equal(f.parameters.componentName, 'C1'); assert.equal(f.parameters.componentIndex, 3); assert.equal(f.parameters.lineNumber, 122)
  assert.equal(f.parameters.originalValue, 667.7817); assert.equal(buildCompositionalFluidProperty({ inspection: f.inspection, baselineRunId: 1, componentIndex: 3, targetValue: 700 }).unit, 'psia')
  assert.ok(buildCompositionalReinjection({ inspection: f.inspection, baselineRunId: 1, group: 'FIELD', targetFraction: .5 }))
  assert.ok(buildCompositionalGasSales({ inspection: f.inspection, baselineRunId: 1, group: 'FIELD', targetSalesTarget: 3000 }))
  assert.ok(buildCompositionalInjectionPressure({ inspection: f.inspection, baselineRunId: 1, well: 'I', targetBhpUpperLimit: 3550 }))
  const old = compositionalReinjectionFixture().inspection; assert.equal(validCompositionalInspection(old), true)
  old.compositionalFluidPropertyContext = f.inspection.compositionalFluidPropertyContext; assert.equal(validCompositionalInspection(old), false)
})
test('strict property parameters reject cross-component cross-field and stale source edits', () => {
  const f = compositionalFluidPropertyFixture()
  for (const [key, value] of [['sourceFile', '../SPE3_GASCOND.DATA'], ['sourceSha256', '0'.repeat(64)], ['lineNumber', 123], ['componentIndex', 2], ['componentName', 'C2'],
    ['field', 'TCRIT'], ['unit', 'barsa'], ['originalValue', 667], ['targetValue', 667.7817], ['targetValue', 0], ['targetValue', -1], ['targetValue', 1e8 + 1],
    ['targetValue', '700'], ['baselineRunId', '1'], ['targetBhpUpperLimit', 700]]) assert.equal(matchesCompositionalFluidProperty({ ...f.parameters, [key]: value }, f.inspection), false, key)
})
test('actual PCRIT response retains duplicate FOPT FOPR FPR columns and compares real native values', () => {
  const f = compositionalFluidPropertyFixture(), r = response(f)
  assert.equal(r.verified, true); assert.equal(r.rawVectorCount, 330); assert.equal(r.metrics.length, 7)
  assert.equal(r.metrics.filter(v => v.keyword === 'FOPT').length, 2)
  assert.equal(new Set(r.metrics.map(v => v.vectorIndex)).size, 7)
  const oil = r.metrics.find(v => v.keyword === 'FOPT'); assert.equal(oil.baselineFinal.value, 2593602.25); assert.equal(oil.currentFinal.value, 2571354.25); assert.equal(oil.finalDifference, -22248)
  assert.equal(compositionalResult(f.property, f.inspection).summary.series.length, 330)
})
test('wrong units duplicate indexes or incomplete final period cannot claim complete property comparison', () => {
  for (const fault of ['unit', 'duplicate', 'period', 'source']) {
    const f = compositionalFluidPropertyFixture(), s = f.property.result.summary
    if (fault === 'unit') s.series.find(v => v.keyword === 'FOPT').unit = 'SM3'
    if (fault === 'duplicate') s.series[1].vectorIndex = s.series[0].vectorIndex
    if (fault === 'period') s.series.forEach(v => v.points.pop())
    if (fault === 'source') f.inspection.compositionalFluidPropertyContext.sourceSha256 = '0'.repeat(64)
    assert.notEqual(response(f)?.verified, true, fault)
  }
})
test('every component record remains bound to the source order name field original value and unit', () => {
  for (const fault of ['index', 'name', 'field', 'unit', 'value', 'missing', 'line', 'extra']) {
    const f = compositionalFluidPropertyFixture(), c = f.inspection.compositionalFluidPropertyContext, r = c.records[2]
    if (fault === 'index') r.componentIndex = 2
    if (fault === 'name') r.componentName = 'C2'
    if (fault === 'field') r.field = 'TCRIT'
    if (fault === 'unit') r.unit = 'barsa'
    if (fault === 'value') r.originalValue = 0
    if (fault === 'missing') c.records.pop()
    if (fault === 'line') c.records.at(-1).lineNumber = 1
    if (fault === 'extra') r.unknown = true
    assert.equal(validCompositionalInspection(f.inspection), false, fault)
  }
})
