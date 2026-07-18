'use strict'

const { randomUUID } = require('node:crypto')
const { InvalidDataError } = require('../domain/errors')
const { NODE_TYPES } = require('../model/flowBalanceMapping')
const { calculateZFactor } = require('./gasDeviationFactor')
const { linearRegression } = require('./regression')

const ALGORITHM_VERSION = 'mattar-mcneil-flowing-pz-preview-v1'

class FlowBalanceCalculator {
  calculate(preparedInput, request = {}) {
    const waterGasRatioLimit = optionalNonNegative(request.waterGasRatioLimit, 'waterGasRatioLimit')
    const calculations = []
    const errors = []
    for (const well of preparedInput.wells) {
      for (const source of well.nodeSources) {
        try {
          assertOfficialBackendStatus(well, source)
          calculations.push(calculateWellSource(well, source, waterGasRatioLimit))
        } catch (error) {
          if (!request.includePartialErrors) throw error
          errors.push({
            status: 'error',
            persisted: false,
            idKind: 'runtime',
            wellName: well.wellName,
            nodeType: source.nodeType,
            pressureSource: source.pressureSource,
            pressureKind: source.pressureKind,
            code: error.code || 'FLOW_BALANCE_CALCULATION_ERROR',
            message: error.message || 'FlowBalance calculation failed.',
            details: {
              wellName: well.wellName,
              nodeType: source.nodeType,
              pressureSource: source.pressureSource,
              ...(error.details || {})
            }
          })
        }
      }
    }
    return {
      runId: randomUUID(),
      persisted: false,
      idKind: 'runtime',
      projectId: preparedInput.projectId,
      gasReservoirId: preparedInput.gasReservoirId,
      calculations,
      errors
    }
  }
}

function assertOfficialBackendStatus(well, source) {
  const status = well.officialFlowBalanceStatus
  if (status?.status !== 'error') return
  throw new InvalidDataError(status.message, {
    wellName: well.wellName,
    nodeType: source.nodeType,
    pressureSource: source.pressureSource,
    statusSource: status.source,
    loggedAt: status.createdAt
  })
}

