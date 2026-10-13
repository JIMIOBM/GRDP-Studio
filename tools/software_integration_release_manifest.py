"""Create a portable provenance manifest for a software-integration release."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable


SCHEMA_VERSION = "grdp-software-integration-release-manifest/1"
SOURCE_COMPONENTS = {
    "springSource": ("backend/src/main/java/com/grdp/studio/softwareintegration",),
    "workerSource": ("worker/Execution", "worker/Storage", "worker/ptk_run.py", "worker/ptk_validate.py", "worker/ptk_network.py"),
    "vueSource": ("vue/src/views/SoftwareIntegration", "vue/src/stores/softwareIntegration.js"),
}


def _git(root: Path, *arguments: str) -> bytes:
    result = subprocess.run(
        ["git", "-C", str(root), *arguments],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    return result.stdout


def _inside_repo(root: Path, relative_path: str) -> Path:
    relative = Path(relative_path)
    if relative.is_absolute() or any(part in ("", ".", "..") for part in relative.parts):
        raise ValueError("component paths must be clean repository-relative paths")
    resolved_root = root.resolve()
    candidate = root
    for part in relative.parts:
        candidate = candidate / part
        if candidate.is_symlink():
            raise ValueError("symlink source/build paths are not accepted")
    resolved = candidate.resolve()
    if not resolved.is_relative_to(resolved_root):
        raise ValueError("component path resolves outside the repository")
    return resolved


def _hash_files(root: Path, paths: Iterable[Path]) -> dict[str, object]:
    digest = hashlib.sha256()
    file_count = 0
    total_bytes = 0
    ordered = sorted(paths, key=lambda path: path.relative_to(root).as_posix().casefold())
    for path in ordered:
        if path.is_symlink() or not path.is_file():
            continue
        relative = path.relative_to(root).as_posix().encode("utf-8")
        size = path.stat().st_size
        digest.update(len(relative).to_bytes(4, "big"))
        digest.update(relative)
        digest.update(size.to_bytes(8, "big"))
        with path.open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
        file_count += 1
        total_bytes += size
    return {"sha256": digest.hexdigest(), "fileCount": file_count, "sizeBytes": total_bytes}


def fingerprint_path(root: Path, relative_path: str) -> dict[str, object]:
    path = _inside_repo(root, relative_path)
    if not path.exists():
        return {"status": "NOT_PRESENT", "path": Path(relative_path).as_posix()}
    if path.is_symlink():
        raise ValueError("symlink build/source paths are not accepted")
    candidates = [path] if path.is_file() else path.rglob("*")
    for candidate in candidates:
        if candidate.is_symlink():
            raise ValueError("symlinks inside a fingerprinted directory are not accepted")
    fingerprint = _hash_files(root.resolve(), candidates)
    return {"status": "FINGERPRINTED", "path": Path(relative_path).as_posix(), **fingerprint}


def _worktree_fingerprint(root: Path) -> dict[str, object]:
    diff = _git(root, "diff", "--binary", "HEAD")
    tracked_names = _git(root, "diff", "--name-only", "-z", "HEAD").split(b"\0")
    untracked_names = _git(root, "ls-files", "--others", "--exclude-standard", "-z").split(b"\0")
    changed_names = {name for name in tracked_names + untracked_names if name}
    digest = hashlib.sha256()
    digest.update(diff)
    for raw_name in sorted(untracked_names):
        if not raw_name:
            continue
        relative = os.fsdecode(raw_name)
        candidate = root / relative
        digest.update(raw_name)
        if candidate.is_symlink():
            digest.update(os.fsencode(os.readlink(candidate)))
        elif candidate.is_file():
            with candidate.open("rb") as source:
                for chunk in iter(lambda: source.read(1024 * 1024), b""):
                    digest.update(chunk)
    return {
        "commit": _git(root, "rev-parse", "HEAD").decode("ascii").strip(),
        "dirty": bool(changed_names),
        "changedFileCount": len(changed_names),
        "diffSha256": digest.hexdigest(),
    }


def create_manifest(
    repo_root: str | Path,
    build_outputs: Iterable[tuple[str, str]] = (),
    pipesim_ptk_version: str | None = None,
    eclipse_version: str | None = None,
    worker_generation: str | None = None,
) -> dict[str, object]:
    root = Path(repo_root).resolve()
    if not (root / ".git").exists():
        raise ValueError("repository root must contain a .git directory")

    source_fingerprints = {
        label: [fingerprint_path(root, path) for path in paths]
        for label, paths in SOURCE_COMPONENTS.items()
    }
    build_fingerprints: dict[str, dict[str, object]] = {}
    for label, relative_path in build_outputs:
        if not label or label in build_fingerprints:
            raise ValueError("build component labels must be non-empty and unique")
        build_fingerprints[label] = fingerprint_path(root, relative_path)

    return {
        "schemaVersion": SCHEMA_VERSION,
        "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
        "repository": _worktree_fingerprint(root),
        "sourceFingerprints": source_fingerprints,
        "buildFingerprints": build_fingerprints,
        "simulatorEnvironment": {
            "pipesimPtkVersion": pipesim_ptk_version,
            "eclipseVersion": eclipse_version,
            "workerGeneration": worker_generation,
        },
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--output", type=Path, required=True, help="new JSON file; existing files are not overwritten by default")
    parser.add_argument("--overwrite", action="store_true")
    parser.add_argument("--build-output", action="append", default=[], metavar="NAME=RELATIVE_PATH",
                        help="fingerprint a built component directory/file under the repository; repeat per component")
    parser.add_argument("--pipesim-ptk-version")
    parser.add_argument("--eclipse-version")
    parser.add_argument("--worker-generation")
    args = parser.parse_args(argv)
    try:
        outputs = []
        for item in args.build_output:
            label, separator, relative_path = item.partition("=")
            if not separator or not label.strip() or not relative_path.strip():
                raise ValueError("--build-output must use NAME=RELATIVE_PATH")
            outputs.append((label.strip(), relative_path.strip()))
        manifest = create_manifest(
            args.repo_root,
            build_outputs=outputs,
            pipesim_ptk_version=args.pipesim_ptk_version,
            eclipse_version=args.eclipse_version,
            worker_generation=args.worker_generation,
        )
        output = args.output.expanduser()
        output.parent.mkdir(parents=True, exist_ok=True)
        mode = "w" if args.overwrite else "x"
        with output.open(mode, encoding="utf-8", newline="") as destination:
            json.dump(manifest, destination, ensure_ascii=False, indent=2)
            destination.write("\n")
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print("release manifest failed: {0}".format(type(error).__name__), file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
