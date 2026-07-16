'use strict'

const { FlowBalanceProductionPoint, FlowBalanceReservoirInput } = require('../domain/flowBalanceObjects')
const { InvalidDataError, MissingDataError } = require('../domain/errors')
const { FIELD_MAPPING, NODE_TYPES, PRESSURE_KINDS, PRESSURE_SOURCES, TABLES, UNITS } = require('../model/flowBalanceMapping')
const { assertPositiveInteger, isNonNegativeFinite, isPositiveFinite, normalizeWellNames, placeholders, toFiniteNumberOrNull } = require('./sqlUtils')

class FlowBalanceRepository {
  constructor(db) {
    if (!db || typeof db.query !== 'function') {
      throw new TypeError('FlowBalanceRepository requires a database client with query(sql, params)')
    }
    this.db = db
  }

  async loadInput({ projectId, gasReservoirId, wellNames }) {
    const scope = normalizeScope(projectId, gasReservoirId, wellNames)
    const [productionBaseRows, wellHeadTubingPressureRows, calculatedBottomHolePressureRows, gasProperties, otherData] = await Promise.all([
      this.findProductionBaseSeries(scope),
      this.findWellHeadTubingPressureSeries(scope),
      this.findCalculatedBottomHolePressureSeries(scope),
      this.findGasProperties(scope),
      this.findOtherData(scope)
    ])
    const productionRows = mergePressureSeries(productionBaseRows, wellHeadTubingPressureRows, calculatedBottomHolePressureRows)

    const productionByWell = groupBy(productionRows, 'wellName')
    const gasPropertyByWell = indexBy(gasProperties, 'wellName')
    const otherDataByWell = indexBy(otherData, 'wellName')

    const wells = scope.wellNames.map(wellName => {
      const production = productionByWell.get(wellName) || []
      if (production.length === 0) {
        throw new MissingDataError('Missing FlowBalance production rows.', missingDetails(scope, wellName, TABLES.production, [
          FIELD_MAPPING.productionDate.field,
          FIELD_MAPPING.dailyGas.field,
          FIELD_MAPPING.cumulativeGas.field
        ]))
      }

      validateProductionSeries(scope, wellName, production)

      const gasProperty = gasPropertyByWell.get(wellName)
      if (!gasProperty) {
        throw new MissingDataError('Missing FlowBalance gas property row.', missingDetails(scope, wellName, TABLES.gasProperty, [
          FIELD_MAPPING.specificGravity.field,
          FIELD_MAPPING.hydrogenSulfide.field,
          FIELD_MAPPING.carbonDioxide.field,
          FIELD_MAPPING.nitrogen.field
        ]))
      }

      const other = otherDataByWell.get(wellName)
      if (!other) {
        throw new MissingDataError('Missing FlowBalance other-data row.', missingDetails(scope, wellName, TABLES.otherData, [
          FIELD_MAPPING.originalFormationPressure.field,
          FIELD_MAPPING.formationTemperature.field
        ]))
      }

      validatePositiveField(scope, wellName, TABLES.otherData, FIELD_MAPPING.originalFormationPressure.field, other.originalFormationPressure)
      validatePositiveField(scope, wellName, TABLES.otherData, FIELD_MAPPING.formationTemperature.field, other.formationTemperature)
      validatePositiveField(scope, wellName, TABLES.gasProperty, FIELD_MAPPING.specificGravity.field, gasProperty.specificGravity)

      return {
        wellName,
        reservoirInput: new FlowBalanceReservoirInput({
          projectId: scope.projectId,
          gasReservoirId: scope.gasReservoirId,
          wellName,
          otherData: other,
          gasProperty
        }),
        production,
        nodeSources: this.buildNodeSources(scope, wellName, production)
      }
    })

    return {
      projectId: scope.projectId,
      gasReservoirId: scope.gasReservoirId,
      wellNames: scope.wellNames,
      wells
    }
  }

  async findProductionBaseSeries(scope) {
    const rows = await this.db.query(
      `SELECT
        id,
        project_id AS projectId,
        project_gas_reservoir_id AS gasReservoirId,
        well_name AS wellName,
        date,
        daily_gas_production AS dailyGas,
        cumulative_gas_production AS cumulativeGas,
        water_gas_ratio AS waterGasRatio,
        well_head_casing_pressure AS wellHeadCasingPressure,
        well_head_tubing_temperature AS wellHeadTubingTemperature,
        well_head_casing_temperature AS wellHeadCasingTemperature
      FROM ${TABLES.production}
      WHERE project_id = ?
        AND project_gas_reservoir_id = ?
        AND well_name IN (${placeholders(scope.wellNames)})
      ORDER BY well_name ASC, date ASC`,
      [scope.projectId, scope.gasReservoirId, ...scope.wellNames]
    )

    return rows.map(row => mapNumericFields(row, [
      'id',
      'projectId',
      'gasReservoirId',
      'dailyGas',
      'cumulativeGas',
      'waterGasRatio',
      'wellHeadCasingPressure',
      'wellHeadTubingTemperature',
      'wellHeadCasingTemperature'
    ]))
  }

