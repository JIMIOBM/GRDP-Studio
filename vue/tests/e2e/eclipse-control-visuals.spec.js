import { expect, test } from '@playwright/test'
import { readFileSync } from 'node:fs'
const base = process.env.GRDP_INPUT_CURVE_API?.replace(/\/$/, '')
const cases = { oil: 388, oilLimited: 389, water: 1515, waterLimited: 1516, groupTarget: 367, groupGuide: 371, groupEfficiency: 375 }
let source = {}
test.beforeAll(async ({ request }) => {
  if (!base) return
  const get = async path => { const r = await request.get(base + '/software-integration' + path); expect(r.status()).toBe(200); const b = await r.json(); expect(b.code).toBe(200); return b.data }
  for (const [key, id] of Object.entries(cases)) {
    const run = await get(`/runs/${id}`), baseline = await get(`/runs/${run.parameters.baselineRunId}`), project = await get(`/projects/${run.projectId}`)
    const inspection = project.models.flatMap(m => m.versions).find(v => v.id === run.modelVersionId).inspection
    source[key] = { run, baseline, inspection }
  }
})
async function open(page, key, label) {
  const errors = [], requests = []
  page.on('pageerror', e => errors.push(e.message))
  await page.route('**/fixtures/control-visuals-source.json', route => route.fulfill({ json: source }))
  await page.route('**/api/software-integration/**', route => { requests.push(route.request().url()); return route.abort() })
  await page.setViewportSize({ width: 1600, height: 1080 }); await page.goto('/tests/e2e/fixtures/eclipse-control-visuals.html')
  await page.getByRole('button', { name: key, exact: true }).click()
  const panel = page.getByRole('region', { name: label, exact: true }); await expect(panel).toBeVisible()
  await expect.poll(() => panel.locator('canvas').count()).toBeGreaterThan(0)
  return { panel, errors, requests }
}
const raw = (run, keyword, name) => run.result.summary.series.find(s => s.keyword === keyword && s.objectName === name).points.map(p => [p.timeDays, p.value])
for (const key of ['oil', 'oilLimited', 'water', 'waterLimited']) test(`真实井控 ${key} 时间线、边界、压力限制与原生报告`, async ({ page }, info) => {
  test.skip(!base, 'Explicit local API opt-in required')
  const { panel, errors, requests } = await open(page, key, 'ECLIPSE井控事件可视化'), item = source[key], well = await panel.getAttribute('data-well'), injection = key.startsWith('water')
  const charts = await panel.locator('.control-charts>div').evaluateAll(els => els.map(el => globalThis.readControlChart(el)))
  expect(charts[0].series[1].data).toEqual(raw(item.run, injection ? 'WWIR' : 'WOPR', well).filter(p => p[0] > 0))
  expect(charts[1].series[0].data).toEqual(raw(item.run, 'WBHP', well).filter(p => p[0] > 0))
  const events = panel.getByRole('button', { name: `${well} 第365天控制事件`, exact: true }); await events.click(); await expect(panel.getByLabel('选中井控事件')).toContainText('(365, 730]')
  const targets = charts[0].series[2].data
  expect(targets.find(p => p[0] === 365)[1]).toBe(injection ? 1 : 500)
  expect(targets.find(p => p[0] === 730)[1]).toBe(item.run.parameters.edits.find(e => e.well === well && e.timeDays === 365)[injection ? 'targetInjectionRate' : 'targetOilRate'])
  await panel.screenshot({ path: info.outputPath(`control-${key}.png`) })
  if (key === 'oil') {
    const wait = page.waitForEvent('download'); await panel.getByRole('button', { name: '导出井控响应PNG', exact: true }).click()
    const download = await wait, path = info.outputPath(download.suggestedFilename()); await download.saveAs(path)
    expect(readFileSync(path).readUInt32BE(20)).toBeGreaterThan(1000)
  }
  expect(errors).toEqual([]); expect(requests).toEqual([])
})
for (const key of ['groupTarget', 'groupGuide', 'groupEfficiency']) test(`真实井组 ${key} 拓扑、贡献、累计与约束`, async ({ page }, info) => {
  test.skip(!base, 'Explicit local API opt-in required')
  const { panel, errors } = await open(page, key, 'ECLIPSE井组分配可视化'), item = source[key], time = Number(await panel.getAttribute('data-time-days'))
  const options = await panel.locator('.group-tree').evaluate(el => globalThis.readControlChart(el)), tree = options.series[0].data[0]
  const value = (keyword, name) => raw(item.run, keyword, name).find(p => p[0] === time)[1]
  expect(tree.value).toBe(value('GOPR', tree.name)); expect(tree.details.total).toBe(value('GOPT', tree.name))
  for (const child of tree.children) { expect(child.value).toBe(value('GOPR', child.name)); expect(child.details.contribution).toBe(child.value * child.details.efficiency); for (const well of child.children) expect(well.value).toBe(value('WOPR', well.name)) }
  expect(Math.abs(tree.children.reduce((s, child) => s + child.details.contribution, 0) - tree.value)).toBeLessThan(.01)
  if (key === 'groupEfficiency') { expect(tree.children[0].details.efficiency).toBe(.5); expect(tree.children[0].value).not.toBe(tree.children[0].details.contribution) }
  await panel.locator('.el-select').filter({ has: page.getByRole('combobox', { name: '井组分配对象', exact: true }) }).click(); await page.getByRole('option', { name: '井 PRODUCR2', exact: true }).click(); await expect(panel.getByLabel('选中井组对象')).toContainText('BHP')
  await expect(page.getByRole('option', { name: '井 PRODUCR2', exact: true })).toBeHidden()
  await panel.screenshot({ path: info.outputPath(`allocation-${key}.png`) })
  const wait = page.waitForEvent('download'); await panel.getByRole('button', { name: '导出井组结构PNG', exact: true }).click(); const download = await wait; await download.saveAs(info.outputPath(download.suggestedFilename()))
  expect(errors).toEqual([])
})
