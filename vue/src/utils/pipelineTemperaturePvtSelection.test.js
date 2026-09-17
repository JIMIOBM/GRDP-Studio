import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import * as Vue from 'vue'
import * as serverRenderer from 'vue/server-renderer'
import { parse } from '@vue/compiler-sfc'
import { compile } from '@vue/compiler-ssr'
import { innerInputColumns, innerPropertyColumns } from './pipelineInnerImport.js'
import { overallInputColumns } from './pipelineThermalParameterImport.js'

const file = new URL('../views/PipelineCapacity/PipelineTemperatureEditor.vue', import.meta.url)
const { descriptor } = parse(fs.readFileSync(file, 'utf8'))
function find(node, predicate) {
  if(predicate(node))return node
  for(const child of node.children||[]) {const found=find(child,predicate);if(found)return found}
}
function compilePart(predicate) {
  const part=find(descriptor.template.ast,predicate)
  assert.ok(part,'The tested temperature-page control must exist')
  return new Function('require',compile(part.loc.source,{mode:'function'}).code)(name=>{
    if(name==='vue')return Vue
    if(name==='vue/server-renderer')return serverRenderer
    throw new Error(`Unexpected render dependency: ${name}`)
  })
}
const hasAttribute=(node,name,value)=>node.type===1&&node.props.some(prop=>prop.name===name&&prop.value?.content===value)
const selector=compilePart(node=>hasAttribute(node,'class','field pvt-field'))
const table=compilePart(node=>hasAttribute(node,'aria-label','内壁放热系数输入参数'))
const overallTable=compilePart(node=>hasAttribute(node,'class','data-table input-table thermal-parameter-table'))
const render=(ssrRender,state)=>serverRenderer.renderToString(Vue.createSSRApp({ssrRender,setup:()=>state}))

for(const kind of ['inner','overall']) {
  test(`${kind}: gas source is the well's automatic PVT model, never a legacy selectable record`,async()=>{
    for(const pvtName of ['当前井气体物性模型','尚未保存 PVT 模型']) {
      const html=await render(selector,{usesPvt:true,pvtName,propertyBusy:true})
      assert.doesNotMatch(html,/<select/)
      assert.match(html,/<input[^>]*readonly/)
      assert.ok(html.includes(pvtName))
    }
  })
}
test('wall and external coefficients do not display or require PVT selection',async()=>{
  assert.doesNotMatch(await render(selector,{usesPvt:false}),/<select/)
})
test('inner table edits conductivity, diameter, flow and PT while PVT density, viscosity and Cp stay read-only',async()=>{
  const config={innerDiameterMm:100,actualFlowM3s:0.2,propertyPressureMpa:8,propertyTemperatureC:-5,
    densityKgM3:999,viscosityMpaS:999,cpJkgK:999,gasConductivityWmK:0.034}
  const html=await render(table,{inputColumns:innerInputColumns,innerPropertyColumns,
    inputRows:[{edge:{id:'a',name:'第一管段'},config}],selected:'a',choose(){},editInput(){},
    effectiveInputs:()=>({densityKgM3:45,viscosityMpaS:0.012,cpJkgK:null,gasConductivityWmK:null}),
    propertyDetails:()=>({sources:{densityKgM3:'PVT 气体密度'}}),f:value=>value??'—'})
  assert.equal((html.match(/<input/g)||[]).length,5)
  assert.match(html,/<input[^>]*value="0.034"[^>]*aria-label="第一管段 气体导热系数/)
  assert.match(html,/value="-5"/)
  assert.doesNotMatch(html,/999/)
  assert.match(html,/title="PVT 气体密度">45<\/td>/)
  assert.match(html,/title="当前井 PVT 模型暂无可用数据">—<\/td>/)
  assert.doesNotMatch(html,/<input[^>]*aria-label="[^\"]*(?:气体密度|动力黏度|比热容)/)
})

test('overall conductivity stays editable in independent and coupled modes and an absent value stays blank',async()=>{
 for(const coupled of [false,true])for(const conductivity of [null,0.034]) {
  const config={propertyPressureMpa:8,propertyTemperatureC:25,ambientC:15,gasConductivityWmK:conductivity}
  const html=await render(overallTable,{isOuter:false,page:{label:'总传热系数'},parameterColumns:overallInputColumns,
   inputRows:[{edge:{id:'a',name:'第一管段',parameters:{diameterMm:100}},config}],selected:'a',choose(){},editParameter(){},
   parameterDisabled:(_,key)=>coupled&&['propertyPressureMpa','propertyTemperatureC'].includes(key)})
  const inputs=html.match(/<input\b[^>]*>/g)||[],lambda=inputs.find(input=>input.includes('气体导热系数'))
  assert.equal(inputs.length,4)
  assert.ok(lambda)
  assert.doesNotMatch(lambda,/disabled|readonly/)
  assert.ok(lambda.includes('W/(m·K)'))
  if(conductivity===null)assert.doesNotMatch(lambda,/value="[0-9]/)
  else assert.match(lambda,/value="0.034"/)
  assert.equal(inputs.filter(input=>input.includes('disabled')).length,coupled?2:0)
 }
})
