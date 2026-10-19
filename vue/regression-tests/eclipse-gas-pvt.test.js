import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
import { buildGasPvt, validGasPvt, gasPvtRecord, gasPvtResponse, gasVolumeUnit, gasPressureUnit } from '../src/views/SoftwareIntegration/eclipseGasPvt.js'
import { batchChoices, buildBatchCase, buildBatchRequest } from '../src/views/SoftwareIntegration/eclipseBatch.js'
const root = new URL('../tests/fixtures/eclipseGasPvt/', import.meta.url)
const load = name => JSON.parse(readFileSync(new URL(name + '.json', root), 'utf8'))
const inspection = load('inspection'), native = load('native-acceptance')
const run = { id: 1, runType: 'eclipse', status: 'SUCCEEDED', resultContract: 'VALID_FULL', parameters: null }
test('dry gas binds zero-pressure table, complete source and FIELD Bg rb/Mscf units', () => {
  const p = buildGasPvt({ run, inspection, multiplier: 2 })
  assert.ok(validGasPvt(p)); assert.equal(Object.keys(p).length, 8)
  assert.equal(p.keywordLineNumber, 82); assert.equal(p.rows.length, 2)
  assert.equal(p.sourceSha256, native.sourceSha256)
  assert.deepEqual(p.rows, inspection.gasPvtContext.rows)
  assert.notEqual(p.rows, inspection.gasPvtContext.rows)
  assert.equal(gasVolumeUnit('FIELD'), 'rb/Mscf'); assert.equal(gasPressureUnit('FIELD'), 'psia')
})
test('invalid multiplier, default table values, units and overridden baseline cannot submit', () => {
  const p = buildGasPvt({ run, inspection, multiplier: 2 })
  for (const change of [{ multiplier: '2' }, { multiplier: 1 }, { multiplier: 0 }, { unit: 'Pa.s' }, { extra: 1 }]) assert.equal(validGasPvt({ ...p, ...change }), false)
  assert.throws(() => buildGasPvt({ run: { ...run, parameters: p }, inspection, multiplier: 2 }))
  const bad = structuredClone(inspection); bad.gasPvtContext.rows[1].values[1] = '1.3'
  assert.equal(gasPvtRecord(bad), null)
  bad.gasPvtContext.rows[1].values[1] = '1*'; assert.equal(gasPvtRecord(bad), null)
})
test('actual adaptive reports align by timestamp and preserve missing values without interpolation', () => {
  assert.equal(native.nativeVerified, true)
  const diff = gasPvtResponse({ series: native.target.series }, { series: native.baseline.series })
  assert.equal(diff.available, true); assert.equal(diff.rows.length, 952)
  assert.equal(diff.unmatchedCount, 904); assert.equal(diff.changedCount, 15)
  const pressure = diff.rows.find(r => r.keyword === 'WBHP' && r.well === 'I' && r.timeDays === 6000)
  assert.equal(pressure.baseline, 4138.58984375); assert.equal(pressure.current, 4145.6728515625)
  assert.equal(diff.rows.find(r => r.keyword === 'WGIT' && r.timeDays === 6000).current, 600000)
  for (const row of diff.rows.filter(r => r.current == null || r.baseline == null)) assert.equal(row.difference, null)
  assert.deepEqual(native.target.arrays, native.baseline.arrays)
})
test('mismatched or empty physical units reject gas response instead of fabricating a difference', () => {
  const current = { series: structuredClone(native.target.series) }, baseline = { series: native.baseline.series }
  const gas = current.series.find(s => s.keyword === 'WGIT')
  gas.unit = 'SM3'; assert.equal(gasPvtResponse(current, baseline).available, false)
  gas.unit = ''; assert.equal(gasPvtResponse(current, baseline).available, false)
})
test('dry gas volume factors retain zero reference pressure and use Bg units distinct from oil',()=>{
 const p=buildGasPvt({run,inspection,multiplier:1.02,field:'FORMATION_VOLUME_FACTOR'});assert.ok(validGasPvt(p));assert.equal(p.schemaVersion,'eclipse-pvdg-parameters/2');assert.equal(p.unit,'rb/Mscf');assert.deepEqual(p.rows,inspection.gasPvtContext.rows)
 for(const system of ['METRIC','LAB','PVT-M'])assert.equal(buildGasPvt({run,inspection:{...inspection,unitSystem:system},multiplier:1.02,field:'FORMATION_VOLUME_FACTOR'}).unit,gasVolumeUnit(system))
 for(const change of [{field:'PRESSURE'},{field:2},{unit:'rb/stb'},{unit:'cP'},{multiplier:101},{schemaVersion:'eclipse-pvdg-parameters/1'},{extra:1}])assert.equal(validGasPvt({...p,...change}),false)
 const above=structuredClone(p);above.rows[0].values[1]='100000';assert.equal(validGasPvt(above),false);delete p.field;assert.equal(validGasPvt(p),false);assert.throws(()=>buildGasPvt({run,inspection,multiplier:1.02,field:'PRESSURE'}))
})
test('dry gas volume and viscosity form independently sourced cases under one native baseline',()=>{
 const baseline=load('baseline');assert.ok(batchChoices(inspection).some(c=>c.kind==='PVDG_BG'))
 const cases=['PVDG_BG','PVDG'].map(kind=>buildBatchCase({run:baseline,inspection,kind,value:kind==='PVDG_BG'?1.02:2,name:kind}))
 const p=buildBatchRequest({run:baseline,name:'气物性',cases,requestKey:'12345678-1234-1234-1234-123456789abc'});assert.equal(p.cases[0].parameters.field,'FORMATION_VOLUME_FACTOR');assert.equal(p.cases[0].parameters.unit,'rb/Mscf');assert.equal(p.cases[1].parameters.schemaVersion,'eclipse-pvdg-parameters/1');assert.equal(p.cases[1].parameters.unit,'cP')
})
test('native Bg response preserves changed internal reports and the actual injection controls',()=>{
 const volume=load('volume/native-acceptance');assert.ok(volume.nativeVerified&&volume.onlyGasVolumeColumnChanged)
 const diff=gasPvtResponse({series:volume.target.series},{series:volume.baseline.series});assert.equal(diff.available,true);assert.equal(diff.rows.length,928);assert.equal(diff.unmatchedCount,880);assert.equal(diff.changedCount,15)
 const last=diff.rows.filter(r=>r.timeDays===6000);assert.equal(last.find(r=>r.keyword==='WBHP'&&r.well==='I').current,4138.6943359375);assert.equal(last.find(r=>r.keyword==='WGIT').current,600000);assert.equal(last.find(r=>r.keyword==='WWPT').current,1188125.625)
 for(const row of diff.rows.filter(r=>r.current==null||r.baseline==null))assert.equal(row.difference,null);assert.deepEqual(volume.target.arrays,volume.baseline.arrays)
})
