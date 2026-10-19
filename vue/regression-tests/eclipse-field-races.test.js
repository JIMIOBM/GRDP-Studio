import * as compositionalFluidProperty from '../src/views/SoftwareIntegration/eclipseCompositionalFluidProperty.js'
import * as waterInjectionPlan from '../src/views/SoftwareIntegration/eclipseWaterInjectionPlan.js'
import * as multiLiveOilPvt from '../src/views/SoftwareIntegration/eclipseMultiLiveOilPvt.js'
import * as multiWaterPvt from '../src/views/SoftwareIntegration/eclipseMultiWaterPvt.js'
import * as pvtwProperties from '../src/views/SoftwareIntegration/eclipsePvtw.js'
import * as multiWetGasPvt from '../src/views/SoftwareIntegration/eclipseMultiWetGasPvt.js'
import * as multiPvt from '../src/views/SoftwareIntegration/eclipseMultiPvt.js'
import * as wetGasPvt from '../src/views/SoftwareIntegration/eclipseWetGasPvt.js'
import * as liveOilPvt from '../src/views/SoftwareIntegration/eclipseLiveOilPvt.js'
import * as multiFamilyII from '../src/views/SoftwareIntegration/eclipseMultiSatFamilyII.js'
import * as multiSatRelperm from '../src/views/SoftwareIntegration/eclipseMultiSatRelperm.js'
import * as coupledRelperm from '../src/views/SoftwareIntegration/eclipseCoupledRelperm.js'
import * as relperm from '../src/views/SoftwareIntegration/eclipseRelperm.js'
import * as pvtw from '../src/views/SoftwareIntegration/eclipsePvtw.js'
import * as gasPvt from '../src/views/SoftwareIntegration/eclipseGasPvt.js'
import * as oilPvt from '../src/views/SoftwareIntegration/eclipseOilPvt.js'
import * as newCompletion from '../src/views/SoftwareIntegration/eclipseNewCompletion.js'
import * as newWell from '../src/views/SoftwareIntegration/eclipseNewWell.js'
import * as completionGeometry from '../src/views/SoftwareIntegration/eclipseCompletionGeometry.js'
import * as controlPlan from '../src/views/SoftwareIntegration/eclipseControlPlan.js'
import * as completionFactor from '../src/views/SoftwareIntegration/eclipseCompletionFactor.js'
import * as gridRegion from '../src/views/SoftwareIntegration/eclipseGridRegion.js'
import * as gridPresentation from '../src/views/SoftwareIntegration/eclipseGridPresentation.js'
import * as batch from '../src/views/SoftwareIntegration/eclipseBatch.js'
import * as restartForecast from '../src/views/SoftwareIntegration/eclipseRestartForecast.js'
import * as groupStructure from '../src/views/SoftwareIntegration/eclipseGroupStructure.js'
import * as advancedInjection from '../src/views/SoftwareIntegration/eclipseAdvancedInjection.js'
import * as groupWaterProduction from '../src/views/SoftwareIntegration/eclipseGroupWaterProduction.js'
import * as groupRate from '../src/views/SoftwareIntegration/eclipseGroupRate.js'
import * as groupGasInjection from '../src/views/SoftwareIntegration/eclipseGroupGasInjection.js'
import * as groupInjection from '../src/views/SoftwareIntegration/eclipseGroupInjection.js'
import * as groupProduction from '../src/views/SoftwareIntegration/eclipseGroupProduction.js'
import * as tstep from '../src/views/SoftwareIntegration/eclipseTstep.js'
import * as newDates from '../src/views/SoftwareIntegration/eclipseNewDates.js'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { compileScript, parse } from '@vue/compiler-sfc'
import * as Vue from 'vue'
import * as THREE from 'three'
import * as requests from '../src/views/SoftwareIntegration/eclipseFieldRequest.js'
import * as survey from '../src/views/SoftwareIntegration/eclipseDeviationSurvey.js'
import * as corners from '../src/views/SoftwareIntegration/eclipseCornerGeometry.js'
import * as wellEvents from '../src/views/SoftwareIntegration/eclipseWellEvents.js'
import * as productionTarget from '../src/views/SoftwareIntegration/eclipseProductionTarget.js'
import * as pressureControl from '../src/views/SoftwareIntegration/eclipsePressureControl.js'
import * as thpControl from '../src/views/SoftwareIntegration/eclipseThpControl.js'
import * as productionControl from '../src/views/SoftwareIntegration/eclipseProductionControl.js'

