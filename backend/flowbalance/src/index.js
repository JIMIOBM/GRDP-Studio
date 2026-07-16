'use strict'

const { readDatabaseConfig } = require('./config/databaseConfig')
const { createMysqlPool, MysqlPoolClient } = require('./db/mysqlPool')
const { FlowBalanceRepository } = require('./repository/flowBalanceRepository')
const { FlowBalanceService, mapFlowBalanceCalculationToDynamicResult } = require('./service/flowBalanceService')
const { FlowBalanceCalculator } = require('./algorithm/flowingMaterialBalanceCalculator')
const { calculateZFactor } = require('./algorithm/gasDeviationFactor')
const errors = require('./domain/errors')
const mapping = require('./model/flowBalanceMapping')

function createFlowBalanceRuntime(config = readDatabaseConfig()) {
  const pool = createMysqlPool(config)
  const db = new MysqlPoolClient(pool)
  const repository = new FlowBalanceRepository(db)
  const calculator = new FlowBalanceCalculator()
  const service = new FlowBalanceService(repository, calculator)
  return {
    pool,
    db,
    repository,
    calculator,
    service,
    close: () => db.close()
  }
}

module.exports = {
  createFlowBalanceRuntime,
  createMysqlPool,
  FlowBalanceRepository,
  FlowBalanceCalculator,
  FlowBalanceService,
  MysqlPoolClient,
  mapFlowBalanceCalculationToDynamicResult,
  calculateZFactor,
  readDatabaseConfig,
  ...errors,
  ...mapping
}
