import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { productionTargetRecords, buildProductionTarget, validProductionTarget } from '../src/views/SoftwareIntegration/eclipseProductionTarget.js'
const inspection = () => JSON.parse(readFileSync(new URL('../tests/fixtures/eclipseIncludeProduction/inspection.json', import.meta.url), 'utf8'))

test('nested INCLUDE targets bind the physical record, original value and hash', () => {
  const i = inspection(), rows = productionTargetRecords(i)
  assert.equal(rows.length, 4)
  const target = rows.find(r => r.well === 'PRODUCR1')
  const p = buildProductionTarget({ inspection: i, baselineRunId: 100, well: target.well, targetOilRate: 10000 })
  assert.equal(p.schemaVersion, 'eclipse-production-target-parameters/2')
  assert.equal(p.sourceFile, 'PRODUCERS.INC'); assert.equal(p.sourceSha256, target.sourceSha256)
  assert.equal(p.lineNumber, target.lineNumber); assert.equal(p.originalOilRate, 20000)
  assert.equal(validProductionTarget(p), true)
})
test('parent-file line numbers never override authoritative INCLUDE execution order', () => {
  const i = inspection(); i.scheduleTimeline[0].lineNumber = 1
  assert.equal(productionTargetRecords(i).length, 4)
  assert.ok(buildProductionTarget({ inspection: i, baselineRunId: 100, well: 'PRODUCR1', targetOilRate: 10000 }))
})
test('changed source hashes, physical rows and malformed package contexts close editing', () => {
  for (const alter of [i => { i.unitSystem = 'METRIC' }, i => { i.packageFiles[0].sha256 = 'X' },
    i => { i.packageProductionTargetContext.targets[0].sourceSha256 = '0'.repeat(64) },
    i => { i.packageProductionTargetContext.targets[0].lineNumber++ },
    i => { i.packageProductionTargetContext.targets.push(i.packageProductionTargetContext.targets[0]) },
    i => { i.packageProductionTargetContext.targets[0].originalOilRate = '20000' },
    i => { i.packageProductionTargetContext.extra = 1 }, i => { i.packageProductionTargetContext = null }]) {
    const i = inspection(); alter(i); assert.deepEqual(productionTargetRecords(i), [])
    assert.equal(buildProductionTarget({ inspection: i, baselineRunId: 100, well: 'PRODUCR1', targetOilRate: 10000 }), null)
  }
})
test('package parameters reject downgrade, unsafe paths and forged extra fields', () => {
  const p = buildProductionTarget({ inspection: inspection(), baselineRunId: 100, well: 'PRODUCR1', targetOilRate: 10000 })
  for (const bad of [{ ...p, schemaVersion: 'eclipse-production-target-parameters/1' }, { ...p, sourceFile: '../PRODUCERS.INC' },
    { ...p, sourceFile: 'controls//PRODUCERS.INC' }, { ...p, sourceFile: 'controls\\PRODUCERS.INC' },
    { ...p, unit: 'sm3/day' }, { ...p, sourceSha256: 'A'.repeat(64) }, { ...p, targetOilRate: 20000 }, { ...p, extra: 1 }]) assert.equal(validProductionTarget(bad), false)
})
test('model changes and unknown wells cannot reuse a previous package target', () => {
  const i = inspection(); delete i.packageProductionTargetContext
  assert.equal(buildProductionTarget({ inspection: i, baselineRunId: 100, well: 'PRODUCR1', targetOilRate: 10000 }), null)
  assert.equal(buildProductionTarget({ inspection: inspection(), baselineRunId: 100, well: 'UNKNOWN', targetOilRate: 10000 }), null)
})
