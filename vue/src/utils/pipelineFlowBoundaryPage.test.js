import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import * as Vue from 'vue'
import * as serverRenderer from 'vue/server-renderer'
import { parse, compileScript } from '@vue/compiler-sfc'
import { compile } from '@vue/compiler-ssr'
import { pipelinePageTitles } from './pipelineNavigation.js'

const readPage = name => parse(fs.readFileSync(new URL(`../views/PipelineCapacity/${name}.vue`, import.meta.url), 'utf8')).descriptor
const descriptor = readPage('PipelineFlowPage'), content = readPage('PipelineCapacityContent'), timeChart = readPage('PipelineTimeChart')
const setup = new Function('computed', 'ref', 'watch', 'defineProps', descriptor.scriptSetup.content.replace(/^import .+$/gm, '')
  + '\nreturn { s, collapsed, dataPage, pageSize, heatMode, working, locked, activeTab, dataRows, pagedRows, chartSeries, emptyChartText, charts, activeChart, selectedChart, resultColumns, statusLabel };')
const bindings = state => setup(Vue.computed, Vue.ref, Vue.watch, () => ({ state }))
function find(node, predicate) {
  if (predicate(node)) return node
  for (const child of node.children || []) { const found = find(child, predicate); if (found) return found }
}
function part(query, source = descriptor) {
  const predicate = typeof query === 'function' ? query : node => node.type === 1 && node.props.some(prop => prop.name === 'class' && prop.value?.content === query)
  const node = find(source.template.ast, predicate)
  assert.ok(node)
  const isolated = node.loc.source.replace(/^<(\w+) v-else(?=\s|>)/, '<$1')
  return new Function('require', compile(isolated, { mode: 'function' }).code)(name => name === 'vue' ? Vue : serverRenderer)
}
const sidebar = part('parameter-panel'), settings = part('batch-settings'), analysis = part('batch-analysis')
const table = part(node => node.type === 1 && node.props.some(prop => prop.name === 'aria-label' && prop.value?.content === '全部工况管流数据'))
const tabs = part('bottom-tabs'), topActions = part('actions', content)
const flatten = nodes => nodes.flatMap(node => node?.type === Vue.Fragment ? flatten(node.children || []) : node?.props?.label ? [node] : [])
const TableStub = { props: ['data'], setup(props, { slots }) { return () => {
  const columns = flatten(slots.default?.() || [])
  return Vue.h('table', [Vue.h('thead', [Vue.h('tr', columns.map(column => Vue.h('th', column.props.label)))]),
    Vue.h('tbody', (props.data || []).map((row, index) => Vue.h('tr', columns.map(column => Vue.h('td', column.children?.default
      ? column.children.default({ row, $index: index }) : row[column.props.prop] ?? '—')))))])
} } }
const render = (ssrRender, scope) => serverRenderer.renderToString(Vue.createSSRApp({ ssrRender, setup: () => Vue.proxyRefs(scope), components: {
  PipelineNumber: { props: ['label', 'modelValue'], render() { return Vue.h('label', [this.label, Vue.h('input', { type: 'number', value: this.modelValue })]) } },
  ElTable: TableStub, ElTableColumn: { render: () => null },
  PipelineTimeChart: { props: ['title', 'series', 'unit', 'emptyText'], render() { return Vue.h('section', { class: 'chart-stub', 'data-title': this.title }, this.series.length ? this.unit : this.emptyText) } }
} }))
function fixture() {
  const first = Date.UTC(2026, 8, 25, 16), second = first + 3600000
  const series = [{ id: 'a', name: '管段 A', data: [[first, 30], [second, null]] }, { id: 'b', name: '管段 B', data: [[first, 28], [second, 27]] }]
  return Vue.reactive({ form: { thermalMode: 'heat', frictionMethod: 'colebrook', jtKmpa: 3 }, panel: 'data',
    busy: false, batchBusy: false, batchStale: false, batchResult: { revision: 2 }, canCalculate: true, canBatchSave: true,
    thermalSourceLoading: false, thermalSourceError: '', thermalConfig: { revision: 4 },
    gasPropertyConfig: { method: 'PR', pvtName: '当前井气体组成' }, gasPropertyError: '',
    batchDataRows: [
      { rowKey: '0-a', operatingAt: '2026-09-26T00:00', name: '管段 A', lengthM: 100, diameterMm: 100, inletMpa: 8, outletMpa: 7.5, inletC: 32, outletC: 30, rate10k: 10, status: 'success' },
      { rowKey: '0-b', operatingAt: '2026-09-26T00:00', name: '管段 B', lengthM: 200, diameterMm: 100, inletMpa: 7.5, outletMpa: 7, inletC: 30, outletC: 28, rate10k: 10, status: 'success' },
      { rowKey: '1-a', operatingAt: '2026-09-26T01:00', name: '管段 A', lengthM: 100, diameterMm: 100, inletMpa: null, outletMpa: null, inletC: null, outletC: null, rate10k: null, status: 'failed', error: '参数不足' },
      { rowKey: '1-b', operatingAt: '2026-09-26T01:00', name: '管段 B', lengthM: 200, diameterMm: 100, inletMpa: 7.3, outletMpa: 6.8, inletC: 29, outletC: 27, rate10k: 9, status: 'success' }
    ], batchChartSeries: { temperature: series, pressure: series, flow: series }, calculate() {}, savePage() {}, f: value => value == null ? '—' : value })
}

