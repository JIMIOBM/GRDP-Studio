import test from 'node:test'
import assert from 'node:assert/strict'
import {
  normalizeTemperatureSegments, normalizeTemperatureSettings, normalizeTemperatureDraft, temperatureSettingsStamp,
  temperatureContextKey, packTemperatureResults, restoreTemperatureResults, temperatureResultPanel, temperatureRestorePlan
} from './pipelineTemperatureState.js'
import { temperatureCoefficientStamp } from './pipelineTemperatureCalculations.js'

const legacySettings = () => ({
  tolerance: 0.001,
  maxIterations: 30,
  segments: [{
    edgeId: 'pipe-a', innerDiameterMm: 100, densityKgM3: 45, actualFlowM3s: 0.2,
    viscosityMpaS: 0.01, cpJkgK: 2000, gasConductivityWmK: 0.04,
    propertySource: '手动输入', pvtId: 42, externalMethod: 'surface-fixed', ambientC: 19,
    layers: [
      { name: '钢管', thicknessMm: 8, conductivityWmK: 45, source: '旧材料资料' },
      { name: '保温层', thicknessMm: null, conductivityWmK: null, source: '' }
    ]
  }]
})

test('temperature drafts and serialized requests project layers to the three current fields', () => {
  const legacy = legacySettings(), before = structuredClone(legacy)
  const settings = normalizeTemperatureSettings(legacy)
  const expectedLayers = [
    { name: '钢管', thicknessMm: 8, conductivityWmK: 45 },
    { name: '保温层', thicknessMm: null, conductivityWmK: null }
  ]
  assert.deepEqual(settings.segments[0].layers, expectedLayers)
  assert.deepEqual(normalizeTemperatureSegments(legacy.segments)[0].layers, expectedLayers)
  assert.deepEqual(JSON.parse(JSON.stringify(settings)).segments[0].layers, expectedLayers)
  for(const key of ['densityKgM3', 'viscosityMpaS', 'cpJkgK', 'propertySource'])assert.equal(Object.hasOwn(settings.segments[0], key), false)
  assert.equal(settings.segments[0].gasConductivityWmK,0.04)
  assert.equal(settings.segments[0].pvtId, 42)
  assert.equal(settings.segments[0].innerDiameterMm, 100)
  assert.equal(settings.segments[0].externalMethod, 'surface-fixed')
  assert.equal(settings.segments[0].ambientC, 19)
  assert.equal(settings.tolerance, 0.001)
  assert.equal(settings.maxIterations, 30)
  assert.deepEqual(legacy, before)
  settings.segments[0].layers[0].thicknessMm = 12
  assert.equal(legacy.segments[0].layers[0].thicknessMm, 8)
})

test('cached baselines and drafts ignore removed material metadata without causing false unsaved state', () => {
  const baseline = legacySettings(), current = legacySettings()
  current.segments[0].layers[0].source = '旧页面尚未保存的来源修改'
  current.segments[0].densityKgM3 = 99
  current.segments[0].cpJkgK = 999
  current.segments[0].propertySource = '旧手动来源'
  const cached = { settings: current, saved: JSON.stringify(baseline), revision: 4, topologyRevision: 9 }
  const before = structuredClone(cached)
  const restored = normalizeTemperatureDraft(cached)
  assert.equal(temperatureSettingsStamp(restored.settings), restored.saved)
  assert.equal(restored.revision, 4)
  assert.equal(restored.topologyRevision, 9)
  assert.equal(Object.hasOwn(restored.settings.segments[0].layers[0], 'source'), false)
  assert.equal(Object.hasOwn(JSON.parse(restored.saved).segments[0].layers[0], 'source'), false)
  assert.deepEqual(cached, before)
})

