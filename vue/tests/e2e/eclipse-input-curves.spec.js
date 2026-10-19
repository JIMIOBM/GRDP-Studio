import { expect, test } from '@playwright/test'
import { readFileSync } from 'node:fs'

const base = process.env.GRDP_INPUT_CURVE_API?.replace(/\/$/, '')
const cases = [
  ['oil', 'eclipseOilPvt', 'oilPvtContext', '油黏度乘数', '创建油物性方案并计算', 2],
  ['gas', 'eclipseGasPvt', 'gasPvtContext', '气黏度乘数', '创建气物性方案并计算', 2],
  ['live', 'eclipseLiveOilPvt', 'liveOilPvtContext', 'PVTO物性乘数', '创建含溶解气油物性方案并计算', 2],
  ['wet', 'eclipseWetGasPvt', 'wetGasPvtContext', 'PVTG物性乘数', '创建湿气物性方案并计算', 2],
  ['relperm', 'eclipseRelperm', 'relpermContext', '相渗乘数', '创建相渗方案并计算', .5],
  ['coupled', 'eclipseCoupledRelperm', 'coupledRelpermContext', '两表相渗乘数', '创建两表相渗方案并计算', .5],
  ['multiSat', 'eclipseMultiSatRelperm', 'multiSatRelpermContext', '两表相渗乘数', '创建两表相渗方案并计算', .5],
  ['familyII', 'eclipseMultiSatFamilyII', 'multiSatRelpermContext', '三表相渗乘数', '创建分区三表相渗方案并计算', .5],
  ['multiPvt', 'eclipseMultiPvt', 'multiPvtContext', '分区PVT乘数', '创建分区PVT方案并计算', 2],
  ['multiLive', 'eclipseMultiLiveOilPvt', 'multiLiveOilPvtContext', '分区PVTO物性乘数', '创建分区含溶解气油物性方案并计算', 2],
  ['multiWet', 'eclipseMultiWetGasPvt', 'multiWetGasPvtContext', '分区PVTG物性乘数', '创建分区湿气物性方案并计算', 2]
]
const load = (name, file) => JSON.parse(readFileSync(new URL(`../fixtures/${name}/${file}.json`, import.meta.url), 'utf8'))
let source = {}
test.beforeAll(async ({ request }) => {
  for (const [key, name] of cases) {
    let run = load(name, 'baseline'), inspection
    if (base) {
      const read = async path => { const r = await request.get(base + '/software-integration' + path); expect(r.status()).toBe(200); const b = await r.json(); expect(b.code).toBe(200); return b.data }
      run = await read(`/runs/${run.id}`)
      const project = await read(`/projects/${run.projectId}`)
      inspection = project.models.flatMap(m => m.versions).find(v => v.id === run.modelVersionId).inspection
    } else {
      try { inspection = load(name, 'platform-inspection') } catch { inspection = load(name, 'inspection') }
    }
    source[key] = { run, inspection }
  }
})
async function open(page, key) {
  const errors = [], writes = []
  page.on('pageerror', e => errors.push(e.message))
  await page.route('**/fixtures/input-curves-source.json', route => route.fulfill({ json: source }))
  await page.route('**/api/software-integration/**', route => { writes.push(route.request().method() + ' ' + route.request().url()); return route.abort() })
  await page.setViewportSize({ width: 1550, height: 1080 })
  await page.goto('/tests/e2e/fixtures/eclipse-input-curves.html')
  await page.getByRole('button', { name: key, exact: true }).click()
  const panel = page.getByRole('region', { name: 'ECLIPSE输入曲线对照', exact: true })
  await expect(panel).toHaveAttribute('data-status', 'original')
  return { panel, errors, writes }
}
async function charts(panel) {
  await expect.poll(async () => panel.locator('canvas').count()).toBeGreaterThan(0)
  return panel.locator('.curve-canvas').evaluateAll(elements => elements.map(el => globalThis.readInputChart(el)))
}
const num = value => Number(String(value).replace(/[dD]/, 'e'))
// Build the oracle directly from actual inspection row tokens, independently of preview adapters.
function oracle(inspection, contextKey, keyword) {
  const context = inspection[contextKey], region = context.regions?.[0] || context
  const table = region.tables?.find(t => t.keyword === keyword) || region
  if (table.records) return table.records.flatMap((branch, i) => branch.rows.map((row, j) => {
    const offset = j === 0 ? 1 : 0
    return { branch: i + 1, x: num(keyword === 'PVTG' ? row.values[offset] : row.values[offset]), y: num(row.values[offset + 2]), line: row.lineNumber }
  }))
  return table.rows.map(row => ({ x: num(row.values[0]), y: num(row.values[['PVDO', 'PVDG'].includes(keyword) ? 2 : 1]), line: row.lineNumber }))
}
for (const [key, , contextKey, input, submit, multiplier] of cases) test(`输入曲线 ${key} 原始表点、校验预览、CSV与原计算入口`, async ({ page }, info) => {
  const { panel, errors, writes } = await open(page, key)
  const keyword = await panel.getAttribute('data-keyword'), expected = oracle(source[key].inspection, contextKey, keyword)
  const original = (await charts(panel)).flatMap(c => c.series).filter(s => s.name.includes(['PVDO', 'PVDG', 'PVTO', 'PVTG'].includes(keyword) ? '黏度' : 'Krw'))
  expect(original.flatMap(s => s.data)).toEqual(expected.map(p => [p.x, p.y]))
  await page.getByRole('spinbutton', { name: input, exact: true }).fill(String(multiplier))
  await expect(panel).toHaveAttribute('data-status', 'preview')
  const preview = (await charts(panel)).flatMap(c => c.series).filter(s => s.name.startsWith('目标预览'))
  expect(preview.flatMap(s => s.data)).toEqual(expected.map(p => [p.x, p.y * multiplier]))
  const waitCsv = page.waitForEvent('download'); await panel.getByRole('button', { name: '导出全部表点CSV', exact: true }).click()
  const download = await waitCsv, file = info.outputPath(download.suggestedFilename()); await download.saveAs(file)
  const lines = readFileSync(file, 'utf8').replace(/^\uFEFF/, '').split('\r\n').map(line => line.split(',').map(token => token.slice(1, -1)))
  const targetRows = lines.slice(1).filter(row => row[15] !== '')
  expect(targetRows.length).toBe(expected.length)
  targetRows.forEach((row, index) => { expect(num(row[0])).toBe(source[key].run.id); expect(num(row[1])).toBe(source[key].run.modelVersionId); expect(row[5]).toBe(keyword); expect(num(row[12])).toBe(expected[index].x); expect(num(row[14])).toBe(expected[index].y); expect(num(row[15])).toBe(expected[index].y * multiplier); expect(num(row[16])).toBe(expected[index].line); expect(row[18]).toBe('preview') })
  await panel.screenshot({ path: info.outputPath(`input-${key}.png`) })
  if (key === 'oil') {
    const wait = page.waitForEvent('download'); await panel.getByRole('button', { name: '导出输入曲线PNG', exact: true }).click()
    const png = await wait, path = info.outputPath(png.suggestedFilename()); await png.saveAs(path)
    expect(readFileSync(path).readUInt32BE(20)).toBeGreaterThan(1000) // Both independent unit panels in one image.
  }
  await page.getByRole('button', { name: submit, exact: true }).click()
  const emitted = await page.evaluate(() => globalThis.inputCurveSubmissions)
  expect(emitted).toHaveLength(1); expect(emitted[0].baselineRunId).toBe(source[key].run.id); expect(emitted[0].multiplier).toBe(multiplier)
  expect(errors).toEqual([]); expect(writes).toEqual([])
  await info.attach('input-proof', { body: JSON.stringify({ apiSource: !!base, runId: source[key].run.id, keyword, targetTablePoints: expected.length, actualSolverRunsCreated: 0, manualUiVerified: false }), contentType: 'application/json' })
})
test('输入曲线 无效相渗、SOF3双列、PNG导出与切换基线清空', async ({ page }, info) => {
  const { panel, errors } = await open(page, 'relperm'), input = page.getByRole('spinbutton', { name: '相渗乘数', exact: true })
  await input.fill('2'); await expect(panel).toHaveAttribute('data-status', 'invalid')
  expect((await charts(panel)).flatMap(c => c.series).some(s => s.name.startsWith('目标预览'))).toBe(false)
  await page.locator('.el-select').filter({ has: page.getByRole('combobox', { name: '相渗调整项目', exact: true }) }).click(); await page.getByRole('option', { name: '油相两列联动 · SOF3', exact: true }).click()
  await expect(panel).toHaveAttribute('data-keyword', 'SOF3'); await expect(panel).toHaveAttribute('data-status', 'original')
  await input.fill('.5'); await expect(panel).toHaveAttribute('data-status', 'preview')
  const option = await charts(panel); expect(option).toHaveLength(1); expect(option[0].series.filter(s => s.name.startsWith('目标预览'))).toHaveLength(2)
  const waitPng = page.waitForEvent('download'); await panel.getByRole('button', { name: '导出输入曲线PNG', exact: true }).click(); const png = await waitPng; await png.saveAs(info.outputPath(png.suggestedFilename()))
  expect(readFileSync(info.outputPath(png.suggestedFilename())).subarray(0, 8).toString('hex')).toBe('89504e470d0a1a0a')
  await page.getByRole('button', { name: '切换基线身份（状态测试）', exact: true }).click(); await expect(panel).toHaveAttribute('data-status', 'original'); await expect(input).toHaveValue('')
  expect(errors).toEqual([])
})
test('输入曲线 PVTG独立分支选择、选区清空与窄屏重排', async ({ page }, info) => {
  const { panel, errors } = await open(page, 'multiWet')
  await page.locator('.el-select').filter({ has: page.getByRole('combobox', { name: '输入曲线分支', exact: true }) }).click(); await page.getByRole('option').filter({ hasText: /^分支 2 ·/ }).click()
  expect((await charts(panel)).flatMap(c => c.series).every(s => s.name.includes('分支 2 ·'))).toBe(true)
  await page.getByRole('spinbutton', { name: '分区PVTG物性乘数', exact: true }).fill('2')
  await page.locator('.el-select').filter({ has: page.getByRole('combobox', { name: 'PVTG流体区', exact: true }) }).click(); await page.getByRole('option').filter({ hasText: /^PVT\/EQL区 2 ·/ }).click()
  await expect(panel).toHaveAttribute('data-region', '2'); await expect(panel).toHaveAttribute('data-status', 'original')
  expect((await charts(panel))[0].series.length).toBeGreaterThan(1)
  await page.setViewportSize({ width: 430, height: 950 })
  await expect.poll(() => panel.locator('.curve-grid').evaluate(el => getComputedStyle(el).gridTemplateColumns.split(' ').length)).toBe(1)
  const boxes = await panel.locator('.curve-canvas').evaluateAll(elements => elements.map(el => ({ w: el.clientWidth, canvas: el.querySelector('canvas')?.clientWidth })))
  await expect.poll(async () => panel.locator('.curve-canvas canvas').first().evaluate(el => el.clientWidth)).toBeLessThan(430)
  expect(boxes.every(b => b.w > 0)).toBe(true); await panel.screenshot({ path: info.outputPath('input-narrow.png') }); expect(errors).toEqual([])
})
for (const [key, aria, label, keyword, input, multiplier, targetSeries] of [
  ['multiPvt', '分区PVT调整项目', '气黏度 · PVDG', 'PVDG', '分区PVT乘数', 2, 1],
  ['familyII', '三表相渗调整项目', '油相 Krow/Krog · SOF3两列联动', 'SOF3', '三表相渗乘数', .5, 2],
  ['coupled', '两表相渗调整项目', '气相 Krg · SGOF', 'SGOF', '两表相渗乘数', .5, 1]
]) test(`输入曲线 ${key} 选择物性后自动定位对应表族`, async ({ page }) => {
  const { panel, errors } = await open(page, key)
  await page.locator('.el-select').filter({ has: page.getByRole('combobox', { name: aria, exact: true }) }).click(); await page.getByRole('option', { name: label, exact: true }).click()
  await expect(panel).toHaveAttribute('data-keyword', keyword); await expect(panel).toHaveAttribute('data-status', 'original')
  await page.getByRole('spinbutton', { name: input, exact: true }).fill(String(multiplier)); await expect(panel).toHaveAttribute('data-status', 'preview')
  expect((await charts(panel)).flatMap(c => c.series).filter(s => s.name.startsWith('目标预览'))).toHaveLength(targetSeries)
  expect(errors).toEqual([])
})
