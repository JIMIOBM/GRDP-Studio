import { createPipelineInput } from './pipelineDefaults.js'
import { normalizeBoundary } from './pipelineBoundary.js'

export const pipelineSectionFields = Object.freeze({
  boundary: Object.freeze(['boundary', 'standardPressurePa', 'standardTemperatureK', 'standardZ'])
})

const objectValue = value => value !== null && typeof value === 'object' && !Array.isArray(value)
const clone = value => Array.isArray(value) ? value.map(clone) : objectValue(value)
  ? Object.fromEntries(Object.entries(value).map(([key, item]) => [key, clone(item)])) : value

function mergeDefaults(defaults, incoming) {
  const merged = clone(defaults)
  if (!objectValue(incoming)) return merged
  for (const [key, value] of Object.entries(incoming)) {
    // JSON responses omit nullable fields; omitted values retain the empty-input shape.
    if (value === undefined) continue
    merged[key] = objectValue(defaults[key]) ? mergeDefaults(defaults[key], value)
      : Array.isArray(defaults[key]) ? (Array.isArray(value) ? clone(value) : []) : clone(value)
  }
  return merged
}

export function normalizePipelineInput(input) {
  const normalized = mergeDefaults(createPipelineInput(), input)
  normalized.boundary = normalizeBoundary(normalized.boundary)
  normalized.constraints = { waterState: normalized.constraints?.waterState === 'available' ? 'available' : 'unknown' }
  return normalized
}

export function sectionInput(input, scope) {
  const fields = pipelineSectionFields[scope]
  if (!fields) throw new RangeError(`不支持的管束能力参数页面：${scope}`)
  const normalized = normalizePipelineInput(input)
  return Object.fromEntries(fields.map(key => [key, clone(normalized[key])]))
}

export function mergePipelineSection(current, saved, scope) {
  const merged = normalizePipelineInput(current)
  const selected = sectionInput(saved, scope)
  Object.assign(merged, selected)
  return merged
}

function equalValue(left, right) {
  if (Object.is(left, right)) return true
  if (Array.isArray(left) || Array.isArray(right)) return Array.isArray(left) && Array.isArray(right)
    && left.length === right.length && left.every((value, index) => equalValue(value, right[index]))
  if (!objectValue(left) || !objectValue(right)) return false
  const keys = Object.keys(left)
  return keys.length === Object.keys(right).length && keys.every(key => Object.hasOwn(right, key) && equalValue(left[key], right[key]))
}

export function reconcilePipelinePage(current, previousSaved, nextSaved, scope) {
  const merged = normalizePipelineInput(current)
  const previous = normalizePipelineInput(previousSaved)
  const next = normalizePipelineInput(nextSaved)
  const updateUntouched = (target, before, after, excluded = []) => {
    const keys = new Set([...Object.keys(target), ...Object.keys(before), ...Object.keys(after)])
    for (const key of keys) {
      if (excluded.includes(key) || !equalValue(target[key], before[key])) continue
      if (Object.hasOwn(after, key)) target[key] = clone(after[key])
      else delete target[key]
    }
  }
  // Keep actual local edits, but adopt concurrent server edits before moving the saved baseline.
  // Geometry comes from the saved topology and is refreshed separately by the caller.
  updateUntouched(merged, previous, next, ['segments', 'equipment'])
  return mergePipelineSection(merged, next, scope)
}
