import { createBoundaryCase } from './pipelineBoundary.js'
import { normalizeOperatingTime, formatOperatingTime, parseOperatingTime } from './pipelineTime.js'

export const BOUNDARY_IMPORT_SCHEMA = 'grdp-pipeline-boundary/v1'
export const BOUNDARY_IMPORT_SHEET = '边界条件'
export const BOUNDARY_IMPORT_META_SHEET = '_boundary_metadata'
export const BOUNDARY_IMPORT_MAX_CASES = 744
const blank = value => value == null || (typeof value === 'string' && value.trim() === '')
const decimal = /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:e[+-]?\d+)?$/i
const pad = (value, length = 2) => String(value).padStart(length, '0')

export function boundaryImportNodes(graph) {
  if (!Array.isArray(graph?.nodes) || !graph.nodes.length) throw new Error('请先保存当前井的管网拓扑，再下载或导入模板。')
  const ids = new Set()
  for (const node of graph.nodes) {
    if (!node || typeof node.id !== 'string' || !node.id.trim() || typeof node.type !== 'string'
      || ids.has(node.id)) throw new Error('当前拓扑的节点编号或类型无效，请重新检查并保存拓扑。')
    ids.add(node.id)
  }
  const wells = graph.nodes.filter(node => node.type === 'well')
  if (wells.length !== 1) throw new Error('边界条件模板要求当前拓扑有且只有一个井口节点。')
  return [wells[0], ...graph.nodes.filter(node => node.type !== 'well')]
}

/** Includes scope and topology contents so an async import cannot cross a navigation or topology edit. */
export function boundaryImportContextStamp(graph, topologyRevision, context) {
  return JSON.stringify([context?.projectId ?? null, context?.gasReservoirId ?? null,
    context?.wellName ?? null, topologyRevision ?? null, graph ?? null])
}

/** Tokens invalidate pending file reads/downloads, including close-and-reopen on the same well. */
export function createBoundaryImportGuard() {
  let operation = 0
  return {
    begin(stamp) { return { operation: ++operation, stamp } },
    cancel() { operation++ },
    isCurrent(token, stamp, visible) { return Boolean(visible) && token?.operation === operation && token.stamp === stamp }
  }
}

function headers(nodes) {
  const first = ['工况时间', '井口', '', ''], second = ['', '供气量（10⁴m³/d）', '进入管网压力（MPa，绝压）', '入口温度（℃）']
  for (const node of nodes.slice(1)) {
    first.push(String(node.name || node.id), '')
    second.push('分输量（10⁴m³/d）', '压力（MPa，绝压）')
  }
  return [first, second]
}

function metadata(nodes, topologyRevision, context) {
  if (!Number.isInteger(topologyRevision) || topologyRevision <= 0) throw new Error('缺少已保存拓扑的版本，请重新加载边界条件。')
  for (const key of ['projectId', 'gasReservoirId']) {
    if (!Number.isSafeInteger(Number(context?.[key])) || Number(context[key]) <= 0) throw new Error('缺少当前项目或气藏信息，请重新加载边界条件。')
  }
  if (typeof context?.wellName !== 'string' || !context.wellName.trim()) throw new Error('缺少当前井名称，请重新加载边界条件。')
  return [
    ['schema', BOUNDARY_IMPORT_SCHEMA],
    ['projectId', String(context.projectId)],
    ['gasReservoirId', String(context.gasReservoirId)],
    ['wellName', context.wellName],
    ['topologyRevision', String(topologyRevision)],
    ['nodeOrder', 'nodeId', 'nodeType', 'nodeName'],
    ...nodes.map((node, index) => [String(index + 1), node.id, node.type, String(node.name || node.id)])
  ]
}

export function boundaryImportTemplate(graph, topologyRevision, context) {
  const nodes = boundaryImportNodes(graph), rows = headers(nodes), width = rows[0].length
  const merges = [{ s: { r: 0, c: 0 }, e: { r: 1, c: 0 } }, { s: { r: 0, c: 1 }, e: { r: 0, c: 3 } }]
  nodes.slice(1).forEach((_, index) => merges.push({ s: { r: 0, c: 4 + index * 2 }, e: { r: 0, c: 5 + index * 2 } }))
  return { rows: [...rows, Array(width).fill('')], metadata: metadata(nodes, topologyRevision, context),
    merges, widths: [24, 24, 32, 20, ...nodes.slice(1).flatMap(() => [24, 26])] }
}

function textCells(XLSX, rows) {
  const sheet = XLSX.utils.aoa_to_sheet(rows)
  rows.forEach((row, r) => row.forEach((value, c) => {
    // A node name beginning with '=' is a label, never an Excel formula.
    if (typeof value === 'string') sheet[XLSX.utils.encode_cell({ r, c })] = { t: 's', v: value }
  }))
  return sheet
}

