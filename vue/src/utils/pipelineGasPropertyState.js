export const gasPropertyContextKey = context => JSON.stringify({
  projectId: Number(context.projectId), gasReservoirId: Number(context.gasReservoirId), wellName: String(context.wellName || '')
})

export function inspectGasComposition(rows, catalog = []) {
  const sum = rows.reduce((total, row) => total + Number(row.moleFraction), 0)
  let issue = ''
  if (!rows.length) issue = 'empty'
  else if (rows.some(row => row.moleFraction == null || !Number.isFinite(Number(row.moleFraction)) || Number(row.moleFraction) < 0 || Number(row.moleFraction) > 1)) issue = 'fraction'
  else if (new Set(rows.map(row => row.code)).size !== rows.length) issue = 'duplicate'
  // Match the persisted PVT composition and EOS validation tolerance.
  else if (!Number.isFinite(sum) || Math.abs(sum - 1) > 1e-6) issue = 'total'
  else if (catalog.length && rows.some(row => !catalog.some(item => item.code === row.code))) issue = 'unsupported'
  return { sum, issue }
}

/** Describe the selected PVT's recorded fractions without inventing missing components. */
export function gasPropertyCompositionIssue(rows, catalog = [], pvtName = '当前井 PVT 模型') {
  const { sum, issue } = inspectGasComposition(rows, catalog)
  const source = `PVT“${pvtName}”`
  if (issue === 'total') {
    const percent = sum * 100
    if (percent < 100) return `${source}现有组分的摩尔含量合计为 ${percent.toFixed(6)}%，不足 100%，请在管束能力 PVT 模型页补齐真实气体组成，不能根据差额推算甲烷等烃类含量。`
    return `${source}现有组分的摩尔含量合计为 ${percent.toFixed(6)}%，超过 100%，数据无效，当前无法计算或保存。`
  }
  return {
    empty: `${source}没有已保存的气体组分摩尔含量，当前无法计算或保存。`,
    fraction: `${source}存在缺失或无效的气体组分摩尔含量；每项应为 0～100%，当前无法计算或保存。`,
    duplicate: `${source}包含重复气体组分，当前无法计算或保存。`,
    unsupported: `${source}包含当前物性计算未支持的气体组分，当前无法计算或保存。`
  }[issue] || ''
}

const hasNumber = value => value != null && String(value).trim() !== '' && Number.isFinite(Number(value))

/** Return actionable reasons instead of silently disabling a parameterized calculation. */
export function gasPropertyConditionIssues({ pressureMpa, temperatureC }) {
  const issues = []
  if (!hasNumber(pressureMpa)) issues.push('请填写计算压力（MPa，绝压）。')
  else if (Number(pressureMpa) <= 0)
    issues.push('计算压力采用绝对压力，必须大于 0 MPa。')
  if (!hasNumber(temperatureC)) issues.push('请填写计算温度（℃）。')
  else if (Number(temperatureC) <= -273.15)
    issues.push('计算温度必须高于绝对零度（−273.15 ℃）。')
  return issues
}

export function gasPropertyInputIssues({ pvtId, method, pressureMpa, temperatureC, compositionIssue }) {
  const issues = gasPropertyConditionIssues({ pressureMpa, temperatureC })
  if (!hasNumber(pvtId) || Number(pvtId) <= 0) issues.unshift('请先在管束能力 PVT 模型页保存当前井的完整气体组成。')
  else if (compositionIssue) issues.unshift(compositionIssue)
  if (!['PR', 'SRK', 'BWRS'].includes(method)) issues.push('请选择 PR、SRK 或 BWRS 计算方法。')
  return issues
}

export function gasPropertyInputs(model) {
  return { pvtId: model.pvtId, method: model.method, points: Object.fromEntries(['z', 'cp'].map(kind => [kind, {
    pressureMpa: model.points[kind].pressureMpa, temperatureC: model.points[kind].temperatureC
  }])) }
}

export function gasPropertyResultStamp(model, kind, compositionRevision) {
  return JSON.stringify({ source: 'pipeline-pvt-model-v1', pvtId: model.pvtId, method: model.method, compositionRevision: compositionRevision || 0,
    pressureMpa: model.points[kind].pressureMpa, temperatureC: model.points[kind].temperatureC })
}

export const gasPropertyResultUnsaved = (point, savedResultMark) => !!point.result && point.resultMark !== savedResultMark

/** A previous calculation remains reviewable even when edited inputs make it stale. */
export const gasPropertyInitialPanel = point => point?.result ? 'result' : 'input'

/** A checkpoint may predate the current model or belong to the old PVT source with a colliding numeric ID. */
export function gasPropertyCheckpointLabel(checkpoint, model) {
  if(model && Number(checkpoint?.pvtId)===Number(model.pvtId)
    && checkpoint?.compositionRevision && checkpoint.compositionRevision===model.compositionRevision) return model.pvtName || `PVT 模型（ID：${model.pvtId}）`
  return checkpoint?.pvtId ? `历史 PVT（ID：${checkpoint.pvtId}）` : '历史 PVT（来源未记录）'
}

/** A save owns the shared selection and one point. The other point's local work remains a draft. */
export function reconcileGasPropertyPoints(savedPoints, draftPoints, savedKind) {
  const other = savedKind === 'z' ? 'cp' : 'z'
  return JSON.parse(JSON.stringify({ ...savedPoints, [other]: draftPoints[other] }))
}

/** Context changes invalidate pending calculation/save responses; source changes also order PVT requests. */
export function createGasPropertyRequestScope() {
  let context = '', generation = 0, source = 0
  const capture = () => ({ context, generation })
  const current = token => token.context === context && token.generation === generation
  return {
    open(value) { context = gasPropertyContextKey(value); ++generation; ++source; return capture() },
    capture, current,
    source() { return { ...capture(), source: ++source } },
    currentSource(token) { return current(token) && token.source === source },
    invalidate() { ++generation; ++source }
  }
}
