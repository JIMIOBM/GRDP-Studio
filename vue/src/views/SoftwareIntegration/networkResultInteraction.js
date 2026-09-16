export const nodeResultRows = (entries, id) => entries.flatMap(entry =>
  (entry.values || []).filter(item => item.name === id).map(item => ({
    variable: entry.variable, unit: entry.unit, value: item.value
  })))

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
