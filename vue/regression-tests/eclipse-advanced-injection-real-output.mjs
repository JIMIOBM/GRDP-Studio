import * as relperm from '../src/views/SoftwareIntegration/eclipseRelperm.js'
import * as gasPvt from '../src/views/SoftwareIntegration/eclipseGasPvt.js'
import * as oilPvt from '../src/views/SoftwareIntegration/eclipseOilPvt.js'
import * as pvtw from '../src/views/SoftwareIntegration/eclipsePvtw.js'
import * as newCompletion from '../src/views/SoftwareIntegration/eclipseNewCompletion.js'
import * as newWell from '../src/views/SoftwareIntegration/eclipseNewWell.js'
import * as completionGeometry from '../src/views/SoftwareIntegration/eclipseCompletionGeometry.js'
import * as gridRegion from '../src/views/SoftwareIntegration/eclipseGridRegion.js'
import * as completionFactor from '../src/views/SoftwareIntegration/eclipseCompletionFactor.js'
import * as restartForecast from '../src/views/SoftwareIntegration/eclipseRestartForecast.js'
import * as controlPlan from '../src/views/SoftwareIntegration/eclipseControlPlan.js'
import * as groupStructure from '../src/views/SoftwareIntegration/eclipseGroupStructure.js'
import * as advancedInjection from '../src/views/SoftwareIntegration/eclipseAdvancedInjection.js'
import * as groupWaterProduction from '../src/views/SoftwareIntegration/eclipseGroupWaterProduction.js'
import * as groupRate from '../src/views/SoftwareIntegration/eclipseGroupRate.js'
import * as groupGasInjection from '../src/views/SoftwareIntegration/eclipseGroupGasInjection.js'
import * as groupInjection from '../src/views/SoftwareIntegration/eclipseGroupInjection.js'
import * as groupProduction from '../src/views/SoftwareIntegration/eclipseGroupProduction.js'
import * as tstep from '../src/views/SoftwareIntegration/eclipseTstep.js'
import * as newDates from '../src/views/SoftwareIntegration/eclipseNewDates.js'
// Replay saved real solver results through the current production result component's setup.
// This checks its decision on real numbers, separately from browser fixtures and human acceptance.
import assert from 'node:assert/strict'
import { readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { compileScript, parse } from '@vue/compiler-sfc'
import * as Vue from 'vue'
import * as requests from '../src/views/SoftwareIntegration/eclipseFieldRequest.js'
import * as events from '../src/views/SoftwareIntegration/eclipseWellEvents.js'
import * as production from '../src/views/SoftwareIntegration/eclipseProductionTarget.js'
import * as pressure from '../src/views/SoftwareIntegration/eclipsePressureControl.js'
import * as rates from '../src/views/SoftwareIntegration/eclipseProductionControl.js'
import * as thp from '../src/views/SoftwareIntegration/eclipseThpControl.js'

const directory = resolve(process.argv[2])
const expected = process.argv[3]
assert.ok(['success','limited'].includes(expected))
const load = name => JSON.parse(readFileSync(resolve(directory, name), 'utf8'))
const receipt = load('acceptance-summary.json')
assert.equal(receipt.apiVerified, true)
const [baselineId, positiveId] = receipt.runIds
const derivedId = expected === 'limited' ? receipt.limitedRunId : positiveId
const baseline = load(`run-${baselineId}.json`), run = load(`run-${derivedId}.json`)
const version = readdirSync(directory).filter(n => /^version-\d+\.json$/.test(n)).map(load).find(v => v.id === run.modelVersionId)
assert.ok(version)
const dependencies = { './eclipseNewCompletion': newCompletion, './eclipseNewWell': newWell, './eclipseGasPvt': gasPvt, './eclipseRelperm': relperm, './eclipseOilPvt': oilPvt, './eclipsePvtw': pvtw, './eclipseCompletionGeometry': completionGeometry, './eclipseGridRegion': gridRegion, './eclipseCompletionFactor': completionFactor, './eclipseRestartForecast': restartForecast, vue: Vue, 'element-plus': { ElMessage: { error() {} } }, '@/api/softwareIntegration': { softwareIntegrationApi: { async getRun(id) { assert.equal(id, baselineId); return { code: 200, data: baseline } } } }, './eclipseFieldRequest': requests, './eclipseWellEvents': events, './eclipseProductionTarget': production, './eclipsePressureControl': pressure, './eclipseProductionControl': rates, './eclipseGroupInjection': groupInjection, './eclipseGroupGasInjection': groupGasInjection, './eclipseControlPlan': controlPlan, './eclipseGroupStructure': groupStructure, './eclipseAdvancedInjection': advancedInjection, './eclipseGroupWaterProduction': groupWaterProduction, './eclipseGroupRate': groupRate, './eclipseGroupProduction': groupProduction, './eclipseTstep': tstep, './eclipseNewDates': newDates, './eclipseThpControl': thp }
const source = readFileSync(new URL('../src/views/SoftwareIntegration/EclipseRunResult.vue', import.meta.url), 'utf8')
const compiled = compileScript(parse(source).descriptor, { id: 'real-thp', genDefaultAs: 'component' }).content
const code = compiled.replace(/^import (.+) from ['"](.+)['"]\r?$/gm, (_, bindings, module) => bindings.startsWith('{') ? `const ${bindings} = deps[${JSON.stringify(module)}]` : `const ${bindings} = {}`)
const component = new Function('deps', `${code}; return component`)(dependencies)
let state
const setup = component.setup
component.setup = (props, context) => { state = setup(props, context); return state }
component.render = () => null
const renderer = Vue.createRenderer({ createElement: () => ({}), createText: () => ({}), createComment: () => ({}), insert() {}, remove() {}, setText() {}, setElementText() {}, patchProp() {}, parentNode: () => null, nextSibling: () => null })
const app = renderer.createApp(component, { run, history: [baseline], inspection: version.inspection })
app.mount({})
for (let i = 0; i < 12; i++) { await Promise.resolve(); await Vue.nextTick() }
const type = state.scheduleAcceptanceType.value, label = state.scheduleAcceptanceLabel.value
assert.equal(type, expected === 'limited' ? 'warning' : 'success')
assert.equal(state.scheduleAdvancedInjectionResponse.value.verified, expected !== 'limited')
if (expected === 'limited') assert.equal(state.scheduleAdvancedInjectionResponse.value.constrained, true)
const evidence = { automatedComponentVerified: true, manualUiVerified: false, runId: derivedId, type, label, response: state.scheduleAdvancedInjectionResponse.value }
writeFileSync(resolve(directory, `automated-result-ui-${expected}.json`), JSON.stringify(evidence, null, 2), { flag: 'wx' })
app.unmount()
console.log(JSON.stringify({ automatedComponentVerified: true, runId: derivedId, type, label }))
