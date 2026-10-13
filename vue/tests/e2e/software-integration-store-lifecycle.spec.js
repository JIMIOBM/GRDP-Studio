import { expect, test } from '@playwright/test'

// Load the production Pinia store and API in Vite; only the HTTP backend is controlled.
const run = (id, status = 'PREPARING', modelVersionId = 101) => ({
  id, projectId: 1, modelId: 11, modelVersionId, status,
  study: 'Base Case', runType: 'nodal', elapsedMillis: 1000,
  cancellable: !['FAILED', 'SUCCEEDED', 'CANCELLED'].includes(status), events: [], artifacts: []
})
const deferred = () => {
  let resolve
  const promise = new Promise(done => { resolve = done })
  return { promise, resolve }
}

async function setup(page) {
  const state = {
    runs: new Map([[1, run(1, 'FAILED')], [2, run(2)]]),
    histories: new Map([[101, []], [102, []]]), posts: [], reads: [],
    postGate: null, detailGate: null, historyGate: null, cancelGate: null,
    failDetails: 0, failPost: false, failCancel: false, failHistory: false
  }
  await page.route('**/store-lifecycle-test', route => route.fulfill({ contentType: 'text/html', body: '<html><body></body></html>' }))
  await page.route('**/api/software-integration/**', async route => {
    const path = new URL(route.request().url()).pathname.replace('/api/software-integration', '')
    const method = route.request().method()
    const fulfill = (data, status = 200) => route.fulfill({ status, json: { code: status, data } })
    if (path === '/capabilities') return fulfill({
      worker: { status: 'AVAILABLE', idle: true },
      pipesimWell: { status: 'AVAILABLE', runTasks: ['nodal'] }
    })
    if (/\/model-versions\/\d+\/runs$/.test(path) && method === 'GET') {
      const history = structuredClone(state.histories.get(Number(path.split('/')[2])) || [])
      if (state.historyGate) await state.historyGate.promise
      if (state.failHistory) return fulfill(null, 503)
      return fulfill(history)
    }
    if (method === 'POST') {
      state.posts.push(path)
      if (path.endsWith('/cancel')) {
        if (state.cancelGate) await state.cancelGate.promise
        if (state.failCancel) return fulfill(null, 503)
        state.runs.set(2, run(2, 'CANCEL_REQUESTED'))
        return fulfill(state.runs.get(2), 202)
      }
      if (state.postGate) await state.postGate.promise
      if (state.failPost) return fulfill(null, 503)
      state.histories.set(101, [run(2), run(1, 'FAILED')])
      return fulfill(run(2), 202)
    }
    const id = Number(path.split('/')[2])
    state.reads.push(id)
    const snapshot = structuredClone(state.runs.get(id))
    const gate = state.detailGate
    if (gate?.id === id) {
      state.detailGate = null
      await gate.promise
    }
    if (id === 2 && state.failDetails > 0) {
      state.failDetails -= 1
      return fulfill(null, 503)
    }
    return fulfill(snapshot)
  })
  await page.goto('/store-lifecycle-test')
  await page.clock.install()
  await page.evaluate(async () => {
    const { createPinia } = await import('/node_modules/pinia/dist/pinia.mjs')
    const { useSoftwareIntegrationStore } = await import('/src/stores/softwareIntegration.js')
    window.store = useSoftwareIntegrationStore(createPinia())
    window.store.projectDetails = { 1: { project: { id: 1 }, models: [{ id: 11, versions: [101, 102].map(id => ({
      id, versionNo: id, status: 'READY', modelKind: 'black_oil_liquid', studies: ['Base Case']
    })) }] } }
    window.store.activeProjectId = 1
    window.store.activeModelId = 11
    await window.store.selectVersion(101)
    await window.store.loadCapabilities()
  })
  return state
}

const snapshot = page => page.evaluate(() => ({
  active: window.store.activeRun?.id ?? null, status: window.store.activeRun?.status ?? null,
  selected: window.store.selectedRun?.id ?? null, selectedStatus: window.store.selectedRun?.status ?? null,
  busy: window.store.workerBusy, submitting: window.store.submittingRun,
  unavailable: window.store.runPollingUnavailable, version: window.store.activeVersionId,
  history: window.store.runHistory.map(item => ({ id: item.id, status: item.status }))
}))
const call = (page, name, argument) => page.evaluate(async ({ name, argument }) => {
  try { const value = await window.store[name](argument); return { id: value?.id ?? null } }
  catch (error) { return { error: error.message || 'HTTP failure' } }
}, { name, argument })
const start = (page, name, argument) => page.evaluate(({ name, argument }) => {
  window.pending = window.store[name](argument).then(value => ({ id: value?.id ?? null }), error => ({ error: error.message || 'HTTP failure' }))
}, { name, argument })

