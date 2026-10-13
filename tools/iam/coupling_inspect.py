"""CC02-B isolated official IAM document discovery, not a coupled solver."""
import argparse
import json
from pathlib import Path
import shutil
import uuid

from coupling_preflight import inventory, is_link, preflight, source_directory
from iam_probe import Client, ProbeError, diagnostics, validate_base_url


SCHEMA = "iam-coupling-inspection/1"
REQUIRED_OPERATIONS = (
    ("/iam/api/v1/workspaces", "get", "Workspaces_GetWorkspaces"),
    ("/iam/api/v1/workspaces", "post", "Workspaces_CreateWorkspace"),
    ("/iam/api/v1/workspaces/{workspaceId}", "delete", "Workspaces_DeleteWorkspace"),
    ("/iam/api/v1/models/workspaces/{workspaceId}", "get", "Models_GetModels"),
    ("/iam/api/v1/couplings/workspaces/{workspaceId}", "get", "Couplings_GetCouplings"),
    ("/iam/api/v1/variables/workspaces/{workspaceId}", "get", "Variables_GetVariables"),
    ("/iam/api/v1/units/workspaces/{workspaceId}", "get", "Units_GetUnits"),
    ("/iam/api/v1/time/workspaces/{workspaceId}", "get", "Time_GetTimeController"),
    ("/iam/api/v1/messages/workspaces/{workspaceId}", "get", "Messages_Messages"),
)


class DiscoveryClient(Client):
    def request(self, method, path, body=None):
        result = super().request(method, path, body)
        # IAM 2022.1 actually returns 204 (not []) for an empty workspace host.
        # Only this recorded wire status permits the special empty-list case;
        # malformed HTTP 200/null responses remain protocol failures.
        if result is None and method == "GET" and path == "/iam/api/v1/workspaces":
            record = json.loads((self.output / (f"{self.sequence:04d}-http.json")).read_text(encoding="utf-8"))
            if record.get("status") == 204 and record.get("responseText") == "":
                return []
        return result


def outside_output(output, source):
    destination = Path(output).absolute()
    for component in (destination, *destination.parents):
        if component.exists() and is_link(component):
            raise ValueError("Evidence path must not contain symbolic links or junctions")
    destination = destination.resolve()
    if destination == source or source in destination.parents:
        raise ValueError("Evidence must be outside the source package")
    return destination


def isolated_copy(source, destination, manifest):
    destination.mkdir(exist_ok=False)
    for record in manifest:
        relative = record["relativePath"]
        copied = destination / relative
        copied.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source / relative, copied)
    if inventory(source) != manifest or inventory(destination) != manifest:
        raise ValueError("Source or isolated package changed during copy; native open prohibited")


def require_swagger(swagger):
    paths = swagger.get("paths", {}) if isinstance(swagger, dict) else {}
    for path, method, operation in REQUIRED_OPERATIONS:
        if paths.get(path, {}).get(method, {}).get("operationId") != operation:
            raise ProbeError("Live Swagger operation not confirmed: " + operation)


def object_list(value, label):
    if not isinstance(value, list) or any(not isinstance(item, dict) for item in value):
        raise ProbeError("Unexpected " + label + " response; expected an object array")
    return value


