import * as relperm from '../src/views/SoftwareIntegration/eclipseRelperm.js'
import * as gasPvt from '../src/views/SoftwareIntegration/eclipseGasPvt.js'
import * as oilPvt from '../src/views/SoftwareIntegration/eclipseOilPvt.js'
import * as pvtw from '../src/views/SoftwareIntegration/eclipsePvtw.js'
import * as newCompletion from '../src/views/SoftwareIntegration/eclipseNewCompletion.js'
import * as newWell from '../src/views/SoftwareIntegration/eclipseNewWell.js'
import * as completionGeometry from '../src/views/SoftwareIntegration/eclipseCompletionGeometry.js'
// Production geometry/field/locator replay using actual 5173 artifact ranges.
import assert from 'node:assert/strict'
import { readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { compileScript, parse } from '@vue/compiler-sfc'
import * as Vue from 'vue'
import * as THREE from 'three'
import * as requests from '../src/views/SoftwareIntegration/eclipseFieldRequest.js'
import * as survey from '../src/views/SoftwareIntegration/eclipseDeviationSurvey.js'
import * as corners from '../src/views/SoftwareIntegration/eclipseCornerGeometry.js'
import * as gridRegion from '../src/views/SoftwareIntegration/eclipseGridRegion.js'

const directory = resolve(process.argv[2])
const load = name => JSON.parse(readFileSync(resolve(directory, name + '.json'), 'utf8'))
assert.equal(load('acceptance-summary').apiVerified, true)
const baseline = load('run-baseline'), target = load('run-target')
const ranges = []
const emitted = []
const deps = {
  vue: Vue, three: THREE,
  'three/addons/controls/OrbitControls.js': { OrbitControls: class {} },
  'element-plus': { ElMessage: { warning() {}, success() {} } },
  './eclipseFieldRequest': requests, './eclipseDeviationSurvey': survey, './eclipseCornerGeometry': corners,
  './eclipseNewCompletion': newCompletion, './eclipseNewWell': newWell, './eclipseGasPvt': gasPvt, './eclipseRelperm': relperm, './eclipseOilPvt': oilPvt, './eclipsePvtw': pvtw, './eclipseCompletionGeometry': completionGeometry, './eclipseGridRegion': gridRegion,
  '@/api/softwareIntegration': { softwareIntegrationApi: {
    async downloadArtifactRange(runId, artifactId, offset, length) {
      const response = await fetch(`http://127.0.0.1:5173/api/software-integration/runs/${runId}/artifacts/${artifactId}/range?offset=${offset}&length=${length}`)
      assert.equal(response.status, 206)
      const artifact = [baseline, target].find(run => run.id === runId).artifacts.find(item => item.id === artifactId)
      assert.equal(response.headers.get('content-range'), `bytes ${offset}-${offset + length - 1}/${artifact.sizeBytes}`)
      const bytes = new Uint8Array(await response.arrayBuffer())
      assert.equal(bytes.length, length)
      ranges.push({ runId, artifactId, offset, length })
      return bytes
    }
  } }
}
const source = readFileSync(new URL('../src/views/SoftwareIntegration/EclipseGrid3D.vue', import.meta.url), 'utf8')
const compiled = compileScript(parse(source).descriptor, { id: 'real-permx', genDefaultAs: 'component' }).content
const code = compiled.replace(/^import (.+) from ['"](.+)['"]\r?$/gm, (_, bindings, module) => {
  assert.ok(deps[module], module)
  if (bindings.startsWith('* as ')) return `const ${bindings.slice(5)} = deps[${JSON.stringify(module)}]`
  return `const ${bindings} = deps[${JSON.stringify(module)}]`
})
const component = new Function('deps', 'cancelAnimationFrame', `${code}; return component`)(deps, () => {})
let state
const setup = component.setup
component.setup = (props, context) => { state = setup(props, context); return state }
component.render = () => null
const propsFor = run => ({ run, runId: run.id, grid: run.result.grid,
  fields: run.result.fieldIndex.files.flatMap(file => file.fields.map(field => ({ file: file.name, byteOrder: file.byteOrder, ...field }))),
  artifacts: run.artifacts, wellCompletions: [] })
const props = Vue.reactive(propsFor(baseline))
const renderer = Vue.createRenderer({ createElement: () => ({}), createText: () => ({}), createComment: () => ({}), insert() {}, remove() {}, setText() {}, setElementText() {}, patchProp() {}, parentNode: () => null, nextSibling: () => null })
const app = renderer.createApp({ render: () => Vue.h(component, { ...props, onCreateGridFieldRun: value => emitted.push(value) }) })
const wait = async () => {
  for (let i = 0; i < 600; i++) {
    await new Promise(resolve => setTimeout(resolve, 20)); await Vue.nextTick()
    if (state.error.value || state.fieldError.value) throw new Error(state.error.value || state.fieldError.value)
    if (state.geometryReady.value && !state.loading.value && !state.fieldLoading.value && state.fieldValues.value.length === 225) return
  }
  throw new Error('Real grid range loading timeout')
}
app.mount({})
try {
  await wait()
  state.selectedFieldKey.value = state.fieldKey(props.fields.find(field => field.keyword === 'PERMX' && field.file.endsWith('.INIT')))
  await wait()
  assert.equal(state.geometrySummary.value.activeCells, 225)
  assert.deepEqual(state.fieldValues.value, Array(225).fill(100))
  state.locateI.value = 1; state.locateJ.value = 1; state.locateK.value = 1; state.locateCell()
  assert.equal(state.selectedCell.value.index, 0)
  assert.equal(state.selectedCell.value.value, 100)
  assert.equal(state.canCreatePermxRun.value, true)
  state.runPermxScenario()
  assert.deepEqual(emitted, [target.parameters])
  Object.assign(props, propsFor(target)); await Vue.nextTick(); await wait()
  state.selectedFieldKey.value = state.fieldKey(props.fields.find(field => field.keyword === 'PERMX' && field.file.endsWith('.INIT')))
  await wait()
  assert.deepEqual(state.fieldValues.value, [50, ...Array(224).fill(100)])
  assert.equal(state.canCreatePermxRun.value, false)
  for (let j = 1; j <= 15; j++) for (let i = 1; i <= 15; i++) {
    state.locateI.value = i; state.locateJ.value = j; state.locateK.value = 1; state.locateCell()
    assert.equal(state.selectedCell.value.index, (j - 1) * 15 + i - 1)
    assert.equal(state.selectedCell.value.value, i === 1 && j === 1 ? 50 : 100)
    assert.equal(state.selectedCell.value.points.length, 8)
  }
  state.locateI.value = 16; state.locateCell()
  assert.equal(state.selectedCell.value, null)
  assert.ok(state.locateError.value)
  const proof = { automatedComponentVerified: true, manualUiVerified: false, baselineRunId: baseline.id, targetRunId: target.id,
    decodedActiveCells: 225, verifiedCoordinateSelections: 225, changedCellValue: 50, unchangedPermxCells: 224,
    exactProductionRequest: true, invalidSelectionCleared: true, actualRangeRequests: ranges.length }
  writeFileSync(resolve(directory, 'automated-grid-ui.json'), JSON.stringify(proof, null, 2), { flag: 'wx' })
  console.log(JSON.stringify(proof))
} finally { app.unmount() }
