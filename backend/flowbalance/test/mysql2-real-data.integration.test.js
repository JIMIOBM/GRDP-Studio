'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const { createFlowBalanceRuntime } = require('../src')

const EXPECTED_SAMPLE_COUNT = 3556

test('loads real X-1 nodeType 57 and 58 FlowBalance pressure series through mysql2', async (t) => {
  if (!process.env.FLOWBALANCE_DB_HOST || !process.env.FLOWBALANCE_DB_PASSWORD) {
    t.skip('FLOWBALANCE_DB_* environment variables are required for mysql2 real database validation')
    return
  }

  const runtime = createFlowBalanceRuntime()
  t.after(async () => runtime.close())

  const input = await runtime.service.prepareCalculationInput({ projectId: 4, gasReservoirId: 2, wellNames: ['X-1'] })
  const well = input.wells[0]

  assert.equal(well.production.length, EXPECTED_SAMPLE_COUNT)
  assert.equal(well.nodeSources.some(node => node.nodeType === 57 && node.pressureSource === 'well_head_tubing_pressure' && node.sampleCount === EXPECTED_SAMPLE_COUNT), true)
  assert.equal(well.nodeSources.some(node => node.nodeType === 58 && node.pressureSource === 'calculated_bottom_hole_pressure' && node.pressureKind === 'calculated' && node.sampleCount === EXPECTED_SAMPLE_COUNT), true)

  let previousTime = null
  let wellHeadMin = Number.POSITIVE_INFINITY
  let wellHeadMax = Number.NEGATIVE_INFINITY
  let bottomHoleMin = Number.POSITIVE_INFINITY
  let bottomHoleMax = Number.NEGATIVE_INFINITY

  for (const point of well.production) {
    assert.equal(point.projectId, 4)
    assert.equal(point.gasReservoirId, 2)
    assert.equal(point.wellName, 'X-1')

    const time = new Date(point.date).getTime()
    assert.equal(Number.isNaN(time), false)
    if (previousTime !== null) assert.equal(time >= previousTime, true)
    previousTime = time

    assert.equal(point.wellHeadTubingPressure.pressureSource, 'well_head_tubing_pressure')
    assert.equal(point.wellHeadTubingPressure.sourceUnit, 'Pa')
    assert.equal(point.wellHeadTubingPressure.standardUnit, 'MPa')
    assert.equal(point.wellHeadTubingPressure.pressureMpa, point.wellHeadTubingPressure.rawPressurePa / 1000000)

    assert.equal(point.calculatedBottomHolePressure.pressureSource, 'calculated_bottom_hole_pressure')
    assert.equal(point.calculatedBottomHolePressure.pressureKind, 'calculated')
    assert.equal(point.calculatedBottomHolePressure.sourceUnit, 'Pa')
    assert.equal(point.calculatedBottomHolePressure.standardUnit, 'MPa')
    assert.equal(point.calculatedBottomHolePressure.pressureMpa, point.calculatedBottomHolePressure.rawPressurePa / 1000000)

    wellHeadMin = Math.min(wellHeadMin, point.wellHeadTubingPressure.pressureMpa)
    wellHeadMax = Math.max(wellHeadMax, point.wellHeadTubingPressure.pressureMpa)
    bottomHoleMin = Math.min(bottomHoleMin, point.calculatedBottomHolePressure.pressureMpa)
    bottomHoleMax = Math.max(bottomHoleMax, point.calculatedBottomHolePressure.pressureMpa)
  }

  assert.equal(wellHeadMin, 7.08)
  assert.equal(wellHeadMax, 24.06)
  assert.equal(round(bottomHoleMin, 12), 11.226379428191)
  assert.equal(round(bottomHoleMax, 12), 33.68923877741)

  const repositorySource = `${runtime.repository.findProductionBaseSeries}\n${runtime.repository.findWellHeadTubingPressureSeries}\n${runtime.repository.findCalculatedBottomHolePressureSeries}`
  assert.doesNotMatch(repositorySource, /measured_bottom_hole_pressure/i)
  assert.doesNotMatch(repositorySource, /COALESCE/i)

  const response = await runtime.service.calculate({ projectId: 4, gasReservoirId: 2, wellNames: ['X-1'] })
  assert.equal(response.persisted, false)
  assert.equal(response.idKind, 'runtime')
  assert.deepEqual(response.nodes.map(node => node.nodeType).sort(), [57, 58])
  for (const node of response.nodes) {
    assert.equal(node.resultId.length > 0, true)
    assert.equal(node.result.resultId, node.resultId)
    assert.equal(node.result.persisted, false)
    assert.equal(node.result.regression.sampleCount > 2, true)
    assert.equal(node.result.regression.slope < 0, true)
    assert.equal(node.result.originalGasVolume > 0, true)
    assert.equal(node.data.length, EXPECTED_SAMPLE_COUNT)
  }
})

function round(value, digits) {
  const factor = 10 ** digits
  return Math.round(value * factor) / factor
}
