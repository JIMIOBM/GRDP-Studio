import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { reactive, computed, ref, watch, nextTick, effectScope } from 'vue'
import * as calculation from './productivityCoefficientCalculation.js'

const source = readFileSync(new URL('../views/SingleWellProductivity/ExponentialContent.vue', import.meta.url), 'utf8')
const script = source.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import[\s\S]*?from\s+['"][^'"]+['"]\s*;?/gm, '')
const flush = async () => { await new Promise(r => setImmediate(r)); await nextTick() }
function workspace(method = '指数式') {
  const props = reactive({ recordId:null, methodType:method, wellName:'A1-3', projectId:7, gasReservoirId:4,
    maximumFormationPressure:56, formationTemperature:120, productivityCoefficientC:2, productivityExponentN:.6,
    correctedCoefficientC:3, correctedExponentN:.6, fittedFormationPressure:24, fittedFlowRate:9,
    operationType:'production', openFlowRate:'', pvtRecord:{pvtId:8,pvtName:'PVT1',gasResultRows:[]} })
  const requests = [], errors = [], records = new Map()
  const emits = (event, value) => {
    if (event === 'update:open-flow-rate') props.openFlowRate = value
    if (event === 'saved') props.recordId = value.id
    if (event === 'restore') {
      const p = value.parameters
      Object.assign(props, { maximumFormationPressure:p.pressure,formationTemperature:p.temperature,
        productivityCoefficientC:p.c,productivityExponentN:p.n,correctedCoefficientC:p.correctedC,
        correctedExponentN:p.correctedN,fittedFormationPressure:p.pointPressure,fittedFlowRate:p.pointRate,
        operationType:value.operation,pvtRecord:value.pvtSnapshot })
    }
  }
  const api = {
    save: async payload => { requests.push(payload); const row={...payload,id:payload.id || 12,name:'记录1',version:'coefficient-v1'}; records.set(row.id,row); return row },
    detail: async id => { if (!records.has(Number(id))) throw new Error('不存在'); return records.get(Number(id)) }
  }
  const deps = { ...calculation, computed, ref, watch, nextTick, onMounted(){}, onBeforeUnmount(){},
    defineProps:()=>props,defineEmits:()=>emits,productivityCoefficientApi:api,
    ElMessage:{success(){},error:m=>errors.push(m)} }
  const scope=effectScope()
  const state=scope.run(()=>new Function(...Object.keys(deps), `${script}; return {handleCalculate,saveRecord,canSave,calculationMethod,binomialCoefficientA,binomialCoefficientB,correctedBinomialA,correctedBinomialB,storedId,calculatedRequest,inputSnapshot}`)(...Object.values(deps)))
  state.calculationMethod.value='压力法'
  if(method==='二项式') {
    state.binomialCoefficientA.value=2; state.binomialCoefficientB.value=3
    state.correctedBinomialA.value=4; state.correctedBinomialB.value=5
  }
  return {props,state,scope,requests,errors,records}
}
for(const method of ['指数式','二项式']) test(`${method}计算后保存、再次保存更新原ID，编辑后不能保存旧结果，刷新读取恢复快照`, async()=>{
  const w=workspace(method)
  try {
    await flush()
    assert.equal(w.state.canSave.value,false)
    w.state.handleCalculate()
    assert.equal(w.state.canSave.value,true)
    await w.state.saveRecord(); await flush()
    assert.equal(w.requests[0].id,null)
    assert.equal(w.requests[0].method,method)
    assert.ok(!('points' in w.requests[0]))
    await w.state.saveRecord()
    assert.equal(w.requests[1].id,12)
    w.props.maximumFormationPressure=55
    assert.equal(w.state.canSave.value,false)
    await w.state.saveRecord()
    assert.equal(w.requests.length,2)
    w.props.recordId=null; await flush()
    w.props.recordId=12; await flush()
    assert.equal(w.props.maximumFormationPressure,56)
    assert.equal(w.state.storedId.value,12)
    assert.equal(w.state.canSave.value,true)
    assert.deepEqual(w.errors,[])
  } finally {w.scope.stop()}
})
