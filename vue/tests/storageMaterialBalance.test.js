import test from 'node:test'
import assert from 'node:assert/strict'
import { createStorageMaterialBalanceState, storageMaterialBalanceChart } from '../src/utils/storageMaterialBalance.js'
import { toSharedMaterialBalanceResult, formatSourceNumber } from '../src/utils/storageMaterialBalanceSource.js'

test('source formatting removes floating tails without mutating values or concealing tiny values', () => {
  assert.equal(formatSourceNumber(3.9599999999999995), '3.96')
  assert.equal(formatSourceNumber(.029975400000000003), '0.0299754')
  assert.equal(formatSourceNumber(0), '0')
  assert.equal(formatSourceNumber(1e-10), '1.0000e-10')
  assert.equal(formatSourceNumber(null), '—')
  assert.equal(formatSourceNumber(Infinity), '—')
  assert.equal(formatSourceNumber('Wichert-Aziz'), 'Wichert-Aziz')
  const detail = sourceDetail()
  const before = structuredClone(detail)
  formatSourceNumber(toSharedMaterialBalanceResult(detail).inputItems[0].gas)
  assert.deepEqual(detail, before)
})

const scope = { projectId: 7, gasReservoirId: 6, storageId: 1 }
const payload = { wells: [], rows: [], skippedDates: [], includedWellCount: 0 }
test('invalid scope never makes a request', async () => {
  let calls = 0
  const state = createStorageMaterialBalanceState(async () => { calls++; return payload })
  for (const id of [null, '', -1, 0, 'abc', 1.5, Infinity, Number.MAX_SAFE_INTEGER + 1]) {
    await state.load({ ...scope, storageId: id })
    assert.equal(state.result.value, null)
    assert.ok(state.error.value)
  }
  assert.equal(calls, 0)
})
test('empty sources are a valid response, not a zero result or network failure', async () => {
  const state = createStorageMaterialBalanceState(async () => ({ data: payload }))
  await state.load(scope)
  assert.deepEqual(state.result.value, payload)
  assert.equal(state.loading.value, false)
  assert.equal(state.error.value, '')
})
test('switching storage cancels stale requests and discards their eventual response', async () => {
  const pending = []
  const state = createStorageMaterialBalanceState((snapshot, signal) => new Promise(resolve => pending.push({ snapshot, signal, resolve })))
  const first = state.load(scope)
  const second = state.load({ ...scope, storageId: 2 })
  assert.equal(pending[0].signal.aborted, true)
  pending[1].resolve({ data: { ...payload, message: 'new' } }); await second
  pending[0].resolve({ data: { ...payload, message: 'old' } }); await first
  assert.equal(state.result.value.message, 'new')
  assert.equal(pending[1].snapshot.storageId, 2)
})
test('failed refresh clears the previous result and shows the server reason', async () => {
  let fail = false
  const state = createStorageMaterialBalanceState(async () => {
    if (fail) throw { response: { data: { msg: '范围不存在' } } }
    return payload
  })
  await state.load(scope); fail = true; await state.load(scope)
  assert.equal(state.result.value, null)
  assert.equal(state.error.value, '范围不存在')
  assert.equal(state.loading.value, false)
})
test('unmount cancels load and prevents state resurrection', async () => {
  let resolve
  const state = createStorageMaterialBalanceState(() => new Promise(done => { resolve = done }))
  const pending = state.load(scope); state.clear(); resolve(payload); await pending
  assert.equal(state.result.value, null); assert.equal(state.loading.value, false)
})
test('malformed response is not displayed', async () => {
  const state = createStorageMaterialBalanceState(async () => ({ data: {} }))
  await state.load(scope)
  assert.match(state.error.value, /格式/); assert.equal(state.result.value, null)
})
test('chart uses aggregated input pressure, exposes units and never fabricates regression', () => {
  const option = storageMaterialBalanceChart([{ date: '2020-01-01', pressure: 15, gas: 3, water: .3 }])
  assert.deepEqual(option.series[0].data, [[3, 15, '2020-01-01']])
  assert.equal(option.series.length, 1)
  assert.match(option.yAxis.name, /MPa/)
  assert.match(option.xAxis.name, /10⁸m³/)
})
test('chart preserves true zero gas but does not turn missing or nonfinite fields into zero', () => {
  const rows = [null, NaN, Infinity, -1].map(pressure => ({ date: '2020-01-01', pressure, gas: 3 }))
  rows.push({ date: '2020-01-02', pressure: 15, gas: 0 })
  assert.deepEqual(storageMaterialBalanceChart(rows).series[0].data, [[0, 15, '2020-01-02']])
  assert.deepEqual(storageMaterialBalanceChart([]).series[0].data, [])
})

