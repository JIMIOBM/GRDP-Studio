const finite = value => typeof value === 'number' && Number.isFinite(value)

export const gridSeriesKey = field => field
  ? [field.keyword, field.dataType, field.count, String(field.file).replace(/\.(?:UNRST|FUNRST|[XF]\d{4,})$/i, '.restart')].join(':')
  : ''

// Report numbers are not elapsed days. Preserve source identity and reject ambiguous frames.
export function gridReportFrames(fields, selected) {
  if (!Number.isSafeInteger(selected?.timeStep) || selected.timeStep <= 0) return []
  const frames = fields.filter(field => gridSeriesKey(field) === gridSeriesKey(selected)
    && Number.isSafeInteger(field.timeStep) && field.timeStep > 0).sort((a, b) => a.timeStep - b.timeStep)
  return new Set(frames.map(field => field.timeStep)).size === frames.length ? frames : []
}

export function gridValueStats(values) {
  let min = Infinity, max = -Infinity, count = 0
  for (const value of values) if (finite(value)) {
    min = Math.min(min, value); max = Math.max(max, value); count++
  }
  if (!count) return null
  let mean = 0
  for (const value of values) if (finite(value)) mean += value / count
  return { min, max, mean, count }
}

export function gridValueRatio(value, bounds) {
  if (!finite(value) || !bounds || !finite(bounds.min) || !finite(bounds.max)) return null
  if (bounds.max <= bounds.min) return 0.5
  const span = bounds.max - bounds.min
  const ratio = Number.isFinite(span) ? (value - bounds.min) / span
    : (value / 2 - bounds.min / 2) / (bounds.max / 2 - bounds.min / 2)
  return Math.max(0, Math.min(1, ratio))
}

export const gridValueColor = (value, bounds) => {
  const ratio = gridValueRatio(value, bounds)
  return ratio === null ? '#94a3b8' : `hsl(${(1 - ratio) * 223.2}, 78%, 50%)`
}

export function gridValueHistogram(values, stats, size = 12) {
  if (!stats || !Number.isInteger(size) || size < 1 || size > 100) return []
  const bins = Array.from({ length: stats.min === stats.max ? 1 : size }, (_, index) => ({ index, count: 0 }))
  for (const value of values) if (finite(value)) bins[Math.min(bins.length - 1, Math.floor(gridValueRatio(value, stats) * bins.length))].count++
  const largest = Math.max(...bins.map(bin => bin.count))
  return bins.map(bin => ({ ...bin, height: largest ? bin.count / largest * 100 : 0,
    low: stats.min * (1 - bin.index / bins.length) + stats.max * (bin.index / bins.length),
    high: stats.min * (1 - (bin.index + 1) / bins.length) + stats.max * ((bin.index + 1) / bins.length) }))
}

export const gridLegendTicks = bounds => bounds ? Array.from({ length: 5 }, (_, i) => bounds.min * (1 - i / 4) + bounds.max * (i / 4)) : []
export const formatGridValue = value => !finite(value) ? '—' : value !== 0 && (Math.abs(value) < 0.001 || Math.abs(value) >= 1e6)
  ? value.toExponential(3) : new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 3 }).format(value)
export const gridFieldUnit = (field, unitSystem) => field?.unit || (/^PERM[XYZ]$/.test(field?.keyword) ? 'mD'
  : ['PORO', 'SWAT', 'SGAS', 'SOIL'].includes(field?.keyword) ? '比例'
    : field?.keyword === 'PRESSURE' && unitSystem === 'FIELD' ? 'psia'
      : field?.keyword === 'PRESSURE' && unitSystem === 'METRIC' ? 'barsa'
        : field?.keyword === 'TEMP' && unitSystem === 'FIELD' ? '°F'
          : field?.keyword === 'TEMP' && unitSystem === 'METRIC' ? '°C' : '模型原单位')
