export const NODAL_TYPE = 'allocation-nodal'
export const NODAL_RECORD = 'allocation-nodal-record'
export function ensureNodalNavigation(nodes, inheritedWell = '') {
  for (const node of nodes || []) {
    const wellName = node.wellName || inheritedWell
    if (node.type === 'production-allocation') {
      node.children ||= []
      let page = node.children.find(child => child.type === NODAL_TYPE)
      if (!page) {
        page = { id: `${node.id}-nodal`, type: NODAL_TYPE, label: '节点分析', wellName, children: [] }
        node.children.push(page)
      }
      if (page.wellName !== wellName) page.wellName = wellName
      if (page.label !== '节点分析') page.label = '节点分析'
      if (!page.lazy) page.lazy = true
    } else ensureNodalNavigation(node.children, wellName)
  }
}
export function nodalPath(nodes, scope, ancestors = []) {
  for (const node of nodes || []) {
    if (node.projectId != null && Number(node.projectId) !== Number(scope.projectId)) continue
    if (node.gasReservoirId != null && Number(node.gasReservoirId) !== Number(scope.gasReservoirId)) continue
    const path = [...ancestors, node]
    if (node.type === NODAL_TYPE && node.wellName === scope.wellName) return path
    const found = nodalPath(node.children, scope, path)
    if (found) return found
  }
  return null
}
export function applyNodalRecords(tree, scope, records, expand = false) {
  ensureNodalNavigation(tree)
  const path = nodalPath(tree, scope)
  if (!path) return null
  const parent = path.at(-1)
  parent.children = records.map(record => ({
    id: `${parent.id}-${scope.projectId}-${scope.gasReservoirId}-record-${record.id}`,
    type: NODAL_RECORD, label: record.name, nodalId: Number(record.id),
    operationMode: record.operationMode, ...scope, children: []
  }))
  parent.loaded = true
  parent.recordsRevision = (parent.recordsRevision || 0) + 1
  if (expand) path.forEach(node => { node.expanded = true })
  return parent
}
export function upsertNodalRecord(tree, scope, record) {
  const parent = nodalPath(tree, scope)?.at(-1)
  const records = (parent?.children || []).filter(n => Number(n.nodalId) !== Number(record.id))
    .map(n => ({ id: n.nodalId, name: n.label, operationMode: n.operationMode }))
  const updated = applyNodalRecords(tree, scope, [record, ...records], true)
  return updated?.children.find(n => n.nodalId === Number(record.id))
}
export async function loadNodalRecords(tree, scope, list) {
  const parent = nodalPath(tree, scope)?.at(-1)
  if (!parent) return
  const revision = parent.recordsRevision || 0
  const request = parent.loadSequence = (parent.loadSequence || 0) + 1
  const response = await list(scope)
  const rows = response?.data ?? response
  if (!Array.isArray(rows)) throw new Error('节点分析历史返回格式不正确')
  if (nodalPath(tree, scope)?.at(-1) === parent && parent.loadSequence === request && (parent.recordsRevision || 0) === revision)
    applyNodalRecords(tree, scope, rows)
}
export async function deleteNodalRecord(tree, node, remove) {
  if (node?.type !== NODAL_RECORD || !node.wellName ||
      ![node.nodalId, node.projectId, node.gasReservoirId].every(id => Number(id) > 0))
    throw new Error('节点分析记录的归属信息不完整，无法删除')
  const scope = { projectId: node.projectId, gasReservoirId: node.gasReservoirId, wellName: node.wellName }
  await remove(node.nodalId, scope)
  const parent = nodalPath(tree, scope)?.at(-1)
  if (parent) {
    parent.children = parent.children.filter(child => Number(child.nodalId) !== Number(node.nodalId))
    // 使删除前发起的历史请求失效，防止已删除节点重新出现在树中。
    parent.recordsRevision = (parent.recordsRevision || 0) + 1
  }
  return parent
}
export function nodalCommandTarget(command, selectedWell = '') {
  if (command?.group !== '配产配注' || command?.name !== '节点分析') return null
  return { type: NODAL_TYPE, wellName: String(command.wellName || selectedWell || '').trim() }
}
