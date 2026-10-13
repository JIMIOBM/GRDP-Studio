const name = s => typeof s === 'string' && /^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,63}$/.test(s)
const rate = n => typeof n === 'number' && Number.isFinite(n) && n > 0 && n <= 100000000
const names = (a,min) => Array.isArray(a) && a.length >= min && a.length <= 1000 && a.every(name) && new Set(a).size === a.length
const ratio = mode => ['REIN','VREP'].includes(mode)
const supported = (phase,mode) => phase === 'OIL' && mode === 'RATE' || phase === 'GAS' && ['RESV','REIN','VREP'].includes(mode)
const unit = (system,mode) => ratio(mode) ? 'fraction' : mode === 'RESV' ? system === 'FIELD' ? 'RB/day' : 'rm3/day' : system === 'FIELD' ? 'STB/day' : 'sm3/day'
export function validAdvancedInjection(p) {
  const fields = ['schemaVersion','baselineRunId','group','phase','sourceFile','lineNumber','controlMode','injectionPhase','unit','originalTargetRate','targetRate','members','referenceGroup','referenceMembers']
  return p && Object.keys(p).length === fields.length && fields.every(k => Object.hasOwn(p,k)) && p.schemaVersion === 'eclipse-advanced-injection-parameters/1'
    && p.phase === 'INITIAL' && supported(p.injectionPhase,p.controlMode) && [unit('FIELD',p.controlMode),unit('METRIC',p.controlMode)].includes(p.unit)
    && Number.isSafeInteger(p.baselineRunId) && p.baselineRunId > 0 && Number.isSafeInteger(p.lineNumber) && p.lineNumber > 0
    && name(p.group) && p.group !== 'FIELD' && typeof p.sourceFile === 'string' && /^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.DATA$/i.test(p.sourceFile)
    && rate(p.originalTargetRate) && rate(p.targetRate) && p.originalTargetRate !== p.targetRate && names(p.members,2)
    && (ratio(p.controlMode) ? name(p.referenceGroup) && !['FIELD',p.group].includes(p.referenceGroup) && names(p.referenceMembers,1) : p.referenceGroup === null && Array.isArray(p.referenceMembers) && p.referenceMembers.length === 0)
}
export function advancedInjectionRecords(i) {
  if (i?.schemaVersion !== 'eclipse-data-inspection/11' || !['FIELD','METRIC'].includes(i.unitSystem) || i.packageFiles?.length !== 1
    || i.packageFiles[0].relativePath !== i.caseName || !Array.isArray(i.advancedInjectionGroupControls) || !Array.isArray(i.scheduleMetadata?.wells) || i.scheduleMetadata.groups?.length !== 0) return []
  const seen = new Set()
  return i.advancedInjectionGroupControls.filter(g => {
    const p = {...g,schemaVersion:'eclipse-advanced-injection-parameters/1',baselineRunId:1,phase:'INITIAL',targetRate:g.originalTargetRate/2,members:g.members?.map(m=>m.well)}
    if (!validAdvancedInjection(p) || seen.has(g.group) || g.sourceFile !== i.caseName || g.unit !== unit(i.unitSystem,g.controlMode)
      || g.members.some(m => !rate(m.bhpLimit) || !rate(m.surfaceRateLimit) || i.scheduleMetadata.wells.filter(w=>w.name===m.well && w.group===g.group).length!==1)
      || i.scheduleMetadata.wells.filter(w=>w.group===g.group).length!==g.members.length
      || ratio(g.controlMode) && (g.referenceMembers.some(w=>i.scheduleMetadata.wells.filter(m=>m.name===w && m.group===g.referenceGroup).length!==1)
        || i.scheduleMetadata.wells.filter(w=>w.group===g.referenceGroup).length!==g.referenceMembers.length)
      || !Array.isArray(i.scheduleTimeline) || i.scheduleTimeline.some(e=>e.sourceFile!==i.caseName || !Number.isSafeInteger(e.lineNumber) || e.lineNumber<=g.lineNumber)) return false
    seen.add(g.group); return true
  })
}
export function buildAdvancedInjection({inspection,baselineRunId,group,targetRate}) {
  const r=advancedInjectionRecords(inspection).find(r=>r.group===group)
  if (!r) return null
  const p={schemaVersion:'eclipse-advanced-injection-parameters/1',baselineRunId,group,phase:'INITIAL',sourceFile:r.sourceFile,lineNumber:r.lineNumber,
    controlMode:r.controlMode,injectionPhase:r.injectionPhase,unit:r.unit,originalTargetRate:r.originalTargetRate,targetRate,members:r.members.map(m=>m.well),referenceGroup:r.referenceGroup,referenceMembers:r.referenceMembers}
  return validAdvancedInjection(p) ? p : null
}
const canonical=s=>typeof s==='string' ? s.trim().toUpperCase() : ''
function curve(summary,keyword,objectName,unit) {
  const rows=summary?.series?.filter(s=>s.keyword===keyword && s.objectName===objectName)
  if (rows?.length!==1 || canonical(rows[0].unit)!==canonical(unit) || !rows[0].points?.length) return null
  const points=rows[0].points
  if (points.some(p=>!Number.isFinite(p.timeDays) || p.timeDays<0 || !Number.isFinite(p.value)) || new Set(points.map(p=>p.timeDays)).size!==points.length) return null
  return new Map(points.map(p=>[p.timeDays,p.value]))
}
export function advancedInjectionResponse(summary,baseline,p,inspection) {
  const empty={available:false,verified:false,constrained:false,rows:[],members:[],referenceRows:[]}
  if (!validAdvancedInjection(p)) return empty
  const r=advancedInjectionRecords(inspection).find(g=>g.group===p.group && ['sourceFile','lineNumber','controlMode','injectionPhase','unit','originalTargetRate','referenceGroup'].every(k=>g[k]===p[k])
    && JSON.stringify(g.members.map(m=>m.well))===JSON.stringify(p.members) && JSON.stringify(g.referenceMembers)===JSON.stringify(p.referenceMembers))
  if (!r) return empty
  const field=inspection.unitSystem==='FIELD', pressureUnit=field?'psia':'barsa', surfaceUnit=field?(p.injectionPhase==='OIL'?'STB/day':'Mscf/day'):'sm3/day', reservoirUnit=field?'RB/day':'rm3/day'
  const reservoir=['RESV','VREP'].includes(p.controlMode), flowUnit=reservoir?reservoirUnit:surfaceUnit
  const groupKeyword=reservoir?'GVIR':p.injectionPhase==='OIL'?'GOIR':'GGIR', wellKeyword=reservoir?'WVIR':p.injectionPhase==='OIL'?'WOIR':'WGIR'
  const referenceKeyword=p.controlMode==='VREP'?'GVPR':'GGPR', referenceWellKeyword=p.controlMode==='VREP'?'WVPR':'WGPR'
  const group=curve(summary,groupKeyword,p.group,flowUnit), oldGroup=curve(baseline,groupKeyword,p.group,flowUnit)
  const wells=r.members.map(m=>({...m,current:curve(summary,wellKeyword,m.well,flowUnit),old:curve(baseline,wellKeyword,m.well,flowUnit),pressure:curve(summary,'WBHP',m.well,pressureUnit),oldPressure:curve(baseline,'WBHP',m.well,pressureUnit),surface:curve(summary,p.injectionPhase==='OIL'?'WOIR':'WGIR',m.well,surfaceUnit),oldSurface:curve(baseline,p.injectionPhase==='OIL'?'WOIR':'WGIR',m.well,surfaceUnit)}))
  const ref=ratio(p.controlMode)?curve(summary,referenceKeyword,p.referenceGroup,flowUnit):null, oldRef=ratio(p.controlMode)?curve(baseline,referenceKeyword,p.referenceGroup,flowUnit):null
  const refs=p.referenceMembers.map(w=>({well:w,current:curve(summary,referenceWellKeyword,w,flowUnit),old:curve(baseline,referenceWellKeyword,w,flowUnit)}))
  const currentCurves=[group,...wells.flatMap(w=>[w.current,w.pressure,w.surface]),...(ratio(p.controlMode)?[ref,...refs.map(w=>w.current)]:[])]
  const baselineCurves=[oldGroup,...wells.flatMap(w=>[w.old,w.oldPressure,w.oldSurface]),...(ratio(p.controlMode)?[oldRef,...refs.map(w=>w.old)]:[])]
  const curves=[...currentCurves,...baselineCurves]
  if (curves.some(c=>!c)) return empty
  const times=[...group.keys()].filter(t=>t>0).sort((a,b)=>a-b)
  const baselineTimes=[...oldGroup.keys()].filter(t=>t>0)
  const aligned=(maps,ts)=>ts.length>0 && maps.every(c=>[...c.keys()].filter(t=>t>0).length===ts.length && ts.every(t=>c.has(t)))
  if (!aligned(currentCurves,times) || !aligned(baselineCurves,baselineTimes)) return empty
  const tol=n=>Math.max(0.0001,Math.abs(n)*1e-6), ptol=n=>Math.max(0.1,Math.abs(n)*1e-6), fractionTolerance=0.0001
  const baselineConsistent=baselineTimes.every(t=>{
    const value=oldGroup.get(t),total=wells.reduce((s,w)=>s+w.old.get(t),0)
    return value>=0 && Math.abs(value-total)<=tol(value) && wells.every(w=>w.old.get(t)>=0 && w.oldPressure.get(t)>=0 && w.oldPressure.get(t)<=w.bhpLimit+ptol(w.bhpLimit) && w.oldSurface.get(t)>=0 && w.oldSurface.get(t)<=w.surfaceRateLimit+tol(w.surfaceRateLimit))
      && (!ratio(p.controlMode) || oldRef.get(t)>0 && refs.every(w=>w.old.get(t)>=0) && Math.abs(oldRef.get(t)-refs.reduce((s,w)=>s+w.old.get(t),0))<=tol(oldRef.get(t)))
  })
  const rows=times.map(timeDays=>{
    const compared=oldGroup.has(timeDays)
    const members=wells.map(w=>({timeDays,well:w.well,rate:w.current.get(timeDays),baselineRate:w.old.get(timeDays)??null,bhp:w.pressure.get(timeDays),baselineBhp:w.oldPressure.get(timeDays)??null,bhpLimit:w.bhpLimit,surfaceRate:w.surface.get(timeDays),baselineSurfaceRate:w.oldSurface.get(timeDays)??null,surfaceRateLimit:w.surfaceRateLimit,
      atBhpLimit:Math.abs(w.pressure.get(timeDays)-w.bhpLimit)<=ptol(w.bhpLimit),atSurfaceLimit:Math.abs(w.surface.get(timeDays)-w.surfaceRateLimit)<=tol(w.surfaceRateLimit)}))
    const rate=group.get(timeDays), baselineRate=oldGroup.get(timeDays)??null, wellTotal=members.reduce((s,m)=>s+m.rate,0)
    const referenceRows=refs.map(w=>({timeDays,well:w.well,rate:w.current.get(timeDays),baselineRate:w.old.get(timeDays)??null})), referenceRate=ref?.get(timeDays)??null, baselineReferenceRate=oldRef?.get(timeDays)??null
    const actualRatio=referenceRate>0?rate/referenceRate:null, baselineRatio=baselineReferenceRate>0?baselineRate/baselineReferenceRate:null
    const target=ratio(p.controlMode)?referenceRate*p.targetRate:p.targetRate, baselineTarget=ratio(p.controlMode)?baselineReferenceRate===null?null:baselineReferenceRate*p.originalTargetRate:p.originalTargetRate
    const consistent=baselineConsistent && rate>=0 && Math.abs(rate-wellTotal)<=tol(rate)
      && members.every(m=>m.rate>=0 && m.bhp>=0 && m.bhp<=m.bhpLimit+ptol(m.bhpLimit) && m.surfaceRate>=0 && m.surfaceRate<=m.surfaceRateLimit+tol(m.surfaceRateLimit))
      && (!ratio(p.controlMode) || referenceRate>0 && referenceRows.every(w=>w.rate>=0) && Math.abs(referenceRate-referenceRows.reduce((s,w)=>s+w.rate,0))<=tol(referenceRate))
    return {timeDays,compared,rate,baselineRate,wellTotal,target,baselineTarget,referenceRate,baselineReferenceRate,actualRatio,baselineRatio,ratioError:actualRatio===null?null:actualRatio-p.targetRate,referenceRows,members,consistent,
      targetReached:ratio(p.controlMode)?actualRatio!==null && Math.abs(actualRatio-p.targetRate)<=fractionTolerance:Math.abs(rate-target)<=tol(target),
      belowTarget:ratio(p.controlMode)?actualRatio!==null && actualRatio<p.targetRate-fractionTolerance:rate<target-tol(target),
      changed:compared && Math.abs(rate-baselineRate)>tol(baselineRate) && members.every(m=>m.rate>0 && Math.abs(m.rate-m.baselineRate)>tol(m.baselineRate))}
  })
  const consistent=rows.every(r=>r.consistent)
  return {available:true,consistent,verified:consistent && rows.every(r=>r.targetReached) && rows.some(r=>r.changed),constrained:consistent && rows.some(r=>r.belowTarget && r.members.every(m=>m.atBhpLimit || m.atSurfaceLimit)),
    rows,members:rows.flatMap(r=>r.members),referenceRows:rows.flatMap(r=>r.referenceRows),unit:p.unit,flowUnit,surfaceUnit,pressureUnit,groupKeyword,wellKeyword,referenceKeyword,fractionTolerance}
}
