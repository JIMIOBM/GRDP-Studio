# Coupling preflight (CC02-A)

This portable, read-only tool inventories an official IAM candidate directory. It is not a native IAM parser, an API probe, or a coupled solver. It needs Python 3.9+ and only the standard library.

```powershell
python tools/iam/coupling_preflight.py --package "<PACKAGE_DIR>" --entry "Reservoir to Network.iam" --iam-home "<IAM_HOME>" --pipesim-home "<PIPESIM_HOME>" --eclipse-home "<ECLIPSE_HOME>" --mpi-root "<MPI_ROOT>" --output "<EVIDENCE_DIR>/preflight.json"
python -m unittest discover -s tools/iam -p test_coupling_preflight.py -v
```

Use real configuration paths, not literal placeholders. The evidence directory must exist and be outside the package; the report must be new. Installation arguments are optional for inventory-only use. Missing or unconfigured directories are observations, never evidence of a working installation.

Exit codes: 3 means inventory collected but native readiness NOT verified; 2 means invalid input, read failure or report write failure. There is no successful native-solve exit code. Consumers must inspect the explicit gates and must not interpret report existence as success.

The manifest hash covers UTF-8 JSON of sorted file records with sorted keys, compact separators and unescaped Unicode. Absolute paths and environment observations are excluded from that hash. It is a snapshot, not a concurrency lock or proof of engineering response.

The tool rejects source links/junctions, portable-path traversal, case-insensitive collisions, excessive sizes/counts and detectable file changes during reads. `.iam` is opaque: no BinaryFormatter deserialization, guessed mappings or automatic `.bpn` conversion. It does not verify INCLUDE closure, embedded native references, versions, licenses, MPI services or credentials.

See [CC02-A acceptance](../../docs/software-integration/acceptance/CC02-A.md) for the official-document constraints and the next native/API gate. Other locally present probe files are separate experiments, not dependencies of this tool or automatically part of its GitHub delivery.