test('restoring legacy drafts retains genuine unsaved material and other-page changes', () => {
  for (const change of [
    settings => { settings.segments[0].layers[0].thicknessMm = 10 },
    settings => { settings.segments[0].layers[0].conductivityWmK = 50 },
    settings => { settings.segments[0].ambientC = 25 },
    settings => { settings.segments[0].pvtId = 43 }
  ]) {
    const baseline = legacySettings(), current = legacySettings()
    change(current)
    const restored = normalizeTemperatureDraft({ settings: current, saved: JSON.stringify(baseline) })
    assert.notEqual(temperatureSettingsStamp(restored.settings), restored.saved)
    assert.deepEqual(restored.settings, normalizeTemperatureSettings(current))
  }
})

test('sparse material responses normalize missing parameters without inventing physical values', () => {
  const sparse = { segments: [{ edgeId: 'pipe-a', layers: [{ name: '钢管' }] }] }
  const complete = { segments: [{ edgeId: 'pipe-a', layers: [{ name: '钢管', thicknessMm: null, conductivityWmK: null }] }] }
  assert.deepEqual(normalizeTemperatureSettings(sparse), complete)
  assert.equal(temperatureSettingsStamp(sparse), temperatureSettingsStamp(complete))
  assert.deepEqual(normalizeTemperatureSettings(normalizeTemperatureSettings(sparse)), complete)
})

test('normalization preserves incomplete drafts and unreadable baselines for ordinary validation', () => {
  assert.equal(normalizeTemperatureDraft(null), null)
  assert.equal(normalizeTemperatureSettings(null), null)
  assert.deepEqual(normalizeTemperatureSegments([null, { edgeId: 'pipe-a', layers: null }]), [null, { edgeId: 'pipe-a', layers: null }])
  for (const saved of ['', 'invalid old baseline']) {
    const draft = normalizeTemperatureDraft({ settings: legacySettings(), saved })
    assert.equal(draft.saved, saved)
    assert.notEqual(temperatureSettingsStamp(draft.settings), saved)
  }
})

test('given conductivity survives draft and save reloads and remains a shared independent editable value',()=>{
 const baseline=legacySettings(),settings=legacySettings()
 settings.segments[0].gasConductivityWmK=0.031
 const draft=normalizeTemperatureDraft({settings,saved:temperatureSettingsStamp(baseline)})
 const reloaded=normalizeTemperatureSettings(JSON.parse(JSON.stringify(draft.settings)))
 assert.equal(reloaded.segments[0].gasConductivityWmK,0.031)
 assert.notEqual(temperatureSettingsStamp(reloaded),draft.saved)
 for(const kind of ['inner','overall'])assert.notEqual(
  temperatureCoefficientStamp(kind,reloaded.segments[0],100,7,'pvt:r1'),
  temperatureCoefficientStamp(kind,baseline.segments[0],100,7,'pvt:r1'))
 for(const kind of ['wall','outer'])assert.equal(
  temperatureCoefficientStamp(kind,reloaded.segments[0],100,7,'pvt:r1'),
  temperatureCoefficientStamp(kind,normalizeTemperatureSettings(baseline).segments[0],100,7,'pvt:r1'))
 for(const missing of [null,undefined]) {
  settings.segments[0].gasConductivityWmK=missing
  const incomplete=normalizeTemperatureSettings(JSON.parse(JSON.stringify(settings)))
  assert.equal(incomplete.segments[0].gasConductivityWmK,missing)
 }
})

