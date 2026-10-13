import * as relperm from '../src/views/SoftwareIntegration/eclipseRelperm.js'
import * as gasPvt from '../src/views/SoftwareIntegration/eclipseGasPvt.js'
import * as oilPvt from '../src/views/SoftwareIntegration/eclipseOilPvt.js'
import * as pvtw from '../src/views/SoftwareIntegration/eclipsePvtw.js'
import * as newCompletion from '../src/views/SoftwareIntegration/eclipseNewCompletion.js'
import * as newWell from '../src/views/SoftwareIntegration/eclipseNewWell.js'
import * as completionGeometry from '../src/views/SoftwareIntegration/eclipseCompletionGeometry.js'
import * as gridRegion from '../src/views/SoftwareIntegration/eclipseGridRegion.js'
import * as completionFactor from '../src/views/SoftwareIntegration/eclipseCompletionFactor.js'
// Replay saved commercial solver output through production component setup.
// No fixture numbers replace the actual curves; this is separate from human acceptance.
import assert from 'node:assert/strict'
import { readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { compileScript, parse } from '@vue/compiler-sfc'
import * as Vue from 'vue'

const directory = resolve(process.argv[2])
const load = name => JSON.parse(readFileSync(resolve(directory, name), 'utf8'))
const receipt = load('acceptance-summary.json')
assert.equal(receipt.apiVerified, true)
const history = load(`run-${receipt.historyRunId}.json`)
const first = load(`run-${receipt.firstRunId}.json`)
const second = load(`run-${receipt.secondRunId}.json`)
const version = readdirSync(directory).filter(n => /^version-\d+\.json$/.test(n)).map(load).find(v => v.id === first.modelVersionId)
assert.ok(version)
const catalogBytes = readFileSync(resolve(directory, 'source-catalog.json'))
const calls = []
const api = {
  async getRun(id) {
    calls.push(id)
    const run = [history, first, second].find(r => r.id === id)
    assert.ok(run)
    return { code: 200, data: run }
  },
  async downloadArtifact(runId, id) {
    assert.equal(runId, history.id)
    assert.equal(id, receipt.catalogArtifactId)
    return new Blob([catalogBytes])
  }
}
const dependencies = { './eclipseNewCompletion': newCompletion, './eclipseNewWell': newWell, './eclipseGasPvt': gasPvt, './eclipseRelperm': relperm, './eclipseOilPvt': oilPvt, './eclipsePvtw': pvtw, './eclipseCompletionGeometry': completionGeometry, './eclipseGridRegion': gridRegion, './eclipseCompletionFactor': completionFactor, vue: Vue, 'element-plus': { ElMessage: { error() {} } }, '@/api/softwareIntegration': { softwareIntegrationApi: api } }
for (const name of readdirSync(new URL('../src/views/SoftwareIntegration/', import.meta.url)).filter(n => /^eclipse.*\.js$/.test(n))) {
  dependencies['./' + name.slice(0, -3)] = await import(new URL('../src/views/SoftwareIntegration/' + name, import.meta.url))
}
const renderer = Vue.createRenderer({ createElement: () => ({}), createText: () => ({}), createComment: () => ({}), insert() {}, remove() {}, setText() {}, setElementText() {}, patchProp() {}, parentNode: () => null, nextSibling: () => null })
const mounted = []
const mount = name => {
  const source = readFileSync(new URL('../src/views/SoftwareIntegration/' + name, import.meta.url), 'utf8')
  const compiled = compileScript(parse(source).descriptor, { id: 'actual-restart-' + name, genDefaultAs: 'component' }).content
  const code = compiled.replace(/^import (.+) from ['"](.+)['"]\r?$/gm, (_, bindings, module) => {
    if (bindings.startsWith('{')) { assert.ok(dependencies[module], module); return `const ${bindings} = deps[${JSON.stringify(module)}]` }
    return `const ${bindings} = {}`
  })
  const component = new Function('deps', `${code}; return component`)(dependencies)
  let state
  const setup = component.setup
  component.setup = (props, context) => { state = setup(props, context); return state }
  component.render = () => null
  const app = renderer.createApp(component, { run: first, history: [history, first, second], inspection: version.inspection })
  app.mount({})
  mounted.push(app)
  return state
}
const settle = async predicate => {
  for (let i = 0; i < 200; i++) {
    await new Promise(resolve => setTimeout(resolve, 5))
    await Vue.nextTick()
    if (predicate()) return
  }
  assert.fail('Component source verification did not settle')
}
try {
  const parent = mount('EclipseRunResult.vue')
  await settle(() => !parent.forecastHistoryLoading.value)
  assert.equal(parent.forecastHistoryVerified.value, true)
  assert.equal(parent.forecastHistoryError.value, '')
  const state = mount('EclipseRestartForecastCompare.vue')
  await settle(() => !state.loading.value)
  assert.equal(state.error.value, '')
  assert.equal(state.historical.value.id, history.id)
  assert.deepEqual(state.source.value, JSON.parse(catalogBytes.toString('utf8')))
  const assertSeries = (actual, inputs) => {
    assert.equal(actual.length, 88 * inputs.length)
    let offset = 0
    for (const input of inputs) {
      for (const original of input.result.summary.series) {
        const displayed = actual[offset++]
        assert.equal(displayed.keyword, original.keyword)
        assert.equal(displayed.objectName, original.objectName)
        assert.equal(displayed.unit, original.unit)
        assert.equal(displayed.sourceRunId, input.id)
        assert.deepEqual(displayed.points, original.points)
      }
    }
  }
  assertSeries(state.twoStageSeries.value, [history, first])
  assert.equal(state.options.value.length, 1)
  state.otherId.value = second.id
  await settle(() => state.other.value?.id === second.id)
  const comparison = state.comparison.value
  assert.equal(comparison.valid, true, comparison.reason)
  assertSeries(comparison.series, [history, first, second])
  assert.equal(comparison.rows.length, 24)
  for (const row of comparison.rows) {
    const find = run => run.result.summary.series.find(s => s.keyword === row.keyword && (s.objectName || '全场') === row.objectName)
    const h = find(history).points.find(p => p.timeDays === 2190).value
    const a = find(first).points.find(p => p.timeDays === row.timeDays).value
    const b = find(second).points.find(p => p.timeDays === row.timeDays).value
    assert.equal(row.historical, h)
    assert.equal(row.firstTotal, a)
    assert.equal(row.secondTotal, b)
    assert.equal(row.firstIncrement, a - h)
    assert.equal(row.secondIncrement, b - h)
    assert.equal(row.difference, a - b)
    assert.ok(['1976-12-31', '1977-12-31'].includes(row.date))
  }
  const proof = { automatedComponentVerified: true, manualUiVerified: false, runIds: [history.id, first.id, second.id], sourceCalls: calls,
    twoStageSeriesCount: 176, threeStageSeriesCount: 264, cumulativeRows: comparison.rows, maxDifference: 0, originDate: state.source.value.catalog.startDate }
  writeFileSync(resolve(directory, 'automated-result-ui.json'), JSON.stringify(proof, null, 2), { flag: 'wx' })
  console.log(JSON.stringify({ automatedComponentVerified: true, twoStageSeriesCount: 176, threeStageSeriesCount: 264, cumulativeRowCount: 24 }))
} finally { mounted.forEach(app => app.unmount()) }
