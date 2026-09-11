import test from 'node:test'
import assert from 'node:assert/strict'
import {
  ATMOSPHERIC_PRESSURE_MPA,
  calculateExponentialCoefficientCurve,
  calculateExponentialCoefficientIprFamily,
  normalizeCoefficientFitPoint
} from '../src/utils/productivityCoefficientCalculation.js'

test('压力法采气曲线从地层压力向下，注气曲线从地层压力向上', () => {
  const common = {
    reservoirPressure: 10,
    coefficient: 2,
    exponent: 1,
    calculationMethod: '压力法',
    steps: 10
  }
  const production = calculateExponentialCoefficientCurve(common)
  const injection = calculateExponentialCoefficientCurve({
    ...common,
    operationType: 'injection',
    maximumFlowingPressure: 10 + (10 - ATMOSPHERIC_PRESSURE_MPA)
  })

  assert.deepEqual(production.points[0], {
    flowRate: 0,
    flowingPressure: 10,
    potentialDifference: 0
  })
  assert.ok(Math.abs(
    production.points.at(-1).flowingPressure - ATMOSPHERIC_PRESSURE_MPA
  ) < 1e-12)
  assert.equal(production.limitRate, injection.limitRate)
  assert.equal(injection.points[0].flowingPressure, 10)
  assert.ok(injection.points.at(-1).flowingPressure > 10)
  assert.ok(injection.points.every((point, index, points) =>
    index === 0 || point.flowRate >= points[index - 1].flowRate))
  assert.ok(injection.points.every((point, index, points) =>
    index === 0 || point.flowingPressure >= points[index - 1].flowingPressure))
})

test('压力平方法交换 Pr/Pwf 后保持正压差和相同极限流量', () => {
  const common = {
    reservoirPressure: 12,
    coefficient: 0.5,
    exponent: 0.8,
    calculationMethod: '压力平方方法'
  }
  const production = calculateExponentialCoefficientCurve(common)
  const injection = calculateExponentialCoefficientCurve({
    ...common,
    operationType: 'injection',
    maximumFlowingPressure: Math.sqrt(
      2 * common.reservoirPressure ** 2 - ATMOSPHERIC_PRESSURE_MPA ** 2
    )
  })

  assert.ok(Math.abs(production.limitRate - injection.limitRate) < 1e-9)
  assert.ok(injection.points.every(point => point.potentialDifference >= 0))
})

test('拟压力法使用PVT插值，注气曲线向右上方增长', () => {
  const pvtResultRows = [
    { pressure: 0, pseudoPressure: 0 },
    { pressure: 10, pseudoPressure: 100 },
    { pressure: 20, pseudoPressure: 200 }
  ]
  const injection = calculateExponentialCoefficientCurve({
    reservoirPressure: 10,
    coefficient: 1,
    exponent: 1,
    calculationMethod: '拟压力',
    operationType: 'injection',
    maximumFlowingPressure: 20,
    pvtResultRows,
    steps: 10
  })

  assert.ok(injection.limitPressure > 10)
  assert.ok(injection.points.at(-1).flowRate > 0)
  assert.ok(injection.points.every((point, index, points) =>
    index === 0 || point.flowingPressure >= points[index - 1].flowingPressure))
})

test('注气必须明确填写大于地层压力的最大井底注入压力', () => {
  assert.throws(() => calculateExponentialCoefficientCurve({
    reservoirPressure: 10,
    coefficient: 1,
    exponent: 1,
    calculationMethod: '拟压力',
    operationType: 'injection'
  }), /最大井底注入压力必须大于地层压力/)
})

test('拟压力注气的PVT数据必须覆盖最大井底注入压力', () => {
  assert.throws(() => calculateExponentialCoefficientCurve({
    reservoirPressure: 10,
    coefficient: 1,
    exponent: 1,
    calculationMethod: '拟压力',
    operationType: 'injection',
    maximumFlowingPressure: 20,
    pvtResultRows: [
      { pressure: 0, pseudoPressure: 0 },
      { pressure: 10, pseudoPressure: 100 }
    ]
  }), /范围仅为 0～10/)
})

test('拟合点使用用户输入的气量和井底压力', () => {
  const curve = calculateExponentialCoefficientCurve({
    reservoirPressure: 10,
    coefficient: 2,
    exponent: 1,
    calculationMethod: '压力法'
  })
  assert.deepEqual(normalizeCoefficientFitPoint({
    operationType: 'production',
    flowRate: '8.5',
    flowingPressure: '6',
    curve
  }), { flowRate: 8.5, flowingPressure: 6 })
})

test('拟合点气量和压力必须同时填写且压力在曲线范围内', () => {
  const curve = calculateExponentialCoefficientCurve({
    reservoirPressure: 10,
    coefficient: 2,
    exponent: 1,
    calculationMethod: '压力法'
  })
  assert.throws(() => normalizeCoefficientFitPoint({
    operationType: 'production',
    flowRate: '8.5',
    flowingPressure: '',
    curve
  }), /同时填写采气拟合点/)
  assert.throws(() => normalizeCoefficientFitPoint({
    operationType: 'injection',
    flowRate: '8.5',
    flowingPressure: '11',
    curve
  }), /注气拟合点井底压力应在/)
})

test('采气IPR图按修正系数生成十个地层压力级别的曲线组', () => {
  const family = calculateExponentialCoefficientIprFamily({
    reservoirPressure: 56.34,
    coefficient: 2.099,
    exponent: 0.8,
    calculationMethod: '压力法',
    operationType: 'production',
    steps: 10
  })

  assert.equal(family.length, 10)
  assert.deepEqual(
    family.map(item => Number(item.reservoirPressure.toFixed(3))),
    [56.34, 50.706, 45.072, 39.438, 33.804, 28.17, 22.536, 16.902, 11.268, 5.634]
  )
  assert.ok(family.every(item => item.curve.points[0].flowRate === 0))
  assert.ok(family.every(item => item.curve.points[0].flowingPressure === item.reservoirPressure))
  assert.ok(family.every((item, index) => index === 0 || item.curve.limitRate < family[index - 1].curve.limitRate))
})

test('注气IPR曲线组从各级地层压力上升到同一个最大井底注入压力', () => {
  const family = calculateExponentialCoefficientIprFamily({
    reservoirPressure: 10,
    coefficient: 1,
    exponent: 1,
    calculationMethod: '压力法',
    operationType: 'injection',
    maximumFlowingPressure: 20,
    levels: 5,
    steps: 10
  })

  assert.equal(family.length, 5)
  assert.ok(family.every(item => item.curve.points[0].flowingPressure === item.reservoirPressure))
  assert.ok(family.every(item => item.curve.points.at(-1).flowingPressure === 20))
  assert.ok(family.every(item => item.curve.points.at(-1).flowRate > 0))
})

test('rejects exponents outside the engineering range', () => {
  for (const exponent of [0.49, 1.01, 24, 50]) {
    assert.throws(() => calculateExponentialCoefficientCurve({
      reservoirPressure: 10,
      coefficient: 1,
      exponent,
      calculationMethod: '压力法'
    }), /0.5～1/)
  }
})
