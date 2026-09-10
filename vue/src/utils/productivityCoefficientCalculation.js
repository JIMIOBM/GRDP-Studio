import { backPressurePotentialDifference } from './backPressureCalculation.js'

export const ATMOSPHERIC_PRESSURE_MPA = 0.101325
// 二项式采气无阻流量规定的井底流压
export const BINOMIAL_OPEN_FLOW_PRESSURE_MPA = 0.1


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

export const normalizeCoefficientFitPoint = ({
  operationType = 'production',
  flowRate,
  flowingPressure,
  curve
}) => {
  const direction = operationType === 'injection' ? 'injection' : 'production'
  const operationLabel = direction === 'injection' ? '注气' : '采气'
  const pressureLabel = direction === 'injection' ? '井底压力' : '井底流压'
  const flowText = String(flowRate ?? '').trim()
  const pressureText = String(flowingPressure ?? '').trim()

  if (!flowText && !pressureText) return null
  if (!flowText || !pressureText) {
    throw new Error(`请同时填写${operationLabel}拟合点的${operationLabel}量和${pressureLabel}`)
  }

  const normalizedFlowRate = Number(flowText)
  const normalizedPressure = Number(pressureText)
  if (!Number.isFinite(normalizedFlowRate) || normalizedFlowRate < 0) {
    throw new Error(`${operationLabel}拟合点${operationLabel}量必须是大于或等于 0 的有效数值`)
  }
  if (!Number.isFinite(normalizedPressure)) {
    throw new Error(`${operationLabel}拟合点${pressureLabel}必须是有效数值`)
  }

  const pressureValues = curve?.points?.map(point => Number(point.flowingPressure))
    .filter(Number.isFinite) || []
  if (pressureValues.length) {
    const minimumPressure = Math.min(...pressureValues)
    const maximumPressure = Math.max(...pressureValues)
    if (normalizedPressure < minimumPressure || normalizedPressure > maximumPressure) {
      throw new Error(
        `${operationLabel}拟合点${pressureLabel}应在 ${minimumPressure.toFixed(4)}～${maximumPressure.toFixed(4)} MPa 之间`
      )
    }
  }

  return { flowRate: normalizedFlowRate, flowingPressure: normalizedPressure }
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

const solveBinomialFlowRate = (
  potentialDifference,
  a,
  b
) => {
  const rawDifference =
    Number(potentialDifference)

  if (!Number.isFinite(rawDifference)) {
    throw new Error(
      '二项式压力差必须是有效数值'
    )
  }

  /*
   * 采气：
   * F(Pr) - F(Pwf) >= 0
   *
   * 注气：
   * F(Pwf) - F(Pr) >= 0
   */
  if (rawDifference < -1e-10) {
    throw new Error(
      '二项式压力差为负，请检查注采方向以及 Pr、Pwf 的大小关系'
    )
  }

  const difference =
    Math.max(0, rawDifference)

  if (difference === 0) {
    return 0
  }

  // B = 0 时退化成 Aq = Δ
  if (Math.abs(b) <= 1e-14) {
    return difference / a
  }

  const discriminant =
    a ** 2 + 4 * b * difference

  if (discriminant < 0) {
    throw new Error(
      '当前二项式参数无实数产量解'
    )
  }

  return (
    (2 * difference) /
    (
      a +
      Math.sqrt(discriminant)
    )
  )
}


export const calculateBinomialCoefficientCurve = ({
  reservoirPressure,
  darcyCoefficient,
  nonDarcyCoefficient,
  calculationMethod,
  operationType = 'production',


  fittedFlowingPressure = null,

  pvtResultRows = [],
  steps = 240
}) => {
  const pressure =
    Number(reservoirPressure)

  const a =
    Number(darcyCoefficient)

  const b =
    Number(nonDarcyCoefficient)

  const direction =
    operationType === 'injection'
      ? 'injection'
      : 'production'

  if (
    !Number.isFinite(pressure) ||
    pressure <= 0
  ) {
    throw new Error(
      '二项式地层压力 Pr 必须大于 0 MPa'
    )
  }

  /*
   * 只有采气需要保证
   * Pr > 0.1，
   * 因为无阻流量终点规定为
   * Pwf = 0.1 MPa。
   */
  if (
    direction === 'production' &&
    pressure <=
      BINOMIAL_OPEN_FLOW_PRESSURE_MPA
  ) {
    throw new Error(
      `采气二项式地层压力 Pr 必须大于 ` +
      `${BINOMIAL_OPEN_FLOW_PRESSURE_MPA} MPa`
    )
  }

  if (
    !Number.isFinite(a) ||
    a < 0
  ) {
    throw new Error(
      '二项式系数 A 必须大于等于 0'
    )
  }

  if (
    !Number.isFinite(b) ||
    b < 0
  ) {
    throw new Error(
      '二项式系数 B 必须大于等于 0'
    )
  }

  if (
    a === 0 &&
    b === 0
  ) {
    throw new Error(
      '二项式系数 A、B 不能同时为 0'
    )
  }

  const method =
    normalizeCoefficientPressureMethod(
      calculationMethod
    )

  const pvtCurve =
    normalizeCoefficientPvtCurve(
      pvtResultRows
    )

  let endPressure

  if (
    direction === 'production'
  ) {
    endPressure =
      BINOMIAL_OPEN_FLOW_PRESSURE_MPA
  } else {
    const parameterPressure =
      numberFrom(
        fittedFlowingPressure
      )

    if (
      !Number.isFinite(
        parameterPressure
      )
    ) {
      throw new Error(
        '注气时请输入参数点井底注入压力 Pwf'
      )
    }

    if (
      parameterPressure <= pressure
    ) {
      throw new Error(
        `注气参数点 Pwf 必须大于地层压力 ` +
        `Pr（${pressure} MPa）`
      )
    }

    endPressure =
      parameterPressure
  }

  if (
    method === 'pseudo-pressure'
  ) {
    if (
      pvtCurve.length < 2
    ) {
      throw new Error(
        '二项式拟压力法需要所选PVT表包含气体拟压力数据；' +
        '可改用压力平方法/压力法，或先补全PVT数据'
      )
    }

    const pvtMinimumPressure =
      pvtCurve[0].pressure

    const pvtMaximumPressure =
      pvtCurve.at(-1).pressure

    const requiredMinimumPressure =
      Math.min(
        pressure,
        endPressure
      )

    const requiredMaximumPressure =
      Math.max(
        pressure,
        endPressure
      )

    if (
      requiredMinimumPressure <
        pvtMinimumPressure - 1e-9 ||
      requiredMaximumPressure >
        pvtMaximumPressure + 1e-9
    ) {
      throw new Error(
        `二项式${
          direction === 'injection'
            ? '注气'
            : '采气'
        }拟压力法需要PVT拟压力数据覆盖 ` +
        `${requiredMinimumPressure}～` +
        `${requiredMaximumPressure} MPa，` +
        `当前仅覆盖 ` +
        `${pvtMinimumPressure}～` +
        `${pvtMaximumPressure} MPa`
      )
    }
  }

  const pointCount =
    Math.max(
      2,
      Math.floor(
        Number(steps)
      ) || 240
    )

  /*
   * Pr 对应的压力函数：
   *
   * 压力法        -> Pr
   * 压力平方法    -> Pr²
   * 拟压力法      -> m(Pr)
   */
  const reservoirPotential =
    coefficientPressurePotential(
      pressure,
      method,
      pvtCurve
    )

  const points =
    Array.from(
      {
        length:
          pointCount + 1
      },
      (_, index) => {
        const ratio =
          index / pointCount

        /*
         * 采气：
         * Pr -> 0.1
         *
         * 注气：
         * Pr -> 参数点 Pwf
         */
        const flowingPressure =
          pressure +
          (
            endPressure -
            pressure
          ) * ratio

        const flowingPotential =
          coefficientPressurePotential(
            flowingPressure,
            method,
            pvtCurve
          )

        const difference =
          direction === 'injection'
            /*
             * 注气：
             * F(Pwf)-F(Pr)
             */
            ? flowingPotential -
              reservoirPotential

            /*
             * 采气：
             * F(Pr)-F(Pwf)
             */
            : reservoirPotential -
              flowingPotential

        const flowRate =
          solveBinomialFlowRate(
            difference,
            a,
            b
          )

        if (
          !Number.isFinite(
            flowRate
          ) ||
          flowRate < 0
        ) {
          throw new Error(
            '当前二项式参数无法生成有效的IPR曲线'
          )
        }

        return {
          flowRate,
          flowingPressure,

          potentialDifference:
            Math.max(
              0,
              difference
            )
        }
      }
    )

  return {
    operationType:
      direction,

    calculationMethod:
      method,

    reservoirPressure:
      pressure,

    limitPressure:
      endPressure,

    /*
     * 采气：
     * Pwf = 0.1 对应无阻流量。
     *
     * 注气：
     * 当前参数点 Pwf 下，
     * A/B 对应的理论注气量。
     */
    limitRate:
      points.at(-1).flowRate,

    points
  }
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
