# GRDP-Studio Software Integration Progress

Last verified: 2026-09-02

## Repository State

- Branch: `violet/feature/software-integration-ui`
- Stage 0 baseline protection is committed and pushed.
- The Stage 2 model-management foundation and Demo-01 run implementation are included in the current handoff.
- Existing unrelated worktree changes must not be reverted.
- Authoritative requirements: `docs/software-integration/requirements.md`
- Multi-Agent workflow: `.opencode/README.md`

## Current Milestone

Stage 0 baseline protection and the Demo-01 implementation are complete in code. Real acceptance is partial:

```text
create software project and READY model version
-> select a persisted Study and nodal/profile/combined
-> persist and claim a Run in Spring Boot
-> execute real PIPESIM PTK through the loopback Worker
-> persist events, result status, error and Artifact metadata
-> display real phases, elapsed time, charts, tables and history
```

The Worker has completed real CSW_101 nodal runs that exactly match Golden. The complete six-run CSW_101/CSW_102 sequence was explicitly deferred by user instruction and must not be represented as passed.

## Verified Complete

### Stage 0 PIPESIM Baseline

- The Avalonia `_clean_number`, curve, profile, and result-contract semantics are frozen in the pure Python `worker/ptk_normalization.py` module.
- `pipesim-well-result/1` freezes result arrays, finite-number requirements, result status, and the approved gas-flow unit policy without guessing black-oil display units.
- Real CSW_101 and CSW_102 nodal, PT-profile, and combined results are stored as six Golden JSON files with six metadata sidecars.
- Capture ran strictly serially in this order: CSW_101 nodal, profile, combined, then CSW_102 nodal, profile, combined.
- Every run was followed by adapter process-tree exit and a successful PTK license reuse probe.
- CSW_101 source SHA-256 remained `bad570add150db61eb5fc48518aa73f75d33114a43f6547d13dd684692aa1b5c` before and after capture.
- CSW_102 source SHA-256 remained `71f369bc2ccb0d39317e97a0d38e3cf63f6dfdb9019298e1f645ed506352f3a7` before and after capture.
- CSW_101 results contain 30 IPR points, 30 VLP points, and 25 profile points; CSW_102 contains 30, 30, and 16 respectively.
- Golden metadata is UTC, strictly ordered, bound to Avalonia revision `795522dfd96cfdeaf42e6549603bc8def6cdf2b6`, and contains no model copies, logs, absolute paths, or credentials.
- Independent review concluded `PASS` for both implementation and real Golden acceptance.

### Demo-01 Persistent PIPESIM Run

- Spring Boot persists Run, Run Event and Artifact metadata in `software_integration_run`, `software_integration_run_event` and `software_integration_artifact`.
- Run states cover queueing, claim, preparation, nodal/profile execution, collection, success, partial success, cancellation, timeout, failure and Worker loss.
- Database claim and an active-slot unique constraint prevent more than one active PIPESIM Run.
- Worker validation and execution share an in-process coordinator and the machine-wide `Global\GRDP-Pipesim-Golden-Capture` mutex.
- Worker requests use relative storage keys and expected SHA-256 values; browser input is never trusted as a local path.
- Worker opens an isolated task copy, leaves the source model unchanged, and checks source SHA-256 before and after execution.
- Python executes existing Studies only with `parameters=null`; nodal, profile and combined are supported.
- Combined nodal success with profile failure is persisted and displayed as `PARTIAL_SUCCEEDED` with `VALID_PARTIAL` result semantics.
- A non-breakaway Windows Job Object owns the Python process tree before the PTK start gate is released.
- Cancellation and timeout publish terminal state only after process-tree exit is confirmed.
- Worker acceptance uncertainty, Worker busy requeue, restart recovery and cancel/success races have explicit persisted semantics.
- Artifact publication validates manifest contents, relative paths, file sizes and SHA-256 before atomic publication.
- Vue uses an isolated Pinia store and provides an explicit READY-model `进入计算` action as well as model double-click activation.
- The central page contains model version, Study, run type, run/cancel controls, real phase, elapsed time, structured error, nodal/profile result tabs and run history.
- Nodal results show IPR/VLP in one ECharts view; profile results show depth, pressure and temperature without guessing unspecified units.