def mapping_review(couplings, variables):
    """Identify raw mapping conflicts. Matching labels do NOT certify physical semantics."""
    index, findings, rows = {}, [], []

    def finding(code, **details):
        findings.append({"code": code, **details})

    for variable in object_list(variables, "variables"):
        name = variable.get("name")
        if not isinstance(name, str) or not name:
            finding("VARIABLE_NAME_NOT_OBSERVED")
        elif name in index:
            index[name] = None  # Ambiguous identity must not pick the first or last value.
            finding("VARIABLE_NAME_DUPLICATE", variable=name)
        else:
            index[name] = variable
    for wrapper in object_list(couplings, "couplings"):
        coupling = wrapper.get("coupling", wrapper)
        if not isinstance(coupling, dict):
            raise ProbeError("Unexpected coupling structure")
        for mapping in object_list(coupling.get("variableMappings") or [], "variableMappings"):
            row = {"coupling": coupling.get("name"), "mapping": mapping}
            source, target = index.get(mapping.get("fromVariable")), index.get(mapping.get("toVariable"))
            row.update(source=source, target=target)
            rows.append(row)
            if source is None or target is None:
                finding("MAPPING_VARIABLE_NOT_OBSERVED", mapping=mapping)
                continue
            if target.get("readOnly") is not False:
                finding("MAPPING_TARGET_READ_ONLY" if target.get("readOnly") is True
                        else "MAPPING_TARGET_WRITABILITY_NOT_OBSERVED", mapping=mapping)
            if not source.get("type") or not target.get("type"):
                finding("MAPPING_TYPE_NOT_OBSERVED", mapping=mapping)
                continue
            if source["type"] != target["type"]:
                finding("MAPPING_TYPE_MISMATCH", mapping=mapping)
            if source["type"] in ("Real", "RealVector", "Simulation"):
                for field in ("measurement", "unit"):
                    # Different published versions use unit/units; preserve raw records.
                    left = source.get(field, source.get("units") if field == "unit" else None)
                    right = target.get(field, target.get("units") if field == "unit" else None)
                    if not left or not right:
                        finding("MAPPING_" + field.upper() + "_NOT_OBSERVED", mapping=mapping)
                    elif left != right:
                        finding("MAPPING_" + field.upper() + "_MISMATCH", mapping=mapping,
                                source=left, target=right)
    if not rows:
        finding("EXPLICIT_MAPPINGS_NOT_OBSERVED")
    return {"explicitMappingCount": len(rows), "rows": rows, "findings": findings,
            "physicalContract": "NOT_VERIFIED",
            "remainingSemantics": ["absoluteOrGaugePressure", "stockTankOrReservoirRate",
                                   "compositionAndPvt", "depthDatum", "wellControls",
                                   "iprAndWellboreOwnership", "timeAlignment", "toleranceAndDirection"]}


def owned_workspace(client, name):
    """Recover only a unique, confirmed ID after an uncertain create; never retry POST."""
    matches = [item for item in object_list(client.api("GET", "workspaces"), "workspaces")
               if item.get("name") == name]
    if len(matches) != 1:
        raise ProbeError("Uncertain create: owned workspace ID not uniquely confirmed")
    return str(uuid.UUID(matches[0]["id"]))


def close_owned_workspace(client, workspace_id):
    deleted = client.api("DELETE", "workspaces/" + workspace_id)
    remaining = object_list(client.api("GET", "workspaces"), "workspaces")
    if deleted is not True or any(str(item.get("id", "")).lower() == workspace_id for item in remaining):
        raise ProbeError("Workspace deletion/absence was not confirmed")


