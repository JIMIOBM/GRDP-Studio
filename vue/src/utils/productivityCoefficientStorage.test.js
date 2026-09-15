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
  const state=scope.run(()=>new Function(...Object.keys(deps), `${script}; return {handleCalculate,saveRecord,canSave,calculationMethod,binomialCoefficientA,binomialCoefficientB,correctedBinomialA,correctedBinomialB,storedId,calculatedRequest,inputSnapshot,loadingRecord,saving,calculationBlocker,fitPointHint,chartType,updateChart,setChart: value => {chart=value}}`)(...Object.values(deps)))
  state.calculationMethod.value='压力法'
  if(method==='二项式') {
    state.binomialCoefficientA.value=2; state.binomialCoefficientB.value=3
    state.correctedBinomialA.value=4; state.correctedBinomialB.value=5
  }
  return {props,state,scope,requests,errors,records,api}
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

test('X-1至X-5以及同名井不同项目均清空旧结果和ID，保存只发送当前作用域', async () => {
  const w=workspace()
  try {
    await flush()
    for (const wellName of ['X-1','X-2','X-3','X-4','X-5']) {
      w.props.recordId=null
      w.props.wellName=wellName
      assert.equal(w.state.calculatedRequest.value,null)
      assert.equal(w.state.canSave.value,false)
      assert.equal(w.state.storedId.value,null)
      w.state.handleCalculate()
      await w.state.saveRecord()
      assert.equal(w.requests.at(-1).wellName,wellName)
      assert.equal(w.requests.at(-1).id,null)
    }
    w.props.recordId=null
    w.props.projectId=6
    w.props.gasReservoirId=1
    assert.equal(w.state.canSave.value,false)
    w.state.handleCalculate()
    await w.state.saveRecord()
    assert.equal(w.requests.at(-1).projectId,6)
    assert.equal(w.requests.at(-1).gasReservoirId,1)
  } finally {w.scope.stop()}
})

test('切井、切项目时旧保存或读取的延迟响应不能覆盖当前井', async () => {
  const w=workspace()
  try {
    await flush()
    let resolveSave
    w.api.save=payload=>{w.requests.push(payload);return new Promise(resolve=>{resolveSave=resolve})}
    w.state.handleCalculate()
    const pending=w.state.saveRecord()
    await w.state.saveRecord()
    assert.equal(w.requests.length,1)
    w.props.projectId=6
    resolveSave({id:88,name:'旧记录'})
    await pending
    assert.equal(w.state.storedId.value,null)
    assert.equal(w.props.recordId,null)
    assert.equal(w.state.calculatedRequest.value,null)
    let resolveDetail
    w.api.detail=()=>new Promise(resolve=>{resolveDetail=resolve})
    w.props.recordId=88
    assert.equal(w.state.loadingRecord.value,true)
    w.props.recordId=null
    w.props.wellName='X-2'
    resolveDetail({id:88,method:'指数式',version:'coefficient-v1',parameters:{pressure:999}})
    await flush()
    assert.notEqual(w.props.maximumFormationPressure,999)
    assert.equal(w.state.storedId.value,null)
  } finally {w.scope.stop()}
})

test('保存期间编辑参数不会发布过期保存事件；上传原始精度结果而不是显示舍入值', async () => {
  const w=workspace()
  try {
    await flush()
    let resolveSave
    w.api.save=payload=>{w.requests.push(payload);return new Promise(resolve=>{resolveSave=resolve})}
    w.state.handleCalculate()
    const expected=w.state.calculatedRequest.value.outputRate
    const pending=w.state.saveRecord()
    assert.equal(w.requests[0].result,expected)
    w.props.correctedCoefficientC=4
    resolveSave({id:88,name:'旧参数记录'})
    await pending
    assert.equal(w.state.storedId.value,null)
    assert.equal(w.state.calculatedRequest.value,null)
    w.state.handleCalculate()
    assert.equal(w.state.canSave.value,true)
  } finally {w.scope.stop()}
})

test('指数式拟合点必填、方向和范围校验生效，换方法会清空旧图并更新参考值', async () => {
  const w=workspace()
  try {
    await flush()
    w.props.fittedFlowRate='';w.props.fittedFormationPressure=''
    w.state.handleCalculate()
    assert.equal(w.state.canSave.value,false)
    assert.match(w.errors.at(-1),/同时填写/)
    w.props.fittedFlowRate=9;w.props.fittedFormationPressure=24
    w.state.handleCalculate()
    const original=w.state.calculatedRequest.value.fitReference.original
    w.state.calculationMethod.value='压力平方法'
    assert.equal(w.state.calculatedRequest.value,null)
    w.state.handleCalculate()
    assert.notEqual(w.state.calculatedRequest.value.fitReference.original,original)
    assert.equal(w.state.calculatedRequest.value.fitPoint.flowRate,9)
    w.props.operationType='injection';w.props.fittedFormationPressure=1
    w.state.handleCalculate()
    assert.equal(w.state.canSave.value,false)
    w.props.fittedFormationPressure=24;w.props.fittedFlowRate=1e100
    w.state.handleCalculate()
    assert.equal(w.state.canSave.value,false)
  } finally {w.scope.stop()}
})

