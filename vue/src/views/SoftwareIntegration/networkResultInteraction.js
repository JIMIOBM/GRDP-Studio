export const nodeResultRows = (entries, id) => entries.flatMap(entry =>
  (entry.values || []).filter(item => item.name === id).map(item => ({
    variable: entry.variable, unit: entry.unit, value: item.value
  })))

// Quality paths are produced by the Worker contract. Resolve only against
// identifiers and variables that are actually present in the returned result;
// never infer a nearby node, endpoint, or branch from a free-form path.
export const qualityLocation = (item, { nodes = [], nodeResults = [], systemResults = [], profiles = [] } = {}) => {
  const path = item?.path
  if (typeof path !== 'string' || !path.trim()) return { kind: 'unknown', label: '无法定位' }
  const groups = entries => Array.isArray(entries) ? entries.filter(entry => typeof entry?.variable === 'string') : []
  const nodeIds = [...new Set((Array.isArray(nodes) ? nodes : []).map(node => node?.id).filter(id => typeof id === 'string' && id))]
  const nodeGroups = groups(nodeResults)
  const systemGroups = groups(systemResults)
  const profileEntries = (Array.isArray(profiles) ? profiles : []).filter(profile => typeof profile?.branch === 'string' && Array.isArray(profile.variables))

  if (path.startsWith('node.')) {
    const matches = nodeIds.flatMap(id => {
      const suffix = `.${id}`
      if (!path.endsWith(suffix)) return []
      const variable = path.slice('node.'.length, -suffix.length)
      return nodeGroups.some(entry => entry.variable === variable) ? [{ id, variable }] : []
    })
    if (matches.length === 1) return { kind: 'node', id: matches[0].id, variable: matches[0].variable, label: `节点 ${matches[0].id}` }
  }

  if (path.startsWith('profiles.')) {
    const matches = profileEntries.flatMap(profile => {
      const prefix = `profiles.${profile.branch}.`
      if (!path.startsWith(prefix)) return []
      const rest = path.slice(prefix.length)
      const match = /^(.+)\[(\d+)\]$/.exec(rest)
      if (!match || !profile.variables.some(variable => variable?.variable === match[1])) return []
      const pointIndex = Number(match[2])
      return Number.isSafeInteger(pointIndex) ? [{ branch: profile.branch, variable: match[1], pointIndex }] : []
    })
    if (matches.length === 1) {
      return { kind: 'profile', id: matches[0].branch, variable: matches[0].variable, pointIndex: matches[0].pointIndex, label: `支路 ${matches[0].branch}` }
    }
  }

  if (path.startsWith('system.')) {
    const matches = systemGroups.flatMap(entry => {
      const prefix = `system.${entry.variable}.`
      const name = path.startsWith(prefix) ? path.slice(prefix.length) : ''
      const returnedNames = Array.isArray(entry.values) ? entry.values.map(value => value?.name).filter(name => typeof name === 'string') : []
      return name && returnedNames.includes(name) ? [{ variable: entry.variable }] : []
    })
    if (matches.length === 1) return { kind: 'system', variable: matches[0].variable, label: '系统结果' }
  }
  return { kind: 'unknown', label: '无法定位' }
}

// Only explicit returned identifiers establish a link; labels and connection direction do not.
export const matchedBranches = (profiles, ids) => profiles.filter(profile =>
  ids.includes(profile.branch) || profile.variables.some(variable =>
    variable.variable === 'BranchEquipment' && variable.values.some(value => ids.includes(value))
  )).map(profile => profile.branch)

export const validatedLayout = (raw, signature, ids) => {
  if (!raw || raw.signature !== signature || !Array.isArray(raw.positions) || raw.positions.length !== ids.length) return null
  const positions = new Map(raw.positions.map(item => [item.id, item]))
  if (positions.size !== ids.length || !ids.every(id => {
    const item = positions.get(id)
    return item && Number.isFinite(item.x) && Number.isFinite(item.y)
  })) return null
  return positions
}

export const networkCsv = rows => '\uFEFF' + rows.map(row => row.map(value => {
  let text = value == null ? '' : typeof value === 'object' ? JSON.stringify(value) : String(value)
  if (typeof value === 'string' && /^[\s]*[=+\-@]/.test(text)) text = "'" + text
  return `"${text.replace(/"/g, '""')}"`
}).join(',')).join('\r\n')

