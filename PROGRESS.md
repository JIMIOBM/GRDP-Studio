# GRDP-Studio Software Integration Progress

Last verified: 2026-09-16

## Repository State

- Branch: `violet/feature/software-integration-ui`
- Stage 0 baseline protection is committed and pushed.
- The Stage 2 model-management foundation, Demo-01 well run, PIPESIM Network vertical slice, and ECLIPSE demonstration workflow are included in the current branch.
- Existing unrelated worktree changes must not be reverted.
- Authoritative requirements: `docs/software-integration/requirements.md`

## Current Milestone

2026-09-16 well scenario extension: reservoir pressure remains the only editable field. It is now supported for nodal/profile/combined runs of validated `basic_gas` wells and nodal runs of `black_oil_liquid`; Network, ECLIPSE, legacy wells and oil profile/combined remain rejected. Strict `pipesim-well-parameters/1` contains only `reservoirPressurePsi` (finite, positive, at most 100000; a product guard, not an official physical limit). Spring persists and returns the snapshot, dispatches it unchanged, and Worker writes it to the request artifact and adapter envelope. Python checks the completion descriptor unit is exactly psia, reads the original value, sets the isolated model and verifies readback before running; for gas PT/profile phases, the same snapshot is also passed as the PT `InletPressure` per Avalonia behavior. Null retains every previous baseline task. No source-model writes or result normalization changes were introduced.

Verification of the scenario package: 132 backend tests, 193 Worker Release tests, 50 Python tests and 12 frontend E2E tests passed; Vue built successfully. Earlier user acceptance applies to the preceding nodal scenario and result-page package, not to this gas PT extension; it is recorded as user acceptance, not independently captured new simulator evidence. No restart or fresh simulation was performed by this work package. Other parameter fields and live parameter-effect evidence remain outstanding; source-pressure preview is implemented below.

Stage 0 baseline protection, the Demo-01 well workflow, PIPESIM Network and ECLIPSE 100 MVP are implemented. Current priority is deployed browser demonstration and readable real results across all three simulator paths. The PIPESIM workflow is:

```text
create software project and upload a well or network .pips model
-> Worker validates the immutable version and persists its model kind and Studies
-> select a persisted Study and compatible nodal/profile/combined/network run type
-> persist and claim a Run in Spring Boot
-> execute real PIPESIM PTK through the loopback Worker
-> persist events, validated result contract, error, cleanup and Artifact metadata
-> display well curves/profiles or network topology/branch profiles, diagnostics and history
```

The formal real B/S PIPESIM acceptance sequence completed on 2026-09-13. CSW_101 nodal/profile/combined and CSW_102 nodal/profile/combined ran strictly serially through Spring Boot and the loopback Worker; all six persisted `SUCCEEDED / VALID_FULL` and exactly matched the frozen Golden results.

The three simulator pages now restore persisted Run history through the same model-version path. Re-entering a retained PIPESIM Well, PIPESIM Network or ECLIPSE 100 version reloads the latest terminal result or resumes polling an active Run without duplicating activation requests or allowing stale navigation responses to cross models.

ECLIPSE uses one standalone `.DATA` without `INCLUDE`, no Study, official `eclrun`, fresh ECLEND success gates and actual RSM Summary. Official BRILLIG Run 49 is the recorded real calculation evidence; see the ECLIPSE checkpoint below.

The 2026-09-14 requirements clarification separates demo-package acceptance (§0.1) from full first-release delivery (§18), includes all three simulators and distinguishes build, mocked UI, real calculation and deployed browser evidence. Consistency/path checks and `git diff --check` passed for this documentation change. No application build, service restart or new simulator run was performed for it; existing production gaps and live-verification stop conditions remain in effect.

## Verified Complete

### Basic-Gas PT Scenario Runs