test('指数式 PVT 空壳、倒序拟压力、覆盖不足均拦截，删除源PVT后仍可恢复有效历史快照', async () => {
  const w=workspace()
  try {
    await flush()
    w.state.calculationMethod.value='拟压力'
    for (const rows of [[],[[0,0,0,0],[60,0,0,-1]],[[0,0,0,0],[60,0,0,0]],[[0,0,0,0],[10,0,0,100]]]) {
      w.props.pvtRecord={pvtId:8,gasResultRows:rows}
      assert.ok(w.state.calculationBlocker.value)
      w.state.handleCalculate()
      assert.equal(w.state.canSave.value,false)
    }
    w.props.pvtRecord={pvtId:8,gasResultRows:[[0,0,0,0],[60,0,0,3600]]}
    w.state.handleCalculate()
    await w.state.saveRecord()
    const saved=w.records.get(12)
    saved.pvtId=null
    saved.pvtSnapshot.pvtId=null
    w.props.recordId=null;await flush()
    w.props.recordId=12;await flush()
    assert.equal(w.props.pvtRecord.pvtId,null)
    assert.equal(w.state.canSave.value,true)
    assert.equal(w.state.calculationBlocker.value,'')
  } finally {w.scope.stop()}
})

test('三种方法的注气IPR显示九条非退化曲线和一个零流量点', async () => {
  const w=workspace()
  try {
    await flush()
    let option
    w.state.setChart({setOption:value=>{option=value}})
    w.props.operationType='injection'
    w.props.pvtRecord={pvtId:8,gasResultRows:[[0,0,0,0],[60,0,0,3600]]}
    w.state.chartType.value='ipr-curve'
    for (const method of ['压力法','压力平方法','拟压力']) {
      w.state.calculationMethod.value=method
      w.state.handleCalculate()
      assert.equal(option.series.length,10)
      const zero=option.series.at(-1)
      assert.equal(zero.type,'scatter')
      assert.deepEqual(zero.data,[[0,56]])
      assert.equal(zero.clip,false)
      assert.ok(option.series.slice(0,-1).every(s=>s.type==='line'))
    }
    const {init}=await import('echarts')
    const chart=init(null,null,{renderer:'svg',ssr:true,width:1200,height:650})
    try {
      chart.setOption({...option,animation:false})
      const svg=chart.renderToSVGString()
      assert.match(svg,/零流量（地层压力等于上限）/)
      assert.doesNotMatch(svg,/NaN|Infinity/)
    } finally {chart.dispose()}
  } finally {w.state.setChart(null);w.scope.stop()}
})

test('边界输入不能留下旧图或可保存结果；科学计数和极小气量保留精度', async () => {
  const w=workspace()
  try {
    await flush()
    for (const [field,value] of [
      ['formationTemperature',' '],['formationTemperature',-273.15],['formationTemperature',Infinity],
      ['productivityCoefficientC',0],['productivityCoefficientC',-1],['productivityCoefficientC','0x18'],
      ['productivityCoefficientC','abc'],['productivityCoefficientC',1e308],
      ['productivityExponentN',24],['correctedExponentN',50],['correctedExponentN',0.49],
      ['maximumFormationPressure',1.01325],['maximumFormationPressure',Infinity],
      ['fittedFormationPressure',0],['fittedFormationPressure',56],['fittedFlowRate',-1]
    ]) {
      const original=w.props[field]
      w.state.handleCalculate()
      assert.equal(w.state.canSave.value,true)
      w.props[field]=value
      w.state.handleCalculate()
      assert.equal(w.state.calculatedRequest.value,null,`${field}=${value}`)
      assert.equal(w.state.canSave.value,false)
      assert.equal(w.props.openFlowRate,'')
      w.props[field]=original
    }
    w.props.fittedFormationPressure=56
    w.props.fittedFlowRate=0
    w.props.productivityCoefficientC='2e-8'
    w.props.correctedCoefficientC='3e-8'
    w.state.handleCalculate()
    assert.equal(w.state.canSave.value,true)
    assert.match(w.props.openFlowRate,/e-/)
    assert.ok(w.state.calculatedRequest.value.outputRate>0)
    w.props.fittedFormationPressure=24
    w.props.fittedFlowRate=1e-3
    w.state.handleCalculate()
    assert.equal(w.state.canSave.value,false)
    assert.match(w.errors.at(-1),/超过拟合曲线范围/)
  } finally {w.scope.stop()}
})

test('两种注采方向三种压力方法均保持端点、拟合点和保存快照一致', async () => {
  const w=workspace()
  try {
    await flush()
    w.props.pvtRecord={pvtId:8,gasResultRows:[[0,0,0,0],[60,0,0,3600]]}
    for (const operation of ['production','injection']) {
      for (const method of ['压力法','压力平方法','拟压力']) {
        w.props.operationType=operation
        w.state.calculationMethod.value=method
        w.state.handleCalculate()
        assert.equal(w.state.canSave.value,true)
        const result=w.state.calculatedRequest.value
        assert.equal(result.iprFamily.length,10)
        assert.equal(result.fittedCurve.points[0].flowRate,0)
        assert.equal(result.fittedCurve.points[0].flowingPressure,operation==='production'?56:5.6)
        assert.equal(result.fittedCurve.points.at(-1).flowingPressure,operation==='production'?0.101325:56)
        assert.ok(result.iprFamily.every(item=>item.curve.points.every(p=>Number.isFinite(p.flowRate)&&Number.isFinite(p.flowingPressure))))
        const expected=3*Math.pow(method==='压力法'?56-.101325:method==='压力平方法'?56**2-.101325**2:60*(56-.101325),.6)
        assert.ok(Math.abs(result.outputRate-expected)<1e-10)
        await w.state.saveRecord()
        assert.equal(w.requests.at(-1).result,result.outputRate)
      }
    }
  } finally {w.scope.stop()}
})
