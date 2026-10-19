import { validPvtw, pvtwLabels, pvtwIndex, pvtwUnit, validPvtwScalar } from './eclipsePvtw.js'
import { multiPvtContext, multiPvtResponse } from './eclipseMultiPvt.js'
const exact=(v,keys)=>v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).length===keys.length&&keys.every(k=>Object.hasOwn(v,k))
const numeric=s=>typeof s==='string'&&/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eEdD][+-]?\d+)?$/.test(s)?Number(s.replace(/[dD]/,'e')):NaN
export const multiWaterPvtSchema='eclipse-multi-pvtw-parameters/1'
export const multiWaterPvtSchema2='eclipse-multi-pvtw-parameters/2'
export const multiWaterPvtSchema3='eclipse-multi-pvtw-parameters/3'
const hasStar=values=>Array.isArray(values)&&values.length===5&&values[4]==='1*'
const effective=values=>Array.isArray(values)&&values.length===4?[...values,'0']:hasStar(values)?[...values.slice(0,4),'0']:values
export const waterPvtValue=(values,field)=>numeric(effective(values)?.[pvtwIndex(field)])
export function multiWaterPvtContext(inspection){
  const c=inspection?.multiWaterPvtContext,fluid=multiPvtContext(inspection),d=inspection?.dimensions,axes=[d?.nx,d?.ny,d?.nz],cells=axes.reduce((a,b)=>a*b,1)
  const pure=inspection?.schemaVersion==='eclipse-data-inspection/4'&&inspection.phases?.length===1&&inspection.phases[0]==='WATER'&&!Object.hasOwn(inspection,'multiPvtContext')&&['FIELD','METRIC','LAB','PVT-M'].includes(inspection.unitSystem)&&inspection.packageFiles?.length===1&&axes.every(n=>Number.isSafeInteger(n)&&n>0)&&Number.isSafeInteger(cells)&&cells<=2e6
  const parent=fluid||(pure?{sourceFile:inspection.caseName,regionCount:c?.regionCount,regions:c?.regions}:null)
  if(!parent||!exact(c,['schemaVersion','sourceFile','regionCount','regions'])||!['eclipse-multi-water-pvt-context/1','eclipse-multi-water-pvt-context/2','eclipse-multi-water-pvt-context/3'].includes(c.schemaVersion)||c.sourceFile!==parent.sourceFile||c.sourceFile!==inspection.packageFiles[0].relativePath||!Number.isSafeInteger(c.regionCount)||c.regionCount<2||c.regionCount>8||c.regionCount!==parent.regionCount||!Array.isArray(c.regions)||c.regions.length!==c.regionCount)return null
  const occupied=new Set()
  for(const key of ['multiPvtContext','multiSatRelpermContext'])for(const r of inspection[key]?.regions||[])for(const t of r.tables||[]){occupied.add(t.keywordLineNumber);for(const row of t.rows||[])occupied.add(row.lineNumber)}
  let prior=0,total=0,missing=false,star=false;const allowsMissing=c.schemaVersion!=='eclipse-multi-water-pvt-context/1',allowsStar=c.schemaVersion==='eclipse-multi-water-pvt-context/3'
  for(const [i,r] of c.regions.entries()){
    if(!exact(r,['number','cellCount','lineNumber','recordValues'])||r.number!==i+1||!Number.isSafeInteger(r.cellCount)||r.cellCount<1||(fluid&&(r.number!==parent.regions[i].number||r.cellCount!==parent.regions[i].cellCount))||!Number.isSafeInteger(r.lineNumber)||r.lineNumber<=prior||r.lineNumber>2e6||occupied.has(r.lineNumber)||!Array.isArray(r.recordValues)||!(r.recordValues.length===5||(allowsMissing&&r.recordValues.length===4))||(hasStar(r.recordValues)&&!allowsStar)||!effective(r.recordValues).every((s,k)=>typeof s==='string'&&s.length<=64&&validPvtwScalar(Object.keys(pvtwLabels)[k],pvtwUnit(inspection.unitSystem,Object.keys(pvtwLabels)[k]),numeric(s))))return null
    missing||=r.recordValues.length===4;star||=hasStar(r.recordValues);total+=r.cellCount;if(total>cells)return null;prior=r.lineNumber
  }return total===cells&&c.schemaVersion===(star?'eclipse-multi-water-pvt-context/3':missing?'eclipse-multi-water-pvt-context/2':'eclipse-multi-water-pvt-context/1')?c:null
}
export function validMultiWaterPvt(p){
  if(!exact(p,['schemaVersion','regionNumber','baselineRunId','sourceFile','sourceSha256','lineNumber','field','unit','originalValue','targetValue','recordValues'])||![multiWaterPvtSchema,multiWaterPvtSchema2,multiWaterPvtSchema3].includes(p.schemaVersion)||!(p.recordValues?.length===5||(p.schemaVersion!==multiWaterPvtSchema&&p.recordValues?.length===4))||(hasStar(p.recordValues)&&p.schemaVersion!==multiWaterPvtSchema3)||!Number.isSafeInteger(p.regionNumber)||p.regionNumber<1||p.regionNumber>8)return false
  const {regionNumber,...single}=p
  return validPvtw({...single,schemaVersion:'eclipse-pvtw-parameters/2',recordValues:effective(p.recordValues)})
}
export function buildMultiWaterPvt({run,inspection,regionNumber,field,targetValue}){
  if(run?.runType!=='eclipse'||run.status!=='SUCCEEDED'||run.resultContract!=='VALID_FULL'||run.parameters!=null||run.resultExpired)throw new Error('请选择当前模型版本、结果未过期的完整无参数成功基线。')
  const c=multiWaterPvtContext(inspection),r=c?.regions.find(r=>r.number===regionNumber)
  if(!r)throw new Error('请选择明确PVT区；每区须有明确前四项水物性记录，第五项可省略或明确写1*并按官方默认0解析。')
  const p={schemaVersion:c.schemaVersion==='eclipse-multi-water-pvt-context/3'?multiWaterPvtSchema3:c.schemaVersion==='eclipse-multi-water-pvt-context/2'?multiWaterPvtSchema2:multiWaterPvtSchema,regionNumber,baselineRunId:run.id,sourceFile:c.sourceFile,sourceSha256:inspection.packageFiles[0].sha256,lineNumber:r.lineNumber,field,unit:pvtwUnit(inspection.unitSystem,field),originalValue:waterPvtValue(r.recordValues,field),targetValue,recordValues:[...r.recordValues]}
  if(!validMultiWaterPvt(p))throw new Error('请输入不同于原值的有效新值，使用模型原单位；水体积系数和黏度须大于0，参考压力、压缩性和黏压系数须非负，且不能超出支持范围。')
  return p
}
export const multiWaterPvtResponse=multiPvtResponse
