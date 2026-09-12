import math
import re
from collections.abc import Mapping
from numbers import Integral, Real


NETWORK_COMPONENT_TYPES = (
    "Well",
    "Source",
    "Sink",
    "Flowline",
    "Junction",
    "Choke",
    "Compressor",
    "Pump",
    "HeatExchanger",
    "TwoPhaseSeparator",
    "ThreePhaseSeparator",
    "SinglephaseSeparator",
    "GenericEquipment",
    "MultiphaseBooster",
    "GenericBooster",
    "OneSubseaBooster",
    "WetGasCompressor",
    "Expander",
    "CheckValve",
    "MultiplierAdder",
    "Injector",
)

_PIPESIM_UNAVAILABLE_SENTINELS = (1.2345e25, -1.0e31)
_LOCAL_PATH = re.compile(r"(?im)(?<![a-z0-9])[a-z]:[\\/].*$")
_LOCAL_PIPE = re.compile(r"(?i)net\.pipe://localhost/pipe/[^\s'\"]+")


def inspect_network(model):
    components = {
        component_type: list(model.find(component=component_type))
        for component_type in ("Well", "Source", "Sink", "Flowline")
    }
    connections = list(model.connections())
    # Single-well models can contain an internal Source, so only surface-network
    # terminal or flowline components select the Network validation branch.
    is_candidate = bool(components["Sink"] or components["Flowline"])
    missing = []
    if not components["Source"] and not components["Well"]:
        missing.append("Source/Well")
    if not components["Sink"]:
        missing.append("Sink")
    if not components["Flowline"]:
        missing.append("Flowline")
    if not connections:
        missing.append("Connection")
    return {
        "candidate": is_candidate,
        "valid": not missing,
        "missing": missing,
        "components": components,
        "connections": connections,
    }


def format_validation_issues(issues, limit=3):
    messages = []
    for issue in list(issues)[:limit]:
        path = sanitize_message(getattr(issue, "path", "") or "").strip()
        property_name = sanitize_message(getattr(issue, "property_name", "") or "").strip()
        message = sanitize_message(getattr(issue, "message", "") or type(issue).__name__).strip()
        location = ".".join(value for value in (path, property_name) if value)
        messages.append("{0}: {1}".format(location, message) if location else message)
    return "; ".join(messages)


def extract_topology(model, inspection=None):
    inspection = inspection or inspect_network(model)
    component_by_name = {}
    for component_type in NETWORK_COMPONENT_TYPES:
        for name in model.find(component=component_type):
            component_by_name.setdefault(str(name), component_type)

    nodes = []
    node_names = set()

    def add_node(name):
        name = str(name or "").strip()
        if name and name not in node_names:
            node_names.add(name)
            nodes.append({
                "id": name,
                "componentType": component_by_name.get(name, "Unknown"),
            })

    edges = []
    for connection in inspection["connections"]:
        source = str(connection.get("Source", "") or "").strip()
        destination = str(connection.get("Destination", "") or "").strip()
        if not source or not destination:
            continue
        add_node(source)
        add_node(destination)
        edges.append({
            "source": source,
            "destination": destination,
            "sourcePort": str(connection.get("Source Port", "") or ""),
        })

    for name in component_by_name:
        add_node(name)

    components = inspection["components"]
    return {
        "nodes": nodes,
        "edges": edges,
        "counts": {
            "nodes": len(nodes),
            "edges": len(edges),
            "sources": len(components["Source"]) + len(components["Well"]),
            "sinks": len(components["Sink"]),
            "flowlines": len(components["Flowline"]),
        },
    }


def network_profile_variables():
    from sixgill.definitions import ProfileVariables

    return [
        ProfileVariables.TOTAL_DISTANCE,
        ProfileVariables.PRESSURE,
        ProfileVariables.TEMPERATURE,
        ProfileVariables.MEAN_VELOCITY_FLUID,
        ProfileVariables.DENSITY_FLUID_INSITU,
        ProfileVariables.Z_FACTOR_GAS_INSITU,
    ]


