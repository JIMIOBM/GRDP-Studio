import { expect, test } from '@playwright/test'

/**
 * 库 → 协同预测 → 一体化耦合优化（单目标/多目标）冒烟：
 *  - URL 直达库功能页（scope=reservoir 的完整地址，可刷新）；
 *  - 左侧记录目录的数据源 = 本项目留存表（GET /prediction-results）：只显示留存过结果的预测，
 *    旧的原平台任务不再出现；留存为空时进页面不自动创建任务，由目录「新建预测」开始；
 *  - 「启动预测」= 组装 reservoirInput 提交 Calc → 绘图轮询 → 结果留存（POST /prediction-results）
 *    → 记录即时进目录；多目标提交井口外输压力；
 *  - 改井选择后启动：先用平台顺延名称创建新任务，不撞"分析已存在"。
 * 原平台（/docker-api）与后端（/api）全部 mock，不依赖真实登录。
 */

const account = { token: 'e2e-coupling-token', userId: 1, userName: 'e2e' }
const projectScope = { projectId: 1, gasReservoirId: 2, storageId: 1 }
const predictionTaskId = 55
const savedRecordName = '井组动态预测 3'

const startTimeMs = Date.parse('2026-10-01T00:00:00.000Z')
const buildDetail = (taskId, wellNames) => ({
  reservoirInput: {
    DynamicPredictionId: taskId,
    targetProduction: 8,
    externalWellheadPressure: 6.5,
    predictedTimeStep: 1,
    predictedTimeStepNumber: 3,
    predictionStartTime: startTimeMs,
    economicLimitGasProduction: 0.5,
    gasType: 1,
    specificGravity: 0.62,
    hydrogenSulfide: 0,
    carbonDioxide: 0,
    nitrogen: 0,
    modificationMethod: 0,
    deviationFactorMethod: 0,
    viscosityMethod: 0,
    originalFormationPressure: 20,
    formationTemperature: 80,
    waterSaturation: 0.3,
    rockCompressionCoefficient: 0.0004,
    waterCompressionCoefficient: 0.0004,
    mbEquationType: 1,
    productivityEvaluationForm: 1
  },
  wellInputs: wellNames.map((wellName, index) => ({
    wellName, id: 91 + index, DynamicPredictionId: taskId
  }))
})
// 与原平台井组动态预测一致：4 个输出指标（气产量、累产气量、地层压力、采出程度）。
const chartResponse = {
  chartItems: [
    {
      yAxisField: 'gasProduction', name: '气产量',
      data: [
        { xValue: '2026-10-01', yValue: 8 },
        { xValue: '2026-10-02', yValue: 7.8 },
        { xValue: '2026-10-03', yValue: 7.6 }
      ]
    },
    {
      yAxisField: 'cumulativeGasProduction', name: '累产气量',
      data: [
        { xValue: '2026-10-01', yValue: 8 },
        { xValue: '2026-10-02', yValue: 15.8 },
        { xValue: '2026-10-03', yValue: 23.4 }
      ]
    },
    {
      yAxisField: 'formationPressure', name: '地层压力',
      data: [
        { xValue: '2026-10-01', yValue: 22.4 },
        { xValue: '2026-10-02', yValue: 22.2 },
        { xValue: '2026-10-03', yValue: 22.0 }
      ]
    },
    {
      yAxisField: 'reservoirRecoveryDegree', name: '采出程度',
      data: [
        { xValue: '2026-10-01', yValue: 19.1 },
        { xValue: '2026-10-02', yValue: 19.3 },
        { xValue: '2026-10-03', yValue: 19.5 }
      ]
    }
  ]
}

/** 预置一条留存记录（列表/结果摘要字段与后端 ResultSummary 一致）。 */
const savedRecord = (predictionId, predictionName, mode) => ({
  id: predictionId, predictionId, predictionName, mode,
  pointCount: 3, startDate: '2026-10-01', endDate: '2026-10-03',
  calculatedAt: '2026-10-16T10:00:00'
})

