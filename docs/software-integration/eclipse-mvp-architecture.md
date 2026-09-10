# ECLIPSE 100 MVP Architecture Contract

## Scope

This document freezes the ECLIPSE 100 MVP selected by the user. It applies only to the software-integration module.

- Simulator: ECLIPSE 100 2024.1.
- Input: one uploaded `.DATA` file only.
- Rejected input: ZIP, directory packages, `INCLUDE` directives, browser-supplied paths, executable arguments, parameter overrides and deck rewriting.
- Execution: one immutable model-version copy per run.
- Output: ECLEND counts, controlled diagnostics, fresh output Artifact metadata and optional RSM Summary series.
- Excluded: parameter studies, model editing, grid/result binary parsing, ECLIPSE ZIP packages and any derived engineering formulas.

## Verified Environment

- The official launcher is discoverable from the controlled local environment.
- `eclrun.exe --report-versions eclipse` returned `2024.1` during architecture preparation.
- The probe's direct `eclipse.exe` call started ECLIPSE but its deck stopped with `Problems=101` and `Errors=4`. Exit code zero, PRT existence, EGRID existence and INIT existence are insufficient success evidence.

## Worker Contract

### Capability

`GET /api/capabilities` adds:

```json
{
  "eclipse100": {
    "version": "2024.1",
    "launcherFound": true,
    "status": "AVAILABLE",
    "runTasks": ["eclipse"],
    "maxTimeoutSeconds": 1800
  }
}
```

The response contains no installation path, environment value, license value or command line.

### Validation

The existing validation request continues to contain only `modelStorageKey` and `expectedSha256`. For an ECLIPSE candidate, validation must:

1. Require a `.DATA` original filename and verify the storage key and SHA-256.
2. Create and hash-check an isolated validation copy.
3. Reject any deck containing an `INCLUDE` instruction after lexical comment handling, with a stable non-retryable `MODEL / ECLIPSE_INCLUDE_UNSUPPORTED` error.
4. Verify the configured launcher through `eclrun --report-versions eclipse` and require version `2024.1`.
5. Return `READY`, `modelKind=eclipse_100`, an empty Study list and a controlled validation message. Do not run the deck to validate it.

Unavailable launcher or a failed version query is retryable `ENVIRONMENT / ECLIPSE_UNAVAILABLE`. License probing must not print or persist environment variables.

### Execution

The common Worker execute envelope adds `runTask=eclipse`. Its `study` field is null for this task and stays mandatory for PIPESIM tasks. `parameters` remains explicitly null for every simulator.

The ECLIPSE adapter must:

1. Reuse `StorageResolver` isolation, source/copy SHA checks, Artifact publication and cancellation/race semantics.
2. Use a dedicated ECLIPSE coordinator and machine mutex, not `Global\GRDP-Pipesim-Golden-Capture`.
3. Run from the isolated work directory with `eclrun -v 2024.1 eclipse <case.DATA>` and a configurable 30-minute default timeout.
4. Snapshot `.ECLEND`, `.MSG`, `.PRT`, `.RSM`, `.SMSPEC`, `.UNSMRY`, `.EGRID`, `.INIT`, `.S????` and `.S?????` before launch.
5. Publish `PREPARING`, `RUNNING_ECLIPSE` and `COLLECTING` only. Never fabricate a solver percentage.
6. On cancellation, terminate and confirm the owned process tree, then run `eclrun kill <case>` followed by `eclrun check <case>` using independent cleanup timeouts. On normal completion or timeout, run `eclrun check <case>`.
7. Treat an output as fresh only if it did not exist before launch or its size or last-write time changed.

### Completion Classification

Success requires all conditions below:

- launcher process exits with zero;
- fresh `.ECLEND` exists and parses all five counts;
- Errors, Problems and Bugs equal zero;
- joined controlled diagnostics from process stdout/stderr, fresh MSG and ECLEND contain neither License nor Fatal classification.

Failure mapping:

| Condition | Worker error |
| --- | --- |
| launcher unavailable or version mismatch | `ENVIRONMENT / ECLIPSE_UNAVAILABLE`, retryable |
| license diagnostic | `LICENSE / LICENSE_UNAVAILABLE`, retryable |
| missing/freshness-invalid ECLEND or launcher nonzero exit | `EXECUTION / ECLIPSE_RUN_FAILED` |
| ECLEND count above zero or fatal diagnostic | `SOLVER / ECLIPSE_SOLVER_FAILED` |
| cancellation or timeout | existing cancellation/timeout terminal semantics |
| unconfirmed process-tree exit or cleanup failure | existing cleanup failure semantics |

