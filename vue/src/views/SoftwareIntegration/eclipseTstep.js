const steps = s => Array.isArray(s) && s.length > 0 && s.length <= 1000 && s.every(v => Number.isSafeInteger(v) && v > 0 && v <= 100000) && s.reduce((a,b)=>a+b,0)<=100000
export function validTstep(p) {
 const fields=['schemaVersion','baselineRunId','sourceFile','lineNumber','unit','originalSteps','targetSteps']
 return p && Object.keys(p).length===fields.length && fields.every(f=>Object.hasOwn(p,f)) && p.schemaVersion==='eclipse-tstep-parameters/1' && p.unit==='day'
   && Number.isSafeInteger(p.baselineRunId) && p.baselineRunId>0 && Number.isSafeInteger(p.lineNumber) && p.lineNumber>0 && typeof p.sourceFile==='string' && /^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.DATA$/i.test(p.sourceFile)
   && steps(p.originalSteps) && steps(p.targetSteps) && p.originalSteps.reduce((a,b)=>a+b,0)===p.targetSteps.reduce((a,b)=>a+b,0) && JSON.stringify(p.originalSteps)!==JSON.stringify(p.targetSteps)
}
export function tstepRecords(i) {
 if (!['eclipse-data-inspection/4','eclipse-data-inspection/5','eclipse-data-inspection/6', 'eclipse-data-inspection/7', 'eclipse-data-inspection/8', 'eclipse-data-inspection/9', 'eclipse-data-inspection/10', 'eclipse-data-inspection/11', 'eclipse-data-inspection/12', 'eclipse-data-inspection/13'].includes(i?.schemaVersion) || !['FIELD','METRIC','PVT-M'].includes(i.unitSystem) || i.packageFiles?.length!==1 || i.caseName!==i.packageFiles[0].relativePath) return []
 const t=i.scheduleTimeline
 if (!Array.isArray(t) || !t.length || t.some((e,n)=>e.kind!=='TSTEP' || e.sourceFile!==i.caseName || !Number.isSafeInteger(e.lineNumber) || e.lineNumber<=0 || n>0 && t[n-1].lineNumber>=e.lineNumber || !Array.isArray(e.steps) || !e.steps.every(v=>typeof v==='string' && /^[+]?[0-9]+$/.test(v)) || !steps(e.steps.map(Number)))) return []
 let time=0
 const rows=t.map(e=>{const originalSteps=e.steps.map(Number),duration=originalSteps.reduce((a,b)=>a+b,0),startDays=time;time+=duration;return {sourceFile:i.caseName,lineNumber:e.lineNumber,originalSteps,duration,startDays,endDays:time}})
 return time<=100000 ? rows : []
}
export function buildTstep({inspection,baselineRunId,lineNumber,stepsText}) {
 const record=tstepRecords(inspection).find(r=>r.lineNumber===lineNumber)
 if (!record || typeof stepsText!=='string' || !/^\s*[0-9]+(?:[\s,]+[0-9]+)*\s*$/.test(stepsText)) return null
 const targetSteps=stepsText.trim().split(/[\s,]+/).map(Number)
 const p={schemaVersion:'eclipse-tstep-parameters/1',baselineRunId,sourceFile:record.sourceFile,lineNumber,unit:'day',originalSteps:[...record.originalSteps],targetSteps}
 return validTstep(p) ? p : null
}
export function tstepResponse(summary,p,inspection) {
 const empty={available:false,verified:false,rows:[]}
 if (!validTstep(p)) return empty
 const record=tstepRecords(inspection).find(r=>r.lineNumber===p.lineNumber && JSON.stringify(r.originalSteps)===JSON.stringify(p.originalSteps))
 if (!record) return empty
 const series=(summary?.series || []).find(s=>s.points?.length && s.keyword==='WOPR') || summary?.series?.[0]
 if (!series?.points?.length || new Set(series.points.map(p=>p.timeDays)).size!==series.points.length || !series.points.every(p=>Number.isFinite(p.timeDays))) return empty
 const times=new Set(series.points.map(p=>p.timeDays));let t=record.startDays
 const rows=p.targetSteps.map(interval=>{t+=interval;return {interval,timeDays:t,present:times.has(t)}})
 return {available:true,verified:rows.every(r=>r.present) && Math.max(...times)===tstepRecords(inspection).at(-1).endDays,rows,startDays:record.startDays,endDays:record.endDays}
}
