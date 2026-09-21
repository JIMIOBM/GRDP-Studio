export const storageNetworkNodeTypes = [
  { type: 'well', label: '井口', symbol: '井' },
  { type: 'junction', label: '连接点', symbol: '●' },
  { type: 'gathering', label: '集气站', symbol: '集' },
  { type: 'compressor', label: '压缩站', symbol: '压' },
  { type: 'valve', label: '阀室', symbol: '阀' },
  { type: 'metering', label: '计量站', symbol: '计' },
  { type: 'external', label: '外输管网', symbol: '出' }
]

export const storageNetworkVariables = [
  { key: 'inletPressureMpa', label: '入口压力', unit: 'MPa' },
  { key: 'outletPressureMpa', label: '出口压力', unit: 'MPa' },
  { key: 'pressureDropMpa', label: '系统压差', unit: 'MPa' },
  { key: 'flowRate10k', label: '输气量', unit: '10⁴m³/d' },
  { key: 'outletTemperatureC', label: '出口温度', unit: '℃' },
  { key: 'compressionPowerKw', label: '压缩功率', unit: 'kW' }
]

export const storageNetworkUid = prefix => `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`
export const cloneStorageNetwork = value => JSON.parse(JSON.stringify(value))

export function createStorageNetworkNode(type, index = 0) {
  const descriptor = storageNetworkNodeTypes.find(item => item.type === type)
  const limited = ['gathering', 'compressor', 'metering', 'external'].includes(type)
  return {
    id: storageNetworkUid(type), type, name: `${descriptor?.label || '节点'}${index + 1}`, wellId: null,
    x: 420, y: 160 + index * 24,
    parameters: { elevationM: 0, ...(limited ? { capacity10k: 500, maxPressureMpa: 30 } : {}) }
  }
}

export function createStorageNetworkEdge(source, target, index = 0) {
  return { id: storageNetworkUid('edge'), source, target, name: `管段${index + 1}`,
    parameters: { lengthM: 1000, diameterMm: 200, roughnessMm: 0.03, maxFlow10k: 500 } }
}

const finite = value => Number.isFinite(Number(value))
const positive = value => finite(value) && Number(value) > 0
const nonnegative = value => finite(value) && Number(value) >= 0

export function checkStorageNetwork(graph, wellIds = []) {
  const errors = [], warnings = [], nodes = Array.isArray(graph?.nodes) ? graph.nodes : []
  const edges = Array.isArray(graph?.edges) ? graph.edges : []
  const ids = new Set(), edgeIds = new Set(), pairs = new Set(), actualWells = new Set()
  const outgoing = new Map(), incoming = new Map()
  let externalCount = 0
  for (const node of nodes) {
    if (!node?.id || ids.has(node.id)) errors.push('节点标识不能为空或重复。')
    ids.add(node?.id)
    outgoing.set(node?.id, [])
    incoming.set(node?.id, 0)
    if (!node?.name?.trim()) errors.push('节点名称不能为空。')
    if (!finite(node?.x) || !finite(node?.y)) errors.push(`${node?.name || '节点'}：坐标无效。`)
    if (!finite(node?.parameters?.elevationM)) errors.push(`${node?.name || '节点'}：高程必须是有效数字。`)
    if (node?.type === 'well') {
      if (!positive(node.wellId) || actualWells.has(Number(node.wellId))) errors.push('每口井必须且只能有一个井节点。')
      actualWells.add(Number(node.wellId))
    }
    if (node?.type === 'external') externalCount++
    if (['gathering', 'compressor', 'metering', 'external'].includes(node?.type)
      && (!positive(node?.parameters?.capacity10k) || !positive(node?.parameters?.maxPressureMpa)))
      errors.push(`${node.name}：请填写正数处理能力和压力上限。`)
  }
  if (externalCount !== 1) errors.push('拓扑必须且只能包含一个外输管网节点。')
  const expected = new Set(wellIds.map(Number))
  if (expected.size !== actualWells.size || [...expected].some(id => !actualWells.has(id))) errors.push('拓扑必须包含当前储气库的全部井。')
  for (const edge of edges) {
    const pair = `${edge?.source}/${edge?.target}`
    if (!edge?.id || edgeIds.has(edge.id) || pairs.has(pair)) errors.push('管段标识或连接关系重复。')
    edgeIds.add(edge?.id); pairs.add(pair)
    if (!ids.has(edge?.source) || !ids.has(edge?.target) || edge?.source === edge?.target) errors.push(`${edge?.name || '管段'}：端点无效。`)
    if (!edge?.name?.trim()) errors.push('管段名称不能为空。')
    if (!positive(edge?.parameters?.lengthM) || !positive(edge?.parameters?.diameterMm)
      || !nonnegative(edge?.parameters?.roughnessMm) || !positive(edge?.parameters?.maxFlow10k))
      errors.push(`${edge?.name || '管段'}：请补齐有效的管长、内径、粗糙度和输量上限。`)
    if (outgoing.has(edge?.source)) outgoing.get(edge.source).push(edge.target)
    if (incoming.has(edge?.target)) incoming.set(edge.target, incoming.get(edge.target) + 1)
  }
  nodes.filter(node => node.type === 'well' && incoming.get(node.id) > 0)
    .forEach(node => errors.push(`${node.name}：井节点只能作为入口。`))
  const external = nodes.find(node => node.type === 'external')
  if (external && outgoing.get(external.id)?.length) errors.push('外输管网节点必须是最终出口。')
  const reaches = (start, target, seen = new Set()) => {
    if (start === target) return true
    if (seen.has(start)) return false
    seen.add(start)
    return (outgoing.get(start) || []).some(next => reaches(next, target, seen))
  }
  if (external) nodes.filter(node => node.type === 'well' && !reaches(node.id, external.id))
    .forEach(node => errors.push(`${node.name}：必须连接到外输管网。`))
  if (!errors.length && edges.length > nodes.length - 1) warnings.push('当前网络包含冗余连接，请确认不存在不必要的并联路径。')
  return { errors: [...new Set(errors)], warnings: [...new Set(warnings)] }
}