const context = () => ({ projectId: 1, gasReservoirId: 2, wellName: '井 A' })
const graph = () => ({ edges: [{ id: 'pipe-a' }, { id: 'pipe-b' }] })
const resultState = () => ({
  context: context(), topologyRevision: 7,
  calculated: {
    inner: { 'pipe-a': { edgeId: 'pipe-a', name: '首段', result: { alphaInside: 12 }, properties: { cpJkgK: 2200 }, stamp: 'inner-original' } },
    wall: { 'pipe-b': { edgeId: 'pipe-b', name: '末段', error: '请填写材料厚度', stamp: 'wall-original' } },
    outer: {},
    overall: { 'pipe-a': { edgeId: 'pipe-a', name: '首段', result: { innerAreaU: 10 }, stamp: 'overall-original' } }
  },
  coupledResults: { 'pipe-a': { edgeId: 'pipe-a', name: '首段', result: { innerAreaU: 20 }, stamp: 'coupled-original', flowStamp: 'flow-original', fromSolve: true } },
  solution: { input: { segments: [{ ambientC: 18 }] }, result: { points: [{ distanceM: 0, temperatureC: 30 }] }, iterations: 3 },
  solutionMark: 'flow-original', calculationMode: 'coupled'
})
const packed = () => packTemperatureResults(resultState())
const restore = saved => restoreTemperatureResults(saved, context(), graph(), 7)

test('temperature scope keys normalize numeric IDs and separate projects, reservoirs and exact well names', () => {
  const base = temperatureContextKey(context())
  assert.equal(base, temperatureContextKey({ wellName: '井 A', gasReservoirId: '02', projectId: '1' }))
  for (const changed of [{ projectId: 3 }, { gasReservoirId: 3 }, { wellName: '井 B' }, { wellName: '井 A ' }]) {
    assert.notEqual(base, temperatureContextKey({ ...context(), ...changed }))
  }
})

test('packing separates actual API rows from original dependency metadata and supplies clean missing groups', () => {
  const saved = packed()
  assert.deepEqual(saved.scope, context())
  assert.equal(saved.topologyRevision, 7)
  assert.deepEqual(saved.coefficients.inner, [{
    row: { edgeId: 'pipe-a', name: '首段', result: { alphaInside: 12 }, properties: { cpJkgK: 2200 } },
    stamp: 'inner-original', flowStamp: null, fromSolve: false
  }])
  assert.deepEqual(saved.coupled[0], {
    row: { edgeId: 'pipe-a', name: '首段', result: { innerAreaU: 20 } },
    stamp: 'coupled-original', flowStamp: 'flow-original', fromSolve: true
  })
  assert.deepEqual(packTemperatureResults({ context: context(), topologyRevision: 7 }), {
    scope: context(), topologyRevision: 7, coefficients: { inner: [], wall: [], outer: [], overall: [] },
    coupled: [], solution: null, solutionMark: '', calculationMode: 'coefficient'
  })
})

test('result snapshots are deep copies and restoring cannot share mutable rows or a temperature curve', () => {
  const original = resultState(), before = structuredClone(original)
  const saved = packTemperatureResults(original), restored = restore(saved)
  restored.calculated.inner['pipe-a'].result.alphaInside = 999
  restored.calculated.inner['pipe-a'].properties.cpJkgK = 999
  restored.coupledResults['pipe-a'].result.innerAreaU = 999
  restored.solution.result.points[0].temperatureC = 999
  restored.solution.input.segments[0].ambientC = 999
  assert.deepEqual(original, before)
  assert.equal(saved.coefficients.inner[0].row.result.alphaInside, 12)
  assert.equal(saved.solution.result.points[0].temperatureC, 30)
  saved.coefficients.inner[0].row.properties.cpJkgK = 1
  saved.solution.input.segments[0].ambientC = 1
  assert.deepEqual(original, before)
})

test('normalizing editable PVT settings does not delete actual calculation properties from saved results', () => {
  const state=resultState()
  Object.assign(state.calculated.inner['pipe-a'],{
    pvtProperties:{pvtId:42,sourceRevision:'pvt-r1',densityKgM3:45,cpJkgK:2200},
    usedInput:{densityKgM3:45,viscosityMpaS:0.013,cpJkgK:2200,gasConductivityWmK:0.039}
  })
  const settings=legacySettings(),saved=packTemperatureResults(state)
  const draft=normalizeTemperatureDraft({settings,saved:temperatureSettingsStamp(settings),results:saved})
  assert.equal(Object.hasOwn(draft.settings.segments[0],'densityKgM3'),false)
  const actual=restore(draft.results).calculated.inner['pipe-a']
  assert.equal(actual.usedInput.densityKgM3,45)
  assert.equal(actual.usedInput.gasConductivityWmK,0.039)
  assert.equal(actual.pvtProperties.sourceRevision,'pvt-r1')
})

