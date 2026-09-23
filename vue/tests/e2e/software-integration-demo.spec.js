import { expect, test } from '@playwright/test'

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
  well: { id: 101, versionNo: 1, status: 'READY', modelKind: 'black_oil_liquid', originalName: 'well-demo.pips', studies: ['Base Case'] },
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
  pipesimNetwork: { version: '2022.1', status: 'AVAILABLE', reasonCode: null, runTasks: ['network', 'system-analysis', 'network-optimizer'], maxTimeoutSeconds: 600 },
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
  state.deletedProjects = [{ ...project, id: 99, name: '已删除演示项目', updatedAt: '2026-09-10T10:00:00' }]
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
    const artifactDownloadMatch = path.match(/^\/software-integration\/runs\/(\d+)\/artifacts\/(\d+)\/download$/)
    if (artifactDownloadMatch && method === 'GET') {
      state.downloadRequests.push({ runId: Number(artifactDownloadMatch[1]), artifactId: Number(artifactDownloadMatch[2]) })
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
  await expect(diagnostics).toContainText('井势与井求解 · 1 条')
  await expect(diagnostics).toContainText('线性方程收敛 · 1 条')
  await expect(diagnostics).toContainText('压力与 PVT 边界 · 1 条')
  await expect(diagnostics).toContainText('非线性与时间步 · 1 条')
  await expect(diagnostics).toContainText('POTENTIAL IN WELL PROD NOT CONVERGED')
  await expect(diagnostics).toContainText('不会自动修改模型')
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
    artifacts: [{ id: 80, name: 'eclipse-output-DEMO.EGRID', type: 'output', contentType: 'application/octet-stream', sizeBytes: 400,
      sha256: 'd'.repeat(64), createdAt: '2026-09-13T05:00:00', expiresAt: '2026-10-13T05:00:00' }],
    result: {
      schemaVersion: 'eclipse-summary-result/1', modelKind: 'eclipse_100', runTask: 'eclipse', resultContract: 'VALID_FULL',
      eclEnd: { comments: 0, warnings: 0, problems: 0, errors: 0, bugs: 0 }, outputFiles: [{ name: 'DEMO.EGRID', sizeBytes: 400, sha256: 'd'.repeat(64) }], summary: null,
      grid: { fileName: 'DEMO.EGRID', nx: 1, ny: 1, nz: 1, activeCells: 1 },
      fieldIndex: { schemaVersion: 'eclipse-binary-field-index/1', files: [{ name: 'DEMO.EGRID', sizeBytes: 400, byteOrder: 'BIG', fields: [
        { keyword: 'COORD', dataType: 'REAL', count: 24, elementSize: 4, dataBytes: 96, segments: [{ offset: 40, length: 96 }] },
        { keyword: 'ZCORN', dataType: 'REAL', count: 8, elementSize: 4, dataBytes: 32, segments: [{ offset: 140, length: 32 }] },
        { keyword: 'ACTNUM', dataType: 'INTE', count: 1, elementSize: 4, dataBytes: 4, segments: [{ offset: 200, length: 4 }] },
        { keyword: 'PRESSURE', dataType: 'REAL', count: 1, elementSize: 4, dataBytes: 4, segments: [{ offset: 220, length: 4 }] }
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
      '80:220': floatBytes([1000])
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
  const canvas = grid3d.locator('.grid-3d-canvas canvas')
  const canvasBox = await canvas.boundingBox()
  await canvas.click({ position: { x: canvasBox.width / 2, y: canvasBox.height / 2 } })
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
  expect(state.unexpectedRequests).toEqual([])
})
