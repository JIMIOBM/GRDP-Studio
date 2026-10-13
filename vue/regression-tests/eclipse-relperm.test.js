import assert from 'node:assert/strict'
import test from 'node:test'
import {readFileSync} from 'node:fs'
import {buildRelperm,validRelperm,relpermContext,relpermResponse,capillaryPressureUnit} from '../src/views/SoftwareIntegration/eclipseRelperm.js'
const root=new URL('../tests/fixtures/eclipseRelperm/',import.meta.url),load=name=>JSON.parse(readFileSync(new URL(name+'.json',root),'utf8'))
const inspection=load('inspection'),run={id:1,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null}
test('water and paired oil scenarios bind all original rows with dimensionless Kr and pressure-difference units',()=>{
  for(const keyword of ['SWFN','SOF3']){
    const p=buildRelperm({run,inspection,keyword,multiplier:.5});assert.ok(validRelperm(p));assert.equal(Object.keys(p).length,9);assert.equal(p.unit,'1');assert.equal(p.rows.length,keyword==='SWFN'?13:15)
    const table=inspection.relpermContext.tables.find(t=>t.keyword===keyword);assert.deepEqual(p.rows,table.rows);assert.notEqual(p.rows,table.rows)
  }
  assert.equal(capillaryPressureUnit('FIELD'),'psi');assert.equal(capillaryPressureUnit('METRIC'),'bar')
})
test('unvalidated gas, unknown fields, wrong units and nonphysical scaled endpoints cannot submit',()=>{
  for(const keyword of ['SWFN','SOF3']){const p=buildRelperm({run,inspection,keyword,multiplier:.5});for(const change of [{multiplier:'0.5'},{multiplier:1},{multiplier:0},{multiplier:2},{keyword:'SGFN'},{unit:'mD'},{extra:1}])assert.equal(validRelperm({...p,...change}),false)}
  assert.throws(()=>buildRelperm({run:{...run,parameters:{}},inspection,keyword:'SWFN',multiplier:.5}))
})
test('oil endpoints, connate saturation and water capillary ordering are preserved across tables',()=>{
  const oil=structuredClone(inspection);oil.relpermContext.tables[2].rows.at(-1).values[2]='0.8';assert.equal(relpermContext(oil),null)
  const saturation=structuredClone(inspection);saturation.relpermContext.tables[2].rows.at(-1).values[0]='0.79';assert.equal(relpermContext(saturation),null)
  const pressure=structuredClone(inspection);pressure.relpermContext.tables[0].rows[1].values[2]='7';assert.equal(relpermContext(pressure),null)
})
test('full independent native water and oil responses retain pressure floor without proportional production assumptions',()=>{
  for(const keyword of ['SWFN','SOF3']){
    const native=load('native-'+keyword);assert.equal(native.nativeVerified,true);const diff=relpermResponse({series:native.target.series},{series:native.baseline.series});assert.equal(diff.rows.length,84);assert.equal(diff.changedCount,61);assert.equal(diff.unmatchedCount,0)
    const pressure=diff.rows.find(r=>r.keyword==='WBHP'&&r.timeDays===100);assert.equal(pressure.current,3000);assert.equal(pressure.baseline,3000)
    const water=diff.rows.find(r=>r.keyword==='WWPR'&&r.timeDays===100);assert.equal(water.current,keyword==='SWFN' ? .6931211352348328 : 3.208101749420166)
    assert.deepEqual(native.target.arrays,native.baseline.arrays)
  }
})
test('missing timestamps remain null and mismatched units cannot produce relative-permeability differences',()=>{
  const native=load('native-SWFN'),current={series:structuredClone(native.target.series)},baseline={series:native.baseline.series};current.series.find(s=>s.keyword==='WWPR').unit='SM3/DAY';assert.equal(relpermResponse(current,baseline).available,false)
  const gap={series:structuredClone(native.target.series)};gap.series.find(s=>s.keyword==='WWPR').points.pop();const diff=relpermResponse(gap,baseline);assert.equal(diff.unmatchedCount,1);assert.equal(diff.rows.find(r=>r.keyword==='WWPR'&&r.timeDays===100).difference,null)
})
