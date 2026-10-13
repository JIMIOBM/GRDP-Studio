import assert from 'node:assert/strict'
import test from 'node:test'
import { reactive } from 'vue'
import { buildSystemAnalysisParameters, validateSystemAnalysisRun } from '../src/views/SoftwareIntegration/systemAnalysisParameters.js'

const draft = overrides => ({ producer: 'Well_1', branchTerminator: 'Flowline_1', outletPressurePsi: 600,
  valuesText: '2400, 3000, 3600', producers: ['Well_1', 'Well_2'], branches: ['Flowline_1'],
  study: 'Study 1', studies: ['Study 1', 'Study 2'], ...overrides })
const request = () => buildSystemAnalysisParameters(draft()).parameters
const run = (version = 2) => ({ modelVersionId: 301, study: 'Study 1', runType: 'system-analysis',
  status: 'SUCCEEDED', resultContract: 'VALID_FULL', parameters: version === 2 ? request() : null,
  result: { schemaVersion: `pipesim-system-analysis-result/${version}`, model_kind: 'network',
    runTask: 'system-analysis', resultContract: 'VALID_FULL', study: 'Study 1', producer: version === 2 ? 'Well_1' : 'Well',
    branchTerminator: 'Flowline_1', outletPressurePsi: 600, scanVariable: 'liquidFlowRate',
    cases: [2400, 3000, 3600].map(scanValue => ({ caseName: 'Base Case', scanValue,
      system: [{ variable: 'BottomHolePressure', unit: 'psia', value: 1200 }],
      node: [{ node: 'Well_1', variables: [{ variable: 'Pressure', unit: 'psia', value: 1200 }] }],
      profile: { pointCount: 2, variables: [
        { variable: 'TotalDistance', unit: version === 2 ? 'ft' : null, values: [0, 100] },
        { variable: 'Pressure', unit: version === 2 ? 'psia' : null, values: [1200, 600] },
        { variable: 'Temperature', unit: version === 2 ? 'degF' : null, values: [150, 120] }
      ] }
    })) } })

test('dynamic request uses the explicitly selected real context and does not mutate its draft', () => {
  const input = draft()
  const snapshot = structuredClone(input)
  const built = buildSystemAnalysisParameters(input)
  assert.deepEqual(built.errors, {})
  assert.equal(built.summary, null)
  assert.deepEqual(built.parameters, { schemaVersion: 'pipesim-system-analysis-parameters/2', producer: 'Well_1',
    branchTerminator: 'Flowline_1', outletPressurePsi: 600, scanVariable: 'liquidFlowRate', values: [2400, 3000, 3600] })
  assert.deepEqual(input, snapshot)
})

test('missing, stale and unsafe object or Study selections cannot produce a request', () => {
  for (const change of [{ producer: '' }, { producer: 'Well' }, { producer: ' Well_1' },
    { branchTerminator: 'FL-2' }, { branchTerminator: '../x' }, { study: 'Missing' }, { studies: [] }]) {
    const result = buildSystemAnalysisParameters(draft(change))
    assert.equal(result.parameters, null)
    assert.ok(Object.keys(result.errors).length)
    assert.equal(typeof result.summary, 'string')
  }
})

test('invalid pressure and liquid axis return field errors, not coerced parameters', () => {
  for (const pressure of ['', null, true, '600', NaN, Infinity, 0, 100001]) {
    const built = buildSystemAnalysisParameters(draft({ outletPressurePsi: pressure }))
    assert.equal(built.parameters, null)
    assert.ok(built.errors.outletPressurePsi)
  }
  for (const valuesText of ['', '1', '1,1', '2,1', '0,1', '1,NaN', '1,Infinity', '1,0x100',
    '1,2,3,4,5,6,7,8,9', '1,1000000001']) {
    const built = buildSystemAnalysisParameters(draft({ valuesText }))
    assert.equal(built.parameters, null)
    assert.ok(built.errors.valuesText)
  }
  assert.deepEqual(buildSystemAnalysisParameters(draft({ valuesText: '2.4e3，3000 3600' })).parameters.values, [2400, 3000, 3600])
})

test('complete dynamic result binds version, Study and all request identities; legacy history remains visible', () => {
  const current = run()
  const before = structuredClone(current)
  assert.deepEqual(validateSystemAnalysisRun(current, 301), current.result)
  assert.deepEqual(current, before)
  assert.ok(validateSystemAnalysisRun(run(1), 301))
  assert.equal(validateSystemAnalysisRun(current, 302), null)
  for (const field of ['producer', 'branchTerminator', 'study', 'outletPressurePsi', 'scanVariable', 'schemaVersion']) {
    const changed = run()
    changed.result[field] = field === 'outletPressurePsi' ? 700 : 'Other'
    assert.equal(validateSystemAnalysisRun(changed, 301), null, field)
  }
  const missing = run(); missing.parameters = null
  assert.equal(validateSystemAnalysisRun(missing, 301), null)
})

test('missing or malformed cases, wrong units and unavailable values never become displayable full results', () => {
  const mutations = [
    r => r.result.cases.pop(), r => r.result.cases.reverse(), r => { r.result.cases[0] = 42 },
    r => { r.result.cases[0].scanValue = 2500 },
    r => { r.result.cases[0].profile.variables[1].unit = 'STB/d' },
    r => { r.result.cases[0].profile.variables[1].unit = null },
    r => { r.result.cases[0].system[0].unit = 'ft' },
    r => { r.result.cases[0].node[0].variables[0].value = NaN },
    r => { r.result.cases[0].profile.variables[1].values[0] = -1e31 },
    r => r.result.cases[0].profile.variables.pop(),
    r => r.result.cases[0].profile.variables.push(structuredClone(r.result.cases[0].profile.variables[0])),
    r => { r.result.extra = true }
  ]
  for (const mutate of mutations) {
    const changed = run(); mutate(changed)
    assert.equal(validateSystemAnalysisRun(changed, 301), null)
  }
})

test('valid reactive dynamic and legacy runs yield independent display snapshots without throwing', () => {
  for (const version of [2, 1]) {
    const input = run(version)
    const expected = structuredClone(input.result)
    const source = reactive(input)
    let snapshot
    assert.doesNotThrow(() => { snapshot = validateSystemAnalysisRun(source, 301) })
    assert.deepEqual(snapshot, expected)
    source.result.cases[0].profile.variables[1].values[0] = 999
    assert.equal(snapshot.cases[0].profile.variables[1].values[0], 1200)
    snapshot.cases[0].system[0].value = 777
    assert.equal(source.result.cases[0].system[0].value, 1200)
    assert.equal(validateSystemAnalysisRun(source, 302), null)
  }
})
