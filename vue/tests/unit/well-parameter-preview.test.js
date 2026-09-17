import test from 'node:test'
import assert from 'node:assert/strict'
import { sourceReservoirPressure, supportsPressureScenario } from '../../src/views/SoftwareIntegration/wellParameterPreview.js'

test('gas pressure scenarios support all well tasks, oil remains nodal only', () => {
  for (const task of ['nodal', 'profile', 'combined']) assert.equal(supportsPressureScenario('basic_gas', task), true)
  assert.equal(supportsPressureScenario('black_oil_liquid', 'nodal'), true)
  for (const kind of ['black_oil_liquid', 'legacy_well', 'network', 'eclipse_100']) {
    for (const task of ['profile', 'combined']) assert.equal(supportsPressureScenario(kind, task), false)
  }
  assert.equal(supportsPressureScenario('basic_gas', 'network'), false)
})

const version = value => ({ status: 'READY', modelKind: 'basic_gas', inspection: {
  schemaVersion: 'pipesim-well-inspection/1', reservoirPressure: { value, unit: 'psia' }
} })
test('source pressure preserves real numbers without applying the scenario edit limit', () => {
  for (const value of [4000.125, 100001]) assert.equal(sourceReservoirPressure(version(value)), value)
  for (const value of [null, true, '4000', 0, -1, NaN, Infinity, 1.2345e25, -1e31]) assert.equal(sourceReservoirPressure(version(value)), null)
})
test('source pressure is version-status, kind and strict schema bound', () => {
  for (const status of ['VALIDATING', 'INVALID', 'UPLOADED']) assert.equal(sourceReservoirPressure({ ...version(1), status }), null)
  for (const modelKind of ['network', 'eclipse_100', 'legacy_well']) assert.equal(sourceReservoirPressure({ ...version(1), modelKind }), null)
  for (const inspection of [null, {}, { ...version(1).inspection, extra: 'unsafe' },
    { schemaVersion: 'pipesim-well-inspection/1', reservoirPressure: null },
    { schemaVersion: 'pipesim-well-inspection/1', reservoirPressure: { value: 1, unit: 'bar' } }]) {
    assert.equal(sourceReservoirPressure({ ...version(1), inspection }), null)
  }
})