async function installMocks(page, options = {}) {
  // 新任务编号避开所有预置任务（留存记录里的任务、指定井集的任务等）。
  const presetIds = [
    predictionTaskId - 1,
    ...Object.keys(options.taskWells || {}).map(Number),
    ...(options.existingChartTasks || []),
    ...(options.savedRecords || []).map(record => Number(record.predictionId))
  ]
  const state = {
    createdPayloads: [],
    calcPayloads: [],
    savedResults: [],
    deletedPlatformTasks: [],
    deletedRecords: [],
    // 留存表内容：GET 返回它，POST 追加（最近在前）。
    savedRecords: [...(options.savedRecords || [])],
    calcDone: false,
    // 平台顺延名称：每次请求 defaultname 返回下一个编号。
    defaultNameSeq: 0,
    // 各任务的井集：创建时记录；options.taskWells 预置已有任务场景。
    taskWells: { [predictionTaskId]: ['X-1', 'X-2', 'X-3'], ...(options.taskWells || {}) },
    nextTaskId: 1 + Math.max(...presetIds)
  }
  await page.addInitScript(value => {
    localStorage.setItem('account', JSON.stringify(value))
  }, account)
  // 兜底：工作台初始化中与本功能无关的后端接口返回空结构；后注册的路由先匹配。
  // 注意用 pathname 前缀匹配，不能用 **/api/** 之类的 glob——会把 Vite 的
  // /src/api/*.js 源码模块请求也拦掉，返回 JSON 后整页模块加载失败。
  await page.route(url => url.pathname.startsWith('/api/') && !url.pathname.startsWith('/api/reservoir-loss/') &&
    url.pathname !== '/api/prediction-results', route => route.fulfill({ json: {} }))
  await page.route(url => url.pathname.startsWith('/api/reservoir-loss/'), async route => {
    const url = new URL(route.request().url())
    if (/\/storages\/1\/wells$/.test(url.pathname)) {
      return route.fulfill({ json: [
        { id: 1, wellName: 'X-1' }, { id: 2, wellName: 'X-2' }, { id: 3, wellName: 'X-3' }
      ] })
    }
    if (/\/storages$/.test(url.pathname)) {
      return route.fulfill({ json: [{ ...projectScope, name: '测试储气库' }] })
    }
    return route.fulfill({ json: {} })
  })
  await page.route(url => url.pathname.startsWith('/docker-api/'), async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname.replace(/^\/docker-api/, '')
    const method = request.method()
    if (path === '/projects/1') {
      return route.fulfill({ json: { wells: ['X-1', 'X-2', 'X-3'] } })
    }
    if (path === '/projectanalysis/1/2/wells') {
      return route.fulfill({ json: { wells: ['X-1', 'X-2', 'X-3'] } })
    }
    if (path === '/projectanalysis/dynamicprediction//1/2/2/defaultname') {
      state.defaultNameSeq += 1
      return route.fulfill({ json: { predictionName: `井组动态预测${state.defaultNameSeq}` } })
    }
    if (path === '/projectanalysis/dynamicprediction' && method === 'POST') {
      const body = request.postDataJSON()
      state.createdPayloads.push(body)
      const taskId = state.nextTaskId
      state.nextTaskId += 1
      state.taskWells[taskId] = body.wellNames
      return route.fulfill({ json: {
        dynamicPredictionId: taskId, dynamicPredictionName: body.dynamicPredictionName, dynamicPredictionType: 2
      } })
    }
    if (path === '/projectanalysis/dynamicprediction/1/2' && method === 'DELETE') {
      // 平台删除任务：body 携带 predictionId（与原平台前端一致）。
      state.deletedPlatformTasks.push(request.postDataJSON())
      return route.fulfill({ json: {} })
    }
    if (/^\/projectanalysis\/dynamicprediction\/1\/2\/\d+$/.test(path) && method === 'GET') {
      const taskId = Number(path.split('/').pop())
      return route.fulfill({ json: buildDetail(taskId, state.taskWells[taskId] || ['X-1', 'X-2', 'X-3']) })
    }
    if (path === '/projectanalysis/dynamicprediction/Calc' && method === 'POST') {
      state.calcPayloads.push(request.postDataJSON())
      state.calcDone = true
      return route.fulfill({ json: {} })
    }
    if (path.startsWith('/projectanalysis/dynamicprediction/result/wells/chart/1/2/')) {
      // 预置了已有结果的任务（打开历史记录）恒定返回曲线；
      // 否则 Calc 之前没有曲线（新任务/未计算），Calc 之后返回本次结果。
      const taskId = Number(path.split('/').pop())
      if (options.existingChartTasks?.includes(taskId)) return route.fulfill({ json: chartResponse })
      return route.fulfill({ json: state.calcDone ? chartResponse : { chartItems: [] } })
    }
    // 目录初始化使用的其他原平台接口（产能试井等）返回空结构，测试不依赖它们。
    return route.fulfill({ json: {} })
  })
  // 本项目后端的预测结果留存接口（后注册的优先于 /api 兜底路由）。
  await page.route(url => url.pathname === '/api/prediction-results', async route => {
    const request = route.request()
    if (request.method() === 'DELETE') {
      const predictionId = Number(new URL(request.url()).searchParams.get('predictionId'))
      state.deletedRecords.push(predictionId)
      state.savedRecords = state.savedRecords.filter(item => Number(item.predictionId) !== predictionId)
      return route.fulfill({ json: { code: 200, msg: 'success', data: 1 } })
    }
    if (request.method() === 'POST') {
      const payload = request.postDataJSON()
      state.savedResults.push(payload)
      const summary = {
        id: state.savedRecords.length + 1, predictionId: payload.predictionId,
        predictionName: payload.predictionName, mode: payload.mode,
        pointCount: payload.points.length, startDate: payload.startDate, endDate: payload.endDate,
        calculatedAt: '2026-10-16T12:00:00'
      }
      state.savedRecords = [summary, ...state.savedRecords.filter(item => item.predictionId !== payload.predictionId)]
      return route.fulfill({ json: { code: 200, msg: 'success', data: summary } })
    }
    return route.fulfill({ json: { code: 200, msg: 'success', data: state.savedRecords } })
  })
  return state
}

