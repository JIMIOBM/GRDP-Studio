import { expect, test } from '@playwright/test'

// Explicit opt-in only: read existing native data, never create a run or fabricate identity.
test('已完成官方运行的真实 EGRID 可在生产三维组件中渲染', async ({ page, request }, testInfo) => {
  test.skip(!process.env.GRDP_NATIVE_GRID_BASE_URL || !process.env.GRDP_NATIVE_GRID_RUN_ID,
    'Set GRDP_NATIVE_GRID_BASE_URL and GRDP_NATIVE_GRID_RUN_ID to read an existing successful official run')
  const base = process.env.GRDP_NATIVE_GRID_BASE_URL.replace(/\/$/, '')
  const runId = Number(process.env.GRDP_NATIVE_GRID_RUN_ID)
  expect(Number.isSafeInteger(runId) && runId > 0).toBeTruthy()
  const response = await request.get(`${base}/software-integration/runs/${runId}`)
  expect(response.status()).toBe(200)
  const envelope = await response.json()
  expect(envelope.code).toBe(200)
  const run = envelope.data
  expect(run.status).toBe('SUCCEEDED'); expect(run.resultContract).toBe('VALID_FULL')
  expect(run.result.grid.activeCells).toBeGreaterThan(0)
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  await page.route('**/fixtures/native-run.json', route => route.fulfill({ json: run }))
  let rangeReads = 0
  await page.route('**/api/software-integration/**', async route => {
    const url = new URL(route.request().url())
    // Deny everything except this run's binary reads; no mocked auth, uploads or calculation.
    if (route.request().method() !== 'GET' || !new RegExp(`^/api/software-integration/runs/${runId}/artifacts/[0-9]+/range$`).test(url.pathname)) {
      await route.abort(); return
    }
    const binary = await request.get(`${base}${url.pathname.slice(4)}${url.search}`)
    expect(binary.status()).toBe(206)
    rangeReads++
    await route.fulfill({ status: 206, contentType: 'application/octet-stream', body: await binary.body() })
  })
  await page.goto('/tests/e2e/fixtures/eclipse-native-grid.html')
  const panel = page.getByRole('region', { name: 'ECLIPSE 三维网格与井定位', exact: true })
  await expect(panel).toHaveAttribute('data-render-ready', 'true', { timeout: 60000 })
  await expect(panel).toContainText(`真实几何已加载 ${run.result.grid.activeCells} 个活动单元`)
  await expect(panel.locator('canvas')).toBeVisible()
  await expect(panel.locator('.el-alert--warning')).toHaveCount(0)
  expect(rangeReads).toBeGreaterThan(2); expect(errors).toEqual([])
  const canvas = panel.locator('canvas')
  const beforeRotation = await canvas.screenshot()
  const box = await canvas.boundingBox()
  expect(box).not.toBeNull()
  await page.mouse.move(box.x + box.width * 0.5, box.y + box.height * 0.5)
  await page.mouse.down()
  await page.mouse.move(box.x + box.width * 0.65, box.y + box.height * 0.6, { steps: 12 })
  await page.mouse.up()
  expect((await canvas.screenshot()).equals(beforeRotation)).toBe(false)
  await panel.getByRole('button', { name: '重置视角', exact: true }).click()
  await expect(panel).toHaveAttribute('data-render-ready', 'true')
  await page.screenshot({ path: testInfo.outputPath('official-grid.png'), fullPage: true })
  await testInfo.attach('official-grid', { path: testInfo.outputPath('official-grid.png'), contentType: 'image/png' })
})
