import assert from 'node:assert/strict'
import test from 'node:test'
import { AxiosError } from 'axios'
import { createSoftwareIntegrationClient } from '../src/api/softwareIntegrationClient.js'

const response = (body, status = 200) => async config => ({ data: body, status, statusText: '', headers: {}, config })

test('accepted business envelopes and binary ranges retain their payloads', async () => {
  const client = createSoftwareIntegrationClient({ baseURL: '/api' })
  for (const code of [0, 200, 201, 202]) {
    const body = { code, data: { id: 5 } }
    assert.equal(await client.get('/runs', { adapter: response(body) }), body)
  }
  const bytes = new Blob([new Uint8Array([0, 1, 255])])
  assert.equal(await client.get('/range', { adapter: response(bytes, 206) }), bytes)
})

test('HTTP 200 business failures and HTTP errors both reject for the caller to present', async () => {
  const client = createSoftwareIntegrationClient({ baseURL: '/api' })
  const failure = { code: 409, msg: 'version conflict', data: null }
  await assert.rejects(client.post('/validate', {}, { adapter: response(failure) }), error => error === failure)
  await assert.rejects(client.post('/validate', {}, { adapter: async config => {
    throw new AxiosError('Conflict', 'ERR_BAD_REQUEST', config, null, { status: 409, data: failure })
  } }), error => error === failure)
  const timeout = new AxiosError('timeout', 'ECONNABORTED')
  await assert.rejects(client.get('/runs', { adapter: async () => { throw timeout } }), error => error === timeout)
})

test('concurrent unauthorized failures keep authentication invalidation without repeated callbacks', async () => {
  let invalidations = 0
  const client = createSoftwareIntegrationClient({ baseURL: '/api', onUnauthorized: () => { invalidations += 1 } })
  const adapter = response({ code: 401, msg: 'expired' })
  const settled = await Promise.allSettled([client.get('/one', { adapter }), client.get('/two', { adapter })])
  assert.ok(settled.every(result => result.status === 'rejected'))
  assert.equal(invalidations, 1)
  const otherClient = createSoftwareIntegrationClient({ baseURL: '/api', onUnauthorized: () => { invalidations += 1 } })
  await assert.rejects(otherClient.get('/runs', { adapter: async config => {
    throw new AxiosError('Unauthorized', 'ERR_BAD_REQUEST', config, null, { status: 401, data: { msg: 'expired' } })
  } }))
  assert.equal(invalidations, 2)
})

test('a later authenticated session can expire again on the same client', async () => {
  const original = globalThis.localStorage
  let account = JSON.stringify({ token: 'first-session' })
  globalThis.localStorage = { getItem: () => account }
  try {
    let invalidations = 0
    const client = createSoftwareIntegrationClient({ baseURL: '/api', onUnauthorized: () => { invalidations += 1; account = null } })
    const adapter = response({ code: 401, msg: 'expired' })
    await assert.rejects(client.get('/first', { adapter }))
    await assert.rejects(client.get('/already-expired', { adapter }))
    assert.equal(invalidations, 1)
    account = JSON.stringify({ token: 'second-session' })
    await assert.rejects(client.get('/second', { adapter }))
    assert.equal(invalidations, 2)
  } finally {
    if (original === undefined) delete globalThis.localStorage
    else globalThis.localStorage = original
  }
})