def _clean_json_value(value, path, quality, missing_is_unavailable=True):
    if value is None:
        if missing_is_unavailable:
            quality.append({"path": path, "code": "UNAVAILABLE"})
        return None
    if isinstance(value, (str, bool)):
        return value
    if isinstance(value, Integral):
        return int(value)
    if isinstance(value, Real):
        number = float(value)
        if not math.isfinite(number):
            quality.append({"path": path, "code": "NON_FINITE"})
            return None
        if any(math.isclose(number, sentinel, rel_tol=1e-12) for sentinel in _PIPESIM_UNAVAILABLE_SENTINELS):
            quality.append({"path": path, "code": "UNAVAILABLE"})
            return None
        return number
    if isinstance(value, Mapping):
        return {
            str(key): _clean_json_value(
                item,
                "{0}.{1}".format(path, key),
                quality,
                missing_is_unavailable,
            )
            for key, item in value.items()
        }
    if hasattr(value, "tolist"):
        return _clean_json_value(value.tolist(), path, quality, missing_is_unavailable)
    if isinstance(value, (list, tuple)):
        return [
            _clean_json_value(
                item,
                "{0}[{1}]".format(path, index),
                quality,
                missing_is_unavailable,
            )
            for index, item in enumerate(value)
        ]
    try:
        return [
            _clean_json_value(
                item,
                "{0}[{1}]".format(path, index),
                quality,
                missing_is_unavailable,
            )
            for index, item in enumerate(value)
        ]
    except TypeError:
        return str(value)


def _normalize_scalar_results(values_by_variable, section, quality):
    normalized = []
    for variable, raw_values in values_by_variable.items():
        if not isinstance(raw_values, Mapping):
            continue
        unit = str(raw_values.get("Unit", "") or "").strip()
        values = []
        for name, value in raw_values.items():
            if str(name) == "Unit":
                continue
            path = "{0}.{1}.{2}".format(section, variable, name)
            values.append({
                "name": str(name),
                "value": _clean_json_value(value, path, quality),
            })
        normalized.append({
            "variable": str(variable),
            "unit": unit,
            "values": values,
        })
    return normalized


def _as_values(value):
    if value is None:
        return []
    if isinstance(value, (str, bytes, Mapping)):
        return [value]
    if hasattr(value, "tolist"):
        value = value.tolist()
    try:
        return list(value)
    except TypeError:
        return [value]


def _normalize_profiles(profiles, profile_units, quality):
    normalized = []
    for branch, raw_profile in profiles.items():
        if not isinstance(raw_profile, Mapping):
            continue
        variables = []
        point_count = 0
        for variable, raw_values in raw_profile.items():
            source_values = _as_values(raw_values)
            point_count = max(point_count, len(source_values))
            variable_name = str(variable)
            missing_is_unavailable = variable_name != "BranchEquipment"
            values = [
                _clean_json_value(
                    value,
                    "profiles.{0}.{1}[{2}]".format(branch, variable_name, index),
                    quality,
                    missing_is_unavailable,
                )
                for index, value in enumerate(source_values)
            ]
            variables.append({
                "variable": variable_name,
                "unit": str(profile_units.get(variable, profile_units.get(variable_name, "")) or "").strip(),
                "values": values,
            })
        normalized.append({
            "branch": str(branch),
            "pointCount": point_count,
            "variables": variables,
        })
    return normalized


def sanitize_message(value):
    message = _LOCAL_PIPE.sub("net.pipe://localhost/pipe/[redacted]", str(value))
    return _LOCAL_PATH.sub("[local path]", message)


def _messages(values):
    if values is None:
        return []
    if isinstance(values, (str, bytes)):
        return [sanitize_message(values)]
    return [sanitize_message(value) for value in values]


def network_failure_message(results):
    summary = getattr(results, "summary", None) or {}
    details = _messages(summary.get("Error", []))
    if not details:
        details = _messages(summary.get("Warning", []))
    if not details:
        details = _messages(getattr(results, "messages", []))
    suffix = ": " + details[0] if details else "."
    return "PIPESIM Network simulation returned state {0}{1}".format(
        sanitize_message(getattr(results, "state", "Unknown")),
        suffix,
    )


