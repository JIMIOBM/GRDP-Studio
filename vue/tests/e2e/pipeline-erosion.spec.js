import { test, expect } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import * as XLSX from 'xlsx'
import { createPipelineInput } from '../../src/utils/pipelineDefaults.js'
import { PIPELINE_BATCH_VERSION } from '../../src/utils/pipelineBatch.js'

test.use({ actionTimeout: 15_000, navigationTimeout: 30_000 })

test('erosion edits all cases, calculates, saves, reopens and invalidates results; template and draggable charts work', async ({ page }) => {
  test.setTimeout(120_000)
  await page.setViewportSize({ width: 1366, height: 900 })
  // Reserve the width normally occupied by the application's navigation tree.
  await page.addInitScript(() => window.addEventListener('DOMContentLoaded', () => {
    const app = document.getElementById('app')
    if (app) app.style.width = '1100px'
  }))
  const pageErrors = []
  page.on('pageerror', error => pageErrors.push(error.message))
  const graph = { nodes: [{ id: 'well', name: '冲蚀测试井', type: 'well', parameters: { elevationM: 0 } },
    { id: 'junction', name: '连接点', type: 'junction', parameters: { elevationM: 0 } },
    { id: 'outlet', name: '出口站', type: 'station', parameters: { elevationM: 0 } }],
  edges: [{ id: 'pipe-a', name: '井口至连接点', source: 'well', target: 'junction', parameters: { diameterMm: 100, lengthM: 500, roughnessMm: 0.03 } },
    { id: 'pipe-b', name: '连接点至出口站', source: 'junction', target: 'outlet', parameters: { diameterMm: 150, lengthM: 800, roughnessMm: 0.03 } }] }
  const gasModel = { pvtId: 81001, pvtName: '测试井气体组成', revision: 1, compositionRevision: 'gas-v1', method: 'PR', issue: null }
  const liquid = { pvtId: 82001, pvtName: '测试井地层水', salinityMgL: 5000, originalPressureMpa: 15,
    volumeFactorMethod: 0, compressibilityMethod: 0, inputHash: 'water-v1', issue: null }
  let model = { revision: 1, topologyRevision: 1, input: { ...createPipelineInput(), thermalMode: 'isothermal',
    boundary: { topologyRevision: 1, activeCaseId: 'case-a', cases: ['case-a', 'case-b'].map((id, index) => ({ id,
      operatingAt: index ? '2026-09-01T01:35' : '2026-09-01T00:20', nodes: graph.nodes.map(node => ({ nodeId: node.id,
        supplyRate10k: node.type === 'well' ? 5 : null, pressureMpa: node.type === 'well' ? 8 : null,
        temperatureC: node.type === 'well' ? 35 : null, withdrawalRate10k: node.id === 'outlet' ? 5 : null })) })) } } }
  let batch = null, calculatedBody = null, savedBody = null, calculateRequests = 0
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url()), resource = url.pathname.replace('/api/pipeline-capacity', '')
    if (!url.pathname.startsWith('/api/')) { await route.continue(); return }
    let data
    if (resource === '/model') data = model
    else if (resource === '/topology') data = { revision: 1, graph }
    else if (resource === '/pvt-model') data = gasModel
    else if (resource === '/erosion/liquid-sources') data = [liquid]
    else if (resource === '/temperature') data = null
    else if (resource === '/batch/latest') data = batch?.id ? batch : null
    else if (resource === '/batch/calculate') {
      calculateRequests++
      calculatedBody = route.request().postDataJSON()
      const input = { ...calculatedBody.input, gasModel,
        constraints: { ...calculatedBody.input.constraints, erosion: { ...calculatedBody.input.constraints.erosion, liquidPvt: liquid } } }
      batch = { revision: model.revision, topologyRevision: 1, graph, input, calculationToken: 'test-erosion-token',
        result: { algorithmVersion: PIPELINE_BATCH_VERSION, successCount: 2, failureCount: 0,
          cases: input.boundary.cases.map((condition, index) => ({ caseId: condition.id, operatingAt: condition.operatingAt, status: 'success',
            pipes: [], equipment: [], hydrate: [], erosion: graph.edges.map(edge => input.constraints.erosion.segments.find(row => row.edgeId === edge.id)?.sandDensityKgM3 != null ? { edgeId: edge.id, name: edge.name,
              distanceM: 250, pointLabel: '管道中点', pressureMpa: 7.8 - index * 0.1, temperatureC: 35, rate10k: 5,
              gasDensityKgM3: 65, liquidDensityKgM3: 1004, mixtureDensityKgM3: 68,
              sandFactor: 45, liquidHoldupFactor: 130, criticalCoefficient: 80, actualVelocityMs: 8 + index,
              criticalVelocityMs: 10, velocityRatio: 0.8 + index * 0.1, status: 'reference_below', applicable: true,
              reason: '比较流速定义待核实，仅供参考', sampledPoints: 11, evaluatedPoints: 11, modelVersion: 'wellbore-test-v1' }
              : { edgeId: edge.id, name: edge.name, status: 'not_evaluated', applicable: false,
                reason: '请补齐持液率、含砂率和砂粒密度' }) })) } }
      data = batch
    } else if (resource === '/batch/save') {
      savedBody = route.request().postDataJSON()
      batch = { ...batch, revision: 2, id: 91003, calculationToken: null }
      model = { revision: 2, topologyRevision: 1, input: batch.input }; data = batch
    } else {
      await route.fulfill({ status: 400, contentType: 'application/json', body: JSON.stringify({ code: 400, msg: `Unexpected test request ${url.pathname}` }) }); return
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, data }) })
  })
  await page.goto('/tests/fixtures/pipeline-erosion.html')
  await expect(page.locator('[aria-label="全部工况管道冲蚀数据"]')).toBeVisible()
  await expect(page.locator('.el-table__body tbody tr')).toHaveCount(4)
  expect(await page.locator('.el-table__body input').evaluateAll(inputs => inputs.every(input => input.value === ''))).toBe(true)
  await page.getByRole('button', { name: '计算', exact: true }).click()
  await expect(page.locator('.error-strip')).toContainText('井口至连接点')
  await expect(page.locator('.error-strip')).toContainText('请填写持液率 Hₗ、含砂率 Hₛ、砂粒密度')
  await expect(page.getByRole('button', { name: '计算', exact: true })).toBeEnabled()
  expect(calculateRequests).toBe(0)
  await expect(page.locator('.erosion-analysis')).toHaveCount(0)
  await page.getByLabel('液相来源 · 地层水 PVT').selectOption('82001')
  await expect(page.getByText('McCain 方法 / Meehan 方法', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '收起参数设置', exact: true }).click()
  await expect(page.getByRole('button', { name: '展开参数设置', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '展开参数设置', exact: true }).click()
  await expect(page.getByRole('separator', { name: '调整参数栏宽度', exact: true })).toBeVisible()
  await page.getByRole('combobox', { name: '选择管道', exact: true }).selectOption('pipe-a')
  await page.getByLabel('持液率 Hₗ（%）', { exact: true }).fill('0.5')
  await page.getByLabel('含砂率 Hₛ（%）', { exact: true }).fill('0.001')
  await page.getByLabel('砂粒密度（kg/m³）', { exact: true }).fill('2650')
  await expect(page.locator('.el-table__body tbody tr')).toHaveCount(2)
  await page.getByRole('button', { name: '计算', exact: true }).click()
  await expect(page.locator('.error-strip')).toContainText('连接点至出口站')
  await expect(page.locator('.error-strip')).not.toContainText('井口至连接点')
  await expect(page.getByRole('button', { name: '计算', exact: true })).toBeEnabled()
  expect(calculateRequests).toBe(0)
  await page.getByRole('combobox', { name: '选择管道', exact: true }).selectOption('pipe-b')
  await page.getByLabel('持液率 Hₗ（%）', { exact: true }).fill('0.8')
  await page.getByLabel('含砂率 Hₛ（%）', { exact: true }).fill('0.003')
  await page.getByLabel('砂粒密度（kg/m³）', { exact: true }).fill('2600')
  await page.getByRole('combobox', { name: '选择管道', exact: true }).selectOption('pipe-a')
  await page.getByRole('button', { name: '计算', exact: true }).click()
  await expect(page.locator('.erosion-analysis')).toBeVisible()
  await expect(page.getByText('冲蚀计算完成：已评价 4 条，未评价 0 条', { exact: true })).toBeVisible()
  expect(calculateRequests).toBe(1)
  expect(calculatedBody.input.boundary.cases).toHaveLength(2)
  expect(calculatedBody.input.segments).toHaveLength(2)
  expect(calculatedBody.input.constraints.erosion.segments[0]).toMatchObject({ edgeId: 'pipe-a', liquidHoldupPercent: 0.5, sandContentPercent: 0.001, sandDensityKgM3: 2650 })
  const legend = page.getByRole('complementary', { name: '曲线说明' })
  await expect(legend.getByRole('button')).toHaveCount(2)
  const before = await legend.boundingBox()
  await page.mouse.move(before.x + 70, before.y + 12); await page.mouse.down()
  await page.mouse.move(before.x - 70, before.y + 82, { steps: 10 }); await page.mouse.up()
  const after = await legend.boundingBox()
  expect(after.x).toBeLessThan(before.x - 50)
  await page.getByRole('radio', { name: '利用率', exact: true }).check()
  await expect(legend.getByRole('button', { name: '临界值 1', exact: true })).toBeVisible()
  await page.screenshot({ path: path.join(os.tmpdir(), 'grdp-pipeline-erosion-analysis.png'), fullPage: true })
  await page.getByRole('button', { name: '保存', exact: true }).click()
  await expect(page.getByText('全部工况参数及本批计算结果已保存', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '保存', exact: true })).toBeDisabled()
  expect(savedBody).toEqual({ projectId: 91001, gasReservoirId: 91002, wellName: '冲蚀测试井', calculationToken: 'test-erosion-token' })
  await page.reload()
  await expect(page.locator('.erosion-analysis')).toBeVisible()
  await page.getByRole('button', { name: '数据列表', exact: true }).click()
  await expect(page.locator('.el-table__body tbody tr')).toHaveCount(4)
  await expect(page.locator('.el-table__header-wrapper th')).toHaveCount(7)
  expect(await page.locator('[aria-label="全部工况管道冲蚀数据"] .el-scrollbar__wrap').evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
  expect(await page.locator('.result-area').evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
  await expect(page.getByText('管材', { exact: true })).toHaveCount(0)
  await page.screenshot({ path: path.join(os.tmpdir(), 'grdp-pipeline-erosion-data.png'), fullPage: true })
  await page.getByRole('button', { name: '2026/09/01 00:20 井口至连接点 查看结果', exact: true }).click()
  const resultDialog = page.getByRole('dialog', { name: '冲蚀计算结果', exact: true })
  await expect(resultDialog).toBeVisible()
  await expect(resultDialog.locator('.result-values dd')).toHaveText(['8.000', '10.000', '0.800'])
  await expect(resultDialog.getByText('比较流速定义待核实，仅供参考', { exact: true })).toBeVisible()
  await resultDialog.locator('.el-dialog__headerbtn').click()
  await expect(resultDialog).toBeHidden()
  await page.getByLabel('2026/09/01 00:20 井口至连接点 持液率 Hₗ', { exact: true }).fill('0.75')
  await page.getByLabel('2026/09/01 00:20 井口至连接点 持液率 Hₗ', { exact: true }).press('Tab')
  await expect(page.locator('.stale-strip')).toBeVisible()
  await expect(page.getByRole('button', { name: '保存', exact: true })).toBeDisabled()
  await page.screenshot({ path: path.join(os.tmpdir(), 'grdp-pipeline-erosion-stale.png'), fullPage: true })
  await page.getByRole('button', { name: '结果分析', exact: true }).click()
  await expect(page.getByRole('complementary', { name: '曲线说明' })).toHaveCount(0)
  await page.getByRole('button', { name: '本地导入', exact: true }).click()
  const downloadEvent = page.waitForEvent('download')
  await page.getByRole('button', { name: '数据模板下载', exact: true }).click()
  const download = await downloadEvent
  expect(download.suggestedFilename()).toBe('冲蚀测试井-冲蚀参数模板.xlsx')
  const workbook = XLSX.read(await readFile(await download.path()), { type: 'buffer' })
  expect(workbook.SheetNames).toContain('管道共用参数')
  expect(workbook.SheetNames).toContain('工况冲蚀参数')
  expect(workbook.Sheets['工况冲蚀参数'].C2.v).toBe(0.75)
  expect(workbook.Sheets['管道共用参数'].B2.v).toBe(0.5)
  expect(XLSX.utils.sheet_to_json(workbook.Sheets['管道共用参数'], { header: 1 })[0]).toEqual(['管道名称', '持液率 Hₗ（%）', '含砂率 Hₛ（%）', '砂粒密度（kg/m³）'])
  expect(XLSX.utils.sheet_to_json(workbook.Sheets['工况冲蚀参数'], { header: 1 })[0]).toEqual(['工况时间（只读）', '管道名称', '持液率 Hₗ（%）', '含砂率 Hₛ（%）', '砂粒密度（kg/m³）'])
  workbook.Sheets['管道共用参数'].C2.v = 0.002
  await page.locator('.erosion-import-dialog input[type=file]').setInputFiles({ name: '冲蚀参数.xlsx', mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    buffer: XLSX.write(workbook, { type: 'buffer', bookType: 'xlsx' }) })
  await page.getByRole('button', { name: '确定', exact: true }).click()
  await page.getByRole('button', { name: '替换参数', exact: true }).click()
  await expect(page.locator('[aria-label="全部工况管道冲蚀数据"]')).toBeVisible()
  await page.getByRole('combobox', { name: '选择管道', exact: true }).selectOption('pipe-a')
  await expect(page.getByLabel('含砂率 Hₛ（%）', { exact: true })).toHaveValue('0.002')
  await expect(page.getByLabel('2026/09/01 00:20 井口至连接点 持液率 Hₗ', { exact: true })).toHaveValue('0.75')
  await page.getByRole('button', { name: '计算', exact: true }).click()
  await expect(page.locator('.erosion-analysis')).toBeVisible()
  expect(calculateRequests).toBe(2)
  expect(calculatedBody.input.constraints.erosion.segments[0].sandContentPercent).toBe(0.002)
  await page.getByLabel('砂粒密度（kg/m³）', { exact: true }).fill('')
  await page.getByRole('button', { name: '计算', exact: true }).click()
  await expect(page.locator('[aria-label="全部工况管道冲蚀数据"]')).toBeVisible()
  await expect(page.locator('.erosion-analysis')).toHaveCount(0)
  await expect(page.locator('.error-strip')).toContainText('井口至连接点')
  await expect(page.locator('.error-strip')).toContainText('请填写砂粒密度')
  await expect(page.getByRole('button', { name: '计算', exact: true })).toBeEnabled()
  expect(calculateRequests).toBe(2)
  expect(pageErrors).toEqual([])
})