- The approved reservoir-pressure scenario now covers all three well run types for `basic_gas`. Nodal uses the isolated Completion pressure; PT profile and combined additionally pass the same value as `PTProfileSimulation.InletPressure`, while retaining the existing gas flow-rate controls. Oil remains nodal-only for non-null scenarios; baseline `parameters=null` behavior is unchanged.
- Model kind is checked again after opening the actual model before any scenario mutation or simulation. Unsupported combinations fail with the existing structured scenario-parameter error; combined PT failure still produces the existing valid partial-result contract.
- Verification: Python fake execution matrix passed 50/50, Worker Release build/tests passed 193/193, backend tests passed 131/131, frontend units passed 11/11 and mocked E2E passed 12/12. The PT parameter names and `psia` unit were checked against the local official Toolkit example and Avalonia adapter. No real gas scenario run or service restart was performed; real CSW_102 profile/combined scenario acceptance remains required.

### Network Branch Condition Overview

- Network results now include a branch condition table with pressure units, point count, first/last returned pressure, signed endpoint difference, valid range and missing-point count. It supports branch search, missing-data filtering, CSV export and exact jump to the existing branch chart.
- The view explicitly states that endpoint order is simulator-return order and does not infer physical flow direction. Missing values and unknown units remain unavailable; no interpolation, unit conversion or fabricated endpoint is added.
- Verification: Network E2E passed 1/1 with exact CSV values and jump behavior; overview unit tests passed 2/2; Vue production build passed. No Network contract, Worker, source model or simulator execution changed.

### Compact Chart Range Sliders

- Software-integration nodal, Network branch and ECLIPSE Summary charts share a 6-pixel neutral-gray range slider with 12-pixel end handles. Removed the data-shadow miniature and additional move bar; native range adjustment, selected-window dragging and inside zoom remain enabled. Existing PT charts have no visible slider and are unchanged.
- Verification: slider unit test passed; Network, RSM and well historical-comparison browser regressions passed 3/3; Vue production build passed. The 1280-wide RSM fixture screenshot was visually inspected. Only chart presentation changed; no simulator run, service restart or other business-module change.

### Original Well Parameter Preview

- Supported well validation reads the unique completion's reservoir pressure from the isolated model copy after confirming the official unit is `psia`. The optional `pipesim-well-inspection/1` metadata is persisted per version and exposed only for READY black-oil/basic-gas wells. No source write, new endpoint, schema migration or calculation-contract change is involved.
- Missing, invalid or unsupported-unit values are unavailable, not guessed or converted; optional preview failure does not invalidate an otherwise supported model. Existing versions require an explicit "重新验证并读取" action. Pending reads immediately disable new calculation, including before the HTTP response arrives. The page shows the original value, can explicitly fill it into a new pressure scenario, and displays original versus edited values without auto-submitting. Source values above the scenario's edit limit remain viewable but cannot be copied into the editor.
- Verification: backend 131 tests, Worker Release 191 tests and Python 48 tests passed; frontend 9 unit tests and 11 mocked browser tests passed, followed by a passing held-validation-request regression. Vue production build passed. The 1280-wide fixture screenshot was visually checked. Backend packaging compiled but could not replace the running JAR; no service was stopped. Real PTK preview, deployment and real parameter-effect acceptance remain separate outstanding checks.

### Project Soft-Delete Recovery Boundary

- Project deletion now has an explicit regression contract: when no active run exists, the endpoint returns HTTP 200 and marks only the project with `deleted_at`; model versions, completed runs, events, artifacts and source files remain available for the future recycle-bin recovery flow. Active runs continue to return scoped HTTP 409.
- The deployed 8080 process was an older JAR that stayed resident while a later build replaced the file, which caused the observed 500. The executable JAR was rebuilt with JDK 21 and the lifecycle script restarted the backend; the Vite-proxied create/delete probe then returned 200. Sunday1 (project id 9) is now in the recycle-bin state; its model and run history were not physically removed.
- Verification: backend Maven tests passed 132/132, including the completed-project soft-delete test; `/actuator/health` returned `UP`; the deployed proxy delete probe returned 200. No active simulator run was interrupted and no source model was modified.

### Demonstration Pages And Scenario Reuse

