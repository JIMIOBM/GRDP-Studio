"""Activate a copied PIPESIM model through IAM and retain every REST response."""
import argparse
import hashlib
import json
import shutil
import time
from pathlib import Path

from iam_probe import Client, ProbeError


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--url", default="http://localhost:9001")
    args = parser.parse_args()
    source = args.model.resolve(strict=True)
    if source.suffix.lower() != ".pips":
        parser.error("Expected a .pips file")
    client = Client(args.url, args.output)
    copied = args.output.resolve() / source.name
    source_hash = sha256(source)
    shutil.copy2(source, copied)
    report = {"source": str(source), "sourceSha256": source_hash,
              "activationPassed": False, "coupledSolveVerified": False}
    wid = None
    try:
        workspace = client.api("POST", "workspaces", {"name": "GRDP-PIPESIM-activation"})
        wid = workspace["id"]
        report["workspaceId"] = wid
        client.wait_idle(wid)
        model = client.api("POST", "models/workspaces/" + wid,
                           {"name": "PIP", "type": "StingrayModel", "file": {"filePath": str(copied)}})
        mid = model["id"]
        report["modelId"] = mid
        client.api("POST", "models/" + mid + "/workspaces/" + wid,
                   {"configuration": [{"name": "Processors", "integerValue": 1}]})
        client.api("POST", "models/activate/" + mid + "/workspaces/" + wid)
        deadline = time.monotonic() + 90
        while True:
            models = client.api("GET", "models/workspaces/" + wid)
            current = next(model for model in models if model["id"] == mid)
            if current.get("activationFailed"):
                raise ProbeError("IAM PIPESIM activation failed")
            if current.get("isActive"):
                report["activationPassed"] = True
                break
            if time.monotonic() >= deadline:
                raise ProbeError("IAM PIPESIM activation timed out")
            time.sleep(2)
    except (ProbeError, KeyError, StopIteration) as error:
        report["error"] = str(error)
    finally:
        if wid:
            snapshot = client.snapshot(wid)
            report["diagnostics"] = snapshot["diagnostics"]
            report["readErrors"] = snapshot["readErrors"]
            if snapshot["diagnostics"] or snapshot["readErrors"]:
                report["activationPassed"] = False
            try:
                client.api("DELETE", "workspaces/" + wid)
                report["workspaceDeleted"] = True
            except ProbeError as error:
                report["workspaceDeleted"] = False
                report["cleanupError"] = str(error)
        report["sourceUnchanged"] = sha256(source) == source_hash
        client.save("activation-result.json", report)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if (report["activationPassed"] and report["sourceUnchanged"]
                 and report.get("workspaceDeleted")) else 1


if __name__ == "__main__":
    raise SystemExit(main())
