import test from 'node:test'
import assert from 'node:assert/strict'
import { buildGroupInjection, validGroupInjection, groupInjectionResponse } from '../src/views/SoftwareIntegration/eclipseGroupInjection.js'
import { productionTargetRecords } from '../src/views/SoftwareIntegration/eclipseProductionTarget.js'
import { pressureControlRecords } from '../src/views/SoftwareIntegration/eclipsePressureControl.js'
import { productionControlRecords } from '../src/views/SoftwareIntegration/eclipseProductionControl.js'
import { newDatesWindows } from '../src/views/SoftwareIntegration/eclipseNewDates.js'

const inspection={schemaVersion:'eclipse-data-inspection/7',caseName:'CASE.DATA',unitSystem:'FIELD',packageFiles:[{relativePath:'CASE.DATA'}],scheduleTimeline:[{sourceFile:'CASE.DATA',lineNumber:20}],
  scheduleMetadata:{groups:[],wells:['P1','P2'].map(name=>({name,group:'G'}))},
  injectionGroupControls:[{group:'G',sourceFile:'CASE.DATA',lineNumber:17,controlMode:'RATE',injectionPhase:'WATER',unit:'STB/day',originalTargetRate:60000,members:['P1','P2'].map(well=>({well,bhpLimit:6000}))}]}
const p=buildGroupInjection({inspection,baselineRunId:1,group:'G',targetRate:30000})
test('group deck does not offer legacy editors whose Worker scope excludes GCONINJE',()=>{
  const i=structuredClone(inspection)
  i.scheduleMetadata.wells.push({name:'PX',group:'OTHER'})
  i.scheduleMetadata.records=[{keyword:'WCONPROD',sourceFile:'CASE.DATA',lineNumber:12,values:['PX','OPEN','ORAT','20000','4*','2500']}]
  assert.deepEqual(productionTargetRecords(i),[]);assert.deepEqual(pressureControlRecords(i),[])
  assert.deepEqual(productionControlRecords(i),[]);assert.deepEqual(newDatesWindows(i),[])
})
const summary=(g,b)=>({series:[['GWIR','G',g,'STB/DAY'],['WWIR','P1',g/2,'STB/DAY'],['WWIR','P2',g/2,'STB/DAY'],['WBHP','P1',b,'PSIA'],['WBHP','P2',b,'PSIA']].map(([keyword,objectName,value,unit])=>({keyword,objectName,unit,points:[{timeDays:1,value}]}))})
test('group total, every member response and BHP prove allocation; pressure-limited target warns',()=>{
  assert.ok(validGroupInjection(p))
  assert.equal(groupInjectionResponse(summary(30000,5500),summary(48000,6000),p,inspection).verified,true)
  const limited=groupInjectionResponse(summary(48000,6000),summary(48000,6000),{...p,targetRate:80000},inspection)
  assert.equal(limited.verified,false);assert.equal(limited.constrained,true)
})
test('missing, wrong unit, duplicate identity, changed membership, noncommon time never verify',()=>{
  for(const fault of ['missing','unit','duplicate','time','sum','bhp']){
    const s=summary(30000,5500)
    if(fault==='missing')s.series.pop()
    if(fault==='unit')s.series[1].unit='Mscf/day'
    if(fault==='duplicate')s.series.push(structuredClone(s.series[0]))
    if(fault==='time')s.series[1].points[0].timeDays=2
    if(fault==='sum')s.series[1].points[0].value=100
    if(fault==='bhp')s.series[3].points[0].value=6100
    assert.equal(groupInjectionResponse(s,summary(48000,6000),p,inspection).verified,false,fault)
  }
  assert.equal(groupInjectionResponse(summary(30000,5500),summary(48000,6000),{...p,members:['P1','OTHER']},inspection).available,false)
  assert.equal(buildGroupInjection({inspection,baselineRunId:1,group:'G',targetRate:60000}),null)
})