function paired(rows, xKey, yKey) {
  return rows.filter(row => row?.[xKey] !== null && row?.[xKey] !== undefined && row?.[xKey] !== ''
      && row?.[yKey] !== null && row?.[yKey] !== undefined && row?.[yKey] !== '')
    .map(row => [Number(row[xKey]), Number(row[yKey])])
    .filter(([x, y]) => Number.isFinite(x) && Number.isFinite(y))
}

function ranks(values) {
  const indexed = values.map((value, index) => ({ value, index })).sort((a, b) => a.value - b.value)
  const result = Array(values.length)
  for (let i = 0; i < indexed.length;) {
    let end = i + 1
    while (end < indexed.length && indexed[end].value === indexed[i].value) end++
    const rank = (i + 1 + end) / 2
    for (let cursor = i; cursor < end; cursor++) result[indexed[cursor].index] = rank
    i = end
  }
  return result
}

function pearsonPairs(pairs) {
  if (pairs.length < 3) return null
  const meanX = pairs.reduce((sum, row) => sum + row[0], 0) / pairs.length
  const meanY = pairs.reduce((sum, row) => sum + row[1], 0) / pairs.length
  let covariance = 0, varianceX = 0, varianceY = 0
  for (const [x, y] of pairs) {
    const dx = x - meanX, dy = y - meanY
    covariance += dx * dy; varianceX += dx * dx; varianceY += dy * dy
  }
  const denominator = Math.sqrt(varianceX * varianceY)
  return denominator > 0 ? Math.max(-1, Math.min(1, covariance / denominator)) : null
}

export function correlationCoefficient(rows, xKey, yKey, method = 'pearson') {
  const pairs = paired(rows, xKey, yKey)
  if (xKey === yKey) return pairs.length >= 3 ? 1 : null
  if (method === 'spearman') {
    const rx = ranks(pairs.map(row => row[0])), ry = ranks(pairs.map(row => row[1]))
    return pearsonPairs(rx.map((rank, index) => [rank, ry[index]]))
  }
  return pearsonPairs(pairs)
}

export function correlationMatrix(rows, method = 'pearson', variables = storageNetworkVariables) {
  return variables.flatMap((x, xIndex) => variables.map((y, yIndex) => ({
    xIndex, yIndex, xKey: x.key, yKey: y.key,
    value: correlationCoefficient(rows, x.key, y.key, method),
    count: paired(rows, x.key, y.key).length
  })))
}

export function linearRegression(rows, xKey, yKey) {
  const pairs = paired(rows, xKey, yKey)
  if (pairs.length < 3) return { pairs, slope: null, intercept: null, r2: null }
  const meanX = pairs.reduce((sum, row) => sum + row[0], 0) / pairs.length
  const meanY = pairs.reduce((sum, row) => sum + row[1], 0) / pairs.length
  let numerator = 0, denominator = 0
  for (const [x, y] of pairs) { numerator += (x - meanX) * (y - meanY); denominator += (x - meanX) ** 2 }
  if (!(denominator > 0)) return { pairs, slope: null, intercept: null, r2: null }
  const slope = numerator / denominator, intercept = meanY - slope * meanX
  const coefficient = pearsonPairs(pairs)
  return { pairs, slope, intercept, r2: coefficient === null ? null : coefficient ** 2 }
}

export function filterStorageNetworkSamples(samples, { wellIds = [], startDate = '', endDate = '' } = {}) {
  const selected = new Set(wellIds.map(Number))
  return (Array.isArray(samples) ? samples : []).filter(row => {
    if (selected.size && !selected.has(Number(row.wellId))) return false
    const date = String(row.operatingAt || '').slice(0, 10)
    return (!startDate || date >= startDate) && (!endDate || date <= endDate)
  })
}

export function strongestTargetCorrelations(rows, targetKey, method = 'pearson', threshold = 0) {
  return storageNetworkVariables.filter(variable => variable.key !== targetKey).map(variable => ({
    ...variable, value: correlationCoefficient(rows, variable.key, targetKey, method)
  })).filter(item => item.value !== null && Math.abs(item.value) >= threshold)
    .sort((a, b) => Math.abs(b.value) - Math.abs(a.value))
}
