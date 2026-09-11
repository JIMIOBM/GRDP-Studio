import { checkTopology, clone } from './pipelineTopology.js'
import { normalizeOperatingTime, parseOperatingTime } from './pipelineTime.js'

export const PIPELINE_BATCH_VERSION = 'network-batch-2.0'

/** A single well may feed several outlets. Incoming branches and loops need a different solver. */
export function networkOrder(graph) {
  if (!graph?.nodes?.length || !Array.isArray(graph.edges)) throw new Error('请先保存当前井的管网拓扑。')
  const checked = checkTopology(graph)
  if (checked.errors.length) throw new Error(checked.errors[0])
  if (!graph.edges.length || graph.edges.length > 400) throw new Error('管网需要 1～400 个已连接管道。')
  const root = graph.nodes.find(node => node.type === 'well')
  const incoming = new Map(graph.nodes.map(node => [node.id, 0]))
  const outgoing = new Map(graph.nodes.map(node => [node.id, []]))
  for (const edge of graph.edges) {
    incoming.set(edge.target, incoming.get(edge.target) + 1)
    outgoing.get(edge.source).push(edge)
  }
  if (incoming.get(root.id) !== 0 || graph.nodes.some(node => node.id !== root.id && incoming.get(node.id) !== 1))
    throw new Error('请保存从当前井口连通到各出口的树状管网；汇合、环路或孤立节点需要先检查。')
  const visited = new Set([root.id]), ordered = [], queue = [root.id]
  for (let i = 0; i < queue.length; i++) for (const edge of outgoing.get(queue[i])) {
    if (visited.has(edge.target)) throw new Error('管网存在环路，请检查连接关系。')
    visited.add(edge.target); ordered.push(edge); queue.push(edge.target)
  }
  if (visited.size !== graph.nodes.length || ordered.length !== graph.edges.length) throw new Error('管网存在未连接到井口的管道。')
  return ordered
}

export function resolveSavedNetworkTopology(detail, base) {
  const input = { ...clone(base), segments: [], equipment: [] }
  const graph = detail?.graph ? clone(detail.graph) : null
  try {
    networkOrder(graph)
    const ordered = graph.edges, nodes = new Map(graph.nodes.map(node => [node.id, node]))
    input.segments = ordered.map(edge => ({ name: edge.name, lengthM: edge.parameters.lengthM,
      diameterMm: edge.parameters.diameterMm, roughnessMm: edge.parameters.roughnessMm,
      elevationChangeM: nodes.get(edge.target).parameters.elevationM - nodes.get(edge.source).parameters.elevationM,
      ambientC: 0, heatTransferWm2K: 0 }))
    ordered.forEach((edge, index) => {
      const node = nodes.get(edge.target)
      if (!['valve', 'compressor'].includes(node.type)) return
      const configs = [{ ...node.parameters, type: node.type, name: node.name }, ...(node.parameters.extraEquipment || [])]
      input.equipment.push(...configs.map(config => ({ name: config.name, type: config.type, afterSegment: index,
        lossK: config.lossK ?? 0, pressureRatio: config.pressureRatio ?? 1, efficiency: config.efficiency ?? 1,
        maxPressureMpa: config.maxPressureMpa, maxPowerKw: config.maxPowerKw ?? 500 })))
    })
    return { input, topologyRevision: detail.revision, graph, error: '' }
  } catch (e) { return { input, topologyRevision: detail?.revision || 0, graph, error: e.message } }
}