// Compile the production SFC setup and mount it with Vue's real lifecycle/watchers.
// Only transport and the host renderer are substituted; range decoding and geometry
// construction remain the production code. No WebGL canvas is created by this host.
const renderer = Vue.createRenderer({
  createElement: () => ({}), createText: () => ({}), createComment: () => ({}),
  insert() {}, remove() {}, setText() {}, setElementText() {}, patchProp() {},
  parentNode: () => null, nextSibling: () => null
})
const mount = (name, initial) => {
  const pending = []
  const api = {
    downloadArtifactRange(...args) {
      let resolve, reject
      const promise = new Promise((yes, no) => { resolve = yes; reject = no })
      pending.push({ args, resolve, reject })
      return promise
    }
  }
  const dependencies = {
    './eclipseWaterInjectionPlan': waterInjectionPlan,
    './eclipseBatch.js': batch, './eclipseCoupledRelperm.js': coupledRelperm,
    './eclipseWetGasPvt.js': wetGasPvt, './eclipseLiveOilPvt.js': liveOilPvt, './eclipseOilPvt.js': oilPvt, './eclipseGasPvt.js': gasPvt,
    './eclipseCompletionFactor': completionFactor,
    './eclipseWetGasPvt': wetGasPvt, './eclipseLiveOilPvt': liveOilPvt, './eclipseOilPvt': oilPvt, './eclipseGasPvt': gasPvt, './eclipseRelperm': relperm, './eclipseCoupledRelperm': coupledRelperm, './eclipseMultiSatRelperm': multiSatRelperm, './eclipseMultiSatRelperm.js': multiSatRelperm, './eclipseMultiLiveOilPvt.js': multiLiveOilPvt, './eclipseMultiLiveOilPvt': multiLiveOilPvt, './eclipseMultiWaterPvt.js': multiWaterPvt, './eclipseMultiWaterPvt': multiWaterPvt, './eclipsePvtw.js': pvtwProperties, './eclipseMultiWetGasPvt.js': multiWetGasPvt, './eclipseMultiWetGasPvt': multiWetGasPvt, './eclipseMultiPvt.js': multiPvt, './eclipseMultiPvt': multiPvt, './eclipseMultiSatFamilyII.js': multiFamilyII, './eclipseMultiSatFamilyII': multiFamilyII,
    './eclipseNewCompletion': newCompletion, './eclipseNewWell': newWell, './eclipsePvtw': pvtw, './eclipseCompletionGeometry': completionGeometry, './eclipseGridRegion': gridRegion,
    './eclipseRestartForecast': restartForecast,
    './eclipseCompositionalFluidProperty.js': compositionalFluidProperty,
    './eclipseGridPresentation.js': gridPresentation,
    vue: Vue, three: THREE,
    'three/addons/controls/OrbitControls.js': { OrbitControls: class {} },
    'element-plus': { ElMessage: { warning() {}, success() {}, error() {} } },
    '@/api/softwareIntegration': { softwareIntegrationApi: api },
    './eclipseFieldRequest': requests, './eclipseDeviationSurvey': survey, './eclipseCornerGeometry': corners,
    './eclipseWellEvents': wellEvents, './eclipseProductionTarget': productionTarget, './eclipsePressureControl': pressureControl, './eclipseProductionControl': productionControl, './eclipseGroupInjection': groupInjection, './eclipseGroupGasInjection': groupGasInjection, './eclipseControlPlan': controlPlan, './eclipseGroupStructure': groupStructure, './eclipseAdvancedInjection': advancedInjection, './eclipseGroupWaterProduction': groupWaterProduction, './eclipseGroupRate': groupRate, './eclipseGroupProduction': groupProduction, './eclipseTstep': tstep, './eclipseNewDates': newDates, './eclipseThpControl': thpControl
  }
  const source = readFileSync(new URL(`../src/views/SoftwareIntegration/${name}.vue`, import.meta.url), 'utf8')
  const { descriptor } = parse(source)
  const compiled = compileScript(descriptor, { id: name, genDefaultAs: 'component' }).content
  const code = compiled.replace(/^import (.+) from ['"](.+)['"]\r?$/gm, (_, bindings, module) => {
    if (bindings.startsWith('* as ')) return `const ${bindings.slice(5)} = deps[${JSON.stringify(module)}]`
    if (bindings.startsWith('{')) return `const ${bindings} = deps[${JSON.stringify(module)}]`
    return `const ${bindings} = {}` // Child SFCs have no role in these setup tests.
  })
  const component = new Function('deps', 'cancelAnimationFrame', `${code}; return component`)(dependencies, () => {})
  const setup = component.setup
  let state
  component.setup = (props, context) => { state = setup(props, context); return state }
  component.render = () => null
  const props = Vue.reactive(initial)
  const emitted = []
  const completionEmitted = []
  const pvtwEmitted = []
  const oilPvtEmitted = []
  const gasPvtEmitted = []
  const multiPvtEmitted = []
  const relpermEmitted = []
  const app = renderer.createApp({ render: () => Vue.h(component, {...props, onCreatePvtRun: parameters => multiPvtEmitted.push(parameters), onCreateRelpermRun: parameters => relpermEmitted.push(parameters), onCreateGasPvtRun: parameters => gasPvtEmitted.push(parameters), onCreateOilPvtRun: parameters => oilPvtEmitted.push(parameters), onCreatePvtwRun: parameters => pvtwEmitted.push(parameters), onCreateNewWellRun: parameters => emitted.push(parameters), onCreateNewCompletionRun: parameters => completionEmitted.push(parameters)}) })
  app.mount({})
  return { state, props, pending, emitted, completionEmitted, pvtwEmitted, oilPvtEmitted, gasPvtEmitted, relpermEmitted, multiPvtEmitted, unmount: () => app.unmount() }
}
const flush = async () => {
  for (let i = 0; i < 12; i++) { await Promise.resolve(); await Vue.nextTick() }
}

test('live oil source branches baseline and selected property clear stale preview and submission',async()=>{
  const inspection=JSON.parse(readFileSync(new URL('../tests/fixtures/eclipseLiveOilPvt/inspection.json',import.meta.url),'utf8'))
  const run={id:1,modelVersionId:2,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null}
  const h=mount('EclipseLiveOilPvtEditor',{run,inspection})
  try{h.state.multiplier.value=2;h.state.submit();assert.equal(h.oilPvtEmitted.length,1);assert.equal(h.state.preview.value.length,12);assert.equal(h.state.preview.value.at(-1).state,'欠饱和');h.state.field.value='FORMATION_VOLUME_FACTOR';await flush();assert.equal(h.state.multiplier.value,null);h.state.multiplier.value=1.02;h.state.submit();assert.equal(h.oilPvtEmitted[1].unit,'rb/stb');h.props.inspection.liveOilPvtContext.records.at(-1).rows.pop();await flush();assert.equal(h.state.record.value,null);assert.equal(h.state.multiplier.value,null);h.state.submit();assert.equal(h.oilPvtEmitted.length,2);h.props.run.id++;await flush();assert.equal(h.state.multiplier.value,null)}finally{h.unmount()}
})

test('wet gas source branches baseline and selected property clear stale preview and submission',async()=>{
  const inspection=JSON.parse(readFileSync(new URL('../tests/fixtures/eclipseWetGasPvt/inspection.json',import.meta.url),'utf8'))
  const run={id:1,modelVersionId:2,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null}
  const h=mount('EclipseWetGasPvtEditor',{run,inspection})
  try{h.state.multiplier.value=2;h.state.submit();assert.equal(h.gasPvtEmitted.length,1);assert.equal(h.state.preview.value.length,29);assert.equal(h.state.preview.value.at(-1).state,'欠饱和');h.state.field.value='FORMATION_VOLUME_FACTOR';await flush();assert.equal(h.state.multiplier.value,null);h.state.multiplier.value=1.02;h.state.submit();assert.equal(h.gasPvtEmitted[1].unit,'rm3/sm3');h.props.inspection.wetGasPvtContext.records.at(-1).rows.splice(1);await flush();assert.equal(h.state.record.value,null);assert.equal(h.state.multiplier.value,null);h.state.submit();assert.equal(h.gasPvtEmitted.length,2);h.props.run.id++;await flush();assert.equal(h.state.multiplier.value,null)}finally{h.unmount()}
})

test('gas PVT editor clears multiplier when source rows or selected baseline change',async()=>{
  const inspection=JSON.parse(readFileSync(new URL('../tests/fixtures/eclipseGasPvt/inspection.json',import.meta.url),'utf8'))
  const run={id:1,modelVersionId:2,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null}
  const h=mount('EclipseGasPvtEditor',{run,inspection})
  try{
    h.state.multiplier.value=2;h.state.submit();assert.equal(h.gasPvtEmitted.length,1);assert.equal(h.gasPvtEmitted[0].rows.length,2)
    h.props.run={...run,id:3};await flush();assert.equal(h.state.multiplier.value,null)
    h.state.multiplier.value=2;h.props.inspection.gasPvtContext.rows[0].values[2]='1.5';await flush();assert.equal(h.state.multiplier.value,null)
    h.state.submit();assert.equal(h.gasPvtEmitted.length,1)
    h.props.inspection.gasPvtContext.rows[0].values[2]='1*';await flush();assert.equal(h.state.record.value,null)
  }finally{h.unmount()}
})

