import assert from 'node:assert/strict'
import test from 'node:test'
import {readFileSync} from 'node:fs'
import {buildPvtw,validPvtw,pvtwRecord,pvtwResponse,pvtwUnit,pvtwLabels} from '../src/views/SoftwareIntegration/eclipsePvtw.js'
const root=new URL('../tests/fixtures/eclipsePvtw/',import.meta.url),load=name=>JSON.parse(readFileSync(new URL(name+'.json',root),'utf8'))
const inspection=load('inspection'),native=load('native-acceptance'),run={id:1,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null}
test('water PVT parameters bind source SHA and every original field',()=>{const p=buildPvtw({run,inspection,targetValue:1});assert.ok(validPvtw(p));assert.equal(Object.keys(p).length,10);assert.equal(p.originalValue,.5);assert.equal(p.lineNumber,77);assert.equal(p.sourceSha256,native.sourceSha256);assert.deepEqual(p.recordValues,['.0','1.0','3.03E-06','.5','0.0'])})
test('wrong types defaults units unknown fields and missing successful baseline fail',()=>{const p=buildPvtw({run,inspection,targetValue:1});for(const change of [{targetValue:'1'},{targetValue:0},{unit:'Pa.s'},{extra:1},{originalValue:1},{recordValues:['.0','1.0','3.03E-06','1*','0.0']}])assert.equal(validPvtw({...p,...change}),false);assert.throws(()=>buildPvtw({run:{...run,parameters:p},inspection,targetValue:1}));const bad=structuredClone(inspection);bad.phases.push('OIL');assert.equal(pvtwRecord(bad),null)})
test('native viscosity change retains actual rate/total while pressure responds',()=>{assert.equal(native.nativeVerified,true);assert.equal(native.onlyWaterViscosityTokenChanged,true);const diff=pvtwResponse({series:native.target.series},{series:native.baseline.series});assert.equal(diff.available,true);assert.equal(diff.rows.length,144);assert.equal(diff.unmatchedCount,0);const p=diff.rows.filter(r=>r.well==='P'&&r.keyword==='WBHP');assert.equal(p.at(-1).baseline,3885.1171875);assert.equal(p.at(-1).current,3770.230712890625);assert.equal(native.pressureChangedReports,23);assert.ok(diff.rows.filter(r=>['WWPR','WWPT'].includes(r.keyword)).every(r=>r.difference===0))})
test('missing baseline or pressure unit mismatch produces no invented comparison',()=>{assert.equal(pvtwResponse({series:native.target.series},null).available,false);const bad={series:structuredClone(native.target.series)};bad.series.find(s=>s.keyword==='WBHP'&&s.objectName==='P').unit='PSIA';assert.equal(pvtwResponse(bad,{series:native.baseline.series}).available,false)})

const fieldsNative=load('fields-native-acceptance')
for(const [label,s] of Object.entries(fieldsNative.scenarios))test(`water ${label}: independent native value, field identity, unit and full source record`,()=>{
 const p=buildPvtw({run,inspection,targetValue:s.targetValue,field:s.field});assert.ok(validPvtw(p));assert.equal(p.field,s.field);assert.equal(p.unit,s.unit);assert.equal(p.originalValue,s.originalValue);assert.equal(p.schemaVersion,s.field==='VISCOSITY'?'eclipse-pvtw-parameters/1':'eclipse-pvtw-parameters/2')
 if(s.field!=='VISCOSITY')assert.equal(validPvtw({...p,schemaVersion:'eclipse-pvtw-parameters/1'}),false)
 const diff=pvtwResponse({series:fieldsNative[label].series},{series:fieldsNative.baseline.series});assert.equal(diff.available,true);assert.equal(diff.unmatchedCount,0);const last=diff.rows.filter(r=>r.well==='P'&&r.keyword==='WBHP').at(-1);assert.equal(last.current,s.lastTargetBhp);assert.equal(last.baseline,s.lastBaselineBhp)
 assert.deepEqual(fieldsNative[label].arrays,fieldsNative.baseline.arrays);assert.equal(Object.keys(pvtwLabels).length,5)
})
test('water FIELD volume is rb/stb and pressure derivatives use inverse psi, with physical bounds',()=>{
 assert.equal(pvtwUnit('FIELD','FORMATION_VOLUME_FACTOR'),'rb/stb');assert.equal(pvtwUnit('LAB','FORMATION_VOLUME_FACTOR'),'rcc/scc');assert.equal(pvtwUnit('PVT-M','REFERENCE_PRESSURE'),'atma');assert.equal(pvtwUnit('FIELD','COMPRESSIBILITY'),'1/psi')
 const p=buildPvtw({run,inspection,targetValue:.00000606,field:'COMPRESSIBILITY'});assert.equal(validPvtw({...p,targetValue:-1}),false);assert.equal(validPvtw({...p,targetValue:.01}),false);assert.equal(validPvtw({...p,unit:'barsa'}),false)
})