test('left parameter panel puts method settings before calculate and save and still collapses', async () => {
  const state = fixture(), page = bindings(state), html = await render(sidebar, page)
  assert.match(html, /参数设置/)
  assert.ok(html.indexOf('class="batch-settings"') >= 0)
  assert.ok(html.indexOf('class="batch-settings"') < html.indexOf('class="side-actions"'))
  assert.equal((html.match(/type="radio"/g) || []).length, 4)
  const actions = await render(part('side-actions'), page)
  assert.equal((actions.match(/<button/g) || []).length, 2)
  assert.match(actions, /class="calculate"[^>]*>计算<\/button>/)
  assert.match(actions, /class="save"[^>]*>保存<\/button>/)
  assert.doesNotMatch(html, /<select|选择工况|查看|导入/)
  state.canBatchSave = false
  assert.match(await render(sidebar, page), /class="save" disabled/)
  page.collapsed.value = true
  assert.doesNotMatch(await render(sidebar, page), /<fieldset|batch-settings|side-actions/)
})

test('left settings use two radio groups and heat-only JT input while the right data area has no settings', async () => {
  const state = fixture(), page = bindings(state), html = await render(settings, page)
  assert.equal((html.match(/<fieldset[^>]*class="method-options"/g) || []).length, 2)
  assert.equal((html.match(/type="radio"/g) || []).length, 4)
  assert.equal((html.match(/type="number"/g) || []).length, 1)
  assert.match(html, /焦耳—汤姆逊系数/)
  assert.match(html, /已保存温度模型参数/)
  assert.doesNotMatch(html, /第 4 版/)
  assert.match(html, /PR · 当前井气体组成/)
  assert.doesNotMatch(html, /<select|<button|选择工况|环境温度（/)
  assert.doesNotMatch(await render(part('flow-data scroll-content'), page), /batch-settings|method-options|物性来源|温度来源/)
  state.form.thermalMode = 'isothermal'
  const isothermal = await render(settings, page)
  assert.doesNotMatch(isothermal, /type="number"|焦耳—汤姆逊系数/)
  assert.equal((isothermal.match(/type="radio"/g) || []).length, 4)
  assert.match(isothermal, /不使用环境温度、总传热系数/)
})

test('batch data lists all hours and pipes and preserves failed rows as missing values with their error', async () => {
  const page = bindings(fixture()), html = await render(table, page)
  assert.equal(page.dataRows.value.length, 4)
  assert.equal((html.match(/<tr>/g) || []).length, 5)
  for (const value of ['2026-09-26 00:00', '2026-09-26 01:00', '管段 A', '管段 B', '参数不足', '计算失败', '计算完成']) assert.ok(html.includes(value))
  const failed = html.match(/<tr><td>2026-09-26 01:00<\/td><td>管段 A[\s\S]*?<\/tr>/)[0]
  assert.equal((failed.match(/<td>—<\/td>/g) || []).length, 5)
  assert.doesNotMatch(failed, /<td>0<\/td>/)
})

