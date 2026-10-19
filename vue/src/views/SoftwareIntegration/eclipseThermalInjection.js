const shape = (v, names) => v && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length === names.length && names.every(k => Object.hasOwn(v, k))
const integer = (v, min = 1, max = Number.MAX_SAFE_INTEGER) => Number.isSafeInteger(v) && v >= min && v <= max
const positive = v => typeof v === 'number' && Number.isFinite(v) && v > 0 && v <= 1e8
const well = v => typeof v === 'string' && /^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,7}$/.test(v)
const source = v => typeof v === 'string' && /^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.DATA$/i.test(v)
const hash = v => typeof v === 'string' && /^[a-f0-9]{64}$/.test(v)
export const thermalInjectionSchema = 'eclipse-thermal-injection-parameters/1'
export const thermalControlSchema = 'eclipse-thermal-injection-parameters/2'
export const thermalStageSchema = 'eclipse-thermal-injection-parameters/3'
export const thermalStageField = field => ['SURFACE_INJECTION_RATE','INJECTION_TEMPERATURE'].includes(field)
export const thermalInjectionLabels = { STEAM_QUALITY: '蒸汽干度', INJECTION_TEMPERATURE: '注入温度', SURFACE_INJECTION_RATE: '地面注入目标', BHP_UPPER_LIMIT: '注入BHP上限' }
const controlField = field => ['SURFACE_INJECTION_RATE', 'BHP_UPPER_LIMIT'].includes(field)
export const thermalInjectionUnit = unit => ({ fraction: '无量纲', degF: '°F', degC: '°C' }[unit] || unit)
const value = (field, unit, v) => typeof v === 'number' && Number.isFinite(v) && (field === 'STEAM_QUALITY' ? unit === 'fraction' && v >= 0 && v <= 1
  : field === 'INJECTION_TEMPERATURE' ? (unit === 'degF' && v > -459.67 || unit === 'degC' && v > -273.15) && v <= 1e6
  : field === 'SURFACE_INJECTION_RATE' ? ['STB/day', 'sm3/day'].includes(unit) && positive(v)
  : field === 'BHP_UPPER_LIMIT' && ['psia', 'barsa'].includes(unit) && positive(v))
export const validThermalInjection = p => shape(p, ['schemaVersion', 'baselineRunId', 'well', 'sourceFile', 'sourceSha256', 'lineNumber', 'field', 'unit', 'originalValue', 'targetValue', ...(p?.schemaVersion===thermalStageSchema?['startTimeDays']:[])]) &&
  [thermalInjectionSchema, thermalControlSchema, thermalStageSchema].includes(p.schemaVersion) && (p.schemaVersion!==thermalStageSchema || thermalStageField(p.field) && integer(p.startTimeDays,1,1e9)) && (p.schemaVersion !== thermalInjectionSchema || !controlField(p.field)) && integer(p.baselineRunId) && well(p.well) && source(p.sourceFile) && hash(p.sourceSha256) && integer(p.lineNumber, 1, 2147483647) &&
  value(p.field, p.unit, p.originalValue) && value(p.field, p.unit, p.targetValue) && p.originalValue !== p.targetValue
