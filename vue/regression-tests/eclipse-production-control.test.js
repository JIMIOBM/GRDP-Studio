import test from 'node:test'
import assert from 'node:assert/strict'
import { productionControlRecords, buildProductionControl, validProductionControl, productionControlResultUnitMatches } from '../src/views/SoftwareIntegration/eclipseProductionControl.js'

const inspection = (mode, units = 'FIELD') => ({ schemaVersion: 'eclipse-data-inspection/4', unitSystem: units, caseName: 'CASE.DATA',
  packageFiles: [{ relativePath: 'CASE.DATA' }], scheduleTimeline: [{ sourceFile: 'CASE.DATA', lineNumber: 7 }],
  scheduleMetadata: { wells: [{ name: 'P1' }], records: [{ keyword: 'WCONPROD', sourceFile: 'CASE.DATA', lineNumber: 5,
    values: ['P1', 'OPEN', mode, mode === 'WRAT' ? '1*' : mode === 'GRAT' ? '2*' : '3*', '1000'] }] } })
test('Three controls bind the right expanded field and FIELD/METRIC units', () => {
  for (const mode of ['WRAT', 'GRAT', 'LRAT']) for (const units of ['FIELD', 'METRIC']) {
    const i = inspection(mode, units)
    const p = buildProductionControl({ inspection: i, baselineRunId: 1, well: 'P1', targetRate: 500 })
    assert.ok(validProductionControl(p))
    assert.equal(p.controlMode, mode)
    assert.equal(p.unit, units === 'METRIC' ? 'sm3/day' : mode === 'GRAT' ? 'Mscf/day' : 'STB/day')
    assert.equal(p.originalTargetRate, 1000)
    assert.equal(validProductionControl({ ...p, unit: 'psia' }), false)
    assert.equal(validProductionControl({ ...p, targetRate: 1000 }), false)
  }
})
test('Default, repeated, stale, SHUT, duplicate and later source records are unavailable', () => {
  for (const fault of ['default', 'repeat', 'shut', 'duplicate', 'later']) {
    const i = inspection('WRAT'), r = i.scheduleMetadata.records[0]
    if (fault === 'default') r.values[4] = '1*'
    if (fault === 'repeat') r.values[4] = '2*1000'
    if (fault === 'shut') r.values[1] = 'SHUT'
    if (fault === 'duplicate') i.scheduleMetadata.records.push({ ...r })
    if (fault === 'later') r.lineNumber = 8
    assert.deepEqual(productionControlRecords(i), [], fault)
  }
})
test('Result units must match the model target unit before claiming target response', () => {
  for (const [result, expected] of [['STB/DAY', 'STB/day'], ['MSCF/DAY', 'Mscf/day'], ['SM3/DAY', 'sm3/day']]) {
    assert.equal(productionControlResultUnitMatches(result, expected), true)
    assert.equal(productionControlResultUnitMatches('PSIA', expected), false)
    assert.equal(productionControlResultUnitMatches(null, expected), false)
  }
  assert.equal(productionControlResultUnitMatches('SM3/DAY', 'STB/day'), false)
})
