'use strict'

const { mapFlowBalanceCalculationToDynamicResult } = require('./flowBalanceResultMapper')

class FlowBalanceService {
  constructor(repository, calculator) {
    this.repository = repository
    this.calculator = calculator
  }

  async prepareCalculationInput(request) {
    return this.repository.loadInput(request)
  }

  mapCalculationResultForFlowBalanceContent(calculation) {
    return mapFlowBalanceCalculationToDynamicResult(calculation)
  }

  async getOfficialStatuses(request) {
    return {
      persisted: false,
      projectId: request.projectId,
      gasReservoirId: request.gasReservoirId,
      statuses: await this.repository.loadOfficialFlowBalanceStatuses(request)
    }
  }

  async calculate(request) {
    if (!this.calculator || typeof this.calculator.calculate !== 'function') {
      throw new TypeError('FlowBalanceService requires an in-memory calculator')
    }
    const preparedInput = await this.repository.loadInput(request)
    const run = this.calculator.calculate(preparedInput, request)
    const mappedNodes = run.calculations.map(calculation => this.mapCalculationResultForFlowBalanceContent(calculation))

    return {
      runId: run.runId,
      persisted: false,
      idKind: 'runtime',
      persistedResultId: null,
      projectId: run.projectId,
      gasReservoirId: run.gasReservoirId,
      nodes: mappedNodes,
      errors: Array.isArray(run.errors) ? run.errors : []
    }
  }
}

module.exports = {
  FlowBalanceService,
  mapFlowBalanceCalculationToDynamicResult
}
