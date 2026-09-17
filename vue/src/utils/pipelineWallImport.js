export const wallImportColumns = Object.freeze([
  { key: 'pipeSequence', label: '管道序号' },
  { key: 'pipeName', label: '管道名称' },
  { key: 'diameterMm', label: '管内径（mm）' },
  { key: 'layerSequence', label: '层序号' },
  { key: 'name', label: '材料层名称' },
  { key: 'thicknessMm', label: '厚度（mm）' },
  { key: 'conductivityWmK', label: '导热系数（W/(m·K)）' }
].map(column => Object.freeze(column)))

const blank = value => value == null || (typeof value === 'string' && value.trim() === '')
function savedEdges(graph) {
  const edges = graph?.edges
  if (!Array.isArray(edges) || !edges.length) throw new Error('当前已保存拓扑没有管道，请先保存管网拓扑。')
  const ids = new Set()
  for (const edge of edges) {
    if (!edge?.id || typeof edge.name !== 'string' || !edge.name.trim() || ids.has(edge.id)) throw new Error('已保存拓扑的管道编号或名称无效，请重新检查拓扑。')
    if (typeof edge.parameters?.diameterMm !== 'number' || !Number.isFinite(edge.parameters.diameterMm) || edge.parameters.diameterMm <= 0) {
      throw new Error(`管道“${edge.name}”缺少有效内径，请先在管网拓扑结构中补全并保存。`)
    }
    ids.add(edge.id)
  }
  return edges
}

/** A material-layer row per existing layer; physical parameters are intentionally blank in the template. */
export function wallImportTemplateRows(graph, settings = {}) {
  const result = [wallImportColumns.map(column => column.label)]
  for (const [index, edge] of savedEdges(graph).entries()) {
    const stored = settings?.segments?.find(segment => segment.edgeId === edge.id)?.layers
    if (stored != null && !Array.isArray(stored)) throw new Error(`管道“${edge.name}”的材料层数据无效，请检查当前设置。`)
    const layers = stored?.length ? stored : [{ name: '钢管' }]
    if (layers.length > 12) throw new Error(`管道“${edge.name}”最多允许 12 个材料层，请先检查当前设置。`)
    layers.forEach((layer, layerIndex) => result.push([
      index + 1, edge.name, edge.parameters.diameterMm, layerIndex + 1, layer?.name ?? '', '', ''
    ]))
  }
  return result
}

function positiveNumber(value, label, row, optional = false) {
  if (blank(value)) {
    if (optional) return null
    throw new Error(`第 ${row} 行：${label}不能为空，须填写大于 0 的数值。`)
  }
  const decimal = typeof value === 'number' || (typeof value === 'string' && /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:e[+-]?\d+)?$/i.test(value.trim()))
  const number = decimal ? Number(value) : NaN
  if (!Number.isFinite(number) || number <= 0) throw new Error(`第 ${row} 行：${label}必须为大于 0 的有限数字，不能填写公式或文本。`)
  return number
}

/** Validate all pipes and layers before returning a patch containing only the material-layer fields. */
export function parseWallImportRows(rows, graph) {
  const edges = savedEdges(graph)
  if (!Array.isArray(rows)) throw new Error('未读取到有效表格，请使用管道导热系数模板。')
  const entries = rows.map((row, index) => ({ row, line: index + 1 })).filter(({ row }) => Array.isArray(row) && row.some(value => !blank(value)))
  if (!entries.length) throw new Error('表格为空，请下载并填写管道导热系数模板。')
  const header = entries[0].row
  if (header.slice(wallImportColumns.length).some(value => !blank(value))
    || wallImportColumns.some((column, index) => String(header[index] ?? '').replace(/^\uFEFF/, '').trim() !== column.label)) {
    throw new Error('表头不符合管道导热系数模板，请保留模板的 7 列名称、单位及顺序。')
  }
  const found = new Map()
  for (const { row, line } of entries.slice(1)) {
    if (row.slice(wallImportColumns.length).some(value => !blank(value))) throw new Error(`第 ${line} 行：包含模板以外的列，请仅保留 7 列。`)
    const pipeSequence = positiveNumber(row[0], '管道序号', line)
    if (!Number.isInteger(pipeSequence) || pipeSequence > edges.length) throw new Error(`第 ${line} 行：管道序号 ${row[0]} 不对应当前拓扑管道。`)
    const edge = edges[pipeSequence - 1]
    if (String(row[1] ?? '') !== edge.name) throw new Error(`第 ${line} 行：管道序号 ${pipeSequence} 应对应“${edge.name}”，当前名称不匹配。请重新下载当前拓扑模板。`)
    const diameter = positiveNumber(row[2], '管内径', line)
    if (diameter !== edge.parameters.diameterMm) throw new Error(`第 ${line} 行：管道“${edge.name}”的管内径应为已保存拓扑中的 ${edge.parameters.diameterMm} mm，导入不能修改管道内径。`)
    const layerSequence = positiveNumber(row[3], '层序号', line)
    if (!Number.isInteger(layerSequence) || layerSequence > 12) throw new Error(`第 ${line} 行：层序号须为 1～12 的整数，每个管道最多 12 层。`)
    if (typeof row[4] !== 'string' || !row[4].trim()) throw new Error(`第 ${line} 行：材料层名称不能为空，须填写文本名称。`)
    const layers = found.get(edge.id) || new Map()
    if (layers.has(layerSequence)) throw new Error(`第 ${line} 行：管道“${edge.name}”的层序号 ${layerSequence} 重复。`)
    layers.set(layerSequence, { name: row[4], thicknessMm: positiveNumber(row[5], '厚度', line, true),
      conductivityWmK: positiveNumber(row[6], '导热系数', line, true) })
    found.set(edge.id, layers)
  }
  const missing = edges.filter(edge => !found.has(edge.id))
  if (missing.length) throw new Error(`缺少管道：${missing.map(edge => edge.name).join('、')}。请填写全部 ${edges.length} 个管道的材料层。`)
  const segments = edges.map(edge => {
    const layers = found.get(edge.id)
    for (let index = 1; index <= layers.size; index++) {
      if (!layers.has(index)) throw new Error(`管道“${edge.name}”缺少层序号 ${index}，材料层应从 1 开始连续编号。`)
    }
    return { edgeId: edge.id, layers: Array.from({ length: layers.size }, (_, index) => layers.get(index + 1)) }
  })
  return { segments }
}
