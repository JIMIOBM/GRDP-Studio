import { validateSpatialPair, spatialFields, spatialChoices, readSpatialValues } from './eclipseSpatialComparison.js'

const integer = value => Number.isSafeInteger(value) && value >= 0
const finite = value => typeof value === 'number' && Number.isFinite(value)
function unique(fields, keyword, step, file) {
  const matches = fields.filter(f => f.keyword === keyword && f.timeStep === step && f.file === file)
  if (matches.length !== 1) throw new Error(`${keyword}的真实报告来源缺失或重复。`)
  return matches[0]
}
export function spatialElementOffset(field, index, artifactSize) {
  if (!integer(index) || !Number.isSafeInteger(field.count) || field.count < 1 || index >= field.count || ![4,8].includes(field.elementSize) || !integer(artifactSize) || !Array.isArray(field.segments) || !field.segments.length) throw new Error('单格字段索引或长度不正确。')
  let ordinal = 0, offset = null, previousEnd = -1
  for (const segment of field.segments) {
    if (!integer(segment.offset) || !Number.isSafeInteger(segment.length) || segment.length <= 0 || segment.length % field.elementSize || segment.offset < previousEnd || segment.offset + segment.length > artifactSize) throw new Error('原始字段分段越界、重叠或未按数值对齐。')
    const count = segment.length / field.elementSize
    if (index >= ordinal && index < ordinal + count) offset = segment.offset + (index - ordinal) * field.elementSize
    ordinal += count; previousEnd = segment.offset + segment.length
  }
  if (ordinal !== field.count || offset == null) throw new Error('原始字段分段与单元总数不一致。')
  return offset
}
async function readElement(run, field, index, request, isCurrent) {
  const artifacts = run.artifacts.filter(a => a.name === 'eclipse-output-' + field.file)
  if (artifacts.length !== 1) throw new Error('缺少唯一字段产物。')
  const offset = spatialElementOffset(field, index, artifacts[0].sizeBytes)
  const values = await readSpatialValues(run, { ...field, count: 1, segments: [{ offset, length: field.elementSize }] }, request, isCurrent)
  return values ? values[0] : null
}
function reportTime(grid, report, unitSystem, beforeClock, afterClock, beforeHeader, afterHeader, beforeSeq, afterSeq) {
  const unitCode = { FIELD:2, METRIC:1 }[unitSystem]
  if (!unitCode || !finite(beforeClock[0]) || beforeClock[0] < 0 || beforeClock[0] !== afterClock[0] || beforeSeq.length !== 1 || afterSeq.length !== 1 || beforeSeq[0] !== report || afterSeq[0] !== report || [beforeHeader,afterHeader].some(h => h.length < 95 || h[2] !== unitCode || h[8] !== grid.nx || h[9] !== grid.ny || h[10] !== grid.nz || h[11] !== grid.activeCells || h[94] !== 500) || [64,65,66].some(i => beforeHeader[i] !== afterHeader[i])) throw new Error('单格报告的实际时间、SEQNUM、E300网格或单位不一致。')
  return beforeClock[0]
}

