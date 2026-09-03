const requireRegressionPoints = (points, xField, yField) => {
  if (!Array.isArray(points) || points.length < 2) {
    throw new Error('回压试井至少需要 2 个有效测试点')
  }
  return points.map(point => {
    const x = Number(point[xField])
    const y = Number(point[yField])
    if (!Number.isFinite(x) || !Number.isFinite(y)) {
      throw new Error('回压试井测试点包含无效数值')
    }
    return { ...point, x, y }
  })
}

const linearRegression = points => {
  const meanX = points.reduce((sum, point) => sum + point.x, 0) / points.length
  const meanY = points.reduce((sum, point) => sum + point.y, 0) / points.length
  const sxx = points.reduce((sum, point) => sum + (point.x - meanX) ** 2, 0)
  if (sxx <= 1e-12) throw new Error('回压试井测试点的自变量不能全部相同')
  const sxy = points.reduce(
    (sum, point) => sum + (point.x - meanX) * (point.y - meanY),
    0
  )
  const slope = sxy / sxx
  const intercept = meanY - slope * meanX
  const total = points.reduce((sum, point) => sum + (point.y - meanY) ** 2, 0)
  const residual = points.reduce(
    (sum, point) => sum + (point.y - intercept - slope * point.x) ** 2,
    0
  )
  const rSquared = total <= 1e-12 ? 1 : Math.max(0, Math.min(1, 1 - residual / total))
  return { intercept, slope, rSquared }
}

// 采气与注气都使用正流量。采气取储层到井底的压力函数降，
// 注气则按资料中的方向取井底到储层的压力函数升。
export const backPressurePotentialDifference = (
  reservoirPotential,
  flowingPotential,
  operationType = 'production'
) => operationType === 'injection'
  ? flowingPotential - reservoirPotential
  : reservoirPotential - flowingPotential

// Back-pressure binomial equation:
//   deltaPsi = A*q + B*q^2
// Linearized for regression as:
//   deltaPsi/q = A + B*q
export const fitBackPressureBinomial = points => {
  const samples = requireRegressionPoints(points, 'flowRate', 'potentialDifference').map(point => {
    if (point.x <= 0 || point.y <= 0) {
      throw new Error('回压试井产量和压力函数差必须大于 0')
    }
    return { ...point, y: point.y / point.x }
  })
  const { intercept, slope, rSquared } = linearRegression(samples)
  if (intercept < 0 || slope < 0) {
    throw new Error('回压试井测试点不能得到有效的二项式系数')
  }
  return {
    darcyCoefficient: intercept,
    nonDarcyCoefficient: slope,
    rSquared,
    transformedPoints: samples.map(point => ({
      ...point,
      transformedPressure: point.y
    }))
  }
}

// Back-pressure exponential equation:
//   q = C*(deltaPsi)^n
// Linearized for regression as:
//   ln(q) = ln(C) + n*ln(deltaPsi)
export const fitBackPressureExponential = points => {
  const samples = requireRegressionPoints(points, 'potentialDifference', 'flowRate').map(point => {
    if (point.x <= 0 || point.y <= 0) {
      throw new Error('回压试井产量和压力函数差必须大于 0')
    }
    return { ...point, x: Math.log(point.x), y: Math.log(point.y) }
  })
  const { intercept, slope, rSquared } = linearRegression(samples)
  const productivityCoefficient = Math.exp(intercept)
  if (!Number.isFinite(productivityCoefficient) || productivityCoefficient <= 0 || slope <= 0) {
    throw new Error('回压试井测试点不能得到有效的指数式系数')
  }
  return {
    productivityCoefficient,
    productivityExponent: slope,
    rSquared
  }
}

export const solveBackPressureBinomialRate = (potentialDifference, darcy, nonDarcy) => {
  if (!Number.isFinite(potentialDifference) || potentialDifference <= 0) return 0
  if (nonDarcy > 1e-12) {
    return (-darcy + Math.sqrt(darcy ** 2 + 4 * nonDarcy * potentialDifference)) /
      (2 * nonDarcy)
  }
  return darcy > 1e-12 ? potentialDifference / darcy : 0
}

export const solveBackPressureExponentialRate = (potentialDifference, coefficient, exponent) =>
  Number.isFinite(potentialDifference) && potentialDifference > 0
    ? coefficient * potentialDifference ** exponent
    : 0
