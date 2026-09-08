import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parse, compileScript } from '@vue/compiler-sfc'
import { transformSync } from 'esbuild'
import * as Vue from 'vue'

// 编译真实表单的setup，仅替换外部接口；用无DOM渲染器验证状态，不需要登录或写数据库。
const source = readFileSync(new URL('../src/views/Reservoir/WellboreLossContent.vue', import.meta.url), 'utf8')
const { descriptor } = parse(source.replace('</script>',
  'defineExpose({ mode, directForm, formulaForm, condensateForm, volumes, calculation, setMode, calculate, resetResult, save, buildInput })\n</script>'))
const { code } = transformSync(compileScript(descriptor, { id: 'vent-loss-test' }).content, { format: 'cjs' })

function mountLoss(lossKind = 'surface', response) {
  const calls = [], savedNodes = []
  const route = Vue.reactive({ query: { projectId: '19', gasReservoirId: '8' } })
  const api = {
    calculate: async data => {
      calls.push(data)
      return response ? response : { data: lossKind === 'surface'
        ? data.input.calculationMode === 'direct'
          ? { ventLossVolume: data.input.inputLossVolume, condensateLossVolume: data.input.inputCondensateLossVolume }
          : { ventLossVolume: 12, condensateLossVolume: 10 }
        : { wellboreLossVolume: 12 } }
    },
    save: async data => {
      calls.push(data)
      return { data: { id: 5, recordName: '记录5' } }
    }
  }
  const dependencies = {
    vue: Vue,
    'vue-router': { useRoute: () => route, useRouter: () => ({ replace: async () => {} }) },
    'element-plus': { ElMessage: { warning() {}, success() {}, error() {} } },
    '@/api/wellboreLoss': { wellboreLossApi: lossKind === 'wellbore' ? api : {} },
    '@/api/surfaceLoss': { surfaceLossApi: lossKind === 'surface' ? api : {} },
    '@/views/DataManagement/NaturalGasImportDialog.vue': {},
    '@/utils/workspaceTreeState': { workspaceTreeData: Vue.ref([]) },
    '@/utils/reservoirGeologicalLossTree': { upsertReservoirLossRecordNode: data => savedNodes.push(data) }
  }
  const module = { exports: {} }
  new Function('require', 'module', 'exports', code)(id => {
    if (!(id in dependencies)) throw new Error('Unmocked module: ' + id)
    return dependencies[id]
  }, module, module.exports)
  const component = module.exports.default
  component.render = () => null
  const noop = () => {}
  const renderer = Vue.createRenderer({
    createElement: () => ({}), createText: () => ({}), createComment: () => ({}),
    setText: noop, setElementText: noop, insert: noop, remove: noop, patchProp: noop,
    parentNode: () => null, nextSibling: () => null
  })
  const app = renderer.createApp(component, { lossKind })
  const vm = app.mount({})
  return { vm, calls, savedNodes, route, close: () => app.unmount() }
}

test('surface reset clears both results but preserves all input values', async () => {
  const ctx = mountLoss()
  try {
    ctx.vm.setMode('direct')
    ctx.vm.directForm.lossVolume = '12'
    ctx.vm.directForm.condensateLossVolume = '10'
    await ctx.vm.calculate()
    assert.equal(ctx.calls.length, 1)
    assert.equal(ctx.calls[0].projectId, 19)
    assert.equal(ctx.calls[0].gasReservoirId, 8)
    assert.deepEqual(ctx.vm.buildInput(),
      { calculationMode: 'direct', inputLossVolume: 12, inputCondensateLossVolume: 10 })
    assert.equal(ctx.vm.calculation.condensateLossVolume, 10)
    ctx.vm.resetResult()
    assert.equal(ctx.vm.calculation, null)
    assert.equal(ctx.vm.directForm.lossVolume, '12')
    assert.equal(ctx.vm.directForm.condensateLossVolume, '10')
    assert.equal(ctx.vm.mode, 'direct')
  } finally { ctx.close() }
})

test('wellbore mode does not require or submit condensate fields', async () => {
  const ctx = mountLoss('wellbore')
  try {
    ctx.vm.setMode('direct')
    ctx.vm.directForm.lossVolume = '12'
    await ctx.vm.calculate()
    assert.deepEqual(ctx.calls[0].input, { calculationMode: 'direct', inputLossVolume: 12 })
    assert.equal(ctx.vm.calculation.wellboreLossVolume, 12)
  } finally { ctx.close() }
})

test('editing condensate inputs discards an in-flight result', async () => {
  let resolve
  const pending = new Promise(done => { resolve = done })
  const ctx = mountLoss('surface', pending)
  try {
    fillFormula(ctx.vm)
    Object.assign(ctx.vm.condensateForm, { volume: '.2', gasOilRatio: '50' })
    const calculation = ctx.vm.calculate()
    ctx.vm.condensateForm.volume = '.4'
    resolve({ data: { ventLossVolume: 12, condensateLossVolume: 10 } })
    await calculation
    assert.equal(ctx.vm.calculation, null)
  } finally { ctx.close() }
})

test('surface saves use the current project and update the surface record folder', async () => {
  const ctx = mountLoss()
  try {
    fillFormula(ctx.vm)
    Object.assign(ctx.vm.condensateForm, { volume: '.2', gasOilRatio: '50' })
    await ctx.vm.calculate()
    await ctx.vm.save()
    assert.equal(ctx.calls[1].projectId, 19)
    assert.equal(ctx.calls[1].gasReservoirId, 8)
    assert.equal(ctx.calls[1].recordId, null)
    assert.equal(ctx.savedNodes[0].lossType, 'surface')
    assert.equal(ctx.savedNodes[0].record.id, 5)
  } finally { ctx.close() }
})

function fillFormula(vm) {
  vm.setMode('formula')
  Object.assign(vm.formulaForm, { averageTemperature: '313.15', pressureBefore: '20',
    pressureAfter: '10', specificGravity: '.7336', h2SMoleFraction: '0',
    co2MoleFraction: '0', n2MoleFraction: '0' })
  vm.volumes[0].value = '100'
}

test('surface direct validates both amounts and saves only the new direct input fields', async () => {
  const ctx = mountLoss()
  try {
    ctx.vm.setMode('direct')
    ctx.vm.directForm.lossVolume = '0'
    await ctx.vm.calculate()
    assert.equal(ctx.vm.calculation, null)
    ctx.vm.directForm.condensateLossVolume = '-1'
    await ctx.vm.calculate()
    assert.equal(ctx.vm.calculation, null)
    ctx.vm.directForm.condensateLossVolume = '0'
    await ctx.vm.calculate()
    assert.deepEqual({ ...ctx.vm.calculation }, { ventLossVolume: 0, condensateLossVolume: 0 })
    await ctx.vm.save()
    assert.equal(ctx.calls.length, 2)
    assert.deepEqual(ctx.calls[1].input, { calculationMode: 'direct', inputLossVolume: 0, inputCondensateLossVolume: 0 })
    assert.equal(ctx.savedNodes.length, 1)
    assert.equal(ctx.savedNodes[0].lossType, 'surface')
    ctx.vm.setMode('formula')
    assert.equal(ctx.vm.calculation, null)
    assert.equal(ctx.vm.directForm.condensateLossVolume, '0')
  } finally { ctx.close() }
})
