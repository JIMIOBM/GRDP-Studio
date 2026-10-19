import { readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { buildRestartForecast } from '../../src/views/SoftwareIntegration/eclipseRestartForecast.js'

export const binaryRestartForecastFixture = () => {
  const read = path => JSON.parse(readFileSync(new URL(path, import.meta.url), 'utf8'))
  const source = read('../../../backend/src/test/resources/eclipse-binary-restart-source-catalog.json')
  const inspection = read('../../../backend/src/test/resources/eclipse-binary-restart-inspection.json')
  const catalogText = JSON.stringify(source), bytes = Buffer.from(catalogText)
  const envelope = label => ({ schemaVersion: 'eclipse-summary-result/1', modelKind: 'eclipse_100', runTask: 'eclipse', resultContract: 'VALID_FULL',
    eclEnd: read(`./eclipseBinaryRestart/${label}.json`).eclEnd, outputFiles: [], summary: { series: read(`./eclipseBinaryRestart/${label}.json`).series } })
  const history = { id: 9551, projectId: 1, modelId: 22, modelVersionId: 201, modelName: 'ECLIPSE演示模型', versionNo: 1, study: null, runType: 'eclipse',
    status: 'SUCCEEDED', resultContract: 'VALID_FULL', parameters: null, events: [], artifacts: [
      { id: 551, name: source.catalog.artifactName, sizeBytes: source.catalog.artifactSizeBytes, sha256: source.catalog.artifactSha256 },
      { id: 552, name: 'eclipse-restart-catalog.json', contentType: 'application/json', sizeBytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') }], result: envelope('history') }
  const future = (id, label, file) => ({ ...structuredClone(history), id, artifacts: [],
    parameters: buildRestartForecast({ source, history, inspection, forecastDataFile: file, restartReport: 80 }), result: envelope(label) })
  return { source, inspection, history, first: future(9552, 'A', 'FOREA.DATA'), second: future(9553, 'B', 'FOREB.DATA'), catalogText, artifactPayloads: { '9551:552': catalogText } }
}
