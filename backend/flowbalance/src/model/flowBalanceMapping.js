'use strict'

const NODE_TYPES = Object.freeze({
  WELLHEAD_FLOWING_PRESSURE: 57,
  BOTTOMHOLE_FLOWING_PRESSURE: 58
})

const UNITS = Object.freeze({
  pressure: 'MPa',
  pressureSource: 'Pa',
  pressureStandard: 'MPa',
  temperature: 'TODO',
  gasProduction: 'TODO',
  ratio: 'TODO',
  composition: 'TODO',
  dimensionless: 'TODO'
})

const PRESSURE_CONVERSION = Object.freeze({
  pascalsPerMpa: 1000000,
  expression: 'value / 1000000'
})

const PRESSURE_SOURCES = Object.freeze({
  WELLHEAD_TUBING: 'well_head_tubing_pressure',
  CALCULATED_BOTTOM_HOLE: 'calculated_bottom_hole_pressure'
})

const PRESSURE_KINDS = Object.freeze({
  WELLHEAD: 'wellhead',
  CALCULATED: 'calculated'
})

const TABLES = Object.freeze({
  production: 'project_production_data',
  recoveredStaticPressure: 'project_achieve_the_recover_static_pressure_of_gas_well',
  reservoir: 'project_gas_reservoir',
  gasProperty: 'project_gas_property',
  otherData: 'project_other_data'
})

const FIELD_MAPPING = Object.freeze({
  projectId: { table: TABLES.production, field: 'project_id', type: 'bigint', unit: 'n/a' },
  gasReservoirId: { table: TABLES.production, field: 'project_gas_reservoir_id', type: 'bigint', unit: 'n/a' },
  wellName: { table: TABLES.production, field: 'well_name', type: 'varchar(255)', unit: 'n/a' },
  productionDate: { table: TABLES.production, field: 'date', type: 'timestamp', unit: 'n/a' },
  dailyGas: { table: TABLES.production, field: 'daily_gas_production', type: 'double', unit: UNITS.gasProduction },
  cumulativeGas: { table: TABLES.production, field: 'cumulative_gas_production', type: 'double', unit: UNITS.gasProduction },
  waterGasRatio: { table: TABLES.production, field: 'water_gas_ratio', type: 'double', unit: UNITS.ratio },
  wellHeadTubingPressure: { table: TABLES.production, field: PRESSURE_SOURCES.WELLHEAD_TUBING, type: 'double', unit: UNITS.pressure, nodeType: NODE_TYPES.WELLHEAD_FLOWING_PRESSURE },
  calculatedBottomHolePressure: { table: TABLES.production, field: PRESSURE_SOURCES.CALCULATED_BOTTOM_HOLE, type: 'double', unit: UNITS.pressure, nodeType: NODE_TYPES.BOTTOMHOLE_FLOWING_PRESSURE },
  wellHeadCasingPressure: { table: TABLES.production, field: 'well_head_casing_pressure', type: 'double', unit: UNITS.pressure },
  wellHeadTubingTemperature: { table: TABLES.production, field: 'well_head_tubing_temperature', type: 'double', unit: UNITS.temperature },
  wellHeadCasingTemperature: { table: TABLES.production, field: 'well_head_casing_temperature', type: 'double', unit: UNITS.temperature },
  originalFormationPressure: { table: TABLES.otherData, field: 'original_formation_pressure', type: 'double', unit: UNITS.pressure },
  formationTemperature: { table: TABLES.otherData, field: 'formation_temperature', type: 'double', unit: UNITS.temperature },
  gasType: { table: TABLES.gasProperty, field: 'gas_type', type: 'varchar(255)', unit: 'n/a' },
  specificGravity: { table: TABLES.gasProperty, field: 'specific_gravity', type: 'double', unit: UNITS.dimensionless },
  hydrogenSulfide: { table: TABLES.gasProperty, field: 'hydrogen_sulfide', type: 'double', unit: UNITS.composition },
  carbonDioxide: { table: TABLES.gasProperty, field: 'carbon_dioxide', type: 'double', unit: UNITS.composition },
  nitrogen: { table: TABLES.gasProperty, field: 'nitrogen', type: 'double', unit: UNITS.composition }
})

module.exports = {
  FIELD_MAPPING,
  NODE_TYPES,
  PRESSURE_CONVERSION,
  PRESSURE_KINDS,
  PRESSURE_SOURCES,
  TABLES,
  UNITS
}