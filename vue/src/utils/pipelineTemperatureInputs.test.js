import test from 'node:test'
import assert from 'node:assert/strict'
import { temperatureLayerInputRows, temperatureOuterGeometryRows, temperatureEffectiveInputs, temperatureViscosityInputRows } from './pipelineTemperatureInputs.js'

test('layer reference tables follow saved topology and display every thickness with cumulative geometry', () => {
  const graph = { edges: [{ id: 'b', name: 'B', parameters: { diameterMm: 100 } }, { id: 'a', name: 'A', parameters: { diameterMm: 200 } }] }
  const settings = { segments: [{ edgeId: 'a', layers: [] }, { edgeId: 'b', innerDiameterMm: 999, layers: [
    { name: '钢管', thicknessMm: 5, conductivityWmK: 45 }, { name: '保温', thicknessMm: 20, conductivityWmK: 0.04 }
  ] }] }
  const before = structuredClone({ graph, settings }), rows = temperatureLayerInputRows(graph, settings)
  assert.deepEqual(rows.map(row => [row.pipeName, row.name, row.thicknessMm, row.innerDiameterMm, row.outerDiameterMm]), [
    ['B', '钢管', 5, 100, 110], ['B', '保温', 20, 110, 150], ['A', '', null, 200, null]
  ])
  assert.deepEqual({ graph, settings }, before)
})

test('an unknown thickness makes downstream diameters unknown rather than counting it as zero', () => {
  const rows = temperatureLayerInputRows({ edges: [{ id: 'a', parameters: { diameterMm: 100 } }] }, { segments: [{ edgeId: 'a', layers: [
    { thicknessMm: null }, { thicknessMm: 5 }
  ] }] })
  assert.equal(rows[0].outerDiameterMm, null)
  assert.equal(rows[1].innerDiameterMm, null)
  assert.equal(rows[1].outerDiameterMm, null)
})

test('buried-pipe geometry exposes the exact radius-depth calculation and leaves invalid geometry unknown', () => {
  const graph = { edges: [{ id: 'a', name: 'A', parameters: { diameterMm: 100 } }] }
  const settings = { segments: [{ edgeId: 'a', burialDepthM: 0.125, layers: [{ thicknessMm: 25 }] }] }
  const row = temperatureOuterGeometryRows(graph, settings)[0]
  assert.equal(row.outerDiameterMm, 150)
  assert.ok(Math.abs(row.depthRatio - 5 / 6) < 1e-14)
  assert.ok(Math.abs(row.y0M - 0.1) < 1e-14)
  assert.ok(Math.abs(row.acoshRatio - Math.log(3)) < 1e-14)
  settings.segments[0].burialDepthM = 0.05
  assert.equal(temperatureOuterGeometryRows(graph, settings)[0].y0M, null)
  settings.segments[0].layers[0].thicknessMm = null
  assert.equal(temperatureOuterGeometryRows(graph, settings)[0].outerDiameterMm, null)
})

const config = { densityKgM3: 11, cpJkgK: 2000, actualFlowM3s: 0.3, viscosityMpaS: 0.01, gasConductivityWmK: 0.04,
  propertyPressureMpa: 8, propertyTemperatureC: 25 }
const entry = { pvtProperties: { sourceRevision: 'r1' }, usedInput: { densityKgM3: 55, cpJkgK: 2900, actualFlowM3s: 0.15,
  viscosityMpaS: 0.018, gasConductivityWmK: 0.053, propertyPressureMpa: 7, propertyTemperatureC: 22 } }

test('actual response supplies PVT properties and the used manual conductivity while retaining editable conditions', () => {
  assert.deepEqual(temperatureEffectiveInputs(config, entry, { current: true }), {
    densityKgM3: 55, cpJkgK: 2900, actualFlowM3s: 0.3, viscosityMpaS: 0.018, gasConductivityWmK: 0.053,
    propertyPressureMpa: 8, propertyTemperatureC: 25
  })
  for (const current of [false, true]) {
    const used = temperatureEffectiveInputs(config, current ? null : entry, { current })
    for(const key of ['densityKgM3', 'cpJkgK', 'viscosityMpaS'])assert.equal(used[key], null)
    assert.equal(used.gasConductivityWmK,0.04)
  }
})

