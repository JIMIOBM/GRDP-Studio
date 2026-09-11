import test from 'node:test'
import assert from 'node:assert/strict'
import {
  backPressurePotentialDifference,
  fitBackPressureBinomial,
  fitBackPressureExponential,
  resequenceBackPressurePoints,
  solveBackPressureBinomialRate,
  solveBackPressureExponentialRate
} from '../src/utils/backPressureCalculation.js'

const closeTo = (actual, expected, tolerance = 1e-10) =>
  assert.ok(Math.abs(actual - expected) <= tolerance, `${actual} != ${expected}`)

test('合并多个日期批次时按当前表格行重新编号', () => {
  const points = resequenceBackPressurePoints([
    { sequence: 1, date: '2011-08-27' },
    { sequence: 1, date: '2013-08-22' },
    { sequence: 1, date: '2014-08-17' },
    { sequence: 2, date: '2011-08-27' }
  ])
  assert.deepEqual(points.map(point => point.sequence), [1, 2, 3, 4])
  assert.deepEqual(points.map(point => point.date), [
    '2011-08-27', '2013-08-22', '2014-08-17', '2011-08-27'
  ])
})

test('回压二项式拟合与反算产量', () => {
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
  closeTo(solveBackPressureBinomialRate(points[2].potentialDifference, darcy, nonDarcy), rates[2])
})

test('回压指数式拟合与反算产量', () => {
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
  closeTo(solveBackPressureExponentialRate(differences[1], coefficient, exponent), points[1].flowRate)
})

test('注气使用正流量和正压力函数升', () => {
  closeTo(backPressurePotentialDifference(35 ** 2, 41 ** 2, 'injection'), 456)
  closeTo(backPressurePotentialDifference(41 ** 2, 35 ** 2, 'production'), 456)
  assert.ok(Number.isNaN(backPressurePotentialDifference('无效', 41, 'injection')))
})

test('注气回压数据可拟合二项式和指数式', () => {
  const rates = [5, 10, 15, 20]
  const binomialPoints = rates.map(flowRate => ({
    flowRate,
    potentialDifference: 0.28 * flowRate + 0.006 * flowRate ** 2
  }))
  const binomial = fitBackPressureBinomial(binomialPoints)
  closeTo(binomial.darcyCoefficient, 0.28)
  closeTo(binomial.nonDarcyCoefficient, 0.006)

  const exponentialPoints = [20, 50, 100, 180].map(potentialDifference => ({
    potentialDifference,
    flowRate: 2.4 * potentialDifference ** 0.82
  }))
  const exponential = fitBackPressureExponential(exponentialPoints)
  closeTo(exponential.productivityCoefficient, 2.4)
  closeTo(exponential.productivityExponent, 0.82)
})

test('拒绝非物理拟合点与退化自变量', () => {
  assert.throws(() => fitBackPressureBinomial([
    { flowRate: 10, potentialDifference: 30 },
    { flowRate: 20, potentialDifference: 20 }
  ]), /有效的二项式系数/)
  assert.throws(() => fitBackPressureExponential([
    { flowRate: 10, potentialDifference: 4 },
    { flowRate: 20, potentialDifference: 4 }
  ]), /自变量不能全部相同/)
})

test('求解器对非法系数、溢出和零压差安全返回 0', () => {
  assert.equal(solveBackPressureBinomialRate(10, -1, 2), 0)
  assert.equal(solveBackPressureBinomialRate(10, 1, -2), 0)
  assert.equal(solveBackPressureExponentialRate(10, Number.NaN, 0.8), 0)
  assert.equal(solveBackPressureExponentialRate(Number.MAX_VALUE, Number.MAX_VALUE, 2), 0)
  assert.equal(solveBackPressureExponentialRate(0, 2, 0.8), 0)
})

test('二项式反算在大达西系数下保持数值稳定', () => {
  const darcy = 1e16
  const rate = 2
  const difference = darcy * rate + rate ** 2
  closeTo(solveBackPressureBinomialRate(difference, darcy, 1), rate, 1e-12)
})
