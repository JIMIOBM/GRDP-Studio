import test from 'node:test'
import assert from 'node:assert/strict'
import {
  temperatureCalculationKinds,
  temperatureCoefficientStamp,
  temperatureResultTabs,
} from './pipelineTemperatureCalculations.js'

function config() {
  return {
    densityKgM3: 60,
    actualFlowM3s: 0.05,
    viscosityMpaS: 0.012,
    cpJkgK: 2200,
    gasConductivityWmK: 0.04,
    pvtId: 8,
    externalMethod: 'surface-fixed',
    burialDepthM: 1.5,
    soilConductivityWmK: 1.2,
    surfaceCoefficientWm2K: 10,
    ambientC: 15,
    propertyPressureMpa: 8,
    propertyTemperatureC: 40,
    propertySource: '物性说明',
    layers: [{ name: '钢管', thicknessMm: 5, conductivityWmK: 45 }],
  }
}

const stamp = (kind, value, diameter = 100, revision = 3) =>
  temperatureCoefficientStamp(kind, value, diameter, revision)

test('navigation sections select independent calculation kinds and result views', () => {
  assert.deepEqual(temperatureCalculationKinds, {
    properties: 'inner', layers: 'wall', environment: 'outer', settings: 'overall',
  })
  const common = [['input', '数据列表'], ['table', '结果分析']]
  assert.deepEqual(temperatureResultTabs('inner'), [['input', '数据列表'], ['table', '结果分析']])
  assert.deepEqual(temperatureResultTabs('outer'), common)
  assert.deepEqual(temperatureResultTabs('wall'), [['input', '数据列表'], ['table', '结果分析']])
  assert.deepEqual(temperatureResultTabs('overall'), common)
})

test('inside coefficient does not depend on burial, material or ambient inputs', () => {
  const original = config()
  const changed = config()
  changed.burialDepthM = null
  changed.layers = []
  changed.ambientC = -10
  changed.soilConductivityWmK = null
  assert.equal(stamp('inner', original), stamp('inner', changed))
  changed.actualFlowM3s *= 2
  assert.notEqual(stamp('inner', original), stamp('inner', changed))
})

test('wall calculation depends on material data, independently of gas and burial inputs', () => {
  const original = config()
  const changed = config()
  changed.densityKgM3 = 200
  changed.actualFlowM3s = null
  changed.gasConductivityWmK = null
  changed.burialDepthM = null
  assert.equal(stamp('wall', original), stamp('wall', changed))
  changed.layers[0].conductivityWmK = 0.2
  assert.notEqual(stamp('wall', original), stamp('wall', changed))
})

test('outside coefficient uses wall geometry without requiring conductivity', () => {
  const original = config()
  const changed = config()
  changed.layers[0].conductivityWmK = null
  changed.viscosityMpaS = null
  changed.densityKgM3 = null
  assert.equal(stamp('outer', original), stamp('outer', changed))
  changed.layers[0].thicknessMm = 20
  assert.notEqual(stamp('outer', original), stamp('outer', changed))
})

test('legacy layer source metadata never invalidates a coefficient result', () => {
  const current = config()
  const legacy = config()
  legacy.layers[0].source = '旧材料资料'
  const changed = structuredClone(legacy)
  changed.layers[0].source = '变更后的旧材料资料'
  const snapshot = structuredClone(legacy)
  for (const kind of Object.values(temperatureCalculationKinds)) {
    assert.equal(stamp(kind, current), stamp(kind, legacy))
    assert.equal(stamp(kind, legacy), stamp(kind, changed))
    assert.equal(temperatureCoefficientStamp(kind, current, 100, 3, true),
      temperatureCoefficientStamp(kind, changed, 100, 3, true))
  }
  assert.deepEqual(legacy, snapshot)
})

test('layer name, thickness and conductivity still invalidate their existing dependent calculations', () => {
  const original = config()
  for (const [field, value, dependentKinds] of [
    ['name', '保温层', ['wall', 'outer', 'overall']],
    ['thicknessMm', 10, ['wall', 'outer', 'overall']],
    ['conductivityWmK', 0.04, ['wall', 'overall']],
  ]) {
    const changed = config()
    changed.layers[0][field] = value
    for (const kind of Object.values(temperatureCalculationKinds)) {
      if (dependentKinds.includes(kind)) assert.notEqual(stamp(kind, original), stamp(kind, changed), `${kind}: ${field}`)
      else assert.equal(stamp(kind, original), stamp(kind, changed), `${kind}: ${field}`)
    }
  }
})