def build_network_result(results, topology, study):
    quality = []
    summary = results.summary or {}
    return {
        "schemaVersion": "pipesim-network-result/1",
        "model_kind": "network",
        "runTask": "network",
        "resultContract": "VALID_FULL",
        "study": study,
        "simulationState": str(results.state),
        "topology": topology,
        "system": _normalize_scalar_results(results.system, "system", quality),
        "node": _normalize_scalar_results(results.node, "node", quality),
        "profiles": _normalize_profiles(results.profile, results.profile_units, quality),
        "summary": {
            "info": _messages(summary.get("Info", [])),
            "warnings": _messages(summary.get("Warning", [])),
            "errors": _messages(summary.get("Error", [])),
        },
        "messages": _messages(results.messages),
        "quality": quality,
    }


def _safe_text(value, allow_empty=False):
    return (
        isinstance(value, str)
        and (allow_empty or bool(value.strip()))
        and not any(ord(character) < 32 or ord(character) == 127 for character in value)
    )


def _safe_port(value):
    return _safe_text(value, allow_empty=True) and (value == "" or bool(value.strip()))


def _safe_number(value):
    return (
        value is None
        or (
            not isinstance(value, bool)
            and isinstance(value, Real)
            and math.isfinite(value)
            and not any(math.isclose(value, sentinel, rel_tol=1e-12) for sentinel in _PIPESIM_UNAVAILABLE_SENTINELS)
        )
    )


def _safe_topology(topology):
    if not isinstance(topology, Mapping) or set(topology) != {"nodes", "edges", "counts"}:
        return None
    nodes = topology.get("nodes")
    edges = topology.get("edges")
    counts = topology.get("counts")
    if not isinstance(nodes, list) or not nodes or not isinstance(edges, list) or not edges:
        return None
    if not isinstance(counts, Mapping) or set(counts) != {"nodes", "edges", "sources", "sinks", "flowlines"}:
        return None
    if any(isinstance(value, bool) or not isinstance(value, int) or value < 0 for value in counts.values()):
        return None
    node_ids = set()
    safe_nodes = []
    for node in nodes:
        if (
            not isinstance(node, Mapping)
            or set(node) != {"id", "componentType"}
            or not _safe_text(node.get("id"))
            or not _safe_text(node.get("componentType"))
            or node["id"] in node_ids
        ):
            return None
        node_ids.add(node["id"])
        safe_nodes.append({"id": node["id"], "componentType": node["componentType"]})
    safe_edges = []
    for edge in edges:
        if (
            not isinstance(edge, Mapping)
            or set(edge) != {"source", "destination", "sourcePort"}
            or not _safe_text(edge.get("source"))
            or not _safe_text(edge.get("destination"))
            or not _safe_port(edge.get("sourcePort"))
            or edge["source"] not in node_ids
            or edge["destination"] not in node_ids
        ):
            return None
        safe_edges.append({
            "source": edge["source"],
            "destination": edge["destination"],
            "sourcePort": edge["sourcePort"],
        })
    if counts["nodes"] < len(safe_nodes) or counts["edges"] < len(safe_edges):
        return None
    return {"nodes": safe_nodes, "edges": safe_edges, "counts": dict(counts)}


def _safe_scalar_series(groups):
    if not isinstance(groups, list):
        return []
    safe_groups = []
    variables = set()
    for group in groups:
        if (
            not isinstance(group, Mapping)
            or set(group) != {"variable", "unit", "values"}
            or not _safe_text(group.get("variable"))
            or not _safe_text(group.get("unit"), allow_empty=True)
            or not isinstance(group.get("values"), list)
            or group["variable"] in variables
        ):
            continue
        names = set()
        values = []
        valid = True
        for item in group["values"]:
            if (
                not isinstance(item, Mapping)
                or set(item) != {"name", "value"}
                or not _safe_text(item.get("name"))
                or item["name"] in names
                or not _safe_number(item.get("value"))
            ):
                valid = False
                break
            names.add(item["name"])
            values.append({"name": item["name"], "value": item["value"]})
        if valid and values:
            variables.add(group["variable"])
            safe_groups.append({"variable": group["variable"], "unit": group["unit"], "values": values})
    return safe_groups