export async function loadSpatialCellHistory({ run, baseline, index, keyword, unitSystem, request, isCurrent = () => true, onProgress = () => {} }) {
  const grid = validateSpatialPair(run, baseline), total = grid.nx*grid.ny*grid.nz
  if (!['TEMP','PRESSURE','SWAT','SGAS','SOIL'].includes(keyword)) throw new Error('当前单格历史仅支持温度、压力和三项饱和度。')
  if (!integer(index) || index >= total) throw new Error('请选择当前网格内的活动单元。')
  const choices = spatialChoices(run,baseline).filter(c => c.keyword === keyword)
  const original = spatialFields(baseline), current = spatialFields(run)
  // The index leaves timeStep undefined before the first positive SEQNUM. Accept
  // that initial block only after decoding its actual SEQNUM=0 and elapsed=0.
  const initial = current.filter(f => f.keyword === keyword && f.timeStep === undefined && /\.UNRST$/i.test(f.file))
  if (initial.length > 1) throw new Error('初始单格场存在多个来源，无法确定报告0。')
  if (initial.length === 1) {
    const before = original.filter(f => f.keyword === keyword && f.timeStep === undefined && f.file === initial[0].file)
    if (before.length !== 1 || [initial[0],before[0]].some(f => !['REAL','DOUB'].includes(f.dataType) || ![total,grid.activeCells].includes(f.count))) throw new Error('初始场或原基线报告0不完整。')
    choices.unshift({ report:0,initial:true,current:initial[0],baseline:before[0] })
  }
  if (!choices.length) throw new Error('该字段没有可核对的共同实际报告。')
  if (choices.length > 256) throw new Error('单格历史首期最多读取256个共同报告，请使用完整计算结果导出。')
  const maskField = unique(original,'ACTNUM',undefined,grid.fileName)
  if (maskField.dataType !== 'INTE') throw new Error('活动格映射必须来自整数ACTNUM。')
  const mask = await readSpatialValues(baseline,maskField,request,isCurrent)
  if (!isCurrent() || !mask) return null
  if (mask.length !== total || mask.some(v => !Number.isInteger(v)) || mask.filter(v => v>0).length !== grid.activeCells || mask[index] <= 0) throw new Error('所选单元不是经ACTNUM核对的活动单元。')
  const activeOrdinal = mask.slice(0,index).filter(v => v>0).length, rows = []
  for (const choice of choices) {
    if (!isCurrent()) return null
    const step = choice.initial ? undefined : choice.report
    const metadata = [[baseline,unique(original,'DOUBHEAD',step,choice.baseline.file)], [run,unique(current,'DOUBHEAD',step,choice.current.file)], [baseline,unique(original,'INTEHEAD',step,choice.baseline.file)], [run,unique(current,'INTEHEAD',step,choice.current.file)], [baseline,unique(original,'SEQNUM',step,choice.baseline.file)], [run,unique(current,'SEQNUM',step,choice.current.file)]]
    if (metadata.some(([,f],i) => f.dataType !== (i<2?'DOUB':'INTE'))) throw new Error('原始报告头格式不正确。')
    const values = await Promise.all([...metadata.map(([source,field]) => readSpatialValues(source,field,request,isCurrent)), readElement(baseline,choice.baseline,choice.baseline.count===total?index:activeOrdinal,request,isCurrent), readElement(run,choice.current,choice.current.count===total?index:activeOrdinal,request,isCurrent)])
    if (!isCurrent() || values.some(v => v==null)) return null
    const [bc,ac,bh,ah,bs,as,before,value] = values, timeDays = reportTime(grid,choice.report,unitSystem,bc,ac,bh,ah,bs,as)
    if (choice.initial && timeDays !== 0 || rows.length && timeDays <= rows.at(-1).timeDays || !finite(before) || !finite(value) || !finite(value-before)) throw new Error('报告时间未递增、初始报告不为0或单元场值不是有限数值。')
    rows.push({ report:choice.report,timeDays,baselineValue:before,value,difference:value-before })
    onProgress(rows.length,choices.length)
  }
  return { index, i:index%grid.nx+1,j:Math.floor(index/grid.nx)%grid.ny+1,k:Math.floor(index/(grid.nx*grid.ny))+1,keyword,rows,runId:run.id,baselineId:baseline.id,modelVersionId:run.modelVersionId }
}

export function spatialCellHistoryCsv(data,unit) {
  const rows = [['baseline_run_id','run_id','model_version_id','keyword','unit','I','J','K','global_cell_index','report','time_days','baseline','scenario','difference'],...data.rows.map(r => [data.baselineId,data.runId,data.modelVersionId,data.keyword,unit,data.i,data.j,data.k,data.index,r.report,r.timeDays,r.baselineValue,r.value,r.difference])]
  return '\uFEFF'+rows.map(row => row.map(v => `"${String(v??'').replace(/"/g,'""')}"`).join(',')).join('\r\n')
}
