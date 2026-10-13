import test from 'node:test'
import assert from 'node:assert/strict'
import { buildGroupWaterProduction, groupWaterProductionResponse, validGroupWaterProduction } from '../src/views/SoftwareIntegration/eclipseGroupWaterProduction.js'
import { validGroupRate } from '../src/views/SoftwareIntegration/eclipseGroupRate.js'
const inspection={schemaVersion:'eclipse-data-inspection/10',caseName:'CASE.DATA',unitSystem:'FIELD',packageFiles:[{relativePath:'CASE.DATA'}],scheduleTimeline:[{sourceFile:'CASE.DATA',lineNumber:20}],scheduleMetadata:{groups:[],wells:['P1','P2'].map(name=>({name,group:'G'}))},waterProductionGroupControls:[{group:'G',sourceFile:'CASE.DATA',lineNumber:17,controlMode:'WRAT',unit:'STB/day',originalTargetRate:2000,members:['P1','P2'].map(well=>({well,bhpLimit:1000,oilRateLimit:4000}))}]}
const p=buildGroupWaterProduction({inspection,baselineRunId:1,group:'G',targetRate:1000})
const summary=(g,b,oil=1000)=>({series:[['GWPR','G',g,'STB/DAY'],['WWPR','P1',g/2,'STB/DAY'],['WWPR','P2',g/2,'STB/DAY'],['WBHP','P1',b,'PSIA'],['WBHP','P2',b,'PSIA'],['WOPR','P1',oil,'STB/DAY'],['WOPR','P2',oil,'STB/DAY']].map(([keyword,objectName,value,unit])=>({keyword,objectName,unit,points:[{timeDays:1,value}]}))})
test('WRAT has its own contract and uses water production curves',()=>{
 assert.ok(validGroupWaterProduction(p));assert.equal(validGroupRate(p),false)
 assert.equal(validGroupWaterProduction({...p,controlMode:'GRAT'}),false)
 assert.equal(validGroupWaterProduction({...p,unit:'Mscf/day'}),false)
 assert.equal(groupWaterProductionResponse(summary(1000,3000),summary(2000,2500),p,inspection).verified,true)
})
test('existing oil limits explain unattainable water targets without claiming BHP was reached',()=>{
 const r=groupWaterProductionResponse(summary(7000,3000,4000),summary(2000,2500),{...p,targetRate:20000},inspection)
 assert.equal(r.verified,false);assert.equal(r.constrained,true)
 assert.ok(r.members.every(m=>m.atOilRateLimit && !m.atBhpLimit))
})
test('oil or pressure violations, baseline sum tampering and absent oil curves cannot pass',()=>{
 for(const fault of ['oil','bhp','missing','duplicate','unit','baselineSum']) {
  const s=summary(1000,3000),old=summary(2000,2500)
  if(fault==='oil')s.series[5].points[0].value=5000
  if(fault==='bhp')s.series[3].points[0].value=900
  if(fault==='missing')s.series.pop()
  if(fault==='duplicate')s.series.push(structuredClone(s.series[5]))
  if(fault==='unit')s.series[5].unit='MSCF/DAY'
  if(fault==='baselineSum')old.series[1].points[0].value=800
  assert.equal(groupWaterProductionResponse(s,old,p,inspection).verified,false,fault)
 }
})
test('one later attained time never hides an earlier rate-limited point',()=>{
 const s=summary(1000,3000),old=summary(2000,2500),early=summary(700,3000,4000)
 s.series.forEach((c,n)=>{c.points[0].timeDays=2;c.points.unshift(early.series[n].points[0]);old.series[n].points.push({...old.series[n].points[0],timeDays:2})})
 const r=groupWaterProductionResponse(s,old,p,inspection)
 assert.equal(r.verified,false);assert.equal(r.constrained,true);assert.deepEqual(r.rows.map(r=>r.targetReached),[false,true])
})
test('missing limit and reordered members never build a scenario',()=>{
 const i=structuredClone(inspection);delete i.waterProductionGroupControls[0].members[0].oilRateLimit
 assert.equal(buildGroupWaterProduction({inspection:i,baselineRunId:1,group:'G',targetRate:1000}),null)
 assert.equal(groupWaterProductionResponse(summary(1000,3000),summary(2000,2500),{...p,members:['P2','P1']},inspection).available,false)
})
