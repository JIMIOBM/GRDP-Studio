"""Run one isolated IAM ECLIPSE sample step and record diagnostics; never claim coupling."""

import argparse
import hashlib
import json
import time
from pathlib import Path

from iam_probe import Client, ProbeError


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--url", default="http://localhost:9001")
    args = parser.parse_args()
    source = args.model.resolve(strict=True)
    original_hash = hashlib.sha256(source.read_bytes()).hexdigest()
    client = Client(args.url, args.output)
    result = {"model": str(source), "modelSha256": original_hash,
              "stepPassed": False, "coupledSolveVerified": False}
    wid = None
    try:
        wid = client.api("POST", "workspaces", {"name": "GRDP-ECL-MPI-diagnostic"})["id"]
        result["workspaceId"] = wid
        client.wait_idle(wid)
        mid = client.api("POST", "models/workspaces/" + wid,
                         {"name": "ECL", "type": "EclipseModel",
                          "file": {"filePath": str(source)}})["id"]
        result["modelId"] = mid
        client.api("POST", "models/activate/" + mid + "/workspaces/" + wid)
        deadline = time.monotonic() + 90
        while True:
            models = client.api("GET", "models/workspaces/" + wid)
            current = next(model for model in models if model["id"] == mid)
            if current.get("activationFailed"):
                raise ProbeError("ECLIPSE activation failed")
            if current.get("isActive"):
                result["activationPassed"] = True
                break
            if time.monotonic() >= deadline:
                raise ProbeError("ECLIPSE activation timed out")
            time.sleep(2)
        client.api("POST", "variables/workspaces/" + wid,
                   [{"name": "Time Controller.Simulation.Simulation End Date",
                     "stringValue": "2004-01-31T16:00:00.000Z"}])
        client.api("POST", "time/step/workspaces/" + wid)
        result["stepIssued"] = True
        client.wait_idle(wid, timeout=120, solver_started=True)
        snapshot = client.snapshot(wid)
        result["diagnostics"] = snapshot["diagnostics"]
        result["readErrors"] = snapshot["readErrors"]
        result["time"] = snapshot["parts"].get("time")
        result["stepPassed"] = not snapshot["diagnostics"] and not snapshot["readErrors"]
    except (ProbeError, KeyError, StopIteration) as error:
        result["error"] = str(error)
    finally:
        if wid:
            try:
                snapshot = client.snapshot(wid)
                result["diagnostics"] = snapshot["diagnostics"]
                result["readErrors"] = snapshot["readErrors"]
                result["time"] = snapshot["parts"].get("time")
                if result["diagnostics"] or result["readErrors"]:
                    result["stepPassed"] = False
            except ProbeError as error:
                result["snapshotError"] = str(error)
                result["stepPassed"] = False
            try:
                client.api("DELETE", "workspaces/" + wid)
                result["workspaceDeleted"] = True
            except ProbeError as error:
                result["workspaceDeleted"] = False
                result["cleanupError"] = str(error)
        result["sourceUnchanged"] = hashlib.sha256(source.read_bytes()).hexdigest() == original_hash
        client.save("step-result.json", result)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["stepPassed"] and result["sourceUnchanged"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
