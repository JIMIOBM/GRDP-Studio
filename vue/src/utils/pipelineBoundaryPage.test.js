import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import * as Vue from 'vue'
import * as serverRenderer from 'vue/server-renderer'
import { parse } from '@vue/compiler-sfc'
import { compile } from '@vue/compiler-ssr'
import * as boundary from './pipelineBoundary.js'
import * as time from './pipelineTime.js'

const file = new URL('../views/PipelineCapacity/PipelineBoundaryPage.vue', import.meta.url)
const { descriptor } = parse(fs.readFileSync(file, 'utf8'))
const boundaryImports = ['MAX_BOUNDARY_CASES', 'boundaryCases', 'activeBoundaryCase', 'createBoundaryCase', 'boundaryRows', 'boundarySummary', 'orphanBoundaryRows', 'formatOperatingTime']
const setup = new Function('computed', 'ref', 'ElMessage', 'ElMessageBox', 'defineProps', ...boundaryImports,
  descriptor.scriptSetup.content.replace(/^import .+$/gm, '') + '\nreturn { s, cases, activeCase, rows, downstreamNodes, caseRows, orphans, summary, disabled, collapsed, importVisible, importedFileName, number, selectCase, addCase, removeCase, removeOrphan, acceptImport, formatOperatingTime };')
function bindings(state, confirm = async () => {}) {
  const messages = [], scope = setup(Vue.computed, Vue.ref, { success: text => messages.push(text), warning: text => messages.push(text) },
    { confirm }, () => ({ state }), ...boundaryImports.map(name => boundary[name] ?? time[name]))
  return { ...scope, messages }
}
function find(node, predicate) {
  if (predicate(node)) return node
  for (const child of node.children || []) { const match = find(child, predicate); if (match) return match }
}
const attribute = (node, name, value) => node.type === 1 && node.props.some(prop => prop.name === name && prop.value?.content === value)
function part(predicate) {
  const node = find(descriptor.template.ast, predicate)
  assert.ok(node)
  return new Function('require', compile(node.loc.source, { mode: 'function' }).code)(name => name === 'vue' ? Vue : serverRenderer)
}
const table = part(node => attribute(node, 'aria-label', '边界条件工况数据'))
const sidebar = part(node => node.type === 1 && node.tag === 'aside')
async function render(ssrRender, scope) {
  return serverRenderer.renderToString(Vue.createSSRApp({ ssrRender, setup: () => Vue.proxyRefs(scope), components: {
    PipelineNumber: { props: ['label', 'modelValue'], render() { return Vue.h('label', this.label) } }
  } }))
}
function fixture() {
  const graph = { nodes: [{ id: 'well', name: '井口 A', type: 'well' }, { id: 'valve', name: '一号阀门', type: 'valve' }, { id: 'end', name: '下游用户', type: 'station' }],
    edges: [{ source: 'well', target: 'valve' }, { source: 'valve', target: 'end' }] }
  const first = boundary.createBoundaryCase(graph), second = boundary.createBoundaryCase(graph)
  first.id = 'first'; first.operatingAt = '2026-09-12T00:00'
  second.id = 'second'; second.operatingAt = '2026-09-12T01:00'
  Object.assign(first.nodes[0], { supplyRate10k: 10, pressureMpa: 8, temperatureC: -5 })
  Object.assign(second.nodes[0], { supplyRate10k: 11, pressureMpa: 7.9, temperatureC: 0 })
  Object.assign(first.nodes[2], { withdrawalRate10k: 10, pressureMpa: 4 })
  Object.assign(second.nodes[2], { withdrawalRate10k: 11, pressureMpa: 4.1 })
  return Vue.reactive({ savedGraph: graph, topologyRevision: 3, context: { projectId: 1, gasReservoirId: 2, wellName: '井 A' },
    busy: false, topologyLoading: false, gasPropertyConfig: {}, form: { standardPressurePa: 101325, standardTemperatureK: 293.15,
      boundary: { topologyRevision: 3, activeCaseId: first.id, cases: [first, second] } }, savePage() {}, reloadPage() {} })
}

test('boundary table renders free time text with a format header and topology-driven node groups', async () => {
  const page = bindings(fixture()), html = await render(table, page)
  assert.match(html, /colspan="3"[^>]*>井口<\/th>/)
  assert.match(html, /colspan="2"[^>]*>一号阀门<\/th>/)
  assert.match(html, /colspan="2"[^>]*>下游用户<\/th>/)
  assert.equal((html.match(/<input/g) || []).length, 16)
  assert.equal((html.match(/type="text"/g) || []).length, 2)
  assert.match(html, /工况时间<small>YYYY\/MM\/DD hh:mm<\/small>/)
  assert.match(html, /value="2026\/09\/12 00:00"/)
  assert.doesNotMatch(descriptor.template.content, /datetime-local|step="3600"|current-case-note|点击一行查看|整点时间/)
  assert.match(html, /aria-label="第 1 组入口温度" value="-5"/)
  assert.match(html, /aria-label="第 2 组入口温度" value="0"/)
  assert.doesNotMatch(html, /<select|流量用途|压力用途/)
  assert.equal((html.match(/aria-selected="true"/g) || []).length, 1)
})

