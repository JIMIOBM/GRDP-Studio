import test from 'node:test'
import assert from 'node:assert/strict'
import { gasLiftEligibility, gasLiftRunSnapshot, gasLiftReason } from '../src/views/SoftwareIntegration/gasLiftTask.js'
import { sourceReservoirPressure } from '../src/views/SoftwareIntegration/wellParameterPreview.js'
import { buildRunTypeOptions } from '../src/views/SoftwareIntegration/runTypeOptions.js'

const capability = { well: 'Well_1', study: 'Study 2', state: 'supported', reason: 'GAS_LIFT_VALVE_SUPPORTED', useGasliftValve: true, targetInjectionRateAvailable: true }
const inspection = { schemaVersion: 'pipesim-well-inspection/9', gasLiftCapabilities: [capability] }
test('native support must match well and selected Study exactly', () => {
  assert.equal(gasLiftEligibility(inspection, 'Well_1', 'Study 2').supported, true)
  assert.equal(gasLiftEligibility(inspection, 'Well_2', 'Study 2').supported, false)
  assert.equal(gasLiftEligibility(inspection, 'Well_1', 'Study 1').supported, false)
})
test('old, missing, duplicate and inconsistent capabilities cannot submit a valve task', () => {
  for (const input of [null, { ...inspection, schemaVersion: 'pipesim-well-inspection/8' },
    { ...inspection, gasLiftCapabilities: [capability, capability] },
    { ...inspection, gasLiftCapabilities: [{ ...capability, useGasliftValve: false }] }]) {
    assert.equal(gasLiftEligibility(input, 'Well_1', 'Study 2').supported, false)
  }
})
test('port workflow gets a readable cause without claiming all gas lift is unsupported', () => {
  assert.match(gasLiftReason('GAS_LIFT_VALVE_REQUIRED'), /GLR/)
  assert.match(gasLiftReason('GAS_LIFT_PARAMETERS_REJECTED'), /尚未启动/)
})
test('historical snapshot copies only its own persisted inputs', () => {
  const parameters = { producer: 'Well_1', outletPressurePsi: 151, surfaceInjectionTemperatureF: 110,
    targetInjectionRateMmscfd: 1.1, reservoirPressurePsi: 1700, gorScfPerStb: 400, waterCutPercent: 80, valuesMmscfd: [0.5, 1] }
  const snapshot = gasLiftRunSnapshot({ runType: 'gas-lift-performance', parameters })
  parameters.valuesMmscfd[0] = 99
  assert.deepEqual(snapshot.valuesMmscfd, [0.5, 1])
  assert.equal(snapshot.reservoirPressurePsi, 1700)
  assert.equal(gasLiftRunSnapshot({ runType: 'nodal', parameters }), null)
})
test('pressure original remains readable for /8 and /9 optional Network metadata', () => {
  const base = { reservoirPressure: { value: 5000, unit: 'psia' }, packageFiles: [], well: 'Well_1', espContexts: [], wellContexts: [] }
  for (const schema of ['pipesim-well-inspection/8', 'pipesim-well-inspection/9']) {
    const data = { ...base, schemaVersion: schema, ...(schema.endsWith('/9') ? { gasLiftCapabilities: [capability] } : {}) }
    for (const extra of [{}, { networkCapability: null }]) {
      assert.equal(sourceReservoirPressure({ status: 'READY', modelKind: 'black_oil_liquid', inspection: { ...data, ...extra } }), 5000)
    }
  }
})
test('/9 menu preserves flow tasks while gating gas by exact native well and Study', () => {
  const input = { isWellModel: true, isNetworkModel: false, modelKind: 'black_oil_liquid',
    wellRunTypeOptions: [{ value: 'profile', label: 'PT 剖面' }], inspectionSchemaVersion: inspection.schemaVersion,
    gasLiftInspection: inspection, wellContexts: [{ context: 'Well_1', completionContexts: ['Completion'], tubingContexts: ['Tubing'], espContexts: [] }],
    selectedWellContext: 'Well_1', selectedStudy: 'Study 2', hasNetworkCapability: false }
  const supported = buildRunTypeOptions(input).map(item => item.value)
  assert.ok(supported.includes('gas-lift-performance'))
  assert.ok(supported.includes('gas-lift-diagnostics'))
  const wrongStudy = buildRunTypeOptions({ ...input, selectedStudy: 'Study 1' }).map(item => item.value)
  assert.ok(wrongStudy.includes('profile'))
  assert.equal(wrongStudy.includes('gas-lift-performance'), false)
  assert.equal(wrongStudy.includes('gas-lift-diagnostics'), false)
})