test('a mismatched well scope or topology revision restores no results or solution', () => {
  const saved = packed()
  for (const other of [{ ...context(), projectId: 3 }, { ...context(), gasReservoirId: 3 }, { ...context(), wellName: '井 B' }]) {
    assert.deepEqual(restoreTemperatureResults(saved, other, graph(), 7), restore(null))
  }
  assert.deepEqual(restoreTemperatureResults(saved, context(), graph(), 8), restore(null))
  assert.deepEqual(restoreTemperatureResults(saved, {}, graph(), 7), restore(null))
  assert.equal(restoreTemperatureResults(saved, { projectId: '1', gasReservoirId: '2', wellName: '井 A' }, graph(), '7').calculated.inner['pipe-a'].result.alphaInside, 12)
})

test('restoring retains original coefficient and EOS marks so changed inputs remain stale', () => {
  const previous = legacySettings().segments[0]
  const oldStamp = temperatureCoefficientStamp('inner', previous, 100, 7)
  const state = resultState()
  state.calculated.inner['pipe-a'].stamp = oldStamp
  state.calculated.overall['pipe-a'].stamp = 'overall-coefficient{"revision":3,"compositionRevision":"old"}'
  const saved = packTemperatureResults(state)
  const changed = { ...previous, pvtId: 100 }
  const restored = restore(saved)
  assert.equal(restored.calculated.inner['pipe-a'].stamp, oldStamp)
  assert.notEqual(restored.calculated.inner['pipe-a'].stamp, temperatureCoefficientStamp('inner', changed, 100, 7))
  assert.equal(restored.calculated.overall['pipe-a'].stamp, state.calculated.overall['pipe-a'].stamp)
  assert.equal(restored.coupledResults['pipe-a'].flowStamp, 'flow-original')
  assert.equal(restored.solutionMark, 'flow-original')
})

test('independent and coupled overall calculations keep distinct results and reported row errors survive', () => {
  const restored = restore(packed())
  assert.equal(restored.calculated.overall['pipe-a'].result.innerAreaU, 10)
  assert.equal(restored.calculated.overall['pipe-a'].fromSolve, false)
  assert.equal(restored.coupledResults['pipe-a'].result.innerAreaU, 20)
  assert.equal(restored.coupledResults['pipe-a'].fromSolve, true)
  assert.equal(restored.calculated.wall['pipe-b'].error, '请填写材料厚度')
  assert.equal(restored.calculationMode, 'coupled')
})

test('invalid or unknown result rows are discarded without fabricating calculated values', () => {
  const saved = packed()
  saved.coefficients.inner.push(
    { row: { edgeId: 'removed', result: { alphaInside: 90 } }, stamp: 'old', fromSolve: false },
    { row: { edgeId: 'pipe-b' }, stamp: 'old', fromSolve: false },
    { row: { edgeId: 'empty', result: {}, error: '' }, stamp: 'old', fromSolve: false }
  )
  saved.coefficients.outer = 'invalid list'
  assert.deepEqual(Object.keys(restore(saved).calculated.inner), ['pipe-a'])
  assert.deepEqual(restore(saved).calculated.outer, {})
  for (const invalid of [
    { row: { edgeId: 'pipe-a', result: { alphaInside: 12 } }, fromSolve: false },
    { row: { edgeId: 'pipe-a', result: { alphaInside: 12 } }, stamp: '', fromSolve: false },
    { row: { edgeId: 'pipe-a', result: { alphaInside: 12 } }, stamp: 'old', fromSolve: true }
  ]) {
    const damaged = packed()
    damaged.coefficients.inner = [invalid]
    assert.deepEqual(restore(damaged).calculated.inner, {})
  }
})

