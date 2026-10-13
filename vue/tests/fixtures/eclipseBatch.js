import { relpermBrowserFixture } from './eclipseRelperm.js'

// Both members retain the separately verified commercial/API series; only browser transport IDs change.
export function batchBrowserFixture() {
  const water = relpermBrowserFixture('SWFN'), oil = relpermBrowserFixture('SOF3')
  const batch = { id: 9801, modelVersionId: water.baseline.modelVersionId, baselineRunId: water.baseline.id,
    name: '相渗两项独立方案', status: 'SUCCEEDED', createdAt: water.baseline.createdAt, baseline: water.baseline,
    members: [{ name: '水相减半', run: water.target }, { name: '油相两列减半', run: oil.target }] }
  const summary = { id: batch.id, modelVersionId: batch.modelVersionId, baselineRunId: batch.baselineRunId, name: batch.name, status: batch.status, createdAt: batch.createdAt, caseCount: 2 }
  return { batch, summary, inspection: water.inspection, baseline: water.baseline, water, oil }
}
