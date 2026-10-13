import test from 'node:test'
import assert from 'node:assert/strict'
import { buildGroupGasInjection, validGroupGasInjection, groupGasInjectionResponse } from '../src/views/SoftwareIntegration/eclipseGroupGasInjection.js'
import { productionTargetRecords } from '../src/views/SoftwareIntegration/eclipseProductionTarget.js'
import { pressureControlRecords } from '../src/views/SoftwareIntegration/eclipsePressureControl.js'
import { productionControlRecords } from '../src/views/SoftwareIntegration/eclipseProductionControl.js'
import { newDatesWindows } from '../src/views/SoftwareIntegration/eclipseNewDates.js'
import { validGroupInjection } from '../src/views/SoftwareIntegration/eclipseGroupInjection.js'

const inspection={schemaVersion:'eclipse-data-inspection/8',caseName:'CASE.DATA',unitSystem:'FIELD',packageFiles:[{relativePath:'CASE.DATA'}],scheduleTimeline:[{sourceFile:'CASE.DATA',lineNumber:20}],
  scheduleMetadata:{groups:[],wells:['P1','P2'].map(name=>({name,group:'G'}))},
  gasInjectionGroupControls:[{group:'G',sourceFile:'CASE.DATA',lineNumber:17,controlMode:'RATE',injectionPhase:'GAS',unit:'Mscf/day',originalTargetRate:60000,members:['P1','P2'].map(well=>({well,bhpLimit:6000}))}]}
const p=buildGroupGasInjection({inspection,baselineRunId:1,group:'G',targetRate:30000})
test('GAS uses its independent contract and cannot accept liquid units or water curves',()=>{
  assert.equal(validGroupInjection(p),false)
  assert.equal(validGroupGasInjection({...p,injectionPhase:'WATER'}),false)
  assert.equal(validGroupGasInjection({...p,unit:'STB/day'}),false)
  assert.equal(validGroupGasInjection({...p,schemaVersion:'eclipse-group-injection-parameters/1'}),false)
  const water=summary(30000,5500)
  water.series.forEach(s=>{ if(s.keyword==='GGIR')s.keyword='GWIR'; if(s.keyword==='WGIR')s.keyword='WWIR' })
  assert.equal(groupGasInjectionResponse(water,summary(48000,6000),p,inspection).available,false)
})
test('group deck does not offer legacy editors whose Worker scope excludes GCONINJE',()=>{
  const i=structuredClone(inspection)
  i.scheduleMetadata.wells.push({name:'PX',group:'OTHER'})
  i.scheduleMetadata.records=[{keyword:'WCONPROD',sourceFile:'CASE.DATA',lineNumber:12,values:['PX','OPEN','ORAT','20000','4*','2500']}]
  assert.deepEqual(productionTargetRecords(i),[]);assert.deepEqual(pressureControlRecords(i),[])
  assert.deepEqual(productionControlRecords(i),[]);assert.deepEqual(newDatesWindows(i),[])
})
const summary=(g,b)=>({series:[['GGIR','G',g,'MSCF/DAY'],['WGIR','P1',g/2,'MSCF/DAY'],['WGIR','P2',g/2,'MSCF/DAY'],['WBHP','P1',b,'PSIA'],['WBHP','P2',b,'PSIA']].map(([keyword,objectName,value,unit])=>({keyword,objectName,unit,points:[{timeDays:1,value}]}))})
test('group total, every member response and BHP prove allocation; pressure-limited target warns',()=>{
  assert.ok(validGroupGasInjection(p))
  assert.equal(groupGasInjectionResponse(summary(30000,5500),summary(48000,6000),p,inspection).verified,true)
  const limited=groupGasInjectionResponse(summary(48000,6000),summary(48000,6000),{...p,targetRate:80000},inspection)
  assert.equal(limited.verified,false);assert.equal(limited.constrained,true)
})
test('a later attainable target cannot hide an earlier pressure-constrained point',()=>{
  const s=summary(30000,5500), old=summary(48000,6000), early=summary(20000,6000)
  s.series.forEach((curve,index)=>{
    curve.points[0].timeDays=2; curve.points.unshift(early.series[index].points[0])
    old.series[index].points.push({...old.series[index].points[0],timeDays:2})
  })
  const response=groupGasInjectionResponse(s,old,p,inspection)
  assert.equal(response.verified,false);assert.equal(response.constrained,true)
  assert.deepEqual(response.rows.map(r=>r.targetReached),[false,true])
})
test('missing, wrong unit, duplicate identity, changed membership, noncommon time never verify',()=>{
  for(const fault of ['missing','unit','duplicate','time','sum','bhp']){
    const s=summary(30000,5500)
    if(fault==='missing')s.series.pop()
    if(fault==='unit')s.series[1].unit='STB/day'
    if(fault==='duplicate')s.series.push(structuredClone(s.series[0]))
    if(fault==='time')s.series[1].points[0].timeDays=2
    if(fault==='sum')s.series[1].points[0].value=100
    if(fault==='bhp')s.series[3].points[0].value=6100
    assert.equal(groupGasInjectionResponse(s,summary(48000,6000),p,inspection).verified,false,fault)
  }
  assert.equal(groupGasInjectionResponse(summary(30000,5500),summary(48000,6000),{...p,members:['P1','OTHER']},inspection).available,false)
  assert.equal(buildGroupGasInjection({inspection,baselineRunId:1,group:'G',targetRate:60000}),null)
})
