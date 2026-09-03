import test from 'node:test'
import assert from 'node:assert/strict'
import {
  backPressurePotentialDifference,
  fitBackPressureBinomial,
  fitBackPressureExponential,
  solveBackPressureBinomialRate,
  solveBackPressureExponentialRate
} from '../src/utils/backPressureCalculation.js'

const closeTo = (actual, expected, tolerance = 1e-10) =>
  assert.ok(Math.abs(actual - expected) <= tolerance, `${actual} != ${expected}`)

test('fits the back-pressure binomial equation deltaPsi/q = A + Bq', () => {
  const darcy = 0.374074
  const nonDarcy = 0.00133065
  const rates = [20, 30, 40, 50]
  const points = rates.map(flowRate => ({
    flowRate,
    potentialDifference: darcy * flowRate + nonDarcy * flowRate ** 2
  }))
  const result = fitBackPressureBinomial(points)
  closeTo(result.darcyCoefficient, darcy)
  closeTo(result.nonDarcyCoefficient, nonDarcy)
  closeTo(result.rSquared, 1)
  closeTo(
    solveBackPressureBinomialRate(points[2].potentialDifference, darcy, nonDarcy),
    rates[2]
  )
})

test('fits the back-pressure exponential equation q = C(deltaPsi)^n', () => {
  const coefficient = 3.25
  const exponent = 0.78
  const differences = [5, 10, 20, 35]
  const points = differences.map(potentialDifference => ({
    potentialDifference,
    flowRate: coefficient * potentialDifference ** exponent
  }))
  const result = fitBackPressureExponential(points)
  closeTo(result.productivityCoefficient, coefficient)
  closeTo(result.productivityExponent, exponent)
  closeTo(result.rSquared, 1)
  closeTo(
    solveBackPressureExponentialRate(differences[1], coefficient, exponent),
    points[1].flowRate
  )
})

test('rejects non-physical back-pressure coefficients', () => {
  assert.throws(() => fitBackPressureBinomial([
    { flowRate: 10, potentialDifference: 30 },
    { flowRate: 20, potentialDifference: 20 }
  ]), /有效的二项式系数/)
})

test('fits injection back-pressure binomial data using Pwf² - Pr²', () => {
  const reservoirPressure = 40
  const darcy = 0.28
  const nonDarcy = 0.006
  const rates = [5, 10, 15, 20]
  const points = rates.map(flowRate => {
    const expectedDifference = darcy * flowRate + nonDarcy * flowRate ** 2
    const flowingPressure = Math.sqrt(reservoirPressure ** 2 + expectedDifference)
    return {
      flowRate,
      potentialDifference: backPressurePotentialDifference(
        reservoirPressure ** 2,
        flowingPressure ** 2,
        'injection'
      )
    }
  })

  const result = fitBackPressureBinomial(points)
  closeTo(result.darcyCoefficient, darcy)
  closeTo(result.nonDarcyCoefficient, nonDarcy)
  closeTo(result.rSquared, 1)
  closeTo(
    solveBackPressureBinomialRate(points[2].potentialDifference, darcy, nonDarcy),
    rates[2]
  )
})

test('fits injection back-pressure exponential data using positive pressure rise', () => {
  const coefficient = 2.4
  const exponent = 0.82
  const reservoirPressure = 35
  const flowingPressures = [36, 38, 41, 45]
  const points = flowingPressures.map(flowingPressure => {
    const potentialDifference = backPressurePotentialDifference(
      reservoirPressure ** 2,
      flowingPressure ** 2,
      'injection'
    )
    return {
      potentialDifference,
      flowRate: coefficient * potentialDifference ** exponent
    }
  })

  const result = fitBackPressureExponential(points)
  closeTo(result.productivityCoefficient, coefficient)
  closeTo(result.productivityExponent, exponent)
  closeTo(result.rSquared, 1)
})