const pageUrl = feature => '/ipr?' + new URLSearchParams({
  scope: 'reservoir', projectId: '1', gasReservoirId: '2', storageId: '1',
  group: '协同预测', parent: '一体化耦合优化', feature
}).toString()

/** 展开模式目录（触发记录懒加载），返回"新建预测"动作节点定位器。 */
async function expandModeDirectory(page, mode) {
  await page.locator('.workspace-side-tree .node-text', { hasText: mode }).click()
  const action = page.locator('.workspace-side-tree .node-text', { hasText: '新建预测' })
  await expect(action).toBeVisible()
  return action
}

test('single objective creates a well group task, applies panel params, draws the chart and saves the result', async ({ page }) => {
  test.setTimeout(90_000)
  const state = await installMocks(page)
  const pageErrors = []
  page.on('pageerror', error => pageErrors.push(error.message))

  await page.goto(pageUrl('单目标'))
  // 本用例通常是整套里第一个访问 /ipr 的：dev server 首访要编译工作台模块树，
  // 首个断言放宽超时，避免把冷启动编译时间误判为页面故障。
  await expect(page.getByText('一体化耦合优化-单目标', { exact: true })).toBeVisible({ timeout: 60_000 })
  await expect(page.getByText('参数设置（单目标）')).toBeVisible()
  // 留存表为空：不自动创建任务，提示从目录开始（旧的原平台任务不再出现）。
  await expect(page.locator('.panel-status')).toContainText('当前还没有本模式的预测记录', { timeout: 30_000 })
  expect(state.createdPayloads).toHaveLength(0)

  // 展开单目标目录 → 点「新建预测」创建任务并带出平台参数。
  await (await expandModeDirectory(page, '单目标')).click()
  await expect(page.locator('.panel-status')).toContainText('已新建预测', { timeout: 30_000 })
  await expect(page.locator('.field', { hasText: '目标产量 (10⁴m³/d)' }).locator('input')).toHaveValue('8')
  expect(state.createdPayloads).toHaveLength(1)
  expect(state.createdPayloads[0]).toMatchObject({
    projectId: 1, gasReservoirId: 2, dynamicPredictionType: 2,
    wellNames: ['X-1', 'X-2', 'X-3'], productivityEvaluationForm: 1
  })
  const taskName = state.createdPayloads[0].dynamicPredictionName
  expect(taskName).toMatch(/^井组动态预测\d+$/)

  // 启动预测 → 出图 → 结果留存 → 记录进目录。
  await page.getByRole('button', { name: '启动预测' }).click()
  await expect(page.locator('.panel-status')).toContainText('预测完成', { timeout: 30_000 })
  await expect(page.locator('.coupling-chart canvas')).toBeVisible()
  await expect(page.locator('.coupling-chart')).toHaveAttribute('aria-label', '预测曲线：气产量、累产气量')
  // 留存请求：范围、任务、模式、逐日结果（保存为异步，等待到位）。
  await expect.poll(() => state.savedResults.length, { timeout: 30_000 }).toBe(1)
  expect(state.savedResults[0]).toMatchObject({
    projectId: 1, gasReservoirId: 2, storageId: 1, mode: '单目标',
    predictionName: taskName
  })
  expect(state.savedResults[0].points).toHaveLength(3)
  expect(state.savedResults[0].startDate).toBe('2026-10-01')
  expect(state.savedResults[0].endDate).toBe('2026-10-03')
  expect(state.savedResults[0].wellNames).toEqual(['X-1', 'X-2', 'X-3'])
  // 保存成功后新记录即时插入目录。
  await expect(page.locator('.workspace-side-tree .node-text', { hasText: taskName })).toBeVisible()

  // 目标量判定：计算量 = 预测期首尾累计气产量差（mock 数据 23.4 − 8 = 15.4），与输入需求量比较。
  const demandInput = page.locator('.target-check input')
  await expect(page.locator('.target-check')).toContainText('15.4')
  await expect(page.locator('.target-check-verdict')).toContainText('输入目标需求量后自动判定')
  await demandInput.fill('20')
  await expect(page.locator('.target-check-verdict')).toContainText('不满足需求量')
  await expect(page.locator('.target-check-verdict')).toContainText('4.6')
  await demandInput.fill('10')
  await expect(page.locator('.target-check-verdict')).toContainText('✓ 满足需求量')

  // Calc：面板参数进入 reservoirInput；单目标下外输压力沿用任务详情值。
  expect(state.calcPayloads).toHaveLength(1)
  expect(state.calcPayloads[0].dynamicPredictionId).toBe(predictionTaskId)
  expect(state.calcPayloads[0].reservoirInput).toMatchObject({
    targetProduction: 8, externalWellheadPressure: 6.5,
    predictedTimeStep: 1, predictedTimeStepNumber: 3,
    predictionStartTime: startTimeMs
  })
  expect(pageErrors).toEqual([])
})

