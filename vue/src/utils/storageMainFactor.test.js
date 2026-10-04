import test from 'node:test'
import assert from 'node:assert/strict'
import {
  FACTOR_LABELS,
  FACTOR_UNITS,
  buildAbsoluteSeries,
  buildRelativeSeries,
  deviationPercent,
  differenceDirection,
  formatFactorValue,
  sourceLabel,
  toNumberOrNull
} from './storageMainFactor.js'

test('空字符串与非法输入不能当成 0', () => {
  assert.equal(toNumberOrNull(''), null)
  assert.equal(toNumberOrNull('   '), null)
  assert.equal(toNumberOrNull('abc'), null)
  assert.equal(toNumberOrNull('0'), 0)
  assert.equal(toNumberOrNull('-1.5'), -1.5)
  assert.equal(toNumberOrNull(null), null)
  assert.equal(toNumberOrNull(undefined), null)
  assert.equal(toNumberOrNull(NaN), null)
  assert.equal(toNumberOrNull(0), 0)
})

test('差异方向与百分比偏差', () => {
  assert.equal(differenceDirection(-0.35), 'NEGATIVE')
  assert.equal(differenceDirection(2), 'POSITIVE')
  assert.equal(differenceDirection(0), 'ZERO')
  assert.equal(differenceDirection(null), null)
  assert.equal(deviationPercent(110, 100), 10)
  assert.equal(deviationPercent(5, 0), null)
  assert.equal(deviationPercent(5, null), null)
  assert.equal(deviationPercent(null, 100), null)
})

test('来源标签', () => {
  assert.equal(sourceLabel('AUTO'), '自动读取')
  assert.equal(sourceLabel('MANUAL'), '手动填写')
  assert.equal(sourceLabel('MISSING'), '待填写')
  assert.equal(sourceLabel(undefined), '待填写')
})

test('格式化带单位，缺值显示占位而不是 0', () => {
  assert.equal(formatFactorValue(32.1534, 'MPa'), '32.15 MPa')
  assert.equal(formatFactorValue(null, 'MPa'), '待填写')
  assert.equal(formatFactorValue(0.8, '小数'), '0.8 小数')
})

test('四个因素的键、标签与单位齐全且键与后端一致', () => {
  assert.deepEqual(Object.keys(FACTOR_LABELS), ['formationPressure', 'poreVolume', 'gas', 'gasSaturation'])
  assert.equal(FACTOR_LABELS.formationPressure, '地层压力')
  assert.equal(FACTOR_UNITS.poreVolume, '10⁸m³')
})

test('相对序列以理论值为 100% 基准，缺失值不产生柱子', () => {
  const factors = [
    { key: 'formationPressure', theoretical: { value: 32.15 }, actual: { value: 31.80 } },
    { key: 'poreVolume', theoretical: { value: null }, actual: { value: null } }
  ]
  const series = buildRelativeSeries(factors)
  assert.equal(series.length, 2)
  assert.ok(Math.abs(series[0].theoretical - 100) < 1e-9)
  assert.ok(Math.abs(series[0].actual - (31.8 / 32.15) * 100) < 1e-9)
  assert.equal(series[1].theoretical, null)
  assert.equal(series[1].actual, null)
})

test('理论值为 0 时相对序列不能除零', () => {
  const series = buildRelativeSeries([{ key: 'gasSaturation', theoretical: { value: 0 }, actual: { value: 0.5 } }])
  assert.equal(series[0].theoretical, 100) // 基准本身恒为 100%
  assert.equal(series[0].actual, null)
})

test('绝对值序列按因素分面，单位不同不混在一张图', () => {
  const series = buildAbsoluteSeries([{ key: 'formationPressure', theoretical: { value: 32.15 }, actual: { value: 31.8 } }])
  assert.equal(series[0].unit, 'MPa')
  assert.equal(series[0].theoretical, 32.15)
  assert.equal(series[0].actual, 31.8)
})