def _safe_profiles(profiles):
    if not isinstance(profiles, list):
        return []
    safe_profiles = []
    branches = set()
    for profile in profiles:
        if (
            not isinstance(profile, Mapping)
            or set(profile) != {"branch", "pointCount", "variables"}
            or not _safe_text(profile.get("branch"))
            or isinstance(profile.get("pointCount"), bool)
            or not isinstance(profile.get("pointCount"), int)
            or profile["pointCount"] < 0
            or not isinstance(profile.get("variables"), list)
            or profile["branch"] in branches
        ):
            continue
        variables = []
        names = set()
        for variable in profile["variables"]:
            if (
                not isinstance(variable, Mapping)
                or set(variable) != {"variable", "unit", "values"}
                or not _safe_text(variable.get("variable"))
                or not _safe_text(variable.get("unit"), allow_empty=True)
                or not isinstance(variable.get("values"), list)
                or variable["variable"] in names
                or len(variable["values"]) > profile["pointCount"]
                or not all(_safe_number(value) for value in variable["values"])
            ):
                continue
            names.add(variable["variable"])
            variables.append({
                "variable": variable["variable"],
                "unit": variable["unit"],
                "values": list(variable["values"]),
            })
        by_name = {variable["variable"]: variable["values"] for variable in variables}
        distance = by_name.get("TotalDistance")
        pressure = by_name.get("Pressure")
        if not isinstance(distance, list) or not distance or not isinstance(pressure, list) or len(distance) != len(pressure):
            continue
        branches.add(profile["branch"])
        safe_profiles.append({
            "branch": profile["branch"],
            "pointCount": profile["pointCount"],
            "variables": variables,
        })
    return safe_profiles


def build_safe_partial_network_result(result):
    topology = _safe_topology(result.get("topology"))
    if topology is None:
        return None
    system = _safe_scalar_series(result.get("system"))
    node = _safe_scalar_series(result.get("node"))
    profiles = _safe_profiles(result.get("profiles"))
    summary = result.get("summary") if isinstance(result.get("summary"), Mapping) else {}
    return {
        "schemaVersion": "pipesim-network-result/1",
        "model_kind": "network",
        "runTask": "network",
        "resultContract": "VALID_PARTIAL",
        "study": result.get("study"),
        "simulationState": "Completed",
        "topology": topology,
        "system": system,
        "node": node,
        "profiles": profiles,
        "summary": {
            "info": _messages(summary.get("info", [])),
            "warnings": _messages(summary.get("warnings", [])),
            "errors": _messages(summary.get("errors", [])),
        },
        "messages": _messages(result.get("messages", [])),
        "quality": [],
    }


