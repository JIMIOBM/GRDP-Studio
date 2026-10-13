const keywords = new Set(['PERMX', 'PERMY', 'PERMZ', 'PORO'])
export const gridRegionUnit = keyword => keyword === 'PORO' ? '体积分数（0–1）' : keywords.has(keyword) ? 'mD' : ''

export function validGridRegionParameters(p) {
  const names = ['schemaVersion', 'baselineRunId', 'fieldFile', 'fieldArtifactSha256', 'gridFile', 'gridArtifactSha256', 'keyword', 'nx', 'ny', 'nz', 'i1', 'i2', 'j1', 'j2', 'k1', 'k2', 'originalValues', 'multiplier']
  if (!p || Object.keys(p).length !== names.length || names.some(name => !Object.hasOwn(p, name))
    || p.schemaVersion !== 'eclipse-grid-region-parameters/1' || !Number.isSafeInteger(p.baselineRunId) || p.baselineRunId <= 0
    || !keywords.has(p.keyword) || !/^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.INIT$/i.test(p.fieldFile || '')
    || !/^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.EGRID$/i.test(p.gridFile || '')
    || !/^[0-9a-f]{64}$/.test(p.fieldArtifactSha256 || '') || !/^[0-9a-f]{64}$/.test(p.gridArtifactSha256 || '')) return false
  if (['nx','ny','nz','i1','i2','j1','j2','k1','k2'].some(name => !Number.isSafeInteger(p[name]) || p[name] <= 0 || p[name] > 2_000_000)
    || p.nx * p.ny * p.nz > 2_000_000 || p.i2 < p.i1 || p.j2 < p.j1 || p.k2 < p.k1 || p.i2 > p.nx || p.j2 > p.ny || p.k2 > p.nz) return false
  const count = (p.i2 - p.i1 + 1) * (p.j2 - p.j1 + 1) * (p.k2 - p.k1 + 1)
  return count <= 4096 && Array.isArray(p.originalValues) && p.originalValues.length === count && typeof p.multiplier === 'number'
    && Number.isFinite(p.multiplier) && p.multiplier >= 0.000001 && p.multiplier <= 100 && p.multiplier !== 1
    && p.originalValues.every(v => typeof v === 'number' && Number.isFinite(v) && v > 0 && Number.isFinite(v * p.multiplier) && v * p.multiplier > 0
      && (p.keyword !== 'PORO' || v <= 1 && v * p.multiplier <= 1))
}

export function gridRegionResponse(current, baseline, wantedKeywords = ['WBHP','WOPR','WWPR','WGPR','WOPT','WWPT','WGPT']) {
  const wanted = new Set(wantedKeywords)
  const index = summary => {
    if (!Array.isArray(summary?.series)) throw new Error('缺少实际Summary，无法对比。')
    const result = new Map()
    for (const series of summary.series.filter(s => wanted.has(s.keyword))) {
      const identity = JSON.stringify([series.keyword, series.objectName])
      if (result.has(identity) || typeof series.unit !== 'string' || !series.unit.trim() || !Array.isArray(series.points)) throw new Error('曲线身份或单位无法核对。')
      let previous = -Infinity
      const points = new Map()
      for (const p of series.points) {
        if (!Number.isFinite(p.timeDays) || !Number.isFinite(p.value) || p.timeDays <= previous) throw new Error('报告时刻或数值无效。')
        previous = p.timeDays; points.set(p.timeDays, p.value)
      }
      result.set(identity, { series, points })
    }
    return result
  }
  try {
    const a = index(baseline), b = index(current), rows = []
    let changedCount = 0, unmatchedCount = 0
    for (const identity of new Set([...a.keys(), ...b.keys()])) {
      const old = a.get(identity), next = b.get(identity), series = next?.series || old.series
      if (old && next && old.series.unit !== next.series.unit) throw new Error('基线和方案的曲线单位不一致，不能计算差值。')
      for (const timeDays of [...new Set([...(old?.points.keys() || []), ...(next?.points.keys() || [])])].sort((x,y) => x-y)) {
        const baselineValue = old?.points.get(timeDays) ?? null, currentValue = next?.points.get(timeDays) ?? null
        const difference = baselineValue === null || currentValue === null ? null : currentValue - baselineValue
        if (difference === null) unmatchedCount++
        else if (Math.abs(difference) > Math.max(1e-6, Math.abs(baselineValue) * 1e-6)) changedCount++
        rows.push({ keyword: series.keyword, well: series.objectName, unit: series.unit, timeDays, baseline: baselineValue, current: currentValue, difference })
      }
    }
    return { available: rows.length > 0, rows, changedCount, unmatchedCount, message: rows.length ? '' : '没有已请求的井压力、产量或累计量曲线。' }
  } catch (error) { return { available: false, rows: [], changedCount: 0, unmatchedCount: 0, message: error.message } }
}