  async findWellHeadTubingPressureSeries(scope) {
    const rows = await this.db.query(
      `SELECT
        id,
        project_id AS projectId,
        project_gas_reservoir_id AS gasReservoirId,
        well_name AS wellName,
        date,
        well_head_tubing_pressure AS wellHeadTubingPressure
      FROM ${TABLES.production}
      WHERE project_id = ?
        AND project_gas_reservoir_id = ?
        AND well_name IN (${placeholders(scope.wellNames)})
      ORDER BY well_name ASC, date ASC`,
      [scope.projectId, scope.gasReservoirId, ...scope.wellNames]
    )

    return rows.map(row => mapNumericFields(row, [
      'id',
      'projectId',
      'gasReservoirId',
      'wellHeadTubingPressure'
    ]))
  }

  async findCalculatedBottomHolePressureSeries(scope) {
    const rows = await this.db.query(
      `SELECT
        id,
        project_id AS projectId,
        project_gas_reservoir_id AS gasReservoirId,
        well_name AS wellName,
        date,
        calculated_bottom_hole_pressure AS calculatedBottomHolePressure
      FROM ${TABLES.production}
      WHERE project_id = ?
        AND project_gas_reservoir_id = ?
        AND well_name IN (${placeholders(scope.wellNames)})
      ORDER BY well_name ASC, date ASC`,
      [scope.projectId, scope.gasReservoirId, ...scope.wellNames]
    )

    return rows.map(row => mapNumericFields(row, [
      'id',
      'projectId',
      'gasReservoirId',
      'calculatedBottomHolePressure'
    ]))
  }

  async findGasProperties(scope) {
    const rows = await this.db.query(
      `SELECT
        id,
        project_id AS projectId,
        project_gas_reservoir_id AS gasReservoirId,
        well_name AS wellName,
        gas_type AS gasType,
        specific_gravity AS specificGravity,
        hydrogen_sulfide AS hydrogenSulfide,
        carbon_dioxide AS carbonDioxide,
        nitrogen
      FROM ${TABLES.gasProperty}
      WHERE project_id = ?
        AND project_gas_reservoir_id = ?
        AND well_name IN (${placeholders(scope.wellNames)})
      ORDER BY well_name ASC`,
      [scope.projectId, scope.gasReservoirId, ...scope.wellNames]
    )
    return rows.map(row => mapNumericFields(row, ['id', 'projectId', 'gasReservoirId', 'specificGravity', 'hydrogenSulfide', 'carbonDioxide', 'nitrogen']))
  }

  async findOtherData(scope) {
    const rows = await this.db.query(
      `SELECT
        id,
        project_id AS projectId,
        project_gas_reservoir_id AS gasReservoirId,
        well_name AS wellName,
        original_formation_pressure AS originalFormationPressure,
        formation_temperature AS formationTemperature
      FROM ${TABLES.otherData}
      WHERE project_id = ?
        AND project_gas_reservoir_id = ?
        AND well_name IN (${placeholders(scope.wellNames)})
      ORDER BY well_name ASC`,
      [scope.projectId, scope.gasReservoirId, ...scope.wellNames]
    )
    return rows.map(row => mapNumericFields(row, ['id', 'projectId', 'gasReservoirId', 'originalFormationPressure', 'formationTemperature']))
  }

  buildNodeSources(scope, wellName, production) {
    const wellHeadRows = production.filter(row => isPositiveFinite(row.wellHeadTubingPressure.rawPressurePa))
    const calculatedRows = production.filter(row => isPositiveFinite(row.calculatedBottomHolePressure.rawPressurePa))
    const nodeSources = []

    if (wellHeadRows.length > 0) {
      nodeSources.push({
        nodeType: NODE_TYPES.WELLHEAD_FLOWING_PRESSURE,
        pressureSource: PRESSURE_SOURCES.WELLHEAD_TUBING,
        pressureKind: PRESSURE_KINDS.WELLHEAD,
        sourceUnit: UNITS.pressureSource,
        standardUnit: UNITS.pressureStandard,
        sourceTable: TABLES.production,
        sourceField: FIELD_MAPPING.wellHeadTubingPressure.field,
        sampleCount: wellHeadRows.length,
        firstDate: wellHeadRows[0].date,
        lastDate: wellHeadRows[wellHeadRows.length - 1].date
      })
    }

    if (calculatedRows.length > 0) {
      nodeSources.push({
        nodeType: NODE_TYPES.BOTTOMHOLE_FLOWING_PRESSURE,
        pressureSource: PRESSURE_SOURCES.CALCULATED_BOTTOM_HOLE,
        pressureKind: PRESSURE_KINDS.CALCULATED,
        sourceUnit: UNITS.pressureSource,
        standardUnit: UNITS.pressureStandard,
        sourceTable: TABLES.production,
        sourceField: FIELD_MAPPING.calculatedBottomHolePressure.field,
        sampleCount: calculatedRows.length,
        firstDate: calculatedRows[0].date,
        lastDate: calculatedRows[calculatedRows.length - 1].date
      })
    }

    if (nodeSources.length === 0) {
      throw new MissingDataError('Missing FlowBalance pressure data for both node types.', {
        projectId: scope.projectId,
        gasReservoirId: scope.gasReservoirId,
        wellName,
        missingFields: [
          FIELD_MAPPING.wellHeadTubingPressure.field,
          FIELD_MAPPING.calculatedBottomHolePressure.field
        ],
        sourceTable: TABLES.production
      })
    }

    return nodeSources
  }
}

