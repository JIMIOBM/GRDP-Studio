import { PIPELINE_BATCH_VERSION, conditionTime } from './pipelineBatch.js'

const finite = value => typeof value === 'number' && Number.isFinite(value)
const orderedCases = result => [...(result?.cases || [])].sort((a, b) =>
  (conditionTime(a.operatingAt) ?? Infinity) - (conditionTime(b.operatingAt) ?? Infinity))

/** Device identities match the ordered device list on each saved topology node. */
export function topologyEquipment(graph) {
  return (graph?.nodes || []).filter(node => ['valve', 'compressor'].includes(node.type)).flatMap(node =>
    [{ ...node.parameters, type: node.type, name: node.name }, ...(node.parameters?.extraEquipment || [])]
      .map((device, index) => ({ ...device, id: `${node.id}:${index}`, nodeId: node.id,
        nodeName: node.name, sequence: index + 1,
        edgeId: graph.edges?.find(edge => edge.target === node.id)?.id })))
}

/** Results and limits always come from the same immutable batch snapshot. */
export function constraintDataRows(detail, kind, current) {
  if (!current || detail?.result?.algorithmVersion !== PIPELINE_BATCH_VERSION) return []
  const items = kind === 'equipment' ? topologyEquipment(detail.graph) : (detail.graph?.edges || [])
  return orderedCases(detail.result).flatMap(condition => {
    const computed = new Map((condition.status === 'success' ? condition[kind] || [] : [])
      .map(row => [kind === 'equipment' ? row.id : row.edgeId, row]))
    return items.map(item => {
      const value = computed.get(item.id)
      return { ...item, ...value, rowKey: `${condition.caseId}/${item.id}`, caseId: condition.caseId,
        operatingAt: condition.operatingAt, status: value?.status || 'not_evaluated',
        reason: condition.error || value?.reason || (!value ? '本工况未取得评价结果' : '') }
    })
  })
}

export function constraintChartSeries(rows, id, kind, metric) {
  const selected = rows.filter(row => (kind === 'equipment' ? row.id : row.edgeId || row.id) === id)
    .filter(row => conditionTime(row.operatingAt) != null)
    .sort((a, b) => conditionTime(a.operatingAt) - conditionTime(b.operatingAt))
  if (!selected.length) return []
  const fields = kind === 'equipment'
    ? metric === 'pressure' ? [['inletMpa', '入口压力'], ['outletMpa', '出口压力'], ['maxPressureMpa', '允许压力']]
      : metric === 'power' ? [['powerKw', '压缩机功率'], ['maxPowerKw', '允许功率']]
        : [['inletC', '入口温度'], ['outletC', '出口温度']]
    : metric === 'margin' ? [['marginC', '最小温度裕度'], ['zero', '平衡边界']]
      : [['temperatureC', '最小裕度位置管道温度'], ['equilibriumC', '同点水合物平衡温度（经验预测）']]
  return fields.map(([key, name]) => ({ id: `${id}:${key}`, name, data: selected.map(row => {
    const evaluated = ['pass', 'fail', 'risk', 'equilibrium', 'conditional'].includes(row.status)
    const value = key === 'zero' ? 0 : row[key]
    return [conditionTime(row.operatingAt), evaluated && finite(value) ? value : null]
  }) }))
}
