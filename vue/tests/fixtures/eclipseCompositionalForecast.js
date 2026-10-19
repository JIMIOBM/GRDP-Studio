import { readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { compositionalFluidPropertyFixture } from './eclipseCompositionalFluidProperty.js'
import { buildCompositionalForecast, compositionalSaveCatalogName } from '../../src/views/SoftwareIntegration/eclipseCompositionalForecast.js'
export const compositionalForecastFixture = () => {
  const read = name => JSON.parse(readFileSync(new URL(`./eclipseCompositionalForecast/${name}.json`, import.meta.url), 'utf8'))
  const previous = compositionalFluidPropertyFixture(), inspection = previous.inspection, catalog = read('catalog'), catalogText = JSON.stringify(catalog)
  const artifact = { id: 800001, name: compositionalSaveCatalogName, contentType: 'application/json', artifactType: 'output',
    sha256: createHash('sha256').update(catalogText).digest('hex'), sizeBytes: Buffer.byteLength(catalogText) }
  const history = { ...previous.baseline, id: 9961, result: read('history-result'), artifacts: [artifact,
    ...catalog.files.map((f, j) => ({ id: 800002 + j, name: f.artifactName, sizeBytes: f.sizeBytes, sha256: f.sha256, contentType: 'application/octet-stream', artifactType: 'output' }))] }
  const parameters = targetGasRate => buildCompositionalForecast({ inspection, historyRun: history, catalog, artifact, targetGasRate })
  const inherited = { ...structuredClone(history), id: 9962, parameters: parameters(null), artifacts: [], result: read('original-result') }
  const lower = { ...structuredClone(history), id: 9963, parameters: parameters(4500), artifacts: [], result: read('lower-result') }
  const portableInspection = read('portable-inspection'), portable = { ...structuredClone(lower), id: 9964, modelVersionId: 202, parameters: null }
  return { inspection, catalog, catalogText, artifact, history, inherited, lower, portableInspection, portable }
}
