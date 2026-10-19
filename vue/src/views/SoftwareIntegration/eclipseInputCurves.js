import { oilPressureUnit, oilVolumeUnit } from './eclipseOilPvt.js'
import { gasVolumeUnit } from './eclipseGasPvt.js'
import { liveOilRsUnit } from './eclipseLiveOilPvt.js'
import { wetGasRvUnit } from './eclipseWetGasPvt.js'
import { capillaryPressureUnit } from './eclipseRelperm.js'

// Explicit table tokens only: defaults, repetitions and empty strings are not points.
export function inputCurveNumber(value) {
  if (typeof value === 'number') return Number.isFinite(value) ? value : null
  if (typeof value !== 'string' || !/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eEdD][+-]?\d+)?$/.test(value.trim())) return null
  const number = Number(value.trim().replace(/[dD]/, 'e'))
  return Number.isFinite(number) ? number : null
}
const required = value => {
  const number = inputCurveNumber(value)
  if (number == null) throw new Error('原表包含未明确的数值，暂不绘制输入曲线。')
  return number
}
const metric = (key, label, unit, original, target) => ({ key, label, unit, original, target })
function definition(keyword, system, field) {
  const p = oilPressureUnit(system), pc = capillaryPressureUnit(system)
  if (['PVDO', 'PVDG', 'PVTO', 'PVTG'].includes(keyword)) {
    const oil = ['PVDO', 'PVTO'].includes(keyword), volume = oil ? 'Bo' : 'Bg'
    return {
      xLabel: keyword === 'PVTG' ? '汽化油气比 Rv' : '压力', xUnit: keyword === 'PVTG' ? wetGasRvUnit(system) : p,
      branchLabel: keyword === 'PVTO' ? 'Rs' : keyword === 'PVTG' ? '压力' : '', branchUnit: keyword === 'PVTO' ? liveOilRsUnit(system) : p,
      metrics: [metric('volume', volume, oil ? oilVolumeUnit(system) : gasVolumeUnit(system), row => row.volume ?? row.bo ?? row.bg, row => field === 'FORMATION_VOLUME_FACTOR' ? row.target : null),
        metric('viscosity', '黏度', 'cP', row => row.viscosity, row => field === 'VISCOSITY' ? row.target : null)]
    }
  }
  if (keyword === 'SOF3') return { xLabel: '油饱和度 So', xUnit: '无量纲', metrics: [metric('Krow', 'Krow', '无量纲', row => row.kr, row => row.target), metric('Krog', 'Krog', '无量纲', row => row.third, row => row.targetOilGas)] }
  if (['SWFN', 'SGFN'].includes(keyword)) return { xLabel: keyword === 'SWFN' ? '水饱和度 Sw' : '气饱和度 Sg', xUnit: '无量纲', metrics: [metric('kr', keyword === 'SWFN' ? 'Krw' : 'Krg', '无量纲', row => row.kr, row => row.target), metric('pc', '毛管压力', pc, row => row.third, () => null)] }
  if (['SWOF', 'SGOF'].includes(keyword)) return { xLabel: keyword === 'SWOF' ? '水饱和度 Sw' : '气饱和度 Sg', xUnit: '无量纲', metrics: [metric('phase', keyword === 'SWOF' ? 'Krw' : 'Krg', '无量纲', row => row.phase, row => field !== 'OIL_KR' ? row.target : null), metric('oil', keyword === 'SWOF' ? 'Krow' : 'Krog', '无量纲', row => row.oil, row => field === 'OIL_KR' ? row.target : null), metric('pc', '毛管压力', pc, row => row.pc, () => null)] }
  throw new Error('该表族尚未开放输入曲线。')
}