export function createBoundaryImportWorkbook(XLSX, graph, topologyRevision, context) {
  const template = boundaryImportTemplate(graph, topologyRevision, context)
  const sheet = textCells(XLSX, template.rows)
  sheet['!merges'] = template.merges
  sheet['!cols'] = template.widths.map(wch => ({ wch }))
  sheet['!rows'] = [{ hpt: 26 }, { hpt: 34 }]
  const workbook = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(workbook, sheet, BOUNDARY_IMPORT_SHEET)
  XLSX.utils.book_append_sheet(workbook, textCells(XLSX, template.metadata), BOUNDARY_IMPORT_META_SHEET)
  workbook.Workbook = { Sheets: [{ name: BOUNDARY_IMPORT_SHEET, Hidden: 0 }, { name: BOUNDARY_IMPORT_META_SHEET, Hidden: 1 }] }
  return workbook
}

function dateText(year, month, day, hour, minute = 0, second = 0, millisecond = 0) {
  if (![year, month, day, hour, minute, second, millisecond].every(Number.isInteger) || year < 1 || year > 9999) return null
  const date = new Date(0)
  date.setUTCFullYear(year, month - 1, day)
  date.setUTCHours(hour, minute, second, millisecond)
  if (date.getUTCFullYear() !== year || date.getUTCMonth() + 1 !== month || date.getUTCDate() !== day
    || date.getUTCHours() !== hour || date.getUTCMinutes() !== minute || date.getUTCSeconds() !== second
    || date.getUTCMilliseconds() !== millisecond) return null
  // Nonzero seconds stay visible for correction at save; never truncate an Excel timestamp to minutes.
  const tail = second || millisecond ? `:${pad(second)}${millisecond ? `.${pad(millisecond, 3)}` : ''}` : ''
  return `${pad(year, 4)}-${pad(month)}-${pad(day)}T${pad(hour)}:${pad(minute)}${tail}`
}

/** Text may be an unfinished draft. Excel serials are wall-clock values, independent of machine timezone. */
export function parseBoundaryOperatingAt(value, line = 0, { date1904 = false } = {}) {
  if (typeof value === 'string' || value == null) return normalizeOperatingTime(value)
  const label = line ? `第 ${line} 行：` : ''
  let result = null
  if (typeof value === 'number' && Number.isFinite(value)) {
    // Excel stores a fraction of a day; round only floating-point noise below one millisecond.
    const milliseconds = Math.round(value * 86400000)
    if (Number.isSafeInteger(milliseconds) && value >= (date1904 ? 0 : 1)) {
      const days = Math.floor(milliseconds / 86400000), withinDay = milliseconds % 86400000
      if (date1904 || days !== 60) { // 1900-02-29 is Excel's fictitious leap day.
        const epoch = date1904 ? Date.UTC(1904, 0, 1) : Date.UTC(1899, 11, 31)
        const date = new Date(epoch + (days - (!date1904 && days > 60 ? 1 : 0)) * 86400000 + withinDay)
        result = dateText(date.getUTCFullYear(), date.getUTCMonth() + 1, date.getUTCDate(), date.getUTCHours(),
          date.getUTCMinutes(), date.getUTCSeconds(), date.getUTCMilliseconds())
      }
    }
  } else if (value instanceof Date && Number.isFinite(value.getTime())) {
    result = dateText(value.getUTCFullYear(), value.getUTCMonth() + 1, value.getUTCDate(), value.getUTCHours(),
      value.getUTCMinutes(), value.getUTCSeconds(), value.getUTCMilliseconds())
  }
  if (!result) throw new Error(`${label}Excel 日期值无效，无法确定工况时间；请改为 YYYY/MM/DD HH:mm 文本后导入。`)
  return result
}

function number(value, label, line, kind) {
  if (blank(value)) return null
  const numeric = typeof value === 'number' || (typeof value === 'string' && decimal.test(value.trim()))
  const parsed = numeric ? Number(value) : NaN
  if (!Number.isFinite(parsed) || (kind === 'flow' && parsed < 0) || (kind === 'pressure' && parsed <= 0)
    || (kind === 'temperature' && parsed <= -273.15)) {
    const rule = kind === 'flow' ? '非负有限数值' : kind === 'pressure' ? '大于 0 的有限绝压（MPa）' : '高于 −273.15 ℃的有限数值'
    throw new Error(`第 ${line} 行：${label}须为${rule}，未知时可留空，不能填写公式或其他文本。`)
  }
  return parsed
}

function checkHeaders(rows, expected) {
  if (!Array.isArray(rows) || rows.length < 2) throw new Error('缺少两层表头，请使用当前井边界条件模板。')
  for (let r = 0; r < 2; r++) {
    const row = rows[r]
    if (!Array.isArray(row) || expected[r].some((value, c) => String(row[c] ?? '') !== value)
      || row.slice(expected[r].length).some(value => !blank(value))) {
      throw new Error('边界条件表头、节点顺序或单位与当前拓扑模板不一致，请重新下载模板。')
    }
  }
}

