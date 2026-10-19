import {buildCoupledRelperm,validCoupledRelperm,validCoupledRelpermTables,coupledRelpermContext,coupledRelpermResponse} from './eclipseCoupledRelperm.js'
import {validRelpermTables} from './eclipseRelperm.js'
const exact=(v,keys)=>v&&Object.keys(v).length===keys.length&&keys.every(k=>Object.hasOwn(v,k))
export function validMultiSatRegions(regions,count,cells,familyII=false){
  if(!Number.isSafeInteger(count)||count<2||count>8||!Number.isSafeInteger(cells)||cells<1||cells>2e6||!Array.isArray(regions)||regions.length!==count)return false
  let total=0;const headers=new Map(),ends=new Map()
  for(const [i,r] of regions.entries()){
    if(!exact(r,['number','cellCount','tables'])||r.number!==i+1||!Number.isSafeInteger(r.cellCount)||r.cellCount<1||r.cellCount>2e6||!(familyII?validRelpermTables(r.tables):validCoupledRelpermTables(r.tables)))return false
    total+=r.cellCount
    let priorEnd=0
    for(const t of r.tables){if(t.keywordLineNumber<=priorEnd||headers.has(t.keyword)&&headers.get(t.keyword)!==t.keywordLineNumber||t.rows[0].lineNumber<=(ends.get(t.keyword)??t.keywordLineNumber))return false;priorEnd=t.rows.at(-1).lineNumber;headers.set(t.keyword,t.keywordLineNumber);ends.set(t.keyword,t.rows.at(-1).lineNumber)}
  }
  return total===cells
}
export function multiSatRelpermContext(inspection){
  const c=inspection?.multiSatRelpermContext,d=inspection?.dimensions,axes=[d?.nx,d?.ny,d?.nz],cells=axes.reduce((a,b)=>a*b,1)
  if(!axes.every(n=>Number.isSafeInteger(n)&&n>0)||!exact(c,['schemaVersion','sourceFile','regionCount','regions'])||c.schemaVersion!=='eclipse-multi-sat-relperm-context/1'||!validMultiSatRegions(c.regions,c.regionCount,cells))return null
  for(const r of c.regions)if(!coupledRelpermContext({...inspection,coupledRelpermContext:{schemaVersion:'eclipse-coupled-relperm-context/1',sourceFile:c.sourceFile,tables:r.tables}}))return null
  return c
}
export function validMultiSatRelperm(p){
  if(!exact(p,['schemaVersion','baselineRunId','sourceFile','sourceSha256','unit','multiplier','field','tables','regionNumber'])||p.schemaVersion!=='eclipse-multi-sat-relperm-parameters/1'||!Number.isSafeInteger(p.regionNumber)||p.regionNumber<1||p.regionNumber>8)return false
  const {regionNumber,...single}=p;return validCoupledRelperm({...single,schemaVersion:'eclipse-coupled-relperm-parameters/1'})
}
export function buildMultiSatRelperm({run,inspection,regionNumber,field,multiplier}){
  const c=multiSatRelpermContext(inspection),region=c?.regions.find(r=>r.number===regionNumber)
  if(!region)throw new Error('请选择当前模型的明确SAT区；区号、全格SATNUM和完整原表须一致。')
  const p={...buildCoupledRelperm({run,inspection:{...inspection,coupledRelpermContext:{schemaVersion:'eclipse-coupled-relperm-context/1',sourceFile:c.sourceFile,tables:region.tables}},field,multiplier}),schemaVersion:'eclipse-multi-sat-relperm-parameters/1',regionNumber}
  if(!validMultiSatRelperm(p))throw new Error('分区相渗方案无效。')
  return p
}
export const multiSatRelpermResponse=coupledRelpermResponse