export const branchComparison = (base, candidate, variable) => {
  const read = (profile, key) => profile?.variables?.find(item => item.variable === key)
  const distance = read(candidate, 'TotalDistance')
  const values = read(candidate, variable)
  for (const key of ['TotalDistance', variable]) {
    const original = read(base, key)
    const compared = read(candidate, key)
    if (!original?.unit || !compared?.unit || original.unit !== compared.unit) return null
  }
  if (!Array.isArray(distance.values) || !distance.values.length || !Array.isArray(values.values) || distance.values.length !== values.values.length) return null
  if (![...distance.values, ...values.values].every(value => value === null || typeof value === 'number' && Number.isFinite(value))) return null
  return distance.values.map((value, index) => ({ distance: value, value: values.values[index] }))
}

const safeComparisonText = (value, allowEmpty = false) => typeof value === 'string' && value.length <= 1000 &&
  (allowEmpty ? value.length === 0 || value.trim().length > 0 : value.trim().length > 0) &&
  !/[\u0000-\u001f\u007f-\u009f]/.test(value) &&
  !/(?:^|[^a-z0-9])(?:[a-z]:[\\/]|\\\\[^\\/\s]+[\\/]|file:|net\.pipe:\/\/|\/\/[^/\s]+\/)/i.test(value)

const safeComparisonValue = (value, depth = 0) => {
  if (value === null) return true
  if (typeof value === 'number') return Number.isFinite(value)
  if (depth >= 8 || !value || typeof value !== 'object') return false
  if (Array.isArray(value)) return value.length <= 512 && value.every(item => safeComparisonValue(item, depth + 1))
  const keys = Object.keys(value)
  return keys.length <= 128 && keys.every(key => safeComparisonText(key, true) && safeComparisonValue(value[key], depth + 1))
}

const safeComparisonScalars = groups => {
  if (!Array.isArray(groups)) return null
  const variables = new Set()
  const result = []
  for (const group of groups) {
    if (!group || !safeComparisonText(group.variable) || !(group.unit === null || safeComparisonText(group.unit, true)) ||
      !Array.isArray(group.values) || variables.has(group.variable)) return null
    const names = new Set()
    const values = []
    for (const item of group.values) {
      if (!item || !safeComparisonText(item.name) || names.has(item.name) || !safeComparisonValue(item.value)) return null
      names.add(item.name)
      values.push({ name: item.name, value: item.value })
    }
    variables.add(group.variable)
    result.push({ variable: group.variable, unit: group.unit, values })
  }
  return result
}

const safeComparisonProfile = profile => {
  if (!profile || !safeComparisonText(profile.branch) || !Array.isArray(profile.variables)) return null
  const variables = profile.variables.map(variable => {
    if (!variable || !safeComparisonText(variable.variable) ||
      !(variable.unit === null || safeComparisonText(variable.unit, true)) || !Array.isArray(variable.values)) return null
    if (variable.variable === 'BranchEquipment') {
      if (!variable.values.every(value => value === null || safeComparisonText(value, true))) return null
    } else if (!variable.values.every(value => value === null || typeof value === 'number' && Number.isFinite(value))) return null
    return { variable: variable.variable, unit: variable.unit, values: [...variable.values] }
  })
  if (variables.some(variable => !variable)) return null
  const distance = variables.find(variable => variable.variable === 'TotalDistance')
  if (!distance?.values.length) return null
  return { branch: profile.branch, variables }
}

export const validatedNetworkComparison = (run, study) => {
  const result = run?.result
  if (!run || !['SUCCEEDED'].includes(run.status) || run.runType !== 'network' ||
    run.resultContract !== 'VALID_FULL' || !result || result.schemaVersion !== 'pipesim-network-result/1' ||
    result.model_kind !== 'network' || result.runTask !== 'network' || result.resultContract !== 'VALID_FULL' ||
    result.simulationState !== 'Completed' || !safeComparisonText(result.study) || result.study !== study ||
    !Array.isArray(result.profiles)) return null
  const profiles = result.profiles.map(safeComparisonProfile)
  if (profiles.some(profile => !profile)) return null
  const system = safeComparisonScalars(result.system)
  const node = safeComparisonScalars(result.node)
  if (!system || !node) return null
  return { study: result.study, system, node, profiles }
}
