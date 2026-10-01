import test from 'node:test'
import assert from 'node:assert/strict'
import { correlationCoefficient, correlationMatrix, filterStorageNetworkSamples, linearRegression,
  strongestTargetCorrelations, checkStorageNetwork } from './storageNetwork.js'

const rows = [
  { wellId: 1, operatingAt: '2026-01-01T00:00', inletPressureMpa: 1, outletPressureMpa: 2, pressureDropMpa: 1, flowRate10k: 10, outletTemperatureC: 20, compressionPowerKw: 100 },
  { wellId: 1, operatingAt: '2026-01-02T00:00', inletPressureMpa: 2, outletPressureMpa: 4, pressureDropMpa: 2, flowRate10k: 20, outletTemperatureC: 21, compressionPowerKw: 200 },
  { wellId: 2, operatingAt: '2026-01-03T00:00', inletPressureMpa: 3, outletPressureMpa: 6, pressureDropMpa: 3, flowRate10k: 30, outletTemperatureC: 22, compressionPowerKw: 300 },
  { wellId: 2, operatingAt: '2026-01-04T00:00', inletPressureMpa: null, outletPressureMpa: 8, pressureDropMpa: 4, flowRate10k: 40, outletTemperatureC: 23, compressionPowerKw: 400 }
]

test('Pearson and regression use complete pairs only and never turn missing input into zero', () => {
  assert.equal(correlationCoefficient(rows, 'inletPressureMpa', 'flowRate10k'), 1)
  const regression = linearRegression(rows, 'inletPressureMpa', 'flowRate10k')
  assert.deepEqual(regression.pairs, [[1, 10], [2, 20], [3, 30]])
  assert.equal(regression.slope, 10)
  assert.equal(regression.intercept, 0)
  assert.equal(regression.r2, 1)
})

test('Spearman assigns average ranks for ties and keeps monotonic direction', () => {
  const tied = [{ x: 1, y: 9 }, { x: 1, y: 9 }, { x: 2, y: 5 }, { x: 3, y: 1 }]
  assert.equal(correlationCoefficient(tied, 'x', 'y', 'spearman'), -1)
})

test('matrix, filtering and target ranking keep the selected scope', () => {
  const filtered = filterStorageNetworkSamples(rows, { wellIds: [2], startDate: '2026-01-03', endDate: '2026-01-04' })
  assert.equal(filtered.length, 2)
  assert.equal(correlationMatrix(rows).length, 36)
  assert.equal(strongestTargetCorrelations(rows, 'compressionPowerKw', 'pearson', .9)[0].value, 1)
})

test('topology validation requires all wells, an external sink and valid engineering limits', () => {
  const nodes = [
    { id: 'w', type: 'well', name: 'A', wellId: 1, x: 0, y: 0, parameters: { elevationM: 0 } },
    { id: 's', type: 'gathering', name: '站', wellId: null, x: 1, y: 0, parameters: { elevationM: 0, capacity10k: 100, maxPressureMpa: 20 } },
    { id: 'o', type: 'external', name: '外输', wellId: null, x: 2, y: 0, parameters: { elevationM: 0, capacity10k: 100, maxPressureMpa: 20 } }
  ]
  const edge = (id, source, target) => ({ id, source, target, name: id, parameters: { lengthM: 100, diameterMm: 200, roughnessMm: .03, maxFlow10k: 100 } })
  assert.deepEqual(checkStorageNetwork({ nodes, edges: [edge('e1', 'w', 's'), edge('e2', 's', 'o')] }, [1]).errors, [])
  assert.match(checkStorageNetwork({ nodes, edges: [edge('e1', 'w', 's')] }, [1]).errors.join(' '), /连接到外输管网/)
  const bad = edge('e1', 'w', 'o'); bad.parameters.maxFlow10k = 0
  assert.match(checkStorageNetwork({ nodes, edges: [bad] }, [1]).errors.join(' '), /补齐有效/)
})
