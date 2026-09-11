import { PIPELINE_BATCH_VERSION, conditionTime, networkOrder } from './pipelineBatch.js'

const finite = value => typeof value === 'number' && Number.isFinite(value)

/** Compare all node measurements against the very same batch input and topology snapshot. */
export function boundaryComparisonRows(detail, current = false) {
  if (!current || detail?.result?.algorithmVersion !== PIPELINE_BATCH_VERSION
      || detail.input?.boundary?.topologyRevision !== detail.topologyRevision) return []
  let edges
  try { edges = networkOrder(detail.graph) } catch { return [] }
  const graph = detail.graph, conditions = new Map((detail.input.boundary.cases || []).map(row => [row.id, row]))
  const orderedNodes = [graph.nodes.find(node => node.type === 'well'), ...edges.map(edge => graph.nodes.find(node => node.id === edge.target))]
  return [...(detail.result.cases || [])].sort((a, b) => (conditionTime(a.operatingAt) ?? Infinity) - (conditionTime(b.operatingAt) ?? Infinity))
    .flatMap(condition => {
      const measurements = conditions.get(condition.caseId)?.nodes
      if (!Array.isArray(measurements) || new Set(measurements.map(row => row.nodeId)).size !== measurements.length) return []
      const pipes = new Map((condition.status === 'success' ? condition.pipes || [] : []).map(pipe => [pipe.edgeId, pipe]))
      return orderedNodes.flatMap(node => {
        const record = measurements.find(row => row.nodeId === node.id)
        if (!record) return []
        const incoming = edges.find(edge => edge.target === node.id)
        const outgoing = edges.filter(edge => edge.source === node.id)
        const arrivals = incoming ? pipes.get(incoming.id) : null
        const departures = outgoing.map(edge => pipes.get(edge.id))
        const device = [...(condition.status === 'success' ? condition.equipment || [] : [])].filter(item => item.nodeId === node.id)
          .sort((a, b) => a.sequence - b.sequence).at(-1)
        const root = node.type === 'well'
        const pressure = root ? departures[0]?.inletMpa : device?.outletMpa ?? arrivals?.outletMpa
        const temperature = root ? departures[0]?.inletC : device?.outletC ?? arrivals?.outletC
        const totalOut = departures.every(pipe => finite(pipe?.rate10k)) ? departures.reduce((sum, pipe) => sum + pipe.rate10k, 0) : null
        const rate = root ? totalOut : finite(arrivals?.rate10k) && finite(totalOut) ? arrivals.rate10k - totalOut : null
        return [
          ['pressure', '压力', 'MPa（绝压）', record.pressureMpa, pressure],
          ['temperature', '温度', '℃', record.temperatureC, temperature],
          ['flow', root ? '供气量' : '节点分输量', '10⁴m³/d', root ? record.supplyRate10k : record.withdrawalRate10k, rate]
        ].filter(([, , , measured]) => finite(measured)).map(([key, parameter, unit, measured, calculated]) => ({
          rowKey: `${condition.caseId}/${node.id}/${key}`, caseId: condition.caseId, operatingAt: condition.operatingAt,
          nodeId: node.id, name: node.name, key, parameter, unit, measured,
          calculated: finite(calculated) ? calculated : null, difference: finite(calculated) ? calculated - measured : null,
          note: condition.error || (device ? '节点温压取末台串联设备出口；分输量按节点流量守恒' : '')
        }))
      })
    })
}

export function comparisonChartSeries(rows, nodeId, key) {
  const values = rows.filter(row => row.nodeId === nodeId && row.key === key && conditionTime(row.operatingAt) != null)
    .sort((a, b) => conditionTime(a.operatingAt) - conditionTime(b.operatingAt))
  return values.length ? [['calculated', '管流计算'], ['measured', '边界实测']].map(([field, name]) => ({ id: `${nodeId}:${field}`, name,
    data: values.map(row => [conditionTime(row.operatingAt), finite(row[field]) ? row[field] : null]) })) : []
}
