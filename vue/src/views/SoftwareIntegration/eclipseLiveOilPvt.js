import { oilPressureUnit, oilVolumeUnit } from './eclipseOilPvt.js'
import { gridRegionResponse } from './eclipseGridRegion.js'
export { oilPressureUnit, oilVolumeUnit }
const number=s=>typeof s==='string'&&s.length<=64&&/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eEdD][+-]?\d+)?$/.test(s)?Number(s.replace(/[dD]/,'e')):NaN
const exact=(value,keys)=>!!value&&typeof value==='object'&&!Array.isArray(value)&&Object.keys(value).length===keys.length&&keys.every(key=>Object.hasOwn(value,key))
export const liveOilPvtLabel=p=>p?.field==='FORMATION_VOLUME_FACTOR'?'含溶解气原油体积系数 Bo':'含溶解气原油黏度'
export const liveOilRsUnit=system=>system==='FIELD'?'Mscf/stb':system==='LAB'?'scc/scc':['METRIC','PVT-M'].includes(system)?'sm3/sm3':''
export function validLiveOilRecords(records,keywordLine){
  if(!Array.isArray(records)||records.length<2||records.length>32||!Array.isArray(records.at(-1)?.rows)||records.at(-1).rows.length<2)return false
  let line=keywordLine,rs=-1,bubble=0,total=0
  for(const record of records){
    if(!exact(record,['rows'])||!Array.isArray(record.rows)||record.rows.length<1||record.rows.length>64||(total+=record.rows.length)>256)return false
    let pressure=0,bo=Infinity,mu=0
    for(const [index,row] of record.rows.entries()){
      const first=index===0,start=first?1:0
      if(!exact(row,['lineNumber','values'])||!Number.isSafeInteger(row.lineNumber)||row.lineNumber<=line||row.lineNumber>2e6||!Array.isArray(row.values)||row.values.length!==(first?4:3))return false
      const n=row.values.map(number);if(n.some(v=>!Number.isFinite(v)))return false
      if(first){if(n[0]<0||n[0]<=rs||n[1]<=bubble)return false;rs=n[0];bubble=n[1]}
      if(n[start]<=pressure||n[start+1]<=0||n[start+1]>=bo||n[start+1]>10||n[start+2]<=0||n[start+2]<mu||n[start+2]>1000)return false
      line=row.lineNumber;[pressure,bo,mu]=n.slice(start)
    }
  }return true
}
export function validLiveOilPvt(p){
  if(!exact(p,['schemaVersion','baselineRunId','sourceFile','sourceSha256','keywordLineNumber','unit','multiplier','records','field'])||p.schemaVersion!=='eclipse-pvto-parameters/1'
    ||!['VISCOSITY','FORMATION_VOLUME_FACTOR'].includes(p.field)||!Number.isSafeInteger(p.baselineRunId)||p.baselineRunId<=0||!Number.isSafeInteger(p.keywordLineNumber)||p.keywordLineNumber<=0||p.keywordLineNumber>2e6
    ||typeof p.sourceFile!=='string'||!/^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.DATA$/i.test(p.sourceFile)||typeof p.sourceSha256!=='string'||!/^[a-f0-9]{64}$/.test(p.sourceSha256)
    ||!(p.field==='VISCOSITY'?p.unit==='cP':['rb/stb','rm3/sm3','rcc/scc'].includes(p.unit))||typeof p.multiplier!=='number'||!Number.isFinite(p.multiplier)||p.multiplier<.000001||p.multiplier>100||p.multiplier===1||!validLiveOilRecords(p.records,p.keywordLineNumber))return false
  const volume=p.field==='FORMATION_VOLUME_FACTOR'
  return p.records.every(record=>{let previous=Infinity;return record.rows.every(row=>{const target=number(row.values.at(volume?-2:-1))*p.multiplier,valid=Number.isFinite(target)&&target>0&&target<=(volume?10:1000)&&(!volume||target<previous);previous=target;return valid})})
}
export function liveOilPvtRecord(inspection){
  const c=inspection?.liveOilPvtContext,file=inspection?.packageFiles?.[0]
  if(inspection?.schemaVersion!=='eclipse-data-inspection/4'||!['METRIC','FIELD','LAB','PVT-M'].includes(inspection.unitSystem)||!inspection.phases?.includes('OIL')||!inspection.phases?.includes('GAS')||inspection.packageFiles?.length!==1
    ||!exact(c,['schemaVersion','sourceFile','keywordLineNumber','records'])||c.schemaVersion!=='eclipse-live-oil-pvt-context/1'||c.sourceFile!==file.relativePath||c.sourceFile!==inspection.caseName)return null
  return validLiveOilPvt({schemaVersion:'eclipse-pvto-parameters/1',baselineRunId:1,sourceFile:c.sourceFile,sourceSha256:file.sha256,keywordLineNumber:c.keywordLineNumber,unit:'cP',multiplier:.5,field:'VISCOSITY',records:c.records})?c:null
}
export function buildLiveOilPvt({run,inspection,multiplier,field='VISCOSITY'}){
  if(run?.runType!=='eclipse'||run.status!=='SUCCEEDED'||run.resultContract!=='VALID_FULL'||run.parameters!=null||run.resultExpired)throw new Error('请选择当前模型版本、结果未过期的完整无参数E100基线。')
  const c=liveOilPvtRecord(inspection);if(!c)throw new Error('需要单DATA、单PVT区明确PVTO完整分支，最高溶解气油比须有欠饱和数据。')
  const p={schemaVersion:'eclipse-pvto-parameters/1',baselineRunId:run.id,sourceFile:c.sourceFile,sourceSha256:inspection.packageFiles[0].sha256,keywordLineNumber:c.keywordLineNumber,unit:field==='FORMATION_VOLUME_FACTOR'?oilVolumeUnit(inspection.unitSystem):'cP',multiplier,field,records:JSON.parse(JSON.stringify(c.records))}
  if(!validLiveOilPvt(p))throw new Error('乘数须为0.000001–100有限数且不为1；所有分支目标Bo须大于0、≤10并递减，黏度须大于0、≤1000cP。')
  return p
}
export const liveOilPvtResponse=(current,baseline)=>gridRegionResponse(current,baseline,['WBHP','WOPR','WWPR','WGPR','WLPR','WGOR','WOPT','WWPT','WGPT','WGIR','WGIT'])
