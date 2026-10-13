import { expect, test } from '@playwright/test'

// Synthetic HTTP-boundary fixtures test the production UI, not commercial calculation.
const project = { id: 1, name: '系统分析浏览器夹具（非原生）' }
const version = (id, versionNo, wells, branches) => ({ id, versionNo, status: 'READY', modelKind: 'network',
  originalName: 'ui-fixture.pips', studies: ['Study 1', 'Study 2'], inspection: {
    schemaVersion: 'pipesim-network-inspection/5', wellContexts: wells, flowlineContexts: branches,
    flowlines: [], chokes: [], packageFiles: [], studies: ['Study 1', 'Study 2'].map(study => ({ study, boundaries: [] }))
  } })
const versions = [version(301, 1, ['Well_1', 'Well_2'], ['Flowline_1', 'Flowline_2']),
  version(302, 2, ['Well_3'], ['Flowline_3'])]
const model = { id: 33, name: '系统分析夹具网络', versions }
const response = data => ({ code: 200, msg: 'success', data })
const completed = (id, versionId, study, parameters) => ({ id, projectId: 1, modelId: 33, modelName: model.name,
  modelVersionId: versionId, versionNo: versionId === 301 ? 1 : 2, study, parameters, runType: 'system-analysis',
  status: 'SUCCEEDED', resultContract: 'VALID_FULL', cancellable: false, events: [], artifacts: [], elapsedMillis: 1000,
  createdAt: '2026-10-08T12:00:00', result: { schemaVersion: `pipesim-system-analysis-result/${parameters ? 2 : 1}`,
    model_kind: 'network', runTask: 'system-analysis', resultContract: 'VALID_FULL', study,
    producer: parameters?.producer || 'Well', branchTerminator: parameters?.branchTerminator || 'Flowline_1',
    outletPressurePsi: parameters?.outletPressurePsi || 600, scanVariable: 'liquidFlowRate',
    cases: (parameters?.values || [2400, 3000]).map(scanValue => ({ caseName: 'UI boundary fixture', scanValue,
      system: [{ variable: 'BottomHolePressure', unit: 'psia', value: 1200 }],
      node: [{ node: parameters?.producer || 'Well', variables: [{ variable: 'Pressure', unit: 'psia', value: 1200 }] }],
      profile: { pointCount: 2, variables: [
        { variable: 'TotalDistance', unit: parameters ? 'ft' : null, values: [0, 100] },
        { variable: 'Pressure', unit: parameters ? 'psia' : null, values: [1200, 600] },
        { variable: 'Temperature', unit: parameters ? 'degF' : null, values: [150, 120] }
      ] }
    })) } })

async function setup(page, { legacy = false, delayed = false } = {}) {
  const state = { posts: [], unexpected: [], errors: [], runs: new Map(), histories: new Map([[301, []], [302, []]]), release: null }
  if (legacy || delayed) {
    const run = completed(901, 301, 'Study 1', null)
    state.runs.set(run.id, run); state.histories.set(301, [run])
  }
  page.on('pageerror', error => state.errors.push(error.message))
  await page.addInitScript(() => localStorage.setItem('account', JSON.stringify({ token: 'e2e-boundary-token', username: 'e2e' })))
  await page.route('**/api/software-integration/**', async route => {
    const path = new URL(route.request().url()).pathname.replace(/^\/api/, '')
    const method = route.request().method()
    if (path === '/software-integration/environment') return route.fulfill({ json: response({
      host: 'test-worker', configured: true, configuredSimulators: ['pipesim'], paths: {}, candidates: {},
      pipesim: { status: 'READY' }, eclipse: { status: 'MISSING' }
    }) })
    if (path.endsWith('/capabilities')) return route.fulfill({ json: response({ worker: { status: 'AVAILABLE', idle: true },
      pipesimNetwork: { status: 'AVAILABLE', version: '2022.1', runTasks: ['network', 'system-analysis'], maxTimeoutSeconds: 600 },
      pipesimWell: { status: 'AVAILABLE', runTasks: [] }, eclipse100: { status: 'AVAILABLE', runTasks: [] } }) })
    if (path === '/software-integration/projects') return route.fulfill({ json: response([project]) })
    if (path === '/software-integration/projects/1') return route.fulfill({ json: response({ project, models: [model] }) })
    const history = path.match(/\/model-versions\/(\d+)\/runs$/)
    if (history && method === 'GET') return route.fulfill({ json: response(state.histories.get(Number(history[1])) || []) })
    if (history && method === 'POST') {
      const versionId = Number(history[1]), payload = route.request().postDataJSON()
      state.posts.push({ versionId, payload })
      const run = completed(1000 + state.posts.length, versionId, payload.study, payload.parameters)
      state.runs.set(run.id, run); state.histories.set(versionId, [run])
      return route.fulfill({ status: 202, json: { code: 202, msg: 'accepted', data: run } })
    }
    const detail = path.match(/\/runs\/(\d+)$/)
    if (detail && method === 'GET') {
      const id = Number(detail[1])
      if (delayed && id === 901) { await new Promise(resolve => { state.release = resolve }); delayed = false }
      return route.fulfill({ json: response(state.runs.get(id)) })
    }
    state.unexpected.push(`${method} ${path}`)
    return route.fulfill({ status: 500, json: { code: 500, msg: 'unexpected test route', data: null } })
  })
  await page.goto('/ipr?workspace=software-integration')
  await page.getByText(model.name, { exact: true }).first().dblclick()
  await expect(page.getByRole('heading', { name: model.name, exact: true })).toBeVisible()
  // The production store selects newest READY; this suite's multi-well/history fixture is v1.
  await page.locator('.run-controls .el-select').first().click()
  await page.getByRole('option', { name: 'v1 · READY', exact: true }).click()
  await expect(page.locator('.run-controls .el-select').first()).toContainText('v1 · READY')
  await page.locator('.run-type-control').getByText('系统分析', { exact: true }).click()
  await expect(page.getByRole('radio', { name: '系统分析', exact: true })).toBeChecked()
  return state
}

