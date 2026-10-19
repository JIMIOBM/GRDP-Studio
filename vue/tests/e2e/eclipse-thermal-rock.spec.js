import { expect, test } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { thermalRockFixture } from '../fixtures/eclipseThermalRock.js'
let f = thermalRockFixture('output')
const actualApi = process.env.GRDP_THERMAL_ROCK_API?.replace(/\/$/, '')
test.beforeAll(async ({ request }) => {
  if (!actualApi) return
  const receipt = JSON.parse(readFileSync(process.env.GRDP_THERMAL_ROCK_EVIDENCE + '/acceptance-summary.json', 'utf8')); expect(receipt.apiVerified).toBe(true)
  const get = async path => { const r = await request.get(actualApi + '/software-integration' + path); expect(r.status()).toBe(200); const b = await r.json(); expect(b.code).toBe(200); return b.data }
  const cases = {}
  for (const name of ['heat-capacity', 'conductivity']) cases[name] = await get('/runs/' + receipt.cases['output-' + name].runId)
  const baseline = await get('/runs/' + receipt.cases['output-heat-capacity'].baselineRunId), project = await get('/projects/' + baseline.projectId)
  const inspection = project.models.flatMap(m => m.versions).find(v => v.id === baseline.modelVersionId).inspection
  f = { baseline, inspection, cases }
})
async function open(page, mutate = () => {}) {
  const source = structuredClone(f); mutate(source); const errors = []
  page.on('pageerror', e => errors.push(e.message))
  await page.route('**/fixtures/thermal-rock-source.json', r => r.fulfill({ json: source }))
  await page.setViewportSize({ width: 1560, height: 1080 }); await page.goto('/tests/e2e/fixtures/eclipse-thermal-rock.html')
  return { panel: page.getByRole('region', { name: 'E300岩石热物性与温度响应', exact: true }), errors }
}
for (const [label, name, target] of [['岩石体积热容', 'heat-capacity', '45'], ['岩石与流体综合导热率', 'conductivity', '12']]) test(`岩石热物性: ${label}输入图与精确提交`, async ({ page }, info) => {
  const { panel, errors } = await open(page)
  await panel.getByRole('button', { name: new RegExp('^' + label) }).click(); await panel.getByRole('spinbutton', { name: '岩石热物性新值', exact: true }).fill(target); await panel.getByRole('spinbutton').blur()
  await expect(panel.locator('canvas')).toHaveCount(1)
  const option = await panel.getByRole('img', { name: '岩石热物性原值与目标值对比', exact: true }).evaluate(el => globalThis.thermalRockChart(el))
  expect(option.series[0].data.map(d => d.value)).toEqual([f.cases[name].parameters.originalValue, Number(target)])
  await expect(panel).toContainText('100'); await expect(panel).toContainText('非活动格原零值覆盖保留')
  await panel.getByRole('button', { name: '修改热物性并真实计算', exact: true }).click(); expect(await page.evaluate(() => globalThis.thermalRockEmitted)).toEqual([f.cases[name].parameters])
  await panel.screenshot({ path: info.outputPath(`thermal-rock-editor-${name}.png`) }); expect(errors).toEqual([])
})
for (const [label, name] of [['热容方案', 'heat-capacity'], ['导热率方案', 'conductivity']]) test(`岩石热物性: ${label}实际温度与差值导出`, async ({ page }, info) => {
  const { panel, errors } = await open(page); await page.getByRole('button', { name: label, exact: true }).click(); await expect(panel.locator('canvas')).toHaveCount(3)
  const option = await panel.getByRole('img', { name: '热物性方案与基线实际曲线', exact: true }).evaluate(el => globalThis.thermalRockChart(el))
  const s = f.cases[name].result.summary.series.find(s => s.keyword === 'WTEMP')
  expect(option.series[1].data).toEqual(s.points.map(p => [p.timeDays, p.value]))
  const before = f.baseline.result.summary.series.find(b => b.vectorIndex === s.vectorIndex), map = new Map(before.points.map(p => [p.timeDays, p.value]))
  const delta = await panel.getByRole('img', { name: '热物性共同实际时刻差值', exact: true }).evaluate(el => globalThis.thermalRockChart(el))
  expect(delta.series[0].data).toEqual(s.points.filter(p => map.has(p.timeDays)).map(p => [p.timeDays, p.value - map.get(p.timeDays)]))
  await expect(panel).toContainText(name === 'heat-capacity' ? '15,137.508789' : '14,966.296875')
  for (const [button, extension] of [['导出热物性响应CSV', 'csv'], ['导出热物性对比图', 'png']]) {
    const wait = page.waitForEvent('download'); await panel.getByRole('button', { name: button, exact: true }).click(); const download = await wait, dest = info.outputPath(download.suggestedFilename()); await download.saveAs(dest)
    if (extension === 'png') expect(readFileSync(dest).subarray(0, 8).toString('hex')).toBe('89504e470d0a1a0a'); else expect(readFileSync(dest, 'utf8')).toContain('vectorIndex')
  }
  await panel.screenshot({ path: info.outputPath(`thermal-rock-${name}.png`) }); expect(errors).toEqual([])
})
test('岩石热物性: 热容零值拒绝，导热率零值保留且切换清空输入', async ({ page }) => {
  const { panel } = await open(page); await panel.getByRole('spinbutton').fill('0'); await panel.getByRole('button', { name: '修改热物性并真实计算', exact: true }).click()
  await expect(panel).toContainText('岩石热容须大于0'); expect(await page.evaluate(() => globalThis.thermalRockEmitted)).toEqual([])
  await panel.getByRole('button', { name: /^岩石与流体综合导热率/ }).click(); await expect(panel.getByRole('spinbutton')).toHaveValue('')
  await panel.getByRole('spinbutton').fill('0'); await panel.getByRole('button', { name: '修改热物性并真实计算', exact: true }).click(); expect((await page.evaluate(() => globalThis.thermalRockEmitted))[0].targetValue).toBe(0)
  await page.getByRole('button', { name: '热容方案', exact: true }).click(); await page.getByRole('button', { name: '编辑原模型', exact: true }).click(); await expect(panel.getByRole('spinbutton')).toHaveValue('')
})
test('岩石热物性: 不同版本基线不显示响应', async ({ page }) => {
  const { panel } = await open(page, source => { source.baseline.modelVersionId++ }); await page.getByRole('button', { name: '热容方案', exact: true }).click()
  await expect(panel).toContainText('来源或原始列不一致时不展示响应'); await expect(panel.locator('canvas')).toHaveCount(1)
})
test('岩石热物性: 窄屏与禁用操作', async ({ page }, info) => {
  const { panel, errors } = await open(page); await page.setViewportSize({ width: 430, height: 900 }); await panel.getByRole('spinbutton').fill('45')
  await page.getByRole('button', { name: '禁用操作', exact: true }).click(); await expect(panel.getByRole('button', { name: '修改热物性并真实计算', exact: true })).toBeDisabled()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 2)).toBe(true)
  await panel.screenshot({ path: info.outputPath('thermal-rock-narrow.png') }); expect(errors).toEqual([])
})
