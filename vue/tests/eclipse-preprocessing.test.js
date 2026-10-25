import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parse, compileScript } from '@vue/compiler-sfc'
import * as Vue from 'vue'
import * as XLSX from 'xlsx'
import * as helpers from '../src/views/SoftwareIntegration/eclipsePreprocessing.js'

const template = id => JSON.parse(readFileSync(new URL(`../../backend/src/main/resources/softwareintegration/preprocessing/${id}.json`, import.meta.url), 'utf8'))
const values = definition => definition.fields.map(field => ({ id: field.id, value: field.baseValue, unit: field.unit, sheet: field.sheet, row: field.tableRow }))

test('Input curves retain physical coordinates and independent PVTO branches', () => {
  const definition = template('e100'), input = values(definition)
  const relperm = helpers.preprocessingCurves(definition, input, 'SWOF')
  assert.deepEqual(relperm.series[0].data[0], [0.15109, 0])
  assert.equal(relperm.series[2].unit, 'psia')
  const pvt = helpers.preprocessingCurves(definition, input, 'PVTO')
  assert.equal(pvt.series.length, 22)
  assert.deepEqual(pvt.series[0].data[0], [14.7, 1])
  assert.deepEqual(pvt.series.at(-2).data, [[4000, 1.12], [5000, 1.1189]])
})

test('The injection timeline honors actual cumulative report intervals and the later shut-in', () => {
  const definition = template('compositional'), input = values(definition)
  const timeline = helpers.preprocessingTimeline(definition, input)
  const shut = timeline.find(event => event.keyword === 'WELOPEN')
  assert.equal(shut.timeDays, 3650)
  assert.equal(shut.object, 'I')
  assert.ok(shut.description.includes('SHUT'))
  input.find(row => row.id === 'TSTEP.1.1.1').value = '300'
  assert.equal(helpers.preprocessingTimeline(definition, input).find(event => event.keyword === 'WELOPEN').timeDays, 3365)
})

const origin = (date = '1990-01-01') => ({ templateId: 'thermal', templateVersion: '1.0.0', values: [{ id: 'project.startDate', value: date }] })

test('Native spatial results stay bound to the exact generated project, version and retained run', () => {
  const build = { projectId: 1, modelId: 2, versionId: 3, runId: 4 }
  const project = { project: { id: 1 }, models: [{ id: 2, versions: [{ id: 3, inspection: { unitSystem: 'FIELD' } }] }] }
  const current = { id: 4, projectId: 1, modelId: 2, modelVersionId: 3, status: 'SUCCEEDED', resultContract: 'VALID_FULL',
    artifacts: [{ id: 8, name: 'eclipse-output-CASE.EGRID' }], result: { grid: { nx: 1, ny: 1, nz: 1 },
      fieldIndex: { schemaVersion: 'eclipse-binary-field-index/1', files: [{ name: 'CASE.EGRID', byteOrder: 'BIG', fields: [{ keyword: 'COORD', count: 24 }] }] } } }
  const spatial = helpers.preprocessingSpatialResult(current, build, project)
  assert.equal(spatial.run, current)
  assert.deepEqual(spatial.fields, [{ file: 'CASE.EGRID', byteOrder: 'BIG', keyword: 'COORD', count: 24 }])
  assert.equal(spatial.unitSystem, 'FIELD')
  for (const change of [{ id: 9 }, { projectId: 9 }, { modelId: 9 }, { modelVersionId: 9 }, { status: 'PARTIAL_SUCCEEDED' },
    { resultContract: 'VALID_PARTIAL' }, { resultExpired: true }, { artifacts: [] }, { result: {} }]) {
    assert.throws(() => helpers.preprocessingSpatialResult({ ...current, ...change }, build, project), /原生三维文件不可用/)
  }
})

const run = points => ({ runType: 'eclipse', status: 'SUCCEEDED', resultContract: 'VALID_FULL', result: { summary: { series: [
  { keyword: 'WOPT', objectName: 'P', unit: 'STB', number: 0, points }
] } } })
test('Result comparison uses only common native report times and preserves real zero values', () => {
  const current = run([{ timeDays: 10, value: 0 }, { timeDays: 30, value: 50 }])
  const baseline = run([{ timeDays: 10, value: 5 }, { timeDays: 20, value: 20 }, { timeDays: 30, value: 40 }])
  const result = helpers.comparePreprocessingRuns(current, baseline, origin(), origin())
  assert.equal(result.metrics[0].label, '单井累计产油 · P · WOPT')
  assert.deepEqual(result.metrics[0].points, [
    { timeDays: 10, current: 0, baseline: 5, difference: -5 },
    { timeDays: 30, current: 50, baseline: 40, difference: 10 }
  ])
  baseline.result.summary.series[0].unit = 'sm3'
  assert.equal(helpers.comparePreprocessingRuns(current, baseline, origin(), origin()).metrics.length, 0)
})