test('duplicate pipe IDs reject the entire ambiguous group without discarding other independent groups', () => {
  const saved = packed()
  saved.coefficients.inner.push(structuredClone(saved.coefficients.inner[0]))
  saved.coupled.push(structuredClone(saved.coupled[0]))
  const restored = restore(saved)
  assert.deepEqual(restored.calculated.inner, {})
  assert.equal(restored.calculated.wall['pipe-b'].error, '请填写材料厚度')
  assert.equal(restored.calculated.overall['pipe-a'].result.innerAreaU, 10)
  assert.deepEqual(restored.coupledResults, {})
  assert.equal(restored.solution, null)
  assert.equal(restored.calculationMode, 'coefficient')
})

test('coupled coefficients and curves restore only as one consistent solution bundle', () => {
  for (const alter of [
    saved => { saved.solution = null },
    saved => { saved.solution.input = null },
    saved => { saved.solution.result.points = [] },
    saved => { saved.solutionMark = '' },
    saved => { saved.coupled = [] },
    saved => { saved.coupled[0].flowStamp = 'a different solve' },
    saved => { saved.coupled[0].fromSolve = false }
  ]) {
    const saved = packed()
    alter(saved)
    const restored = restore(saved)
    assert.deepEqual(restored.coupledResults, {})
    assert.equal(restored.solution, null)
    assert.equal(restored.solutionMark, '')
    assert.equal(restored.calculated.overall['pipe-a'].result.innerAreaU, 10)
  }
})

test('overall restoration keeps the preferred mode when available and falls back only to a mode with results', () => {
  const saved = packed()
  saved.calculationMode = 'coefficient'
  assert.equal(restore(saved).calculationMode, 'coefficient')
  saved.coefficients.overall = []
  assert.equal(restore(saved).calculationMode, 'coupled')
  const independent = packed()
  independent.coupled = []
  assert.equal(restore(independent).calculationMode, 'coefficient')
})

test('re-entering or clicking a node selects its result panel without borrowing results from another calculation', () => {
  const restored = restore(packed())
  for (let click = 0; click < 3; click++) {
    assert.equal(temperatureResultPanel('inner', restored.calculated, restored.coupledResults, 'coupled'), 'table')
    assert.equal(temperatureResultPanel('wall', restored.calculated, restored.coupledResults), 'table')
    assert.equal(temperatureResultPanel('outer', restored.calculated, restored.coupledResults), 'input')
    assert.equal(temperatureResultPanel('overall', restored.calculated, restored.coupledResults, 'coupled'), 'table')
  }
  assert.equal(temperatureResultPanel('overall', restored.calculated, {}, 'coupled'), 'input')
  assert.equal(temperatureResultPanel('unknown', restored.calculated, restored.coupledResults), 'input')
  assert.equal(temperatureResultPanel('inner', { inner: { 'pipe-a': { edgeId: 'pipe-a' } } }), 'input')
})

test('read-only saved results do not affect parameter dirty stamps while clean drafts still retain result history', () => {
  const settings = legacySettings(), results = packed()
  const withHistory = { ...settings, savedResults: results }
  const before = structuredClone(withHistory)
  assert.equal(temperatureSettingsStamp(settings), temperatureSettingsStamp(withHistory))
  assert.equal(Object.hasOwn(normalizeTemperatureSettings(withHistory), 'savedResults'), false)
  const draft = normalizeTemperatureDraft({ settings: withHistory, saved: JSON.stringify(withHistory), results, revision: 4, topologyRevision: 7 })
  assert.equal(temperatureSettingsStamp(draft.settings), draft.saved)
  assert.equal(Object.hasOwn(JSON.parse(draft.saved), 'savedResults'), false)
  assert.equal(restore(draft.results).calculated.inner['pipe-a'].result.alphaInside, 12)
  assert.deepEqual(withHistory, before)
})

