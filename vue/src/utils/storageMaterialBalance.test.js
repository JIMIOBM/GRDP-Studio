import test from 'node:test'
import assert from 'node:assert/strict'
import {
  selectStorageMaterialBalanceWells
} from './storageMaterialBalance.js'

test('选择井后按剩余井的储量权重重算压力和累产量', () => {
  const result = {
    wells: [
      { wellId: 1, wellName: 'X-1', gasVolume: 10, included: true, samples: [
        { date: '2025-01-01', pressure: 20, gas: 2, water: 1 }
      ] },
      { wellId: 2, wellName: 'X-2', gasVolume: 30, included: true, samples: [
        { date: '2025-01-01', pressure: 24, gas: 3, water: 4 }
      ] }
    ],
    rows: [],
    partialDates: [],
    sourceGasVolume: 40,
    includedWellCount: 2,
    message: '旧说明'
  }

  const both = selectStorageMaterialBalanceWells(result, [1, 2])
  assert.equal(both.rows[0].pressure, 23)
  assert.equal(both.rows[0].gas, 5)
  assert.equal(both.rows[0].water, 5)
  assert.equal(both.sourceGasVolume, 40)
  assert.equal(both.wells[0].weight, 0.25)

  const one = selectStorageMaterialBalanceWells(result, [1])
  assert.equal(one.rows[0].pressure, 20)
  assert.equal(one.rows[0].gas, 2)
  assert.equal(one.rows[0].water, 1)
  assert.equal(one.sourceGasVolume, 10)
  assert.equal(one.includedWellCount, 1)
  assert.notDeepEqual(one.rows, both.rows)
})

test('物质平衡汇总保留日期并集，缺少该日记录的井标记为部分日期', () => {
  const result = selectStorageMaterialBalanceWells({
    wells: [
      { wellId: 1, wellName: 'X-1', gasVolume: 10, included: true, samples: [
        { date: '2025-01-01', pressure: 20, gas: 2, water: 1 }
      ] },
      { wellId: 2, wellName: 'X-2', gasVolume: 30, included: true, samples: [
        { date: '2025-01-02', pressure: 24, gas: 3, water: 4 }
      ] }
    ], rows: [], partialDates: [], sourceGasVolume: 40, includedWellCount: 2, message: ''
  }, [1, 2])

  assert.deepEqual(result.rows.map(row => row.date), ['2025-01-01', '2025-01-02'])
  assert.deepEqual(result.partialDates, [
    { date: '2025-01-01', missingWells: ['X-2'] },
    { date: '2025-01-02', missingWells: ['X-1'] }
  ])
})