test('multi objective submits the edited wellhead export pressure as the second target', async ({ page }) => {
  test.setTimeout(90_000)
  const state = await installMocks(page)
  const pageErrors = []
  page.on('pageerror', error => pageErrors.push(error.message))

  await page.goto(pageUrl('多目标'))
  await expect(page.getByText('参数设置（多目标）')).toBeVisible()
  const pressureInput = page.locator('.field', { hasText: '井口外输压力 (MPa)' }).locator('input')
  await expect(pressureInput).toBeVisible()
  await expect(page.locator('.panel-status')).toContainText('当前还没有本模式的预测记录', { timeout: 30_000 })

  await (await expandModeDirectory(page, '多目标')).click()
  await expect(page.locator('.panel-status')).toContainText('已新建预测', { timeout: 30_000 })
  await expect(pressureInput).toHaveValue('6.5')

  // 用户改压力后启动预测：Calc 提交新压力值，留存记录标为多目标。
  await pressureInput.fill('7.2')
  await page.getByRole('button', { name: '启动预测' }).click()
  await expect(page.locator('.panel-status')).toContainText('预测完成', { timeout: 30_000 })
  // 多目标与原平台一致，绘制 4 个输出指标。
  await expect(page.locator('.coupling-chart'))
    .toHaveAttribute('aria-label', '预测曲线：气产量、地层压力、累产气量、采出程度')
  expect(state.calcPayloads).toHaveLength(1)
  expect(state.calcPayloads[0].reservoirInput.externalWellheadPressure).toBe(7.2)
  expect(state.calcPayloads[0].reservoirInput.targetProduction).toBe(8)
  expect(state.createdPayloads).toHaveLength(1)
  await expect.poll(() => state.savedResults.length, { timeout: 30_000 }).toBe(1)
  expect(state.savedResults[0].mode).toBe('多目标')
  expect(pageErrors).toEqual([])
})

