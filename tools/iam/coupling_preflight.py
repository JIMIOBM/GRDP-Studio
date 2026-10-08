"""Read-only CC02 package/environment inventory; never asserts native coupling success."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import stat


SCHEMA = "iam-coupling-preflight/1"
MAX_FILES = 10000
MAX_BYTES = 2 * 1024**3


def is_link(path):
    info = path.lstat()
    return path.is_symlink() or bool(
        getattr(info, "st_file_attributes", 0)
        & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0)
    )


def source_directory(value):
    path = Path(os.path.abspath(value))
    for component in (path, *path.parents):
        if component.exists() and is_link(component):
            raise ValueError("Package path must not contain symbolic links or junctions")
    if not path.is_dir():
        raise ValueError("Package directory does not exist")
    return path


def relative_entry(value):
    # Use portable relative paths, including when the tool is run outside Windows.
    normalized = value.replace("\\", "/")
    if not normalized or normalized.startswith("/") or ":" in normalized:
        raise ValueError("Entry must be a relative .iam file inside the package")
    parts = normalized.split("/")
    if any(part in ("", ".", "..") for part in parts) or Path(normalized).suffix.lower() != ".iam":
        raise ValueError("Entry must be a relative .iam file inside the package")
    return normalized


def inventory(root, max_files=MAX_FILES, max_bytes=MAX_BYTES):
    records, names, total = [], set(), 0

    def walk_error(error):
        raise error

    for directory, folders, files in os.walk(root, followlinks=False, onerror=walk_error):
        for name in sorted(folders + files):
            path = Path(directory) / name
            if is_link(path):
                raise ValueError("Package contains a symbolic link or junction: " + str(path.relative_to(root)))
            relative = path.relative_to(root).as_posix()
            if relative.casefold() in names:
                raise ValueError("Case-insensitive package path collision: " + relative)
            names.add(relative.casefold())
        for name in sorted(files):
            path = Path(directory) / name
            before = path.stat()
            if not stat.S_ISREG(before.st_mode):
                raise ValueError("Package contains a non-regular file")
            total += before.st_size
            if len(records) >= max_files or total > max_bytes:
                raise ValueError("Package exceeds the inventory file/byte limit")
            digest = hashlib.sha256()
            bytes_read = 0
            with path.open("rb") as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                    bytes_read += len(chunk)
                    if bytes_read > before.st_size:
                        raise ValueError("Package changed during inventory: " + path.relative_to(root).as_posix())
                    digest.update(chunk)
            after = path.stat()
            if (before.st_size, before.st_mtime_ns, before.st_ino) != (
                after.st_size, after.st_mtime_ns, after.st_ino
            ) or bytes_read != before.st_size or is_link(path):
                raise ValueError("Package changed during inventory: " + path.relative_to(root).as_posix())
            records.append({"relativePath": path.relative_to(root).as_posix(),
                            "sizeBytes": after.st_size, "sha256": digest.hexdigest()})
    return sorted(records, key=lambda record: record["relativePath"])


def installation_observations(iam_home, pipesim_home, eclipse_home, mpi_root):
    observations = {"configuredPathsOnly": True, "versionsVerified": False}
    roots = {"iam": iam_home, "pipesim": pipesim_home, "eclipse": eclipse_home, "mpi": mpi_root}
    for name, value in roots.items():
        observations[name] = {"configured": value is not None,
                              "directoryExists": value is not None and Path(value).is_dir()}
    adapters = Path(iam_home) / "Extensions" / "Adapters" if iam_home else None
    observations["iam"]["adapterDirectoryNames"] = sorted(
        path.name for path in adapters.iterdir() if path.is_dir()
    ) if adapters and adapters.is_dir() else []
    observations["iam"]["restExecutablePresent"] = bool(
        iam_home and (Path(iam_home) / "Schlumberger.Avocet.WebAPI.Server.exe").is_file()
    )
    observations["mpi"]["legacyExecutablePresent"] = bool(
        mpi_root and (Path(mpi_root) / "intel64" / "bin" / "mpiexec.exe").is_file()
    )
    observations["mpi"]["flatExecutablePresent"] = bool(
        mpi_root and (Path(mpi_root) / "bin" / "mpiexec.exe").is_file()
    )
    observations["mpi"]["serviceAndCredentialsVerified"] = False
    return observations


def preflight(package, entry, *, iam_home=None, pipesim_home=None, eclipse_home=None, mpi_root=None):
    root = source_directory(package)
    entry = relative_entry(entry)
    files = inventory(root)
    if entry not in {record["relativePath"] for record in files}:
        raise ValueError("The exact entry path was not found in the package manifest")
    candidates = {"legacyNetworks": [], "modernModels": [], "reservoirDecks": []}
    for record in files:
        suffix = Path(record["relativePath"]).suffix.lower()
        category = {".bpn": "legacyNetworks", ".pips": "modernModels", ".data": "reservoirDecks"}.get(suffix)
        if category:
            candidates[category].append(record["relativePath"])
    observations = installation_observations(iam_home, pipesim_home, eclipse_home, mpi_root)
    findings = []

    def finding(code, detail):
        findings.append({"code": code, "detail": detail})

    if candidates["legacyNetworks"]:
        finding("LEGACY_BPN_REQUIRES_NATIVE_REVIEW", "Legacy .bpn candidates found; discover native model references and conversion requirements. Do not rename to .pips.")
    if mpi_root and not observations["mpi"]["legacyExecutablePresent"]:
        finding("MPI_LEGACY_LAYOUT_NOT_OBSERVED", "intel64/bin/mpiexec.exe is absent. File presence alone does not verify MPI service, credentials or IAM compatibility.")
    for name in ("iam", "pipesim", "eclipse", "mpi"):
        if not observations[name]["directoryExists"]:
            finding("INSTALLATION_NOT_OBSERVED", name + " directory is absent or was not configured.")
    if iam_home and not observations["iam"]["restExecutablePresent"]:
        finding("IAM_REST_EXECUTABLE_NOT_OBSERVED", "Expected IAM REST executable was not observed; inspect the configured installation.")

    # The native document may be a binary serialized object. Never deserialize it
    # or infer authoritative model references/mappings from reporting XML or names.
    gates = ["nativeDocumentMetadata", "dependencyClosure", "connectorCompatibility", "license",
             "variableUnitsAndControls", "nativeBaseline", "restBaseline", "twoSidedParameterResponse"]
    manifest_bytes = json.dumps(files, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return {"schemaVersion": SCHEMA, "status": "BLOCKED" if findings else "NOT_VERIFIED",
            "canCalculate": False, "coupledSolveVerified": False,
            "package": {"entry": entry, "fileCount": len(files),
                        "totalBytes": sum(record["sizeBytes"] for record in files),
                        "manifestSha256": hashlib.sha256(manifest_bytes).hexdigest(), "files": files},
            "document": {"format": "OPAQUE_NATIVE", "deserialized": False},
            "fileCandidates": candidates, "environment": observations, "findings": findings,
            "gates": {gate: "NOT_VERIFIED" for gate in gates},
            "nextStep": "Use an isolated copy in the official IAM reader to discover references, embedded files, model types and mappings; verify the supported version/license/MPI combination before any joint solve."}


def write_report(report, output, package):
    root = source_directory(package)
    destination = Path(output).resolve()
    if destination == root or root in destination.parents:
        raise ValueError("Report must be outside the source package")
    # Exclusive creation prevents overwriting earlier evidence or another file.
    with destination.open("x", encoding="utf-8") as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2)
        stream.write("\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--package", required=True)
    parser.add_argument("--entry", required=True, help="Relative .iam document path")
    parser.add_argument("--iam-home")
    parser.add_argument("--pipesim-home")
    parser.add_argument("--eclipse-home")
    parser.add_argument("--mpi-root")
    parser.add_argument("--output", required=True, help="New JSON file outside the source package; parent must exist")
    args = parser.parse_args()
    try:
        report = preflight(args.package, args.entry, iam_home=args.iam_home, pipesim_home=args.pipesim_home,
                           eclipse_home=args.eclipse_home, mpi_root=args.mpi_root)
        write_report(report, args.output, args.package)
    except (OSError, ValueError) as error:
        parser.exit(2, str(error) + "\n")
    print(json.dumps({"status": report["status"], "fileCount": report["package"]["fileCount"],
                      "coupledSolveVerified": False, "report": str(Path(args.output).resolve())}, ensure_ascii=False))
    # A report is successfully collected, but the native readiness gate is not passed.
    return 3


if __name__ == "__main__":
    raise SystemExit(main())
