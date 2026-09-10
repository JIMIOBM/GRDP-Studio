# GRDP-Studio Software Integration Progress

Last verified: 2026-09-10

## Repository State

- Branch: `violet/feature/software-integration-ui`
- Stage 0 baseline protection is committed and pushed.
- The Stage 2 model-management foundation, Demo-01 well run, PIPESIM Network vertical slice, and the paused ECLIPSE migration checkpoint are included in the current handoff.
- Existing unrelated worktree changes must not be reverted.
- Authoritative requirements: `docs/software-integration/requirements.md`
- Multi-Agent workflow: `.opencode/README.md`

## Current Milestone

Stage 0 baseline protection, the Demo-01 well workflow, and the PIPESIM Network B/S vertical slice are complete in code. The implemented workflow is:

```text
create software project and upload a well or network .pips model
-> Worker validates the immutable version and persists its model kind and Studies
-> select a persisted Study and compatible nodal/profile/combined/network run type
-> persist and claim a Run in Spring Boot
-> execute real PIPESIM PTK through the loopback Worker
-> persist events, validated result contract, error, cleanup and Artifact metadata
-> display well curves/profiles or network topology/branch profiles, diagnostics and history
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

### PIPESIM Network Vertical Slice

- Validation distinguishes a surface Network from the internal Source found in single-well models, requires upstream Source/Well, Sink, Flowline and Connection, and validates each returned Network Study through PTK.
- Successful validation persists version-level `modelKind=network`; run compatibility no longer depends on the mutable parent model type.
- Migration `008_software_integration_pipesim_network.sql` and the schema initializer add `RUNNING_NETWORK`, version-level model kind, and repeatable READY-version backfill. Historical well rows use `legacy_well` rather than inventing an exact fluid kind.
- Worker capabilities advertise `pipesimNetwork` and the `network` task while retaining the same in-process and machine-wide serialization used by well tasks.
- The Worker executes the existing Study with `parameters=null`, requests the approved six numeric profile variables, and preserves every PTK-returned branch plus system and node variables.
- `pipesim-network-result/1` freezes topology, system, node, profiles, summary, messages and quality. Every profile has non-empty equal-length TotalDistance and Pressure arrays.
- NaN/Infinity, finite PTK missing sentinels and native numeric missing values become `null` with exactly matching `NON_FINITE` or `UNAVAILABLE` quality entries. Textual BranchEquipment gaps are not misclassified as numeric quality failures.
- Validation diagnostics, run events, result responses and newly generated Artifacts redact drive paths and private `net.pipe` identifiers.
- Spring dispatch validates the complete Network contract before publishing `SUCCEEDED / VALID_FULL`; well result validation remains on the unchanged `pipesim-well-result/1` path.
- Vue separates well and network models, offers only compatible run types per selected version, and displays a directed ECharts topology, topology counts, branch selector, profile chart, searchable system/node tables, summary/messages/quality and run history.
- Completed PIPESIM Well and PIPESIM Network Ribbon commands open the model-import workflow instead of the generic development placeholder.
- Independent final review concluded `PASS` after Study binding, strict numeric/path validation, Artifact/API redaction and unknown-version UI handling were verified in the complete diff.

Real Network execution evidence before the final strict Study/numeric/path gate:

```text
Official CSN_302_Gas Transmission Network.pips, Project 5 / Model 6 / Version 6 / Run 7
CREATED -> QUEUED -> CLAIMED -> PREPARING -> RUNNING_NETWORK -> COLLECTING -> SUCCEEDED
VALID_FULL in 14.034 seconds; 12 nodes, 12 edges, 2 sources, 1 sink, 6 flowlines
91 system variables, 25 node variables, six profiles with 501/501/7/4/101/7 points
7 UNAVAILABLE values; no drive paths, unredacted net.pipe IDs, or numeric missing sentinels
five Artifacts with matching manifest/API SHA-256; source SHA unchanged; Worker idle

Run 8 repeated the same VALID_FULL topology/variable/profile/quality dimensions in 23.366 seconds,
published five Artifacts, confirmed process-tree exit, and returned the Worker to idle.
```

### Environment And Lifecycle

- AHKs, original GRDP, Studio backend, Vue frontend, MySQL, Redis, and the Worker can be managed with the parent lifecycle scripts.
- Worker is included in start, health-check, PID-recording, and safe-stop flow.
- Current service checks returned HTTP 200 for backend health, Worker health, and frontend login.
- Worker listens on `http://127.0.0.1:5150`.
- Worker capability detection finds PIPESIM 2022.1, Python 3.9, and the PTK module ZIP.

### ECLIPSE Migration Checkpoint

