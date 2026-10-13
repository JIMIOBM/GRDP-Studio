import { historyBrowserFixture } from '../fixtures/eclipseHistory.js'
import { historyBatchBrowserFixture } from '../fixtures/eclipseHistoryBatch.js'
import { batchBrowserFixture } from '../fixtures/eclipseBatch.js'
import { relpermBrowserFixture } from '../fixtures/eclipseRelperm.js'
import { gasPvtBrowserFixture } from '../fixtures/eclipseGasPvt.js'
import { oilPvtBrowserFixture } from '../fixtures/eclipseOilPvt.js'
import { pvtwBrowserFixture } from '../fixtures/eclipsePvtw.js'
import { groupStructureFixture as structure } from '../fixtures/eclipseGroupStructure.js'
import { controlPlanFixture } from '../fixtures/eclipseControlPlan.js'
import { restartForecastFixture } from '../fixtures/eclipseRestartForecast.js'
import { compositionalBrowserFixture } from '../fixtures/eclipseCompositional.js'
import { thermalBrowserFixture } from '../fixtures/eclipseThermal.js'
import { gridRegionBrowserFixture } from '../fixtures/eclipseGridRegion.js'
import { completionGeometryBrowserFixture } from '../fixtures/eclipseCompletionGeometry.js'
import { newWellBrowserFixture } from '../fixtures/eclipseNewWell.js'
import { newCompletionBrowserFixture } from '../fixtures/eclipseNewCompletion.js'
import { expect, test } from '@playwright/test'

async function fillNewCompletion(page,parameters){
  const panel=page.getByRole('region',{name:'ECLIPSE 已有井新增完井',exact:true})
  for(const key of ['i','j','k','diameter','kh','skin'])await panel.getByRole('spinbutton',{name:`新完井 ${key}`,exact:true}).fill(String(parameters[key]))
  return panel
}
test('ECLIPSE 新完井 原井身份与实际ACTNUM绑定完整参数提交',async({page})=>{
  const f=newCompletionBrowserFixture()
  const state=await installMockBackend(page,{eclipseInspection:f.inspection,eclipseHistory:[summary(f.baseline)],runs:[f.baseline],rangePayloads:f.rangePayloads})
  await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型')
  const panel=await fillNewCompletion(page,f.parameters)
  await expect(panel).toContainText('原井口、压力基准、组及井控保留');await expect(panel).toContainText('mD.m')
  await panel.getByRole('button',{name:'新增完井连接并计算',exact:true}).click()
  await expect.poll(()=>state.createPayloads.length).toBe(1)
  expect(state.createPayloads[0].payload.parameters).toEqual(f.parameters)
  expect(state.rangeRequests.some(r=>r.runId===f.baseline.id&&r.params.endsWith('length=4'))).toBe(true);expect(state.unexpectedRequests).toEqual([])
})
for(const [label,change,message] of [['occupied',{i:1},'已有井连接重复'],['capacity',{},'连接容量不足']])test(`ECLIPSE 新完井 ${label}拒绝且不创建运行`,async({page})=>{
  const f=newCompletionBrowserFixture();if(label==='capacity')f.inspection.newWellContext.maxConnections=1
  const state=await installMockBackend(page,{eclipseInspection:f.inspection,eclipseHistory:[summary(f.baseline)],runs:[f.baseline],rangePayloads:f.rangePayloads})
  await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型')
  const panel=await fillNewCompletion(page,{...f.parameters,...change})
  await panel.getByRole('button',{name:'新增完井连接并计算',exact:true}).click();await expect(panel.getByRole('alert')).toContainText(message)
  expect(state.createPayloads).toEqual([]);expect(state.unexpectedRequests).toEqual([])
})
test('ECLIPSE 新完井 非活动连接拒绝且不创建运行',async({page})=>{
  const f=newCompletionBrowserFixture(),file=f.baseline.result.fieldIndex.files.find(v=>v.name.endsWith('.EGRID')),field=file.fields.find(v=>v.keyword==='ACTNUM'),artifact=f.baseline.artifacts.find(a=>a.name==='eclipse-output-'+file.name)
  f.rangePayloads[`${artifact.id}:${field.segments[0].offset+4}`]=Buffer.alloc(4)
  const state=await installMockBackend(page,{eclipseInspection:f.inspection,eclipseHistory:[summary(f.baseline)],runs:[f.baseline],rangePayloads:f.rangePayloads})
  await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型')
  const panel=await fillNewCompletion(page,f.parameters);await panel.getByRole('button',{name:'新增完井连接并计算',exact:true}).click()
  await expect(panel.getByRole('alert')).toContainText('活动单元');expect(state.createPayloads).toEqual([]);expect(state.unexpectedRequests).toEqual([])
})
test('ECLIPSE 新完井 原生压力响应与未修改井控刷新恢复',async({page})=>{
  const f=newCompletionBrowserFixture()
  const state=await installMockBackend(page,{eclipseInspection:f.inspection,eclipseHistory:[summary(f.target),summary(f.baseline)],runs:[f.target,f.baseline],rangePayloads:f.rangePayloads})
  await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型')
  const panel=page.getByRole('region',{name:'ECLIPSE 新完井方案验收',exact:true})
  const check=async()=>{
    await expect(panel).toContainText(`基线 #${f.baseline.id}`);await expect(panel).toContainText('新连接 I2/J1/K1')
    await expect(panel).toContainText('144 项实际报告数值');await expect(panel).toContainText('0 项缺少同时间值')
    await expect(panel.getByLabel('ECLIPSE 新完井井级响应',{exact:true})).toContainText('3906.57421875')
    await expect(panel).toContainText('eclipse-schedule-applied-package.zip')
  }
  await check();await page.reload();await activateModel(page,'ECLIPSE演示模型');await check();expect(state.unexpectedRequests).toEqual([])
})
test('ECLIPSE 新完井 单位不一致拒绝生成差值',async({page})=>{
  const f=newCompletionBrowserFixture();f.target.result.summary.series.find(s=>s.keyword==='WBHP'&&s.objectName==='P').unit='PSIA'
  await installMockBackend(page,{eclipseInspection:f.inspection,eclipseHistory:[summary(f.target),summary(f.baseline)],runs:[f.target,f.baseline],rangePayloads:f.rangePayloads})
  await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型')
  const panel=page.getByRole('region',{name:'ECLIPSE 新完井方案验收',exact:true});await expect(panel).toContainText('单位不一致')
  await expect(panel.getByLabel('ECLIPSE 新完井井级响应',{exact:true})).toHaveCount(0)
})

async function fillNewWell(page, parameters) {
  const panel = page.getByRole('region', { name: 'ECLIPSE 新增生产井', exact: true })
  await panel.getByRole('textbox', { name: '新增井名称', exact: true }).fill(parameters.well)
  for (const key of ['i', 'j', 'k', 'referenceDepth', 'diameter', 'kh', 'skin', 'waterRate', 'bhpLimit'])
    await panel.getByRole('spinbutton', { name: `新增井 ${key}`, exact: true }).fill(String(parameters[key]))
  return panel
}

test('ECLIPSE 新井 实际ACTNUM核对并提交完整来源和井控', async ({ page }) => {
  const f = newWellBrowserFixture()
  const state = await installMockBackend(page, { eclipseInspection: f.inspection, eclipseHistory: [summary(f.baseline)], runs: [f.baseline], rangePayloads: f.rangePayloads })
  await openWorkspace(page); await activateModel(page, 'ECLIPSE演示模型')
  const panel = await fillNewWell(page, f.parameters)
  await expect(panel).toContainText('SM3/DAY'); await expect(panel).toContainText('BARSA')
  await panel.getByRole('button', { name: '创建新井工程并计算', exact: true }).click()
  await expect.poll(() => state.createPayloads.length).toBe(1)
  expect(state.createPayloads[0].payload.parameters).toEqual(f.parameters)
  expect(state.rangeRequests.some(r => r.runId === f.baseline.id && r.params.endsWith('length=4'))).toBe(true)
  expect(state.unexpectedRequests).toEqual([])
})

for (const [change, message] of [['name', '新井名与已有井冲突'], ['cell', '已有井连接占用']]) test(`ECLIPSE 新井 ${change}冲突不创建运行`, async ({ page }) => {
  const f = newWellBrowserFixture()
  const state = await installMockBackend(page, { eclipseInspection: f.inspection, eclipseHistory: [summary(f.baseline)], runs: [f.baseline], rangePayloads: f.rangePayloads })
  await openWorkspace(page); await activateModel(page, 'ECLIPSE演示模型')
  const panel = await fillNewWell(page, { ...f.parameters, ...(change === 'name' ? { well: 'P' } : { i: 1 }) })
  await panel.getByRole('button', { name: '创建新井工程并计算', exact: true }).click()
  await expect(panel.getByRole('alert')).toContainText(message)
  expect(state.createPayloads).toEqual([]); expect(state.unexpectedRequests).toEqual([])
})

test('ECLIPSE 新井 非活动连接不创建运行', async ({ page }) => {
  const f = newWellBrowserFixture(), file = f.baseline.result.fieldIndex.files.find(v => v.name.endsWith('.EGRID'))
  const field = file.fields.find(v => v.keyword === 'ACTNUM'), artifact = f.baseline.artifacts.find(a => a.name === 'eclipse-output-' + file.name)
  f.rangePayloads[`${artifact.id}:${field.segments[0].offset + 14 * 4}`] = Buffer.alloc(4)
  const state = await installMockBackend(page, { eclipseInspection: f.inspection, eclipseHistory: [summary(f.baseline)], runs: [f.baseline], rangePayloads: f.rangePayloads })
  await openWorkspace(page); await activateModel(page, 'ECLIPSE演示模型')
  const panel = await fillNewWell(page, f.parameters)
  await panel.getByRole('button', { name: '创建新井工程并计算', exact: true }).click()
  await expect(panel.getByRole('alert')).toContainText('活动单元')
  expect(state.createPayloads).toEqual([]); expect(state.unexpectedRequests).toEqual([])
})

test('ECLIPSE 新井 真实减产关井及缺失基线刷新恢复', async ({ page }) => {
  const f = newWellBrowserFixture()
  const state = await installMockBackend(page, { eclipseInspection: f.inspection, eclipseHistory: [summary(f.target), summary(f.baseline)], runs: [f.target, f.baseline], rangePayloads: f.rangePayloads })
  await openWorkspace(page); await activateModel(page, 'ECLIPSE演示模型')
  const panel = page.getByRole('region', { name: 'ECLIPSE 新井方案验收', exact: true })
  const check = async () => {
    await expect(panel).toContainText(`基线 #${f.baseline.id}`)
    await expect(panel).toContainText('实际24个报告，4项压力约束下减产，16项关井/停井报告')
    await expect(panel).toContainText('不把其基线填为0')
    await expect(panel.getByLabel('ECLIPSE 新井实际生产与状态', { exact: true })).toContainText('1369.1064453125')
    await expect(panel.getByLabel('ECLIPSE 新井对原井影响', { exact: true })).toContainText('基线（新井为空）')
  }
  await check(); await page.reload(); await activateModel(page, 'ECLIPSE演示模型'); await check()
  expect(state.unexpectedRequests).toEqual([])
})

test('ECLIPSE 新井 单位错误不生成减产状态和混单位差值', async ({ page }) => {
  const f = newWellBrowserFixture()
  f.target.result.summary.series.find(s => s.keyword === 'WBHP' && s.objectName === 'P2').unit = 'PSIA'
  await installMockBackend(page, { eclipseInspection: f.inspection, eclipseHistory: [summary(f.target), summary(f.baseline)], runs: [f.target, f.baseline], rangePayloads: f.rangePayloads })
  await openWorkspace(page); await activateModel(page, 'ECLIPSE演示模型')
  const panel = page.getByRole('region', { name: 'ECLIPSE 新井方案验收', exact: true })
  await expect(panel).toContainText('单位不一致')
  await expect(panel.getByLabel('ECLIPSE 新井实际生产与状态', { exact: true })).toHaveCount(0)
})

for (const label of ['diameter','kh','skin']) test(`ECLIPSE 完井数值 ${label} 原值单位与完整记录提交`, async ({page}) => {
  const f=completionGeometryBrowserFixture(label)
  const state=await installMockBackend(page,{eclipseInspection:f.inspection,eclipseHistory:[summary(f.baseline)],runs:[f.baseline]})
  await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型')
  const panel=page.getByRole('region',{name:'ECLIPSE 完井数值调整',exact:true})
  await panel.locator('.el-select').filter({has:page.getByRole('combobox',{name:'完井数值调整项目',exact:true})}).click()
  await page.getByRole('option',{name:{diameter:'井径',kh:'渗透率×完井厚度 KH',skin:'皮肤系数'}[label],exact:true}).click()
  await expect(panel).toContainText(`原值 ${f.parameters.originalValue}`)
  await expect(panel).toContainText(label==='skin'?'无量纲':f.parameters.unit)
  await panel.getByRole('spinbutton',{name:'完井数值新值',exact:true}).fill(String(f.parameters.targetValue))
  await panel.getByRole('button',{name:'创建完井数值方案并计算',exact:true}).click()
  await expect.poll(()=>state.createPayloads.length).toBe(1)
  expect(state.createPayloads[0].payload.parameters).toEqual(f.parameters);expect(state.unexpectedRequests).toEqual([])
})

for (const label of ['diameter','kh','skin']) test(`ECLIPSE 完井数值 ${label} 原生井级响应刷新恢复`, async ({page}) => {
  const f=completionGeometryBrowserFixture(label)
  const state=await installMockBackend(page,{eclipseInspection:f.inspection,eclipseHistory:[summary(f.target),summary(f.baseline)],runs:[f.target,f.baseline]})
  await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型')
  const panel=page.getByRole('region',{name:'ECLIPSE 完井数值方案验收',exact:true})
  const check=async()=>{
    await expect(panel).toContainText('#8966');await expect(panel).toContainText('CF保持自动计算')
    await expect(panel).toContainText('72 项实际报告数值，23 项共有时刻变化，0 项缺少同时间值')
    await expect(panel.getByLabel('ECLIPSE 完井数值井级响应',{exact:true})).toContainText('BARSA')
    await expect(panel).toContainText('eclipse-schedule-applied-package.zip')
  }
  await check();await page.reload();await activateModel(page,'ECLIPSE演示模型');await check();expect(state.unexpectedRequests).toEqual([])
})

test('ECLIPSE 完井数值 负皮肤不创建运行、正CF源不提供编辑',async({page})=>{
  const f=completionGeometryBrowserFixture('skin')
  const state=await installMockBackend(page,{eclipseInspection:f.inspection,eclipseHistory:[summary(f.baseline)],runs:[f.baseline]})
  await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型')
  const panel=page.getByRole('region',{name:'ECLIPSE 完井数值调整',exact:true})
  await panel.locator('.el-select').filter({has:page.getByRole('combobox',{name:'完井数值调整项目',exact:true})}).click();await page.getByRole('option',{name:'皮肤系数',exact:true}).click()
  await panel.getByRole('spinbutton',{name:'完井数值新值',exact:true}).fill('-1');await panel.getByRole('button',{name:'创建完井数值方案并计算',exact:true}).click()
  await expect(panel.getByRole('alert')).toContainText('皮肤系数为0–100');expect(state.createPayloads).toEqual([])
  state.models[2].versions[0].inspection.scheduleMetadata.records.find(r=>r.lineNumber===157).values[7]='1.5'
  await page.reload();await activateModel(page,'ECLIPSE演示模型');await expect(panel).toContainText('当前模型没有可调整记录')
  await expect(panel.getByRole('button',{name:'创建完井数值方案并计算',exact:true})).toHaveCount(0);expect(state.unexpectedRequests).toEqual([])
})

test('ECLIPSE 完井数值 结果单位不一致不生成差值',async({page})=>{
  const f=completionGeometryBrowserFixture();f.target.result.summary.series.find(s=>s.keyword==='WBHP').unit='PSIA'
  await installMockBackend(page,{eclipseInspection:f.inspection,eclipseHistory:[summary(f.target),summary(f.baseline)],runs:[f.target,f.baseline]})
  await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型')
  const panel=page.getByRole('region',{name:'ECLIPSE 完井数值方案验收',exact:true})
  await expect(panel).toContainText('单位不一致');await expect(panel.getByLabel('ECLIPSE 完井数值井级响应',{exact:true})).toHaveCount(0)
})

for (const keyword of ['PERMX', 'PORO']) test(`ECLIPSE 区域 ${keyword} 六格原生字段提交完整范围和校验信息`, async ({ page }) => {
  const f = gridRegionBrowserFixture(keyword)
  const state = await installMockBackend(page, { eclipseHistory: [summary(f.baseline)], runs: [f.baseline], rangePayloads: f.rangePayloads })
  await openWorkspace(page); await activateModel(page, 'ECLIPSE演示模型')
  const grid = page.locator('#eclipse-3d-result .eclipse-grid-3d-panel')
  await expect(grid).toHaveAttribute('data-render-ready', 'true')
  await grid.locator('.grid-3d-controls .el-select').click()
  await page.getByRole('option', { name: new RegExp(`^${keyword} · ECLIPSE_PERMX_WATER.INIT`) }).click()
  await expect(grid).toHaveAttribute('data-render-ready', 'true')
  const panel = grid.getByLabel('ECLIPSE 区域物性真实重算方案', { exact: true })
  await panel.getByRole('spinbutton', { name: '区域 I 终点', exact: true }).fill('3')
  await panel.getByRole('spinbutton', { name: '区域 J 终点', exact: true }).fill('2')
  await panel.getByRole('spinbutton', { name: '区域物性乘数', exact: true }).fill(String(f.parameters.multiplier))
  await expect(panel).toContainText('选区 6 个单元')
  await expect(panel).toContainText(keyword === 'PORO' ? '体积分数' : 'mD')
  await panel.getByRole('button', { name: '修改选区并真实重算' }).click()
  await expect.poll(() => state.createPayloads.length).toBe(1)
  expect(state.createPayloads[0].payload.parameters).toEqual(f.parameters)
  expect(state.unexpectedRequests).toEqual([])
})

test('ECLIPSE 区域 PORO 越物性上限拦截，不创建计算', async ({ page }) => {
  const f = gridRegionBrowserFixture('PORO')
  const state = await installMockBackend(page, { eclipseHistory: [summary(f.baseline)], runs: [f.baseline], rangePayloads: f.rangePayloads })
  await openWorkspace(page); await activateModel(page, 'ECLIPSE演示模型')
  const grid = page.locator('#eclipse-3d-result .eclipse-grid-3d-panel')
  await expect(grid).toHaveAttribute('data-render-ready', 'true')
  await grid.locator('.grid-3d-controls .el-select').click()
  await page.getByRole('option', { name: /^PORO · ECLIPSE_PERMX_WATER.INIT/ }).click()
  await expect(grid).toHaveAttribute('data-render-ready', 'true')
  const panel = grid.getByLabel('ECLIPSE 区域物性真实重算方案', { exact: true })
  await panel.getByRole('spinbutton', { name: '区域物性乘数', exact: true }).fill('2')
  await panel.getByRole('button', { name: '修改选区并真实重算' }).click()
  await expect(panel.getByRole('alert')).toContainText('孔隙度')
  expect(state.createPayloads).toEqual([])
  expect(state.unexpectedRequests).toEqual([])
})

for (const keyword of ['PERMX', 'PORO']) test(`ECLIPSE 区域 ${keyword} 原生井级响应与来源刷新恢复`, async ({ page }) => {
  const f = gridRegionBrowserFixture(keyword)
  const state = await installMockBackend(page, { eclipseHistory: [summary(f.target), summary(f.baseline)], runs: [f.target, f.baseline], rangePayloads: f.rangePayloads })
  await openWorkspace(page); await activateModel(page, 'ECLIPSE演示模型')
  const panel = page.getByRole('region', { name: 'ECLIPSE 区域物性方案验收', exact: true })
  const check = async () => {
    await expect(panel).toContainText(keyword)
    await expect(panel).toContainText('#8903')
    await expect(panel).toContainText('1–3 / 1–2 / 1–1')
    await expect(panel).toContainText('72 项实际报告数值，23 项共有时刻变化，0 项缺少同时间值')
    await expect(panel.getByLabel('ECLIPSE 区域物性井级响应', { exact: true })).toContainText('BARSA')
    await expect(panel).toContainText('eclipse-grid-field-applied-package.zip')
  }
  await check(); await page.reload(); await activateModel(page, 'ECLIPSE演示模型'); await check()
  expect(state.unexpectedRequests).toEqual([])
})

test('ECLIPSE 区域 结果压力单位不一致不生成差值', async ({ page }) => {
  const f = gridRegionBrowserFixture('PERMX'); f.target.result.summary.series.find(s => s.keyword === 'WBHP').unit = 'PSIA'
  await installMockBackend(page, { eclipseHistory: [summary(f.target), summary(f.baseline)], runs: [f.target, f.baseline], rangePayloads: f.rangePayloads })
  await openWorkspace(page); await activateModel(page, 'ECLIPSE演示模型')
  const panel = page.getByRole('region', { name: 'ECLIPSE 区域物性方案验收', exact: true })
  await expect(panel).toContainText('单位不一致')
  await expect(panel.getByLabel('ECLIPSE 区域物性井级响应', { exact: true })).toHaveCount(0)
})

function completionFactorFixture() {
  const parameters = { schemaVersion: 'eclipse-completion-factor-parameters/1', baselineRunId: 8201, well: 'G1', sourceFile: 'BASE.SCH', lineNumber: 116,
    i: '14', j: '2', k1: '1', k2: '1', originalConnectionFactor: 3.028, targetConnectionFactor: 1.5 }
  const deck = { ...structuredClone(inspection), schemaVersion: 'eclipse-data-inspection/4', caseName: 'EX5.DATA', unitSystem: 'FIELD', wellNames: ['G1'],
    dimensions: { nx: 14, ny: 2, nz: 1 }, scheduleMetadata: { wells: [{ name: 'G1' }], groups: [],
      completions: [{ keyword: 'COMPDAT', well: 'G1', sourceFile: 'BASE.SCH', lineNumber: 116, i: '14', j: '2', k1: '1', k2: '1', status: 'OPEN' }],
      records: [{ keyword: 'COMPDAT', sourceFile: 'BASE.SCH', lineNumber: 116, values: ['G1', '14', '2', '1', '1', 'OPEN', '1*', '3.028', '0.656'] }] } }
  const baseline = { id: 8201, projectId: 1, modelId: 22, modelVersionId: 201, modelName: 'ECLIPSE演示模型', versionNo: 1, runType: 'eclipse', study: null,
    status: 'SUCCEEDED', resultContract: 'VALID_FULL', parameters: null, artifacts: [], events: [], result: {
      schemaVersion: 'eclipse-summary-result/1', modelKind: 'eclipse_100', runTask: 'eclipse', resultContract: 'VALID_FULL', caseName: 'EX5.DATA',
      eclEnd: { comments: 0, warnings: 0, problems: 0, errors: 0, bugs: 0 }, outputFiles: [], summary: { series: [
        { keyword: 'WOPR', objectName: 'G1', unit: 'STB/DAY', points: [{ timeDays: 30, value: 100 }, { timeDays: 60, value: 100 }] },
        { keyword: 'WBHP', objectName: 'G1', unit: 'PSIA', points: [{ timeDays: 30, value: 3000 }, { timeDays: 60, value: 3000 }] }] } } }
  const derived = { ...structuredClone(baseline), id: 8202, parameters }
  derived.result.summary.series[1].points = [{ timeDays: 30, value: 2900 }, { timeDays: 60, value: 2950 }]
  return { parameters, deck, baseline, derived }
}

test('ECLIPSE CF 单位与精确来源提交', async ({ page }) => {
  const f = completionFactorFixture(), state = await installMockBackend(page, { eclipseInspection: f.deck, eclipseHistory: [summary(f.baseline)], runs: [f.baseline] })
  await openWorkspace(page); await activateModel(page, 'ECLIPSE演示模型')
  const editor = page.getByRole('region', { name: 'ECLIPSE COMPDAT 连接因子方案', exact: true })
  await editor.getByText('创建 COMPDAT 连接因子方案', { exact: true }).click()
  await expect(editor).toContainText('cP.rb/day/psi')
  await expect(editor.getByLabel('ECLIPSE COMPDAT 原连接因子', { exact: true })).toHaveValue('3.028')
  await editor.getByRole('spinbutton', { name: 'ECLIPSE COMPDAT 目标连接因子' }).fill('1.5')
  await page.getByRole('button', { name: '运行', exact: true }).click(); await expect.poll(() => state.createPayloads.length).toBe(1)
  expect(state.createPayloads[0].payload.parameters).toEqual(f.parameters)
  expect(state.unexpectedRequests).toEqual([])
})

test('ECLIPSE CF 压力变化不冒充比例产油且刷新恢复', async ({ page }) => {
  const f = completionFactorFixture(), state = await installMockBackend(page, { eclipseInspection: f.deck, eclipseHistory: [summary(f.derived), summary(f.baseline)], runs: [f.derived, f.baseline] })
  await openWorkspace(page); await activateModel(page, 'ECLIPSE演示模型')
  const panel = page.getByRole('region', { name: '连接因子井级实际响应', exact: true })
  const check = async () => {
    await expect(panel).toContainText('共有时刻有2项数值变化'); await expect(panel).toContainText('工程没有请求WWPR、WGPR、WOPT，不补算')
    await expect(panel.getByLabel('CF目标井真实对比', { exact: true })).toContainText('-100')
    await expect(panel).toContainText('不能按CF比例直接推算产量')
    await expect(page.getByRole('region', { name: 'ECLIPSE Schedule 方案验收' })).toContainText('3.028 cP.rb/day/psi')
  }
  await check(); await page.reload(); await activateModel(page, 'ECLIPSE演示模型'); await check()
  expect(state.unexpectedRequests).toEqual([])
})

test('ECLIPSE CF 不同单位结果拒绝生成差值', async ({ page }) => {
  const f = completionFactorFixture(); f.derived.result.summary.series[1].unit = 'BARSA'
  await installMockBackend(page, { eclipseInspection: f.deck, eclipseHistory: [summary(f.derived), summary(f.baseline)], runs: [f.derived, f.baseline] })
  await openWorkspace(page); await activateModel(page, 'ECLIPSE演示模型')
  const panel = page.getByRole('region', { name: '连接因子井级实际响应', exact: true })
  await expect(panel).toContainText('目标井指标尚不可核对')
  await expect(panel.getByLabel('CF目标井真实对比', { exact: true })).toHaveCount(0)
})

test('E300 原始组分曲线检索完整导出及刷新恢复', async ({ page }) => {
  const f = compositionalBrowserFixture()
  const state = await installMockBackend(page, { eclipseModelKind: 'eclipse_300', eclipseInspection: f.inspection,
    eclipseHistory: [summary(f.run)], runs: [f.run], capabilities: { ...availableCapabilities(), eclipse300: availableCapabilities().eclipse100 } })
  await openWorkspace(page); await activateModel(page, 'ECLIPSE演示模型')
  const panel = page.getByRole('region', { name: 'ECLIPSE 300 组分计算结果' })
  await expect(panel).toContainText('5条原始曲线、每条2个实际时刻')
  await expect(panel).toContainText('网格 (7, 7, 1)')
  await expect(page.getByRole('region', { name: 'ECLIPSE 单井启停方案' })).toHaveCount(0)
  const download = page.waitForEvent('download')
  await panel.getByRole('button', { name: '导出全部原始曲线CSV' }).click()
  expect((await download).suggestedFilename()).toContain('E300-801')
  await panel.getByText('隐藏未定义槽位', { exact: true }).click()
  await expect(panel).toContainText('未定义井槽位 2')
  await panel.locator('.el-select').filter({ has: page.getByRole('combobox', { name: 'E300结果组分' }) }).click()
  await page.getByRole('option', { name: '9 · C7+3', exact: true }).click()
  await expect(panel.getByLabel('E300组分与原始指标', { exact: true })).toContainText('当前筛选下没有曲线；未请求的组分不会补算')
  await page.reload(); await activateModel(page, 'ECLIPSE演示模型')
  await expect(panel).toContainText('5条原始曲线、每条2个实际时刻')
  await page.getByRole('tab', { name: 'DATA 检查' }).click()
  await expect(page.getByText('9: C7+3', { exact: false })).toBeVisible()
  expect(state.unexpectedRequests).toEqual([])
})

test('E300 独立能力允许原模型计算且提交空参数', async ({ page }) => {
  const f = compositionalBrowserFixture(), caps = availableCapabilities()
  caps.eclipse300 = { ...caps.eclipse100 }; caps.eclipse100 = { ...caps.eclipse100, status: 'UNAVAILABLE', runTasks: [] }
  const state = await installMockBackend(page, { eclipseModelKind: 'eclipse_300', eclipseInspection: f.inspection, capabilities: caps })
  await openWorkspace(page); await activateModel(page, 'ECLIPSE演示模型')
  await page.getByRole('button', { name: '运行', exact: true }).click()
  await expect.poll(() => state.createPayloads.length).toBe(1)
  expect(state.createPayloads[0].payload).toEqual({ study: null, runType: 'eclipse', parameters: null })
})

test('E300 未发布能力不能借用 E100 能力运行', async ({ page }) => {
  const f = compositionalBrowserFixture(), state = await installMockBackend(page, { eclipseModelKind: 'eclipse_300', eclipseInspection: f.inspection })
  await openWorkspace(page); await activateModel(page, 'ECLIPSE演示模型')
  await expect(page.getByRole('button', { name: '运行', exact: true })).toBeDisabled()
  expect(state.createPayloads).toEqual([])
})

test('E300 错误模型组分来源拒绝展示曲线', async ({ page }) => {
  const f = compositionalBrowserFixture(); f.run.result.simulation.componentNames[0] = 'OTHER'
  await installMockBackend(page, { eclipseModelKind: 'eclipse_300', eclipseInspection: f.inspection, eclipseHistory: [summary(f.run)], runs: [f.run] })
  await openWorkspace(page); await activateModel(page, 'ECLIPSE演示模型')
  await expect(page.getByRole('region', { name: 'ECLIPSE 300 组分计算结果' })).toContainText('组分结果或模型来源核对未通过')
  await expect(page.getByRole('button', { name: '导出全部原始曲线CSV' })).toHaveCount(0)
})

test('E300 热采温度产汽热损失检索与刷新恢复', async ({ page }) => {
  const f = thermalBrowserFixture(), caps = { ...availableCapabilities(), eclipse300: availableCapabilities().eclipse100 }
  const state = await installMockBackend(page, { eclipseModelKind: 'eclipse_300', eclipseInspection: f.inspection, eclipseHistory: [summary(f.run)], runs: [f.run], capabilities: caps })
  await openWorkspace(page); await activateModel(page, 'ECLIPSE演示模型')
  const panel = page.getByRole('region', { name: 'ECLIPSE 300 热采计算结果' })
  await expect(panel).toContainText('蒸汽驱热采'); await expect(panel).toContainText('网格 (9, 1, 4)')
  await expect(panel).toContainText('DEG F'); await expect(panel).toContainText('井底压力变化')
  await expect(page.getByRole('region', { name: 'ECLIPSE 300 组分计算结果' })).toHaveCount(0)
  await expect(page.getByRole('region', { name: 'ECLIPSE 单井启停方案' })).toHaveCount(0)
  const select = panel.locator('.el-select').filter({ has: page.getByRole('combobox', { name: 'E300热采结果类型' }) })
  await select.click(); await page.getByRole('option', { name: '产汽量与累计产汽', exact: true }).click()
  await expect(panel.getByLabel('E300温度与热指标', { exact: true })).toContainText('WSTPR')
  await select.click(); await page.getByRole('option', { name: '热损失与累计热损失', exact: true }).click()
  await expect(panel.getByLabel('E300温度与热指标', { exact: true })).toContainText('FHLR')
  const download = page.waitForEvent('download'); await panel.getByRole('button', { name: '导出全部热采原始曲线CSV' }).click()
  expect((await download).suggestedFilename()).toContain('E300-热采-811')
  await page.reload(); await activateModel(page, 'ECLIPSE演示模型'); await expect(panel).toContainText('网格 (9, 1, 4)')
  await page.getByRole('tab', { name: 'DATA 检查' }).click(); await expect(page.getByText('LIVEOIL', { exact: true })).toBeVisible()
  expect(state.unexpectedRequests).toEqual([])
})

test('E300 热采完整模型运行提交空参数', async ({ page }) => {
  const f = thermalBrowserFixture()
  const state = await installMockBackend(page, { eclipseModelKind: 'eclipse_300', eclipseInspection: f.inspection,
    capabilities: { ...availableCapabilities(), eclipse300: availableCapabilities().eclipse100 } })
  await openWorkspace(page); await activateModel(page, 'ECLIPSE演示模型')
  await page.getByRole('button', { name: '运行', exact: true }).click(); await expect.poll(() => state.createPayloads.length).toBe(1)
  expect(state.createPayloads[0].payload).toEqual({ study: null, runType: 'eclipse', parameters: null })
})

test('E300 热采温度单位与检查不符不展示', async ({ page }) => {
  const f = thermalBrowserFixture(); f.run.result.summary.series[0].unit = 'DEG C'
  await installMockBackend(page, { eclipseModelKind: 'eclipse_300', eclipseInspection: f.inspection, eclipseHistory: [summary(f.run)], runs: [f.run] })
  await openWorkspace(page); await activateModel(page, 'ECLIPSE演示模型')
  await expect(page.getByRole('region', { name: 'ECLIPSE 300 热采计算结果' })).toContainText('热采结果或模型来源核对未通过')
  await expect(page.getByRole('button', { name: '导出全部热采原始曲线CSV' })).toHaveCount(0)
})

test.beforeEach(async ({ page }) => {
  page.on('pageerror', error => console.error(`[browser] ${error.message}`))
})

const project = { id: 1, name: '演示项目', description: '浏览器回归专用数据' }
const inspection = {
  schemaVersion: 'eclipse-data-inspection/3',
  caseName: 'DEMO.DATA',
  sections: ['RUNSPEC', 'GRID', 'SOLUTION', 'SUMMARY', 'SCHEDULE'],
  unitSystem: 'METRIC',
  phases: ['OIL', 'WATER'],
  dimensions: { nx: 4, ny: 3, nz: 2 },
  wellNames: ['WELL-A'],
  scheduleTimeline: [{ kind: 'TSTEP', steps: ['30', '60'] }],
  packageFiles: [
    { relativePath: 'DEMO.DATA', sizeBytes: 128, sha256: 'a'.repeat(64) },
    { relativePath: 'include/grid.inc', sizeBytes: 256, sha256: 'b'.repeat(64) }
  ]
}
const versions = {
  well: { id: 101, versionNo: 1, status: 'READY', modelKind: 'black_oil_liquid', originalName: 'well-demo.pips', studies: ['Base Case'], inspection: {
    schemaVersion: 'pipesim-well-inspection/4', reservoirPressure: null, well: 'Well_1', espContexts: ['Well_1:ESP'],
    packageFiles: [{ relativePath: 'well-demo.pips', sizeBytes: 512, sha256: 'e'.repeat(64) }]
  } },
  network: {
    id: 301, versionNo: 1, status: 'READY', modelKind: 'network', originalName: 'network-demo.pips', studies: ['Network Base'],
    inspection: {
      schemaVersion: 'pipesim-network-inspection/2',
      studies: [{ study: 'Network Base', boundaries: [
        { node: 'Supply_1', boundaryNodeType: 'Source', isActive: true, isSurfaceCondition: true, flowRateType: 'GasFlowRate', pressure: null, temperature: 131, gasFlowRate: 1306.84, liquidFlowRate: null, massFlowRate: null },
        { node: 'Terminal', boundaryNodeType: 'Sink', isActive: true, isSurfaceCondition: true, flowRateType: 'LiquidFlowRate', pressure: 855, temperature: null, gasFlowRate: null, liquidFlowRate: null, massFlowRate: null }
      ] }],
      packageFiles: [
        { relativePath: 'network-demo.pips', sizeBytes: 512, sha256: 'c'.repeat(64) },
        { relativePath: 'network-settings.xml', sizeBytes: 96, sha256: 'd'.repeat(64) }
      ]
    }
  },
  eclipse: { id: 201, versionNo: 1, status: 'READY', modelKind: 'eclipse_100', originalName: 'DEMO.DATA', studies: [], inspection }
}
const models = [
  { id: 11, name: '井筒演示模型', versions: [versions.well] },
  { id: 33, name: '管网演示模型', versions: [versions.network] },
  { id: 22, name: 'ECLIPSE演示模型', versions: [versions.eclipse] }
]
const response = data => ({ code: 200, msg: 'success', data })
const availableCapabilities = () => ({
  worker: { status: 'AVAILABLE', idle: true, reasonCode: null },
  pipesimWell: { version: '2022.1', status: 'AVAILABLE', reasonCode: null, runTasks: ['nodal', 'profile', 'combined'], maxTimeoutSeconds: 600 },
  pipesimNetwork: { version: '2022.1', status: 'AVAILABLE', reasonCode: null, runTasks: ['network', 'system-analysis', 'network-optimizer', 'network-well-performance-curves'], maxTimeoutSeconds: 600 },
  eclipse100: { version: '2024.1', status: 'AVAILABLE', reasonCode: null, runTasks: ['eclipse'], maxTimeoutSeconds: 1800 }
})
const summary = run => ({
  id: run.id,
  projectId: 1,
  modelId: run.modelId,
  modelVersionId: run.modelVersionId,
  modelName: run.modelName,
  versionNo: 1,
  study: run.study,
  runType: run.runType,
  resultContract: run.resultContract,
  status: run.status,
  createdAt: '2026-09-13T10:00:00',
  elapsedMillis: run.elapsedMillis || 0,
  cancellable: run.cancellable
})

async function installMockBackend(page, options = {}) {
  const state = {
    models: structuredClone(models),
    validationRequests: [],
    holdValidation: false,
    releaseValidation: null,
    runs: new Map(),
    histories: new Map([[101, options.wellHistory || []], [201, options.eclipseHistory || []], [301, options.networkHistory || []]]),
    createPayloads: [],
    releaseCreateRun: null,
    historyReads: new Map(),
    runReads: new Map(),
    delayedWellRunId: options.delayedWellRunId || null,
    releaseWellDetail: null,
    heldRunId: null,
    releaseHeldRun: null,
    capabilities: options.capabilities || availableCapabilities(),
    capabilityReads: 0,
    downloadRequests: [],
    rangeRequests: [],
    unexpectedRequests: []
  }
  if (options.eclipseInspection) state.models[2].versions[0].inspection = structuredClone(options.eclipseInspection)
  if (options.eclipseModelKind) {
    state.models[2].versions[0].modelKind = options.eclipseModelKind
    state.models[2].versions[0].originalName = 'official.zip'
  }
  if (options.networkInspection) state.models[1].versions[0].inspection = structuredClone(options.networkInspection)
  state.deletedProjects = [{ ...project, id: 99, name: '已删除演示项目', updatedAt: '2026-09-10T10:00:00' }]
  for (const run of options.runs || []) state.runs.set(run.id, run)

  await page.route('**/api/software-integration/**', async route => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname.replace(/^\/api/, '')
    const method = request.method()

    if (path === '/software-integration/environment' && method === 'GET') {
      return route.fulfill({ json: response({ host: 'test-worker', configured: true, configuredSimulators: ['pipesim'],
        paths: {}, candidates: {}, pipesim: { status: 'READY' }, eclipse: { status: 'MISSING' } }) })
    }

    if (/^\/software-integration\/model-versions\/\d+\/eclipse-history-datasets$/.test(path) && method === 'GET') {
      return route.fulfill({ json: response(options.eclipseHistoryDatasets || []) })
    }

    if (/^\/software-integration\/model-versions\/\d+\/eclipse-batches$/.test(path) && method === 'GET') {
      return route.fulfill({ json: response(options.eclipseBatches || []) })
    }

    if (path === '/software-integration/capabilities' && method === 'GET') {
      state.capabilityReads += 1
      return route.fulfill({ json: response(state.capabilities) })
    }
    if (path === '/software-integration/recycle-bin/projects' && method === 'GET') {
      return route.fulfill({ json: response(state.deletedProjects) })
    }
    if (path === '/software-integration/projects' && method === 'GET') {
      return route.fulfill({ json: response([project]) })
    }
    if (path === '/software-integration/projects/1' && method === 'GET') {
      return route.fulfill({ json: response({ project, models: state.models }) })
    }
    const modelDeleteMatch = path.match(/^\/software-integration\/projects\/1\/models\/(\d+)$/)
    if (modelDeleteMatch && method === 'DELETE') {
      const modelId = Number(modelDeleteMatch[1])
      state.models = state.models.filter(model => model.id !== modelId)
      return route.fulfill({ json: response(null) })
    }
    if (path === '/software-integration/projects/1/model-versions/101/validate' && method === 'POST') {
      state.validationRequests.push(101)
      if (state.holdValidation) await new Promise(resolve => { state.releaseValidation = resolve })
      if (options.validationFailure) return route.fulfill({
        status: options.validationFailure.status,
        json: { code: 409, msg: '模型检查服务暂不可用', data: null }
      })
      state.models[0].versions[0].status = 'VALIDATING'
      state.models[0].versions[0].inspection = null
      return route.fulfill({ json: response({ project, models: state.models }) })
    }

    const historyMatch = path.match(/^\/software-integration\/model-versions\/(\d+)\/runs$/)
    if (historyMatch && method === 'GET') {
      const versionId = Number(historyMatch[1])
      state.historyReads.set(versionId, (state.historyReads.get(versionId) || 0) + 1)
      return route.fulfill({ json: response(state.histories.get(versionId) || []) })
    }
    if (historyMatch && method === 'POST') {
      const versionId = Number(historyMatch[1])
      const payload = request.postDataJSON()
      state.createPayloads.push({ versionId, payload })
      if (options.holdCreateRun) await new Promise(resolve => { state.releaseCreateRun = resolve })
      if (options.createRunFailure) {
        return route.fulfill({
          status: options.createRunFailure.status || 409,
          json: { code: options.createRunFailure.status || 409, msg: options.createRunFailure.message, data: null }
        })
      }
      const eclipse = versionId === 201
      const run = {
        id: eclipse ? 9201 : 9101,
        projectId: 1,
        modelId: eclipse ? 22 : 11,
        modelVersionId: versionId,
        modelName: eclipse ? 'ECLIPSE演示模型' : '井筒演示模型',
        versionNo: 1,
        study: payload.study,
        runType: payload.runType,
        parameters: payload.parameters,
        status: eclipse ? 'RUNNING_ECLIPSE' : 'PREPARING',
        createdAt: '2026-09-13T10:00:00',
        elapsedMillis: 1000,
        cancellable: true,
        events: [],
        artifacts: []
      }
      state.runs.set(run.id, run)
      state.histories.get(versionId).unshift(summary(run))
      return route.fulfill({ status: 202, json: { code: 202, msg: 'accepted', data: summary(run) } })
    }

    const runMatch = path.match(/^\/software-integration\/runs\/(\d+)$/)
    if (runMatch && method === 'GET') {
      const id = Number(runMatch[1])
      state.runReads.set(id, (state.runReads.get(id) || 0) + 1)
      if (id === state.delayedWellRunId) {
        await new Promise(resolve => { state.releaseWellDetail = resolve })
        state.delayedWellRunId = null
      }
      if (id === state.heldRunId) {
        await new Promise(resolve => { state.releaseHeldRun = resolve })
        state.heldRunId = null
      }
      return route.fulfill({ json: { code: 200, msg: 'success', data: state.runs.get(id) } })
    }
    const artifactDownloadMatch = path.match(/^\/software-integration\/runs\/(\d+)\/artifacts\/(\d+)\/download$/)
    if (artifactDownloadMatch && method === 'GET') {
      state.downloadRequests.push({ runId: Number(artifactDownloadMatch[1]), artifactId: Number(artifactDownloadMatch[2]) })
      const payload = options.artifactPayloads?.[`${artifactDownloadMatch[1]}:${artifactDownloadMatch[2]}`]
      if (payload !== undefined) return route.fulfill({ status: 200, contentType: 'application/json', body: payload })
      return route.fulfill({ status: 200, contentType: 'text/plain', body: 'artifact fixture' })
    }
    const artifactRangeMatch = path.match(/^\/software-integration\/runs\/(\d+)\/artifacts\/(\d+)\/range$/)
    if (artifactRangeMatch && method === 'GET') {
      const artifactId = Number(artifactRangeMatch[2])
      const offset = Number(url.searchParams.get('offset'))
      state.rangeRequests.push({ runId: Number(artifactRangeMatch[1]), artifactId, params: url.searchParams.toString() })
      const requestedLength = Number(url.searchParams.get('length'))
      const configured = options.rangePayloads?.[`${artifactId}:${offset}`]
      const payloadLength = Number.isSafeInteger(requestedLength) && requestedLength > 12 ? requestedLength : 12
      const payload = configured ? Buffer.from(configured) : Buffer.alloc(payloadLength)
      if (!configured) for (let index = 0; index + 4 <= payload.length; index += 4) payload.writeFloatBE(index / 4 + 1, index)
      return route.fulfill({ status: 206, contentType: 'application/octet-stream', body: payload })
    }
    const cancelMatch = path.match(/^\/software-integration\/runs\/(\d+)\/cancel$/)
    if (cancelMatch && method === 'POST') {
      const id = Number(cancelMatch[1])
      const run = state.runs.get(id)
      Object.assign(run, { status: 'CANCEL_REQUESTED', cancellable: false })
      state.histories.set(run.modelVersionId, [summary(run)])
      return route.fulfill({ status: 202, json: { code: 202, msg: 'accepted', data: summary(run) } })
    }

    state.unexpectedRequests.push(`${method} ${path}`)
    return route.fulfill({ status: 500, json: { code: 500, msg: 'unexpected mocked route', data: null } })
  })

  await page.addInitScript(() => {
    localStorage.setItem('account', JSON.stringify({ token: 'e2e-local-token', username: 'e2e' }))
  })
  return state
}

async function openWorkspace(page) {
  await page.goto('/ipr?workspace=software-integration')
  await expect(page.getByText('井筒演示模型', { exact: true }).first()).toBeVisible()
}

async function activateModel(page, name) {
  await page.getByText(name, { exact: true }).first().dblclick()
  await expect(page.getByRole('heading', { name, exact: true }).last()).toBeVisible()
}

for (const status of [200, 503]) {
  test(`重新验证失败只显示一条内联提示（HTTP ${status}）`, async ({ page }) => {
    const state = await installMockBackend(page, { validationFailure: { status } })
    await openWorkspace(page)
    await activateModel(page, '井筒演示模型')
    const preview = page.getByRole('region', { name: '原模型参数', exact: true })
    await preview.getByRole('button', { name: '重新验证并读取', exact: true }).click()
    await expect(page.getByTestId('run-feedback')).toContainText('原模型参数读取请求失败')
    await expect(page.getByTestId('run-feedback')).toHaveCount(1)
    await expect(page.locator('.el-message')).toHaveCount(0)
    await expect(page.getByRole('button', { name: '运行', exact: true })).toBeEnabled()
    expect(state.validationRequests).toEqual([101])
  })
}

test('切换模型后旧验证失败不会污染当前运行提示', async ({ page }) => {
  const state = await installMockBackend(page, { validationFailure: { status: 503 } })
  state.holdValidation = true
  await openWorkspace(page)
  await activateModel(page, '井筒演示模型')
  await page.getByRole('region', { name: '原模型参数', exact: true }).getByRole('button', { name: '重新验证并读取', exact: true }).click()
  await expect.poll(() => Boolean(state.releaseValidation)).toBe(true)
  await activateModel(page, '管网演示模型')
  const finished = page.waitForResponse(response => response.url().endsWith('/model-versions/101/validate'))
  state.releaseValidation()
  await finished
  await expect(page.getByTestId('run-feedback')).toHaveCount(0)
  await expect(page.locator('.el-message')).toHaveCount(0)
})

test('运行任务选项跟随已部署执行能力，保留历史结果入口', async ({ page }) => {
  const capabilities = availableCapabilities()
  capabilities.pipesimNetwork.runTasks = ['network']
  const state = await installMockBackend(page, { capabilities })
  await openWorkspace(page)
  await activateModel(page, '管网演示模型')
  await expect(page.locator('label.el-radio-button').filter({ hasText: '管网模拟' })).toBeVisible()
  await expect(page.locator('label.el-radio-button').filter({ hasText: '系统分析' })).toHaveCount(0)
  await expect(page.locator('label.el-radio-button').filter({ hasText: '网络优化' })).toHaveCount(0)
  await expect(page.getByRole('tab', { name: '运行记录', exact: true })).toBeVisible()
  expect(state.createPayloads).toHaveLength(0)
})

test('软件集成工作区不初始化解析融合目录', async ({ page }) => {
  const parsingFusionRequests = []
  page.on('request', request => {
    const pathname = new URL(request.url()).pathname
    if (pathname.startsWith('/docker-api/')) parsingFusionRequests.push(`${request.method()} ${pathname}`)
  })
  const state = await installMockBackend(page)
  await openWorkspace(page)
  await expect(page.getByRole('tree').getByText('井筒演示模型', { exact: true })).toBeVisible()
  await page.waitForTimeout(250)
  expect(parsingFusionRequests).toEqual([])
  expect(state.unexpectedRequests).toEqual([])
})

test('软件项目回收站可读取已删除项目', async ({ page }) => {
  const state = await installMockBackend(page)
  await openWorkspace(page)
  await page.getByRole('button', { name: '回收站' }).click()
  const dialog = page.getByRole('dialog', { name: '软件项目回收站' })
  await expect(dialog).toContainText('已删除演示项目')
  expect(state.unexpectedRequests).toEqual([])
})

test('软件模型可在无活动运行时删除并从资源列表移除', async ({ page }) => {
  const state = await installMockBackend(page)
  await openWorkspace(page)
  const row = page.locator('.models-table .el-table__row').filter({ hasText: '井筒演示模型' })
  await row.getByRole('button', { name: '删除' }).click()
  const confirm = page.getByRole('dialog', { name: '删除软件模型' })
  await expect(confirm).toContainText('井筒演示模型')
  await confirm.getByRole('button', { name: '确定' }).click()
  await expect(page.getByText('井筒演示模型', { exact: true }).first()).toHaveCount(0)
  expect(state.models.some(model => model.id === 11)).toBe(false)
  expect(state.unexpectedRequests).toEqual([])
})

test('原模型参数显式读取、回填和方案值对照', async ({ page }, testInfo) => {
  const state = await installMockBackend(page)
  await openWorkspace(page)
  await activateModel(page, '井筒演示模型')
  const preview = page.getByRole('region', { name: '原模型参数', exact: true })
  await expect(preview).toContainText('暂无可用原值')
  expect(state.validationRequests).toEqual([])
  state.holdValidation = true
  await preview.getByRole('button', { name: '重新验证并读取' }).click()
  await expect.poll(() => Boolean(state.releaseValidation)).toBe(true)
  await expect(page.getByRole('button', { name: '运行', exact: true })).toBeDisabled()
  state.holdValidation = false
  state.releaseValidation()
  await expect(preview).toContainText('正在验证并读取')
  await expect(page.getByRole('button', { name: '运行', exact: true })).toBeDisabled()
  const well = state.models[0].versions[0]
  well.status = 'READY'
  well.inspection = { schemaVersion: 'pipesim-well-inspection/1', reservoirPressure: { value: 4321.25, unit: 'psia' } }
  await expect(preview).toContainText('4321.25 psia', { timeout: 15000 })
  await expect(page.getByRole('checkbox', { name: '使用地层压力方案' })).not.toBeChecked()
  await preview.getByRole('button', { name: '以原值创建方案' }).click()
  await expect(page.getByRole('spinbutton', { name: '方案地层压力' })).toHaveValue('4321.25')
  await page.getByRole('spinbutton', { name: '方案地层压力' }).fill('4000')
  await expect(page.getByTestId('pressure-scenario-comparison')).toHaveText('原值 4321.25 → 方案值 4000 psia')
  expect(state.createPayloads).toHaveLength(0)
  for (const width of [1440, 1280]) {
    await page.setViewportSize({ width, height: width === 1280 ? 720 : 900 })
    await page.screenshot({ path: testInfo.outputPath(`source-parameters-${width}.png`) })
  }
  await activateModel(page, '管网演示模型')
  await expect(preview).toHaveCount(0)
  await activateModel(page, '井筒演示模型')
  await expect(page.getByRole('checkbox', { name: '使用地层压力方案' })).not.toBeChecked()
  await preview.getByRole('button', { name: '重新验证并读取' }).click()
  await expect(preview).toContainText('正在验证并读取')
  well.status = 'READY'
  well.inspection = { schemaVersion: 'pipesim-well-inspection/1', reservoirPressure: null }
  await expect(preview).toContainText('暂无可用原值', { timeout: 15000 })
  await expect(preview.getByRole('button', { name: '以原值创建方案' })).toHaveCount(0)
})

test('基础气井 PT 压力方案保留运行类型并提交准确快照', async ({ page }) => {
  const historical = { id: 9010, modelId: 11, modelVersionId: 101, projectId: 1, versionNo: 1, study: 'Base Case', runType: 'profile', status: 'FAILED', parameters: { schemaVersion: 'pipesim-well-parameters/1', reservoirPressurePsi: 3500 }, createdAt: '2026-09-15T10:00:00', cancellable: false, events: [], artifacts: [] }
  const state = await installMockBackend(page, { wellHistory: [summary(historical)], runs: [historical] })
  state.models[0].versions[0].modelKind = 'basic_gas'
  await openWorkspace(page)
  await activateModel(page, '井筒演示模型')
  await page.getByRole('button', { name: '载入此方案参数' }).click()
  await expect(page.getByRole('radio', { name: 'PT 剖面', exact: true })).toBeChecked()
  await expect(page.getByRole('spinbutton', { name: '方案地层压力' })).toHaveValue('3500')
  await expect(page.getByText('基础气井方案同时将该值用于 PT 入口压力，沿用桌面端计算方式。')).toBeVisible()
  await page.locator('.run-type-control').getByText('组合运行', { exact: true }).click()
  await expect(page.getByRole('checkbox', { name: '使用地层压力方案' })).not.toBeChecked()
  await page.getByText('使用地层压力方案', { exact: true }).click()
  await page.getByRole('spinbutton', { name: '方案地层压力' }).fill('4000')
  await page.getByRole('button', { name: '运行', exact: true }).click()
  expect(state.createPayloads[0].payload).toEqual({ study: 'Base Case', runType: 'combined', parameters: { schemaVersion: 'pipesim-well-parameters/1', reservoirPressurePsi: 4000 } })
})

test('井筒压力方案提交精确快照，切换运行类型清空编辑', async ({ page }) => {
  const historical = { id: 9000, modelId: 11, modelVersionId: 101, projectId: 1, versionNo: 1, study: 'Base Case', runType: 'nodal', status: 'FAILED', parameters: { schemaVersion: 'pipesim-well-parameters/1', reservoirPressurePsi: 3500 }, createdAt: '2026-09-15T10:00:00', cancellable: false, events: [], artifacts: [] }
  const state = await installMockBackend(page, { wellHistory: [summary(historical)], runs: [historical] })
  await openWorkspace(page)
  await activateModel(page, '井筒演示模型')
  await page.getByRole('radio', { name: '节点分析', exact: true }).check()
  await page.getByRole('button', { name: '载入此方案参数' }).click()
  await expect(page.getByRole('spinbutton', { name: '方案地层压力' })).toHaveValue('3500')
  await page.locator('.run-type-control').getByText('PT 剖面', { exact: true }).click()
  await page.locator('.run-type-control').getByText('节点分析', { exact: true }).click()
  await expect(page.getByRole('checkbox', { name: '使用地层压力方案' })).not.toBeChecked()
  await page.getByText('使用地层压力方案', { exact: true }).click()
  await page.getByRole('spinbutton', { name: '方案地层压力' }).fill('4000')
  await page.getByRole('button', { name: '运行', exact: true }).click()
  await expect(page.getByText('当前结果 #9101：压力方案 · 地层压力 4000 psia')).toBeVisible()
  expect(state.createPayloads[0].payload.parameters).toEqual({ schemaVersion: 'pipesim-well-parameters/1', reservoirPressurePsi: 4000 })
  expect(state.runs.get(9000).parameters.reservoirPressurePsi).toBe(3500)
  await expect(page.getByRole('button', { name: '载入此方案参数' })).toBeDisabled()
})

test('压力字段空值就地提示，纠正后只提交一个准确方案', async ({ page }) => {
  const state = await installMockBackend(page)
  await openWorkspace(page)
  await activateModel(page, '井筒演示模型')
  await page.getByRole('radio', { name: '节点分析', exact: true }).check()
  await page.getByText('使用地层压力方案', { exact: true }).click()
  await page.getByRole('button', { name: '运行', exact: true }).click()
  const fieldError = page.getByTestId('pressure-field-error')
  await expect(fieldError).toContainText('平台编辑范围')
  await expect(page.getByRole('group', { name: '方案地层压力校验' })).toHaveAttribute('aria-invalid', 'true')
  expect(state.createPayloads).toHaveLength(0)
  await expect(page.locator('.el-message')).toHaveCount(0)
  await page.getByRole('spinbutton', { name: '方案地层压力' }).fill('4321.25')
  await page.getByRole('spinbutton', { name: '方案地层压力' }).press('Tab')
  await expect(fieldError).toHaveCount(0)
  await page.getByRole('button', { name: '运行', exact: true }).click()
  expect(state.createPayloads).toEqual([{ versionId: 101, payload: {
    study: 'Base Case', runType: 'nodal', parameters: {
      schemaVersion: 'pipesim-well-parameters/1', reservoirPressurePsi: 4321.25
    }
  } }])
})

test('切换任务或关闭压力方案清除字段错误，不影响原值计算', async ({ page }) => {
  const state = await installMockBackend(page)
  await openWorkspace(page)
  await activateModel(page, '井筒演示模型')
  await page.getByRole('radio', { name: '节点分析', exact: true }).check()
  const enabled = page.getByRole('checkbox', { name: '使用地层压力方案' })
  await page.getByText('使用地层压力方案', { exact: true }).click()
  await page.getByRole('button', { name: '运行', exact: true }).click()
  await expect(page.getByTestId('pressure-field-error')).toBeVisible()
  await page.locator('.run-type-control').getByText('PT 剖面', { exact: true }).click()
  await page.locator('.run-type-control').getByText('节点分析', { exact: true }).click()
  await expect(enabled).not.toBeChecked()
  await page.getByText('使用地层压力方案', { exact: true }).click()
  await expect(page.getByTestId('pressure-field-error')).toHaveCount(0)
  await page.getByRole('button', { name: '运行', exact: true }).click()
  await expect(page.getByTestId('pressure-field-error')).toBeVisible()
  await page.getByText('使用地层压力方案', { exact: true }).click()
  await expect(page.getByTestId('pressure-field-error')).toHaveCount(0)
  await page.getByRole('button', { name: '运行', exact: true }).click()
  expect(state.createPayloads).toEqual([{ versionId: 101, payload: { study: 'Base Case', runType: 'nodal', parameters: null } }])
})

test('PIPESIM 官方任务预检诊断显示井、字段和原始说明', async ({ page }) => {
  const failed = {
    id: 9340, modelId: 11, modelVersionId: 101, projectId: 1, versionNo: 1,
    modelName: '井筒演示模型', study: 'Study 1', runType: 'nodal', status: 'FAILED',
    createdAt: '2026-09-24T10:00:00', elapsedMillis: 10, cancellable: false,
    error: {
      category: 'VALIDATION', code: 'PIPESIM_TASK_VALIDATION_FAILED', retryable: false,
      diagnostics: [{ path: 'Well_1', property: 'OutletPressure', message: 'Pressure is outside the task limits.' }]
    },
    events: [], artifacts: []
  }
  const state = await installMockBackend(page, { wellHistory: [summary(failed)], runs: [failed] })
  await openWorkspace(page)
  await activateModel(page, '井筒演示模型')
  await page.getByRole('tab', { name: '运行记录' }).click()
  await page.getByRole('tabpanel', { name: '运行记录' }).locator('.el-table__row').filter({ hasText: '9340' }).click()
  await expect(page.getByText('官方任务预检发现问题，求解器尚未启动。请先处理下方井/字段诊断，再重新运行。')).toBeVisible()
  await expect(page.getByRole('list', { name: 'PIPESIM 官方预检诊断' })).toContainText('Well_1')
  await expect(page.getByRole('list', { name: 'PIPESIM 官方预检诊断' })).toContainText('OutletPressure')
  await expect(page.getByRole('list', { name: 'PIPESIM 官方预检诊断' })).toContainText('Pressure is outside the task limits.')
  expect(state.unexpectedRequests).toEqual([])
})

test('模型快速切换不会串入旧历史，并按模拟器契约创建运行', async ({ page }) => {
  const oldWellRun = {
    id: 7101,
    projectId: 1,
    modelId: 11,
    modelVersionId: 101,
    modelName: '井筒演示模型',
    versionNo: 1,
    study: 'Base Case',
    runType: 'nodal',
    status: 'TIMED_OUT',
    resultContract: null,
    result: { marker: 'OLD_WELL_RESULT_MUST_NOT_LEAK' },
    error: { category: 'EXECUTION', code: 'OLD_WELL_TIMEOUT', retryable: true },
    createdAt: '2026-09-13T08:00:00',
    elapsedMillis: 600000,
    cancellable: false,
    events: [],
    artifacts: []
  }
  const state = await installMockBackend(page, {
    wellHistory: [summary(oldWellRun)],
    runs: [oldWellRun],
    delayedWellRunId: oldWellRun.id
  })
  await openWorkspace(page)

  await page.getByText('井筒演示模型', { exact: true }).first().dblclick()
  await page.getByRole('tab', { name: '运行记录' }).click()
  await expect(page.getByText('7101', { exact: true })).toBeVisible()
  await expect(page.getByText('已超时', { exact: true }).first()).toBeVisible()
  await page.getByText('ECLIPSE演示模型', { exact: true }).first().dblclick()
  await expect(page.getByRole('heading', { name: 'ECLIPSE演示模型', exact: true }).last()).toBeVisible()
  state.releaseWellDetail?.()
  await expect(page.getByText('运行类型：ECLIPSE')).toBeVisible()
  await expect(page.getByText('Study：不适用')).toBeVisible()
  await expect(page.getByText('7101', { exact: true })).toHaveCount(0)
  await expect(page.getByText('已超时', { exact: true })).toHaveCount(0)
  await expect(page.getByText('OLD_WELL_RESULT_MUST_NOT_LEAK')).toHaveCount(0)
  await page.getByRole('tab', { name: '运行记录' }).click()
  await expect(page.getByText('该模型版本暂无运行记录')).toBeVisible()

  await page.getByRole('button', { name: '运行', exact: true }).click()
  await expect.poll(() => state.createPayloads.length).toBe(1)
  expect(state.createPayloads[0]).toEqual({
    versionId: 201,
    payload: { study: null, runType: 'eclipse', parameters: null }
  })

  const terminalHistory = page.waitForResponse(response =>
    response.url().includes('/api/software-integration/model-versions/201/runs') && response.request().method() === 'GET')
  state.runs.get(9201).status = 'SUCCEEDED'
  state.runs.get(9201).cancellable = false
  state.runs.get(9201).elapsedMillis = 2500
  await expect(page.getByText('运行成功', { exact: true }).first()).toBeVisible({ timeout: 5000 })
  await terminalHistory
  await expect(page.getByRole('button', { name: '运行', exact: true })).toBeEnabled()
  await expect(page.getByRole('button', { name: '取消', exact: true })).toBeDisabled()

  await page.getByText('井筒演示模型', { exact: true }).first().dblclick()
  await expect(page.getByRole('heading', { name: '井筒演示模型', exact: true }).last()).toBeVisible()
  await page.getByRole('button', { name: '运行', exact: true }).click()
  await expect.poll(() => state.createPayloads.length).toBe(2)
  expect(state.createPayloads[1]).toEqual({
    versionId: 101,
    payload: { study: 'Base Case', runType: 'nodal', parameters: null }
  })
  expect(state.unexpectedRequests).toEqual([])
})

test('活动任务可取消，轮询读取取消后的持久终态', async ({ page }) => {
  const state = await installMockBackend(page)
  await openWorkspace(page)
  await activateModel(page, '井筒演示模型')
  await page.getByRole('button', { name: '运行', exact: true }).click()
  await expect(page.getByText('准备模型', { exact: true }).first()).toBeVisible()

  await page.getByRole('button', { name: '取消', exact: true }).click()
  await expect(page.getByText('正在取消', { exact: true }).first()).toBeVisible()
  state.runs.get(9101).status = 'CANCELLED'
  state.runs.get(9101).elapsedMillis = 3200
  state.histories.set(101, [summary(state.runs.get(9101))])
  await expect(page.getByText('已取消', { exact: true }).first()).toBeVisible({ timeout: 5000 })
  await expect(page.getByText('任务已取消。确认 Study 后可重新提交运行。')).toBeVisible()
  expect(state.unexpectedRequests).toEqual([])
})

test('ECLIPSE 页面卸载再进入后恢复持久活动任务并继续轮询', async ({ page }) => {
  const retained = {
    id: 8201,
    projectId: 1,
    modelId: 22,
    modelVersionId: 201,
    modelName: 'ECLIPSE演示模型',
    versionNo: 1,
    study: null,
    runType: 'eclipse',
    status: 'RUNNING_ECLIPSE',
    createdAt: '2026-09-13T09:00:00',
    elapsedMillis: 5000,
    cancellable: true,
    events: [],
    artifacts: []
  }
  const state = await installMockBackend(page, { eclipseHistory: [summary(retained)], runs: [retained] })
  await openWorkspace(page)
  await activateModel(page, 'ECLIPSE演示模型')
  await page.getByRole('tab', { name: '运行记录' }).click()
  await expect(page.getByRole('tabpanel', { name: '运行记录' }).getByText('8201', { exact: true })).toBeVisible()
  expect(state.historyReads.get(201)).toBe(1)

  await page.getByText('解析融合', { exact: true }).first().click()
  await expect(page).toHaveURL(/\/ipr(?:\?|$)/)
  await expect(page.getByText('井筒演示模型', { exact: true })).toHaveCount(0)
  const readsBeforeRemount = state.runReads.get(8201) || 0
  const historyBeforeRemount = state.historyReads.get(201) || 0
  await page.getByText('软件集成', { exact: true }).first().click()
  await expect(page).toHaveURL(/workspace=software-integration/)
  await expect(page.getByRole('heading', { name: 'ECLIPSE演示模型', exact: true }).last()).toBeVisible()
  await page.getByRole('tab', { name: '运行记录' }).click()
  await expect(page.getByRole('tabpanel', { name: '运行记录' }).getByText('8201', { exact: true })).toBeVisible()
  expect(state.historyReads.get(201)).toBe(historyBeforeRemount + 1)
  await expect.poll(() => state.runReads.get(8201) || 0).toBe(readsBeforeRemount + 1)

  state.heldRunId = 8201
  const pollRequest = page.waitForRequest(request => request.url().endsWith('/api/software-integration/runs/8201'))
  await pollRequest
  await expect.poll(() => typeof state.releaseHeldRun).toBe('function')
  retained.status = 'SUCCEEDED'
  retained.cancellable = false
  retained.elapsedMillis = 9000
  state.histories.set(201, [summary(retained)])
  const terminalHistory = page.waitForResponse(response =>
    response.url().includes('/api/software-integration/model-versions/201/runs') && response.request().method() === 'GET')
  state.releaseHeldRun?.()
  await expect(page.getByText('运行成功', { exact: true }).first()).toBeVisible({ timeout: 5000 })
  await terminalHistory
  await expect(page.getByRole('button', { name: '运行', exact: true })).toBeEnabled()
  await expect(page.getByRole('button', { name: '取消', exact: true })).toBeDisabled()
  await expect(page.waitForRequest(request => request.url().endsWith('/api/software-integration/runs/8201'), { timeout: 2300 })).rejects.toThrow()
  expect(state.unexpectedRequests).toEqual([])
})

test('单模拟器不可用只禁用对应新运行，历史与安全状态仍可展示', async ({ page }) => {
  const historical = {
    id: 8301,
    projectId: 1,
    modelId: 22,
    modelVersionId: 201,
    modelName: 'ECLIPSE演示模型',
    versionNo: 1,
    study: null,
    runType: 'eclipse',
    status: 'SUCCEEDED',
    createdAt: '2026-09-13T07:00:00',
    elapsedMillis: 8000,
    cancellable: false,
    events: [],
    artifacts: []
  }
  const capabilities = availableCapabilities()
  capabilities.eclipse100 = {
    version: null,
    status: 'UNAVAILABLE',
    reasonCode: 'ECLIPSE_UNAVAILABLE',
    runTasks: [],
    maxTimeoutSeconds: 1800,
    path: 'C:\\sensitive\\eclrun.exe',
    license: 'SECRET-LICENSE-CONTENT',
    workerId: 'worker-private-17',
    generationId: 'generation-private-42',
    activeRunId: 999999
  }
  const state = await installMockBackend(page, {
    capabilities,
    eclipseHistory: [summary(historical)],
    runs: [historical]
  })
  await openWorkspace(page)
  await activateModel(page, 'ECLIPSE演示模型')

  expect(state.capabilityReads).toBeGreaterThan(0)
  await expect(page.getByRole('button', { name: '运行', exact: true })).toBeDisabled()
  const disabledReason = page.locator('.inline-notice').filter({ hasText: '新运行不可用：ECLIPSE 执行环境不可用' })
  await expect(disabledReason).toBeVisible()
  await expect(disabledReason).toHaveAttribute('title', 'ECLIPSE 执行环境不可用；历史运行与已有结果仍可查看。')
  await expect(page.getByRole('region', { name: '模拟器执行能力' })).toHaveCount(0)
  await expect(page.getByText('暂时不能创建新运行', { exact: true })).toHaveCount(0)
  await expect(page.locator('.model-readiness, .run-provenance, .workflow-guide')).toHaveCount(0)
  await page.getByRole('tab', { name: '运行记录' }).click()
  const eclipseHistory = page.getByRole('tabpanel', { name: '运行记录' })
  await expect(eclipseHistory.getByText('8301', { exact: true })).toBeVisible()
  await expect(eclipseHistory.getByText('成功', { exact: true })).toBeVisible()
  await page.getByRole('tab', { name: 'ECLIPSE 结果' }).click()
  await expect(page.getByText('本次 ECLIPSE 运行已成功结束，但可验证运行结果不可用')).toBeVisible()

  const bodyText = await page.locator('body').innerText()
  for (const secret of ['C:\\sensitive\\eclrun.exe', 'SECRET-LICENSE-CONTENT', 'worker-private-17', 'generation-private-42', '999999']) {
    expect(bodyText).not.toContain(secret)
  }

  await activateModel(page, '井筒演示模型')
  await expect(page.getByRole('button', { name: '运行', exact: true })).toBeEnabled()
  expect(state.unexpectedRequests).toEqual([])
})

test('Worker 忙碌时禁用所有新运行但不影响历史读取', async ({ page }) => {
  const historical = {
    id: 8401,
    projectId: 1,
    modelId: 11,
    modelVersionId: 101,
    modelName: '井筒演示模型',
    versionNo: 1,
    study: 'Base Case',
    runType: 'nodal',
    status: 'CANCELLED',
    createdAt: '2026-09-13T06:00:00',
    elapsedMillis: 3000,
    cancellable: false,
    events: [],
    artifacts: []
  }
  const capabilities = availableCapabilities()
  capabilities.worker = { status: 'AVAILABLE', idle: false, reasonCode: 'WORKER_BUSY' }
  const state = await installMockBackend(page, {
    capabilities,
    wellHistory: [summary(historical)],
    runs: [historical]
  })
  await openWorkspace(page)
  await activateModel(page, '井筒演示模型')
  expect(state.capabilityReads).toBeGreaterThan(0)
  await expect(page.getByRole('button', { name: '运行', exact: true })).toBeDisabled()
  const busyReason = page.locator('.inline-notice').filter({ hasText: '新运行不可用：Worker 正在执行其他任务' })
  await expect(busyReason).toBeVisible()
  await expect(busyReason).toHaveAttribute('title', 'Worker 正在执行其他任务；历史运行与已有结果仍可查看。')
  await expect(page.getByRole('region', { name: '模拟器执行能力' })).toHaveCount(0)
  await expect(page.getByText('暂时不能创建新运行', { exact: true })).toHaveCount(0)
  await expect(page.locator('.model-readiness, .run-provenance, .workflow-guide')).toHaveCount(0)
  await page.getByRole('tab', { name: '运行记录' }).click()
  await expect(page.getByRole('tabpanel', { name: '运行记录' }).getByText('8401', { exact: true })).toBeVisible()

  await activateModel(page, 'ECLIPSE演示模型')
  await expect(page.getByRole('button', { name: '运行', exact: true })).toBeDisabled()
  expect(state.unexpectedRequests).toEqual([])
})

test('ECLIPSE 使用单一标题和版本控制并提供检查与结果页签', async ({ page }) => {
  const state = await installMockBackend(page)
  await openWorkspace(page)
  await activateModel(page, 'ECLIPSE演示模型')

  await expect(page.getByRole('heading', { name: 'ECLIPSE演示模型', exact: true })).toHaveCount(1)
  await expect(page.getByText('模型版本', { exact: true })).toHaveCount(1)
  await expect(page.getByRole('tab', { name: 'ECLIPSE 结果' })).toBeVisible()
  await expect(page.getByRole('tab', { name: 'DATA 检查' })).toBeVisible()
  await page.getByRole('tab', { name: 'DATA 检查' }).click()
  await expect(page.getByRole('definition').filter({ hasText: 'DEMO.DATA' }).first()).toBeVisible()
  await expect(page.getByRole('heading', { name: '工程依赖清单', exact: true })).toBeVisible()
  await expect(page.getByText('include/grid.inc', { exact: true })).toBeVisible()
  await expect(page.getByText('a'.repeat(64), { exact: true })).toBeVisible()
  await page.getByRole('tab', { name: 'ECLIPSE 结果' }).click()
  await expect(page.getByText('尚无已验证真实计算结果')).toBeVisible()
  expect(state.unexpectedRequests).toEqual([])
})

test('ECLIPSE 部分成功诊断保留原文并按模式提供审阅分类', async ({ page }) => {
  const run = {
    id: 9401, projectId: 1, modelId: 22, modelVersionId: 201, modelName: 'ECLIPSE演示模型', versionNo: 1,
    study: null, runType: 'eclipse', status: 'PARTIAL_SUCCEEDED', resultContract: 'VALID_PARTIAL', parameters: null,
    result: {
      schemaVersion: 'eclipse-summary-result/1', modelKind: 'eclipse_100', runTask: 'eclipse', resultContract: 'VALID_PARTIAL',
      eclEnd: { comments: 1, warnings: 3, problems: 1, errors: 0, bugs: 0 }, outputFiles: [], summary: null,
      grid: null, fieldIndex: { schemaVersion: 'eclipse-binary-field-index/1', files: [] },
      messages: [
        { severity: 'PROBLEM', category: 'SOLVER', code: 'ECLIPSE_PROBLEM', message: 'POTENTIAL IN WELL PROD NOT CONVERGED IN 1 X 8 ITERATIONS.', retryable: false },
        { severity: 'WARNING', category: 'SOLVER', code: 'ECLIPSE_WARNING', message: 'LINEAR EQUATION CONVERGENCE PROBLEMS WERE EXPERIENCED THIS TIME-STEP.', retryable: true },
        { severity: 'WARNING', category: 'SOLVER', code: 'ECLIPSE_WARNING', message: 'THE MAXIMUM PRESSURE ENTERED IN THE PVT TABLES FOR THE OIL PHASE IS 7000.700.', retryable: false },
        { severity: 'WARNING', category: 'SOLVER', code: 'ECLIPSE_WARNING', message: 'NONLINEAR ITERATION LIMIT REACHED; TIME-STEP WAS REDUCED.', retryable: true }
      ]
    },
    events: [], artifacts: []
  }
  const state = await installMockBackend(page, { eclipseHistory: [summary(run)], runs: [run] })
  await openWorkspace(page)
  await activateModel(page, 'ECLIPSE演示模型')
  const diagnostics = page.locator('#eclipse-diagnostics-result')
  await expect(page.getByRole('navigation', { name: 'ECLIPSE 结果区域' })).toContainText('4 条消息')
  await expect(diagnostics.locator('.diagnostic-counts')).toBeVisible()
  await expect(diagnostics.locator('.diagnostic-counts')).toContainText('Problems 1')
  await expect(diagnostics.locator('details')).not.toHaveAttribute('open', '')
  await expect(diagnostics.getByText('POTENTIAL IN WELL PROD NOT CONVERGED IN 1 X 8 ITERATIONS.', { exact: true })).not.toBeVisible()
  await diagnostics.locator('summary').click()
  await expect(diagnostics.getByText('POTENTIAL IN WELL PROD NOT CONVERGED IN 1 X 8 ITERATIONS.', { exact: true })).toBeVisible()
  await expect(diagnostics).toContainText('井势与井求解 · 1 条')
  await expect(diagnostics).toContainText('线性方程收敛 · 1 条')
  await expect(diagnostics).toContainText('压力与 PVT 边界 · 1 条')
  await expect(diagnostics).toContainText('非线性与时间步 · 1 条')
  await expect(diagnostics).toContainText('POTENTIAL IN WELL PROD NOT CONVERGED')
  await expect(diagnostics).toContainText('不会自动修改模型')
  expect(state.unexpectedRequests).toEqual([])
})

test('ECLIPSE 开井产油目标显示原值单位并绑定记录提交', async ({ page }) => {
  const baseline = { id: 9391, projectId: 1, modelId: 22, modelVersionId: 201, modelName: 'ECLIPSE演示模型', versionNo: 1,
    study: null, runType: 'eclipse', status: 'SUCCEEDED', resultContract: 'VALID_FULL', parameters: null,
    result: { eclEnd: { comments: 0, warnings: 0, problems: 0, errors: 0, bugs: 0 }, outputFiles: [] }, events: [], artifacts: [] }
  const state = await installMockBackend(page, { eclipseHistory: [summary(baseline)], runs: [baseline], eclipseInspection: {
    schemaVersion: 'eclipse-data-inspection/4', caseName: 'CASE.DATA', unitSystem: 'FIELD', sections: inspection.sections,
    phases: ['OIL', 'WATER', 'GAS'], dimensions: { nx: 1, ny: 1, nz: 1 }, wellNames: ['P1', 'P2'],
    packageFiles: [{ relativePath: 'CASE.DATA', sizeBytes: 100, sha256: 'a'.repeat(64) }],
    scheduleTimeline: [{ kind: 'TSTEP', steps: ['365'], sourceFile: 'CASE.DATA', lineNumber: 10 }],
    scheduleMetadata: { wells: [{ name: 'P1', group: 'G', sourceFile: 'CASE.DATA', lineNumber: 3 }, { name: 'P2', group: 'G', sourceFile: 'CASE.DATA', lineNumber: 4 }], groups: [], completions: [], records: [
      { keyword: 'WCONPROD', values: ['P1', 'OPEN', 'ORAT', '20000', '4*', '2500'], sourceFile: 'CASE.DATA', lineNumber: 6 },
      { keyword: 'WCONPROD', values: ['P2', 'OPEN', 'ORAT', '15000', '4*', '2500'], sourceFile: 'CASE.DATA', lineNumber: 7 }] }
  } })
  await openWorkspace(page)
  await activateModel(page, 'ECLIPSE演示模型')
  const editor = page.getByRole('region', { name: 'ECLIPSE WCONPROD 预测初始段方案' })
  await editor.getByText('创建开井产油目标方案（可下载工程）', { exact: true }).click()
  await expect(editor).toContainText('原产油目标：20000 STB/day')
  await expect(editor.getByRole('combobox', { name: 'ECLIPSE WCONPROD 方案状态' })).toHaveCount(0)
  const wellSelect = editor.getByRole('combobox', { name: 'ECLIPSE WCONPROD 方案井' })
  await wellSelect.focus()
  await wellSelect.press('ArrowDown')
  await page.getByRole('option', { name: 'P2', exact: true }).click()
  await expect(editor).toContainText('原产油目标：15000 STB/day')
  await expect(editor.getByRole('spinbutton', { name: 'ECLIPSE WCONPROD ORAT 目标值' })).toHaveValue('13500')
  await wellSelect.focus()
  await wellSelect.press('ArrowDown')
  await page.getByRole('option', { name: 'P1', exact: true }).click()
  await editor.getByRole('spinbutton', { name: 'ECLIPSE WCONPROD ORAT 目标值' }).fill('10000')
  await page.getByRole('button', { name: '运行', exact: true }).click()
  await expect.poll(() => state.createPayloads.length).toBe(1)
  expect(state.createPayloads[0].payload.parameters).toEqual({ schemaVersion: 'eclipse-production-target-parameters/1', baselineRunId: 9391,
    well: 'P1', phase: 'INITIAL', sourceFile: 'CASE.DATA', lineNumber: 6, unit: 'STB/day', originalOilRate: 20000, targetOilRate: 10000 })
  expect(state.unexpectedRequests).toEqual([])
})

test('ECLIPSE 生产流量编辑切换井后重绑模式、单位和原值', async ({ page }) => {
  const baseline = { id: 9421, projectId: 1, modelId: 22, modelVersionId: 201, modelName: 'ECLIPSE演示模型', versionNo: 1,
    study: null, runType: 'eclipse', status: 'SUCCEEDED', resultContract: 'VALID_FULL', parameters: null,
    result: { eclEnd: { comments: 0, warnings: 0, problems: 0, errors: 0, bugs: 0 }, outputFiles: [] }, events: [], artifacts: [] }
  const state = await installMockBackend(page, { eclipseHistory: [summary(baseline)], runs: [baseline], eclipseInspection: {
    schemaVersion: 'eclipse-data-inspection/4', caseName: 'CASE.DATA', unitSystem: 'FIELD', sections: inspection.sections,
    phases: ['OIL', 'WATER', 'GAS'], dimensions: { nx: 1, ny: 1, nz: 1 }, wellNames: ['P1', 'P2'],
    packageFiles: [{ relativePath: 'CASE.DATA', sizeBytes: 100, sha256: 'a'.repeat(64) }],
    scheduleTimeline: [{ kind: 'TSTEP', steps: ['5'], sourceFile: 'CASE.DATA', lineNumber: 10 }],
    scheduleMetadata: { wells: [{ name: 'P1' }, { name: 'P2' }], groups: [], completions: [], records: [
      { keyword: 'WCONPROD', values: ['P1', 'OPEN', 'WRAT', '1*', '1000'], sourceFile: 'CASE.DATA', lineNumber: 6 },
      { keyword: 'WCONPROD', values: ['P2', 'OPEN', 'GRAT', '2*', '20000'], sourceFile: 'CASE.DATA', lineNumber: 7 }] }
  } })
  await openWorkspace(page); await activateModel(page, 'ECLIPSE演示模型')
  const editor = page.getByRole('region', { name: 'ECLIPSE 生产流量目标方案', exact: true })
  await editor.getByText('创建产水／产气／总液目标方案', { exact: true }).click()
  const target = editor.getByRole('spinbutton', { name: 'ECLIPSE 新生产流量目标' })
  await target.fill('500')
  const well = editor.getByRole('combobox', { name: 'ECLIPSE 流量方案井' })
  await well.focus(); await well.press('ArrowDown')
  await page.getByRole('option', { name: 'P2 · GRAT', exact: true }).click()
  await expect(editor).toContainText('原目标：20000 Mscf/day')
  await expect(target).toHaveValue('16000')
  await target.fill('10000')
  await page.getByRole('button', { name: '运行', exact: true }).click()
  await expect.poll(() => state.createPayloads.length).toBe(1)
  expect(state.createPayloads[0].payload.parameters).toEqual({ schemaVersion: 'eclipse-production-control-parameters/1', baselineRunId: 9421,
    well: 'P2', phase: 'INITIAL', sourceFile: 'CASE.DATA', lineNumber: 7, controlMode: 'GRAT', originalTargetRate: 20000, targetRate: 10000, unit: 'Mscf/day' })
  expect(state.unexpectedRequests).toEqual([])
})

function groupProductionFixture() {
  const f = newDatesFixture(), members = ['P1','P2']
  f.deck.schemaVersion = 'eclipse-data-inspection/6'; f.deck.vfpTables = []
  f.deck.wellNames = members
  f.deck.scheduleMetadata.wells = members.map(name => ({ name, group:'G', sourceFile:'CASE.DATA', lineNumber:8 }))
  f.deck.groupControls = [{ group:'G', sourceFile:'CASE.DATA', lineNumber:14, controlMode:'ORAT', unit:'STB/day', originalTargetRate:60000, members:members.map(well=>({well,bhpLimit:2500})) }]
  const output = (g,b) => [['GOPR','G',g,'STB/DAY'],...members.map(w=>['WOPR',w,g/2,'STB/DAY']),...members.map(w=>['WBHP',w,b,'PSIA'])].map(([keyword,objectName,value,unit])=>({keyword,objectName,unit,points:[{timeDays:1,value}]}))
  f.baseline.result.summary.series = output(48000,2500)
  f.parameters = { schemaVersion:'eclipse-group-production-parameters/1',baselineRunId:9461,group:'G',phase:'INITIAL',sourceFile:'CASE.DATA',lineNumber:14,controlMode:'ORAT',unit:'STB/day',originalTargetRate:60000,targetRate:30000,members }
  f.derived.parameters = f.parameters; f.derived.result.summary.series = output(30000,3370)
  return f
}

function groupInjectionFixture() {
  const f = groupProductionFixture(), members = ['P1','P2']
  f.deck.schemaVersion = 'eclipse-data-inspection/7'; delete f.deck.groupControls
  f.deck.phases = ['WATER']
  f.deck.injectionGroupControls = [{group:'G',sourceFile:'CASE.DATA',lineNumber:14,controlMode:'RATE',injectionPhase:'WATER',unit:'STB/day',originalTargetRate:2,members:members.map(well=>({well,bhpLimit:6000}))}]
  const output = (g,b) => [['GWIR','G',g,'STB/DAY'],...members.map(w=>['WWIR',w,g/2,'STB/DAY']),...members.map(w=>['WBHP',w,b,'PSIA'])].map(([keyword,objectName,value,unit])=>({keyword,objectName,unit,points:[{timeDays:1,value}]}))
  f.baseline.result.summary.series = output(2,5800)
  f.parameters = {schemaVersion:'eclipse-group-injection-parameters/1',baselineRunId:9461,group:'G',phase:'INITIAL',sourceFile:'CASE.DATA',lineNumber:14,controlMode:'RATE',injectionPhase:'WATER',unit:'STB/day',originalTargetRate:2,targetRate:1,members}
  f.derived.parameters = f.parameters; f.derived.result.summary.series = output(1,5100)
  return { ...f, output }
}

test('ECLIPSE 注水组编辑器提交目标、注入相与完整成员',async({page})=>{
  const f=groupInjectionFixture(),state=await installMockBackend(page,{eclipseInspection:f.deck,eclipseHistory:[summary(f.baseline)],runs:[f.baseline]})
  await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型')
  await page.getByRole('tab',{name:'DATA 检查'}).click()
  await expect(page.getByRole('heading',{name:'已有注水组目标',exact:true})).toBeVisible()
  await page.getByRole('tab',{name:'ECLIPSE 结果'}).click()
  await page.getByText('调整注入组总注水目标',{exact:true}).click()
  const panel=page.getByRole('region',{name:'ECLIPSE 注入组控方案'})
  await expect(panel).toContainText('P1 BHP≤6000')
  await panel.getByRole('spinbutton',{name:'ECLIPSE 组总注水目标'}).fill('1')
  await page.getByRole('button',{name:'运行',exact:true}).click()
  await expect.poll(()=>state.createPayloads.length).toBe(1)
  expect(state.createPayloads[0].payload.parameters).toEqual(f.parameters)
  expect(state.unexpectedRequests).toEqual([])
})
for(const fault of ['success','limited','missing'])test(`ECLIPSE 注水组结果 ${fault} 刷新恢复`,async({page})=>{
  const f=groupInjectionFixture()
  if(fault==='limited'){f.derived.parameters={...f.parameters,targetRate:10};f.derived.result.summary.series=f.output(2.345,6000)}
  if(fault==='missing')f.derived.result.summary.series.pop()
  const state=await installMockBackend(page,{eclipseInspection:f.deck,eclipseHistory:[summary(f.derived),summary(f.baseline)],runs:[f.derived,f.baseline]})
  await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型')
  const check=async()=>{
    const panel=page.getByRole('region',{name:'ECLIPSE Schedule 方案验收'})
    await expect(panel).toContainText('GCONINJE 注入组控方案验收')
    await expect(panel).toContainText(fault==='success' ? '组总注水达标，已验证各井分配与压力上限' : fault==='limited' ? '注水组目标未达标：各井已触及 BHP 上限' : '缺少同时间的 GWIR、WWIR 或 BHP，无法验证注入分配')
    await expect(panel.getByLabel('ECLIPSE 注水组总量核对',{exact:true})).toContainText('各井 WWIR 合计')
  }
  await check();await page.reload();await activateModel(page,'ECLIPSE演示模型');await check()
  expect(state.unexpectedRequests).toEqual([])
})
test('ECLIPSE 生产组编辑器提交总目标和真实井归属', async({page})=>{
  const f=groupProductionFixture(),state=await installMockBackend(page,{eclipseInspection:f.deck,eclipseHistory:[summary(f.baseline)],runs:[f.baseline]})
  await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型')
  await page.getByText('调整生产组总产油目标',{exact:true}).click()
  const panel=page.getByRole('region',{name:'ECLIPSE 生产组控方案'})
  await expect(panel).toContainText('P1 BHP≥2500')
  await panel.getByRole('spinbutton',{name:'ECLIPSE 组总产油目标'}).fill('30000')
  await page.getByRole('button',{name:'运行',exact:true}).click()
  await expect.poll(()=>state.createPayloads.length).toBe(1)
  expect(state.createPayloads[0].payload.parameters).toEqual(f.parameters)
})
for(const fault of ['success','limited','missing'])test(`ECLIPSE 组目标与井分配 ${fault} 刷新恢复`,async({page})=>{
  const f=groupProductionFixture()
  if(fault==='limited'){f.derived.parameters={...f.parameters,targetRate:80000};f.derived.result.summary=structuredClone(f.baseline.result.summary)}
  if(fault==='missing')f.derived.result.summary.series.pop()
  const state=await installMockBackend(page,{eclipseInspection:f.deck,eclipseHistory:[summary(f.derived),summary(f.baseline)],runs:[f.derived,f.baseline]})
  await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型')
  const check=async()=>{
    const panel=page.getByRole('region',{name:'ECLIPSE Schedule 方案验收'})
    await expect(panel).toContainText('GCONPROD 生产组控方案验收')
    await expect(panel).toContainText(fault==='success' ? '组总产油达标，已验证各井分配与压力下限' : fault==='limited' ? '组目标未达标：各井已触及 BHP 下限' : '缺少同时间的组、井流量或 BHP，无法验证分配')
    if(fault==='success')await expect(panel.getByLabel('ECLIPSE 组内各井分配',{exact:true})).toContainText('3370')
  }
  await check();await page.reload();await activateModel(page,'ECLIPSE演示模型');await check()
  expect(state.unexpectedRequests).toEqual([])
})
function groupGasInjectionFixture() {
  const f = groupProductionFixture(), members = ['P1','P2']
  f.deck.schemaVersion = 'eclipse-data-inspection/8'; delete f.deck.groupControls
  f.deck.phases = ['GAS']
  f.deck.gasInjectionGroupControls = [{group:'G',sourceFile:'CASE.DATA',lineNumber:14,controlMode:'RATE',injectionPhase:'GAS',unit:'Mscf/day',originalTargetRate:2,members:members.map(well=>({well,bhpLimit:6000}))}]
  const output = (g,b) => [['GGIR','G',g,'MSCF/DAY'],...members.map(w=>['WGIR',w,g/2,'MSCF/DAY']),...members.map(w=>['WBHP',w,b,'PSIA'])].map(([keyword,objectName,value,unit])=>({keyword,objectName,unit,points:[{timeDays:1,value}]}))
  f.baseline.result.summary.series = output(2,5800)
  f.parameters = {schemaVersion:'eclipse-group-gas-injection-parameters/1',baselineRunId:9461,group:'G',phase:'INITIAL',sourceFile:'CASE.DATA',lineNumber:14,controlMode:'RATE',injectionPhase:'GAS',unit:'Mscf/day',originalTargetRate:2,targetRate:1,members}
  f.derived.parameters = f.parameters; f.derived.result.summary.series = output(1,5100)
  return { ...f, output }
}

test('ECLIPSE 注气组编辑器提交目标、注入相与完整成员',async({page})=>{
  const f=groupGasInjectionFixture(),state=await installMockBackend(page,{eclipseInspection:f.deck,eclipseHistory:[summary(f.baseline)],runs:[f.baseline]})
  await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型')
  await page.getByRole('tab',{name:'DATA 检查'}).click()
  await expect(page.getByRole('heading',{name:'已有注气组目标',exact:true})).toBeVisible()
  await page.getByRole('tab',{name:'ECLIPSE 结果'}).click()
  await page.getByText('调整注入组总注气目标',{exact:true}).click()
  const panel=page.getByRole('region',{name:'ECLIPSE 注气组控方案'})
  await expect(panel).toContainText('P1 BHP≤6000')
  await panel.getByRole('spinbutton',{name:'ECLIPSE 组总注气目标'}).fill('1')
  await page.getByRole('button',{name:'运行',exact:true}).click()
  await expect.poll(()=>state.createPayloads.length).toBe(1)
  expect(state.createPayloads[0].payload.parameters).toEqual(f.parameters)
  expect(state.unexpectedRequests).toEqual([])
})
for(const fault of ['success','limited','partial','missing'])test(`ECLIPSE 注气组结果 ${fault} 刷新恢复`,async({page})=>{
  const f=groupGasInjectionFixture()
  if(fault==='limited'){f.derived.parameters={...f.parameters,targetRate:10};f.derived.result.summary.series=f.output(2.345,6000)}
  if(fault==='partial'){
    const early=f.output(0.5,6000)
    f.derived.result.summary.series.forEach((s,index)=>{
      s.points[0].timeDays=2; s.points.unshift(early[index].points[0])
      f.baseline.result.summary.series[index].points.push({...f.baseline.result.summary.series[index].points[0],timeDays:2})
    })
  }
  if(fault==='missing')f.derived.result.summary.series.pop()
  const state=await installMockBackend(page,{eclipseInspection:f.deck,eclipseHistory:[summary(f.derived),summary(f.baseline)],runs:[f.derived,f.baseline]})
  await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型')
  const check=async()=>{
    const panel=page.getByRole('region',{name:'ECLIPSE Schedule 方案验收'})
    await expect(panel).toContainText('GCONINJE 注气组控方案验收')
    await expect(panel).toContainText(fault==='success' ? '组总注气达标，已验证各井分配与压力上限' : fault==='limited' ? '注气组目标未达标：各井已触及 BHP 上限' : fault==='partial' ? '注气组部分时刻未达标：各井已触及 BHP 上限' : '缺少同时间的 GGIR、WGIR 或 BHP，无法验证注入分配')
    await expect(panel.getByLabel('ECLIPSE 注气组总量核对',{exact:true})).toContainText('各井 WGIR 合计')
  }
  await check();await page.reload();await activateModel(page,'ECLIPSE演示模型');await check()
  expect(state.unexpectedRequests).toEqual([])
})

function tstepFixture() {
  const f = newDatesFixture()
  f.deck.scheduleTimeline = [7,12].map(lineNumber => ({ kind: 'TSTEP', sourceFile: 'CASE.DATA', lineNumber, steps: Array(10).fill('5') }))
  const rate = times => ({ keyword:'WOPR', objectName:'P1', unit:'STB/DAY', points:times.map(timeDays=>({timeDays,value:100})) })
  f.baseline.result.summary.series = [rate([0,5,10,15,20,25,30,35,40,45,50,55,100])]
  f.parameters = { schemaVersion:'eclipse-tstep-parameters/1',baselineRunId:9461,sourceFile:'CASE.DATA',lineNumber:7,unit:'day',originalSteps:Array(10).fill(5),targetSteps:[1,4,5,10,30] }
  f.derived.parameters = f.parameters
  f.derived.result.summary.series = [rate([0,1,5,10,20,50,55,100])]
  return f
}
test('ECLIPSE TSTEP 编辑器保持总时长并提交正确源记录', async ({page}) => {
  const f=tstepFixture(),state=await installMockBackend(page,{eclipseInspection:f.deck,eclipseHistory:[summary(f.baseline)],runs:[f.baseline]})
  await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型')
  await page.getByText('调整 TSTEP 报告间隔',{exact:true}).click()
  const panel=page.getByRole('region',{name:'ECLIPSE TSTEP 方案'})
  await expect(panel).toContainText('合计 50 天')
  await panel.getByRole('textbox',{name:'ECLIPSE 新 TSTEP 间隔'}).fill('1 2')
  await page.getByRole('button',{name:'运行',exact:true}).click()
  await expect(page.getByText('请填写不同于原值的正整数天数列表，总时长必须保持不变（最多1000项）',{exact:true})).toBeVisible()
  expect(state.createPayloads).toHaveLength(0)
  await panel.getByRole('textbox',{name:'ECLIPSE 新 TSTEP 间隔'}).fill('1 4 5 10 30')
  await page.getByRole('button',{name:'运行',exact:true}).click()
  await expect.poll(()=>state.createPayloads.length).toBe(1)
  expect(state.createPayloads[0].payload.parameters).toEqual(f.parameters)
})
for (const missing of [false,true]) test(`ECLIPSE TSTEP 报告核对 ${missing ? '缺失' : '齐全'} 与刷新恢复`,async({page})=>{
  const f=tstepFixture()
  if(missing)f.derived.result.summary.series[0].points=f.derived.result.summary.series[0].points.filter(p=>p.timeDays!==1)
  const state=await installMockBackend(page,{eclipseInspection:f.deck,eclipseHistory:[summary(f.derived),summary(f.baseline)],runs:[f.derived,f.baseline]})
  await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型')
  const check=async()=>{
    const panel=page.getByRole('region',{name:'ECLIPSE Schedule 方案验收'})
    await expect(panel).toContainText('TSTEP 报告间隔方案验收')
    await expect(panel).toContainText(missing ? '报告时间点缺失或源绑定失效，需核对输出' : '新报告时间点均已在真实结果中找到')
    await expect(panel.getByLabel('ECLIPSE TSTEP 真实报告时间',{exact:true})).toContainText(missing ? '缺失' : '已找到')
  }
  await check();await page.reload();await activateModel(page,'ECLIPSE演示模型');await check()
  expect(state.unexpectedRequests).toEqual([])
})
function newDatesFixture() {
  const sourceFile = 'CASE.DATA'
  const deck = { schemaVersion: 'eclipse-data-inspection/4', caseName: sourceFile, unitSystem: 'FIELD', sections: inspection.sections, phases: ['OIL', 'WATER', 'GAS'], dimensions: { nx: 1, ny: 1, nz: 1 }, wellNames: ['P1'], packageFiles: [{ relativePath: sourceFile, sizeBytes: 100, sha256: 'a'.repeat(64) }], scheduleTimeline: [6,26].map((day,n) => ({ kind: 'DATES', records: [{ day: String(day), month: 'JAN', year: '1996', time: null }], sourceFile, lineNumber: [16,22][n] })), scheduleMetadata: { wells: [{ name: 'P1', group: 'G', sourceFile, lineNumber: 8 }], groups: [], completions: [{ keyword: 'COMPDAT', well: 'P1', i: '1', j: '1', k1: '1', k2: '1', status: 'OPEN', sourceFile, lineNumber: 11 }], records: [{ keyword: 'WCONPROD', values: ['P1','OPEN','BHP','5*','3000'], sourceFile, lineNumber: 14 }] } }
  const series = values => [['DAY',[1,6,11,21,26],''],['MONTH',[1,1,1,1,1],''],['YEAR',[1996,1996,1996,1996,1996],''],['WOPR',values,'STB/DAY']].map(([keyword, v, unit]) => ({ keyword, objectName: keyword === 'WOPR' ? 'P1' : null, unit, points: v.map((value,n) => ({ timeDays: [0,5,10,20,25][n], value })) }))
  const baseline = { id: 9461, projectId: 1, modelId: 22, modelVersionId: 201, modelName: 'ECLIPSE演示模型', versionNo: 1, study: null, runType: 'eclipse', status: 'SUCCEEDED', resultContract: 'VALID_FULL', parameters: null, events: [], artifacts: [], result: { schemaVersion: 'eclipse-summary-result/1', modelKind: 'eclipse_100', runTask: 'eclipse', resultContract: 'VALID_FULL', eclEnd: { comments:0,warnings:0,problems:0,errors:0,bugs:0 }, outputFiles: [], summary: { series: series([0,100,100,100,100]) } } }
  const parameters = { schemaVersion: 'eclipse-new-dates-parameters/1', baselineRunId: 9461, well: 'P1', sourceFile, beforeLineNumber: 16, beforeDate: '1996-01-06', afterLineNumber: 22, afterDate: '1996-01-26', shutDate: '1996-01-11', openDate: '1996-01-21' }
  const derived = { ...structuredClone(baseline), id: 9462, parameters }
  derived.result.summary.series = series([0,100,100,0,90])
  return { deck, baseline, derived, parameters }
}
test('ECLIPSE 新日期编辑器校验区间并提交源绑定参数', async ({ page }) => {
  const f = newDatesFixture(), state = await installMockBackend(page, { eclipseInspection: f.deck, eclipseHistory: [summary(f.baseline)], runs: [f.baseline] })
  await openWorkspace(page); await activateModel(page, 'ECLIPSE演示模型')
  await page.getByText('新增日期停井／复产', { exact: true }).click()
  const panel = page.getByRole('region', { name: 'ECLIPSE 新增日期方案' })
  await panel.getByRole('textbox', { name: 'ECLIPSE 新停井日期' }).fill('1996-02-30')
  await page.getByRole('button', { name: '运行', exact: true }).click()
  await expect(page.getByText('停井、复产日期必须递增，并严格位于所选两个已有日期之间', { exact: true })).toBeVisible()
  expect(state.createPayloads).toHaveLength(0)
  await panel.getByRole('textbox', { name: 'ECLIPSE 新停井日期' }).fill('1996-01-11')
  await panel.getByRole('textbox', { name: 'ECLIPSE 新复产日期' }).fill('1996-01-21')
  await page.getByRole('button', { name: '运行', exact: true }).click()
  await expect.poll(() => state.createPayloads.length).toBe(1)
  expect(state.createPayloads[0]).toMatchObject({ versionId: 201, payload: { parameters: f.parameters, runType: 'eclipse', study: null } })
  expect(state.unexpectedRequests).toEqual([])
})
for (const fault of ['positive','no-shut','no-reopen','missing-calendar']) {
  test(`ECLIPSE 新日期 ${fault} 真实日历响应与刷新恢复`, async ({ page }) => {
    const f = newDatesFixture()
    if (fault === 'no-shut') f.derived.result.summary.series.at(-1).points[3].value = 100
    if (fault === 'no-reopen') f.derived.result.summary.series.at(-1).points[4].value = 0
    if (fault === 'missing-calendar') f.derived.result.summary.series.shift()
    const state = await installMockBackend(page, { eclipseInspection: f.deck, eclipseHistory: [summary(f.derived),summary(f.baseline)], runs: [f.derived,f.baseline] })
    await openWorkspace(page); await activateModel(page, 'ECLIPSE演示模型')
    const check = async () => {
      const panel = page.getByRole('region', { name: 'ECLIPSE Schedule 方案验收' })
      await expect(panel).toContainText('新增日期停井／复产方案验收')
      await expect(panel).toContainText(fault === 'positive' ? '检测到停井零产量与复产非零响应' : fault === 'missing-calendar' ? '缺少真实日历与 WOPR，需核对报告日期' : '停井或复产响应不足，需审阅控制约束')
      if (fault === 'positive') await expect(panel.getByLabel('ECLIPSE 新日期真实响应', { exact: true })).toContainText('1996-01-21')
    }
    await check(); await page.reload(); await activateModel(page, 'ECLIPSE演示模型'); await check()
    expect(state.unexpectedRequests).toEqual([])
  })
}
function thpFixture() {
  const table = { number: 1, datumDepth: 6900, flowType: 'LIQ', waterFractionType: 'WCT', gasFractionType: 'GOR', alqType: '', unitSystem: 'FIELD', sourceFile: 'CASE.DATA', lineNumber: 5, sha256: 'a'.repeat(64), flowAxis: [1, 3000], thpAxis: [200, 1000], waterFractionAxis: [0, .8], gasFractionAxis: [1, 4], alqAxis: [0] }
  const deck = { schemaVersion: 'eclipse-data-inspection/5', caseName: 'CASE.DATA', unitSystem: 'FIELD', sections: inspection.sections, phases: ['OIL', 'WATER', 'GAS'], dimensions: { nx: 1, ny: 1, nz: 1 }, wellNames: ['HORIZ'], packageFiles: [{ relativePath: 'CASE.DATA', sizeBytes: 100, sha256: 'a'.repeat(64) }], vfpTables: [table], scheduleTimeline: [{ kind: 'TSTEP', steps: ['1', '9'], sourceFile: 'CASE.DATA', lineNumber: 40 }], scheduleMetadata: { wells: [{ name: 'HORIZ', group: 'G', sourceFile: 'CASE.DATA', lineNumber: 20 }], groups: [], completions: [], records: [{ keyword: 'WCONPROD', values: ['HORIZ', 'OPEN', 'THP', '5*', '400', '500', '1'], sourceFile: 'CASE.DATA', lineNumber: 30 }] } }
  const baseline = { id: 9451, projectId: 1, modelId: 22, modelVersionId: 201, modelName: 'ECLIPSE演示模型', versionNo: 1, study: null, runType: 'eclipse', status: 'SUCCEEDED', resultContract: 'VALID_FULL', parameters: null, events: [], artifacts: [], result: { schemaVersion: 'eclipse-summary-result/1', modelKind: 'eclipse_100', runTask: 'eclipse', resultContract: 'VALID_FULL', eclEnd: { comments: 0, warnings: 0, problems: 0, errors: 0, bugs: 0 }, outputFiles: [], summary: { series: [['WTHP', 500, 'PSIA'], ['WBHP', 2100, 'PSIA'], ['WLPR', 2000, 'STB/DAY'], ['WOPR', 1600, 'STB/DAY'], ['WWPR', 400, 'STB/DAY'], ['WGPR', 3200, 'MSCF/DAY']].map(([keyword, value, unit]) => ({ keyword, objectName: 'HORIZ', unit, points: [{ timeDays: 1, value }] })) } } }
  const parameters = { schemaVersion: 'eclipse-thp-control-parameters/1', baselineRunId: 9451, well: 'HORIZ', phase: 'INITIAL', sourceFile: 'CASE.DATA', lineNumber: 30, unit: 'psia', originalThp: 500, targetThp: 700, vfpTableNumber: 1, vfpTableSha256: table.sha256, originalAlq: 0 }
  const derived = { ...structuredClone(baseline), id: 9452, parameters }
  for (const s of derived.result.summary.series) s.points[0].value = { WTHP: 700, WBHP: 2300, WLPR: 1500, WOPR: 1200, WWPR: 300, WGPR: 2400 }[s.keyword]
  return { deck, baseline, derived, parameters }
}
test('ECLIPSE THP 页面显示表范围、提交绑定参数并拒绝未改变压力', async ({ page }) => {
  const f = thpFixture()
  const state = await installMockBackend(page, { eclipseInspection: f.deck, eclipseHistory: [summary(f.baseline)], runs: [f.baseline] })
  await openWorkspace(page); await activateModel(page, 'ECLIPSE演示模型')
  await page.getByRole('tab', { name: 'DATA 检查' }).click()
  await expect(page.getByRole('definition').filter({ hasText: 'eclipse-data-inspection/5' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '工程依赖清单', exact: true })).toBeVisible()
  await page.getByRole('tab', { name: 'ECLIPSE 结果' }).click()
  await page.getByText('创建井口压力 THP 方案', { exact: true }).click()
  const panel = page.getByRole('region', { name: 'ECLIPSE 井口压力 THP 方案' })
  await expect(panel).toContainText('THP 范围 200–1000 psia')
  await expect(panel).toContainText('井底基准深度 6900 ft')
  await panel.getByRole('spinbutton', { name: 'ECLIPSE 新井口压力' }).fill('500')
  await page.getByRole('button', { name: '运行', exact: true }).click()
  await expect(page.getByText('请选择同版本基线和有效 THP 井；新压力必须不同于原目标并位于 VFP 表范围内', { exact: true })).toBeVisible()
  expect(state.createPayloads).toHaveLength(0)
  await panel.getByRole('spinbutton', { name: 'ECLIPSE 新井口压力' }).fill('700')
  await page.getByRole('button', { name: '运行', exact: true }).click()
  await expect.poll(() => state.createPayloads.length).toBe(1)
  expect(state.createPayloads[0]).toMatchObject({ versionId: 201, payload: { runType: 'eclipse', study: null, parameters: f.parameters } })
  expect(state.unexpectedRequests).toEqual([])
})
for (const fault of ['positive', 'constraint', 'extrapolation', 'missing', 'wrong-unit']) {
  test(`ECLIPSE THP ${fault} 压力产量对照与刷新恢复`, async ({ page }) => {
    const f = thpFixture()
    if (fault === 'constraint') f.derived.result.summary.series[0].points[0].value = 650
    if (fault === 'extrapolation') f.derived.result.summary.series.find(s => s.keyword === 'WLPR').points[0].value = 4000
    if (fault === 'missing') f.derived.result.summary.series = f.derived.result.summary.series.filter(s => s.keyword !== 'WTHP')
    if (fault === 'wrong-unit') { f.baseline.result.summary.series[0].unit = 'BARSA'; f.derived.result.summary.series[0].unit = 'BARSA' }
    const state = await installMockBackend(page, { eclipseInspection: f.deck, eclipseHistory: [summary(f.derived), summary(f.baseline)], runs: [f.derived, f.baseline] })
    await openWorkspace(page); await activateModel(page, 'ECLIPSE演示模型')
    const check = async () => {
      const panel = page.getByRole('region', { name: 'ECLIPSE Schedule 方案验收' })
      await expect(panel).toContainText('井口压力 THP / VFP 方案验收')
      if (fault === 'positive') {
        await expect(panel).toContainText('井口压力达到新目标且总液产量发生变化')
        await expect(panel.getByLabel('ECLIPSE 目标井 WTHP 基线差异', { exact: true })).toContainText('200')
        await expect(panel.getByLabel('ECLIPSE 目标井 WLPR 基线差异', { exact: true })).toContainText('-500')
        await expect(panel.getByLabel('ECLIPSE THP 方案 WBHP 基线差异', { exact: true })).toContainText('2300')
      } else {
        await expect(panel).toContainText(fault === 'constraint' ? 'THP 未达到目标或产量无变化，需审阅其它约束' : fault === 'extrapolation' ? '工况超出 VFP 表范围，需审阅外推' : '缺少可对齐井口压力 WTHP')
        await expect(panel.getByText('井口压力达到新目标且总液产量发生变化', { exact: true })).toHaveCount(0)
      }
    }
    await check(); await page.reload(); await activateModel(page, 'ECLIPSE演示模型'); await check()
    expect(state.unexpectedRequests).toEqual([])
  })
}
for (const mode of ['WRAT', 'GRAT', 'LRAT', 'METRIC', 'missing', 'constrained', 'wrong-unit']) {
  test(`ECLIPSE 生产流量 ${mode} 对照正确变量、约束提示及刷新恢复`, async ({ page }) => {
    const control = mode === 'METRIC' ? 'GRAT' : ['WRAT', 'GRAT', 'LRAT'].includes(mode) ? mode : 'WRAT'
    const keyword = { WRAT: 'WWPR', GRAT: 'WGPR', LRAT: 'WLPR' }[control]
    const unit = mode === 'METRIC' ? 'SM3/DAY' : control === 'GRAT' ? 'MSCF/DAY' : 'STB/DAY'
    const baseline = { id: 9431, projectId: 1, modelId: 22, modelVersionId: 201, modelName: 'ECLIPSE演示模型', versionNo: 1,
      study: null, runType: 'eclipse', status: 'SUCCEEDED', resultContract: 'VALID_FULL', parameters: null, events: [], artifacts: [],
      result: { schemaVersion: 'eclipse-summary-result/1', modelKind: 'eclipse_100', runTask: 'eclipse', resultContract: 'VALID_FULL',
        eclEnd: { comments: 0, warnings: 0, problems: 0, errors: 0, bugs: 0 }, outputFiles: [], summary: { series: [
          { keyword, objectName: 'P1', unit, points: [{ timeDays: 5, value: 1000 }] }] } } }
    const derived = { ...structuredClone(baseline), id: 9432, parameters: {
      schemaVersion: 'eclipse-production-control-parameters/1', baselineRunId: 9431, well: 'P1', phase: 'INITIAL', sourceFile: 'CASE.DATA', lineNumber: 6,
      controlMode: control, originalTargetRate: 1000, targetRate: 500, unit: mode === 'METRIC' ? 'sm3/day' : control === 'GRAT' ? 'Mscf/day' : 'STB/day' } }
    derived.result.summary.series[0].points[0].value = mode === 'constrained' ? 700 : 500
    if (mode === 'missing') derived.result.summary.series = []
    if (mode === 'wrong-unit') {
      derived.result.summary.series[0].unit = 'SM3/DAY'
      baseline.result.summary.series[0].unit = 'SM3/DAY'
    }
    const state = await installMockBackend(page, { eclipseHistory: [summary(derived), summary(baseline)], runs: [derived, baseline] })
    await openWorkspace(page); await activateModel(page, 'ECLIPSE演示模型')
    const check = async () => {
      const panel = page.getByRole('region', { name: 'ECLIPSE Schedule 方案验收' })
      await expect(panel).toContainText(`${control} 生产目标方案验收`)
      if (['missing', 'wrong-unit'].includes(mode)) await expect(panel).toContainText('缺少可对齐目标井流量')
      else if (mode === 'constrained') await expect(panel).toContainText('目标流量未达到或无差异，需审阅其它约束')
      else {
        await expect(panel).toContainText('检测到目标流量差异并达到新目标')
        await expect(panel.getByLabel(`ECLIPSE 目标井 ${keyword} 基线差异`, { exact: true })).toContainText('-500')
      }
      if (['missing', 'constrained', 'wrong-unit'].includes(mode)) await expect(panel.getByText('检测到目标流量差异并达到新目标', { exact: true })).toHaveCount(0)
    }
    await check(); await page.reload(); await activateModel(page, 'ECLIPSE演示模型'); await check()
    expect(state.unexpectedRequests).toEqual([])
  })
}

test('ECLIPSE BHP 编辑切换井后重绑原压力、控制模式和流体', async ({ page }) => {
  const baseline = { id: 9401, projectId: 1, modelId: 22, modelVersionId: 201, modelName: 'ECLIPSE演示模型', versionNo: 1,
    study: null, runType: 'eclipse', status: 'SUCCEEDED', resultContract: 'VALID_FULL', parameters: null,
    result: { eclEnd: { comments: 0, warnings: 0, problems: 0, errors: 0, bugs: 0 }, outputFiles: [] }, events: [], artifacts: [] }
  const state = await installMockBackend(page, { eclipseHistory: [summary(baseline)], runs: [baseline], eclipseInspection: {
    schemaVersion: 'eclipse-data-inspection/4', caseName: 'CASE.DATA', unitSystem: 'FIELD', sections: inspection.sections,
    phases: ['OIL', 'WATER'], dimensions: { nx: 1, ny: 1, nz: 1 }, wellNames: ['P1', 'I1'],
    packageFiles: [{ relativePath: 'CASE.DATA', sizeBytes: 100, sha256: 'a'.repeat(64) }],
    scheduleTimeline: [{ kind: 'TSTEP', steps: ['5'], sourceFile: 'CASE.DATA', lineNumber: 10 }],
    scheduleMetadata: { wells: [{ name: 'P1' }, { name: 'I1' }], groups: [], completions: [], records: [
      { keyword: 'WCONPROD', values: ['P1', 'OPEN', 'BHP', '5*', '3000'], sourceFile: 'CASE.DATA', lineNumber: 6 },
      { keyword: 'WCONINJE', values: ['I1', 'WATER', 'OPEN', 'RESV', '1*', '1000', '6000'], sourceFile: 'CASE.DATA', lineNumber: 7 }] }
  } })
  await openWorkspace(page); await activateModel(page, 'ECLIPSE演示模型')
  const editor = page.getByRole('region', { name: 'ECLIPSE 井底压力 BHP 方案', exact: true })
  await editor.getByText('创建井底压力 BHP 方案', { exact: true }).click()
  await expect(editor).toContainText('原控制：BHP · 原 BHP 设定／限值：3000 psia')
  const target = editor.getByRole('spinbutton', { name: 'ECLIPSE BHP 目标压力' })
  await target.fill('3500')
  const well = editor.getByRole('combobox', { name: 'ECLIPSE BHP 方案井' })
  await well.focus(); await well.press('ArrowDown')
  await page.getByRole('option', { name: 'I1 · 注入井', exact: true }).click()
  await expect(editor).toContainText('原控制：RESV · 原 BHP 设定／限值：6000 psia')
  await expect(target).toHaveValue('5400')
  await target.fill('5000')
  await page.getByRole('button', { name: '运行', exact: true }).click()
  await expect.poll(() => state.createPayloads.length).toBe(1)
  expect(state.createPayloads[0].payload.parameters).toEqual({ schemaVersion: 'eclipse-pressure-control-parameters/1', baselineRunId: 9401,
    well: 'I1', phase: 'INITIAL', sourceFile: 'CASE.DATA', lineNumber: 7, keyword: 'WCONINJE', originalControlMode: 'RESV', originalBhp: 6000, targetBhp: 5000, unit: 'psia', injectionType: 'WATER' })
  expect(state.unexpectedRequests).toEqual([])
})

for (const mode of ['producer', 'injector', 'gas', 'missing-pressure', 'constrained', 'stopped']) {
  test(`ECLIPSE BHP ${mode} 压力与流量对照、缺失提示和刷新恢复`, async ({ page }) => {
    const injection = ['injector', 'stopped'].includes(mode), well = injection ? 'I1' : 'P1', rate = injection ? 'WWIR' : mode === 'gas' ? 'WGPR' : 'WOPR'
    const baseline = { id: 9411, projectId: 1, modelId: 22, modelVersionId: 201, modelName: 'ECLIPSE演示模型', versionNo: 1,
      study: null, runType: 'eclipse', status: 'SUCCEEDED', resultContract: 'VALID_FULL', parameters: null, events: [], artifacts: [],
      result: { schemaVersion: 'eclipse-summary-result/1', modelKind: 'eclipse_100', runTask: 'eclipse', resultContract: 'VALID_FULL',
        eclEnd: { comments: 0, warnings: 0, problems: 0, errors: 0, bugs: 0 }, outputFiles: [], summary: { series: [
          { keyword: rate, objectName: well, unit: mode === 'gas' ? 'MSCF/DAY' : 'STB/DAY', points: [{ timeDays: 5, value: 1000 }] },
          { keyword: 'WBHP', objectName: well, unit: 'PSIA', points: [{ timeDays: 5, value: injection ? 6000 : 3000 }] }
        ] } } }
    const derived = { ...structuredClone(baseline), id: 9412, parameters: {
      schemaVersion: 'eclipse-pressure-control-parameters/1', baselineRunId: 9411, well, phase: 'INITIAL', sourceFile: 'CASE.DATA', lineNumber: 6,
      keyword: injection ? 'WCONINJE' : 'WCONPROD', originalControlMode: injection ? 'RESV' : 'BHP', originalBhp: injection ? 6000 : 3000,
      targetBhp: injection ? 5000 : 3500, unit: 'psia', injectionType: injection ? 'WATER' : 'NONE' } }
    derived.result.summary.series[0].points[0].value = 700
    derived.result.summary.series[1].points[0].value = injection ? 5000 : 3500
    if (mode === 'missing-pressure') derived.result.summary.series.pop()
    if (mode === 'stopped') {
      derived.result.summary.series[0].points[0].value = 0
      derived.result.summary.series[1].points[0].value = 0
    }
    if (mode === 'constrained') {
      derived.result.summary.series = structuredClone(baseline.result.summary.series)
      baseline.result.outputFiles = [{ name: 'CASE.UNSMRY', sha256: 'a'.repeat(64) }]
      derived.result.outputFiles = [{ name: 'CASE.UNSMRY', sha256: 'b'.repeat(64) }]
    }
    const state = await installMockBackend(page, { eclipseHistory: [summary(derived), summary(baseline)], runs: [derived, baseline] })
    await openWorkspace(page); await activateModel(page, 'ECLIPSE演示模型')
    const check = async () => {
      const panel = page.getByRole('region', { name: 'ECLIPSE Schedule 方案验收' })
      await expect(panel).toContainText('井底压力 BHP 方案验收')
      if (!['constrained', 'stopped'].includes(mode)) await expect(panel.getByLabel(`ECLIPSE 目标井 ${rate} 基线差异`, { exact: true })).toContainText('-300')
      if (mode === 'missing-pressure') {
        await expect(panel).toContainText('缺少可对齐井底压力')
        await expect(panel.getByText('检测到井底压力与流量差异', { exact: true })).toHaveCount(0)
      } else if (mode === 'stopped') {
        await expect(panel).toContainText('目标井对照流量为零，需审阅压力约束')
        await expect(panel.getByText('检测到井底压力与流量差异', { exact: true })).toHaveCount(0)
      } else if (mode === 'constrained') {
        await expect(panel).toContainText('压力或流量未发生差异，需审阅约束')
        await expect(panel.getByText('检测到井底压力与流量差异', { exact: true })).toHaveCount(0)
      } else {
        await expect(panel).toContainText('检测到井底压力与流量差异')
        await expect(panel.getByLabel('ECLIPSE 目标井 WBHP 基线差异', { exact: true })).toContainText(injection ? '-1000' : '500')
      }
    }
    await check(); await page.reload(); await activateModel(page, 'ECLIPSE演示模型'); await check()
    expect(state.unexpectedRequests).toEqual([])
  })
}

test('ECLIPSE 开井产油结果按目标井数值比较并可恢复历史', async ({ page }) => {
  const result = { schemaVersion: 'eclipse-summary-result/1', modelKind: 'eclipse_100', runTask: 'eclipse', resultContract: 'VALID_FULL',
    eclEnd: { comments: 1, warnings: 19, problems: 0, errors: 0, bugs: 0 }, outputFiles: [],
    summary: { series: [{ keyword: 'WOPR', objectName: 'P1', unit: 'STB/DAY', points: [{ timeDays: 0.1, value: 10000 }, { timeDays: 4, value: 9000 }] }] } }
  const current = { id: 9393, projectId: 1, modelId: 22, modelVersionId: 201, versionNo: 1, modelName: 'ECLIPSE演示模型', study: null,
    runType: 'eclipse', status: 'SUCCEEDED', resultContract: 'VALID_FULL', result, events: [], artifacts: [], parameters: {
      schemaVersion: 'eclipse-production-target-parameters/1', baselineRunId: 9392, well: 'P1', phase: 'INITIAL',
      sourceFile: 'CASE.DATA', lineNumber: 6, unit: 'STB/day', originalOilRate: 20000, targetOilRate: 10000 } }
  const baseline = { ...structuredClone(current), id: 9392, parameters: null }
  baseline.result.summary.series[0].points[0].value = 12084.87
  baseline.result.summary.series[0].points[1].value = 11919.14
  const state = await installMockBackend(page, { eclipseHistory: [summary(current), summary(baseline)], runs: [current, baseline] })
  await openWorkspace(page)
  await activateModel(page, 'ECLIPSE演示模型')
  const acceptance = page.getByRole('region', { name: 'ECLIPSE Schedule 方案验收' })
  await expect(acceptance).toContainText('开井产油目标方案')
  await expect(acceptance).toContainText('20000 STB/day')
  await expect(acceptance).toContainText('10000 STB/day')
  await expect(acceptance).toContainText('检测到目标井产油率差异')
  await expect(acceptance.getByLabel('ECLIPSE 目标井 WOPR 基线差异')).toContainText('12084.87')
  await page.reload()
  await activateModel(page, 'ECLIPSE演示模型')
  await expect(acceptance).toContainText('开井产油目标方案')
  expect(state.unexpectedRequests).toEqual([])
})

test('ECLIPSE WCONHIST ORAT 方案只提交受控字段', async ({ page }) => {
  const current = {
    id: 9302, projectId: 1, modelId: 22, modelVersionId: 201, modelName: 'ECLIPSE演示模型', versionNo: 1,
    study: null, runType: 'eclipse', status: 'SUCCEEDED', resultContract: 'VALID_FULL', parameters: null,
    result: { eclEnd: { comments: 0, warnings: 0, problems: 0, errors: 0, bugs: 0 }, outputFiles: [] }, events: [], artifacts: []
  }
  const baseline = {
    id: 9301, projectId: 1, modelId: 22, modelVersionId: 201, modelName: 'ECLIPSE演示模型', versionNo: 1,
    study: null, runType: 'eclipse', status: 'SUCCEEDED', resultContract: 'VALID_FULL', parameters: null,
    result: { eclEnd: { comments: 0, warnings: 0, problems: 0, errors: 0, bugs: 0 }, outputFiles: [] }, events: [], artifacts: []
  }
  const state = await installMockBackend(page, { eclipseHistory: [summary(current), summary(baseline)], runs: [current, baseline] })
  state.models[2].versions[0].inspection = {
    ...structuredClone(inspection),
    schemaVersion: 'eclipse-data-inspection/4',
    scheduleTimeline: [{ kind: 'DATES', records: [{ day: '15', month: 'JAN', year: '1970' }] }],
    scheduleMetadata: {
      wells: [{ name: 'G1', group: 'FIELD', sourceFile: 'BASE.SCH', lineNumber: 1 }],
      groups: [{ name: 'FIELD', parent: null, sourceFile: 'BASE.SCH', lineNumber: 2 }],
      records: [{ keyword: 'WCONHIST', values: ['G1', 'OPEN', 'ORAT', '35.686', '0.119', '45436.102', '4*'], sourceFile: 'BASE.SCH', lineNumber: 3 }],
      completions: []
    }
  }
  await openWorkspace(page)
  await activateModel(page, 'ECLIPSE演示模型')
  const editor = page.getByRole('region', { name: 'ECLIPSE WCONHIST 产量方案', exact: true })
  await expect(editor).toBeVisible()
  await editor.getByText('创建 WCONHIST ORAT 方案', { exact: true }).click()
  await editor.getByRole('spinbutton', { name: 'ECLIPSE WCONHIST ORAT 目标值' }).fill('55.5')
  await page.getByRole('button', { name: '运行', exact: true }).click()
  await expect.poll(() => state.createPayloads.length).toBe(1)
  expect(state.createPayloads[0]).toEqual({
    versionId: 201,
    payload: {
      study: null,
      runType: 'eclipse',
      parameters: {
        schemaVersion: 'eclipse-schedule-parameters/2',
        baselineRunId: 9302,
        well: 'G1',
        date: '1970-01-15',
        status: 'SHUT',
        controlMode: 'ORAT',
        targetOilRate: 55.5
      }
    }
  })
  expect(state.unexpectedRequests).toEqual([])
})

for (const wellCount of [1, 2]) {
test(`ECLIPSE ${wellCount === 1 ? '单井' : '井组'}双事件方案提交递增日期的 SHUT 到 OPEN 合同`, async ({ page }) => {
  const current = {
    id: 9322, projectId: 1, modelId: 22, modelVersionId: 201, modelName: 'ECLIPSE演示模型', versionNo: 1,
    study: null, runType: 'eclipse', status: 'SUCCEEDED', resultContract: 'VALID_FULL', parameters: null,
    result: { eclEnd: { comments: 0, warnings: 0, problems: 0, errors: 0, bugs: 0 }, outputFiles: [] }, events: [], artifacts: []
  }
  const baseline = { ...structuredClone(current), id: 9321 }
  const state = await installMockBackend(page, { eclipseHistory: [summary(current), summary(baseline)], runs: [current, baseline] })
  state.models[2].versions[0].inspection = {
    ...structuredClone(inspection),
    schemaVersion: 'eclipse-data-inspection/4',
    scheduleTimeline: [
      { kind: 'DATES', records: [{ day: '15', month: 'JAN', year: '1970' }] },
      { kind: 'DATES', records: [{ day: '1', month: 'FEB', year: '1970' }] }
    ],
    scheduleMetadata: {
      wells: ['G1', 'G2'].slice(0, wellCount).map(name => ({ name, group: 'FIELD', sourceFile: 'BASE.SCH', lineNumber: 1 })),
      groups: [{ name: 'FIELD', parent: null, sourceFile: 'BASE.SCH', lineNumber: 2 }],
      records: ['G1', 'G2'].slice(0, wellCount).map(name => ({ keyword: 'WCONHIST', values: [name, 'OPEN', 'ORAT', '35.686'], sourceFile: 'BASE.SCH', lineNumber: 3 })),
      completions: []
    }
  }
  await openWorkspace(page)
  await activateModel(page, 'ECLIPSE演示模型')
  const editor = page.getByRole('region', { name: 'ECLIPSE 井组双事件 Schedule 方案', exact: true })
  await expect(editor).toBeVisible()
  await editor.getByText('创建井组关井 → 重开方案', { exact: true }).click()
  if (wellCount === 2) {
    const wellSelect = editor.getByRole('combobox', { name: 'ECLIPSE 双事件生产井', exact: true })
    await wellSelect.focus()
    await wellSelect.press('ArrowDown')
    await page.getByRole('option', { name: 'G2', exact: true }).click()
    await page.keyboard.press('Escape')
  }
  await page.getByRole('button', { name: '运行', exact: true }).click()
  await expect.poll(() => state.createPayloads.length).toBe(1)
  expect(state.createPayloads[0]).toEqual({
    versionId: 201,
    payload: {
      study: null,
      runType: 'eclipse',
      parameters: {
        schemaVersion: `eclipse-schedule-parameters/${wellCount === 1 ? 5 : 6}`,
        baselineRunId: 9322,
        ...(wellCount === 1 ? { well: 'G1' } : { wells: ['G1', 'G2'] }),
        events: [
          { date: '1970-01-15', status: 'SHUT' },
          { date: '1970-02-01', status: 'OPEN' }
        ]
      }
    }
  })
  expect(state.unexpectedRequests).toEqual([])
})

}

test('ECLIPSE 多日期启停保留所有事件并拒绝重复日期', async ({ page }) => {
  const baseline = { id: 9351, projectId: 1, modelId: 22, modelVersionId: 201, versionNo: 1, modelName: 'ECLIPSE演示模型',
    study: null, runType: 'eclipse', status: 'SUCCEEDED', resultContract: 'VALID_FULL', parameters: null,
    result: { eclEnd: { comments: 0, warnings: 0, problems: 0, errors: 0, bugs: 0 }, outputFiles: [] }, events: [], artifacts: [] }
  const state = await installMockBackend(page, { eclipseHistory: [summary(baseline)], runs: [baseline] })
  state.models[2].versions[0].inspection = { ...structuredClone(inspection), schemaVersion: 'eclipse-data-inspection/4',
    scheduleTimeline: ['JAN', 'FEB', 'MAR'].map((month, i) => ({ kind: 'DATES', records: [{ day: i === 0 ? '15' : '1', month, year: '1970' }] })),
    scheduleMetadata: { wells: ['G1', 'G2'].map(name => ({ name, group: 'FIELD', sourceFile: 'BASE.SCH', lineNumber: 1 })), groups: [],
      records: ['G1', 'G2'].map(well => ({ keyword: 'WCONHIST', values: [well, 'OPEN', 'ORAT', '35'], sourceFile: 'BASE.SCH', lineNumber: 3 })), completions: [] } }
  await openWorkspace(page)
  await activateModel(page, 'ECLIPSE演示模型')
  const editor = page.getByRole('region', { name: 'ECLIPSE 井组双事件 Schedule 方案', exact: true })
  await editor.getByText('创建井组关井 → 重开方案', { exact: true }).click()
  const select = editor.getByRole('combobox', { name: 'ECLIPSE 双事件生产井', exact: true })
  await select.focus(); await select.press('ArrowDown')
  await page.getByRole('option', { name: 'G2', exact: true }).click(); await page.keyboard.press('Escape')
  await editor.getByText('编辑多日期启停（2–24个事件）', { exact: true }).click()
  await editor.getByRole('button', { name: '添加启停事件', exact: true }).click()
  const third = editor.getByRole('combobox', { name: 'ECLIPSE 事件 3 日期', exact: true })
  await third.focus(); await third.press('ArrowDown')
  await page.getByRole('option', { name: '1970-02-01', exact: true }).click()
  await page.getByRole('button', { name: '运行', exact: true }).click()
  await expect(page.getByText('请选择成功基线、1–8口生产井及2–24个按日期递增的已有日期启停事件')).toBeVisible()
  expect(state.createPayloads).toHaveLength(0)
  await third.focus(); await third.press('ArrowDown')
  await page.getByRole('option', { name: '1970-03-01', exact: true }).click()
  await page.getByRole('button', { name: '运行', exact: true }).click()
  await expect.poll(() => state.createPayloads.length).toBe(1)
  expect(state.createPayloads[0].payload.parameters).toEqual({ schemaVersion: 'eclipse-schedule-parameters/7', baselineRunId: 9351,
    wells: ['G1', 'G2'], events: [{ date: '1970-01-15', status: 'SHUT' }, { date: '1970-02-01', status: 'OPEN' }, { date: '1970-03-01', status: 'SHUT' }] })
  expect(state.unexpectedRequests).toEqual([])
})

for (const injection of [true, false]) {
  test(`ECLIPSE ${injection ? '注入量' : '多日期'}结果比较与刷新恢复`, async ({ page }) => {
    const baseline = { id: 9361, projectId: 1, modelId: 22, modelVersionId: 201, versionNo: 1, modelName: 'ECLIPSE演示模型',
      study: null, runType: 'eclipse', status: 'SUCCEEDED', resultContract: 'VALID_FULL', parameters: null, events: [], artifacts: [],
      result: { schemaVersion: 'eclipse-summary-result/1', modelKind: 'eclipse_100', runTask: 'eclipse', resultContract: 'VALID_FULL',
        eclEnd: { comments: 0, warnings: 0, problems: 0, errors: 0, bugs: 0 }, outputFiles: [], summary: { series: [
          { keyword: injection ? 'WWIR' : 'WOPR', objectName: injection ? 'D1' : 'G1', unit: 'STB/DAY', points: [{ timeDays: 31, value: injection ? 2000 : 35 }] }
        ] } } }
    const derived = { ...structuredClone(baseline), id: 9362, parameters: injection
      ? { schemaVersion: 'eclipse-schedule-parameters/3', baselineRunId: 9361, well: 'D1', date: '1970-01-15', injectionType: 'WATER', controlMode: 'RATE', targetInjectionRate: 1500 }
      : { schemaVersion: 'eclipse-schedule-parameters/7', baselineRunId: 9361, wells: ['G1'], events: [
          { date: '1970-01-15', status: 'SHUT' }, { date: '1970-02-01', status: 'OPEN' }, { date: '1970-03-01', status: 'SHUT' }] } }
    derived.result.summary.series[0].points[0].value = injection ? 1500 : 0
    const state = await installMockBackend(page, { eclipseHistory: [summary(derived), summary(baseline)], runs: [derived, baseline] })
    await openWorkspace(page); await activateModel(page, 'ECLIPSE演示模型')
    const check = async () => {
      await expect(page.getByLabel(`ECLIPSE 目标井 ${injection ? 'WWIR' : 'WOPR'} 基线差异`, { exact: true })).toContainText(injection ? '-500' : '-35')
      await expect(page.getByText(injection ? '检测到目标井注入量差异' : 'WELOPEN 多日期调度方案验收', { exact: true })).toBeVisible()
      if (!injection) await expect(page.getByLabel('ECLIPSE 多日期时间轴', { exact: true })).toContainText('1970-03-01')
    }
    await check()
    await page.reload(); await activateModel(page, 'ECLIPSE演示模型'); await check()
    expect(state.unexpectedRequests).toEqual([])
  })
}

test('ECLIPSE 接续预测按真实截止日期提交完整来源和报告', async ({ page }) => {
  const f = restartForecastFixture()
  const state = await installMockBackend(page, { eclipseInspection: f.inspection, eclipseHistory: [summary(f.history)], runs: [f.history], artifactPayloads: f.artifactPayloads })
  await openWorkspace(page); await activateModel(page, 'ECLIPSE演示模型')
  const editor = page.getByRole('region', { name: 'ECLIPSE 历史重启预测方案', exact: true })
  await editor.getByText('创建历史重启预测方案', { exact: true }).click()
  await expect(editor).toContainText('已核对 81 个真实历史报告')
  await expect(editor).toContainText('1975-12-31 · 第 2190 天 · 报告 80')
  await editor.locator('.el-select').nth(1).click(); await page.getByRole('option', { name: 'FOREB.DATA', exact: true }).click()
  await page.getByRole('button', { name: '运行', exact: true }).click()
  await expect.poll(() => state.createPayloads.length).toBe(1)
  expect(state.createPayloads[0].payload.parameters).toEqual(f.second.parameters)
  expect(state.unexpectedRequests).toEqual([])
})

test('ECLIPSE 接续预测核对两个未来新增产油且刷新可恢复来源', async ({ page }) => {
  const f = restartForecastFixture()
  const histories = [f.first, f.second, f.history].map(r => ({ ...summary(r), parameters: r.parameters }))
  const state = await installMockBackend(page, { eclipseInspection: f.inspection, eclipseHistory: histories, runs: [f.first, f.second, f.history], artifactPayloads: f.artifactPayloads })
  await openWorkspace(page); await activateModel(page, 'ECLIPSE演示模型')
  const check = async () => {
    const region = page.getByRole('region', { name: 'ECLIPSE 历史接续与未来方案对比', exact: true })
    await expect(region).toContainText('截止状态已核对')
    await expect(region).toContainText('共同起算日期 1970-01-01')
    await region.locator('.el-select').filter({ has: page.getByRole('combobox', { name: 'ECLIPSE 另一个未来方案', exact: true }) }).click(); await page.getByRole('option', { name: '#9553 · FOREB.DATA', exact: true }).click()
    await expect(region).toContainText('累计增量来自真实累计值')
    const table = region.getByLabel('ECLIPSE 未来累计产油与新增产油', { exact: true })
    await expect(table).toContainText('1976-12-31'); await expect(table).toContainText('1977-12-31')
    await expect(table).toContainText('150'); await expect(table).toContainText('120'); await expect(table).toContainText('30')
  }
  await check(); await page.reload(); await activateModel(page, 'ECLIPSE演示模型'); await check()
  expect(state.unexpectedRequests).toEqual([])
})

test('ECLIPSE 接续预测目录损坏不会退回手填号或创建运行', async ({ page }) => {
  const f = restartForecastFixture()
  const state = await installMockBackend(page, { eclipseInspection: f.inspection, eclipseHistory: [summary(f.history)], runs: [f.history], artifactPayloads: { '9551:552': f.catalogText.replace('1975-12-31', '1975-12-30') } })
  await openWorkspace(page); await activateModel(page, 'ECLIPSE演示模型')
  const editor = page.getByRole('region', { name: 'ECLIPSE 历史重启预测方案', exact: true })
  await editor.getByText('创建历史重启预测方案', { exact: true }).click()
  await expect(editor).toContainText('历史报告目录读取或核对失败')
  await expect(editor.getByRole('spinbutton')).toHaveCount(0)
  await page.getByRole('button', { name: '运行', exact: true }).click()
  await expect(page.getByTestId('run-feedback')).toContainText('请等待历史目录核对完成')
  expect(state.createPayloads).toHaveLength(0)
})

test('ECLIPSE 接续预测错误时间偏移不能生成新增产油对比表', async ({ page }) => {
  const f = restartForecastFixture()
  for (const series of f.second.result.summary.series) for (const point of series.points) point.timeDays -= 2190
  const state = await installMockBackend(page, { eclipseInspection: f.inspection, eclipseHistory: [f.first, f.second, f.history].map(r => ({ ...summary(r), parameters: r.parameters })),
    runs: [f.first, f.second, f.history], artifactPayloads: f.artifactPayloads })
  await openWorkspace(page); await activateModel(page, 'ECLIPSE演示模型')
  const region = page.getByRole('region', { name: 'ECLIPSE 历史接续与未来方案对比', exact: true })
  await expect(region).toContainText('截止状态已核对'); await region.locator('.el-select').filter({ has: page.getByRole('combobox', { name: 'ECLIPSE 另一个未来方案', exact: true }) }).click(); await page.getByRole('option', { name: '#9553 · FOREB.DATA', exact: true }).click()
  await expect(region).toContainText('未来输出时刻与原始日期不一致')
  await expect(region.getByLabel('ECLIPSE 未来累计产油与新增产油', { exact: true })).toHaveCount(0)
  expect(state.unexpectedRequests).toEqual([])
})

test('ECLIPSE 历史预测必须提供 RESTART 中声明的报告号', async ({ page }) => {
  const history = {
    id: 9342, projectId: 1, modelId: 22, modelVersionId: 201, modelName: 'ECLIPSE演示模型', versionNo: 1,
    study: null, runType: 'eclipse', status: 'SUCCEEDED', resultContract: 'VALID_FULL', parameters: null,
    result: {
      eclEnd: { comments: 0, warnings: 0, problems: 0, errors: 0, bugs: 0 }, outputFiles: [],
      fieldIndex: { schemaVersion: 'eclipse-binary-field-index/1', files: [{
        name: 'EX3.FUNRST', sizeBytes: 256, byteOrder: 'BIG', fields: [
          { keyword: 'SEQNUM', dataType: 'INTE', count: 1, elementSize: 4, dataBytes: 4, timeStep: 41, segments: [{ offset: 32, length: 4 }] },
          { keyword: 'SEQNUM', dataType: 'INTE', count: 1, elementSize: 4, dataBytes: 4, timeStep: 14, segments: [{ offset: 64, length: 4 }] },
          { keyword: 'INTEHEAD', dataType: 'INTE', count: 1, elementSize: 4, dataBytes: 4, timeStep: 99, segments: [{ offset: 96, length: 4 }] }
        ]
      }, {
        name: 'OTHER.FUNRST', sizeBytes: 128, byteOrder: 'BIG', fields: [
          { keyword: 'SEQNUM', dataType: 'INTE', count: 1, elementSize: 4, dataBytes: 4, timeStep: 99, segments: [{ offset: 32, length: 4 }] }
        ]
      }] }
    }, events: [],
    artifacts: [{ name: 'eclipse-output-EX3.FUNRST', sha256: 'a'.repeat(64) }]
  }
  const state = await installMockBackend(page, { eclipseHistory: [summary(history)], runs: [history] })
  state.models[2].versions[0].inspection = {
    ...structuredClone(inspection), caseName: 'EX3.DATA', schemaVersion: 'eclipse-data-inspection/4',
    packageFiles: [
      { relativePath: 'EX3.DATA', sizeBytes: 128, sha256: 'a'.repeat(64) },
      { relativePath: 'EX3_PRED.DATA', sizeBytes: 256, sha256: 'b'.repeat(64) }
    ],
    scheduleMetadata: { wells: [], groups: [], records: [], completions: [] }
  }
  await openWorkspace(page)
  await activateModel(page, 'ECLIPSE演示模型')
  const editor = page.getByRole('region', { name: 'ECLIPSE 历史重启预测方案', exact: true })
  await editor.getByText('创建历史重启预测方案', { exact: true }).click()
  await expect.poll(() => state.runReads.get(9342) || 0).toBeGreaterThan(0)
  await expect(editor.locator('.el-select').nth(2)).toContainText('eclipse-output-EX3.FUNRST')
  await expect(editor).toContainText('候选报告来自所选历史 Run 的 FUNRST SEQNUM 索引')

  await page.getByRole('button', { name: '运行', exact: true }).click()
  await expect(page.getByText('预测运行必须选择当前版本的预测 DATA、成功历史运行、FUNRST Artifact 和正整数重启报告')).toBeVisible()
  expect(state.createPayloads).toHaveLength(0)

  await editor.locator('.el-select').nth(3).click()
  await expect(page.getByRole('option', { name: '报告 41', exact: true })).toBeVisible()
  await expect(page.getByRole('option', { name: '报告 99', exact: true })).toHaveCount(0)
  await page.getByRole('option', { name: '报告 14', exact: true }).click()
  await page.getByRole('button', { name: '运行', exact: true }).click()
  await expect.poll(() => state.createPayloads.length).toBe(1)
  expect(state.createPayloads[0].payload.parameters).toMatchObject({
    schemaVersion: 'eclipse-history-forecast-parameters/1',
    historyRunId: 9342,
    forecastDataFile: 'EX3_PRED.DATA',
    restartArtifactName: 'eclipse-output-EX3.FUNRST',
    restartReport: 14
  })
  expect(state.unexpectedRequests).toEqual([])
})

test('ECLIPSE 旧历史运行缺少 FUNRST 索引时保留手动报告号回退', async ({ page }) => {
  const history = {
    id: 9344, projectId: 1, modelId: 22, modelVersionId: 201, modelName: 'ECLIPSE演示模型', versionNo: 1,
    study: null, runType: 'eclipse', status: 'SUCCEEDED', resultContract: 'VALID_FULL', parameters: null,
    result: { eclEnd: { comments: 0, warnings: 0, problems: 0, errors: 0, bugs: 0 }, outputFiles: [] },
    events: [], artifacts: [{ name: 'eclipse-output-EX3.FUNRST', sha256: 'd'.repeat(64) }]
  }
  const state = await installMockBackend(page, { eclipseHistory: [summary(history)], runs: [history] })
  state.models[2].versions[0].inspection = {
    ...structuredClone(inspection), caseName: 'EX3.DATA', schemaVersion: 'eclipse-data-inspection/4',
    packageFiles: [
      { relativePath: 'EX3.DATA', sizeBytes: 128, sha256: 'a'.repeat(64) },
      { relativePath: 'EX3_PRED.DATA', sizeBytes: 256, sha256: 'b'.repeat(64) }
    ],
    scheduleMetadata: { wells: [], groups: [], records: [], completions: [] }
  }
  await openWorkspace(page)
  await activateModel(page, 'ECLIPSE演示模型')
  const editor = page.getByRole('region', { name: 'ECLIPSE 历史重启预测方案', exact: true })
  await editor.getByText('创建历史重启预测方案', { exact: true }).click()
  await expect.poll(() => state.runReads.get(9344) || 0).toBeGreaterThan(0)
  await expect(editor).toContainText('该历史 Artifact 暂无可读 SEQNUM 索引')
  const reportInput = editor.getByRole('spinbutton', { name: 'ECLIPSE 重启报告编号' })
  await reportInput.fill('14')
  await page.getByRole('button', { name: '运行', exact: true }).click()
  await expect.poll(() => state.createPayloads.length).toBe(1)
  expect(state.createPayloads[0].payload.parameters).toMatchObject({ historyRunId: 9344, restartReport: 14 })
  expect(state.unexpectedRequests).toEqual([])
})

test('ECLIPSE 历史预测结果显示并核对来源链且不拼接时间轴', async ({ page }) => {
  const hash = 'c'.repeat(64)
  const result = {
    schemaVersion: 'eclipse-summary-result/1', modelKind: 'eclipse_100', runTask: 'eclipse', resultContract: 'VALID_FULL',
    eclEnd: { comments: 1, warnings: 0, problems: 0, errors: 0, bugs: 0 }, outputFiles: [],
    summary: { series: [{ keyword: 'FOPR', objectName: null, unit: 'STB/DAY', points: [{ timeDays: 20, value: 12.5 }] }] }
  }
  const history = {
    id: 9342, projectId: 1, modelId: 22, modelVersionId: 201, modelName: 'ECLIPSE演示模型', versionNo: 1,
    study: null, runType: 'eclipse', status: 'SUCCEEDED', resultContract: 'VALID_FULL', parameters: null,
    result, events: [], artifacts: [{ name: 'eclipse-output-EX3.FUNRST', sha256: hash }]
  }
  const forecast = {
    ...structuredClone(history), id: 9343,
    parameters: {
      schemaVersion: 'eclipse-history-forecast-parameters/1', historyRunId: 9342,
      forecastDataFile: 'EX3_PRED.DATA', restartArtifactName: 'eclipse-output-EX3.FUNRST',
      restartArtifactSha256: hash, restartReport: 14
    },
    artifacts: []
  }
  const state = await installMockBackend(page, { eclipseHistory: [summary(forecast), summary(history)], runs: [forecast, history] })
  await openWorkspace(page)
  await activateModel(page, 'ECLIPSE演示模型')
  await page.getByRole('tab', { name: '运行记录' }).click()
  await page.getByRole('tabpanel', { name: '运行记录' }).locator('.el-table__row').filter({ hasText: '9343' }).click()
  await page.getByRole('tab', { name: 'ECLIPSE 结果' }).click()

  const provenance = page.getByRole('region', { name: 'ECLIPSE 历史预测来源链' })
  await expect(provenance).toContainText('来源已核对')
  await expect(provenance).toContainText('EX3_PRED.DATA')
  await expect(provenance).toContainText('Restart report')
  await expect(provenance).toContainText('14')
  await expect(provenance).toContainText(hash)
  await expect(provenance).toContainText('本页 Summary 只代表当前预测 Run')
  await expect(page.getByRole('combobox', { name: 'ECLIPSE 历史结果对比' })).toHaveCount(0)
  await expect(page.getByText('预测段独立显示；待确认 Restart 时间原点后再开放跨阶段对比')).toBeVisible()
  await expect.poll(() => state.runReads.get(9342) || 0).toBeGreaterThan(0)
  expect(state.unexpectedRequests).toEqual([])
})

for (const wellCount of [1, 2]) {
test(`ECLIPSE ${wellCount === 1 ? '单井' : '井组'}双事件结果显示井状态时间轴和对齐的 WOPR 数值差异`, async ({ page }) => {
  const result = {
    schemaVersion: 'eclipse-summary-result/1', modelKind: 'eclipse_100', runTask: 'eclipse', resultContract: 'VALID_FULL',
    eclEnd: { comments: 2, warnings: 10, problems: 0, errors: 0, bugs: 0 }, outputFiles: [],
    summary: { series: [{ keyword: 'WOPR', objectName: 'G1', unit: 'STB/DAY', points: [
      { timeDays: 14, value: 35.686 }, { timeDays: 31, value: 0 }, { timeDays: 59, value: 64.598 }
    ] }] }
  }
  const current = {
    id: 9332, projectId: 1, modelId: 22, modelVersionId: 201, versionNo: 1, modelName: 'ECLIPSE演示模型', study: null,
    runType: 'eclipse', status: 'SUCCEEDED', resultContract: 'VALID_FULL',
    parameters: { schemaVersion: 'eclipse-schedule-parameters/5', baselineRunId: 9331, well: 'G1', events: [
      { date: '1970-01-15', status: 'SHUT' }, { date: '1970-02-01', status: 'OPEN' }
    ] }, result, events: [], artifacts: []
  }
  const baseline = {
    ...structuredClone(current), id: 9331, parameters: null,
    result: { ...structuredClone(result), summary: { series: [{ keyword: 'WOPR', objectName: 'G1', unit: 'STB/DAY', points: [
      { timeDays: 14, value: 35.686 }, { timeDays: 31, value: 35.686 }, { timeDays: 59, value: 64.598 }
    ] }] } }
  }
  if (wellCount === 2) {
    current.parameters.schemaVersion = 'eclipse-schedule-parameters/6'
    delete current.parameters.well
    current.parameters.wells = ['G1', 'G2']
    current.result.summary.series.push({ keyword: 'WOPR', objectName: 'G2', unit: 'STB/DAY', points: [
      { timeDays: 14, value: 749.61 }, { timeDays: 31, value: 0 }, { timeDays: 59, value: 1339.57 }
    ] })
    baseline.result.summary.series.push({ keyword: 'WOPR', objectName: 'G2', unit: 'STB/DAY', points: [
      { timeDays: 14, value: 749.61 }, { timeDays: 31, value: 749.61 }, { timeDays: 59, value: 1339.57 }
    ] })
  }
  const state = await installMockBackend(page, { eclipseHistory: [summary(current), summary(baseline)], runs: [current, baseline] })
  await openWorkspace(page)
  await activateModel(page, 'ECLIPSE演示模型')
  await page.getByRole('tab', { name: '运行记录' }).click()
  await page.getByRole('tabpanel', { name: '运行记录' }).locator('.el-table__row').filter({ hasText: '9332' }).click()
  await page.getByRole('tab', { name: 'ECLIPSE 结果' }).click()
  const acceptance = page.getByRole('region', { name: 'ECLIPSE Schedule 方案验收' })
  await expect(acceptance).toContainText('WELOPEN 双事件调度方案验收')
  await expect(acceptance).toContainText('1970-01-15')
  await expect(acceptance).toContainText('1970-02-01')
  await expect(acceptance).toContainText('SHUT（关井）')
  await expect(acceptance).toContainText('OPEN（开井）')
  await expect(acceptance).toContainText('检测到目标井产油率差异')
  await expect(acceptance).toContainText('35.68600')
  await expect(acceptance).toContainText('0.000000')
  if (wellCount === 2) {
    await expect(acceptance).toContainText('G1、G2')
    const table = acceptance.getByLabel('ECLIPSE 目标井 WOPR 基线差异', { exact: true })
    await expect(table).toContainText('G2')
    await expect(table).toContainText('749.6100')
    await expect(table).toContainText('1339.570')
  }
  expect(state.unexpectedRequests).toEqual([])
})
}

test('ECLIPSE WCONINJE RATE 方案只提交受控字段', async ({ page }) => {
  const current = {
    id: 9312, projectId: 1, modelId: 22, modelVersionId: 201, modelName: 'ECLIPSE演示模型', versionNo: 1,
    study: null, runType: 'eclipse', status: 'SUCCEEDED', resultContract: 'VALID_FULL', parameters: null,
    result: { eclEnd: { comments: 0, warnings: 0, problems: 0, errors: 0, bugs: 0 }, outputFiles: [] }, events: [], artifacts: []
  }
  const baseline = {
    id: 9311, projectId: 1, modelId: 22, modelVersionId: 201, modelName: 'ECLIPSE演示模型', versionNo: 1,
    study: null, runType: 'eclipse', status: 'SUCCEEDED', resultContract: 'VALID_FULL', parameters: null,
    result: { eclEnd: { comments: 0, warnings: 0, problems: 0, errors: 0, bugs: 0 }, outputFiles: [] }, events: [], artifacts: []
  }
  const state = await installMockBackend(page, { eclipseHistory: [summary(current), summary(baseline)], runs: [current, baseline] })
  state.models[2].versions[0].inspection = {
    ...structuredClone(inspection),
    schemaVersion: 'eclipse-data-inspection/4',
    scheduleTimeline: [{ kind: 'DATES', records: [{ day: '15', month: 'JAN', year: '1970' }] }],
    scheduleMetadata: {
      wells: [{ name: 'D1', group: 'FIELD', sourceFile: 'BASE.SCH', lineNumber: 1 }],
      groups: [{ name: 'FIELD', parent: null, sourceFile: 'BASE.SCH', lineNumber: 2 }],
      records: [{ keyword: 'WCONINJE', values: ['D1', 'WATER', '1*', 'RATE', '2000.000', '5*'], sourceFile: 'BASE.SCH', lineNumber: 3 }],
      completions: []
    }
  }
  await openWorkspace(page)
  await activateModel(page, 'ECLIPSE演示模型')
  const editor = page.getByRole('region', { name: 'ECLIPSE WCONINJE 注入方案', exact: true })
  await expect(editor).toBeVisible()
  await editor.getByText('创建 WCONINJE RATE 方案', { exact: true }).click()
  await editor.getByRole('spinbutton', { name: 'ECLIPSE WCONINJE RATE 注入速率' }).fill('1500')
  await page.getByRole('button', { name: '运行', exact: true }).click()
  await expect.poll(() => state.createPayloads.length).toBe(1)
  expect(state.createPayloads[0]).toEqual({
    versionId: 201,
    payload: {
      study: null,
      runType: 'eclipse',
      parameters: {
        schemaVersion: 'eclipse-schedule-parameters/3',
        baselineRunId: 9312,
        well: 'D1',
        date: '1970-01-15',
        injectionType: 'WATER',
        controlMode: 'RATE',
        targetInjectionRate: 1500
      }
    }
  })
  expect(state.unexpectedRequests).toEqual([])
})

test('PIPESIM ESP 曲线展示官方双任务原始点并支持导出', async ({ page }) => {
  const curve = (frequencyHz, scale) => ({
    frequencyHz, frequencyLabel: `FREQ= ${frequencyHz.toFixed(2)} Hz`, flowRate: [100 * scale, 200 * scale, 300 * scale], flowRateUnit: 'bbl/d',
    head: [1000 * scale, 800 * scale, 500 * scale], headUnit: 'ft'
  })
  const pump = (model, scale) => ({
    pumpName: 'B-ESP',
    inputs: { frequency: 60, frequencyUnit: 'Hz', manufacturer: 'REDA', model, minFlowRate: 4500, maxFlowRate: 9000, stages: 100 },
    frequencies: [curve(30, scale), curve(60, scale)],
    operatingEnvelope: {
      qMin: { flowRate: [100, 110, 120], flowRateUnit: 'bbl/d', head: [900, 850, 800], headUnit: 'ft' },
      bep: { flowRate: [200, 210, 220], flowRateUnit: 'bbl/d', head: [800, 750, 700], headUnit: 'ft' },
      qMax: { flowRate: [300, 310, 320], flowRateUnit: 'bbl/d', head: [500, 450, 400], headUnit: 'ft' }
    }
  })
  const run = {
    id: 9351, projectId: 1, modelId: 11, modelVersionId: 101, versionNo: 1, modelName: '井筒演示模型', study: 'Base Case', runType: 'esp-curves', status: 'SUCCEEDED', resultContract: 'VALID_FULL',
    parameters: { schemaVersion: 'pipesim-esp-curves-parameters/1' }, events: [], artifacts: [],
    result: { schemaVersion: 'pipesim-esp-curves-result/1', model_kind: 'black_oil_liquid', runTask: 'esp-curves', resultContract: 'VALID_FULL', producer: 'Well_1', pump: pump('J7000N', 1), nodalPump: pump('J7000N', 1.1) }
  }
  const capabilities = structuredClone(availableCapabilities())
  capabilities.pipesimWell.runTasks.push('esp-curves')
  const state = await installMockBackend(page, { capabilities, wellHistory: [summary(run)], runs: [run] })
  await openWorkspace(page)
  await activateModel(page, '井筒演示模型')
  await page.getByRole('tab', { name: 'ESP 曲线', exact: true }).click()
  const result = page.getByRole('region', { name: 'PIPESIM ESP 曲线结果', exact: true })
  await expect(result).toContainText('REDA')
  await expect(result).toContainText('J7000N')
  await expect(result).toContainText('Qmin')
  await expect(result).toContainText('3 个原始点')
  const downloadPromise = page.waitForEvent('download')
  await result.getByRole('button', { name: '导出原始点 CSV', exact: true }).click()
  const stream = await (await downloadPromise).createReadStream()
  let csv = ''
  for await (const chunk of stream) csv += chunk.toString('utf8')
  expect(csv).toContain('PT Profile')
  expect(csv).toContain('"PT Profile","B-ESP",30,100,"bbl/d",1000,"ft"')
  expect(state.unexpectedRequests).toEqual([])
})

const espMultiWellInspection = (schemaVersion = 'pipesim-well-inspection/8') => {
  const packageFiles = structuredClone(versions.well.inspection.packageFiles)
  const document = {
    schemaVersion, reservoirPressure: null, well: null,
    espContexts: ['Well_1:ESP', 'Well_2:ESP'],
    wellContexts: ['Well_1', 'Well_2'].map(context => ({
      context, espContexts: [`${context}:ESP`],
      completionContexts: [`${context}:Completion`], tubingContexts: [`${context}:Tubing`],
      ...(schemaVersion === 'pipesim-well-inspection/8' ? {
        tubingComponents: [{ context: `${context}:Tubing`, innerDiameter: 2.441, unit: 'in' }]
      } : {})
    })),
    packageFiles
  }
  if (schemaVersion === 'pipesim-well-inspection/8') document.networkCapability = {
    schemaVersion: 'pipesim-network-capability/1', studies: ['Base Case'],
    networkInspection: {
      schemaVersion: 'pipesim-network-inspection/3',
      studies: [{ study: 'Base Case', boundaries: structuredClone(versions.network.inspection.studies[0].boundaries) }],
      chokes: [], packageFiles: structuredClone(packageFiles)
    }
  }
  return document
}

for (const inspectionSchema of ['pipesim-well-inspection/6', 'pipesim-well-inspection/8']) {
test(`PIPESIM ESP Head Factor 结果可恢复且方案请求保留无量纲参数合同 ${inspectionSchema}`, async ({ page }) => {
  const run = {
    id: 9353, projectId: 1, modelId: 11, modelVersionId: 101, versionNo: 1, modelName: '井筒演示模型', study: 'Base Case', runType: 'esp-parameter', status: 'SUCCEEDED', resultContract: 'VALID_FULL',
    parameters: { schemaVersion: 'pipesim-esp-parameter-parameters/3', wellContext: 'Well_1', espContext: 'Well_1:ESP', parameter: 'headFactor', targetValue: 0.9 }, events: [], artifacts: [],
    result: {
      schemaVersion: 'pipesim-esp-parameter-result/2', model_kind: 'black_oil_liquid', runTask: 'esp-parameter', resultContract: 'VALID_FULL', producer: 'Well_1', espContext: 'Well_1:ESP',
      parameter: 'headFactor', unit: '1', originalValue: 2.5, targetValue: 0.9, appliedValue: 0.9, sourceModelUnchanged: true, isolatedCopy: true,
      baseline: { parameterValue: 2.5, ipr: [{ flow: 100, pressure: 1200 }], vlp: [{ flow: 100, pressure: 1300 }], profile: [{ depth: 0, pressure: 1500, temperature: 80 }] },
      target: { parameterValue: 0.9, ipr: [{ flow: 100, pressure: 1204.3557 }], vlp: [{ flow: 100, pressure: 1301.335 }], profile: [{ depth: 0, pressure: 1569.4455, temperature: 92.4913 }] },
      comparison: { maxIprPressureDelta: 4.3557, maxVlpPressureDelta: 1.335, maxProfilePressureDelta: 69.4455, maxProfileTemperatureDelta: 12.4913 }
    }
  }
  const capabilities = structuredClone(availableCapabilities())
  capabilities.pipesimWell.runTasks.push('esp-parameter', 'esp-curves')
  const state = await installMockBackend(page, { capabilities, wellHistory: [summary(run)], runs: [run] })
  state.models[0].versions[0].inspection = espMultiWellInspection(inspectionSchema)
  await openWorkspace(page)
  await activateModel(page, '井筒演示模型')
  await expect(page.locator('label.el-radio-button').filter({ hasText: '节点分析' })).toHaveCount(0)
  const objectInventory = page.getByRole('region', { name: 'PIPESIM 井与 ESP 对象清单', exact: true })
  await expect(objectInventory.locator('details')).not.toHaveAttribute('open', '')
  await objectInventory.locator('summary').click()
  await expect(objectInventory.getByText('Well_1', { exact: true })).toBeVisible()
  await expect(objectInventory.getByText('Well_2', { exact: true })).toBeVisible()
  await objectInventory.getByRole('combobox', { name: '目标井上下文' }).focus()
  await objectInventory.getByRole('combobox', { name: '目标井上下文' }).press('ArrowDown')
  await page.getByRole('option', { name: 'Well_2', exact: true }).click()
  await expect(page.getByRole('radio', { name: 'ESP 曲线', exact: true })).toHaveCount(1)
  await page.getByRole('tab', { name: 'ESP 参数方案', exact: true }).click()
  const result = page.getByRole('region', { name: 'PIPESIM ESP 单参数方案结果', exact: true })
  await expect(result).toContainText('ESP 扬程系数 Head Factor')
  await expect(result).toContainText('2.500')
  await expect(result).toContainText('0.900 无量纲（1）')
  await expect(result).toContainText('4.356')

  await page.locator('label.el-radio-button').filter({ hasText: 'ESP 参数方案' }).click()
  const editor = page.getByRole('region', { name: 'PIPESIM ESP 单参数方案', exact: true })
  await editor.getByRole('combobox', { name: 'ESP 上下文' }).focus()
  await editor.getByRole('combobox', { name: 'ESP 上下文' }).press('ArrowDown')
  await page.getByRole('option', { name: 'Well_2:ESP', exact: true }).click()
  const parameterSelect = editor.getByRole('combobox', { name: 'ESP 方案参数' })
  await parameterSelect.focus()
  await parameterSelect.press('ArrowDown')
  await page.getByRole('option', { name: '扬程系数 Head Factor (无量纲)' }).click()
  await editor.getByRole('spinbutton', { name: 'ESP 方案目标值' }).fill('0.9')
  await page.getByRole('button', { name: '运行', exact: true }).click()
  await expect.poll(() => state.createPayloads.length).toBe(1)
  expect(state.createPayloads[0].payload).toEqual({
    study: 'Base Case',
    runType: 'esp-parameter',
    parameters: { schemaVersion: 'pipesim-esp-parameter-parameters/3', wellContext: 'Well_2', espContext: 'Well_2:ESP', parameter: 'headFactor', targetValue: 0.9 }
  })
  expect(state.unexpectedRequests).toEqual([])
})

}

for (const espTask of ['esp-curves', 'esp-parameter']) {
test(`PIPESIM /8 多井 ESP ${espTask} 提交选定井和泵的上下文`, async ({ page }) => {
  const capabilities = structuredClone(availableCapabilities())
  capabilities.pipesimWell.runTasks.push(espTask)
  const state = await installMockBackend(page, { capabilities })
  state.models[0].versions[0].inspection = espMultiWellInspection()
  await openWorkspace(page)
  await activateModel(page, '井筒演示模型')
  const inventory = page.getByRole('region', { name: 'PIPESIM 井与 ESP 对象清单', exact: true })
  const well = inventory.getByRole('combobox', { name: '目标井上下文' })
  await well.focus()
  await well.press('ArrowDown')
  await page.getByRole('option', { name: 'Well_2', exact: true }).click()
  await page.locator('.run-type-control').getByText(espTask === 'esp-curves' ? 'ESP 曲线' : 'ESP 参数方案', { exact: true }).click()
  if (espTask === 'esp-parameter') await page.getByRole('spinbutton', { name: 'ESP 方案目标值' }).fill('70')
  await page.getByRole('button', { name: '运行', exact: true }).click()
  await expect.poll(() => state.createPayloads.length).toBe(1)
  const parameters = espTask === 'esp-curves' ? {
    schemaVersion: 'pipesim-esp-curves-parameters/2', wellContext: 'Well_2', espContext: 'Well_2:ESP'
  } : {
    schemaVersion: 'pipesim-esp-parameter-parameters/3', wellContext: 'Well_2', espContext: 'Well_2:ESP',
    parameter: 'operatingFrequency', targetValue: 70
  }
  expect(state.createPayloads[0].payload).toEqual({ study: 'Base Case', runType: espTask, parameters })
  expect(state.unexpectedRequests).toEqual([])
})
}

for (const missing of ['completion', 'tubing', 'both']) {
test(`PIPESIM /8 目标井缺少 ${missing} 时不开放 ESP 任务且不自动提交`, async ({ page }) => {
  const capabilities = structuredClone(availableCapabilities())
  capabilities.pipesimWell.runTasks.push('esp-curves', 'esp-parameter', 'trajectory')
  const state = await installMockBackend(page, { capabilities })
  const document = espMultiWellInspection()
  const target = document.wellContexts[1]
  if (missing !== 'tubing') target.completionContexts = []
  if (missing !== 'completion') {
    target.tubingContexts = []
    target.tubingComponents = []
  }
  state.models[0].versions[0].inspection = document
  await openWorkspace(page)
  await activateModel(page, '井筒演示模型')
  const inventory = page.getByRole('region', { name: 'PIPESIM 井与 ESP 对象清单', exact: true })
  const well = inventory.getByRole('combobox', { name: '目标井上下文' })
  await well.focus()
  await well.press('ArrowDown')
  await page.getByRole('option', { name: 'Well_1', exact: true }).click()
  const tasks = page.locator('.run-type-control')
  await expect(tasks.getByRole('radio', { name: 'ESP 曲线', exact: true })).toBeVisible()
  await tasks.getByText('ESP 参数方案', { exact: true }).click()
  await expect(page.getByRole('region', { name: 'PIPESIM ESP 单参数方案', exact: true })).toBeVisible()
  await well.focus()
  await well.press('ArrowDown')
  await page.getByRole('option', { name: 'Well_2', exact: true }).click()
  await inventory.locator('summary').click()
  await expect(inventory.getByText('ESP：Well_2:ESP', { exact: true })).toBeVisible()
  await expect(tasks.getByRole('radio', { name: 'ESP 曲线', exact: true })).toHaveCount(0)
  await expect(tasks.getByRole('radio', { name: 'ESP 参数方案', exact: true })).toHaveCount(0)
  await expect(tasks.getByRole('radio', { name: '井轨迹', exact: true })).toBeChecked()
  await expect(page.getByRole('region', { name: 'PIPESIM ESP 单参数方案', exact: true })).toHaveCount(0)
  await expect(page.getByRole('region', { name: 'PIPESIM ESP 曲线对象', exact: true })).toHaveCount(0)
  expect(state.createPayloads).toEqual([])
  expect(state.unexpectedRequests).toEqual([])
})
}

test('PIPESIM VFP 表生成固定使用当前版本已验证的生产井对象', async ({ page }) => {
  const capabilities = structuredClone(availableCapabilities())
  capabilities.pipesimWell.runTasks.push('vfp-tables')
  const state = await installMockBackend(page, { capabilities })
  await openWorkspace(page)
  await activateModel(page, '井筒演示模型')
  await page.locator('label.el-radio-button').filter({ hasText: 'VFP 表生成' }).click()

  const editor = page.getByRole('region', { name: 'PIPESIM VFP 表参数', exact: true })
  const producer = editor.getByRole('textbox', { name: 'VFP 生产井' })
  await expect(producer).toHaveValue('Well_1')
  await expect(producer).toBeDisabled()
  await page.getByRole('button', { name: '运行', exact: true }).click()
  await expect.poll(() => state.createPayloads.length).toBe(1)
  expect(state.createPayloads[0].payload).toMatchObject({
    runType: 'vfp-tables',
    parameters: { schemaVersion: 'pipesim-vfp-tables-parameters/1', producer: 'Well_1', reservoirSimulator: 'ECLIPSE' }
  })
  expect(state.unexpectedRequests).toEqual([])
})

test('PIPESIM VFP 结果按 VFPPROD 的 THP 行和 LIQ 数值轴展示并导出', async ({ page }) => {
  const capabilities = structuredClone(availableCapabilities())
  capabilities.pipesimWell.runTasks.push('vfp-tables')
  const axes = {
    liquidRatesStbPerDay: [100, 200, 300], outletPressuresPsi: [250, 350],
    waterCutFraction: [0.4], gorMscfPerStb: [0.265], artificialLiftInjectionDpPsi: [40]
  }
  const row = (thpIndex, values, temperatureValues) => ({ thpIndex, waterCutIndex: 1, gorIndex: 1, artificialLiftIndex: 1, values, temperatureValues })
  const rows = [row(1, [101, 102, 103], [51, 52, 53]), row(2, [201, 202, 203], [61, 62, 63])]
  const run = {
    id: 9360, projectId: 1, modelId: 11, modelVersionId: 101, versionNo: 1, modelName: '井筒演示模型', study: 'Base Case', runType: 'vfp-tables', status: 'SUCCEEDED', resultContract: 'VALID_FULL',
    parameters: { schemaVersion: 'pipesim-vfp-tables-parameters/1', producer: 'Well_1', reservoirSimulator: 'ECLIPSE', tableNumber: 2, includeTemperature: true, bottomHoleDatumDepth: 30, liquidRatesStbPerDay: axes.liquidRatesStbPerDay, outletPressuresPsi: axes.outletPressuresPsi, waterCutFraction: axes.waterCutFraction, gorMscfPerStb: axes.gorMscfPerStb, artificialLiftInjectionDpPsi: axes.artificialLiftInjectionDpPsi },
    events: [], artifacts: [
      { id: 9361, name: 'pipesim-vfp-table.vfpprod', type: 'output', sizeBytes: 128, sha256: 'a'.repeat(64), contentType: 'text/plain; charset=utf-8', expiresAt: null },
      { id: 9362, name: 'pipesim-vfp-table-temperature.vfpprod', type: 'output', sizeBytes: 128, sha256: 'b'.repeat(64), contentType: 'text/plain; charset=utf-8', expiresAt: null },
      { id: 9363, name: 'pipesim-vfp-engine-CSW_131_VFP.DATA', type: 'output', sizeBytes: 256, sha256: 'c'.repeat(64), contentType: 'application/octet-stream', expiresAt: null }
    ], result: {
      schemaVersion: 'pipesim-vfp-tables-result/2', model_kind: 'black_oil_liquid', runTask: 'vfp-tables', resultContract: 'VALID_FULL', producer: 'Well_1', reservoirSimulator: 'ECLIPSE', tableNumber: 2, includeTemperature: true, bottomHoleDatumDepth: 30, axes,
      table: { valueName: 'BHP', unit: 'psia', rows: rows.map(({ temperatureValues, ...item }) => item) },
      temperatureTable: { valueName: 'TEMP', unit: 'F', rows: rows.map(({ values: _values, temperatureValues, ...item }) => ({ ...item, values: temperatureValues })) },
      vfpTableContent: 'VFPPROD 2 30.0', vfpTableWithTemperatureContent: 'VFPPROD 2 30.0'
    }
  }
  const state = await installMockBackend(page, { capabilities, wellHistory: [summary(run)], runs: [run] })
  await openWorkspace(page)
  await activateModel(page, '井筒演示模型')
  await page.getByRole('tab', { name: 'VFP 表', exact: true }).click()
  const result = page.getByRole('region', { name: 'PIPESIM VFP 表结果', exact: true })
  await expect(result).toContainText('THP 索引')
  await expect(result).toContainText('100: 101')

  const downloadPromise = page.waitForEvent('download')
  await result.getByRole('button', { name: '导出 VFP CSV', exact: true }).click()
  const stream = await (await downloadPromise).createReadStream()
  let csv = ''
  for await (const chunk of stream) csv += chunk.toString('utf8')
  expect(csv).toContain('thpIndex,waterCutIndex,gorIndex,artificialLiftIndex,thpPsi,liquidRateStbPerDay,bhpPsi,temperatureF')
  expect(csv).toContain('1,1,1,1,250,100,101,51')
  expect(csv).toContain('2,1,1,1,350,300,203,63')

  await expect(result.getByRole('button', { name: '下载官方 VFPPROD', exact: true })).toBeVisible()
  const artifactDownload = page.waitForEvent('download')
  await result.getByRole('button', { name: '下载官方 VFPPROD', exact: true }).click()
  await artifactDownload
  expect(state.downloadRequests).toContainEqual({ runId: 9360, artifactId: 9361 })
  const temperatureDownload = page.waitForEvent('download')
  await result.getByRole('button', { name: '下载温度 VFPPROD', exact: true }).click()
  await temperatureDownload
  expect(state.downloadRequests).toContainEqual({ runId: 9360, artifactId: 9362 })
  const engineDownload = page.waitForEvent('download')
  await result.getByRole('button', { name: '下载引擎文件 · CSW_131_VFP.DATA', exact: true }).click()
  await engineDownload
  expect(state.downloadRequests).toContainEqual({ runId: 9360, artifactId: 9363 })
})

test('PIPESIM 多井井轨迹必须选择目标井并将井上下文写入运行请求', async ({ page }) => {
  const capabilities = structuredClone(availableCapabilities())
  capabilities.pipesimWell.runTasks.push('trajectory')
  const state = await installMockBackend(page, { capabilities })
  state.models[0].versions[0].inspection = {
    schemaVersion: 'pipesim-well-inspection/6', reservoirPressure: null, well: null,
    espContexts: ['Well_1:ESP', 'Well_2:ESP'],
    wellContexts: [
      { context: 'Well_1', espContexts: ['Well_1:ESP'], completionContexts: ['Well_1:Completion'], tubingContexts: ['Well_1:Tubing'] },
      { context: 'Well_2', espContexts: ['Well_2:ESP'], completionContexts: [], tubingContexts: [] }
    ],
    packageFiles: [{ relativePath: 'well-demo.pips', sizeBytes: 512, sha256: 'e'.repeat(64) }]
  }
  await openWorkspace(page)
  await activateModel(page, '井筒演示模型')
  await expect(page.locator('label.el-radio-button').filter({ hasText: '节点分析' })).toHaveCount(0)
  const objectInventory = page.getByRole('region', { name: 'PIPESIM 井与 ESP 对象清单', exact: true })
  await objectInventory.getByRole('combobox', { name: '目标井上下文' }).focus()
  await objectInventory.getByRole('combobox', { name: '目标井上下文' }).press('ArrowDown')
  await page.getByRole('option', { name: 'Well_2', exact: true }).click()
  await expect(objectInventory).toContainText('无完井（流动任务不可运行）')
  await page.locator('label.el-radio-button').filter({ hasText: '井轨迹' }).click()
  await page.getByRole('combobox', { name: '目标井上下文' }).focus()
  await page.getByRole('combobox', { name: '目标井上下文' }).press('ArrowDown')
  await page.getByRole('option', { name: 'Well_2', exact: true }).click()
  await page.getByRole('button', { name: '运行', exact: true }).click()
  await expect.poll(() => state.createPayloads.length).toBe(1)
  expect(state.createPayloads[0].payload).toEqual({
    study: 'Base Case',
    runType: 'trajectory',
    parameters: { schemaVersion: 'pipesim-well-trajectory-parameters/2', wellContext: 'Well_2' }
  })
  expect(state.unexpectedRequests).toEqual([])
})

test('PIPESIM 受控井轨迹方案以单站倾角与逐井上下文提交', async ({ page }) => {
  const capabilities = structuredClone(availableCapabilities())
  capabilities.pipesimWell.runTasks.push('trajectory')
  const state = await installMockBackend(page, { capabilities })
  state.models[0].versions[0].inspection = {
    schemaVersion: 'pipesim-well-inspection/6', reservoirPressure: null, well: 'Well_1',
    espContexts: [],
    wellContexts: [{ context: 'Well_1', espContexts: [], completionContexts: [], tubingContexts: [] }],
    packageFiles: [{ relativePath: 'well-demo.pips', sizeBytes: 512, sha256: 'e'.repeat(64) }]
  }
  await openWorkspace(page)
  await activateModel(page, '井筒演示模型')
  await page.locator('label.el-radio-button').filter({ hasText: '井轨迹' }).click()
  await page.locator('label.el-checkbox').filter({ hasText: '创建倾角方案并重算 Profile' }).click()
  await page.getByRole('button', { name: '运行', exact: true }).click()
  await expect.poll(() => state.createPayloads.length).toBe(1)
  expect(state.createPayloads[0].payload).toEqual({
    study: 'Base Case',
    runType: 'trajectory',
    parameters: { schemaVersion: 'pipesim-well-trajectory-parameters/3', wellContext: 'Well_1', stationIndex: 2, targetInclinationDeg: 26.92574034 }
  })
  expect(state.unexpectedRequests).toEqual([])
})

test('PIPESIM 井轨迹方案结果展示隔离副本回读和同深度 Profile 对比', async ({ page }) => {
  const run = {
    id: 9355, projectId: 1, modelId: 11, modelVersionId: 101, versionNo: 1, modelName: '井筒演示模型', study: 'Study 1', runType: 'trajectory', status: 'SUCCEEDED', resultContract: 'VALID_FULL',
    parameters: { schemaVersion: 'pipesim-well-trajectory-parameters/3', wellContext: 'Well_1', stationIndex: 1, targetInclinationDeg: 26.92574034 }, events: [], artifacts: [],
    result: {
      schemaVersion: 'pipesim-well-trajectory-result/2', model_kind: 'black_oil_liquid', runTask: 'trajectory', resultContract: 'VALID_FULL', producer: 'Well_1',
      units: { measuredDepth: 'ft', trueVerticalDepth: 'ft', inclination: 'deg', azimuth: 'deg', maxDogLegSeverity: 'deg/100ft' },
      points: [
        { measuredDepth: 0, trueVerticalDepth: 0, inclination: 0, azimuth: null, maxDogLegSeverity: null },
        { measuredDepth: 1000, trueVerticalDepth: 900, inclination: 26.92574034, azimuth: null, maxDogLegSeverity: null },
        { measuredDepth: 1800, trueVerticalDepth: 1610, inclination: 26.92574034, azimuth: null, maxDogLegSeverity: null }
      ],
      application: { applied: true, isolatedCopy: true, sourceModelUnchanged: true, artifactName: 'pipesim-trajectory-applied.pips', stationIndex: 1, originalInclinationDeg: 25.92574034, targetInclinationDeg: 26.92574034, maxTvdBeforeFt: 1620, maxTvdAfterFt: 1610 },
      profileComparison: {
        method: 'exact-depth-match-no-interpolation', baselinePointCount: 4, targetPointCount: 4, matchedPointCount: 2,
        baseline: [{ depth: 0, pressure: 200, temperature: 70 }, { depth: 100, pressure: 300, temperature: 80 }, { depth: 100, pressure: 301, temperature: 80.5 }, { depth: 200, pressure: 400, temperature: 90 }],
        target: [{ depth: 0, pressure: 200, temperature: 70 }, { depth: 100, pressure: 302, temperature: 81 }, { depth: 200, pressure: 405, temperature: 92 }, { depth: 300, pressure: 450, temperature: 95 }],
        matchedPoints: [
          { depth: 0, baselinePressure: 200, targetPressure: 200, pressureDelta: 0, baselineTemperature: 70, targetTemperature: 70, temperatureDelta: 0 },
          { depth: 200, baselinePressure: 400, targetPressure: 405, pressureDelta: 5, baselineTemperature: 90, targetTemperature: 92, temperatureDelta: 2 }
        ],
        maxAbsPressureDelta: 5, maxAbsTemperatureDelta: 2
      }
    }
  }
  const capabilities = structuredClone(availableCapabilities())
  capabilities.pipesimWell.runTasks.push('trajectory')
  const state = await installMockBackend(page, { capabilities, wellHistory: [summary(run)], runs: [run] })
  await openWorkspace(page)
  await activateModel(page, '井筒演示模型')
  await page.getByRole('tab', { name: '井轨迹', exact: true }).click()
  const result = page.getByRole('region', { name: 'PIPESIM 井轨迹结果', exact: true })
  const comparison = result.getByRole('region', { name: '轨迹方案及剖面对比', exact: true })
  await expect(comparison).toContainText('隔离副本已保存并重新打开核对')
  await expect(comparison).toContainText('pipesim-trajectory-applied.pips')
  await expect(comparison).toContainText('共同深度点 2 / 4 基线 · 4 方案')
  await expect(comparison).toContainText('重复深度坐标 1 组未参与精确配对')
  await expect(comparison).toContainText('同深度点最大 |Δ压力|：5.000')
  await expect(comparison.getByRole('img', { name: '压力基线和方案曲线' })).toBeVisible()
  await expect(comparison.locator('.el-table')).toBeVisible()
  expect(state.unexpectedRequests).toEqual([])
})

test('PIPESIM 井轨迹展示官方点位并生成三维派生坐标', async ({ page }) => {
  const run = {
    id: 9352, projectId: 1, modelId: 11, modelVersionId: 101, versionNo: 1, modelName: '井筒演示模型', study: 'Study 1', runType: 'trajectory', status: 'SUCCEEDED', resultContract: 'VALID_FULL',
    parameters: { schemaVersion: 'pipesim-well-trajectory-parameters/1' }, events: [], artifacts: [],
    result: {
      schemaVersion: 'pipesim-well-trajectory-result/1', model_kind: 'black_oil_liquid', runTask: 'trajectory', resultContract: 'VALID_FULL', producer: 'Well_1',
      units: { measuredDepth: 'ft', trueVerticalDepth: 'ft', inclination: 'deg', azimuth: 'deg', maxDogLegSeverity: 'deg/100ft' },
      points: [
        { measuredDepth: 0, trueVerticalDepth: 0, inclination: 0, azimuth: null, maxDogLegSeverity: null },
        { measuredDepth: 1000, trueVerticalDepth: 980, inclination: 25, azimuth: 90, maxDogLegSeverity: 1.2 },
        { measuredDepth: 1800, trueVerticalDepth: 1500, inclination: 45, azimuth: 90, maxDogLegSeverity: 1.4 }
      ]
    }
  }
  const capabilities = structuredClone(availableCapabilities())
  capabilities.pipesimWell.runTasks.push('trajectory')
  const state = await installMockBackend(page, { capabilities, wellHistory: [summary(run)], runs: [run] })
  await openWorkspace(page)
  await activateModel(page, '井筒演示模型')
  await page.getByRole('tab', { name: '井轨迹', exact: true }).click()
  const result = page.getByRole('region', { name: 'PIPESIM 井轨迹结果', exact: true })
  await expect(result).toContainText('Well_1')
  await expect(result).toContainText('3')
  await expect(result).toContainText('三维')
  await expect(result).toContainText('X')
  const downloadPromise = page.waitForEvent('download')
  await result.getByRole('button', { name: '导出轨迹 CSV', exact: true }).click()
  const stream = await (await downloadPromise).createReadStream()
  let csv = ''
  for await (const chunk of stream) csv += chunk.toString('utf8')
  expect(csv).toContain('measuredDepth')
  expect(csv).toContain('Well_1')
  expect(state.unexpectedRequests).toEqual([])
})

test('PIPESIM 轨迹缺少方位时仅显示 MD/TVD 剖面且不伪造 XYZ', async ({ page }) => {
  const run = {
    id: 9354, projectId: 1, modelId: 11, modelVersionId: 101, versionNo: 1, modelName: '井筒演示模型', study: 'Study 1', runType: 'trajectory', status: 'SUCCEEDED', resultContract: 'VALID_FULL',
    parameters: { schemaVersion: 'pipesim-well-trajectory-parameters/1' }, events: [], artifacts: [],
    result: {
      schemaVersion: 'pipesim-well-trajectory-result/1', model_kind: 'black_oil_liquid', runTask: 'trajectory', resultContract: 'VALID_FULL', producer: 'Well_1',
      units: { measuredDepth: 'ft', trueVerticalDepth: 'ft', inclination: 'deg', azimuth: 'deg', maxDogLegSeverity: 'deg/100ft' },
      points: [
        { measuredDepth: 0, trueVerticalDepth: 0, inclination: 0, azimuth: null, maxDogLegSeverity: null },
        { measuredDepth: 1000, trueVerticalDepth: 980, inclination: 25, azimuth: null, maxDogLegSeverity: null },
        { measuredDepth: 1800, trueVerticalDepth: 1500, inclination: 45, azimuth: null, maxDogLegSeverity: null }
      ]
    }
  }
  const capabilities = structuredClone(availableCapabilities())
  capabilities.pipesimWell.runTasks.push('trajectory')
  const state = await installMockBackend(page, { capabilities, wellHistory: [summary(run)], runs: [run] })
  await openWorkspace(page)
  await activateModel(page, '井筒演示模型')
  await page.getByRole('tab', { name: '井轨迹', exact: true }).click()
  const result = page.getByRole('region', { name: 'PIPESIM 井轨迹结果', exact: true })
  await expect(result).toContainText('仅 MD/TVD 剖面')
  await expect(result).toContainText('不生成或暗示空间 X/Y 定位')
  await expect(result.getByLabel('MD/TVD 井剖面图')).toBeVisible()
  await expect(result.getByRole('radio', { name: '三维' })).toHaveCount(0)
  await expect(result).not.toContainText('选中点相对派生坐标')

  const downloadPromise = page.waitForEvent('download')
  await result.getByRole('button', { name: '导出轨迹 CSV' }).click()
  const stream = await (await downloadPromise).createReadStream()
  let csv = ''
  for await (const chunk of stream) csv += chunk.toString('utf8')
  expect(csv).toContain('coordinateMode')
  expect(csv).toContain('md-tvd-profile-no-spatial-azimuth')
  expect(csv).toContain('"","",""')
  expect(state.unexpectedRequests).toEqual([])
})

test('PIPESIM Network 组态保持真实数量、设备图例与既有结果访问', async ({ page }, testInfo) => {
  const componentTypes = [
    'Well', 'Sink', 'Junction', 'ControlValve', 'Pump', 'Compressor',
    'Separator', 'HeatExchanger', 'ProcessTank', 'Flowline', 'CustomDevice'
  ]
  const nodes = componentTypes.map((componentType, index) => ({ id: `N${index + 1}`, componentType }))
  const edges = nodes.slice(0, -1).map((node, index) => ({
    source: node.id,
    destination: nodes[index + 1].id,
    sourcePort: index === 0 ? 'outlet' : ''
  }))
  const result = {
    schemaVersion: 'pipesim-network-result/1',
    model_kind: 'network',
    runTask: 'network',
    resultContract: 'VALID_FULL',
    study: 'Network Base',
    simulationState: 'Completed',
    topology: {
      nodes,
      edges,
      counts: { nodes: 11, edges: 10, sources: 1, sinks: 1, flowlines: 1 }
    },
    system: [{ variable: 'SystemOutletPressure', unit: 'bara', values: [{ name: 'Network', value: 82.5 }] }],
    node: [{ variable: 'Pressure', unit: 'bara', values: Array.from({ length: 25 }, (_, index) => ({ name: `N${index + 1}`, value: index === 1 ? 81.2 : 80 - index })) }],
    profiles: [{
      branch: 'N1-N2',
      pointCount: 2,
      variables: [
        { variable: 'BranchEquipment', unit: '', values: ['Flowline-A', 'Flowline-A'] },
        { variable: 'TotalDistance', unit: 'm', values: [0, 100] },
        { variable: 'Pressure', unit: 'bara', values: [90, 81.2] }
      ]
    }, {
      branch: 'Comparison-branch', pointCount: 3,
      variables: [
        { variable: 'TotalDistance', unit: 'm', values: [15, 75, 125] },
        { variable: 'Pressure', unit: 'bara', values: [91, 85, 79] }
      ]
    }],
    summary: { info: ['Network calculation completed'], warnings: [], errors: [] },
    messages: [],
    quality: [
      { path: 'node.Pressure.N2', code: 'UNAVAILABLE' },
      { path: 'profiles.N1-N2.Pressure[1]', code: 'NON_FINITE' },
      { path: 'system.SystemOutletPressure.Network', code: 'UNAVAILABLE' }
    ]
  }
  const historical = {
    id: 8501,
    projectId: 1,
    modelId: 33,
    modelVersionId: 301,
    modelName: '管网演示模型',
    versionNo: 1,
    study: 'Network Base',
    runType: 'network',
    status: 'SUCCEEDED',
    resultContract: 'VALID_FULL',
    result,
    createdAt: '2026-09-13T05:00:00',
    elapsedMillis: 12000,
    cancellable: false,
    events: [],
    artifacts: []
  }
  const current = structuredClone(historical)
  current.id = 8502
  current.createdAt = '2026-09-13T06:00:00'
  current.parameters = { schemaVersion: 'pipesim-network-parameters/1', boundaries: [{ node: 'Terminal', pressure: 900 }] }
  current.result.system[0].values[0].value = 83.5
  current.result.node[0].values[1].value = 81.8
  current.result.profiles[0].variables.find(item => item.variable === 'Pressure').values = [90, 81.8]
  const state = await installMockBackend(page, {
    networkHistory: [summary(current), summary(historical)],
    runs: [current, historical]
  })
  await openWorkspace(page)
  await activateModel(page, '管网演示模型')

  await page.getByRole('tab', { name: '工程包' }).click()
  await expect(page.getByRole('region', { name: 'PIPESIM 模型工程包依赖' })).toContainText('network-demo.pips')
  await page.getByRole('tab', { name: '管网结果' }).click()

  await expect(page.getByText('11 个节点 / 10 条连接', { exact: true })).toBeVisible()
  const scenarioAcceptance = page.getByTestId('network-scenario-acceptance')
  await expect(scenarioAcceptance).toContainText('管网边界方案验收')
  await expect(scenarioAcceptance).toContainText('Terminal')
  await expect(scenarioAcceptance).toContainText('压力：900')
  await expect(scenarioAcceptance).toContainText('隔离计算副本')
  const overview = page.getByRole('region', { name: '管网支路工况总览' })
  await expect(overview).toContainText('首点减末点')
  await overview.getByRole('textbox', { name: '搜索工况支路' }).fill('Comparison')
  const overviewDownload = page.waitForEvent('download')
  await overview.getByRole('button', { name: '导出工况 CSV' }).click()
  const overviewStream = await (await overviewDownload).createReadStream()
  let overviewCsv = ''
  for await (const chunk of overviewStream) overviewCsv += chunk.toString('utf8')
  expect(overviewCsv).toContain('"8502","Network Base","Comparison-branch","bara","3","91","79","12","79","91","0"')
  await overview.getByRole('button', { name: '查看剖面' }).click()
  await expect(page.locator('.profile-controls')).toContainText('Comparison-branch')
  await overview.getByRole('textbox', { name: '搜索工况支路' }).fill('N1-N2')
  await overview.getByRole('button', { name: '查看剖面' }).click()
  await overview.getByRole('textbox', { name: '搜索工况支路' }).fill('')
  for (const viewport of [{ width: 1440, height: 900 }, { width: 1280, height: 720 }]) {
    await page.setViewportSize(viewport)
    await overview.scrollIntoViewIfNeeded()
    await page.screenshot({ path: testInfo.outputPath(`branch-overview-${viewport.width}.png`) })
  }
  const topologyStats = page.locator('.count-strip[aria-label="管网拓扑统计"]')
  await expect(topologyStats.getByText('节点', { exact: true })).toBeVisible()
  await expect(topologyStats.getByText('11', { exact: true })).toBeVisible()
  await expect(topologyStats.getByText('连接', { exact: true })).toBeVisible()
  await expect(topologyStats.getByText('10', { exact: true })).toBeVisible()

  const topologyCanvas = page.getByTestId('network-topology-canvas')
  await expect(topologyCanvas).toHaveAttribute('role', 'application')
  await expect(topologyCanvas).toHaveAttribute('aria-label', 'PIPESIM 返回的管网组态，可拖动节点、平移和滚轮缩放')
  await topologyCanvas.hover()
  await page.mouse.wheel(0, -120)
  const box = await topologyCanvas.boundingBox()
  expect(box).not.toBeNull()
  await page.mouse.move(box.x + box.width * 0.65, box.y + box.height * 0.65)
  await page.mouse.down()
  await page.mouse.move(box.x + box.width * 0.55, box.y + box.height * 0.55, { steps: 3 })
  await page.mouse.up()

  const fitButton = page.getByTestId('network-topology-fit')
  await expect(fitButton).toHaveAttribute('aria-label', '重新应用确定性布局并适应视图')
  await fitButton.click()
  await expect(topologyCanvas).toBeVisible()

  await expect(page.getByText('箭头仅表示返回的连接方向，不代表实际流向。', { exact: false })).toBeVisible()
  await page.getByText('设备符号', { exact: true }).click()
  for (const deviceType of ['well', 'sink', 'junction', 'control', 'pump', 'compressor', 'separator', 'thermal', 'process', 'pipe', 'unknown']) {
    await expect(page.locator(`[data-device-type="${deviceType}"]`)).toBeVisible()
  }
  await expect(page.locator('.topology-chart')).toHaveCount(1)
  const topologyDownload = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出拓扑 CSV', exact: true }).click()
  const topologyStream = await (await topologyDownload).createReadStream()
  let topologyCsv = ''
  for await (const chunk of topologyStream) topologyCsv += chunk.toString('utf8')
  expect(topologyCsv).toContain('"记录类型","ID","组件类型","源","目标","源端口"')
  expect(topologyCsv).toContain('"节点","N2","Sink","","",""')
  expect(topologyCsv).toContain('"连接","","","N1","N2","outlet"')
  await page.getByRole('combobox', { name: '搜索管网设备' }).click()
  await page.getByRole('option', { name: 'N2', exact: true }).click()
  const selection = page.getByLabel('管网点选结果')
  await expect(selection.getByText('81.8', { exact: true })).toBeVisible()
  await expect(selection.getByText('未返回可精确匹配的支路结果')).toBeVisible()
  await page.getByRole('button', { name: '保存布局', exact: true }).click()
  await expect(page.getByText('当前运行的布局已保存到本浏览器。')).toBeVisible()
  const saved = await page.evaluate(() => JSON.parse(localStorage.getItem('grdp:network-layout:v1:run:8502')))
  expect(saved.positions).toHaveLength(11)
  saved.positions[0].x += 27
  await page.evaluate(snapshot => localStorage.setItem('grdp:network-layout:v1:run:8502', JSON.stringify(snapshot)), saved)
  await activateModel(page, 'ECLIPSE演示模型')
  await activateModel(page, '管网演示模型')
  await page.getByRole('button', { name: '保存布局', exact: true }).click()
  expect(await page.evaluate(() => JSON.parse(localStorage.getItem('grdp:network-layout:v1:run:8502')).positions)).toEqual(saved.positions)
  await page.getByText('设备符号', { exact: true }).click()
  await page.getByTestId('network-topology-fit').click()
  expect(await page.evaluate(() => localStorage.getItem('grdp:network-layout:v1:run:8502'))).toBeNull()

  await expect(page.getByText('分支剖面', { exact: true })).toBeVisible()
  await page.getByText('选择同单位支路（最多 4 条）', { exact: true }).click()
  await page.getByRole('option', { name: 'Comparison-branch', exact: true }).click()
  await page.getByRole('combobox', { name: '对比管网支路' }).press('Escape')
  await expect(page.locator('.profile-panel .el-table__body-wrapper').getByText('Comparison-branch', { exact: true })).toHaveCount(3)
  const profileDownload = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出剖面 CSV', exact: true }).click()
  const profileStream = await (await profileDownload).createReadStream()
  let profileCsv = ''
  for await (const chunk of profileStream) profileCsv += chunk.toString('utf8')
  expect(profileCsv).toContain('"8502","Network Base","Comparison-branch","15","m","Pressure","91","bara"')
  expect(profileCsv).toContain('"N1-N2","100","m","Pressure","81.8","bara"')
  await page.getByRole('combobox', { name: '对比历史管网运行' }).click()
  await page.getByRole('option', { name: '运行 #8501 · Network Base', exact: true }).click()
  await expect(page.locator('.profile-panel .el-table__body-wrapper')).toContainText('N1-N2 · 历史运行 #8501')
  const historicalProfileDownload = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出剖面 CSV', exact: true }).click()
  const historicalProfileStream = await (await historicalProfileDownload).createReadStream()
  let historicalProfileCsv = ''
  for await (const chunk of historicalProfileStream) historicalProfileCsv += chunk.toString('utf8')
  expect(historicalProfileCsv).toContain('"8501","Network Base","N1-N2 · 历史运行 #8501","100","m","Pressure","81.2","bara"')
  await expect(page.getByRole('tab', { name: '系统结果 (1)' })).toBeVisible()
  const systemResultPanel = page.getByRole('tabpanel', { name: '系统结果 (1)' })
  await expect(systemResultPanel.getByText('SystemOutletPressure', { exact: false }).first()).toBeVisible()
  await expect(systemResultPanel.getByText(/当前值与历史运行 #8501/)).toBeVisible()
  await expect(systemResultPanel.getByText('83.5', { exact: true })).toBeVisible()
  await expect(systemResultPanel.getByText('82.5', { exact: true })).toBeVisible()
  await expect(systemResultPanel.getByText('1', { exact: true }).first()).toBeVisible()
  const systemDownload = page.waitForEvent('download')
  await systemResultPanel.getByRole('button', { name: '导出当前表格 CSV', exact: true }).click()
  const systemStream = await (await systemDownload).createReadStream()
  let systemCsv = ''
  for await (const chunk of systemStream) systemCsv += chunk.toString('utf8')
  expect(systemCsv).toContain('"变量","单位","对象","当前值","历史值","差值（当前−历史）"')
  expect(systemCsv).toContain('"SystemOutletPressure","bara","Network","83.5","82.5","1"')
  await page.getByRole('tab', { name: '节点结果 (1)' }).click()
  const nodeResultPanel = page.getByRole('tabpanel', { name: '节点结果 (1)' })
  await expect(nodeResultPanel.getByText('Pressure (bara)', { exact: true }).first()).toBeVisible()
  await expect(nodeResultPanel.getByText('N2', { exact: true })).toBeVisible()
  await expect(nodeResultPanel.getByText('81.8', { exact: true })).toBeVisible()
  await expect(nodeResultPanel.getByText('80', { exact: true }).first()).toBeVisible()
  await expect(nodeResultPanel.getByText('0.6', { exact: true })).toBeVisible()
  await expect(nodeResultPanel.locator('.network-pagination')).toContainText('25')
  await nodeResultPanel.locator('.network-pagination .btn-next').click()
  await expect(nodeResultPanel.getByText('N25', { exact: true })).toBeVisible()
  await page.getByRole('tab', { name: '消息与质量' }).click()
  const diagnosticsPanel = page.getByRole('tabpanel', { name: '消息与质量' })
  await expect(diagnosticsPanel.getByText('node.Pressure.N2', { exact: true })).toBeVisible()
  await expect(diagnosticsPanel.getByRole('button', { name: '定位节点 N2', exact: true })).toBeVisible()
  await expect(diagnosticsPanel.getByText('系统结果', { exact: true })).toBeVisible()
  await diagnosticsPanel.getByRole('button', { name: '定位节点 N2', exact: true }).click()
  await expect(page.getByLabel('管网点选结果')).toContainText('N2')
  await diagnosticsPanel.getByRole('button', { name: '定位支路 N1-N2', exact: true }).click()
  await expect(page.locator('.profile-panel')).toContainText('N1-N2')
  await activateModel(page, 'ECLIPSE演示模型')
  await activateModel(page, '管网演示模型')
  await expect(page.locator('.profile-panel .el-table__body-wrapper').getByText('Comparison-branch', { exact: true })).toHaveCount(0)
  await expect(page.getByText('N1-N2', { exact: true }).first()).toBeVisible()
  for (const viewport of [{ width: 1440, height: 900 }, { width: 1280, height: 720 }]) {
    await page.setViewportSize(viewport)
    await page.locator('.topology-panel').scrollIntoViewIfNeeded()
    await page.screenshot({ path: testInfo.outputPath(`network-${viewport.width}.png`) })
    const bounds = await page.locator('.topology-panel').boundingBox()
    expect(bounds.x + bounds.width).toBeLessThanOrEqual(viewport.width)
  }
  expect(state.unexpectedRequests).toEqual([])
})

test('PIPESIM Network 边界方案只提交勾选节点的覆盖值', async ({ page }) => {
  const state = await installMockBackend(page)
  await openWorkspace(page)
  await activateModel(page, '管网演示模型')
  const editor = page.getByRole('region', { name: '管网边界条件方案', exact: true })
  await expect(editor).toContainText('Supply_1（Source）')
  const sourceRow = editor.locator('.network-boundary-row').filter({ hasText: 'Supply_1' })
  await sourceRow.getByRole('button', { name: '覆盖：Supply_1（Source）', exact: true }).click()
  await sourceRow.locator('.el-select').click()
  await page.getByRole('option', { name: '压力', exact: true }).click()
  await sourceRow.getByRole('spinbutton', { name: '边界覆盖值' }).fill('1500')
  await page.getByRole('button', { name: '运行', exact: true }).click()
  expect(state.createPayloads[0].payload).toEqual({
    study: 'Network Base',
    runType: 'network',
    parameters: { schemaVersion: 'pipesim-network-parameters/1', boundaries: [{ node: 'Supply_1', pressure: 1500 }] }
  })
  expect(state.unexpectedRequests).toEqual([])
})

test('PIPESIM Network Flowline 内径方案绑定原值和基线并提交可持久化工程合同', async ({ page }) => {
  const inspection = structuredClone(versions.network.inspection)
  inspection.schemaVersion = 'pipesim-network-inspection/5'
  inspection.chokes = []
  inspection.wellContexts = []
  inspection.flowlineContexts = ['FL-2']
  inspection.flowlines = [{ name: 'FL-2', innerDiameter: 4, unit: 'in' }]
  const baseline = (id, createdAt) => ({
    id, projectId: 1, modelId: 33, modelVersionId: 301, modelName: '管网演示模型', versionNo: 1,
    study: 'Network Base', runType: 'network', status: 'SUCCEEDED', resultContract: 'VALID_FULL',
    parameters: null, createdAt, elapsedMillis: 2500, cancellable: false, events: [], artifacts: []
  })
  const state = await installMockBackend(page, {
    networkInspection: inspection,
    networkHistory: [summary(baseline(8801, '2026-09-20T07:00:00'))],
    runs: [baseline(8801, '2026-09-20T07:00:00')]
  })
  await openWorkspace(page)
  await activateModel(page, '管网演示模型')
  const editor = page.getByRole('region', { name: '管网边界条件方案', exact: true })
  await page.getByText('创建 Flowline 内径派生工程并重算', { exact: true }).click()
  await expect(editor).toContainText('检查原值：4 in')
  const target = page.getByRole('spinbutton', { name: '目标 Flowline 内径' })
  await target.fill('6')
  await page.getByRole('button', { name: '运行', exact: true }).click()

  await expect.poll(() => state.createPayloads.length).toBe(1)
  const submitted = state.createPayloads[0].payload
  expect(submitted.study).toBe('Network Base')
  expect(submitted.runType).toBe('network')
  expect(submitted.parameters).toMatchObject({
    schemaVersion: 'pipesim-network-flowline-diameter-parameters/1',
    flowline: 'FL-2', originalInnerDiameter: 4, unit: 'in', targetInnerDiameter: 6
  })
  expect(submitted.parameters.baselineRunId).toBe(8801)
  expect(state.unexpectedRequests).toEqual([])
})

test('PIPESIM 单根油管内径方案绑定成功Profile基线并提交派生模型合同', async ({ page }) => {
  const baseline = id => ({
    id, projectId: 1, modelId: 11, modelVersionId: 101, modelName: '井筒演示模型', versionNo: 1,
    study: 'Base Case', runType: 'profile', status: 'SUCCEEDED', resultContract: 'VALID_FULL', parameters: null,
    elapsedMillis: 1800, cancellable: false, events: [], artifacts: []
  })
  const latestBaseline = baseline(8804)
  const state = await installMockBackend(page, {
    wellHistory: [summary(latestBaseline)],
    runs: [latestBaseline]
  })
  state.models[0].versions[0].inspection = {
    schemaVersion: 'pipesim-well-inspection/8', reservoirPressure: null, well: 'Well_1', espContexts: [],
    wellContexts: [{
      context: 'Well_1', espContexts: [], completionContexts: ['Well_1:Completion'], tubingContexts: ['Tubing_1'],
      tubingComponents: [{ context: 'Tubing_1', innerDiameter: 2.441, unit: 'in' }]
    }],
    networkCapability: {
      schemaVersion: 'pipesim-network-capability/1', studies: ['Base Case'],
      networkInspection: {
        schemaVersion: 'pipesim-network-inspection/5', studies: [{ study: 'Base Case', boundaries: [{
          node: 'Source_1', boundaryNodeType: 'Source', isActive: true, isSurfaceCondition: true,
          flowRateType: 'GasFlowRate', pressure: 120, temperature: 80, gasFlowRate: 5,
          liquidFlowRate: null, massFlowRate: null
        }] }], chokes: [], packageFiles: [{ relativePath: 'well-demo.pips', sizeBytes: 512, sha256: 'e'.repeat(64) }],
        wellContexts: [], flowlineContexts: [], flowlines: []
      }
    },
    packageFiles: [{ relativePath: 'well-demo.pips', sizeBytes: 512, sha256: 'e'.repeat(64) }]
  }
  await openWorkspace(page)
  await activateModel(page, '井筒演示模型')
  await page.locator('.run-type-control').getByText('PT 剖面', { exact: true }).click()
  const editor = page.getByTestId('well-tubing-diameter-scenario')
  await editor.getByText('创建单根油管内径派生工程并重算 Profile', { exact: true }).click()
  await expect(editor).toContainText('检查原值：2.441 in')
  await editor.getByRole('spinbutton', { name: '目标油管内径' }).fill('2.3')
  await page.getByRole('button', { name: '运行', exact: true }).click()

  await expect.poll(() => state.createPayloads.length).toBe(1)
  expect(state.createPayloads[0].payload).toEqual({
    study: 'Base Case',
    runType: 'profile',
    parameters: {
      schemaVersion: 'pipesim-well-tubing-diameter-parameters/1', baselineRunId: 8804,
      wellContext: 'Well_1', tubingContext: 'Tubing_1', originalInnerDiameter: 2.441, unit: 'in', targetInnerDiameter: 2.3
    }
  })
  expect(state.unexpectedRequests).toEqual([])
})

test('PIPESIM Network 单连接重接绑定完整拓扑基线并提交受控方案', async ({ page }) => {
  const topology = {
    nodes: [
      { id: 'Source-1', componentType: 'Source' }, { id: 'Flowline-1', componentType: 'Flowline' },
      { id: 'Junction-1', componentType: 'Junction' }, { id: 'Junction-2', componentType: 'Junction' },
      { id: 'Sink-1', componentType: 'Sink' }
    ],
    edges: [
      { source: 'Source-1', sourcePort: '', destination: 'Flowline-1' },
      { source: 'Flowline-1', sourcePort: '', destination: 'Junction-1' },
      { source: 'Junction-1', sourcePort: '', destination: 'Sink-1' },
      { source: 'Source-1', sourcePort: '', destination: 'Junction-2' },
      { source: 'Junction-2', sourcePort: '', destination: 'Sink-1' }
    ],
    counts: { nodes: 5, edges: 5, sources: 1, sinks: 1, flowlines: 1 }
  }
  const run = {
    id: 8810, projectId: 1, modelId: 33, modelVersionId: 301, modelName: '管网演示模型', versionNo: 1,
    study: 'Network Base', runType: 'network', status: 'SUCCEEDED', resultContract: 'VALID_FULL', parameters: null,
    createdAt: '2026-09-22T07:00:00', elapsedMillis: 2500, cancellable: false, events: [], artifacts: [],
    result: {
      schemaVersion: 'pipesim-network-result/1', model_kind: 'network', runTask: 'network',
      resultContract: 'VALID_FULL', study: 'Network Base', simulationState: 'Completed', topology,
      system: [], node: [], profiles: [], summary: { info: [], warnings: [], errors: [] }, messages: [], quality: []
    }
  }
  const state = await installMockBackend(page, { networkHistory: [summary(run)], runs: [run], holdCreateRun: true })
  await openWorkspace(page)
  await activateModel(page, '管网演示模型')
  await page.getByRole('tab', { name: '管网结果' }).click()
  const editor = page.getByRole('region', { name: 'PIPESIM 单连接派生方案' })
  await expect(editor).toContainText('完整成功无参数基线')
  await editor.locator('.el-select').nth(0).click()
  await page.getByRole('option', { name: /Flowline-1 .* Junction-1/ }).click()
  await editor.locator('.el-select').nth(1).click()
  await page.getByRole('option', { name: 'Junction-2 · Junction', exact: true }).click()
  await expect(editor.getByTestId('network-connection-impact')).toContainText('替换 Flowline-1')
  await expect(editor.getByTestId('network-connection-impact')).toContainText('基线可达汇点：Sink-1；方案后：Sink-1')
  await editor.getByRole('button', { name: '撤销本次选择', exact: true }).click()
  await expect(editor.getByTestId('network-connection-impact')).toHaveCount(0)
  await editor.locator('.el-select').nth(0).click()
  await page.getByRole('option', { name: /Flowline-1 .* Junction-1/ }).click()
  await editor.locator('.el-select').nth(1).click()
  await page.getByRole('option', { name: 'Junction-2 · Junction', exact: true }).click()
  const submitButton = editor.getByRole('button', { name: '保存派生拓扑并真实重算' })
  await submitButton.click()

  await expect.poll(() => state.createPayloads.length).toBe(1)
  await expect(submitButton).toBeDisabled()
  state.releaseCreateRun?.()
  expect(state.createPayloads[0].payload).toEqual({
    study: 'Network Base', runType: 'network',
    parameters: {
      schemaVersion: 'pipesim-network-connection-parameters/1', baselineRunId: 8810,
      source: 'Flowline-1', sourcePort: '', originalDestination: 'Junction-1', newDestination: 'Junction-2'
    }
  })
  expect(state.unexpectedRequests).toEqual([])
})

test('PIPESIM Network 单边重接阻止悬空 Flowline 与占用入口', async ({ page }) => {
  const topology = {
    nodes: [
      { id: 'Source', componentType: 'Source' }, { id: 'FL-3', componentType: 'Flowline' },
      { id: 'FL-1', componentType: 'Flowline' }, { id: 'Choke', componentType: 'Choke' },
      { id: 'FL-2', componentType: 'Flowline' }, { id: 'Manifold', componentType: 'Junction' },
      { id: 'Sink-1', componentType: 'Sink' }
    ],
    edges: [
      { source: 'Source', sourcePort: '', destination: 'FL-3' },
      { source: 'FL-3', sourcePort: '', destination: 'Manifold' },
      { source: 'FL-1', sourcePort: '', destination: 'Choke' },
      { source: 'Choke', sourcePort: '', destination: 'FL-2' },
      { source: 'FL-2', sourcePort: '', destination: 'Manifold' },
      { source: 'Manifold', sourcePort: '', destination: 'Sink-1' }
    ],
    counts: { nodes: 7, edges: 6, sources: 1, sinks: 1, flowlines: 3 }
  }
  const run = {
    id: 8813, projectId: 1, modelId: 33, modelVersionId: 301, modelName: '管网演示模型', versionNo: 1,
    study: 'Network Base', runType: 'network', status: 'SUCCEEDED', resultContract: 'VALID_FULL', parameters: null,
    createdAt: '2026-09-22T07:00:00', elapsedMillis: 2500, cancellable: false, events: [], artifacts: [],
    result: {
      schemaVersion: 'pipesim-network-result/1', model_kind: 'network', runTask: 'network',
      resultContract: 'VALID_FULL', study: 'Network Base', simulationState: 'Completed', topology,
      system: [], node: [], profiles: [], summary: { info: [], warnings: [], errors: [] }, messages: [], quality: []
    }
  }
  const state = await installMockBackend(page, { networkHistory: [summary(run)], runs: [run] })
  await openWorkspace(page)
  await activateModel(page, '管网演示模型')
  await page.getByRole('tab', { name: '管网结果' }).click()
  const editor = page.getByRole('region', { name: 'PIPESIM 单连接派生方案' })
  await editor.locator('.el-select').nth(0).click()
  await page.getByRole('option', { name: /Source .* FL-3/ }).click()
  await expect(editor.getByRole('alert')).toContainText('ConnectionsBoth')
  await editor.locator('.el-select').nth(1).click()
  const occupiedChoke = page.getByRole('option', { name: /Choke · Choke（入口不可用）/ })
  await expect(occupiedChoke).toHaveAttribute('aria-disabled', 'true')
  await expect(editor.getByRole('button', { name: '保存派生拓扑并真实重算' })).toBeDisabled()
  expect(state.createPayloads).toHaveLength(0)
  expect(state.unexpectedRequests).toEqual([])
})

test('PIPESIM Network 派生重算失败时展示后端拒绝原因', async ({ page }) => {
  const topology = {
    nodes: [
      { id: 'Source-1', componentType: 'Source' }, { id: 'Flowline-1', componentType: 'Flowline' },
      { id: 'Junction-1', componentType: 'Junction' }, { id: 'Junction-2', componentType: 'Junction' },
      { id: 'Sink-1', componentType: 'Sink' }
    ],
    edges: [
      { source: 'Source-1', sourcePort: '', destination: 'Flowline-1' },
      { source: 'Flowline-1', sourcePort: '', destination: 'Junction-1' },
      { source: 'Junction-1', sourcePort: '', destination: 'Sink-1' },
      { source: 'Source-1', sourcePort: '', destination: 'Junction-2' },
      { source: 'Junction-2', sourcePort: '', destination: 'Sink-1' }
    ],
    counts: { nodes: 5, edges: 5, sources: 1, sinks: 1, flowlines: 1 }
  }
  const run = {
    id: 8811, projectId: 1, modelId: 33, modelVersionId: 301, modelName: '管网演示模型', versionNo: 1,
    study: 'Network Base', runType: 'network', status: 'SUCCEEDED', resultContract: 'VALID_FULL', parameters: null,
    createdAt: '2026-09-22T07:00:00', elapsedMillis: 2500, cancellable: false, events: [], artifacts: [],
    result: {
      schemaVersion: 'pipesim-network-result/1', model_kind: 'network', runTask: 'network',
      resultContract: 'VALID_FULL', study: 'Network Base', simulationState: 'Completed', topology,
      system: [], node: [], profiles: [], summary: { info: [], warnings: [], errors: [] }, messages: [], quality: []
    }
  }
  const reason = 'Network 连接方案必须绑定同一版本、同一 Study 的完整无参数成功基线，且重接目标必须来自该基线拓扑'
  const state = await installMockBackend(page, {
    networkHistory: [summary(run)], runs: [run], createRunFailure: { status: 409, message: reason }
  })
  await openWorkspace(page)
  await activateModel(page, '管网演示模型')
  await page.getByRole('tab', { name: '管网结果' }).click()
  const editor = page.getByRole('region', { name: 'PIPESIM 单连接派生方案' })
  await editor.locator('.el-select').nth(0).click()
  await page.getByRole('option', { name: /Flowline-1 .* Junction-1/ }).click()
  await editor.locator('.el-select').nth(1).click()
  await page.getByRole('option', { name: 'Junction-2 · Junction', exact: true }).click()
  await editor.getByRole('button', { name: '保存派生拓扑并真实重算' }).click()

  await expect(page.getByTestId('run-feedback')).toContainText(reason)
  await expect(page.getByTestId('run-feedback')).toHaveCount(1)
  await expect(page.locator('.el-message')).toHaveCount(0)
  expect(state.createPayloads).toHaveLength(1)
  expect(state.unexpectedRequests).toEqual([])
})

test('PIPESIM Network 普通运行不把按钮点击事件误当成受控方案', async ({ page }) => {
  const state = await installMockBackend(page)
  await openWorkspace(page)
  await activateModel(page, '管网演示模型')
  await page.locator('.run-type-control').getByText('管网模拟', { exact: true }).click()
  await page.getByRole('button', { name: '运行', exact: true }).click()

  await expect.poll(() => state.createPayloads.length).toBe(1)
  expect(state.createPayloads[0].payload).toEqual({
    study: 'Network Base', runType: 'network', parameters: null
  })
  expect(state.unexpectedRequests).toEqual([])
})

test('PIPESIM Network 结果展示接受官方布尔值与描述文本标量', async ({ page }) => {
  const run = {
    id: 8820, projectId: 1, modelId: 33, modelVersionId: 301, modelName: '管网演示模型', versionNo: 1,
    study: 'Network Base', runType: 'network', status: 'SUCCEEDED', resultContract: 'VALID_FULL', parameters: null,
    createdAt: '2026-09-22T08:00:00', elapsedMillis: 2500, cancellable: false, events: [], artifacts: [],
    result: {
      schemaVersion: 'pipesim-network-result/1', model_kind: 'network', runTask: 'network',
      resultContract: 'VALID_FULL', study: 'Network Base', simulationState: 'Completed',
      topology: {
        nodes: [
          { id: 'Source-1', componentType: 'Source' },
          { id: 'Flowline-1', componentType: 'Flowline' },
          { id: 'Sink-1', componentType: 'Sink' }
        ],
        edges: [
          { source: 'Source-1', sourcePort: '', destination: 'Flowline-1' },
          { source: 'Flowline-1', sourcePort: '', destination: 'Sink-1' }
        ],
        counts: { nodes: 3, edges: 2, sources: 1, sinks: 1, flowlines: 1 }
      },
      system: [{ variable: 'FlowrateBeyondCurveMaxRate', unit: '', values: [{ name: 'Network', value: false }] }],
      node: [{ variable: 'LimitedBy', unit: '', values: [{ name: 'Well', value: 'PressureControl' }] }],
      profiles: [{ branch: 'Flowline-1', pointCount: 2, variables: [
        { variable: 'TotalDistance', unit: 'm', values: [0, 100] },
        { variable: 'Pressure', unit: 'bara', values: [90, 81.2] }
      ] }],
      summary: { info: [], warnings: [], errors: [] }, messages: [], quality: []
    }
  }
  const state = await installMockBackend(page, { networkHistory: [summary(run)], runs: [run] })
  await openWorkspace(page)
  await activateModel(page, '管网演示模型')
  await page.getByRole('tab', { name: '管网结果' }).click()

  await expect(page.getByText('管网结果不可用：已隐藏未通过校验的数据。')).toHaveCount(0)
  await expect(page.getByTestId('network-topology-canvas')).toBeVisible()
  expect(state.unexpectedRequests).toEqual([])
})

test('混合 PIPESIM 井模型只为原生验证通过的 Study 开放管网工作流', async ({ page }) => {
  const state = await installMockBackend(page)
  state.models[0].versions[0].inspection = {
    schemaVersion: 'pipesim-well-inspection/7', reservoirPressure: null,
    well: 'Well_1', espContexts: [],
    wellContexts: [{ context: 'Well_1', espContexts: [], completionContexts: ['Well_1:Completion'], tubingContexts: ['Well_1:Tubing'] }],
    packageFiles: [{ relativePath: 'well-demo.pips', sizeBytes: 512, sha256: 'e'.repeat(64) }],
    networkCapability: {
      schemaVersion: 'pipesim-network-capability/1', studies: ['Base Case'],
      networkInspection: {
        schemaVersion: 'pipesim-network-inspection/3',
        studies: [{ study: 'Base Case', boundaries: [
          { node: 'Source_1', boundaryNodeType: 'Source', isActive: true, isSurfaceCondition: true, flowRateType: 'GasFlowRate', pressure: 120, temperature: 80, gasFlowRate: 5, liquidFlowRate: null, massFlowRate: null }
        ] }],
        chokes: [],
        packageFiles: [{ relativePath: 'well-demo.pips', sizeBytes: 512, sha256: 'e'.repeat(64) }]
      }
    }
  }
  await openWorkspace(page)
  await activateModel(page, '井筒演示模型')
  await expect(page.getByTestId('pipesim-network-capability-notice')).toContainText('Base Case')
  await expect(page.getByTestId('pipesim-network-capability-notice')).toContainText('原生 Study 校验')
  await expect(page.getByRole('radio', { name: '节点分析', exact: true })).toBeVisible()
  await expect(page.getByRole('radio', { name: '管网模拟', exact: true })).toBeVisible()
  await page.locator('label.el-radio-button').filter({ hasText: '管网模拟' }).click()
  await expect(page.getByRole('region', { name: '管网边界条件方案', exact: true })).toContainText('Source_1')
  await page.getByRole('button', { name: '运行', exact: true }).click()
  await expect.poll(() => state.createPayloads.length).toBe(1)
  expect(state.createPayloads[0].payload).toEqual({ study: 'Base Case', runType: 'network', parameters: null })
  expect(state.unexpectedRequests).toEqual([])
})

test('PIPESIM Network Optimizer 展示稀疏变量、不可用值和质量标记并支持导出', async ({ page }) => {
  const result = {
    schemaVersion: 'pipesim-network-optimizer-result/1',
    model_kind: 'network',
    runTask: 'network-optimizer',
    resultContract: 'VALID_FULL',
    simulationState: 'Completed',
    summary: { info: ['Finished simulation'], warnings: [], errors: [] },
    messages: [],
    variables: [
      { key: 'OptimizerLiquid_rate', label: 'Liquid rate', unit: 'STB/d' },
      { key: 'OptimizerGas_lift_rate', label: 'Gas lift rate', unit: 'mmscf/d' },
      { key: 'OptimizerWell_is_shut_off', label: 'Well is shut off', unit: '' }
    ],
    wells: [{ name: 'Well_1', values: [
      { key: 'OptimizerLiquid_rate', value: 123.456 },
      { key: 'OptimizerWell_is_shut_off', value: false }
    ]}],
    flowlines: [{ name: 'B_1', values: [
      { key: 'OptimizerLiquid_rate', value: null }
    ]}],
    sinks: [{ name: 'CPF', values: [
      { key: 'OptimizerGas_lift_rate', value: 0.5 }
    ]}],
    quality: [{ path: 'flowlines.B_1.OptimizerLiquid_rate', code: 'UNAVAILABLE' }]
  }
  const run = {
    id: 8510, projectId: 1, modelId: 33, modelVersionId: 301, modelName: '管网演示模型', versionNo: 1,
    study: 'Network Base', runType: 'network-optimizer', status: 'SUCCEEDED', resultContract: 'VALID_FULL',
    result, createdAt: '2026-09-13T07:00:00', elapsedMillis: 30000, cancellable: false, events: [], artifacts: []
  }
  const state = await installMockBackend(page, { networkHistory: [summary(run)], runs: [run] })
  await openWorkspace(page)
  await activateModel(page, '管网演示模型')
  await page.getByRole('tab', { name: '网络优化' }).click()

  const resultRegion = page.getByRole('region', { name: 'PIPESIM Network Optimizer 结果' })
  await expect(resultRegion).toContainText('VALID_FULL')
  await expect(resultRegion).toContainText('1 / 1 / 1')
  await expect(resultRegion).toContainText('3')
  await expect(resultRegion).toContainText('STB/d')
  await expect(resultRegion).toContainText('mmscf/d')
  await expect(resultRegion).toContainText('123.456')
  await expect(resultRegion).toContainText('否')
  await expect(resultRegion).toContainText('—')

  const downloadPromise = page.waitForEvent('download')
  await resultRegion.getByRole('button', { name: '导出 CSV' }).first().click()
  const stream = await (await downloadPromise).createReadStream()
  let csv = ''
  for await (const chunk of stream) csv += chunk.toString('utf8')
  expect(csv).toContain('质量标记')
  expect(csv).toContain('OptimizerLiquid_rate')
  expect(csv).not.toContain('C:\\')
  expect(state.unexpectedRequests).toEqual([])
})

test('PIPESIM Network Optimizer 可选择应用到隔离副本并提交 v2 参数合同', async ({ page }) => {
  const state = await installMockBackend(page)
  await openWorkspace(page)
  await activateModel(page, '管网演示模型')
  await page.locator('.run-type-control').getByText('网络优化', { exact: true }).click()
  const scenario = page.getByRole('region', { name: 'PIPESIM 网络优化工况', exact: true })
  await scenario.getByText('将优化结果应用到隔离方案副本', { exact: true }).click()
  await expect(scenario).toContainText('调用官方 apply_results()')
  await page.getByRole('button', { name: '运行', exact: true }).click()
  expect(state.createPayloads[0].payload).toEqual({
    study: 'Network Base',
    runType: 'network-optimizer',
    parameters: { schemaVersion: 'pipesim-network-optimizer-parameters/2', applyResults: true }
  })
  expect(state.unexpectedRequests).toEqual([])
})

test('PIPESIM Well Performance Curves 使用检查清单上下文并提交独立内径敏感性合同', async ({ page }) => {
  const inspection = structuredClone(versions.network.inspection)
  inspection.schemaVersion = 'pipesim-network-inspection/4'
  inspection.wellContexts = ['Well']
  inspection.flowlineContexts = ['FL-2']
  const state = await installMockBackend(page, { networkInspection: inspection })
  await openWorkspace(page)
  await activateModel(page, '管网演示模型')
  await page.locator('.run-type-control').getByText('井性能曲线', { exact: true }).click()

  const scenario = page.getByRole('region', { name: 'PIPESIM 井性能曲线工况', exact: true })
  await scenario.locator('.el-select').nth(0).click()
  await page.getByRole('option', { name: 'Well', exact: true }).click()
  await scenario.locator('.el-select').nth(1).click()
  await page.getByRole('option', { name: 'FL-2', exact: true }).click()
  await expect(scenario.getByRole('textbox', { name: '井性能曲线内径扫描值' })).toHaveValue('4, 5, 6')
  await page.getByRole('button', { name: '运行', exact: true }).click()

  await expect.poll(() => state.createPayloads.length).toBe(1)
  expect(state.createPayloads[0].payload).toEqual({
    study: 'Network Base',
    runType: 'network-well-performance-curves',
    parameters: {
      schemaVersion: 'pipesim-network-well-performance-parameters/1',
      producerContext: 'Well',
      branchTerminator: 'FL-2',
      sensitivityUnit: 'in',
      sensitivityValues: [4, 5, 6]
    }
  })
  expect(state.unexpectedRequests).toEqual([])
})

test('PIPESIM Well Performance Curves 可恢复官方 Case 结果并下载 PWI Artifact', async ({ page }) => {
  const inspection = structuredClone(versions.network.inspection)
  inspection.schemaVersion = 'pipesim-network-inspection/4'
  inspection.wellContexts = ['Well']
  inspection.flowlineContexts = ['FL-2']
  const artifact = { id: 8521, name: 'pipesim-network-well-performance.pwi', type: 'output', sizeBytes: 64, sha256: 'f'.repeat(64), contentType: 'text/plain', expiresAt: null }
  const run = {
    id: 8520, projectId: 1, modelId: 33, modelVersionId: 301, modelName: '管网演示模型', versionNo: 1,
    study: 'Network Base', runType: 'network-well-performance-curves', status: 'SUCCEEDED', resultContract: 'VALID_FULL',
    parameters: { schemaVersion: 'pipesim-network-well-performance-parameters/1', producerContext: 'Well', branchTerminator: 'FL-2', sensitivityUnit: 'in', sensitivityValues: [4, 5, 6] },
    createdAt: '2026-09-20T07:00:00', elapsedMillis: 3000, cancellable: false, events: [], artifacts: [artifact],
    result: {
      schemaVersion: 'pipesim-network-well-performance-result/1', model_kind: 'network', runTask: 'network-well-performance-curves', resultContract: 'VALID_FULL',
      study: 'Network Base', producerContext: 'Well', branchTerminator: 'FL-2',
      sensitivity: { variable: 'InnerDiameter', unit: 'in', values: [4, 5, 6] }, flowratePoints: 50, simulationState: 'Completed',
      system: [{ variable: 'SystemPressureLoss', unit: 'psi', values: [{ name: 'System', value: 125.5 }] }],
      cases: [4, 5, 6].map((value, index) => ({ caseId: `Case-${index + 1}`, caseIndex: index, sensitivityValueIn: value, node: [{ variable: 'Pressure', unit: 'psi', values: [{ name: 'Well', value: 1000 - index * 100 }] }] })),
      summary: { info: ['Completed'], warnings: [], errors: [] }, messages: [], quality: [],
      pwiArtifact: { artifactName: artifact.name }
    }
  }
  const state = await installMockBackend(page, { networkInspection: inspection, networkHistory: [summary(run)], runs: [run] })
  await openWorkspace(page)
  await activateModel(page, '管网演示模型')
  await page.getByRole('tab', { name: '运行记录' }).click()
  await page.getByRole('tabpanel', { name: '运行记录' }).locator('.el-table__row').filter({ hasText: '8520' }).click()
  await page.getByRole('tab', { name: '井性能曲线' }).click()

  const result = page.getByTestId('pipesim-network-well-performance-result')
  await expect(result).toContainText('VALID_FULL')
  await expect(result).toContainText('125.5')
  await expect(result).toContainText('Case-1')
  await result.locator('label.el-radio-button').filter({ hasText: '6 in' }).click()
  await expect(result).toContainText('Case-3')
  await result.getByRole('button', { name: '下载官方 PWI' }).click()
  await expect.poll(() => state.downloadRequests).toEqual([{ runId: 8520, artifactId: 8521 }])
  expect(state.unexpectedRequests).toEqual([])
})

test('RSM 按对象和指标检索，同单位比较并导出原始点', async ({ page }, testInfo) => {
  const run = {
    id: 8601, projectId: 1, modelId: 22, modelVersionId: 201, versionNo: 1,
    modelName: 'ECLIPSE演示模型', study: null, runType: 'eclipse', status: 'SUCCEEDED', resultContract: 'VALID_FULL',
    events: [], artifacts: [], result: {
      schemaVersion: 'eclipse-summary-result/1', modelKind: 'eclipse_100', runTask: 'eclipse', resultContract: 'VALID_FULL',
      eclEnd: { comments: 0, warnings: 0, problems: 0, errors: 0, bugs: 0 }, outputFiles: [],
      summary: { series: [
        { keyword: 'WOPR', objectName: 'W1', unit: 'STB/DAY', points: [{ timeDays: 0, value: 10 }, { timeDays: 2, value: 7 }] },
        { keyword: 'WOPR', objectName: 'W2', unit: 'STB/DAY', points: [{ timeDays: 1, value: 8 }, { timeDays: 3, value: 6 }] },
        { keyword: 'WBHP', objectName: 'W1', unit: 'PSIA', points: [{ timeDays: 0, value: 150 }] },
        { keyword: 'UNKNOWN', objectName: null, unit: null, points: [{ timeDays: 0, value: 1 }] }
      ] }
    }
  }
  const previous = structuredClone(run)
  previous.id = 8600
  previous.result.summary.series[0].points = [{ timeDays: 0.5, value: 12 }, { timeDays: 4, value: 5 }]
  const state = await installMockBackend(page, { eclipseHistory: [summary(run), summary(previous)], runs: [run, previous] })
  await openWorkspace(page)
  await activateModel(page, 'ECLIPSE演示模型')
  const explorer = page.getByLabel('RSM 曲线工作台')
  await expect(explorer.getByRole('checkbox', { name: 'WOPR · W1', exact: true })).toBeChecked()
  await expect(explorer.getByRole('checkbox', { name: 'WBHP · W1', exact: true })).toBeDisabled()
  await explorer.getByRole('checkbox', { name: 'WOPR · W2', exact: true }).check()
  await expect(explorer.getByText('已选 2/6')).toBeVisible()
  await explorer.getByRole('textbox', { name: '搜索 RSM 指标' }).fill('井底')
  await expect(explorer.getByRole('checkbox')).toHaveCount(1)
  await explorer.getByRole('textbox', { name: '搜索 RSM 指标' }).fill('')
  const downloadPromise = page.waitForEvent('download')
  await explorer.getByRole('button', { name: '导出曲线 CSV', exact: true }).click()
  const download = await downloadPromise
  const stream = await download.createReadStream()
  let csv = ''
  for await (const chunk of stream) csv += chunk.toString('utf8')
  expect(csv).toContain('"8601","WOPR","W2","STB/DAY","1","8"')
  expect(csv).not.toContain('WBHP')
  for (const viewport of [{ width: 1440, height: 900 }, { width: 1280, height: 720 }]) {
    await page.setViewportSize(viewport)
    await explorer.scrollIntoViewIfNeeded()
    await page.screenshot({ path: testInfo.outputPath(`rsm-${viewport.width}.png`) })
    const box = await explorer.boundingBox()
    expect(box.x + box.width).toBeLessThanOrEqual(viewport.width)
  }
  await explorer.getByRole('button', { name: '清空选择' }).click()
  await explorer.getByRole('checkbox', { name: 'WBHP · W1', exact: true }).check()
  await expect(explorer.getByRole('checkbox', { name: 'WOPR · W1', exact: true })).toBeDisabled()
  await page.getByRole('combobox', { name: 'ECLIPSE 历史结果对比' }).click()
  await page.getByRole('option', { name: /运行 #8600/ }).click()
  await expect(explorer.getByRole('checkbox', { name: 'WOPR · W1 · #8600', exact: true })).toBeChecked()
  await expect(explorer.getByRole('checkbox', { name: 'WOPR · W1 · #8601', exact: true })).toBeChecked()
  await expect(explorer.getByRole('checkbox', { name: 'WBHP · W1 · #8600', exact: true })).toBeDisabled()
  const comparedDownload = page.waitForEvent('download')
  await explorer.getByRole('button', { name: '导出曲线 CSV', exact: true }).click()
  const comparedStream = await (await comparedDownload).createReadStream()
  let comparedCsv = ''
  for await (const chunk of comparedStream) comparedCsv += chunk.toString('utf8')
  expect(comparedCsv).toContain('"8600","WOPR","W1","STB/DAY","0.5","12"')
  expect(comparedCsv).toContain('"8601","WOPR","W1","STB/DAY","2","7"')
  for (const viewport of [{ width: 1440, height: 900 }, { width: 1280, height: 720 }]) {
    await page.setViewportSize(viewport)
    await explorer.scrollIntoViewIfNeeded()
    await page.screenshot({ path: testInfo.outputPath(`rsm-history-${viewport.width}.png`) })
  }
  await activateModel(page, '井筒演示模型')
  await activateModel(page, 'ECLIPSE演示模型')
  await expect(explorer.getByRole('checkbox', { name: /#8600/ })).toHaveCount(0)
  state.heldRunId = 8600
  await page.getByRole('combobox', { name: 'ECLIPSE 历史结果对比' }).click()
  await page.getByRole('option', { name: /运行 #8600/ }).click()
  await expect.poll(() => Boolean(state.releaseHeldRun)).toBe(true)
  await activateModel(page, '井筒演示模型')
  state.releaseHeldRun()
  await expect(page.getByLabel('RSM 曲线工作台')).toHaveCount(0)
})

test('井筒历史比较保留来源与原始值，切换模型清除比较', async ({ page }, testInfo) => {
  const makeRun = (id, pressure) => ({
    id, projectId: 1, modelId: 11, modelVersionId: 101, versionNo: 1, modelName: '井筒演示模型',
    study: 'Base Case', runType: 'combined', status: 'SUCCEEDED', resultContract: 'VALID_FULL', events: [], artifacts: [],
    parameters: id === 8702 ? { schemaVersion: 'pipesim-well-parameters/1', reservoirPressurePsi: 4000 } : null,
    result: {
      schemaVersion: 'pipesim-well-result/1', model_kind: 'black_oil_liquid', runTask: 'combined', resultContract: 'VALID_FULL',
      units: Object.fromEntries(Object.entries({ flow: 'STB/DAY', pressure: 'PSIA', depth: 'FT', temperature: 'DEGF' }).map(([key, displayUnit]) => [key, { displayUnit }])),
      ipr: Array.from({ length: 25 }, (_, index) => ({ flow: index === 0 ? 0 : index === 1 ? 10 : index + 9, pressure: index === 0 ? pressure : index === 1 ? 0 : index })),
      vlp: Array.from({ length: 25 }, (_, index) => ({ flow: index === 0 ? 0 : index === 1 ? 10 : index + 9, pressure: index === 1 ? pressure : index })),
      profile: Array.from({ length: 25 }, (_, index) => ({ depth: index * 100, pressure: index === 0 ? pressure : pressure + index * 20, temperature: 70 + index }))
    }
  })
  const current = makeRun(8702, 200)
  const previous = makeRun(8701, 100)
  const state = await installMockBackend(page, { wellHistory: [summary(current), summary(previous)], runs: [current, previous] })
  await openWorkspace(page)
  await activateModel(page, '井筒演示模型')
  await page.getByRole('tab', { name: '综合结果', exact: true }).click()
  await expect(page.getByRole('region', { name: '井筒综合结果' }).locator('.nodal-result')).toBeVisible()
  await expect(page.getByRole('region', { name: '井筒综合结果' }).locator('.profile-result')).toBeVisible()
  await expect(page.locator('#well-nodal-export-csv')).toHaveCount(1)
  for (const viewport of [{ width: 1440, height: 900 }, { width: 1280, height: 720 }]) {
    await page.setViewportSize(viewport)
    await page.getByRole('region', { name: '井筒综合结果' }).scrollIntoViewIfNeeded()
    await page.screenshot({ path: testInfo.outputPath(`combined-${viewport.width}.png`) })
  }
  await page.getByRole('tab', { name: '节点分析', exact: true }).click()
  await page.getByRole('combobox', { name: '井筒历史结果对比' }).click()
  await page.getByRole('option', { name: /8701/ }).click()
  await expect.poll(() => state.runReads.get(8701)).toBe(1)
  await expect(page.getByLabel('井筒方案对比摘要')).toBeVisible()
  await expect(page.getByLabel('井筒方案对比摘要')).toContainText('地层压力方案：4000 psia')
  await expect(page.getByLabel('井筒方案对比摘要')).toContainText('原模型参数（未覆盖）')
  await expect(page.getByLabel('井筒方案对比摘要')).toContainText('IPR 压力')
  await expect(page.getByLabel('井筒方案对比摘要')).toContainText('0 ～ 100')
  await expect(page.locator('.nodal-result .curve-pagination')).toBeVisible()
  await page.locator('.nodal-result .curve-pagination li.number').filter({ hasText: '2' }).click()
  await expect(page.locator('.nodal-result').getByText('运行 #8701 · v1 · Base Case', { exact: true }).first()).toBeVisible()
  const promise = page.waitForEvent('download')
  await page.locator('#well-nodal-export-csv').click()
  const stream = await (await promise).createReadStream()
  let csv = ''
  for await (const chunk of stream) csv += chunk.toString('utf8')
  expect(csv).toContain('"运行 #8701 · v1 · Base Case","IPR","0","100"')
  await page.getByRole('tab', { name: 'PT 剖面', exact: true }).click()
  await expect(page.locator('.profile-result .profile-pagination')).toBeVisible()
  await page.locator('.profile-result .profile-pagination li.number').filter({ hasText: '2' }).click()
  await expect(page.locator('.profile-result').getByText('运行 #8701 · v1 · Base Case', { exact: true }).first()).toBeVisible()
  for (const viewport of [{ width: 1440, height: 900 }, { width: 1280, height: 720 }]) {
    await page.setViewportSize(viewport)
    await page.locator('.profile-result').scrollIntoViewIfNeeded()
    await page.screenshot({ path: testInfo.outputPath(`well-${viewport.width}.png`) })
  }
  await activateModel(page, 'ECLIPSE演示模型')
  await activateModel(page, '井筒演示模型')
  await expect(page.locator('.nodal-result').getByText('运行 #8701 · v1 · Base Case', { exact: true })).toHaveCount(0)
  previous.result.units.flow.displayUnit = 'm3/d'
  await page.getByRole('tab', { name: '节点分析', exact: true }).click()
  await page.getByRole('combobox', { name: '井筒历史结果对比' }).click()
  await page.getByRole('option', { name: /8701/ }).click()
  await expect(page.locator('.nodal-result').getByText('结果单位不同，不能叠加比较。')).toBeVisible()
  await activateModel(page, 'ECLIPSE演示模型')
  await activateModel(page, '井筒演示模型')
  state.heldRunId = 8701
  await page.getByRole('combobox', { name: '井筒历史结果对比' }).click()
  await page.getByRole('option', { name: /8701/ }).click()
  await expect.poll(() => Boolean(state.releaseHeldRun)).toBe(true)
  await activateModel(page, 'ECLIPSE演示模型')
  state.releaseHeldRun()
  await expect(page.getByText('运行 #8701 · v1 · Base Case', { exact: true })).toHaveCount(0)
})

test('PIPESIM 敏感性结果可浏览原始点并导出全量扫描数据', async ({ page }) => {
  const makePoints = offset => Array.from({ length: 25 }, (_, index) => ({ flow: index * 10, pressure: offset + index }))
  const run = {
    id: 8751, projectId: 1, modelId: 11, modelVersionId: 101, versionNo: 1, modelName: '井筒演示模型',
    study: 'Base Case', runType: 'sensitivity', status: 'SUCCEEDED', resultContract: 'VALID_FULL', events: [], artifacts: [],
    parameters: { schemaVersion: 'pipesim-well-sensitivity-parameters/1', targetVariable: 'reservoirPressure', values: [3000, 4000] },
    result: {
      schemaVersion: 'pipesim-well-sensitivity-result/1', model_kind: 'black_oil_liquid', runTask: 'sensitivity', resultContract: 'VALID_FULL',
      targetVariable: 'reservoirPressure', units: {
        flow: { displayUnit: 'STB/DAY' }, pressure: { displayUnit: 'PSIA' }, depth: { displayUnit: 'FT' }, temperature: { displayUnit: 'DEGF' }
      },
      cases: [{ value: 3000, ipr: makePoints(100), vlp: makePoints(150) }, { value: 4000, ipr: makePoints(200), vlp: makePoints(250) }]
    }
  }
  const state = await installMockBackend(page, { wellHistory: [summary(run)], runs: [run] })
  await openWorkspace(page)
  await activateModel(page, '井筒演示模型')
  await page.getByRole('tab', { name: '敏感性结果', exact: true }).click()
  const result = page.locator('.sensitivity-result')
  await expect(result).toContainText('地层压力 · 2 个真实计算工况')
  await expect(result.getByRole('button', { name: '导出全部敏感性点 CSV', exact: true })).toBeVisible()
  const points = result.getByLabel('敏感性原始点')
  await expect(points.getByText('工况 3000 · 原始点', { exact: true })).toBeVisible()
  await expect(points.locator('.point-pagination')).toBeVisible()
  await points.locator('.point-pagination li.number').filter({ hasText: '2' }).click()
  await expect(points.getByText('21', { exact: true })).toBeVisible()
  await points.locator('.point-toolbar .el-select').nth(1).click()
  await page.getByRole('option', { name: 'VLP', exact: true }).click()
  await expect(points).toContainText('150')
  const downloadPromise = page.waitForEvent('download')
  await result.getByRole('button', { name: '导出全部敏感性点 CSV', exact: true }).click()
  const stream = await (await downloadPromise).createReadStream()
  let csv = ''
  for await (const chunk of stream) csv += chunk.toString('utf8')
  expect(csv).toContain('"3000","IPR","1","0","100"')
  expect(csv).toContain('"4000","VLP","25","240","274"')
  expect(state.unexpectedRequests).toEqual([])
})

test('执行详情 Artifact 可从持久化运行记录下载', async ({ page }) => {
  const run = {
  id: 8801, projectId: 1, modelId: 22, modelVersionId: 201, versionNo: 1,
  modelName: 'ECLIPSE演示模型', study: null, runType: 'eclipse', status: 'SUCCEEDED', resultContract: 'VALID_FULL',
  events: [], artifacts: [{ id: 77, name: 'run.log', type: 'log', contentType: 'text/plain', sizeBytes: 15,
      sha256: 'a'.repeat(64), createdAt: '2026-09-13T05:00:00', expiresAt: '2026-10-13T05:00:00' }], result: null,
    resultExpiresAt: '2026-08-13T05:00:00', resultExpired: true
  }
  const state = await installMockBackend(page, { eclipseHistory: [summary(run)], runs: [run] })
  await openWorkspace(page)
  await activateModel(page, 'ECLIPSE演示模型')
  await expect(page.getByText('本次 ECLIPSE 结果已超过 30 天保留期限', { exact: true })).toBeVisible()
  const execution = page.locator('details[aria-label="持久化执行详情"]')
  await execution.locator('summary').click()
  await expect(execution).toContainText('run.log')
  const downloadPromise = page.waitForEvent('download')
  await execution.getByRole('button', { name: '下载', exact: true }).click()
  const download = await downloadPromise
  const stream = await download.createReadStream()
  let body = ''
  for await (const chunk of stream) body += chunk.toString('utf8')
  expect(body).toBe('artifact fixture')
  expect(state.downloadRequests).toEqual([{ runId: 8801, artifactId: 77 }])
  expect(state.unexpectedRequests).toEqual([])
})

test('ECLIPSE 结果页可直接下载受控二进制 Artifact', async ({ page }) => {
  const run = {
    id: 8802, projectId: 1, modelId: 22, modelVersionId: 201, versionNo: 1,
    modelName: 'ECLIPSE演示模型', study: null, runType: 'eclipse', status: 'SUCCEEDED', resultContract: 'VALID_FULL',
    events: [], artifacts: [{ id: 78, name: 'eclipse-output-DEMO.EGRID', type: 'output', contentType: 'application/octet-stream', sizeBytes: 2000,
      sha256: 'b'.repeat(64), createdAt: '2026-09-13T05:00:00', expiresAt: '2026-10-13T05:00:00' }, { id: 79, name: 'eclipse-output-DEMO.UNRST', type: 'output', contentType: 'application/octet-stream', sizeBytes: 100,
      sha256: 'c'.repeat(64), createdAt: '2026-09-13T05:00:00', expiresAt: '2026-10-13T05:00:00' }], result: {
      schemaVersion: 'eclipse-summary-result/1', modelKind: 'eclipse_100', runTask: 'eclipse', resultContract: 'VALID_FULL',
      eclEnd: { comments: 0, warnings: 0, problems: 0, errors: 0, bugs: 0 }, outputFiles: [{ name: 'DEMO.EGRID', sizeBytes: 2000, sha256: 'b'.repeat(64) }],
      summary: null, grid: { fileName: 'DEMO.EGRID', nx: 4, ny: 3, nz: 2, activeCells: 24 },
      fieldIndex: { schemaVersion: 'eclipse-binary-field-index/1', files: [{ name: 'DEMO.EGRID', sizeBytes: 2000, byteOrder: 'BIG',
        fields: [{ keyword: 'PRESSURE', dataType: 'REAL', count: 401, elementSize: 4, dataBytes: 1604, segments: [{ offset: 40, length: 1604 }] },
          { keyword: 'PORO', dataType: 'REAL', count: 24, elementSize: 4, dataBytes: 96, segments: [{ offset: 1700, length: 96 }] }] },
        { name: 'DEMO.UNRST', sizeBytes: 100, byteOrder: 'BIG', fields: [{ keyword: 'PRESSURE', dataType: 'REAL', count: 24, elementSize: 4, dataBytes: 96, timeStep: 7, segments: [{ offset: 0, length: 96 }] }] }] }
    }
  }
  const state = await installMockBackend(page, { eclipseHistory: [summary(run)], runs: [run] })
  await openWorkspace(page)
  await activateModel(page, 'ECLIPSE演示模型')
  const audit = page.locator('details.eclipse-audit')
  await audit.locator('summary').click()
  await expect(audit).toContainText('PRESSURE')
  const fieldIndex = audit.locator('.result-panel').filter({ hasText: '二进制场索引' })
  const grid2d = page.locator('#eclipse-2d-result')
  await expect(fieldIndex.getByText('本次运行没有可解析的二进制场索引', { exact: true })).toHaveCount(0)
  await fieldIndex.locator('.field-index-toolbar .el-select').nth(1).click()
  await page.getByRole('option', { name: '时间步 7', exact: true }).click()
  await expect(fieldIndex.locator('.el-table__row')).toHaveCount(1)
  await expect(fieldIndex).toContainText('DEMO.UNRST')
  await expect(grid2d.locator('.grid-slice-controls .el-select').first()).toContainText('时间步 7（含静态字段）')
  await grid2d.locator('.grid-slice-controls .el-select').first().click()
  await page.getByRole('option', { name: '全部时间步（含静态字段）', exact: true }).click()
  await expect(fieldIndex.locator('.el-table__row')).toHaveCount(3)
  await audit.getByRole('button', { name: '预览前 200 个值', exact: true }).first().click()
  await expect(audit).toContainText('展示 200 个真实值（第 1-200 个）')
  await audit.locator('.field-preview-pagination li.number').filter({ hasText: '2' }).click()
  await expect(audit).toContainText('展示 200 个真实值（第 201-400 个）')
  await expect(grid2d.getByRole('combobox', { name: 'ECLIPSE 层切片时间步' })).toBeVisible()
  await grid2d.locator('.grid-slice-controls .el-select').first().click()
  await expect(page.getByRole('option', { name: '时间步 7（含静态字段）', exact: true })).toBeVisible()
  await page.keyboard.press('Escape')
  await grid2d.getByRole('button', { name: '读取二维切片', exact: true }).click()
  await expect(grid2d).toContainText('12 个有效单元')
  const sliceDownload = page.waitForEvent('download')
  await grid2d.getByRole('button', { name: '导出二维切片 CSV', exact: true }).click()
  const sliceStream = await (await sliceDownload).createReadStream()
  let sliceCsv = ''
  for await (const chunk of sliceStream) sliceCsv += chunk.toString('utf8')
  expect(sliceCsv).toContain('"文件","关键字","数据类型","时间步","层","I","J","值"')
  expect(sliceCsv).toContain('"DEMO.EGRID","PORO","REAL","静态","1","1","1"')
  const downloadPromise = page.waitForEvent('download')
  await audit.locator('.el-table__row').filter({ hasText: 'eclipse-output-DEMO.EGRID' }).getByRole('button', { name: '下载', exact: true }).click()
  const download = await downloadPromise
  expect(download.suggestedFilename()).toBe('eclipse-output-DEMO.EGRID')
  expect(state.downloadRequests).toEqual([{ runId: 8802, artifactId: 78 }])
  expect(state.rangeRequests).toEqual([
    { runId: 8802, artifactId: 78, params: 'offset=40&length=800' },
    { runId: 8802, artifactId: 78, params: 'offset=840&length=800' },
    { runId: 8802, artifactId: 78, params: 'offset=1700&length=48' }
  ])
  expect(state.unexpectedRequests).toEqual([])
})

test('ECLIPSE 三维网格可用真实 EGRID 范围定位井并生成场值编辑草案', async ({ page }) => {
  const inspectionWithCompletion = {
    schemaVersion: 'eclipse-data-inspection/4',
    caseName: 'DEMO.DATA',
    sections: ['RUNSPEC', 'GRID', 'SOLUTION', 'SUMMARY', 'SCHEDULE'],
    unitSystem: 'METRIC',
    phases: ['OIL'],
    dimensions: { nx: 1, ny: 1, nz: 1 },
    wellNames: ['WELL-A'],
    scheduleTimeline: [{ kind: 'DATES', records: [{ day: '1', month: 'JAN', year: '1970', time: null }] }],
    packageFiles: [{ relativePath: 'DEMO.DATA', sizeBytes: 128, sha256: 'a'.repeat(64) }],
    scheduleMetadata: {
      wells: [{ name: 'WELL-A', group: null, sourceFile: 'DEMO.DATA', lineNumber: 1 }],
      groups: [], records: [],
      completions: [{ keyword: 'COMPDAT', well: 'WELL-A', i: '1', j: '1', k1: '1', k2: '1', status: 'OPEN', sourceFile: 'DEMO.DATA', lineNumber: 10 }]
    }
  }
  const floatBytes = values => {
    const bytes = Buffer.alloc(values.length * 4)
    values.forEach((value, index) => bytes.writeFloatBE(value, index * 4))
    return bytes
  }
  const run = {
    id: 8803, projectId: 1, modelId: 22, modelVersionId: 201, versionNo: 1,
    modelName: 'ECLIPSE演示模型', study: null, runType: 'eclipse', status: 'SUCCEEDED', resultContract: 'VALID_FULL', events: [],
    artifacts: [
      { id: 80, name: 'eclipse-output-DEMO.EGRID', type: 'output', contentType: 'application/octet-stream', sizeBytes: 400,
        sha256: 'd'.repeat(64), createdAt: '2026-09-13T05:00:00', expiresAt: '2026-10-13T05:00:00' },
      { id: 81, name: 'eclipse-output-DEMO.INIT', type: 'output', contentType: 'application/octet-stream', sizeBytes: 48,
        sha256: 'c'.repeat(64), createdAt: '2026-09-13T05:00:00', expiresAt: '2026-10-13T05:00:00' }
    ],
    result: {
      schemaVersion: 'eclipse-summary-result/1', modelKind: 'eclipse_100', runTask: 'eclipse', resultContract: 'VALID_FULL',
      eclEnd: { comments: 0, warnings: 0, problems: 0, errors: 0, bugs: 0 }, outputFiles: [
        { name: 'DEMO.EGRID', sizeBytes: 400, sha256: 'd'.repeat(64) }, { name: 'DEMO.INIT', sizeBytes: 48, sha256: 'c'.repeat(64) }
      ], summary: null,
      grid: { fileName: 'DEMO.EGRID', nx: 1, ny: 1, nz: 1, activeCells: 1 },
      fieldIndex: { schemaVersion: 'eclipse-binary-field-index/1', files: [{ name: 'DEMO.EGRID', sizeBytes: 400, byteOrder: 'BIG', fields: [
        { keyword: 'COORD', dataType: 'REAL', count: 24, elementSize: 4, dataBytes: 96, segments: [{ offset: 40, length: 96 }] },
        { keyword: 'ZCORN', dataType: 'REAL', count: 8, elementSize: 4, dataBytes: 32, segments: [{ offset: 140, length: 32 }] },
        { keyword: 'ACTNUM', dataType: 'INTE', count: 1, elementSize: 4, dataBytes: 4, segments: [{ offset: 200, length: 4 }] },
        { keyword: 'PRESSURE', dataType: 'REAL', count: 1, elementSize: 4, dataBytes: 4, segments: [{ offset: 220, length: 4 }] }
      ] }, { name: 'DEMO.INIT', sizeBytes: 48, byteOrder: 'BIG', fields: [
        { keyword: 'PERMX', dataType: 'REAL', count: 1, elementSize: 4, dataBytes: 4, segments: [{ offset: 40, length: 4 }] }
      ] }] }
    }
  }
  const state = await installMockBackend(page, {
    eclipseInspection: inspectionWithCompletion,
    eclipseHistory: [summary(run)],
    runs: [run],
    rangePayloads: {
      '80:40': floatBytes([0, 0, 0, 0, 0, 10, 1, 0, 0, 1, 0, 10, 0, 1, 0, 0, 1, 10, 1, 1, 0, 1, 1, 10]),
      '80:140': floatBytes([0, 0, 0, 0, 10, 10, 10, 10]),
      '80:200': Buffer.from([0, 0, 0, 1]),
      '80:220': floatBytes([1000]),
      '81:40': floatBytes([200])
    }
  })
  await openWorkspace(page)
  await activateModel(page, 'ECLIPSE演示模型')
  const resultNavigation = page.getByRole('navigation', { name: 'ECLIPSE 结果区域' })
  await expect(resultNavigation.getByRole('button', { name: /三维几何 \/ 井定位/ })).toContainText('可进入')
  for (const viewport of [{ width: 1440, height: 900 }, { width: 1280, height: 720 }]) {
    await page.setViewportSize(viewport)
    await resultNavigation.scrollIntoViewIfNeeded()
    const navigationBox = await resultNavigation.boundingBox()
    expect(navigationBox.x + navigationBox.width).toBeLessThanOrEqual(viewport.width)
  }
  await resultNavigation.getByRole('button', { name: /三维几何 \/ 井定位/ }).click()
  await expect(page.locator('#eclipse-3d-result')).toBeInViewport()
  const grid3d = page.locator('#eclipse-3d-result .eclipse-grid-3d-panel')
  await expect(grid3d).toContainText('真实几何已加载 1 个活动单元')
  await expect(grid3d).toHaveAttribute('data-render-ready', 'true')
  await expect(grid3d).toContainText('1 条 COMPDAT/COMPDATM 完井记录')
  await expect(grid3d).toContainText('WELL-A')
  const cnt = `FILETYPE MULTIWELL
START 2
RECLEN 4
WELLNAME HEADER LINE 1 ITEM 2
ENDPOINT MARKER -999
ATTRIBUTE XPOS POSITION 1 UNITS FEET
ATTRIBUTE YPOS POSITION 2 UNITS FEET
ATTRIBUTE TVD POSITION 3 UNITS FEET
ATTRIBUTE MD POSITION 4 UNITS FEET
FILE EX3.DEV
END
`
  const dev = `WELLNAME: SLANTW1
0 0 0 0
0.5 0 4 5
1 0 9 10
-999
`
  await grid3d.getByTestId('eclipse-deviation-files').setInputFiles([
    { name: 'EX3.CNT', mimeType: 'text/plain', buffer: Buffer.from(cnt) },
    { name: 'EX3.DEV', mimeType: 'text/plain', buffer: Buffer.from(dev) }
  ])
  const surveyPanel = grid3d.getByTestId('eclipse-deviation-import')
  await expect(surveyPanel.getByTestId('eclipse-deviation-summary')).toContainText('1')
  await expect(surveyPanel).toContainText('SLANTW1')
  await expect(surveyPanel.getByTestId('eclipse-deviation-plan')).toBeVisible()
  await expect(surveyPanel).toContainText('三维叠加仍需同源 GRDECL 与已核实深度基准')
  await surveyPanel.getByTestId('eclipse-grid-reference-file').setInputFiles({
    name: 'DEMO.GRDECL', mimeType: 'text/plain', buffer: Buffer.from('MAPAXES\n0 1\n0 0\n1 0 /\nGRIDUNIT\nFEET /\nMAPUNITS FEET\n')
  })
  await surveyPanel.getByTestId('eclipse-tvd-zero-z').fill('0')
  await surveyPanel.getByTestId('eclipse-tvd-direction').click()
  await page.getByRole('option', { name: 'TVD 增加时 EGRID Z 增加', exact: true }).click()
  await expect(grid3d.locator('.grid-3d-canvas')).toHaveAttribute('data-deviation-track-ready', 'true')
  await expect(surveyPanel.getByTestId('eclipse-deviation-overlay-ready')).toContainText('实测 Survey')
  await surveyPanel.getByTestId('eclipse-grid-reference-file').setInputFiles({
    name: 'WRONG.GRDECL', mimeType: 'text/plain', buffer: Buffer.from('MAPAXES\n0 0\n0 1000000\n1000000 1000000 /\nGRIDUNIT\nFEET /\nMAPUNITS FEET\n')
  })
  await expect(grid3d.locator('.grid-3d-canvas')).toHaveAttribute('data-deviation-track-ready', 'false')
  await expect(surveyPanel).toContainText('远离当前网格')
  await surveyPanel.getByTestId('eclipse-grid-reference-file').setInputFiles({
    name: 'DEMO.GRDECL', mimeType: 'text/plain', buffer: Buffer.from('MAPAXES\n0 1\n0 0\n1 0 /\nGRIDUNIT\nFEET /\nMAPUNITS FEET\n')
  })
  await expect(grid3d.locator('.grid-3d-canvas')).toHaveAttribute('data-deviation-track-ready', 'true')
  await surveyPanel.locator('.deviation-survey-wells .el-select').click()
  await page.getByRole('option', { name: 'WELL-A', exact: true }).click()
  await expect(surveyPanel.locator('.deviation-survey-wells')).toContainText('WELL-A')
  const canvas = grid3d.locator('.grid-3d-canvas canvas')
  const pickGridCell = async () => {
    const canvasBox = await canvas.boundingBox()
    await canvas.click({ position: { x: canvasBox.width / 2, y: canvasBox.height / 2 } })
    await expect(grid3d.locator('.selected-cell').first()).toBeVisible()
  }
  await pickGridCell()
  await expect(grid3d).toContainText('选中网格单元')
  await expect(grid3d.locator('.selected-cell')).toContainText('I1')
  await grid3d.getByLabel('编辑选中网格场值', { exact: true }).fill('1200')
  await grid3d.getByRole('button', { name: '加入编辑草案', exact: true }).click()
  await expect(grid3d).toContainText('编辑草案1 个当前字段单元')
  const downloadPromise = page.waitForEvent('download')
  await grid3d.getByRole('button', { name: '导出 JSON', exact: true }).click()
  const download = await downloadPromise
  expect(download.suggestedFilename()).toBe('eclipse-PRESSURE-field-edit-draft.json')
  const stream = await download.createReadStream()
  let json = ''
  for await (const chunk of stream) json += chunk.toString('utf8')
  expect(JSON.parse(json)).toMatchObject({ schemaVersion: 'eclipse-field-edit-draft/1', semantics: 'derived-postprocess-only', edits: [{ i: 1, j: 1, k: 1, editedValue: 1200 }] })
  await grid3d.locator('.grid-3d-controls .el-select').click()
  await page.getByRole('option', { name: /PERMX · DEMO.INIT/ }).click()
  await expect(grid3d).toHaveAttribute('data-render-ready', 'true')
  await expect(grid3d.locator('.selected-cell')).toHaveCount(0)
  const locator = grid3d.getByLabel('按 IJK 定位活动单元', { exact: true })
  for (const axis of ['I', 'J', 'K']) await locator.getByRole('spinbutton', { name: `定位网格 ${axis}`, exact: true }).fill('1')
  await locator.getByRole('button', { name: '定位活动单元', exact: true }).click()
  await expect(grid3d.locator('.selected-cell').first()).toContainText('200')
  await grid3d.locator('.grid-3d-controls .el-select').click()
  await page.getByRole('option', { name: /PRESSURE · DEMO.EGRID/ }).click()
  await expect(grid3d).toHaveAttribute('data-render-ready', 'true')
  await expect(grid3d.locator('.selected-cell')).toHaveCount(0)
  await pickGridCell()
  const pressureCell = grid3d.locator('.selected-cell').first()
  await expect(pressureCell.locator('dl > div').filter({ has: page.locator('dt', { hasText: /^原值$/ }) })).toContainText('1000')
  await expect(pressureCell.locator('dl > div').filter({ has: page.locator('dt', { hasText: /^当前值$/ }) })).toContainText('1200')
  await grid3d.locator('.grid-3d-controls .el-select').click()
  await page.getByRole('option', { name: /PERMX · DEMO.INIT/ }).click()
  await expect(grid3d).toHaveAttribute('data-render-ready', 'true')
  await expect(grid3d.locator('.selected-cell')).toHaveCount(0)
  await pickGridCell()
  await expect(grid3d.locator('.selected-cell').first()).toContainText('200')
  const permxScenario = grid3d.locator('[aria-label="PERMX 单格真实重算方案"]')
  await expect(permxScenario.getByRole('button', { name: '修改此单元并真实重算' })).toBeEnabled()
  await permxScenario.getByRole('button', { name: '修改此单元并真实重算' }).click()
  await expect.poll(() => state.createPayloads.length).toBe(1)
  expect(state.createPayloads[0]).toMatchObject({ versionId: 201, payload: {
    study: null,
    runType: 'eclipse',
    parameters: {
      schemaVersion: 'eclipse-grid-field-parameters/1', baselineRunId: 8803,
      fieldFile: 'DEMO.INIT', fieldArtifactSha256: 'c'.repeat(64),
      nx: 1, ny: 1, nz: 1, i: 1, j: 1, k: 1, originalValue: 200, multiplier: 0.5
    }
  } })
  expect(state.unexpectedRequests).toEqual([])
})


function groupRateFixture(mode) {
  const f=groupProductionFixture(),members=['P1','P2']
  f.deck.schemaVersion='eclipse-data-inspection/9';delete f.deck.groupControls
  const unit=mode==='GRAT' ? 'Mscf/day' : 'STB/day',groupKey=mode==='GRAT' ? 'GGPR' : 'GLPR',wellKey=mode==='GRAT' ? 'WGPR' : 'WLPR'
  f.deck.groupRateControls=[{group:'G',sourceFile:'CASE.DATA',lineNumber:14,controlMode:mode,unit,originalTargetRate:60000,members:members.map(well=>({well,bhpLimit:2500}))}]
  const output=(g,b)=>[[groupKey,'G',g,unit.toUpperCase()],...members.map(w=>[wellKey,w,g/2,unit.toUpperCase()]),...members.map(w=>['WBHP',w,b,'PSIA'])].map(([keyword,objectName,value,unit])=>({keyword,objectName,unit,points:[{timeDays:1,value}]}))
  f.baseline.result.summary.series=output(60000,3000)
  f.parameters={schemaVersion:'eclipse-group-rate-parameters/1',baselineRunId:9461,group:'G',phase:'INITIAL',sourceFile:'CASE.DATA',lineNumber:14,controlMode:mode,unit,originalTargetRate:60000,targetRate:30000,members}
  f.derived.parameters=f.parameters;f.derived.result.summary.series=output(30000,4000)
  return {...f,output,groupKey,wellKey}
}
for(const mode of ['GRAT','LRAT']) {
  test(`ECLIPSE ${mode} 生产组气液编辑器提交已有模式和完整成员`,async({page})=>{
    const f=groupRateFixture(mode),state=await installMockBackend(page,{eclipseInspection:f.deck,eclipseHistory:[summary(f.baseline)],runs:[f.baseline]})
    await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型')
    await page.getByRole('tab',{name:'DATA 检查'}).click()
    await expect(page.getByRole('heading',{name:'已有生产组气液目标',exact:true})).toBeVisible()
    await page.getByRole('tab',{name:'ECLIPSE 结果'}).click()
    await page.getByText('调整生产组总产气/总产液目标',{exact:true}).click()
    const panel=page.getByRole('region',{name:'ECLIPSE 生产组气液目标方案'})
    await expect(panel).toContainText(mode);await expect(panel).toContainText('P1 BHP≥2500')
    await panel.getByRole('spinbutton',{name:'ECLIPSE 组总产气/总产液目标'}).fill('30000')
    await page.getByRole('button',{name:'运行',exact:true}).click()
    await expect.poll(()=>state.createPayloads.length).toBe(1)
    expect(state.createPayloads[0].payload.parameters).toEqual(f.parameters)
    expect(state.unexpectedRequests).toEqual([])
  })
  for(const fault of ['success','limited','partial','missing'])test(`ECLIPSE ${mode} 生产组气液结果 ${fault} 刷新恢复`,async({page})=>{
    const f=groupRateFixture(mode)
    if(fault==='limited'){f.derived.parameters={...f.parameters,targetRate:80000};f.derived.result.summary.series=f.output(48000,2500)}
    if(fault==='partial'){
      const early=f.output(20000,2500)
      f.derived.result.summary.series.forEach((s,index)=>{s.points[0].timeDays=2;s.points.unshift(early[index].points[0]);f.baseline.result.summary.series[index].points.push({...f.baseline.result.summary.series[index].points[0],timeDays:2})})
    }
    if(fault==='missing')f.derived.result.summary.series.pop()
    const state=await installMockBackend(page,{eclipseInspection:f.deck,eclipseHistory:[summary(f.derived),summary(f.baseline)],runs:[f.derived,f.baseline]})
    await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型')
    const check=async()=>{
      const panel=page.getByRole('region',{name:'ECLIPSE Schedule 方案验收'})
      await expect(panel).toContainText('GCONPROD 生产组气液方案验收')
      await expect(panel).toContainText(fault==='success' ? '生产组目标达标，已验证各井分配与压力下限' : fault==='limited' ? '生产组目标未达标：各井已触及 BHP 下限' : fault==='partial' ? '生产组部分时刻未达标：各井已触及 BHP 下限' : '缺少同时间的 GGPR/GLPR、WGPR/WLPR 或 BHP，无法验证生产分配')
      await expect(panel.getByLabel('ECLIPSE 生产组气液总量核对',{exact:true})).toContainText(`各井 ${f.wellKey} 合计`)
    }
    await check();await page.reload();await activateModel(page,'ECLIPSE演示模型');await check()
    expect(state.unexpectedRequests).toEqual([])
  })
}

function groupWaterProductionFixture() {
 const f=groupRateFixture('LRAT'), members=['P1','P2']
 f.deck.schemaVersion='eclipse-data-inspection/10'
 f.deck.waterProductionGroupControls=f.deck.groupRateControls.map(g=>({...g,controlMode:'WRAT',members:g.members.map(m=>({...m,oilRateLimit:20000}))}))
 delete f.deck.groupRateControls
 f.parameters={...f.parameters,schemaVersion:'eclipse-group-water-production-parameters/1',controlMode:'WRAT'}
 f.derived.parameters=f.parameters
 const output=(g,b,oil=1000)=>[['GWPR','G',g,'STB/DAY'],...members.map(w=>['WWPR',w,g/2,'STB/DAY']),...members.map(w=>['WBHP',w,b,'PSIA']),...members.map(w=>['WOPR',w,oil,'STB/DAY'])].map(([keyword,objectName,value,unit])=>({keyword,objectName,unit,points:[{timeDays:1,value}]}))
 f.baseline.result.summary.series=output(60000,3000)
 f.derived.result.summary.series=output(30000,4000)
 return {...f,output,groupKey:'GWPR',wellKey:'WWPR'}
}
for(const mode of ['WRAT']) {
  test(`ECLIPSE ${mode} 生产组产水编辑器提交已有模式和完整成员`,async({page})=>{
    const f=groupWaterProductionFixture(),state=await installMockBackend(page,{eclipseInspection:f.deck,eclipseHistory:[summary(f.baseline)],runs:[f.baseline]})
    await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型')
    await page.getByRole('tab',{name:'DATA 检查'}).click()
    await expect(page.getByRole('heading',{name:'已有生产组产水目标',exact:true})).toBeVisible()
    await page.getByRole('tab',{name:'ECLIPSE 结果'}).click()
    await page.getByText('调整生产组总产水目标',{exact:true}).click()
    const panel=page.getByRole('region',{name:'ECLIPSE 生产组产水目标方案'})
    await expect(panel).toContainText(mode);await expect(panel).toContainText('P1 BHP≥2500')
    await panel.getByRole('spinbutton',{name:'ECLIPSE 组总产水目标'}).fill('30000')
    await page.getByRole('button',{name:'运行',exact:true}).click()
    await expect.poll(()=>state.createPayloads.length).toBe(1)
    expect(state.createPayloads[0].payload.parameters).toEqual(f.parameters)
    expect(state.unexpectedRequests).toEqual([])
  })
  for(const fault of ['success','limited','partial','missing'])test(`ECLIPSE ${mode} 生产组产水结果 ${fault} 刷新恢复`,async({page})=>{
    const f=groupWaterProductionFixture()
    if(fault==='limited'){f.derived.parameters={...f.parameters,targetRate:80000};f.derived.result.summary.series=f.output(48000,3000,20000)}
    if(fault==='partial'){
      const early=f.output(20000,3000,20000)
      f.derived.result.summary.series.forEach((s,index)=>{s.points[0].timeDays=2;s.points.unshift(early[index].points[0]);f.baseline.result.summary.series[index].points.push({...f.baseline.result.summary.series[index].points[0],timeDays:2})})
    }
    if(fault==='missing')f.derived.result.summary.series.pop()
    const state=await installMockBackend(page,{eclipseInspection:f.deck,eclipseHistory:[summary(f.derived),summary(f.baseline)],runs:[f.derived,f.baseline]})
    await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型')
    const check=async()=>{
      const panel=page.getByRole('region',{name:'ECLIPSE Schedule 方案验收'})
      await expect(panel).toContainText('GCONPROD 生产组产水方案验收')
      await expect(panel).toContainText(fault==='success' ? '生产组目标达标，已验证各井分配与压力下限' : fault==='limited' ? '生产组目标未达标：各井已触及原压力或产油上限' : fault==='partial' ? '生产组部分时刻未达标：各井已触及原压力或产油上限' : '缺少同时间的 GWPR、WWPR、WOPR 或 BHP，无法验证生产分配')
      await expect(panel.getByLabel('ECLIPSE 生产组产水总量核对',{exact:true})).toContainText(`各井 ${f.wellKey} 合计`)
    }
    await check();await page.reload();await activateModel(page,'ECLIPSE演示模型');await check()
    expect(state.unexpectedRequests).toEqual([])
  })
}

function advancedInjectionFixture(mode) {
 const f=groupProductionFixture(),fraction=['REIN','VREP'].includes(mode),reservoir=['RESV','VREP'].includes(mode),members=['I1','I2'],refs=fraction?['P1','P2']:[]
 const phase=mode==='RATE'?'OIL':'GAS',unit=fraction?'fraction':reservoir?'RB/day':'STB/day',flowUnit=reservoir?'RB/DAY':phase==='OIL'?'STB/DAY':'MSCF/DAY',groupKey=reservoir?'GVIR':phase==='OIL'?'GOIR':'GGIR',wellKey=reservoir?'WVIR':phase==='OIL'?'WOIR':'WGIR'
 f.deck.schemaVersion='eclipse-data-inspection/11';delete f.deck.groupControls
 f.deck.wellNames=[...members,...refs];f.deck.scheduleMetadata.wells=[...members.map(name=>({name,group:'I',sourceFile:'CASE.DATA',lineNumber:8})),...refs.map(name=>({name,group:'G',sourceFile:'CASE.DATA',lineNumber:9}))]
 f.deck.advancedInjectionGroupControls=[{group:'I',sourceFile:'CASE.DATA',lineNumber:14,controlMode:mode,injectionPhase:phase,unit,originalTargetRate:fraction?0.2:10000,members:members.map(well=>({well,bhpLimit:4900,surfaceRateLimit:50000})),referenceGroup:fraction?'G':null,referenceMembers:refs}]
 f.parameters={schemaVersion:'eclipse-advanced-injection-parameters/1',baselineRunId:9461,group:'I',phase:'INITIAL',sourceFile:'CASE.DATA',lineNumber:14,controlMode:mode,injectionPhase:phase,unit,originalTargetRate:fraction?0.2:10000,targetRate:fraction?0.1:5000,members,referenceGroup:fraction?'G':null,referenceMembers:refs}
 const output=(g,b,production=100000)=>[[groupKey,'I',g,flowUnit],...members.map(w=>[wellKey,w,g/2,flowUnit]),...members.map(w=>['WBHP',w,b,'PSIA']),...(reservoir?members.map(w=>['WGIR',w,10000,'MSCF/DAY']):[]),...(fraction?[[mode==='REIN'?'GGPR':'GVPR','G',production,flowUnit],...refs.map(w=>[mode==='REIN'?'WGPR':'WVPR',w,production/2,flowUnit])]:[])].map(([keyword,objectName,value,unit])=>({keyword,objectName,unit,points:[{timeDays:1,value}]}))
 f.baseline.result.summary.series=output(fraction?20000:10000,2500);f.derived.parameters=f.parameters;f.derived.result.summary.series=output(fraction?10000:5000,3000)
 return {...f,output,fraction}
}
for(const mode of ['RATE','RESV','REIN','VREP']) {
 test(`ECLIPSE ${mode} 注入方案编辑器提交完整来源与目标`,async({page})=>{
  const f=advancedInjectionFixture(mode),state=await installMockBackend(page,{eclipseInspection:f.deck,eclipseHistory:[summary(f.baseline)],runs:[f.baseline]})
  await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型');await page.getByRole('tab',{name:'DATA 检查'}).click()
  await expect(page.getByRole('heading',{name:'已有注入目标与回注比例',exact:true})).toBeVisible()
  await page.getByRole('tab',{name:'ECLIPSE 结果'}).click();await page.getByText('调整注入目标或回注比例',{exact:true}).click()
  const panel=page.getByRole('region',{name:'ECLIPSE 注入目标与回注比例方案'})
  await expect(panel).toContainText(mode);await expect(panel).toContainText('I1 ≤ 4900')
  if(f.fraction)await expect(panel).toContainText('来源生产组 G')
  await panel.getByRole('spinbutton',{name:'ECLIPSE 注入目标或比例'}).fill(f.fraction?'0.1':'5000')
  await page.getByRole('button',{name:'运行',exact:true}).click();await expect.poll(()=>state.createPayloads.length).toBe(1)
  expect(state.createPayloads[0].payload.parameters).toEqual(f.parameters);expect(state.unexpectedRequests).toEqual([])
 })
 for(const fault of ['success','limited','partial','missing'])test(`ECLIPSE ${mode} 注入方案结果 ${fault} 刷新恢复`,async({page})=>{
  const f=advancedInjectionFixture(mode)
  if(fault==='limited'){f.derived.parameters={...f.parameters,targetRate:f.fraction?5:100000};f.derived.result.summary.series=f.output(f.fraction?40000:4000,4900)}
  if(fault==='partial'){
   const early=f.output(f.fraction?5000:4000,4900)
   f.derived.result.summary.series.forEach((s,n)=>{s.points[0].timeDays=2;s.points.unshift(early[n].points[0]);f.baseline.result.summary.series[n].points.push({...f.baseline.result.summary.series[n].points[0],timeDays:2})})
  }
  if(fault==='missing')f.derived.result.summary.series.pop()
  const state=await installMockBackend(page,{eclipseInspection:f.deck,eclipseHistory:[summary(f.derived),summary(f.baseline)],runs:[f.derived,f.baseline]})
  await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型')
  const check=async()=>{
   const panel=page.getByRole('region',{name:'ECLIPSE Schedule 方案验收'})
   await expect(panel).toContainText('GCONINJE 注入目标与回注比例验收')
   await expect(panel).toContainText(fault==='success'?'注入目标达标，已核对各井分配与来源生产量':fault==='limited'?'目标未达标：各井已触及原压力或流量上限':fault==='partial'?'部分时刻未达标：各井已触及原压力或流量上限':'缺少完整同时间注入、压力或来源生产曲线，无法验证')
   await expect(panel.getByLabel('ECLIPSE 注入方案总量核对',{exact:true})).toContainText('各注入井合计')
   if(f.fraction){await expect(panel).toContainText('来源生产组 G');await expect(panel).toContainText('实际比例');await expect(panel).toContainText('比例误差')}
  }
  await check();await page.reload();await activateModel(page,'ECLIPSE演示模型');await check();expect(state.unexpectedRequests).toEqual([])
 })
}

function controlPlanBrowserFixture() {
 const f=groupProductionFixture(),c=controlPlanFixture('CASE.DATA'),dimensions=f.deck.dimensions
 f.deck={...c.inspection,dimensions};f.parameters={...c.parameters,baselineRunId:f.baseline.id}
 f.baseline.result.summary=c.summary();f.derived.parameters=f.parameters;f.derived.result.summary=c.summary(f.parameters)
 return {...f,c}
}
test('ECLIPSE 分阶段产油目标编辑器提交六项完整来源',async({page})=>{
 const f=controlPlanBrowserFixture(),state=await installMockBackend(page,{eclipseInspection:f.deck,eclipseHistory:[summary(f.baseline)],runs:[f.baseline]})
 await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型');await page.getByRole('tab',{name:'DATA 检查'}).click()
 await expect(page.getByRole('heading',{name:'已有分阶段产油目标',exact:true})).toBeVisible()
 await page.getByRole('tab',{name:'ECLIPSE 结果'}).click();await page.getByText('制定多井分阶段产油目标',{exact:true}).click()
 const panel=page.getByRole('region',{name:'ECLIPSE 分阶段产油目标方案'})
 for(const e of f.parameters.edits)await panel.getByRole('spinbutton',{name:`ECLIPSE 阶段目标 ${e.well} ${e.date}`,exact:true}).fill(String(e.targetOilRate))
 await page.getByRole('button',{name:'运行',exact:true}).click();await expect.poll(()=>state.createPayloads.length).toBe(1)
 expect(state.createPayloads[0].payload.parameters).toEqual(f.parameters);expect(state.unexpectedRequests).toEqual([])
})
for(const fault of ['success','limited','partial','missing','initial'])test(`ECLIPSE 分阶段产油目标结果 ${fault} 刷新恢复`,async({page})=>{
 const f=controlPlanBrowserFixture()
 if(fault==='limited'||fault==='partial')f.derived.result.summary=f.c.summary(f.parameters,fault)
 if(fault==='missing')f.derived.result.summary.series.pop()
 if(fault==='initial'){
  const r=f.deck.productionControlPlan.records[0],{bhpLimit,...e}=r
  f.parameters={schemaVersion:'eclipse-control-plan-parameters/1',baselineRunId:f.baseline.id,edits:[{...e,targetOilRate:400}]}
  f.derived.parameters=f.parameters;f.derived.result.summary=f.c.summary(f.parameters)
 }
 const state=await installMockBackend(page,{eclipseInspection:f.deck,eclipseHistory:[summary(f.derived),summary(f.baseline)],runs:[f.derived,f.baseline]})
 await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型')
 const check=async()=>{
  const panel=page.getByRole('region',{name:'ECLIPSE Schedule 方案验收'})
  await expect(panel).toContainText('多井分阶段产油目标验收')
  await expect(panel).toContainText(fault==='missing'?'缺少完整目标链或井油量、压力曲线，无法核对阶段':fault==='limited'||fault==='partial'?'分阶段目标未达标：井已触及原 BHP 下限':'分阶段目标达标，已核对后续覆盖与井压力')
  if(fault!=='missing'){
   await expect(panel.getByLabel('ECLIPSE 阶段修改逐项核对',{exact:true})).toContainText('PRODUCR1')
   await expect(panel.getByLabel('ECLIPSE 阶段目标与实际井响应',{exact:true})).toContainText(fault==='initial'?'400':'250')
  }
 }
 await check();await page.reload();await activateModel(page,'ECLIPSE演示模型');await check();expect(state.unexpectedRequests).toEqual([])
})

function groupStructureBrowserFixture(keyword) {
 const f=groupProductionFixture(),g=structure(keyword)
 delete f.deck.groupControls
 Object.assign(f.deck,g.inspection,{wellNames:g.p.members,vfpTables:[]})
 f.deck.scheduleMetadata.records=[];f.deck.scheduleMetadata.completions=[]
 f.deck.scheduleMetadata.groups.forEach((r,n)=>Object.assign(r,{sourceFile:'CASE.DATA',lineNumber:20+n}))
 f.deck.scheduleMetadata.wells.forEach((r,n)=>Object.assign(r,{sourceFile:'CASE.DATA',lineNumber:8+n}))
 f.deck.scheduleTimeline=[{kind:'TSTEP',steps:['3650'],sourceFile:'CASE.DATA',lineNumber:100}]
 f.parameters={...g.p,baselineRunId:f.baseline.id};f.derived.parameters=f.parameters
 f.baseline.result.summary=g.summary();f.derived.result.summary=g.summary(true)
 return {...f,g}
}
for(const keyword of ['GCONPROD','WGRUPCON','GEFAC']) {
 test(`ECLIPSE 组树 ${keyword} 编辑完整来源与图`,async({page})=>{
  const f=groupStructureBrowserFixture(keyword),state=await installMockBackend(page,{eclipseInspection:f.deck,eclipseHistory:[summary(f.baseline)],runs:[f.baseline]})
  await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型');await page.getByRole('tab',{name:'DATA 检查'}).click()
  await expect(page.getByRole('heading',{name:'已有生产组树与分配控制',exact:true})).toBeVisible()
  await page.getByRole('tab',{name:'ECLIPSE 结果'}).click();await page.getByText('调整组树目标、井导向或开工比例',{exact:true}).click()
  const panel=page.getByRole('region',{name:'ECLIPSE 组树与分配方案'})
  if(keyword!=='GCONPROD'){
   await panel.locator('.el-select').filter({has:page.getByRole('combobox',{name:'ECLIPSE 组树操作'})}).click()
   await page.getByRole('option',{name:keyword==='WGRUPCON'?'井固定油导向 · PRODUCR2':'子组开工比例 · GA',exact:true}).click()
  }
  await expect(panel).toContainText('P → GA → PRODUCR1、PRODUCR2')
  await expect(panel).toContainText('20000/2500')
  await panel.getByRole('spinbutton',{name:'ECLIPSE 组树新值'}).fill(String(f.parameters.targetValue))
  await page.getByRole('button',{name:'运行',exact:true}).click();await expect.poll(()=>state.createPayloads.length).toBe(1)
  expect(state.createPayloads[0].payload.parameters).toEqual(f.parameters);expect(state.unexpectedRequests).toEqual([])
 })
 for(const fault of ['success','limited','partial','missing'])test(`ECLIPSE 组树 ${keyword} 结果 ${fault} 刷新恢复`,async({page})=>{
  const f=groupStructureBrowserFixture(keyword)
  if(fault==='limited')f.derived.result.summary=f.g.summary(true,true)
  if(fault==='partial'){
   const late=f.g.summary(true,true)
   f.derived.result.summary.series.forEach((s,n)=>s.points.push({...late.series[n].points[0],timeDays:11}))
  }
  if(fault==='missing')f.derived.result.summary.series.pop()
  const state=await installMockBackend(page,{eclipseInspection:f.deck,eclipseHistory:[summary(f.derived),summary(f.baseline)],runs:[f.derived,f.baseline]})
  await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型')
  const check=async()=>{
   const panel=page.getByRole('region',{name:'ECLIPSE Schedule 方案验收'})
   await expect(panel).toContainText('生产组树、井导向与开工比例验收')
   await expect(panel).toContainText(fault==='success'?'组树目标达标，已验证井分配与开工比例影响':fault==='missing'?'缺少完整组树、累计量、井效率或压力曲线，无法验证':'目标或分配未达标：井已触及原压力或产油上限')
   await expect(panel.getByLabel('ECLIPSE 父组目标与累计量核对',{exact:true})).toContainText('子组折算贡献合计')
   await expect(panel.getByLabel('ECLIPSE 组树井分配与限值',{exact:true})).toContainText('权重理论分配')
  }
  await check();await page.reload();await activateModel(page,'ECLIPSE演示模型');await check();expect(state.unexpectedRequests).toEqual([])
 })
}


test('ECLIPSE 水物性 原五项和cP单位提交完整源绑定',async({page})=>{
  const f=pvtwBrowserFixture(),state=await installMockBackend(page,{eclipseInspection:f.inspection,eclipseHistory:[summary(f.baseline)],runs:[f.baseline]})
  await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型')
  const panel=page.getByRole('region',{name:'ECLIPSE 水物性调整',exact:true});await expect(panel).toContainText('0.5');await expect(panel).toContainText('3.03E-06');await expect(panel).toContainText('cP')
  await panel.getByRole('spinbutton',{name:'水物性新值',exact:true}).fill('1');await panel.getByRole('button',{name:'创建水物性方案并计算',exact:true}).click()
  await expect.poll(()=>state.createPayloads.length).toBe(1);expect(state.createPayloads[0].payload.parameters).toEqual(f.parameters);expect(state.unexpectedRequests).toEqual([])
})
for(const value of ['0','0.5'])test(`ECLIPSE 水物性 ${value}拒绝且不创建运行`,async({page})=>{
  const f=pvtwBrowserFixture(),state=await installMockBackend(page,{eclipseInspection:f.inspection,eclipseHistory:[summary(f.baseline)],runs:[f.baseline]})
  await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型');const panel=page.getByRole('region',{name:'ECLIPSE 水物性调整',exact:true})
  await panel.getByRole('spinbutton',{name:'水物性新值',exact:true}).fill(value);await panel.getByRole('button',{name:'创建水物性方案并计算',exact:true}).click();await expect(panel.getByRole('alert')).toContainText('不同于原值');expect(state.createPayloads).toEqual([]);expect(state.unexpectedRequests).toEqual([])
})
test('ECLIPSE 水物性 实际压力与保留产量刷新恢复',async({page})=>{
  const f=pvtwBrowserFixture(),state=await installMockBackend(page,{eclipseInspection:f.inspection,eclipseHistory:[summary(f.target),summary(f.baseline)],runs:[f.target,f.baseline]})
  await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型');const panel=page.getByRole('region',{name:'ECLIPSE 水物性方案验收',exact:true})
  const check=async()=>{await expect(panel).toContainText('0.5 → 1 cP');await expect(panel).toContainText('144 项实际井级数值');await expect(panel.getByLabel('ECLIPSE 水物性井级响应',{exact:true})).toContainText('3770.230712890625')}
  await check();await page.reload();await activateModel(page,'ECLIPSE演示模型');await check();expect(state.unexpectedRequests).toEqual([])
})
test('ECLIPSE 水物性 缺少原表与单位冲突不提供假编辑或差值',async({page})=>{
  const f=pvtwBrowserFixture();delete f.inspection.pvtwContext
  await installMockBackend(page,{eclipseInspection:f.inspection,eclipseHistory:[summary(f.baseline)],runs:[f.baseline]});await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型')
  const panel=page.getByRole('region',{name:'ECLIPSE 水物性调整',exact:true});await expect(panel).toContainText('唯一明确五项');await expect(panel.getByRole('button',{name:'创建水物性方案并计算',exact:true})).toHaveCount(0)
})
test('ECLIPSE 水物性 混压力单位拒绝生成差值',async({page})=>{
  const f=pvtwBrowserFixture();f.target.result.summary.series.find(s=>s.keyword==='WBHP'&&s.objectName==='P').unit='PSIA'
  await installMockBackend(page,{eclipseInspection:f.inspection,eclipseHistory:[summary(f.target),summary(f.baseline)],runs:[f.target,f.baseline]});await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型')
  const panel=page.getByRole('region',{name:'ECLIPSE 水物性方案验收',exact:true});await expect(panel).toContainText('单位不一致');await expect(panel.getByLabel('ECLIPSE 水物性井级响应',{exact:true})).toHaveCount(0)
})

for(const [label,title,unit,last] of [['pressure','参考压力','barsa','3883.715087890625'],['volume','水体积系数','rm3/sm3','3882.820068359375'],['compressibility','水压缩性','1/bar','3886.484619140625'],['viscosibility','黏压系数','1/bar','3884.661865234375']]){
 test(`ECLIPSE 水物性 ${label}原单位原值与完整五项来源提交`,async({page})=>{
  const f=pvtwBrowserFixture(label),state=await installMockBackend(page,{eclipseInspection:f.inspection,eclipseHistory:[summary(f.baseline)],runs:[f.baseline]})
  await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型');const panel=page.getByRole('region',{name:'ECLIPSE 水物性调整',exact:true})
  await panel.locator('.el-select').filter({has:page.getByRole('combobox',{name:'水物性调整项目',exact:true})}).click();await page.getByRole('option',{name:title,exact:true}).click();await expect(panel).toContainText(unit)
  await panel.getByRole('spinbutton',{name:'水物性新值',exact:true}).fill(String(f.parameters.targetValue));await panel.getByRole('button',{name:'创建水物性方案并计算',exact:true}).click()
  await expect.poll(()=>state.createPayloads.length).toBe(1);expect(state.createPayloads[0].payload.parameters).toEqual(f.parameters);expect(state.unexpectedRequests).toEqual([])
 })
 test(`ECLIPSE 水物性 ${label}实际原生压力与刷新恢复`,async({page})=>{
  const f=pvtwBrowserFixture(label),state=await installMockBackend(page,{eclipseInspection:f.inspection,eclipseHistory:[summary(f.target),summary(f.baseline)],runs:[f.target,f.baseline]})
  await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型');const panel=page.getByRole('region',{name:'ECLIPSE 水物性方案验收',exact:true})
  const check=async()=>{await expect(panel).toContainText(title+'方案验收');await expect(panel).toContainText(f.parameters.targetValue+' '+unit);await expect(panel).toContainText('144 项实际井级数值');await expect(panel.getByLabel('ECLIPSE 水物性井级响应',{exact:true})).toContainText(last)}
  await check();await page.reload();await activateModel(page,'ECLIPSE演示模型');await check();expect(state.unexpectedRequests).toEqual([])
 })
}

test('ECLIPSE 油物性 完整原表和原单位提交',async({page})=>{
 const f=oilPvtBrowserFixture(),state=await installMockBackend(page,{eclipseInspection:f.inspection,eclipseHistory:[summary(f.baseline)],runs:[f.baseline]})
 await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型');const panel=page.getByRole('region',{name:'ECLIPSE 油物性调整',exact:true})
 await expect(panel).toContainText('6 行');await expect(panel).toContainText('cP');const table=panel.getByLabel('ECLIPSE 原油物性与目标预览',{exact:true});await expect(table).toContainText('psia');await expect(table).toContainText('rb/stb');await expect(table).toContainText('1.243')
 await panel.getByRole('spinbutton',{name:'油黏度乘数',exact:true}).fill('2');await panel.getByRole('button',{name:'创建油物性方案并计算',exact:true}).click();await expect.poll(()=>state.createPayloads.length).toBe(1);expect(state.createPayloads[0].payload.parameters).toEqual(f.parameters);expect(state.unexpectedRequests).toEqual([])
})
for(const value of ['0','1'])test(`ECLIPSE 油物性 ${value}拒绝且不创建运行`,async({page})=>{
 const f=oilPvtBrowserFixture(),state=await installMockBackend(page,{eclipseInspection:f.inspection,eclipseHistory:[summary(f.baseline)],runs:[f.baseline]})
 await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型');const panel=page.getByRole('region',{name:'ECLIPSE 油物性调整',exact:true})
 await panel.getByRole('spinbutton',{name:'油黏度乘数',exact:true}).fill(value);await panel.getByRole('button',{name:'创建油物性方案并计算',exact:true}).click();await expect(panel.getByRole('alert')).toContainText('不为1');expect(state.createPayloads).toEqual([]);expect(state.unexpectedRequests).toEqual([])
})
test('ECLIPSE 油物性 实际油水总液与压力限制刷新恢复',async({page})=>{
 const f=oilPvtBrowserFixture(),state=await installMockBackend(page,{eclipseInspection:f.inspection,eclipseHistory:[summary(f.target),summary(f.baseline)],runs:[f.target,f.baseline]})
 await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型');const panel=page.getByRole('region',{name:'ECLIPSE 油物性方案验收',exact:true})
 const check=async()=>{await expect(panel).toContainText('6 行油黏度 × 2');await expect(panel).toContainText('84 项实际井级数值');const table=panel.getByLabel('ECLIPSE 油物性井级响应',{exact:true});for(const text of ['WLPR','WOPR','WWPR','WBHP','STB/DAY','PSIA','785.7467651367188','782.5386352539062','3.208101749420166','3000'])await expect(table).toContainText(text)}
 await check();await page.reload();await activateModel(page,'ECLIPSE演示模型');await check();expect(state.unexpectedRequests).toEqual([])
})
test('ECLIPSE 油物性 缺少原表不提供编辑',async({page})=>{
 const f=oilPvtBrowserFixture();delete f.inspection.oilPvtContext;await installMockBackend(page,{eclipseInspection:f.inspection,eclipseHistory:[summary(f.baseline)],runs:[f.baseline]});await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型');const panel=page.getByRole('region',{name:'ECLIPSE 油物性调整',exact:true});await expect(panel).toContainText('唯一明确PVDO');await expect(panel.getByRole('button',{name:'创建油物性方案并计算',exact:true})).toHaveCount(0)
})
test('ECLIPSE 油物性 混总液单位不生成假差值',async({page})=>{
 const f=oilPvtBrowserFixture();f.target.result.summary.series.find(s=>s.keyword==='WLPR').unit='SM3/DAY';await installMockBackend(page,{eclipseInspection:f.inspection,eclipseHistory:[summary(f.target),summary(f.baseline)],runs:[f.target,f.baseline]});await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型');const panel=page.getByRole('region',{name:'ECLIPSE 油物性方案验收',exact:true});await expect(panel).toContainText('单位不一致');await expect(panel.getByLabel('ECLIPSE 油物性井级响应',{exact:true})).toHaveCount(0)
})

test('ECLIPSE 气物性 完整原表和原单位提交',async({page})=>{
 const f=gasPvtBrowserFixture(),state=await installMockBackend(page,{eclipseInspection:f.inspection,eclipseHistory:[summary(f.baseline)],runs:[f.baseline]})
 await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型');const panel=page.getByRole('region',{name:'ECLIPSE 气物性调整',exact:true})
 await expect(panel).toContainText('2 行');await expect(panel).toContainText('cP');const table=panel.getByLabel('ECLIPSE 原气物性与目标预览',{exact:true});await expect(table).toContainText('psia');await expect(table).toContainText('rb/Mscf');await expect(table).toContainText('.92')
 await panel.getByRole('spinbutton',{name:'气黏度乘数',exact:true}).fill('2');await panel.getByRole('button',{name:'创建气物性方案并计算',exact:true}).click();await expect.poll(()=>state.createPayloads.length).toBe(1);expect(state.createPayloads[0].payload.parameters).toEqual(f.parameters);expect(state.unexpectedRequests).toEqual([])
})
for(const value of ['0','1'])test(`ECLIPSE 气物性 ${value}拒绝且不创建运行`,async({page})=>{
 const f=gasPvtBrowserFixture(),state=await installMockBackend(page,{eclipseInspection:f.inspection,eclipseHistory:[summary(f.baseline)],runs:[f.baseline]})
 await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型');const panel=page.getByRole('region',{name:'ECLIPSE 气物性调整',exact:true})
 await panel.getByRole('spinbutton',{name:'气黏度乘数',exact:true}).fill(value);await panel.getByRole('button',{name:'创建气物性方案并计算',exact:true}).click();await expect(panel.getByRole('alert')).toContainText('不为1');expect(state.createPayloads).toEqual([]);expect(state.unexpectedRequests).toEqual([])
})
test('ECLIPSE 气物性 实际不同时间步及6000天响应刷新恢复',async({page})=>{
 const f=gasPvtBrowserFixture(),state=await installMockBackend(page,{eclipseInspection:f.inspection,eclipseHistory:[summary(f.target),summary(f.baseline)],runs:[f.target,f.baseline]})
 await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型');const panel=page.getByRole('region',{name:'ECLIPSE 气物性方案验收',exact:true})
 const check=async()=>{await expect(panel).toContainText('2 行气黏度 × 2');await expect(panel).toContainText('952 项实际井级数值');await expect(panel).toContainText('904 项缺少同时间值');await panel.getByText('全部报告时刻',{exact:true}).click();await page.getByRole('option',{name:'6000天',exact:true}).click();const table=panel.getByLabel('ECLIPSE 气物性井级响应',{exact:true});await expect(table.locator('tbody tr')).toHaveCount(8);for(const text of ['WGIT','WWPT','WBHP','MSCF','STB/DAY','PSIA','4145.6728515625','4138.58984375','200.07347106933594','1176417.625','600000','4000'])await expect(table).toContainText(text)}
 await check();await page.reload();await activateModel(page,'ECLIPSE演示模型');await check();expect(state.unexpectedRequests).toEqual([])
})
test('ECLIPSE 气物性 缺少原表不提供编辑',async({page})=>{
 const f=gasPvtBrowserFixture();delete f.inspection.gasPvtContext;await installMockBackend(page,{eclipseInspection:f.inspection,eclipseHistory:[summary(f.baseline)],runs:[f.baseline]});await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型');const panel=page.getByRole('region',{name:'ECLIPSE 气物性调整',exact:true});await expect(panel).toContainText('唯一明确PVDG');await expect(panel.getByRole('button',{name:'创建气物性方案并计算',exact:true})).toHaveCount(0)
})
test('ECLIPSE 气物性 混累计气单位不生成假差值',async({page})=>{
 const f=gasPvtBrowserFixture();f.target.result.summary.series.find(s=>s.keyword==='WGIT').unit='SM3';await installMockBackend(page,{eclipseInspection:f.inspection,eclipseHistory:[summary(f.target),summary(f.baseline)],runs:[f.target,f.baseline]});await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型');const panel=page.getByRole('region',{name:'ECLIPSE 气物性方案验收',exact:true});await expect(panel).toContainText('单位不一致');await expect(panel.getByLabel('ECLIPSE 气物性井级响应',{exact:true})).toHaveCount(0)
})

for(const keyword of ['SWFN','SOF3'])test(`ECLIPSE 相渗 ${keyword}完整原表与无量纲目标提交`,async({page})=>{
 const f=relpermBrowserFixture(keyword),state=await installMockBackend(page,{eclipseInspection:f.inspection,eclipseHistory:[summary(f.baseline)],runs:[f.baseline]})
 await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型');const panel=page.getByRole('region',{name:'ECLIPSE 相渗调整',exact:true})
 if(keyword==='SOF3'){await panel.getByText('水相相渗 · SWFN',{exact:true}).click();await page.getByRole('option',{name:'油相两列联动 · SOF3',exact:true}).click()}
 await expect(panel).toContainText(keyword==='SWFN'?'13 行':'15 行');await expect(panel).toContainText('无量纲')
 const table=panel.getByLabel('ECLIPSE 原相渗与目标预览',{exact:true});await expect(table).toContainText(keyword==='SWFN'?'原毛管压力（psi）':'原油气相渗 Krog')
 await panel.getByRole('spinbutton',{name:'相渗乘数',exact:true}).fill('0.5');await panel.getByRole('button',{name:'创建相渗方案并计算',exact:true}).click()
 await expect.poll(()=>state.createPayloads.length).toBe(1);expect(state.createPayloads[0].payload.parameters).toEqual(f.parameters);expect(state.unexpectedRequests).toEqual([])
})
for(const value of ['0','1','2'])test(`ECLIPSE 相渗 ${value}拒绝且不创建运行`,async({page})=>{
 const f=relpermBrowserFixture('SWFN'),state=await installMockBackend(page,{eclipseInspection:f.inspection,eclipseHistory:[summary(f.baseline)],runs:[f.baseline]})
 await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型');const panel=page.getByRole('region',{name:'ECLIPSE 相渗调整',exact:true})
 await panel.getByRole('spinbutton',{name:'相渗乘数',exact:true}).fill(value);await panel.getByRole('button',{name:'创建相渗方案并计算',exact:true}).click();await expect(panel.getByRole('alert')).toContainText('0–1');expect(state.createPayloads).toEqual([]);expect(state.unexpectedRequests).toEqual([])
})
for(const keyword of ['SWFN','SOF3'])test(`ECLIPSE 相渗 ${keyword}实际油水液响应与刷新恢复`,async({page})=>{
 const f=relpermBrowserFixture(keyword),state=await installMockBackend(page,{eclipseInspection:f.inspection,eclipseHistory:[summary(f.target),summary(f.baseline)],runs:[f.target,f.baseline]})
 await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型');const panel=page.getByRole('region',{name:'ECLIPSE 相渗方案验收',exact:true})
 const check=async()=>{await expect(panel).toContainText(`${keyword} · ${keyword==='SWFN'?13:15} 行相渗 × 0.5`);await expect(panel).toContainText('84 项实际井级数值');await expect(panel).toContainText('0 项缺少同时间值');const table=panel.getByLabel('ECLIPSE 相渗井级响应',{exact:true});for(const text of ['WLPR','WOPR','WWPR','WBHP','STB/DAY','PSIA','3000',keyword==='SWFN'?'0.6931211352348328':'3.208101749420166'])await expect(table).toContainText(text)}
 await check();await page.reload();await activateModel(page,'ECLIPSE演示模型');await check();expect(state.unexpectedRequests).toEqual([])
})
test('ECLIPSE 相渗 缺少完整三表不提供编辑',async({page})=>{
 const f=relpermBrowserFixture('SWFN');delete f.inspection.relpermContext;await installMockBackend(page,{eclipseInspection:f.inspection,eclipseHistory:[summary(f.baseline)],runs:[f.baseline]});await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型');const panel=page.getByRole('region',{name:'ECLIPSE 相渗调整',exact:true});await expect(panel).toContainText('唯一明确SAT区');await expect(panel.getByRole('button',{name:'创建相渗方案并计算',exact:true})).toHaveCount(0)
})
test('ECLIPSE 相渗 混单位不生成假差值',async({page})=>{
 const f=relpermBrowserFixture('SOF3');f.target.result.summary.series.find(s=>s.keyword==='WOPR').unit='SM3/DAY';await installMockBackend(page,{eclipseInspection:f.inspection,eclipseHistory:[summary(f.target),summary(f.baseline)],runs:[f.target,f.baseline]});await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型');const panel=page.getByRole('region',{name:'ECLIPSE 相渗方案验收',exact:true});await expect(panel).toContainText('单位不一致');await expect(panel.getByLabel('ECLIPSE 相渗井级响应',{exact:true})).toHaveCount(0)
})

async function installBatchBrowser(page, {complete=false, lose=false}={}) {
 const f=batchBrowserFixture(),records=complete?[f.summary]:[],submitted=[]
 const state=await installMockBackend(page,{eclipseInspection:f.inspection,eclipseHistory:[summary(f.baseline)],runs:[f.baseline,f.water.target,f.oil.target],eclipseBatches:records})
 await page.route('**/api/software-integration/**',async route=>{
  const path=new URL(route.request().url()).pathname,method=route.request().method()
  if(path.endsWith('/model-versions/201/eclipse-batches')&&method==='POST'){
   submitted.push(route.request().postDataJSON());if(!records.length)records.push(f.summary)
   if(lose&&submitted.length===1)return route.abort('failed')
   return route.fulfill({status:201,json:{code:201,data:f.batch}})
  }
  if(path.endsWith('/eclipse-batches/9801')&&method==='GET')return route.fulfill({json:response(f.batch)})
  return route.fallback()
 })
 return{f,submitted,state}
}
async function addBatchCase(page,panel,kind,name,value){
 await panel.locator('.el-select').first().locator('.el-select__placeholder').click()
 await page.getByRole('option',{name:kind,exact:true}).click()
 await panel.getByRole('textbox',{name:'批量方案名称',exact:true}).fill(name)
 await panel.getByRole('spinbutton',{name:'批量方案数值',exact:true}).fill(value)
 await panel.getByRole('button',{name:'加入方案清单',exact:true}).click()
}
async function draftBatch(page,panel){
 await panel.getByRole('textbox',{name:'批量任务名称',exact:true}).fill('相渗两项独立方案')
 await addBatchCase(page,panel,'水相 Krw 乘数','水相减半','0.5')
 await addBatchCase(page,panel,'油相两列相渗乘数','油相两列减半','0.5')
}
test('ECLIPSE 批量方案 同基线完整参数与实际商业结果对比',async({page})=>{
 const {f,submitted,state}=await installBatchBrowser(page);await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型')
 const panel=page.getByRole('region',{name:'ECLIPSE 批量方案',exact:true});await expect(panel.getByRole('button',{name:'运行全部方案',exact:true})).toBeDisabled()
 await draftBatch(page,panel);await expect(panel.getByLabel('待计算方案清单',{exact:true}).locator('tbody tr')).toHaveCount(2)
 await panel.getByRole('button',{name:'运行全部方案',exact:true}).click();await expect.poll(()=>submitted.length).toBe(1)
 expect(submitted[0]).toEqual({requestKey:expect.stringMatching(/^[a-f0-9-]{36}$/),name:f.batch.name,baselineRunId:f.baseline.id,cases:[{name:'水相减半',parameters:f.water.parameters},{name:'油相两列减半',parameters:f.oil.parameters}]})
 const table=panel.getByLabel('批量方案结果对比',{exact:true});await expect(table.locator('tbody tr')).toHaveCount(8)
 for(const text of ['0.69312114','787.29614','782.53864','3.2081017','STB/DAY','PSIA','3000.0000'])await expect(table).toContainText(text)
 expect(state.unexpectedRequests).toEqual([])
})
test('ECLIPSE 批量方案 重复参数不加入清单',async({page})=>{
 const {submitted,state}=await installBatchBrowser(page);await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型');const panel=page.getByRole('region',{name:'ECLIPSE 批量方案',exact:true})
 await addBatchCase(page,panel,'水相 Krw 乘数','水相1','0.5');await addBatchCase(page,panel,'水相 Krw 乘数','水相2','0.5')
 await expect(panel.getByRole('alert')).toContainText('重复');await expect(panel.getByLabel('待计算方案清单',{exact:true}).locator('tbody tr')).toHaveCount(1)
 await expect(panel.getByRole('button',{name:'运行全部方案',exact:true})).toBeDisabled();expect(submitted).toEqual([]);expect(state.unexpectedRequests).toEqual([])
})
test('ECLIPSE 批量方案 刷新恢复与子运行选择',async({page})=>{
 const {state}=await installBatchBrowser(page,{complete:true});await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型');const panel=page.getByRole('region',{name:'ECLIPSE 批量方案',exact:true})
 await expect(panel.getByLabel('批量方案结果对比',{exact:true}).locator('tbody tr')).toHaveCount(8);await page.reload();await activateModel(page,'ECLIPSE演示模型')
 await expect(panel).toContainText('全部完成');await panel.getByRole('button',{name:'查看运行与下载模型',exact:true}).first().click()
 await expect(page.getByRole('region',{name:'ECLIPSE 相渗方案验收',exact:true})).toContainText('SWFN');await expect(panel).toContainText('全部完成');expect(state.unexpectedRequests).toEqual([])
})
test('ECLIPSE 批量方案 响应丢失后刷新重试不换提交标识',async({page})=>{
 const {submitted}=await installBatchBrowser(page,{lose:true});await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型');const panel=page.getByRole('region',{name:'ECLIPSE 批量方案',exact:true})
 await draftBatch(page,panel);await panel.getByRole('button',{name:'运行全部方案',exact:true}).click();await expect(panel).toContainText('上次提交尚未确认')
 await page.reload();await activateModel(page,'ECLIPSE演示模型');await panel.getByRole('button',{name:'重试原批量提交',exact:true}).click()
 await expect.poll(()=>submitted.length).toBe(2);expect(submitted[1]).toEqual(submitted[0]);await expect(panel).not.toContainText('上次提交尚未确认')
 await expect(panel.getByLabel('批量方案结果对比',{exact:true}).locator('tbody tr')).toHaveCount(8)
})

async function gridBatchDraft(page) {
 const grid=page.locator('#eclipse-3d-result .eclipse-grid-3d-panel')
 await expect(grid).toHaveAttribute('data-render-ready','true')
 const editor=grid.getByLabel('ECLIPSE 区域物性真实重算方案',{exact:true})
 async function add(keyword,name,multiplier){
  await grid.locator('.grid-3d-controls .el-select').click()
  await page.getByRole('option',{name:new RegExp(`^${keyword} · ECLIPSE_PERMX_WATER.INIT`)}).click()
  await editor.getByRole('spinbutton',{name:'区域 I 终点',exact:true}).fill('3')
  await editor.getByRole('spinbutton',{name:'区域 J 终点',exact:true}).fill('2')
  await editor.getByRole('spinbutton',{name:'区域物性乘数',exact:true}).fill(String(multiplier))
  await editor.getByRole('textbox',{name:'批量区域方案名称',exact:true}).fill(name)
  await editor.getByRole('button',{name:'将区域加入批量清单',exact:true}).click()
 }
 return {editor,add}
}

test('ECLIPSE 批量网格 两个真实区域从三维网格入队且刷新保留结果',async({page})=>{
 const a=gridRegionBrowserFixture('PERMX'),b=gridRegionBrowserFixture('PORO');b.target.id=8905
 const cases=[{name:'渗透率减半',parameters:a.parameters},{name:'孔隙度八成',parameters:b.parameters}]
 const batch={id:9802,modelVersionId:201,baselineRunId:a.baseline.id,name:'网格区域两方案',status:'SUCCEEDED',baseline:a.baseline,members:[{...cases[0],run:a.target},{...cases[1],run:b.target}]}
 const saved={id:batch.id,modelVersionId:201,baselineRunId:a.baseline.id,name:batch.name,status:batch.status,caseCount:2}
 const records=[],submitted=[]
 const state=await installMockBackend(page,{eclipseInspection:{...inspection,pvtwContext:null},eclipseHistory:[summary(a.baseline)],runs:[a.baseline,a.target,b.target],rangePayloads:{...a.rangePayloads,...b.rangePayloads},eclipseBatches:records})
 await page.route('**/api/software-integration/**',async route=>{
  const path=new URL(route.request().url()).pathname,method=route.request().method()
  if(path.endsWith('/model-versions/201/eclipse-batches')&&method==='POST'){
   submitted.push(route.request().postDataJSON());records.push(saved);return route.fulfill({status:201,json:{code:201,data:batch}})
  }
  if(path.endsWith('/eclipse-batches/9802')&&method==='GET')return route.fulfill({json:response(batch)})
  return route.fallback()
 })
 await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型')
 const panel=page.getByRole('region',{name:'ECLIPSE 批量方案',exact:true}),grid=await gridBatchDraft(page)
 await grid.add('PERMX','渗透率减半',.5)
 await expect(panel.getByRole('button',{name:'运行全部方案',exact:true})).toBeDisabled()
 await grid.add('PORO','孔隙度八成',.8)
 await expect(panel.getByLabel('待计算方案清单',{exact:true}).locator('tbody tr')).toHaveCount(2)
 await expect(panel).toContainText('I 1–3 / J 1–2 / K 1–1；6 个单元')
 await panel.getByRole('textbox',{name:'批量任务名称',exact:true}).fill(batch.name)
 await panel.getByRole('button',{name:'运行全部方案',exact:true}).click()
 await expect.poll(()=>submitted.length).toBe(1);expect(submitted[0].cases).toEqual(cases)
 const table=panel.getByLabel('批量方案结果对比',{exact:true});await expect(table.locator('tbody tr')).toHaveCount(6)
 await expect(table).toContainText('BARSA');await expect(table).toContainText('SM3/DAY');expect(state.createPayloads).toHaveLength(0)
 await page.reload();await activateModel(page,'ECLIPSE演示模型');await expect(panel).toContainText('全部完成');await expect(table.locator('tbody tr')).toHaveCount(6)
 expect(state.unexpectedRequests).toEqual([])
})

test('ECLIPSE 批量网格 重复区域与孔隙度越界不加入也不提交',async({page})=>{
 const f=gridRegionBrowserFixture('PERMX')
 const state=await installMockBackend(page,{eclipseHistory:[summary(f.baseline)],runs:[f.baseline],rangePayloads:f.rangePayloads})
 await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型')
 const grid=await gridBatchDraft(page),panel=page.getByRole('region',{name:'ECLIPSE 批量方案',exact:true})
 await grid.add('PERMX','第一项',.5);await grid.add('PERMX','同参数',.5)
 await expect(page.locator('.el-message--error')).toContainText('重复')
 await grid.add('PORO','孔隙度超上限',2);await expect(grid.editor.getByRole('alert')).toContainText('孔隙度')
 await expect(panel.getByLabel('待计算方案清单',{exact:true}).locator('tbody tr')).toHaveCount(1)
 await expect(panel.getByRole('button',{name:'运行全部方案',exact:true})).toBeDisabled()
 expect(state.createPayloads).toHaveLength(0);expect(state.unexpectedRequests).toEqual([])
})

async function installHistoryBrowser(page,{existing=false,scenario=false,gas=false,reject=false}={}){
 const f=historyBrowserFixture(gas),records=existing?[f.dataset.metadata]:[],submitted=[]
 const inspection=(gas?gasPvtBrowserFixture():relpermBrowserFixture('SWFN')).inspection
 const history=scenario?[summary(f.target),summary(f.baseline)]:[summary(f.baseline)]
 const state=await installMockBackend(page,{eclipseInspection:inspection,eclipseHistory:history,runs:[f.baseline,f.target],eclipseHistoryDatasets:records})
 await page.route('**/api/software-integration/**',async route=>{
  const url=new URL(route.request().url()),path=url.pathname,method=route.request().method()
  if(path.endsWith('/model-versions/201/eclipse-history-datasets')&&method==='POST'){
   submitted.push(route.request().postDataJSON())
   if(reject)return route.fulfill({status:400,json:{code:400,message:'观察单位须与原生模型单位一致'}})
   if(!records.length)records.push(f.dataset.metadata)
   return route.fulfill({status:201,json:{code:201,data:f.dataset}})
  }
  if(path.endsWith('/eclipse-history-datasets/'+f.dataset.metadata.id)&&method==='GET')return route.fulfill({json:response(f.dataset)})
  if(path.endsWith('/eclipse-history-datasets/'+f.dataset.metadata.id+'/comparison')&&method==='GET')return route.fulfill({json:response(Number(url.searchParams.get('runId'))===f.baseline.id?f.comparison:f.scenario)})
  return route.fallback()
 })
 return{f,submitted,state}
}
test('ECLIPSE 历史校核 原CSV字节提交与可复核误差及缺失值',async({page})=>{
 const {f,submitted,state}=await installHistoryBrowser(page);await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型')
 const panel=page.getByRole('region',{name:'ECLIPSE 历史实测校核',exact:true})
 await panel.getByRole('textbox',{name:'实测数据名称',exact:true}).fill(f.dataset.metadata.name)
 await panel.getByLabel('历史实测 CSV 文件',{exact:true}).setInputFiles({name:f.dataset.metadata.sourceFile,mimeType:'text/csv',buffer:Buffer.from(f.dataset.csv,'utf8')})
 await panel.getByRole('button',{name:'导入并校核',exact:true}).click();await expect.poll(()=>submitted.length).toBe(1)
 expect(submitted[0]).toEqual({name:f.dataset.metadata.name,baselineRunId:f.baseline.id,sourceFile:f.dataset.metadata.sourceFile,csv:f.dataset.csv})
 const table=panel.getByLabel('历史拟合指标',{exact:true});await expect(table.locator('tbody tr')).toHaveCount(3)
 const oil=table.locator('tbody tr').filter({hasText:'WOPR'});for(const text of ['66.67%','2.5000000','2.5495098','-0.50000000'])await expect(oil).toContainText(text)
 const zero=table.locator('tbody tr').filter({hasText:'WWPR'});await expect(zero).toContainText('不可计算 · 0点')
 const points=panel.getByLabel('历史逐点校核',{exact:true});await expect(points.locator('tbody tr')).toHaveCount(3)
 const gap=points.locator('tbody tr').filter({hasText:'102.5'});await expect(gap).toContainText('没有同一报告时刻');await expect(gap).toContainText('不可计算')
 await expect(panel.getByRole('img',{name:'观察值与计算值曲线',exact:true})).toBeVisible()
 const downloaded=page.waitForEvent('download');await panel.getByRole('button',{name:'导出逐点校核 CSV',exact:true}).click();expect((await downloaded).suggestedFilename()).toContain('history-comparison.csv')
 expect(state.unexpectedRequests).toEqual([])
})
test('ECLIPSE 历史校核 方案刷新复用观察源与原文件下载',async({page})=>{
 const {f,state}=await installHistoryBrowser(page,{existing:true,scenario:true});await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型')
 const panel=page.getByRole('region',{name:'ECLIPSE 历史实测校核',exact:true});await expect(panel).toContainText('运行 #'+f.target.id)
 await expect(panel.getByLabel('历史拟合指标',{exact:true}).locator('tbody tr')).toHaveCount(3);await page.reload();await activateModel(page,'ECLIPSE演示模型');await expect(panel).toContainText('运行 #'+f.target.id)
 const downloaded=page.waitForEvent('download');await panel.getByRole('button',{name:'下载原实测 CSV',exact:true}).click();expect((await downloaded).suggestedFilename()).toBe(f.dataset.metadata.sourceFile)
 expect(state.unexpectedRequests).toEqual([])
})
test('ECLIPSE 历史校核 实际气体不同报告时刻保留55个缺失点',async({page})=>{
 const {state}=await installHistoryBrowser(page,{existing:true,scenario:true,gas:true});await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型')
 const panel=page.getByRole('region',{name:'ECLIPSE 历史实测校核',exact:true}),table=panel.getByLabel('历史拟合指标',{exact:true})
 await expect(table.locator('tbody tr')).toHaveCount(1);for(const text of ['WGIT','MSCF','61','9.84%'])await expect(table).toContainText(text)
 const points=panel.getByLabel('历史逐点校核',{exact:true});await expect(points.locator('tbody tr')).toHaveCount(61)
 await expect(points.locator('tbody tr').filter({hasText:'没有同一报告时刻'})).toHaveCount(55);expect(state.unexpectedRequests).toEqual([])
})
test('ECLIPSE 历史校核 单位拒绝不生成拟合表',async({page})=>{
 const {f,submitted}=await installHistoryBrowser(page,{reject:true});await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型')
 const panel=page.getByRole('region',{name:'ECLIPSE 历史实测校核',exact:true});await panel.getByRole('textbox',{name:'实测数据名称',exact:true}).fill('错误单位')
 await panel.getByLabel('历史实测 CSV 文件',{exact:true}).setInputFiles({name:'wrong-unit.csv',mimeType:'text/csv',buffer:Buffer.from(f.dataset.csv.replaceAll('STB/DAY','SM3/DAY'),'utf8')})
 await panel.getByRole('button',{name:'导入并校核',exact:true}).click();await expect(panel.getByRole('alert')).toContainText('单位一致');expect(submitted).toHaveLength(1)
 await expect(panel.getByLabel('历史拟合指标',{exact:true})).toHaveCount(0);await expect(panel.getByLabel('历史逐点校核',{exact:true})).toHaveCount(0)
})

async function installHistoryBatchBrowser(page,isolated=false){
 const f=historyBatchBrowserFixture(isolated),calls=[]
 const state=await installMockBackend(page,{eclipseHistory:[summary(f.runs[0])],runs:f.runs,eclipseBatches:[f.summary],eclipseHistoryDatasets:[f.dataset.metadata]})
 await page.route('**/api/software-integration/**',async route=>{
  const url=new URL(route.request().url()),path=url.pathname,method=route.request().method()
  if(path.endsWith('/eclipse-batches/'+f.batch.id)&&method==='GET')return route.fulfill({json:response(f.batch)})
  if(path.endsWith('/eclipse-history-datasets/'+f.dataset.metadata.id)&&method==='GET')return route.fulfill({json:response(f.dataset)})
  if(path.endsWith('/eclipse-history-datasets/'+f.dataset.metadata.id+'/comparison')&&method==='GET'){
   const id=Number(url.searchParams.get('runId'));calls.push(id)
   if(!f.evaluations[id])return route.fulfill({status:409,json:{code:409,message:'无完整成功结果'}})
   return route.fulfill({json:response(f.evaluations[id])})
  }
  return route.fallback()
 })
 return{f,state,calls}
}

test('ECLIPSE 批量历史 相同观察点逐指标排序并导出及查看实际方案',async({page})=>{
 const {f,state}=await installHistoryBatchBrowser(page);await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型')
 const panel=page.getByRole('region',{name:'ECLIPSE 批量历史校核',exact:true})
 await panel.getByRole('button',{name:'校核本批方案',exact:true}).click()
 const table=panel.getByLabel('批量方案历史误差',{exact:true});await expect(table.locator('tbody tr')).toHaveCount(3)
 await expect(table.locator('tbody tr').first()).toContainText('原基线');await expect(table.locator('tbody tr').first()).toContainText('2/3');await expect(table).toContainText('2.5495098');await expect(table).toContainText('2.8830032');await expect(table).toContainText('4.7263967')
 await panel.getByRole('combobox',{name:'批量历史比较指标',exact:true}).click();await page.getByRole('option',{name:'P1 · WWPR · STB/DAY',exact:true}).click()
 await expect(table.locator('tbody tr').first()).toContainText('SWFN');await expect(table.locator('tbody tr').first()).toContainText('0.69312114')
 const downloadPromise=page.waitForEvent('download');await panel.getByRole('button',{name:'下载本指标批量校核 CSV',exact:true}).click()
 const download=await downloadPromise,stream=await download.createReadStream(),buffers=[];for await(const chunk of stream)buffers.push(chunk)
 const csv=Buffer.concat(buffers).toString('utf8');expect(csv).toContain(f.dataset.metadata.sourceSha256);expect(csv).toContain('SWFN');expect(csv).toContain('WWPR');expect(csv).not.toContain('NaN')
 await page.reload();await activateModel(page,'ECLIPSE演示模型');await panel.getByRole('button',{name:'校核本批方案',exact:true}).click();await expect(table.locator('tbody tr')).toHaveCount(3)
 await table.getByRole('button',{name:'查看方案逐点结果',exact:true}).nth(1).click()
 await expect(page.getByRole('region',{name:'ECLIPSE 历史实测校核',exact:true})).toContainText('运行 #9081')
 expect(state.createPayloads).toHaveLength(0);expect(state.unexpectedRequests).toEqual([])
})

test('ECLIPSE 批量历史 实际失败运行不请求拟合不参与排名',async({page})=>{
 const {state,calls}=await installHistoryBatchBrowser(page,true);await openWorkspace(page);await activateModel(page,'ECLIPSE演示模型')
 const panel=page.getByRole('region',{name:'ECLIPSE 批量历史校核',exact:true});await panel.getByRole('button',{name:'校核本批方案',exact:true}).click()
 const table=panel.getByLabel('批量方案历史误差',{exact:true});await expect(table.locator('tbody tr')).toHaveCount(3)
 const failed=table.locator('tbody tr').filter({hasText:'FAILED'});await expect(failed).toContainText('不可计算');await expect(failed).toContainText('不排序');await expect(failed).toContainText('no longer matches')
 expect(calls).not.toContain(9081);expect(calls).toContain(9080);expect(calls).toContain(9082);expect(state.createPayloads).toHaveLength(0);expect(state.unexpectedRequests).toEqual([])
})
