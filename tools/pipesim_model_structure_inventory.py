"""Read PIPESIM Study, component, and stored-task metadata without running a solver."""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from typing import Callable

try:
    from .pipesim_model_inventory import _sha256_and_size, discover_models
except ImportError:
    from pipesim_model_inventory import _sha256_and_size, discover_models


SCHEMA_VERSION = "pipesim-model-structure-inventory/1"
COMPONENTS = (
    "Well",
    "Source",
    "Sink",
    "Flowline",
    "Junction",
    "Choke",
    "Completion",
    "Tubing",
    "ESP",
    "Compressor",
    "Pump",
    "TwoPhaseSeparator",
    "ThreePhaseSeparator",
)
TASK_CLASS_NAMES = (
    "NETWORKSIMULATION",
    "NETWORKOPTIMIZERSIMULATION",
    "NODALANALYSISOPERATION",
    "PTPROFILESIMULATION",
    "GLDIAGNOSTICSOPERATION",
    "VFPTABLESOPERATION",
    "WELLPERFORMANCECURVESOPERATION",
    "SYSTEMANALYSISOPERATION",
)


def _names(entries: object) -> list[str]:
    names = set()
    for entry in entries:
        name = getattr(entry, "name", None)
        if isinstance(name, str) and name.strip() and len(name) <= 255:
            names.add(name.strip())
    return sorted(names, key=str.casefold)


def inspect_open_model(model: object, model_classes: object) -> dict[str, object]:
    """Read only already-stored catalog/components; do not create tasks or save."""
    catalog = model._catalog
    try:
        toolkit_version = model.about.toolkit_version
        if not isinstance(toolkit_version, str) or len(toolkit_version) > 64:
            toolkit_version = None
    except Exception:
        toolkit_version = None
    studies = _names(catalog.lookup_entries_by_class_ids([model_classes.STUDY]))
    wells = [name for name in model.find(component="Well")
             if isinstance(name, str) and name.strip() and len(name) <= 255]
    components = {}
    for component in COMPONENTS:
        try:
            contexts = model.find(component=component)
        except Exception:
            contexts = []
        components[component] = len(contexts)

    stored_tasks = {}
    for class_name in TASK_CLASS_NAMES:
        class_id = getattr(model_classes, class_name, None)
        if class_id is None:
            continue
        try:
            task_names = _names(catalog.lookup_entries_by_class_ids([class_id]))
        except Exception:
            task_names = []
        if task_names:
            stored_tasks[class_name] = task_names

    return {
        "toolkitVersion": toolkit_version,
        "studies": studies,
        "wellContexts": sorted(set(wells), key=str.casefold),
        "componentCounts": components,
        "storedTaskOperations": stored_tasks,
        # Presence is inventory evidence only; it does not prove task validity or solver success.
        "compatibilityStatus": "NOT_TESTED",
    }


def inspect_model(
    model_path: Path,
    pipesim_home: Path,
    model_factory: Callable[[str], object],
    model_classes: object,
) -> dict[str, object]:
    digest, size = _sha256_and_size(model_path)
    row: dict[str, object] = {
        "modelRelativePath": model_path.relative_to(pipesim_home).as_posix(),
        "sizeBytes": size,
        "sha256": digest,
        "inspectionStatus": "INSPECTION_FAILED",
        "toolkitVersion": None,
        "studies": [],
        "wellContexts": [],
        "componentCounts": {},
        "storedTaskOperations": {},
        "diagnosticCode": "PTK_INSPECTION_FAILED",
    }
    model = None
    inspection_completed = False
    try:
        model = model_factory(str(model_path))
        row.update(inspect_open_model(model, model_classes))
        inspection_completed = True
    except Exception as error:
        # Do not serialize exception text: PTK errors can contain local paths or account details.
        row["diagnosticCode"] = "PTK_" + type(error).__name__.upper()
    finally:
        if model is not None:
            try:
                model.close()
            except Exception:
                row["inspectionStatus"] = "CLOSE_FAILED"
                row["diagnosticCode"] = "PTK_CLOSE_FAILED"
    if model is not None and row["inspectionStatus"] != "CLOSE_FAILED":
        try:
            after_digest, after_size = _sha256_and_size(model_path)
            if (digest, size) != (after_digest, after_size):
                row["inspectionStatus"] = "SOURCE_CHANGED"
                row["diagnosticCode"] = "SOURCE_CHANGED_DURING_INSPECTION"
            elif inspection_completed:
                row["inspectionStatus"] = "INSPECTED"
                row["diagnosticCode"] = ""
        except Exception as error:
            row["inspectionStatus"] = "INSPECTION_FAILED"
            row["diagnosticCode"] = "PTK_" + type(error).__name__.upper()
    return row


def collect_inventory(
    pipesim_home: str | Path,
    model_factory: Callable[[str], object],
    model_classes: object,
    selected_relative_path: str | None = None,
    limit: int | None = None,
) -> dict[str, object]:
    home = Path(pipesim_home).expanduser().resolve()
    rows = discover_models(home)
    if selected_relative_path:
        normalized = Path(selected_relative_path).as_posix().casefold()
        rows = [row for row in rows if str(row["modelRelativePath"]).casefold() == normalized]
        if not rows:
            raise ValueError("requested model relative path was not found in the Case Studies inventory")
    if limit is not None:
        if limit < 1:
            raise ValueError("limit must be a positive integer")
        rows = rows[:limit]

    inspected = []
    for base_row in rows:
        model_path = home / Path(str(base_row["modelRelativePath"]))
        row = inspect_model(model_path, home, model_factory, model_classes)
        row["catalogGroup"] = base_row["catalogGroup"]
        inspected.append(row)
    return {"schemaVersion": SCHEMA_VERSION, "models": inspected}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pipesim-home", default=os.environ.get("PIPESIM_HOME"))
    parser.add_argument("--ptk-path", default=os.environ.get("PIPESIM_PTK_PATH"),
                        help="Python Toolkit Modules path (defaults to PIPESIM_PTK_PATH)")
    parser.add_argument("--model-relative-path", help="inspect one path relative to PIPESIM_HOME")
    parser.add_argument("--limit", type=int, help="inspect only the first N models")
    parser.add_argument("--output", type=Path, help="write JSON to a new file instead of stdout")
    parser.add_argument("--overwrite", action="store_true", help="allow replacing the requested output file")
    args = parser.parse_args(argv)
    if not args.pipesim_home:
        parser.error("provide --pipesim-home or set PIPESIM_HOME")
    if not args.ptk_path:
        parser.error("provide --ptk-path or set PIPESIM_PTK_PATH")
    try:
        sys.path.insert(0, str(args.ptk_path))
        from sixgill.core.resources import ModelClasses
        from sixgill.pipesim import Model

        payload = collect_inventory(
            args.pipesim_home,
            lambda path: Model.open(path),
            ModelClasses,
            selected_relative_path=args.model_relative_path,
            limit=args.limit,
        )
        serialized = json.dumps(payload, ensure_ascii=False, indent=2) + "\n"
        if args.output:
            mode = "w" if args.overwrite else "x"
            with args.output.open(mode, encoding="utf-8", newline="") as output:
                output.write(serialized)
        else:
            sys.stdout.write(serialized)
    except (OSError, ValueError, ImportError, ModuleNotFoundError) as error:
        print("structure inventory failed: {0}".format(type(error).__name__), file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