test('oil PVT editor clears multiplier when source rows or selected baseline change',async()=>{
  const inspection=JSON.parse(readFileSync(new URL('../tests/fixtures/eclipseOilPvt/inspection.json',import.meta.url),'utf8'))
  const run={id:1,modelVersionId:2,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null}
  const h=mount('EclipseOilPvtEditor',{run,inspection})
  try{
    h.state.multiplier.value=2;h.state.submit();assert.equal(h.oilPvtEmitted.length,1);assert.equal(h.oilPvtEmitted[0].rows.length,6)
    h.props.run={...run,id:3};await flush();assert.equal(h.state.multiplier.value,null)
    h.state.multiplier.value=2;h.props.inspection.oilPvtContext.rows[0].values[2]='0.4';await flush();assert.equal(h.state.multiplier.value,null)
    h.state.submit();assert.equal(h.oilPvtEmitted.length,1)
    h.props.inspection.oilPvtContext.rows[0].values[2]='1*';await flush();assert.equal(h.state.record.value,null)
  }finally{h.unmount()}
})
test('oil volume selection clears the old multiplier and emits a Bo snapshot',async()=>{
  const inspection=JSON.parse(readFileSync(new URL('../tests/fixtures/eclipseOilPvt/inspection.json',import.meta.url),'utf8')),run={id:1,modelVersionId:2,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null},h=mount('EclipseOilPvtEditor',{run,inspection})
  try{h.state.multiplier.value=2;h.state.field.value='FORMATION_VOLUME_FACTOR';await flush();assert.equal(h.state.multiplier.value,null);h.state.multiplier.value=1.02;h.state.submit();assert.equal(h.oilPvtEmitted[0].field,'FORMATION_VOLUME_FACTOR');assert.equal(h.oilPvtEmitted[0].unit,'rb/stb');assert.equal(h.state.preview.value[0].target,1.26*1.02);h.props.run.id++;await flush();assert.equal(h.state.multiplier.value,null);h.state.submit();assert.equal(h.oilPvtEmitted.length,1)}finally{h.unmount()}
})
test('dry gas volume selection clears the old multiplier and emits a Bg snapshot',async()=>{
 const inspection=JSON.parse(readFileSync(new URL('../tests/fixtures/eclipseGasPvt/inspection.json',import.meta.url),'utf8')),run={id:1,modelVersionId:2,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null},h=mount('EclipseGasPvtEditor',{run,inspection})
 try{h.state.multiplier.value=2;h.state.field.value='FORMATION_VOLUME_FACTOR';await flush();assert.equal(h.state.multiplier.value,null);h.state.multiplier.value=1.02;h.state.submit();assert.equal(h.gasPvtEmitted[0].field,'FORMATION_VOLUME_FACTOR');assert.equal(h.gasPvtEmitted[0].unit,'rb/Mscf');assert.equal(h.state.preview.value[0].target,1.02);h.props.run.id++;await flush();assert.equal(h.state.multiplier.value,null);h.state.submit();assert.equal(h.gasPvtEmitted.length,1)}finally{h.unmount()}
})

test('new well editor: actual ACTNUM byte order, inactive rejection and stale coordinate/run replies never submit', async () => {
  const inspection=JSON.parse(readFileSync(new URL('../tests/fixtures/eclipseNewWell/inspection.json',import.meta.url),'utf8'))
  const initial={run:{id:485,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null},inspection,
    grid:{nx:15,ny:15,nz:1,fileName:'CASE.EGRID'},fields:[{keyword:'ACTNUM',file:'CASE.EGRID',byteOrder:'BIG',dataType:'INTE',count:225,elementSize:4,segments:[{offset:16,length:900}]}],
    artifacts:[{id:9,name:'eclipse-output-CASE.EGRID',contentType:'application/octet-stream',sha256:'a'.repeat(64)}]}
  const h=mount('EclipseNewWellEditor',initial)
  try{
    const fill=()=>{h.state.draft.value={well:'P2',group:'G',i:15,j:1,k:1,referenceDepth:4000,diameter:.2,kh:1000,skin:0,waterRate:100,bhpLimit:3000}}
    fill();const old=h.state.submit();assert.deepEqual(h.pending[0].args,[485,9,72,4]);h.state.draft.value.i=14;h.pending[0].resolve(binary([1],'INTE'));await old;assert.equal(h.emitted.length,0)
    fill();const inactive=h.state.submit();h.pending[1].resolve(binary([0],'INTE'));await inactive;assert.match(h.state.error.value,/活动单元/);assert.equal(h.emitted.length,0)
    fill();const valid=h.state.submit();h.pending[2].resolve(binary([1],'INTE'));await valid;assert.equal(h.emitted.length,1);assert.equal(h.emitted[0].i,15)
    const stale=h.state.submit();h.props.run={...initial.run,id:486};h.pending[3].resolve(binary([1],'INTE'));await stale;assert.equal(h.emitted.length,1)
    fill();const unmounted=h.state.submit();h.unmount();h.pending[4].reject(new Error('old response'));await unmounted;assert.equal(h.emitted.length,1)
  }finally{h.unmount()}
})
const binary = (values, type = 'REAL') => {
  const bytes = new Uint8Array(values.length * 4)
  const view = new DataView(bytes.buffer)
  values.forEach((value, i) => type === 'REAL' ? view.setFloat32(i * 4, value) : view.setInt32(i * 4, value))
  return bytes
}
test('new completion editor: inactive ACTNUM and stale coordinate/run/unmount replies cannot submit', async () => {
  const inspection=JSON.parse(readFileSync(new URL('../tests/fixtures/eclipseNewCompletion/inspection.json',import.meta.url),'utf8'))
  const initial={run:{id:498,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null},inspection,
    grid:{nx:15,ny:15,nz:1,fileName:'CASE.EGRID'},fields:[{keyword:'ACTNUM',file:'CASE.EGRID',byteOrder:'BIG',dataType:'INTE',count:225,elementSize:4,segments:[{offset:16,length:900}]}],
    artifacts:[{id:9,name:'eclipse-output-CASE.EGRID',contentType:'application/octet-stream',sha256:'a'.repeat(64)}]}
  const h=mount('EclipseNewCompletionEditor',initial);let disposed=false
  try{
    const fill=()=>{h.state.draft.value={well:'P',i:2,j:1,k:1,diameter:.2,kh:1000,skin:0}}
    fill();const stale=h.state.submit();assert.deepEqual(h.pending[0].args,[498,9,20,4]);h.state.draft.value.i=3;h.pending[0].resolve(binary([1],'INTE'));await stale;assert.equal(h.completionEmitted.length,0)
    fill();const inactive=h.state.submit();h.pending[1].resolve(binary([0],'INTE'));await inactive;assert.match(h.state.error.value,/活动单元/);assert.equal(h.completionEmitted.length,0)
    fill();const valid=h.state.submit();h.pending[2].resolve(binary([1],'INTE'));await valid;assert.equal(h.completionEmitted.length,1);assert.equal(h.completionEmitted[0].schemaVersion,'eclipse-new-completion-parameters/1');assert.equal(h.completionEmitted[0].i,2)
    const changed=h.state.submit();h.props.run={...initial.run,id:499};h.pending[3].reject(new Error('old reply'));await changed;assert.equal(h.completionEmitted.length,1);assert.equal(h.state.error.value,'')
    fill();const unmounted=h.state.submit();h.unmount();disposed=true;h.pending[4].resolve(binary([1],'INTE'));await unmounted;assert.equal(h.completionEmitted.length,1)
  }finally{if(!disposed)h.unmount()}
})
const field = (keyword = 'PRESSURE', options = {}) => ({
  file: 'CASE.INIT', keyword, dataType: 'REAL', byteOrder: 'BIG', elementSize: 4,
  count: 2, segments: [{ offset: 0, length: 4 }, { offset: 16, length: 4 }], ...options
})
const artifact = (id = 11, file = 'CASE.INIT') => ({ id, name: `eclipse-output-${file}`, contentType: 'application/octet-stream' })
const sliceProps = () => ({ runId: 1, grid: { nx: 2, ny: 1, nz: 1, activeCells: 2 }, fields: [field(), field('PERMX')], artifacts: [artifact()], timeStep: 'all' })

