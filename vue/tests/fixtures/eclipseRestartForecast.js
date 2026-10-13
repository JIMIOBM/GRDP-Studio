import { readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { buildRestartForecast } from '../../src/views/SoftwareIntegration/eclipseRestartForecast.js'

export const restartForecastFixture = () => {
  // Actual source/catalog metadata; browser cumulative numbers are constructed UI data.
  const source = JSON.parse(readFileSync(new URL('../../../backend/src/test/resources/eclipse-restart-source-catalog.json', import.meta.url), 'utf8'))
  const inspection = JSON.parse(readFileSync(new URL('../../../backend/src/test/resources/eclipse-restart-inspection.json', import.meta.url), 'utf8'))
  const catalogText = JSON.stringify(source), catalogBytes = Buffer.from(catalogText)
  const envelope = series => ({ schemaVersion: 'eclipse-summary-result/1', modelKind: 'eclipse_100', runTask: 'eclipse', resultContract: 'VALID_FULL',
    eclEnd: { comments: 0, warnings: 0, problems: 0, errors: 0, bugs: 0 }, outputFiles: [], summary: { series } })
  const history = { id: 9551, projectId: 1, modelId: 22, modelVersionId: 201, modelName: 'ECLIPSE演示模型', versionNo: 1, study: null, runType: 'eclipse',
    status: 'SUCCEEDED', resultContract: 'VALID_FULL', parameters: null, events: [], artifacts: [
      { id: 551, name: source.catalog.artifactName, sizeBytes: source.catalog.artifactSizeBytes, sha256: source.catalog.artifactSha256 },
      { id: 552, name: 'eclipse-restart-catalog.json', contentType: 'application/json', sizeBytes: catalogBytes.length, sha256: createHash('sha256').update(catalogBytes).digest('hex') }],
    result: envelope(['FOPT', 'WOPT'].map((keyword, index) => ({ keyword, objectName: index ? 'G1' : null, unit: 'STB',
      points: source.catalog.reports.map(r => ({ timeDays: r.timeDays, value: r.timeDays / 2190 * (index ? 10 : 100) })) }))) }
  const first = { ...structuredClone(history), id: 9552, artifacts: [], parameters: buildRestartForecast({ source, history, inspection, forecastDataFile: 'FOREA.DATA', restartReport: 80 }),
    result: envelope([{ keyword: 'FOPT', objectName: null, unit: 'STB', points: [{ timeDays: 2556, value: 150 }, { timeDays: 2921, value: 250 }] },
      { keyword: 'WOPT', objectName: 'G1', unit: 'STB', points: [{ timeDays: 2556, value: 20 }, { timeDays: 2921, value: 35 }] }]) }
  const second = { ...structuredClone(history), id: 9553, artifacts: [], parameters: buildRestartForecast({ source, history, inspection, forecastDataFile: 'FOREB.DATA', restartReport: 80 }),
    result: envelope([{ keyword: 'FOPT', objectName: null, unit: 'STB', points: [{ timeDays: 2556, value: 140 }, { timeDays: 2921, value: 220 }] },
      { keyword: 'WOPT', objectName: 'G1', unit: 'STB', points: [{ timeDays: 2556, value: 18 }, { timeDays: 2921, value: 30 }] }]) }
  return { source, inspection, history, first, second, catalogText, artifactPayloads: { '9551:552': catalogText } }
}
