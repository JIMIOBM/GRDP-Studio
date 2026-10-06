"""L42 preparation: create and reopen one official template, never claim it is runnable."""
import argparse
import json
from pathlib import Path
import re
import sys
import tempfile
import zipfile


def diagnostic(issue):
    if isinstance(issue, str):
        return {"path": "", "property": "", "message": issue}
    return {"path": str(getattr(issue, "path", "") or ""),
            "property": str(getattr(issue, "property_name", "") or ""),
            "message": str(getattr(issue, "message", "") or type(issue).__name__)}


def create_template(model_api, output, well_name):
    output = Path(output)
    if not output.is_absolute() or output.suffix.lower() != ".pips":
        raise ValueError("Use an absolute .pips output path")
    if output.exists() or output.is_symlink():
        raise ValueError("Output already exists; overwriting is forbidden")
    if not output.parent.is_dir():
        raise ValueError("Output parent must already exist")
    if not isinstance(well_name, str) or not re.fullmatch(r"[A-Za-z][A-Za-z0-9_-]{0,63}", well_name):
        raise ValueError("Well name must be a safe identifier of 1 to 64 characters")
    model = model_api.new(str(output), overwrite=False)
    try:
        # Exact installed official create_well_from_template.py example API.
        model.copy("Simple vertical", well_name, True)
        if list(model.find(component="Well")) != [well_name]:
            raise ValueError("Template copy did not create exactly the requested well")
        model.save(str(output))
    finally:
        model.close()
    model = model_api.open(str(output))
    try:
        wells = list(model.find(component="Well"))
        if wells != [well_name]:
            raise ValueError("Saved template well did not survive reopening")
        return {
            "schemaVersion": "grdp-pipesim-template-preflight/1",
            "template": "Simple vertical",
            "origin": "official-template-derived",
            "well": well_name,
            "reopened": True,
            "unitsSystem": "PIPESIM_FIELD",
            "completionContexts": list(model.find(component="Completion", Well=well_name)),
            "tubingContexts": list(model.find(component="Tubing", Well=well_name)),
            "modelDiagnostics": [diagnostic(issue) for issue in model.validate()],
            "calculationVerified": False,
            "nextGate": "Explicit fluid/boundary conditions, native task validation and platform run are required",
        }
    finally:
        model.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ptk", required=True, help="Installed PythonToolkitModules.zip")
    parser.add_argument("--output", required=True, help="New absolute .pips file; existing files are rejected")
    parser.add_argument("--well", default="ConsoleWell")
    args = parser.parse_args()
    toolkit = Path(args.ptk)
    if not toolkit.is_file():
        parser.error("Installed toolkit is missing")
    # Model.new opens a physical bundled .pips template through __file__;
    # zipimport works for Model.open but cannot expose this resource as a file.
    with tempfile.TemporaryDirectory(prefix="grdp-ptk-template-") as runtime:
        root = Path(runtime).resolve()
        with zipfile.ZipFile(toolkit) as archive:
            for entry in archive.infolist():
                candidate = (root / entry.filename.replace("\\", "/")).resolve()
                if not candidate.is_relative_to(root) or (entry.external_attr >> 16) & 0o170000 == 0o120000:
                    raise ValueError("Toolkit archive contains an unsafe entry")
            archive.extractall(root)
        sys.path.insert(0, str(root))
        from sixgill.pipesim import Model
        print(json.dumps(create_template(Model, args.output, args.well), ensure_ascii=False))


if __name__ == "__main__":
    main()
