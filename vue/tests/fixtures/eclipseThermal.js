// Actual Worker inspection; small synthetic curves test browser contracts only.
const inspectionSnapshot = {
  "schemaVersion": "eclipse-data-inspection/15",
  "caseName": "THERM3.DATA",
  "sections": [
    "RUNSPEC",
    "GRID",
    "EDIT",
    "PROPS",
    "SOLUTION",
    "SUMMARY",
    "SCHEDULE"
  ],
  "unitSystem": "FIELD",
  "phases": [
    "OIL",
    "WATER",
    "GAS"
  ],
  "dimensions": {
    "nx": 9,
    "ny": 5,
    "nz": 4
  },
  "wellNames": [
    "I",
    "PNEAR",
    "PFAR"
  ],
  "scheduleTimeline": [
    {
      "kind": "TSTEP",
      "steps": [
        "365",
        "365",
        "365",
        "365",
        "365",
        "365",
        "365",
        "365",
        "365",
        "365"
      ],
      "sourceFile": "THERM3.DATA",
      "lineNumber": 460
    }
  ],
  "packageFiles": [
    {
      "relativePath": "THERM3.DATA",
      "sizeBytes": 10695,
      "sha256": "babef9f21032db39dda8ca424a5d8d9fdbf1bfaf4d64fda650372f1820a08de9"
    }
  ],
  "simulation": {
    "solver": 300,
    "modelType": "THERMAL",
    "componentCount": 3,
    "componentNames": [
      "C1",
      "C2",
      "HEAVY"
    ],
    "fluidModel": "LIVEOIL",
    "summaryProgramIdentifier": 500
  }
}
export const thermalBrowserFixture = () => {
  const inspection = structuredClone(inspectionSnapshot)
  const series = [
    ['BTEMP', null, 1, 'DEG F', 125, 510.5],
    ['BTEMP', null, 144, 'DEG F', 125, 424.8],
    ['WTEMP', 'I', 1, 'DEG F', 528, 514],
    ['WTEMP', 'PNEAR', 1, 'DEG F', 125, 510],
    ['WTEMP', 'PFAR', 1, 'DEG F', 125, 509],
    ['WSTPR', 'PNEAR', 1, 'STB/DAY', 0, 2],
    ['FHLR', null, 0, 'BTU/DAY', 3, 4]
  ].map(([keyword, objectName, number, unit, first, last], index) => ({
    vectorIndex: index + 1, keyword, objectName, number, unit,
    points: [{ timeDays: 1, value: first }, { timeDays: 3650, value: last }]
  }))
  const run = { id: 811, projectId: 1, modelId: 22, modelVersionId: 201, modelName: 'ECLIPSE演示模型', versionNo: 1,
    study: null, runType: 'eclipse', parameters: null, status: 'SUCCEEDED', resultContract: 'VALID_FULL', cancellable: false,
    createdAt: '2026-10-12T10:00:00', elapsedMillis: 1000, events: [], artifacts: [],
    result: { schemaVersion: 'eclipse-summary-result/3', modelKind: 'eclipse_300', runTask: 'eclipse', caseName: inspection.caseName,
      resultContract: 'VALID_FULL', simulation: structuredClone(inspection.simulation),
      eclEnd: { comments: 1, warnings: 2, problems: 0, errors: 0, bugs: 0 }, summary: { startDate: '1990-01-01', timeVectorIndex: 0, series } } }
  return { inspection, run }
}
