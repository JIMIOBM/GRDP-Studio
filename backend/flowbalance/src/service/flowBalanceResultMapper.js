'use strict'

function mapFlowBalanceCalculationToDynamicResult(calculation) {
  if (!calculation || typeof calculation !== 'object') {
    throw new TypeError('FlowBalance calculation result is required')
  }

  const regression = normalizeRegression(calculation.regression)
  const originalGasVolume = finitePositive(calculation.originalGasVolume ?? calculation.ogip, 'originalGasVolume')
  const data = normalizeSeries(calculation.regression?.transformedSeries ?? calculation.transformedSeries ?? calculation.data)

  return {
    resultId: requiredString(calculation.resultId, 'resultId'),
    persisted: false,
    idKind: 'runtime',
    nodeType: requiredInteger(calculation.nodeType, 'nodeType'),
    pressureSource: requiredString(calculation.pressureSource, 'pressureSource'),
    pressureKind: requiredString(calculation.pressureKind, 'pressureKind'),
    input: {
      wellName: requiredString(calculation.wellName, 'wellName'),
      nodeType: requiredInteger(calculation.nodeType, 'nodeType'),
      pressureSource: requiredString(calculation.pressureSource, 'pressureSource'),
      pressureKind: requiredString(calculation.pressureKind, 'pressureKind'),
      sourceUnit: requiredString(calculation.sourceUnit, 'sourceUnit'),
      standardUnit: requiredString(calculation.standardUnit, 'standardUnit'),
      algorithmVersion: requiredString(calculation.algorithmVersion, 'algorithmVersion'),
      originalGasVolumeUnit: calculation.originalGasVolumeUnit || 'cumulative_gas_production source unit',
      ...(calculation.inputSummary || {})
    },
    result: {
      resultId: requiredString(calculation.resultId, 'resultId'),
      persisted: false,
      idKind: 'runtime',
      nodeType: requiredInteger(calculation.nodeType, 'nodeType'),
      pressureSource: requiredString(calculation.pressureSource, 'pressureSource'),
      pressureKind: requiredString(calculation.pressureKind, 'pressureKind'),
      originalGasVolume,
      gradient: regression.slope,
      slope: regression.slope,
      intercept: regression.intercept,
      rsquared: regression.rSquared,
      reliability: calculation.reliability ?? null,
      regression
    },
    data,
    diagnostics: {
      ...(calculation.diagnostics || {}),
      regression,
      excludedPoints: Array.isArray(calculation.excludedPoints) ? calculation.excludedPoints : []
    },
    metadata: {
      resultId: calculation.resultId,
      persisted: false,
      idKind: 'runtime',
      wellName: calculation.wellName,
      nodeType: calculation.nodeType,
      pressureSource: calculation.pressureSource,
      pressureKind: calculation.pressureKind,
      sourceUnit: calculation.sourceUnit,
      standardUnit: calculation.standardUnit,
      algorithmVersion: calculation.algorithmVersion
    }
  }
}

function normalizeRegression(regression) {
  if (!regression || typeof regression !== 'object') {
    throw new TypeError('regression is required')
  }

  return {
    slope: finiteNumber(regression.slope, 'regression.slope'),
    intercept: finiteNumber(regression.intercept, 'regression.intercept'),
    rSquared: finiteNumber(regression.rSquared ?? regression.rsquared, 'regression.rSquared'),
    sampleCount: requiredInteger(regression.sampleCount, 'regression.sampleCount')
  }
}

function normalizeSeries(series) {
  if (!Array.isArray(series) || series.length === 0) {
    throw new TypeError('transformed series must contain at least one point')
  }

  return series.map((point, index) => ({
    index: point.index ?? index + 1,
    date: point.date ?? null,
    pseudotime: finiteNumber(point.pseudotime ?? point.pseudoTime ?? point.x, `series[${index}].pseudotime`),
    pressure: finiteNumber(point.pressure ?? point.y, `series[${index}].pressure`),
    isDeleted: Boolean(point.isDeleted),
    exclusionReason: point.exclusionReason ?? null
  }))
}

function finitePositive(value, name) {
  const number = finiteNumber(value, name)
  if (number <= 0) throw new RangeError(`${name} must be positive`)
  return number
}

function finiteNumber(value, name) {
  const number = Number(value)
  if (!Number.isFinite(number)) throw new TypeError(`${name} must be a finite number`)
  return number
}

function requiredInteger(value, name) {
  const number = Number(value)
  if (!Number.isInteger(number)) throw new TypeError(`${name} must be an integer`)
  return number
}

function requiredString(value, name) {
  if (typeof value !== 'string' || value.trim() === '') {
    throw new TypeError(`${name} must be a non-empty string`)
  }
  return value
}

module.exports = {
  mapFlowBalanceCalculationToDynamicResult
}
