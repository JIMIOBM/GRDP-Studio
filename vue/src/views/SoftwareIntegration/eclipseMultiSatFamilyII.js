import {buildRelperm,validRelperm,validRelpermTables,relpermContext,relpermResponse} from './eclipseRelperm.js'
import {validMultiSatRegions} from './eclipseMultiSatRelperm.js'
const exact=(v,keys)=>v&&Object.keys(v).length===keys.length&&keys.every(k=>Object.hasOwn(v,k))
export const multiFamilyIILabels={WATER_KR:'水相 Krw · SWFN',GAS_KR:'气相 Krg · SGFN',OIL_KR:'油相 Krow/Krog · SOF3两列联动'}
const keyword=field=>({WATER_KR:'SWFN',GAS_KR:'SGFN',OIL_KR:'SOF3'})[field]
export function multiFamilyIIContext(inspection){
  const c=inspection?.multiSatRelpermContext,d=inspection?.dimensions,axes=[d?.nx,d?.ny,d?.nz],cells=axes.reduce((a,b)=>a*b,1)
  if(!axes.every(n=>Number.isSafeInteger(n)&&n>0)||!exact(c,['schemaVersion','sourceFile','regionCount','regions'])||c.schemaVersion!=='eclipse-multi-sat-relperm-context/2'||!validMultiSatRegions(c.regions,c.regionCount,cells,true))return null
  for(const r of c.regions)if(!relpermContext({...inspection,relpermContext:{schemaVersion:'eclipse-relperm-context/1',sourceFile:c.sourceFile,tables:r.tables}}))return null
  return c
}
export function validMultiFamilyII(p){
  if(!exact(p,['schemaVersion','baselineRunId','sourceFile','sourceSha256','unit','multiplier','field','tables','regionNumber'])||p.schemaVersion!=='eclipse-multi-sat-relperm-parameters/2'||!Number.isSafeInteger(p.regionNumber)||p.regionNumber<1||p.regionNumber>8||!Object.hasOwn(multiFamilyIILabels,p.field)||!validRelpermTables(p.tables))return false
  if(p.tables.some((t,i)=>i>0&&t.keywordLineNumber<=p.tables[i-1].rows.at(-1).lineNumber))return false
  const key=keyword(p.field),t=p.tables.find(t=>t.keyword===key),{regionNumber,field,tables,...single}=p
  return validRelperm({...single,schemaVersion:key==='SGFN'?'eclipse-relperm-parameters/2':'eclipse-relperm-parameters/1',keyword:key,keywordLineNumber:t.keywordLineNumber,rows:t.rows})
}
export function buildMultiFamilyII({run,inspection,regionNumber,field,multiplier}){
  const c=multiFamilyIIContext(inspection),region=c?.regions.find(r=>r.number===regionNumber)
  if(!region||!Object.hasOwn(multiFamilyIILabels,field))throw new Error('请选择当前模型的明确SAT区及三表相渗项目。')
  if(run?.resultExpired)throw new Error('基线结果已过期。')
  const single=buildRelperm({run,inspection:{...inspection,relpermContext:{schemaVersion:'eclipse-relperm-context/1',sourceFile:c.sourceFile,tables:region.tables}},keyword:keyword(field),multiplier})
  const {keyword:chosen,keywordLineNumber,rows,...rest}=single
  const p={...rest,schemaVersion:'eclipse-multi-sat-relperm-parameters/2',regionNumber,field,tables:region.tables.map(t=>({...t,rows:t.rows.map(r=>({...r,values:[...r.values]}))}))}
  if(!validMultiFamilyII(p))throw new Error('分区三表相渗方案无效，SOF3油相两列必须联动。')
  return p
}
export const multiFamilyIIResponse=relpermResponse
