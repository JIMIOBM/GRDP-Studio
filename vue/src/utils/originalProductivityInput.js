const isPresent = value => value !== null && value !== undefined && value !== ''

/** Normalize one-point inputs exactly like the local calculator before sending them to the platform. */
export function buildOriginalProductivityInputItems(points, fallbackPressure, operationType = 'production') {
  const injection = operationType === 'injection'
  const normalized = (Array.isArray(points) ? points : [])
    .filter(point => point && isPresent(point.flowRate) && isPresent(point.flowingPressure))
    .map((point, index) => {
      const flowRate = Number(point.flowRate)
      const flowingPressure = Number(point.flowingPressure)
      const recoveryPressure = isPresent(point.recoveryPressure)
        ? Number(point.recoveryPressure)
        : Number(fallbackPressure)

      if (!Number.isFinite(flowRate) || flowRate <= 0) {
        throw new Error(injection ? '测试注气量必须大于 0' : '测试气产量必须大于 0')
      }
      if (![flowingPressure, recoveryPressure].every(Number.isFinite)) {
        throw new Error('压力数据必须是有效数值')
      }
      if (!injection && recoveryPressure <= flowingPressure) {
        throw new Error('采气时地层/恢复压力必须大于测试流压')
      }
      if (injection && flowingPressure <= recoveryPressure) {
        throw new Error(`注气时井底注入压力必须大于地层压力（当前 ${flowingPressure} MPa ≤ ${recoveryPressure} MPa）`)
      }
      return {
        sequence: Number(point.sequence || index + 1),
        flowRate,
        flowingPressure,
        recoveryPressure
      }
    })
    .sort((left, right) => left.sequence - right.sequence)

  if (normalized.length < 1) throw new Error('至少需要 1 个有效测试点')
  if (normalized.some(point => !Number.isInteger(point.sequence) || point.sequence <= 0)) {
    throw new Error('测点序号必须为大于 0 的整数')
  }
  if (new Set(normalized.map(point => point.sequence)).size !== normalized.length) {
    throw new Error('测点序号不能重复')
  }

  return normalized.map(point => ({
    testPointNumber: point.sequence,
    reserviorPressure: point.recoveryPressure,
    testDailyGasProduction: point.flowRate,
    testFlowPressure: point.flowingPressure,
    testDailyOilProduction: 0
  }))
}
