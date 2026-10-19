import { expect, test } from '@playwright/test'
import { readFileSync } from 'node:fs'
const base=process.env.GRDP_CELL_API?.replace(/\/$/,'')
let source
async function get(request,path){const r=await request.get(base+'/software-integration'+path);expect(r.status()).toBe(200);const b=await r.json();expect(b.code).toBe(200);return b.data}
test.beforeAll(async({request})=>{
  if(!base)return
  const [baseline,rate,temperature,project]=await Promise.all([get(request,'/runs/1824'),get(request,'/runs/1825'),get(request,'/runs/1827'),get(request,'/projects/324')])
  source={baseline,rate,temperature,inspection:project.models.flatMap(m=>m.versions).find(v=>v.id===baseline.modelVersionId).inspection}
})
async function open(page,request,{corrupt=false,missingGeometry=false}={}){
  const state={errors:[],writes:[],ranges:[],slow:false,delayed:false}
  page.on('pageerror',e=>state.errors.push(e.message))
  await page.route('**/fixtures/visual-business-source.json',r=>{const data=structuredClone(source);if(missingGeometry)data.baseline.result.fieldIndex.files.find(f=>f.name.endsWith('.EGRID')).fields=data.baseline.result.fieldIndex.files.find(f=>f.name.endsWith('.EGRID')).fields.filter(f=>f.keyword!=='COORD');return r.fulfill({json:data})})
  const clock=source.rate.result.fieldIndex.files.find(f=>f.name.endsWith('.UNRST')).fields.find(f=>f.keyword==='DOUBHEAD'&&f.timeStep===1)
  await page.route('**/api/software-integration/**',async route=>{
    const u=new URL(route.request().url())
    if(route.request().method()!=='GET'||!/^\/api\/software-integration\/runs\/(1824|1825|1827)\/artifacts\/[0-9]+\/range$/.test(u.pathname)){state.writes.push(u.pathname);await route.abort();return}
    const record={path:u.pathname,offset:Number(u.searchParams.get('offset')),length:Number(u.searchParams.get('length'))};state.ranges.push(record)
    if(state.slow&&!state.delayed&&u.pathname.includes('/1825/')&&record.length===4){state.delayed=true;await new Promise(resolve=>setTimeout(resolve,700))}
    const response=await request.get(base+u.pathname.slice(4)+u.search);expect(response.status()).toBe(206)
    if(corrupt&&u.pathname.includes('/1825/')&&record.offset===clock.segments[0].offset){const bytes=Buffer.from(await response.body());bytes.writeDoubleBE(bytes.readDoubleBE(0)+1,0);await route.fulfill({status:206,contentType:'application/octet-stream',body:bytes});return}
    await route.fulfill({response})
  })
  await page.setViewportSize({width:1600,height:1080});await page.goto('/tests/e2e/fixtures/eclipse-visual-business.html')
  const panel=page.getByRole('region',{name:'E300热采空间差值分析',exact:true}),cell=page.getByRole('region',{name:'ECLIPSE单格实际历史曲线',exact:true})
  await expect(panel).toHaveAttribute('data-ready','true',{timeout:60000})
  return {state,panel,cell}
}
// Independent complete-field decoding is the oracle; production reads only one value per field.
async function native(request,run,keyword,report){
  const file=run.result.fieldIndex.files.find(f=>f.name.endsWith(keyword==='ACTNUM'?'.EGRID':'.UNRST')),field=file.fields.find(f=>f.keyword===keyword&&f.timeStep===report),artifact=run.artifacts.find(a=>a.name==='eclipse-output-'+file.name),out=[]
  for(const segment of field.segments){const r=await request.get(`${base}/software-integration/runs/${run.id}/artifacts/${artifact.id}/range?offset=${segment.offset}&length=${segment.length}`);expect(r.status()).toBe(206);const bytes=await r.body();const method=field.dataType==='INTE'?'readInt32':field.dataType==='REAL'?'readFloat':'readDouble';for(let i=0;i<bytes.length;i+=field.elementSize)out.push(bytes[method+(file.byteOrder==='BIG'?'BE':'LE')](i))}
  expect(out).toHaveLength(field.count);return out
}
async function oracle(request,index,keyword,run=source.rate){
  const mask=await native(request,source.baseline,'ACTNUM',undefined);expect(mask[index]).toBeGreaterThan(0);const active=mask.slice(0,index).filter(v=>v>0).length
  const fields=run.result.fieldIndex.files.find(f=>f.name.endsWith('.UNRST')).fields.filter(f=>f.keyword===keyword).sort((a,b)=>a.timeStep-b.timeStep),rows=[]
  for(const field of fields){const [before,after,time,seq]=await Promise.all([native(request,source.baseline,keyword,field.timeStep),native(request,run,keyword,field.timeStep),native(request,run,'DOUBHEAD',field.timeStep),native(request,run,'SEQNUM',field.timeStep)]);expect(seq).toEqual([field.timeStep ?? 0]);const b=before[before.length===mask.length?index:active],v=after[after.length===mask.length?index:active];rows.push({report:seq[0],timeDays:time[0],before:b,value:v,difference:v-b})}
  rows.sort((a,b)=>a.timeDays-b.timeDays)
  return rows
}
async function options(cell){await expect.poll(()=>cell.locator('canvas').count()).toBe(1);return cell.locator('.cell-history-chart').evaluate(el=>globalThis.readVisualChartOptions(el))}
test('真实温度单格逐报告历史、调整前零差、独立原生值和单值范围读取',async({page,request},info)=>{
  test.skip(!base,'Explicit local API opt-in required')
  const {state,panel,cell}=await open(page,request),start=state.ranges.length
  await panel.locator('.spatial-maps svg rect').first().click();await expect(cell).toHaveAttribute('data-ready','true',{timeout:60000})
  const index=Number(await cell.getAttribute('data-cell-index')),rows=await oracle(request,index,'TEMP'),opt=await options(cell)
  expect(opt.series[0].data).toEqual(rows.map(r=>[r.timeDays,r.before]));expect(opt.series[1].data).toEqual(rows.map(r=>[r.timeDays,r.value]));expect(opt.series[2].data).toEqual(rows.map(r=>[r.timeDays,r.difference]))
  expect(rows.filter(r=>r.timeDays<=1825).every(r=>r.difference===0)).toBe(true);expect(rows.at(-1).difference).not.toBe(0)
  const valueFields=[source.baseline,source.rate].flatMap(run=>{const file=run.result.fieldIndex.files.find(f=>f.name.endsWith('.UNRST')),artifact=run.artifacts.find(a=>a.name==='eclipse-output-'+file.name);return file.fields.filter(f=>f.keyword==='TEMP').map(f=>({path:`/api/software-integration/runs/${run.id}/artifacts/${artifact.id}/range`,segments:f.segments}))})
  const reads=state.ranges.slice(start).filter(r=>valueFields.some(f=>f.path===r.path&&f.segments.some(s=>r.offset>=s.offset&&r.offset<s.offset+s.length)))
  expect(reads).toHaveLength(rows.length*2);expect(reads.every(r=>r.length===4)).toBe(true);expect(rows[0].report).toBe(0);expect(rows[0].timeDays).toBe(0)
  const csvWait=page.waitForEvent('download');await cell.getByRole('button',{name:'导出单格历史CSV',exact:true}).click();const csv=await csvWait,path=info.outputPath(csv.suggestedFilename());await csv.saveAs(path)
  const csvRows=readFileSync(path,'utf8').replace(/^\uFEFF/,'').split('\r\n').slice(1).map(line=>line.split(',').map(v=>v.slice(1,-1)))
  expect(csvRows).toHaveLength(rows.length);csvRows.forEach((r,i)=>{expect(r.slice(0,2).map(Number)).toEqual([1824,1825]);expect(Number(r[8])).toBe(index);expect(r.slice(9).map(Number)).toEqual([rows[i].report,rows[i].timeDays,rows[i].before,rows[i].value,rows[i].difference])})
  const pngWait=page.waitForEvent('download');await cell.getByRole('button',{name:'导出单格历史PNG',exact:true}).click();const png=await pngWait;await png.saveAs(info.outputPath(png.suggestedFilename()))
  await cell.screenshot({path:info.outputPath('temperature-cell-history.png')});expect(state.errors).toEqual([]);expect(state.writes).toEqual([])
})
test('同一单元压力及三项饱和度历史均与真实场逐格相符',async({page,request},info)=>{
  test.skip(!base,'Explicit local API opt-in required')
  const {state,panel,cell}=await open(page,request)
  const proofs=[]
  for(const keyword of ['PRESSURE','SWAT','SGAS','SOIL']){
    await panel.locator('.spatial-controls .el-select').click();await page.getByRole('option',{name:`${keyword} · 报告 10`,exact:true}).click();await expect(panel).toHaveAttribute('data-ready','true')
    await panel.locator('.spatial-maps svg rect').first().click();await expect(cell).toHaveAttribute('data-ready','true',{timeout:60000});await expect(cell).toHaveAttribute('data-keyword',keyword)
    const index=Number(await cell.getAttribute('data-cell-index')),rows=await oracle(request,index,keyword),opt=await options(cell)
    expect(opt.series[2].data).toEqual(rows.map(r=>[r.timeDays,r.difference]));proofs.push({keyword,index,rows})
  }
  await info.attach('independent-four-fields',{body:JSON.stringify(proofs),contentType:'application/json'});expect(state.errors).toEqual([])
})
test('快速点选与切换降温方案隔离旧单格请求',async({page,request})=>{
  test.skip(!base,'Explicit local API opt-in required')
  const {state,panel,cell}=await open(page,request);state.slow=true
  const cells=panel.locator('.spatial-maps article').first().locator('svg rect');await cells.nth(0).click();await expect.poll(()=>state.delayed).toBe(true);await cells.nth(1).click()
  await expect(cell).toHaveAttribute('data-ready','true',{timeout:60000});const index=Number(await cell.getAttribute('data-cell-index')),rows=await oracle(request,index,'TEMP');expect((await options(cell)).series[1].data).toEqual(rows.map(r=>[r.timeDays,r.value]))
  await page.getByRole('button',{name:'降温方案1827',exact:true}).click();await expect(panel).toHaveAttribute('data-ready','true');await expect(cell).toHaveCount(0)
  await panel.locator('.spatial-maps svg rect').first().click();await expect(cell).toHaveAttribute('data-ready','true',{timeout:60000});await expect(cell).toHaveAttribute('data-run-id','1827')
  const changed=await oracle(request,Number(await cell.getAttribute('data-cell-index')),'TEMP',source.temperature);expect((await options(cell)).series[1].data).toEqual(changed.map(r=>[r.timeDays,r.value]));expect(state.errors).toEqual([])
})
test('报告原始时间不一致时不展示部分单格历史',async({page,request})=>{
  test.skip(!base,'Explicit local API opt-in required')
  const {panel,cell,state}=await open(page,request,{corrupt:true});await panel.locator('.spatial-maps svg rect').first().click();await expect(cell).toContainText('实际时间、SEQNUM');await expect(cell).toHaveAttribute('data-ready','false');await expect(cell.locator('canvas')).toHaveCount(0);expect(state.errors).toEqual([])
})
test('真实三维差值使用EGRID角点、分层、原值切换及定位联动单格历史',async({page,request},info)=>{
  test.skip(!base,'Explicit local API opt-in required')
  const {state,panel,cell}=await open(page,request),viewer=page.getByRole('region',{name:'ECLIPSE真实网格三维方案对比',exact:true})
  await panel.getByRole('button',{name:'查看三维空间差值',exact:true}).click();await expect(viewer).toHaveAttribute('data-ready','true',{timeout:60000});await expect(viewer).toHaveAttribute('data-visible-cells','100')
  await viewer.getByText('原基线',{exact:true}).click();await expect(viewer).toHaveAttribute('data-mode','baselineValue');await viewer.getByText('当前方案',{exact:true}).click();await expect(viewer).toHaveAttribute('data-mode','value');await viewer.getByText('方案差值',{exact:true}).click()
  const mask=await native(request,source.baseline,'ACTNUM',undefined),grid=source.baseline.result.grid,k=2,expected=mask.filter((v,i)=>v>0&&Math.floor(i/(grid.nx*grid.ny))+1===k).length
  await viewer.locator('.el-select').click();await page.getByRole('option',{name:'K = 2',exact:true}).click();await expect(viewer).toHaveAttribute('data-visible-cells',String(expected))
  const index=mask.findIndex((v,i)=>v>0&&Math.floor(i/(grid.nx*grid.ny))+1===k),ijk=[index%grid.nx+1,Math.floor(index/grid.nx)%grid.ny+1,k]
  for(const [n,v]of ijk.entries())await viewer.getByRole('spinbutton',{name:['三维差值定位I','三维差值定位J','三维差值定位K'][n],exact:true}).fill(String(v))
  await viewer.getByRole('button',{name:'定位并查看单格历史',exact:true}).click();await expect(cell).toHaveAttribute('data-ready','true',{timeout:60000});await expect(cell).toHaveAttribute('data-cell-index',String(index))
  await viewer.getByRole('spinbutton',{name:'三维差值纵向倍数',exact:true}).fill('3');await viewer.getByRole('button',{name:'重置三维视角',exact:true}).click()
  const wait=page.waitForEvent('download');await viewer.getByRole('button',{name:'导出三维差值PNG',exact:true}).click();const download=await wait,path=info.outputPath(download.suggestedFilename());await download.saveAs(path);expect(readFileSync(path).subarray(0,8).toString('hex')).toBe('89504e470d0a1a0a');expect(readFileSync(path).length).toBeGreaterThan(20000)
  await viewer.screenshot({path:info.outputPath('thermal-real-grid-3d-difference.png')})
  await panel.getByRole('button',{name:'收起三维空间差值',exact:true}).click();await expect(viewer).toHaveCount(0);await expect(cell).toHaveAttribute('data-ready','true')
  expect(state.errors).toEqual([]);expect(state.writes).toEqual([])
})
test('三维缺少真实角点时保留层图而拒绝虚构几何',async({page,request})=>{
  test.skip(!base,'Explicit local API opt-in required')
  const {state,panel}=await open(page,request,{missingGeometry:true}),viewer=page.getByRole('region',{name:'ECLIPSE真实网格三维方案对比',exact:true})
  await panel.getByRole('button',{name:'查看三维空间差值',exact:true}).click();await expect(viewer).toContainText('缺少唯一的全局EGRID真实角点字段');await expect(viewer).toHaveAttribute('data-ready','false');await expect(panel.locator('.spatial-maps article')).toHaveCount(3);expect(state.errors).toEqual([])
})
