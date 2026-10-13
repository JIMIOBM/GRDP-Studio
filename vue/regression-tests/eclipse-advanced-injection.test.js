import test from 'node:test'
import assert from 'node:assert/strict'
import { advancedInjectionRecords, buildAdvancedInjection, advancedInjectionResponse, validAdvancedInjection } from '../src/views/SoftwareIntegration/eclipseAdvancedInjection.js'
import { validGroupGasInjection } from '../src/views/SoftwareIntegration/eclipseGroupGasInjection.js'
function fixture(mode) {
 const fraction=['REIN','VREP'].includes(mode), reservoir=['RESV','VREP'].includes(mode), phase=mode==='RATE'?'OIL':'GAS',unit=fraction?'fraction':reservoir?'RB/day':'STB/day',flowUnit=reservoir?'RB/DAY':phase==='OIL'?'STB/DAY':'MSCF/DAY'
 const members=['I1','I2'],refs=fraction?['P1','P2']:[],groupKeyword=reservoir?'GVIR':phase==='OIL'?'GOIR':'GGIR',wellKeyword=reservoir?'WVIR':phase==='OIL'?'WOIR':'WGIR'
 const inspection={schemaVersion:'eclipse-data-inspection/11',caseName:'CASE.DATA',unitSystem:'FIELD',packageFiles:[{relativePath:'CASE.DATA'}],scheduleTimeline:[{sourceFile:'CASE.DATA',lineNumber:20}],scheduleMetadata:{groups:[],wells:[...members.map(name=>({name,group:'I'})),...refs.map(name=>({name,group:'G'}))]},advancedInjectionGroupControls:[{group:'I',sourceFile:'CASE.DATA',lineNumber:17,controlMode:mode,injectionPhase:phase,unit,originalTargetRate:fraction?0.2:10000,members:members.map(well=>({well,bhpLimit:4900,surfaceRateLimit:50000})),referenceGroup:fraction?'G':null,referenceMembers:refs}]}
 const p=buildAdvancedInjection({inspection,baselineRunId:1,group:'I',targetRate:fraction?0.1:5000})
 const summary=(g,b,production=100000)=>({series:[[groupKeyword,'I',g,flowUnit],...members.map(w=>[wellKeyword,w,g/2,flowUnit]),...members.map(w=>['WBHP',w,b,'PSIA']),...(reservoir?members.map(w=>['WGIR',w,10000,'MSCF/DAY']):[]),...(fraction?[[mode==='REIN'?'GGPR':'GVPR','G',production,flowUnit],...refs.map(w=>[mode==='REIN'?'WGPR':'WVPR',w,production/2,flowUnit])]:[])].map(([keyword,objectName,value,unit])=>({keyword,objectName,unit,points:[{timeDays:1,value}]}))})
 return {inspection,p,summary,fraction,groupKeyword,wellKeyword}
}
for(const mode of ['RATE','RESV','REIN','VREP'])test(`${mode} uses its own volume or fraction with separate baseline source production`,()=>{
 const {inspection,p,summary,fraction}=fixture(mode)
 assert.equal(validAdvancedInjection(p),true);assert.equal(validGroupGasInjection(p),false)
 const response=advancedInjectionResponse(summary(fraction?12000:5000,3000,120000),summary(fraction?20000:10000,2500,100000),p,inspection)
 assert.equal(response.verified,true)
 if(fraction){assert.equal(response.rows[0].target,12000);assert.equal(response.rows[0].baselineTarget,20000);assert.equal(response.rows[0].actualRatio,0.1);assert.equal(response.rows[0].ratioError,0)}
})
test('ratio mismatch cannot be hidden by an attainable absolute rate or truncated source production',()=>{
 const {inspection,p,summary}=fixture('REIN')
 const s=summary(10000,3000,200000),old=summary(20000,2500)
 assert.equal(advancedInjectionResponse(s,old,p,inspection).verified,false)
 s.series.pop();assert.equal(advancedInjectionResponse(s,old,p,inspection).available,false)
})
test('explicit ratio tolerance reports real error and rejects beyond tolerance',()=>{
 const {inspection,p,summary}=fixture('VREP'),old=summary(20000,2500)
 const r=advancedInjectionResponse(summary(10005,3000),old,p,inspection)
 assert.equal(r.verified,true);assert.equal(r.fractionTolerance,0.0001);assert.ok(r.rows[0].ratioError>0)
 assert.equal(advancedInjectionResponse(summary(10020,3000),old,p,inspection).verified,false)
})
test('pressure constraints and partial attainment warn while invalid curves never pass',()=>{
 const {inspection,p,summary}=fixture('RESV'),old=summary(10000,2500),early=summary(4000,4900),s=summary(5000,3000)
 s.series.forEach((c,n)=>{c.points[0].timeDays=2;c.points.unshift(early.series[n].points[0]);old.series[n].points.push({...old.series[n].points[0],timeDays:2})})
 const r=advancedInjectionResponse(s,old,p,inspection);assert.equal(r.verified,false);assert.equal(r.constrained,true);assert.deepEqual(r.rows.map(r=>r.targetReached),[false,true])
 for(const fault of ['pressure','surface','unit','duplicate','time','sum']) {
  const s=summary(5000,3000),old=summary(10000,2500)
  if(fault==='pressure')s.series[3].points[0].value=5000
  if(fault==='surface')s.series[5].points[0].value=60000
  if(fault==='unit')s.series[0].unit='MSCF/DAY'
  if(fault==='duplicate')s.series.push(structuredClone(s.series[1]))
  if(fault==='time')s.series[1].points=[]
  if(fault==='sum')old.series[1].points[0].value=1000
  assert.equal(advancedInjectionResponse(s,old,p,inspection).verified,false,fault)
 }
})
test('changed reference group, incomplete source membership, unsupported phase and extra fields reject',()=>{
 const {inspection,p,summary}=fixture('REIN')
 for(const change of [{referenceGroup:'OTHER'},{referenceMembers:['P2','P1']},{injectionPhase:'OIL'},{extra:1}])assert.equal(advancedInjectionResponse(summary(10000,3000),summary(20000,2500),{...p,...change},inspection).available,false)
 const i=structuredClone(inspection);i.scheduleMetadata.wells.push({name:'P3',group:'G'});assert.deepEqual(advancedInjectionRecords(i),[])
})
test('adaptive timesteps may differ between runs; every actual scenario point is still checked',()=>{
 const {inspection,p,summary}=fixture('REIN'),old=summary(20000,2500),s=summary(10000,3000)
 old.series.forEach(c=>c.points.push({...c.points[0],timeDays:3}))
 s.series.forEach(c=>c.points.push({...c.points[0],timeDays:2}))
 let r=advancedInjectionResponse(s,old,p,inspection)
 assert.equal(r.verified,true);assert.equal(r.rows[1].baselineRate,null);assert.equal(r.rows[1].baselineTarget,null);assert.equal(r.rows[1].compared,false)
 s.series.filter(c=>['GGIR','WGIR'].includes(c.keyword)).forEach(c=>{c.points[1].value/=2})
 s.series.filter(c=>c.keyword==='WBHP').forEach(c=>{c.points[1].value=4900})
 r=advancedInjectionResponse(s,old,p,inspection)
 assert.equal(r.verified,false);assert.equal(r.constrained,true);assert.deepEqual(r.rows.map(r=>r.targetReached),[true,false])
 // A missing point inside one run is still incomplete, not an adaptive-step difference.
 s.series[1].points.pop();assert.equal(advancedInjectionResponse(s,old,p,inspection).available,false)
})
