const editFields = ['well', 'sourceFile', 'sourceSha256', 'lineNumber', 'date', 'timeDays', 'unit', 'originalInjectionRate', 'targetInjectionRate']
const recordFields = [...editFields.filter(k => k !== 'targetInjectionRate'), 'bhpLimit']
const shape = (o, keys) => o && typeof o === 'object' && !Array.isArray(o) && Object.keys(o).length === keys.length && keys.every(k => Object.hasOwn(o, k))
const integer = (n, min = 0, max = Number.MAX_SAFE_INTEGER) => Number.isSafeInteger(n) && n >= min && n <= max
const positive = n => typeof n === 'number' && Number.isFinite(n) && n > 0 && n <= 100000000
const hash = s => typeof s === 'string' && /^[a-f0-9]{64}$/.test(s)
const well = s => typeof s === 'string' && /^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,7}$/.test(s)
const path = s => typeof s === 'string' && s.length > 0 && s.length <= 512 && s.split('/').every(p => p !== '.' && p !== '..' && /^[A-Za-z0-9][A-Za-z0-9._ -]*$/.test(p))
const day = s => {
  if (typeof s !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(s)) return null
  const time = Date.parse(`${s}T00:00:00Z`)
  return Number.isFinite(time) && new Date(time).toISOString().slice(0, 10) === s ? time / 86400000 : null
}
const number = (text, value) => typeof text === 'string' && /^[+-]?(?:\d+\.?\d*|\.\d+)(?:[Ee][+-]?\d+)?$/.test(text) && Number(text) === value
const expand = values => {
  if (!Array.isArray(values)) return null
  const result = []
  for (let text of values) {
    if (typeof text !== 'string') return null
    const repeated = /^(\d+)\*(.*)$/.exec(text), count = repeated ? Number(repeated[1]) : 1
    if (!integer(count, 1, 128) || result.length + count > 128) return null
    if (repeated) text = repeated[2]
    for (let i = 0; i < count; i++) result.push(text)
  }
  return result
}
export const validWaterInjectionPlan = p => {
  if (!shape(p, ['schemaVersion', 'baselineRunId', 'edits']) || p.schemaVersion !== 'eclipse-water-injection-plan-parameters/1' || !integer(p.baselineRunId, 1) || !Array.isArray(p.edits) || !p.edits.length || p.edits.length > 24) return false
  const seen = new Set(), physical = new Set(); let previous = -1
  for (const e of p.edits) {
    if (!shape(e, editFields) || !well(e.well) || !path(e.sourceFile) || !hash(e.sourceSha256) || !integer(e.lineNumber, 1, 2147483647) || !integer(e.timeDays, 0, 3000000)
      || day(e.date) === null || !['STB/day', 'sm3/day'].includes(e.unit) || !positive(e.originalInjectionRate) || !positive(e.targetInjectionRate) || e.targetInjectionRate === e.originalInjectionRate
      || e.timeDays < previous || seen.has(`${e.well}:${e.timeDays}`) || physical.has(`${e.sourceFile}:${e.lineNumber}`)) return false
    seen.add(`${e.well}:${e.timeDays}`); physical.add(`${e.sourceFile}:${e.lineNumber}`); previous = e.timeDays
  }
  return true
}
export const waterInjectionPlanRecords = i => {
  const p = i?.waterInjectionControlPlanContext
  if (i?.schemaVersion !== 'eclipse-data-inspection/4' || !['FIELD', 'METRIC'].includes(i.unitSystem) || !Array.isArray(i.packageFiles) || i.packageFiles.length < 2 ||
    !shape(p, ['schemaVersion', 'startDate', 'endTimeDays', 'records']) || p.schemaVersion !== 'eclipse-water-injection-control-plan-context/1' || day(p.startDate) === null ||
    !integer(p.endTimeDays, 1, 3000000) || !Array.isArray(p.records) || !p.records.length || p.records.length > 1000 || !Array.isArray(i.scheduleTimeline) || i.scheduleTimeline.length < 2) return []
  const start = day(p.startDate), reports = new Set(), months = ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC']
  let previousDate = start
  for (const event of i.scheduleTimeline) {
    if (event.kind !== 'DATES' || !Array.isArray(event.records) || !event.records.length) return []
    for (const r of event.records) {
      if (typeof r.year !== 'string' || !/^\d{4}$/.test(r.year) || typeof r.day !== 'string' || !/^(?:[1-9]|[12][0-9]|3[01])$/.test(r.day) || !(r.time === null || r.time === '00:00:00')) return []
      const month = months.indexOf(r.month) + 1, date = day(`${r.year}-${String(month).padStart(2, '0')}-${r.day.padStart(2, '0')}`)
      if (!month || date === null || date <= previousDate) return []
      previousDate = date; reports.add(date - start)
    }
  }
  if (previousDate - start !== p.endTimeDays) return []
  const m = i.scheduleMetadata, unit = i.unitSystem === 'FIELD' ? 'STB/day' : 'sm3/day'
  if (!Array.isArray(m?.wells) || !Array.isArray(m?.records) || !Array.isArray(m?.completions) || !Array.isArray(m?.groups) || m.groups.length ||
    m.records.some(r => !['WCONPROD', 'WCONINJE', 'COMPDAT'].includes(r.keyword)) || m.records.filter(r => r.keyword === 'WCONINJE' && ['WAT', 'WATER'].includes(r.values?.[1])).length !== p.records.length) return []
  const seen = new Set(), physical = new Set(), initial = new Set(), phases = new Set(); let previous = -1
  for (const c of p.records) {
    if (!shape(c, recordFields) || !well(c.well) || !path(c.sourceFile) || !hash(c.sourceSha256) || !integer(c.lineNumber, 1, 2147483647) || !integer(c.timeDays, 0, p.endTimeDays - 1) ||
      day(c.date) === null || day(c.date) - start !== c.timeDays || c.timeDays < previous || c.timeDays > 0 && !reports.has(c.timeDays) || c.unit !== unit ||
      !positive(c.originalInjectionRate) || !positive(c.bhpLimit) || seen.has(`${c.well}:${c.timeDays}`) || physical.has(`${c.sourceFile}:${c.lineNumber}`)) return []
    if (c.timeDays === 0) initial.add(c.well)
    else { if (!initial.has(c.well)) return []; phases.add(c.timeDays) }
    if (i.packageFiles.filter(f => f.relativePath === c.sourceFile && f.sha256 === c.sourceSha256).length !== 1 || m.wells.filter(w => w.name === c.well).length !== 1 ||
      m.records.some(r => r.keyword === 'WCONPROD' && r.values?.[0] === c.well)) return []
    const perfs = m.completions.filter(cmp => cmp.well === c.well)
    if (!perfs.length || perfs.some(cmp => cmp.status !== 'OPEN')) return []
    const records = m.records.filter(r => r.keyword === 'WCONINJE' && r.sourceFile === c.sourceFile && r.lineNumber === c.lineNumber)
    if (records.length !== 1) return []
    const v = expand(records[0].values)
    if (!v || v.length < 7 || v.length > 10 || v[0] !== c.well || !['WAT', 'WATER'].includes(v[1]) || v[2] !== 'OPEN' || v[3] !== 'RATE' ||
      !number(v[4], c.originalInjectionRate) || v[5] !== '' || !number(v[6], c.bhpLimit) || v.slice(7).some(x => x !== '' && x !== '0')) return []
    seen.add(`${c.well}:${c.timeDays}`); physical.add(`${c.sourceFile}:${c.lineNumber}`); previous = c.timeDays
  }
  return initial.size && phases.size >= 2 ? p.records : []
}
export const waterInjectionPlanMatches = (p, i) => {
  if (!validWaterInjectionPlan(p)) return false
  const records = waterInjectionPlanRecords(i); let previous = -1
  if (!records.length) return false
  for (const e of p.edits) {
    const index = records.findIndex(c => editFields.filter(k => k !== 'targetInjectionRate').every(k => c[k] === e[k]))
    if (index <= previous) return false
    previous = index
  }
  return true
}
export const buildWaterInjectionPlan = ({ inspection, baselineRunId, edits }) => {
  if (!Array.isArray(edits) || edits.some(e => !positive(e.targetInjectionRate))) return null
  const p = { schemaVersion: 'eclipse-water-injection-plan-parameters/1', baselineRunId,
    edits: edits.filter(e => e.targetInjectionRate !== e.originalInjectionRate).map(e => Object.fromEntries(editFields.map(k => [k, e[k]]))) }
  return waterInjectionPlanMatches(p, inspection) ? p : null
}

