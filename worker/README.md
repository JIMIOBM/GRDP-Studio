# GRDP Software Integration Worker

The Worker runs under the signed-in Windows user, listens only on loopback, and is called only by Spring Boot. It does not write the business database.

## Configuration

`Worker:StorageRoot` defaults to `C:\GRDP-Data`. `Worker:PipesimHome`, `Worker:PipesimPtkPath`, `Worker:PythonPath`, and timeout limits are trusted host configuration, never browser input. Environment overrides use the `GRDP_WORKER_` prefix and the normal .NET section separator, for example `GRDP_WORKER_Worker__StorageRoot`.

PIPESIM validation and execution share both an in-process reservation and the machine-wide `Global\GRDP-Pipesim-Golden-Capture` mutex. The mutex is acquired and released on one dedicated thread.

The Worker assigns the Python host to a Windows Job Object before releasing its start gate. The Job uses `KILL_ON_JOB_CLOSE` without breakaway, so cancellation, timeout, and Worker shutdown retain ownership of the complete PTK process tree. Terminal publication and global-lock release require confirmed Job cleanup.

## API

- `GET /api/health`
- `GET /api/capabilities`
- `POST /api/models/validate`
- `POST /api/runs/execute`
- `GET /api/runs/{runId}?afterSequence=N`
- `POST /api/runs/{runId}/cancel`

Validation accepts only a relative storage key and expected source hash:

```json
{
  "modelStorageKey": "models/12/34/model.pips",
  "expectedSha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
}
```

Execution requires every field, including an explicitly null `parameters` value:

```json
{
  "runId": 101,
  "modelStorageKey": "models/12/34/model.pips",
  "expectedModelSha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "study": "Study 1",
  "runTask": "combined",
  "parameters": null,
  "timeoutSeconds": 600
}
```

Accepted runs return HTTP 202:

```json
{
  "runId": 101,
  "state": "CLAIMED",
  "workerId": "grdp-pipesim-worker",
  "generationId": "00000000000000000000000000000000",
  "acceptedAtUtc": "2026-08-31T00:00:00+00:00"
}
```

Run status is process-generation observation, not business truth. `afterSequence` returns only events with a greater sequence while `lastSequence` remains the latest sequence in the complete snapshot:

```json
{
  "runId": 101,
  "state": "SUCCEEDED",
  "lastSequence": 5,
  "workerId": "grdp-pipesim-worker",
  "generationId": "00000000000000000000000000000000",
  "acceptedAtUtc": "2026-08-31T00:00:00+00:00",
  "startedAtUtc": "2026-08-31T00:00:00+00:00",
  "completedAtUtc": "2026-08-31T00:00:01+00:00",
  "elapsedMillis": 1000,
  "events": [
    {
      "sequence": 5,
      "state": "SUCCEEDED",
      "occurredAtUtc": "2026-08-31T00:00:01+00:00",
      "message": "PIPESIM result completed successfully."
    }
  ],
  "result": {
    "schemaVersion": "pipesim-well-result/1",
    "model_kind": "black_oil_liquid",
    "runTask": "combined",
    "resultContract": "VALID_FULL",
    "units": {
      "flow": { "displayUnit": null, "semantics": "unspecified" },
      "pressure": { "displayUnit": null, "semantics": "unspecified" },
      "depth": { "displayUnit": null, "semantics": "unspecified" },
      "temperature": { "displayUnit": null, "semantics": "unspecified" }
    },
    "ipr": [{ "flow": 1.0, "pressure": 2.0 }],
    "vlp": [{ "flow": 1.0, "pressure": 2.0 }],
    "profile": [{ "depth": 1.0, "pressure": 2.0, "temperature": 3.0 }]
  },
  "error": null,
  "artifacts": [
    {
      "storageKey": "jobs/101/output/normalized-result.json",
      "size": 123,
      "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
      "contentType": "application/json"
    }
  ],
  "cleanup": {
    "processTreeExitConfirmed": true,
    "inputDeleted": true,
    "workDirectoryDeleted": true,
    "killUsed": false,
    "message": "Process-tree exit was confirmed before terminal publication."
  }
}
```

Error objects are always `{ "category", "code", "message", "retryable" }`. Categories distinguish request, coordination, storage, environment/PTK, license, model, execution, protocol, cancellation, timeout, and cleanup failures. Active cancellation returns 202, an already cancelled run returns 200, another terminal state returns 409, and an unknown run returns 404. Python is assigned to a Windows Job Object configured with `KILL_ON_JOB_CLOSE` and no breakaway flags before the start gate is released; Job creation, configuration, or assignment failure terminates Python without starting PTK. After the single start-gate read, the Python adapter has no cooperative stdin cancellation reader. Cancellation and timeout send only a best-effort grace signal, wait `GracefulStopSeconds` (2 seconds by default), and then terminate the Job and confirm that its active-process count reached zero.

The adapter opens only the task copy under `jobs/<runId>/input`, never calls `model.set_value`, selects only an existing Study, and emits structured JSONL phase events. A combined profile failure preserves valid nodal arrays as `VALID_PARTIAL` and ends in `PARTIAL_SUCCEEDED`. Artifacts are limited to controlled JSON, log, and manifest files under `jobs/<runId>/output`; the copied model is removed after confirmed process-tree exit and is never an artifact.
