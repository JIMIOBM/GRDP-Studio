import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
import { buildNewCompletionParameters, validNewCompletionParameters, completionProducerWells, newCompletionResponse } from '../src/views/SoftwareIntegration/eclipseNewCompletion.js'
const root=new URL('../tests/fixtures/eclipseNewCompletion/',import.meta.url)
const load=name=>JSON.parse(readFileSync(new URL(name+'.json',root),'utf8'))
const inspection=load('inspection'),native=load('native-acceptance')
const args=()=>({run:{id:1,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null},inspection:structuredClone(inspection),
  grid:{fileName:'CASE.EGRID',nx:15,ny:15,nz:1},field:{file:'CASE.EGRID',keyword:'ACTNUM',dataType:'INTE',elementSize:4,count:225},
  artifact:{name:'eclipse-output-CASE.EGRID',contentType:'application/octet-stream',sha256:'a'.repeat(64)},
  draft:{well:'P',i:2,j:1,k:1,diameter:.2,kh:1000,skin:0},activeValue:1})
test('new completion binds existing producer, source/grid and complete physical units',()=>{
  assert.deepEqual(completionProducerWells(inspection),['P'])
  const p=buildNewCompletionParameters(args());assert.ok(validNewCompletionParameters(p));assert.equal(Object.keys(p).length,18)
  assert.equal(p.sourceFile,'ECLIPSE_NEW_COMPLETION_WATER.DATA');assert.equal(p.sourceSha256,native.sourceSha256);assert.equal(p.lengthUnit,'m');assert.equal(p.insertionLine,179)
})
test('unknown/injection well, occupied/inactive coordinates, capacity, bad types and unit fail',()=>{
  for(const change of [{well:'P2'},{well:'I'},{i:1},{i:15,j:15},{i:16},{i:'2'},{skin:-1},{diameter:0},{kh:0}]){
    const a=args();Object.assign(a.draft,change);assert.throws(()=>buildNewCompletionParameters(a))
  }
  let a=args();a.activeValue=0;assert.throws(()=>buildNewCompletionParameters(a),/活动/)
  a=args();a.inspection.newWellContext.maxConnections=1;assert.throws(()=>buildNewCompletionParameters(a),/容量/)
  const p=buildNewCompletionParameters(args());assert.equal(validNewCompletionParameters({...p,extra:1}),false);assert.equal(validNewCompletionParameters({...p,lengthUnit:'BARSA'}),false)
})
test('independent native extra connection raises pressure while actual controls remain unchanged',()=>{
  assert.equal(native.nativeVerified,true);assert.equal(native.onlyAddedConnectionBlock,true);assert.equal(native.geometryUnchanged,true)
  const diff=newCompletionResponse({series:native.target.series},{series:native.baseline.series});assert.equal(diff.available,true);assert.equal(diff.unmatchedCount,0)
  const selected=keyword=>diff.rows.filter(r=>r.well==='P'&&r.keyword===keyword)
  for(const keyword of ['WWPR','WWPT'])assert.ok(selected(keyword).every(r=>r.difference===0))
  assert.equal(selected('WBHP').at(-1).baseline,3885.1171875);assert.equal(selected('WBHP').at(-1).current,3906.57421875)
  assert.equal(native.pressureChangedReports,23)
})
test('missing baseline and wrong units cannot become invented completion response',()=>{
  assert.equal(newCompletionResponse({series:native.target.series},null).available,false)
  const summary={series:structuredClone(native.target.series)};summary.series.find(s=>s.keyword==='WBHP'&&s.objectName==='P').unit='PSIA'
  assert.equal(newCompletionResponse(summary,{series:native.baseline.series}).available,false)
})