test('row selection and edits affect only that case and retain measurements at all nodes', async () => {
  const state = fixture(), page = bindings(state), [first, second] = state.form.boundary.cases
  page.selectCase(second)
  assert.equal(state.form.boundary.activeCaseId, 'second')
  page.number(page.caseRows.value[1].supply, 'pressureMpa', { target: { value: '9.3' } })
  assert.equal(second.nodes[0].pressureMpa, 9.3)
  assert.equal(first.nodes[0].pressureMpa, 8)
  assert.equal(page.rows.value.find(row => row.nodeId === 'end').record, second.nodes[2])
  page.selectCase(first)
  assert.equal(first.nodes[2].pressureMpa, 4)
  assert.equal(second.nodes[2].pressureMpa, 4.1)
  page.number(first.nodes[0], 'supplyRate10k', { target: { value: '' } })
  assert.equal(first.nodes[0].supplyRate10k, null)
  first.operatingAt = '2026/09/'
  assert.match(await render(table, page), /value="2026\/09\/"/)
  assert.equal(page.messages.length, 0)
  first.operatingAt = '2026/09/12 00:17'
  assert.match(await render(table, page), /value="2026\/09\/12 00:17"/)
})

test('adding starts blank, deleting selects a surviving row, and removing all rows leaves an explicit empty draft', () => {
  const state = fixture(), page = bindings(state)
  page.addCase()
  const added = page.activeCase.value
  assert.equal(page.cases.value.length, 3)
  assert.equal(added.operatingAt, null)
  assert.ok(added.nodes.every(node => node.supplyRate10k == null && node.withdrawalRate10k == null && node.pressureMpa == null && node.temperatureC == null))
  page.removeCase(added)
  assert.equal(page.activeCase.value.id, 'second')
  page.removeCase(page.activeCase.value)
  assert.equal(page.activeCase.value.id, 'first')
  page.removeCase(page.activeCase.value)
  assert.equal(page.cases.value.length, 0)
  assert.equal(state.form.boundary.activeCaseId, null)
  page.addCase()
  assert.equal(page.cases.value.length, 1)
})

test('left panel collapses and contains import and standard state without date, topology or data-use controls', async () => {
  const page = bindings(fixture()), expanded = await render(sidebar, page)
  assert.match(expanded, /参数设置/)
  assert.match(expanded, /本地导入/)
  assert.match(expanded, /标准状态/)
  assert.doesNotMatch(expanded, /type="datetime-local"|查看管网拓扑|工况设置|用途|<details|<select/)
  page.collapsed.value = true
  const collapsed = await render(sidebar, page)
  assert.match(collapsed, /aria-label="展开参数设置"/)
  assert.doesNotMatch(collapsed, /<fieldset|本地导入/)
})

test('import requires confirmation before replacing filled cases and cancel preserves measured values', async () => {
  const state = fixture(), before = JSON.stringify(state.form.boundary), imported = [boundary.createBoundaryCase(state.savedGraph)]
  const cancel = bindings(state, async () => { throw new Error('cancel') })
  await cancel.acceptImport({ cases: imported, fileName: '边界.xlsx' })
  assert.equal(JSON.stringify(state.form.boundary), before)
  let confirmations = 0
  const page = bindings(state, async () => { confirmations++ })
  await page.acceptImport({ cases: imported, fileName: '边界.xlsx' })
  assert.equal(confirmations, 1)
  assert.equal(state.form.boundary.activeCaseId, imported[0].id)
  assert.deepEqual(state.form.boundary.cases, imported)
  assert.equal(state.form.boundary.topologyRevision, 3)
  assert.equal(page.importedFileName.value, '边界.xlsx')
})

test('a context or topology change while confirming import cannot overwrite the new boundary data', async () => {
  const state = fixture(), before = JSON.stringify(state.form.boundary)
  const page = bindings(state, async () => { state.context.wellName = '井 B' })
  await page.acceptImport({ cases: [boundary.createBoundaryCase(state.savedGraph)], fileName: '边界.xlsx' })
  assert.equal(JSON.stringify(state.form.boundary), before)
  assert.match(page.messages[0], /已变化/)
})

test('measurement totals include the active operating hour and every measured withdrawal', () => {
  const state=fixture(),page=bindings(state)
  state.form.boundary.cases[0].nodes[1].withdrawalRate10k=2
  assert.equal(page.summary.value.supplyRate10k,10)
  assert.equal(page.summary.value.withdrawalRate10k,12)
  assert.equal(page.summary.value.differenceRate10k,-2)
  page.selectCase(state.form.boundary.cases[1])
  assert.equal(page.summary.value.supplyRate10k,11)
  assert.equal(page.summary.value.withdrawalRate10k,11)
  assert.equal(page.summary.value.differenceRate10k,0)
})

test('adding beyond the storage limit is rejected and deleting a different case preserves the selected operating hour',()=>{
  const state=fixture(),page=bindings(state),second=state.form.boundary.cases[1]
  page.removeCase(second)
  assert.equal(state.form.boundary.activeCaseId,'first')
  while(state.form.boundary.cases.length<boundary.MAX_BOUNDARY_CASES)state.form.boundary.cases.push(boundary.createBoundaryCase(state.savedGraph))
  page.addCase()
  assert.equal(state.form.boundary.cases.length,boundary.MAX_BOUNDARY_CASES)
  assert.equal(state.form.boundary.activeCaseId,'first')
  assert.match(page.messages[0],/最多支持/)
})
