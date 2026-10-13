import { expect, test } from '@playwright/test'

const environment = (configured = false) => ({
  host: 'execution-host', configured, configuredSimulators: configured ? ['pipesim'] : [],
  paths: { pipesimHome: 'D:\\SLB\\PIPESIM2022.1', pythonPath: 'D:\\Python39\\python.exe', pipesimPtkPath: 'D:\\SLB\\PIPESIM2022.1\\PythonToolkitModules.zip', eclrunPath: '' },
  candidates: { pipesimHome: ['D:\\SLB\\PIPESIM2022.1'], pythonPath: ['D:\\Python39\\python.exe'], pipesimPtkPath: ['D:\\SLB\\PIPESIM2022.1\\PythonToolkitModules.zip'], eclrunPath: [] },
  pipesim: { status: 'READY', message: 'Toolkit 可导入' }, eclipse: { status: 'MISSING', message: '未找到启动器' },
  licenseNotice: '许可证及计算成功需实际运行验证。'
})
async function setup(page, options = {}) {
  let snapshot = options.snapshot || environment()
  const saved = []
  let capabilityReads = 0
  await page.addInitScript(() => localStorage.setItem('account', JSON.stringify({ token: 'local-test' })))
  await page.route('**/api/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    if (path.endsWith('/software-integration/environment')) {
      if (options.offline) return route.fulfill({ status: 503, json: { code: 503, msg: '请先启动 Worker' } })
      if (request.method() === 'PUT') {
        saved.push(request.postDataJSON())
        expect(request.headers()['x-grdp-environment']).toBe('1')
        if (options.failSave) return route.fulfill({ status: 400, json: { code: 400, msg: 'Toolkit 导入失败，原配置未更改' } })
        snapshot = { ...snapshot, configured: true, configuredSimulators: [saved.at(-1).simulator] }
      }
      return route.fulfill({ json: { code: 200, data: snapshot } })
    }
    if (path.endsWith('/capabilities')) {
      capabilityReads++
      return route.fulfill({ json: { code: 200, data: { workerOnline: true, pipesimWell: { status: 'AVAILABLE' } } } })
    }
    return route.fulfill({ json: { code: 200, data: [] } })
  })
  await page.goto('/ipr?workspace=software-integration')
  return { saved, capabilityReads: () => capabilityReads }
}

test('首次向导允许只配置 PIPESIM，立即刷新能力，重载不强迫配置 ECLIPSE', async ({ page }) => {
  const state = await setup(page)
  const dialog = page.getByRole('dialog', { name: '软件集成 · 环境发现与配置' })
  await expect(dialog).toBeVisible()
  await expect(dialog).toContainText('execution-host')
  await expect(dialog).toContainText('未找到启动器')
  await expect(dialog).toContainText('许可证')
  await expect(dialog.getByRole('button', { name: '稍后配置 / 继续浏览' })).toBeInViewport()
  await page.screenshot({ path: 'C:/Users/Violet/AppData/Local/Temp/grdp-environment-wizard-20261013.png', fullPage: true })
  await dialog.getByRole('button', { name: '验证并保存 PIPESIM', exact: true }).click()
  await expect.poll(() => state.saved.length).toBe(1)
  expect(state.saved[0].simulator).toBe('pipesim')
  expect(state.saved[0].paths.pythonPath).toBe('D:\\Python39\\python.exe')
  await expect.poll(state.capabilityReads).toBeGreaterThan(1)
  await dialog.getByRole('button', { name: '稍后配置 / 继续浏览' }).click()
  await page.reload()
  await expect(page.getByText('计算主机：execution-host', { exact: true })).toBeVisible()
  await expect(dialog).not.toBeVisible()
  await page.getByRole('button', { name: '环境设置', exact: true }).click()
  await expect(dialog).toBeVisible()
})

test('已保存的软件失效时重新提示，即使另一软件可用', async ({ page }) => {
  const snapshot = environment(true)
  snapshot.pipesim = { status: 'FAILED', message: 'Toolkit 导入失败' }
  snapshot.eclipse = { status: 'READY', message: 'ECLIPSE 可用' }
  await setup(page, { snapshot })
  await expect(page.getByRole('dialog')).toBeVisible()
  await expect(page.getByRole('dialog')).toContainText('Toolkit 导入失败')
})

test('验证失败保留输入并显示原因，不刷新计算能力', async ({ page }) => {
  const state = await setup(page, { failSave: true })
  await page.getByRole('button', { name: '验证并保存 PIPESIM', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('原配置未更改')
  await expect(page.getByRole('dialog')).toContainText('首次使用')
  expect(state.capabilityReads()).toBe(1)
})

test('Worker 离线可关闭向导继续浏览并重新检测', async ({ page }) => {
  await setup(page, { offline: true })
  const dialog = page.getByRole('dialog')
  await expect(dialog).toContainText('请先启动 Worker')
  await dialog.getByRole('button', { name: '稍后配置 / 继续浏览' }).click()
  await expect(dialog).not.toBeVisible()
  await page.getByRole('button', { name: '环境设置', exact: true }).click()
  await expect(dialog).toBeVisible()
})

test('解析融合页面不触发环境向导', async ({ page }) => {
  await setup(page)
  await page.goto('/ipr')
  await expect(page.getByRole('button', { name: '环境设置', exact: true })).toHaveCount(0)
  await expect(page.getByRole('dialog', { name: '软件集成 · 环境发现与配置' })).toHaveCount(0)
})
