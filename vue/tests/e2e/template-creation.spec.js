import { expect, test } from '@playwright/test'

// Isolated UI contract tests: mount the real component and replace only HTTP.
// This does not log into the user's platform and is not native SDK acceptance.
async function setup(page) {
  const state = { posts: [], job: null, records: [], failCapabilities: false, rejectCreate: false, getGate: null, listGate: null }
  await page.route('**/template-creation-test', route => route.fulfill({
    contentType: 'text/html', body: '<html><body><div id="app"></div></body></html>'
  }))
  await page.route('**/api/software-integration/projects/*/template-creations**', async route => {
    const url = new URL(route.request().url())
    const method = route.request().method()
    const reply = (data, status = 200) => route.fulfill({ status, json: { code: status === 202 ? 200 : status, msg: status < 300 ? 'success' : '测试拒绝', data } })
    if (url.pathname.endsWith('/capabilities')) return state.failCapabilities ? reply(null, 502) : reply({ template: 'Simple vertical', unitsSystem: 'PIPESIM_FIELD' })
    if (method === 'GET' && url.pathname.endsWith('/template-creations')) {
      const records = structuredClone(state.records)
      const gate = state.listGate; state.listGate = null
      if (gate) { gate.started(); await gate.promise }
      return reply(records)
    }
    if (method === 'POST') {
      state.posts.push(url.pathname)
      if (url.pathname.endsWith('/cancel')) {
        state.job = { ...state.job, cancellationRequested: true }
        return reply(state.job)
      }
      if (url.pathname.endsWith('/register')) return reply(51)
      if (state.rejectCreate) return reply(null, 409)
      const payload = route.request().postDataJSON()
      state.job = { requestId: payload.requestId, projectId: 1, well: payload.well, state: 'PREPARING', profile: null }
      return reply(state.job, 202)
    }
    const snapshot = state.job ? structuredClone(state.job) : null
    const gate = state.getGate
    state.getGate = null
    if (gate) { gate.started(); await gate.promise }
    return snapshot ? reply(snapshot) : reply(null, 404)
  })
  await page.goto('/template-creation-test')
  await page.evaluate(async () => {
    const { createApp } = await import('/node_modules/.vite/deps/vue.js')
    const { default: ElementPlus } = await import('/node_modules/.vite/deps/element-plus.js')
    const { default: Creator } = await import('/src/views/SoftwareIntegration/PipesimTemplateCreator.vue')
    window.creatorApp = createApp(Creator, { projectId: 1 }).use(ElementPlus).mount('#app')
  })
  await page.getByRole('button', { name: '新建 PIPESIM 模板井' }).click()
  return state
}

async function fillExperiment(page) {
  await page.getByPlaceholder('例如 ConsoleWell').fill('ConsoleWell')
  await page.getByRole('button', { name: '填入演示实验参数（非官方原始数据）' }).click()
}

test('取消确认不冒充终态，取消后不展示曲线，刷新不重复 POST', async ({ page }) => {
  const state = await setup(page)
  await fillExperiment(page)
  await page.getByRole('button', { name: '创建并真实计算' }).click()
  await expect(page.getByRole('dialog').getByText(/请求 ID：.*正在真实建井与计算/)).toBeVisible()
  await page.getByRole('button', { name: '取消任务' }).click()
  await expect(page.getByText(/已请求取消，等待进程退出/)).toBeVisible()
  await expect(page.getByRole('button', { name: '开始另一口井' })).toBeDisabled()
  const originalId = state.job.requestId
  state.job = { ...state.job, state: 'CANCELLED', cancellationRequested: false, errorCode: 'VALIDATION_CANCELLED' }
  await page.getByRole('button', { name: '查询 / 恢复状态' }).click()
  await expect(page.getByRole('button', { name: '开始另一口井' })).toBeEnabled()
  await expect(page.getByRole('button', { name: '下载 .pips' })).toBeDisabled()
  // Re-mount in a fresh page with the same sessionStorage. No new create POST is allowed.
  await page.reload()
  await page.evaluate(async () => {
    const { createApp } = await import('/node_modules/.vite/deps/vue.js')
    const { default: ElementPlus } = await import('/node_modules/.vite/deps/element-plus.js')
    const { default: Creator } = await import('/src/views/SoftwareIntegration/PipesimTemplateCreator.vue')
    createApp(Creator, { projectId: 1 }).use(ElementPlus).mount('#app')
  })
  await expect(page.getByText(/已取消/).first()).toBeVisible()
  expect(state.posts.filter(path => path.endsWith('/template-creations'))).toHaveLength(1)
  expect(state.job.requestId).toBe(originalId)
})

