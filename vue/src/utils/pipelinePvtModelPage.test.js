import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import * as Vue from 'vue'
import * as renderer from 'vue/server-renderer'
import {parse} from '@vue/compiler-sfc'
import {compile} from '@vue/compiler-ssr'
import {pvtModelColumns,pvtModelRows,pipelinePvtMethods} from './pipelinePvtModel.js'
const {descriptor}=parse(fs.readFileSync(new URL('../views/PipelineCapacity/PipelinePvtModelPage.vue',import.meta.url),'utf8'))
function find(node,attribute,value){
 if(node.type===1&&node.props.some(p=>p.name===attribute&&p.value?.content===value))return node
 for(const child of node.children||[]){const result=find(child,attribute,value);if(result)return result}
}
async function render(attribute,value,state){
 const node=find(descriptor.template.ast,attribute,value);assert.ok(node)
 const ssrRender=new Function('require',compile(node.loc.source,{mode:'function'}).code)(name=>name==='vue'?Vue:renderer)
 return renderer.renderToString(Vue.createSSRApp({ssrRender,setup:()=>state}))
}
const catalog=['CH4','C2H6','C3H8','IC4','NC4','IC5','NC5','NC6','NC7','NC8','NC9','NC10','N2','CO2','H2S'].map(code=>({code,name:code,criticalTemperatureK:190,criticalPressurePa:4e6,acentricFactor:0.011,molarMassKgMol:0.016}))
test('PVT table contains fifteen editable mole percentages while species identity and constants remain read-only',async()=>{
 const rows=pvtModelRows(catalog,[{code:'CH4',moleFraction:1}])
 const html=await render('aria-label','PVT 完整气体组成',{rows,pvtModelColumns,f:value=>value})
 const inputs=html.match(/<input[^>]*>/g)||[]
 assert.equal(inputs.length,15)
 for(const input of inputs){assert.match(input,/type="number"/);assert.match(input,/摩尔含量/);assert.match(input,/min="0"/);assert.match(input,/max="100"/)}
 assert.match(html,/<td>190<\/td><td>4<\/td><td>0.011<\/td><td>0.016<\/td>/)
})
test('PVT method radio buttons and save action live in the left parameter form',async()=>{
 const html=await render('class','parameter-form',{name:'A井气体模型',method:'SRK',pipelinePvtMethods,fileName:'',save(){},importVisible:false})
 assert.equal((html.match(/type="radio"/g)||[]).length,3)
 assert.match(html.match(/<input[^>]*value="SRK"[^>]*>/)[0],/\bchecked\b/)
 assert.match(html,/<button[^>]*>保存<\/button>/)
 assert.match(html,/本地导入/)
 assert.doesNotMatch(html,/<select/)
})
