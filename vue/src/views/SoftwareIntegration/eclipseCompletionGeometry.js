import { gridRegionResponse } from './eclipseGridRegion.js'

export const completionGeometryLabels = { DIAMETER: '井径', KH: '渗透率×完井厚度 KH', SKIN: '皮肤系数' }
const fields = Object.keys(completionGeometryLabels)
const index = field => ({ DIAMETER: 8, KH: 9, SKIN: 10 }[field])
const number = text => typeof text === 'string' && /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eEdD][+-]?\d+)?$/.test(text) ? Number(text.replace(/[dD]/, 'e')) : NaN
export const completionGeometryUnit = (system, field) => {
  if (!['METRIC', 'FIELD', 'LAB', 'PVT-M'].includes(system) || !fields.includes(field)) return ''
  if (field === 'SKIN') return '1'
  const length = system === 'FIELD' ? 'ft' : system === 'LAB' ? 'cm' : 'm'
  return field === 'KH' ? `mD.${length}` : length
}
const scalar = (field, unit, n) => typeof n === 'number' && Number.isFinite(n) && (field === 'SKIN' ? n >= 0 && n <= 100
  : field === 'KH' ? n > 0 && n <= 1e9 : field === 'DIAMETER' && n > 0 && n * (unit === 'ft' ? .3048 : unit === 'cm' ? .01 : 1) <= 2)

export function validCompletionGeometry(p) {
  const names = ['schemaVersion','baselineRunId','well','sourceFile','lineNumber','i','j','k1','k2','field','unit','originalValue','targetValue','recordValues']
  if (!p || Object.keys(p).length !== names.length || names.some(k => !Object.hasOwn(p,k)) || p.schemaVersion !== 'eclipse-completion-geometry-parameters/1'
    || !Number.isSafeInteger(p.baselineRunId) || p.baselineRunId <= 0 || !Number.isSafeInteger(p.lineNumber) || p.lineNumber <= 0 || p.lineNumber > 2e6
    || typeof p.well !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,63}$/.test(p.well)
    || typeof p.sourceFile !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.DATA$/i.test(p.sourceFile)
    || ['i','j','k1','k2'].some(k => typeof p[k] !== 'string' || !/^[1-9][0-9]{0,6}$/.test(p[k])) || p.k1 !== p.k2
    || !fields.includes(p.field) || !['METRIC','FIELD','LAB','PVT-M'].some(s => p.unit === completionGeometryUnit(s,p.field))
    || !scalar(p.field,p.unit,p.originalValue) || !scalar(p.field,p.unit,p.targetValue) || p.originalValue === p.targetValue
    || !Array.isArray(p.recordValues) || p.recordValues.length < 11 || p.recordValues.length > 14
    || p.recordValues.some(v => typeof v !== 'string' || !v.length || v.length > 64 || /[\x00-\x1f\x7f-\x9f]/.test(v))) return false
  const row = p.recordValues
  return row[0] === p.well && row[5] === 'OPEN' && ['i','j','k1','k2'].every((key,i) => row[i+1] === p[key])
    && (['*','1*'].includes(row[7]) || number(row[7]) === 0) && number(row[index(p.field)]) === p.originalValue
}

export function completionGeometryRecords(inspection) {
  const records = inspection?.scheduleMetadata?.records
  if (!Array.isArray(records) || !Array.isArray(inspection?.packageFiles) || inspection.packageFiles.length !== 1
    || !Array.isArray(inspection.scheduleTimeline) || !completionGeometryUnit(inspection.unitSystem,'DIAMETER')) return []
  const connections = records.filter(r => ['COMPDAT','COMPDATM'].includes(r.keyword))
  if (connections.some(r => /[*?]/.test(r.values?.[0] || ''))) return []
  return connections.filter(r => {
    const row = r.values
    if (r.keyword !== 'COMPDAT' || !Array.isArray(row) || typeof row[0] !== 'string'
      || connections.filter(x => x.values?.[0]?.trim() === row[0].trim()).length !== 1
      || r.sourceFile !== inspection.packageFiles[0].relativePath
      || inspection.scheduleTimeline.some(t => t.sourceFile !== r.sourceFile || !Number.isSafeInteger(t.lineNumber) || t.lineNumber <= r.lineNumber)
      || ['nx','ny','nz'].some(k => !Number.isSafeInteger(inspection.dimensions?.[k]) || inspection.dimensions[k] <= 0)
      || ['i','j','k1'].some((k,i) => Number(row[i+1]) > inspection.dimensions[['nx','ny','nz'][i]])
      || !fields.every(f => scalar(f,completionGeometryUnit(inspection.unitSystem,f),number(row[index(f)])))) return false
    return validCompletionGeometry({ schemaVersion:'eclipse-completion-geometry-parameters/1',baselineRunId:1,well:row[0],sourceFile:r.sourceFile,lineNumber:r.lineNumber,
      i:row[1],j:row[2],k1:row[3],k2:row[4],field:'SKIN',unit:'1',originalValue:number(row[10]),targetValue:number(row[10]) === 0 ? 1 : 0,recordValues:row })
  })
}

export function buildCompletionGeometry({run,inspection,record,field,targetValue}) {
  if (run?.status !== 'SUCCEEDED' || run?.resultContract !== 'VALID_FULL' || run?.parameters != null || run?.runType !== 'eclipse') throw new Error('请先选择当前模型版本的完整成功无参数基线。')
  const selected = completionGeometryRecords(inspection).find(r => r.sourceFile === record?.sourceFile && r.lineNumber === record?.lineNumber && JSON.stringify(r.values) === JSON.stringify(record?.values))
  if (!selected) throw new Error('需要单文件、初始单连接、OPEN、自动CF及明确井径/KH/皮肤系数的原始COMPDAT记录。')
  const row = selected.values
  const p = { schemaVersion:'eclipse-completion-geometry-parameters/1',baselineRunId:run.id,well:row[0],sourceFile:selected.sourceFile,lineNumber:selected.lineNumber,
    i:row[1],j:row[2],k1:row[3],k2:row[4],field,unit:completionGeometryUnit(inspection.unitSystem,field),originalValue:number(row[index(field)]),targetValue,recordValues:[...row] }
  if (!validCompletionGeometry(p)) throw new Error('目标值须不同于原值：井径为正且不超过2米，KH为正且不超过10⁹，皮肤系数为0–100；使用模型原单位。')
  return p
}

export function completionGeometryResponse(current, baseline, well) {
  if (typeof well !== 'string' || !well.trim()) return { available:false,rows:[],changedCount:0,unmatchedCount:0,message:'缺少目标井。' }
  const selected = summary => summary && { ...summary, series: Array.isArray(summary.series) ? summary.series.filter(s => s.objectName === well.trim()) : summary.series }
  const a = selected(current), b = selected(baseline)
  const wanted = new Set(['WBHP','WOPR','WWPR','WGPR','WOPT','WWPT','WGPT'])
  if ([a,b].some(s => s?.series?.some(v => wanted.has(v.keyword) && (typeof v.unit !== 'string' || !v.unit.trim())))) return { available:false,rows:[],changedCount:0,unmatchedCount:0,message:'目标井曲线缺少单位，无法计算差值。' }
  return gridRegionResponse(a,b)
}
