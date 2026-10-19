import test from 'node:test'
import assert from 'node:assert/strict'
import { forecastPresentation, forecastMetricSummary, forecastPresentationCsv } from '../src/views/SoftwareIntegration/eclipseForecastPresentation.js'
import { binaryRestartForecastFixture } from '../tests/fixtures/eclipseBinaryRestart.js'
import { compositionalForecastFixture } from '../tests/fixtures/eclipseCompositionalForecast.js'

const e100 = () => { const f = binaryRestartForecastFixture(); return { f, args: { kind:'e100',source:f.source,history:f.history,run:f.first,other:f.second,inspection:f.inspection } } }
const e300 = () => { const f = compositionalForecastFixture(); return { f, args: { kind:'e300',catalog:f.catalog,history:f.history,run:f.inherited,other:f.lower,inspection:f.inspection } } }
test('E100 binary catalog shows only real reports and separate future points without synthetic cutoff values', () => {
  const { args } = e100(), result = forecastPresentation(args)
  assert.ok(result.valid,result.reason); assert.equal(result.cutoff,2190); assert.equal(result.report,80); assert.equal(result.reports.length,81)
  const m = result.metrics.find(m=>m.keyword==='FOPT')
  assert.equal(m.historical.at(-1).timeDays,2190)
  assert.deepEqual(m.forecasts.map(f=>f.points.map(p=>p.timeDays)),[[2556,2921],[2556,2921]])
  const s=forecastMetricSummary(m); assert.equal(s.entries[0].increment,m.forecasts[0].points.at(-1).value-m.savedValue)
  assert.equal(s.common.timeDays,2921); assert.equal(s.common.difference,m.forecasts[0].points.at(-1).value-m.forecasts[1].points.at(-1).value)
})
test('E300 same SAVE state compares two independent forecasts and keeps duplicate FOPT vectors', () => {
  const {args}=e300(), result=forecastPresentation(args)
  assert.ok(result.valid,result.reason); assert.equal(result.cutoff,3285); assert.equal(result.reports.length,1); assert.equal(result.forecasts.length,2)
  const oils=result.metrics.filter(m=>m.keyword==='FOPT'); assert.equal(oils.length,2); assert.notEqual(oils[0].key,oils[1].key)
  assert.deepEqual(oils.map(m=>m.forecasts.map(f=>f.points.at(-1).value)),[[2593395,2497045],[2593395,2497045]])
  for(const m of oils){assert.equal(m.forecasts[0].points[0].timeDays,3286); assert.equal(forecastMetricSummary(m).common.difference,96350)}
  const pressure=result.metrics.find(m=>m.keyword==='FPR'); assert.equal(pressure.cumulative,false); assert.equal(forecastMetricSummary(pressure).entries[0].increment,null)
})
test('history SAVE card clips the uninterrupted history at the savepoint and creates no future',()=>{
  const {f,args}=e300(), result=forecastPresentation({...args,run:f.history,other:null})
  assert.ok(result.valid,result.reason); assert.equal(result.forecasts.length,0)
  assert.ok(result.metrics.every(m=>m.historical.at(-1).timeDays===3285 && m.forecasts.length===0))
})
test('portable forecast without historical summary never invents saved cumulative or future increments',()=>{
  const {f}=e300(), result=forecastPresentation({kind:'e300',catalog:f.catalog,run:f.portable,inspection:f.portableInspection,portable:true})
  assert.ok(result.valid,result.reason); assert.equal(result.historyId,null)
  const m=result.metrics.find(m=>m.keyword==='FOPT'); assert.equal(m.savedValue,null); assert.deepEqual(m.historical,[]); assert.equal(forecastMetricSummary(m).entries[0].increment,null)
  const rows=forecastPresentationCsv(result).split('\r\n').slice(1); assert.ok(rows.every(row=>row.endsWith(',"",""')))
})
test('comparison rejects foreign model, different catalog, duplicate alternative and incomplete forecasts',()=>{
  for(const mutate of [f=>f.lower.modelVersionId++,f=>f.lower.parameters.saveCatalogSha256='0'.repeat(64),f=>f.lower.id=f.inherited.id,f=>f.lower.parameters.targetGasRate=null,f=>f.lower.result.summary.series.forEach(s=>s.points.pop())]){
    const {f,args}=e300(); mutate(f); assert.equal(forecastPresentation(args).valid,false)
  }
})
test('same raw index with different keyword or unit never becomes a matching metric',()=>{
  for(const change of [{unit:'SM3'},{keyword:'WRONG'},{objectName:'OTHER'}]){
    const {f,args}=e300(), column=f.lower.result.summary.series.find(s=>s.keyword==='FOPT'); Object.assign(column,change)
    assert.equal(forecastPresentation(args).valid,false)
  }
})
test('last shared actual time is used without filling missing adaptive times or losing zero differences',()=>{
  const m={cumulative:true,savedValue:100,forecasts:[{runId:1,points:[{timeDays:2,value:101},{timeDays:3,value:103},{timeDays:5,value:110}]},{runId:2,points:[{timeDays:2,value:101},{timeDays:4,value:105},{timeDays:5,value:110}]}]}
  assert.deepEqual(forecastMetricSummary(m).common,{timeDays:5,first:110,second:110,difference:0})
  m.forecasts[1].points=[{timeDays:4,value:105}]; assert.equal(forecastMetricSummary(m).common,null)
})
test('CSV preserves raw identity and actual times, with increments restricted to true cumulative future data',()=>{
  const {args}=e300(), result=forecastPresentation(args), csv=forecastPresentationCsv(result), lines=csv.replace(/^\uFEFF/,'').split('\r\n').map(line=>line.split(',').map(t=>t.slice(1,-1)))
  const oil=result.metrics.find(m=>m.keyword==='FOPT'), future=lines.slice(1).find(row=>row[0]==='forecast'&&Number(row[1])===args.run.id&&Number(row[5])===oil.vectorIndex)
  assert.equal(Number(future[7]),3286); assert.equal(Number(future[10]),oil.savedValue); assert.equal(Number(future[11]),Number(future[9])-oil.savedValue)
  assert.ok(lines.slice(1).filter(row=>row[0]==='history'||row[3]==='FPR').every(row=>row[11]===''))
})
