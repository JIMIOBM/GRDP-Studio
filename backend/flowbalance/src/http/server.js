'use strict'

const http = require('node:http')
const { URL } = require('node:url')
const { FlowBalanceDomainError } = require('../domain/errors')

function createHttpServer(runtime, options = {}) {
  const allowedOrigin = options.allowedOrigin || '*'
  return http.createServer(async (request, response) => {
    setCors(response, allowedOrigin)
    if (request.method === 'OPTIONS') return send(response, 204)
    const url = new URL(request.url, 'http://localhost')
    if (request.method === 'GET' && url.pathname === '/flowbalance/health') {
      return sendJson(response, 200, { status: 'ok', service: 'grdp-flowbalance', persistence: false })
    }
    if (request.method === 'POST' && url.pathname === '/flowbalance/calc') {
      try {
        const body = await readJson(request)
        const calculationRequest = normalizeRequest(body)
        const result = await runtime.service.calculate(calculationRequest)
        return sendJson(response, 200, result)
      } catch (error) {
        const status = error instanceof FlowBalanceDomainError ? 422 : error instanceof SyntaxError || error instanceof TypeError || error instanceof RangeError ? 400 : 500
        return sendJson(response, status, {
          code: error.code || (status === 500 ? 'FLOW_BALANCE_INTERNAL_ERROR' : 'FLOW_BALANCE_INVALID_REQUEST'),
          message: status === 500 ? 'FlowBalance calculation failed.' : error.message,
          details: error.details || {}
        })
      }
    }
    sendJson(response, 404, { code: 'NOT_FOUND', message: 'Route not found' })
  })
}

function normalizeRequest(body) {
  if (!body || typeof body !== 'object') throw new TypeError('JSON request body is required')
  for (const key of ['projectId', 'gasReservoirId']) {
    if (!Number.isInteger(Number(body[key])) || Number(body[key]) <= 0) throw new TypeError(`${key} must be a positive integer`)
  }
  if (!Array.isArray(body.wellNames) || body.wellNames.length === 0) throw new TypeError('wellNames must contain at least one well')
  return {
    ...body,
    projectId: Number(body.projectId),
    gasReservoirId: Number(body.gasReservoirId)
  }
}

function readJson(request) {
  return new Promise((resolve, reject) => {
    const chunks = []
    let size = 0
    request.on('data', chunk => {
      size += chunk.length
      if (size > 1024 * 1024) {
        reject(new RangeError('Request body exceeds 1 MiB'))
        request.destroy()
      } else chunks.push(chunk)
    })
    request.on('end', () => {
      try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}')) } catch (error) { reject(error) }
    })
    request.on('error', reject)
  })
}

function setCors(response, origin) {
  response.setHeader('Access-Control-Allow-Origin', origin)
  response.setHeader('Access-Control-Allow-Headers', 'Content-Type')
  response.setHeader('Access-Control-Allow-Methods', 'GET,POST,OPTIONS')
}

function sendJson(response, status, body) {
  const payload = JSON.stringify(body)
  response.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(payload),
    'Cache-Control': 'no-store'
  })
  response.end(payload)
}

function send(response, status) {
  response.writeHead(status)
  response.end()
}

module.exports = { createHttpServer }