- Well history now identifies original-model and reservoir-pressure scenarios. Loading a same-version historical scenario fills the nodal parameter controls without submitting a run or changing the historical snapshot; reuse is disabled during execution, and switching version/task clears the editor.
- The well combined-result tab follows the Avalonia nodal/profile arrangement: IPR/VLP and pressure/temperature profiles appear together, with existing tables, exports and provenance. Narrow layouts stack the panels; inactive charts are unmounted.
- ECLIPSE can compare a successful same-version historical RSM result with the current result. Default pairing requires identical keyword, object and explicit unit. Current curves are solid and historical curves dashed; CSV retains each run ID and its original time/value samples. Navigation clears comparison state and ignores late responses. No interpolation or result-contract relaxation was added.
- Verification: `npm run test:e2e` passed 12/12 (43.3 seconds), covering scenario reuse, gas PT task selection, combined results, historical RSM exports and navigation races; `npm run build` passed with existing dependency/chunk warnings. Fixture-based screenshots were captured at 1440 and 1280 widths; the combined 1440 and historical RSM 1280 views were visually checked. These are frontend checks, not new real simulator evidence. No services were restarted or source models modified.

### Result Interaction And Network Branch Comparison

- CSN_302 display fix (2026-09-15): the frontend incorrectly rejected official empty unit strings and treated `BranchEquipment` strings as numeric profile data. The boundary now preserves empty units and explicitly validates equipment strings/nulls and their point count; unsafe local paths and invalid numeric data are still rejected. Direct read-only checks against persisted Runs 56 and 52 now accept all 12 nodes and 6 profiles; Run 53 remains partial with 20 nodes and 7 profiles. The logged-in localhost browser restored Run 56 successfully, showing topology, the 501-point B_C pressure table, 91 system variables and 25 node variables, with no result-unavailable notice. Seven unit tests, the Network E2E test (including textual equipment with empty units), and Vue build passed. No new simulator run or backend change was needed.

- The user accepted the current result-page changes on 2026-09-15: searchable ECLIPSE RSM vectors with same-unit overlays and CSV/PNG export; well same-version historical curve comparison and exports; Network device search, exact result linkage, per-run browser-local layout persistence and exports. This is user acceptance of the UI package, not evidence of a new simulator calculation.
- Network multi-branch profile comparison is available. A primary branch can overlay up to four other branches for the selected pressure, temperature, velocity, density or Z-factor variable when returned distance and variable units are explicitly identical. Each branch retains its original distance samples, point order and null gaps; no interpolation, guessed units or offset alignment is applied.
- The combined raw table and CSV identify the branch; CSV also identifies Run and Study. Switching the model/result, primary branch or variable clears comparisons. Unsupported units or malformed comparison arrays disable that branch without hiding the original result.
- Verification on this worktree: `node --test tests/unit/result-presentation.test.js` passed 5/5; `npm run test:e2e -- --grep 'PIPESIM Network'` passed 1/1, including overlay selection, exact uneven-distance CSV output, model-switch reset and existing topology interactions; `npm run build` passed with existing dependency/chunk warnings. The initial interaction test clicked an input covered by the Element Plus placeholder; targeting the visible placeholder resolved the test failure.
- No backend, Worker, simulator contract, source model or success gate changed. No service restart or new calculation was performed. The new branch comparison has mocked browser evidence; live multi-branch comparison and its dedicated viewport visual checks remain unverified. Existing earlier calculation evidence remains applicable only to unchanged execution code.

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

Formal post-deployment six-run acceptance on 2026-09-13:

```text
CSW_101 nodal    -> Run 27,  79.746 s, 30 IPR / 30 VLP /  0 profile, 5 Artifacts
CSW_101 profile  -> Run 28,  12.757 s,  0 IPR /  0 VLP / 25 profile, 5 Artifacts
CSW_101 combined -> Run 29,  70.885 s, 30 IPR / 30 VLP / 25 profile, 5 Artifacts
CSW_102 nodal    -> Run 30, 113.035 s, 30 IPR / 30 VLP /  0 profile, 5 Artifacts
CSW_102 profile  -> Run 31,  11.403 s,  0 IPR /  0 VLP / 16 profile, 5 Artifacts
CSW_102 combined -> Run 32, 113.145 s, 30 IPR / 30 VLP / 16 profile, 5 Artifacts
```

