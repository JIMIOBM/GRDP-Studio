import test from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { createRenderer, h, nextTick, reactive } from 'vue'
import { parse, compileScript, compileTemplate } from '@vue/compiler-sfc'
import * as XLSX from 'xlsx'
import {
  BOUNDARY_IMPORT_SCHEMA, BOUNDARY_IMPORT_SHEET, BOUNDARY_IMPORT_META_SHEET, BOUNDARY_IMPORT_MAX_CASES,
  boundaryImportTemplate, createBoundaryImportWorkbook, parseBoundaryImportWorkbook,
  parseBoundaryImportRows, parseBoundaryOperatingAt, boundaryImportContextStamp, createBoundaryImportGuard
} from './pipelineBoundaryImport.js'

const context = { projectId: 2, gasReservoirId: 8, wellName: '试验井' }, revision = 3
// The well is intentionally not the first node, and two downstream nodes share a display name.
const graph = { nodes: [
  { id: 'junction-b', name: '节点', type: 'junction' },
  { id: 'well-a', name: '试验井井口', type: 'well' },
  { id: 'terminal-c', name: '节点', type: 'station' }
], edges: [{ id: 'a', source: 'well-a', target: 'junction-b' }, { id: 'b', source: 'junction-b', target: 'terminal-c' }] }
const row = ['2026-09-10 08:00', 10, 8, 40, 0, 7.8, 10, 7.5]
const template = () => boundaryImportTemplate(graph, revision, context)
const rows = (...entries) => [...template().rows.slice(0, 2), ...entries.map(entry => [...entry])]
const workbook = (...entries) => {
  const book = createBoundaryImportWorkbook(XLSX, graph, revision, context)
  if (entries.length) XLSX.utils.sheet_add_aoa(book.Sheets[BOUNDARY_IMPORT_SHEET], entries, { origin: 'A3' })
  return book
}
const read = book => parseBoundaryImportWorkbook(XLSX, book, graph, revision, context)
const atNode = (entry, id) => entry.nodes.find(node => node.nodeId === id)
const serial = (year, month, day, hour, date1904 = false, minute = 0, second = 0, millisecond = 0) => (Date.UTC(year, month - 1, day, hour, minute, second, millisecond)
  - (date1904 ? Date.UTC(1904, 0, 1) : Date.UTC(1899, 11, 30))) / 86400000

test('template has the exact two-level horizontal layout, all saved nodes, blank operating inputs and hidden metadata', () => {
  const result = template(), book = workbook()
  assert.deepEqual(result.rows, [
    ['工况时间', '井口', '', '', '节点', '', '节点', ''],
    ['', '供气量（10⁴m³/d）', '进入管网压力（MPa，绝压）', '入口温度（℃）', '分输量（10⁴m³/d）', '压力（MPa，绝压）', '分输量（10⁴m³/d）', '压力（MPa，绝压）'],
    ['', '', '', '', '', '', '', '']
  ])
  assert.deepEqual(result.merges, [
    { s: { r: 0, c: 0 }, e: { r: 1, c: 0 } }, { s: { r: 0, c: 1 }, e: { r: 0, c: 3 } },
    { s: { r: 0, c: 4 }, e: { r: 0, c: 5 } }, { s: { r: 0, c: 6 }, e: { r: 0, c: 7 } }
  ])
  assert.deepEqual(result.metadata.slice(6).map(record => record[1]), ['well-a', 'junction-b', 'terminal-c'])
  assert.equal(result.metadata[0][1], BOUNDARY_IMPORT_SCHEMA)
  assert.equal(book.Workbook.Sheets.find(sheet => sheet.name === BOUNDARY_IMPORT_META_SHEET).Hidden, 1)
  assert.throws(() => read(book), /没有可导入的工况/)
})

