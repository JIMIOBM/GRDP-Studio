import * as compositionalFluidProperty from '../src/views/SoftwareIntegration/eclipseCompositionalFluidProperty.js'
import * as coupledRelperm from '../src/views/SoftwareIntegration/eclipseCoupledRelperm.js'
import * as multiLiveOilPvt from '../src/views/SoftwareIntegration/eclipseMultiLiveOilPvt.js'
import * as multiWaterPvt from '../src/views/SoftwareIntegration/eclipseMultiWaterPvt.js'
import * as pvtwProperties from '../src/views/SoftwareIntegration/eclipsePvtw.js'
import * as multiWetGasPvt from '../src/views/SoftwareIntegration/eclipseMultiWetGasPvt.js'
import * as multiPvt from '../src/views/SoftwareIntegration/eclipseMultiPvt.js'
import * as multiFamilyII from '../src/views/SoftwareIntegration/eclipseMultiSatFamilyII.js'
import * as multiSatRelperm from '../src/views/SoftwareIntegration/eclipseMultiSatRelperm.js'
import * as wetGasPvt from '../src/views/SoftwareIntegration/eclipseWetGasPvt.js'
import * as liveOilPvt from '../src/views/SoftwareIntegration/eclipseLiveOilPvt.js'
import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
import { compileScript, parse } from '@vue/compiler-sfc'
import * as Vue from 'vue'
import * as batch from '../src/views/SoftwareIntegration/eclipseBatch.js'
import * as oilPvt from '../src/views/SoftwareIntegration/eclipseOilPvt.js'
import * as gasPvt from '../src/views/SoftwareIntegration/eclipseGasPvt.js'
import { gridRegionBrowserFixture } from '../tests/fixtures/eclipseGridRegion.js'
const root = new URL('../tests/fixtures/eclipseRelperm/', import.meta.url)
const load = name => JSON.parse(readFileSync(new URL(name + '.json', root), 'utf8'))
const baseline = load('baseline'), inspection = load('platform-inspection')
const candidate = keyword => batch.buildBatchCase({ run: baseline, inspection, kind: keyword, value: .5, name: keyword })
const request = () => batch.buildBatchRequest({ run: baseline, name: '两项相渗', cases: [candidate('SWFN'), candidate('SOF3')], requestKey: '12345678-1234-1234-1234-123456789abc' })
const collection = () => ({ id: 1, modelVersionId: baseline.modelVersionId, baselineRunId: baseline.id, name: '两项相渗', status: 'SUCCEEDED', baseline: structuredClone(baseline), members: ['SWFN', 'SOF3'].map(name => ({ name, run: load(name) })) })

test('batch binds two independent actual source scenarios and serializable reactive snapshots', () => {
  const cases = Vue.reactive([candidate('SWFN'), candidate('SOF3')])
  const p = batch.buildBatchRequest({ run: baseline, name: ' 两项相渗 ', cases, requestKey: request().requestKey })
  assert.ok(batch.validBatchRequest(p)); assert.equal(p.name, '两项相渗'); assert.equal(p.cases[0].parameters.baselineRunId, baseline.id)
  cases[0].parameters.rows[1].values[1] = '0.99'; assert.notEqual(p.cases[0].parameters.rows[1].values[1], '0.99')
})
test('duplicate names, semantically equal parameters, mixed baseline and invalid bounds cannot form a batch', () => {
  for (const mutate of [p => p.cases.pop(), p => p.cases[1].name = p.cases[0].name.toLowerCase(),
    p => p.cases[1].parameters = Object.fromEntries(Object.entries(p.cases[0].parameters).reverse()),
    p => p.cases[1].parameters.baselineRunId++, p => p.cases[0].parameters.multiplier = 2,
    p => p.requestKey = '../batch', p => p.name = '\nname', p => p.extra = true]) {
    const p = request(); mutate(p); assert.equal(batch.validBatchRequest(p), false)
  }
  assert.throws(() => batch.buildBatchCase({ run: { ...baseline, resultExpired: true }, inspection, kind: 'SWFN', value: .5, name: '失效' }))
})
test('batch comparison uses both independent native results at every actual time and never synthesizes failed results', () => {
  const b = collection(), response = batch.batchResponse(b)
  assert.equal(response.rows.length, 168)
  for (const name of ['SWFN', 'SOF3']) {
    const native = load('native-' + name); assert.equal(native.nativeVerified, true)
    for (const row of response.rows.filter(row => row.name === name)) {
      const expected = native.target.series.find(series => series.keyword === row.keyword && series.objectName === row.well).points.find(p => p.timeDays === row.timeDays)
      assert.equal(row.current, expected.value)
    }
  }
  b.members[0].run.status = 'FAILED'; b.members[0].run.result = null
  const isolated = batch.batchResponse(b); assert.equal(isolated.rows.length, 84); assert.ok(isolated.rows.every(row => row.name === 'SOF3'))
})
test('expired baselines, cross-version responses and mismatched units are excluded, gaps remain null', () => {
  let b = collection(); b.baseline.resultExpired = true; assert.equal(batch.batchResponse(b).rows.length, 0)
  b = collection(); b.members[0].run.modelVersionId++; assert.equal(batch.batchResponse(b).rows.length, 84)
  b = collection(); b.members[0].run.result.summary.series.find(s => s.keyword === 'WWPR').unit = 'SM3/DAY'
  assert.match(batch.batchResponse(b).message, /单位不一致/); assert.equal(batch.batchResponse(b).rows.length, 84)
  b = collection(); b.members[0].run.result.summary.series.find(s => s.keyword === 'WWPR').points.pop()
  assert.equal(batch.batchResponse(b).rows.find(row => row.name === 'SWFN' && row.keyword === 'WWPR' && row.timeDays === 100).difference, null)
})

