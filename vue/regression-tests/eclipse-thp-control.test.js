import test from 'node:test'
import assert from 'node:assert/strict'
import { thpControlRecords, buildThpControl, thpTableCoverage, validThpControl } from '../src/views/SoftwareIntegration/eclipseThpControl.js'

const table = { number: 1, datumDepth: 6900, flowType: 'LIQ', waterFractionType: 'WCT', gasFractionType: 'GOR', alqType: '', unitSystem: 'FIELD', sourceFile: 'CASE.DATA', lineNumber: 5, sha256: 'a'.repeat(64), flowAxis: [1, 3000], thpAxis: [200, 1000], waterFractionAxis: [0, .8], gasFractionAxis: [1, 4], alqAxis: [0] }
const inspection = { schemaVersion: 'eclipse-data-inspection/5', unitSystem: 'FIELD', caseName: 'CASE.DATA', packageFiles: [{ relativePath: 'CASE.DATA' }], vfpTables: [table], scheduleTimeline: [{ sourceFile: 'CASE.DATA', lineNumber: 40 }], scheduleMetadata: { wells: [{ name: 'HORIZ' }], records: [{ keyword: 'WCONPROD', sourceFile: 'CASE.DATA', lineNumber: 30, values: ['HORIZ', 'OPEN', 'THP', '5*', '400', '500', '1'] }] } }
const p = buildThpControl({ inspection, baselineRunId: 1, well: 'HORIZ', targetThp: 700 })
const summary = { series: [['WLPR', 2000, 'STB/DAY'], ['WOPR', 1600, 'STB/DAY'], ['WWPR', 400, 'STB/DAY'], ['WGPR', 3200, 'MSCF/DAY'], ['WTHP', 700, 'PSIA']].map(([keyword, value, unit]) => ({ keyword, unit, objectName: 'HORIZ', points: [{ timeDays: 1, value }] })) }
test('THP source binding and defaulted ALQ preserve units and table hash', () => {
  assert.equal(thpControlRecords(inspection).length, 1)
  assert.equal(p.originalAlq, 0)
  assert.equal(p.originalThp, 500)
  assert.equal(p.vfpTableSha256, table.sha256)
  assert.ok(validThpControl(p))
  assert.equal(buildThpControl({ inspection, baselineRunId: 1, well: 'HORIZ', targetThp: 1500 }), null)
  assert.equal(validThpControl({ ...p, extra: true }), false)
})
test('THP table coverage requires unique complete same-unit aligned real outputs', () => {
  assert.equal(thpTableCoverage(summary, p, [table]).inRange, true)
  for (const fault of ['unit', 'missing', 'duplicate', 'alignment', 'flow', 'gor']) {
    const bad = structuredClone(summary)
    if (fault === 'unit') bad.series[0].unit = 'SM3/DAY'
    if (fault === 'missing') bad.series.pop()
    if (fault === 'duplicate') bad.series.push(bad.series[0])
    if (fault === 'alignment') bad.series[1].points[0].timeDays = 2
    if (fault === 'flow') bad.series[0].points[0].value = 4000
    if (fault === 'gor') bad.series[3].points[0].value = 10000
    assert.equal(thpTableCoverage(bad, p, [table]).inRange, false, fault)
  }
})
test('Stale, duplicate, unsupported or unbound VFP cannot offer a THP editor', () => {
  for (const fault of ['duplicate', 'sha', 'mode', 'alq', 'later']) {
    const bad = structuredClone(inspection)
    if (fault === 'duplicate') bad.vfpTables.push(table)
    if (fault === 'sha') bad.vfpTables[0].sha256 = 'bad'
    if (fault === 'mode') bad.scheduleMetadata.records[0].values[2] = 'BHP'
    if (fault === 'alq') bad.scheduleMetadata.records[0].values.push('1')
    if (fault === 'later') bad.vfpTables[0].lineNumber = 50
    assert.deepEqual(thpControlRecords(bad), [], fault)
  }
})
