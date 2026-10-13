// Mount the production CF result against real saved source/target runs and all native points.
import assert from 'node:assert/strict'
import { readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { compileScript, parse } from '@vue/compiler-sfc'
import * as Vue from 'vue'

const directory = resolve(process.argv[2])
const load = name => JSON.parse(readFileSync(resolve(directory, name), 'utf8'))
const receipt = load('acceptance-summary.json')
assert.equal(receipt.apiVerified, true)
const version = load('version-original.json'), baseline = load('run-baseline.json'), run = load('run-target.json')
const dependencies = { vue: Vue, 'element-plus': { ElMessage: { error() {}, warning() {} } }, '@/api/softwareIntegration': {
  softwareIntegrationApi: { async getRun(id) { assert.equal(id, baseline.id); return { code: 200, data: baseline } } } } }
for (const name of readdirSync(new URL('../src/views/SoftwareIntegration/', import.meta.url)).filter(n => /^eclipse.*\.js$/.test(n))) {
  dependencies['./' + name.slice(0, -3)] = await import(new URL('../src/views/SoftwareIntegration/' + name, import.meta.url))
}
const source = readFileSync(new URL('../src/views/SoftwareIntegration/EclipseRunResult.vue', import.meta.url), 'utf8')
const compiled = compileScript(parse(source).descriptor, { id: 'real-cf', genDefaultAs: 'component' }).content
const code = compiled.replace(/^import (.+) from ['"](.+)['"]\r?$/gm, (_, bindings, module) => {
  if (bindings.startsWith('{')) { assert.ok(dependencies[module], module); return `const ${bindings} = deps[${JSON.stringify(module)}]` }
  return `const ${bindings} = {}`
})
const component = new Function('deps', `${code}; return component`)(dependencies)
let state
const setup = component.setup
component.setup = (props, context) => { state = setup(props, context); return state }
component.render = () => null
const renderer = Vue.createRenderer({ createElement: () => ({}), createText: () => ({}), createComment: () => ({}), insert() {}, remove() {}, setText() {}, setElementText() {}, patchProp() {}, parentNode: () => null, nextSibling: () => null })
const app = renderer.createApp(component, { run, history: [run, baseline], inspection: version.inspection })
app.mount({})
try {
  for (let i = 0; i < 100 && !state.scheduleBaselineResult.value; i++) { await Promise.resolve(); await Vue.nextTick() }
  assert.deepEqual(state.result.value, run.result)
  assert.deepEqual(state.scheduleBaselineResult.value, baseline.result)
  assert.equal(state.factorUnit.value, 'cP.rb/day/psi')
  const response = state.factorResponse.value
  assert.equal(response.available, true); assert.equal(response.changedCount, receipt.effectCount)
  assert.deepEqual(response.missingIndicators, ['WOPT'])
  let verified = 0
  for (const row of response.rows) {
    const current = run.result.summary.series.find(s => s.keyword === row.keyword && s.objectName === 'G1')
    const source = baseline.result.summary.series.find(s => s.keyword === row.keyword && s.objectName === 'G1')
    assert.equal(row.unit, current.unit)
    assert.equal(row.baseline, source.points.find(p => p.timeDays === row.timeDays)?.value ?? null)
    assert.equal(row.current, current.points.find(p => p.timeDays === row.timeDays)?.value ?? null)
    assert.equal(row.difference, row.baseline === null || row.current === null ? null : row.current - row.baseline)
    verified++
  }
  assert.equal(verified, 324)
  const proof = { automatedComponentVerified: true, manualUiVerified: false, runId: run.id, baselineRunId: baseline.id,
    responseRows: verified, changedRows: response.changedCount, unmatchedRows: response.unmatchedCount, missingIndicators: response.missingIndicators,
    seriesCount: run.result.summary.series.length, pointsPerSeries: run.result.summary.series[0].points.length }
  writeFileSync(resolve(directory, 'automated-result-ui.json'), JSON.stringify(proof, null, 2), { flag: 'wx' })
  console.log(JSON.stringify(proof))
} finally { app.unmount() }
