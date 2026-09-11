const pad = value => String(value).padStart(2, '0')

function timeParts(value) {
  if (typeof value !== 'string') return null
  const match = /^(\d{4})\/(\d{2})\/(\d{2}) (\d{2}):(\d{2})$/.exec(value)
    || /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::00)?$/.exec(value)
  if (!match) return null
  const [year, month, day, hour, minute] = match.slice(1).map(Number)
  if (year < 1 || year > 9999 || month < 1 || month > 12 || day < 1 || day > 31
    || hour > 23 || minute > 59) return null
  const date = new Date(0)
  date.setUTCFullYear(year, month - 1, day)
  date.setUTCHours(hour, minute, 0, 0)
  if (date.getUTCFullYear() !== year || date.getUTCMonth() + 1 !== month || date.getUTCDate() !== day) return null
  return { year: String(year).padStart(4, '0'), month: pad(month), day: pad(day), hour: pad(hour), minute: pad(minute), timestamp: date.getTime() - 8 * 3600000 }
}

/** Local engineering time is Beijing time, independent of the browser's timezone. */
export function parseOperatingTime(value) {
  return timeParts(value)?.timestamp ?? null
}

/** Normalize valid legacy/display values; preserve unfinished input without validation feedback. */
export function normalizeOperatingTime(value) {
  const parts = timeParts(value)
  return parts ? `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}` : value
}

export function formatOperatingTime(value) {
  const parts = timeParts(value)
  return parts ? `${parts.year}/${parts.month}/${parts.day} ${parts.hour}:${parts.minute}` : value == null ? '' : String(value)
}
