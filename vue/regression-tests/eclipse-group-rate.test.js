import test from 'node:test'
import assert from 'node:assert/strict'
import { buildGroupRate, validGroupRate, groupRateResponse } from '../src/views/SoftwareIntegration/eclipseGroupRate.js'
import { productionTargetRecords } from '../src/views/SoftwareIntegration/eclipseProductionTarget.js'
import { pressureControlRecords } from '../src/views/SoftwareIntegration/eclipsePressureControl.js'
import { productionControlRecords } from '../src/views/SoftwareIntegration/eclipseProductionControl.js'
import { newDatesWindows } from '../src/views/SoftwareIntegration/eclipseNewDates.js'
import { validGroupInjection } from '../src/views/SoftwareIntegration/eclipseGroupInjection.js'

const inspection={schemaVersion:'eclipse-data-inspection/9',caseName:'CASE.DATA',unitSystem:'FIELD',packageFiles:[{relativePath:'CASE.DATA'}],scheduleTimeline:[{sourceFile:'CASE.DATA',lineNumber:20}],
  scheduleMetadata:{groups:[],wells:['P1','P2'].map(name=>({name,group:'G'}))},
  groupRateControls:[{group:'G',sourceFile:'CASE.DATA',lineNumber:17,controlMode:'GRAT',unit:'Mscf/day',originalTargetRate:60000,members:['P1','P2'].map(well=>({well,bhpLimit:2500}))}]}
const p=buildGroupRate({inspection,baselineRunId:1,group:'G',targetRate:30000})
test('GAS uses its independent contract and cannot accept liquid units or water curves',()=>{
  assert.equal(validGroupInjection(p),false)
  assert.equal(validGroupRate({...p,controlMode:'WRAT'}),false)
  assert.equal(validGroupRate({...p,unit:'STB/day'}),false)
  assert.equal(validGroupRate({...p,schemaVersion:'eclipse-group-injection-parameters/1'}),false)
  const water=summary(30000,4000)
  water.series.forEach(s=>{ if(s.keyword==='GGPR')s.keyword='GLPR'; if(s.keyword==='WGPR')s.keyword='WLPR' })
  assert.equal(groupRateResponse(water,summary(48000,2500),p,inspection).available,false)
})
test('group deck does not offer legacy editors whose Worker scope excludes GCONPROD',()=>{
  const i=structuredClone(inspection)
  i.scheduleMetadata.wells.push({name:'PX',group:'OTHER'})
  i.scheduleMetadata.records=[{keyword:'WCONPROD',sourceFile:'CASE.DATA',lineNumber:12,values:['PX','OPEN','ORAT','20000','4*','2500']}]
  assert.deepEqual(productionTargetRecords(i),[]);assert.deepEqual(pressureControlRecords(i),[])
  assert.deepEqual(productionControlRecords(i),[]);assert.deepEqual(newDatesWindows(i),[])
})
const summary=(g,b)=>({series:[['GGPR','G',g,'MSCF/DAY'],['WGPR','P1',g/2,'MSCF/DAY'],['WGPR','P2',g/2,'MSCF/DAY'],['WBHP','P1',b,'PSIA'],['WBHP','P2',b,'PSIA']].map(([keyword,objectName,value,unit])=>({keyword,objectName,unit,points:[{timeDays:1,value}]}))})
test('group total, every member response and BHP prove allocation; pressure-limited target warns',()=>{
  assert.ok(validGroupRate(p))
  assert.equal(groupRateResponse(summary(30000,4000),summary(48000,2500),p,inspection).verified,true)
  const limited=groupRateResponse(summary(48000,2500),summary(48000,2500),{...p,targetRate:80000},inspection)
  assert.equal(limited.verified,false);assert.equal(limited.constrained,true)
})
test('a later attainable target cannot hide an earlier pressure-constrained point',()=>{
  const s=summary(30000,4000), old=summary(48000,2500), early=summary(20000,2500)
  s.series.forEach((curve,index)=>{
    curve.points[0].timeDays=2; curve.points.unshift(early.series[index].points[0])
    old.series[index].points.push({...old.series[index].points[0],timeDays:2})
  })
  const response=groupRateResponse(s,old,p,inspection)
  assert.equal(response.verified,false);assert.equal(response.constrained,true)
  assert.deepEqual(response.rows.map(r=>r.targetReached),[false,true])
})
test('missing, wrong unit, duplicate identity, changed membership, noncommon time never verify',()=>{
  for(const fault of ['missing','unit','duplicate','time','sum','bhp']){
    const s=summary(30000,4000)
    if(fault==='missing')s.series.pop()
    if(fault==='unit')s.series[1].unit='STB/day'
    if(fault==='duplicate')s.series.push(structuredClone(s.series[0]))
    if(fault==='time')s.series[1].points[0].timeDays=2
    if(fault==='sum')s.series[1].points[0].value=100
    if(fault==='bhp')s.series[3].points[0].value=2400
    assert.equal(groupRateResponse(s,summary(48000,2500),p,inspection).verified,false,fault)
  }
  assert.equal(groupRateResponse(summary(30000,4000),summary(48000,2500),{...p,members:['P1','OTHER']},inspection).available,false)
  assert.equal(buildGroupRate({inspection,baselineRunId:1,group:'G',targetRate:60000}),null)
})

test('LRAT uses liquid curves and rejects gas units, modes and tampered baseline sum',()=>{
  const i=structuredClone(inspection);i.groupRateControls[0].controlMode='LRAT';i.groupRateControls[0].unit='STB/day'
  const q=buildGroupRate({inspection:i,baselineRunId:1,group:'G',targetRate:30000})
  const liquid=s=>{s.series.forEach(r=>{if(r.keyword==='GGPR')r.keyword='GLPR';if(r.keyword==='WGPR')r.keyword='WLPR';if(r.keyword!=='WBHP')r.unit='STB/DAY'});return s}
  const current=liquid(summary(30000,4000)), old=liquid(summary(48000,2500))
  assert.equal(groupRateResponse(current,old,q,i).verified,true)
  assert.equal(validGroupRate({...q,unit:'Mscf/day'}),false)
  assert.equal(groupRateResponse(summary(30000,4000),summary(48000,2500),q,i).available,false)
  assert.equal(groupRateResponse(current,old,{...q,controlMode:'GRAT',unit:'sm3/day'},i).available,false)
  old.series[1].points[0].value=20000
  assert.equal(groupRateResponse(current,old,q,i).consistent,false)
})
test('duplicate native time and nonfinite pressure are unavailable',()=>{
  for(const fault of ['duplicateTime','nan']) {
    const s=summary(30000,4000)
    if(fault==='duplicateTime')s.series[0].points.push({...s.series[0].points[0]})
    else s.series[3].points[0].value=NaN
    assert.equal(groupRateResponse(s,summary(48000,2500),p,inspection).available,false)
  }
})
