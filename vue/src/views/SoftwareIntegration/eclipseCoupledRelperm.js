import { gridRegionResponse } from './eclipseGridRegion.js'
import { capillaryPressureUnit } from './eclipseRelperm.js'
const number=s=>typeof s==='string'&&s.length<=64&&/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eEdD][+-]?\d+)?$/.test(s)?Number(s.replace(/[dD]/,'e')):NaN
const exact=(v,keys)=>v&&Object.keys(v).length===keys.length&&keys.every(k=>Object.hasOwn(v,k))
export const coupledRelpermLabels={WATER_KR:'水相 Krw · SWOF',GAS_KR:'气相 Krg · SGOF',OIL_KR:'油相 Krow/Krog · 两表联动'}
export function validCoupledRelpermTables(tables){
  if(!Array.isArray(tables)||tables.length!==2||new Set(tables.map(t=>t?.keyword)).size!==2)return false
  let last=0
  for(const t of tables){
    if(!exact(t,['keyword','keywordLineNumber','rows'])||!['SWOF','SGOF'].includes(t.keyword)||!Number.isSafeInteger(t.keywordLineNumber)||t.keywordLineNumber<=last||t.keywordLineNumber>2e6||!Array.isArray(t.rows)||t.rows.length<2||t.rows.length>64)return false
    let line=t.keywordLineNumber,sat=-1,kr=-1,oil=Infinity,pc=t.keyword==='SWOF'?Infinity:-Infinity
    for(const row of t.rows){
      if(!exact(row,['lineNumber','values'])||!Number.isSafeInteger(row.lineNumber)||row.lineNumber<=line||row.lineNumber>2e6||!Array.isArray(row.values)||row.values.length!==4)return false
      const n=row.values.map(number)
      if(n.some(v=>!Number.isFinite(v))||n[0]<0||n[0]>1||n[0]<=sat||n[1]<0||n[1]>1||n[1]<kr||n[2]<0||n[2]>1||n[2]>oil||t.keyword==='SWOF'&&n[3]>pc||t.keyword==='SGOF'&&n[3]<pc)return false
      ;[sat,kr,oil,pc]=n;line=row.lineNumber
    }
    if(number(t.rows[0].values[1])!==0||number(t.rows.at(-1).values[2])!==0||t.keyword==='SGOF'&&number(t.rows[0].values[0])!==0)return false
    last=line
  }
  return Math.abs(number(tables.find(t=>t.keyword==='SWOF').rows[0].values[2])-number(tables.find(t=>t.keyword==='SGOF').rows[0].values[2]))<=1e-12
}
export function validCoupledRelperm(p){
  if(!exact(p,['schemaVersion','baselineRunId','sourceFile','sourceSha256','unit','multiplier','field','tables'])||p.schemaVersion!=='eclipse-coupled-relperm-parameters/1'||p.unit!=='1'||!Object.hasOwn(coupledRelpermLabels,p.field)
    ||!Number.isSafeInteger(p.baselineRunId)||p.baselineRunId<=0||typeof p.sourceFile!=='string'||!/^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.DATA$/i.test(p.sourceFile)||typeof p.sourceSha256!=='string'||!/^[a-f0-9]{64}$/.test(p.sourceSha256)
    ||typeof p.multiplier!=='number'||!Number.isFinite(p.multiplier)||p.multiplier<.000001||p.multiplier>100||p.multiplier===1||!validCoupledRelpermTables(p.tables))return false
  let changed=false
  for(const t of p.tables)if(p.field==='OIL_KR'||t.keyword===(p.field==='WATER_KR'?'SWOF':'SGOF'))for(const r of t.rows){const kr=number(r.values[p.field==='OIL_KR'?2:1]),target=kr*p.multiplier;if(!Number.isFinite(target)||target<0||target>1)return false;changed ||= target!==kr}
  return changed
}
export function coupledRelpermContext(inspection){
  const c=inspection?.coupledRelpermContext,file=inspection?.packageFiles?.[0]
  if(inspection?.schemaVersion!=='eclipse-data-inspection/4'||!capillaryPressureUnit(inspection.unitSystem)||inspection.phases?.length!==3||!['OIL','GAS','WATER'].every(p=>inspection.phases.includes(p))||inspection.packageFiles?.length!==1
    ||!exact(c,['schemaVersion','sourceFile','tables'])||c.schemaVersion!=='eclipse-coupled-relperm-context/1'||c.sourceFile!==file.relativePath||!validCoupledRelpermTables(c.tables)||typeof file.sha256!=='string'||!/^[a-f0-9]{64}$/.test(file.sha256))return null
  return c
}
export function buildCoupledRelperm({run,inspection,field,multiplier}){
  if(run?.runType!=='eclipse'||run.status!=='SUCCEEDED'||run.resultContract!=='VALID_FULL'||run.parameters!=null||run.resultExpired)throw new Error('请选择结果未过期的完整无参数成功基线。')
  const c=coupledRelpermContext(inspection);if(!c)throw new Error('需要单DATA三相、单SAT区明确SWOF/SGOF原表。')
  const p={schemaVersion:'eclipse-coupled-relperm-parameters/1',baselineRunId:run.id,sourceFile:c.sourceFile,sourceSha256:inspection.packageFiles[0].sha256,unit:'1',multiplier,field,tables:c.tables.map(t=>({...t,rows:t.rows.map(r=>({...r,values:[...r.values]}))}))}
  if(!validCoupledRelperm(p))throw new Error('乘数须为0.000001–100有限数且不为1，目标相渗须在0–1内。油相两张表必须同时调整，保持首端值一致。')
  return p
}
export const coupledRelpermResponse=(current,baseline)=>gridRegionResponse(current,baseline,['WBHP','WOPR','WWPR','WGPR','WLPR','WOPT','WWPT','WGPT','WWIR','WWIT','WGIR','WGIT'])