test('团队成员无需本标签页请求ID即可恢复持久记录，查询不创建任务', async ({ page }) => {
  const state = await setup(page)
  const id = 'aa391543-b261-4d9e-9ad3-d3b88840d74f'
  state.job = { requestId: id, projectId: 1, well: 'TeamWell', state: 'SUCCEEDED', versionId: 61,
    profile: [{ depth: 0, pressure: 250, temperature: 92 }] }
  state.records = [{ requestId: id, projectId: 1, well: 'TeamWell', state: 'SUCCEEDED', versionId: 61 }]
  await page.getByRole('button', { name: '刷新团队记录' }).click()
  await page.getByRole('combobox', { name: '选择团队建井记录' }).click()
  await page.getByRole('option', { name: /TeamWell/ }).click()
  await expect(page.getByText(/已登记模型版本 #61/)).toBeVisible()
  await expect(page.getByRole('button', { name: '下载 .pips' })).toBeEnabled()
  await expect(page.getByRole('button', { name: '保存到项目并验证' })).toBeDisabled()
  expect(state.posts).toHaveLength(0)
})

test('团队记录较早刷新迟到不能覆盖较新的列表', async ({ page }) => {
  const state = await setup(page)
  await expect(page.getByRole('button', { name: '刷新团队记录' })).toBeEnabled()
  let release, started
  const begin = new Promise(resolve => { started = resolve })
  state.records = [{ requestId: 'aa391543-b261-4d9e-9ad3-d3b88840d74f', well: 'OldWell', state: 'PREPARING' }]
  state.listGate = { promise: new Promise(resolve => { release = resolve }), started }
  await page.getByRole('button', { name: '刷新团队记录' }).click()
  await begin
  state.records = [{ requestId: 'bb391543-b261-4d9e-9ad3-d3b88840d74f', well: 'NewWell', state: 'SUCCEEDED' }]
  // Reopening the dialog starts a newer read while the old request is still in flight.
  await page.getByRole('button', { name: 'Close this dialog' }).click()
  const newResponse = page.waitForResponse(async response => response.url().endsWith('/template-creations')
    && response.status() === 200 && (await response.json()).data[0]?.well === 'NewWell')
  await page.getByRole('button', { name: '新建 PIPESIM 模板井' }).click()
  await newResponse
  const oldResponse = page.waitForResponse(async response => response.url().endsWith('/template-creations')
    && response.status() === 200 && (await response.json()).data[0]?.well === 'OldWell')
  release(); await oldResponse
  await page.getByRole('combobox', { name: '选择团队建井记录' }).click()
  await expect(page.getByRole('option', { name: /NewWell/ })).toBeVisible()
  await expect(page.getByRole('option', { name: /OldWell/ })).toHaveCount(0)
  expect(state.posts).toHaveLength(0)
})

test('SDK 服务未就绪时禁止建井；明确拒绝可修正重试', async ({ page }) => {
  const state = await setup(page)
  state.failCapabilities = true
  await page.getByRole('button', { name: 'Close this dialog' }).click()
  await page.getByRole('button', { name: '新建 PIPESIM 模板井' }).click()
  await expect(page.getByRole('button', { name: '创建并真实计算' })).toBeDisabled()
  await expect(page.getByText(/建井服务未就绪/)).toBeVisible()
  state.failCapabilities = false
  await page.getByRole('button', { name: '检查建井服务' }).click()
  await fillExperiment(page)
  state.rejectCreate = true
  await page.getByRole('button', { name: '创建并真实计算' }).click()
  await expect(page.getByRole('button', { name: '创建并真实计算' })).toBeEnabled()
  await expect(page.getByPlaceholder('例如 ConsoleWell')).toBeEnabled()
})

test('明确执行前拒绝允许人工新任务，但未知状态仍禁止重建', async ({ page }) => {
  const state = await setup(page)
  await fillExperiment(page)
  await page.getByRole('button', { name: '创建并真实计算' }).click()
  await expect.poll(() => state.job?.state).toBe('PREPARING')
  state.job = { ...state.job, state: 'UNCERTAIN' }
  await page.getByRole('button', { name: '查询 / 恢复状态' }).click()
  await expect(page.getByRole('button', { name: '开始另一口井' })).toBeDisabled()
  state.job = { ...state.job, state: 'REJECTED', errorCode: 'CREATION_NOT_DISPATCHED' }
  await page.getByRole('button', { name: '查询 / 恢复状态' }).click()
  await expect(page.getByText(/执行前已拒绝（未启动建井）/).first()).toBeVisible()
  await expect(page.getByRole('button', { name: '开始另一口井' })).toBeEnabled()
  await expect(page.getByRole('button', { name: '下载 .pips' })).toBeDisabled()
  await page.getByRole('button', { name: '开始另一口井' }).click()
  await expect(page.getByPlaceholder('例如 ConsoleWell')).toBeEnabled()
  expect(state.posts.filter(path => path.endsWith('/template-creations'))).toHaveLength(1)
})

test('成功显示真实返回点，登记后不能重复登记', async ({ page }) => {
  const state = await setup(page)
  await fillExperiment(page)
  await page.getByRole('button', { name: '创建并真实计算' }).click()
  await expect.poll(() => state.job?.state).toBe('PREPARING')
  state.job = { ...state.job, state: 'SUCCEEDED', profile: [
    { depth: 0, pressure: 250, temperature: 92 }, { depth: 9200, pressure: 3394, temperature: 151 }
  ] }
  await page.getByRole('button', { name: '查询 / 恢复状态' }).click()
  await expect(page.getByRole('button', { name: '下载 .pips' })).toBeEnabled()
  await expect(page.getByText('3394', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '保存到项目并验证' }).click()
  await expect(page.getByText(/已登记模型版本 #51/)).toBeVisible()
  await expect(page.getByRole('button', { name: '保存到项目并验证' })).toBeDisabled()
})

test('较早查询迟到不能把成功状态改回计算中', async ({ page }) => {
  const state = await setup(page)
  await fillExperiment(page)
  await page.getByRole('button', { name: '创建并真实计算' }).click()
  await expect(page.getByRole('button', { name: '取消任务' })).toBeEnabled()
  let release, started
  const begin = new Promise(resolve => { started = resolve })
  state.getGate = { promise: new Promise(resolve => { release = resolve }), started }
  await page.getByRole('button', { name: '查询 / 恢复状态' }).click()
  await begin
  state.job = { ...state.job, state: 'SUCCEEDED', profile: [{ depth: 0, pressure: 250, temperature: 92 }] }
  await page.getByRole('button', { name: '查询 / 恢复状态' }).click()
  await expect(page.getByRole('button', { name: '下载 .pips' })).toBeEnabled()
  const oldResponse = page.waitForResponse(async response => response.url().endsWith(state.job.requestId)
    && response.status() === 200 && (await response.json()).data.state === 'PREPARING')
  release()
  await oldResponse
  await page.evaluate(() => new Promise(requestAnimationFrame))
  await expect(page.getByRole('button', { name: '下载 .pips' })).toBeEnabled()
  await expect(page.getByRole('button', { name: '取消任务' })).toBeDisabled()
})
