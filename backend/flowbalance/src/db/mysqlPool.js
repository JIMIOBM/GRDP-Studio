'use strict'

const mysql = require('mysql2/promise')
const { readDatabaseConfig } = require('../config/databaseConfig')

function createMysqlPool(config = readDatabaseConfig()) {
  return mysql.createPool(config)
}

class MysqlPoolClient {
  constructor(pool) {
    if (!pool || typeof pool.query !== 'function') {
      throw new TypeError('MysqlPoolClient requires a mysql2/promise pool')
    }
    this.pool = pool
  }

  async query(sql, params = []) {
    const [rows] = await this.pool.query(sql, params)
    return rows
  }

  async execute(sql, params = []) {
    const [result] = await this.pool.execute(sql, params)
    return result
  }

  async transaction(callback) {
    const connection = await this.pool.getConnection()
    try {
      await connection.beginTransaction()
      const client = new MysqlConnectionClient(connection)
      const result = await callback(client)
      await connection.commit()
      return result
    } catch (error) {
      await connection.rollback()
      throw error
    } finally {
      connection.release()
    }
  }

  async close() {
    await this.pool.end()
  }
}

class MysqlConnectionClient {
  constructor(connection) {
    this.connection = connection
  }

  async query(sql, params = []) {
    const [rows] = await this.connection.query(sql, params)
    return rows
  }

  async execute(sql, params = []) {
    const [result] = await this.connection.execute(sql, params)
    return result
  }
}

module.exports = {
  MysqlPoolClient,
  createMysqlPool
}
