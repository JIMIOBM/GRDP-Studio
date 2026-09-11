import { newEquipment } from './pipelineDefaults.js'

export const topologyDrafts = new Map()
export const nodeTypes = [
  { type: 'well', label: '井口', symbol: '井' }, { type: 'junction', label: '汇合节点', symbol: '汇' },
  { type: 'split', label: '分流节点', symbol: '分' }, { type: 'valve', label: '阀门', symbol: '阀' },
  { type: 'compressor', label: '压缩机', symbol: '压' }, { type: 'station', label: '集气站', symbol: '站' }
]
export const clone = value => JSON.parse(JSON.stringify(value))
export function normalizeTopologyPvtReferences(graph) {
  if(!graph)return graph
  const clean=clone(graph)
  for(const edge of clean.edges||[])if(edge.parameters)delete edge.parameters.pvtId
  return clean
}
export const uid = () => crypto.randomUUID()
export function createNode(type, x, y, index) {
  return { id: uid(), type, name: `${nodeTypes.find(n => n.type === type).label}${index + 1}`,
    parameters: { elevationM: 0, ...(type === 'valve' || type === 'compressor' ? { ...newEquipment(index), type } : {}) }, x, y }
}
export function fromInput(input, wellName) {
  const nodes = [], edges = []
  const first = createNode('well', 100, 230, 0); first.name = wellName; nodes.push(first)
  input.segments.forEach((segment, index) => {
    // Equipment sharing one segment exit remains an ordered equipment list on that node.
    const equipment = input.equipment.filter(e => e.afterSegment === index)
    const type = equipment[0]?.type || (index === input.segments.length - 1 ? 'station' : 'junction')
    const target = createNode(type, 100 + (index + 1) * 220, 230, index)
    target.name = equipment[0]?.name || (index === input.segments.length - 1 ? '出口站' : `连接点${index + 1}`)
    target.parameters.elevationM = nodes[index].parameters.elevationM + segment.elevationChangeM
    if (equipment.length) { Object.assign(target.parameters, equipment[0]); target.parameters.extraEquipment = clone(equipment.slice(1)) }
    nodes.push(target)
    const parameters=clone(segment);delete parameters.pvtId
    edges.push({ id: uid(), source: nodes[index].id, target: target.id, name: segment.name, parameters })
  })
  return { nodes, edges, settings: {}, layout: { x: 0, y: 0, zoom: 1 } }
}
export function checkTopology(graph) {
  const errors = [], warnings = [], ids = new Set(graph.nodes.map(n => n.id))
  const wells = graph.nodes.filter(n => n.type === 'well')
  if (wells.length !== 1) errors.push('必须且只能包含当前井的一个井口。')
  if (wells.some(n => graph.edges.some(e => e.target === n.id))) errors.push('当前井井口只能作为入口。')
  if (!graph.nodes.length) errors.push('请至少添加入口和出口节点。')
  if (ids.size !== graph.nodes.length) errors.push('节点标识重复。')
  const pairs = new Set(), edgeIds = new Set(), incoming = new Map(), outgoing = new Map()
  graph.nodes.forEach(n => { incoming.set(n.id, []); outgoing.set(n.id, []) })
  graph.edges.forEach(edge => {
    if (edgeIds.has(edge.id)) errors.push(`${edge.name}：管段标识重复。`)
    edgeIds.add(edge.id)
    if (!edge.name?.trim()) errors.push('管段名称不能为空。')
    if (!ids.has(edge.source) || !ids.has(edge.target)) { errors.push(`${edge.name}：连接端点不存在。`); return }
    if (edge.source === edge.target) errors.push(`${edge.name}：不能连接自身。`)
    const pair = `${edge.source}/${edge.target}`
    if (pairs.has(pair)) errors.push(`${edge.name}：存在重复连接。`)
    pairs.add(pair); outgoing.get(edge.source).push(edge); incoming.get(edge.target).push(edge)
    const p = edge.parameters || {}
    if (!Number.isFinite(p.lengthM) || p.lengthM <= 0 || !Number.isFinite(p.diameterMm) || p.diameterMm <= 0 || !Number.isFinite(p.roughnessMm) || p.roughnessMm < 0) errors.push(`${edge.name}：管长、内径或粗糙度不完整。`)
  })
  const checkEquipment = (p, type, name) => {
    if (!name?.trim()) errors.push('设备名称不能为空。')
    if (!['valve', 'compressor'].includes(type) || !Number.isFinite(p.maxPressureMpa) || p.maxPressureMpa <= 0 ||
        (type === 'valve' && !(Number.isFinite(p.lossK) && p.lossK >= 0)) ||
        (type === 'compressor' && (!(Number.isFinite(p.pressureRatio) && p.pressureRatio >= 1 && p.pressureRatio <= 5) ||
          !(Number.isFinite(p.efficiency) && p.efficiency > 0 && p.efficiency <= 1) || !(Number.isFinite(p.maxPowerKw) && p.maxPowerKw > 0)))) {
      errors.push(`${name || '设备'}：请补齐有效的设备参数与运行限值。`)
    }
  }
  graph.nodes.forEach(n => {
    if (!n.name?.trim()) errors.push('节点名称不能为空。')
    if (!Number.isFinite(n.parameters.elevationM)) errors.push(`${n.name}：请填写高程。`)
    if (['valve','compressor'].includes(n.type)) {
      checkEquipment(n.parameters, n.type, n.name)
      for (const equipment of n.parameters.extraEquipment || []) checkEquipment(equipment, equipment.type, equipment.name)
    }
    if (!incoming.get(n.id).length && !outgoing.get(n.id).length) errors.push(`${n.name}：孤立节点。`)
  })
  const sources = graph.nodes.filter(n => incoming.get(n.id).length === 0)
  const sinks = graph.nodes.filter(n => outgoing.get(n.id).length === 0)
  const branching = graph.nodes.some(n => incoming.get(n.id).length > 1 || outgoing.get(n.id).length > 1)
  const ordered = [], visited = new Set(); let current = sources[0]
  while (current && !visited.has(current.id)) {
    visited.add(current.id)
    const edge = outgoing.get(current.id)?.[0]
    if (!edge) break
    ordered.push(edge); current = graph.nodes.find(n => n.id === edge.target)
  }
  const serial = !errors.length && sources.length === 1 && sinks.length === 1 && !branching && visited.size === graph.nodes.length && ordered.length === graph.edges.length
  if (!serial && graph.nodes.length) warnings.push('管网包含分支或其他连接，请核对各下载点分输量与连接关系；树状分支可进行管流批量计算。')
  if (graph.edges.length > 400) errors.push('当前管网最多支持 400 个管道。')
  if (serial && ['valve','compressor'].includes(sources[0].type)) errors.push('入口设备没有上游管段，请在设备前添加入口管段。')
  return { errors, warnings, serial: serial && !errors.length, ordered, sources, sinks }
}
export function removeObjects(graph, selectedIds) {
  const removable = new Set(selectedIds.filter(id => !graph.nodes.some(n => n.id === id && n.type === 'well')))
  graph.edges = graph.edges.filter(e => !removable.has(e.id) && !removable.has(e.source) && !removable.has(e.target))
  graph.nodes = graph.nodes.filter(n => !removable.has(n.id))
}
