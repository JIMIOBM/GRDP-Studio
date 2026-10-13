const name=s=>typeof s==='string' && /^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,7}$/.test(s)
const value=n=>typeof n==='number' && Number.isFinite(n) && n>0 && n<=100000000
const shape=(o,keys)=>o && Object.keys(o).length===keys.length && keys.every(k=>Object.hasOwn(o,k))
const keywords=['GCONPROD','WGRUPCON','GEFAC']
const fields=['schemaVersion','baselineRunId','phase','parentGroup','objectName','keyword','sourceFile','lineNumber','unit','originalValue','targetValue','members']
export const groupStructureKey=c=>`${c.keyword}:${c.objectName}`
export const groupStructureLabel=c=>`${c.keyword==='GCONPROD'?'父组产油目标':c.keyword==='WGRUPCON'?'井固定油导向':'子组开工比例'} · ${c.objectName}`
export function validGroupStructure(p) {
 return shape(p,fields) && p.schemaVersion==='eclipse-group-structure-parameters/1' && p.phase==='INITIAL'
  && Number.isSafeInteger(p.baselineRunId) && p.baselineRunId>0 && Number.isSafeInteger(p.lineNumber) && p.lineNumber>0
  && name(p.parentGroup) && p.parentGroup!=='FIELD' && name(p.objectName) && keywords.includes(p.keyword)
  && typeof p.sourceFile==='string' && /^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.DATA$/i.test(p.sourceFile)
  && value(p.originalValue) && value(p.targetValue) && p.originalValue!==p.targetValue
  && (p.keyword==='GCONPROD' ? p.objectName===p.parentGroup && ['STB/day','sm3/day'].includes(p.unit) : p.unit==='dimensionless' && (p.keyword!=='GEFAC' || p.originalValue<=1 && p.targetValue<=1))
  && Array.isArray(p.members) && p.members.length>=4 && p.members.length<=1000 && p.members.every(name) && new Set(p.members).size===p.members.length
}
export function groupStructureRecords(i) {
 if(i?.schemaVersion!=='eclipse-data-inspection/12' || !['FIELD','METRIC'].includes(i.unitSystem) || i.packageFiles?.length!==1 || i.packageFiles[0].relativePath!==i.caseName
  || !Array.isArray(i.groupStructureControls) || !i.groupStructureControls.length || i.groupStructureControls.length>1002 || !Array.isArray(i.scheduleMetadata?.wells) || !Array.isArray(i.scheduleMetadata.groups) || !Array.isArray(i.scheduleTimeline))return []
 const controls=i.groupStructureControls, first=controls[0], leaves=first.leaves, parent=first.parentGroup, seen=new Set()
 if(!name(parent) || parent==='FIELD' || !Array.isArray(leaves) || leaves.length<2 || leaves.length>500 || !value(first.parentTarget) || i.scheduleMetadata.groups.length!==leaves.length)return []
 const groups=new Set(),wells=new Set(),guides=new Set();let factors=0
 for(const leaf of leaves) {
  if(!shape(leaf,['group','efficiency','members']) || !name(leaf.group) || ['FIELD',parent].includes(leaf.group) || groups.has(leaf.group) || !value(leaf.efficiency) || leaf.efficiency>1 || !Array.isArray(leaf.members) || leaf.members.length<2
   || i.scheduleMetadata.groups.filter(g=>g.name===leaf.group && g.parent===parent).length!==1 || i.scheduleMetadata.wells.filter(w=>w.group===leaf.group).length!==leaf.members.length)return []
  groups.add(leaf.group);if(leaf.efficiency!==1)factors++
  for(const m of leaf.members) {
   if(!shape(m,['well','bhpLimit','oilRateLimit','guideRate']) || !name(m.well) || wells.has(m.well) || !value(m.bhpLimit) || !value(m.oilRateLimit) || !Number.isFinite(m.guideRate) || m.guideRate<0 || m.guideRate>100000000 || i.scheduleMetadata.wells.filter(w=>w.name===m.well && w.group===leaf.group).length!==1)return []
   wells.add(m.well);if(m.guideRate>0)guides.add(m.well)
  }
 }
 if(wells.size>1000 || factors>1 || guides.size>0 && (guides.size!==wells.size || factors>0) || i.scheduleMetadata.wells.some(w=>w.group===parent))return []
 const guideRecords=new Set(),factorRecords=new Set();let parentRecords=0
 for(const c of controls) {
  const p={schemaVersion:'eclipse-group-structure-parameters/1',baselineRunId:1,phase:'INITIAL',parentGroup:c.parentGroup,objectName:c.objectName,keyword:c.keyword,sourceFile:c.sourceFile,lineNumber:c.lineNumber,unit:c.unit,originalValue:c.originalValue,targetValue:c.originalValue/2,members:[...wells]}
  if(!shape(c,['parentGroup','objectName','keyword','sourceFile','lineNumber','unit','originalValue','parentTarget','leaves']) || !validGroupStructure(p) || seen.has(groupStructureKey(c)) || c.sourceFile!==i.caseName
   || c.parentGroup!==parent || c.parentTarget!==first.parentTarget || JSON.stringify(c.leaves)!==JSON.stringify(leaves) || c.unit!==(c.keyword==='GCONPROD'?i.unitSystem==='FIELD'?'STB/day':'sm3/day':'dimensionless')
   || i.scheduleTimeline.some(e=>e.sourceFile!==c.sourceFile || !Number.isSafeInteger(e.lineNumber) || e.lineNumber<=c.lineNumber))return []
  seen.add(groupStructureKey(c))
  if(c.keyword==='GCONPROD'){if(c.originalValue!==c.parentTarget)return [];parentRecords++}
  if(c.keyword==='WGRUPCON'){
   const m=leaves.flatMap(l=>l.members).find(m=>m.well===c.objectName)
   if(!m || m.guideRate!==c.originalValue || !guides.has(c.objectName))return []
   guideRecords.add(c.objectName)
  }
  if(c.keyword==='GEFAC'){
   const leaf=leaves.find(l=>l.group===c.objectName)
   if(!leaf || leaf.efficiency!==c.originalValue)return []
   factorRecords.add(c.objectName)
  }
 }
 if(parentRecords!==1 || factorRecords.size>1 || guides.size!==guideRecords.size || factors>0 && factorRecords.size!==1 || guideRecords.size>0 && factorRecords.size>0)return []
 return controls
}
export function buildGroupStructure({inspection,baselineRunId,controlKey,targetValue}) {
 const c=groupStructureRecords(inspection).find(c=>groupStructureKey(c)===controlKey)
 if(!c)return null
 const p={schemaVersion:'eclipse-group-structure-parameters/1',baselineRunId,phase:'INITIAL',parentGroup:c.parentGroup,objectName:c.objectName,keyword:c.keyword,sourceFile:c.sourceFile,lineNumber:c.lineNumber,unit:c.unit,originalValue:c.originalValue,targetValue,members:c.leaves.flatMap(l=>l.members.map(m=>m.well))}
 return validGroupStructure(p)?p:null
}
const canonical=s=>typeof s==='string'?s.trim().toUpperCase():''
function curve(summary,keyword,objectName,unit) {
 const rows=summary?.series?.filter(s=>s.keyword===keyword && s.objectName===objectName)
 if(rows?.length!==1 || !Object.hasOwn(rows[0],'unit') || !(rows[0].unit===null && unit==='' || typeof rows[0].unit==='string' && canonical(rows[0].unit)===canonical(unit)) || !rows[0].points?.length)return null
 const pts=rows[0].points
 if(pts.some(p=>!Number.isFinite(p.timeDays) || p.timeDays<0 || !Number.isFinite(p.value)) || new Set(pts.map(p=>p.timeDays)).size!==pts.length)return null
 return new Map(pts.map(p=>[p.timeDays,p.value]))
}
export function groupStructureResponse(summary,baseline,p,inspection) {
 const empty={available:false,consistent:false,verified:false,constrained:false,rows:[],leaves:[],members:[]}
 if(!validGroupStructure(p))return empty
 const c=groupStructureRecords(inspection).find(c=>c.keyword===p.keyword && c.objectName===p.objectName && ['parentGroup','sourceFile','lineNumber','unit','originalValue'].every(k=>c[k]===p[k]) && JSON.stringify(c.leaves.flatMap(l=>l.members.map(m=>m.well)))===JSON.stringify(p.members))
 if(!c)return empty
 const field=inspection.unitSystem==='FIELD',flowUnit=field?'STB/day':'sm3/day',volumeUnit=field?'STB':'sm3',pressureUnit=field?'psia':'barsa'
 // Efficiency products are dimensionless; Eclipse reports their unit as an empty string.
 const efficiencyUnit=''
 function data(s) {
  const parent=curve(s,'GOPR',p.parentGroup,flowUnit),total=curve(s,'GOPT',p.parentGroup,volumeUnit),fieldRate=curve(s,'FOPR',null,flowUnit),fieldTotal=curve(s,'FOPT',null,volumeUnit)
  const leaves=c.leaves.map(l=>({...l,current:curve(s,'GOPR',l.group,flowUnit),total:curve(s,'GOPT',l.group,volumeUnit),members:l.members.map(m=>({...m,rate:curve(s,'WOPR',m.well,flowUnit),total:curve(s,'WOPT',m.well,volumeUnit),pressure:curve(s,'WBHP',m.well,pressureUnit),efficiency:curve(s,'WEFFG',m.well,efficiencyUnit)}))}))
  const maps=[parent,total,fieldRate,fieldTotal,...leaves.flatMap(l=>[l.current,l.total,...l.members.flatMap(m=>[m.rate,m.total,m.pressure,m.efficiency])])]
  if(maps.some(m=>!m))return null
  const times=[...parent.keys()].filter(t=>t>0).sort((a,b)=>a-b)
  if(!times.length || maps.some(m=>[...m.keys()].filter(t=>t>0).length!==times.length || times.some(t=>!m.has(t))))return null
  return {parent,total,fieldRate,fieldTotal,leaves,times}
 }
 const current=data(summary),old=data(baseline)
 if(!current || !old)return empty
 const tol=n=>Math.max(.0001,Math.abs(n)*1e-6),ptol=n=>Math.max(.1,Math.abs(n)*1e-6)
 function rows(d,derived) {
  const parentTarget=derived && p.keyword==='GCONPROD'?p.targetValue:c.parentTarget
  const all=d.leaves.flatMap(l=>l.members),guideTotal=all.reduce((n,m)=>n+(derived && p.keyword==='WGRUPCON' && m.well===p.objectName?p.targetValue:m.guideRate),0)
  return d.times.map(timeDays=>{
   const leaves=d.leaves.map(l=>{
    const efficiency=derived && p.keyword==='GEFAC' && l.group===p.objectName?p.targetValue:l.efficiency
    const members=l.members.map(m=>{
     const rate=m.rate.get(timeDays),total=m.total.get(timeDays),bhp=m.pressure.get(timeDays),effective=m.efficiency.get(timeDays),guideRate=derived && p.keyword==='WGRUPCON' && m.well===p.objectName?p.targetValue:m.guideRate
     const guideTarget=guideTotal>0?parentTarget*guideRate/guideTotal:null
     return {timeDays,group:l.group,well:m.well,rate,total,bhp,bhpLimit:m.bhpLimit,oilRateLimit:m.oilRateLimit,efficiency:effective,guideRate,guideTarget,
      guideReached:guideTarget===null || Math.abs(rate-guideTarget)<=tol(guideTarget),atBhpLimit:Math.abs(bhp-m.bhpLimit)<=ptol(m.bhpLimit),atOilLimit:Math.abs(rate-m.oilRateLimit)<=tol(m.oilRateLimit),
      consistent:rate>=0 && total>=0 && bhp>=m.bhpLimit-ptol(m.bhpLimit) && rate<=m.oilRateLimit+tol(m.oilRateLimit) && Math.abs(effective-efficiency)<=1e-6}
    })
    const rate=l.current.get(timeDays),total=l.total.get(timeDays),wellTotal=members.reduce((n,m)=>n+m.rate,0),wellCumulative=members.reduce((n,m)=>n+m.total,0)
    return {timeDays,group:l.group,rate,total,efficiency,contribution:rate*efficiency,wellTotal,wellCumulative,members,consistent:rate>=0 && total>=0 && Math.abs(rate-wellTotal)<=tol(rate) && Math.abs(total-wellCumulative)<=tol(total) && members.every(m=>m.consistent)}
   })
   const rate=d.parent.get(timeDays),total=d.total.get(timeDays),contribution=leaves.reduce((n,l)=>n+l.contribution,0),leafTotal=leaves.reduce((n,l)=>n+l.total,0),members=leaves.flatMap(l=>l.members)
   return {timeDays,rate,total,contribution,leafTotal,target:parentTarget,leaves,members,
    consistent:rate>=0 && total>=0 && leaves.every(l=>l.consistent) && Math.abs(rate-contribution)<=tol(rate) && Math.abs(total-leafTotal)<=tol(total) && Math.abs(rate-d.fieldRate.get(timeDays))<=tol(rate) && Math.abs(total-d.fieldTotal.get(timeDays))<=tol(total),
    targetReached:Math.abs(rate-parentTarget)<=tol(parentTarget) && (p.keyword!=='WGRUPCON' || members.every(m=>m.guideReached)),
    constrained:(rate<parentTarget-tol(parentTarget) || p.keyword==='WGRUPCON' && members.some(m=>!m.guideReached)) && members.some(m=>m.atBhpLimit || m.atOilLimit)}
  })
 }
 const baselineRows=rows(old,false),baselineMap=new Map(baselineRows.map(r=>[r.timeDays,r])),currentRows=rows(current,true)
 const consistent=baselineRows.every(r=>r.consistent) && currentRows.every(r=>r.consistent)
 const result=currentRows.map(r=>{
  const b=baselineMap.get(r.timeDays),changed=b && (p.keyword==='GCONPROD'?Math.abs(r.rate-b.rate)>tol(b.rate) && r.members.every((m,n)=>Math.abs(m.rate-b.members[n].rate)>tol(b.members[n].rate))
   :p.keyword==='WGRUPCON'?r.members.some((m,n)=>m.well===p.objectName && Math.abs(m.rate-b.members[n].rate)>tol(b.members[n].rate))
   :r.leaves.some((l,n)=>l.group===p.objectName && Math.abs(l.rate-b.leaves[n].rate)>tol(b.leaves[n].rate) && Math.abs(l.total-b.leaves[n].total)>tol(b.leaves[n].total)))
  return {...r,compared:!!b,baselineRate:b?.rate??null,baselineTotal:b?.total??null,changed:!!changed,
   leaves:r.leaves.map((l,n)=>({...l,baselineRate:b?.leaves[n].rate??null,baselineTotal:b?.leaves[n].total??null,baselineEfficiency:c.leaves[n].efficiency})),
   members:r.members.map((m,n)=>({...m,baselineRate:b?.members[n].rate??null,baselineTotal:b?.members[n].total??null}))}
 })
 return {available:true,consistent,verified:consistent && result.every(r=>r.targetReached) && result.some(r=>r.changed),constrained:consistent && result.some(r=>r.constrained),
  rows:result,leaves:result.flatMap(r=>r.leaves),members:result.flatMap(r=>r.members),flowUnit,volumeUnit,pressureUnit,unit:p.unit}
}
