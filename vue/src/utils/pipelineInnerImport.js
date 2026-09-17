import { temperatureImportNumber } from './pipelineTemperatureImport.js'

export const gasConductivityInputColumn = Object.freeze({ key: 'gasConductivityWmK', label: '气体导热系数 λ', unit: 'W/(m·K)' })

export const innerInputColumns = Object.freeze([
  { key: 'innerDiameterMm', label: '管内径 D', unit: 'mm' },
  { key: 'actualFlowM3s', label: '工况体积流量 Q', unit: 'm³/s' },
  { key: 'propertyPressureMpa', label: '物性计算压力', unit: 'MPa绝压' },
  { key: 'propertyTemperatureC', label: '物性计算温度', unit: '℃' },
  gasConductivityInputColumn
].map(column => Object.freeze(column)))

export const innerPropertyColumns = Object.freeze([
  { key: 'densityKgM3', label: '气体密度 ρ', unit: 'kg/m³' },
  { key: 'viscosityMpaS', label: '动力黏度 μ', unit: 'mPa·s' },
  { key: 'cpJkgK', label: '定压比热容 Cp', unit: 'J/(kg·K)' }
].map(column => Object.freeze(column)))

export const innerImportColumns = Object.freeze([
  { key: 'sequence', label: '序号' },
  { key: 'name', label: '管道名称' },
  ...innerInputColumns.map(({ key, label, unit }) => ({ key, label: `${label}（${unit}）` }))
].map(column => Object.freeze(column)))

const blank = value => value == null || (typeof value === 'string' && value.trim() === '')

function savedEdges(graph) {
  const edges = graph?.edges
  if (!Array.isArray(edges) || !edges.length) throw new Error('当前已保存拓扑没有管道，请先保存管网拓扑。')
  const ids = new Set()
  for (const edge of edges) {
    if (!edge?.id || typeof edge.name !== 'string' || !edge.name.trim() || ids.has(edge.id)) {
      throw new Error('已保存拓扑的管道编号或名称无效，请重新检查拓扑。')
    }
    ids.add(edge.id)
  }
  return edges
}

export function innerImportTemplateRows(graph, settings = {}) {
  return [innerImportColumns.map(column => column.label), ...savedEdges(graph).map((edge, index) => [
    index + 1, edge.name, edge.parameters?.diameterMm ?? '', '', '', '', settings.segments?.find(c=>c.edgeId===edge.id)?.gasConductivityWmK??''
  ])]
}

function positiveNumber(value, label, row, optional = false) {
  if (blank(value)) {
    if (optional) return null
    throw new Error(`第 ${row} 行：${label}不能为空，须填写大于 0 的数值。`)
  }
  const decimal = typeof value === 'number' || (typeof value === 'string'
    && /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:e[+-]?\d+)?$/i.test(value.trim()))
  const number = decimal ? Number(value) : NaN
  if (!Number.isFinite(number) || number <= 0) throw new Error(`第 ${row} 行：${label}必须为大于 0 的有限数字，不能填写公式或文本。`)
  return number
}

/** Validate the complete table before returning any updates; edge IDs always come from saved topology. */
export function parseInnerImportRows(rows, graph) {
  const edges = savedEdges(graph)
  if (!Array.isArray(rows)) throw new Error('未读取到有效表格，请使用内壁参数模板。')
  const entries = rows.map((row, index) => ({ row, line: index + 1 }))
    .filter(({ row }) => Array.isArray(row) && row.some(value => !blank(value)))
  if (!entries.length) throw new Error('表格为空，请下载并填写内壁参数模板。')
  const header = entries[0].row
  if (header.slice(innerImportColumns.length).some(value => !blank(value))
    || innerImportColumns.some((column, index) => String(header[index] ?? '').replace(/^\uFEFF/, '').trim() !== column.label)) {
    throw new Error('表头不符合内壁参数模板，请保留模板的 7 列名称、单位及顺序。密度、黏度和 Cp 应从 PVT 读取；气体导热系数须填写，请重新下载模板。')
  }
  const found = new Map()
  for (const { row, line } of entries.slice(1)) {
    if (row.slice(innerImportColumns.length).some(value => !blank(value))) throw new Error(`第 ${line} 行：包含模板以外的列，请仅保留 7 列。`)
    const sequence = positiveNumber(row[0], '序号', line)
    if (!Number.isInteger(sequence) || sequence > edges.length) throw new Error(`第 ${line} 行：序号 ${row[0]} 不对应当前拓扑管道。`)
    const edge = edges[sequence - 1]
    if (String(row[1] ?? '') !== edge.name) throw new Error(`第 ${line} 行：序号 ${sequence} 应对应管道“${edge.name}”，当前名称不匹配。请重新下载当前拓扑模板。`)
    if (found.has(edge.id)) throw new Error(`第 ${line} 行：管道“${edge.name}”重复，每个管道只能填写一行。`)
    const segment = { edgeId: edge.id }
    for (let index = 2; index < innerImportColumns.length; index++) {
      const column = innerImportColumns[index]
      segment[column.key] = temperatureImportNumber(row[index], column.label, line, {
        optional: index !== 2 && column.key !== 'gasConductivityWmK', minExclusive: column.key === 'propertyTemperatureC' ? -273.15 : 0
      })
    }
    found.set(edge.id, segment)
  }
  const missing = edges.filter(edge => !found.has(edge.id))
  if (missing.length) throw new Error(`缺少管道：${missing.map(edge => edge.name).join('、')}。请使用包含全部 ${edges.length} 个管道的模板。`)
  return { segments: edges.map(edge => found.get(edge.id)) }
}


export { assertTemperatureImportSheet as assertInnerImportSheet, parseTemperatureImportCsv as parseInnerImportCsv,
  temperatureImportCsv as innerImportCsv } from './pipelineTemperatureImport.js'