test.afterEach(async ({ page }) => { await page.evaluate(() => window.store?.cleanup()) })

test('历史展示保留活动跟踪，终态不覆盖历史选择', async ({ page }) => {
  const state = await setup(page)
  await call(page, 'createRun')
  await call(page, 'selectRun', 1)
  expect(await snapshot(page)).toMatchObject({ active: 2, selected: 1, busy: true })
  await call(page, 'loadRunHistory')
  expect(await snapshot(page)).toMatchObject({ active: 2, selected: 1 })
  state.runs.set(2, run(2, 'SUCCEEDED'))
  state.histories.set(101, [run(2, 'SUCCEEDED'), run(1, 'FAILED')])
  await page.clock.runFor(1600)
  await expect.poll(() => snapshot(page)).toMatchObject({ active: null, selected: 1, busy: false })
  const reads = state.reads.length
  await page.clock.runFor(5000)
  expect(state.reads).toHaveLength(reads)
})

for (const entry of ['createRun', 'retryRun']) {
  test(`${entry} 已受理后 GET 失败仍忙碌并由轮询恢复，禁止重复 POST`, async ({ page }) => {
    const state = await setup(page)
    await call(page, 'selectRun', 1)
    state.failDetails = 1
    expect(await call(page, entry)).toEqual({ id: 2 })
    expect(await snapshot(page)).toMatchObject({ active: 2, selected: 2, busy: true, submitting: false })
    expect(await call(page, 'createRun')).toHaveProperty('error')
    await call(page, 'selectRun', 1)
    expect(await call(page, 'retryRun')).toHaveProperty('error')
    expect(state.posts).toHaveLength(1)
    await page.clock.runFor(1600)
    await expect.poll(() => state.reads.filter(id => id === 2).length).toBe(2)
    expect(await snapshot(page)).toMatchObject({ active: 2, selected: 1 })
  })

  test(`${entry} 的 POST 和详情延迟期间两入口共享互斥`, async ({ page }) => {
    const state = await setup(page)
    await call(page, 'selectRun', 1)
    state.postGate = deferred()
    state.detailGate = { id: 2, ...deferred() }
    const detailGate = state.detailGate
    await start(page, entry)
    await expect.poll(() => state.posts.length).toBe(1)
    for (const name of ['createRun', 'retryRun']) expect(await call(page, name)).toHaveProperty('error')
    expect(await snapshot(page)).toMatchObject({ submitting: true })
    state.postGate.resolve()
    await expect.poll(() => snapshot(page)).toMatchObject({ active: 2, selected: 2, busy: true, submitting: true })
    await call(page, 'selectRun', 1)
    for (const name of ['createRun', 'retryRun']) expect(await call(page, name)).toHaveProperty('error')
    detailGate.resolve()
    await page.evaluate(() => window.pending)
    expect(await snapshot(page)).toMatchObject({ active: 2, selected: 1, submitting: false })
    expect(state.posts).toHaveLength(1)
  })

  test(`${entry} POST 延迟时切导航，受理后不恢复旧页面，返回版本恢复任务`, async ({ page }) => {
    const state = await setup(page)
    await call(page, 'selectRun', 1)
    state.postGate = deferred()
    await start(page, entry)
    await expect.poll(() => state.posts.length).toBe(1)
    await call(page, 'selectVersion', 102)
    state.postGate.resolve()
    expect(await page.evaluate(() => window.pending)).toEqual({ id: null })
    expect(await snapshot(page)).toMatchObject({ version: 102, active: null, selected: null, busy: true })
    await call(page, 'loadCapabilities')
    expect(await call(page, 'createRun')).toHaveProperty('error')
    await call(page, 'selectVersion', 101)
    expect(await snapshot(page)).toMatchObject({ active: 2, selected: 2 })
    expect(state.posts).toHaveLength(1)
  })
}

test('已受理详情延迟跨导航，旧详情不得恢复状态或轮询', async ({ page }) => {
  const state = await setup(page)
  const gate = state.detailGate = { id: 2, ...deferred() }
  await start(page, 'createRun')
  await expect.poll(() => state.reads.includes(2)).toBe(true)
  await call(page, 'selectVersion', 102)
  gate.resolve()
  await page.evaluate(() => window.pending)
  const reads = state.reads.length
  await page.clock.runFor(5000)
  expect(await snapshot(page)).toMatchObject({ version: 102, active: null, selected: null })
  expect(state.reads).toHaveLength(reads)
})

