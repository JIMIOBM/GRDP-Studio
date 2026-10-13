const name = s => typeof s === 'string' && /^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,63}$/.test(s)
const rate = n => typeof n === 'number' && Number.isFinite(n) && n > 0 && n <= 100000000
export function validGroupGasInjection(p) {
  const fields = ['schemaVersion','baselineRunId','group','phase','sourceFile','lineNumber','controlMode','injectionPhase','unit','originalTargetRate','targetRate','members']
  return p && Object.keys(p).length === fields.length && fields.every(k => Object.hasOwn(p,k)) && p.schemaVersion === 'eclipse-group-gas-injection-parameters/1'
    && p.phase === 'INITIAL' && p.injectionPhase === 'GAS' && p.controlMode === 'RATE' && ['Mscf/day','sm3/day'].includes(p.unit)
    && Number.isSafeInteger(p.baselineRunId) && p.baselineRunId > 0 && Number.isSafeInteger(p.lineNumber) && p.lineNumber > 0
    && name(p.group) && p.group !== 'FIELD' && typeof p.sourceFile === 'string' && /^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.DATA$/i.test(p.sourceFile)
    && rate(p.originalTargetRate) && rate(p.targetRate) && p.originalTargetRate !== p.targetRate
    && Array.isArray(p.members) && p.members.length >= 2 && p.members.length <= 1000 && p.members.every(name) && new Set(p.members).size === p.members.length
}
export function groupGasInjectionRecords(i) {
  if (i?.schemaVersion !== 'eclipse-data-inspection/8' || !['FIELD','METRIC'].includes(i.unitSystem) || i.packageFiles?.length !== 1
    || i.packageFiles[0].relativePath !== i.caseName || !Array.isArray(i.gasInjectionGroupControls) || !Array.isArray(i.scheduleMetadata?.wells) || i.scheduleMetadata.groups?.length !== 0) return []
  const unit = i.unitSystem === 'FIELD' ? 'Mscf/day' : 'sm3/day'
  const seen = new Set()
  return i.gasInjectionGroupControls.filter(g => {
    if (!name(g.group) || g.group === 'FIELD' || seen.has(g.group) || g.sourceFile !== i.caseName || !Number.isSafeInteger(g.lineNumber) || g.lineNumber <= 0
      || g.injectionPhase !== 'GAS' || g.controlMode !== 'RATE' || g.unit !== unit || !rate(g.originalTargetRate) || !Array.isArray(g.members) || g.members.length < 2
      || new Set(g.members.map(m => m.well)).size !== g.members.length || g.members.some(m => !name(m.well) || !rate(m.bhpLimit)
        || i.scheduleMetadata.wells.filter(w => w.name === m.well && w.group === g.group).length !== 1)
      || i.scheduleMetadata.wells.filter(w => w.group === g.group).length !== g.members.length
      || !Array.isArray(i.scheduleTimeline) || i.scheduleTimeline.some(e => e.sourceFile !== i.caseName || !Number.isSafeInteger(e.lineNumber) || e.lineNumber <= g.lineNumber)) return false
    seen.add(g.group); return true
  })
}
export function buildGroupGasInjection({inspection,baselineRunId,group,targetRate}) {
  const r = groupGasInjectionRecords(inspection).find(r => r.group === group)
  if (!r) return null
  const p = { schemaVersion:'eclipse-group-gas-injection-parameters/1',baselineRunId,group,phase:'INITIAL',sourceFile:r.sourceFile,lineNumber:r.lineNumber,
    controlMode:'RATE',injectionPhase:r.injectionPhase,unit:r.unit,originalTargetRate:r.originalTargetRate,targetRate,members:r.members.map(m=>m.well) }
  return validGroupGasInjection(p) ? p : null
}
const canonical = s => typeof s === 'string' ? s.trim().toUpperCase() : ''
function curve(summary, keyword, objectName, unit) {
  const rows = summary?.series?.filter(s=>s.keyword===keyword && s.objectName===objectName)
  if (rows?.length!==1 || canonical(rows[0].unit)!==canonical(unit) || !rows[0].points?.length) return null
  const points=rows[0].points
  if (points.some(p=>!Number.isFinite(p.timeDays) || p.timeDays<0 || !Number.isFinite(p.value)) || new Set(points.map(p=>p.timeDays)).size!==points.length) return null
  return new Map(points.map(p=>[p.timeDays,p.value]))
}
export function groupGasInjectionResponse(summary,baseline,p,inspection) {
  const empty={available:false,verified:false,constrained:false,rows:[],members:[]}
  if (!validGroupGasInjection(p)) return empty
  const r=groupGasInjectionRecords(inspection).find(g=>g.group===p.group && g.sourceFile===p.sourceFile && g.lineNumber===p.lineNumber && g.injectionPhase===p.injectionPhase && g.unit===p.unit
    && g.originalTargetRate===p.originalTargetRate && JSON.stringify(g.members.map(m=>m.well))===JSON.stringify(p.members))
  if (!r) return empty
  const pressureUnit=inspection.unitSystem==='FIELD' ? 'psia' : 'barsa'
  const group=curve(summary,'GGIR',p.group,p.unit), oldGroup=curve(baseline,'GGIR',p.group,p.unit)
  const wells=r.members.map(m=>({...m,current:curve(summary,'WGIR',m.well,p.unit),old:curve(baseline,'WGIR',m.well,p.unit),pressure:curve(summary,'WBHP',m.well,pressureUnit)}))
  if (!group || !oldGroup || wells.some(w=>!w.current || !w.old || !w.pressure)) return empty
  const tol=n=>Math.max(0.0001,Math.abs(n)*1e-6)
  const pressureTol=n=>Math.max(0.1,Math.abs(n)*1e-6)
  const times=[...group.keys()].filter(t=>t>0 && oldGroup.has(t) && wells.every(w=>w.current.has(t) && w.old.has(t) && w.pressure.has(t))).sort((a,b)=>a-b)
  if (!times.length) return empty
  const rows=times.map(timeDays=>{
    const members=wells.map(w=>({timeDays,well:w.well,baselineRate:w.old.get(timeDays),rate:w.current.get(timeDays),bhp:w.pressure.get(timeDays),bhpLimit:w.bhpLimit,
      atBhpLimit:Math.abs(w.pressure.get(timeDays)-w.bhpLimit)<=pressureTol(w.bhpLimit)}))
    const rate=group.get(timeDays), baselineRate=oldGroup.get(timeDays), wellTotal=members.reduce((s,m)=>s+m.rate,0)
    return {timeDays,rate,baselineRate,wellTotal,target:p.targetRate,consistent:rate>=0 && baselineRate>=0 && Math.abs(rate-wellTotal)<=tol(rate)
      && members.every(m=>m.rate>=0 && m.baselineRate>=0 && m.bhp>=0 && m.bhp<=m.bhpLimit+pressureTol(m.bhpLimit)),
      targetReached:Math.abs(rate-p.targetRate)<=tol(p.targetRate),changed:Math.abs(rate-baselineRate)>tol(baselineRate)
      && members.every(m=>m.rate>0 && Math.abs(m.rate-m.baselineRate)>tol(m.baselineRate)), members}
  })
  const consistent=rows.every(r=>r.consistent)
  return {available:true,verified:consistent && rows.every(r=>r.targetReached) && rows.some(r=>r.changed),
    constrained:consistent && rows.some(r=>r.rate<p.targetRate-tol(p.targetRate) && r.members.every(m=>m.atBhpLimit)),
    consistent,unit:p.unit,pressureUnit,rows,members:rows.flatMap(r=>r.members)}
}