test('all rows import atomically into independent cases keyed by node IDs with only canonical numeric inputs', () => {
  const second = ['2026-09-10T09:00:00', '0', '', -20, '', '', 0, 0.5]
  const table = rows(row, second), before = structuredClone({ table, graph })
  const result = parseBoundaryImportRows(table, graph)
  assert.equal(result.length, 2)
  assert.ok(result[0].id && result[1].id && result[0].id !== result[1].id)
  assert.equal(result[0].operatingAt, '2026-09-10T08:00')
  assert.equal(result[1].operatingAt, '2026-09-10T09:00')
  assert.deepEqual(atNode(result[0], 'well-a'), { nodeId: 'well-a', supplyRate10k: 10,
    withdrawalRate10k: null, pressureMpa: 8, temperatureC: 40 })
  assert.deepEqual(atNode(result[0], 'junction-b'), { nodeId: 'junction-b', supplyRate10k: null,
    withdrawalRate10k: 0, pressureMpa: 7.8, temperatureC: null })
  for (const entry of result) for (const node of entry.nodes) {
    assert.deepEqual(Object.keys(node), ['nodeId', 'supplyRate10k', 'withdrawalRate10k', 'pressureMpa', 'temperatureC'])
  }
  assert.equal(atNode(result[0], 'terminal-c').withdrawalRate10k, 10)
  assert.equal(atNode(result[1], 'well-a').supplyRate10k, 0)
  assert.equal(atNode(result[1], 'well-a').pressureMpa, null)
  assert.equal(atNode(result[1], 'junction-b').withdrawalRate10k, null)
  result[0].nodes[0].pressureMpa = 100
  assert.deepEqual({ table, graph }, before)
  assert.notEqual(result[1].nodes[0].pressureMpa, 100)
  const bad = rows(row, ['2026-09-10 09:00', 1, -1])
  const unchanged = structuredClone(bad)
  assert.throws(() => parseBoundaryImportRows(bad, graph), /第 4 行/)
  assert.deepEqual(bad, unchanged)
})

test('decimal and scientific values accept zero flows and blank values without accepting invalid types or units', () => {
  const valid = parseBoundaryImportRows(rows(['2026-09-10 08:00', '1e1', '.8e1', '-2.5e1', '+0', '5', null, '']), graph)[0]
  assert.equal(atNode(valid, 'well-a').supplyRate10k, 10)
  assert.equal(atNode(valid, 'well-a').temperatureC, -25)
  for (const [column, values] of [
    [1, [-1, '-0.1']], [4, [-1]], [6, [-1]], [2, [0, -1, '1e-999']],
    [5, [0, -1]], [7, [0, -1]], [3, [-273.15, -300]]
  ]) for (const value of values) {
    const data = [...row]; data[column] = value
    assert.throws(() => parseBoundaryImportRows(rows(data), graph), `accepted ${value} in column ${column}`)
  }
  for (const column of [1, 2, 3, 4, 5, 6, 7]) for (const value of [true, false, {}, [], new Date(),
    'NaN', 'Infinity', '0x10', '1,000', '8 MPa', '=1+1', Infinity, NaN, '1e309']) {
    const data = [...row]; data[column] = value
    assert.throws(() => parseBoundaryImportRows(rows(data), graph), `accepted ${String(value)} in column ${column}`)
  }
})

test('operating time preserves arbitrary minutes in local text, date cells and both Excel date systems', () => {
  for (const value of ['2026-09-10 08:37', '2026-09-10T08:37:00', '2026/09/10 08:37', serial(2026, 9, 10, 8, false, 37),
    new Date(Date.UTC(2026, 8, 10, 8, 37))]) {
    assert.equal(parseBoundaryOperatingAt(value), '2026-09-10T08:37')
  }
  assert.equal(parseBoundaryOperatingAt(serial(2026, 9, 10, 8, true, 37), 3, { date1904: true }), '2026-09-10T08:37')
  assert.equal(parseBoundaryOperatingAt(0, 3, { date1904: true }), '1904-01-01T00:00')
  assert.equal(parseBoundaryOperatingAt(1), '1900-01-01T00:00')
  assert.equal(parseBoundaryOperatingAt(59), '1900-02-28T00:00')
  assert.equal(parseBoundaryOperatingAt(61), '1900-03-01T00:00')
  assert.equal(parseBoundaryOperatingAt('0001-01-01 00:00'), '0001-01-01T00:00')
  assert.equal(parseBoundaryOperatingAt('2024-02-29 23:59'), '2024-02-29T23:59')
  for (const value of [true, false, {}, [], new Date(NaN), 0, -1, 60, 60.5, NaN, Infinity]) {
    assert.throws(() => parseBoundaryOperatingAt(value), `accepted invalid time ${String(value)}`)
  }
})

