"""Create a portable SHA-256 inventory of PIPESIM Case Studies models."""

from __future__ import annotations

import argparse
import csv
import hashlib
import os
import sys
from pathlib import Path
from typing import TextIO


FIELDS = (
    "modelRelativePath",
    "catalogGroup",
    "sizeBytes",
    "sha256",
    "compatibilityStatus",
    "study",
    "well",
    "task",
    "runId",
    "evidence",
)


def _sha256_and_size(path: Path) -> tuple[str, int]:
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as model_file:
        for chunk in iter(lambda: model_file.read(1024 * 1024), b""):
            digest.update(chunk)
            size += len(chunk)
    return digest.hexdigest(), size


def discover_models(pipesim_home: str | Path) -> list[dict[str, str | int]]:
    """List .pips files beneath Case Studies without exposing machine paths."""
    home = Path(pipesim_home).expanduser()
    case_studies = home / "Case Studies"
    if not case_studies.is_dir():
        raise ValueError("PIPESIM home must contain a 'Case Studies' directory")

    rows: list[dict[str, str | int]] = []
    for model_path in case_studies.rglob("*"):
        if model_path.is_symlink() or not model_path.is_file() or model_path.suffix.lower() != ".pips":
            continue
        relative = model_path.relative_to(home).as_posix()
        category = model_path.relative_to(case_studies).parts[0]
        if category == "Well Models":
            group = "WELL_MODEL"
        elif category == "Network Models":
            group = "NETWORK_MODEL"
        else:
            group = "OTHER_CASE_STUDY"
        digest, size = _sha256_and_size(model_path)
        rows.append({
            "modelRelativePath": relative,
            "catalogGroup": group,
            "sizeBytes": size,
            "sha256": digest,
            "compatibilityStatus": "NOT_TESTED",
            "study": "",
            "well": "",
            "task": "",
            "runId": "",
            "evidence": "",
        })

    return sorted(rows, key=lambda row: str(row["modelRelativePath"]).casefold())


def write_inventory(rows: list[dict[str, str | int]], output: TextIO) -> None:
    writer = csv.DictWriter(output, fieldnames=FIELDS, lineterminator="\n", extrasaction="raise")
    writer.writeheader()
    writer.writerows(rows)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--pipesim-home",
        default=os.environ.get("PIPESIM_HOME"),
        help="PIPESIM installation root (defaults to PIPESIM_HOME)",
    )
    parser.add_argument("--output", type=Path, help="write UTF-8 CSV to a new file instead of stdout")
    parser.add_argument("--overwrite", action="store_true", help="allow replacing the requested output file")
    args = parser.parse_args(argv)
    if not args.pipesim_home:
        parser.error("provide --pipesim-home or set PIPESIM_HOME")
    try:
        rows = discover_models(args.pipesim_home)
        if args.output:
            if args.output.exists() and not args.overwrite:
                raise ValueError("output file already exists; choose a new path or pass --overwrite")
            mode = "w" if args.overwrite else "x"
            with args.output.open(mode, encoding="utf-8-sig", newline="") as output:
                write_inventory(rows, output)
        else:
            write_inventory(rows, sys.stdout)
    except (OSError, ValueError) as error:
        print(f"inventory failed: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