function calculateWellSource(well, source, waterGasRatioLimit) {
  const reservoir = well.reservoirInput
  const initialPressureMpa = positive(reservoir.originalFormationPressure.value, 'originalFormationPressure') / 1000000
  const temperatureK = positive(reservoir.formationTemperature.value, 'formationTemperature')
  const gas = {
    specificGravity: positive(reservoir.specificGravity.value, 'specificGravity'),
    hydrogenSulfide: fraction(reservoir.hydrogenSulfide.value, 'hydrogenSulfide'),
    carbonDioxide: fraction(reservoir.carbonDioxide.value, 'carbonDioxide')
  }
  const initialZ = calculateZFactor({ pressureMpa: initialPressureMpa, temperatureK, ...gas })
  const initialPOverZ = initialPressureMpa / initialZ
  const totalProductionRows = well.production.length
  const transformedSeries = []
  const used = []
  const reasonCounts = {}
  let missingPressureCount = 0

  well.production.forEach((point, index) => {
    const pressure = source.nodeType === NODE_TYPES.WELLHEAD_FLOWING_PRESSURE
      ? point.wellHeadTubingPressure
      : point.calculatedBottomHolePressure
    const cumulative = Number(point.cumulativeGas.value)
    const rawWaterGasRatio = point.waterGasRatio.value
    const wgr = rawWaterGasRatio === null || rawWaterGasRatio === undefined || rawWaterGasRatio === ''
      ? null
      : Number(rawWaterGasRatio)
    const reasons = []
    if (!Number.isFinite(cumulative) || cumulative <= 0) reasons.push('invalid_cumulative_gas')

    if (pressure.pressureMpa === null) {
      missingPressureCount++
    } else if (!Number.isFinite(pressure.pressureMpa) || pressure.pressureMpa <= 0) {
      reasons.push('invalid_flowing_pressure')
    }

    if (Number.isFinite(pressure.pressureMpa) && pressure.pressureMpa >= initialPressureMpa) reasons.push('flowing_pressure_not_below_initial')
    if (waterGasRatioLimit !== null && Number.isFinite(wgr) && wgr > waterGasRatioLimit) reasons.push('water_gas_ratio_above_limit')

    let pOverZ = null
    if (reasons.length === 0 && pressure.pressureMpa !== null) {
      const z = calculateZFactor({ pressureMpa: pressure.pressureMpa, temperatureK, ...gas })
      pOverZ = pressure.pressureMpa / z
      if (!Number.isFinite(pOverZ) || pOverZ <= 0) reasons.push('invalid_pressure_over_z')
    }
    if (pressure.pressureMpa === null) reasons.push('missing_pressure')

    reasons.forEach(reason => { reasonCounts[reason] = (reasonCounts[reason] || 0) + 1 })
    const row = {
      index: index + 1,
      date: point.date,
      pseudotime: cumulative,
      pressure: pOverZ ?? 0,
      isDeleted: reasons.length > 0,
      exclusionReason: reasons.join(',') || null,
      cumulativeGas: cumulative,
      flowingPressureMpa: pressure.pressureMpa,
      pressureSource: source.pressureSource
    }
    transformedSeries.push(row)
    if (!row.isDeleted) used.push({ x: cumulative, y: pOverZ })
  })

  let regression
  try {
    regression = linearRegression(used)
  } catch (error) {
    throw new InvalidDataError(`FlowBalance regression failed for ${well.wellName} nodeType ${source.nodeType}: ${error.message}`, {
      wellName: well.wellName,
      nodeType: source.nodeType,
      rawPointCount: well.production.length,
      usedPointCount: used.length,
      exclusionReasonCounts: reasonCounts
    })
  }
  if (!(regression.slope < 0)) {
    throw new InvalidDataError('Flowing p/z must decline with cumulative production.', {
      wellName: well.wellName,
      nodeType: source.nodeType,
      slope: regression.slope,
      rSquared: regression.rSquared
    })
  }

  const ogipCumulativeUnit = -initialPOverZ / regression.slope
  if (!Number.isFinite(ogipCumulativeUnit) || ogipCumulativeUnit <= Math.max(...used.map(point => point.x))) {
    throw new InvalidDataError('Calculated OGIP must be finite and greater than produced cumulative gas.', {
      wellName: well.wellName,
      nodeType: source.nodeType,
      ogipCumulativeUnit
    })
  }

  return {
    resultId: randomUUID(),
    persisted: false,
    idKind: 'runtime',
    wellName: well.wellName,
    nodeType: source.nodeType,
    pressureSource: source.pressureSource,
    pressureKind: source.pressureKind,
    sourceUnit: source.sourceUnit,
    standardUnit: source.standardUnit,
    algorithmVersion: ALGORITHM_VERSION,
    inputSummary: {
      originalFormationPressureMpa: initialPressureMpa,
      formationTemperatureK: temperatureK,
      specificGravity: gas.specificGravity,
      hydrogenSulfide: gas.hydrogenSulfide,
      carbonDioxide: gas.carbonDioxide,
      nitrogen: Number(reservoir.nitrogen.value),
      waterGasRatioLimit,
      initialZFactor: initialZ,
      plotX: 'cumulativeGasProduction',
      plotY: 'flowingPressureMpa/z'
    },
    // The database column has no verified unit metadata. Keep OGIP in exactly the
    // same source unit as cumulative_gas_production instead of inventing a scale.
    originalGasVolume: ogipCumulativeUnit,
    originalGasVolumeUnit: 'cumulative_gas_production source unit',
    ogipCumulativeUnit,
    regression: { ...regression, transformedSeries },
    reliability: regression.rSquared >= 0.9 ? 'preview-fit-high' : regression.rSquared >= 0.7 ? 'preview-fit-medium' : 'preview-fit-low',
    diagnostics: {
      engineeringValidation: 'required',
      methodAssumptions: ['dry-gas', 'pseudo-steady-state', 'flowing-p-over-z parallel-line'],
      totalProductionRows,
      rawPointCount: well.production.length,
      sourcePointCount: used.length,
      missingPressureCount,
      usedPointCount: used.length,
      excludedPointCount: well.production.length - used.length,
      exclusionReasonCounts: reasonCounts,
      initialPOverZ,
      shiftedIntercept: initialPOverZ,
      rateCoefficientOfVariation: coefficientOfVariation(well.production.map(point => Number(point.dailyGas.value)).filter(value => value > 0))
    },
    excludedPoints: transformedSeries.filter(point => point.isDeleted)
  }
}

function coefficientOfVariation(values) {
  if (values.length < 2) return null
  const mean = values.reduce((sum, value) => sum + value, 0) / values.length
  const variance = values.reduce((sum, value) => sum + Math.pow(value - mean, 2), 0) / (values.length - 1)
  return mean > 0 ? Math.sqrt(variance) / mean : null
}

function positive(value, name) {
  const number = Number(value)
  if (!Number.isFinite(number) || number <= 0) throw new RangeError(`${name} must be positive`)
  return number
}

function fraction(value, name) {
  const number = Number(value ?? 0)
  if (!Number.isFinite(number) || number < 0 || number > 1) throw new RangeError(`${name} must be a mole fraction between 0 and 1`)
  return number
}

function optionalNonNegative(value, name) {
  if (value === undefined || value === null || value === '') return null
  const number = Number(value)
  if (!Number.isFinite(number) || number < 0) throw new RangeError(`${name} must be non-negative`)
  return number
}

module.exports = { ALGORITHM_VERSION, FlowBalanceCalculator }