test('Partial expired or differently dated calculations never appear as comparable success', () => {
  const current = run([{ timeDays: 10, value: 3 }]), baseline = run([{ timeDays: 10, value: 2 }])
  for (const change of [{ status: 'FAILED' }, { resultContract: 'VALID_PARTIAL' }, { resultExpired: true }]) {
    assert.equal(helpers.comparePreprocessingRuns({ ...current, ...change }, baseline, origin(), origin()).metrics.length, 0)
  }
  assert.equal(helpers.comparePreprocessingRuns(current, baseline, origin(), origin('2000-01-01')).metrics.length, 0)
})

const renderer = Vue.createRenderer({
  createElement: () => ({}), createText: () => ({}), createComment: () => ({}), insert() {}, remove() {},
  setText() {}, setElementText() {}, patchProp() {}, parentNode: () => null, nextSibling: () => null
})
const settle = async () => { await Vue.nextTick(); await new Promise(resolve => setImmediate(resolve)); await Vue.nextTick() }
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }

function mount(overrides = {}) {
  const api = {
    getEclipsePreprocessingTemplate: async id => template(id), listEclipsePreprocessingBuilds: async () => [],
    loadEclipsePreprocessingExample: async (_, id) => ({ sourceId: 'source-1', templateId: id, values: values(template(id)) }),
    ...overrides
  }
  const source = readFileSync(new URL('../src/views/SoftwareIntegration/EclipsePreprocessingWorkbench.vue', import.meta.url), 'utf8')
  const compiled = compileScript(parse(source).descriptor, { id: 'preprocessing-test', genDefaultAs: 'component' }).content
  const code = compiled.replace(/^import ([\s\S]*?) from ['"](.+)['"];?\r?$/gm, (_, bindings, module) => {
    if (bindings.startsWith('* as ')) return `const ${bindings.slice(5)}=deps[${JSON.stringify(module)}]`
    if (bindings.startsWith('{')) return `const ${bindings.replace(/\bas\b/g, ':')}=deps[${JSON.stringify(module)}]`
    return `const ${bindings}={}`
  })
  const component = new Function('deps', `${code};return component`)({ vue: Vue, xlsx: XLSX,
    '@/api/softwareIntegration': { softwareIntegrationApi: api }, './eclipsePreprocessing.js': helpers })
  const setup = component.setup; let state
  component.setup = (props, context) => { state = setup(props, context); return state }; component.render = () => null
  const emitted = []
  const app = renderer.createApp(component, { projectId: 1, onRegistered: event => emitted.push(event) }); app.mount({})
  return { get state() { return state }, emitted, unmount: () => app.unmount() }
}

test('An older template response cannot overwrite a new model type', async () => {
  const first = deferred(), second = deferred()
  const model = mount({ getEclipsePreprocessingTemplate: id => id === 'e100' ? first.promise : second.promise })
  try {
    model.state.type.value = 'thermal'; await settle()
    second.resolve(template('thermal')); await settle()
    first.resolve(template('e100')); await settle()
    assert.equal(model.state.template.value.id, 'thermal')
    assert.equal(model.state.modelName.value, '表格_THERM3')
  } finally { model.unmount() }
})

test('Editing after validation starts invalidates a late preview instead of enabling generation', async () => {
  const validation = deferred()
  const model = mount({ validateEclipsePreprocessing: () => validation.promise })
  try {
    await settle(); await model.state.loadExample(); model.state.confirmed.value = true
    const pending = model.state.validate()
    model.state.rows.value[0].value = '2000-01-01'; model.state.dirty()
    validation.resolve({ valid: true, issues: [], preview: {} }); await pending
    assert.equal(model.state.validation.value, null)
  } finally { model.unmount() }
})