test('unfinished or invalid ordinary time text is preserved as an editable draft and seconds are not silently removed', () => {
  for (const value of ['', null, undefined, '稍后补充', '2026/9/10 8:37', '2026-02-29 00:00', '2026-04-31 00:00',
    '2026-13-01 00:00', '2026-09-10', '2026-09-10 24:00', '2026-09-10 08:60',
    '2026-09-10 08:37:01', '2026-09-10T08:37Z', '2026-09-10T08:37+08:00', '0000-01-01 00:00']) {
    assert.equal(parseBoundaryOperatingAt(value), value)
  }
  assert.equal(parseBoundaryOperatingAt(serial(2026, 9, 10, 8, false, 37, 1)), '2026-09-10T08:37:01')
  assert.equal(parseBoundaryOperatingAt(serial(2026, 9, 10, 8, false, 37, 1, 125)), '2026-09-10T08:37:01.125')
  const draft = parseBoundaryImportRows(rows(['日期待确认', 10, 8], ['', 11, 9], ['日期待确认', 12, 10]), graph)
  assert.deepEqual(draft.map(entry => entry.operatingAt), ['日期待确认', '', '日期待确认'])
  assert.deepEqual(draft.map(entry => atNode(entry, 'well-a').supplyRate10k), [10, 11, 12])
})

test('Excel serial date interpretation is identical in Shanghai and a DST timezone', () => {
  const moduleUrl = new URL('./pipelineBoundaryImport.js', import.meta.url).href
  const code = `import {parseBoundaryOperatingAt as p} from ${JSON.stringify(moduleUrl)}; console.log(JSON.stringify([p(${serial(2026, 3, 8, 2, false, 17)}),p(${serial(2026, 11, 1, 1, false, 43)}),p('2026/09/10 08:37')]));`
  const outputs = ['Asia/Shanghai', 'America/New_York', 'UTC'].map(TZ => {
    const child = spawnSync(process.execPath, ['--input-type=module', '-e', code], { encoding: 'utf8', env: { ...process.env, TZ } })
    assert.equal(child.status, 0, child.stderr)
    return JSON.parse(child.stdout)
  })
  outputs.forEach(value => assert.deepEqual(value, ['2026-03-08T02:17', '2026-11-01T01:43', '2026-09-10T08:37']))
})

test('duplicate valid minutes across text and serial formats are rejected while missing time remains editable', () => {
  const first = ['2026/09/10 08:37', ...row.slice(1)]
  for (const time of ['2026-09-10T08:37:00', serial(2026, 9, 10, 8, false, 37)]) {
    assert.throws(() => parseBoundaryImportRows(rows(first, [time]), graph), /重复/)
  }
  assert.equal(parseBoundaryImportRows(rows(first, ['2026/09/10 08:38']), graph).length, 2)
  assert.equal(parseBoundaryImportRows(rows(['', 1, 8]), graph)[0].operatingAt, '')
  assert.equal(parseBoundaryImportRows(rows([], row, ['', null, ' ']), graph).length, 1)
  assert.throws(() => parseBoundaryImportRows(rows([], ['', null]), graph), /没有可导入/)
})

test('up to 744 minute cases import, and a 745th row fails without a partial result', () => {
  const start = serial(2026, 9, 1, 0)
  const entries = Array.from({ length: BOUNDARY_IMPORT_MAX_CASES }, (_, index) => [start + index / 1440])
  assert.equal(read(workbook(...entries)).length, 744)
  assert.throws(() => parseBoundaryImportRows(rows(...entries, [start + 744 / 1440]), graph), /最多/)
  assert.throws(() => read(workbook(...entries, [start + 744 / 1440])), /最多/)
})

