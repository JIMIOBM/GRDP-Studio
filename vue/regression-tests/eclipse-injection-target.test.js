import test from 'node:test'
import assert from 'node:assert/strict'
import { compareEclipseWellRates, eclipseSurfaceRateUnit, eclipseDatedControl } from '../src/views/SoftwareIntegration/eclipseWellEvents.js'

test('injection comparison selects injection phase and never substitutes production rate', () => {
  const a = { series: [{ keyword: 'WWIR', objectName: 'D1', unit: 'STB/DAY', points: [{ timeDays: 14, value: 1500 }] }] }
  const b = { series: [{ keyword: 'WWIR', objectName: 'D1', unit: 'STB/DAY', points: [{ timeDays: 14, value: 2000 }] }] }
  const diff = compareEclipseWellRates(a, b, ['D1'], 'WWIR')
  assert.equal(diff.available, true)
  assert.equal(diff.changedCount, 1)
  assert.equal(diff.rows[0].difference, -500)
  assert.equal(compareEclipseWellRates(a, b, ['D1']).available, false)
  assert.equal(compareEclipseWellRates(a, b, ['D1'], 'WGIR').available, false)
})

test('official rate units and original values follow the selected date and phase', () => {
  assert.equal(eclipseSurfaceRateUnit('FIELD', 'WATER'), 'STB/day')
  assert.equal(eclipseSurfaceRateUnit('FIELD', 'GAS'), 'Mscf/day')
  assert.equal(eclipseSurfaceRateUnit('LAB', 'OIL'), 'scc/hr')
  assert.equal(eclipseSurfaceRateUnit('METRIC'), 'sm³/day')
  assert.equal(eclipseSurfaceRateUnit('UNKNOWN'), null)
  assert.equal(eclipseSurfaceRateUnit('FIELD', 'MULTI'), null)
  const first = { keyword: 'WCONINJE', values: ['D1', 'WATER', 'OPEN', 'RATE', '2000'], sourceFile: 'BASE.SCH', lineNumber: 12 }
  const second = { ...first, values: ['D1', 'WATER', 'OPEN', 'RATE', '3000'], lineNumber: 22 }
  const inspection = { scheduleTimeline: [
    { kind: 'DATES', sourceFile: 'BASE.SCH', lineNumber: 10, records: [{ day: '15', month: 'JAN', year: '1970' }] },
    { kind: 'DATES', sourceFile: 'BASE.SCH', lineNumber: 20, records: [{ day: '1', month: 'FEB', year: '1970' }] }
  ], scheduleMetadata: { records: [first, second] } }
  assert.equal(eclipseDatedControl(inspection, 'WCONINJE', 'D1', '1970-02-01'), second)
  assert.equal(eclipseDatedControl(inspection, 'WCONINJE', 'D1', '1970-01-15'), first)
  assert.equal(eclipseDatedControl(inspection, 'WCONINJE', 'D1', '1970-03-01'), null)
  inspection.scheduleMetadata.records.push({ ...second })
  assert.equal(eclipseDatedControl(inspection, 'WCONINJE', 'D1', '1970-02-01'), null)
})