export function inputCurveModel({ tables = [], unitSystem, multiplier, field, validate }) {
  let status = 'original', error = ''
  if (multiplier != null) {
    try {
      if (typeof validate !== 'function') throw new Error('缺少参数校验，暂不显示目标曲线。')
      validate()
      status = 'preview'
    } catch (e) { status = 'invalid'; error = e.message || '参数未通过校验。' }
  }
  try {
    const result = tables.map(table => {
      const d = definition(table.keyword, unitSystem, field)
      if (!d.xUnit || d.metrics.some(m => !m.unit)) throw new Error('原表单位不明确，暂不绘制输入曲线。')
      const branches = new Map()
      for (const row of table.rows) {
        const id = d.branchLabel ? row.branch : 0
        if (d.branchLabel && (!Number.isInteger(id) || id < 1)) throw new Error('原表分支身份不完整。')
        if (!Number.isInteger(row.line) || row.line < 1) throw new Error('原表源行不完整。')
        const branchValue = d.branchLabel ? required(table.keyword === 'PVTO' ? row.rs : row.pressure) : null
        if (!branches.has(id)) branches.set(id, { id, value: branchValue, label: d.branchLabel ? `分支 ${id} · ${d.branchLabel} ${branchValue} ${d.branchUnit}` : table.keyword, rows: [] })
        if (branches.get(id).value !== branchValue) throw new Error('同一分支存在不同参考值，暂不绘制输入曲线。')
        branches.get(id).rows.push(row)
      }
      return { keyword: table.keyword, keywordLine: table.keywordLineNumber, xLabel: d.xLabel, xUnit: d.xUnit, branches: [...branches.values()].map(branch => ({
        ...branch, metrics: d.metrics.map(m => ({ key: m.key, label: m.label, unit: m.unit, points: branch.rows.map(row => {
          const target = status === 'preview' ? m.target(row) : null
          if (target != null && inputCurveNumber(target) == null) throw new Error('目标表点不是有效数值，暂不绘制目标曲线。')
          return { x: required(['SWFN', 'SGFN', 'SOF3', 'SWOF', 'SGOF'].includes(table.keyword) ? row.saturation : table.keyword === 'PVTG' ? row.rv : row.pressure), original: required(m.original(row)), target: target == null ? null : required(target), line: row.line, state: row.state || '' }
        }) }))
      })) }
    })
    return { status, error, tables: result, pointCount: tables.reduce((sum, t) => sum + t.rows.length, 0) }
  } catch (e) { return { status: 'unavailable', error: e.message, tables: [], pointCount: 0 } }
}

export function inputCurvePanels(table, branchId = 'all') {
  if (!table) return []
  const panels = new Map()
  for (const branch of table.branches.filter(b => branchId === 'all' || String(b.id) === branchId)) for (const m of branch.metrics) {
    if (!panels.has(m.unit)) panels.set(m.unit, { unit: m.unit, labels: [], series: [] })
    const panel = panels.get(m.unit)
    if (!panel.labels.includes(m.label)) panel.labels.push(m.label)
    panel.series.push({ name: `原表 · ${m.label} · ${branch.label}`, metric: m.label, branch: branch.label, target: false, points: m.points })
    if (m.points.some(p => p.target != null)) panel.series.push({ name: `目标预览 · ${m.label} · ${branch.label}`, metric: m.label, branch: branch.label, target: true, points: m.points })
  }
  return [...panels.values()]
}

export function inputCurveCsv(model, { runId, modelVersionId, sourceFile, region, regionKind }) {
  const rows = [['baseline_run_id', 'model_version_id', 'source_file', 'region_kind', 'region', 'keyword', 'keyword_line', 'branch', 'branch_reference', 'metric', 'x_label', 'x_unit', 'x', 'y_unit', 'original', 'target_preview', 'source_line', 'state', 'status']]
  for (const table of model.tables) for (const branch of table.branches) for (const m of branch.metrics) for (const p of m.points) rows.push([runId, modelVersionId, sourceFile, regionKind, region, table.keyword, table.keywordLine, branch.id || '', branch.label, m.label, table.xLabel, table.xUnit, p.x, m.unit, p.original, p.target, p.line, p.state, model.status])
  // Treat source names as text when opened in spreadsheet software.
  return '\uFEFF' + rows.map(row => row.map(value => { const s = String(value ?? ''); return `"${(typeof value === 'string' && /^[=+\-@\t\r]/.test(s) ? "'" + s : s).replace(/"/g, '""')}"` }).join(',')).join('\r\n')
}
