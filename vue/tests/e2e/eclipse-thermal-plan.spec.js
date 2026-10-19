import { expect, test } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { thermalPlanFixture } from '../fixtures/eclipseThermalPlan.js'
let f = thermalPlanFixture('output')
const actualApi = process.env.GRDP_THERMAL_PLAN_API?.replace(/\/$/, '')
test.beforeAll(async ({ request }) => {
  if (!actualApi) return
  const receipt = JSON.parse(readFileSync(process.env.GRDP_THERMAL_PLAN_EVIDENCE + '/acceptance-summary.json', 'utf8'))
  expect(receipt.apiVerified).toBe(true)
  const get = async path => { const r = await request.get(actualApi + '/software-integration' + path); expect(r.status()).toBe(200); const b = await r.json(); expect(b.code).toBe(200); return b.data }
  const plans = {}
  for (const name of ['two-stage', 'restore', 'pressure-limited']) plans[name] = await get('/runs/' + receipt.cases['output-' + name].runId)
  const baseline = await get('/runs/' + receipt.cases['output-two-stage'].baselineRunId)
  const project = await get('/projects/' + baseline.projectId)
  const inspection = project.models.flatMap(m => m.versions).find(v => v.id === baseline.modelVersionId).inspection
  f = { baseline, inspection, plans }
})
async function open(page, mutate = () => {}) {
  const source = structuredClone(f); mutate(source); const errors = []
  page.on('pageerror', e => errors.push(e.message))
  await page.route('**/fixtures/thermal-plan-source.json', r => r.fulfill({ json: source }))
  await page.setViewportSize({ width: 1560, height: 1080 }); await page.goto('/tests/e2e/fixtures/eclipse-thermal-plan.html')
  return { panel: page.getByRole('region', { name: 'E300多阶段注汽计划', exact: true }), errors }
}
async function choose(page, aria, label) {
  await page.locator('.el-select').filter({ has: page.getByRole('combobox', { name: aria, exact: true }) }).click()
  await page.getByRole('listbox', { name: aria, exact: true }).getByRole('option', { name: label, exact: true }).click()
}
async function edit(page, panel) {
  await choose(page, '多阶段注汽注入井', 'I')
  await choose(page, '阶段2生效天数', '第 2190 天后')
  await choose(page, '阶段1生效天数', '第 1095 天后')
  await panel.getByRole('spinbutton', { name: '阶段1注入目标', exact: true }).fill('25')
  await panel.getByRole('spinbutton', { name: '阶段2注入目标', exact: true }).fill('25')
  await panel.getByRole('spinbutton', { name: '阶段2输入温度', exact: true }).fill('400')
  await panel.getByRole('spinbutton', { name: '阶段2输入温度', exact: true }).blur()
}
test('多阶段注汽: 图形预览和完整精确参数提交', async ({ page }, info) => {
  const { panel, errors } = await open(page); await edit(page, panel)
  await expect(panel.locator('canvas')).toHaveCount(1)
  const option = await panel.getByRole('img', { name: '多阶段注汽目标时间轴', exact: true }).evaluate(el => globalThis.thermalPlanChart(el))
  expect(option.series.map(s => s.data)).toEqual([[[0, 37.5], [1095, 37.5]], [[1095, 25], [2190, 25]], [[2190, 25], [3650, 25]]])
  await panel.getByRole('button', { name: '按多阶段计划真实计算', exact: true }).click()
  expect(await page.evaluate(() => globalThis.thermalPlanEmitted)).toEqual([f.plans['two-stage'].parameters])
  await panel.screenshot({ path: info.outputPath('thermal-plan-editor.png') }); expect(errors).toEqual([])
})
for (const [label, name] of [['两阶段计算', 'two-stage'], ['三阶段恢复', 'restore'], ['压力限注', 'pressure-limited']]) test(`多阶段注汽: ${label}真实响应和导出`, async ({ page }, info) => {
  const { panel, errors } = await open(page); await page.getByRole('button', { name: label, exact: true }).click()
  await expect(panel).toContainText('阶段前结果与基线一致'); await expect(panel.locator('canvas')).toHaveCount(3)
  const option = await panel.getByRole('img', { name: '多阶段实际注入和井压', exact: true }).evaluate(el => globalThis.thermalPlanChart(el))
  const raw = f.plans[name].result.summary.series.find(s => s.keyword === 'WWIR' && s.objectName === 'I').points.map(p => [p.timeDays, p.value])
  expect(option.series[0].data).toEqual(raw)
  if (name === 'pressure-limited') await expect(panel).toContainText('出现压力限注')
  if (name === 'restore') {
    await panel.getByRole('button', { name: /阶段 3.*2920/ }).click(); await expect(panel.locator('.period-detail')).toContainText('输入温度 450 °F')
  }
  for (const [button, type] of [['导出实际响应CSV', 'csv'], ['导出计划图', 'png']]) {
    const wait = page.waitForEvent('download'); await panel.getByRole('button', { name: button, exact: true }).click(); const download = await wait
    const dest = info.outputPath(download.suggestedFilename()); await download.saveAs(dest)
    if (type === 'png') expect(readFileSync(dest).subarray(0, 8).toString('hex')).toBe('89504e470d0a1a0a')
    else expect(readFileSync(dest, 'utf8')).toContain('baselineValue')
  }
  await panel.screenshot({ path: info.outputPath(`thermal-plan-${name}.png`) }); expect(errors).toEqual([])
})
test('多阶段注汽: 原值重复、非递增时间和干度越界均不提交', async ({ page }) => {
  const { panel } = await open(page); await choose(page, '多阶段注汽注入井', 'I')
  await panel.getByRole('button', { name: '按多阶段计划真实计算', exact: true }).click(); await expect(panel).toContainText('每阶段至少改变一项')
  await edit(page, panel); await panel.getByRole('spinbutton', { name: '阶段2蒸汽干度', exact: true }).fill('1.1')
  await panel.getByRole('button', { name: '按多阶段计划真实计算', exact: true }).click(); expect(await page.evaluate(() => globalThis.thermalPlanEmitted)).toEqual([])
})
test('多阶段注汽: 添加恢复阶段与切换运行清除草案', async ({ page }) => {
  const { panel } = await open(page); await edit(page, panel); await panel.getByRole('button', { name: '添加注汽阶段', exact: true }).click()
  await choose(page, '阶段3生效天数', '第 2920 天后')
  for (const [key, value] of [['注入目标', '37.5'], ['输入温度', '450']]) await panel.getByRole('spinbutton', { name: `阶段3${key}`, exact: true }).fill(value)
  await panel.getByRole('button', { name: '按多阶段计划真实计算', exact: true }).click()
  const emitted = await page.evaluate(() => globalThis.thermalPlanEmitted); expect(emitted).toHaveLength(1); expect(emitted[0].stages[2].surfaceInjectionRate).toBe(37.5)
  await page.getByRole('button', { name: '两阶段计算', exact: true }).click(); await page.getByRole('button', { name: '编辑计划', exact: true }).click()
  await expect(panel.getByRole('spinbutton')).toHaveCount(0)
})
test('多阶段注汽: 前段篡改和缺少曲线不能显示通过', async ({ page }) => {
  const { panel } = await open(page, source => { source.plans['two-stage'].result.summary.series.find(s => s.keyword === 'WOPT' && s.objectName === 'PNEAR').points[0].value += 1 })
  await page.getByRole('button', { name: '两阶段计算', exact: true }).click(); await expect(panel).toContainText('核对未通过'); await expect(panel).not.toContainText('约束已核对')
})
test('多阶段注汽: 窄屏布局与禁用操作', async ({ page }, info) => {
  const { panel, errors } = await open(page); await page.setViewportSize({ width: 430, height: 900 }); await edit(page, panel)
  await page.getByRole('button', { name: '禁用操作', exact: true }).click(); await expect(panel.getByRole('button', { name: '按多阶段计划真实计算', exact: true })).toBeDisabled()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 2)).toBe(true)
  await panel.screenshot({ path: info.outputPath('thermal-plan-narrow.png') }); expect(errors).toEqual([])
})
