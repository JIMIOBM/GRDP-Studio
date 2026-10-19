import { expect, test } from '@playwright/test'
import { readFileSync } from 'node:fs'
const base = process.env.GRDP_THERMAL_ROCK_API?.replace(/\/$/, '')
let source
test.beforeAll(async ({ request }) => {
  if (!base) return
  const proof = JSON.parse(readFileSync(process.env.GRDP_THERMAL_ROCK_EVIDENCE + '/acceptance-summary.json', 'utf8')); expect(proof.apiVerified).toBe(true)
  const get = async path => { const r = await request.get(base + '/software-integration' + path); expect(r.status()).toBe(200); const b = await r.json(); expect(b.code).toBe(200); return b.data }
  const cases = {}
  for (const key of ['heat-capacity', 'conductivity']) cases[key] = await get('/runs/' + proof.cases['output-' + key].runId)
  const baseline = await get('/runs/' + proof.cases['output-heat-capacity'].baselineRunId), project = await get('/projects/' + baseline.projectId)
  source = { baseline, cases, inspection: project.models.flatMap(m => m.versions).find(v => v.id === baseline.modelVersionId).inspection }
})
for (const [label, key] of [['热容方案', 'heat-capacity'], ['导热率方案', 'conductivity']]) test(`岩石热物性实际空间: ${label}真实三维与单格历史`, async ({ page, request }, info) => {
  test.skip(!base, 'Explicit local API opt-in required')
  const errors = [], writes = [], ids = [source.baseline.id, ...Object.values(source.cases).map(r => r.id)]
  page.on('pageerror', e => errors.push(e.message))
  await page.route('**/fixtures/thermal-rock-spatial-source.json', r => r.fulfill({ json: source }))
  await page.route('**/api/software-integration/**', async route => {
    const u = new URL(route.request().url()), match = u.pathname.match(/^\/api\/software-integration\/runs\/(\d+)\/artifacts\/\d+\/range$/)
    if (route.request().method() !== 'GET' || !match || !ids.includes(Number(match[1]))) { writes.push(u.pathname); await route.abort(); return }
    const r = await request.get(base + u.pathname.slice(4) + u.search); expect(r.status()).toBe(206); await route.fulfill({ response: r })
  })
  await page.setViewportSize({ width: 1600, height: 1080 }); await page.goto('/tests/e2e/fixtures/eclipse-thermal-rock-spatial.html')
  await page.getByRole('button', { name: label, exact: true }).click()
  const panel = page.getByRole('region', { name: 'E300热采空间差值分析', exact: true })
  await expect(panel).toHaveAttribute('data-run-id', String(source.cases[key].id)); await expect(panel).toHaveAttribute('data-ready', 'true', { timeout: 60000 })
  await panel.locator('.el-select').click(); await page.getByRole('option', { name: 'TEMP · 报告 10', exact: true }).click(); await expect(panel).toHaveAttribute('data-ready', 'true')
  await panel.getByRole('button', { name: '查看三维空间差值', exact: true }).click()
  const viewer = page.getByRole('region', { name: 'ECLIPSE真实网格三维方案对比', exact: true })
  await expect(viewer).toHaveAttribute('data-ready', 'true', { timeout: 60000 }); await expect(viewer).toHaveAttribute('data-visible-cells', '100')
  await viewer.locator('.el-select').click(); await page.getByRole('option', { name: 'K = 2', exact: true }).click(); await expect(viewer).toHaveAttribute('data-visible-cells', '25')
  await viewer.getByRole('spinbutton', { name: '三维差值定位K', exact: true }).fill('2'); await viewer.getByRole('button', { name: '定位并查看单格历史', exact: true }).click()
  const cell = page.getByRole('region', { name: 'ECLIPSE单格实际历史曲线', exact: true }); await expect(cell).toHaveAttribute('data-ready', 'true', { timeout: 60000 }); await expect(cell).toHaveAttribute('data-cell-index', '45'); await expect(cell).toContainText('I1 · J1 · K2')
  await viewer.screenshot({ path: info.outputPath(`thermal-rock-${key}-real-3d.png`) }); await cell.screenshot({ path: info.outputPath(`thermal-rock-${key}-cell-history.png`) })
  const wait = page.waitForEvent('download'); await viewer.getByRole('button', { name: '导出三维差值PNG', exact: true }).click(); const file = await wait; await file.saveAs(info.outputPath(file.suggestedFilename()))
  expect(errors).toEqual([]); expect(writes).toEqual([])
})