export const waterInjectionPlanKey = c => `${c.sourceFile}:${c.lineNumber}:${c.well}`
export function waterInjectionPlanPeriods(inspection) {
  const records = waterInjectionPlanRecords(inspection)
  return records.map(c => {
    const next = records.find(r => r.well === c.well && r.timeDays > c.timeDays)
    return { ...c, nextTimeDays: next?.timeDays ?? inspection.waterInjectionControlPlanContext.endTimeDays, nextDate: next?.date ?? null }
  })
}
function curve(s,keyword,well,unit) {
 const rows=s?.series?.filter(c=>c.keyword===keyword && c.objectName===well)
 if(rows?.length!==1 || typeof rows[0].unit!=='string' || rows[0].unit.trim().toUpperCase()!==unit.toUpperCase() || !rows[0].points?.length)return null
 const p=rows[0].points
 if(p.some(p=>!Number.isFinite(p.timeDays) || p.timeDays<0 || !Number.isFinite(p.value)) || new Set(p.map(p=>p.timeDays)).size!==p.length)return null
 return new Map(p.map(p=>[p.timeDays,p.value]))
}
export function waterInjectionPlanResponse(summary,baseline,p,inspection) {
 const empty={available:false,consistent:false,verified:false,constrained:false,rows:[],edits:[]}
 if(!waterInjectionPlanMatches(p,inspection))return empty
 const records=waterInjectionPlanPeriods(inspection),wells=[...new Set(p.edits.map(e=>e.well))],flowUnit=inspection.unitSystem==='FIELD'?'STB/day':'sm3/day',pressureUnit=inspection.unitSystem==='FIELD'?'psia':'barsa'
 const tol=n=>Math.max(.0001,Math.abs(n)*1e-6),ptol=n=>Math.max(.1,Math.abs(n)*1e-6),selected=new Map(p.edits.map(e=>[waterInjectionPlanKey(e),e]))
 function data(s){
  const curves=wells.map(well=>({well,rate:curve(s,'WWIR',well,flowUnit),pressure:curve(s,'WBHP',well,pressureUnit)})),maps=curves.flatMap(c=>[c.rate,c.pressure])
  if(maps.some(m=>!m))return null
  const times=[...maps[0].keys()].filter(t=>t>0).sort((a,b)=>a-b)
  if(times.at(-1)!==inspection.waterInjectionControlPlanContext?.endTimeDays || maps.some(m=>[...m.keys()].filter(t=>t>0).length!==times.length || times.some(t=>!m.has(t))))return null
  return curves.flatMap(c=>times.map(timeDays=>{
   const record=records.filter(r=>r.well===c.well && r.timeDays<timeDays).at(-1)
   if(!record || timeDays>record.nextTimeDays)return null
   return {record,timeDays,well:c.well,rate:c.rate.get(timeDays),bhp:c.pressure.get(timeDays)}
  }))
 }
 const current=data(summary),old=data(baseline)
 if(!current || !old || current.some(r=>!r) || old.some(r=>!r))return empty
 const oldMap=new Map(old.map(r=>[`${r.well}:${r.timeDays}`,r]))
 const valid=r=>r.rate>=0 && r.bhp<=r.record.bhpLimit+ptol(r.record.bhpLimit)
 const consistent=old.every(r=>valid(r) && r.rate<=r.record.originalInjectionRate+tol(r.record.originalInjectionRate)) && current.every(r=>valid(r) && r.rate<=(selected.get(waterInjectionPlanKey(r.record))?.targetInjectionRate??r.record.originalInjectionRate)+tol(selected.get(waterInjectionPlanKey(r.record))?.targetInjectionRate??r.record.originalInjectionRate))
 const rows=current.map(r=>{
  const e=selected.get(waterInjectionPlanKey(r.record)),b=oldMap.get(`${r.well}:${r.timeDays}`),target=e?.targetInjectionRate??r.record.originalInjectionRate
  return {timeDays:r.timeDays,well:r.well,date:r.record.date,nextDate:r.record.nextDate,originalInjectionRate:r.record.originalInjectionRate,target,rate:r.rate,baselineRate:b?.rate??null,bhp:r.bhp,bhpLimit:r.record.bhpLimit,
   modified:!!e,recordKey:waterInjectionPlanKey(r.record),compared:!!b,changed:!!b && Math.abs(r.rate-b.rate)>tol(b.rate),targetReached:Math.abs(r.rate-target)<=tol(target),atBhpLimit:Math.abs(r.bhp-r.record.bhpLimit)<=ptol(r.record.bhpLimit)}
 })
 const edits=p.edits.map(e=>{
  const points=rows.filter(r=>r.recordKey===waterInjectionPlanKey(e))
  return {...e,pointCount:points.length,targetCount:points.filter(r=>r.targetReached).length,changedCount:points.filter(r=>r.changed).length}
 })
 return {available:true,consistent,verified:consistent && rows.every(r=>r.targetReached) && edits.every(e=>e.pointCount>0 && e.changedCount>0),constrained:consistent && rows.some(r=>!r.targetReached && r.atBhpLimit),rows,edits,flowUnit,pressureUnit}
}