test('slice: reverse success, old failure/finally, field/layer/run changes and unmount', async () => {
  const h = mount('EclipseGridSlice', sliceProps())
  const s = h.state
  const old = s.loadSlice()
  s.selectedFieldKey.value = s.fieldKey(h.props.fields[1])
  assert.equal(s.slice.value, null)
  const current = s.loadSlice()
  h.pending[0].reject(new Error('old failure'))
  await old
  assert.equal(s.loading.value, true)
  assert.equal(s.error.value, '')
  h.pending[1].resolve(binary([30])); await flush()
  h.pending[2].resolve(binary([40])); await current
  assert.deepEqual(s.slice.value.rows.map(row => row.value), [30, 40])
  const stale = s.loadSlice()
  s.layer.value = 2
  assert.equal(s.slice.value, null)
  h.pending[3].resolve(binary([99])); await stale
  assert.equal(h.pending.length, 4) // No stale second range.
  assert.equal(s.slice.value, null)
  s.layer.value = 1
  const beforeRun = s.loadSlice()
  h.props.runId = 2; h.props.artifacts = [artifact(22)]; await flush()
  const next = s.loadSlice()
  h.pending[5].resolve(binary([5])); await flush()
  assert.deepEqual(h.pending[6].args, [2, 22, 16, 4])
  h.pending[6].resolve(binary([6])); await next
  h.pending[4].resolve(binary([88])); await beforeRun
  assert.deepEqual(s.slice.value.rows.map(row => row.value), [5, 6])
  const disposed = s.loadSlice()
  h.unmount(); h.pending[7].reject(new Error('unmounted')); await disposed
  assert.equal(s.error.value, '')
})

test('slice: ACTNUM compression and delayed Blob conversion cannot start a new-context field range', async () => {
  const options = sliceProps()
  options.grid.activeCells = 1
  options.fields = [field('ACTNUM', { file: 'CASE.EGRID', dataType: 'INTE' }), field('PERMX', { count: 1, segments: [{ offset: 32, length: 4 }] })]
  options.artifacts.push(artifact(12, 'CASE.EGRID'))
  const h = mount('EclipseGridSlice', options)
  const first = h.state.loadSlice()
  h.pending[0].resolve(binary([0], 'INTE')); await flush()
  h.pending[1].resolve(binary([1], 'INTE')); await flush()
  assert.deepEqual(h.pending[2].args, [1, 11, 32, 4])
  h.pending[2].resolve(binary([7])); await first
  assert.deepEqual(h.state.slice.value.rows.map(row => row.value), [null, 7])
  let resolveBuffer
  const blob = new Blob([])
  blob.arrayBuffer = () => new Promise(resolve => { resolveBuffer = resolve })
  const stale = h.state.loadSlice()
  h.pending[3].resolve(blob); await flush()
  h.props.runId = 2; await flush()
  resolveBuffer(binary([1], 'INTE').buffer); await stale
  assert.equal(h.pending.length, 4)
  assert.equal(h.state.slice.value, null)
  h.unmount()
})

test('preview: new field wins in reverse order; page, artifact, filter and unmount invalidate', async () => {
  const h = mount('EclipseRunResult', { run: { id: 1, artifacts: [artifact()] }, history: [], inspection: null })
  const s = h.state
  const old = s.previewField(field())
  const current = s.previewField(field('PERMX'))
  h.pending[1].resolve(binary([3])); await flush()
  h.pending[2].resolve(binary([4])); await current
  h.pending[0].resolve(binary([9])); await old
  assert.equal(s.fieldPreview.value.keyword, 'PERMX')
  assert.deepEqual(s.fieldPreview.value.values, [3, 4])
  const large = field('PRESSURE', { count: 400, segments: [{ offset: 0, length: 1600 }] })
  const page1 = s.previewField(large)
  s.loadFieldPreviewPage(2)
  assert.deepEqual(h.pending[4].args, [1, 11, 800, 800])
  h.pending[3].reject(new Error('old page')); await page1
  assert.equal(s.fieldPreviewLoading.value, true)
  assert.equal(s.fieldPreviewError.value, '')
  h.pending[4].resolve(binary(Array(200).fill(20))); await flush()
  assert.equal(s.fieldPreview.value.page, 2)
  const metadata = s.previewField(field())
  h.props.run.artifacts[0].id = 22; await flush()
  h.pending[5].resolve(binary([99])); await metadata
  assert.equal(s.fieldPreview.value, null)
  const filtered = s.previewField(field())
  s.fieldTimeStepFilter.value = 'static'
  h.pending[6].reject(new Error('filtered')); await filtered
  assert.equal(s.fieldPreviewError.value, '')
  const disposed = s.previewField(field())
  h.unmount(); h.pending[7].resolve(binary([8])); await disposed
  assert.equal(s.fieldPreview.value, null)
})

const geometryProps = () => ({
  runId: 1, run: { id: 1 }, grid: { fileName: 'CASE.EGRID', nx: 1, ny: 1, nz: 1, activeCells: 1 },
  fields: [
    field('COORD', { file: 'CASE.EGRID', count: 24, segments: [{ offset: 0, length: 96 }] }),
    field('ZCORN', { file: 'CASE.EGRID', count: 8, segments: [{ offset: 96, length: 32 }] }),
    field('ACTNUM', { file: 'CASE.EGRID', count: 1, dataType: 'INTE', segments: [{ offset: 128, length: 4 }] }),
    field('PRESSURE', { count: 1, segments: [{ offset: 0, length: 4 }] }),
    field('PERMX', { count: 1, segments: [{ offset: 16, length: 4 }] })
  ], artifacts: [artifact(), artifact(12, 'CASE.EGRID')], wellCompletions: []
})
const finishGeometry = async (h, start = 0) => {
  h.pending[start].resolve(binary([0,0,0,0,0,1, 1,0,0,1,0,1, 0,1,0,0,1,1, 1,1,0,1,1,1]))
  h.pending[start + 1].resolve(binary([0,0,0,0,1,1,1,1]))
  h.pending[start + 2].resolve(binary([1], 'INTE'))
  await flush()
}