For every Run, the persisted result matched the corresponding frozen Golden Schema, model kind, run task, result contract, units, array order, lengths and every double value. Each Run confirmed `cleanup.processTreeExitConfirmed=true`, published five validated Artifact records, returned the Worker to `idle=true`, and left the official source-model SHA-256 unchanged. The sequence stopped between Runs until those gates passed; no PIPESIM tasks ran in parallel.

An earlier Spring Run successfully completed PIPESIM but exposed MySQL JSON numeric normalization (`110.84152977856141` became `110.8415297785614`). Migration `007_software_integration_demo01_result_precision.sql` and the current initializer change `result_json` to `LONGTEXT` so the frozen JSON double representation can be retained. The post-deployment Runs 27-32 now verify that all six persisted results retain exact Golden double values after this conversion.

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

Fresh final-contract Network acceptance on 2026-09-13:

```text
CSN_302_Gas Transmission Network, Project 5 / Model 6 / Version 6 / Run 37
CREATED -> QUEUED -> CLAIMED -> PREPARING -> RUNNING_NETWORK -> COLLECTING -> SUCCEEDED
VALID_FULL in 23.195 seconds; 12 nodes, 12 edges, six profiles, seven quality entries
five Artifacts; process-tree exit confirmed; Worker returned idle
no private net.pipe URI in the persisted browser API result
```

Run 36 is retained as audit evidence of a fixed cross-layer redaction-contract defect: the Worker had completed a valid full CSN_302 result, but its redacted message still used the URI-shaped placeholder `net.pipe://localhost/pipe/[redacted]`, which the strict Spring sanitizer correctly rejected. The Worker now emits the idempotent plain placeholder `[local pipe]` and validates full-result summary, messages and quality fields before publishing success. Spring's strict validator was not relaxed.

Latest Demo-first verification on 2026-09-12:

```text
CSN_308_Water Injection Network, Project 6 / Model Version 7 / Run 18
CREATED -> QUEUED -> CLAIMED -> PREPARING -> RUNNING_NETWORK -> COLLECTING -> PARTIAL_SUCCEEDED
VALID_PARTIAL in 15.564 seconds; PIPESIM returned simulationState=Completed.
The Worker accepts the model's Source + Well source-count convention for partial results,
removes raw simulator diagnostics from the limited display payload, and Spring Boot persists
the safe topology as a real partial result. Vue labels it "部分真实计算结果" and does not
claim complete tables, profiles, or a VALID_FULL result.

Run 20 repeated the same real partial-result path after the Worker root-whitelist hardening:
`PREPARING -> RUNNING_NETWORK -> COLLECTING -> PARTIAL_SUCCEEDED` in 18.645 seconds.
```

- The run page now presents persisted execution events, cleanup outcome and Artifact metadata without exposing paths, raw worker logs, or download links.
- ECLIPSE successful runs without an RSM Summary now explicitly state that no usable Summary data was returned; no curve is inferred.
- The demo workspace now follows the parsing/fusion visual language and provides a state-derived project -> import -> validation -> calculation -> result guide; its result page keeps audit metadata collapsed below the primary result.
- The Network result topology is rendered as a compact PIPESIM-inspired equipment schematic: semantic well/source/sink/flowline/junction/control/rotating/process symbols, deterministic returned-connection orientation, safe hover detail, overlap-hidden short labels, and a responsive non-overlapping legend. It preserves every returned node and edge rather than inferring or dropping engineering components.

### Environment And Lifecycle

- AHKs, original GRDP, Studio backend, Vue frontend, MySQL, Redis, and the Worker can be managed with the parent lifecycle scripts.
- Worker is included in start, health-check, PID-recording, and safe-stop flow.
- Current service checks returned HTTP 200 for backend health, Worker health, and frontend login.
- Worker listens on `http://127.0.0.1:5150`.
- Worker capability detection finds PIPESIM 2022.1, Python 3.9, and the PTK module ZIP.

### ECLIPSE Migration Checkpoint

