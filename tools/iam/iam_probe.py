"""Local IAM REST diagnostic client. A successful HTTP response is not a solve result."""
import argparse
import json
import socket
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import ProxyHandler, Request, build_opener


class ProbeError(RuntimeError):
    pass


def validate_base_url(value):
    parsed = urlsplit(value)
    # IAM 2022.1 uses a localhost HTTP listener and provides no authentication.
    if (parsed.scheme != "http" or parsed.hostname != "localhost"
            or parsed.username or parsed.password or parsed.path not in ("", "/")
            or parsed.query or parsed.fragment):
        raise ValueError("Use http://localhost:<port>; remote IAM endpoints are unsupported")
    if not parsed.port:
        raise ValueError("An explicit port is required")
    return value.rstrip("/")


def diagnostics(models, controller, messages):
    errors = []
    for model in models:
        if model.get("activationFailed"):
            errors.append("MODEL_ACTIVATION_FAILED: " + model.get("name", "unknown"))
        elif model.get("requiresActivation") and not model.get("isActive"):
            errors.append("MODEL_NOT_ACTIVE: " + model.get("name", "unknown"))
    status = controller.get("status", "")
    if any(word in status.lower() for word in ("fail", "error", "abort")):
        errors.append("SIMULATION_FAILED: " + status)
    for key in ("statusMessages", "solverMessages", "StatusMessages", "SolverMessages"):
        for message in messages.get(key, []):
            if str(message.get("severity", "")).lower() in ("error", "critical", "fatal"):
                errors.append(str(message.get("text", "Unknown IAM error")))
    return list(dict.fromkeys(errors))


class Client:
    def __init__(self, base_url, output, timeout=30):
        self.base_url = validate_base_url(base_url)
        self.output = Path(output)
        # Do not overwrite a previous run's evidence.
        self.output.mkdir(parents=True, exist_ok=False)
        self.timeout = timeout
        self.opener = build_opener(ProxyHandler({}))
        self.sequence = 0

    def save(self, name, value):
        (self.output / name).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")

    def request(self, method, path, body=None):
        self.sequence += 1
        record = {"method": method, "path": path, "request": body,
                  "utc": datetime.now(timezone.utc).isoformat()}
        data = None if body is None else json.dumps(body).encode("utf-8")
        req = Request(self.base_url + path, data=data, method=method,
                      headers={"Content-Type": "application/json", "Accept": "application/json"})
        try:
            with self.opener.open(req, timeout=self.timeout) as response:
                raw = response.read().decode("utf-8-sig")
                record.update(status=response.status, responseText=raw)
            return json.loads(raw) if raw else None
        except HTTPError as error:
            record.update(status=error.code, responseText=error.read().decode("utf-8", errors="replace"))
            raise ProbeError(f"{method} {path}: HTTP {error.code}") from error
        except (URLError, socket.timeout, TimeoutError, OSError) as error:
            record["transportError"] = str(error)
            # A timed-out POST may have already changed IAM. Never retry it automatically.
            record["outcomeUncertain"] = method != "GET"
            raise ProbeError(f"{method} {path}: transport failure; no automatic retry") from error
        finally:
            self.save(f"{self.sequence:04d}-http.json", record)

    def api(self, method, path, body=None):
        return self.request(method, "/iam/api/v1/" + path, body)

    def wait_idle(self, workspace_id, timeout=60, solver_started=False):
        deadline = time.monotonic() + timeout
        while True:
            # This build supports DELETE, but not GET, on /workspaces/{id}.
            workspaces = self.api("GET", "workspaces") or []
            workspace = next((item for item in workspaces if item.get("id") == workspace_id), None)
            if workspace is None:
                raise ProbeError("Workspace disappeared before becoming Idle")
            if workspace.get("status") == "Idle":
                return {"workspace": workspace, "simulation": None}
            if workspace.get("status") != "Busy":
                raise ProbeError("Unexpected workspace state: " + str(workspace.get("status")))
            # IAM can leave a workspace Busy after a solver has already failed. The time
            # endpoint is the authoritative terminal signal; callers must still inspect
            # its status and diagnostics and must not treat isFinished as success.
            if solver_started:
                simulation = self.api("GET", "time/workspaces/" + workspace_id) or {}
                if simulation.get("isFinished"):
                    return {"workspace": workspace, "simulation": simulation}
            if time.monotonic() >= deadline:
                raise ProbeError("Workspace remained Busy; simulation completion is unconfirmed")
            time.sleep(1)

    def snapshot(self, workspace_id):
        # uuid validation also prevents path injection into local endpoints.
        workspace_id = str(uuid.UUID(workspace_id))
        result = {"workspaceId": workspace_id, "parts": {}, "readErrors": []}
        for endpoint in ("models", "couplings", "time", "messages"):
            try:
                result["parts"][endpoint] = self.api("GET", endpoint + "/workspaces/" + workspace_id)
            except ProbeError as error:
                result["readErrors"].append(str(error))
        parts = result["parts"]
        result["diagnostics"] = diagnostics(parts.get("models") or [], parts.get("time") or {},
                                              parts.get("messages") or {})
        # Snapshots cannot prove convergence, provenance, unit consistency, or conservation.
        result["coupledSolveVerified"] = False
        self.save("snapshot.json", result)
        return result

    def smoke(self):
        workspace_id = None
        result = {"kind": "iam-rest-smoke/1", "passed": False, "coupledSolveVerified": False}
        try:
            self.request("GET", "/swagger/docs/v1")
            created = self.api("POST", "workspaces", {"name": "GRDP-smoke-" + uuid.uuid4().hex})
            workspace_id = str(uuid.UUID(created["id"]))
            result["workspaceId"] = workspace_id
            self.wait_idle(workspace_id)
            snapshot = self.snapshot(workspace_id)
            if snapshot["diagnostics"] or snapshot["readErrors"]:
                raise ProbeError("IAM diagnostics contain errors; inspect snapshot.json")
            result["passed"] = True
        except (ProbeError, ValueError, KeyError) as error:
            result["error"] = str(error)
        finally:
            if workspace_id:
                try:
                    self.api("DELETE", "workspaces/" + workspace_id)
                    result["workspaceDeleted"] = True
                except ProbeError as error:
                    result.update(passed=False, workspaceDeleted=False, cleanupError=str(error))
            self.save("smoke-result.json", result)
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://localhost:9001")
    parser.add_argument("--output", required=True, help="New evidence directory; must not already exist")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("smoke", help="Create/read/delete an empty diagnostic workspace; no simulator solve")
    sub.add_parser("snapshot", help="Read a known workspace without changing it").add_argument("workspace")
    args = parser.parse_args()
    client = Client(args.url, args.output)
    result = client.smoke() if args.command == "smoke" else client.snapshot(args.workspace)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if (result.get("passed") if args.command == "smoke"
                 else not result["readErrors"] and not result["diagnostics"]) else 1


if __name__ == "__main__":
    raise SystemExit(main())
