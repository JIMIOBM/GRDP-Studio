'use strict'

function linearRegression(points) {
  if (!Array.isArray(points) || points.length < 3) {
    throw new RangeError('FlowBalance regression requires at least three points')
  }

  const values = points.map((point, index) => {
    const x = Number(point.x)
    const y = Number(point.y)
    if (!Number.isFinite(x) || !Number.isFinite(y)) {
      throw new TypeError(`Regression point ${index} must contain finite x/y values`)
    }
    return { x, y }
  })

  const count = values.length
  const meanX = values.reduce((sum, point) => sum + point.x, 0) / count
  const meanY = values.reduce((sum, point) => sum + point.y, 0) / count
  let covariance = 0
  let varianceX = 0
  let varianceY = 0

  values.forEach(point => {
    const dx = point.x - meanX
    const dy = point.y - meanY
    covariance += dx * dy
    varianceX += dx * dx
    varianceY += dy * dy
  })

  if (varianceX === 0) throw new RangeError('FlowBalance regression x values have zero variance')
  if (varianceY === 0) throw new RangeError('FlowBalance regression y values have zero variance')

  const slope = covariance / varianceX
  const intercept = meanY - slope * meanX
  let residualSum = 0
  values.forEach(point => {
    const residual = point.y - (slope * point.x + intercept)
    residualSum += residual * residual
  })

  return {
    slope,
    intercept,
    rSquared: Math.max(0, Math.min(1, 1 - residualSum / varianceY)),
    sampleCount: count
  }
}

module.exports = { linearRegression }