test('real XLSX and XLS files preserve topology metadata, values, blank fields, merges, and numeric dates', () => {
  for (const bookType of ['xlsx', 'biff8']) {
    const book = workbook(row, [serial(2026, 9, 10, 9, false, 26), 0, '', -10, 0, '', null, 5])
    book.Sheets[BOUNDARY_IMPORT_SHEET].A4.z = 'yyyy/mm/dd hh:mm'
    const restored = XLSX.read(XLSX.write(book, { type: 'buffer', bookType }), { type: 'buffer', cellFormula: true, cellDates: false })
    const result = read(restored)
    assert.equal(result.length, 2)
    assert.equal(result[1].operatingAt, '2026-09-10T09:26')
    assert.equal(atNode(result[1], 'well-a').pressureMpa, null)
    assert.equal(atNode(result[1], 'well-a').supplyRate10k, 0)
    assert.equal(atNode(result[1], 'well-a').temperatureC, -10)
    assert.equal(atNode(result[1], 'terminal-c').pressureMpa, 5)
    assert.deepEqual(restored.Sheets[BOUNDARY_IMPORT_SHEET]['!merges'], template().merges)
    assert.equal(restored.Workbook.Sheets.find(sheet => sheet.name === BOUNDARY_IMPORT_META_SHEET).Hidden, 1)
  }
})

test('1904 epoch workbooks and native date cells are read without treating dates as UTC instants', () => {
  for (const bookType of ['xlsx', 'biff8']) {
    const book = workbook([serial(2026, 9, 10, 8, true, 37), 10, 8, 40])
    book.Workbook.WBProps = { date1904: true }
    book.Sheets[BOUNDARY_IMPORT_SHEET].A3.z = 'yyyy/mm/dd hh:mm'
    const restored = XLSX.read(XLSX.write(book, { type: 'buffer', bookType }), { type: 'buffer', cellFormula: true, cellDates: false })
    assert.equal(read(restored)[0].operatingAt, '2026-09-10T08:37')
  }
  // A predecoded date cell follows the same parser path; numeric/date-format file roundtrips are covered above.
  const dated = workbook(row)
  dated.Sheets[BOUNDARY_IMPORT_SHEET].A3 = { t: 'd', v: new Date(Date.UTC(2026, 8, 10, 8, 37)), z: 'yyyy/mm/dd hh:mm' }
  assert.equal(read(dated)[0].operatingAt, '2026-09-10T08:37')
})

test('templates cannot be transplanted to another well, project, reservoir, topology revision or same-named node IDs', () => {
  const source = workbook(row)
  for (const changed of [{ ...context, projectId: 3 }, { ...context, gasReservoirId: 9 }, { ...context, wellName: '另一井' }]) {
    assert.throws(() => parseBoundaryImportWorkbook(XLSX, source, graph, revision, changed), /不一致/)
  }
  assert.throws(() => parseBoundaryImportWorkbook(XLSX, source, graph, revision + 1, context), /不一致/)
  for (const mutate of [
    changed => { changed.nodes[0].id = 'same-name-new-id' },
    changed => { changed.nodes[0].name = '改名节点' },
    changed => { changed.nodes[0].type = 'compressor' },
    changed => { [changed.nodes[0], changed.nodes[2]] = [changed.nodes[2], changed.nodes[0]] }
  ]) {
    const changed = structuredClone(graph); mutate(changed)
    assert.throws(() => parseBoundaryImportWorkbook(XLSX, source, changed, revision, context), /不一致/)
  }
  for (const change of [
    book => { delete book.Sheets[BOUNDARY_IMPORT_META_SHEET] },
    book => { book.Sheets[BOUNDARY_IMPORT_META_SHEET].B1.v = 'another-schema' },
    book => { book.Sheets[BOUNDARY_IMPORT_META_SHEET].B7.v = 'another-well-id' },
    book => { delete book.Sheets[BOUNDARY_IMPORT_SHEET] }
  ]) { const book = workbook(row); change(book); assert.throws(() => read(book)) }
})