async function choose(page, name, value) {
  await selectField(page, name).click()
  await page.getByRole('option', { name: value, exact: true }).click()
}

function selectField(page, name) {
  // Element Plus renders selected text outside its readonly search input.
  return page.locator('.el-select').filter({ has: page.getByRole('combobox', { name, exact: true }) })
}

test('invalid fields send no POST; corrected real well submits one /2 Run and renders its units', async ({ page }, testInfo) => {
  const state = await setup(page)
  await page.getByRole('button', { name: '运行', exact: true }).click()
  expect(state.posts).toHaveLength(0)
  await expect(page.getByRole('region', { name: 'PIPESIM 系统分析工况' }).getByRole('alert')).toHaveCount(2)
  await expect(page.getByTestId('run-feedback')).toHaveCount(1)
  await expect(page.locator('.el-message')).toHaveCount(0)
  await choose(page, '系统分析井', 'Well_2')
  await choose(page, '系统分析分支终点', 'Flowline_2')
  await page.getByRole('textbox', { name: '系统分析液体流量扫描值' }).fill('1,1')
  await page.getByRole('button', { name: '运行', exact: true }).click()
  expect(state.posts).toHaveLength(0)
  await page.getByRole('textbox', { name: '系统分析液体流量扫描值' }).fill('2400,3000,3600')
  await expect(page.getByRole('region', { name: 'PIPESIM 系统分析工况' }).getByRole('alert')).toHaveCount(0)
  await page.getByRole('button', { name: '运行', exact: true }).click()
  await expect(page.getByRole('region', { name: 'PIPESIM 系统分析结果' })).toContainText('井：Well_2')
  expect(state.posts).toEqual([{ versionId: 301, payload: { study: 'Study 1', runType: 'system-analysis', parameters: {
    schemaVersion: 'pipesim-system-analysis-parameters/2', producer: 'Well_2', branchTerminator: 'Flowline_2',
    outletPressurePsi: 600, scanVariable: 'liquidFlowRate', values: [2400, 3000, 3600]
  } } }])
  await expect(page.getByRole('region', { name: 'PIPESIM 系统分析结果' })).toContainText('3 个工况')
  expect(state.errors).toEqual([]); expect(state.unexpected).toEqual([])
  await page.screenshot({ path: testInfo.outputPath('system-analysis-main-ui-fixture.png'), fullPage: true })
})

test('Study and version switches clear old selections and only a unique real well is preselected', async ({ page }) => {
  const state = await setup(page)
  await choose(page, '系统分析井', 'Well_2'); await choose(page, '系统分析分支终点', 'Flowline_2')
  await page.locator('.run-controls .el-select').nth(1).click()
  await page.getByRole('option', { name: 'Study 2', exact: true }).click()
  await expect(selectField(page, '系统分析井')).toContainText('选择实际井')
  await expect(selectField(page, '系统分析井')).not.toContainText('Well_2')
  await expect(selectField(page, '系统分析分支终点')).toContainText('选择 Flowline')
  await expect(selectField(page, '系统分析分支终点')).not.toContainText('Flowline_2')
  await page.locator('.run-controls .el-select').first().click()
  await page.getByRole('option', { name: 'v2 · READY', exact: true }).click()
  await expect(selectField(page, '系统分析井')).toContainText('Well_3')
  await expect(selectField(page, '系统分析分支终点')).toContainText('Flowline_3')
  expect(state.posts).toHaveLength(0); expect(state.errors).toEqual([])
})

test('historical /1 result remains visible without inventing profile units', async ({ page }) => {
  const state = await setup(page, { legacy: true })
  await expect(page.getByRole('region', { name: 'PIPESIM 系统分析结果' })).toContainText('井：Well')
  await expect(page.getByRole('region', { name: 'PIPESIM 系统分析结果' })).toContainText('2 个工况')
  expect(state.posts).toHaveLength(0); expect(state.errors).toEqual([])
})

test('late old-version history detail cannot populate the new version result', async ({ page }) => {
  const state = await setup(page, { delayed: true })
  await expect.poll(() => typeof state.release).toBe('function')
  await page.locator('.run-controls .el-select').first().click()
  await page.getByRole('option', { name: 'v2 · READY', exact: true }).click()
  state.release()
  await expect(selectField(page, '系统分析井')).toContainText('Well_3')
  await expect(page.getByRole('region', { name: 'PIPESIM 系统分析结果' })).not.toContainText('井：Well ·')
  await expect(page.getByRole('region', { name: 'PIPESIM 系统分析结果' })).toContainText('尚未选择成功的系统分析运行')
  expect(state.posts).toHaveLength(0); expect(state.errors).toEqual([])
})
