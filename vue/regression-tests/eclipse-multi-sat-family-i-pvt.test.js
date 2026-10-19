import assert from 'node:assert/strict'
import test from 'node:test'
import {readFileSync} from 'node:fs'
import {multiPvtContext,multiPvtChoices} from '../src/views/SoftwareIntegration/eclipseMultiPvt.js'
import {multiSatRelpermContext} from '../src/views/SoftwareIntegration/eclipseMultiSatRelperm.js'
import {coupledRelpermLabels} from '../src/views/SoftwareIntegration/eclipseCoupledRelperm.js'
import {batchChoices,buildBatchCase,buildBatchRequest} from '../src/views/SoftwareIntegration/eclipseBatch.js'
import {gridRegionResponse} from '../src/views/SoftwareIntegration/eclipseGridRegion.js'
const root=new URL('../tests/fixtures/eclipseMultiSatFamilyIPvt/',import.meta.url),load=name=>JSON.parse(readFileSync(new URL(name+'.json',root),'utf8'))
const inspection=load('inspection'),native=load('native-acceptance'),run={id:1,modelVersionId:2,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null,result:{schemaVersion:'eclipse-summary-result/1',modelKind:'eclipse_100',eclEnd:{problems:0,errors:0,bugs:0}}}
test('coupled-table combined source retains independently numbered SAT and PVT contexts',()=>{
  const sat=multiSatRelpermContext(inspection),pvt=multiPvtContext(inspection)
  assert.deepEqual(sat.regions.map(r=>r.cellCount),[5,8]);assert.deepEqual(pvt.regions.map(r=>r.cellCount),[6,7])
  assert.deepEqual(new Set(batchChoices(inspection).map(c=>c.kind)),new Set([...Object.keys(multiPvtChoices),...Object.keys(coupledRelpermLabels).map(f=>'MULTI_'+f)]))
  assert.ok(native.nativeVerified&&native.independentMaps&&native.identicalTablesPreserveSingleRegionPhysics)
  assert.equal(native.familyConversionComparison.fullSeriesExactlyEqual,true);assert.ok(native.familyConversionComparison.differences.every(v=>v.maximumAbsoluteDifference===0))
  assert.deepEqual(native.satnumFull,[...Array(5).fill(1),...Array(8).fill(2)]);assert.deepEqual(native.pvtnumFull,[...Array(6).fill(1),...Array(7).fill(2)])
  assert.deepEqual(native.satActiveCellsPerRegion,[2,8]);assert.deepEqual(native.activeCellsPerRegion,[3,7])
})
test('seven coupled-table independent batch cases bind the tables of the chosen axis',()=>{
  const cases=batchChoices(inspection).map(c=>{
    const fluid=c.kind.startsWith('PVT_'),field=fluid?multiPvtChoices[c.kind].field:c.kind.slice(6)
    const args={run,inspection,kind:c.kind,name:c.label,value:fluid?(field==='VISCOSITY'?2:1.02):.5}
    assert.throws(()=>buildBatchCase(args));const item=buildBatchCase({...args,regionNumber:2})
    assert.deepEqual(item.parameters.tables,(fluid?inspection.multiPvtContext:inspection.multiSatRelpermContext).regions[1].tables);assert.equal(item.parameters.sourceSha256,native.sourceSha256)
    return item
  })
  const request=buildBatchRequest({run,name:'独立SAT与PVT选区',cases,requestKey:'12345678-1234-1234-1234-123456789abc'})
  assert.equal(request.cases.length,7);assert.equal(new Set(cases.map(c=>JSON.stringify(c.parameters))).size,7)
})
test('all seven coupled-table native edits retain independent maps geometry and numerical responses',()=>{
  for(const label of ['oil-mu','oil-bo','gas-mu','gas-bg','water-kr','gas-kr','oil-kr']){
    const target=native[label];assert.deepEqual(target.arrays,native.baseline.arrays);assert.deepEqual(target.actnum,native.baseline.actnum);assert.deepEqual(target.satnum,native.baseline.satnum);assert.deepEqual(target.pvtnum,native.baseline.pvtnum)
    assert.equal(target.eclEnd.warnings,1);for(const k of ['problems','errors','bugs'])assert.equal(target.eclEnd[k],0)
    const response=gridRegionResponse({series:target.series},{series:native.baseline.series},['WBHP','WOPR','WWPR','WGPR','WLPR','WOPT','WWPT','WGPT','WGIR','WGIT']);assert.equal(response.available,true);assert.ok(response.changedCount>0)
  }
})
