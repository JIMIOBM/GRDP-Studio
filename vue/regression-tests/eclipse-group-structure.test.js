import test from 'node:test'
import assert from 'node:assert/strict'
import { groupStructureRecords, groupStructureKey, buildGroupStructure, validGroupStructure, groupStructureResponse } from '../src/views/SoftwareIntegration/eclipseGroupStructure.js'

import { groupStructureFixture as fixture } from '../tests/fixtures/eclipseGroupStructure.js'
export { fixture }
for(const keyword of ['GCONPROD','WGRUPCON','GEFAC'])test(`${keyword} binds full graph and verifies actual allocation and cumulative contribution`,()=>{
 const {inspection,p,summary}=fixture(keyword);assert.ok(validGroupStructure(p));assert.equal(groupStructureRecords(inspection).length,keyword==='GCONPROD'?1:keyword==='WGRUPCON'?5:2)
 const r=groupStructureResponse(summary(true),summary(),p,inspection);assert.equal(r.verified,true);assert.ok(r.rows.every(r=>r.consistent))
 if(keyword==='GEFAC'){assert.equal(r.rows[0].rate,2000);assert.equal(r.leaves[0].contribution,r.leaves[0].rate*.5);assert.equal(r.rows[0].total,r.leaves.reduce((n,l)=>n+l.total,0));assert.notEqual(r.leaves[0].total,r.leaves[0].baselineTotal)}
 if(keyword==='WGRUPCON')assert.equal(r.members[1].guideTarget,1000)
})
test('cycles, parent wells, missing fixed guide, mixed efficiency and inconsistent snapshots cannot bind',()=>{
 for(const fault of ['cycle','parentWell','missingGuide','mixedEfficiency','snapshot']){
  const {inspection}=fixture('WGRUPCON')
  if(fault==='cycle')inspection.scheduleMetadata.groups[1].parent='GA'
  if(fault==='parentWell')inspection.scheduleMetadata.wells[0].group='P'
  if(fault==='missingGuide')inspection.groupStructureControls.pop()
  if(fault==='mixedEfficiency')inspection.groupStructureControls.forEach(c=>c.leaves[0].efficiency=.8)
  if(fault==='snapshot')inspection.groupStructureControls[1].leaves[0].members[0].guideRate=2
  assert.deepEqual(groupStructureRecords(inspection),[],fault)
 }
})
test('strict source, member order, schema and dimensionless units reject stale edits',()=>{
 const {inspection,p,summary}=fixture('GEFAC')
 for(const change of [{sourceFile:'OTHER.DATA'},{lineNumber:51},{originalValue:.7},{parentGroup:'OTHER'},{members:[...p.members].reverse()},{targetValue:1.1},{unit:'STB/day'},{extra:true},{schemaVersion:'eclipse-schedule-parameters/1'}])assert.equal(groupStructureResponse(summary(true),summary(),{...p,...change},inspection).available,false,JSON.stringify(change))
})
test('missing, wrong-unit, duplicate or mismatched time curves cannot pass',()=>{
 const {inspection,p,summary}=fixture()
 for(const fault of ['missing','unit','duplicate','time','sum','doubleEfficiency','pressure','oilLimit']){
  const current=summary(true),old=summary()
  if(fault==='missing')current.series.pop()
  if(fault==='unit')current.series[0].unit='RB/DAY'
  if(fault==='duplicate')current.series.push(structuredClone(current.series[0]))
  if(fault==='time')current.series[1].points[0].timeDays=11
  if(fault==='sum')old.series[0].points[0].value=1999
  if(fault==='doubleEfficiency')current.series.find(c=>c.keyword==='WEFFG').points[0].value=.5
  if(fault==='pressure')current.series.find(c=>c.keyword==='WBHP').points[0].value=2000
  if(fault==='oilLimit')current.series.find(c=>c.keyword==='WOPR').points[0].value=30000
  assert.equal(groupStructureResponse(current,old,p,inspection).verified,false,fault)
 }
})
test('adaptive grids do not omit a late constrained point absent from the baseline',()=>{
 const {inspection,p,summary}=fixture(),current=summary(true),old=summary(),limited=summary(true,true)
 current.series.forEach((c,n)=>c.points.push({...limited.series[n].points[0],timeDays:11}))
 old.series.forEach(c=>c.points.push({...c.points[0],timeDays:12}))
 const r=groupStructureResponse(current,old,p,inspection);assert.equal(r.available,true);assert.equal(r.consistent,true);assert.equal(r.verified,false);assert.equal(r.constrained,true);assert.equal(r.rows[1].baselineRate,null);assert.deepEqual(r.rows.map(r=>r.targetReached),[true,false])
})
test('native dimensionless null is accepted, absent or numeric efficiency units reject',()=>{
 const {inspection,p,summary}=fixture('GEFAC'),old=summary(),current=summary(true)
 current.series.filter(c=>c.keyword==='WEFFG').forEach(c=>c.unit=null)
 assert.equal(groupStructureResponse(current,old,p,inspection).verified,true)
 const c=current.series.find(c=>c.keyword==='WEFFG');c.unit=123
 assert.equal(groupStructureResponse(current,old,p,inspection).available,false)
 delete c.unit;assert.equal(groupStructureResponse(current,old,p,inspection).available,false)
})