test('data pagination keeps every batch row reachable and resets when another batch replaces the data', async () => {
  const state = fixture(), page = bindings(state)
  state.batchDataRows = Array.from({ length: 123 }, (_, index) => ({ ...state.batchDataRows[0], rowKey: `row-${index}`, name: `管段 ${index}` }))
  await Vue.nextTick()
  page.dataPage.value = 3
  assert.equal(page.pagedRows.value.length, 23)
  assert.equal(page.pagedRows.value[0].rowKey, 'row-100')
  state.batchDataRows = state.batchDataRows.slice(0, 2)
  await Vue.nextTick()
  assert.equal(page.dataPage.value, 1)
  assert.equal(page.pagedRows.value.length, 2)
})

test('result analysis switches one full-size time chart using the three upper-left radio options', async () => {
  const page = bindings(fixture()), html = await render(analysis, page)
  assert.equal((html.match(/class="chart-stub"/g) || []).length, 1)
  assert.equal((html.match(/type="radio"/g) || []).length, 3)
  for (const label of ['温度/时间', '压力/时间', '流量/时间']) assert.ok(html.includes(label))
  assert.doesNotMatch(html, /<table|<select|<button|沿程|实测对比/)
  for (const chart of page.charts) {
    page.activeChart.value = chart.key
    const switched = await render(analysis, page)
    assert.equal((switched.match(/class="chart-stub"/g) || []).length, 1)
    assert.ok(switched.includes(`data-title="${chart.title}"`))
    assert.equal(page.selectedChart.value.unit, chart.unit)
  }
  assert.equal(page.chartSeries.value.temperature[0].data[1][1], null)
  const tabHtml = await render(tabs, page)
  assert.equal((tabHtml.match(/<button/g) || []).length, 2)
  assert.doesNotMatch(tabHtml, /沿程明细/)
})

test('missing or outdated results leave the selected chart empty and never reuse stale batch output', async () => {
  const state = fixture(), page = bindings(state)
  state.batchResult = null
  assert.deepEqual(page.chartSeries.value, {})
  assert.match(await render(analysis, page), /请先点击左侧/)
  state.batchResult = {}; state.batchStale = true
  assert.deepEqual(page.dataRows.value, [])
  assert.deepEqual(page.chartSeries.value, {})
  assert.match(await render(analysis, page), /重新计算/)
  state.batchStale = false; state.batchBusy = true
  assert.match(await render(analysis, page), /正在计算全部工况/)
  assert.deepEqual(page.chartSeries.value, {})
})

test('flow parent toolbar keeps only refresh and batch export and ignores old single-case state', async () => {
  const state = { ...fixture(), activePage: 'flow', batchError: '批量计算失败', error: '旧单工况错误', stale: true }
  const factory = new Function('computed', 'defineProps', 'defineEmits', 'defineExpose', 'usePipelineWorkspace', 'pipelinePageTitles',
    content.scriptSetup.content.replace(/^import .+$/gm, '') + '\nreturn { s, pageBusy, pageError, pageStale };')
  const scope = factory(Vue.computed, () => ({}), () => () => {}, () => {}, () => state, pipelinePageTitles)
  assert.equal(scope.pageError.value, '批量计算失败')
  assert.equal(scope.pageStale.value, false)
  const html = await render(topActions, scope)
  assert.equal((html.match(/<button/g) || []).length, 2)
  assert.match(html, />刷新<\/button>/)
  assert.match(html, />导出<\/button>/)
  assert.doesNotMatch(html, /结果对比|保存|计算|返回管流/)
})

