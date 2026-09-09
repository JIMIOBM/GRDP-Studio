import test from 'node:test'
import assert from 'node:assert/strict'

import { selectDefaultPvtRecord } from '../src/utils/pvtSelection.js'

const records = [
  { pvtId: 4, pvtNo: 1, status: 'data-ready' },
  { pvtId: 19, pvtNo: 14, status: 'calculated', lastCalculatedKind: 'gas' },
  { pvtId: 32, pvtNo: 25, status: 'calculated', lastCalculatedKind: 'gas' }
]

test('无指定值时默认选择最新已计算PVT表', () => {
  assert.equal(selectDefaultPvtRecord(records)?.pvtId, 32)
})

test('返回已保存记录时保留明确指定的PVT表', () => {
  assert.equal(selectDefaultPvtRecord(records, '19')?.pvtId, 19)
})

test('没有已计算表时选择编号最新的可用表', () => {
  assert.equal(selectDefaultPvtRecord(records.slice(0, 1))?.pvtId, 4)
})

test('空列表不产生虚假选择', () => {
  assert.equal(selectDefaultPvtRecord([], null), null)
})