test('连续轮询失败保持活动锁，重新选择活动 Run 即使 GET 失败也恢复轮询', async ({ page }) => {
  const state = await setup(page)
  state.failDetails = 4
  await call(page, 'createRun')
  for (let index = 0; index < 3; index += 1) {
    await page.clock.runFor(index === 0 ? 1600 : 2100)
    await expect.poll(() => state.failDetails).toBe(2 - index)
  }
  expect(await snapshot(page)).toMatchObject({ active: 2, busy: true, unavailable: true })
  await call(page, 'selectRun', 1)
  expect(await snapshot(page)).toMatchObject({ active: 2, selected: 1, unavailable: true })
  state.failDetails = 1
  expect(await call(page, 'selectRun', 2)).toHaveProperty('error')
  const reads = state.reads.length
  await page.clock.runFor(1600)
  await expect.poll(() => state.reads.length).toBe(reads + 1)
  expect(await snapshot(page)).toMatchObject({ active: 2, selected: 2, unavailable: false })
})

test('取消摘要立即更新跟踪，详情失败与历史选择不影响取消终态', async ({ page }) => {
  const state = await setup(page)
  await call(page, 'createRun')
  state.cancelGate = deferred()
  await start(page, 'cancelRun')
  await expect.poll(() => state.posts.length).toBe(2)
  await call(page, 'selectRun', 1)
  state.failDetails = 1
  state.cancelGate.resolve()
  await page.evaluate(() => window.pending)
  expect(await snapshot(page)).toMatchObject({ active: 2, status: 'CANCEL_REQUESTED', selected: 1 })
  state.runs.set(2, run(2, 'CANCELLED'))
  state.histories.set(101, [run(2, 'CANCELLED'), run(1, 'FAILED')])
  await page.clock.runFor(1600)
  await expect.poll(() => snapshot(page)).toMatchObject({ active: null, selected: 1, busy: false })
})

test('取消 POST 失败恢复轮询，延迟旧轮询不得回退取消状态', async ({ page }) => {
  const state = await setup(page)
  await call(page, 'createRun')
  state.failCancel = true
  expect(await call(page, 'cancelRun')).toHaveProperty('error')
  const gate = state.detailGate = { id: 2, ...deferred() }
  const reads = state.reads.length
  await page.clock.runFor(1600)
  await expect.poll(() => state.reads.length).toBe(reads + 1)
  state.failCancel = false
  await call(page, 'cancelRun')
  gate.resolve()
  await expect.poll(() => snapshot(page)).toMatchObject({ status: 'CANCEL_REQUESTED' })
})

test('真正 POST 失败释放互斥，允许用户重新提交', async ({ page }) => {
  const state = await setup(page)
  state.failPost = true
  expect(await call(page, 'createRun')).toHaveProperty('error')
  expect(await snapshot(page)).toMatchObject({ busy: false, submitting: false, active: null })
  state.failPost = false
  expect(await call(page, 'createRun')).toEqual({ id: 2 })
  expect(state.posts).toHaveLength(2)
})

test('POST 尚未返回时选择历史不丢失已受理活动跟踪', async ({ page }) => {
  const state = await setup(page)
  state.postGate = deferred()
  await start(page, 'createRun')
  await expect.poll(() => state.posts.length).toBe(1)
  await call(page, 'selectRun', 1)
  state.postGate.resolve()
  await page.evaluate(() => window.pending)
  expect(await snapshot(page)).toMatchObject({ active: 2, selected: 1, busy: true })
})

test('延迟历史选择期间活动完成，仍显示用户选择的历史', async ({ page }) => {
  const state = await setup(page)
  await call(page, 'createRun')
  const gate = state.detailGate = { id: 1, ...deferred() }
  await start(page, 'selectRun', 1)
  await expect.poll(() => state.detailGate).toBe(null)
  state.runs.set(2, run(2, 'SUCCEEDED'))
  state.histories.set(101, [run(2, 'SUCCEEDED'), run(1, 'FAILED')])
  await page.clock.runFor(1600)
  await expect.poll(() => snapshot(page)).toMatchObject({ active: null })
  gate.resolve()
  await page.evaluate(() => window.pending)
  expect(await snapshot(page)).toMatchObject({ selected: 1, active: null })
})

