import test from 'node:test'
import assert from 'node:assert/strict'
import { buildReservoirTreeNodes } from '../src/config/reservoirRibbon.js'
import { workspaceTreeData, workspaceActiveNodeId, resolveReservoirLocation,
  activateReservoirCommand } from '../src/utils/workspaceTreeState.js'

const cases = [
  ['微观损耗', '地质损耗'], ['逸散性损耗', '地质损耗'], ['井筒损耗', ''], ['地面损耗', '']
]
const find = (node, label) => node.label === label ? node
  : node.children?.map(child => find(child, label)).find(Boolean)

function fixture(feature, parent, loaded = true) {
  const reservoir = buildReservoirTreeNodes({ id: 'r7-4', label: '当前库', projectId: 7, gasReservoirId: 4 })
  const other = buildReservoirTreeNodes({ id: 'r7-5', label: '其他库', projectId: 7, gasReservoirId: 5 })
  workspaceTreeData.value = [{ id: 'g-reservoir', children: [other, reservoir] }]
  const method = find(reservoir, feature)
  const record = { ...method, id: method.id + '/record:1', label: feature + '1',
    type: 'reservoir-geological-loss-record', lossRecordId: 1, lazy: false, children: [] }
  if (loaded) method.children = [record]
  return { reservoir, method, record, query: { scope: 'reservoir', projectId: '7', gasReservoirId: '4',
    group: '损耗评价', parent, feature, lossRecordId: '1' } }
}

for (const [feature, parent] of cases) {
  test(feature + ' opens and highlights the exact record instead of its method directory', () => {
    const { query, method, record } = fixture(feature, parent)
    const target = resolveReservoirLocation(query)
    assert.equal(target.lossRecordId, '1')
    activateReservoirCommand(target)
    assert.equal(workspaceActiveNodeId.value, record.id)
    assert.notEqual(workspaceActiveNodeId.value, method.id)
    assert.equal(method.expanded, true)
  })
}

test('a top-menu draft still selects its method, not a saved record', () => {
  const { query, method } = fixture('地面损耗', '')
  delete query.lossRecordId
  activateReservoirCommand(resolveReservoirLocation(query))
  assert.equal(workspaceActiveNodeId.value, method.id)
})

test('a lazy record keeps its stable selection ID until the real node is loaded', () => {
  const { query, method, record } = fixture('微观损耗', '地质损耗', false)
  activateReservoirCommand(resolveReservoirLocation(query))
  assert.equal(workspaceActiveNodeId.value, record.id)
  assert.equal(method.children.length, 0)
  method.children = [record]
  assert.equal(workspaceActiveNodeId.value, method.children[0].id)
})

test('changing between records follows the route record ID', () => {
  const { query, method, record } = fixture('井筒损耗', '')
  method.children.push({ ...record, id: method.id + '/record:2', lossRecordId: 2 })
  activateReservoirCommand(resolveReservoirLocation(query))
  assert.equal(workspaceActiveNodeId.value, record.id)
  activateReservoirCommand(resolveReservoirLocation({ ...query, lossRecordId: '2' }))
  assert.equal(workspaceActiveNodeId.value, method.id + '/record:2')
})