- Verified real ECLIPSE success on 2026-09-14 using the official `C:\ecl\2024.1\eclipse\data\BRILLIG.DATA`: Software Project 6 / Model 12 / Version 14 / Run 49 completed as `SUCCEEDED / VALID_FULL` in 11.788 seconds through the browser -> Spring Boot -> Worker -> `eclrun -v 2024.1 eclipse` path. The immutable uploaded deck has SHA-256 `1ad7e36b6b2928c88b1661180f9e5cedcde729b62afe58d03ba9baec2b65215b`, size 512,869 bytes, dimensions 20 x 15 x 8, and oil/water/gas phases. Fresh ECLEND counts were Comments=3, Warnings=23, Problems=0, Errors=0 and Bugs=0; cleanup was confirmed, eight ECLIPSE output files and three validated Artifacts were recorded.
- Run 49 returned 571 real RSM Summary series. For example, FOPR contains 417 points covering simulation day 0 through day 2920; the Vue result page displays the selected returned series without deriving or fabricating values.
- Run 47 is retained as audit evidence of the fixed Worker/Backend RSM JSON casing mismatch: ECLIPSE completed and returned Summary data, but Backend rejected PascalCase series/point keys. The Worker now publishes contract-required lower-camel fields with regression coverage; the Backend display validator was not relaxed.
- ECLIPSE v2 DATA inspection now omits only null `DATES`/`TSTEP` sibling fields, so a valid `TSTEP` schedule record is accepted by the strict Backend inspection contract. `BRILLIG.DATA` revalidation moved from `INVALID` to `READY` after this compatibility fix.

- ECLIPSE 100 MVP implementation is deployed: Spring Boot and migration support, Worker `eclrun` execution/cleanup/parsing, and Vue model/run/result UI are present. Official-example Run 49 is the current real B/S success evidence.
- A separate C# probe project built successfully and launched the trusted local ECLIPSE 2024.1 installation. The process created current-run PRT, EGRID and INIT outputs and exited without leaving an ECLIPSE process behind.
- The probe's sample deck did not complete a valid simulation: its PRT contained four errors, 101 reported problems, repeated `CONVERGENCE ERROR = NaN`, and a terminal `RUN STOPPED` condition. Process exit code zero and output-file existence therefore must not be treated as simulation success.
- The official launcher is discoverable from the controlled local environment; `eclrun.exe --report-versions eclipse` returned `2024.1`. The Worker must use this launcher, not the probe's direct `eclipse.exe` invocation.
- The user selected the first MVP input boundary: one `.DATA` file only, with ZIP, directory packages and `INCLUDE` decks rejected. No Study, parameter override or input-deck modification is permitted.
- The user confirmed that `docs/software-integration/requirements.md` is authoritative for the ECLIPSE MVP; the external `PRO.MD` remains an unmodified historical backup.
- The Worker accepts one isolated `.DATA`, rejects lexical `INCLUDE`, invokes `eclrun -v 2024.1 eclipse`, requires a fresh zero-error `.ECLEND`, treats RSM as optional, and publishes only controlled artifacts plus metadata-only ECLIPSE output inventory. The Backend persists `RUNNING_ECLIPSE` and nullable ECLIPSE Study state; Vue hides Study, preserves version selection, and shows ECLEND, optional Summary, cleanup and separate output/Artifact inventories.
- ECLIPSE DATA inspection v1 is committed in `a390d1dc`: the Worker streams one `.DATA` without invoking ECLIPSE and returns safe sections, unit system, phases and dimensions; Backend persists validated `inspection_json`; Vue displays the read-only overview.
- ECLIPSE DATA inspection v2 is committed in `ecf8140c`: it adds source-order WELSPECS well names and lexical `DATES`/`TSTEP` schedule records to the overview. It preserves inspection v1 compatibility, has no derived dates, durations, well states or engineering values, and retains no-run/no-deck-modification boundaries. User manual testing completed without reported issues.
- The Demo-first adaptation makes PIPESIM Well, PIPESIM Network and ECLIPSE 100 direct Ribbon entry points. A Ribbon click opens the type-filtered native picker, then hands the selected file to the software workspace; failed uploads and project-loading paths release their loading state. READY inspected ECLIPSE versions compose the safe DATA overview with the ECLIPSE run/history/result path, while non-ready versions remain inspection-only. Browser diagnostics are restricted to safe generic text and allowlisted codes.
- The browser multipart client no longer overrides `Content-Type`, so the browser supplies the required multipart boundary. Current running services have not been restarted; real CSN_309 upload/validation must be repeated after deployment. The user selected code commit only and deferred service restart/live validation.
- Demo-first is the current highest-priority requirement: each simulator exposes a real upload/run/status/result path when the environment permits it; Network contract rejection remains safely non-displayable, and ECLIPSE success is shown only after an approved deck passes the ECLEND success gates.
- The PIPESIM Network demo path preserves real `Completed` Network output as `PARTIAL_SUCCEEDED / VALID_PARTIAL` only after Worker, Backend and Vue validate a safe topology and finite display-data subset. The UI labels it `部分真实计算结果`; it never claims `VALID_FULL` or invents missing data.
- Verified on 2026-09-11: Backend Maven test suite passed 74 tests; Worker Release build passed with 0 warnings/errors and Worker xUnit passed 61 tests; Vue `npm run build` passed. Vue emits existing Rollup pure-comment, Sass legacy API and bundle-size warnings.
- Run 49 provides a serialized real standalone `.DATA` success with fresh zero-error `.ECLEND`, cleanup evidence, output metadata hashes and 571 returned RSM Summary series.
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
- PIPESIM Well, PIPESIM Network and ECLIPSE 100 all reload persisted Run history on model activation and retained-workspace remount. Active Runs resume polling; terminal Runs restore the latest persisted detail. Mounted-state, navigation-generation and retained-ID checks prevent duplicate requests, post-unmount polling and cross-model stale responses.
- The workspace now reads one safe Spring capability contract for Worker, PIPESIM Well, PIPESIM Network and ECLIPSE 100 readiness. Worker busy blocks only new submissions, a simulator-specific unavailable state blocks only that simulator, and persisted history/results remain browseable in both cases. Browser-visible capability data is allowlisted and excludes local paths, license text, Worker IDs, generation IDs and active Run IDs.
- The software-integration model page now follows the parsing/fusion information hierarchy more closely: a compact light-gray top control/readiness area, white result-first canvas, yellow active tabs and bottom history/audit content. ECLIPSE uses one model header and one version selector, with calculation results and DATA inspection in the same tabbed content region.

