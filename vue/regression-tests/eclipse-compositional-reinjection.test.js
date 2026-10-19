import test from 'node:test'
import assert from 'node:assert/strict'
import { compositionalReinjectionFixture } from '../tests/fixtures/eclipseCompositionalReinjection.js'
import { compositionalProductionFixture } from '../tests/fixtures/eclipseCompositionalProduction.js'
import { validCompositionalInspection, compositionalResult } from '../src/views/SoftwareIntegration/eclipseCompositional.js'
import { compositionalReinjectionRecords, matchesCompositionalReinjection, buildCompositionalReinjection, compositionalReinjectionResponse } from '../src/views/SoftwareIntegration/eclipseCompositionalReinjection.js'

test('actual /16 inspection binds both sources and keeps old /14 closed and readable', () => {
  const f = compositionalReinjectionFixture(), r = compositionalReinjectionRecords(f.inspection)[0]
  assert.equal(validCompositionalInspection(f.inspection), true); assert.equal(r.group, 'FIELD'); assert.equal(r.originalFraction, 1)
  assert.equal(r.salesTarget, 1500); assert.equal(r.shutAfterDays, 3650); assert.equal(r.injectionBhpUpperLimit, 4000)
  const old = compositionalProductionFixture().inspection
  assert.equal(validCompositionalInspection(old), true); assert.deepEqual(compositionalReinjectionRecords(old), [])
  const unknown = structuredClone(f.inspection); unknown.schemaVersion = 'eclipse-data-inspection/14'
  assert.equal(validCompositionalInspection(unknown), false)
  delete unknown.compositionalReinjectionContext; assert.equal(validCompositionalInspection(unknown), true)
})
test('strict available-gas fraction parameters reject stale or untyped changes and accept numeric zero', () => {
  const f = compositionalReinjectionFixture()
  assert.equal(matchesCompositionalReinjection(f.parameters, f.inspection), true)
  assert.equal(buildCompositionalReinjection({ inspection: f.inspection, baselineRunId: f.baseline.id, group: 'FIELD', targetFraction: 0 }).targetFraction, 0)
  for (const [key, value] of [['sourceFile', '../SPE3_GASCOND.DATA'], ['sourceSha256', '0'.repeat(64)], ['group', 'OTHER'], ['phase', 'WATER'], ['lineNumber', 451], ['unit', 'Mscf/day'],
    ['originalFraction', .9], ['targetFraction', 1], ['targetFraction', -.01], ['targetFraction', 1.01], ['targetFraction', '0.5'], ['baselineRunId', '1'], ['targetRate', 2350], ['schemaVersion', 'eclipse-compositional-production-parameters/1']])
    assert.equal(matchesCompositionalReinjection({ ...f.parameters, [key]: value }, f.inspection), false, key)
})
test('half reinjection uses available gas after sales, native cumulative and the original later shut plan', () => {
  const f = compositionalReinjectionFixture(), r = compositionalReinjectionResponse(f.native.half.summary, f.native.baseline.summary, f.parameters, f.inspection)
  assert.equal(r.verified, true); assert.equal(r.consistent, true); assert.equal(r.completePeriod, true); assert.equal(r.rows.length, 125)
  assert.equal(r.rows[0].availableGas, 4700); assert.equal(r.rows[0].plannedTarget, 2350); assert.equal(r.rows[0].actualInjection, 2350)
  assert.notEqual(r.rows[0].plannedTarget, r.rows[0].producedGas * .5)
  const boundary = r.rows.find(v => v.timeDays === 3650); assert.equal(boundary.planShut, false); assert.equal(boundary.actualInjection, 2350)
  assert.ok(r.closed > 0); assert.ok(r.active > 0); assert.equal(r.attained, r.active)
  assert.ok(r.rows.filter(v => v.planShut).every(v => v.actualInjection === 0))
  assert.equal(r.final.cumulativeInjection, 8577500); assert.equal(r.baselineCumulativeInjection, 17155000)
  assert.equal(r.final.producedGas, 31.043663024902344); assert.equal(r.final.productionBhp, 500); assert.equal(r.final.actualInjection, 0)
  assert.equal(compositionalResult(f.half, f.inspection).summary.series.length, 330)
})
test('corrupt gas accounting, mismatched source or a false shut time cannot become an editable inspection', () => {
  for (const fault of ['hash', 'size', 'source-kind', 'sales', 'pressure', 'fraction', 'line', 'shut', 'step', 'dates', 'well', 'extra']) {
    const f = compositionalReinjectionFixture(), c = f.inspection.compositionalReinjectionContext, r = c.records[0]
    if (fault === 'hash') c.sourceSha256 = '0'.repeat(64)
    if (fault === 'size') c.sourceSizeBytes = 1
    if (fault === 'source-kind') r.gasSourceKind = 'MIX'
    if (fault === 'sales') r.salesTarget = -1
    if (fault === 'pressure') r.injectionBhpUpperLimit = 0
    if (fault === 'fraction') r.originalFraction = 1.01
    if (fault === 'line') r.salesLineNumber = r.lineNumber
    if (fault === 'shut') r.shutAfterDays = 3651
    if (fault === 'step') f.inspection.scheduleTimeline[0].steps[0] = '328.6'
    if (fault === 'dates') f.inspection.scheduleTimeline[0].kind = 'DATES'
    if (fault === 'well') r.injectionWell = 'UNKNOWN'
    if (fault === 'extra') r.undocumented = true
    assert.equal(validCompositionalInspection(f.inspection), false, fault)
    assert.deepEqual(compositionalReinjectionRecords(f.inspection), [], fault)
  }
})
test('ambiguous or wrong-unit outputs cannot verify and violations of targets, caps and shut remain visible', () => {
  for (const fault of ['missing', 'duplicate', 'unit', 'pressure', 'rate', 'shut', 'total']) {
    const f = compositionalReinjectionFixture(), s = f.native.half.summary, get = key => s.series.find(v => v.keyword === key && v.objectName === null)
    if (fault === 'missing') s.series = s.series.filter(v => !(v.keyword === 'WGIT' && v.objectName === 'I'))
    if (fault === 'duplicate') s.series.push(structuredClone(get('FGIR')))
    if (fault === 'unit') get('FGIR').unit = 'STB/DAY'
    if (fault === 'pressure') s.series.find(v => v.keyword === 'WBHP' && v.objectName === 'I').points[0].value = 4001
    if (fault === 'rate') { get('FGIR').points[0].value = 2351; s.series.find(v => v.keyword === 'WGIR' && v.objectName === 'I').points[0].value = 2351 }
    if (fault === 'shut') { get('FGIR').points.at(-1).value = 1; s.series.find(v => v.keyword === 'WGIR' && v.objectName === 'I').points.at(-1).value = 1 }
    if (fault === 'total') { get('FGIT').points[0].value = -1; s.series.find(v => v.keyword === 'WGIT' && v.objectName === 'I').points[0].value = -1 }
    const r = compositionalReinjectionResponse(s, f.native.baseline.summary, f.parameters, f.inspection)
    if (['pressure', 'rate', 'shut'].includes(fault)) { assert.equal(r.consistent, false); assert.equal(r.verified, false) } else assert.equal(r, null)
  }
})
test('an apparently full result missing the final period cannot pass the complete original plan', () => {
  const f = compositionalReinjectionFixture()
  for (const s of f.native.half.summary.series) s.points.pop()
  const r = compositionalReinjectionResponse(f.native.half.summary, f.native.baseline.summary, f.parameters, f.inspection)
  assert.equal(r.completePeriod, false); assert.equal(r.verified, false)
})
