import test from 'node:test'
import assert from 'node:assert/strict'
import * as XLSX from 'xlsx'
import { createErosionImportWorkbook, parseErosionImportWorkbook, erosionImportContextStamp, EROSION_CASE_SHEET, EROSION_SEGMENT_SHEET } from './pipelineErosionImport.js'

const graph = { edges: [{ id: 'pipe-a', name: '=井口至阀组' }, { id: 'pipe-b', name: '出口管道' }] }
const context = { projectId: 1, gasReservoirId: 2, wellName: 'A1-3' }, revision = 3
const cases = [{ id: 'condition-a', operatingAt: '2026-09-01T00:35' }, { id: 'condition-b', operatingAt: '2026-09-01T01:10' }]
const settings = { segments: [{ edgeId: 'pipe-a', liquidHoldupPercent: 0.5, sandContentPercent: 0.001, sandDensityKgM3: 2650 }],
  cases: [{ caseId: 'condition-b', edgeId: 'pipe-a', liquidHoldupPercent: 0, sandContentPercent: null }] }
const create = () => createErosionImportWorkbook(XLSX, graph, revision, context, cases, settings)
const parse = workbook => parseErosionImportWorkbook(XLSX, workbook, graph, revision, context, cases)
test('new templates contain only identities and leave all three user parameters blank', () => {
  const workbook = createErosionImportWorkbook(XLSX, graph, revision, context, cases, {})
  assert.equal(EROSION_SEGMENT_SHEET, '管道共用参数')
  const rows = parse(workbook)
  assert.ok(rows.segments.every(row => row.liquidHoldupPercent === null && row.sandContentPercent === null && row.sandDensityKgM3 === null))
  assert.deepEqual(rows.cases, [])
})
test('Excel roundtrip preserves identities, arbitrary minutes, measured zero and nullable overrides', () => {
  for (const bookType of ['xlsx', 'biff8']) {
    const workbook = XLSX.read(XLSX.write(create(), { bookType, type: 'buffer' }), { type: 'buffer', cellFormula: true })
    assert.equal(workbook.Sheets[EROSION_SEGMENT_SHEET].A2.t, 's')
    assert.equal(workbook.Sheets[EROSION_SEGMENT_SHEET].A2.f, undefined)
    assert.deepEqual(XLSX.utils.sheet_to_json(workbook.Sheets[EROSION_SEGMENT_SHEET], { header: 1 })[0],
      ['管道名称', '持液率 Hₗ（%）', '含砂率 Hₛ（%）', '砂粒密度（kg/m³）'])
    assert.deepEqual(XLSX.utils.sheet_to_json(workbook.Sheets[EROSION_CASE_SHEET], { header: 1 })[0],
      ['工况时间（只读）', '管道名称', '持液率 Hₗ（%）', '含砂率 Hₛ（%）', '砂粒密度（kg/m³）'])
    const parsed = parse(workbook)
    assert.equal(parsed.segments.length, 2)
    assert.equal(parsed.segments[0].liquidHoldupPercent, 0.5)
    assert.equal(parsed.segments[1].sandDensityKgM3, null)
    assert.deepEqual(parsed.cases, [{ caseId: 'condition-b', edgeId: 'pipe-a', liquidHoldupPercent: 0, sandContentPercent: null, sandDensityKgM3: null }])
  }
})
test('foreign well, changed topology, changed cases and edited readonly cells cannot cross-apply data', () => {
  const workbook = create()
  assert.throws(() => parseErosionImportWorkbook(XLSX, workbook, graph, revision, { ...context, wellName: 'A2' }, cases), /校验/)
  assert.throws(() => parseErosionImportWorkbook(XLSX, workbook, graph, revision + 1, context, cases), /校验/)
  assert.throws(() => parseErosionImportWorkbook(XLSX, workbook, graph, revision, context, [{ ...cases[0], id: 'replacement' }, cases[1]]), /校验/)
  workbook.Sheets[EROSION_SEGMENT_SHEET].A2.v = '其他管道'
  assert.throws(() => parse(workbook), /只读/)
  assert.notEqual(erosionImportContextStamp(graph, revision, context, cases), erosionImportContextStamp(graph, revision, context, [...cases].reverse()))
})
test('formulas, error cells, extra rows and invalid numeric fractions are rejected atomically', () => {
  for (const value of [{ t: 'n', v: 1, f: 'SUM(1)' }, { t: 'e', v: 7 }, { t: 's', v: 'NaN' }, { t: 'n', v: 100 }, { t: 'n', v: -1 }]) {
    const workbook = create(); workbook.Sheets[EROSION_SEGMENT_SHEET].B2 = value
    assert.throws(() => parse(workbook), /公式|错误|百分数/)
  }
  const extra = create(); extra.Sheets[EROSION_CASE_SHEET].A1000 = { t: 'n', v: 1 }
  assert.throws(() => parse(extra), /范围外/)
  const totals = create(); totals.Sheets[EROSION_SEGMENT_SHEET].C2 = { t: 'n', v: 50 }; totals.Sheets[EROSION_CASE_SHEET].C2 = { t: 'n', v: 50 }
  assert.throws(() => parse(totals), /之和必须小于/)
  assert.equal(settings.cases[0].liquidHoldupPercent, 0)
})
