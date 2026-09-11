// Unsaved temperature settings survive switching sections within this session.
export const temperatureDrafts = new Map()

/** Editable settings include given gas conductivity; density, viscosity and Cp only come from PVT. */
export function normalizeTemperatureSegments(segments) {
  if (!Array.isArray(segments)) return segments
  return segments.map(config => {
    if (config == null) return config
    const { densityKgM3, viscosityMpaS, cpJkgK, propertySource, ...parameters } = config
    return {
    ...parameters,
    layers: Array.isArray(config.layers) ? config.layers.map(layer => ({
      name: layer?.name ?? '',
      thicknessMm: layer?.thicknessMm ?? null,
      conductivityWmK: layer?.conductivityWmK ?? null
    })) : config.layers
    }
  })
}

export function normalizeTemperatureSettings(settings) {
  if (settings == null) return settings
  const { savedResults, ...parameters } = settings
  return { ...parameters, segments: normalizeTemperatureSegments(settings.segments) }
}

export const temperatureSettingsStamp = settings => JSON.stringify(normalizeTemperatureSettings(settings))

/** Normalize both sides of a cached dirty comparison so removed fields cannot cause false unsaved warnings. */
export function normalizeTemperatureDraft(draft) {
  if (draft == null) return draft
  let saved = draft.saved
  if (saved) {
    try { saved = temperatureSettingsStamp(JSON.parse(saved)) }
    catch { /* An absent or unreadable baseline must remain different from a valid draft. */ }
  }
  return { ...draft, settings: normalizeTemperatureSettings(draft.settings), saved }
}

function semanticParameters(value) {
  if (Array.isArray(value)) return value.map(semanticParameters)
  if (value != null && typeof value === 'object') {
    return Object.fromEntries(Object.keys(value).sort().filter(key => value[key] != null)
      .map(key => [key, semanticParameters(value[key])]))
  }
  return value
}

/** A completed save may arrive after leaving the page. Matching server parameters keep its newer baseline. */
export function temperatureRestorePlan(cached, loadedSettings, loadedRevision, topologyRevision) {
  if (!cached || cached.topologyRevision !== topologyRevision) return { useCachedResults: false, restoreDraft: false }
  const cachedDirty = temperatureSettingsStamp(cached.settings) !== cached.saved
  const useCachedResults = cached.revision === loadedRevision || cachedDirty
  const equivalent = JSON.stringify(semanticParameters(normalizeTemperatureSettings(cached.settings)))
    === JSON.stringify(semanticParameters(normalizeTemperatureSettings(loadedSettings)))
  return { useCachedResults, restoreDraft: useCachedResults && cachedDirty && !equivalent }
}

const coefficientKinds = ['inner', 'wall', 'outer', 'overall']
const object = value => value != null && typeof value === 'object' && !Array.isArray(value)
const scopeFor = context => ({
  projectId: Number(context?.projectId),
  gasReservoirId: Number(context?.gasReservoirId),
  wellName: String(context?.wellName ?? '')
})
const validScope = scope => Number.isSafeInteger(scope.projectId) && scope.projectId > 0
  && Number.isSafeInteger(scope.gasReservoirId) && scope.gasReservoirId > 0 && scope.wellName.length > 0
const clone = value => {
  try { return JSON.parse(JSON.stringify(value)) }
  catch { return null }
}
const realRow = row => object(row) && typeof row.edgeId === 'string' && row.edgeId.length > 0
  && ((object(row.result) && Object.keys(row.result).length > 0)
    || (typeof row.error === 'string' && row.error.trim().length > 0))
const records = value => object(value) ? Object.values(value) : []

/** Numeric route IDs and numeric API IDs address the same well; property order is irrelevant. */
export const temperatureContextKey = context => JSON.stringify(scopeFor(context))

function packRow(entry) {
  if (!realRow(entry) || typeof entry.stamp !== 'string' || !entry.stamp) return null
  const { stamp, flowStamp, fromSolve, ...row } = entry
  return clone({ row, stamp, flowStamp: typeof flowStamp === 'string' && flowStamp ? flowStamp : null, fromSolve: fromSolve === true })
}

