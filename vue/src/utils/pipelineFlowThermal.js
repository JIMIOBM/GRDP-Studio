import { normalizeTemperatureSettings } from './pipelineTemperatureState.js'

function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical)
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort()
    .filter(key => value[key] != null).map(key => [key, canonical(value[key])]))
  return value
}

export function flowThermalSourceChanged(detail, snapshot, topologyRevision) {
  return !detail?.revision || !snapshot || snapshot.revision !== detail.revision
    || snapshot.topologyRevision !== topologyRevision
    || JSON.stringify(canonical(normalizeTemperatureSettings(snapshot.settings)))
      !== JSON.stringify(canonical(normalizeTemperatureSettings(detail.settings)))
}