test('3D: real geometry builds; field/layer races clear cell values, drafts and stale errors', async () => {
  const h = mount('EclipseGrid3D', geometryProps())
  await finishGeometry(h)
  assert.equal(h.state.geometrySummary.value.activeCells, 1)
  const s = h.state
  s.selectedFieldKey.value = s.fieldKey(h.props.fields[4])
  h.pending[4].resolve(binary([50])); await flush()
  h.pending[3].resolve(binary([90])); await flush()
  assert.deepEqual(s.fieldValues.value, [50])
  s.selectCell({ index: 0, i: 1, j: 1, k: 1, value: 50 })
  s.editValue.value = 60; s.applyEdit()
  assert.equal(Object.keys(s.draftOverrides.value).length, 1)
  const old = s.loadSelectedField()
  s.layer.value = 2
  assert.deepEqual(s.fieldValues.value, [])
  assert.equal(s.selectedCell.value, null)
  s.exportDraft() // Must return before accessing document/URL while new field is pending.
  h.pending[5].reject(new Error('old layer')); await old
  assert.equal(s.fieldLoading.value, true)
  assert.equal(s.fieldError.value, '')
  h.pending[6].resolve(binary([70])); await flush()
  assert.deepEqual(s.fieldValues.value, [70])
  const oldRunField = s.loadSelectedField()
  h.props.runId = 2; await flush()
  assert.deepEqual(s.draftOverrides.value, {})
  assert.deepEqual(s.fieldValues.value, [])
  h.pending[7].resolve(binary([999])); await oldRunField
  assert.deepEqual(s.fieldValues.value, [])
  h.unmount()
  for (const request of h.pending.slice(8)) request.reject(new Error('unmounted geometry'))
  await flush()
  assert.equal(s.error.value, '')
  assert.equal(s.fieldError.value, '')
})

test('3D: metadata/run replacement cancels old geometry multi-range continuation', async () => {
  const options = geometryProps()
  options.fields[0].segments = [{ offset: 0, length: 48 }, { offset: 48, length: 48 }]
  const h = mount('EclipseGrid3D', options)
  h.props.runId = 2
  await flush()
  h.pending[0].resolve(binary(Array(12).fill(0)))
  h.pending[1].resolve(binary(Array(8).fill(0)))
  h.pending[2].resolve(binary([1], 'INTE'))
  await flush()
  assert.equal(h.pending.length, 6)
  assert.equal(h.state.geometryReady.value, false)
  assert.ok(h.pending.slice(3).every(request => request.args[0] === 2))
  h.unmount()
  h.pending.slice(3).forEach(request => request.reject(new Error('disposed')))
  await flush()
  assert.equal(h.state.error.value, '')
})

test('3D: IJK locator selects decoded active cells and clears invalid or inactive targets', async () => {
  const h = mount('EclipseGrid3D', geometryProps())
  const s = h.state
  s.locateCell()
  assert.equal(s.selectedCell.value, null)
  await finishGeometry(h)
  h.pending[3].resolve(binary([100])); await flush()
  s.locateCell()
  assert.deepEqual([s.selectedCell.value.i, s.selectedCell.value.j, s.selectedCell.value.k, s.selectedCell.value.value], [1, 1, 1, 100])
  for (const invalid of [0, 2, 1.5, null, NaN]) {
    s.locateI.value = invalid
    s.locateCell()
    assert.equal(s.selectedCell.value, null)
    assert.equal(s.editValue.value, null)
    assert.match(s.locateError.value, /整数/)
  }
  s.locateI.value = 1
  s.locateCell()
  assert.equal(s.locateError.value, '')
  const pending = s.loadSelectedField()
  s.locateCell()
  assert.equal(s.selectedCell.value, null)
  h.pending[4].resolve(binary([50])); await pending
  s.locateCell()
  assert.equal(s.selectedCell.value.value, 50)
  h.unmount()

  const options = geometryProps()
  options.grid.nx = 2
  options.fields[0].count = 36
  options.fields[0].segments = [{ offset: 0, length: 144 }]
  options.fields[1].count = 16
  options.fields[1].segments = [{ offset: 144, length: 64 }]
  options.fields[2].count = 2
  options.fields[2].segments = [{ offset: 208, length: 8 }]
  const inactive = mount('EclipseGrid3D', options)
  inactive.pending[0].resolve(binary([0,0,0,0,0,1, 1,0,0,1,0,1, 2,0,0,2,0,1, 0,1,0,0,1,1, 1,1,0,1,1,1, 2,1,0,2,1,1]))
  inactive.pending[1].resolve(binary([0,0,0,0,0,0,0,0,1,1,1,1,1,1,1,1]))
  inactive.pending[2].resolve(binary([1,0], 'INTE')); await flush()
  inactive.pending[3].resolve(binary([100])); await flush()
  inactive.state.locateI.value = 2
  inactive.state.locateCell()
  assert.equal(inactive.state.selectedCell.value, null)
  assert.match(inactive.state.locateError.value, /ACTNUM/)
  inactive.unmount()
})

test('slice CSV exports only the completed layer; switching time step immediately blocks export', async () => {
  const h = mount('EclipseGridSlice', sliceProps())
  const task = h.state.loadSlice()
  h.pending[0].resolve(binary([10])); await flush()
  h.pending[1].resolve(binary([20])); await task
  const originalDocument = globalThis.document
  const createUrl = URL.createObjectURL
  const revokeUrl = URL.revokeObjectURL
  const blobs = []
  let clicks = 0
  globalThis.document = {
    body: { appendChild() {} },
    createElement: () => ({ click() { clicks++ }, remove() {} })
  }
  URL.createObjectURL = blob => { blobs.push(blob); return 'blob:test' }
  URL.revokeObjectURL = () => {}
  try {
    h.state.exportSliceCsv()
    assert.equal(clicks, 1)
    const csv = await blobs[0].text()
    assert.match(csv, /"CASE.INIT","PRESSURE","REAL","静态","1","1","1","10"/)
    h.props.timeStep = 'static'; await flush()
    h.state.exportSliceCsv()
    assert.equal(clicks, 1)
  } finally {
    // Let the export's zero-delay URL cleanup finish before restoring globals.
    await new Promise(resolve => setTimeout(resolve, 0))
    globalThis.document = originalDocument
    URL.createObjectURL = createUrl
    URL.revokeObjectURL = revokeUrl
    h.unmount()
  }
})

test('preview: a missing-artifact selection invalidates pending success and keeps its own error', async () => {
  const h = mount('EclipseRunResult', { run: { id: 1, artifacts: [artifact()] }, history: [], inspection: null })
  const old = h.state.previewField(field())
  await h.state.previewField(field('PRESSURE', { file: 'MISSING.INIT' }))
  const message = h.state.fieldPreviewError.value
  assert.ok(message.includes('Artifact'))
  h.pending[0].resolve(binary([90])); await old
  assert.equal(h.state.fieldPreview.value, null)
  assert.equal(h.state.fieldPreviewError.value, message)
  assert.equal(h.state.fieldPreviewLoading.value, false)
  h.unmount()
})

test('slice: compressed multi-read snapshots field segments and artifact before ACTNUM awaits', async () => {
  const options = sliceProps()
  options.grid.activeCells = 1
  options.fields = [field('ACTNUM', { file: 'CASE.EGRID', dataType: 'INTE' }), field('PERMX', { count: 1, segments: [{ offset: 32, length: 4 }] })]
  options.artifacts.push(artifact(12, 'CASE.EGRID'))
  const h = mount('EclipseGridSlice', options)
  const task = h.state.loadSlice()
  // Deliberately mutate the raw objects to bypass reactive invalidation: the
  // request snapshot itself must still prevent mixing metadata after an await.
  options.fields[0].segments[1].offset = 160
  options.fields[1].segments[0].offset = 320
  options.fields[1].byteOrder = 'LITTLE'
  options.artifacts[0].id = 99
  h.pending[0].resolve(binary([0], 'INTE')); await flush()
  assert.deepEqual(h.pending[1].args, [1, 12, 16, 4])
  h.pending[1].resolve(binary([1], 'INTE')); await flush()
  assert.deepEqual(h.pending[2].args, [1, 11, 32, 4])
  h.pending[2].resolve(binary([7])); await task
  assert.deepEqual(h.state.slice.value.rows.map(row => row.value), [null, 7])
  h.unmount()
})


