import test from 'node:test'
import assert from 'node:assert/strict'
import { buildOriginalProductivityInputItems } from './originalProductivityInput.js'

test('omits incomplete rows and falls back to the selected formation pressure', () => {
  const rows = buildOriginalProductivityInputItems([
    { sequence: 1, flowRate: 39.63, recoveryPressure: null, flowingPressure: 33.216 },
    { sequence: 2, flowRate: null, recoveryPressure: 50, flowingPressure: 30 },
    { sequence: 3, flowRate: 40, recoveryPressure: 50, flowingPressure: null }
  ], 50)
  assert.deepEqual(rows, [{
    testPointNumber: 1,
    reserviorPressure: 50,
    testDailyGasProduction: 39.63,
    testFlowPressure: 33.216,
    testDailyOilProduction: 0
  }])
})

test('preserves valid explicit recovery pressures and sorts by point number', () => {
  const rows = buildOriginalProductivityInputItems([
    { sequence: 2, flowRate: 30, recoveryPressure: 48, flowingPressure: 31 },
    { sequence: 1, flowRate: 20, recoveryPressure: 46, flowingPressure: 30 }
  ], 50)
  assert.deepEqual(rows.map(row => [row.testPointNumber, row.reserviorPressure]), [[1, 46], [2, 48]])
})

test('rejects invalid production and injection measurements before submission', () => {
  assert.throws(() => buildOriginalProductivityInputItems([
    { sequence: 1, flowRate: 0, recoveryPressure: 50, flowingPressure: 30 }
  ], 50), /产量必须大于 0/)
  assert.throws(() => buildOriginalProductivityInputItems([
    { sequence: 1, flowRate: 20, recoveryPressure: 30, flowingPressure: 32 }
  ], 50), /恢复压力必须大于测试流压/)
  assert.throws(() => buildOriginalProductivityInputItems([
    { sequence: 1, flowRate: 20, recoveryPressure: 30, flowingPressure: 28 }
  ], 50, 'injection'), /注入压力必须大于地层压力/)
  assert.throws(() => buildOriginalProductivityInputItems([
    { sequence: 1, flowRate: 20, recoveryPressure: 30, flowingPressure: 28 }
  ], 30, 'injection'), /注入压力必须大于地层压力/)
})

test('rejects invalid pressure, nonfinite inputs, and duplicate sequence numbers', () => {
  assert.throws(() => buildOriginalProductivityInputItems([
    { sequence: 1, flowRate: 20, recoveryPressure: null, flowingPressure: 28 }
  ], 'bad'), /压力数据必须是有效数值/)
  assert.throws(() => buildOriginalProductivityInputItems([
    { sequence: 1, flowRate: 'Infinity', recoveryPressure: 50, flowingPressure: 30 }
  ], 50), /产量必须大于 0/)
  assert.throws(() => buildOriginalProductivityInputItems([
    { sequence: 1, flowRate: 20, recoveryPressure: 50, flowingPressure: 30 },
    { sequence: 1, flowRate: 21, recoveryPressure: 51, flowingPressure: 31 }
  ], 50), /测点序号不能重复/)
})
