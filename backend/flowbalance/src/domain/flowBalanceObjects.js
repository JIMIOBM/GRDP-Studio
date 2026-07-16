'use strict'

const { FIELD_MAPPING, NODE_TYPES, PRESSURE_CONVERSION, PRESSURE_KINDS, PRESSURE_SOURCES, TABLES, UNITS } = require('../model/flowBalanceMapping')

class FlowBalanceProductionPoint {
  constructor(row) {
    this.id = row.id
    this.projectId = row.projectId
    this.gasReservoirId = row.gasReservoirId
    this.wellName = row.wellName
    this.date = row.date
    this.dailyGas = withUnit(row.dailyGas, FIELD_MAPPING.dailyGas.unit)
    this.cumulativeGas = withUnit(row.cumulativeGas, FIELD_MAPPING.cumulativeGas.unit)
    this.waterGasRatio = withUnit(row.waterGasRatio, FIELD_MAPPING.waterGasRatio.unit)
    this.wellHeadTubingPressure = withPressure(row.wellHeadTubingPressure, PRESSURE_SOURCES.WELLHEAD_TUBING, PRESSURE_KINDS.WELLHEAD, NODE_TYPES.WELLHEAD_FLOWING_PRESSURE)
    this.calculatedBottomHolePressure = withPressure(row.calculatedBottomHolePressure, PRESSURE_SOURCES.CALCULATED_BOTTOM_HOLE, PRESSURE_KINDS.CALCULATED, NODE_TYPES.BOTTOMHOLE_FLOWING_PRESSURE)
    this.wellHeadCasingPressure = withUnit(row.wellHeadCasingPressure, FIELD_MAPPING.wellHeadCasingPressure.unit)
    this.wellHeadTubingTemperature = withUnit(row.wellHeadTubingTemperature, FIELD_MAPPING.wellHeadTubingTemperature.unit)
    this.wellHeadCasingTemperature = withUnit(row.wellHeadCasingTemperature, FIELD_MAPPING.wellHeadCasingTemperature.unit)
  }
}

class FlowBalanceReservoirInput {
  constructor({ projectId, gasReservoirId, wellName, otherData, gasProperty }) {
    this.projectId = projectId
    this.gasReservoirId = gasReservoirId
    this.wellName = wellName
    this.originalFormationPressure = withSource(otherData?.originalFormationPressure, FIELD_MAPPING.originalFormationPressure)
    this.formationTemperature = withSource(otherData?.formationTemperature, FIELD_MAPPING.formationTemperature)
    this.gasType = withSource(gasProperty?.gasType, FIELD_MAPPING.gasType)
    this.specificGravity = withSource(gasProperty?.specificGravity, FIELD_MAPPING.specificGravity)
    this.hydrogenSulfide = withSource(gasProperty?.hydrogenSulfide, FIELD_MAPPING.hydrogenSulfide)
    this.carbonDioxide = withSource(gasProperty?.carbonDioxide, FIELD_MAPPING.carbonDioxide)
    this.nitrogen = withSource(gasProperty?.nitrogen, FIELD_MAPPING.nitrogen)
  }
}

class FlowBalanceNodeSource {
  constructor({ nodeType, pressureSource, sourceTable, sourceField, rows }) {
    this.nodeType = nodeType
    this.pressureSource = pressureSource
    this.sourceTable = sourceTable
    this.sourceField = sourceField
    this.sampleCount = rows.length
    this.firstDate = rows[0]?.date || null
    this.lastDate = rows[rows.length - 1]?.date || null
  }
}

function withUnit(value, unit) {
  return { value, unit }
}

function withPressure(value, pressureSource, pressureKind, nodeType) {
  const rawPressurePa = (value === null || value === undefined || value === '')
    ? null
    : (Number.isFinite(Number(value)) ? Number(value) : null)
  const pressureMpa = rawPressurePa === null ? null : rawPressurePa / PRESSURE_CONVERSION.pascalsPerMpa
  return {
    value: pressureMpa,
    rawPressurePa,
    pressureMpa,
    unit: UNITS.pressureStandard,
    sourceUnit: UNITS.pressureSource,
    standardUnit: UNITS.pressureStandard,
    conversion: PRESSURE_CONVERSION.expression,
    pressureSource,
    pressureKind,
    nodeType
  }
}

function withSource(value, mapping) {
  return {
    value,
    unit: mapping.unit,
    sourceTable: mapping.table,
    sourceField: mapping.field
  }
}

module.exports = {
  FlowBalanceNodeSource,
  FlowBalanceProductionPoint,
  FlowBalanceReservoirInput,
  TABLES
}