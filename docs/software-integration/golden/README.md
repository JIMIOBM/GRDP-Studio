# PIPESIM well-result golden baseline

`pipesim-well-result-v1/` is generated only from real CSW_101 and CSW_102 runs through the read-only Avalonia `ptk_worker.py` adapter. Each result has a metadata sidecar. No placeholder simulator values are permitted.

Capture is serialized by `worker/tests/Golden/Capture-AvaloniaPipesimGolden.ps1`. The script holds a Windows `Global\GRDP-Pipesim-Golden-Capture` mutex for the complete batch and fails closed if it detects a running GRDP Worker, Avalonia host, PIPESIM process, or Python PTK adapter. Worker, Avalonia, PIPESIM, and other PTK consumers must therefore be stopped before capture. After every individual run, the capture adapter process tree is terminated and a fresh health probe must check out and release the PTK license before the next run starts.

If process enumeration fails, or a potential Python, .NET, PTK, PIPESIM, or Avalonia host has unreadable `CommandLine` or `ExecutablePath` metadata, capture stops with a coordination error. The GRDP Worker cannot be changed to participate in the Global mutex within WP-01 file ownership. Repeated fail-closed scans are therefore performed immediately before each child launch, but they cannot eliminate the remaining interval in which a non-participating external process could start after a scan.

Verification is performed by `worker/tests/Golden/Verify-Golden.ps1` and the Python normalization regression suite. The verifier rejects extra files, non-UTC or out-of-order metadata, invalid combined results, and inconsistent adapter or source hashes. Capture also enables local-source verification against the current models and Avalonia revision before publication. The unittest suite reports real-Golden checks as `skipped` when the canonical directory is absent so normalization and Schema tests can still run without a license. A skipped real-Golden test is **not** Golden acceptance: `Verify-Golden.ps1` is the mandatory non-zero gate and reports `BLOCKED_MISSING_REAL_GOLDEN` until all real files exist.

The canonical layout is one result source per case and run type:

```text
pipesim-well-result-v1/
├── CSW_101/
│   ├── nodal.json
│   ├── nodal.metadata.json
│   ├── pt-profile.json
│   ├── pt-profile.metadata.json
│   ├── combined.json
│   └── combined.metadata.json
└── CSW_102/
    └── (the same six files)
```

The capture script stages the complete twelve-file batch beside the destination on the same volume, verifies it, and then publishes it with directory renames. If capture, validation, or publication fails, staging is cleaned and an existing complete destination is restored rather than mixed with files from the failed batch. Publication occurs only after all six real runs succeed and each source model has the same SHA-256 before and after its three runs. If PTK, the license, or either model is unavailable, the directory remains absent and verification reports `BLOCKED_MISSING_REAL_GOLDEN`; an empty or synthetic result is never substituted.