test('陈旧活动选择详情不能回退取消状态或复活已完成任务', async ({ page }) => {
  const state = await setup(page)
  await call(page, 'createRun')
  const gate = state.detailGate = { id: 2, ...deferred() }
  await start(page, 'selectRun', 2)
  await expect.poll(() => state.detailGate).toBe(null)
  await call(page, 'cancelRun')
  gate.resolve()
  await page.evaluate(() => window.pending)
  expect(await snapshot(page)).toMatchObject({ status: 'CANCEL_REQUESTED', selectedStatus: 'CANCEL_REQUESTED' })
  const nextGate = state.detailGate = { id: 2, ...deferred() }
  await start(page, 'selectRun', 2)
  await expect.poll(() => state.detailGate).toBe(null)
  state.runs.set(2, run(2, 'CANCELLED'))
  state.histories.set(101, [run(2, 'CANCELLED')])
  await page.clock.runFor(1600)
  await expect.poll(() => snapshot(page)).toMatchObject({ active: null, selectedStatus: 'CANCELLED' })
  nextGate.resolve()
  await page.evaluate(() => window.pending)
  expect(await snapshot(page)).toMatchObject({ active: null, selectedStatus: 'CANCELLED' })
})

test('终态历史刷新失败仍刷新 Worker 能力并释放受理锁', async ({ page }) => {
  const state = await setup(page)
  await call(page, 'createRun')
  state.runs.set(2, run(2, 'SUCCEEDED'))
  state.failHistory = true
  await page.clock.runFor(1600)
  await expect.poll(() => snapshot(page)).toMatchObject({ active: null, busy: false, selectedStatus: 'SUCCEEDED' })
})

test('受理前开始的空历史响应不能清除新活动任务', async ({ page }) => {
  const state = await setup(page)
  state.historyGate = deferred()
  await start(page, 'loadRunHistory')
  await expect.poll(() => page.evaluate(() => window.store.loadingHistory)).toBe(true)
  await call(page, 'createRun')
  state.historyGate.resolve()
  await page.evaluate(() => window.pending)
  expect(await snapshot(page)).toMatchObject({ active: 2, selected: 2, history: [{ id: 2, status: 'PREPARING' }] })
})

test('取消 POST 延迟期间重新选择活动任务仍接收取消摘要', async ({ page }) => {
  const state = await setup(page)
  await call(page, 'createRun')
  state.cancelGate = deferred()
  await start(page, 'cancelRun')
  await expect.poll(() => state.posts.length).toBe(2)
  await call(page, 'selectRun', 2)
  state.failDetails = 1
  state.cancelGate.resolve()
  await page.evaluate(() => window.pending)
  expect(await snapshot(page)).toMatchObject({ status: 'CANCEL_REQUESTED', selectedStatus: 'CANCEL_REQUESTED', busy: true })
})

test('取消 POST 延迟跨导航不恢复旧任务', async ({ page }) => {
  const state = await setup(page)
  await call(page, 'createRun')
  state.cancelGate = deferred()
  await start(page, 'cancelRun')
  await expect.poll(() => state.posts.length).toBe(2)
  await call(page, 'selectVersion', 102)
  state.cancelGate.resolve()
  await page.evaluate(() => window.pending)
  const reads = state.reads.length
  await page.clock.runFor(5000)
  expect(await snapshot(page)).toMatchObject({ active: null, selected: null, version: 102 })
  expect(state.reads).toHaveLength(reads)
})

test('首次详情已终态仍刷新历史并释放忙状态', async ({ page }) => {
  const state = await setup(page)
  state.runs.set(2, run(2, 'SUCCEEDED'))
  expect(await call(page, 'createRun')).toEqual({ id: 2 })
  await expect.poll(() => snapshot(page)).toMatchObject({ active: null, busy: false, selectedStatus: 'SUCCEEDED' })
  const reads = state.reads.length
  await page.clock.runFor(5000)
  expect(state.reads).toHaveLength(reads)
})

test('导航离开期间已受理任务完成，返回版本的终态历史释放忙状态', async ({ page }) => {
  const state = await setup(page)
  await call(page, 'createRun')
  await call(page, 'selectVersion', 102)
  state.runs.set(2, run(2, 'SUCCEEDED'))
  state.histories.set(101, [run(2, 'SUCCEEDED')])
  await call(page, 'selectVersion', 101)
  await expect.poll(() => snapshot(page)).toMatchObject({ active: null, busy: false, selectedStatus: 'SUCCEEDED' })
})
