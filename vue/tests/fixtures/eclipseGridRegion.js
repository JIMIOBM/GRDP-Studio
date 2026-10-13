import { readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
const root = new URL('./eclipseGridRegion/', import.meta.url)
const result = JSON.parse(readFileSync(new URL('result.json', root), 'utf8'))
const provenance = JSON.parse(readFileSync(new URL('provenance.json', root), 'utf8'))
export function gridRegionBrowserFixture(keyword = 'PERMX') {
  const artifacts = provenance.files.map((file, index) => ({ id: 180 + index, name: 'eclipse-output-' + file.name,
    type: 'output', contentType: 'application/octet-stream', sizeBytes: file.sizeBytes, sha256: file.sha256 }))
  const rangePayloads = {}
  for (const file of result.fieldIndex.files) {
    const bytes = readFileSync(new URL(file.name, root))
    const artifact = artifacts.find(a => a.name.endsWith(file.name))
    if (createHash('sha256').update(bytes).digest('hex') !== artifact.sha256) throw new Error('Native browser fixture changed')
    for (const field of file.fields) for (const segment of field.segments) rangePayloads[`${artifact.id}:${segment.offset}`] = bytes.subarray(segment.offset, segment.offset + segment.length)
  }
  const baseline = { id: 8903, projectId: 1, modelId: 22, modelVersionId: 201, versionNo: 1, modelName: 'ECLIPSE演示模型', study: null,
    runType: 'eclipse', status: 'SUCCEEDED', resultContract: 'VALID_FULL', parameters: null, events: [], artifacts, result: structuredClone(result) }
  const init = artifacts.find(a => a.name.endsWith('.INIT')), grid = artifacts.find(a => a.name.endsWith('.EGRID'))
  const parameters = { schemaVersion: 'eclipse-grid-region-parameters/1', baselineRunId: baseline.id, fieldFile: 'ECLIPSE_PERMX_WATER.INIT',
    fieldArtifactSha256: init.sha256, gridFile: 'ECLIPSE_PERMX_WATER.EGRID', gridArtifactSha256: grid.sha256,
    keyword, nx: 15, ny: 15, nz: 1, i1: 1, i2: 3, j1: 1, j2: 2, k1: 1, k2: 1,
    originalValues: Array(6).fill(keyword === 'PORO' ? 1 : 100), multiplier: keyword === 'PORO' ? 0.8 : 0.5 }
  const label = keyword.toLowerCase()
  const native = JSON.parse(readFileSync(new URL(label + '-summary.json', root), 'utf8'))
  const targetBytes = readFileSync(new URL(label + '.INIT', root))
  if (createHash('sha256').update(targetBytes).digest('hex') !== native.sha256) throw new Error('Native regional INIT changed')
  const target = { ...structuredClone(baseline), id: 8904, parameters: structuredClone(parameters),
    artifacts: artifacts.map(a => ({ ...a, id: a.id + 100, sha256: a.name.endsWith('.INIT') ? native.sha256 : a.sha256 })) }
  target.result.summary = native.summary; target.result.eclEnd = native.eclEnd
  for (const f of target.result.outputFiles) if (f.name.endsWith('.INIT')) f.sha256 = native.sha256
  for (const file of target.result.fieldIndex.files) {
    const bytes = file.name.endsWith('.INIT') ? targetBytes : readFileSync(new URL(file.name, root))
    const artifact = target.artifacts.find(a => a.name.endsWith(file.name))
    for (const field of file.fields) for (const segment of field.segments) rangePayloads[`${artifact.id}:${segment.offset}`] = bytes.subarray(segment.offset, segment.offset + segment.length)
  }
  return { baseline, target, rangePayloads, parameters }
}
