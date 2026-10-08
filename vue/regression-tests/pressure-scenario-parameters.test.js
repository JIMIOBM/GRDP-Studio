import assert from 'node:assert/strict'
import test from 'node:test'
import { buildPressureScenarioParameters } from '../src/views/SoftwareIntegration/pressureScenarioParameters.js'

const build = overrides => buildPressureScenarioParameters({ enabled: true, modelKind: 'black_oil_liquid', runType: 'nodal', pressure: 4000, ...overrides })

test('valid pressure builds the existing absolute-pressure contract without coercion', () => {
  for (const pressure of [0.000001, 4000.125, 100000]) {
    assert.deepEqual(build({ pressure }), {
      parameters: { schemaVersion: 'pipesim-well-parameters/1', reservoirPressurePsi: pressure },
      fieldError: null, contextError: null
    })
  }
})

test('empty, nonnumeric and out-of-range values cannot produce parameters', () => {
  for (const pressure of [null, undefined, '', '4000', true, NaN, Infinity, -Infinity, 0, -1, 100000.001]) {
    const result = build({ pressure })
    assert.equal(result.parameters, null)
    assert.match(result.fieldError, /psia.*平台编辑范围/)
    assert.equal(result.contextError, null)
  }
})

test('disabled draft never adds parameters or validation errors to a baseline run', () => {
  assert.deepEqual(build({ enabled: false, modelKind: 'network', pressure: NaN }), {
    parameters: null, fieldError: null, contextError: null
  })
})

test('unsupported tasks are context errors rather than incorrect pressure-field errors', () => {
  for (const modelKind of ['network', 'legacy_well', 'water_injection', undefined]) {
    const result = build({ modelKind })
    assert.equal(result.parameters, null)
    assert.equal(result.fieldError, null)
    assert.ok(result.contextError)
  }
  for (const runType of ['profile', 'combined', 'trajectory', 'sensitivity', undefined]) {
    assert.ok(build({ runType }).contextError)
  }
})

test('basic gas keeps its supported PT and combined tasks; outputs are independent snapshots', () => {
  const first = build({ modelKind: 'basic_gas', runType: 'profile' })
  first.parameters.reservoirPressurePsi = 123
  for (const runType of ['nodal', 'profile', 'combined']) {
    assert.equal(build({ modelKind: 'basic_gas', runType }).parameters.reservoirPressurePsi, 4000)
  }
  assert.ok(build({ modelKind: 'basic_gas', runType: 'vfp-tables' }).contextError)
})