/** Keep actual API rows and their original dependency marks, never derived or freshly stamped values. */
export function packTemperatureResults({ context, topologyRevision, calculated, coupledResults, solution, solutionMark, calculationMode } = {}) {
  return {
    scope: scopeFor(context),
    topologyRevision: Number(topologyRevision) || 0,
    coefficients: Object.fromEntries(coefficientKinds.map(kind => [kind, records(calculated?.[kind]).map(packRow).filter(Boolean)])),
    coupled: records(coupledResults).map(packRow).filter(Boolean),
    solution: object(solution) ? clone(solution) : null,
    solutionMark: typeof solutionMark === 'string' ? solutionMark : '',
    calculationMode: calculationMode === 'coupled' ? 'coupled' : 'coefficient'
  }
}

function emptyResults() {
  return { calculated: Object.fromEntries(coefficientKinds.map(kind => [kind, {}])),
    coupledResults: {}, solution: null, solutionMark: '', calculationMode: 'coefficient' }
}

/** A duplicate pipe makes the whole group ambiguous. Other invalid rows are discarded individually. */
function restoreGroup(rows, edgeIds, fromSolve) {
  if (!Array.isArray(rows)) return {}
  const seen = new Set(), entries = []
  for (const saved of rows) {
    const id = saved?.row?.edgeId
    if (typeof id === 'string') {
      if (seen.has(id)) return {}
      seen.add(id)
    }
    if (!object(saved) || !realRow(saved.row) || !edgeIds.has(id)
      || typeof saved.stamp !== 'string' || !saved.stamp || saved.fromSolve !== fromSolve) continue
    if (fromSolve && (typeof saved.flowStamp !== 'string' || !saved.flowStamp)) continue
    const row = clone(saved.row)
    if (!row) continue
    entries.push([id, { ...row, stamp: saved.stamp,
      flowStamp: typeof saved.flowStamp === 'string' && saved.flowStamp ? saved.flowStamp : null, fromSolve }])
  }
  return Object.fromEntries(entries)
}

/** Scope/topology changes discard history. Changed parameters keep old marks for the normal stale check. */
export function restoreTemperatureResults(savedResults, context, graph, topologyRevision) {
  const restored = emptyResults(), scope = scopeFor(context)
  if (!object(savedResults) || !validScope(scope) || !validScope(scopeFor(savedResults.scope))
    || temperatureContextKey(savedResults.scope) !== temperatureContextKey(scope)
    || !Number.isSafeInteger(Number(topologyRevision)) || Number(topologyRevision) <= 0
    || Number(savedResults.topologyRevision) !== Number(topologyRevision)) return restored
  const edgeIds = new Set((Array.isArray(graph?.edges) ? graph.edges : []).map(edge => edge?.id).filter(id => typeof id === 'string'))
  for (const kind of coefficientKinds) restored.calculated[kind] = restoreGroup(savedResults.coefficients?.[kind], edgeIds, false)
  const coupled = restoreGroup(savedResults.coupled, edgeIds, true)
  const mark = savedResults.solutionMark, solution = savedResults.solution
  if (Object.keys(coupled).length && typeof mark === 'string' && mark
    && records(coupled).every(entry => entry.flowStamp === mark)
    && object(solution?.input) && object(solution?.result)
    && Array.isArray(solution.result.points) && solution.result.points.length) {
    const copied = clone(solution)
    if (copied) {
      restored.coupledResults = coupled
      restored.solution = copied
      restored.solutionMark = mark
    }
  }
  const hasIndependent = records(restored.calculated.overall).some(realRow)
  const hasCoupled = records(restored.coupledResults).some(realRow)
  restored.calculationMode = savedResults.calculationMode === 'coupled' ? 'coupled' : 'coefficient'
  if (restored.calculationMode === 'coupled' && !hasCoupled && hasIndependent) restored.calculationMode = 'coefficient'
  else if (restored.calculationMode === 'coefficient' && !hasIndependent && hasCoupled) restored.calculationMode = 'coupled'
  return restored
}

/** Re-entering or clicking the same node opens existing results, including reported calculation errors. */
export function temperatureResultPanel(kind, calculated, coupledResults, calculationMode = 'coefficient') {
  if (!coefficientKinds.includes(kind)) return 'input'
  const selected = kind === 'overall' && calculationMode === 'coupled' ? coupledResults : calculated?.[kind]
  return records(selected).some(realRow) ? 'table' : 'input'
}
