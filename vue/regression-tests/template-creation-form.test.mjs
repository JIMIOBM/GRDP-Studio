import test from 'node:test'
import assert from 'node:assert/strict'
import { creationPayload, experimentInputs, terminalCreation } from '../src/views/SoftwareIntegration/templateCreationForm.js'

test('explicit experiment has exactly the scalar contract and never mutates inputs', () => {
  const result = creationPayload('ConsoleWell', 'Study 1', experimentInputs, 'request-id')
  assert.equal(Object.keys(result.inputs).length, 12)
  assert.equal(result.inputs.unitsSystem, 'PIPESIM_FIELD')
  assert.equal(experimentInputs.study, undefined)
})
test('missing, string, nonfinite, out-of-range inputs and invalid names are rejected', () => {
  for (const value of [null, undefined, '35', NaN, Infinity, 0, 101]) {
    assert.throws(() => creationPayload('Well', 'Study', { ...experimentInputs, oilApi: value }, 'id'))
  }
  assert.throws(() => creationPayload('../Well', 'Study', experimentInputs, 'id'))
  assert.throws(() => creationPayload('Well', ' ', experimentInputs, 'id'))
})
test('inclusive watercut endpoints and physical temperature boundary agree with Worker', () => {
  for (const value of [0, 100]) assert.equal(creationPayload('Well', 'Study', { ...experimentInputs, waterCutPercent: value }, 'id').inputs.waterCutPercent, value)
  assert.throws(() => creationPayload('Well', 'Study', { ...experimentInputs, reservoirTemperatureDegF: -459.67 }, 'id'))
})
test('uncertain and preparing must not be presented as completed or allow another creation', () => {
  assert.equal(terminalCreation('UNCERTAIN'), false)
  assert.equal(terminalCreation('PREPARING'), false)
  assert.equal(terminalCreation('CANCELLED'), true)
  assert.equal(terminalCreation('SUCCEEDED'), true)
  assert.equal(terminalCreation('REJECTED'), true)
  assert.equal(terminalCreation('UNKNOWN_NEW_STATE'), false)
})