test('surface resistance is relevant only for the second boundary method', () => {
  for (const kind of ['outer', 'overall']) {
    const original = config()
    const changed = config()
    changed.surfaceCoefficientWm2K = 30
    assert.equal(stamp(kind, original), stamp(kind, changed))
    original.externalMethod = changed.externalMethod = 'surface-resistance'
    assert.notEqual(stamp(kind, original), stamp(kind, changed))
  }
})

test('overall coefficient tracks dependencies from each constituent calculation', () => {
  const original = config()
  for (const change of [
    value => { value.pvtId = 15 },
    value => { value.layers[0].conductivityWmK = 0.15 },
    value => { value.burialDepthM = 2 },
  ]) {
    const changed = config()
    change(changed)
    assert.notEqual(stamp('overall', original), stamp('overall', changed))
  }
})

test('coefficient stamps ignore operating annotations and temperature-solver settings', () => {
  const original = config()
  const changed = config()
  Object.assign(changed, {
    ambientC: -15,
    propertySource: '更新说明', tolerance: 0.0001, maxIterations: 60,
  })
  for (const kind of Object.values(temperatureCalculationKinds)) {
    assert.equal(stamp(kind, original), stamp(kind, changed))
    assert.notEqual(stamp(kind, original), stamp(kind, original, 120))
    assert.notEqual(stamp(kind, original), stamp(kind, original, 100, 4))
  }
})

test('PVT selection, pressure, temperature and source changes invalidate inner and overall only', () => {
  const original = config()
  const sourceStamp = (kind, value, source) => temperatureCoefficientStamp(kind, value, 100, 3, source)
  for (const field of ['pvtId', 'propertyPressureMpa', 'propertyTemperatureC','gasConductivityWmK']) {
    const changed = config()
    changed[field] += 1
    for (const kind of ['inner', 'overall']) {
      assert.notEqual(stamp(kind, original), stamp(kind, changed), `${kind}: ${field}`)
      assert.notEqual(sourceStamp(kind, original, 'r1'), sourceStamp(kind, original, 'r2'), `${kind}: source revision`)
    }
    for (const kind of ['wall', 'outer']) {
      assert.equal(stamp(kind, original), stamp(kind, changed), `${kind}: ${field}`)
      assert.equal(sourceStamp(kind, original, 'r1'), sourceStamp(kind, original, 'r2'), `${kind}: source revision`)
    }
  }
})

test('legacy manually entered properties are ignored while editable inside diameter invalidates its result', () => {
  const original = { ...config(), innerDiameterMm: 90 }
  const changed = { ...original, densityKgM3: 999, viscosityMpaS: null, cpJkgK: 999 }
  for (const kind of Object.values(temperatureCalculationKinds))assert.equal(stamp(kind, original), stamp(kind, changed))
  assert.equal(stamp('inner', original), stamp('inner', original, 120))
  changed.innerDiameterMm = 110
  assert.notEqual(stamp('inner', original), stamp('inner', changed))
  assert.notEqual(stamp('inner', { ...original, innerDiameterMm: 100 }), stamp('inner', { ...original, innerDiameterMm: null }))
  for (const kind of ['wall', 'outer', 'overall']) {
    assert.equal(stamp(kind, original), stamp(kind, changed))
  }
})

test('stamps are stable across property order and safe for empty settings', () => {
  const original = config()
  const reversed = Object.fromEntries(Object.entries(original).reverse())
  for (const kind of Object.values(temperatureCalculationKinds)) {
    assert.equal(stamp(kind, original), stamp(kind, reversed))
    assert.equal(stamp(kind, null), stamp(kind, undefined))
    assert.doesNotThrow(() => JSON.parse(stamp(kind, null)))
  }
})

test('unknown calculation kind cannot silently fall back to a different calculation', () => {
  assert.throws(() => temperatureResultTabs('properties'), /未知温度计算类型/)
  assert.throws(() => stamp('invalid', config()), /未知温度计算类型/)
})
