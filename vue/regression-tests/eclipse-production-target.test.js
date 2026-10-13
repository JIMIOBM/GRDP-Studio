import test from 'node:test'
import assert from 'node:assert/strict'
import { productionTargetRecords, buildProductionTarget } from '../src/views/SoftwareIntegration/eclipseProductionTarget.js'

const inspection = () => ({ unitSystem: 'FIELD', caseName: 'CASE.DATA', packageFiles: [{ relativePath: 'CASE.DATA' }],
  scheduleTimeline: [{ kind: 'TSTEP', sourceFile: 'CASE.DATA', lineNumber: 10 }],
  scheduleMetadata: { wells: [{ name: 'P1' }], records: [{ keyword: 'WCONPROD', sourceFile: 'CASE.DATA', lineNumber: 6, values: ['P1', 'OPEN', 'ORAT', '20000', '4*', '2500'] }] } })
test('only matching initial OPEN literal records produce bound parameters', () => {
  const rows = productionTargetRecords(inspection())
  assert.equal(rows.length, 1)
  assert.deepEqual(buildProductionTarget({ inspection: inspection(), baselineRunId: 1, well: 'P1', targetOilRate: 10000 }), {
    schemaVersion: 'eclipse-production-target-parameters/1', baselineRunId: 1, well: 'P1', phase: 'INITIAL',
    sourceFile: 'CASE.DATA', lineNumber: 6, unit: 'STB/day', originalOilRate: 20000, targetOilRate: 10000
  })
})
test('switching model or source, bad unit, duplicate, closed and later records invalidate selection', () => {
  for (const mutate of [i => { i.unitSystem = 'METRIC' }, i => { i.packageFiles.push({ relativePath: 'extra.inc' }) },
    i => { i.scheduleMetadata.records[0].values[1] = 'SHUT' }, i => { i.scheduleMetadata.records[0].sourceFile = 'OTHER.DATA' },
    i => { i.scheduleTimeline[0].lineNumber = 4 }, i => { i.scheduleMetadata.records.push(i.scheduleMetadata.records[0]) }]) {
    const i = inspection(); mutate(i)
    assert.equal(buildProductionTarget({ inspection: i, baselineRunId: 1, well: 'P1', targetOilRate: 10000 }), null)
  }
  assert.equal(buildProductionTarget({ inspection: inspection(), baselineRunId: 1, well: 'OLD', targetOilRate: 10000 }), null)
  assert.equal(buildProductionTarget({ inspection: inspection(), baselineRunId: 1, well: 'P1', targetOilRate: 20000 }), null)
})
