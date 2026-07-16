'use strict'

function readDatabaseConfig(env = process.env) {
  return {
    host: required(env.FLOWBALANCE_DB_HOST, 'FLOWBALANCE_DB_HOST'),
    port: numberOrDefault(env.FLOWBALANCE_DB_PORT, 3306),
    user: required(env.FLOWBALANCE_DB_USER, 'FLOWBALANCE_DB_USER'),
    password: required(env.FLOWBALANCE_DB_PASSWORD, 'FLOWBALANCE_DB_PASSWORD'),
    database: required(env.FLOWBALANCE_DB_NAME, 'FLOWBALANCE_DB_NAME'),
    waitForConnections: true,
    connectionLimit: numberOrDefault(env.FLOWBALANCE_DB_CONNECTION_LIMIT, 5),
    maxIdle: numberOrDefault(env.FLOWBALANCE_DB_MAX_IDLE, 5),
    idleTimeout: numberOrDefault(env.FLOWBALANCE_DB_IDLE_TIMEOUT_MS, 60000),
    connectTimeout: numberOrDefault(env.FLOWBALANCE_DB_CONNECT_TIMEOUT_MS, 10000),
    timezone: env.FLOWBALANCE_DB_TIMEZONE || 'Z',
    decimalNumbers: false,
    supportBigNumbers: true,
    bigNumberStrings: true,
    namedPlaceholders: false
  }
}

function required(value, name) {
  if (value === undefined || value === null || value === '') {
    throw new Error(`${name} is required`)
  }
  return value
}

function numberOrDefault(value, defaultValue) {
  if (value === undefined || value === null || value === '') return defaultValue
  const number = Number(value)
  if (!Number.isInteger(number) || number <= 0) throw new Error(`${value} is not a positive integer`)
  return number
}

module.exports = {
  readDatabaseConfig
}
