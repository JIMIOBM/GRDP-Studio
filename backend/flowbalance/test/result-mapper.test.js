'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const { mapFlowBalanceCalculationToDynamicResult } = require('../src/service/flowBalanceResultMapper')

test('maps verified in-memory FlowBalance result to current DynamicBalanceContent JSON shape', () => {
  const mapped = mapFlowBalanceCalculationToDynamicResult({
    resultId: 'runtime-result-57',
    wellName: 'X-1',
    nodeType: 57,
    pressureSource: 'well_head_tubing_pressure',
    pressureKind: 'wellhead',
    sourceUnit: 'Pa',
    standardUnit: 'MPa',
    algorithmVersion: 'verified-fmb-v1',
    inputSummary: {
      originalFormationPressureMpa: 50,
      waterGasRatioLimit: 0.0602
    },
    originalGasVolume: 123.45,
    regression: {
      slope: 0.0012,
      intercept: 0.34,
      rSquared: 0.987,
      sampleCount: 2,
      transformedSeries: [
        { date: '2004-04-21', pseudotime: 1, pressure: 0.35, isDeleted: false },
        { date: '2004-04-22', x: 2, y: 0.36, isDeleted: true, exclusionReason: 'example' }
      ]
    },
    diagnostics: {
      rawPointCount: 2,
      usedPointCount: 1,
      excludedPointCount: 1,
      exclusionReasonCounts: { example: 1 }
    }
  })

  assert.equal(mapped.input.wellName, 'X-1')
  assert.equal(mapped.resultId, 'runtime-result-57')
  assert.equal(mapped.persisted, false)
  assert.equal(mapped.input.nodeType, 57)
  assert.equal(mapped.input.pressureSource, 'well_head_tubing_pressure')
  assert.equal(mapped.input.originalFormationPressureMpa, 50)
  assert.equal(mapped.result.originalGasVolume, 123.45)
  assert.equal(mapped.result.gradient, 0.0012)
  assert.equal(mapped.result.slope, 0.0012)
  assert.equal(mapped.result.intercept, 0.34)
  assert.equal(mapped.result.rsquared, 0.987)
  assert.deepEqual(mapped.data.map(point => [point.pseudotime, point.pressure, point.isDeleted]), [
    [1, 0.35, false],
    [2, 0.36, true]
  ])
  assert.equal(mapped.diagnostics.regression.sampleCount, 2)
})

test('rejects non-positive OGIP instead of creating a result object', () => {
  assert.throws(() => mapFlowBalanceCalculationToDynamicResult(validResult({ originalGasVolume: 0 })), /originalGasVolume must be positive/)
})

test('rejects missing transformed series instead of creating chart fallback points', () => {
  const result = validResult()
  delete result.regression.transformedSeries
  assert.throws(() => mapFlowBalanceCalculationToDynamicResult(result), /transformed series must contain at least one point/)
})

test('rejects non-finite regression values', () => {
  assert.throws(() => mapFlowBalanceCalculationToDynamicResult(validResult({ regression: { slope: Number.NaN } })), /regression\.slope must be a finite number/)
})

function validResult(overrides = {}) {
  return {
    resultId: 'runtime-result-58',
    wellName: 'X-1',
    nodeType: 58,
    pressureSource: 'calculated_bottom_hole_pressure',
    pressureKind: 'calculated',
    sourceUnit: 'Pa',
    standardUnit: 'MPa',
    algorithmVersion: 'verified-fmb-v1',
    originalGasVolume: 10,
    regression: {
      slope: 1,
      intercept: 2,
      rSquared: 0.9,
      sampleCount: 1,
      transformedSeries: [{ pseudotime: 1, pressure: 2, isDeleted: false }]
    },
    ...overrides,
    regression: {
      slope: 1,
      intercept: 2,
      rSquared: 0.9,
      sampleCount: 1,
      transformedSeries: [{ pseudotime: 1, pressure: 2, isDeleted: false }],
      ...(overrides.regression || {})
    }
  }
}
