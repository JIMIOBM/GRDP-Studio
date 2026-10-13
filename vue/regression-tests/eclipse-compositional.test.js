import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { validCompositionalInspection, compositionalResult, componentOf, compositionalObject, compositionalCsv } from '../src/views/SoftwareIntegration/eclipseCompositional.js'

const fixture = () => {
  const inspection = JSON.parse(readFileSync(new URL('../../backend/src/test/resources/eclipse-compositional-inspection.json', import.meta.url), 'utf8'))
  // Small synthetic curves exercise UI identity and guards, never used as native solver evidence.
  const series = ['FOPR', 'FOPR', 'WXMF_1', 'BXMF_6', 'WGPR'].map((keyword, i) => ({ vectorIndex: i + 1, keyword,
    objectName: i === 2 ? 'P' : i === 4 ? ':+:+:+:+' : null, number: i === 3 ? 61 : 2, unit: i === 2 || i === 3 ? null : 'STB/DAY',
    points: [{ timeDays: 0, value: i + 1 }, { timeDays: 0.25, value: (i + 1) * 0.5 }] }))
  const run = { id: 1, status: 'SUCCEEDED', resultContract: 'VALID_FULL', result: { schemaVersion: 'eclipse-summary-result/2', modelKind: 'eclipse_300', runTask: 'eclipse',
    caseName: inspection.caseName, resultContract: 'VALID_FULL', simulation: structuredClone(inspection.simulation), eclEnd: { comments: 1, warnings: 3, problems: 0, errors: 0, bugs: 0 },
    summary: { startDate: '1990-01-01', timeVectorIndex: 0, series } } }
  return { inspection, run }
}

test('native inspection nine ordered components and original source file are recognized', () => {
  const f = fixture(); assert.equal(validCompositionalInspection(f.inspection), true)
  assert.equal(compositionalResult(f.run, f.inspection), f.run.result)
})
test('duplicate curves raw NUMS placeholders full CSV and actual fractional clocks remain distinct', () => {
  const { inspection, run } = fixture(), r = compositionalResult(run, inspection)
  assert.equal(r.summary.series.length, 5)
  assert.equal(compositionalCsv(r).split('\r\n').length, 11)
  assert.match(compositionalCsv(r), /":\+:\+:\+:\+"/)
  assert.deepEqual(componentOf(r.summary.series[2], r.simulation), { index: 1, name: 'CO2' })
  assert.deepEqual(componentOf(r.summary.series[3], r.simulation), { index: 6, name: 'C4-6' })
  assert.equal(compositionalObject(r.summary.series[3], inspection.dimensions), '网格 (7, 7, 1)')
  assert.equal(componentOf(r.summary.series[0], r.simulation), null)
})
test('wrong model solver ordered component list and old result contract do not display', () => {
  for (const fault of ['solver', 'name', 'case', 'schema', 'status', 'contract', 'problems']) {
    const { inspection, run } = fixture()
    if (fault === 'solver') run.result.simulation.solver = 100
    if (fault === 'name') run.result.simulation.componentNames[0] = 'OTHER'
    if (fault === 'case') run.result.caseName = 'OTHER.DATA'
    if (fault === 'schema') run.result.schemaVersion = 'eclipse-summary-result/1'
    if (fault === 'status') run.status = 'RUNNING'
    if (fault === 'contract') run.resultContract = 'VALID_PARTIAL'
    if (fault === 'problems') run.result.eclEnd.problems = 1
    assert.equal(compositionalResult(run, inspection), null, fault)
  }
})
test('missing vectors raw identities fake origin and mismatched actual clocks are rejected', () => {
  for (const fault of ['missing', 'index', 'axis', 'number', 'component', 'block', 'clock', 'duplicate', 'nan', 'date', 'year', 'extra']) {
    const { inspection, run } = fixture(), s = run.result.summary, v = s.series[2]
    if (fault === 'missing') s.series.splice(1, 1)
    if (fault === 'index') v.vectorIndex = 2
    if (fault === 'axis') s.timeVectorIndex = 3
    if (fault === 'number') delete v.number
    if (fault === 'component') v.keyword = 'WXMF_10'
    if (fault === 'block') s.series[3].number = 325
    if (fault === 'clock') v.points[1].timeDays = 0.5
    if (fault === 'duplicate') v.points[1].timeDays = 0
    if (fault === 'nan') v.points[1].value = NaN
    if (fault === 'date') s.startDate = '1990-02-31'
    if (fault === 'year') s.startDate = '0000-01-01'
    if (fault === 'extra') v.fake = true
    assert.equal(compositionalResult(run, inspection), null, fault)
  }
})
test('component filters cannot invent unrequested component outputs', () => {
  const { run } = fixture(); assert.equal(run.result.summary.series.filter(s => componentOf(s, run.result.simulation)?.index === 9).length, 0)
})
