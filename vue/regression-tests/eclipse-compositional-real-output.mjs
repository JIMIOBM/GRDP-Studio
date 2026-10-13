// Production component setup replay of saved real E300 runs; separate from human acceptance.
import assert from 'node:assert/strict'
import { readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { compileScript, parse } from '@vue/compiler-sfc'
import * as Vue from 'vue'
import * as compositional from '../src/views/SoftwareIntegration/eclipseCompositional.js'

const directory = resolve(process.argv[2])
const load = name => JSON.parse(readFileSync(resolve(directory, name), 'utf8'))
const receipt = load('acceptance-summary.json')
assert.equal(receipt.apiVerified, true)
const version = load('version-official.json'), run = load('run-official-first.json')
const source = readFileSync(new URL('../src/views/SoftwareIntegration/EclipseCompositionalResult.vue', import.meta.url), 'utf8')
const compiled = compileScript(parse(source).descriptor, { id: 'real-e300', genDefaultAs: 'component' }).content
const dependencies = { vue: Vue, './eclipseCompositional': compositional }
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
const app = renderer.createApp(component, { run, inspection: version.inspection })
app.mount({})
try {
  assert.deepEqual(state.result.value, run.result)
  state.hideUndefined.value = false
  assert.equal(state.rows.value.length, 330)
  for (let i = 0; i < 330; i++) {
    const original = run.result.summary.series[i], displayed = state.plotted.value[i], row = state.rows.value[i]
    assert.equal(row.vectorIndex, original.vectorIndex); assert.equal(row.number, original.number)
    assert.equal(displayed.keyword, original.keyword); assert.equal(displayed.unit, original.unit)
    assert.equal(displayed.sourceRunId, run.id); assert.deepEqual(displayed.points, original.points)
  }
  assert.equal(compositional.compositionalCsv(run.result).split('\r\n').length, 41251)
  const counts = {}
  for (let index = 1; index <= 9; index++) {
    state.componentIndex.value = index
    const expected = run.result.summary.series.filter(s => compositional.componentOf(s, run.result.simulation)?.index === index)
    assert.deepEqual(state.selected.value, expected); counts[index] = expected.length
  }
  assert.equal(counts[9], 0)
  assert.equal(compositional.compositionalObject(run.result.summary.series.find(s => s.keyword === 'BVOIL'), version.inspection.dimensions), '网格 (7, 7, 4)')
  const proof = { automatedComponentVerified: true, manualUiVerified: false, runId: run.id, seriesCount: 330, pointsPerSeries: 125,
    numericPaths: 82500, maxDifference: 0, csvRows: 41251, componentOutputCounts: counts }
  writeFileSync(resolve(directory, 'automated-result-ui.json'), JSON.stringify(proof, null, 2), { flag: 'wx' })
  console.log(JSON.stringify(proof))
} finally { app.unmount() }