test('Double-clicking generation submits one request and preserves its idempotency key', async () => {
  const pending = deferred(), requests = []
  const model = mount({ buildEclipsePreprocessing: (_, input) => { requests.push(input); return pending.promise },
    getEclipsePreprocessingBuild: async () => ({ requestId: 'job-1', status: 'SUCCEEDED', versionId: 1, runId: 2 }) })
  try {
    await settle(); await model.state.loadExample(); model.state.confirmed.value = true; model.state.validation.value = { valid: true }
    const key = model.state.requestId.value
    const a = model.state.build(), b = model.state.build()
    assert.equal(requests.length, 1); assert.equal(requests[0].requestId, key)
    pending.resolve({ requestId: 'job-1', status: 'VALIDATING' }); await Promise.all([a, b]); await settle()
    assert.equal(model.emitted.length, 1)
    assert.equal(model.state.job.value.runId, 2)
  } finally { model.unmount() }
})

test('Resuming an old template never relabels its inputs as the current template version', async () => {
  const model = mount({ getEclipsePreprocessingInputs: async () => ({
    templateId: 'e100', templateVersion: 'obsolete', sourceId: 'old-source',
    values: values(template('e100')), modelName: '旧模型', confirmedDefaults: true
  }) })
  try {
    await settle(); await model.state.loadExample()
    const source = model.state.sourceId.value
    await model.state.resume({ requestId: 'old-build', status: 'SUCCEEDED' })
    assert.match(model.state.error.value, /旧模板/)
    assert.equal(model.state.sourceId.value, source)
    assert.equal(model.state.job.value, null)
  } finally { model.unmount() }
})

test('Unmounting ignores delayed import failures', async () => {
  const pending = deferred(), model = mount({ loadEclipsePreprocessingExample: () => pending.promise })
  await settle(); const request = model.state.loadExample(); model.unmount(); pending.reject(new Error('stale failure')); await request
  assert.equal(model.state.error.value, '')
})


test('History failure does not prevent loading a template or importing a new example', async () => {
  const model = mount({ listEclipsePreprocessingBuilds: async () => { throw new Error('history unavailable') } })
  try {
    await settle()
    assert.equal(model.state.template.value.id, 'e100')
    assert.match(model.state.error.value, /历史记录加载失败/)
    assert.deepEqual(model.state.builds.value, [])
    await model.state.loadExample()
    assert.ok(model.state.rows.value.length > 0)
  } finally { model.unmount() }
})

test('Unavailable model history is terminal and never resumes polling', async () => {
  let requests = 0
  const model = mount({
    listEclipsePreprocessingBuilds: async () => [{ requestId: 'deleted', templateId: 'e100', status: 'UNAVAILABLE', runId: 2 }],
    getEclipsePreprocessingBuild: async () => { requests++; throw new Error('must not poll deleted model') },
  })
  try {
    await settle()
    assert.equal(model.state.history.value.length, 1)
    assert.equal(model.state.job.value, null)
    assert.deepEqual(model.state.comparisonChoices.value, [])
    assert.equal(requests, 0)
    assert.ok(helpers.preprocessingTerminal.has('UNAVAILABLE'))
  } finally { model.unmount() }
})

test('Well preview uses explicit connection columns, layers and statuses without inventing a trajectory', () => {
  const cells = [
    { i: 1, j: 1, k: 1, top: 100, active: false },
    { i: 1, j: 1, k: 2, top: 110, active: true },
    { i: 2, j: 1, k: 1, top: 100, active: true },
    { i: 2, j: 1, k: 2, top: 110, active: true },
    { i: 3, j: 1, k: 2, top: 110, active: true },
  ]
  const well = { i: 1, j: 1, layers: [1, 2], completions: [
    { i: 2, j: 1, k1: 1, k2: 2, status: 'OPEN' },
    { i: 3, j: 1, k1: 2, k2: 2, status: 'SHUT' },
  ] }
  const result = helpers.preprocessingWellLocations({ cells }, well)
  assert.equal(result.head, cells[0])
  assert.deepEqual(result.completions.map(({ cell, status }) => [cell.i, cell.j, cell.k, status]), [
    [2, 1, 1, 'OPEN'], [2, 1, 2, 'OPEN'], [3, 1, 2, 'SHUT'],
  ])
  assert.deepEqual(helpers.preprocessingWellLocations({ cells }, { i: 1, j: 1, layers: [1, 2] }).completions, [])
})
