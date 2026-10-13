import { gridRegionResponse } from './eclipseGridRegion.js'
const number=s=>typeof s==='string'&&s.length<=64&&/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eEdD][+-]?\d+)?$/.test(s)?Number(s.replace(/[dD]/,'e')):NaN
export const oilPressureUnit=system=>system==='FIELD'?'psia':['LAB','PVT-M'].includes(system)?'atma':system==='METRIC'?'barsa':''
export const oilVolumeUnit=system=>system==='FIELD'?'rb/stb':system==='LAB'?'rcc/scc':['METRIC','PVT-M'].includes(system)?'rm3/sm3':''
const keys=['schemaVersion','baselineRunId','sourceFile','sourceSha256','keywordLineNumber','unit','multiplier','rows']
export function validOilPvtRows(rows,keywordLine){
  if(!Array.isArray(rows)||rows.length<2||rows.length>64)return false
  let line=keywordLine,pressure=0,bo=Infinity,mu=0
  for(const row of rows){
    if(!row||Object.keys(row).length!==2||!Object.hasOwn(row,'lineNumber')||!Object.hasOwn(row,'values')||!Number.isSafeInteger(row.lineNumber)||row.lineNumber<=line||row.lineNumber>2e6||!Array.isArray(row.values)||row.values.length!==3)return false
    const n=row.values.map(number)
    if(n.some(v=>!Number.isFinite(v))||n[0]<=pressure||n[1]<=0||n[1]>=bo||n[1]>10||n[2]<=0||n[2]<mu||n[2]>1000)return false
    line=row.lineNumber;[pressure,bo,mu]=n
  }
  return true
}
export const validOilPvt=p=>!!p&&Object.keys(p).length===keys.length&&keys.every(k=>Object.hasOwn(p,k))&&p.schemaVersion==='eclipse-pvdo-parameters/1'
  &&Number.isSafeInteger(p.baselineRunId)&&p.baselineRunId>0&&Number.isSafeInteger(p.keywordLineNumber)&&p.keywordLineNumber>0&&p.keywordLineNumber<=2e6
  &&typeof p.sourceFile==='string'&&/^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.DATA$/i.test(p.sourceFile)&&typeof p.sourceSha256==='string'&&/^[a-f0-9]{64}$/.test(p.sourceSha256)&&p.unit==='cP'
  &&typeof p.multiplier==='number'&&Number.isFinite(p.multiplier)&&p.multiplier>=.000001&&p.multiplier<=100&&p.multiplier!==1&&validOilPvtRows(p.rows,p.keywordLineNumber)
  &&p.rows.every(r=>{const target=number(r.values[2])*p.multiplier;return Number.isFinite(target)&&target>0&&target<=1000})
export function oilPvtRecord(inspection){
  const c=inspection?.oilPvtContext,file=inspection?.packageFiles?.[0]
  if(inspection?.schemaVersion!=='eclipse-data-inspection/4'||!['METRIC','FIELD','LAB','PVT-M'].includes(inspection.unitSystem)||!inspection.phases?.includes('OIL')||inspection.packageFiles?.length!==1
    ||c?.schemaVersion!=='eclipse-oil-pvt-context/1'||c.sourceFile!==file.relativePath)return null
  return validOilPvt({schemaVersion:'eclipse-pvdo-parameters/1',baselineRunId:1,sourceFile:c.sourceFile,sourceSha256:file.sha256,keywordLineNumber:c.keywordLineNumber,unit:'cP',multiplier:.5,rows:c.rows})?c:null
}
export function buildOilPvt({run,inspection,multiplier}){
  if(run?.runType!=='eclipse'||run.status!=='SUCCEEDED'||run.resultContract!=='VALID_FULL'||run.parameters!=null)throw new Error('请选择当前模型版本的完整无参数成功基线。')
  const c=oilPvtRecord(inspection);if(!c)throw new Error('需要单DATA死油、唯一明确PVDO表；多PVT区、默认字段、INCLUDE及溶解气模型尚未开放。')
  const p={schemaVersion:'eclipse-pvdo-parameters/1',baselineRunId:run.id,sourceFile:c.sourceFile,sourceSha256:inspection.packageFiles[0].sha256,keywordLineNumber:c.keywordLineNumber,unit:'cP',multiplier,rows:c.rows.map(r=>({lineNumber:r.lineNumber,values:[...r.values]}))}
  if(!validOilPvt(p))throw new Error('乘数须为0.000001–100有限数值且不为1；全部目标油黏度必须大于0且≤1000cP，原表需满足压力递增、体积系数递减及黏度不减。')
  return p
}
export const oilPvtResponse=(current,baseline)=>gridRegionResponse(current,baseline,['WBHP','WOPR','WWPR','WLPR'])