Real execution evidence:

```text
CSW_101 nodal, Worker run 910104
CLAIMED -> PREPARING -> RUNNING_NODAL -> COLLECTING -> SUCCEEDED
30 IPR points, 30 VLP points, VALID_FULL, approximately 70 seconds
Golden exact match; source SHA unchanged; process tree exited; Worker returned idle
```

An earlier Spring Run successfully completed PIPESIM but exposed MySQL JSON numeric normalization (`110.84152977856141` became `110.8415297785614`). Migration `007_software_integration_demo01_result_precision.sql` and the current initializer change `result_json` to `LONGTEXT` so the frozen JSON double representation can be retained. The updated backend was rebuilt and restarted, but a post-deployment six-run comparison was deferred by user instruction.

### Environment And Lifecycle

- AHKs, original GRDP, Studio backend, Vue frontend, MySQL, Redis, and the Worker can be managed with the parent lifecycle scripts.
- Worker is included in start, health-check, PID-recording, and safe-stop flow.
- Current service checks returned HTTP 200 for backend health, Worker health, and frontend login.
- Worker listens on `http://127.0.0.1:5150`.
- Worker capability detection finds PIPESIM 2022.1, Python 3.9, and the PTK module ZIP.

### Backend Model Management

- Independent software-project list, detail, create, update, and soft-delete endpoints exist.
- `.pips` and `.zip` file extensions are accepted at upload with a 500 MB limit.
- Same-name uploads create incrementing model versions.
- Upload records include original name, size, SHA-256, status, and timestamps.
- Revalidation endpoint exists for a model version.
- Project, model, and model-version entities, mappers, DTOs, migration SQL, and development schema initialization exist.
- Asynchronous validation updates `UPLOADED -> VALIDATING -> READY / INVALID / ENVIRONMENT_ERROR`.
- Worker unavailability and PTK license unavailability are classified as environment errors rather than invalid models.

Current browser API endpoints:

```text
GET    /software-integration/projects
GET    /software-integration/projects/{projectId}
POST   /software-integration/projects
PUT    /software-integration/projects/{projectId}
DELETE /software-integration/projects/{projectId}
POST   /software-integration/projects/{projectId}/models
POST   /software-integration/projects/{projectId}/model-versions/{versionId}/validate
```

### Worker Validation

- `GET /api/health` and `GET /api/capabilities` are implemented.
- `POST /api/models/validate` launches the PIPESIM Python Toolkit validation adapter.
- Validation and execution are serialized by the shared Worker coordinator and machine-wide mutex.
- The migrated validation rules require exactly one Well, Completion, and Tubing.
- Approved model kinds are black-oil liquid wells and CSW_102-style vertical compositional gas wells.
- Study names are read from the PTK model catalog.
- PTK license failures return HTTP 503 instead of an invalid-model response.

Real model verification completed successfully:

```text
CSW_101_Basic Oil Well.pips -> READY, black_oil_liquid, Well_1, Study 1
CSW_102_Basic Gas Well.pips -> READY, basic_gas, Well_1, Study 1
```

The browser upload path was also verified with CSW_101 through multipart upload and asynchronous database status write-back.

### Frontend Workspace

- Software integration uses an isolated central workspace rather than the parsing/fusion content tree.
- The left resource hierarchy follows the approved Avalonia semantics: project -> well model category -> model.
- Project selection, search, collapse, model selection, upload, delete, and revalidate interactions are wired.
- Project creation is opened from the Ribbon in a dialog rather than a marketing-style central empty state.
- Validation status, message tooltip, Study list, and revalidation action are displayed.
- Validation polling runs only while a model is `UPLOADED` or `VALIDATING` and is cleared on component unmount.
- Ribbon new-project and model-import commands are connected for software-integration mode.

### Build Verification