test('water PVT editor clears draft when source or baseline changes and emits bound actual record',async()=>{
  const inspection=JSON.parse(readFileSync(new URL('../tests/fixtures/eclipsePvtw/inspection.json',import.meta.url),'utf8'))
  const h=mount('EclipsePvtwEditor',{run:{id:1,modelVersionId:2,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null},inspection})
  try{
    h.state.target.value=1;h.state.submit();assert.equal(h.pvtwEmitted.length,1);assert.equal(h.pvtwEmitted[0].originalValue,.5)
    h.props.inspection.pvtwContext.recordValues[3]='.75';await flush();assert.equal(h.state.target.value,null)
    h.state.target.value=1;h.state.submit();assert.equal(h.pvtwEmitted[1].originalValue,.75)
    h.props.run={...h.props.run,id:2};await flush();assert.equal(h.state.target.value,null)
    h.state.target.value=0;h.state.submit();assert.equal(h.pvtwEmitted.length,2);assert.match(h.state.error.value,/大于0/)
  }finally{h.unmount()}
})

test('water property selector clears a viscosity draft before pressure or compressibility submission',async()=>{
 const inspection=JSON.parse(readFileSync(new URL('../tests/fixtures/eclipsePvtw/inspection.json',import.meta.url),'utf8'))
 const h=mount('EclipsePvtwEditor',{run:{id:1,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null},inspection})
 try{h.state.target.value=1;h.state.field.value='REFERENCE_PRESSURE';await flush();assert.equal(h.state.target.value,null);h.state.target.value=4000;h.state.submit();assert.equal(h.pvtwEmitted[0].unit,'barsa');assert.equal(h.pvtwEmitted[0].originalValue,0);h.state.field.value='COMPRESSIBILITY';await flush();assert.equal(h.state.target.value,null);assert.equal(h.state.unit.value,'1/bar')}finally{h.unmount()}
})

test('relative permeability selector clears water draft before paired oil submission',async()=>{
 const inspection=JSON.parse(readFileSync(new URL('../tests/fixtures/eclipseRelperm/inspection.json',import.meta.url),'utf8'))
 const h=mount('EclipseRelpermEditor',{run:{id:1,modelVersionId:2,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null},inspection})
 try{h.state.multiplier.value=.5;h.state.submit();assert.equal(h.relpermEmitted[0].keyword,'SWFN');assert.equal(h.relpermEmitted[0].rows.length,13);h.state.keyword.value='SOF3';await flush();assert.equal(h.state.multiplier.value,null);h.state.multiplier.value=.5;h.state.submit();assert.equal(h.relpermEmitted[1].keyword,'SOF3');assert.equal(h.relpermEmitted[1].rows.length,15)}finally{h.unmount()}
})
test('relative permeability editor invalidates baseline or cross-table endpoint changes',async()=>{
 const inspection=JSON.parse(readFileSync(new URL('../tests/fixtures/eclipseRelperm/inspection.json',import.meta.url),'utf8'))
 const run={id:1,modelVersionId:2,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null},h=mount('EclipseRelpermEditor',{run,inspection})
 try{h.state.multiplier.value=.5;h.props.run={...run,id:3};await flush();assert.equal(h.state.multiplier.value,null);h.state.multiplier.value=.5;h.props.inspection.relpermContext.tables[2].rows.at(-1).values[2]='0.8';await flush();assert.equal(h.state.context.value,null);assert.equal(h.state.multiplier.value,null);h.state.submit();assert.equal(h.relpermEmitted.length,0)}finally{h.unmount()}
})


test('coupled relperm editor resets multiplier on phase and baseline changes and emits both linked tables',async()=>{
 const inspection=JSON.parse(readFileSync(new URL('../tests/fixtures/eclipseCoupledRelperm/inspection.json',import.meta.url),'utf8'))
 const run={id:1,modelVersionId:2,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null},h=mount('EclipseCoupledRelpermEditor',{run,inspection})
 try{h.state.multiplier.value=.5;h.state.field.value='OIL_KR';await flush();assert.equal(h.state.multiplier.value,null);h.state.multiplier.value=.5;h.state.submit();assert.equal(h.state.error.value,'');const p=h.relpermEmitted[0];assert.equal(p.field,'OIL_KR');assert.deepEqual(p.tables,inspection.coupledRelpermContext.tables);h.props.run={...run,id:2};await flush();assert.equal(h.state.multiplier.value,null)}finally{h.unmount()}
})


test('multi SAT editor changes region without carrying an old multiplier and emits only chosen original rows',async()=>{
 const inspection=JSON.parse(readFileSync(new URL('../tests/fixtures/eclipseMultiSatRelperm/inspection.json',import.meta.url),'utf8'))
 const run={id:1,modelVersionId:2,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null},h=mount('EclipseCoupledRelpermEditor',{run,inspection})
 try{h.state.multiplier.value=.5;h.state.regionNumber.value=2;await flush();assert.equal(h.state.multiplier.value,null);h.state.field.value='OIL_KR';await flush();h.state.multiplier.value=.5;h.state.submit();const p=h.relpermEmitted[0];assert.equal(p.regionNumber,2);assert.equal(p.schemaVersion,'eclipse-multi-sat-relperm-parameters/1');assert.deepEqual(p.tables,inspection.multiSatRelpermContext.regions[1].tables);h.props.run={...run,id:3};await flush();assert.equal(h.state.regionNumber.value,1);assert.equal(h.state.multiplier.value,null)}finally{h.unmount()}
})


test('three-table multi SAT editor clears region drafts and emits both SOF3 oil columns from chosen source',async()=>{
 const inspection=JSON.parse(readFileSync(new URL('../tests/fixtures/eclipseMultiSatFamilyII/inspection.json',import.meta.url),'utf8'))
 const run={id:1,modelVersionId:2,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null},h=mount('EclipseMultiSatFamilyIIEditor',{run,inspection})
 try{h.state.multiplier.value=.5;h.state.regionNumber.value=2;await flush();assert.equal(h.state.multiplier.value,null);h.state.field.value='OIL_KR';await flush();h.state.multiplier.value=.5;h.state.submit();const p=h.relpermEmitted[0];assert.equal(p.regionNumber,2);assert.equal(p.schemaVersion,'eclipse-multi-sat-relperm-parameters/2');assert.deepEqual(p.tables,inspection.multiSatRelpermContext.regions[1].tables);assert.equal(h.state.preview.value[2].rows.at(-1).target,h.state.preview.value[2].rows.at(-1).targetOilGas);h.props.run={...run,id:3};await flush();assert.equal(h.state.regionNumber.value,1);assert.equal(h.state.multiplier.value,null)}finally{h.unmount()}
})