def validate_network_result(result, expected_study=None):
    if result.get("schemaVersion") != "pipesim-network-result/1":
        return False
    if result.get("model_kind") != "network" or result.get("runTask") != "network":
        return False
    if result.get("resultContract") != "VALID_FULL" or result.get("simulationState") != "Completed":
        return False
    if not isinstance(result.get("study"), str) or (
        expected_study is not None and result.get("study") != expected_study
    ):
        return False
    topology = result.get("topology")
    if not isinstance(topology, dict) or not topology.get("nodes") or not topology.get("edges"):
        return False
    profiles = result.get("profiles")
    if not isinstance(profiles, list) or not profiles:
        return False
    quality = result.get("quality")
    if not isinstance(quality, list):
        return False
    quality_paths = set()
    for item in quality:
        if (
            not isinstance(item, dict)
            or set(item) != {"path", "code"}
            or not isinstance(item.get("path"), str)
            or item.get("code") not in ("NON_FINITE", "UNAVAILABLE")
            or item["path"] in quality_paths
        ):
            return False
        quality_paths.add(item["path"])

    missing_paths = set()
    data_paths = set()
    valid_values = True

    def add_path(path):
        nonlocal valid_values
        if path in data_paths:
            valid_values = False
        data_paths.add(path)

    def validate_numeric(value, path):
        nonlocal valid_values
        if value is None:
            add_path(path)
            missing_paths.add(path)
            return True
        elif isinstance(value, Mapping):
            has_numeric_leaf = False
            for key, item in value.items():
                has_numeric_leaf = validate_numeric(item, "{0}.{1}".format(path, key)) or has_numeric_leaf
            if not has_numeric_leaf:
                valid_values = False
            return has_numeric_leaf
        elif isinstance(value, list):
            has_numeric_leaf = False
            for index, item in enumerate(value):
                has_numeric_leaf = validate_numeric(item, "{0}[{1}]".format(path, index)) or has_numeric_leaf
            if not has_numeric_leaf:
                valid_values = False
            return has_numeric_leaf
        else:
            add_path(path)
            if isinstance(value, bool) or not isinstance(value, Real) or not math.isfinite(value):
                valid_values = False
                return False
            elif any(math.isclose(value, sentinel, rel_tol=1e-12) for sentinel in _PIPESIM_UNAVAILABLE_SENTINELS):
                valid_values = False
                return False
            return True

    def validate_text(value, path):
        nonlocal valid_values
        if isinstance(value, list):
            for index, item in enumerate(value):
                validate_text(item, "{0}[{1}]".format(path, index))
            return
        add_path(path)
        if value is not None and not isinstance(value, str):
            valid_values = False

    for section in ("system", "node"):
        groups = result.get(section)
        if not isinstance(groups, list):
            return False
        variables_seen = set()
        for group in groups:
            if (
                not isinstance(group, dict)
                or set(group) != {"variable", "unit", "values"}
                or not isinstance(group.get("variable"), str)
                or group["variable"] in variables_seen
                or not isinstance(group.get("unit"), str)
                or not isinstance(group.get("values"), list)
            ):
                return False
            variables_seen.add(group["variable"])
            names_seen = set()
            for item in group["values"]:
                if (
                    not isinstance(item, dict)
                    or set(item) != {"name", "value"}
                    or not isinstance(item.get("name"), str)
                    or item["name"] in names_seen
                ):
                    return False
                names_seen.add(item["name"])
                validate_numeric(
                    item["value"],
                    "{0}.{1}.{2}".format(section, group["variable"], item["name"]),
                )

    branches_seen = set()
    for profile in profiles:
        if (
            not isinstance(profile, dict)
            or set(profile) != {"branch", "pointCount", "variables"}
            or not isinstance(profile.get("branch"), str)
            or profile["branch"] in branches_seen
            or isinstance(profile.get("pointCount"), bool)
            or not isinstance(profile.get("pointCount"), int)
            or profile["pointCount"] < 0
            or not isinstance(profile.get("variables"), list)
        ):
            return False
        branches_seen.add(profile["branch"])
        variable_names = [item.get("variable") for item in profile["variables"] if isinstance(item, dict)]
        if (
            len(variable_names) != len(profile["variables"])
            or any(not isinstance(name, str) for name in variable_names)
            or len(variable_names) != len(set(variable_names))
        ):
            return False
        variables = {
            item.get("variable"): item.get("values")
            for item in profile["variables"]
        }
        distance = variables.get("TotalDistance")
        pressure = variables.get("Pressure")
        if not isinstance(distance, list) or not distance or not isinstance(pressure, list):
            return False
        if len(distance) != len(pressure) or profile.get("pointCount") < len(distance):
            return False
        for variable in profile["variables"]:
            if (
                set(variable) != {"variable", "unit", "values"}
                or not isinstance(variable.get("variable"), str)
                or not isinstance(variable.get("unit"), str)
                or not isinstance(variable.get("values"), list)
            ):
                return False
            path = "profiles.{0}.{1}".format(profile["branch"], variable["variable"])
            if variable["variable"] == "BranchEquipment":
                validate_text(variable["values"], path)
            else:
                validate_numeric(variable["values"], path)
    return valid_values and missing_paths == quality_paths