- Backend package completed successfully after loading the software-integration code.
- Worker `dotnet build` completed with zero errors.
- Vue `npm run build` completed successfully; existing Sass deprecation and bundle-size warnings remain.
- Worker xUnit completed with 24 passing tests after the final Job Object and cancellation-race fixes.
- `python -m unittest discover -s worker/tests/PythonNormalization -p "test_*.py"` completed with 31 tests and no failures.
- Backend tests completed with 35 passing tests before the result precision migration; the current backend including migration 007 subsequently completed a successful package build.
- `pwsh -File .\worker\tests\Golden\Verify-Golden.ps1 -VerifyLocalSources` verified all six real results and metadata sidecars against the current models and Avalonia adapter.
- The final Vue build completed successfully after adding the explicit calculation entry.
- AHKs, original GRDP, Studio backend, Vue, Worker health and Worker capabilities returned HTTP 200 after deployment.

## Known Gaps And Risks

These items are not complete and must not be represented as finished:

### Demo-01 Acceptance

- Migration 007 has been deployed by rebuilding and restarting the backend, but no new Spring Run has yet demonstrated exact Golden equality after the column conversion.
- The formal CSW_101 nodal/profile/combined then CSW_102 nodal/profile/combined serial acceptance sequence is not complete.
- A failed historical Run remains as audit evidence of the removed Python stdin monitor deadlock. It must not be presented as a current Worker failure.
- Real PIPESIM cancellation and timeout are protected by Job Object integration tests but have not been intentionally triggered against the acceptance models.

### Model Management

- ZIP upload is stored but ZIP extraction and validation are not implemented. Current validation rejects/non-readies ZIP versions.
- ZIP traversal, symlink/reparse-point, file-count, depth, and expanded-size protections are not implemented.
- Existing storage keys under the configured root are normalized to relative keys; root-external values are rejected.
- Model kind and well name returned by Worker are not persisted as model-version metadata.
- Async validation is process-local and not represented by a durable queue. A backend restart can leave work requiring recovery.
- Multi-Worker execution is not implemented; Demo-01 is intentionally single-machine and single-Worker.
- Schema initialization and migration SQL both create the same tables; long-term migration ownership is unresolved.
- Project recovery from the 30-day recycle bin and physical cleanup are not implemented.
- Model deletion and old-version selection UI are not implemented.
- Browser-level automation for model activation and Run controls is not implemented.

### Deferred Production Features

- Artifact download API and 30-day expiration cleanup are not implemented.
- Full production retry policy is not implemented; interrupted simulator Runs are not automatically retried.
- Durable validation queueing remains separate from the persistent Run queue and is not implemented.

### UI And Regression

- Browser behavior has not been automated with Playwright.
- Shared Shell changes have built successfully but do not yet have an automated parsing/fusion regression test.
- Mobile behavior and long-running polling/error transitions need browser-level verification.

## Current PTK Run Contract Research

Avalonia research established the existing Worker envelope for the next milestone:

```json
{
  "task": "run",
  "payload": {
    "study": "Study 1",
    "run_task": "nodal | profile | combined",
    "parameters": null
  }
}
```

First-release web behavior must not expose parameter overrides, so `parameters` remains null and the source model is not modified.

Expected normalized result payload:

```json
{
  "ipr": [{ "flow": 0.0, "pressure": 0.0 }],
  "vlp": [{ "flow": 0.0, "pressure": 0.0 }],
  "profile": [{ "depth": 0.0, "pressure": 0.0, "temperature": 0.0 }],
  "model_kind": "black_oil_liquid | basic_gas"
}
```

Nodal and combined runs require non-empty IPR/VLP. Profile-only runs require a non-empty profile. Avalonia accepts a combined run with valid nodal curves and an empty profile as a partial result; detailed architecture must preserve and explicitly represent that behavior.

## Next Bounded Goal

Run the post-migration Demo-01 acceptance sequence without changing the frozen Golden:

```text
verify result_json is LONGTEXT
-> CSW_101 nodal -> profile -> combined
-> CSW_102 nodal -> profile -> combined
-> compare every Spring result exactly with Golden
-> verify history, Artifact manifest, source SHA and Worker idle after restart
-> perform browser calculation-entry and ordinary /ipr regression checks
-> independent Reviewer PASS
```

## Handoff Rule

At the end of every bounded milestone, update this file with:

- behavior actually completed;
- exact commands and acceptance models executed;
- failures classified as code, environment, license, or unverified;
- new known gaps;
- one next bounded goal.

Do not append conversational history. Replace stale status so this document remains the concise source of current truth.