const sourceDetail = () => ({
  wellId: 21, resultId: 1, parameters: { temperature: 80, hydrogenSulfide: 4.62 },
  inputRows: [{ date: '2005-06-10', pressure: 30.312, gas: .7942872, water: .0299754, deleted: false }],
  output: { gasVolume: 35.0078, intercept: 31.2172, gradient: -.89172, rSquared: .9884, reliability: 2 },
  resultRows: [{ pressure: 30.5546, gas: .7942872, deleted: false }, { pressure: 21.3, gas: 11, deleted: true }],
  regressionLine: [{ pressure: 30.5089, gas: .7942872 }, { pressure: 21.6, gas: 10.7 }]
})
test('shared component adapter keeps production input, Pp, regression and units distinct', () => {
  const result = toSharedMaterialBalanceResult(sourceDetail())
  assert.equal(result.input.temperature, 80)
  assert.equal(result.input.hydrogenSulfide, 4.62)
  assert.equal(result.inputItems[0].pressure, 30.312)
  assert.equal(result.chartItems[0].data[0].yValue, 30.5546)
  assert.equal(result.chartItems[1].data[0].yValue, 30.5089)
  assert.equal(result.output.gradient, -.89172)
  assert.equal(result.output.originalGasVolume, 35.0078)
  assert.equal(result.chartItems[0].data[1].isDeleted, true)
})
test('missing regression line is not extrapolated from original output coefficients', () => {
  const detail = sourceDetail(); detail.regressionLine = []
  const result = toSharedMaterialBalanceResult(detail)
  assert.equal(result.chartItems.length, 1)
})
test('failed original result preserves original zeros but does not display fitted line', () => {
  const detail = sourceDetail(); detail.output.reliability = 0; detail.output.gasVolume = 0
  const result = toSharedMaterialBalanceResult(detail)
  assert.equal(result.output.originalGasVolume, 0)
  assert.equal(result.validRegression, false)
  assert.equal(result.chartItems.length, 1)
})
test('invalid source points are never coerced into zero coordinates', () => {
  const detail = sourceDetail()
  detail.resultRows = [{ pressure: null, gas: 1 }, { pressure: Infinity, gas: 2 }, { pressure: 20, gas: null }]
  assert.deepEqual(toSharedMaterialBalanceResult(detail).chartItems[0].data, [])
  assert.equal(toSharedMaterialBalanceResult(null), null)
})
test('source response must match the selected well and result IDs', async () => {
  const state = createStorageMaterialBalanceState(async () => ({ ...sourceDetail(), wellId: 22 }), {
    keys: ['projectId', 'gasReservoirId', 'storageId', 'wellId', 'resultId'],
    validate: (data, request) => data.wellId === request.wellId && data.resultId === request.resultId
  })
  await state.load({ ...scope, wellId: 21, resultId: 1 })
  assert.equal(state.result.value, null); assert.match(state.error.value, /格式/)
})
test('switching source discards stale response just like switching storage', async () => {
  const pending = []
  const state = createStorageMaterialBalanceState((params) => new Promise(resolve => pending.push({ params, resolve })), {
    keys: ['projectId', 'gasReservoirId', 'storageId', 'wellId', 'resultId'], validate: data => !!data.resultId
  })
  const first = state.load({ ...scope, wellId: 21, resultId: 1 })
  const second = state.load({ ...scope, wellId: 22, resultId: 2 })
  pending[1].resolve({ resultId: 2 }); await second
  pending[0].resolve({ resultId: 1 }); await first
  assert.equal(state.result.value.resultId, 2)
})
