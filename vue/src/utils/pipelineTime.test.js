import test from 'node:test'
import assert from 'node:assert/strict'
import { parseOperatingTime, normalizeOperatingTime, formatOperatingTime } from './pipelineTime.js'
import { boundaryTimeIssue, normalizeBoundary } from './pipelineBoundary.js'

test('arbitrary minutes and legacy formats represent the same Beijing timestamp', () => {
  for (const value of ['2026/09/01 08:37', '2026-09-01T08:37', '2026-09-01 08:37:00']) {
    assert.equal(parseOperatingTime(value), Date.UTC(2026, 8, 1, 0, 37))
    assert.equal(normalizeOperatingTime(value), '2026-09-01T08:37')
    assert.equal(formatOperatingTime(value), '2026/09/01 08:37')
  }
  assert.notEqual(parseOperatingTime('2026/09/01 08:37'), parseOperatingTime('2026/09/01 08:38'))
  assert.equal(formatOperatingTime('2024-02-29T23:59'), '2024/02/29 23:59')
  assert.equal(formatOperatingTime('0001-01-01T00:01'), '0001/01/01 00:01')
})

test('free text is retained while incomplete dates, invalid dates and nonzero seconds are not valid times', () => {
  for (const value of ['', '2026/09/', '2026/9/1 8:37', '2026/02/29 08:37', '2026/04/31 08:37',
    '2026/09/01 24:00', '2026/09/01 08:60', '0000/01/01 00:00', '2026-09-01T08:37:01', '2026-09-01T08:37Z']) {
    assert.equal(parseOperatingTime(value), null)
    assert.equal(normalizeOperatingTime(value), value)
    assert.equal(formatOperatingTime(value), value)
  }
  assert.equal(formatOperatingTime(null), '')
  assert.equal(parseOperatingTime(null), null)
})

test('saving requires valid distinct times, while draft normalization preserves unfinished text', () => {
  const boundary = { topologyRevision: 1, activeCaseId: 'a', cases: [
    { id: 'a', operatingAt: '2026/09/01 08:17', nodes: [] }, { id: 'b', operatingAt: '2026/09/01 08:49', nodes: [] }
  ] }
  assert.equal(boundaryTimeIssue(boundary), '')
  const normalized = normalizeBoundary(boundary)
  assert.equal(normalized.cases[0].operatingAt, '2026-09-01T08:17')
  boundary.cases[1].operatingAt = '2026-09-01T08:17:00'
  assert.match(boundaryTimeIssue(boundary), /第 2 行.*第 1 行重复/)
  boundary.cases[1].operatingAt = '2026/09/'
  assert.equal(normalizeBoundary(boundary).cases[1].operatingAt, '2026/09/')
  assert.match(boundaryTimeIssue(boundary), /第 2 行.*YYYY\/MM\/DD hh:mm/)
  assert.match(boundaryTimeIssue({ cases: [{ id: 'a', operatingAt: null, nodes: [] }] }), /第 1 行.*无效/)
})