test('coupled Q and mean PT come only from the current actual calculation snapshot', () => {
  const used = temperatureEffectiveInputs(config, entry, { coupled: true, current: true })
  assert.equal(used.actualFlowM3s, 0.15)
  assert.equal(used.propertyPressureMpa, 7)
  assert.equal(used.propertyTemperatureC, 22)
  const stale = temperatureEffectiveInputs(config, entry, { coupled: true, current: false })
  assert.equal(stale.actualFlowM3s, null)
  assert.equal(stale.propertyPressureMpa, null)
  assert.equal(stale.propertyTemperatureC, null)
})

test('PVT previews supply available properties while conductivity remains independently given', () => {
  const used = temperatureEffectiveInputs(config, entry, { current: false, preview: { densityKgM3: 60, viscosityMpaS: 0.015 } })
  assert.equal(used.densityKgM3, 60)
  assert.equal(used.viscosityMpaS, 0.015)
  assert.equal(used.cpJkgK, null)
  assert.equal(used.gasConductivityWmK, 0.04)
  assert.equal(used.propertyPressureMpa, 8)
  assert.equal(used.propertyTemperatureC, 25)
})

test('historical manual snapshots cannot masquerade as actual PVT calculation inputs', () => {
  const old = { usedInput: entry.usedInput }
  const used = temperatureEffectiveInputs(config, old, { current: true })
  for(const key of ['densityKgM3', 'cpJkgK', 'viscosityMpaS'])assert.equal(used[key], null)
  assert.equal(used.gasConductivityWmK,0.04)
})

test('coupled preview properties cannot replace the missing actual iteration conditions', () => {
  const used = temperatureEffectiveInputs(config, entry, { coupled: true, current: false, preview: entry.usedInput })
  for(const key of Object.keys(used))assert.equal(used[key],key==='gasConductivityWmK'?0.04:null)
})

test('missing given conductivity never falls back to a PVT preview value',()=>{
 const used=temperatureEffectiveInputs({...config,gasConductivityWmK:null},null,{preview:{gasConductivityWmK:999}})
 assert.equal(used.gasConductivityWmK,null)
})

test('Standing read-only inputs follow the supplied snapshot and retain zero corrections without inventing missing properties',()=>{
 const graph={edges:[{id:'a',name:'管段 A'},{id:'b',name:'管段 B'}]}
 const detail={pvtName:'当前井模型',pressureMpa:8,temperatureC:0,
  viscosityMpaS:999,gasGravity:999,
  viscosityCalculation:{gasGravity:0.65,pseudoCriticalTemperatureK:200,pseudoCriticalPressureMpa:4.5,
   reducedTemperature:1.36575,reducedPressure:8/4.5,lowPressureViscosityMpaS:0.01,
   nitrogenCorrectionMpaS:0,carbonDioxideCorrectionMpaS:0.0001,hydrogenSulfideCorrectionMpaS:0,
   correctedLowPressureViscosityMpaS:0.0101,pressurePolynomial:0.3,viscosityMpaS:0.02}}
 const before=structuredClone(detail),rows=temperatureViscosityInputRows(graph,{a:detail})
 const first=rows.filter(row=>row.pipeName==='管段 A')
 assert.equal(first.length,14)
 assert.equal(first.find(row=>row.name==='物性计算温度').value,0)
 assert.equal(first.find(row=>row.name==='氮气黏度修正量').value,0)
 assert.equal(first.find(row=>row.name==='气体相对密度 γg').value,0.65)
 assert.equal(first.find(row=>row.name==='动力黏度 μg').value,0.02)
 assert.ok(first.every(row=>row.source==='Standing（资料原式）'&&row.pvtName==='当前井模型'))
 assert.ok(rows.filter(row=>row.pipeName==='管段 B').every(row=>row.value===null))
 assert.deepEqual(detail,before)
 assert.deepEqual(temperatureViscosityInputRows(null),[])
})
