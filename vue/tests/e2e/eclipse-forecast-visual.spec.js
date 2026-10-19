import { expect, test } from '@playwright/test'
import { readFileSync } from 'node:fs'
const base=process.env.GRDP_FORECAST_API?.replace(/\/$/,'')
let source={}
test.beforeAll(async({request})=>{
  if(!base)return
  const get=async path=>{const r=await request.get(base+'/software-integration'+path);expect(r.status()).toBe(200);const b=await r.json();expect(b.code).toBe(200);return b.data}
  for(const [key,id,other] of [['e100',399,400],['e300',1754,1756],['portable',1757,null]]){
    const run=await get(`/runs/${id}`),project=await get(`/projects/${run.projectId}`),inspection=project.models.flatMap(m=>m.versions).find(v=>v.id===run.modelVersionId).inspection
    source[key]={run,inspection,other:other?await get(`/runs/${other}`):null,history:run.parameters?.historyRunId?await get(`/runs/${run.parameters.historyRunId}`):null}
  }
})
async function open(page,request,{slow=false,corrupt=false}={}){
  const errors=[],writes=[]
  page.on('pageerror',e=>errors.push(e.message))
  await page.route('**/fixtures/forecast-visual-source.json',r=>r.fulfill({json:source}))
  await page.route('**/api/software-integration/**',async route=>{
    const path=new URL(route.request().url()).pathname
    if(route.request().method()!=='GET'||!/^\/api\/software-integration\/runs\/(398|399|400|1753|1754|1756|1757)(\/artifacts\/[0-9]+\/download)?$/.test(path)){writes.push(path);await route.abort();return}
    if(slow&&path==='/api/software-integration/runs/1756')await new Promise(resolve=>setTimeout(resolve,900))
    const r=await request.get(base+path.slice(4));expect(r.status()).toBe(200)
    if(corrupt&&path.includes('/398/artifacts/')){const value=await r.json();value.physicsSha256='0'.repeat(64);await route.fulfill({json:value});return}
    await route.fulfill({response:r})
  })
  await page.setViewportSize({width:1600,height:1080});await page.goto('/tests/e2e/fixtures/eclipse-forecast-visual.html')
  return {panel:page.getByRole('region',{name:'ECLIPSE保存点与预测分支看板',exact:true}),errors,writes}
}
async function options(panel){await expect.poll(()=>panel.locator('canvas').count()).toBe(1);return panel.locator('.forecast-chart').evaluate(el=>globalThis.forecastChart(el))}
async function choose(page,aria,label){await page.locator('.el-select').filter({has:page.getByRole('combobox',{name:aria,exact:true})}).click();await page.getByRole('option',{name:label,exact:true}).click()}
const raw=(run,index,keyword)=>run.result.summary.series.find(v=>index==null?v.keyword===keyword&&v.objectName==null:v.vectorIndex===index).points.map(p=>[p.timeDays,p.value])
test('真实E100保存报告、两个预测分支和实际新增量导出',async({page,request},info)=>{
  test.skip(!base,'Explicit local API opt-in required')
  const {panel,errors,writes}=await open(page,request);await expect(panel).toHaveAttribute('data-ready','true')
  await choose(page,'ECLIPSE 另一个未来方案',`#400 · ${source.e100.other.parameters.forecastDataFile}`);await expect(panel).toHaveAttribute('data-other-id','400')
  const opt=await options(panel);expect(opt.series).toHaveLength(3)
  expect(opt.series[1].data).toEqual(raw(source.e100.run,null,'FOPT'));expect(opt.series[2].data).toEqual(raw(source.e100.other,null,'FOPT'))
  expect(opt.series[1].data.map(p=>p[0])).toEqual([2556,2921]);await expect(panel).toContainText('第 2190 天')
  await panel.getByText('保存点后新增',{exact:true}).click();const delta=await options(panel),saved=raw(source.e100.history,null,'FOPT').find(p=>p[0]===2190)[1]
  expect(delta.series).toHaveLength(2);expect(delta.series[0].data).toEqual(raw(source.e100.run,null,'FOPT').map(([t,v])=>[t,v-saved]))
  const download=page.waitForEvent('download');await panel.getByRole('button',{name:'导出接续指标CSV',exact:true}).click();const csv=await download;await csv.saveAs(info.outputPath(csv.suggestedFilename()))
  const pngWait=page.waitForEvent('download');await panel.getByRole('button',{name:'导出预测分支PNG',exact:true}).click();const png=await pngWait;await png.saveAs(info.outputPath(png.suggestedFilename()));expect(readFileSync(info.outputPath(png.suggestedFilename())).subarray(0,8).toString('hex')).toBe('89504e470d0a1a0a')
  await panel.screenshot({path:info.outputPath('e100-forecast-branches.png')});expect(errors).toEqual([]);expect(writes).toEqual([])
})
test('真实E300同一SAVE双方案、重复原始列与96350累计差值',async({page,request},info)=>{
  test.skip(!base,'Explicit local API opt-in required')
  const {panel,errors,writes}=await open(page,request);await page.getByRole('button',{name:'E300',exact:true}).click();await expect(panel).toHaveAttribute('data-ready','true')
  await choose(page,'E300对比预测方案','#1756 · 产气目标 4500 Mscf/day');await expect(panel).toHaveAttribute('data-other-id','1756')
  const opt=await options(panel),index=source.e300.run.result.summary.series.find(v=>v.keyword==='FOPT').vectorIndex
  expect(opt.series[1].data).toEqual(raw(source.e300.run,index));expect(opt.series[2].data).toEqual(raw(source.e300.other,index));expect(opt.series[1].data[0][0]).toBe(3286)
  await expect(panel.getByLabel('保存点与预测增量指标')).toContainText('96,350')
  await choose(page,'预测分支指标','FOPT · 全场 · 原始列314 · STB');const duplicate=await options(panel);expect(duplicate.title[0].text).toContain('原始列314');expect(duplicate.series[1].data).toEqual(raw(source.e300.run,313))
  const download=page.waitForEvent('download');await panel.getByRole('button',{name:'导出接续指标CSV',exact:true}).click();const csv=await download;await csv.saveAs(info.outputPath(csv.suggestedFilename()))
  await panel.screenshot({path:info.outputPath('e300-forecast-branches.png')})
  await panel.getByRole('button',{name:/预测 #1756 ·/}).click();expect(await page.evaluate(()=>globalThis.forecastSelectedRuns)).toEqual([1756])
  expect(errors).toEqual([]);expect(writes).toEqual([])
})
test('真实E300原历史展示保存点并保持原沿计划请求',async({page,request},info)=>{
  test.skip(!base,'Explicit local API opt-in required')
  const {panel,errors}=await open(page,request);await page.getByRole('button',{name:'历史保存点',exact:true}).click();await expect(panel).toHaveAttribute('data-ready','true')
  expect((await options(panel)).series).toHaveLength(1);await expect(panel).toContainText('创建自己的预测分支')
  await page.getByRole('button',{name:'沿原计划续算',exact:true}).click();const emitted=await page.evaluate(()=>globalThis.forecastEmittedPlans);expect(emitted).toHaveLength(1);expect(emitted[0].historyRunId).toBe(1753);expect(emitted[0].targetGasRate).toBeNull();expect(Object.keys(emitted[0])).toHaveLength(7)
  await panel.screenshot({path:info.outputPath('e300-history-savepoint.png')});expect(errors).toEqual([])
})
test('真实导入预测缺少历史累计时只显示原值，窄屏仍可展示',async({page,request},info)=>{
  test.skip(!base,'Explicit local API opt-in required')
  const {panel,errors}=await open(page,request);await page.getByRole('button',{name:'导入续算',exact:true}).click();await expect(panel).toHaveAttribute('data-ready','true')
  await expect(panel.getByLabel('保存点与预测增量指标')).toContainText('未提供');await expect(panel.getByLabel('预测曲线数值方式')).toHaveCount(0)
  const opt=await options(panel);expect(opt.series).toHaveLength(1);expect(opt.series[0].data[0][0]).toBe(3286)
  await page.setViewportSize({width:430,height:950});await expect.poll(()=>panel.evaluate(el=>el.scrollWidth<=el.clientWidth+2)).toBe(true)
  await panel.screenshot({path:info.outputPath('portable-forecast-narrow.png')});expect(errors).toEqual([])
})
test('迟到的对比预测不会恢复到另一模型的保存点看板',async({page,request})=>{
  test.skip(!base,'Explicit local API opt-in required')
  const {panel,errors}=await open(page,request,{slow:true});await page.getByRole('button',{name:'E300',exact:true}).click();await expect(panel).toHaveAttribute('data-ready','true')
  await choose(page,'E300对比预测方案','#1756 · 产气目标 4500 Mscf/day');await page.getByRole('button',{name:'E100',exact:true}).click();await expect(panel).toHaveAttribute('data-run-id','399')
  await page.waitForTimeout(1100);await expect(panel).toHaveAttribute('data-other-id','');expect(errors).toEqual([])
})
test('实际保存目录哈希不符时拒绝展示预测增量',async({page,request})=>{
  test.skip(!base,'Explicit local API opt-in required')
  const {panel}=await open(page,request,{corrupt:true});await expect(page.getByRole('region',{name:'ECLIPSE 历史接续与未来方案对比',exact:true})).toContainText('物理来源核对失败');await expect(panel).toHaveCount(0)
})
