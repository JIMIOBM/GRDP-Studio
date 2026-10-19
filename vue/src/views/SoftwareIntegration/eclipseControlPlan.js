import { validPackageControlPlan, packageControlPlanRecords, packageControlPlanMatches, buildPackageControlPlan } from './eclipsePackageControlPlan.js'
const fields=['well','sourceFile','lineNumber','date','timeDays','unit','originalOilRate','targetOilRate']
const recordFields=['well','sourceFile','lineNumber','date','timeDays','unit','originalOilRate','bhpLimit']
const shape=(o,keys)=>o && Object.keys(o).length===keys.length && keys.every(k=>Object.hasOwn(o,k))
const name=s=>typeof s==='string' && /^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,7}$/.test(s)
const source=s=>typeof s==='string' && /^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.DATA$/i.test(s)
const positive=n=>typeof n==='number' && Number.isFinite(n) && n>0 && n<=100000000
const day=s=>{
 if(typeof s!=='string' || !/^\d{4}-\d{2}-\d{2}$/.test(s))return null
 const d=new Date(`${s}T00:00:00Z`)
 return Number.isFinite(d.getTime()) && d.toISOString().slice(0,10)===s?d.getTime()/86400000:null
}
export const controlPlanKey=c=>`${c.sourceFile}:${c.lineNumber}:${c.well}`
export function validControlPlan(p) {
 if(p?.schemaVersion==='eclipse-control-plan-parameters/2')return validPackageControlPlan(p)
 if(!shape(p,['schemaVersion','baselineRunId','edits']) || p.schemaVersion!=='eclipse-control-plan-parameters/1' || !Number.isSafeInteger(p.baselineRunId) || p.baselineRunId<=0 || !Array.isArray(p.edits) || !p.edits.length || p.edits.length>24)return false
 let previous=0,file=null;const seen=new Set()
 for(const e of p.edits){
  if(!shape(e,fields) || !name(e.well) || !source(e.sourceFile) || !Number.isSafeInteger(e.lineNumber) || e.lineNumber<=previous || day(e.date)===null || !Number.isSafeInteger(e.timeDays) || e.timeDays<0
   || !['STB/day','sm3/day'].includes(e.unit) || !positive(e.originalOilRate) || !positive(e.targetOilRate) || e.originalOilRate===e.targetOilRate || seen.has(`${e.well}:${e.date}`) || file!==null && file!==e.sourceFile)return false
  seen.add(`${e.well}:${e.date}`);previous=e.lineNumber;file=e.sourceFile
 }
 return true
}
function expanded(values) {
 if(!Array.isArray(values) || values.some(v=>typeof v!=='string'))return null
 const result=[]
 for(let v of values){let count=1;const r=/^(\d+)\*(.*)$/.exec(v);if(r){count=Number(r[1]);v=r[2]}
  if(!Number.isSafeInteger(count) || count<1 || count>128 || result.length+count>128)return null
  for(let n=0;n<count;n++)result.push(v)
 }
 return result
}
export function controlPlanRecords(i) {
 if(i && Object.hasOwn(i,'packageProductionControlPlanContext'))return packageControlPlanRecords(i)
 if(i?.schemaVersion!=='eclipse-data-inspection/13' || !['FIELD','METRIC'].includes(i.unitSystem) || !source(i.caseName) || i.packageFiles?.length!==1 || i.packageFiles[0].relativePath!==i.caseName
  || !shape(i.productionControlPlan,['startDate','endTimeDays','records']) || !Array.isArray(i.scheduleTimeline) || i.scheduleTimeline.length<2)return []
 const plan=i.productionControlPlan,start=day(plan.startDate),records=plan.records,metadata=i.scheduleMetadata,unit=i.unitSystem==='FIELD'?'STB/day':'sm3/day'
 if(start===null || !Number.isSafeInteger(plan.endTimeDays) || plan.endTimeDays<=0 || !Array.isArray(records) || !records.length || records.length>1000
  || !Array.isArray(metadata?.wells) || !Array.isArray(metadata.records) || !Array.isArray(metadata.completions) || !Array.isArray(metadata.groups) || metadata.groups.length)return []
 const reports=[];let previous=start,previousLine=0
 const months=['JAN','FEB','MAR','APR','MAY','JUN','JUL','AUG','SEP','OCT','NOV','DEC']
 for(const e of i.scheduleTimeline){
  if(e.kind!=='DATES' || e.sourceFile!==i.caseName || !Number.isSafeInteger(e.lineNumber) || e.lineNumber<=previousLine || !Array.isArray(e.records) || !e.records.length)return []
  for(const r of e.records){
   const month=months.indexOf(r.month)+1
   if(!month || !/^\d{4}$/.test(r.year) || !/^(?:[1-9]|[12][0-9]|3[01])$/.test(r.day) || !(r.time===null || r.time==='00:00:00'))return []
   const d=day(`${r.year}-${String(month).padStart(2,'0')}-${String(r.day).padStart(2,'0')}`)
   if(d===null || d<=previous)return [];previous=d
  }
  previousLine=e.lineNumber;reports.push({line:e.lineNumber,date:previous})
 }
 if(previous-start!==plan.endTimeDays)return []
 if(metadata.records.filter(r=>r.keyword==='WCONPROD').length!==records.length || metadata.records.some(r=>!['WCONPROD','WCONINJE','COMPDAT'].includes(r.keyword) || r.sourceFile!==i.caseName || r.keyword!=='WCONPROD' && r.lineNumber>=reports[0].line))return []
 const seen=new Set(),initial=new Set(),phases=new Set();previousLine=0
 for(const c of records){
  if(!shape(c,recordFields) || !name(c.well) || c.sourceFile!==i.caseName || !Number.isSafeInteger(c.lineNumber) || c.lineNumber<=previousLine || day(c.date)===null
   || !Number.isSafeInteger(c.timeDays) || c.timeDays<0 || c.timeDays>=plan.endTimeDays || c.unit!==unit || !positive(c.originalOilRate) || !positive(c.bhpLimit) || seen.has(`${c.well}:${c.timeDays}`))return []
  const expected=reports.filter(r=>r.line<c.lineNumber).at(-1)?.date??start
  if(day(c.date)!==expected || expected-start!==c.timeDays || c.timeDays>0 && !initial.has(c.well))return []
  if(c.timeDays===0)initial.add(c.well);else phases.add(c.timeDays)
  if(metadata.wells.filter(w=>w.name===c.well).length!==1 || metadata.records.some(r=>r.keyword==='WCONINJE' && r.values?.[0]===c.well))return []
  const perfs=metadata.completions.filter(p=>p.well===c.well)
  if(!perfs.length || perfs.some(p=>p.status!=='OPEN' || p.lineNumber>=reports[0].line))return []
  const matches=metadata.records.filter(r=>r.keyword==='WCONPROD' && r.lineNumber===c.lineNumber)
  if(matches.length!==1)return []
  const v=expanded(matches[0].values)
  if(!v || v.length<9 || v.length>12 || v[0]!==c.well || v[1]!=='OPEN' || v[2]!=='ORAT' || Number(v[3])!==c.originalOilRate || v.slice(4,8).some(s=>s!=='') || Number(v[8])!==c.bhpLimit || v.slice(9).some(s=>s!=='' && s!=='0'))return []
  seen.add(`${c.well}:${c.timeDays}`);previousLine=c.lineNumber
 }
 return phases.size>=2 && initial.size?records:[]
}
export function controlPlanPeriods(i) {
 const records=controlPlanRecords(i)
 return records.map(c=>{
  const next=records.find(r=>r.well===c.well && r.timeDays>c.timeDays)
  return {...c,nextTimeDays:next?.timeDays??(i.packageProductionControlPlanContext?.endTimeDays??i.productionControlPlan?.endTimeDays),nextDate:next?.date??null}
 })
}
export function buildControlPlan({inspection,baselineRunId,edits}) {
 if(inspection && Object.hasOwn(inspection,'packageProductionControlPlanContext'))return buildPackageControlPlan({inspection,baselineRunId,edits})
 const records=controlPlanRecords(inspection)
 if(!records.length || !Array.isArray(edits) || edits.some(e=>!positive(e.targetOilRate)))return null
 const selected=edits.filter(e=>e.targetOilRate!==e.originalOilRate)
 const p={schemaVersion:'eclipse-control-plan-parameters/1',baselineRunId,edits:selected.map(e=>Object.fromEntries(fields.map(k=>[k,e[k]])))}
 return matches(p,inspection)?p:null
}
function matches(p,i) {
 if(p?.schemaVersion==='eclipse-control-plan-parameters/2')return packageControlPlanMatches(p,i)
 const records=controlPlanRecords(i)
 return validControlPlan(p) && records.length>0 && p.edits.every(e=>records.filter(c=>fields.filter(k=>k!=='targetOilRate').every(k=>c[k]===e[k])).length===1)
}
function curve(s,keyword,well,unit) {
 const rows=s?.series?.filter(c=>c.keyword===keyword && c.objectName===well)
 if(rows?.length!==1 || typeof rows[0].unit!=='string' || rows[0].unit.trim().toUpperCase()!==unit.toUpperCase() || !rows[0].points?.length)return null
 const p=rows[0].points
 if(p.some(p=>!Number.isFinite(p.timeDays) || p.timeDays<0 || !Number.isFinite(p.value)) || new Set(p.map(p=>p.timeDays)).size!==p.length)return null
 return new Map(p.map(p=>[p.timeDays,p.value]))
}
export function controlPlanResponse(summary,baseline,p,inspection) {
 const empty={available:false,consistent:false,verified:false,constrained:false,rows:[],edits:[]}
 if(!matches(p,inspection))return empty
 const records=controlPlanPeriods(inspection),wells=[...new Set(p.edits.map(e=>e.well))],flowUnit=inspection.unitSystem==='FIELD'?'STB/day':'sm3/day',pressureUnit=inspection.unitSystem==='FIELD'?'psia':'barsa'
 const tol=n=>Math.max(.0001,Math.abs(n)*1e-6),ptol=n=>Math.max(.1,Math.abs(n)*1e-6),selected=new Map(p.edits.map(e=>[controlPlanKey(e),e]))
 function data(s){
  const curves=wells.map(well=>({well,rate:curve(s,'WOPR',well,flowUnit),pressure:curve(s,'WBHP',well,pressureUnit)})),maps=curves.flatMap(c=>[c.rate,c.pressure])
  if(maps.some(m=>!m))return null
  const times=[...maps[0].keys()].filter(t=>t>0).sort((a,b)=>a-b)
  if(times.at(-1)!==(inspection.packageProductionControlPlanContext?.endTimeDays??inspection.productionControlPlan?.endTimeDays) || maps.some(m=>[...m.keys()].filter(t=>t>0).length!==times.length || times.some(t=>!m.has(t))))return null
  return curves.flatMap(c=>times.map(timeDays=>{
   const record=records.filter(r=>r.well===c.well && r.timeDays<timeDays).at(-1)
   if(!record || timeDays>record.nextTimeDays)return null
   return {record,timeDays,well:c.well,rate:c.rate.get(timeDays),bhp:c.pressure.get(timeDays)}
  }))
 }
 const current=data(summary),old=data(baseline)
 if(!current || !old || current.some(r=>!r) || old.some(r=>!r))return empty
 const oldMap=new Map(old.map(r=>[`${r.well}:${r.timeDays}`,r]))
 const valid=r=>r.rate>=0 && r.bhp>=r.record.bhpLimit-ptol(r.record.bhpLimit)
 const consistent=old.every(r=>valid(r) && r.rate<=r.record.originalOilRate+tol(r.record.originalOilRate)) && current.every(r=>valid(r) && r.rate<=(selected.get(controlPlanKey(r.record))?.targetOilRate??r.record.originalOilRate)+tol(selected.get(controlPlanKey(r.record))?.targetOilRate??r.record.originalOilRate))
 const rows=current.map(r=>{
  const e=selected.get(controlPlanKey(r.record)),b=oldMap.get(`${r.well}:${r.timeDays}`),target=e?.targetOilRate??r.record.originalOilRate
  return {timeDays:r.timeDays,well:r.well,date:r.record.date,nextDate:r.record.nextDate,originalOilRate:r.record.originalOilRate,target,rate:r.rate,baselineRate:b?.rate??null,bhp:r.bhp,bhpLimit:r.record.bhpLimit,
   modified:!!e,recordKey:controlPlanKey(r.record),compared:!!b,changed:!!b && Math.abs(r.rate-b.rate)>tol(b.rate),targetReached:Math.abs(r.rate-target)<=tol(target),atBhpLimit:Math.abs(r.bhp-r.record.bhpLimit)<=ptol(r.record.bhpLimit)}
 })
 const edits=p.edits.map(e=>{
  const points=rows.filter(r=>r.recordKey===controlPlanKey(e))
  return {...e,pointCount:points.length,targetCount:points.filter(r=>r.targetReached).length,changedCount:points.filter(r=>r.changed).length}
 })
 return {available:true,consistent,verified:consistent && rows.every(r=>r.targetReached) && edits.every(e=>e.pointCount>0 && e.changedCount>0),constrained:consistent && rows.some(r=>!r.targetReached && r.atBhpLimit),rows,edits,flowUnit,pressureUnit}
}