const chartFunctions = new Function(timeChart.script.content.replace(/^export /gm, '') + '\nreturn { formatBatchChartTime, batchTimeChartOptions, batchChartLegendItems, clampBatchLegendPosition };')()
test('time axes use the well-test canvas and dense real time ticks while preserving minute values and gaps', async () => {
  assert.doesNotThrow(() => compileScript(timeChart, { id: 'time-chart-test', inlineTemplate: true }))
  const start = Date.UTC(2026, 8, 25, 16), hour = 3600000
  const option = chartFunctions.batchTimeChartOptions([{ id: 'a', name: '管段 A', data: [[start, 0], [start + hour, null], [start + 2 * hour, 5], [start + 3 * hour, NaN]] }], '出口温度—时间', '℃')
  assert.equal(option.xAxis.type, 'time')
  assert.equal(option.xAxis.axisLabel.formatter(start), '2026-09-26\n00:00')
  assert.equal(option.xAxis.axisLabel.formatter(start + 17 * 60000), '2026-09-26\n00:17')
  assert.equal(option.xAxis.axisLabel.hideOverlap, true)
  assert.equal(option.xAxis.minInterval, 60000)
  assert.equal(option.grid.show, true)
  assert.equal(option.grid.borderColor, '#d7dfeb')
  assert.equal(option.title.textStyle.fontWeight, 600)
  for (const axis of [option.xAxis, option.yAxis]) {
    assert.equal(axis.axisLine.show, true)
    assert.equal(axis.axisTick.show, true)
    assert.equal(axis.axisLabel.fontSize, 12)
    assert.equal(axis.splitLine.lineStyle.color, '#dbe4f1')
    assert.equal(axis.minorTick.splitNumber, 5)
    assert.equal(axis.minorSplitLine.lineStyle.color, '#edf2f8')
  }
  assert.equal(option.legend.show, false)
  assert.equal(option.series[0].connectNulls, false)
  assert.deepEqual(option.series[0].data, [[start, 0], [start + hour, null], [start + 2 * hour, 5], [start + 3 * hour, null]])
  for (const invalid of [null, undefined, Infinity, '2026-09-26', 1e30]) assert.equal(chartFunctions.formatBatchChartTime(invalid), '—')
  const tooltip = option.tooltip.formatter([{ value: [start, 0], seriesName: '<img src=x>' }])
  assert.match(tooltip, /2026-09-26 00:00/)
  assert.match(tooltip, /0.000/)
  assert.doesNotMatch(tooltip, /<img/)
  const echarts = await import('echarts')
  const chart = echarts.init(null, null, { renderer: 'svg', ssr: true, width: 1000, height: 600 })
  try {
    const minuteData = [[start + 17 * 60000, 30], [start + 49 * 60000, null], [start + 137 * 60000, 32]]
    chart.setOption(chartFunctions.batchTimeChartOptions([{ id: 'minute', name: '分钟工况', data: minuteData }], '出口温度—时间', '℃'))
    assert.deepEqual(chart.getOption().series[0].data, minuteData)
    for (const dimension of ['xAxis', 'yAxis']) {
      const axis = chart.getModel().getComponent(dimension).axis
      assert.ok(axis.getTicksCoords().length >= 8, `${dimension} must draw dense major ticks`)
      assert.ok(axis.getMinorTicksCoords().flat().length >= 20, `${dimension} minor ticks must be implemented by the installed ECharts`)
    }
    const svg = chart.renderToSVGString()
    assert.match(svg, /stroke="#d7dfeb"/)
    assert.match(svg, /stroke="#dbe4f1"/)
    assert.match(svg, /stroke="#edf2f8"/)
  } finally { chart.dispose() }
})

