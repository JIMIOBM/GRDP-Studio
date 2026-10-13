import test from 'node:test'
import assert from 'node:assert/strict'
import { buildEclipseMultiDateEvents, validateEclipseMultiDateEvents, validateEclipseWellEvents } from '../src/views/SoftwareIntegration/eclipseWellEvents.js'

const events = [{ date: '1970-01-15', status: 'SHUT' }, { date: '1970-02-01', status: 'OPEN' }, { date: '1970-03-01', status: 'SHUT' }]
test('multi-date plan retains every well and event without altering old contract', () => {
  const { parameters } = buildEclipseMultiDateEvents({ baselineRunId: 74, wells: ['G1', 'G2'], events, knownWells: ['G1', 'G2'], knownDates: events.map(e => e.date) })
  assert.deepEqual(parameters.events, events)
  assert.deepEqual(parameters.wells, ['G1', 'G2'])
  assert.equal(validateEclipseMultiDateEvents(parameters), true)
  assert.equal(validateEclipseWellEvents({ ...parameters, schemaVersion: 'eclipse-schedule-parameters/6' }), false)
  assert.equal(validateEclipseMultiDateEvents({ ...parameters, wells: ['G1'] }), true)
  assert.equal(validateEclipseMultiDateEvents({ ...parameters, events: [...events].reverse() }), false)
  assert.equal(validateEclipseMultiDateEvents({ ...parameters, events: [...events, events[2]] }), false)
  assert.equal(validateEclipseMultiDateEvents({ ...parameters, events: Array.from({ length: 25 }, (_, i) => ({ date: `1970-01-${String(i + 1).padStart(2, '0')}`, status: 'OPEN' })) }), false)
  assert.equal(validateEclipseMultiDateEvents(parameters, ['G1'], events.map(e => e.date)), false)
  assert.equal(validateEclipseMultiDateEvents(parameters, ['G1', 'G2'], [events[0].date]), false)
})
