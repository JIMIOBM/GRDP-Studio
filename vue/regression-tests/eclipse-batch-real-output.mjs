import assert from 'node:assert/strict'
import { readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { compileScript, parse } from '@vue/compiler-sfc'
import * as Vue from 'vue'
import * as batch from '../src/views/SoftwareIntegration/eclipseBatch.js'

const directory = resolve(process.argv[2]), load = name => JSON.parse(readFileSync(resolve(directory, name + '.json'), 'utf8'))
const receipt = load('acceptance-summary'); assert.equal(receipt.apiVerified, true)
const call = async path => {
  const response = await fetch('http://127.0.0.1:5173/api/software-integration' + path, { signal: AbortSignal.timeout(60000) })
  assert.equal(response.status, 200); const reply = await response.json(); assert.equal(reply.code, 200); return reply
}
const api = { listEclipseBatches: version => call(`/model-versions/${version}/eclipse-batches`), getEclipseBatch: id => call(`/eclipse-batches/${id}`) }
const saved = new Map()
Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: { getItem: key => saved.get(key) ?? null, setItem: (key, value) => saved.set(key, value), removeItem: key => saved.delete(key) } })
const { descriptor } = parse(readFileSync(new URL('../src/views/SoftwareIntegration/EclipseBatchPanel.vue', import.meta.url), 'utf8'))
const code = compileScript(descriptor, { id: 'real-batch', genDefaultAs: 'component' }).content.replace(/^import (.+) from ['"](.+)['"]\r?$/gm, (_, bindings, module) => `const ${bindings} = deps[${JSON.stringify(module)}]`)
const component = new Function('deps', `${code}; return component`)({ vue: Vue, './eclipseBatch.js': batch, '@/api/softwareIntegration': { softwareIntegrationApi: api } })
const setup = component.setup; let state
component.setup = (props, context) => { state = setup(props, context); return state }; component.render = () => null
const renderer = Vue.createRenderer({ createElement: () => ({}), createText: () => ({}), createComment: () => ({}), insert() {}, remove() {}, setText() {}, setElementText() {}, patchProp() {}, parentNode: () => null, nextSibling: () => null })
const old = load('batch-completed'), baseline = (await call('/runs/' + old.baselineRunId)).data
const props = Vue.reactive({ run: baseline, inspection: JSON.parse(readFileSync(new URL('../tests/fixtures/eclipseRelperm/platform-inspection.json', import.meta.url), 'utf8')) })
const app = renderer.createApp({ render: () => Vue.h(component, props) }); app.mount({})
let proof = { automatedUiVerified: false, manualUiVerified: false }
try {
  await state.loadBatches(); await state.refreshSelected(old.id); await Vue.nextTick()
  assert.deepEqual(JSON.parse(JSON.stringify(state.selected.value)), old)
  assert.equal(state.response.value.rows.length, 168); assert.equal(state.day.value, 100); assert.equal(state.rows.value.length, 8)
  for (const row of state.response.value.rows) {
    const expected = load('run-' + row.name).result.summary.series.find(s => s.keyword === row.keyword && s.objectName === row.well).points.find(p => p.timeDays === row.timeDays)
    assert.equal(row.current, expected.value)
  }
  const isolated = load('batch-isolated'); props.run = isolated.baseline
  for (let i = 0; i < 12; i++) { await Promise.resolve(); await Vue.nextTick() }
  await state.loadBatches(); await state.refreshSelected(isolated.id); await Vue.nextTick()
  assert.equal(state.selected.value.status, 'PARTIAL_SUCCEEDED')
  assert.equal(state.selected.value.members[0].run.result, null)
  assert.ok(state.response.value.rows.length > 0 && state.response.value.rows.every(row => row.runId === receipt.isolatedRunIds[1]))
  proof = { automatedUiVerified: true, manualUiVerified: false, actualApiTransport: true, batchIds: [old.id, isolated.id], nativeAlignedRows: 168, failedResultRemainsNull: true, isolatedRows: state.response.value.rows.length }
  console.log(JSON.stringify(proof))
} finally { app.unmount(); writeFileSync(resolve(directory, 'automated-batch-ui.json'), JSON.stringify(proof, null, 2)) }