const renderer = Vue.createRenderer({ createElement: () => ({}), createText: () => ({}), createComment: () => ({}), insert() {}, remove() {}, setText() {}, setElementText() {}, patchProp() {}, parentNode: () => null, nextSibling: () => null })
const flush = async () => { for (let i = 0; i < 12; i++) { await Promise.resolve(); await Vue.nextTick() } }
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
function mount(api, saved = new Map()) {
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: { getItem: key => saved.get(key) ?? null, setItem: (key, value) => saved.set(key, value), removeItem: key => saved.delete(key) } })
  const { descriptor } = parse(readFileSync(new URL('../src/views/SoftwareIntegration/EclipseBatchPanel.vue', import.meta.url), 'utf8'))
  const code = compileScript(descriptor, { id: 'batch', genDefaultAs: 'component' }).content.replace(/^import (.+) from ['"](.+)['"]\r?$/gm, (_, bindings, module) => bindings.startsWith('{') ? `const ${bindings} = deps[${JSON.stringify(module)}]` : bindings.startsWith('* as ') ? `const ${bindings.slice(5)} = deps[${JSON.stringify(module)}]` : `const ${bindings} = {}`)
  const component = new Function('deps', `${code}; return component`)({ './eclipseCompositionalFluidProperty.js': compositionalFluidProperty, vue: Vue, './eclipseBatch.js': batch, './eclipseCoupledRelperm.js': coupledRelperm, './eclipseCoupledRelperm': coupledRelperm, './eclipseMultiSatRelperm.js': multiSatRelperm, './eclipseMultiLiveOilPvt.js': multiLiveOilPvt, './eclipseMultiLiveOilPvt': multiLiveOilPvt, './eclipseMultiWaterPvt.js': multiWaterPvt, './eclipseMultiWaterPvt': multiWaterPvt, './eclipsePvtw.js': pvtwProperties, './eclipseMultiWetGasPvt.js': multiWetGasPvt, './eclipseMultiWetGasPvt': multiWetGasPvt, './eclipseMultiPvt.js': multiPvt, './eclipseMultiPvt': multiPvt, './eclipseMultiSatFamilyII.js': multiFamilyII, './eclipseMultiSatFamilyII': multiFamilyII, './eclipseWetGasPvt.js': wetGasPvt, './eclipseLiveOilPvt.js': liveOilPvt, './eclipseOilPvt.js': oilPvt, './eclipseGasPvt.js': gasPvt, '@/api/softwareIntegration': { softwareIntegrationApi: api } })
  const setup = component.setup; let state
  component.setup = (props, context) => { state = setup(props, context); return state }; component.render = () => null
  const props = Vue.reactive({ run: structuredClone(baseline), inspection: structuredClone(inspection) })
  const app = renderer.createApp({ render: () => Vue.h(component, props) }); app.mount({})
  return { state, props, saved, unmount: () => app.unmount() }
}
test('a lost submit response persists the exact key; retry and page remount do not generate another request', async () => {
  const submitted = [], saved = new Map()
  const api = { async listEclipseBatches() { return { data: [] } }, async createEclipseBatch(version, p) { submitted.push(structuredClone(p)); if (submitted.length === 1) throw new Error('response lost'); return { data: collection() } } }
  let h = mount(api, saved)
  try {
    await flush(); h.state.cases.value = request().cases; await h.state.submit(); assert.equal(saved.size, 1); assert.equal(h.state.pendingRequest.value.requestKey, submitted[0].requestKey)
    h.unmount(); h = mount(api, saved); await flush(); await h.state.submit()
    assert.deepEqual(submitted[1], submitted[0]); assert.equal(saved.size, 0); assert.equal(h.state.selected.value.id, 1)
  } finally { h.unmount() }
})
test('a stale history response cannot replace another model; source or baseline changes clear unsubmitted cases', async () => {
  const old = deferred(), next = deferred(); let count = 0
  const h = mount({ listEclipseBatches: () => (++count === 1 ? old.promise : next.promise) })
  try {
    h.state.cases.value = request().cases; h.props.run.id++; await flush(); assert.equal(h.state.cases.value.length, 0)
    h.props.run.modelVersionId++; await flush(); old.resolve({ data: [collection()] }); await flush(); assert.equal(h.state.selected.value, null)
    next.resolve({ data: [] }); await flush(); assert.deepEqual(h.state.batches.value, [])
    h.state.cases.value = request().cases; h.props.inspection.relpermContext.tables[0].rows[1].values[1] = '.04'; await flush(); assert.equal(h.state.cases.value.length, 0)
  } finally { h.unmount() }
})
test('server validation rejection clears the pending request and never inserts a successful batch', async () => {
  const h = mount({ async listEclipseBatches() { return { data: [] } }, async createEclipseBatch() { throw { code: 400, message: 'source rejected' } } })
  try { await flush(); h.state.cases.value = request().cases; await h.state.submit(); assert.equal(h.saved.size, 0); assert.equal(h.state.selected.value, null); assert.match(h.state.error.value, /source rejected/) } finally { h.unmount() }
})

test('native grid cases bind the original region values and hashes, snapshot them, and reject stale models or physical overflow', () => {
  const f = gridRegionBrowserFixture('PERMX'), g = gridRegionBrowserFixture('PORO')
  const cases = [f, g].map(item => batch.buildGridBatchCase({ run: f.baseline, name: item.parameters.keyword, parameters: Vue.reactive(item.parameters) }))
  const p = batch.buildBatchRequest({ run: f.baseline, name: '网格区域', cases, requestKey: request().requestKey })
  assert.ok(batch.validBatchRequest(p)); assert.deepEqual(p.cases[0].parameters.originalValues, Array(6).fill(100))
  f.parameters.originalValues[0] = 101; assert.equal(cases[0].parameters.originalValues[0], 100)
  for (const mutate of [r => r.resultExpired = true, r => r.id++, r => r.result.modelKind = 'eclipse_300']) {
    const run = structuredClone(f.baseline); mutate(run)
    assert.throws(() => batch.buildGridBatchCase({ run, name: '网格', parameters: cases[0].parameters }))
  }
  assert.throws(() => batch.buildGridBatchCase({ run: g.baseline, name: '孔隙度', parameters: { ...g.parameters, multiplier: 2 } }))
  assert.throws(() => batch.buildGridBatchCase({ run: g.baseline, name: ' ', parameters: g.parameters }))
})

test('grid-only source queues two cases without a fluid table and prevents duplicates, ninth case or changing an unconfirmed submit', async () => {
  const f = gridRegionBrowserFixture('PERMX'), g = gridRegionBrowserFixture('PORO')
  const submitted = []
  const h = mount({ async listEclipseBatches() { return { data: [] } }, async createEclipseBatch(version, p) { submitted.push(structuredClone(p)); throw new Error('response lost') } })
  try {
    h.props.run = f.baseline; h.props.inspection = {}; await flush()
    assert.equal(h.state.choices.value.length, 0)
    h.state.addGridCase({ name: '渗透率减半', parameters: f.parameters })
    assert.equal(h.state.canBuild.value, true)
    assert.throws(() => h.state.addGridCase({ name: '重复', parameters: f.parameters }), /重复/)
    h.state.addGridCase({ name: '孔隙度八成', parameters: g.parameters })
    await h.state.submit(); assert.equal(submitted.length, 1); assert.deepEqual(submitted[0].cases.map(c => c.parameters), [f.parameters, g.parameters])
    assert.throws(() => h.state.addGridCase({ name: '不能改', parameters: { ...f.parameters, multiplier: .6 } }), /确认/)
    h.state.pendingRequest.value = null
    for (let i = 0; i < 6; i++) h.state.addGridCase({ name: '渗透率' + i, parameters: { ...f.parameters, multiplier: .6 + i / 100 } })
    assert.throws(() => h.state.addGridCase({ name: '第九项', parameters: { ...f.parameters, multiplier: .9 } }), /8/)
    h.props.run.id++; await flush(); assert.equal(h.state.cases.value.length, 0)
  } finally { h.unmount() }
})
