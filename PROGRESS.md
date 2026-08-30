# GRDP-Studio Software Integration Progress

Last verified: 2026-08-30

## Repository State

- Branch: `violet/feature/software-integration-ui`
- Stage 0 baseline protection was committed in this handoff.
- Other Stage 1/2 software-integration work remains uncommitted.
- Existing unrelated worktree changes must not be reverted.
- Authoritative requirements: `docs/software-integration/requirements.md`
- Multi-Agent workflow: `.opencode/README.md`

## Current Milestone

Stage 0 baseline protection is complete. The implementation has also reached a partial Stage 2 model-management flow:

```text
create software project
-> upload one .pips model version
-> persist file and SHA-256
-> asynchronously call local Worker
-> open model with PIPESIM PTK
-> validate supported single-well structure
-> read Study names
-> persist READY / INVALID / ENVIRONMENT_ERROR
-> display project/model tree and validation state
```

Stage 1 persistent-run foundations and Stage 3 simulator execution have not been implemented.

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
- Validation is serialized with an in-process semaphore to avoid concurrent PTK license checkout.
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
- `python -m unittest discover -s worker/tests/PythonNormalization -p "test_*.py"` completed with 20 tests and no failures or skips.
- `pwsh -File .\worker\tests\Golden\Verify-Golden.ps1 -VerifyLocalSources` verified all six real results and metadata sidecars against the current models and Avalonia adapter.
- After capture, AHKs, original GRDP, Studio backend, Vue, Worker health, and Worker capabilities all returned HTTP 200.

## Known Gaps And Risks

These items are not complete and must not be represented as finished:

### Stage 0 Operational Boundary

- Golden Capture uses a machine-wide mutex and fail-closed process scans, but the current Worker does not participate in the same cross-process lock. Capture therefore requires the Worker and all other PTK consumers to be stopped first.
- A non-participating external PTK consumer could still start after the final process scan. Durable global serialization remains a Stage 1 architecture requirement.

### Model Management

- ZIP upload is stored but ZIP extraction and validation are not implemented. Current validation rejects/non-readies ZIP versions.
- ZIP traversal, symlink/reparse-point, file-count, depth, and expanded-size protections are not implemented.
- `storage_key` currently stores an absolute path; requirements call for a relative storage key.
- Model kind and well name returned by Worker are not persisted as model-version metadata.
- Async validation is process-local and not represented by a durable queue. A backend restart can leave work requiring recovery.
- Validation uses an in-memory semaphore only; cross-process or future multi-Worker serialization is not implemented.
- Schema initialization and migration SQL both create the same tables; long-term migration ownership is unresolved.
- Project recovery from the 30-day recycle bin and physical cleanup are not implemented.
- Model deletion and old-version selection UI are not implemented.
- The frontend loads each project detail separately and does not yet use the required isolated Pinia store.

### Persistent Tasks And Execution

- No `software_integration_run`, run-event, artifact, worker, or worker-capability persistence exists.
- No durable queue, claim protocol, global task lock, restart recovery, or dry-run adapter exists.
- No node-analysis, PT-profile, or combined-run Worker endpoint exists.
- No Study selection control for creating runs exists.
- No cancel, timeout, retry, or `WORKER_LOST` behavior exists.
- No result curve/profile page, run history, event timeline, artifact manifest, or artifact download exists.
- No 30-day artifact retention/expiration cleanup exists.

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

Before implementation, invoke `@architect` to design the persistent execution contract and divide it into non-overlapping work packages.

Required architecture output:

1. Run, event, artifact, worker, and capability schema with migration ownership.
2. Browser API and Worker API request/response DTOs.
3. Full state transition table and allowed transition enforcement.
4. Durable global single-task queue and claim mechanism.
5. Cancel, timeout, process termination, restart recovery, and partial-result semantics.
6. Artifact layout, relative storage keys, manifest, checksum, and 30-day expiration.
7. Worker process model for opening a model and executing nodal/profile/combined without parameter overrides.
8. Frontend Study selection, task submission, real phase display, result views, and history.
9. Work-package file ownership and dependency order.
10. CSW_101/CSW_102 golden acceptance and parsing/fusion regression matrix.

Recommended implementation order after architecture approval:

```text
freeze contracts and state machine
-> implement durable dry-run task queue
-> independently review queue/recovery behavior
-> implement Worker run adapter
-> add backend result/artifact publication
-> add frontend Study/task/result flow
-> run CSW_101 and CSW_102 acceptance serially
-> reviewer PASS
```

## Handoff Rule

At the end of every bounded milestone, update this file with:

- behavior actually completed;
- exact commands and acceptance models executed;
- failures classified as code, environment, license, or unverified;
- new known gaps;
- one next bounded goal.

Do not append conversational history. Replace stale status so this document remains the concise source of current truth.
