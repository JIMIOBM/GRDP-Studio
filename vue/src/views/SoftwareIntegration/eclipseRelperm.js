import { gridRegionResponse } from './eclipseGridRegion.js'
const number=s=>typeof s==='string'&&s.length<=64&&/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eEdD][+-]?\d+)?$/.test(s)?Number(s.replace(/[dD]/,'e')):NaN
const keys=['schemaVersion','baselineRunId','sourceFile','sourceSha256','keyword','keywordLineNumber','unit','multiplier','rows']
export const capillaryPressureUnit=system=>system==='FIELD'?'psi':system==='METRIC'?'bar':['LAB','PVT-M'].includes(system)?'atm':''
export function validRelpermRows(keyword,rows,keywordLine){
  if(!['SWFN','SGFN','SOF3'].includes(keyword)||!Array.isArray(rows)||rows.length<2||rows.length>64)return false
  let line=keywordLine,saturation=-1,kr=-1,third=keyword==='SWFN'?Infinity:-Infinity
  for(const row of rows){
    if(!row||Object.keys(row).length!==2||!Object.hasOwn(row,'lineNumber')||!Object.hasOwn(row,'values')||!Number.isSafeInteger(row.lineNumber)||row.lineNumber<=line||row.lineNumber>2e6||!Array.isArray(row.values)||row.values.length!==3)return false
    const n=row.values.map(number)
    if(n.some(v=>!Number.isFinite(v))||n[0]<0||n[0]>1||n[0]<=saturation||n[1]<0||n[1]>1||n[1]<kr||keyword==='SOF3'&&(n[2]<0||n[2]>1)||keyword==='SWFN'&&n[2]>third||keyword!=='SWFN'&&n[2]<third)return false
    line=row.lineNumber;[saturation,kr,third]=n
  }
  if(number(rows[0].values[1])!==0||keyword==='SGFN'&&number(rows[0].values[0])!==0)return false
  return keyword!=='SOF3'||number(rows[0].values[2])===0&&Math.abs(number(rows.at(-1).values[1])-number(rows.at(-1).values[2]))<=1e-12
}
export function validRelpermTables(tables){
  if(!Array.isArray(tables)||tables.length!==3||new Set(tables.map(t=>t?.keyword)).size!==3)return false
  for(const t of tables)if(!t||Object.keys(t).length!==3||!['keyword','keywordLineNumber','rows'].every(k=>Object.hasOwn(t,k))||!Number.isSafeInteger(t.keywordLineNumber)||t.keywordLineNumber<=0||t.keywordLineNumber>2e6||!validRelpermRows(t.keyword,t.rows,t.keywordLineNumber))return false
  const water=tables.find(t=>t.keyword==='SWFN'),oil=tables.find(t=>t.keyword==='SOF3')
  return !!water&&!!oil&&Math.abs(number(water.rows[0].values[0])+number(oil.rows.at(-1).values[0])-1)<=1e-12
}
export function validRelperm(p){
  if(!p||Object.keys(p).length!==keys.length||!keys.every(k=>Object.hasOwn(p,k))||p.schemaVersion!=='eclipse-relperm-parameters/1'||!['SWFN','SOF3'].includes(p.keyword)||p.unit!=='1'
    ||!Number.isSafeInteger(p.baselineRunId)||p.baselineRunId<=0||!Number.isSafeInteger(p.keywordLineNumber)||p.keywordLineNumber<=0||p.keywordLineNumber>2e6
    ||typeof p.sourceFile!=='string'||!/^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.DATA$/i.test(p.sourceFile)||typeof p.sourceSha256!=='string'||!/^[a-f0-9]{64}$/.test(p.sourceSha256)
    ||typeof p.multiplier!=='number'||!Number.isFinite(p.multiplier)||p.multiplier<.000001||p.multiplier>100||p.multiplier===1||!validRelpermRows(p.keyword,p.rows,p.keywordLineNumber))return false
  let changed=false
  for(const row of p.rows)for(const column of p.keyword==='SWFN'?[1]:[1,2]){const kr=number(row.values[column]),target=kr*p.multiplier;if(!Number.isFinite(target)||target<0||target>1)return false;changed ||= target!==kr}
  return changed
}
export function relpermContext(inspection){
  const c=inspection?.relpermContext,file=inspection?.packageFiles?.[0]
  if(inspection?.schemaVersion!=='eclipse-data-inspection/4'||!capillaryPressureUnit(inspection.unitSystem)||inspection.phases?.length!==3||!['OIL','WATER','GAS'].every(p=>inspection.phases.includes(p))||inspection.packageFiles?.length!==1
    ||c?.schemaVersion!=='eclipse-relperm-context/1'||c.sourceFile!==file.relativePath||!validRelpermTables(c.tables)||typeof file.sha256!=='string'||!/^[a-f0-9]{64}$/.test(file.sha256))return null
  return c
}
export function buildRelperm({run,inspection,keyword,multiplier}){
  if(run?.runType!=='eclipse'||run.status!=='SUCCEEDED'||run.resultContract!=='VALID_FULL'||run.parameters!=null)throw new Error('请选择当前模型版本的完整无参数成功基线。')
  const c=relpermContext(inspection),t=c?.tables.find(t=>t.keyword===keyword)
  if(!t)throw new Error('需要单DATA三相模型、唯一明确SAT区及完整SWFN/SGFN/SOF3；多区、默认字段、滞后和端点缩放尚未开放。')
  const p={schemaVersion:'eclipse-relperm-parameters/1',baselineRunId:run.id,sourceFile:c.sourceFile,sourceSha256:inspection.packageFiles[0].sha256,keyword,keywordLineNumber:t.keywordLineNumber,unit:'1',multiplier,rows:t.rows.map(r=>({lineNumber:r.lineNumber,values:[...r.values]}))}
  if(!validRelperm(p))throw new Error('乘数须为0.000001–100有限数值且不为1；目标相渗必须在0–1之间且有变化。SOF3油相两列联动，保留相同末端值。')
  return p
}
export const relpermResponse=(current,baseline)=>gridRegionResponse(current,baseline,['WBHP','WOPR','WWPR','WLPR','WGPR','WOPT','WWPT','WGPT'])
