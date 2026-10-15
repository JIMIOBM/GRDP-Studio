import { EROSION_INPUT_FIELDS } from './pipelineErosion.js'
import { formatOperatingTime } from './pipelineTime.js'

export const EROSION_SEGMENT_SHEET = '管道共用参数'
export const EROSION_CASE_SHEET = '工况冲蚀参数'
const META_SHEET = '_erosion_metadata'
const BASE_HEADERS = ['管道名称']
const INPUT_HEADERS = EROSION_INPUT_FIELDS.map(field => `${field.label}（${field.unit}）`)
const blank = value => value == null || (typeof value === 'string' && value.trim() === '')
export function erosionImportContextStamp(graph, revision, context, cases) {
  return JSON.stringify([context?.projectId, context?.gasReservoirId, context?.wellName, revision,
    (graph?.edges || []).map(edge => [edge.id, edge.name]), (cases || []).map(row => [row.id, row.operatingAt])])
}
function sources(graph, revision, context, cases) {
  if (!graph?.edges?.length || !Number.isInteger(revision) || revision <= 0) throw new Error('请先保存管网拓扑。')
  if (!context?.wellName || !context?.projectId || !context?.gasReservoirId) throw new Error('缺少当前项目、气藏或井信息，请重新加载。')
  const edgeIds = new Set(), caseIds = new Set()
  for (const edge of graph.edges) {
    if (!edge?.id || edgeIds.has(edge.id)) throw new Error('管道编号重复或缺失，请重新保存拓扑。')
    edgeIds.add(edge.id)
  }
  for (const row of cases || []) {
    if (!row?.id || caseIds.has(row.id)) throw new Error('工况编号重复或缺失，请重新保存边界条件。')
    caseIds.add(row.id)
  }
  return graph.edges
}
function metadata(graph, revision, context, cases) {
  return [['schema', 'grdp-pipeline-erosion/v3'], ['projectId', String(context.projectId)],
    ['gasReservoirId', String(context.gasReservoirId)], ['wellName', context.wellName], ['topologyRevision', String(revision)],
    ...graph.edges.map((edge, index) => ['edge', String(index + 1), edge.id, edge.name || '']),
    ...(cases || []).map((row, index) => ['case', String(index + 1), row.id, formatOperatingTime(row.operatingAt)])]
}
function pipeCells(edge) { return [edge.name || ''] }
function values(row) { return EROSION_INPUT_FIELDS.map(({ key }) => row[key] ?? '') }
export function erosionImportTemplate(graph, revision, context, cases, settings) {
  const edges = sources(graph, revision, context, cases)
  const defaults = new Map((settings?.segments || []).map(row => [row.edgeId, row]))
  const overrides = new Map((settings?.cases || []).map(row => [`${row.caseId}/${row.edgeId}`, row]))
  return { segments: [[...BASE_HEADERS, ...INPUT_HEADERS], ...edges.map(edge => [...pipeCells(edge), ...values(defaults.get(edge.id) || {})])],
    cases: [['工况时间（只读）', ...BASE_HEADERS, ...INPUT_HEADERS], ...(cases || []).flatMap(row =>
      edges.map(edge => [formatOperatingTime(row.operatingAt), ...pipeCells(edge), ...values(overrides.get(`${row.id}/${edge.id}`) || {})]))],
    metadata: metadata(graph, revision, context, cases) }
}
function textSheet(XLSX, rows) {
  const sheet = XLSX.utils.aoa_to_sheet(rows)
  rows.forEach((row, r) => row.forEach((value, c) => {
    if (typeof value === 'string') sheet[XLSX.utils.encode_cell({ r, c })] = { t: 's', v: value }
  }))
  return sheet
}
export function createErosionImportWorkbook(XLSX, graph, revision, context, cases, settings) {
  const data = erosionImportTemplate(graph, revision, context, cases, settings), workbook = XLSX.utils.book_new()
  for (const [name, rows, widths] of [[EROSION_SEGMENT_SHEET, data.segments, [25, 22, 22, 25]],
    [EROSION_CASE_SHEET, data.cases, [28, 25, 22, 22, 25]], [META_SHEET, data.metadata, []]]) {
    const sheet = textSheet(XLSX, rows); sheet['!cols'] = widths.map(wch => ({ wch }))
    XLSX.utils.book_append_sheet(workbook, sheet, name)
  }
  workbook.Workbook = { Sheets: workbook.SheetNames.map(name => ({ name, Hidden: name === META_SHEET ? 1 : 0 })) }
  return workbook
}
function sheetRows(XLSX, workbook, name, rowCount, columns) {
  const sheet = workbook?.Sheets?.[name]
  if (!sheet) throw new Error(`缺少“${name}”工作表，请使用当前页面下载的模板。`)
  for (const [address, cell] of Object.entries(sheet)) {
    if (address.startsWith('!')) continue
    if (cell?.f != null || cell?.F != null || cell?.t === 'e') throw new Error(`“${name}” ${address} 包含公式或 Excel 错误值，请粘贴为数值。`)
    if (blank(cell?.v)) continue
    if (!/^[A-Z]+[1-9]\d*$/.test(address)) throw new Error(`“${name}”包含无效单元格。`)
    const point = XLSX.utils.decode_cell(address)
    if (point.r >= rowCount || point.c >= columns) throw new Error(`“${name}”包含模板范围外的数据。`)
  }
  return XLSX.utils.sheet_to_json(sheet, { header: 1, raw: true, defval: null, blankrows: true,
    range: { s: { r: 0, c: 0 }, e: { r: rowCount - 1, c: columns - 1 } } })
}
function equalCells(actual, expected, row, count, name) {
  for (let c = 0; c < count; c++) if (String(actual[row]?.[c] ?? '') !== String(expected[row]?.[c] ?? '')) {
    throw new Error(`“${name}”第 ${row + 1} 行的表头、管道或工况信息已变化，请保留模板只读列并重新下载当前模板。`)
  }
}
function inputValues(row, offset, name, line) {
  return Object.fromEntries(EROSION_INPUT_FIELDS.map((field, index) => {
    const value = row[offset + index]
    if (blank(value)) return [field.key, null]
    const number = typeof value === 'number' || (typeof value === 'string' && /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:e[+-]?\d+)?$/i.test(value.trim())) ? Number(value) : NaN
    if (!Number.isFinite(number) || number < 0 || (field.key === 'sandDensityKgM3' ? number <= 0 : number >= 100)) {
      throw new Error(`“${name}”第 ${line} 行：${field.label}应为${field.key === 'sandDensityKgM3' ? '大于 0 的数值' : '0（含）～100（不含）的百分数'}，未知可留空。`)
    }
    return [field.key, number]
  }))
}
function checkFractions(record, base, label) {
  const liquid = record.liquidHoldupPercent ?? base?.liquidHoldupPercent
  const sand = record.sandContentPercent ?? base?.sandContentPercent
  if (liquid != null && sand != null && liquid + sand >= 100) throw new Error(`${label}：持液率与含砂率之和必须小于 100%。`)
}
export function parseErosionImportWorkbook(XLSX, workbook, graph, revision, context, cases) {
  const template = erosionImportTemplate(graph, revision, context, cases, {}), edges = graph.edges
  const meta = sheetRows(XLSX, workbook, META_SHEET, template.metadata.length, 4)
  template.metadata.forEach((_, index) => equalCells(meta, template.metadata, index, 4, '井、拓扑和工况校验信息'))
  const segments = sheetRows(XLSX, workbook, EROSION_SEGMENT_SHEET, template.segments.length, 4)
  const rows = sheetRows(XLSX, workbook, EROSION_CASE_SHEET, template.cases.length, 5)
  equalCells(segments, template.segments, 0, 4, EROSION_SEGMENT_SHEET)
  equalCells(rows, template.cases, 0, 5, EROSION_CASE_SHEET)
  const parsedSegments = edges.map((edge, index) => {
    equalCells(segments, template.segments, index + 1, 1, EROSION_SEGMENT_SHEET)
    const record = { edgeId: edge.id, ...inputValues(segments[index + 1], 1, EROSION_SEGMENT_SHEET, index + 2) }
    checkFractions(record, null, `管道“${edge.name}”`)
    return record
  })
  const parsedCases = (cases || []).flatMap((condition, caseIndex) => edges.map((edge, index) => {
    const r = caseIndex * edges.length + index + 1
    equalCells(rows, template.cases, r, 2, EROSION_CASE_SHEET)
    const record = { caseId: condition.id, edgeId: edge.id, ...inputValues(rows[r], 2, EROSION_CASE_SHEET, r + 1) }
    checkFractions(record, parsedSegments[index], `工况“${formatOperatingTime(condition.operatingAt)}”、管道“${edge.name}”`)
    return record
  })).filter(record => EROSION_INPUT_FIELDS.some(field => record[field.key] != null))
  return { segments: parsedSegments, cases: parsedCases }
}
