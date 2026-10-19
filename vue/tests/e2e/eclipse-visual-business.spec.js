import { expect, test } from '@playwright/test'
import { historyBatchComparison } from '../../src/views/SoftwareIntegration/eclipseHistoryBatch.js'
import { historyIdentity } from '../../src/views/SoftwareIntegration/eclipseHistory.js'
import { formatGridValue } from '../../src/views/SoftwareIntegration/eclipseGridPresentation.js'
import { batchResponse } from '../../src/views/SoftwareIntegration/eclipseBatch.js'

const base = process.env.GRDP_VISUAL_BASE_URL?.replace(/\/$/, '')
let source
async function get(request, path) {
  const response = await request.get(base + '/software-integration' + path)
  expect(response.status()).toBe(200)
  const body = await response.json(); expect(body.code).toBe(200); return body.data
}
test.beforeAll(async ({ request }) => {
  if (!base) return
  const [baseline, rate, temperature, compositionalBaseline, compositional, project, batch, dataset, compositionalProject] = await Promise.all([
    get(request, '/runs/1824'), get(request, '/runs/1825'), get(request, '/runs/1827'), get(request, '/runs/1808'), get(request, '/runs/1814'), get(request, '/projects/324'), get(request, '/eclipse-batches/28'), get(request, '/eclipse-history-datasets/28'), get(request, '/projects/318')])
  const evaluations = {}
  for (const run of [batch.baseline, ...batch.members.map(m => m.run)]) evaluations[run.id] = await get(request, `/eclipse-history-datasets/28/comparison?runId=${run.id}`)
  const metric = evaluations[batch.baselineRunId].comparison.metrics[0]
  const ranking = historyBatchComparison({ batch, dataset, evaluations, metricKey: historyIdentity(metric) })
  expect(ranking.canRank).toBe(true)
  const compositionalInspection = compositionalProject.models.flatMap(m => m.versions).find(v => v.id === compositionalBaseline.modelVersionId).inspection
  const batchRows = batchResponse(batch, compositionalInspection).rows.filter(row => row.timeDays === 5475)
  source = { baseline, rate, temperature, compositionalBaseline, compositional, batchRows, batch, ranking, metricLabel: `${metric.keyword} · 原始列${metric.vectorIndex + 1} · ${metric.unit}`, inspection: project.models.flatMap(m => m.versions).find(v => v.id === baseline.modelVersionId).inspection }
})
async function open(page, request, slow = false) {
  const errors = [], writes = []
  page.on('pageerror', e => errors.push(e.message))
  await page.route('**/fixtures/visual-business-source.json', route => route.fulfill({ json: source }))
  let delayed = false
  await page.route('**/api/software-integration/**', async route => {
    const url = new URL(route.request().url())
    if (route.request().method() !== 'GET' || !/^\/api\/software-integration\/runs\/(1824|1825|1827)\/artifacts\/[0-9]+\/range$/.test(url.pathname)) {
      writes.push(route.request().method() + ' ' + url.pathname); await route.abort(); return
    }
    if (slow && !delayed && url.pathname.includes('/1825/')) { delayed = true; await new Promise(resolve => setTimeout(resolve, 900)) }
    const response = await request.get(base + url.pathname.slice(4) + url.search)
    expect(response.status()).toBe(206)
    await route.fulfill({ status: 206, contentType: 'application/octet-stream', body: await response.body() })
  })
  await page.setViewportSize({ width: 1600, height: 1080 })
  await page.goto('/tests/e2e/fixtures/eclipse-visual-business.html')
  return { errors, writes }
}
// Independent native decoding for the spatial oracle; does not use the production comparison helper.
async function nativeValues(request, run, keyword, report) {
  const file = run.result.fieldIndex.files.find(f => f.name.endsWith(keyword === 'ACTNUM' ? '.EGRID' : '.UNRST'))
  const fields = file.fields.filter(f => f.keyword === keyword && f.timeStep === report)
  expect(fields.length).toBe(1)
  const field = fields[0], artifact = run.artifacts.find(a => a.name === 'eclipse-output-' + file.name), result = []
  for (const segment of field.segments) {
    const response = await request.get(`${base}/software-integration/runs/${run.id}/artifacts/${artifact.id}/range?offset=${segment.offset}&length=${segment.length}`)
    expect(response.status()).toBe(206)
    const bytes = await response.body()
    for (let i = 0; i < bytes.length; i += field.elementSize) result.push(field.dataType === 'INTE' ? bytes.readInt32BE(i) : field.dataType === 'DOUB' ? bytes.readDoubleBE(i) : bytes.readFloatBE(i))
  }
  return result
}
test('真实热采方案：指标看板、原生场逐格差值、生效前后与CSV图片导出', async ({ page, request }, testInfo) => {
  test.skip(!base, 'Explicit local API opt-in required')
  const state = await open(page, request)
  const panel = page.getByRole('region', { name: 'E300热采空间差值分析', exact: true }), dashboard = page.getByRole('region', { name: 'ECLIPSE方案效果看板', exact: true })
  await expect(panel).toHaveAttribute('data-ready', 'true', { timeout: 60000 })
  await expect(dashboard).toContainText('114,062.5'); await expect(dashboard).toContainText('136,875')
  const before = await nativeValues(request, source.baseline, 'TEMP', 10), after = await nativeValues(request, source.rate, 'TEMP', 10), mask = await nativeValues(request, source.baseline, 'ACTNUM', undefined)
  const changes = after.map((value, i) => value - before[i]), mean = changes.reduce((sum, d) => sum + d / changes.length, 0)
  await expect(panel.getByLabel('空间差值统计')).toContainText(formatGridValue(mean))
  await expect(panel).toContainText('实际第 3650 天')
  const csvWait = page.waitForEvent('download'); await panel.getByRole('button', { name: '导出逐格差值CSV', exact: true }).click()
  const csvDownload = await csvWait, csvPath = testInfo.outputPath(csvDownload.suggestedFilename()); await csvDownload.saveAs(csvPath)
  const { readFile } = await import('node:fs/promises'), lines = (await readFile(csvPath, 'utf8')).replace(/^\uFEFF/, '').split('\r\n').map(line => line.split(',').map(value => value.slice(1, -1)))
  expect(lines.length).toBe(101)
  let ordinal = 0
  for (let index = 0; index < mask.length; index++) if (mask[index] > 0) {
    const row = lines[++ordinal].map(Number)
    expect(row[0]).toBe(1824); expect(row[1]).toBe(1825); expect(row[5]).toBe(3650)
    expect(row.slice(6, 9)).toEqual([index % 9 + 1, Math.floor(index / 9) % 5 + 1, Math.floor(index / 45) + 1])
    expect(row[9]).toBe(before[ordinal - 1]); expect(row[10]).toBe(after[ordinal - 1]); expect(row[11]).toBe(changes[ordinal - 1])
  }
  await panel.locator('.spatial-controls .el-select').click(); await page.getByRole('option', { name: 'TEMP · 报告 5', exact: true }).click()
  await expect(panel).toHaveAttribute('data-ready', 'true'); await expect(panel).toContainText('实际第 1825 天')
  await expect(panel.getByLabel('空间差值统计')).toContainText('0 / 100')
  await panel.locator('.spatial-controls .el-select').click(); await page.getByRole('option', { name: 'TEMP · 报告 10', exact: true }).click()
  await expect(panel).toHaveAttribute('data-ready', 'true')
  await panel.locator('svg rect').first().click(); await expect(panel.getByLabel('空间差值选中单元')).toBeVisible()
  const pngWait = page.waitForEvent('download'); await panel.getByRole('button', { name: '导出空间对比图片', exact: true }).click()
  const png = await pngWait; await png.saveAs(testInfo.outputPath(png.suggestedFilename()))
  await panel.screenshot({ path: testInfo.outputPath('thermal-spatial-difference.png') }); await dashboard.screenshot({ path: testInfo.outputPath('thermal-scenario-dashboard.png') })
  await page.getByRole('button', { name: '降温方案1827', exact: true }).click()
  await expect(panel).toHaveAttribute('data-ready', 'true'); await expect(panel).toHaveAttribute('data-run-id', '1827')
  const colder = await nativeValues(request, source.temperature, 'TEMP', 10), colderMean = colder.reduce((sum, v, i) => sum + (v - before[i]) / colder.length, 0)
  await expect(panel.getByLabel('空间差值统计')).toContainText(formatGridValue(colderMean))
  expect(state.errors).toEqual([]); expect(state.writes).toEqual([])
  await testInfo.attach('native-comparison', { body: JSON.stringify({ baseline:1824, rate:1825, temperature:1827, cells:100, physicalTimeDays:3650, rateTemperatureMeanDifference:mean, temperatureMeanDifference:colderMean, csvCellValuesVerified:300, manualUiVerified:false }), contentType:'application/json' })
})
test('真实结果可视化：旧请求晚到不覆盖新方案、组分响应与历史排名', async ({ page, request }, testInfo) => {
  test.skip(!base, 'Explicit local API opt-in required')
  const state = await open(page, request, true)
  await page.getByRole('button', { name: '降温方案1827', exact: true }).click()
  const panel = page.getByRole('region', { name: 'E300热采空间差值分析', exact: true })
  await expect(panel).toHaveAttribute('data-ready', 'true', { timeout:60000 }); await expect(panel).toHaveAttribute('data-run-id','1827')
  await page.waitForTimeout(1200); await expect(panel).toHaveAttribute('data-run-id','1827')
  const actualBaseline = await nativeValues(request, source.baseline, 'TEMP', 10), actualTemperature = await nativeValues(request, source.temperature, 'TEMP', 10)
  const expectedMean = actualTemperature.reduce((sum, value, i) => sum + (value - actualBaseline[i]) / actualTemperature.length, 0)
  await expect(panel.getByLabel('空间差值统计')).toContainText(formatGridValue(expectedMean))
  await page.getByRole('button', { name:'组分', exact:true }).click()
  const dashboard = page.getByRole('region',{name:'ECLIPSE方案效果看板',exact:true})
  await expect(dashboard).toContainText('2,591,593'); await expect(dashboard).toContainText('2,593,602.25')
  await dashboard.screenshot({path:testInfo.outputPath('compositional-scenario-dashboard.png')})
  await page.getByRole('button',{name:'批量',exact:true}).click()
  const sensitivity=page.getByRole('region',{name:'批量方案敏感性可视化',exact:true})
  await expect(sensitivity).toContainText('7个可绘制方案')
  const actualValues=await sensitivity.locator('.batch-plot-canvas').evaluate(el=>globalThis.readVisualChartOptions(el)?.series[0].data.map(item=>item.value))
  const original=source.compositionalBaseline.result.summary.series.find(s=>s.keyword==='FOPT'&&s.vectorIndex===18).points.at(-1).value
  const expectedValues=source.batch.members.map(member=>member.run.result.summary.series.find(s=>s.keyword==='FOPT'&&s.vectorIndex===18).points.at(-1).value-original)
  expect(actualValues).toEqual(expectedValues)
  await sensitivity.screenshot({path:testInfo.outputPath('compositional-batch-sensitivity.png')})
  await page.getByRole('button',{name:'历史排名',exact:true}).click()
  const ranking=page.getByRole('region',{name:'历史拟合方案可视化排名',exact:true})
  const best=source.ranking.rows.find(row=>row.rank===1)
  expect(best.runId).toBe(1814); expect(best.metric.rmse).toBe(0)
  await expect(ranking.locator('.ranking-best')).toContainText(best.name)
  await ranking.getByRole('button',{name:new RegExp(best.name+' RMSE 0 查看方案')}).click()
  await expect(page.getByTestId('ranking-selected-run')).toHaveText('1814')
  await ranking.screenshot({path:testInfo.outputPath('history-scenario-ranking.png')})
  for(const width of [1100,760,390]){
    await page.setViewportSize({width,height:1000}); expect(await ranking.evaluate(el=>el.scrollWidth<=el.clientWidth+2)).toBe(true)
  }
  expect(state.errors).toEqual([]); expect(state.writes).toEqual([])
})
test('保留三维增强：真实网格报告播放、色标、剖切与图片导出', async ({ page, request }, testInfo) => {
  test.skip(!base, 'Explicit local API opt-in required')
  const state = await open(page, request)
  await page.getByRole('button', { name: '三维', exact: true }).click()
  const panel = page.getByRole('region', { name: 'ECLIPSE 三维网格与井定位', exact: true })
  await expect(panel).toHaveAttribute('data-render-ready', 'true', { timeout: 60000 })
  await expect(panel).toContainText('真实几何已加载 100 个活动单元')
  const ticks = await panel.locator('.display-ticks span').allTextContents()
  await panel.getByRole('button', { name: '固定当前色标', exact: true }).click()
  await panel.getByRole('button', { name: '上一个三维报告步', exact: true }).click()
  await expect(panel).toHaveAttribute('data-render-ready', 'true')
  await expect(panel.locator('.display-ticks span')).toHaveText(ticks)
  await panel.getByRole('button', { name: '解除固定色标', exact: true }).click()
  await panel.locator('.display-tools .el-select').nth(0).click()
  await page.getByRole('option', { name: 'I 剖面', exact: true }).click()
  const mask = await nativeValues(request, source.baseline, 'ACTNUM', undefined)
  const expected = mask.filter((v, i) => v > 0 && i % 9 === 0).length
  await expect(panel.getByLabel('三维原始场值统计')).toContainText(`${expected} / 100`)
  await panel.getByRole('button', { name: '俯视', exact: true }).click()
  await panel.locator('.display-tools .el-select').nth(0).click(); await page.getByRole('option', { name: '完整网格', exact: true }).click()
  await panel.getByRole('button', { name: '立体', exact: true }).click()
  await panel.getByRole('button', { name: '下一个三维报告步', exact: true }).click(); await expect(panel).toHaveAttribute('data-render-ready', 'true')
  await panel.getByRole('button', { name: '播放演变', exact: true }).click()
  await expect(panel.locator('.display-playback')).toContainText('报告步 1')
  await expect(panel).toHaveAttribute('data-render-ready', 'true')
  await panel.getByRole('button', { name: '暂停播放', exact: true }).click()
  const promise = page.waitForEvent('download'); await panel.getByRole('button', { name: '导出演示图片', exact: true }).click()
  const png = await promise; expect(png.suggestedFilename()).toMatch(/^eclipse-1825-PRESSURE-\d+\.png$/)
  await png.saveAs(testInfo.outputPath(png.suggestedFilename()))
  await panel.screenshot({ path: testInfo.outputPath('retained-grid-visualization.png') })
  await page.setViewportSize({ width:390, height:1000 }); expect(await panel.evaluate(el=>el.scrollWidth<=el.clientWidth+2)).toBe(true)
  await panel.screenshot({ path: testInfo.outputPath('retained-grid-mobile.png') })
  expect(state.errors).toEqual([]); expect(state.writes).toEqual([])
})
