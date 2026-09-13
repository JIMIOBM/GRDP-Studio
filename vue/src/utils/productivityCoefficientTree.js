export const COEFFICIENT_GROUP = 'productivity-coefficient-group'
export const COEFFICIENT_METHOD = 'productivity-coefficient-method'
export const COEFFICIENT_RECORD = 'productivity-coefficient-record'

export const ensureCoefficientTree = tree => {
  const wells = tree.find(n => n.id === 'g-well')?.children || []
  for (const well of wells) {
    const parent = well.children?.find(n => n.type === 'single-well-productivity' || n.label === '单井产能')
    if (!parent) continue
    parent.children ||= []
    let group = parent.children.find(n => n.type === COEFFICIENT_GROUP || n.label === '产能系数')
    if (!group) {
      group = { id: `${well.id}-coefficient`, label: '产能系数', children: [] }
      parent.children.push(group)
    }
    Object.assign(group, { type: COEFFICIENT_GROUP, wellName: well.wellName || well.label })
    group.children ||= []
    for (const method of ['二项式', '指数式']) {
      let node = group.children.find(n => n.label === method)
      if (!node) {
        node = { id: `${group.id}-${method}`, label: method, children: [] }
        group.children.push(node)
      }
      Object.assign(node, { type: COEFFICIENT_METHOD, method, wellName: group.wellName, lazy: true })
    }
  }
}

export const applyCoefficientRecords = (tree, scope, records, expand = false) => {
  ensureCoefficientTree(tree)
  const well = tree.find(n => n.id === 'g-well')?.children?.find(n => (n.wellName || n.label) === scope.wellName)
  const parent = well?.children?.find(n => n.type === 'single-well-productivity' || n.label === '单井产能')
  const group = parent?.children?.find(n => n.type === COEFFICIENT_GROUP)
  if (!group) return
  for (const node of group.children.filter(n => n.type === COEFFICIENT_METHOD)) {
    node.children = records.filter(r => r.method === node.method).map(r => ({
      id: `${node.id}-${r.id}`, label: r.name, type: COEFFICIENT_RECORD,
      method: r.method, coefficientId: r.id, ...scope, children: []
    }))
    node.loaded = true
    if (expand) node.expanded = true
  }
  if (expand) { well.expanded = true; parent.expanded = true; group.expanded = true }
}

export const coefficientLocation = node => ({ name: 'SingleWellProductivity', query: {
  module: '产能系数', method: node.method, well: node.wellName,
  projectId: node.projectId, gasReservoirId: node.gasReservoirId, coefficientId: node.coefficientId
} })
