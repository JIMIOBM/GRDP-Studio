import { compositionalPressureInactiveFixture } from '../tests/fixtures/eclipseCompositionalPressureInactive.js'
import test from 'node:test'
import assert from 'node:assert/strict'
import { compositionalPressureFixture } from '../tests/fixtures/eclipseCompositionalPressure.js'
import { buildCompositionalPressure, matchesCompositionalPressure, compositionalPressureResponse } from '../src/views/SoftwareIntegration/eclipseCompositionalPressure.js'
import { validCompositionalProduction } from '../src/views/SoftwareIntegration/eclipseCompositionalProduction.js'
import { compositionalResult } from '../src/views/SoftwareIntegration/eclipseCompositional.js'

test('producer pressure binds separate WELTARG row and its own strictly closed parameter schema', () => {
  const f = compositionalPressureFixture(), p = f.parameters
  assert.equal(p.lineNumber, 433); assert.equal(p.originalBhpLowerLimit, 500); assert.equal(p.unit, 'psia')
  assert.equal(matchesCompositionalPressure(p, f.inspection), true); assert.equal(validCompositionalProduction(p), false)
  for (const [key, value] of [['schemaVersion', 'eclipse-compositional-production-parameters/1'], ['sourceSha256', '0'.repeat(64)], ['sourceFile', '../SPE3_GASCOND.DATA'], ['lineNumber', 430], ['well', 'I'], ['unit', 'Mscf/day'], ['unit', 'barsa'], ['originalBhpLowerLimit', 499], ['targetBhpLowerLimit', 0], ['targetBhpLowerLimit', '1500'], ['targetBhpLowerLimit', 500], ['targetGasRate', 6200]])
    assert.equal(matchesCompositionalPressure({ ...p, [key]: value }, f.inspection), false, key)
  assert.equal(buildCompositionalPressure({ inspection: f.inspection, baselineRunId: f.baseline.id, well: 'I', targetBhpLowerLimit: 1500 }), null)
})

test('official pressure response uses actual gas and BHP, keeps all 330 vectors and unmodified gas target', () => {
  const f = compositionalPressureFixture(), r = compositionalPressureResponse(f.native.pressure.summary, f.native.baseline.summary, f.parameters, f.inspection)
  assert.equal(r.verified, true); assert.equal(r.consistent, true); assert.equal(r.limited, 14); assert.equal(r.originalGasTarget, 6200)
  assert.equal(r.rows.length, 125); assert.equal(r.bhpLowerLimit, 1500); assert.equal(r.finalBhp, 1500)
  assert.equal(r.finalGasRate, 4.9881815910339355); assert.equal(r.finalTime, 5475)
  assert.equal(compositionalResult(f.pressure, f.inspection).summary.series.length, 330)
  assert.ok(r.rows.some(v => v.gasRate < 6200 && v.pressureLimited))
})

test('wrong units, duplicate curves, missing gas, foreign vector identity and effective pressure breaches cannot verify', () => {
  for (const fault of ['unit', 'duplicate', 'missing', 'identity', 'pressure', 'gas']) {
    const f = compositionalPressureFixture(), s = f.native.pressure.summary, pressure = s.series.find(v => v.keyword === 'WBHP' && v.objectName === 'P')
    if (fault === 'unit') pressure.unit = 'BARSA'
    if (fault === 'duplicate') s.series.push(structuredClone(pressure))
    if (fault === 'missing') s.series = s.series.filter(v => v.keyword !== 'WGPR')
    if (fault === 'identity') pressure.vectorIndex++
    if (fault === 'pressure') pressure.points[0].value = 1499
    if (fault === 'gas') s.series.find(v => v.keyword === 'WGPR' && v.objectName === 'P').points[0].value = 6300
    const r = compositionalPressureResponse(s, f.native.baseline.summary, f.parameters, f.inspection)
    if (['pressure', 'gas'].includes(fault)) { assert.equal(r.consistent, false); assert.equal(r.verified, false) } else assert.equal(r, null)
  }
})

test('missing requested cumulative stays absent; actual cumulative is never integrated from the target', () => {
  const f = compositionalPressureFixture(), r = compositionalPressureResponse(f.native.pressure.summary, f.native.baseline.summary, f.parameters, f.inspection)
  if (r.cumulative) assert.notEqual(r.cumulative.value, 6200 * 5475)
  for (const s of [f.native.pressure.summary, f.native.baseline.summary]) s.series = s.series.filter(v => v.keyword !== 'WGPT')
  assert.equal(compositionalPressureResponse(f.native.pressure.summary, f.native.baseline.summary, f.parameters, f.inspection).cumulative, null)
})

test('pressure before the initial producer control cannot become an editable BHP binding', () => {
  const f = compositionalPressureFixture()
  f.inspection.compositionalProductionContext.records[0].bhpLineNumber = 429
  assert.equal(matchesCompositionalPressure({ ...f.parameters, lineNumber: 429 }, f.inspection), false)
  assert.equal(buildCompositionalPressure({ inspection: f.inspection, baselineRunId: f.baseline.id, well: 'P', targetBhpLowerLimit: 1500 }), null)
})

test('independent native 600 psia constraint remains inactive and preserves real baseline flow and pressure', () => {
  const f = compositionalPressureInactiveFixture(), r = compositionalPressureResponse(f.native.pressure.summary, f.native.baseline.summary, f.parameters, f.inspection)
  assert.equal(r.verified, true); assert.equal(r.inactive, true); assert.equal(r.changed, 0); assert.equal(r.limited, 0)
  assert.equal(r.bhpLowerLimit, 600); assert.equal(r.finalGasRate, 6200); assert.equal(r.finalBhp, 1031.34814453125)
  assert.equal(r.cumulative.value, 33945000)
  assert.deepEqual(f.native.pressure.summary, f.native.baseline.summary)
})
