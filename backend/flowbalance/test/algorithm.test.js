'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const { FlowBalanceCalculator } = require('../src/algorithm/flowingMaterialBalanceCalculator')
const { calculateZFactor } = require('../src/algorithm/gasDeviationFactor')
const { linearRegression } = require('../src/algorithm/regression')

test('linear regression returns the exact declining line', () => {
  const result = linearRegression([{ x: 1, y: 8 }, { x: 2, y: 6 }, { x: 3, y: 4 }])
  assert.equal(result.slope, -2)
  assert.equal(result.intercept, 10)
  assert.equal(result.rSquared, 1)
  assert.equal(result.sampleCount, 3)
})

test('DAK returns finite positive z factors for the verified X-1 pressure range', () => {
  for (const pressureMpa of [7.08, 11.22637942819062, 24.06, 33.68923877741049, 50]) {
    const z = calculateZFactor({
      pressureMpa,
      temperatureK: 353.15,
      specificGravity: 0.58,
      hydrogenSulfide: 0.0462,
      carbonDioxide: 0.0396
    })
    assert.equal(Number.isFinite(z) && z > 0, true)
  }
})

test('calculator creates distinct non-persistent node 57 and 58 results without fallback', () => {
  const run = new FlowBalanceCalculator().calculate(calculationFixture())
  assert.equal(run.persisted, false)
  assert.equal(run.idKind, 'runtime')
  assert.equal(run.calculations.length, 2)

  const wellhead = run.calculations.find(item => item.nodeType === 57)
  const bottomhole = run.calculations.find(item => item.nodeType === 58)
  assert.equal(wellhead.pressureSource, 'well_head_tubing_pressure')
  assert.equal(wellhead.pressureKind, 'wellhead')
  assert.equal(bottomhole.pressureSource, 'calculated_bottom_hole_pressure')
  assert.equal(bottomhole.pressureKind, 'calculated')
  assert.notEqual(wellhead.resultId, bottomhole.resultId)
  assert.equal(wellhead.persisted, false)
  assert.equal(bottomhole.persisted, false)
  assert.equal(wellhead.regression.sampleCount, 6)
  assert.equal(bottomhole.regression.sampleCount, 6)
  assert.equal(wellhead.regression.slope < 0, true)
  assert.equal(bottomhole.regression.slope < 0, true)
  assert.equal(wellhead.originalGasVolume > 600, true)
  assert.equal(bottomhole.originalGasVolume > 600, true)
  assert.equal(wellhead.originalGasVolumeUnit, 'cumulative_gas_production source unit')
})

test('partial-result mode keeps a valid pressure source and reports the failing source', () => {
  const fixture = calculationFixture()
  fixture.wells[0].production.forEach(point => {
    point.calculatedBottomHolePressure.pressureMpa = null
  })

  const run = new FlowBalanceCalculator().calculate(fixture, { includePartialErrors: true })
  assert.equal(run.calculations.length, 1)
  assert.equal(run.calculations[0].nodeType, 57)
  assert.equal(run.errors.length, 1)
  assert.equal(run.errors[0].nodeType, 58)
  assert.equal(run.errors[0].persisted, false)
  assert.equal(run.errors[0].code, 'FLOW_BALANCE_INVALID_DATA')
})

test('official backend error log takes precedence over the independent preview calculation', () => {
  const fixture = calculationFixture()
  fixture.wells[0].officialFlowBalanceStatus = {
    status: 'error',
    source: 'official-backend-log',
    message: '动态储量分析-流动质平衡:参与回归分析的数据点数必须大于0',
    createdAt: '2026-07-18 10:13:32'
  }

  const run = new FlowBalanceCalculator().calculate(fixture, { includePartialErrors: true })
  assert.equal(run.calculations.length, 0)
  assert.deepEqual(run.errors.map(item => item.nodeType).sort(), [57, 58])
  assert.equal(run.errors.every(item => item.message.includes('参与回归分析的数据点数必须大于0')), true)
  assert.equal(run.errors.every(item => item.details.statusSource === 'official-backend-log'), true)
})

function calculationFixture() {
  const production = [1, 2, 3, 4, 5, 6].map(day => ({
    date: `2004-01-0${day}`,
    dailyGas: { value: 10 + day },
    cumulativeGas: { value: day * 100 },
    waterGasRatio: { value: 0.01 },
    wellHeadTubingPressure: { pressureMpa: 48 - day * 4 },
    calculatedBottomHolePressure: { pressureMpa: 49 - day * 3.5 }
  }))
  return {
    projectId: 4,
    gasReservoirId: 2,
    wells: [{
      wellName: 'X-1',
      reservoirInput: {
        originalFormationPressure: { value: 50000000 },
        formationTemperature: { value: 353.15 },
        specificGravity: { value: 0.58 },
        hydrogenSulfide: { value: 0.0462 },
        carbonDioxide: { value: 0.0396 },
        nitrogen: { value: 0 }
      },
      production,
      nodeSources: [
        { nodeType: 57, pressureSource: 'well_head_tubing_pressure', pressureKind: 'wellhead', sourceUnit: 'Pa', standardUnit: 'MPa' },
        { nodeType: 58, pressureSource: 'calculated_bottom_hole_pressure', pressureKind: 'calculated', sourceUnit: 'Pa', standardUnit: 'MPa' }
      ]
    }]
  }
}