## Result Contract

Only a real successful run may return `eclipse-summary-result/1`.

```json
{
  "schemaVersion": "eclipse-summary-result/1",
  "modelKind": "eclipse_100",
  "runTask": "eclipse",
  "resultContract": "VALID_FULL",
  "caseName": "CASE.DATA",
  "eclEnd": {
    "comments": 0,
    "warnings": 0,
    "problems": 0,
    "errors": 0,
    "bugs": 0
  },
  "summary": {
    "series": [
      {
        "keyword": "FOPR",
        "objectName": null,
        "unit": "STB/DAY",
        "points": [{ "timeDays": 0.0, "value": 0.0 }]
      }
    ]
  },
  "outputFiles": [{ "name": "CASE.ECLEND", "sizeBytes": 1 }]
}
```

- `summary` may be null when no fresh RSM exists or RSM parsing fails; this does not change a successful solver result.
- RSM parsing preserves Avalonia behavior: skip TIME/YEARS as series, retain keyword/unit/object qualifier, merge matching keyword/object series, keep points in source order, skip malformed values and retain empty header series.
- Result validation requires finite point values, nonnegative ordered `timeDays`, valid ECLEND zero counts and fresh Artifact descriptors. No formula-derived values or inferred units are allowed.

## Backend And Browser Contract

- Persist version `modelKind=eclipse_100`; derive model `simulatorType=ECLIPSE_100` from the newest READY version.
- ECLIPSE run creation accepts only `runType=eclipse`, requires `study=null`, and rejects every non-null parameter object. PIPESIM keeps its current Study requirement unchanged.
- Add `RUNNING_ECLIPSE` to the active-slot migration, Java state machine, Worker-event mapping and UI status map.
- The existing global active-run slot remains in effect for MVP, so one simulator operation is active at a time. The ECLIPSE Worker mutex remains distinct for cross-process safety.
- The browser groups `eclipse_100` under `ECLIPSE 模型`, exposes one `ECLIPSE 计算` action, and renders only ECLEND counts, Summary charts/tables when present, Artifact metadata, cleanup state and structured errors.
- Browser API responses, events and Artifacts apply the existing recursive diagnostic sanitizer. Local paths, private pipes and license values never cross the Worker boundary.

## Work Packages

1. Architect, serialized shared contract: approve this document; define lexical `INCLUDE` detection; freeze migration changes, API nullability and result JSON schema.
2. Executor A, serialized migration/backend: model type compatibility, nullable ECLIPSE study contract, `RUNNING_ECLIPSE`, result validator, dispatcher mappings and JUnit tests.
3. Executor B, Worker: options/capability discovery, ECLRUN runner, output snapshot/ECLEND/RSM parser, coordinator, cleanup, Artifact integration and xUnit tests. This package must not touch Vue or database migrations.
4. Executor C, Vue: ECLIPSE tree/category/upload hints, run controls, status stage and result components. It starts after the response schema is frozen.
5. Reviewer: inspect the complete diff, run all unit/build checks, and verify that no license/path data enters Git or API output before authorizing one serialized real-deck run.

## Acceptance Matrix

- Unit: ECLEND count parser; License/Fatal classifier; fresh/stale output behavior; RSM parser; INCLUDE rejection; zero-exit with Errors/Problems failure; null Study compatibility; ECLIPSE result validator.
- Worker integration: launcher unavailable; version mismatch; nonzero exit; fresh clean ECLEND; stale ECLEND; cancellation `kill -> check`; timeout `check`; unconfirmed cleanup; source SHA unchanged.
- Backend: model-version type isolation; rejected PIPESIM/ECLIPSE task combinations; state transitions; sanitized result/event/Artifact publication.
- Vue: ECLIPSE model classification; no Study selector; only ECLIPSE action; no Summary curve when `summary=null`; error and ECLEND count rendering.
- Real acceptance: one user-approved standalone `.DATA` deck that has no `INCLUDE` and completes with fresh zero-count ECLEND. Record source SHA, launcher version, Artifact hashes, Worker cleanup and returned Summary dimensions. A failing deck is valid evidence only for failure classification, never for success.
