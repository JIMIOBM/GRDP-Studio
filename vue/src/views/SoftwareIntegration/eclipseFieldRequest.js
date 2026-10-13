// Snapshot index metadata before the first await, including every range segment.
export const snapshotField = field => field ? {
  ...field,
  segments: field.segments?.map(segment => ({ ...segment }))
} : null

export const snapshotRangeContext = (runId, artifacts) => ({
  runId,
  artifacts: artifacts.map(artifact => ({ ...artifact }))
})

export const createFieldRequestGate = () => {
  let generation = 0
  let disposed = false
  return {
    begin() {
      const current = ++generation
      return () => !disposed && current === generation
    },
    invalidate() { generation++ },
    dispose() { disposed = true; generation++ }
  }
}
