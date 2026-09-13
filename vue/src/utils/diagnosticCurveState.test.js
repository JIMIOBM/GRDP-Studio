import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { computed, nextTick, ref, watch, reactive, effectScope } from 'vue'

const componentText = readFileSync(new URL('../views/WellControlInventory/DiagnosticCurveContent.vue', import.meta.url), 'utf8')
const setupCode = componentText.match(/<script setup>([\s\S]*?)<\/script>/)[1]
  .replace(/^import[\s\S]*?from\s+['"][^'"]+['"]\s*;?/gm, '')
const flush = async () => { await new Promise(resolve => setImmediate(resolve)); await nextTick() }

function setupWorkspace() {
  const props = reactive({ projectId: 7, gasReservoirId: 4, node: { wellName: 'A1-3', diagnosticId: 12 } })
  const events = []
  const requests = []
  const messages = []
  let loads = 0
  let rejectCalculate = false
  const api = {
    getRecord: async id => {
      loads++
      return { record: { diagnosticId: id, diagnosticName: '诊断曲线1' }, pvtId: 8,
        upperPressureLimit: 56, lowerPressureLimit: 20,
        productionData: [{ sequence: 1, time: '2026-09-01', gas: .25, cycle: '采气1' }], result: null }
    },
    calculate: async payload => {
      requests.push(payload)
      if (rejectCalculate) throw new Error('测试计算失败')
      return { cycles: [], calculationNo: requests.length }
    }
  }
  const dependencies = {
    computed, ref, watch, nextTick, defineProps: () => props,
    defineEmits: () => (...args) => events.push(args), onMounted() {}, onBeforeUnmount() {},
    pvtStorageApi: { list: async () => [{ pvtId: 8 }], getDetail: async () => ({ gasResults: [
      { pressure: 20, deviationFactor: .9 }, { pressure: 56, deviationFactor: 1.1 }
    ] }) },
    diagnosticCurveApi: api,
    ElMessage: { error: text => messages.push(text), warning: text => messages.push(text), success() {} },
    console: { log() {}, warn() {}, error() {} }
  }
  const scope = effectScope()
  const state = scope.run(() => new Function(...Object.keys(dependencies), `${setupCode}
    return { handleRecalculate, selectedPvtId, inputUpperLimit, inputLowerLimit, rows,
      result, diagnosticId, diagnosticName, activePanel, calculatedPvtSnapshot, calculating }`)(...Object.values(dependencies)))
  return { props, state, scope, requests, events, messages, loads: () => loads, fail: () => { rejectCalculate = true } }
}

test('诊断曲线计算完成不再绑定到新建页面入口，保存事件保留', () => {
  const parent = readFileSync(new URL('../views/IprInterface.vue', import.meta.url), 'utf8')
  const tag = parent.match(/<DiagnosticCurveContent\b[\s\S]*?\/>/)[0]
  assert.doesNotMatch(tag, /@recalculate/)
  assert.match(tag, /@saved="handleDiagnosticSaved"/)
})

test('打开已有诊断记录后连续计算，保留记录ID、压力、PVT与数据，仅更新结果', async () => {
  const w = setupWorkspace()
  try {
    await flush()
    assert.equal(w.state.diagnosticId.value, 12)
    w.state.inputUpperLimit.value = 55
    await w.state.handleRecalculate()
    await flush()
    await w.state.handleRecalculate()
    await flush()
    assert.equal(w.state.diagnosticId.value, 12)
    assert.equal(w.state.diagnosticName.value, '诊断曲线1')
    assert.equal(w.props.node.diagnosticId, 12)
    assert.equal(w.state.inputUpperLimit.value, 55)
    assert.equal(w.state.inputLowerLimit.value, 20)
    assert.equal(w.state.selectedPvtId.value, '8')
    assert.equal(w.state.rows.value.length, 1)
    assert.equal(w.state.rows.value[0].gas, .25)
    assert.equal(w.state.result.value.calculationNo, 2)
    assert.equal(w.state.activePanel.value, 'analysis')
    assert.equal(w.state.calculatedPvtSnapshot.value.zCurve.length, 2)
    assert.equal(w.loads(), 1)
    assert.deepEqual(w.events, [])
    assert.deepEqual(w.messages, [])
  } finally { w.scope.stop() }
})

test('诊断计算失败保留已有参数；主动切换记录仍正常读取新记录', async () => {
  const w = setupWorkspace()
  try {
    await flush()
    w.fail()
    await w.state.handleRecalculate()
    await flush()
    assert.equal(w.state.inputUpperLimit.value, 56)
    assert.equal(w.state.selectedPvtId.value, '8')
    assert.equal(w.state.rows.value.length, 1)
    assert.equal(w.state.calculating.value, false)
    assert.equal(w.messages.length, 1)
    w.props.node = { wellName: 'A1-3', diagnosticId: 13 }
    await flush()
    assert.equal(w.state.diagnosticId.value, 13)
    assert.equal(w.loads(), 2)
  } finally { w.scope.stop() }
})