### Build Verification

- Backend package completed successfully after loading the software-integration code.
- Worker Release `dotnet build` completed with zero warnings and zero errors.
- Vue `npm run build` completed successfully; existing Sass deprecation and bundle-size warnings remain.
- Worker xUnit completed with 175 passing tests, including Network result, ECLIPSE RSM serialization, capability and sanitized-Artifact coverage.
- `python -m unittest discover -s worker/tests/PythonNormalization -p "test_*.py"` completed with 42 tests and no failures. The Windows console emitted non-fatal GBK decoding warnings from subprocess reader threads.
- Backend Maven tests completed with 114 passing tests, including requested-Study binding, numeric/path uniqueness, version isolation, migration backfill, diagnostic redaction and safe three-simulator capability normalization.
- `pwsh -File .\worker\tests\Golden\Verify-Golden.ps1 -VerifyLocalSources` verified all six real results and metadata sidecars against the current models and Avalonia adapter.
- The final Vue build completed successfully after adding the explicit calculation entry.
- Vue `npm run build` completed successfully after unifying Run-history restoration; independent review concluded `PASS`. Existing Sass legacy API, Rollup pure-comment and bundle-size warnings remain.
- AHKs, original GRDP, Studio backend, Vue, Worker health and Worker capabilities returned HTTP 200 after deployment.

## Known Gaps And Risks

These items are not complete and must not be represented as finished:

### Demo-01 Acceptance

- Migration 007 is deployed, and Runs 27-32 demonstrate exact Golden equality after `result_json` conversion to `LONGTEXT`.
- The formal CSW_101 nodal/profile/combined then CSW_102 nodal/profile/combined serial acceptance sequence is complete.
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
- Focused mocked-browser automation covers software-project/model activation, exact PIPESIM/ECLIPSE Run payloads, cancellation, terminal polling, stale-response isolation and retained ECLIPSE Run-history restoration. Real-backend browser acceptance remains separate.

### Deferred Production Features