test('changing well selection creates a new task with a platform-suffixed name instead of the occupied one', async ({ page }) => {
  test.setTimeout(90_000)
  const state = await installMocks(page)
  const pageErrors = []
  page.on('pageerror', error => pageErrors.push(error.message))

  await page.goto(pageUrl('单目标'))
  await expect(page.locator('.panel-status')).toContainText('当前还没有本模式的预测记录', { timeout: 30_000 })
  await (await expandModeDirectory(page, '单目标')).click()
  await expect(page.locator('.panel-status')).toContainText('已新建预测', { timeout: 30_000 })
  expect(state.createdPayloads).toHaveLength(1)
  const firstTaskName = state.createdPayloads[0].dynamicPredictionName

  // 取消一口井：提示将创建新任务；启动预测时必须用平台顺延名称，不能复用已占用的名称。
  await page.locator('.storage-well-list .record-row', { hasText: 'X-3' }).locator('input').uncheck()
  await expect(page.getByText(/井选择已改变/)).toBeVisible()

  await page.getByRole('button', { name: '启动预测' }).click()
  await expect(page.locator('.panel-status')).toContainText('预测完成', { timeout: 30_000 })

  expect(state.createdPayloads).toHaveLength(2)
  expect(state.createdPayloads[1].dynamicPredictionName).not.toBe(firstTaskName)
  expect(state.createdPayloads[1].dynamicPredictionName).toMatch(/^井组动态预测\d+$/)
  expect([...state.createdPayloads[1].wellNames].sort()).toEqual(['X-1', 'X-2'])
  // 新任务完成计算并出图。
  expect(state.calcPayloads).toHaveLength(1)
  expect(state.calcPayloads[0].dynamicPredictionId).toBe(predictionTaskId + 1)
  await expect(page.locator('.coupling-chart canvas')).toBeVisible()
  expect(pageErrors).toEqual([])
})

test('a saved record of the mode is reused on entry instead of creating a duplicate', async ({ page }) => {
  test.setTimeout(90_000)
  // 留存表里已有一条单目标记录（只含 X-1、X-2 两口井）。
  const state = await installMocks(page, {
    savedRecords: [savedRecord(predictionTaskId, savedRecordName, '单目标')],
    taskWells: { [predictionTaskId]: ['X-1', 'X-2'] }
  })
  const pageErrors = []
  page.on('pageerror', error => pageErrors.push(error.message))

  await page.goto(pageUrl('单目标'))
  await expect(page.locator('.panel-status')).toContainText('已载入本模式最近的预测记录', { timeout: 30_000 })
  // 不创建任何新任务；名称显示为留存记录名；井勾选同步为任务的井集。
  expect(state.createdPayloads).toHaveLength(0)
  await expect(page.locator('.field', { hasText: '预测名称' }).locator('input')).toHaveValue(savedRecordName)
  await expect(page.getByText('参与预测的井（2）')).toBeVisible()
  await expect(page.locator('.field', { hasText: '目标产量 (10⁴m³/d)' }).locator('input')).toHaveValue('8')

  // 直接启动预测：复用留存记录对应的平台任务。
  await page.getByRole('button', { name: '启动预测' }).click()
  await expect(page.locator('.panel-status')).toContainText('预测完成', { timeout: 30_000 })
  expect(state.calcPayloads).toHaveLength(1)
  expect(state.calcPayloads[0].dynamicPredictionId).toBe(predictionTaskId)
  expect(state.createdPayloads).toHaveLength(0)
  await expect.poll(() => state.savedResults.length, { timeout: 30_000 }).toBe(1)
  expect(pageErrors).toEqual([])
})

test('saved records live under the mode directory and open with their existing chart', async ({ page }) => {
  test.setTimeout(120_000)
  // 任务 55 已有计算结果并已留存；记录打开时显示平台已有曲线。
  const state = await installMocks(page, {
    savedRecords: [savedRecord(predictionTaskId, savedRecordName, '单目标')],
    existingChartTasks: [predictionTaskId]
  })
  const pageErrors = []
  page.on('pageerror', error => pageErrors.push(error.message))

  // 1. 从记录直达地址打开：载入参数并显示平台上已有结果，不触发任何创建/计算。
  await page.goto(`${pageUrl('单目标')}&predictionId=${predictionTaskId}`)
  await expect(page.locator('.panel-status')).toContainText('已载入预测记录', { timeout: 30_000 })
  await expect(page.locator('.panel-status')).toContainText('已有结果已带出')
  await expect(page.locator('.coupling-chart')).toHaveAttribute('aria-label', '预测曲线：气产量、累产气量')
  expect(state.createdPayloads).toHaveLength(0)
  expect(state.calcPayloads).toHaveLength(0)
  // 目录树自动展开到该记录（记录来自留存表）。
  await expect(page.locator('.workspace-side-tree .node-text', { hasText: savedRecordName })).toBeVisible()

  // 2. 改井选择后启动：新任务（平台顺延名称）留存成功后立即插入单目标目录。
  await page.locator('.storage-well-list .record-row', { hasText: 'X-3' }).locator('input').uncheck()
  await page.getByRole('button', { name: '启动预测' }).click()
  await expect(page.locator('.panel-status')).toContainText('预测完成', { timeout: 30_000 })
  expect(state.createdPayloads).toHaveLength(1)
  expect([...state.createdPayloads[0].wellNames].sort()).toEqual(['X-1', 'X-2'])
  await expect.poll(() => state.savedResults.length, { timeout: 30_000 }).toBe(1)
  const newName = state.createdPayloads[0].dynamicPredictionName
  await expect(page.locator('.workspace-side-tree .node-text', { hasText: newName })).toBeVisible()
  expect(pageErrors).toEqual([])
})