test('two-level headers must keep exact node grouping, units and columns', () => {
  for (const mutate of [
    table => { table[0][1] = '井口供气量' }, table => { table[1][2] = '进入管网压力（kPa）' },
    table => { table[1][3] = '入口温度（K）' }, table => { table[0][4] = '未知节点' },
    table => { table[0].push('额外节点') }, table => { table[2].push(0) }
  ]) {
    const table = rows(row); mutate(table)
    assert.throws(() => parseBoundaryImportRows(table, graph))
  }
  const named = structuredClone(graph); named.nodes[0].name = '=SUM(A1)'; named.nodes[2].name = ' 节点 ';
  const book = createBoundaryImportWorkbook(XLSX, named, revision, context)
  assert.deepEqual(book.Sheets[BOUNDARY_IMPORT_SHEET].E1, { t: 's', v: '=SUM(A1)' })
  XLSX.utils.sheet_add_aoa(book.Sheets[BOUNDARY_IMPORT_SHEET], [row], { origin: 'A3' })
  assert.equal(parseBoundaryImportWorkbook(XLSX, book, named, revision, context).length, 1)
})

test('formulas and Excel error cells in data or hidden metadata are rejected even when a cached number exists', () => {
  for (const sheetName of [BOUNDARY_IMPORT_SHEET, BOUNDARY_IMPORT_META_SHEET]) {
    for (const cell of [{ t: 'n', v: 8, f: '4+4' }, { t: 'n', v: 8, F: 'B3:C3' }, { t: 'e', v: 7 }]) {
      const book = workbook(row); book.Sheets[sheetName].B3 = cell
      assert.throws(() => read(book), /公式|错误值/)
    }
  }
  const book = workbook(row); book.Sheets[BOUNDARY_IMPORT_SHEET].XFD1000000 = { t: 'n', v: 8, f: '4+4' }
  assert.throws(() => read(book), /公式/)
  for (const cell of [{ t: 'n', v: serial(2026, 9, 10, 8, false, 37), f: 'NOW()' }, { t: 'e', v: 7 }]) {
    const dated = workbook(row); dated.Sheets[BOUNDARY_IMPORT_SHEET].A3 = cell
    assert.throws(() => read(dated), /公式|错误值/)
  }
})

test('formatting-only inflated worksheet ranges do not allocate empty rows, and out-of-template values cannot vanish', () => {
  const book = workbook(row), sheet = book.Sheets[BOUNDARY_IMPORT_SHEET]
  sheet['!ref'] = 'A1:XFD1048576'; sheet.XFD1048576 = { t: 'z' }
  assert.equal(read(book).length, 1)
  sheet.I3 = { t: 'n', v: 0 }
  assert.throws(() => read(book), /范围外/)
  delete sheet.I3; sheet.A747 = { t: 's', v: '2026-10-10 08:00' }
  assert.throws(() => read(book), /范围外/)
})

test('pending async imports are cancelled on close/reopen, selection changes and context changes', async () => {
  const guard = createBoundaryImportGuard(), stamp = boundaryImportContextStamp(graph, revision, context)
  const token = guard.begin(stamp)
  assert.equal(guard.isCurrent(token, stamp, true), true)
  assert.equal(guard.isCurrent(token, stamp, false), false)
  for (const changed of [boundaryImportContextStamp(graph, revision + 1, context),
    boundaryImportContextStamp(graph, revision, { ...context, wellName: '另一井' })]) assert.equal(guard.isCurrent(token, changed, true), false)
  let complete, applied = false
  const pending = new Promise(resolve => { complete = resolve }).then(() => {
    if (guard.isCurrent(token, stamp, true)) applied = true
  })
  guard.cancel(); guard.begin(stamp) // Close then reopen the same context before file.arrayBuffer completes.
  complete(); await pending
  assert.equal(applied, false)
  const newer = guard.begin(stamp)
  assert.equal(guard.isCurrent(token, stamp, true), false)
  assert.equal(guard.isCurrent(newer, stamp, true), true)
})

test('missing or invalid saved topology and scope cannot create an unbound template', () => {
  for (const invalid of [null, { nodes: [] }, { nodes: graph.nodes.filter(node => node.type !== 'well') },
    { nodes: [...graph.nodes, { id: 'well-b', type: 'well' }] }, { nodes: [...graph.nodes, graph.nodes[0]] }]) {
    assert.throws(() => boundaryImportTemplate(invalid, revision, context))
  }
  for (const invalidRevision of [0, -1, 1.5, null]) assert.throws(() => boundaryImportTemplate(graph, invalidRevision, context))
  for (const invalidContext of [{}, { ...context, projectId: 0 }, { ...context, wellName: '' }]) {
    assert.throws(() => boundaryImportTemplate(graph, revision, invalidContext))
  }
})