/** Detached parsing only. Caller must validate workbook metadata before applying returned cases. */
export function parseBoundaryImportRows(rows, graph, { date1904 = false } = {}) {
  const nodes = boundaryImportNodes(graph), expected = headers(nodes), width = expected[0].length
  checkHeaders(rows, expected)
  const found = new Set(), parsed = []
  for (let r = 2; r < rows.length; r++) {
    const row = rows[r], line = r + 1
    if (!Array.isArray(row) || row.every(blank)) continue
    if (parsed.length >= BOUNDARY_IMPORT_MAX_CASES) throw new Error(`每次最多导入 ${BOUNDARY_IMPORT_MAX_CASES} 个工况。`)
    if (row.slice(width).some(value => !blank(value))) throw new Error(`第 ${line} 行：包含当前拓扑模板以外的数据列。`)
    const operatingAt = parseBoundaryOperatingAt(row[0], line, { date1904 })
    const instant = parseOperatingTime(operatingAt)
    if (instant != null) {
      if (found.has(instant)) throw new Error(`第 ${line} 行：工况时间 ${formatOperatingTime(operatingAt)} 重复。`)
      found.add(instant)
    }
    const values = new Map([[nodes[0].id, {
      supplyRate10k: number(row[1], '井口供气量', line, 'flow'),
      pressureMpa: number(row[2], '进入管网压力', line, 'pressure'),
      temperatureC: number(row[3], '入口温度', line, 'temperature')
    }]])
    nodes.slice(1).forEach((node, index) => values.set(node.id, {
      withdrawalRate10k: number(row[4 + 2 * index], `${node.name || node.id} 分输量`, line, 'flow'),
      pressureMpa: number(row[5 + 2 * index], `${node.name || node.id} 压力`, line, 'pressure')
    }))
    parsed.push({ operatingAt, values })
  }
  if (!parsed.length) throw new Error('没有可导入的工况，请至少填写一行工况数据。')
  // Create cases only after the entire file passes validation; never mutate the current boundary draft.
  return parsed.map(({ operatingAt, values }) => {
    const entry = createBoundaryCase(graph)
    return { ...entry, operatingAt, nodes: entry.nodes.map(node => ({ ...node, ...values.get(node.nodeId) })) }
  })
}

function sheetRows(XLSX, sheet, rowLimit, columnLimit, label) {
  if (!sheet || typeof sheet !== 'object') throw new Error(`文件缺少${label}工作表，请重新下载当前井模板。`)
  let lastRow = 0
  for (const [address, cell] of Object.entries(sheet)) {
    if (address.startsWith('!')) continue
    if (cell?.f != null || cell?.F != null) throw new Error(`${label}单元格 ${address} 包含公式，请粘贴为数值后再导入。`)
    if (cell?.t === 'e') throw new Error(`${label}单元格 ${address} 包含 Excel 错误值，请修正后再导入。`)
    if (blank(cell?.v)) continue
    if (!/^[A-Z]+[1-9]\d*$/.test(address)) throw new Error(`${label}包含无效单元格地址。`)
    const position = XLSX.utils.decode_cell(address)
    if (position.r >= rowLimit || position.c >= columnLimit) throw new Error(`${label}包含模板范围外的数据，工况最多 ${BOUNDARY_IMPORT_MAX_CASES} 行，请移除多余行列。`)
    lastRow = Math.max(lastRow, position.r)
  }
  // Ignore formatting-only cells outside the data area without allocating a million empty Excel rows.
  return XLSX.utils.sheet_to_json(sheet, { header: 1, raw: true, defval: null, blankrows: true,
    range: { s: { r: 0, c: 0 }, e: { r: lastRow, c: columnLimit - 1 } } })
}

export function parseBoundaryImportWorkbook(XLSX, workbook, graph, topologyRevision, context) {
  const template = boundaryImportTemplate(graph, topologyRevision, context)
  const actual = sheetRows(XLSX, workbook?.Sheets?.[BOUNDARY_IMPORT_META_SHEET], template.metadata.length, 4, '拓扑校验信息')
  if (actual.length !== template.metadata.length || template.metadata.some((row, r) =>
    Array.from({ length: 4 }, (_, c) => c).some(c => String(actual[r]?.[c] ?? '') !== String(row[c] ?? '')))) {
    throw new Error('模板的项目、气藏、井、节点编号或拓扑版本与当前页面不一致，请下载当前井最新模板；请勿修改隐藏的拓扑校验信息。')
  }
  const rows = sheetRows(XLSX, workbook?.Sheets?.[BOUNDARY_IMPORT_SHEET], BOUNDARY_IMPORT_MAX_CASES + 2, template.rows[0].length, '边界条件')
  const rawEpoch = workbook?.Workbook?.WBProps?.date1904
  if (![undefined, null, false, true, 0, 1, '0', '1', 'false', 'true'].includes(rawEpoch)) throw new Error('Excel 日期系统标记无效，请重新保存文件后导入。')
  const date1904 = [true, 1, '1', 'true'].includes(rawEpoch)
  return parseBoundaryImportRows(rows, graph, { date1904 })
}