export function buildGridRegionParameters({ run, grid, field, initArtifact, gridArtifact, bounds, values, actnum, multiplier }) {
  if (run?.status !== 'SUCCEEDED' || run?.resultContract !== 'VALID_FULL' || run?.parameters != null
    || !Number.isSafeInteger(run.id) || run.id <= 0 || !keywords.has(field?.keyword)
    || !field.file?.toUpperCase().endsWith('.INIT') || field.timeStep !== undefined || !['REAL', 'DOUB'].includes(field.dataType)) {
    throw new Error('请使用完整无参数成功基线和静态 INIT/PERM/PORO 字段。')
  }
  const dimensions = ['nx', 'ny', 'nz'].map(axis => grid?.[axis])
  const size = dimensions.reduce((a, b) => a * b, 1)
  if (!dimensions.every(n => Number.isSafeInteger(n) && n > 0) || size > 2_000_000 || field.count !== size || values?.length !== size || actnum?.length !== size) {
    throw new Error('需要完整全网格场值与 ACTNUM。')
  }
  if (initArtifact?.name !== `eclipse-output-${field.file}` || gridArtifact?.name !== `eclipse-output-${grid.fileName}`
    || [initArtifact, gridArtifact].some(a => a?.contentType !== 'application/octet-stream' || !/^[0-9a-f]{64}$/i.test(a?.sha256 || ''))) {
    throw new Error('缺少同次运行的 INIT/EGRID 校验信息。')
  }
  const axes = [['i1', 'i2'], ['j1', 'j2'], ['k1', 'k2']]
  if (!axes.every(([start, end], axis) => Number.isSafeInteger(bounds[start]) && Number.isSafeInteger(bounds[end])
    && bounds[start] >= 1 && bounds[end] >= bounds[start] && bounds[end] <= dimensions[axis])) throw new Error('区域界限必须为模型范围内的整数，起点不能大于终点。')
  const count = axes.reduce((n, [start, end]) => n * (bounds[end] - bounds[start] + 1), 1)
  if (count > 4096) throw new Error('本次区域最多4096个单元，请缩小范围。')
  if (typeof multiplier !== 'number' || !Number.isFinite(multiplier) || multiplier < 0.000001 || multiplier > 100 || multiplier === 1) throw new Error('乘数须在0.000001–100之间且不能为1。')
  const originalValues = []
  for (let k = bounds.k1; k <= bounds.k2; k++) for (let j = bounds.j1; j <= bounds.j2; j++) for (let i = bounds.i1; i <= bounds.i2; i++) {
    const index = (k - 1) * grid.nx * grid.ny + (j - 1) * grid.nx + i - 1
    const value = values[index], target = value * multiplier
    if (!(actnum[index] > 0)) throw new Error(`选区包含非活动单元 I${i}/J${j}/K${k}。`)
    if (typeof value !== 'number' || !Number.isFinite(value) || value <= 0 || !Number.isFinite(target) || target <= 0
      || field.keyword === 'PORO' && (value > 1 || target > 1)) throw new Error('原值或目标值超出物性范围：孔隙度须大于0且不超过1，渗透率须为正数。')
    originalValues.push(value)
  }
  return { schemaVersion: 'eclipse-grid-region-parameters/1', baselineRunId: run.id, fieldFile: field.file,
    fieldArtifactSha256: initArtifact.sha256.toLowerCase(), gridFile: grid.fileName, gridArtifactSha256: gridArtifact.sha256.toLowerCase(),
    keyword: field.keyword, nx: grid.nx, ny: grid.ny, nz: grid.nz, ...Object.fromEntries(axes.flat().map(key => [key, bounds[key]])), originalValues, multiplier }
}