test('the actual dialog imports once after validation and discards pending reads after close or context changes', async () => {
  const source = readFileSync(new URL('../views/PipelineCapacity/PipelineBoundaryImportDialog.vue', import.meta.url), 'utf8')
  const { descriptor, errors } = parse(source)
  assert.deepEqual(errors, [])
  const script = compileScript(descriptor, { id: 'boundary-import-test' })
  assert.deepEqual(compileTemplate({ id: 'boundary-import-test', source: descriptor.template.content,
    filename: 'PipelineBoundaryImportDialog.vue', compilerOptions: { bindingMetadata: script.bindings } }).errors, [])
  const code = script.content.replace(/from (['"])([^'"]+)\1/g, (_, quote, path) => {
    const resolved = path.startsWith('@/utils/') ? new URL(`./${path.slice('@/utils/'.length)}.js`, import.meta.url).href : import.meta.resolve(path)
    return `from ${JSON.stringify(resolved)}`
  }).replaceAll("import('xlsx')", `import(${JSON.stringify(import.meta.resolve('xlsx'))})`)
  const { default: Dialog } = await import(`data:text/javascript;base64,${Buffer.from(code).toString('base64')}`)
  // Exercise the real component's setup/watchers/emits with a minimal renderer; no DOM snapshot assertions.
  Dialog.render = () => null
  const renderer = createRenderer({
    createElement: () => ({ children: [] }), createText: () => ({}), createComment: () => ({}),
    insert: (child, parent) => parent.children.push(child), remove() {}, parentNode: () => null,
    nextSibling: () => null, setText() {}, setElementText() {}, patchProp() {}
  })
  const previousWindow = globalThis.window
  globalThis.window = { addEventListener() {}, removeEventListener() {} }
  try {
    for (const action of ['success', 'invalid-time-draft', 'close-reopen', 'well-change', 'topology-change', 'invalid-data']) {
      const received = [], state = reactive({ modelValue: true, graph: structuredClone(graph), topologyRevision: revision, context: { ...context } })
      const app = renderer.createApp({ setup: () => () => h(Dialog, { ...state,
        'onUpdate:modelValue': value => { state.modelValue = value }, onImported: value => received.push(value) }) })
      app.mount({ children: [] })
      const setup = app._instance.subTree.component.setupState
      let finish
      const file = { name: '边界条件.xlsx', size: 100, arrayBuffer: () => new Promise(resolve => { finish = resolve }) }
      setup.setFile(file)
      const pending = setup.confirmImport()
      assert.equal(setup.busy, true)
      if (action === 'close-reopen') {
        setup.close(); await nextTick(); state.modelValue = true; await nextTick()
      } else if (action === 'well-change') {
        state.context = { ...context, wellName: '其他井' }; await nextTick()
      } else if (action === 'topology-change') {
        state.topologyRevision++; await nextTick()
      }
      const book = workbook(row)
      if (action === 'invalid-data') book.Sheets[BOUNDARY_IMPORT_SHEET].C3.v = -1
      if (action === 'invalid-time-draft') book.Sheets[BOUNDARY_IMPORT_SHEET].A3.v = '2026/09/待确认'
      finish(XLSX.write(book, { type: 'array', bookType: 'xlsx' }))
      await pending; await nextTick()
      assert.equal(setup.busy, false)
      if (action === 'success' || action === 'invalid-time-draft') {
        assert.equal(received.length, 1)
        assert.equal(received[0].fileName, file.name)
        assert.equal(received[0].cases[0].operatingAt, action === 'success' ? '2026-09-10T08:00' : '2026/09/待确认')
        assert.equal(state.modelValue, false)
      } else {
        assert.equal(received.length, 0)
        if (action === 'invalid-data') assert.match(setup.error, /第 3 行.*进入管网压力/)
        if (action.endsWith('change')) assert.match(setup.error, /已变化/)
      }
      app.unmount()
    }
  } finally {
    if (previousWindow === undefined) delete globalThis.window
    else globalThis.window = previousWindow
  }
})
