import { gridValueStats } from './eclipseGridPresentation.js'

const numeric = value => typeof value === 'number' && Number.isFinite(value)
const integer = value => Number.isSafeInteger(value) && value > 0
const maxCells = 250000
const keywords = ['TEMP', 'PRESSURE', 'SWAT', 'SGAS', 'SOIL']
export const spatialFields = run => (run?.result?.fieldIndex?.files || []).flatMap(file => (file.fields || []).map(field => ({ ...field, file: file.name, byteOrder: file.byteOrder })))
export const spatialSnapshot = run => run ? {
  id: run.id, modelId: run.modelId, modelVersionId: run.modelVersionId, runType: run.runType, status: run.status,
  resultContract: run.resultContract, resultExpired: run.resultExpired, parameters: run.parameters ? { baselineRunId: run.parameters.baselineRunId } : null,
  artifacts: (run.artifacts || []).map(a => ({ ...a })),
  result: { grid: run.result?.grid ? { ...run.result.grid } : null, fieldIndex: { files: (run.result?.fieldIndex?.files || []).map(file => ({ ...file, fields: file.fields.map(f => ({ ...f, segments: f.segments?.map(s => ({ ...s })) })) })) } }
} : null

function gridArtifact(run) {
  const name = 'eclipse-output-' + run?.result?.grid?.fileName
  const list = run?.artifacts?.filter(a => a.name === name) || []
  if (list.length !== 1 || !/^[a-f0-9]{64}$/i.test(list[0].sha256 || '')) throw new Error('缺少唯一的真实EGRID来源。')
  return list[0]
}
export function validateSpatialPair(run, baseline) {
  for (const r of [run, baseline]) {
    if (!integer(r?.id) || r.runType !== 'eclipse' || r.status !== 'SUCCEEDED' || r.resultContract !== 'VALID_FULL' || r.resultExpired) throw new Error('空间比较需要未过期的完整成功结果。')
  }
  if (run.id === baseline.id || run.parameters?.baselineRunId !== baseline.id || baseline.parameters != null ||
    !integer(run.modelId) || !integer(run.modelVersionId) || run.modelId !== baseline.modelId || run.modelVersionId !== baseline.modelVersionId) throw new Error('方案与原基线必须来自同一模型版本。')
  const a = run.result?.grid, b = baseline.result?.grid
  if (!a || !b || !['nx', 'ny', 'nz', 'activeCells'].every(k => integer(a[k]) && a[k] === b[k]) || a.fileName !== b.fileName || a.nx * a.ny * a.nz > maxCells || a.activeCells > a.nx * a.ny * a.nz) throw new Error('网格维度或活动格不一致，或超过25万格空间比较上限。')
  if (gridArtifact(run).sha256.toLowerCase() !== gridArtifact(baseline).sha256.toLowerCase()) throw new Error('两个运行的EGRID不同，不能直接逐格相减。')
  return a
}
const unique = (fields, keyword, step, file) => {
  const found = fields.filter(f => f.keyword === keyword && f.timeStep === step && (!file || f.file === file))
  if (found.length !== 1) throw new Error(`${keyword}在所选报告步缺失或有重复，不能确定来源。`)
  return found[0]
}
export function spatialChoices(run, baseline) {
  validateSpatialPair(run, baseline)
  const current = spatialFields(run), original = spatialFields(baseline), grid = run.result.grid
  const valid = field => keywords.includes(field.keyword) && /\.UNRST$/i.test(field.file) && ['REAL', 'DOUB'].includes(field.dataType) &&
    integer(field.timeStep) && [grid.activeCells, grid.nx * grid.ny * grid.nz].includes(field.count)
  return current.filter(valid).flatMap(field => {
    const candidates = original.filter(f => valid(f) && f.keyword === field.keyword && f.timeStep === field.timeStep && f.file === field.file)
    if (candidates.length !== 1 || current.filter(f => f.file === field.file && f.keyword === field.keyword && f.timeStep === field.timeStep).length !== 1) return []
    return [{ key: `${field.keyword}:${field.timeStep}`, keyword: field.keyword, report: field.timeStep, current: field, baseline: candidates[0] }]
  }).sort((a, b) => keywords.indexOf(a.keyword) - keywords.indexOf(b.keyword) || a.report - b.report)
}

export async function readSpatialValues(run, field, request, isCurrent) {
  const artifacts = run.artifacts.filter(a => a.name === 'eclipse-output-' + field.file && a.contentType === 'application/octet-stream' && integer(a.id))
  const sizes = { REAL: 4, DOUB: 8, INTE: 4 }
  if (artifacts.length !== 1 || !integer(field.count) || field.count > maxCells * 8 || sizes[field.dataType] !== field.elementSize || !['BIG', 'LITTLE'].includes(field.byteOrder)) throw new Error('二进制字段索引不可安全读取。')
  const artifact = artifacts[0], segments = field.segments
  if (!Array.isArray(segments) || !segments.length || segments.some(s => !Number.isSafeInteger(s.offset) || s.offset < 0 || !integer(s.length) || s.length % field.elementSize || s.offset + s.length > artifact.sizeBytes) ||
    segments.reduce((sum, s) => sum + s.length, 0) !== field.count * field.elementSize) throw new Error('二进制范围与字段长度不一致。')
  const values = [], little = field.byteOrder === 'LITTLE'
  for (const segment of segments) for (let start = 0; start < segment.length; start += 1048576) {
    if (!isCurrent()) return null
    const length = Math.min(segment.length - start, 1048576)
    const payload = await request(run.id, artifact.id, segment.offset + start, length)
    const bytes = await (payload instanceof Blob ? payload : new Blob([payload])).arrayBuffer()
    if (!isCurrent()) return null
    if (bytes.byteLength !== length) throw new Error('实际返回的二进制长度与请求不一致。')
    const view = new DataView(bytes)
    for (let i = 0; i < length; i += field.elementSize) values.push(field.dataType === 'DOUB' ? view.getFloat64(i, little) : field.dataType === 'REAL' ? view.getFloat32(i, little) : view.getInt32(i, little))
  }
  return values
}

