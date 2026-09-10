import { backPressurePotentialDifference } from './backPressureCalculation.js'

export const ATMOSPHERIC_PRESSURE_MPA = 0.101325

export const normalizeCoefficientPressureMethod = value => ({
  '拟压力': 'pseudo-pressure',
  '压力平方方法': 'pressure-squared',
  '压力平方法': 'pressure-squared',
  '压力法': 'pressure'
}[value] || value)

const numberFrom = value => {
  if (value === null || value === undefined || value === '') return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}

const readField = (record, names) => {
  for (const name of names) {
    if (record?.[name] !== null && record?.[name] !== undefined && record?.[name] !== '') {
      return record[name]
    }
  }
  return null
}

export const normalizeCoefficientPvtCurve = rows => {
  const byPressure = new Map()
  ;(Array.isArray(rows) ? rows : []).forEach(row => {
    const pressure = numberFrom(Array.isArray(row)
      ? row[0]
      : readField(row, ['pressure', 'formationPressure', 'reservoirPressure', '压力', '压力(MPa)']))
    const pseudoPressure = numberFrom(Array.isArray(row)
      ? row[3]
      : readField(row, [
          'pseudoPressure', 'pseudo_pressure', 'gasPseudoPressure', 'mP', 'mp',
          '气体拟压力', '气体拟压力(MPa²/(mPa·s))'
        ]))
    if (Number.isFinite(pressure) && pressure >= 0 && Number.isFinite(pseudoPressure)) {
      byPressure.set(pressure, { pressure, pseudoPressure })
    }
  })
  if (!byPressure.has(0)) byPressure.set(0, { pressure: 0, pseudoPressure: 0 })
  return [...byPressure.values()].sort((left, right) => left.pressure - right.pressure)
}

const interpolate = (value, points, xField, yField, rangeName) => {
  if (!Number.isFinite(value) || value < 0) throw new Error('压力必须是非负有效数值')
  if (!Array.isArray(points) || points.length < 2) {
    throw new Error('所选PVT表暂无气体拟压力数据，请先完成天然气PVT计算或导入结果')
  }
  const first = points[0]
  const last = points.at(-1)
  if (value < first[xField] - 1e-9 || value > last[xField] + 1e-9) {
    throw new Error(`${rangeName}范围仅为 ${first[xField]}～${last[xField]}`)
  }
  const exact = points.find(point => Math.abs(point[xField] - value) <= 1e-9)
  if (exact) return exact[yField]
  const upperIndex = points.findIndex(point => point[xField] > value)
  const lower = points[upperIndex - 1]
  const upper = points[upperIndex]
  if (!lower || !upper || upper[xField] === lower[xField]) {
    throw new Error(`${rangeName}数据无法覆盖 ${value}`)
  }
  const ratio = (value - lower[xField]) / (upper[xField] - lower[xField])
  return lower[yField] + ratio * (upper[yField] - lower[yField])
}

export const coefficientPressurePotential = (pressure, method, pvtCurve = []) => {
  if (method === 'pressure') return pressure
  if (method === 'pressure-squared') return pressure ** 2
  if (method === 'pseudo-pressure') {
    return interpolate(pressure, pvtCurve, 'pressure', 'pseudoPressure', 'PVT拟压力压力')
  }
  throw new Error('不支持的计算方法')
}

const injectionLimit = (reservoirPressure, maximumFlowingPressure, method, pvtCurve) => {
  const maximum = numberFrom(maximumFlowingPressure)
  if (!Number.isFinite(maximum) || maximum <= reservoirPressure) {
    throw new Error(`最大井底注入压力必须大于地层压力 ${reservoirPressure} MPa`)
  }
  // 同时校验拟压力法所选 PVT 表能够覆盖用户指定的注入压力。
  coefficientPressurePotential(maximum, method, pvtCurve)
  return maximum
}

export const calculateExponentialCoefficientCurve = ({
  reservoirPressure,
  coefficient,
  exponent,
  calculationMethod,
  operationType = 'production',
  maximumFlowingPressure = null,
  pvtResultRows = [],
  steps = 240
}) => {
  const pressure = Number(reservoirPressure)
  const c = Number(coefficient)
  const n = Number(exponent)
  const direction = operationType === 'injection' ? 'injection' : 'production'
  const coefficientLabel = direction === 'injection' ? '注气能力系数 C' : '产能系数 C'
  const exponentLabel = direction === 'injection' ? '注气指数 n' : '产能指数 n'
  if (!Number.isFinite(pressure) || pressure <= ATMOSPHERIC_PRESSURE_MPA) {
    throw new Error('计算IPR曲线的最大地层压力必须大于大气压')
  }
  if (!Number.isFinite(c) || c <= 0) throw new Error(`${coefficientLabel}必须大于 0`)
  if (!Number.isFinite(n) || n <= 0) throw new Error(`${exponentLabel}必须大于 0`)

  const method = normalizeCoefficientPressureMethod(calculationMethod)
  const pvtCurve = normalizeCoefficientPvtCurve(pvtResultRows)
  const minimumPressure = Math.min(pressure, ATMOSPHERIC_PRESSURE_MPA)
  const maximumPressure = direction === 'injection'
    ? injectionLimit(pressure, maximumFlowingPressure, method, pvtCurve)
    : pressure
  const pointCount = Math.max(2, Math.floor(Number(steps)) || 240)
  const reservoirPotential = coefficientPressurePotential(pressure, method, pvtCurve)
  const points = Array.from({ length: pointCount + 1 }, (_, index) => {
    const ratio = index / pointCount
    const flowingPressure = direction === 'injection'
      ? pressure + (maximumPressure - pressure) * ratio
      : pressure - (pressure - minimumPressure) * ratio
    const flowingPotential = coefficientPressurePotential(flowingPressure, method, pvtCurve)
    const difference = backPressurePotentialDifference(
      reservoirPotential,
      flowingPotential,
      direction
    )
    const flowRate = difference <= 0 ? 0 : c * difference ** n
    if (!Number.isFinite(flowRate)) throw new Error('当前参数无法生成有效的IPR曲线')
    return { flowRate, flowingPressure, potentialDifference: Math.max(0, difference) }
  })

  return {
    operationType: direction,
    calculationMethod: method,
    reservoirPressure: pressure,
    limitPressure: maximumPressure,
    limitRate: points.at(-1).flowRate,
    points
  }
}