- Artifact download API and 30-day expiration cleanup are not implemented.
- Full production retry policy is not implemented; interrupted simulator Runs are not automatically retried.
- Durable validation queueing remains separate from the persistent Run queue and is not implemented.

### UI And Regression

- The software-integration workspace has been visually flattened to match the parsing/fusion module: the persistent execution-environment strip, workflow guide, READY summary card, top-level run provenance strip and full-width run alerts were removed. Capability checks remain a background create-Run gate; compact local status text is used at the run controls/result pane, while safe run provenance, errors, events, cleanup and Artifact metadata remain available in the collapsed execution details.
- Playwright Chromium regression is configured under `vue/tests/e2e`. Seven focused tests passed with all software-integration APIs mocked: stale PIPESIM history cannot cross into ECLIPSE after rapid navigation; PIPESIM/ECLIPSE create payloads and cancellation remain exact; retained ECLIPSE active Runs resume a genuine follow-up poll, reach the persisted terminal state and stop polling; per-simulator unavailability and Worker busy state gate only new Runs while preserving history; unsafe capability fields never enter the DOM; ECLIPSE keeps one model header/version control with result and DATA-inspection tabs; and the Network schematic preserves returned node/edge counts, equipment legend and result navigation.
- The UI-slimming regression additionally asserts that `/capabilities` is still requested, capability-disabled reasons remain compact and safe, and the removed readiness/workflow/provenance/full-width alert elements do not return. `npm run build` and `npm run test:e2e` completed successfully (7/7); final review returned `PASS`.
- Shared Shell changes have built successfully but do not yet have an automated parsing/fusion regression test.
- Real-backend browser acceptance, mobile behavior and longer-running environment/error transitions still need browser-level verification.
- The final reviewed build is deployed. Runs 9 and 10 returned retryable `LICENSE_UNAVAILABLE` during earlier post-hardening attempts and completed process-tree cleanup; the user subsequently confirmed the license environment is available and explicitly requested no further live verification in this handoff. These runs remain environment observations, not code failures.
- Fresh CSN_302 Run 37 passed the final deployed full-result and redaction contracts. Run 36 remains failed audit evidence of the fixed URI-shaped redaction mismatch.

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

Baseline runs retain `parameters=null`. The 2026-09-16 approved scenario extension permits the strict reservoir-pressure object for basic-gas nodal/profile/combined and black-oil nodal, applied only to the task copy. For basic-gas PT phases the value is also passed as the PT inlet pressure following Avalonia semantics. Source models remain unchanged.

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

## Next Milestone

Keep the reviewed simulator implementation and frozen Golden unchanged while completing the next demo-hardening package:

The next bounded functional package is real serial CSW_102 profile/combined scenario acceptance, followed by one individually verified additional well parameter. The Network branch condition overview, compact chart sliders, source-pressure preview, combined well view and ECLIPSE history comparison are implemented above. Only reservoir pressure is editable; Network parameter editing, ECLIPSE deck editing, other well fields and sensitivity execution still require individually verified contracts. The older deployed-smoke checklist below remains an outstanding evidence gap.

```text
perform a deployed real-backend browser smoke pass for project/model activation, Runs 27-32, Run 37 and ECLIPSE Run 49 history restoration and real result display
-> visually inspect the flattened software-integration workspace at 1440x900 and 1280x720 against the parsing/fusion module using deployed real data
-> add parsing/fusion shared-Shell regression protection without changing existing business behavior
-> separately plan the remaining real cancellation/timeout acceptance
```

The six-run CSW_101/CSW_102 comparison, CSN_302 Run 37 and official ECLIPSE Run 49 are recorded calculation evidence. Reuse these for history and display checks; changed execution code requires relevant new calculation evidence before being claimed verified. Real cancellation/timeout remains outstanding and must continue to respect global PIPESIM serialization and the recorded stop conditions. This next milestone does not authorize restarting active services or resuming previously stopped real acceptance.

## Progress Maintenance

After each verified milestone, update this file with:

- behavior actually completed;
- exact commands and acceptance models executed;
- failures classified as code, environment, license, or unverified;
- new known gaps;
- one next milestone.

Do not append conversational history. Replace stale status so this document remains the concise source of current truth.
