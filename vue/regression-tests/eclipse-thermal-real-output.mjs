// Replay the production SFC against all saved native thermal values; human acceptance remains separate.
import assert from 'node:assert/strict'
import { readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { compileScript, parse } from '@vue/compiler-sfc'
import * as Vue from 'vue'
import * as thermal from '../src/views/SoftwareIntegration/eclipseThermal.js'

const directory = resolve(process.argv[2])
const load = name => JSON.parse(readFileSync(resolve(directory, name), 'utf8'))
assert.equal(load('acceptance-summary.json').apiVerified, true)
const version = load('version-official.json'), run = load('run-official-first.json')
const source = readFileSync(new URL('../src/views/SoftwareIntegration/EclipseThermalResult.vue', import.meta.url), 'utf8')
const compiled = compileScript(parse(source).descriptor, { id: 'real-thermal', genDefaultAs: 'component' }).content
const dependencies = { vue: Vue, './eclipseThermal': thermal }
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
  assert.equal(state.category.value, 'temperature')
  assert.equal(state.rows.value.length, 5)
  state.hideUndefined.value = false
  state.category.value = 'all'
  assert.equal(state.rows.value.length, 343)
  for (let i = 0; i < 343; i++) {
    const original = run.result.summary.series[i], displayed = state.plotted.value[i], row = state.rows.value[i]
    assert.equal(row.vectorIndex, original.vectorIndex); assert.equal(row.number, original.number)
    assert.equal(displayed.keyword, original.keyword); assert.equal(displayed.unit, original.unit)
    assert.equal(displayed.sourceRunId, run.id); assert.deepEqual(displayed.points, original.points)
  }
  assert.equal(thermal.thermalCsv(run.result).split('\r\n').length, 42533)
  const counts = {}
  for (const category of ['temperature', 'steam', 'heat', 'all']) {
    state.category.value = category
    const expected = run.result.summary.series.filter(s => category === 'all' || thermal.thermalCategory(s.keyword) === category)
    assert.deepEqual(state.selected.value, expected); counts[category] = expected.length
  }
  assert.equal(counts.temperature, 5)
  assert.ok(counts.steam > 0); assert.equal(counts.heat, 2)
  const temperatures = run.result.summary.series.filter(s => thermal.thermalCategory(s.keyword) === 'temperature')
  assert.ok(temperatures.every(s => s.unit === 'DEG F' && s.points.at(-1).timeDays === 3650))
  assert.equal(thermal.thermalObject(temperatures.find(s => s.keyword === 'BTEMP' && s.number === 144), version.inspection.dimensions), '网格 (9, 1, 4)')
  const proof = { automatedComponentVerified: true, manualUiVerified: false, runId: run.id, seriesCount: 343, pointsPerSeries: 124,
    numericPaths: 85064, maxDifference: 0, csvRows: 42533, categoryOutputCounts: counts,
    temperatures: temperatures.map(s => ({ keyword: s.keyword, object: thermal.thermalObject(s, version.inspection.dimensions), unit: s.unit, last: s.points.at(-1) })) }
  writeFileSync(resolve(directory, 'automated-result-ui.json'), JSON.stringify(proof, null, 2), { flag: 'wx' })
  console.log(JSON.stringify(proof))
} finally { app.unmount() }