function normalizeScope(projectId, gasReservoirId, wellNames) {
  assertPositiveInteger(projectId, 'projectId')
  assertPositiveInteger(gasReservoirId, 'gasReservoirId')
  return {
    projectId,
    gasReservoirId,
    wellNames: normalizeWellNames(wellNames)
  }
}

function validateProductionSeries(scope, wellName, rows) {
  let previousTime = null
  rows.forEach((row, index) => {
    if (!row.date || Number.isNaN(new Date(row.date).getTime())) {
      throw new InvalidDataError('Invalid production date.', dataDetails(scope, wellName, TABLES.production, FIELD_MAPPING.productionDate.field, index))
    }
    const time = new Date(row.date).getTime()
    if (previousTime !== null && time < previousTime) {
      throw new InvalidDataError('Production series is not sorted by date.', dataDetails(scope, wellName, TABLES.production, FIELD_MAPPING.productionDate.field, index))
    }
    previousTime = time
    validateNonNegativeField(scope, wellName, TABLES.production, FIELD_MAPPING.dailyGas.field, row.dailyGas.value, index)
    validatePositiveField(scope, wellName, TABLES.production, FIELD_MAPPING.cumulativeGas.field, row.cumulativeGas.value, index)
    validateOptionalPressureField(scope, wellName, TABLES.production, FIELD_MAPPING.wellHeadTubingPressure.field, row.wellHeadTubingPressure.rawPressurePa, index)
    validateOptionalPressureField(scope, wellName, TABLES.production, FIELD_MAPPING.calculatedBottomHolePressure.field, row.calculatedBottomHolePressure.rawPressurePa, index)
  })
}

function validateNonNegativeField(scope, wellName, sourceTable, field, value, rowIndex = undefined) {
  if (!isNonNegativeFinite(value)) {
    throw new InvalidDataError('Required FlowBalance numeric field must be a non-negative finite number.', {
      ...dataDetails(scope, wellName, sourceTable, field, rowIndex),
      value
    })
  }
}

function validatePositiveField(scope, wellName, sourceTable, field, value, rowIndex = undefined) {
  if (!isPositiveFinite(value)) {
    throw new InvalidDataError('Required FlowBalance numeric field must be a positive finite number.', {
      ...dataDetails(scope, wellName, sourceTable, field, rowIndex),
      value
    })
  }
}

function validateOptionalPressureField(scope, wellName, sourceTable, field, value, rowIndex) {
  if (value === null || value === undefined) return
  if (!isPositiveFinite(value)) {
    throw new InvalidDataError('FlowBalance pressure field must be a positive finite number when present.', {
      ...dataDetails(scope, wellName, sourceTable, field, rowIndex),
      value
    })
  }
}

function dataDetails(scope, wellName, sourceTable, field, rowIndex) {
  return {
    projectId: scope.projectId,
    gasReservoirId: scope.gasReservoirId,
    wellName,
    sourceTable,
    missingField: field,
    rowIndex
  }
}

function missingDetails(scope, wellName, sourceTable, fields) {
  return {
    projectId: scope.projectId,
    gasReservoirId: scope.gasReservoirId,
    wellName,
    sourceTable,
    missingFields: fields
  }
}

function groupBy(rows, key) {
  const grouped = new Map()
  rows.forEach(row => {
    if (!grouped.has(row[key])) grouped.set(row[key], [])
    grouped.get(row[key]).push(row)
  })
  return grouped
}

function indexBy(rows, key) {
  const indexed = new Map()
  rows.forEach(row => indexed.set(row[key], row))
  return indexed
}

function mapNumericFields(row, fields) {
  const mapped = { ...row }
  fields.forEach(field => {
    mapped[field] = toFiniteNumberOrNull(mapped[field])
  })
  return mapped
}

function mergePressureSeries(productionRows, wellHeadRows, calculatedRows) {
  const wellHeadById = indexBy(wellHeadRows, 'id')
  const calculatedById = indexBy(calculatedRows, 'id')
  return productionRows.map(row => new FlowBalanceProductionPoint({
    ...row,
    wellHeadTubingPressure: wellHeadById.get(row.id)?.wellHeadTubingPressure ?? null,
    calculatedBottomHolePressure: calculatedById.get(row.id)?.calculatedBottomHolePressure ?? null
  }))
}

module.exports = {
  FlowBalanceRepository
}