def inspect_package(package, entry, output, *, url="http://localhost:9001", timeout=30,
                    client_factory=DiscoveryClient):
    validate_base_url(url)
    if not 1 <= timeout <= 60:
        raise ValueError("Timeout must be 1 to 60 seconds")
    root = source_directory(package)
    destination = outside_output(output, root)
    offline = preflight(root, entry)
    manifest = offline["package"]["files"]
    client = client_factory(url, destination, timeout=timeout)
    client.save("preflight.json", offline)
    copied = destination / "input"
    isolated_copy(root, copied, manifest)
    name = "GRDP-CC02-inspect-" + uuid.uuid4().hex
    report = {"schemaVersion": SCHEMA, "kind": "OFFICIAL_NATIVE_METADATA_DISCOVERY",
              "workspaceName": name, "package": offline["package"], "parts": {},
              "metadataRead": False, "canCalculate": False, "coupledSolveVerified": False,
              "sourceUnchanged": False, "workspaceDeleted": False, "outcomeUncertain": False,
              "readErrors": [], "cleanupErrors": [], "diagnostics": [],
              "gates": dict(offline["gates"]),
              "operations": {"explicitActivation": 0, "variableWrites": 0, "solverSteps": 0}}
    report["nativeOpenMayActivateModels"] = True
    workspace_id, create_issued = None, False
    try:
        swagger = client.request("GET", "/swagger/docs/v1")
        client.save("swagger.json", swagger)
        require_swagger(swagger)
        # This diagnostic owns an isolated host, not the user's existing workspaces.
        if object_list(client.api("GET", "workspaces"), "workspaces"):
            raise ProbeError("IAM host is not empty; use an isolated host and leave other sessions untouched")
        create_issued = True
        opened = client.api("POST", "workspaces", {"name": name,
                           "file": {"filePath": str(copied / offline["package"]["entry"])}})
        workspace_id = str(uuid.UUID(opened["id"]))
        report["workspaceId"] = workspace_id
        client.wait_idle(workspace_id, timeout=timeout)
        for endpoint in ("models", "couplings", "variables", "units", "time", "messages"):
            path = endpoint + "/workspaces/" + workspace_id
            if endpoint == "variables":
                path += "?flatten=true"
            try:
                value = client.api("GET", path)
                if endpoint in ("models", "couplings", "variables", "units"):
                    object_list(value, endpoint)
                elif not isinstance(value, dict):
                    raise ProbeError("Unexpected " + endpoint + " response; expected an object")
                report["parts"][endpoint] = value
            except (ProbeError, ValueError) as error:
                report["readErrors"].append(str(error))
        parts = report["parts"]
        report["diagnostics"] = diagnostics(parts.get("models", []), parts.get("time", {}),
                                               parts.get("messages", {}))
        models = parts.get("models", [])
        report["modelDiscoveryRead"] = bool(models) and all(
            isinstance(model.get("type"), str) and model["type"] for model in models)
        report["modelSummary"] = [{key: model.get(key) for key in
                                   ("id", "name", "type", "isActive", "requiresActivation", "activationFailed")}
                                  for model in models]
        report["metadataRead"] = not report["readErrors"] and bool(models) and all(
            isinstance(model.get("type"), str) and model["type"] for model in models)
        if report["metadataRead"]:
            # Observed metadata, not a PASSED native-solve or physical-contract gate.
            report["gates"]["nativeDocumentMetadata"] = "OBSERVED_REST"
        if "couplings" in parts and "variables" in parts:
            report["mappingReview"] = mapping_review(parts["couplings"], parts["variables"])
        report["referenceAndEmbeddingStatus"] = "NOT_VERIFIED"
    except (ProbeError, ValueError, KeyError, TypeError) as error:
        report["readErrors"].append(str(error))
        if create_issued and workspace_id is None:
            report["outcomeUncertain"] = True
            try:
                workspace_id = owned_workspace(client, name)
                report["workspaceId"] = workspace_id
            except (ProbeError, ValueError, KeyError) as cleanup_error:
                report["cleanupErrors"].append(str(cleanup_error))
    finally:
        if workspace_id:
            try:
                close_owned_workspace(client, workspace_id)
                report["workspaceDeleted"] = True
            except (ProbeError, ValueError) as error:
                report["cleanupErrors"].append(str(error))
        try:
            report["sourceUnchanged"] = inventory(root) == manifest
            report["isolatedFilesAfterRead"] = inventory(copied)
        except (OSError, ValueError) as error:
            report["readErrors"].append(str(error))
        report["metadataRead"] = report["metadataRead"] and not report["readErrors"]
        report["status"] = "METADATA_COLLECTED" if (
            report["metadataRead"] and report["sourceUnchanged"] and report["workspaceDeleted"]
            and not report["outcomeUncertain"]
        ) else "BLOCKED"
        client.save("inspection.json", report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--package", required=True)
    parser.add_argument("--entry", required=True)
    parser.add_argument("--output", required=True, help="New evidence directory outside the source")
    parser.add_argument("--url", default="http://localhost:9001")
    parser.add_argument("--timeout", type=int, default=30)
    args = parser.parse_args()
    try:
        report = inspect_package(args.package, args.entry, args.output, url=args.url, timeout=args.timeout)
    except (OSError, ValueError) as error:
        parser.exit(2, str(error) + "\n")
    print(json.dumps({key: report[key] for key in ("status", "metadataRead", "canCalculate",
                     "coupledSolveVerified", "sourceUnchanged", "workspaceDeleted", "readErrors",
                     "cleanupErrors")}, ensure_ascii=False, indent=2))
    # 3 means discovery collected, NOT coupled calculation readiness.
    return 3 if report["status"] == "METADATA_COLLECTED" else 2


if __name__ == "__main__":
    raise SystemExit(main())
