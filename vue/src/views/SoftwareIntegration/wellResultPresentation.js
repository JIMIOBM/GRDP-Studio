export const wellComparisonIssue = (result, comparison, fields) => {
  if (!comparison) return ''
  if (!result?.model_kind || result.model_kind !== comparison.model_kind) return '模型类别不同，不能叠加比较。'
  if (fields.some(field => !result?.units?.[field]?.displayUnit || !comparison?.units?.[field]?.displayUnit)) {
    return '结果未提供完整单位，无法确认可比性。'
  }
  if (fields.some(field => result.units[field].displayUnit !== comparison.units[field].displayUnit)) {
    return '结果单位不同，不能叠加比较。'
  }
  return ''
}

const csvCell = value => {
  const text = value == null ? '' : String(value)
  const safe = typeof value === 'string' && /^[=+\-@\t\r]/.test(text) ? `'${text}` : text
  return `"${safe.replaceAll('"', '""')}"`
}

export const wellCsv = rows => '\uFEFF' + rows.map(row => row.map(csvCell).join(',')).join('\r\n')

const download = (url, filename) => {
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  anchor.click()
}

export const downloadWellCsv = (rows, filename) => {
  const url = URL.createObjectURL(new Blob([wellCsv(rows)], { type: 'text/csv;charset=utf-8' }))
  download(url, filename)
  setTimeout(() => URL.revokeObjectURL(url), 0)
}

export const downloadWellChart = (chart, filename) => {
  if (chart) download(chart.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: '#fff' }), filename)
}