export const validThermalInjectionContext = (c, i) => {
  if (!shape(c, ['schemaVersion', 'sourceFile', 'sourceSha256', 'sourceSizeBytes', 'solver', 'componentCount', 'componentNames', 'fluidModel', 'records']) ||
    c.schemaVersion !== 'eclipse-thermal-injection-context/1' || !['eclipse-data-inspection/15', 'eclipse-data-inspection/23'].includes(i?.schemaVersion) || i.simulation?.solver !== 300 || i.simulation?.modelType !== 'THERMAL' ||
    i.simulation?.componentCount !== 3 || i.simulation?.fluidModel !== 'LIVEOIL' || i.simulation?.summaryProgramIdentifier !== 500 || c.solver !== 300 || c.componentCount !== 3 || c.fluidModel !== 'LIVEOIL' ||
    !Array.isArray(c.componentNames) || c.componentNames.length !== 3 || c.componentNames.some((n, j) => n !== i.simulation.componentNames?.[j]) ||
    !source(c.sourceFile) || c.sourceFile !== i.caseName || !hash(c.sourceSha256) || !integer(c.sourceSizeBytes, 1, 4 * 1024 * 1024) ||
    !Array.isArray(i.packageFiles) || i.packageFiles.length !== 1 || !Array.isArray(i.wellNames) || !['FIELD', 'METRIC'].includes(i.unitSystem) ||
    !Array.isArray(c.records) || !c.records.length || c.records.length > 1000) return false
  const f = i.packageFiles[0], field = i.unitSystem === 'FIELD'
  if (f.relativePath !== c.sourceFile || f.sha256 !== c.sourceSha256 || f.sizeBytes !== c.sourceSizeBytes) return false
  const names = new Set(), rows = new Set()
  for (const r of c.records) {
    if (!shape(r, ['well', 'lineNumber', 'originalSteamQuality', 'originalTemperature', 'temperatureUnit', 'surfaceInjectionRate', 'rateUnit', 'bhpUpperLimit', 'controlLineNumber']) ||
      !well(r.well) || !i.wellNames.includes(r.well) || names.has(r.well) || !integer(r.lineNumber, 1, c.sourceSizeBytes) || rows.has(r.lineNumber) ||
      !integer(r.controlLineNumber, 1, r.lineNumber - 1) || rows.has(r.controlLineNumber) || r.temperatureUnit !== (field ? 'degF' : 'degC') || r.rateUnit !== (field ? 'STB/day' : 'sm3/day') ||
      !value('STEAM_QUALITY', 'fraction', r.originalSteamQuality) || !value('INJECTION_TEMPERATURE', r.temperatureUnit, r.originalTemperature) || !positive(r.surfaceInjectionRate) || !positive(r.bhpUpperLimit)) return false
    names.add(r.well); rows.add(r.lineNumber); rows.add(r.controlLineNumber)
  }
  return true
}
export const thermalInjectionRecords = i => validThermalInjectionContext(i?.thermalInjectionContext, i) ? i.thermalInjectionContext.records : []
export function thermalStageDays(i) {
  if(!thermalInjectionRecords(i).length || !Array.isArray(i.scheduleTimeline) || i.scheduleTimeline.length!==1)return []
  const clock=i.scheduleTimeline[0],c=i.thermalInjectionContext
  if(clock.kind!=='TSTEP'||clock.sourceFile!==c.sourceFile||!Array.isArray(clock.steps)||clock.steps.length<2||clock.steps.length>1000)return []
  let total=0;const days=[]
  for(const [index,step] of clock.steps.entries()){
    if(typeof step!=='string'||!/^[1-9][0-9]{0,8}(?:\.0+)?$/.test(step))return []
    total+=Number(step);if(!Number.isSafeInteger(total)||total>1e9)return []
    if(index<clock.steps.length-1)days.push(total)
  }
  return days
}
const original = (r, field) => ({ STEAM_QUALITY: ['fraction', r.originalSteamQuality], INJECTION_TEMPERATURE: [r.temperatureUnit, r.originalTemperature],
  SURFACE_INJECTION_RATE: [r.rateUnit, r.surfaceInjectionRate], BHP_UPPER_LIMIT: [r.rateUnit === 'STB/day' ? 'psia' : 'barsa', r.bhpUpperLimit] }[field])