test('multi PVT editor clears drafts when fluid region field or baseline changes and binds the chosen complete pair',async()=>{
 const inspection=JSON.parse(readFileSync(new URL('../tests/fixtures/eclipseMultiPvt/inspection.json',import.meta.url),'utf8'))
 const run={id:1,modelVersionId:2,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null},h=mount('EclipseMultiPvtEditor',{run,inspection})
 try{h.state.multiplier.value=2;h.state.regionNumber.value=2;await flush();assert.equal(h.state.multiplier.value,null);h.state.kind.value='PVT_GAS_BG';await flush();h.state.multiplier.value=1.02;h.state.submit();assert.equal(h.state.error.value,'');const p=h.multiPvtEmitted[0];assert.equal(p.regionNumber,2);assert.equal(p.keyword,'PVDG');assert.equal(p.unit,'rb/Mscf');assert.deepEqual(p.tables,inspection.multiPvtContext.regions[1].tables);h.props.run={...run,id:3};await flush();assert.equal(h.state.regionNumber.value,1);assert.equal(h.state.multiplier.value,null)}finally{h.unmount()}
})

test('multi PVTO editor clears region field and baseline drafts and binds every selected branch with original dry gas rows',async()=>{
 const inspection=JSON.parse(readFileSync(new URL('../tests/fixtures/eclipseMultiLiveOilPvt/inspection.json',import.meta.url),'utf8'))
 const run={id:1,modelVersionId:2,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null},h=mount('EclipseMultiLiveOilPvtEditor',{run,inspection})
 try{h.state.multiplier.value=2;h.state.regionNumber.value=2;await flush();assert.equal(h.state.multiplier.value,null);h.state.field.value='FORMATION_VOLUME_FACTOR';await flush();h.state.multiplier.value=1.02;h.state.submit();assert.equal(h.state.error.value,'');const p=h.oilPvtEmitted[0];assert.equal(p.regionNumber,2);assert.equal(p.unit,'rb/stb');assert.equal(p.records.length,9);assert.equal(h.state.preview.value.length,12);assert.deepEqual(p.gasTable,inspection.multiLiveOilPvtContext.regions[1].gasTable);h.props.run={...run,id:3};await flush();assert.equal(h.state.regionNumber.value,1);assert.equal(h.state.multiplier.value,null)}finally{h.unmount()}
})

test('multi PVTG editor clears region field and baseline drafts and binds every selected branch with original dry gas rows',async()=>{
 const inspection=JSON.parse(readFileSync(new URL('../tests/fixtures/eclipseMultiWetGasPvt/inspection.json',import.meta.url),'utf8'))
 const run={id:1,modelVersionId:2,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null},h=mount('EclipseMultiWetGasPvtEditor',{run,inspection})
 try{h.state.multiplier.value=2;h.state.regionNumber.value=2;await flush();assert.equal(h.state.multiplier.value,null);h.state.field.value='FORMATION_VOLUME_FACTOR';await flush();h.state.multiplier.value=1.02;h.state.submit();assert.equal(h.state.error.value,'');const p=h.gasPvtEmitted[0];assert.equal(p.regionNumber,2);assert.equal(p.unit,'rm3/sm3');assert.equal(p.records.length,13);assert.equal(h.state.preview.value.length,29);assert.deepEqual(p.oilTable,inspection.multiWetGasPvtContext.regions[1].oilTable);h.props.run={...run,id:3};await flush();assert.equal(h.state.regionNumber.value,1);assert.equal(h.state.multiplier.value,null)}finally{h.unmount()}
})

test('combined SAT and PVT batch switches require a fresh region selection',async()=>{
 const inspection=JSON.parse(readFileSync(new URL('../tests/fixtures/eclipseMultiSatPvt/inspection.json',import.meta.url),'utf8'))
 const run={id:1,modelVersionId:2,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null},h=mount('EclipseBatchPanel',{run,inspection})
 try{h.state.kind.value='PVT_OIL_MU';await flush();h.state.regionNumber.value=2;await flush();h.state.value.value=2;h.state.kind.value='FAMILY_II_GAS_KR';await flush();assert.equal(h.state.regionNumber.value,null);assert.equal(h.state.value.value,null);h.state.regionNumber.value=2;await flush();h.state.value.value=.5;h.state.kind.value='PVT_GAS_BG';await flush();assert.equal(h.state.regionNumber.value,null);assert.equal(h.state.value.value,null)}finally{h.unmount()}
})

test('coupled-table SAT and PVT batch switches require a fresh region selection',async()=>{
 const inspection=JSON.parse(readFileSync(new URL('../tests/fixtures/eclipseMultiSatFamilyIPvt/inspection.json',import.meta.url),'utf8'))
 const run={id:1,modelVersionId:2,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null},h=mount('EclipseBatchPanel',{run,inspection})
 try{h.state.kind.value='PVT_OIL_MU';await flush();h.state.regionNumber.value=2;await flush();h.state.value.value=2;h.state.kind.value='MULTI_GAS_KR';await flush();assert.equal(h.state.regionNumber.value,null);assert.equal(h.state.value.value,null);h.state.regionNumber.value=2;await flush();h.state.value.value=.5;h.state.kind.value='PVT_GAS_BG';await flush();assert.equal(h.state.regionNumber.value,null);assert.equal(h.state.value.value,null)}finally{h.unmount()}
})

test('regional water editor resets absolute target on region, field and baseline changes',async()=>{
 const inspection=JSON.parse(readFileSync(new URL('../tests/fixtures/eclipseMultiWaterPvt/inspection.json',import.meta.url),'utf8'))
 const run={id:1,modelVersionId:2,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null},h=mount('EclipseMultiWaterPvtEditor',{run,inspection})
 try{assert.equal(h.state.regionNumber.value,null);h.state.regionNumber.value=2;await flush();assert.equal(h.state.preview.value.length,5);h.state.target.value=.8;h.state.field.value='VISCOSIBILITY';await flush();assert.equal(h.state.target.value,null);h.state.target.value=1e-6;h.state.submit();assert.equal(h.state.error.value,'');const p=h.pvtwEmitted[0];assert.equal(p.regionNumber,2);assert.equal(p.unit,'1/psi');assert.equal(p.targetValue,1e-6);assert.deepEqual(p.recordValues,inspection.multiWaterPvtContext.regions[1].recordValues);h.state.regionNumber.value=1;await flush();assert.equal(h.state.target.value,null);h.props.run={...run,id:3};await flush();assert.equal(h.state.regionNumber.value,null);assert.equal(h.state.target.value,null)}finally{h.unmount()}
})

