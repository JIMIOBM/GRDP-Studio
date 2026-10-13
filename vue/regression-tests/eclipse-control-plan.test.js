import test from 'node:test'
import assert from 'node:assert/strict'
import { controlPlanRecords, controlPlanPeriods, buildControlPlan, validControlPlan, controlPlanResponse } from '../src/views/SoftwareIntegration/eclipseControlPlan.js'
import { controlPlanFixture } from '../tests/fixtures/eclipseControlPlan.js'

test('actual inspection service snapshot binds two wells at three dates with leap-day shifts',()=>{
 const {inspection,parameters,summary}=controlPlanFixture()
 assert.equal(controlPlanRecords(inspection).length,10);assert.ok(validControlPlan(parameters));assert.equal(parameters.edits.length,6)
 assert.deepEqual(parameters.edits.filter(e=>e.well==='PRODUCR1').map(e=>e.date),['1983-10-19','1984-10-18','1985-10-18'])
 const r=controlPlanResponse(summary(parameters),summary(),parameters,inspection);assert.equal(r.verified,true);assert.equal(r.edits.length,6);assert.equal(r.rows.length,10)
 assert.deepEqual(r.rows.filter(r=>r.well==='PRODUCR1').map(r=>r.target),[500,250,350,450,450])
})
test('report date boundary closes the old period and the next existing control overrides an initial edit',()=>{
 const {inspection,summary}=controlPlanFixture(),c=controlPlanRecords(inspection)[0]
 const p=buildControlPlan({inspection,baselineRunId:1,edits:[{...c,targetOilRate:400}]})
 const r=controlPlanResponse(summary(p),summary(),p,inspection);assert.equal(r.verified,true)
 assert.deepEqual(r.rows.map(r=>r.target),[400,500,500,500,500]);assert.equal(r.rows[0].nextDate,'1983-10-19');assert.equal(r.rows[1].modified,false)
 const period=controlPlanPeriods(inspection)[0];assert.equal(period.nextTimeDays,365)
})
test('the full initial and later override chain, raw original values and BHP must agree',()=>{
 for(const fault of ['missing','time','date','bhp','original','completion','mode']){
  const {inspection}=controlPlanFixture(),c=inspection.productionControlPlan.records[9]
  if(fault==='missing')inspection.productionControlPlan.records.pop()
  if(fault==='time')c.timeDays=1096
  if(fault==='date')c.date='1985-10-19'
  if(fault==='bhp')c.bhpLimit=2499
  if(fault==='original')c.originalOilRate=501
  if(fault==='completion')inspection.scheduleMetadata.completions[0].status='SHUT'
  if(fault==='mode')inspection.scheduleMetadata.records.find(r=>r.keyword==='WCONPROD').values[2]='LRAT'
  assert.deepEqual(controlPlanRecords(inspection),[],fault)
 }
})
test('strict source, date, order and values reject stale or duplicate edits',()=>{
 const {inspection,parameters,summary}=controlPlanFixture()
 for(const fault of ['source','date','time','original','unit','reverse','duplicate','zero','same','extra']){
  const p=structuredClone(parameters),e=p.edits[5]
  if(fault==='source')e.sourceFile='OTHER.DATA'
  if(fault==='date')e.date='1985-10-19'
  if(fault==='time')e.timeDays=1096
  if(fault==='original')e.originalOilRate=501
  if(fault==='unit')e.unit='RB/day'
  if(fault==='reverse')p.edits.reverse()
  if(fault==='duplicate')p.edits.push({...e})
  if(fault==='zero')e.targetOilRate=0
  if(fault==='same')e.targetOilRate=e.originalOilRate
  if(fault==='extra')p.extra=true
  assert.equal(controlPlanResponse(summary(parameters),summary(),p,inspection).available,false,fault)
 }
})
test('mixed time, clock, non-increasing dates and wrong end cannot become an editable plan',()=>{
 for(const fault of ['mixed','clock','order','end']){
  const {inspection}=controlPlanFixture()
  if(fault==='mixed')inspection.scheduleTimeline[0].kind='TSTEP'
  if(fault==='clock')inspection.scheduleTimeline[0].records[0].time='06:00:00'
  if(fault==='order')inspection.scheduleTimeline[1].records[0].year='1982'
  if(fault==='end')inspection.productionControlPlan.endTimeDays=3651
  assert.deepEqual(controlPlanRecords(inspection),[],fault)
 }
})
test('missing, duplicate, wrong-unit or truncated curves cannot pass; BHP is a lower bound',()=>{
 const {inspection,parameters,summary}=controlPlanFixture()
 for(const fault of ['missing','duplicate','unit','truncated','time','pressure']){
  const current=summary(parameters)
  if(fault==='missing')current.series.pop()
  if(fault==='duplicate')current.series.push(structuredClone(current.series[0]))
  if(fault==='unit')current.series[0].unit='RB/DAY'
  if(fault==='truncated')current.series.forEach(c=>c.points.pop())
  if(fault==='time')current.series[0].points[1].timeDays=731
  if(fault==='pressure')current.series[1].points[1].value=2000
  assert.equal(controlPlanResponse(current,summary(),parameters,inspection).verified,false,fault)
 }
})
test('every edit needs an actual response; all source periods must remain present',()=>{
 const {inspection,parameters,summary}=controlPlanFixture()
 const r=controlPlanResponse(summary(),summary(),parameters,inspection);assert.equal(r.verified,false)
 assert.ok(r.edits.every(e=>e.changedCount===0))
 const current=summary(parameters);current.series.forEach(c=>c.points.splice(1,1))
 assert.equal(controlPlanResponse(current,summary(),parameters,inspection).verified,false)
})
test('different adaptive grids retain every late constrained point even without a baseline match',()=>{
 const {inspection,parameters,summary}=controlPlanFixture(),current=summary(parameters),old=summary()
 const limited=summary(parameters,'partial')
 current.series.forEach((c,n)=>c.points.splice(-1,0,{...limited.series[n].points.at(-1),timeDays:3649}))
 const r=controlPlanResponse(current,old,parameters,inspection);assert.equal(r.available,true);assert.equal(r.consistent,true);assert.equal(r.verified,false);assert.equal(r.constrained,true)
 assert.ok(r.rows.filter(r=>r.timeDays===3649).every(r=>r.baselineRate===null && !r.targetReached))
})