export function assembleSpatialCells(grid, actnum, before, after) {
  const total = grid.nx * grid.ny * grid.nz
  if (actnum.length !== total || actnum.some(v => !Number.isInteger(v)) || actnum.filter(v => v > 0).length !== grid.activeCells ||
    ![total, grid.activeCells].includes(before.length) || ![total, grid.activeCells].includes(after.length)) throw new Error('ACTNUM或场值长度未与真实网格核对。')
  const cells = []
  let active = 0
  for (let index = 0; index < total; index++) if (actnum[index] > 0) {
    const baselineValue = before[before.length === total ? index : active], value = after[after.length === total ? index : active]
    if (!numeric(baselineValue) || !numeric(value) || !numeric(value - baselineValue)) throw new Error('活动格含非有限值，无法计算可靠差值。')
    cells.push({ index, i: index % grid.nx + 1, j: Math.floor(index / grid.nx) % grid.ny + 1, k: Math.floor(index / (grid.nx * grid.ny)) + 1, baselineValue, value, difference: value - baselineValue })
    active++
  }
  return cells
}

export async function loadSpatialComparison({ run, baseline, choice, unitSystem, request, isCurrent }) {
  const grid = validateSpatialPair(run, baseline), a = spatialFields(run), b = spatialFields(baseline)
  const selected = spatialChoices(run, baseline).find(c => c.key === choice.key)
  if (!selected) throw new Error('所选字段或报告已不属于当前结果。')
  const actnum = unique(b, 'ACTNUM', undefined, grid.fileName)
  if (actnum.dataType !== 'INTE') throw new Error('ACTNUM格式不是整数。')
  const requests = [
    [baseline, actnum], [baseline, selected.baseline], [run, selected.current],
    [baseline, unique(b, 'DOUBHEAD', choice.report, selected.baseline.file)], [run, unique(a, 'DOUBHEAD', choice.report, selected.current.file)],
    [baseline, unique(b, 'INTEHEAD', choice.report, selected.baseline.file)], [run, unique(a, 'INTEHEAD', choice.report, selected.current.file)],
    [baseline, unique(b, 'SEQNUM', choice.report, selected.baseline.file)], [run, unique(a, 'SEQNUM', choice.report, selected.current.file)]
  ]
  if (requests.slice(3, 5).some(([, f]) => f.dataType !== 'DOUB') || requests.slice(5).some(([, f]) => f.dataType !== 'INTE')) throw new Error('报告时间或网格头格式不正确。')
  const values = await Promise.all(requests.map(([source, field]) => readSpatialValues(source, field, request, isCurrent)))
  if (!isCurrent() || values.some(v => !v)) return null
  const [mask, before, after, clockBefore, clockAfter, headerBefore, headerAfter, sequenceBefore, sequenceAfter] = values
  // E300 uses FIELD=2/METRIC=1, unlike the E100 restart unit numbering.
  const unitCode = { FIELD: 2, METRIC: 1 }[unitSystem]
  if (!unitCode || !numeric(clockBefore[0]) || clockBefore[0] < 0 || clockBefore[0] !== clockAfter[0] ||
    // Thermal INTEHEAD[67] can contain the report count. The report identity is SEQNUM.
    sequenceBefore.length !== 1 || sequenceAfter.length !== 1 || sequenceBefore[0] !== choice.report || sequenceAfter[0] !== choice.report ||
    headerBefore.length < 95 || headerAfter.length < 95 || [headerBefore, headerAfter].some(h => h[2] !== unitCode || h[8] !== grid.nx || h[9] !== grid.ny || h[10] !== grid.nz || h[11] !== grid.activeCells || h[94] !== 500) ||
    [64, 65, 66].some(index => headerBefore[index] !== headerAfter[index])) throw new Error('两个报告的实际时间、E300网格或模型单位不同，拒绝相减。')
  const cells = assembleSpatialCells(grid, mask, before, after)
  return { grid, cells, report: choice.report, keyword: choice.keyword, timeDays: clockBefore[0],
    baselineStats: gridValueStats(cells.map(c => c.baselineValue)), currentStats: gridValueStats(cells.map(c => c.value)), differenceStats: gridValueStats(cells.map(c => c.difference)),
    changedCells: cells.filter(c => c.difference !== 0).length }
}
export const differenceColor = (value, maxAbs) => {
  if (!numeric(value)) return '#e5eaf0'
  if (!maxAbs || value === 0) return '#f5f7fb'
  const strength = Math.min(1, Math.abs(value) / maxAbs)
  return `hsl(${value < 0 ? 214 : 8}, ${50 + strength * 30}%, ${97 - strength * 47}%)`
}
export function spatialCsv(data, runId, baselineId, unit) {
  const cell = value => '"' + String(value ?? '').replaceAll('"', '""') + '"'
  const rows = [['baselineRunId', 'runId', 'keyword', 'unit', 'report', 'timeDays', 'I', 'J', 'K', 'baseline', 'scenario', 'difference'],
    ...data.cells.map(c => [baselineId, runId, data.keyword, unit, data.report, data.timeDays, c.i, c.j, c.k, c.baselineValue, c.value, c.difference])]
  return '\uFEFF' + rows.map(row => row.map(cell).join(',')).join('\r\n')
}
