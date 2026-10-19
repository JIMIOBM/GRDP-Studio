const sha = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value)
const integer = (value, min = 0, max = Number.MAX_SAFE_INTEGER) => Number.isSafeInteger(value) && value >= min && value <= max
const fields = (value, names) => value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length === names.length && names.every(name => Object.hasOwn(value, name))
const dataPath = value => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9._ /-]{0,511}\.DATA$/i.test(value) && !value.includes('\\') && !value.includes(':') && value.split('/').every(part => part && part !== '.' && part !== '..')
const dateMs = value => {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return null
  const time = Date.parse(`${value}T00:00:00Z`)
  return Number.isFinite(time) && new Date(time).toISOString().slice(0, 10) === value ? time : null
}
const daysBetween = (origin, date) => (dateMs(date) - dateMs(origin)) / 86400000

export const validRestartSource = source => {
  if (!fields(source, ['schemaVersion', 'catalog', 'physicsSha256', 'forecastInputs']) || !['eclipse-restart-source/1', 'eclipse-restart-source/2'].includes(source.schemaVersion) || !sha(source.physicsSha256)) return false
  const c = source.catalog, binary = source.schemaVersion === 'eclipse-restart-source/2'
  if (!fields(c, ['schemaVersion', 'caseName', 'startDate', 'artifactName', 'artifactSha256', 'artifactSizeBytes', 'reports']) || c.schemaVersion !== (binary ? 'eclipse-restart-catalog/2' : 'eclipse-restart-catalog/1') ||
    typeof c.caseName !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}$/.test(c.caseName) || c.artifactName !== `eclipse-output-${c.caseName}.${binary ? 'UNRST' : 'FUNRST'}` ||
    !sha(c.artifactSha256) || !integer(c.artifactSizeBytes, 1, 128 * 1024 * 1024) || dateMs(c.startDate) === null || !Array.isArray(c.reports) || !c.reports.length || c.reports.length > 8192) return false
  let previousNumber = -1, previousTime = -1, end = null
  const first = c.reports[0]
  for (const report of c.reports) {
    if (!fields(report, ['reportNumber', 'timeDays', 'date', 'unitSystem', 'dimensions', 'activeCells', 'phaseMask', 'solver', 'byteOffset', 'sizeBytes', 'sha256']) ||
      !integer(report.reportNumber, 0, 2147483647) || !integer(report.timeDays, 0, 3000000) || dateMs(report.date) === null || daysBetween(c.startDate, report.date) !== report.timeDays ||
      !['FIELD', 'METRIC'].includes(report.unitSystem) || !fields(report.dimensions, ['nx', 'ny', 'nz']) || !Object.values(report.dimensions).every(n => integer(n, 1, 100000000)) ||
      !integer(report.activeCells, 1, 100000000) || !integer(report.phaseMask, 1, 7) || report.solver !== 100 || !sha(report.sha256) ||
      !integer(report.byteOffset, 0, c.artifactSizeBytes) || !integer(report.sizeBytes, 1, c.artifactSizeBytes) || report.byteOffset + report.sizeBytes > c.artifactSizeBytes ||
      end !== null && report.byteOffset !== end || report.reportNumber <= previousNumber || report.timeDays <= previousTime) return false
    const cells = report.dimensions.nx * report.dimensions.ny * report.dimensions.nz
    if (!integer(cells, 1, 100000000) || report.activeCells > cells || ['activeCells', 'phaseMask', 'solver', 'unitSystem'].some(key => report[key] !== first[key]) ||
      ['nx', 'ny', 'nz'].some(key => report.dimensions[key] !== first.dimensions[key])) return false
    previousNumber = report.reportNumber; previousTime = report.timeDays; end = report.byteOffset + report.sizeBytes
  }
  if (end !== c.artifactSizeBytes || !Array.isArray(source.forecastInputs) || source.forecastInputs.length > 64) return false
  const paths = new Set()
  for (const future of source.forecastInputs) {
    if (!fields(future, ['relativePath', 'sha256', 'restartReport', 'restartCase', 'futureDates']) || !dataPath(future.relativePath) || !sha(future.sha256) || paths.has(future.relativePath) ||
      !integer(future.restartReport, 1, 2147483647) || future.restartCase !== c.caseName || !Array.isArray(future.futureDates) || !future.futureDates.length || future.futureDates.length > 8192) return false
    const report = c.reports.find(r => r.reportNumber === future.restartReport)
    if (!report) return false
    let previous = report.date
    for (const date of future.futureDates) { if (dateMs(date) === null || date <= previous) return false; previous = date }
    paths.add(future.relativePath)
  }
  return true
}
export const validRestartForecast = parameters => fields(parameters, ['schemaVersion', 'historyRunId', 'forecastDataFile', 'restartArtifactName', 'restartArtifactSha256', 'restartReport', 'restartTimeDays', 'restartDate', 'physicsSha256']) &&
  ['eclipse-history-forecast-parameters/2', 'eclipse-history-forecast-parameters/3'].includes(parameters.schemaVersion) && integer(parameters.historyRunId, 1) && dataPath(parameters.forecastDataFile) &&
  typeof parameters.restartArtifactName === 'string' && (parameters.schemaVersion === 'eclipse-history-forecast-parameters/3' ? /^eclipse-output-[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.UNRST$/i : /^eclipse-output-[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.FUNRST$/i).test(parameters.restartArtifactName) &&
  sha(parameters.restartArtifactSha256) && integer(parameters.restartReport, 1, 2147483647) && integer(parameters.restartTimeDays, 0, 3000000) && dateMs(parameters.restartDate) !== null && sha(parameters.physicsSha256)

export const buildRestartForecast = ({ source, history, inspection, forecastDataFile, restartReport }) => {
  if (!validRestartSource(source) || !history || history.runType !== 'eclipse' || history.status !== 'SUCCEEDED' || history.resultContract !== 'VALID_FULL' || history.parameters != null || history.resultExpired === true || !integer(history.id, 1)) return null
  const c = source.catalog, future = source.forecastInputs.find(f => f.relativePath === forecastDataFile && f.restartReport === restartReport)
  const report = c.reports.find(r => r.reportNumber === restartReport)
  if (!future || !report || !(inspection?.packageFiles || []).some(f => f.relativePath === future.relativePath && f.sha256 === future.sha256) ||
    !(history.artifacts || []).some(a => a.name === c.artifactName && a.sha256 === c.artifactSha256 && a.sizeBytes === c.artifactSizeBytes)) return null
  return { schemaVersion: c.schemaVersion === 'eclipse-restart-catalog/2' ? 'eclipse-history-forecast-parameters/3' : 'eclipse-history-forecast-parameters/2', historyRunId: history.id, forecastDataFile, restartArtifactName: c.artifactName,
    restartArtifactSha256: c.artifactSha256, restartReport, restartTimeDays: report.timeDays, restartDate: report.date, physicsSha256: source.physicsSha256 }
}
export const restartForecastMatchesSource = (parameters, source, history, inspection) => {
  if (!validRestartForecast(parameters)) return false
  const actual = buildRestartForecast({ source, history, inspection, forecastDataFile: parameters.forecastDataFile, restartReport: parameters.restartReport })
  return actual !== null && Object.keys(actual).every(key => actual[key] === parameters[key])
}

const fullGrid = series => {
  if (!Array.isArray(series) || !series.length) return null
  const grid = series[0]?.points?.map(p => p.timeDays)
  if (!Array.isArray(grid) || !grid.length || !grid.every((t, i) => typeof t === 'number' && Number.isFinite(t) && t >= 0 && (!i || t > grid[i - 1]))) return null
  const identities = new Set()
  for (const item of series) {
    const key = `${item.keyword}\u0001${item.objectName ?? ''}`
    if (typeof item.keyword !== 'string' || identities.has(key) || !Array.isArray(item.points) || item.points.length !== grid.length ||
      item.points.some((p, i) => p.timeDays !== grid[i] || typeof p.value !== 'number' || !Number.isFinite(p.value))) return null
    identities.add(key)
  }
  return grid
}
export const restartForecastSeries = ({ source, history, run, inspection }) => {
  if (!run || run.status !== 'SUCCEEDED' || run.resultContract !== 'VALID_FULL' || run.modelId !== history?.modelId || run.modelVersionId !== history?.modelVersionId ||
    !restartForecastMatchesSource(run.parameters, source, history, inspection)) return []
  const historical = history.result?.summary?.series, future = run.result?.summary?.series
  const hg = fullGrid(historical), fg = fullGrid(future), cutoff = run.parameters.restartTimeDays
  const dates = source.forecastInputs.find(f => f.relativePath === run.parameters.forecastDataFile).futureDates
  if (!hg || !fg || !hg.includes(cutoff) || fg.length !== dates.length || fg.some((t, i) => t <= cutoff || t !== daysBetween(source.catalog.startDate, dates[i]))) return []
  return [...historical.map(s => ({ ...s, points: s.points.filter(p => p.timeDays <= cutoff), sourceRunId: history.id, historical: true })),
    ...future.map(s => ({ ...s, sourceRunId: run.id }))]
}
export const compareRestartForecasts = ({ source, history, first, second, inspection }) => {
  const invalid = reason => ({ valid: false, reason, rows: [], series: [] })
  if (![first, second].every(run => run && run.status === 'SUCCEEDED' && run.resultContract === 'VALID_FULL' && run.runType === 'eclipse' &&
    run.modelId === history?.modelId && run.modelVersionId === history?.modelVersionId && restartForecastMatchesSource(run.parameters, source, history, inspection))) return invalid('两个预测须来自同一成功历史、同一物理模型和已验证报告')
  if (first.id === second.id || first.parameters.forecastDataFile === second.parameters.forecastDataFile || first.parameters.restartReport !== second.parameters.restartReport) return invalid('请选择同一截止报告的两个不同未来方案')
  const cutoff = first.parameters.restartTimeDays, h = history.result?.summary?.series, a = first.result?.summary?.series, b = second.result?.summary?.series
  const hg = fullGrid(h), ag = fullGrid(a), bg = fullGrid(b)
  if (!hg || !ag || !bg || !hg.includes(cutoff)) return invalid('历史截止点或完整实际曲线缺失')
  for (const [run, grid] of [[first, ag], [second, bg]]) {
    const dates = source.forecastInputs.find(f => f.relativePath === run.parameters.forecastDataFile).futureDates
    if (grid.length !== dates.length || grid.some((time, i) => time <= cutoff || time !== daysBetween(source.catalog.startDate, dates[i]))) return invalid('未来输出时刻与原始日期不一致，不能对齐')
  }
  const common = ag.filter(time => bg.includes(time))
  if (!common.length) return invalid('两个未来方案没有共同的实际输出时刻')
  const rows = []
  for (const original of h.filter(s => ['FOPT', 'WOPT'].includes(s.keyword))) {
    const one = a.find(s => s.keyword === original.keyword && s.objectName === original.objectName), two = b.find(s => s.keyword === original.keyword && s.objectName === original.objectName)
    if (!one || !two || !original.unit || one.unit !== original.unit || two.unit !== original.unit) return invalid('累计产油曲线或单位不完整')
    const historical = original.points.find(p => p.timeDays === cutoff).value
    for (const time of common) {
      const x = one.points.find(p => p.timeDays === time).value, y = two.points.find(p => p.timeDays === time).value
      if (x < historical || y < historical) return invalid('未来累计值早于历史累计状态，接续异常')
      rows.push({ keyword: original.keyword, objectName: original.objectName || '全场', timeDays: time,
        date: new Date(dateMs(source.catalog.startDate) + time * 86400000).toISOString().slice(0, 10), unit: original.unit,
        historical, firstTotal: x, secondTotal: y, firstIncrement: x - historical, secondIncrement: y - historical, difference: x - y })
    }
  }
  if (!rows.some(r => r.keyword === 'FOPT')) return invalid('缺少原生全场累计产油，不能推算未来增量')
  return { valid: true, reason: '历史与两个未来方案已按同一 START 日期核对，累计增量来自真实累计值', rows,
    series: [...h.map(s => ({ ...s, points: s.points.filter(p => p.timeDays <= cutoff), sourceRunId: history.id, historical: true })),
      ...a.map(s => ({ ...s, sourceRunId: first.id })), ...b.map(s => ({ ...s, sourceRunId: second.id }))] }
}