test('the new prediction action node creates the next platform-suffixed record without touching the current one', async ({ page }) => {
  test.setTimeout(90_000)
  const state = await installMocks(page)
  const pageErrors = []
  page.on('pageerror', error => pageErrors.push(error.message))

  await page.goto(pageUrl('单目标'))
  const nameInput = page.locator('.field', { hasText: '预测名称' }).locator('input')
  await expect(page.locator('.panel-status')).toContainText('当前还没有本模式的预测记录', { timeout: 30_000 })

  // 展开目录 → 新建第一条（尚未留存，不进记录列表）。
  await (await expandModeDirectory(page, '单目标')).click()
  await expect(page.locator('.panel-status')).toContainText('已新建预测', { timeout: 30_000 })
  expect(state.createdPayloads).toHaveLength(1)
  const firstName = state.createdPayloads[0].dynamicPredictionName
  expect(firstName).toMatch(/^井组动态预测\d+$/)
  await expect(nameInput).toHaveValue(firstName)
  // 只有"启动预测并留存成功"之后才会出现在目录里。
  await expect(page.locator('.workspace-side-tree .node-text', { hasText: firstName })).toHaveCount(0)

  // 连续新建：继续顺延为下一条任务。
  await page.locator('.workspace-side-tree .node-text', { hasText: '新建预测' }).click()
  await expect.poll(() => state.createdPayloads.length, { timeout: 30_000 }).toBe(2)
  expect(state.createdPayloads[1].dynamicPredictionName).not.toBe(firstName)
  await expect(nameInput).toHaveValue(state.createdPayloads[1].dynamicPredictionName)
  expect(pageErrors).toEqual([])
})

test('a saved record can be deleted via the context menu', async ({ page }) => {
  test.setTimeout(90_000)
  const state = await installMocks(page, {
    savedRecords: [savedRecord(predictionTaskId, savedRecordName, '单目标')]
  })
  const pageErrors = []
  page.on('pageerror', error => pageErrors.push(error.message))

  await page.goto(pageUrl('单目标'))
  await expect(page.locator('.panel-status')).toContainText('已载入本模式最近的预测记录', { timeout: 30_000 })

  // 展开目录并右键记录 → 菜单出现"删除预测记录"。
  await page.locator('.workspace-side-tree .node-text', { hasText: '单目标' }).click()
  const recordNode = page.locator('.workspace-side-tree .node-text', { hasText: savedRecordName })
  await expect(recordNode).toBeVisible()
  await recordNode.click({ button: 'right' })
  const menuItem = page.locator('.tree-context-menu-item', { hasText: '删除预测记录' })
  await expect(menuItem).toBeVisible()
  await menuItem.click()

  // 确认弹窗（明确说明将同时删除平台任务与本地留存）。
  await expect(page.locator('.el-message-box')).toContainText('删除平台上的该预测任务')
  await page.locator('.el-message-box__btns button', { hasText: '删除' }).click()

  // 先删平台任务、再删本地留存，目录里记录消失。
  await expect.poll(() => state.deletedPlatformTasks.length, { timeout: 30_000 }).toBe(1)
  expect(state.deletedPlatformTasks[0]).toMatchObject({ projectId: 1, gasReservoirId: 2, predictionId: predictionTaskId })
  await expect.poll(() => state.deletedRecords.length, { timeout: 30_000 }).toBe(1)
  expect(state.deletedRecords[0]).toBe(predictionTaskId)
  await expect(recordNode).toHaveCount(0)
  expect(pageErrors).toEqual([])
})
