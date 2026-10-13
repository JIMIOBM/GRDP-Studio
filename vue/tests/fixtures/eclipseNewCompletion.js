import { readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
const root = new URL('./eclipseNewCompletion/', import.meta.url)
const load = name => JSON.parse(readFileSync(new URL(name + '.json', root), 'utf8'))
export function newCompletionBrowserFixture() {
  const proof = load('native-acceptance'), receipt = load('platform-acceptance')
  if (!proof.nativeVerified || !receipt.apiVerified) throw new Error('New-completion native/platform proof missing')
  const inspection = load('inspection'), baseline = load('baseline'), target = load('target'), rangePayloads = {}
  for (const run of [baseline, target]) {
    Object.assign(run, { projectId: 1, modelId: 22, modelVersionId: 201, versionNo: 1, modelName: 'ECLIPSE演示模型' })
    for (const file of run.result.fieldIndex.files) {
      if (!file.name.endsWith('.EGRID') && !file.name.endsWith('.INIT')) continue
      const fixtureName = file.name.endsWith('.INIT') ? (run === baseline ? 'baseline.INIT' : 'target.INIT') : file.name
      const bytes = readFileSync(new URL(fixtureName, root)), artifact = run.artifacts.find(a => a.name === 'eclipse-output-' + file.name)
      if (createHash('sha256').update(bytes).digest('hex') !== artifact.sha256) throw new Error('New-completion binary fixture changed')
      for (const field of file.fields) for (const segment of field.segments) {
        rangePayloads[`${artifact.id}:${segment.offset}`] = bytes.subarray(segment.offset, segment.offset + segment.length)
        if (field.keyword === 'ACTNUM') for (let position = segment.offset; position < segment.offset + segment.length; position += 4)
          rangePayloads[`${artifact.id}:${position}`] = bytes.subarray(position, position + 4)
      }
    }
  }
  return { inspection, baseline, target, parameters: structuredClone(target.parameters), rangePayloads }
}
