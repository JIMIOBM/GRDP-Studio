import test from 'node:test'
import assert from 'node:assert/strict'
import { deviationValues, tubingRows, productionValues, wellRows, latestRow, rowsOf } from '../src/utils/temperatureSources.js'

test('depth and angle come from the same deepest deviation row', () => {
  assert.deepEqual(deviationValues({ data: { items: [
    { measuredDepth: 20, inclination: 5 }, { measuredDepth: 3000, inclination: 40 }
  ] } }, 'A'), { depth: 3000, angle: 40 })
})
test('selects latest tubing only, never casing diameter', () => {
  const response = { items: [
    { type: '套管', innerDiameter: 150, date: '2026-09-01' },
    { type: '油管', innerDiameter: 62, innerRoughness: 0.02, date: '2026-08-01' },
    { type: '油管', innerDiameter: 70, innerRoughness: 0.016, date: '2026-09-01' }
  ] }
  assert.equal(tubingRows(response, 'A')[0].diameter, 70)
  assert.equal(tubingRows(response, 'A')[0].roughness, 0.016)
  assert.throws(() => tubingRows({ items: [response.items[0]] }, 'A'), /油管/)
})
test('latest production is well scoped and does not substitute head values at bottom', () => {
  const row = latestRow(wellRows([
    { well_name: 'B', date: '2026-10-01', daily_gas_production: 99 },
    { well_name: 'A', date: '2026-08-01', daily_gas_production: 1 },
    { well_name: 'A', date: '2026-09-01', daily_gas_production: 25000, daily_water_production: 0,
      well_head_tubing_pressure: 3.8, well_head_tubing_temperature: 30, measured_bottom_hole_pressure: 12 }
  ], 'A'))
  const fields = [{ name: 'daily_gas_production', unit_label: 'm³/d' }]
  assert.deepEqual(productionValues(row, 'wellhead', fields), { fWh: 3.8, tWh: 30, qGas: 2.5, qLiq: 0 })
  assert.deepEqual(productionValues(row, 'bottomhole', fields), { fWh: 12, tWh: null, qGas: 2.5, qLiq: 0 })
})
test('normalizes array rows using metadata and preserves missing numeric fields', () => {
  const row = rowsOf({ data: { fields: [{ name: 'well_name' }, { name: 'daily_gas_production' }], rows: [['A', '']] } })[0]
  assert.equal(row.well_name, 'A')
  assert.equal(productionValues(row, 'wellhead').qGas, null)
})

test('well-scoped production endpoint accepts unnamed rows and maps data-management fields', () => {
  const response = { data: { items: [
    { date: '2026-08-01', wellHeadTubingPressure: 2 },
    { date: '2026-09-04', wellHeadTubingPressure: 3.8, wellHeadTubingTemperature: 30, dailyGasProduction: 2.5, dailyWaterProduction: 2 },
    { wellName: '另一口井', date: '2026-10-01', wellHeadTubingPressure: 99 }
  ] } }
  const row = latestRow(wellRows(rowsOf(response), '当前井', true))
  assert.equal(row.date, '2026-09-04')
  assert.deepEqual(productionValues(row, 'wellhead'), { fWh: 3.8, tWh: 30, qGas: 2.5, qLiq: 2 })
})
