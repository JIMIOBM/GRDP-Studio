'use strict'

const { createFlowBalanceRuntime } = require('./index')
const { createHttpServer } = require('./http/server')

const port = Number(process.env.FLOWBALANCE_PORT || 8891)
const host = process.env.FLOWBALANCE_HOST || '0.0.0.0'

let runtime
let server
let shuttingDown = false

try {
  runtime = createFlowBalanceRuntime()
  runtime.pool.on('error', (err) => {
    console.error('FlowBalance pool error:', err.message)
  })
  server = createHttpServer(runtime, { allowedOrigin: process.env.FLOWBALANCE_ALLOWED_ORIGIN || '*' })
} catch (error) {
  console.error('FlowBalance startup failed:', error.message)
  process.exit(1)
}

try {
  server.listen(port, host, () => {
    console.log(`FlowBalance development server listening on http://${host}:${port}`)
  })
} catch (error) {
  console.error('FlowBalance listen failed:', error.message)
  process.exit(1)
}

async function shutdown() {
  if (shuttingDown) return
  shuttingDown = true
  if (server) {
    server.close(async () => {
      if (runtime) await runtime.close()
      process.exit(0)
    })
  } else {
    process.exit(1)
  }
}

process.on('SIGINT', shutdown)
process.on('SIGTERM', shutdown)

process.on('uncaughtException', (error) => {
  console.error('FlowBalance uncaught exception:', error.message)
  if (!shuttingDown) shutdown()
})

process.on('unhandledRejection', (reason) => {
  console.error('FlowBalance unhandled rejection:', reason)
  if (!shuttingDown) shutdown()
})