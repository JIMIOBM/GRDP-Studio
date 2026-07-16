'use strict'

function assertPositiveInteger(value, name) {
  if (!Number.isInteger(value) || value <= 0) {
    throw new TypeError(`${name} must be a positive integer`)
  }
}

function normalizeWellNames(wellNames) {
  if (!Array.isArray(wellNames)) throw new TypeError('wellNames must be an array')
  const normalized = [...new Set(wellNames.map(value => String(value || '').trim()).filter(Boolean))]
  if (normalized.length === 0) throw new TypeError('wellNames must contain at least one value')
  return normalized
}

function placeholders(values) {
  return values.map(() => '?').join(', ')
}

function toFiniteNumberOrNull(value) {
  if (value === null || value === undefined || value === '') return null
  const number = Number(value)
  return Number.isFinite(number) ? number : null
}

function isPositiveFinite(value) {
  return Number.isFinite(Number(value)) && Number(value) > 0
}

function isNonNegativeFinite(value) {
  return Number.isFinite(Number(value)) && Number(value) >= 0
}

module.exports = {
  assertPositiveInteger,
  isNonNegativeFinite,
  isPositiveFinite,
  normalizeWellNames,
  placeholders,
  toFiniteNumberOrNull
}
