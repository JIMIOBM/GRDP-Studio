import assert from 'node:assert/strict'
import test from 'node:test'
import { createRenderer, ref } from 'vue'
import { useRunFeedback } from '../src/views/SoftwareIntegration/useRunFeedback.js'
import { buildRunNotice } from '../src/views/SoftwareIntegration/runNotice.js'

const mountFeedback = () => {
  const context = ref('version-1')
  let feedback
  const renderer = createRenderer({
    createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null
  })
  const app = renderer.createApp({ setup() { feedback = useRunFeedback([context]); return () => null } })
  app.mount({})
  return { feedback, context, unmount: () => app.unmount() }
}

test('late response cannot publish after navigating away and back to the same version', () => {
  const { feedback, context, unmount } = mountFeedback()
  const oldAction = feedback.begin()
  context.value = 'version-2'
  context.value = 'version-1'
  oldAction.error('old failure')
  assert.equal(feedback.notice.value, null)
  const newAction = feedback.begin()
  newAction.success('new response')
  oldAction.success('stale response')
  assert.deepEqual(feedback.notice.value, { type: 'success', message: 'new response' })
  unmount()
  newAction.error('after unmount')
  assert.equal(feedback.notice.value, null)
})

test('a newer action replaces the earlier feedback and blocks its completion', () => {
  const { feedback, unmount } = mountFeedback()
  const first = feedback.begin()
  first.error('one error')
  const second = feedback.begin()
  assert.equal(feedback.notice.value, null)
  first.error('late first error')
  second.error('second error')
  assert.deepEqual(feedback.notice.value, { type: 'danger', message: 'second error' })
  unmount()
})

test('refresh success never masks an invalid or partial result', () => {
  const operationNotice = { type: 'success', message: 'refreshed' }
  assert.equal(buildRunNotice({ operationNotice, networkTopologyUnavailable: true }).type, 'danger')
  assert.equal(buildRunNotice({ operationNotice, isPartial: true }).type, 'warning')
  assert.equal(buildRunNotice({ operationNotice, isNetworkPartial: true }).type, 'warning')
  assert.equal(buildRunNotice({ operationNotice, terminalGuidance: 'failure' }).message, 'failure')
  assert.equal(buildRunNotice({ operationNotice, pollingUnavailable: true }).action, 'refresh')
  assert.equal(buildRunNotice({ operationNotice, networkContractRejected: true }).action, 'history')
  assert.equal(buildRunNotice({ operationNotice }), operationNotice)
})
