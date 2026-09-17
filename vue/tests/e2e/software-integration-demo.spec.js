import { expect, test } from '@playwright/test'

test.beforeEach(async ({ page }) => {
  page.on('pageerror', error => console.error(`[browser] ${error.message}`))
})

const project = { id: 1, name: '演示项目', description: '浏览器回归专用数据' }
const inspection = {
  schemaVersion: 'eclipse-data-inspection/1',
  caseName: 'DEMO.DATA',
  sections: ['RUNSPEC', 'GRID', 'SOLUTION', 'SUMMARY', 'SCHEDULE'],
  unitSystem: 'METRIC',
  phases: ['OIL', 'WATER'],
  dimensions: { nx: 4, ny: 3, nz: 2 }
}
const versions = {
  well: { id: 101, versionNo: 1, status: 'READY', modelKind: 'black_oil_liquid', originalName: 'well-demo.pips', studies: ['Base Case'] },
  network: { id: 301, versionNo: 1, status: 'READY', modelKind: 'network', originalName: 'network-demo.pips', studies: ['Network Base'] },
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
  pipesimNetwork: { version: '2022.1', status: 'AVAILABLE', reasonCode: null, runTasks: ['network'], maxTimeoutSeconds: 600 },
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
    historyReads: new Map(),
    runReads: new Map(),
    delayedWellRunId: options.delayedWellRunId || null,
    releaseWellDetail: null,
    heldRunId: null,
    releaseHeldRun: null,
    capabilities: options.capabilities || availableCapabilities(),
    capabilityReads: 0,
    unexpectedRequests: []
  }
  for (const run of options.runs || []) state.runs.set(run.id, run)

  await page.route('**/api/software-integration/**', async route => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname.replace(/^\/api/, '')
    const method = request.method()

    if (path === '/software-integration/capabilities' && method === 'GET') {
      state.capabilityReads += 1
      return route.fulfill({ json: response(state.capabilities) })
    }
    if (path === '/software-integration/projects' && method === 'GET') {
      return route.fulfill({ json: response([project]) })
    }
    if (path === '/software-integration/projects/1' && method === 'GET') {
      return route.fulfill({ json: response({ project, models: state.models }) })
    }
    if (path === '/software-integration/projects/1/model-versions/101/validate' && method === 'POST') {
      state.validationRequests.push(101)
      if (state.holdValidation) await new Promise(resolve => { state.releaseValidation = resolve })
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
  await expect(page.getByText('DEMO.DATA', { exact: true })).toBeVisible()
  await page.getByRole('tab', { name: 'ECLIPSE 结果' }).click()
  await expect(page.getByText('尚无已验证真实计算结果')).toBeVisible()
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
    node: [{ variable: 'Pressure', unit: 'bara', values: [{ name: 'N2', value: 81.2 }] }],
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
    quality: []
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
  const state = await installMockBackend(page, {
    networkHistory: [summary(historical)],
    runs: [historical]
  })
  await openWorkspace(page)
  await activateModel(page, '管网演示模型')

  await expect(page.getByText('11 个节点 / 10 条连接', { exact: true })).toBeVisible()
  const overview = page.getByRole('region', { name: '管网支路工况总览' })
  await expect(overview).toContainText('首点减末点')
  await overview.getByRole('textbox', { name: '搜索工况支路' }).fill('Comparison')
  const overviewDownload = page.waitForEvent('download')
  await overview.getByRole('button', { name: '导出工况 CSV' }).click()
  const overviewStream = await (await overviewDownload).createReadStream()
  let overviewCsv = ''
  for await (const chunk of overviewStream) overviewCsv += chunk.toString('utf8')
  expect(overviewCsv).toContain('"8501","Network Base","Comparison-branch","bara","3","91","79","12","79","91","0"')
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
  await page.getByRole('combobox', { name: '搜索管网设备' }).click()
  await page.getByRole('option', { name: 'N2', exact: true }).click()
  const selection = page.getByLabel('管网点选结果')
  await expect(selection.getByText('81.2', { exact: true })).toBeVisible()
  await expect(selection.getByText('未返回可精确匹配的支路结果')).toBeVisible()
  await page.getByRole('button', { name: '保存布局', exact: true }).click()
  await expect(page.getByText('当前运行的布局已保存到本浏览器。')).toBeVisible()
  const saved = await page.evaluate(() => JSON.parse(localStorage.getItem('grdp:network-layout:v1:run:8501')))
  expect(saved.positions).toHaveLength(11)
  saved.positions[0].x += 27
  await page.evaluate(snapshot => localStorage.setItem('grdp:network-layout:v1:run:8501', JSON.stringify(snapshot)), saved)
  await activateModel(page, 'ECLIPSE演示模型')
  await activateModel(page, '管网演示模型')
  await page.getByRole('button', { name: '保存布局', exact: true }).click()
  expect(await page.evaluate(() => JSON.parse(localStorage.getItem('grdp:network-layout:v1:run:8501')).positions)).toEqual(saved.positions)
  await page.getByText('设备符号', { exact: true }).click()
  await page.getByTestId('network-topology-fit').click()
  expect(await page.evaluate(() => localStorage.getItem('grdp:network-layout:v1:run:8501'))).toBeNull()

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
  expect(profileCsv).toContain('"8501","Network Base","Comparison-branch","15","m","Pressure","91","bara"')
  expect(profileCsv).toContain('"N1-N2","100","m","Pressure","81.2","bara"')
  await activateModel(page, 'ECLIPSE演示模型')
  await activateModel(page, '管网演示模型')
  await expect(page.locator('.profile-panel .el-table__body-wrapper').getByText('Comparison-branch', { exact: true })).toHaveCount(0)
  await expect(page.getByText('N1-N2', { exact: true }).first()).toBeVisible()
  await expect(page.getByRole('tab', { name: '系统结果 (1)' })).toBeVisible()
  await expect(page.getByText('SystemOutletPressure', { exact: false }).first()).toBeVisible()
  await page.getByRole('tab', { name: '节点结果 (1)' }).click()
  const nodeResultPanel = page.getByRole('tabpanel', { name: '节点结果 (1)' })
  await expect(nodeResultPanel.getByText('Pressure (bara)', { exact: true }).first()).toBeVisible()
  await expect(nodeResultPanel.getByText('N2', { exact: true })).toBeVisible()
  for (const viewport of [{ width: 1440, height: 900 }, { width: 1280, height: 720 }]) {
    await page.setViewportSize(viewport)
    await page.locator('.topology-panel').scrollIntoViewIfNeeded()
    await page.screenshot({ path: testInfo.outputPath(`network-${viewport.width}.png`) })
    const bounds = await page.locator('.topology-panel').boundingBox()
    expect(bounds.x + bounds.width).toBeLessThanOrEqual(viewport.width)
  }
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
    result: {
      schemaVersion: 'pipesim-well-result/1', model_kind: 'black_oil_liquid', runTask: 'combined', resultContract: 'VALID_FULL',
      units: Object.fromEntries(Object.entries({ flow: 'STB/DAY', pressure: 'PSIA', depth: 'FT', temperature: 'DEGF' }).map(([key, displayUnit]) => [key, { displayUnit }])),
      ipr: [{ flow: 0, pressure }, { flow: 10, pressure: 0 }], vlp: [{ flow: 0, pressure: 0 }, { flow: 10, pressure }],
      profile: [{ depth: 0, pressure, temperature: 70 }, { depth: 100, pressure: pressure + 20, temperature: 80 }]
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
  await expect(page.locator('.nodal-result').getByText('运行 #8701 · v1 · Base Case', { exact: true }).first()).toBeVisible()
  const promise = page.waitForEvent('download')
  await page.locator('#well-nodal-export-csv').click()
  const stream = await (await promise).createReadStream()
  let csv = ''
  for await (const chunk of stream) csv += chunk.toString('utf8')
  expect(csv).toContain('"运行 #8701 · v1 · Base Case","IPR","0","100"')
  await page.getByRole('tab', { name: 'PT 剖面', exact: true }).click()
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
