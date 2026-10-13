import test from 'node:test'
import assert from 'node:assert/strict'
import { buildEclipseWellEvents, compareEclipseWellRates, validateEclipseWellEvents } from '../src/views/SoftwareIntegration/eclipseWellEvents.js'

const draft = { baselineRunId: 74, wells: ['G1', 'G2'], shutDate: '1970-01-15', openDate: '1970-02-01',
  knownWells: ['G1', 'G2'], knownDates: ['1970-01-15', '1970-02-01'] }
test('group and historical single-well contracts remain distinct', () => {
  const group = buildEclipseWellEvents(draft)
  assert.equal(group.error, null)
  assert.equal(group.parameters.schemaVersion, 'eclipse-schedule-parameters/6')
  assert.deepEqual(group.parameters.wells, ['G1', 'G2'])
  assert.equal(Object.hasOwn(group.parameters, 'well'), false)
  assert.equal(buildEclipseWellEvents({ ...draft, wells: ['G1'] }).parameters.schemaVersion, 'eclipse-schedule-parameters/5')
})
test('bad group, baseline and dates never produce submit parameters', () => {
  for (const override of [{ wells: [] }, { wells: ['G1', 'G1'] }, { wells: ['G1', 'MISSING'] },
    { wells: ['*'] }, { wells: ['G1', 2] }, { wells: Array(9).fill('G1') }, { baselineRunId: '74' },
    { openDate: '1970-01-15' }, { openDate: '1970-02-30' }, { openDate: '1970-02-02' }]) {
    assert.equal(buildEclipseWellEvents({ ...draft, ...override }).parameters, null)
  }
})
test('extra fields and mixed single/group identities rejected', () => {
  const parameters = buildEclipseWellEvents(draft).parameters
  assert.equal(validateEclipseWellEvents({ ...parameters, well: 'G1' }), false)
  assert.equal(validateEclipseWellEvents({ ...parameters, events: [{ ...parameters.events[0], x: 1 }, parameters.events[1]] }), false)
})
const curve = (well, values, unit = 'STB/DAY', times = [0, 15, 32]) => ({ keyword: 'WOPR', objectName: well, unit,
  points: values.map((value, index) => ({ timeDays: times[index], value })) })
test('each well is compared against its own same-unit aligned baseline', () => {
  const comparison = compareEclipseWellRates({ series: [curve('G1', [10, 0, 10]), curve('G2', [20, 0, 20])] },
    { series: [curve('G1', [10, 10, 10]), curve('G2', [20, 20, 20])] }, ['G1', 'G2'])
  assert.equal(comparison.available, true)
  assert.equal(comparison.changedCount, 2)
  assert.equal(comparison.rows.length, 6)
  assert.deepEqual(comparison.comparisons.map(item => item.changedCount), [1, 1])
})
test('missing well, unit mismatch, no aligned points or duplicate identity is not comparable', () => {
  const baseline = { series: [curve('G1', [10, 10, 10]), curve('G2', [20, 20, 20])] }
  for (const series of [[curve('G1', [10, 0, 10])], [curve('G1', [10, 0, 10], 'm3/day'), curve('G2', [20, 0, 20])],
    [curve('G1', [10, 0, 10], 'STB/DAY', [1, 2, 3]), curve('G2', [20, 0, 20])],
    [curve('G1', [10, 0, 10]), curve('G1', [10, 0, 10]), curve('G2', [20, 0, 20])]]) {
    assert.equal(compareEclipseWellRates({ series }, baseline, ['G1', 'G2']).available, false)
  }
})
