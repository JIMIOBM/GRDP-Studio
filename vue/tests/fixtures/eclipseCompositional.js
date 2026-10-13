// Actual native inspection; small synthetic curves for browser contracts only.
const nativeInspection = {
  "schemaVersion": "eclipse-data-inspection/14",
  "caseName": "SPE3_GASCOND.DATA",
  "sections": [
    "RUNSPEC",
    "GRID",
    "PROPS",
    "SOLUTION",
    "SUMMARY",
    "SCHEDULE"
  ],
  "unitSystem": "FIELD",
  "phases": [
    "WATER"
  ],
  "dimensions": {
    "nx": 9,
    "ny": 9,
    "nz": 4
  },
  "wellNames": [
    "I",
    "P"
  ],
  "scheduleTimeline": [
    {
      "kind": "TSTEP",
      "steps": [
        "328.5",
        "328.5",
        "328.5",
        "328.5",
        "328.5",
        "328.5",
        "328.5",
        "328.5",
        "328.5",
        "328.5"
      ],
      "sourceFile": "SPE3_GASCOND.DATA",
      "lineNumber": 463
    },
    {
      "kind": "TSTEP",
      "steps": [
        "36.5",
        "36.5",
        "36.5",
        "36.5",
        "36.5",
        "36.5",
        "36.5",
        "36.5",
        "36.5",
        "36.5"
      ],
      "sourceFile": "SPE3_GASCOND.DATA",
      "lineNumber": 476
    },
    {
      "kind": "TSTEP",
      "steps": [
        "182.5",
        "182.5",
        "182.5",
        "182.5",
        "182.5",
        "182.5",
        "182.5",
        "182.5",
        "182.5",
        "182.5"
      ],
      "sourceFile": "SPE3_GASCOND.DATA",
      "lineNumber": 486
    }
  ],
  "packageFiles": [
    {
      "relativePath": "SPE3_GASCOND.DATA",
      "sizeBytes": 8773,
      "sha256": "30f36c71726bae3e1c3da3820eefa60212f0628a5c4c1a2163f42c51d75d7feb"
    }
  ],
  "simulation": {
    "solver": 300,
    "modelType": "COMPOSITIONAL",
    "componentCount": 9,
    "componentNames": [
      "CO2",
      "N2",
      "C1",
      "C2",
      "C3",
      "C4-6",
      "C7+1",
      "C7+2",
      "C7+3"
    ],
    "equationOfState": "PR"
  }
}
export const compositionalBrowserFixture = () => {
  const inspection = structuredClone(nativeInspection)
  const series = ['FOPR', 'FOPR', 'WXMF_1', 'BXMF_6', 'WGPR'].map((keyword, i) => ({ vectorIndex: i + 1, keyword,
    objectName: i === 2 ? 'P' : i === 4 ? ':+:+:+:+' : null, number: i === 3 ? 61 : 2, unit: i === 2 || i === 3 ? null : 'STB/DAY',
    points: [{ timeDays: 0, value: i + 1 }, { timeDays: 0.25, value: (i + 1) * 0.5 }] }))
  const run = { id: 801, projectId: 1, modelId: 22, modelVersionId: 201, modelName: 'ECLIPSE演示模型', versionNo: 1,
    study: null, runType: 'eclipse', parameters: null, status: 'SUCCEEDED', resultContract: 'VALID_FULL', cancellable: false,
    createdAt: '2026-10-12T10:00:00', elapsedMillis: 1000, events: [], artifacts: [],
    result: { schemaVersion: 'eclipse-summary-result/2', modelKind: 'eclipse_300', runTask: 'eclipse', caseName: inspection.caseName,
      resultContract: 'VALID_FULL', simulation: structuredClone(inspection.simulation),
      eclEnd: { comments: 1, warnings: 3, problems: 0, errors: 0, bugs: 0 }, summary: { startDate: '1990-01-01', timeVectorIndex: 0, series } } }
  return { inspection, run }
}