test('live-oil combined panels require independent SAT and PVT selections and clear invalid drafts',async()=>{
 const inspection=JSON.parse(readFileSync(new URL('../tests/fixtures/eclipseMultiSatPvto/inspection.json',import.meta.url),'utf8')),run={id:1,modelVersionId:2,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null}
 for(const [name,field,emitted] of [['EclipseMultiLiveOilPvtEditor','FORMATION_VOLUME_FACTOR','oilPvtEmitted'],['EclipseMultiSatFamilyIIEditor','OIL_KR','relpermEmitted']]){const h=mount(name,{run,inspection});try{assert.equal(h.state.regionNumber.value,null);h.state.multiplier.value=.5;h.state.submit();assert.equal(h[emitted].length,0);h.state.regionNumber.value=2;await flush();assert.equal(h.state.multiplier.value,null);h.state.field.value=field;await flush();h.state.multiplier.value=field==='OIL_KR'?.5:1.02;h.state.submit();assert.equal(h.state.error.value,'');assert.equal(h[emitted][0].regionNumber,2);h.props.run={...run,id:3};await flush();assert.equal(h.state.regionNumber.value,null);assert.equal(h.state.multiplier.value,null)}finally{h.unmount()}}
 const h=mount('EclipseBatchPanel',{run,inspection});try{h.state.kind.value='REGION_PVTO_MU';await flush();h.state.regionNumber.value=2;await flush();h.state.value.value=2;h.state.kind.value='FAMILY_II_GAS_KR';await flush();assert.equal(h.state.regionNumber.value,null);assert.equal(h.state.value.value,null)}finally{h.unmount()}
})

test('wet-gas combined panels require independent SAT and PVT selections and clear invalid drafts',async()=>{
 const inspection=JSON.parse(readFileSync(new URL('../tests/fixtures/eclipseMultiSatPvtg/inspection.json',import.meta.url),'utf8')),run={id:1,modelVersionId:2,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null}
 for(const [name,field,emitted] of [['EclipseMultiWetGasPvtEditor','FORMATION_VOLUME_FACTOR','gasPvtEmitted'],['EclipseMultiSatFamilyIIEditor','OIL_KR','relpermEmitted']]){const h=mount(name,{run,inspection});try{assert.equal(h.state.regionNumber.value,null);h.state.multiplier.value=.5;h.state.submit();assert.equal(h[emitted].length,0);h.state.regionNumber.value=2;await flush();assert.equal(h.state.multiplier.value,null);h.state.field.value=field;await flush();h.state.multiplier.value=field==='OIL_KR'?.5:1.02;h.state.submit();assert.equal(h.state.error.value,'');assert.equal(h[emitted][0].regionNumber,2);h.props.run={...run,id:3};await flush();assert.equal(h.state.regionNumber.value,null);assert.equal(h.state.multiplier.value,null)}finally{h.unmount()}}
 const h=mount('EclipseBatchPanel',{run,inspection});try{h.state.kind.value='REGION_PVTG_MU';await flush();h.state.regionNumber.value=2;await flush();h.state.value.value=2;h.state.kind.value='FAMILY_II_GAS_KR';await flush();assert.equal(h.state.regionNumber.value,null);assert.equal(h.state.value.value,null)}finally{h.unmount()}
})

test('Family I wet-gas combined panels require independent SAT and PVT selections and clear invalid drafts',async()=>{
 const inspection=JSON.parse(readFileSync(new URL('../tests/fixtures/eclipseMultiSatFamilyIPvtg/inspection.json',import.meta.url),'utf8')),run={id:1,modelVersionId:2,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null}
 for(const [name,field,emitted] of [['EclipseMultiWetGasPvtEditor','FORMATION_VOLUME_FACTOR','gasPvtEmitted'],['EclipseCoupledRelpermEditor','OIL_KR','relpermEmitted']]){const h=mount(name,{run,inspection});try{assert.equal(h.state.regionNumber.value,null);h.state.multiplier.value=.5;h.state.submit();assert.equal(h[emitted].length,0);h.state.regionNumber.value=2;await flush();assert.equal(h.state.multiplier.value,null);h.state.field.value=field;await flush();h.state.multiplier.value=field==='OIL_KR'?.5:1.02;h.state.submit();assert.equal(h.state.error.value,'');assert.equal(h[emitted][0].regionNumber,2);h.props.run={...run,id:3};await flush();assert.equal(h.state.regionNumber.value,null);assert.equal(h.state.multiplier.value,null)}finally{h.unmount()}}
 const h=mount('EclipseBatchPanel',{run,inspection});try{h.state.kind.value='REGION_PVTG_MU';await flush();h.state.regionNumber.value=2;await flush();h.state.value.value=2;h.state.kind.value='MULTI_GAS_KR';await flush();assert.equal(h.state.regionNumber.value,null);assert.equal(h.state.value.value,null)}finally{h.unmount()}
})

test('Family I original live-oil combined panels require independent SAT and PVT selections and clear invalid drafts',async()=>{
 const inspection=JSON.parse(readFileSync(new URL('../tests/fixtures/eclipseMultiSatFamilyIPvto/inspection.json',import.meta.url),'utf8')),run={id:1,modelVersionId:2,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null}
 for(const [name,field,emitted] of [['EclipseMultiLiveOilPvtEditor','FORMATION_VOLUME_FACTOR','oilPvtEmitted'],['EclipseCoupledRelpermEditor','OIL_KR','relpermEmitted']]){const h=mount(name,{run,inspection});try{assert.equal(h.state.regionNumber.value,null);h.state.multiplier.value=.5;h.state.submit();assert.equal(h[emitted].length,0);h.state.regionNumber.value=2;await flush();assert.equal(h.state.multiplier.value,null);h.state.field.value=field;await flush();h.state.multiplier.value=field==='OIL_KR'?.5:1.02;h.state.submit();assert.equal(h.state.error.value,'');assert.equal(h[emitted][0].regionNumber,2);h.props.run={...run,id:3};await flush();assert.equal(h.state.regionNumber.value,null);assert.equal(h.state.multiplier.value,null)}finally{h.unmount()}}
 const h=mount('EclipseBatchPanel',{run,inspection});try{h.state.kind.value='REGION_PVTO_MU';await flush();h.state.regionNumber.value=2;await flush();h.state.value.value=2;h.state.kind.value='MULTI_GAS_KR';await flush();assert.equal(h.state.regionNumber.value,null);assert.equal(h.state.value.value,null)}finally{h.unmount()}
})

test('official fifth water 1* previews effective zero while retaining raw source through region and field changes',async()=>{
 for(const kind of ['three','pure']){const inspection=JSON.parse(readFileSync(new URL(`../tests/fixtures/eclipseMultiWaterStars/${kind}/inspection.json`,import.meta.url),'utf8')),run={id:1,modelVersionId:2,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null},h=mount('EclipseMultiWaterPvtEditor',{run,inspection});try{assert.equal(h.state.regionNumber.value,null);h.state.regionNumber.value=2;await flush();assert.equal(h.state.preview.value[4].value,'0（官方默认，源记录1*）');h.state.field.value='VISCOSIBILITY';await flush();assert.equal(h.state.original.value,0);h.state.target.value=1e-6;h.state.submit();assert.equal(h.state.error.value,'');assert.equal(h.pvtwEmitted[0].schemaVersion,'eclipse-multi-pvtw-parameters/3');assert.equal(h.pvtwEmitted[0].recordValues[4],'1*');h.state.regionNumber.value=1;await flush();assert.equal(h.state.target.value,null);h.props.run={...run,id:3};await flush();assert.equal(h.state.regionNumber.value,null)}finally{h.unmount()}}
})
