'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const { FlowBalanceRepository } = require('../src/repository/flowBalanceRepository')

test('repository uses scoped parameterized production and pressure queries', async () => {
  const calls = []
  const db = {
    async query(sql, params) {
      calls.push({ sql, params })
      if (sql.includes('FROM project_production_data')) {
        if (sql.includes('well_head_tubing_pressure AS wellHeadTubingPressure')) {
          return [pressureRow({ wellHeadTubingPressure: '10000000' })]
        }
        if (sql.includes('calculated_bottom_hole_pressure AS calculatedBottomHolePressure')) {
          return [pressureRow({ calculatedBottomHolePressure: '20000000' })]
        }
        return [productionRow({ dailyGas: '1' })]
      }
      if (sql.includes('FROM project_gas_property')) return [gasPropertyRow()]
      if (sql.includes('FROM project_other_data')) return [otherDataRow()]
      return []
    }
  }

  const input = await new FlowBalanceRepository(db).loadInput({ projectId: 4, gasReservoirId: 2, wellNames: ['X-1'] })
  const well = input.wells[0]
  const point = well.production[0]

  assert.equal(well.nodeSources.length, 2)
  assert.equal(point.dailyGas.value, 1)
  assert.equal(point.wellHeadTubingPressure.rawPressurePa, 10000000)
  assert.equal(point.wellHeadTubingPressure.pressureMpa, 10)
  assert.equal(point.wellHeadTubingPressure.sourceUnit, 'Pa')
  assert.equal(point.wellHeadTubingPressure.standardUnit, 'MPa')
  assert.equal(point.calculatedBottomHolePressure.rawPressurePa, 20000000)
  assert.equal(point.calculatedBottomHolePressure.pressureMpa, 20)
  assert.equal(point.calculatedBottomHolePressure.pressureSource, 'calculated_bottom_hole_pressure')
  assert.equal(point.calculatedBottomHolePressure.pressureKind, 'calculated')
  assert.equal(well.nodeSources[0].pressureSource, 'well_head_tubing_pressure')
  assert.equal(well.nodeSources[0].sourceUnit, 'Pa')
  assert.equal(well.nodeSources[0].standardUnit, 'MPa')
  assert.equal(well.nodeSources[1].nodeType, 58)
  assert.equal(well.nodeSources[1].pressureSource, 'calculated_bottom_hole_pressure')
  assert.equal(well.nodeSources[1].pressureKind, 'calculated')
  assert.equal(well.nodeSources[1].sourceUnit, 'Pa')
  assert.equal(well.nodeSources[1].standardUnit, 'MPa')

  assert.equal(calls[0].params[0], 4)
  assert.equal(calls[0].params[1], 2)
  assert.equal(calls[0].params[2], 'X-1')
  assert.match(calls[0].sql, /project_id = \?/)
  assert.match(calls[0].sql, /project_gas_reservoir_id = \?/)
  assert.match(calls[0].sql, /well_name IN \(\?\)/)
  assert.equal(calls.some(call => /calculated_bottom_hole_pressure AS calculatedBottomHolePressure/.test(call.sql)), true)
  assert.equal(calls.some(call => /measured_bottom_hole_pressure/i.test(call.sql)), false)
  assert.equal(calls.some(call => /COALESCE/i.test(call.sql)), false)
})

test('repository accepts shut-in production rows with zero daily gas', async () => {
  const db = {
    async query(sql) {
      if (sql.includes('FROM project_production_data')) {
        if (sql.includes('well_head_tubing_pressure AS wellHeadTubingPressure')) {
          return [pressureRow({ wellHeadTubingPressure: '10000000' })]
        }
        if (sql.includes('calculated_bottom_hole_pressure AS calculatedBottomHolePressure')) {
          return [pressureRow({ calculatedBottomHolePressure: '20000000' })]
        }
        return [productionRow({ dailyGas: '0' })]
      }
      if (sql.includes('FROM project_gas_property')) return [gasPropertyRow()]
      if (sql.includes('FROM project_other_data')) return [otherDataRow()]
      return []
    }
  }

  const input = await new FlowBalanceRepository(db).loadInput({ projectId: 4, gasReservoirId: 2, wellNames: ['X-1'] })
  assert.equal(input.wells[0].production[0].dailyGas.value, 0)
  assert.equal(input.wells[0].nodeSources[0].sampleCount, 1)
  assert.equal(input.wells[0].nodeSources[1].sampleCount, 1)
})

function productionRow(overrides = {}) {
  return {
    id: '1',
    projectId: '4',
    gasReservoirId: '2',
    wellName: 'X-1',
    date: '2004-04-21 00:00:00',
    dailyGas: '1',
    cumulativeGas: '10',
    waterGasRatio: '0.1',
    wellHeadCasingPressure: '50',
    wellHeadTubingTemperature: '300',
    wellHeadCasingTemperature: '299',
    ...overrides
  }
}

function pressureRow(overrides = {}) {
  return {
    id: '1',
    projectId: '4',
    gasReservoirId: '2',
    wellName: 'X-1',
    date: '2004-04-21 00:00:00',
    ...overrides
  }
}

function gasPropertyRow() {
  return {
    id: '2',
    projectId: '4',
    gasReservoirId: '2',
    wellName: 'X-1',
    gasType: 'dry',
    specificGravity: '0.58',
    hydrogenSulfide: '0.0462',
    carbonDioxide: '0.0396',
    nitrogen: '0'
  }
}

function otherDataRow() {
  return {
    id: '3',
    projectId: '4',
    gasReservoirId: '2',
    wellName: 'X-1',
    originalFormationPressure: '50000000',
    formationTemperature: '353.15'
  }
}