test('time chart keeps its draggable legend when switching metrics, clamps on resize, and cleans up on unmount', async () => {
  let mounted, unmount, observerCallback, observed, disconnected = 0, disposed = 0, initialized = 0, resized = 0
  const chart = { setOption() {}, resize() { resized++ }, dispose() { disposed++ } }
  const runtime = new Function('nextTick', 'onBeforeUnmount', 'onMounted', 'ref', 'watch', 'computed', 'echarts', 'ResizeObserver', 'defineProps', 'batchTimeChartOptions', 'batchChartLegendItems', 'clampBatchLegendPosition',
    timeChart.scriptSetup.content.replace(/^import .+$/gm, '') + '\nreturn { host, hasData, render, legendHost, legendPosition, legendItems, startLegendDrag, moveLegendDrag, endLegendDrag, handleLegendClick, toggleSeries, hiddenSeriesIds };')
  const props = Vue.reactive({ title: '出口温度—时间', unit: '℃', series: [{ id: 'a', name: 'A', data: [[Date.now(), null]] }] })
  const scope = runtime(Vue.nextTick, hook => { unmount = hook }, hook => { mounted = hook }, Vue.ref, () => {}, Vue.computed,
    { init() { initialized++; return chart } }, class { constructor(callback) { observerCallback = callback } observe(host) { observed = host } disconnect() { disconnected++ } },
    () => props, chartFunctions.batchTimeChartOptions, chartFunctions.batchChartLegendItems, chartFunctions.clampBatchLegendPosition)
  scope.host.value = { clientWidth: 900, clientHeight: 550 }
  scope.legendHost.value = { offsetWidth: 210, offsetHeight: 100 }
  mounted(); await Vue.nextTick()
  assert.equal(scope.hasData.value, false)
  assert.equal(initialized, 1)
  assert.equal(observed, scope.host.value)
  observerCallback(); assert.equal(resized, 2)
  const initialPosition = { ...scope.legendPosition.value }
  let captured = false
  const target = { setPointerCapture() { captured = true }, hasPointerCapture() { return captured }, releasePointerCapture() { captured = false } }
  scope.startLegendDrag({ button: 0, pointerId: 1, clientX: 700, clientY: 90, currentTarget: target, preventDefault() {} })
  scope.moveLegendDrag({ pointerId: 1, clientX: 550, clientY: 180 })
  assert.deepEqual(scope.legendPosition.value, { x: initialPosition.x - 150, y: initialPosition.y + 90 })
  scope.endLegendDrag({ pointerId: 1 }); assert.equal(captured, false)
  let stoppedClick = 0
  const click = { detail: 1, preventDefault() {}, stopPropagation() { stoppedClick++ } }
  scope.handleLegendClick(click)
  assert.equal(stoppedClick, 1, 'dragging a row must not also hide its curve')
  scope.startLegendDrag({ button: 0, pointerId: 2, clientX: 550, clientY: 180, currentTarget: target })
  scope.endLegendDrag({ pointerId: 2 })
  scope.handleLegendClick(click)
  assert.equal(stoppedClick, 1, 'ordinary clicks must still reach the curve toggle')
  const moved = { ...scope.legendPosition.value }
  props.title = '出口压力—时间'; props.unit = 'MPa（绝压）'
  await scope.render()
  assert.deepEqual(scope.legendPosition.value, moved)
  assert.equal(initialized, 1)
  scope.toggleSeries('a')
  assert.deepEqual(scope.hiddenSeriesIds.value, ['a'])
  const hiddenOption = chartFunctions.batchTimeChartOptions(props.series, props.title, props.unit, scope.hiddenSeriesIds.value)
  assert.equal(hiddenOption.series[0].lineStyle.color, scope.legendItems.value[0].color)
  assert.deepEqual(hiddenOption.series[0].data, [])
  scope.host.value.clientWidth = 350; scope.host.value.clientHeight = 250
  observerCallback()
  assert.ok(scope.legendPosition.value.x + 210 <= 350)
  assert.ok(scope.legendPosition.value.y + 100 <= 250)
  const pending = scope.render(); unmount(); await pending
  assert.equal(disconnected, 1)
  assert.equal(disposed, 1)
  assert.equal(initialized, 1)
})

test('a single successful hourly value remains visible when every surrounding hour failed', () => {
  const start = Date.UTC(2026, 8, 25, 16), hour = 3600000
  const option = chartFunctions.batchTimeChartOptions([{ id: 'a', name: '管段 A', data: [[start, null], [start + hour, 0], [start + 2 * hour, null]] }], '标况流量—时间', '10⁴m³/d')
  assert.equal(option.series[0].showSymbol, true)
  assert.equal(option.series[0].symbolSize, 6)
  assert.equal(option.series[0].connectNulls, false)
  assert.deepEqual(option.series[0].data, [[start, null], [start + hour, 0], [start + 2 * hour, null]])
  const separated = chartFunctions.batchTimeChartOptions([{ id: 'a', name: '管段 A', data: [[start, 1], [start + hour, null], [start + 2 * hour, 2]] }], '标况流量—时间', '10⁴m³/d')
  assert.equal(separated.series[0].showSymbol, true)
  assert.equal(separated.series[0].connectNulls, false)
})
