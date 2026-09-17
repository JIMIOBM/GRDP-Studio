export const isTemperatureImportBlank = value => value == null || (typeof value === 'string' && value.trim() === '')

export function temperatureImportEdges(graph, requireDiameter = false) {
  const edges = graph?.edges
  if (!Array.isArray(edges) || !edges.length) throw new Error('当前已保存拓扑没有管道，请先保存管网拓扑。')
  const ids = new Set()
  for (const edge of edges) {
    if (!edge?.id || typeof edge.name !== 'string' || !edge.name.trim() || ids.has(edge.id)) {
      throw new Error('已保存拓扑的管道编号或名称无效，请重新检查拓扑。')
    }
    if (requireDiameter && (typeof edge.parameters?.diameterMm !== 'number'
      || !Number.isFinite(edge.parameters.diameterMm) || edge.parameters.diameterMm <= 0)) {
      throw new Error(`管道“${edge.name}”缺少有效内径，请先在管网拓扑结构中补全并保存。`)
    }
    ids.add(edge.id)
  }
  return edges
}

/** Numeric cells and decimal/scientific text are accepted without coercing dates, booleans or formulas. */
export function temperatureImportNumber(value, label, line, { optional = false, minExclusive = null } = {}) {
  if (isTemperatureImportBlank(value)) {
    if (optional) return null
    throw new Error(`第 ${line} 行：${label}不能为空，须填写有效数值。`)
  }
  const decimal = typeof value === 'number' || (typeof value === 'string'
    && /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:e[+-]?\d+)?$/i.test(value.trim()))
  const number = decimal ? Number(value) : NaN
  if (!Number.isFinite(number) || (minExclusive != null && number <= minExclusive)) {
    const range = minExclusive == null ? '有限数字' : `大于 ${minExclusive} 的有限数字`
    throw new Error(`第 ${line} 行：${label}必须为${range}，不能填写公式或文本。`)
  }
  return number
}

/** Keep original worksheet row numbers in errors while allowing empty trailing rows and columns. */
export function temperatureImportTableRows(rows, columns, title) {
  if (!Array.isArray(rows)) throw new Error(`未读取到有效表格，请使用${title}模板。`)
  const entries = rows.map((row, index) => ({ row, line: index + 1 }))
    .filter(({ row }) => Array.isArray(row) && row.some(value => !isTemperatureImportBlank(value)))
  if (!entries.length) throw new Error(`表格为空，请下载并填写${title}模板。`)
  const header = entries[0].row
  if (header.slice(columns.length).some(value => !isTemperatureImportBlank(value))
    || columns.some((column, index) => String(header[index] ?? '').replace(/^\uFEFF/, '').trim() !== column.label)) {
    throw new Error(`表头不符合${title}模板，请保留模板的 ${columns.length} 列名称、单位及顺序。`)
  }
  for (const { row, line } of entries.slice(1)) {
    if (row.slice(columns.length).some(value => !isTemperatureImportBlank(value))) {
      throw new Error(`第 ${line} 行：包含模板以外的列，请仅保留 ${columns.length} 列。`)
    }
  }
  return entries.slice(1)
}

export function temperatureImportPipe(row, line, edges, sequenceLabel = '序号') {
  const sequence = temperatureImportNumber(row[0], sequenceLabel, line, { minExclusive: 0 })
  if (!Number.isInteger(sequence) || sequence > edges.length) throw new Error(`第 ${line} 行：${sequenceLabel} ${row[0]} 不对应当前拓扑管道。`)
  const edge = edges[sequence - 1]
  if (String(row[1] ?? '') !== edge.name) {
    throw new Error(`第 ${line} 行：${sequenceLabel} ${sequence} 应对应管道“${edge.name}”，当前名称不匹配。请重新下载当前拓扑模板。`)
  }
  return edge
}

export function assertTemperatureImportDiameter(value, edge, line) {
  const diameter = temperatureImportNumber(value, '管内径', line, { minExclusive: 0 })
  if (diameter !== edge.parameters.diameterMm) {
    throw new Error(`第 ${line} 行：管道“${edge.name}”的管内径应为已保存拓扑中的 ${edge.parameters.diameterMm} mm，导入不能修改管道内径。`)
  }
}

/** Complete, atomic one-row-per-pipe imports; the caller returns only the editable fields for its page. */
export function parseTemperatureSinglePipeRows(rows, graph, { columns, title, fixedDiameter = false, parseValues }) {
  const edges = temperatureImportEdges(graph, fixedDiameter), found = new Map()
  for (const { row, line } of temperatureImportTableRows(rows, columns, title)) {
    const edge = temperatureImportPipe(row, line, edges)
    if (found.has(edge.id)) throw new Error(`第 ${line} 行：管道“${edge.name}”重复，每个管道只能填写一行。`)
    if (fixedDiameter) assertTemperatureImportDiameter(row[2], edge, line)
    found.set(edge.id, { edgeId: edge.id, ...parseValues(row, line) })
  }
  const missing = edges.filter(edge => !found.has(edge.id))
  if (missing.length) throw new Error(`缺少管道：${missing.map(edge => edge.name).join('、')}。请使用包含全部 ${edges.length} 个管道的模板。`)
  return { segments: edges.map(edge => found.get(edge.id)) }
}

/** Check cell types before sheet_to_json can collapse Excel errors into blank values. */
export function assertTemperatureImportSheet(sheet) {
  if (!sheet || typeof sheet !== 'object') throw new Error('文件没有可读取的工作表，请使用对应的参数模板。')
  for (const [address, cell] of Object.entries(sheet)) {
    if (address.startsWith('!')) continue
    if (cell?.t === 'e') throw new Error(`单元格 ${address} 包含 Excel 错误值，请修正后再导入；错误值不能作为空值导入。`)
    if (cell?.f != null || cell?.F != null) throw new Error(`单元格 ${address} 包含公式，请将公式粘贴为数值后再导入。`)
  }
}

/** RFC-style CSV values are data only; formulas are never evaluated. */
export function parseTemperatureImportCsv(text) {
  const source = String(text ?? '').replace(/^\uFEFF/, '')
  if (!source) return []
  const rows = []
  let row = [], field = '', quoted = false, closed = false, line = 1
  const pushField = () => { row.push(field); field = ''; closed = false }
  const pushRow = () => { pushField(); rows.push(row); row = [] }
  for (let index = 0; index < source.length; index++) {
    const char = source[index]
    if (quoted) {
      if (char === '"') {
        if (source[index + 1] === '"') { field += '"'; index++ }
        else { quoted = false; closed = true }
      } else {
        field += char
        if (char === '\n' || (char === '\r' && source[index + 1] !== '\n')) line++
      }
    } else if (char === ',') pushField()
    else if (char === '\n' || char === '\r') {
      pushRow(); line++
      if (char === '\r' && source[index + 1] === '\n') index++
    } else if (char === '"' && field === '' && !closed) quoted = true
    else {
      if (char === '"' || closed) throw new Error(`CSV 第 ${line} 行引号格式错误，请使用标准 CSV 表格。`)
      field += char
    }
  }
  if (quoted) throw new Error(`CSV 第 ${line} 行引号未闭合，请检查文件。`)
  if (row.length || field !== '' || closed || source.endsWith(',')) pushRow()
  return rows
}

/** Correct escaping for data interchange. The UI downloads XLSX with explicit string cells. */
export function temperatureImportCsv(rows) {
  return rows.map(row => row.map(value => {
    const text = value == null ? '' : String(value)
    return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text
  }).join(',')).join('\r\n')
}
