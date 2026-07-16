'use strict'

class FlowBalanceDomainError extends Error {
  constructor(code, message, details = {}) {
    super(message)
    this.name = 'FlowBalanceDomainError'
    this.code = code
    this.details = details
  }
}

class MissingDataError extends FlowBalanceDomainError {
  constructor(message, details) {
    super('FLOW_BALANCE_MISSING_DATA', message, details)
  }
}

class InvalidDataError extends FlowBalanceDomainError {
  constructor(message, details) {
    super('FLOW_BALANCE_INVALID_DATA', message, details)
  }
}

module.exports = {
  FlowBalanceDomainError,
  InvalidDataError,
  MissingDataError
}