/** Active row selection and old single-case outputs do not change a batch calculation. */
export function batchInputMark(input, topologyRevision) {
  const boundary = input?.boundary ? clone(input.boundary) : null
  if (boundary) {
    delete boundary.activeCaseId
    if (Array.isArray(boundary.cases)) boundary.cases.forEach(condition => {
      condition.operatingAt = normalizeOperatingTime(condition.operatingAt)
    })
  }
  // The server omits absent measurements; the editable form represents them as null.
  const canonical = value => Array.isArray(value) ? value.map(canonical)
    : value && typeof value === 'object' ? Object.fromEntries(Object.keys(value).sort()
      .filter(key => value[key] != null).map(key => [key, canonical(value[key])])) : value
  return JSON.stringify(canonical({ topologyRevision, boundary, waterState: input?.constraints?.waterState === 'available' ? 'available' : 'unknown', thermalMode: input?.thermalMode,
    frictionMethod: input?.frictionMethod, jtKmpa: input?.thermalMode === 'heat' ? input?.jtKmpa ?? null : null,
    standardPressurePa: input?.standardPressurePa, standardTemperatureK: input?.standardTemperatureK }))
}

/** Boundary timestamps are local engineering time (Asia/Shanghai), independent of browser timezone. */
export function conditionTime(value) {
  return parseOperatingTime(value)
}
const orderedCases = input => [...(input?.boundary?.cases || [])].sort((a, b) =>
  (conditionTime(a.operatingAt) ?? Infinity) - (conditionTime(b.operatingAt) ?? Infinity))

export function batchDataRows(graph, input, result, current) {
  let edges
  try { edges = networkOrder(graph) } catch { edges = graph?.edges || [] }
  const cases = new Map((current ? result?.cases || [] : []).map(row => [row.caseId, row]))
  return orderedCases(input).flatMap(condition => {
    const computed = cases.get(condition.id), pipes = new Map((computed?.pipes || []).map(row => [row.edgeId, row]))
    return edges.map(edge => {
      const pipe = computed?.status === 'success' ? pipes.get(edge.id) : null
      return { rowKey: `${condition.id}/${edge.id}`, operatingAt: condition.operatingAt || '',
        name: edge.name, lengthM: edge.parameters?.lengthM, diameterMm: edge.parameters?.diameterMm,
        inletMpa: pipe?.inletMpa ?? null, outletMpa: pipe?.outletMpa ?? null,
        inletC: pipe?.inletC ?? null, outletC: pipe?.outletC ?? null, rate10k: pipe?.rate10k ?? null,
        status: computed?.status || 'pending', error: computed?.error || '',
        message: computed?.error || computed?.notes?.join('\n') || '' }
    })
  })
}

export function batchChartSeries(graph, result, current) {
  const empty = { temperature: [], pressure: [], flow: [] }
  if (!current || !result) return empty
  const cases = (result.cases || []).filter(row => conditionTime(row.operatingAt) != null)
    .sort((a, b) => conditionTime(a.operatingAt) - conditionTime(b.operatingAt))
    .map(row => ({ ...row, values: new Map((row.pipes || []).map(pipe => [pipe.edgeId, pipe])) }))
  for (const [kind, key] of [['temperature', 'outletC'], ['pressure', 'outletMpa'], ['flow', 'rate10k']]) {
    empty[kind] = (graph?.edges || []).map(edge => ({ id: edge.id, name: edge.name,
      data: cases.map(row => {
        const value = row.status === 'success' ? row.values.get(edge.id)?.[key] : null
        return [conditionTime(row.operatingAt), typeof value === 'number' && Number.isFinite(value) ? value : null]
      }) }))
  }
  return empty
}

export function batchExportRows(graph, input, result, current) {
  return [['工况时间', '管道名称', '入口压力(MPa绝压)', '出口压力(MPa绝压)', '入口温度(℃)', '出口温度(℃)', '标况流量(10⁴m³/d)', '计算状态', '计算提示'],
    ...batchDataRows(graph, input, result, current).map(row => [row.operatingAt.replace('T', ' '), row.name,
      row.inletMpa ?? '', row.outletMpa ?? '', row.inletC ?? '', row.outletC ?? '', row.rate10k ?? '',
      ({success:'成功',error:'失败',pending:'待计算'})[row.status], row.message])]
}
