import test from 'node:test'
import assert from 'node:assert/strict'
import { pressureControlRecords, buildPressureControl, validPressureControl } from '../src/views/SoftwareIntegration/eclipsePressureControl.js'

const inspection = () => ({ schemaVersion: 'eclipse-data-inspection/4', caseName: 'CASE.DATA', unitSystem: 'FIELD', packageFiles: [{ relativePath: 'CASE.DATA' }],
  scheduleTimeline: [{ kind: 'TSTEP', sourceFile: 'CASE.DATA', lineNumber: 10 }], scheduleMetadata: {
    wells: [{ name: 'P1' }, { name: 'I1' }], records: [
      { keyword: 'WCONPROD', sourceFile: 'CASE.DATA', lineNumber: 5, values: ['P1', 'OPEN', 'ORAT', '20000', '4*', '3000'] },
      { keyword: 'WCONINJE', sourceFile: 'CASE.DATA', lineNumber: 8, values: ['I1', 'WATER', 'OPEN', 'RESV', '1*', '1000', '6000'] }
    ] } })
test('producer and injector retain correct pressure indexes, original mode and fluid', () => {
  const i = inspection()
  assert.deepEqual(pressureControlRecords(i).map(r => r.originalBhp), [3000, 6000])
  for (const [well, targetBhp] of [['P1', 3500], ['I1', 5000]]) {
    const p = buildPressureControl({ inspection: i, baselineRunId: 1, well, targetBhp })
    assert.equal(validPressureControl(p), true)
    assert.equal(p.unit, 'psia')
    assert.equal(p.injectionType, well === 'I1' ? 'WATER' : 'NONE')
    assert.equal(validPressureControl({ ...p, unit: 'barsa' }), false)
    assert.equal(validPressureControl({ ...p, status: 'SHUT' }), false)
  }
})
test('defaulted pressure, stale context, duplicate source and changed units cannot become a plan', () => {
  for (const mutation of [
    i => { i.unitSystem = 'METRIC' }, i => { i.packageFiles.push({ relativePath: 'EXTRA.DATA' }) },
    i => { i.scheduleMetadata.records.push({ ...i.scheduleMetadata.records[0] }) },
    i => { i.scheduleMetadata.records[0].values = ['P1', 'OPEN', 'BHP', '6*'] },
    i => { i.scheduleMetadata.records[0].values[1] = 'SHUT' },
    i => { i.scheduleMetadata.records[0].lineNumber = 20 }
  ]) {
    const i = inspection(); mutation(i)
    assert.equal(buildPressureControl({ inspection: i, baselineRunId: 1, well: 'P1', targetBhp: 3500 }), null)
  }
})