export const matchesThermalInjection = (p, i) => validThermalInjection(p) && thermalInjectionRecords(i).some(r => {
  const v = original(r, p.field)
  return r.well === p.well && (controlField(p.field) ? r.controlLineNumber : r.lineNumber) === p.lineNumber && v?.[0] === p.unit && v[1] === p.originalValue
}) && p.sourceFile === i.thermalInjectionContext.sourceFile && p.sourceSha256 === i.thermalInjectionContext.sourceSha256 && (p.schemaVersion!==thermalStageSchema || thermalStageDays(i).includes(p.startTimeDays))
export const buildThermalInjection = ({ inspection, baselineRunId, well: name, field, targetValue, startTimeDays = 0 }) => {
  const r = thermalInjectionRecords(inspection).find(r => r.well === name), c = inspection?.thermalInjectionContext
  if (!r || !Object.hasOwn(thermalInjectionLabels, field) || !integer(startTimeDays,0,1e9) || startTimeDays>0 && (!thermalStageField(field)||!thermalStageDays(inspection).includes(startTimeDays))) return null
  const [unit, originalValue] = original(r, field)
  const p = { schemaVersion: startTimeDays>0 ? thermalStageSchema : controlField(field) ? thermalControlSchema : thermalInjectionSchema, baselineRunId, well: name, sourceFile: c.sourceFile, sourceSha256: c.sourceSha256,
    lineNumber: controlField(field) ? r.controlLineNumber : r.lineNumber, field, unit, originalValue, targetValue, ...(startTimeDays>0?{startTimeDays}:{}) }
  return validThermalInjection(p) ? p : null
}
const points = s => Array.isArray(s?.points) && s.points.length > 0 && s.points.every((p, j) => typeof p.value === 'number' && Number.isFinite(p.value) && typeof p.timeDays === 'number' && Number.isFinite(p.timeDays) && p.timeDays >= 0 && (!j || p.timeDays > s.points[j - 1].timeDays))
export const thermalInjectionResponse = (summary, baseline, p, i) => {
  if (!matchesThermalInjection(p, i) || summary?.startDate !== baseline?.startDate || !Array.isArray(summary?.series) || !Array.isArray(baseline?.series)) return null
  const record = thermalInjectionRecords(i).find(r => r.well === p.well), field = i.unitSystem === 'FIELD', pressureUnit = field ? 'PSIA' : 'BARSA'
  const staged=p.schemaVersion===thermalStageSchema, start=staged?p.startTimeDays:0
  const metrics = [], rows = []
  const collect = (keyword, objectName, label, unit, required) => {
    const a = summary.series.filter(s => s.keyword === keyword && s.objectName === objectName), b = baseline.series.filter(s => s.keyword === keyword && s.objectName === objectName)
    if (!a.length && !b.length && !required) return true
    if (a.length !== 1 || b.length !== 1 || !points(a[0]) || !points(b[0]) || a[0].vectorIndex !== b[0].vectorIndex || a[0].number !== b[0].number ||
      typeof a[0].unit !== 'string' || !a[0].unit || a[0].unit !== b[0].unit || unit && a[0].unit.toUpperCase() !== unit) return false
    const before = new Map(b[0].points.map(v => [v.timeDays, v.value]))
    const data = a[0].points.map(v => ({ keyword, objectName, label, unit: a[0].unit, timeDays: v.timeDays, value: v.value, baselineValue: before.get(v.timeDays) ?? null,
      ...(staged?{phase:v.timeDays<=start?'调整前':'调整后'}:{}),difference: before.has(v.timeDays) ? v.value - before.get(v.timeDays) : null }))
    rows.push(...data); metrics.push({ keyword, objectName, label, unit: a[0].unit, finalValue: data.at(-1).value, finalTime: data.at(-1).timeDays,
      changed: data.filter(v => v.difference !== null && v.difference !== 0).length, pointCount: data.length, commonCount: data.filter(v => v.baselineValue !== null).length })
    return true
  }
  if (!collect('WWIR', p.well, '实际地面注入量', field ? 'STB/DAY' : 'SM3/DAY', true) ||
    !collect('WWIT', p.well, '实际累计地面注入量', field ? 'STB' : 'SM3', true) || !collect('WBHP', p.well, '注入井井底压力', pressureUnit, true) || !collect('FHLR', null, '全场热损失率', null, true) ||
    !collect('FHLT', null, '全场累计热损失', null, false) || !collect('WTEMP', p.well, '注入井实际井温', field ? 'DEG F' : 'DEG C', false)) return null
  for (const name of i.wellNames.filter(n => n !== p.well)) if (!collect('WOPR', name, `${name}产油量`, field ? 'STB/DAY' : 'SM3/DAY', false)) return null
  const bhpUpperLimit = p.field === 'BHP_UPPER_LIMIT' ? p.targetValue : record.bhpUpperLimit
  const surfaceInjectionTarget = p.field === 'SURFACE_INJECTION_RATE' ? p.targetValue : record.surfaceInjectionRate
  const pressure = rows.filter(r => r.keyword === 'WBHP'), rates = rows.filter(r => r.keyword === 'WWIR')
  const consistent = pressure.every(r => r.value >= 0 && r.value <= bhpUpperLimit + .1) && rates.every(r => r.value >= 0 && r.value <= (staged && r.timeDays<=start ? record.surfaceInjectionRate : surfaceInjectionTarget) + .001)
  const pressureChanged = metrics.find(m => m.keyword === 'WBHP').changed > 0, heatChanged = metrics.find(m => m.keyword === 'FHLR').changed > 0
  const preStageVerified=!staged || summary.series.filter(s=>!['TCPU','ELAPSED','TCPUTS','TCPUDAY'].includes(s.keyword)).every(s=>{
    const original=baseline.series.find(b=>b.vectorIndex===s.vectorIndex&&b.keyword===s.keyword&&b.objectName===s.objectName&&b.number===s.number&&b.unit===s.unit)
    if(!original||!points(s)||!points(original))return false
    const a=s.points.filter(p=>p.timeDays<=start),b=original.points.filter(p=>p.timeDays<=start)
    return a.length>0&&a.length===b.length&&a.every((p,index)=>p.timeDays===b[index].timeDays&&p.value===b[index].value)
  })
  return { rows, metrics, consistent, verified: consistent && pressureChanged && heatChanged && preStageVerified,...(staged?{startTimeDays:start,preStageVerified}:{}), bhpUpperLimit, pressureUnit, surfaceInjectionTarget, rateUnit: record.rateUnit,
    temperatureAvailable: metrics.some(m => m.keyword === 'WTEMP'), pressureLimited: pressure.some(r => Math.abs(r.value - bhpUpperLimit) <= .1) }
}
