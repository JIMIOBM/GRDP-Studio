import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {buildNewWellParameters,newWellOutcome,newWellResponse,validNewWellParameters,newWellUnits} from '../src/views/SoftwareIntegration/eclipseNewWell.js'
const inspection=JSON.parse(readFileSync(new URL('../tests/fixtures/eclipseNewWell/inspection.json',import.meta.url),'utf8'))
const native=JSON.parse(readFileSync(new URL('../tests/fixtures/eclipseNewWell/native-acceptance.json',import.meta.url),'utf8'))
const run={id:485,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null}
const grid={nx:15,ny:15,nz:1,fileName:'CASE.EGRID'},field={keyword:'ACTNUM',file:'CASE.EGRID',dataType:'INTE',count:225,elementSize:4}
const artifact={id:1,name:'eclipse-output-CASE.EGRID',contentType:'application/octet-stream',sha256:'a'.repeat(64)}
const draft={well:'P2',group:'G',i:15,j:1,k:1,referenceDepth:4000,diameter:.2,kh:1000,skin:0,waterRate:100,bhpLimit:3000}
const build=(overrides={})=>buildNewWellParameters({run,inspection,grid,field,artifact,draft,activeValue:1,...overrides})
test('new well: independent initial water producer binds source, grid, explicit pressure and capacity',()=>{
 const p=build();assert.equal(validNewWellParameters(p),true);assert.equal(p.sourceSha256,native.sourceSha256);assert.equal(p.insertionLine,179)
 assert.equal(p.lengthUnit,'m');assert.equal(p.rateUnit,'SM3/DAY');assert.equal(p.pressureUnit,'BARSA')
 assert.deepEqual(newWellUnits('FIELD'),{length:'ft',rate:'STB/DAY',pressure:'PSIA'});assert.equal(newWellUnits('unknown'),null)
})
test('new well: conflicts, occupied/inactive cells, source/capacity/units and pseudo numbers reject',()=>{
 for(const bad of [{well:'P'},{group:'FIELD'},{group:'UNKNOWN'},{i:1},{i:16},{i:'15'},{waterRate:'100'},{bhpLimit:0},{diameter:3},{skin:-1}])assert.throws(()=>build({draft:{...draft,...bad}}))
 assert.throws(()=>build({activeValue:0}));assert.throws(()=>build({artifact:{...artifact,sha256:'bad'}}))
 for(const change of [v=>v.newWellContext.maxWells=2,v=>v.newWellContext.maxWellsPerGroup=2,v=>v.unitSystem='UNKNOWN',v=>v.newWellContext=null]){
  const v=structuredClone(inspection);change(v);assert.throws(()=>build({inspection:v}))}
 assert.equal(validNewWellParameters({...build(),pressureUnit:'PSIA'}),false);assert.equal(validNewWellParameters({...build(),extra:true}),false)
})
test('new well: every native status/rate/pressure/total report retained, pressure limited then shut',()=>{
 assert.equal(native.nativeVerified,true);assert.equal(native.explicitStateVerified,true)
 const out=newWellOutcome({series:native.target.series},build());assert.equal(out.available,true);assert.equal(out.rows.length,24);assert.equal(out.limitedReports,4);assert.equal(out.shutReports,16)
 assert.equal(out.cumulativeUnit,'SM3');assert.equal(out.rows.at(-1).stateLabel,'关井');assert.equal(out.rows.at(-1).bhp,0);assert.equal(out.rows.at(-1).total,1369.1064453125)
 const diff=newWellResponse({series:native.target.series},{series:native.baseline.series});assert.equal(diff.available,true)
 const added=diff.rows.filter(r=>r.well==='P2');assert.equal(added.length,72);assert.ok(added.every(r=>r.baseline===null&&r.difference===null))
 for(const row of out.rows)for(const [key,field] of [['WSTAT','state'],['WWPR','rate'],['WBHP','bhp'],['WWPT','total']])assert.equal(row[field],native.target.series.find(s=>s.keyword===key&&s.objectName==='P2').points.find(p=>p.timeDays===row.timeDays).value)
})
test('new well: missing/duplicate vectors, wrong units, invalid state and different clocks cannot claim actual outcome',()=>{
 for(const mutate of [s=>s.splice(s.findIndex(v=>v.keyword==='WSTAT'&&v.objectName==='P2'),1),s=>s.push(structuredClone(s.find(v=>v.keyword==='WBHP'&&v.objectName==='P2'))),
  s=>s.find(v=>v.keyword==='WBHP'&&v.objectName==='P2').unit='PSIA',s=>s.find(v=>v.keyword==='WSTAT'&&v.objectName==='P2').points[1].value=9,
  s=>s.find(v=>v.keyword==='WWPT'&&v.objectName==='P2').points.pop()]){const series=structuredClone(native.target.series);mutate(series);assert.equal(newWellOutcome({series},build()).available,false)}
})
