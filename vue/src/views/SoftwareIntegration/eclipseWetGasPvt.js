import { gasPressureUnit, gasVolumeUnit } from './eclipseGasPvt.js'
import { gridRegionResponse } from './eclipseGridRegion.js'
export { gasPressureUnit, gasVolumeUnit }
const number=s=>typeof s==='string'&&s.length<=64&&/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eEdD][+-]?\d+)?$/.test(s)?Number(s.replace(/[dD]/,'e')):NaN
const exact=(value,keys)=>!!value&&typeof value==='object'&&!Array.isArray(value)&&Object.keys(value).length===keys.length&&keys.every(key=>Object.hasOwn(value,key))
export const wetGasPvtLabel=p=>p?.field==='FORMATION_VOLUME_FACTOR'?'湿气体积系数 Bg':'湿气黏度'
export const wetGasRvUnit=system=>system==='FIELD'?'stb/Mscf':system==='LAB'?'scc/scc':['METRIC','PVT-M'].includes(system)?'sm3/sm3':''
export function validWetGasRecords(records,keywordLine){
  if(!Array.isArray(records)||records.length<2||records.length>32||!Array.isArray(records.at(-1)?.rows)||records.at(-1).rows.length<2)return false
  let line=keywordLine,pressure=-1,total=0
  for(const record of records){
    if(!exact(record,['rows'])||!Array.isArray(record.rows)||record.rows.length<1||record.rows.length>64||(total+=record.rows.length)>256)return false
    let rv=Infinity
    for(const [index,row] of record.rows.entries()){
      const first=index===0,start=first?1:0
      if(!exact(row,['lineNumber','values'])||!Number.isSafeInteger(row.lineNumber)||row.lineNumber<=line||row.lineNumber>2e6||!Array.isArray(row.values)||row.values.length!==(first?4:3))return false
      const n=row.values.map(number);if(n.some(v=>!Number.isFinite(v)))return false
      if(first){if(n[0]<0||n[0]<=pressure)return false;pressure=n[0]}
      if(n[start]<0||n[start]>=rv||n[start+1]<=0||n[start+1]>100000||n[start+2]<=0||n[start+2]>1000)return false
      line=row.lineNumber;rv=n[start]
    }
  }return true
}
export function validWetGasPvt(p){
  if(!exact(p,['schemaVersion','baselineRunId','sourceFile','sourceSha256','keywordLineNumber','unit','multiplier','records','field'])||p.schemaVersion!=='eclipse-pvtg-parameters/1'
    ||!['VISCOSITY','FORMATION_VOLUME_FACTOR'].includes(p.field)||!Number.isSafeInteger(p.baselineRunId)||p.baselineRunId<=0||!Number.isSafeInteger(p.keywordLineNumber)||p.keywordLineNumber<=0||p.keywordLineNumber>2e6
    ||typeof p.sourceFile!=='string'||!/^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.DATA$/i.test(p.sourceFile)||typeof p.sourceSha256!=='string'||!/^[a-f0-9]{64}$/.test(p.sourceSha256)
    ||!(p.field==='VISCOSITY'?p.unit==='cP':['rb/Mscf','rm3/sm3','rcc/scc'].includes(p.unit))||typeof p.multiplier!=='number'||!Number.isFinite(p.multiplier)||p.multiplier<.000001||p.multiplier>100||p.multiplier===1||!validWetGasRecords(p.records,p.keywordLineNumber))return false
  const volume=p.field==='FORMATION_VOLUME_FACTOR'
  return p.records.every(record=>record.rows.every(row=>{const target=number(row.values.at(volume?-2:-1))*p.multiplier;return Number.isFinite(target)&&target>0&&target<=(volume?100000:1000)}))
}
export function wetGasPvtRecord(inspection){
  const c=inspection?.wetGasPvtContext,file=inspection?.packageFiles?.[0]
  if(inspection?.schemaVersion!=='eclipse-data-inspection/4'||!['METRIC','FIELD','LAB','PVT-M'].includes(inspection.unitSystem)||!inspection.phases?.includes('OIL')||!inspection.phases?.includes('GAS')||inspection.packageFiles?.length!==1
    ||!exact(c,['schemaVersion','sourceFile','keywordLineNumber','records'])||c.schemaVersion!=='eclipse-wet-gas-pvt-context/1'||c.sourceFile!==file.relativePath||c.sourceFile!==inspection.caseName)return null
  return validWetGasPvt({schemaVersion:'eclipse-pvtg-parameters/1',baselineRunId:1,sourceFile:c.sourceFile,sourceSha256:file.sha256,keywordLineNumber:c.keywordLineNumber,unit:'cP',multiplier:.5,field:'VISCOSITY',records:c.records})?c:null
}
export function buildWetGasPvt({run,inspection,multiplier,field='VISCOSITY'}){
  if(run?.runType!=='eclipse'||run.status!=='SUCCEEDED'||run.resultContract!=='VALID_FULL'||run.parameters!=null||run.resultExpired)throw new Error('请选择当前模型版本、结果未过期的完整无参数E100基线。')
  const c=wetGasPvtRecord(inspection);if(!c)throw new Error('需要单DATA、单PVT区明确PVTG完整分支，最高压力须有欠饱和数据。')
  const p={schemaVersion:'eclipse-pvtg-parameters/1',baselineRunId:run.id,sourceFile:c.sourceFile,sourceSha256:inspection.packageFiles[0].sha256,keywordLineNumber:c.keywordLineNumber,unit:field==='FORMATION_VOLUME_FACTOR'?gasVolumeUnit(inspection.unitSystem):'cP',multiplier,field,records:JSON.parse(JSON.stringify(c.records))}
  if(!validWetGasPvt(p))throw new Error('乘数须为0.000001–100有限数且不为1；所有分支目标Bg须大于0、≤100000，黏度须大于0、≤1000cP。')
  return p
}
export const wetGasPvtResponse=(current,baseline)=>gridRegionResponse(current,baseline,['WBHP','WOPR','WWPR','WGPR','WLPR','WGOR','WOPT','WWPT','WGPT','WGIR','WGIT'])