test('a save completed after leaving the page adopts the newer server baseline while retaining cached results', () => {
  const previous = legacySettings(), edited = legacySettings()
  edited.segments[0].layers[0].thicknessMm = 10
  const cached = { settings: edited, saved: temperatureSettingsStamp(previous), revision: 4, topologyRevision: 7, results: packed() }
  const before = structuredClone(cached)
  assert.deepEqual(temperatureRestorePlan(cached, structuredClone(edited), 5, 7), {
    useCachedResults: true, restoreDraft: false
  })
  assert.deepEqual(cached, before)
})

test('server omission of null object fields and reordered keys still confirms the late save succeeded', () => {
  const previous = { tolerance: 0.001, maxIterations: 30, segments: [{ edgeId: 'pipe-a', propertyPressureMpa: 4, propertyTemperatureC: null,
    layers: [{ name: '钢管', thicknessMm: null, conductivityWmK: null }] }] }
  const edited = structuredClone(previous)
  edited.segments[0].propertyPressureMpa = 4.5
  const cached = { settings: edited, saved: temperatureSettingsStamp(previous), revision: 4, topologyRevision: 7 }
  const sparseServer = { segments: [{ layers: [{ name: '钢管' }], propertyPressureMpa: 4.5, edgeId: 'pipe-a' }],
    maxIterations: 30, tolerance: 0.001, savedResults: packed() }
  assert.deepEqual(temperatureRestorePlan(cached, sparseServer, 5, 7), { useCachedResults: true, restoreDraft: false })
})

test('genuine unsaved changes remain drafts when server parameters are still different', () => {
  const previous = legacySettings(), edited = legacySettings()
  edited.segments[0].layers[0].thicknessMm = 10
  const cached = { settings: edited, saved: temperatureSettingsStamp(previous), revision: 4, topologyRevision: 7 }
  for (const serverRevision of [4, 5]) {
    assert.deepEqual(temperatureRestorePlan(cached, previous, serverRevision, 7), { useCachedResults: true, restoreDraft: true })
  }
})

test('missing caches, changed topologies and clean obsolete revisions cannot restore an old draft', () => {
  const settings = legacySettings()
  const cached = { settings, saved: temperatureSettingsStamp(settings), revision: 4, topologyRevision: 7 }
  const empty = { useCachedResults: false, restoreDraft: false }
  assert.deepEqual(temperatureRestorePlan(null, settings, 4, 7), empty)
  assert.deepEqual(temperatureRestorePlan(undefined, settings, 4, 7), empty)
  assert.deepEqual(temperatureRestorePlan(cached, settings, 4, 8), empty)
  assert.deepEqual(temperatureRestorePlan(cached, settings, 5, 7), empty)
  assert.deepEqual(temperatureRestorePlan(cached, settings, 4, 7), { useCachedResults: true, restoreDraft: false })
})

test('semantic comparison preserves array order, empty element positions and numeric value types', () => {
  const baseline = { segments: [{ edgeId: 'pipe-a', samples: [null, 1], propertyPressureMpa: 4.5, layers: [] }] }
  const cached = { settings: baseline, saved: 'different old baseline', revision: 4, topologyRevision: 7 }
  for (const change of [
    value => { value.segments[0].samples = [1, null] },
    value => { value.segments[0].samples = [1] },
    value => { value.segments[0].propertyPressureMpa = '4.5' },
    value => { value.segments.push({ edgeId: 'pipe-b', layers: [] }); value.segments.reverse() }
  ]) {
    const server = structuredClone(baseline)
    change(server)
    assert.deepEqual(temperatureRestorePlan(cached, server, 5, 7), { useCachedResults: true, restoreDraft: true })
  }
  assert.deepEqual(temperatureRestorePlan(cached, structuredClone(baseline), 5, 7), { useCachedResults: true, restoreDraft: false })
})
