import assert from 'node:assert/strict'
import test from 'node:test'
import { newDatesWindows, buildNewDates, validNewDates, newDatesResponse } from '../src/views/SoftwareIntegration/eclipseNewDates.js'
const inspection = () => ({ schemaVersion: 'eclipse-data-inspection/4', caseName: 'CASE.DATA', packageFiles: [{ relativePath: 'CASE.DATA' }], scheduleTimeline: [6, 26].map((day, n) => ({ kind: 'DATES', records: [{ day: String(day), month: 'JAN', year: '1996', time: null }], sourceFile: 'CASE.DATA', lineNumber: [16, 22][n] })), scheduleMetadata: { wells: [{ name: 'P1', sourceFile: 'CASE.DATA', lineNumber: 8 }], completions: [{ well: 'P1', status: 'OPEN', sourceFile: 'CASE.DATA', lineNumber: 11 }], records: [{ keyword: 'WCONPROD', values: ['P1', 'OPEN', 'BHP', '5*', '3000'], sourceFile: 'CASE.DATA', lineNumber: 14 }] } })
const parameters = () => buildNewDates({ inspection: inspection(), baselineRunId: 1, well: 'P1', beforeLineNumber: 16, shutDate: '1996-01-11', openDate: '1996-01-21' })
test('only increasing single-record DATES windows and existing open producers are offered', () => {
  assert.equal(newDatesWindows(inspection()).length, 1)
  assert.ok(validNewDates(parameters()))
  for (const fault of ['tstep', 'multiple', 'order', 'files', 'time']) {
    const i = inspection()
    if (fault === 'tstep') i.scheduleTimeline[0].kind = 'TSTEP'
    if (fault === 'multiple') i.scheduleTimeline[0].records.push({ day: '7', month: 'JAN', year: '1996' })
    if (fault === 'order') i.scheduleTimeline.reverse()
    if (fault === 'files') i.packageFiles.push({ relativePath: 'OTHER.SCH' })
    if (fault === 'time') i.scheduleTimeline[0].records[0].time = '12:00:00'
    assert.deepEqual(newDatesWindows(i), [], fault)
  }
})
test('invalid calendar, duplicate, outside interval and stale source reject', () => {
  const p = parameters()
  for (const change of [{ shutDate: '1996-02-30' }, { openDate: p.shutDate }, { openDate: p.afterDate }, { beforeLineNumber: '16' }, { extra: 1 }]) assert.equal(validNewDates({ ...p, ...change }), false)
  assert.equal(buildNewDates({ inspection: inspection(), baselineRunId: 1, well: 'P1', beforeLineNumber: 17, shutDate: p.shutDate, openDate: p.openDate }), null)
})
const summary = values => ({ series: [['DAY', [1, 6, 11, 21, 26], ''], ['MONTH', [1, 1, 1, 1, 1], ''], ['YEAR', [1996, 1996, 1996, 1996, 1996], ''], ['WOPR', [0, ...values], 'STB/DAY']].map(([keyword, v, unit]) => ({ keyword, objectName: keyword === 'WOPR' ? 'P1' : null, unit, points: v.map((value, n) => ({ timeDays: [0, 5, 10, 20, 25][n], value })) })) })
test('report date records describe preceding interval; zero at reopening date and positive afterwards prove response', () => {
  const p = parameters(), current = summary([100, 100, 0, 90]), baseline = summary([100, 100, 100, 100])
  assert.equal(newDatesResponse(current, baseline, p).verified, true)
  assert.equal(newDatesResponse(summary([100, 100, 100, 90]), baseline, p).verified, false)
  assert.equal(newDatesResponse(summary([100, 100, 0, 0]), baseline, p).verified, false)
  const absent = summary([100, 100, 0, 90]); absent.series.shift()
  assert.equal(newDatesResponse(absent, baseline, p).available, false)
  const duplicate = summary([100, 100, 0, 90]); duplicate.series.push(duplicate.series[0])
  assert.equal(newDatesResponse(duplicate, baseline, p).available, false)
})