- ECLIPSE migration is selected as the next software-integration capability. Architecture preparation is complete; implementation has not started.
- A separate C# probe project built successfully and launched the trusted local ECLIPSE 2024.1 installation. The process created current-run PRT, EGRID and INIT outputs and exited without leaving an ECLIPSE process behind.
- The probe's sample deck did not complete a valid simulation: its PRT contained four errors, 101 reported problems, repeated `CONVERGENCE ERROR = NaN`, and a terminal `RUN STOPPED` condition. Process exit code zero and output-file existence therefore must not be treated as simulation success.
- The official launcher is discoverable from the controlled local environment; `eclrun.exe --report-versions eclipse` returned `2024.1`. The Worker must use this launcher, not the probe's direct `eclipse.exe` invocation.
- The user selected the first MVP input boundary: one `.DATA` file only, with ZIP, directory packages and `INCLUDE` decks rejected. No Study, parameter override or input-deck modification is permitted.
- No ECLIPSE Worker, Spring Boot, database, API or Vue implementation has been added to GRDP-Studio. The prepared contract and Executor work packages are in `docs/software-integration/eclipse-mvp-architecture.md`.
- The external probe is evidence for executable invocation only. Its permissive success check, formula-generated fallback values, hard-coded license-path handling and license-environment logging must not be migrated.
- The contract preserves Avalonia-compatible RSM/ECLEND parsing, output freshness, License/Fatal classification, immutable isolated-run behavior, cancellation cleanup and result semantics.

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
- Well validation requires exactly one Well, Completion, and Tubing; Network validation instead requires the approved surface-network topology and a runnable Network Study.
- Approved model kinds are black-oil liquid wells, CSW_102-style vertical compositional gas wells, and PIPESIM Network models.
- Study names are read from the PTK model catalog.
- PTK license failures return HTTP 503 instead of an invalid-model response.

Real model verification completed successfully:

```text
CSW_101_Basic Oil Well.pips -> READY, black_oil_liquid, Well_1, Study 1
CSW_102_Basic Gas Well.pips -> READY, basic_gas, Well_1, Study 1
CSN_302_Gas Transmission Network.pips -> READY, network, Study 1
```

The browser upload path was also verified with CSW_101 through multipart upload and asynchronous database status write-back.

### Frontend Workspace

- Software integration uses an isolated central workspace rather than the parsing/fusion content tree.
- The left resource hierarchy separates project -> well/network model category -> model.
- Project selection, search, collapse, model selection, upload, delete, and revalidate interactions are wired.
- Project creation is opened from the Ribbon in a dialog rather than a marketing-style central empty state.
- Validation status, message tooltip, Study list, and revalidation action are displayed.
- Validation polling runs only while a model is `UPLOADED` or `VALIDATING` and is cleared on component unmount.
- Ribbon new-project and model-import commands are connected for software-integration mode.

### Build Verification

- Backend package completed successfully after loading the software-integration code.
- Worker `dotnet build` completed with zero errors.
- Vue `npm run build` completed successfully; existing Sass deprecation and bundle-size warnings remain.
- Worker xUnit completed with 26 passing tests after adding Network state, capability and sanitized-Artifact coverage.
- `python -m unittest discover -s worker/tests/PythonNormalization -p "test_*.py"` completed with 39 tests and no failures.
- Backend Maven tests completed with 52 passing tests, including requested-Study binding, numeric/path uniqueness, version isolation, migration backfill and diagnostic redaction.
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
- Model kind is persisted per version and legacy READY rows are backfilled; the Worker-returned well name is not yet persisted as separate version metadata.
- Async validation is process-local and not represented by a durable queue. A backend restart can leave work requiring recovery.
- Multi-Worker execution is not implemented; Demo-01 is intentionally single-machine and single-Worker.
- Schema initialization and migration SQL both create the same tables; long-term migration ownership is unresolved.
- Project recovery from the 30-day recycle bin and physical cleanup are not implemented.
- Model deletion is not implemented; explicit old-version selection and version-bound history are implemented.
- Browser-level automation for model activation and Run controls is not implemented.

### Deferred Production Features

- Artifact download API and 30-day expiration cleanup are not implemented.
- Full production retry policy is not implemented; interrupted simulator Runs are not automatically retried.
- Durable validation queueing remains separate from the persistent Run queue and is not implemented.

### UI And Regression

- Browser behavior has not been automated with Playwright.
- Shared Shell changes have built successfully but do not yet have an automated parsing/fusion regression test.
- Mobile behavior and long-running polling/error transitions need browser-level verification.
- The final reviewed build is deployed. Runs 9 and 10 returned retryable `LICENSE_UNAVAILABLE` during earlier post-hardening attempts and completed process-tree cleanup; the user subsequently confirmed the license environment is available and explicitly requested no further live verification in this handoff. These runs remain environment observations, not code failures.
- A fresh CSN_302 run against the final deployed build is intentionally deferred until the user decides to continue; Runs 7 and 8 remain the verified real Network success evidence.

## Frozen PIPESIM Well Run Contract

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

Keep the reviewed PIPESIM implementation and frozen Golden unchanged while implementing the approved ECLIPSE MVP:

```text
Architect reviews the prepared ECLIPSE MVP contract and freezes any implementation-level ambiguity
-> Executors complete migration/schema and Worker contract work before parallel Worker and Vue work packages
-> Reviewer verifies the complete diff before any real ECLIPSE B/S acceptance run
```

The separately deferred fresh CSN_302 run and six-run CSW_101/CSW_102 comparison remain explicit PIPESIM acceptance gaps; they must not be run or represented as passed without a new user decision.

## Handoff Rule

At the end of every bounded milestone, update this file with:

- behavior actually completed;
- exact commands and acceptance models executed;
- failures classified as code, environment, license, or unverified;
- new known gaps;
- one next bounded goal.

Do not append conversational history. Replace stale status so this document remains the concise source of current truth.
