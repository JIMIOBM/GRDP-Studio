import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildPressureGradientComparison,
  calculateDatumPressure,
  calculatePressureIntercept,
  isCalculablePressureRecord,
  normalizePressureRecord,
  pressureSourceKeys
} from './formationPressureGradient.js'

test('折算到统一基准深度并计算深度截距', () => {
  const input = { measuredPressure: 19.65, measuredCoordinate: 1900, gradient: 0.01 }
  assert.equal(calculateDatumPressure({ ...input, referenceCoordinate: 2000 }), 20.65)
  assert.ok(Math.abs(calculatePressureIntercept(input) - 0.65) < 1e-10)
})

test('海拔向上为正时反转坐标差，截距使用加号', () => {
  const input = { measuredPressure: 20, measuredCoordinate: 1000, gradient: 0.01, coordinateMode: 'elevation' }
  assert.equal(calculateDatumPressure({ ...input, referenceCoordinate: 900 }), 21)
  assert.equal(calculatePressureIntercept(input), 30)
})

test('读取智慧气藏实测静压接口常见的驼峰和下划线字段', () => {
  const normalized = normalizePressureRecord({
    well_name: 'X-5',
    test_date: '2010/06/10 00:00:00',
    reservior_pressure: 19.65
  }, 'fallback-well', 'source:X-5:2010/06/10:0')
  assert.equal(normalized.wellName, 'X-5')
  assert.equal(normalized.date, '2010-06-10')
  assert.equal(normalized.measuredPressure, '19.65')
  assert.equal(normalized.measuredCoordinate, '')
  assert.equal(normalized.gradient, '')
})

test('源静压记录有 ID 时使用稳定标识，兼容旧版日期和顺序标识', () => {
  const first = pressureSourceKeys({ id: 18, date: '2025-01-02' }, 'X-1', 0)
  const reordered = pressureSourceKeys({ id: 18, date: '2025-01-02' }, 'X-1', 3)
  assert.equal(first.sourceKey, reordered.sourceKey)
  assert.equal(first.sourceKey, 'source:X-1:id:18')
  assert.equal(first.legacySourceKey, 'source:X-1:2025-01-02:0')
  assert.deepEqual(pressureSourceKeys({ date: '2025-01-02' }, 'X-1', 0), {
    sourceKey: 'source:X-1:2025-01-02:0', legacySourceKey: 'source:X-1:2025-01-02:0'
  })
})

test('不计算缺值、非法日期、负深度、负压力或负梯度', () => {
  const base = {
    wellName: 'X-5', date: '2010-06-10', measuredPressure: '19.65',
    measuredCoordinate: '1900', gradient: '0.01'
  }
  assert.equal(isCalculablePressureRecord(base, '2000'), true)
  assert.equal(isCalculablePressureRecord({ ...base, date: '2010-02-30' }, '2000'), false)
  assert.equal(isCalculablePressureRecord({ ...base, measuredCoordinate: '-1' }, '2000'), false)
  assert.equal(isCalculablePressureRecord({ ...base, measuredPressure: '' }, '2000'), false)
  assert.equal(isCalculablePressureRecord({ ...base, measuredPressure: '-2' }, '2000'), false)
  assert.equal(isCalculablePressureRecord({ ...base, gradient: '-0.01' }, '2000'), false)
  assert.equal(isCalculablePressureRecord({ ...base, gradient: 'not-a-number' }, '2000'), false)
  assert.equal(calculateDatumPressure({ ...base, referenceCoordinate: '2000', measuredPressure: ' ' }), null)
})

test('按井和测压期分组，最近三期排序、缺值留空、重复测点取均值', () => {
  const point = (wellName, date, pressure, depth = 2000) => ({
    wellName, date, measuredPressure: String(pressure), measuredCoordinate: String(depth), gradient: '0.01'
  })
  const rows = [
    point('X-1', '2024-01-01', 9),
    point('X-1', '2024-02-01', 10),
    point('X-1', '2024-03-01', 11, 1900),
    point('X-1', '2024-03-01', 13, 2100),
    point('X-2', '2024-03-01', 12),
    point('X-1', '2024-04-01', 12),
    point('X-2', '2024-04-01', 14),
    { ...point('X-2', '2024-04-01', 100), gradient: '' }
  ]
  const result = buildPressureGradientComparison({
    rows, selectedWells: ['X-1', 'X-2'], referenceCoordinate: '2000', dateRange: 'recent3'
  })
  assert.deepEqual(result.allDates, ['2024-01-01', '2024-02-01', '2024-03-01', '2024-04-01'])
  assert.deepEqual(result.dates, ['2024-02-01', '2024-03-01', '2024-04-01'])
  assert.deepEqual(result.categories, ['X-1', 'X-2'])
  assert.deepEqual(result.series.map(item => item.data), [
    [10, null],
    [12, 12],
    [12, 14]
  ])
  assert.equal(result.validRows.length, 7)
  assert.equal(result.maxPressure, 14)
})

test('时间横轴时按井生成序列，并且全部日期选项保留完整历史', () => {
  const rows = [
    { wellName: 'X-1', date: '2024-01-01', measuredPressure: '10', measuredCoordinate: '2000', gradient: '0' },
    { wellName: 'X-1', date: '2024-02-01', measuredPressure: '11', measuredCoordinate: '2000', gradient: '0' }
  ]
  const result = buildPressureGradientComparison({
    rows, selectedWells: ['X-1'], referenceCoordinate: '2000', dateRange: 'all', chartAxis: 'time'
  })
  assert.deepEqual(result.categories, ['2024-01-01', '2024-02-01'])
  assert.deepEqual(result.seriesKeys, ['X-1'])
  assert.deepEqual(result.series[0].data, [10, 11])
})
