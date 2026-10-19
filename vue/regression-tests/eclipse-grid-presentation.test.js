import test from 'node:test'
import assert from 'node:assert/strict'
import { gridReportFrames, gridValueStats, gridValueRatio, gridValueHistogram, gridLegendTicks, gridValueColor, gridFieldUnit } from '../src/views/SoftwareIntegration/eclipseGridPresentation.js'

test('report playback keeps source and field identity, sorts actual steps without creating missing steps', () => {
  const f = (timeStep, file = 'CASE.UNRST', keyword = 'PRESSURE') => ({ timeStep, file, keyword, dataType: 'REAL', count: 3 })
  const selected = f(8)
  assert.deepEqual(gridReportFrames([selected, f(2), f(3, 'OTHER.UNRST'), f(4, 'CASE.UNRST', 'SWAT'), f(undefined), f(0)], selected).map(x => x.timeStep), [2, 8])
  assert.deepEqual(gridReportFrames([f(8), f(8)], selected), [])
  assert.deepEqual(gridReportFrames([f(2)], f(undefined)), [])
  assert.deepEqual(gridReportFrames([f(10, 'CASE.X0010'), f(2, 'CASE.X0002')], f(2, 'CASE.X0002')).map(x => x.timeStep), [2, 10])
})

test('statistics keep finite numeric values and histogram accounts for every valid cell', () => {
  const values = [-3, 0, 3, 6, null, NaN, Infinity, '2']
  const stats = gridValueStats(values)
  assert.deepEqual(stats, { min: -3, max: 6, mean: 1.5, count: 4 })
  const bins = gridValueHistogram(values, stats)
  assert.equal(bins.reduce((sum, bin) => sum + bin.count, 0), 4)
  assert.equal(bins[0].count, 1); assert.equal(bins.at(-1).count, 1)
  assert.equal(gridValueStats([null, NaN]), null)
  assert.deepEqual(gridValueHistogram([], null), [])
})

test('constant, large and signed ranges produce finite legends and colors; locked scale clamps extremes', () => {
  const same = gridValueStats([2, 2])
  assert.equal(gridValueRatio(2, same), 0.5)
  assert.equal(gridValueHistogram([2, 2], same)[0].count, 2)
  assert.deepEqual(gridLegendTicks(same), [2, 2, 2, 2, 2])
  assert.equal(gridValueStats([1e308, 1e308]).mean, 1e308)
  assert.equal(gridValueRatio(0, { min: -1e308, max: 1e308 }), 0.5)
  assert.equal(gridValueRatio(-10, { min: 0, max: 1 }), 0)
  assert.equal(gridValueRatio(20, { min: 0, max: 1 }), 1)
  assert.equal(gridValueColor(null, same), '#94a3b8')
  assert.equal(gridFieldUnit({ keyword: 'PRESSURE' }), '模型原单位')
  assert.equal(gridFieldUnit({ keyword: 'PRESSURE', unit: 'PSIA' }), 'PSIA')
  assert.equal(gridFieldUnit({ keyword: 'PRESSURE' }, 'FIELD'), 'psia')
  assert.equal(gridFieldUnit({ keyword: 'PRESSURE' }, 'METRIC'), 'barsa')
  assert.equal(gridFieldUnit({ keyword: 'TEMP' }, 'FIELD'), '°F')
  assert.equal(gridFieldUnit({ keyword: 'TEMP' }, 'METRIC'), '°C')
  assert.equal(gridFieldUnit({ keyword: 'TEMP' }, 'UNKNOWN'), '模型原单位')
})

test('large fields do not rely on variadic array min/max limits', () => {
  const values = Array.from({ length: 300000 }, (_, i) => i % 25)
  const stats = gridValueStats(values)
  assert.equal(stats.max, 24); assert.equal(stats.count, values.length)
  assert.equal(gridValueHistogram(values, stats).reduce((sum, bin) => sum + bin.count, 0), values.length)
})
