import { newCompletionResponse } from './eclipseNewCompletion.js'
const names=['schemaVersion','baselineRunId','sourceFile','sourceSha256','lineNumber','field','unit','originalValue','targetValue','recordValues']
const number=s=>typeof s==='string'&&/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eEdD][+-]?\d+)?$/.test(s)?Number(s.replace(/[dD]/,'e')):NaN
export const pvtwLabels={REFERENCE_PRESSURE:'参考压力',FORMATION_VOLUME_FACTOR:'水体积系数',COMPRESSIBILITY:'水压缩性',VISCOSITY:'参考水黏度',VISCOSIBILITY:'黏压系数'}
export const pvtwIndex=field=>Object.keys(pvtwLabels).indexOf(field)
export function pvtwUnit(system,field){
  if(!['METRIC','FIELD','LAB','PVT-M'].includes(system)||pvtwIndex(field)<0)return ''
  if(field==='VISCOSITY')return 'cP'
  if(field==='FORMATION_VOLUME_FACTOR')return system==='FIELD'?'rb/stb':system==='LAB'?'rcc/scc':'rm3/sm3'
  if(field==='REFERENCE_PRESSURE')return system==='FIELD'?'psia':['LAB','PVT-M'].includes(system)?'atma':'barsa'
  return system==='FIELD'?'1/psi':['LAB','PVT-M'].includes(system)?'1/atm':'1/bar'
}
const scalar=(field,unit,n)=>typeof n==='number'&&Number.isFinite(n)&&(field==='REFERENCE_PRESSURE'?n>=0&&n*(unit==='psia'?.0689475729:unit==='atma'?1.01325:1)<=20000
  :field==='FORMATION_VOLUME_FACTOR'?n>0&&n<=10:field==='VISCOSITY'?n>0&&n<=1000:['COMPRESSIBILITY','VISCOSIBILITY'].includes(field)&&n>=0&&n*(unit==='1/psi'?14.5037738:unit==='1/atm'?1/1.01325:1)<=.001)
export const validPvtw=p=>!!p&&Object.keys(p).length===names.length&&names.every(k=>Object.hasOwn(p,k))&&['eclipse-pvtw-parameters/1','eclipse-pvtw-parameters/2'].includes(p.schemaVersion)
  &&(p.schemaVersion!=='eclipse-pvtw-parameters/1'||p.field==='VISCOSITY')&&pvtwIndex(p.field)>=0
  &&Number.isSafeInteger(p.baselineRunId)&&p.baselineRunId>0&&Number.isSafeInteger(p.lineNumber)&&p.lineNumber>0&&p.lineNumber<=2e6
  &&typeof p.sourceFile==='string'&&/^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.DATA$/i.test(p.sourceFile)&&typeof p.sourceSha256==='string'&&/^[a-f0-9]{64}$/.test(p.sourceSha256)
  &&['METRIC','FIELD','LAB','PVT-M'].some(s=>p.unit===pvtwUnit(s,p.field))&&[p.originalValue,p.targetValue].every(n=>scalar(p.field,p.unit,n))&&p.originalValue!==p.targetValue
  &&Array.isArray(p.recordValues)&&p.recordValues.length===5&&p.recordValues.every((s,i)=>typeof s==='string'&&s.length<=64&&Number.isFinite(number(s))&&([1,3].includes(i)?number(s)>0:number(s)>=0))&&number(p.recordValues[pvtwIndex(p.field)])===p.originalValue
export function pvtwRecord(inspection) {
  const c=inspection?.pvtwContext,file=inspection?.packageFiles?.[0]
  if(inspection?.schemaVersion!=='eclipse-data-inspection/4'||!['METRIC','FIELD','LAB','PVT-M'].includes(inspection.unitSystem)||inspection.phases?.length!==1||inspection.phases[0]!=='WATER'
    ||inspection.packageFiles?.length!==1||c?.schemaVersion!=='eclipse-pvtw-context/1'||c.sourceFile!==file.relativePath)return null
  const originalValue=number(c.recordValues?.[3])
  return validPvtw({schemaVersion:'eclipse-pvtw-parameters/1',baselineRunId:1,sourceFile:c.sourceFile,sourceSha256:file.sha256,lineNumber:c.lineNumber,field:'VISCOSITY',unit:'cP',originalValue,targetValue:originalValue===1?2:1,recordValues:c.recordValues})?c:null
}
export function buildPvtw({run,inspection,targetValue,field='VISCOSITY'}) {
  if(run?.runType!=='eclipse'||run.status!=='SUCCEEDED'||run.resultContract!=='VALID_FULL'||run.parameters!=null)throw new Error('请选择当前模型版本的完整无参数成功基线。')
  const c=pvtwRecord(inspection);if(!c)throw new Error('需要单DATA纯水、唯一明确五项PVTW；多区、默认字段及覆盖工程尚未开放。')
  const p={schemaVersion:field==='VISCOSITY'?'eclipse-pvtw-parameters/1':'eclipse-pvtw-parameters/2',baselineRunId:run.id,sourceFile:c.sourceFile,sourceSha256:inspection.packageFiles[0].sha256,lineNumber:c.lineNumber,field,unit:pvtwUnit(inspection.unitSystem,field),originalValue:number(c.recordValues[pvtwIndex(field)]),targetValue,recordValues:[...c.recordValues]}
  if(!validPvtw(p))throw new Error('目标须为有限数值且不同于原值；黏度大于0且≤1000cP，体积系数大于0且≤10，参考压力0–20000bar等值，压缩/黏压系数0–0.001 1/bar等值；使用原单位。')
  return p
}
export const pvtwResponse=newCompletionResponse
