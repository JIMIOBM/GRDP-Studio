import { dataManagementApi } from '@/api/docker'
import { pvtStorageApi } from '@/api/pvtStorage'
import { numberOf, readSourceNumber, sourceCollection, unpack } from '@/utils/temperatureSources'

const failureMessage = (label, reason) => {
  const message = reason?.response?.data?.message || reason?.response?.data?.msg || reason?.message
  return `${label}加载失败${message ? `：${message}` : ''}`
}

const fulfilledCollection = result => result.status === 'fulfilled'
  ? sourceCollection(result.value)
  : { items: [], fields: [] }

const normalizePvtRecords = result => {
  if (result.status !== 'fulfilled') return []
  const payload = unpack(result.value)
  const records = Array.isArray(payload)
    ? payload
    : Array.isArray(payload.items)
      ? payload.items
      : Array.isArray(payload.records)
        ? payload.records
        : []
  return records.filter(record => Number.isFinite(Number(record?.pvtId)))
}

const buildTubings = ({ items, fields }) => {
  const candidates = items.filter(row => {
    const type = String(row?.type ?? row?.casingType ?? row?.casing_type ?? '').toLowerCase()
    return !type || type.includes('油管') || type.includes('tubing')
  })
  const rows = candidates.length ? candidates : items
  return rows.flatMap((row, index) => {
    const diameter = readSourceNumber(row, ['innerDiameter', 'inner_diameter', '内径'], fields)
    if (diameter === null || diameter <= 0) return []
    const top = readSourceNumber(row, ['topMeasuredDepth', 'top_measured_depth', '顶部测量深度'], fields)
    const bottom = readSourceNumber(row, ['bottomMeasuredDepth', 'bottom_measured_depth', '底部测量深度'], fields)
    const type = row?.type ?? row?.casingType ?? row?.casing_type ?? '油管'
    const interval = top !== null || bottom !== null
      ? `（${top ?? 0}–${bottom ?? '?'} m）`
      : ''
    return [{ key: `${index}-${diameter}-${top ?? ''}-${bottom ?? ''}`, diameter, label: `${type}${interval} · 内径 ${diameter} mm` }]
  })
}

const deepestDeviation = ({ items, fields }) => items.reduce((deepest, row) => {
  const depth = readSourceNumber(row, ['measuredDepth', 'measured_depth', '测量深度'], fields)
  if (depth === null || depth <= (deepest?.depth ?? Number.NEGATIVE_INFINITY)) return deepest
  return {
    row,
    depth,
    angle: readSourceNumber(row, ['inclination', 'wellInclination', 'well_inclination', '井斜角'], fields)
  }
}, null)

export const loadTemperatureSources = async (projectId, gasReservoirId, wellName) => {
  const calls = await Promise.allSettled([
    dataManagementApi.getWellDeviation(projectId, gasReservoirId, wellName, { silentError: true }),
    dataManagementApi.getWellCompletion(projectId, gasReservoirId, wellName, { silentError: true }),
    dataManagementApi.getProductionData(projectId, gasReservoirId, wellName, { silentError: true }),
    pvtStorageApi.list(projectId, gasReservoirId, wellName)
  ])
  const labels = ['井斜数据', '完井数据', '注采数据', 'PVT记录']
  const errors = calls.flatMap((result, index) => result.status === 'rejected'
    ? [failureMessage(labels[index], result.reason)]
    : [])

  const deviation = fulfilledCollection(calls[0])
  const completion = fulfilledCollection(calls[1])
  const production = fulfilledCollection(calls[2])
  const tubings = buildTubings(completion)
  const deepest = deepestDeviation(deviation)

  if (!deepest) errors.push('井斜数据中没有有效的测量深度')
  if (!tubings.length) errors.push('完井数据中没有有效的油管内径')
  if (!production.items.length) errors.push('当前井没有可用的注采数据')

  return {
    input: {
      depth: deepest?.depth ?? null,
      angle: numberOf(deepest?.angle),
      idTubing: tubings[0]?.diameter ?? null,
      gammaG: null,
      rhoL: 1000
    },
    tubings,
    production,
    productionFields: production.fields,
    pvtRecords: normalizePvtRecords(calls[3]),
    errors
  }
}
