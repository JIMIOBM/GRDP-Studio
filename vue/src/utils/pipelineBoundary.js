import { normalizeOperatingTime, parseOperatingTime } from './pipelineTime.js'

const nodeFields = ['nodeId', 'supplyRate10k', 'withdrawalRate10k', 'pressureMpa', 'temperatureC']
const graphNodes = graph => Array.isArray(graph?.nodes) ? graph.nodes : []
const nodeRecords = condition => Array.isArray(condition?.nodes) ? condition.nodes : []
const records = boundary => nodeRecords(activeBoundaryCase(boundary))
const finiteRate = value => typeof value === 'number' && Number.isFinite(value) && value >= 0
export const MAX_BOUNDARY_CASES = 744

export function boundaryCases(boundary) {
  return Array.isArray(boundary?.cases) ? boundary.cases : []
}

/** Selection is explicit: invalid or missing selection never switches to another operating hour. */
export function activeBoundaryCase(boundary) {
  if (!boundary) return null
  return boundaryCases(boundary).find(condition => condition.id === boundary.activeCaseId) || null
}

export function createBoundaryCase(graph) {
  return { id: globalThis.crypto.randomUUID(), operatingAt: null,
    nodes: graphNodes(graph).map(node => emptyNode(node.id)) }
}

function roles(graph) {
  const outgoing = new Set((graph?.edges || []).map(edge => edge.source))
  return new Map(graphNodes(graph).map(node => [node.id,
    node.type === 'well' ? 'supply' : outgoing.has(node.id) ? 'junction' : 'delivery']))
}

function emptyNode(nodeId) {
  return { nodeId, supplyRate10k: null, withdrawalRate10k: null, pressureMpa: null, temperatureC: null }
}

function copyNode(record, defaults) {
  return Object.fromEntries(nodeFields.map(key => [key, record?.[key] === undefined ? defaults[key] : record[key]]))
}

/** Canonical JSON shape for sparse API responses; never infer a boundary condition or operating time. */
export function normalizeBoundary(boundary) {
  if (boundary == null) return null
  return { topologyRevision: boundary.topologyRevision ?? null,
    activeCaseId: boundary.activeCaseId ?? null,
    cases: boundaryCases(boundary).map(condition => ({ id: condition.id ?? null, operatingAt: normalizeOperatingTime(condition.operatingAt) ?? null,
      nodes: nodeRecords(condition).map(record => Object.fromEntries(nodeFields.map(key => [key, record?.[key] ?? null]))) })) }
}

/** Invoked by Save only: editing and importing may retain unfinished time text. */
export function boundaryTimeIssue(boundary) {
  const seen = new Map()
  for (const [index, condition] of boundaryCases(boundary).entries()) {
    const stamp = parseOperatingTime(condition.operatingAt)
    if (stamp == null) return `第 ${index + 1} 行工况时间无效，请按 YYYY/MM/DD hh:mm 填写真实日期和时间，例如 2026/09/01 08:30。`
    if (seen.has(stamp)) return `第 ${index + 1} 行工况时间与第 ${seen.get(stamp)} 行重复，请为每组工况填写不同时间。`
    seen.set(stamp, index + 1)
  }
  return ''
}

/** Align a detached draft to saved node IDs. Removed/duplicate records remain for explicit review. */
export function hydrateBoundary(input, graph, topologyRevision) {
  const previous = normalizeBoundary(input?.boundary)
  const sourceCases = previous?.cases ?? [{ id: 'case-1', operatingAt: null, nodes: [] }]
  const cases = sourceCases.map(condition => {
    const saved = nodeRecords(condition), used = new Set()
    const nodes = graphNodes(graph).map(node => {
      const index = saved.findIndex((record, i) => !used.has(i) && record?.nodeId === node.id)
      if (index >= 0) used.add(index)
      return copyNode(index < 0 ? null : saved[index], emptyNode(node.id))
    })
    for (let i = 0; i < saved.length; i++) {
      if (!used.has(i)) nodes.push(copyNode(saved[i], emptyNode(saved[i]?.nodeId)))
    }
    return { id: condition.id, operatingAt: condition.operatingAt, nodes }
  })
  return { topologyRevision: topologyRevision ?? previous?.topologyRevision ?? 0,
    activeCaseId: previous == null ? 'case-1' : previous.activeCaseId, cases }
}

/** Display metadata comes from topology; edit row.record to update the original boundary draft. */
export function boundaryRows(graph, boundary) {
  const nodeRoles = roles(graph)
  return graphNodes(graph).map(node => {
    const record = records(boundary).find(item => item.nodeId === node.id) || null
    return { ...record, nodeId: node.id, name: node.name || node.id, nodeType: node.type,
      role: nodeRoles.get(node.id), record }
  })
}

/** These records cannot be mapped uniquely to current topology and must not disappear on reload. */
export function orphanBoundaryRows(boundary, graph) {
  const ids = new Set(graphNodes(graph).map(node => node.id))
  const seen = new Set()
  return records(boundary).flatMap(record => {
    const reason = !ids.has(record.nodeId) ? 'deleted' : seen.has(record.nodeId) ? 'duplicate' : null
    seen.add(record.nodeId)
    return reason ? [{ ...record, name: `${reason === 'deleted' ? '已移除' : '重复'}节点 ${record.nodeId}`, reason, record }] : []
  })
}

/** Summarize entered flows. Null is unknown, never an inferred zero or a solved flow. */
export function boundarySummary(boundary, graph) {
  const rows = boundaryRows(graph, boundary)
  const missingSupplyNodeIds = [], missingWithdrawalNodeIds = []
  const supply = [], withdrawal = []
  for (const row of rows) {
    if (!row.record) {
      if (row.role === 'supply') missingSupplyNodeIds.push(row.nodeId)
      if (row.role === 'delivery') missingWithdrawalNodeIds.push(row.nodeId)
      continue
    }
    if (row.role === 'supply') {
      if (finiteRate(row.supplyRate10k)) supply.push(row.supplyRate10k)
      else missingSupplyNodeIds.push(row.nodeId)
    } else if (row.role === 'delivery' || row.withdrawalRate10k != null) {
      // An internal node without a withdrawal entry is not an identified consumer.
      if (finiteRate(row.withdrawalRate10k)) withdrawal.push(row.withdrawalRate10k)
      else missingWithdrawalNodeIds.push(row.nodeId)
    }
  }
  const total = (values, missing) => {
    if (!values.length || missing.length) return null
    const sum = values.reduce((value, next) => value + next, 0)
    return Number.isFinite(sum) ? sum : null
  }
  const supplyRate10k = total(supply, missingSupplyNodeIds)
  const withdrawalRate10k = total(withdrawal, missingWithdrawalNodeIds)
  const orphanNodeIds = orphanBoundaryRows(boundary, graph).map(row => row.nodeId)
  const complete = supplyRate10k !== null && withdrawalRate10k !== null && !orphanNodeIds.length
  return { supplyRate10k, withdrawalRate10k,
    differenceRate10k: complete ? supplyRate10k - withdrawalRate10k : null,
    missingSupplyNodeIds, missingWithdrawalNodeIds, orphanNodeIds, complete }
}
