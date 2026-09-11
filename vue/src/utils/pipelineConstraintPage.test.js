import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import * as Vue from 'vue'
import * as renderer from 'vue/server-renderer'
import { parse, compileScript } from '@vue/compiler-sfc'
import { compile } from '@vue/compiler-ssr'
import { constraintChartSeries } from './pipelineConstraintResults.js'
import { formatOperatingTime } from './pipelineTime.js'
import { statusLabels } from './pipelineDefaults.js'

const read = name => parse(fs.readFileSync(new URL(`../views/PipelineCapacity/${name}.vue`, import.meta.url),'utf8')).descriptor
const page=read('PipelineConstraintPage')
const create=new Function('computed','ref','watch','defineProps','defineEmits','constraintChartSeries','formatOperatingTime',
  page.scriptSetup.content.replace(/^import .+$/gm,'')+'\nreturn {props,s,isEquipment,enabled,rows,catalog,selectedId,metric,dataPage,pageSize,selected,pagedRows,chartOptions,selectedChart,series,sourceModel,emptyText,equipmentColumns,hydrateColumns,statusLabel,chooseRow,formatOperatingTime,emit};')
const ssrRender=new Function('require',compile(page.template.content,{mode:'function'}).code)(name=>name==='vue'?Vue:renderer)
const flatten=nodes=>nodes.flatMap(node=>node?.type===Vue.Fragment?flatten(node.children||[]):node?.props?.label?[node]:[])
const Table={props:['data'],setup(props,{slots}){return()=>{
  const columns=flatten(slots.default?.()||[])
  return Vue.h('table',[Vue.h('thead',[Vue.h('tr',columns.map(column=>Vue.h('th',column.props.label)))]),
    Vue.h('tbody',props.data.map((row,index)=>Vue.h('tr',columns.map(column=>Vue.h('td',column.children?.default?column.children.default({row,$index:index}):row[column.props.prop]??'—')))))])
}}}
function fixture(kind='equipment') {
  const state=Vue.reactive({form:{constraints:{waterState:'unknown'},thermalMode:'heat'},batchBusy:false,batchStale:false,panel:'data',canCalculate:true,canBatchSave:true,
    batchResult:{hydrateModel:{relativeDensity:0.65}},batchGasModel:{pvtName:'计算所用气体组成'},batchSaved:false,
    gasPropertyConfig:{pvtName:'当前气体组成'},gasPropertyError:'',thermalSourceError:'',
    equipmentCatalog:[{id:'valve:0',name:'阀门',nodeName:'设备节点',sequence:1,type:'valve',lossK:8,maxPressureMpa:10},
      {id:'valve:1',name:'压缩机',nodeName:'设备节点',sequence:2,type:'compressor',pressureRatio:1.2,efficiency:0.75,maxPressureMpa:12,maxPowerKw:500}],
    equipmentRows:[{id:'valve:0',name:'阀门',rowKey:'first/valve:0',type:'valve',sequence:1,operatingAt:'2026-09-11T08:17',status:'pass',inletMpa:8,outletMpa:7.9},
      {id:'valve:1',name:'压缩机',rowKey:'second/valve:1',type:'compressor',sequence:2,operatingAt:'2026-09-11T08:49',status:'fail',inletMpa:7.9,outletMpa:9,powerKw:501,maxPowerKw:500}],
    savedGraph:{edges:[{id:'pipe',name:'管道 A'}]},hydrateRows:[{id:'pipe',edgeId:'pipe',name:'管道 A',rowKey:'first/pipe',operatingAt:'2026-09-11T08:17',status:'conditional',temperatureC:5,equilibriumC:9,marginC:-4,distanceM:800,reason:'含水条件未知'}],
    statusLabels,f:value=>value==null?'—':String(value),calculate(){},savePage(){}})
  const bindings=create(Vue.computed,Vue.ref,Vue.watch,()=>({state,kind}),()=>()=>{},constraintChartSeries,formatOperatingTime)
  return {state,bindings,kind}
}
const render=fixture=>renderer.renderToString(Vue.createSSRApp({ssrRender,setup:()=>({...Vue.proxyRefs(fixture.bindings),kind:fixture.kind}),components:{
  PipelineParameterPanel:{setup(_,{slots}){return()=>Vue.h('aside',{class:'parameter-panel'},slots.default?.())}},
  PipelineTimeChart:{props:['series','title','emptyText'],render(){return Vue.h('section',{class:'chart-stub'},this.series.length?this.title:this.emptyText)}},
  ElTable:Table,ElTableColumn:{render:()=>null},ElPagination:{render:()=>null}
}}))

test('all constraint and comparison templates compile with the common panel and time-chart components',()=>{
  for(const name of ['PipelineConstraintPage','PipelineConstraintsOverview','PipelineComparisonPage','PipelineParameterPanel','PipelineCapacityContent'])
    assert.doesNotThrow(()=>compileScript(read(name),{id:name,inlineTemplate:true}))
})

test('device page reads topology parameters and displays all cases without duplicate editable configuration or calculation',async()=>{
  const f=fixture(),html=await render(f)
  assert.match(html,/设备资料/);assert.match(html,/全部工况 · 全部设备/)
  assert.match(html,/2026\/09\/11 08:17/);assert.match(html,/2026\/09\/11 08:49/)
  assert.match(html,/入口温度/);assert.match(html,/功率裕度/);assert.match(html,/前往管流计算/)
  assert.doesNotMatch(html,/type="number"|class="calculate"|class="save"|设备参数/)
  f.state.panel='analysis';f.bindings.selectedId.value='valve:1';f.bindings.metric.value='power'
  const analysis=await render(f);assert.equal((analysis.match(/class="chart-stub"/g)||[]).length,1)
  assert.match(analysis,/功率\/时间/);assert.equal(f.bindings.series.value[0].data[0][1],501)
})

test('hydrate page uses the calculation PVT, editable water radios, shared batch actions, and explicit empirical assumptions',async()=>{
  const f=fixture('hydrate'),html=await render(f)
  assert.match(html,/计算所用气体组成/);assert.match(html,/Safamirzaei/);assert.match(html,/纯水/)
  assert.match(html,/经验预测/);assert.match(html,/原文表 2 数据范围/);assert.match(html,/范围外显示未评价/)
  assert.equal((html.match(/name="pipeline-hydrate-water"/g)||[]).length,2)
  assert.match(html,/class="calculate"[^>]*>计算/);assert.match(html,/class="save"[^>]*>保存/)
  assert.match(html,/需确认含水条件/);assert.doesNotMatch(html,/本期暂不启用/)
  f.state.panel='analysis';f.bindings.metric.value='margin'
  assert.equal(f.bindings.series.value[0].data[0][1],-4)
  assert.equal(f.bindings.series.value[1].data[0][1],0)
})

test('stale constraint pages hide numeric outputs and analysis regardless of leftover state values',async()=>{
  for(const kind of ['equipment','hydrate']) {
    const f=fixture(kind);f.state.batchStale=true
    assert.deepEqual(f.bindings.rows.value,[]);assert.deepEqual(f.bindings.series.value,[])
    f.state.panel='analysis';assert.match(await render(f),/重新计算全部工况/)
    f.state.panel='data';assert.doesNotMatch(await render(f),/2026\/09\/11 08:17/)
  }
})
