import json
import math
import os
import sys
import copy
import re

_PROTOCOL_OUTPUT = sys.stdout
SCRIPT_DIRECTORY = os.path.dirname(os.path.abspath(__file__))
if SCRIPT_DIRECTORY not in sys.path:
    sys.path.insert(0, SCRIPT_DIRECTORY)

ptk_path = os.environ.get("PIPESIM_PTK_PATH")
if ptk_path and ptk_path not in sys.path:
    sys.path.insert(0, ptk_path)

from ptk_normalization import (  # noqa: E402
    _clean_number,
    normalize_curve,
    normalize_profile,
    validate_result_contract,
)
from ptk_network import (  # noqa: E402
    NETWORK_COMPONENT_TYPES,
    build_network_result,
    build_safe_partial_network_result,
    extract_topology,
    format_validation_issues,
    inspect_network,
    network_failure_message,
    network_profile_variables,
    _normalize_scalar_results,
    validate_network_result,
)


class AdapterFailure(Exception):
    def __init__(self, category, code, message, retryable=False, diagnostics=None):
        super().__init__(message)
        self.category = category
        self.code = code
        self.retryable = retryable
        self.diagnostics = diagnostics


def _error(category, code, message, retryable=False):
    return {
        "category": category,
        "code": code,
        "message": message,
        "retryable": retryable,
    }


def _safe_exception_diagnostic(exception, limit=320):
    message = " ".join("".join(character for character in str(exception)
                                if character >= " " and character != "\x7f").split())
    message = re.sub(r"(?i)\b[A-Z]:\\(?:[^\s\\]+\\)*[^\s,;]*", "[local path]", message)
    message = re.sub(r"\\\\[^\\\s]+\\[^\\\s]+(?:\\[^\s,;]*)?", "[network path]", message)
    message = re.sub(r"(?i)\b(password|passwd|token|secret|license[_ -]?key)\s*[:=]\s*[^\s,;]+",
                     r"\1=[redacted]", message)
    return message[:limit] or "The Toolkit returned no diagnostic text."


def _validation_diagnostics(issues):
    issues = list(issues or [])
    diagnostics = []

    def safe_text(value, limit):
        if value is None:
            return ""
        text = str(value)
        text = "".join(character for character in text if character >= " " and character != "\x7f")
        text = re.sub(r"(?i)\b[A-Z]:\\(?:[^\s\\]+\\)*[^\s,;]*", "[local path]", text)
        text = re.sub(r"\\\\[^\\\s]+\\[^\\\s]+(?:\\[^\s,;]*)?", "[network path]", text)
        text = re.sub(r"(?i)\b(password|passwd|token|secret|license[_ -]?key)\s*[:=]\s*[^\s,;]+",
                      r"\1=[redacted]", text)
        return text[:limit]

    for issue in issues[:40]:
        def read_text(name, fallback=""):
            value = issue.get(name, fallback) if isinstance(issue, dict) else getattr(issue, name, fallback)
            return safe_text(value, 500)

        diagnostics.append({
            "path": read_text("path")[:255],
            "property": read_text("property_name")[:128],
            "message": read_text("message") or "Official validation returned an issue without readable details.",
        })
    if len(issues) > 40:
        diagnostics.append({"path": "", "property": "", "message": "Additional validation issues were omitted after the 40-item display limit."})
    return diagnostics


def _preflight_well_task(task, producer, study, parameters=None):
    try:
        if parameters:
            task.set_conditions(producer=producer, study=study, parameters=parameters)
        issues = task.validate(producer=producer, study=study)
        if issues is None:
            raise AdapterFailure("VALIDATION", "PIPESIM_TASK_VALIDATION_EMPTY",
                                 "The PIPESIM task preflight returned no validation status.")
        issues = list(issues)
    except AdapterFailure:
        raise
    except Exception as exc:
        raise AdapterFailure("VALIDATION", "PIPESIM_TASK_VALIDATION_UNAVAILABLE",
                             "The PIPESIM task preflight could not validate this well and Study.") from exc
    diagnostics = _validation_diagnostics(issues)
    if diagnostics:
        raise AdapterFailure("VALIDATION", "PIPESIM_TASK_VALIDATION_FAILED",
                             f"PIPESIM task preflight found {len(issues)} issue(s).", diagnostics=diagnostics)


def _classify_exception(exc, default_code="PIPESIM_RUN_FAILED"):
    text = str(exc).lower()
    name = type(exc).__name__.lower()
    if "license" in text or "license" in name:
        return AdapterFailure(
            "LICENSE", "LICENSE_UNAVAILABLE", "PIPESIM Python Toolkit license is unavailable.", True
        )
    if "import" in name or "module" in name or "sixgill" in text:
        return AdapterFailure(
            "ENVIRONMENT", "PTK_UNAVAILABLE", "PIPESIM Python Toolkit is unavailable.", True
        )
    return AdapterFailure("EXECUTION", default_code, "PIPESIM well execution failed.", False)


def _unit_descriptor():
    return {"displayUnit": None, "semantics": "unspecified"}


def _result_units(model_kind):
    flow_unit = (
        {"displayUnit": "mmscf/d", "semantics": "standard_gas_volume_rate"}
        if model_kind == "basic_gas"
        else _unit_descriptor()
    )
    return {
        "flow": flow_unit,
        "pressure": _unit_descriptor(),
        "depth": _unit_descriptor(),
        "temperature": _unit_descriptor(),
    }


def _result_document(model_kind, run_task, ipr, vlp, profile):
    for point in ipr + vlp:
        if _clean_number(point.get("flow")) is None or _clean_number(point.get("pressure")) is None:
            raise AdapterFailure("PROTOCOL", "NON_FINITE_RESULT", "The normalized curve contains a non-finite value.")
    for point in profile:
        if any(_clean_number(point.get(name)) is None for name in ("depth", "pressure", "temperature")):
            raise AdapterFailure("PROTOCOL", "NON_FINITE_RESULT", "The normalized profile contains a non-finite value.")
    contract = validate_result_contract(run_task, ipr, vlp, profile)
    return {
        "schemaVersion": "pipesim-well-result/1",
        "model_kind": model_kind,
        "runTask": run_task,
        "resultContract": contract,
        "units": _result_units(model_kind),
        "ipr": ipr,
        "vlp": vlp,
        "profile": profile,
    }


def _well_output_variables():
    """Use the explicit output list from the official PTK nodal examples.

    PIPESIM 2022.1 can return a default diagnostic column without a unit id
    for multi-string/injection wells.  The Toolkit then raises KeyError(None)
    while converting the result.  Explicit variables keep the result contract
    deterministic and still expose both liquid and gas candidates to the
    phase-aware normalizer.
    """
    from sixgill.definitions import ProfileVariables, SystemVariables

    return {
        "system_variables": [
            SystemVariables.PRESSURE,
            SystemVariables.VOLUME_FLOWRATE_LIQUID_STOCKTANK,
            SystemVariables.VOLUME_FLOWRATE_GAS_STOCKTANK,
        ],
        "profile_variables": [
            ProfileVariables.TEMPERATURE,
            ProfileVariables.PRESSURE,
            ProfileVariables.ELEVATION,
            ProfileVariables.TOTAL_DISTANCE,
        ],
    }


def _run_components(model, requested_well=None, require_flow_components=True):
    all_wells = list(model.find(component="Well"))
    if not all_wells:
        raise AdapterFailure("MODEL", "WELL_NOT_FOUND", "The PIPESIM model has no well context.")
    if requested_well is None:
        if len(all_wells) != 1:
            raise AdapterFailure("MODEL", "WELL_CONTEXT_REQUIRED", "Select one well explicitly because the model contains multiple wells.")
        requested_well = all_wells[0]
    if requested_well not in all_wells:
        raise AdapterFailure("MODEL", "WELL_CONTEXT_NOT_FOUND", "The requested well context does not exist in the model.")
    if not require_flow_components:
        return {"Well": [requested_well]}, "legacy_well"
    components = {
        "Well": [requested_well],
        "BlackOilFluid": list(model.find(component="BlackOilFluid")),
        "Completion": list(model.find(component="Completion", Well=requested_well)),
        "Tubing": list(model.find(component="Tubing", Well=requested_well)),
    }
    if not components["Completion"]:
        raise AdapterFailure("MODEL", "COMPLETION_NOT_FOUND", "The selected PIPESIM well has no associated completion.")
    if not components["Tubing"]:
        raise AdapterFailure("MODEL", "TUBING_NOT_FOUND", "The selected PIPESIM well has no associated tubing.")

    from sixgill.definitions import Constants, Parameters

    completion = components["Completion"][0]
    fluid_type = model.fluids.fluid_type
    if fluid_type == Constants.FluidType.COMPOSITIONAL:
        if len(components["Completion"]) == 1 and len(components["Tubing"]) == 1:
            try:
                geometry = model.get_value(completion, parameter=Parameters.Completion.GEOMETRYPROFILETYPE)
            except Exception:
                geometry = None
            if str(geometry).lower() == "vertical":
                return components, "basic_gas"
        return components, "legacy_well"
    if fluid_type == Constants.FluidType.BLACKOIL:
        if len(components["Completion"]) == 1 and len(components["Tubing"]) == 1:
            try:
                fluid = model.get_value(completion, parameter=Parameters.Well.ASSOCIATEDBLACKOILFLUID)
            except Exception:
                fluid = None
            if fluid in components["BlackOilFluid"]:
                return components, "black_oil_liquid"
        return components, "legacy_well"
    raise AdapterFailure("MODEL", "UNSUPPORTED_MODEL", "The model fluid type is not supported.")


def _well_esp_contexts(model, well_context):
    try:
        values = model.find(component="ESP", Well=well_context)
    except Exception:
        values = []
    return sorted({value.strip() for value in values if isinstance(value, str) and value.strip()})


def _well_for_esp_context(model, esp_context):
    if isinstance(esp_context, str) and esp_context.upper() == "AUTO":
        all_esps = list(model.find(component="ESP"))
        if len(all_esps) != 1:
            raise AdapterFailure("MODEL", "ESP_CONTEXT_REQUIRED", "Select one ESP explicitly because the model contains multiple ESP components.")
        esp_context = all_esps[0]
    matches = [well for well in model.find(component="Well")
               if esp_context in _well_esp_contexts(model, well)]
    if not matches:
        raise AdapterFailure("MODEL", "ESP_CONTEXT_NOT_FOUND", "The requested ESP context is not associated with a model well.")
    if len(matches) != 1:
        raise AdapterFailure("MODEL", "ESP_CONTEXT_AMBIGUOUS", "The ESP context matches more than one well; select a unique well context.")
    return matches[0]


def _esp_curves_parameters(parameters):
    if isinstance(parameters, dict) and parameters == {"schemaVersion": "pipesim-esp-curves-parameters/1"}:
        return {"schemaVersion": "pipesim-esp-curves-parameters/1", "wellContext": None, "espContext": None}
    if (not isinstance(parameters, dict) or set(parameters) != {"schemaVersion", "wellContext", "espContext"}
            or parameters.get("schemaVersion") != "pipesim-esp-curves-parameters/2"):
        raise AdapterFailure("PROTOCOL", "INVALID_ESP_CURVES_PARAMETERS", "ESP Curves parameters must bind an inspected well and ESP context.")
    well, esp = parameters.get("wellContext"), parameters.get("espContext")
    if not isinstance(well, str) or not well.strip() or not isinstance(esp, str) or not esp.strip():
        raise AdapterFailure("PROTOCOL", "INVALID_ESP_CURVES_PARAMETERS", "ESP Curves parameters must bind an inspected well and ESP context.")
    return {"schemaVersion": parameters["schemaVersion"], "wellContext": well.strip(), "espContext": esp.strip()}


def _trajectory_parameters(parameters):
    if isinstance(parameters, dict) and parameters == {"schemaVersion": "pipesim-well-trajectory-parameters/1"}:
        return {"schemaVersion": "pipesim-well-trajectory-parameters/1", "wellContext": None}
    if isinstance(parameters, dict) and parameters.get("schemaVersion") == "pipesim-well-trajectory-parameters/3":
        if (set(parameters) != {"schemaVersion", "wellContext", "stationIndex", "targetInclinationDeg"}
                or not isinstance(parameters.get("wellContext"), str) or not parameters["wellContext"].strip()
                or len(parameters["wellContext"]) > 255 or ".." in parameters["wellContext"]
                or any(character in parameters["wellContext"] for character in ("/", "\\", "\x00"))
                or any(ord(character) < 32 or ord(character) == 127 for character in parameters["wellContext"])
                or isinstance(parameters.get("stationIndex"), bool) or not isinstance(parameters.get("stationIndex"), int)
                or parameters["stationIndex"] < 1 or parameters["stationIndex"] > 4094
                or isinstance(parameters.get("targetInclinationDeg"), bool)
                or not isinstance(parameters.get("targetInclinationDeg"), (int, float))
                or not math.isfinite(parameters["targetInclinationDeg"])
                or parameters["targetInclinationDeg"] < 0 or parameters["targetInclinationDeg"] > 90):
            raise AdapterFailure("PROTOCOL", "INVALID_TRAJECTORY_PARAMETERS", "Trajectory edit must select an interior station and a finite inclination from 0 to 90 degrees.")
        return {
            "schemaVersion": parameters["schemaVersion"],
            "wellContext": parameters["wellContext"].strip(),
            "stationIndex": parameters["stationIndex"],
            "targetInclinationDeg": float(parameters["targetInclinationDeg"]),
        }
    if (not isinstance(parameters, dict) or set(parameters) != {"schemaVersion", "wellContext"}
            or parameters.get("schemaVersion") != "pipesim-well-trajectory-parameters/2"):
        raise AdapterFailure("PROTOCOL", "INVALID_TRAJECTORY_PARAMETERS", "Trajectory parameters must bind an inspected well context.")
    well = parameters.get("wellContext")
    if (not isinstance(well, str) or not well.strip() or len(well) > 255 or ".." in well
            or any(character in well for character in ("/", "\\", "\x00"))
            or any(ord(character) < 32 or ord(character) == 127 for character in well)):
        raise AdapterFailure("PROTOCOL", "INVALID_TRAJECTORY_PARAMETERS", "Trajectory parameters must bind an inspected well context.")
    return {"schemaVersion": parameters["schemaVersion"], "wellContext": well.strip()}


def _study_names(model):
    from sixgill.core.resources import ModelClasses

    return [
        study.name
        for study in model._catalog.lookup_entries_by_class_ids([ModelClasses.STUDY])
    ]


def _execute_network(model, study, parameters, emit_event):
    inspection = inspect_network(model)
    if not inspection["valid"]:
        raise AdapterFailure(
            "MODEL",
            "UNSUPPORTED_NETWORK_MODEL",
            "The model is missing required network components: {0}.".format(
                ", ".join(inspection["missing"])
            ),
        )
    try:
        issues = model.tasks.networksimulation.validate(study=study)
    except Exception as exc:
        failure = _classify_exception(exc, "NETWORK_VALIDATION_FAILED")
        if failure.category == "EXECUTION":
            failure = AdapterFailure(
                "MODEL",
                "NETWORK_VALIDATION_FAILED",
                "The selected Network Simulation Study could not be validated.",
            )
        raise failure
    if issues:
        raise AdapterFailure(
            "MODEL",
            "NETWORK_VALIDATION_FAILED",
            "The selected Network Simulation Study has validation issues: {0}".format(
                format_validation_issues(issues)
            ),
        )

    topology = extract_topology(model, inspection)
    emit_event("RUNNING_NETWORK", "Running the selected Study network simulation.")
    boundary_overrides = _network_boundary_overrides(model, study, parameters)
    try:
        run_kwargs = {"study": study, "profile_variables": network_profile_variables()}
        if boundary_overrides is not None:
            run_kwargs["boundaries"] = boundary_overrides
        simulation = model.tasks.networksimulation.run(**run_kwargs)
    except Exception as exc:
        failure = _classify_exception(exc, "NETWORK_RUN_FAILED")
        if failure.category == "EXECUTION":
            failure = AdapterFailure(
                "EXECUTION",
                "NETWORK_RUN_FAILED",
                "PIPESIM Network simulation failed.",
            )
        raise failure

    if str(getattr(simulation, "state", "")) != "Completed":
        raise AdapterFailure(
            "EXECUTION",
            "NETWORK_SIMULATION_FAILED",
            network_failure_message(simulation),
        )

    emit_event("COLLECTING", "Collecting and normalizing PIPESIM result arrays.")
    try:
        result = build_network_result(simulation, topology, study)
    except Exception as exc:
        raise AdapterFailure(
            "PROTOCOL",
            "NETWORK_RESULT_NORMALIZATION_FAILED",
            "The PIPESIM Network result could not be normalized ({0}).".format(type(exc).__name__),
        )
    if not validate_network_result(result, study):
        result = build_safe_partial_network_result(result)
        if result is None:
            raise AdapterFailure(
                "PROTOCOL",
                "INVALID_NETWORK_RESULT_CONTRACT",
                "The normalized Network result has no safe topology for a limited display.",
            )
        return result, [_error(
            "PROTOCOL",
            "NETWORK_RESULT_LIMITED",
            "PIPESIM Network returned partial display data; full result validation did not pass.",
        )]
    return result, []


def _network_optimizer_parameters(parameters):
    if (not isinstance(parameters, dict)
            or set(parameters) != {"schemaVersion", "applyResults"}
            or parameters.get("schemaVersion") not in (
                "pipesim-network-optimizer-parameters/1",
                "pipesim-network-optimizer-parameters/2",
            )
            or (parameters.get("schemaVersion") == "pipesim-network-optimizer-parameters/1"
                and parameters.get("applyResults") is not False)
            or (parameters.get("schemaVersion") == "pipesim-network-optimizer-parameters/2"
                and parameters.get("applyResults") is not True)):
        raise AdapterFailure("PROTOCOL", "INVALID_NETWORK_OPTIMIZER_PARAMETERS",
                             "Network Optimizer parameters are invalid.")
    return parameters


def _optimizer_scalar(raw_value, path, quality):
    if isinstance(raw_value, bool):
        return raw_value
    try:
        value = float(raw_value)
    except (TypeError, ValueError):
        raise AdapterFailure("PROTOCOL", "INVALID_NETWORK_OPTIMIZER_RESULT",
                             "PIPESIM returned a non-numeric Network Optimizer value for {0}.".format(path))
    if not math.isfinite(value):
        quality.append({"path": path, "code": "UNAVAILABLE"})
        return None
    return value


def _optimizer_text_array(value, name):
    if not isinstance(value, (list, tuple)):
        raise AdapterFailure("PROTOCOL", "INVALID_NETWORK_OPTIMIZER_RESULT",
                             "PIPESIM returned invalid Network Optimizer {0} messages.".format(name))
    normalized = []
    for item in value:
        _safe_system_text(str(item), "optimizer " + name, allow_empty=True)
        normalized.append(str(item))
    return normalized


def _normalize_optimizer_groups(raw_groups, group_name, variable_keys, quality):
    if not isinstance(raw_groups, dict) or not raw_groups:
        raise AdapterFailure("PROTOCOL", "EMPTY_NETWORK_OPTIMIZER_RESULT",
                             "PIPESIM returned no Network Optimizer {0} results.".format(group_name))
    groups = []
    for name, raw_values in raw_groups.items():
        name = str(name)
        _safe_system_text(name, "optimizer " + group_name + " name")
        if not isinstance(raw_values, dict):
            raise AdapterFailure("PROTOCOL", "INVALID_NETWORK_OPTIMIZER_RESULT",
                                 "PIPESIM returned invalid Network Optimizer {0} values.".format(group_name))
        values = []
        for key in sorted(raw_values):
            key = str(key)
            if key not in variable_keys:
                raise AdapterFailure("PROTOCOL", "INVALID_NETWORK_OPTIMIZER_RESULT",
                                     "PIPESIM returned an unknown Network Optimizer variable.")
            values.append({
                "key": key,
                "value": _optimizer_scalar(raw_values[key], group_name + "." + name + "." + key, quality),
            })
        groups.append({"name": name, "values": values})
    return groups


def _optimizer_application_audit(model):
    """Apply the official optimizer result and capture a bounded read-back audit.

    The PTK API applies all optimizer controls through apply_results().  The
    official example exposes gas-lift injection rate as a stable, readable
    control, so capture those controls when present.  Other optimizer control
    families remain applied by PTK but are not guessed into the result contract.
    """
    try:
        from sixgill.definitions import Parameters

        parameter = Parameters.GasLiftInjection.GASRATE
        contexts = list(model.find(component="GasLiftInjection"))
    except Exception:
        contexts = []
        parameter = "GasRate"

    before = {}
    units = {}
    for context in contexts:
        context = str(context)
        try:
            raw = model.get_value(context=context, parameter=parameter)
            value = float(raw)
            if math.isfinite(value):
                before[context] = value
                try:
                    units[context] = str(model.describe(context=context, parameter=parameter).units_symbol or "")
                except Exception:
                    units[context] = ""
        except Exception:
            continue

    try:
        model.tasks.networkoptimizersimulation.apply_results()
    except Exception as exc:
        raise _classify_exception(exc, "NETWORK_OPTIMIZER_APPLY_FAILED")

    changes = []
    for context in sorted(before):
        try:
            raw = model.get_value(context=context, parameter=parameter)
            after = float(raw)
            if math.isfinite(after):
                changes.append({
                    "context": context,
                    "parameter": str(parameter),
                    "unit": units.get(context, ""),
                    "before": before[context],
                    "after": after,
                })
        except Exception:
            continue

    # PTK keeps the applied values in the opened model until save() is called.
    # close() alone does not persist the optimizer changes into the isolated
    # .pips artifact that the Worker publishes.
    try:
        model.save()
    except Exception as exc:
        raise _classify_exception(exc, "NETWORK_OPTIMIZER_APPLY_FAILED")

    return {
        "requested": True,
        "applied": True,
        "scope": "isolated-model-copy",
        "sourceModelUnchanged": True,
        "artifactName": "pipesim-network-optimizer-applied.pips",
        "changes": changes,
    }


def _execute_network_optimizer(model, study, parameters, emit_event):
    parameters = _network_optimizer_parameters(parameters)
    emit_event("RUNNING_NETWORK", "Running official PIPESIM Network Optimizer.")
    try:
        simulation = model.tasks.networkoptimizersimulation.run()
    except Exception as exc:
        failure = _classify_exception(exc, "NETWORK_OPTIMIZER_FAILED")
        if failure.category == "EXECUTION":
            failure = AdapterFailure("EXECUTION", "NETWORK_OPTIMIZER_FAILED",
                                     "PIPESIM Network Optimizer failed.")
        raise failure
    if str(getattr(simulation, "state", "")) != "Completed":
        raise AdapterFailure("EXECUTION", "NETWORK_OPTIMIZER_FAILED",
                             "PIPESIM Network Optimizer did not complete successfully.")
    try:
        variable_names = getattr(simulation, "variable_names", None)
        units = getattr(simulation, "units", None)
        if not isinstance(variable_names, dict) or not variable_names or not isinstance(units, dict):
            raise AdapterFailure("PROTOCOL", "EMPTY_NETWORK_OPTIMIZER_RESULT",
                                 "PIPESIM returned no Network Optimizer variable metadata.")
        variables = []
        for key in sorted(variable_names):
            key = str(key)
            label = variable_names[key]
            unit = units.get(key, "")
            _safe_system_text(key, "optimizer variable key")
            _safe_system_text(str(label), "optimizer variable label")
            _safe_system_text(str(unit), "optimizer variable unit", allow_empty=True)
            variables.append({"key": key, "label": str(label), "unit": str(unit)})
        variable_keys = {item["key"] for item in variables}
        quality = []
        result_summary = getattr(simulation, "summary", {})
        if not isinstance(result_summary, dict):
            result_summary = {}
        application = _optimizer_application_audit(model) if parameters["applyResults"] else None
        result = {
            "schemaVersion": "pipesim-network-optimizer-result/2" if application else "pipesim-network-optimizer-result/1",
            "model_kind": "network",
            "runTask": "network-optimizer",
            "resultContract": "VALID_FULL",
            "simulationState": "Completed",
            "summary": {
                "info": _optimizer_text_array(result_summary.get("Info", []), "info"),
                "warnings": _optimizer_text_array(result_summary.get("Warning", []), "warnings"),
                "errors": _optimizer_text_array(result_summary.get("Error", []), "errors"),
            },
            "messages": _optimizer_text_array(getattr(simulation, "messages", []), "messages"),
            "variables": variables,
            "wells": _normalize_optimizer_groups(getattr(simulation, "well_results", None), "wells", variable_keys, quality),
            "flowlines": _normalize_optimizer_groups(getattr(simulation, "flowline_results", None), "flowlines", variable_keys, quality),
            "sinks": _normalize_optimizer_groups(getattr(simulation, "sink_results", None), "sinks", variable_keys, quality),
            "quality": quality,
        }
        if application is not None:
            result["application"] = application
        return result
    except AdapterFailure:
        raise
    except Exception as exc:
        raise AdapterFailure("PROTOCOL", "NETWORK_OPTIMIZER_RESULT_NORMALIZATION_FAILED",
                             "The PIPESIM Network Optimizer result could not be normalized ({0}).".format(type(exc).__name__))


def _system_analysis_context_name(value):
    return (isinstance(value, str) and bool(value) and value == value.strip()
            and len(value) <= 255 and ".." not in value
            and not any(character in value for character in ("/", "\\", "\x00"))
            and not any(ord(character) < 32 or ord(character) == 127 for character in value))


def _system_analysis_parameters(parameters):
    if (not isinstance(parameters, dict)
            or set(parameters) != {
                "schemaVersion", "producer", "branchTerminator",
                "outletPressurePsi", "scanVariable", "values"
            }
            or parameters.get("schemaVersion") not in (
                "pipesim-system-analysis-parameters/1", "pipesim-system-analysis-parameters/2")
            or (parameters.get("schemaVersion") == "pipesim-system-analysis-parameters/1"
                and parameters.get("producer") != "Well")
            or (parameters.get("schemaVersion") == "pipesim-system-analysis-parameters/2"
                and (not _system_analysis_context_name(parameters.get("producer"))
                     or not _system_analysis_context_name(parameters.get("branchTerminator"))))
            or parameters.get("scanVariable") != "liquidFlowRate"
            or not isinstance(parameters.get("branchTerminator"), str)
            or not parameters["branchTerminator"].strip()
            or len(parameters["branchTerminator"]) > 255
            or any(character in parameters["branchTerminator"] for character in ("/", "\\", "..", "\x00"))
            or isinstance(parameters.get("outletPressurePsi"), bool)
            or not isinstance(parameters.get("outletPressurePsi"), (int, float))
            or not math.isfinite(parameters["outletPressurePsi"])
            or not 0 < parameters["outletPressurePsi"] <= 100000
            or not isinstance(parameters.get("values"), list)
            or not 2 <= len(parameters["values"]) <= 8):
        raise AdapterFailure("PROTOCOL", "INVALID_SYSTEM_ANALYSIS_PARAMETERS", "System Analysis parameters are invalid.")
    previous = None
    for value in parameters["values"]:
        if (isinstance(value, bool) or not isinstance(value, (int, float))
                or not math.isfinite(value) or value <= 0 or value > 1000000000
                or previous is not None and value <= previous):
            raise AdapterFailure("PROTOCOL", "INVALID_SYSTEM_ANALYSIS_PARAMETERS",
                                 "System Analysis scan values must be positive, finite, and strictly increasing.")
        previous = value
    return parameters


def _finite_system_value(value, name):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise AdapterFailure("PROTOCOL", "INVALID_SYSTEM_ANALYSIS_RESULT",
                             "PIPESIM returned a non-finite System Analysis value for {0}.".format(name))
    return value


def _safe_system_text(value, name, allow_empty=False):
    if (not isinstance(value, str) or (not allow_empty and not value.strip())
            or len(value) > 1000 or any(character in value for character in ("\x00", "\r", "\n"))):
        raise AdapterFailure("PROTOCOL", "INVALID_SYSTEM_ANALYSIS_RESULT",
                             "PIPESIM returned unsafe System Analysis text for {0}.".format(name))
    return value


def _system_analysis_case_name(system, node, profile):
    for mapping in (profile, node):
        if isinstance(mapping, dict) and mapping:
            return _safe_system_text(next(iter(mapping)), "caseName")
    if isinstance(system, dict):
        for values in system.values():
            if isinstance(values, dict):
                names = [name for name in values if name != "Unit"]
                if names:
                    return _safe_system_text(names[0], "caseName")
    raise AdapterFailure("PROTOCOL", "EMPTY_SYSTEM_ANALYSIS_RESULT",
                         "PIPESIM returned no System Analysis case name.")


def _normalize_system_analysis_system(system, case_name):
    if not isinstance(system, dict):
        raise AdapterFailure("PROTOCOL", "INVALID_SYSTEM_ANALYSIS_RESULT", "PIPESIM System Analysis system data is invalid.")
    normalized = []
    for variable, values in system.items():
        _safe_system_text(variable, "system variable")
        if not isinstance(values, dict) or case_name not in values:
            continue
        value = values[case_name]
        unit = values.get("Unit")
        if unit is not None:
            _safe_system_text(unit, "system unit", allow_empty=True)
        normalized.append({
            "variable": variable,
            "unit": unit,
            "value": _finite_system_value(value, "system." + variable),
        })
    if not normalized:
        raise AdapterFailure("PROTOCOL", "EMPTY_SYSTEM_ANALYSIS_RESULT", "PIPESIM returned no System Analysis system values.")
    return normalized


def _normalize_system_analysis_nodes(nodes, case_name):
    if not isinstance(nodes, dict) or case_name not in nodes or not isinstance(nodes[case_name], dict):
        raise AdapterFailure("PROTOCOL", "EMPTY_SYSTEM_ANALYSIS_RESULT", "PIPESIM returned no System Analysis node values.")
    by_node = {}
    for variable, values in nodes[case_name].items():
        _safe_system_text(variable, "node variable")
        if not isinstance(values, dict):
            raise AdapterFailure("PROTOCOL", "INVALID_SYSTEM_ANALYSIS_RESULT", "PIPESIM System Analysis node data is invalid.")
        unit = values.get("Unit")
        if unit is not None:
            _safe_system_text(unit, "node unit", allow_empty=True)
        for node_name, raw_value in values.items():
            if node_name == "Unit":
                continue
            _safe_system_text(node_name, "node name")
            by_node.setdefault(node_name, []).append({
                "variable": variable,
                "unit": unit,
                "value": _finite_system_value(raw_value, "node." + variable),
            })
    normalized = [{"node": node_name, "variables": variables} for node_name, variables in by_node.items() if variables]
    if not normalized:
        raise AdapterFailure("PROTOCOL", "EMPTY_SYSTEM_ANALYSIS_RESULT", "PIPESIM returned no usable System Analysis node values.")
    return normalized


def _normalize_system_analysis_profile(profile, case_name, profile_units=None):
    if not isinstance(profile, dict) or case_name not in profile or not isinstance(profile[case_name], dict):
        raise AdapterFailure("PROTOCOL", "EMPTY_SYSTEM_ANALYSIS_RESULT", "PIPESIM returned no System Analysis profile.")
    raw_profile = profile[case_name]
    variables = []
    distance_length = None
    pressure_length = None
    for variable, values in raw_profile.items():
        _safe_system_text(variable, "profile variable")
        if not isinstance(values, (list, tuple)) or not values:
            raise AdapterFailure("PROTOCOL", "INVALID_SYSTEM_ANALYSIS_RESULT", "PIPESIM System Analysis profile values are invalid.")
        normalized_values = []
        for value in values:
            if variable == "BranchEquipment":
                if value is not None:
                    _safe_system_text(value, "profile equipment", allow_empty=True)
                normalized_values.append(value)
            else:
                normalized_values.append(_finite_system_value(value, "profile." + variable))
        unit = profile_units.get(variable) if isinstance(profile_units, dict) else None
        if unit is not None:
            _safe_system_text(unit, "profile unit", allow_empty=True)
        variables.append({"variable": variable, "unit": unit, "values": normalized_values})
        if variable == "TotalDistance":
            distance_length = len(normalized_values)
        elif variable == "Pressure":
            pressure_length = len(normalized_values)
    if distance_length is None or pressure_length is None or distance_length != pressure_length:
        raise AdapterFailure("PROTOCOL", "INVALID_SYSTEM_ANALYSIS_RESULT",
                             "PIPESIM System Analysis profile lacks matching TotalDistance and Pressure arrays.")
    if any(len(item["values"]) != distance_length for item in variables):
        raise AdapterFailure("PROTOCOL", "INVALID_SYSTEM_ANALYSIS_RESULT",
                             "PIPESIM System Analysis profile arrays have different lengths.")
    return {"pointCount": distance_length, "variables": variables}


def _network_well_performance_parameters(parameters):
    fields = {"schemaVersion", "producerContext", "branchTerminator", "sensitivityUnit", "sensitivityValues"}
    if (not isinstance(parameters, dict) or set(parameters) != fields
            or parameters.get("schemaVersion") != "pipesim-network-well-performance-parameters/1"
            or parameters.get("sensitivityUnit") != "in"):
        raise AdapterFailure("PROTOCOL", "INVALID_NETWORK_WELL_PERFORMANCE_PARAMETERS",
                             "Well Performance Curves require inspected producer/branch contexts and inch values.")
    producer, branch, values = parameters.get("producerContext"), parameters.get("branchTerminator"), parameters.get("sensitivityValues")
    def safe_context(value):
        return (isinstance(value, str) and bool(value.strip()) and len(value) <= 255 and ".." not in value
                and not any(character in value for character in ("/", "\\", "\x00"))
                and not any(ord(character) < 32 or ord(character) == 127 for character in value))
    if not safe_context(producer) or not safe_context(branch) or not isinstance(values, list) or not 2 <= len(values) <= 8:
        raise AdapterFailure("PROTOCOL", "INVALID_NETWORK_WELL_PERFORMANCE_PARAMETERS",
                             "Well Performance Curves require valid contexts and 2 to 8 increasing diameter values.")
    normalized, previous = [], 0.0
    for value in values:
        if (isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value)
                or value <= previous or value > 1000):
            raise AdapterFailure("PROTOCOL", "INVALID_NETWORK_WELL_PERFORMANCE_PARAMETERS",
                                 "Flowline inner diameters must be finite, positive, increasing, and no greater than 1000 in.")
        normalized.append(float(value))
        previous = float(value)
    return {"producerContext": producer.strip(), "branchTerminator": branch.strip(), "sensitivityValues": normalized}


def _execute_network_well_performance_curves(model, study, parameters, emit_event):
    parameters = _network_well_performance_parameters(parameters)
    producer, branch = parameters["producerContext"], parameters["branchTerminator"]
    try:
        wells, flowlines = list(model.find(component="Well")), list(model.find(component="Flowline"))
    except Exception as exc:
        raise AdapterFailure("MODEL", "NETWORK_COMPONENT_DISCOVERY_FAILED",
                             "PIPESIM could not enumerate Well and Flowline contexts.") from exc
    if producer not in wells:
        raise AdapterFailure("MODEL", "NETWORK_PRODUCER_NOT_FOUND", "The selected producer is not a Well component in this PIPESIM model.")
    if branch not in flowlines:
        raise AdapterFailure("MODEL", "NETWORK_BRANCH_NOT_FOUND", "The selected branch terminator is not a Flowline component in this PIPESIM model.")

    from sixgill.definitions import Parameters, SystemVariables
    try:
        diameter_parameter = Parameters.Flowline.INNERDIAMETER
        unit = str(model.describe(context=branch, parameter=diameter_parameter).units_symbol or "").strip()
    except Exception as exc:
        raise AdapterFailure("MODEL", "NETWORK_DIAMETER_UNIT_UNAVAILABLE",
                             "The selected Flowline inner-diameter unit could not be confirmed.") from exc
    if unit.lower() != "in":
        raise AdapterFailure("MODEL", "NETWORK_DIAMETER_UNIT_UNSUPPORTED",
                             "The first Well Performance Curves slice accepts Flowline inner diameter only in inches; this model reports another unit.")

    task_parameters = {
        Parameters.WellPerformanceCurvesSimulation.BRANCHTERMINATOR: branch,
        # The installed 2022.1 official example uses FlowratePoints=50; the
        # manual marks it deprecated, so keep the compatibility choice isolated here.
        Parameters.WellPerformanceCurvesSimulation.FLOWRATEPOINTS: 50,
        Parameters.WellPerformanceCurvesSimulation.SENSITIVITYVARIABLES: [{
            Parameters.WellPerformanceCurvesSimulation.SensitivityVariable.COMPONENT: branch,
            Parameters.WellPerformanceCurvesSimulation.SensitivityVariable.VARIABLE: diameter_parameter,
            Parameters.WellPerformanceCurvesSimulation.SensitivityVariable.VALUES: parameters["sensitivityValues"],
        }],
    }
    system_variables = [
        SystemVariables.WELL_CURVE_PERFORMANCE_PWI,
        SystemVariables.SYSTEM_PRESSURE_LOSS,
        SystemVariables.SYSTEM_TEMPERATURE_DIFFERENCE,
        SystemVariables.PRESSURE,
        SystemVariables.TEMPERATURE,
        SystemVariables.CASE_NUMBER,
    ]
    task = model.tasks.wellperformancecurvessimulation
    try:
        task.set_conditions(producer=producer, study=study, parameters=task_parameters)
    except Exception as exc:
        raise _classify_exception(exc, "NETWORK_WELL_PERFORMANCE_SETUP_FAILED")
    emit_event("RUNNING_NETWORK", "Running official PIPESIM Well Performance Curves for the selected producer and branch.")
    try:
        simulation = task.run(producer=producer, study=study, parameters=task_parameters, system_variables=system_variables)
    except Exception as exc:
        failure = _classify_exception(exc, "NETWORK_WELL_PERFORMANCE_FAILED")
        if failure.category == "EXECUTION":
            failure = AdapterFailure("EXECUTION", "NETWORK_WELL_PERFORMANCE_FAILED", "PIPESIM Well Performance Curves calculation failed.")
        raise failure
    if str(getattr(simulation, "state", "")) != "Completed":
        raise AdapterFailure("EXECUTION", "NETWORK_WELL_PERFORMANCE_FAILED", "PIPESIM Well Performance Curves did not complete successfully.")

    try:
        raw_system = getattr(simulation, "system", None)
        raw_node = getattr(simulation, "node", None)
        pwi_variable = SystemVariables.WELL_CURVE_PERFORMANCE_PWI
        raw_pwi = raw_system.get(pwi_variable) if isinstance(raw_system, dict) else None
        if not isinstance(raw_pwi, dict):
            raise AdapterFailure("PROTOCOL", "NETWORK_WELL_PERFORMANCE_PWI_MISSING", "PIPESIM did not return the official PWI payload.")
        pwi_values = [value for key, value in raw_pwi.items() if str(key) != "Unit"]
        if len(pwi_values) != 1 or not isinstance(pwi_values[0], str):
            raise AdapterFailure("PROTOCOL", "NETWORK_WELL_PERFORMANCE_PWI_INVALID", "PIPESIM returned an ambiguous PWI payload.")
        pwi_content = pwi_values[0].replace("\n", "")
        if not pwi_content.strip() or len(pwi_content.encode("utf-8")) > 2_000_000 or "\x00" in pwi_content:
            raise AdapterFailure("PROTOCOL", "NETWORK_WELL_PERFORMANCE_PWI_INVALID", "PIPESIM returned an empty or oversized PWI payload.")
        if not isinstance(raw_node, dict) or not raw_node or len(raw_node) != len(parameters["sensitivityValues"]):
            raise AdapterFailure("PROTOCOL", "NETWORK_WELL_PERFORMANCE_CASE_MISMATCH",
                                 "The returned PIPESIM case count does not match the requested diameter sensitivity values.")

        quality = []
        system_without_pwi = {key: value for key, value in raw_system.items() if str(key) != str(pwi_variable)}
        system = _normalize_scalar_results(system_without_pwi, "system", quality)
        cases = []
        for index, (case_id, node_results) in enumerate(raw_node.items()):
            case_id = str(case_id)
            _safe_system_text(case_id, "Well Performance case")
            if not isinstance(node_results, dict):
                raise AdapterFailure("PROTOCOL", "NETWORK_WELL_PERFORMANCE_CASE_INVALID", "PIPESIM returned an invalid node result case.")
            normalized_node = _normalize_scalar_results(node_results, "node.{0}".format(case_id), quality)
            if not normalized_node:
                raise AdapterFailure("PROTOCOL", "NETWORK_WELL_PERFORMANCE_CASE_EMPTY", "PIPESIM returned an empty node result case.")
            cases.append({"caseId": case_id, "caseIndex": index,
                          "sensitivityValueIn": parameters["sensitivityValues"][index], "node": normalized_node})
        if not system:
            raise AdapterFailure("PROTOCOL", "NETWORK_WELL_PERFORMANCE_SYSTEM_EMPTY", "PIPESIM returned no usable system result values.")
        summary = getattr(simulation, "summary", {}) or {}
        if not isinstance(summary, dict): summary = {}
        return {
            "schemaVersion": "pipesim-network-well-performance-result/1",
            "model_kind": "network", "runTask": "network-well-performance-curves", "resultContract": "VALID_FULL",
            "study": study, "producerContext": producer, "branchTerminator": branch,
            "sensitivity": {"variable": "InnerDiameter", "unit": "in", "values": parameters["sensitivityValues"]},
            "flowratePoints": 50, "simulationState": "Completed", "system": system, "cases": cases,
            "summary": {
                "info": _optimizer_text_array(summary.get("Info", []), "info"),
                "warnings": _optimizer_text_array(summary.get("Warning", []), "warnings"),
                "errors": _optimizer_text_array(summary.get("Error", []), "errors"),
            },
            "messages": _optimizer_text_array(getattr(simulation, "messages", []), "messages"),
            "quality": quality,
            "pwiArtifact": {"artifactName": "pipesim-network-well-performance.pwi", "content": pwi_content},
        }
    except AdapterFailure:
        raise
    except Exception as exc:
        raise AdapterFailure("PROTOCOL", "NETWORK_WELL_PERFORMANCE_RESULT_INVALID",
                             "PIPESIM Well Performance Curves results could not be normalized.") from exc


def _preflight_system_analysis_context(model, parameters):
    from sixgill.definitions import Units

    try:
        unit_system = model.about.unit_system
        wells = list(model.find(component="Well"))
        flowlines = list(model.find(component="Flowline"))
        connections = list(model.connections())
    except Exception as exc:
        raise AdapterFailure("MODEL", "SYSTEM_ANALYSIS_CONTEXT_UNAVAILABLE",
                             "System Analysis model contexts and units could not be verified.") from exc
    if unit_system != Units.FIELD:
        raise AdapterFailure("MODEL", "SYSTEM_ANALYSIS_UNIT_UNSUPPORTED",
                             "This System Analysis slice requires verified Field units (psia and STB/d).")
    producer, branch = parameters["producer"], parameters["branchTerminator"]
    if producer not in wells:
        raise AdapterFailure("MODEL", "SYSTEM_ANALYSIS_PRODUCER_NOT_FOUND",
                             "Select an existing Well component for System Analysis.")
    if branch not in flowlines:
        raise AdapterFailure("MODEL", "SYSTEM_ANALYSIS_BRANCH_NOT_FOUND",
                             "Select an existing Flowline branch terminator for System Analysis.")
    downstream = {}
    for edge in connections:
        if not isinstance(edge, dict) or not isinstance(edge.get("Source"), str) or not isinstance(edge.get("Destination"), str):
            raise AdapterFailure("MODEL", "SYSTEM_ANALYSIS_TOPOLOGY_UNAVAILABLE",
                                 "System Analysis connection directions could not be verified.")
        downstream.setdefault(edge["Source"], []).append(edge["Destination"])
    pending, visited = [producer], set()
    while pending:
        context = pending.pop()
        if context in visited:
            continue
        visited.add(context)
        pending.extend(downstream.get(context, []))
    if branch not in visited:
        raise AdapterFailure("MODEL", "SYSTEM_ANALYSIS_BRANCH_UNREACHABLE",
                             "The selected branch terminator is not downstream of the selected well.")


def _prepare_system_analysis_case(task, producer, study, task_parameters):
    try:
        task.set_conditions(producer=producer, study=study, parameters=task_parameters)
        readback = task.get_conditions(producer=producer, study=study)
        expected = {"Producer": producer, **task_parameters}
        if not isinstance(readback, dict):
            raise AdapterFailure("MODEL", "SYSTEM_ANALYSIS_CONDITION_READBACK_MISMATCH",
                                 "System Analysis conditions could not be read back.")
        for key, value in expected.items():
            actual = readback.get(key)
            if isinstance(value, (int, float)):
                matched = (not isinstance(actual, bool) and isinstance(actual, (int, float))
                           and math.isfinite(actual) and abs(actual - value) <= max(1e-8, abs(value) * 1e-8))
            else:
                matched = actual == value
            if not matched:
                raise AdapterFailure("MODEL", "SYSTEM_ANALYSIS_CONDITION_READBACK_MISMATCH",
                                     "System Analysis conditions differ from the requested case.")
        issues = list(task.validate(producer=producer, study=study, validate_model=True))
        if issues:
            raise AdapterFailure("MODEL", "SYSTEM_ANALYSIS_VALIDATION_FAILED",
                                 "The selected System Analysis case did not pass official validation.",
                                 diagnostics=format_validation_issues(issues))
    except AdapterFailure:
        raise
    except Exception as exc:
        raise AdapterFailure("MODEL", "SYSTEM_ANALYSIS_PREFLIGHT_FAILED",
                             "System Analysis conditions or official validation could not be confirmed.") from exc


def _system_analysis_field_unit(variable, unit):
    # This slice requests these variables in verified PIPESIM_FIELD units.
    # Do not convert, relabel or accept a nonempty but unrelated unit.
    expected = {"TotalDistance": "ft", "Pressure": "psia", "BottomHolePressure": "psia",
                "Temperature": "degF", "VolumeFlowrateLiquidStockTank": "STB/d"}
    return variable in expected and unit == expected[variable]


def _validate_system_analysis_case_units(case):
    for variable in case["profile"]["variables"]:
        if variable["variable"] in ("TotalDistance", "Pressure", "Temperature") and not variable["unit"]:
            raise AdapterFailure("PROTOCOL", "SYSTEM_ANALYSIS_RESULT_UNIT_MISSING",
                                 "System Analysis profile units must be supplied by the official result.")
        if variable["variable"] != "BranchEquipment" and not _system_analysis_field_unit(variable["variable"], variable["unit"]):
            raise AdapterFailure("PROTOCOL", "SYSTEM_ANALYSIS_RESULT_UNIT_UNSUPPORTED",
                                 "System Analysis returned an unverified variable or Field unit.")
        if variable["variable"] != "BranchEquipment" and any(_clean_number(value) is None for value in variable["values"]):
            raise AdapterFailure("PROTOCOL", "INVALID_SYSTEM_ANALYSIS_RESULT",
                                 "System Analysis profile contains unavailable native values.")
    names = {value["variable"] for value in case["profile"]["variables"]}
    if not {"TotalDistance", "Pressure", "Temperature"}.issubset(names):
        raise AdapterFailure("PROTOCOL", "INVALID_SYSTEM_ANALYSIS_RESULT",
                             "System Analysis must return distance, pressure and temperature profiles.")
    for value in case["system"] + [value for node in case["node"] for value in node["variables"]]:
        if not _system_analysis_field_unit(value["variable"], value["unit"]) or _clean_number(value["value"]) is None:
            raise AdapterFailure("PROTOCOL", "INVALID_SYSTEM_ANALYSIS_RESULT",
                                 "System Analysis numeric values and units must be available in the official result.")


def _execute_system_analysis(model, study, parameters, emit_event):
    parameters = _system_analysis_parameters(parameters)
    from sixgill.definitions import Constants, Parameters, ProfileVariables, SystemVariables

    dynamic_context = parameters["schemaVersion"] == "pipesim-system-analysis-parameters/2"
    if dynamic_context:
        _preflight_system_analysis_context(model, parameters)

    system_variables = [
        SystemVariables.PRESSURE,
        SystemVariables.TEMPERATURE,
        SystemVariables.VOLUME_FLOWRATE_LIQUID_STOCKTANK,
        SystemVariables.BOTTOM_HOLE_PRESSURE,
    ]
    profile_variables = [
        ProfileVariables.TEMPERATURE,
        ProfileVariables.PRESSURE,
        ProfileVariables.TOTAL_DISTANCE,
    ]
    cases = []
    for index, scan_value in enumerate(parameters["values"], start=1):
        emit_event("RUNNING_NETWORK", "Running PIPESIM System Analysis case {0}/{1}.".format(
            index, len(parameters["values"])))
        task_parameters = {
            Parameters.SystemAnalysisSimulation.BRANCHTERMINATOR: parameters["branchTerminator"],
            Parameters.SystemAnalysisSimulation.OUTLETPRESSURE: parameters["outletPressurePsi"],
            Parameters.SystemAnalysisSimulation.LIQUIDFLOWRATE: scan_value,
            Parameters.SystemAnalysisSimulation.FLOWRATETYPE: Constants.FlowRateType.LIQUIDFLOWRATE,
            Parameters.SystemAnalysisSimulation.CALCULATEDVARIABLE: Constants.CalculatedVariable.INLETPRESSURE,
        }
        try:
            if dynamic_context:
                _prepare_system_analysis_case(model.tasks.systemanalysissimulation,
                                              parameters["producer"], study, task_parameters)
            simulation = model.tasks.systemanalysissimulation.run(
                producer=parameters["producer"],
                study=study,
                parameters=task_parameters,
                system_variables=system_variables,
                profile_variables=profile_variables,
            )
        except AdapterFailure:
            raise
        except Exception as exc:
            failure = _classify_exception(exc, "SYSTEM_ANALYSIS_CASE_FAILED")
            if failure.category == "EXECUTION":
                failure = AdapterFailure("EXECUTION", "SYSTEM_ANALYSIS_CASE_FAILED",
                                         "PIPESIM System Analysis calculation failed.")
            raise failure
        if str(getattr(simulation, "state", "")) != "Completed":
            raise AdapterFailure("EXECUTION", "SYSTEM_ANALYSIS_SIMULATION_FAILED",
                                 "PIPESIM System Analysis did not complete successfully.")
        raw_system = getattr(simulation, "system", None)
        raw_node = getattr(simulation, "node", None)
        raw_profile = getattr(simulation, "profile", None)
        case_name = _system_analysis_case_name(raw_system, raw_node, raw_profile)
        if dynamic_context and (set(raw_node or {}) != {case_name} or set(raw_profile or {}) != {case_name}):
            raise AdapterFailure("PROTOCOL", "INVALID_SYSTEM_ANALYSIS_RESULT",
                                 "The official result does not contain exactly the requested System Analysis case.")
        case = {
            "caseName": case_name,
            "scanValue": scan_value,
            "system": _normalize_system_analysis_system(raw_system, case_name),
            "node": _normalize_system_analysis_nodes(raw_node, case_name),
            "profile": _normalize_system_analysis_profile(raw_profile, case_name,
                getattr(simulation, "profile_units", None) if dynamic_context else None),
        }
        if dynamic_context:
            _validate_system_analysis_case_units(case)
        cases.append(case)
    return {
        "schemaVersion": "pipesim-system-analysis-result/2" if dynamic_context else "pipesim-system-analysis-result/1",
        "model_kind": "network",
        "runTask": "system-analysis",
        "resultContract": "VALID_FULL",
        "study": study,
        "producer": parameters["producer"],
        "branchTerminator": parameters["branchTerminator"],
        "outletPressurePsi": parameters["outletPressurePsi"],
        "scanVariable": parameters["scanVariable"],
        "cases": cases,
    }


def _gas_lift_performance_parameters(parameters):
    required = {
        "schemaVersion", "producer", "outletPressurePsi", "surfaceInjectionTemperatureF",
        "targetInjectionRateMmscfd", "reservoirPressurePsi", "gorScfPerStb", "waterCutPercent",
        "valuesMmscfd"
    }
    if (not isinstance(parameters, dict) or set(parameters) != required
            or parameters.get("schemaVersion") != "pipesim-gas-lift-performance-parameters/1"
            or not isinstance(parameters.get("producer"), str)
            or not parameters["producer"].strip() or len(parameters["producer"]) > 255
            or any(character in parameters["producer"] for character in ("/", "\\", "..", "\x00"))
            or not isinstance(parameters.get("valuesMmscfd"), list)
            or not 2 <= len(parameters["valuesMmscfd"]) <= 16):
        raise AdapterFailure("PROTOCOL", "INVALID_GAS_LIFT_PERFORMANCE_PARAMETERS",
                             "Gas Lift Performance parameters are invalid.")
    limits = {
        "outletPressurePsi": (0, 100000),
        "surfaceInjectionTemperatureF": (-1000, 100000),
        "targetInjectionRateMmscfd": (0, 100000),
        "reservoirPressurePsi": (0, 100000),
        "gorScfPerStb": (0, 1000000),
        "waterCutPercent": (0, 100),
    }
    for name, (lower, upper) in limits.items():
        value = parameters.get(name)
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value < lower or value > upper:
            raise AdapterFailure("PROTOCOL", "INVALID_GAS_LIFT_PERFORMANCE_PARAMETERS",
                                 "Gas Lift Performance boundary values are invalid.")
    previous = -math.inf
    for value in parameters["valuesMmscfd"]:
        if (isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value)
                or value < 0 or value <= previous or value > 100000):
            raise AdapterFailure("PROTOCOL", "INVALID_GAS_LIFT_PERFORMANCE_PARAMETERS",
                                 "Gas Lift Performance scan values must be finite and strictly increasing.")
        previous = value
    return parameters


def _gas_lift_stage_failure(exc, code, category="VALIDATION"):
    failure = _classify_exception(exc, code)
    if failure.category == "EXECUTION":
        failure = AdapterFailure(category, code, "PIPESIM gas lift task rejected this stage.")
    failure.diagnostics = [{"path": "", "property": code,
                            "message": _safe_exception_diagnostic(exc)}]
    return failure


def _run_gas_lift_task(model, producer, study, conditions, boundaries, system_variables, emit_event):
    from ptk_gas_lift import discover_gas_lift
    from sixgill.definitions import Units

    if model.about.unit_system != Units.FIELD:
        raise AdapterFailure("MODEL", "GAS_LIFT_UNITS_UNSUPPORTED",
                             "The current gas lift parameter contract requires the PIPESIM Field unit system.")
    try:
        capability = discover_gas_lift(model, producer, study, strict=True)
    except Exception as exc:
        raise _gas_lift_stage_failure(exc, "GAS_LIFT_CAPABILITY_UNKNOWN") from exc
    if capability["state"] != "supported":
        reason = capability["reason"]
        raise AdapterFailure("MODEL", reason, "The selected well and Study do not support this valve task.",
                             diagnostics=[{"path": producer, "property": "Gas lift data/TargetInjectionRate",
                                           "message": "This valve workflow requires UseGasliftValve=true and the native target injection rate axis. GL Port/GLR is a different workflow."}])
    task = model.tasks.gldiagnosticssimulation
    try:
        task.set_conditions(producer=producer, parameters=conditions, boundaries=boundaries, study=study)
    except Exception as exc:
        raise _gas_lift_stage_failure(exc, "GAS_LIFT_PARAMETERS_REJECTED") from exc
    try:
        issues = task.validate(producer=producer, study=study, validate_model=True)
        if issues is None:
            raise AdapterFailure("VALIDATION", "GAS_LIFT_VALIDATION_UNAVAILABLE", "Gas lift task validation returned no status.")
        diagnostics = _validation_diagnostics(issues)
        if diagnostics:
            raise AdapterFailure("VALIDATION", "GAS_LIFT_VALIDATION_FAILED", "Gas lift task validation found issues.", diagnostics=diagnostics)
    except AdapterFailure:
        raise
    except Exception as exc:
        raise _gas_lift_stage_failure(exc, "GAS_LIFT_VALIDATION_UNAVAILABLE") from exc
    # Keep the existing transport state; the event message names the actual task.
    emit_event("RUNNING_NODAL", "Running official PIPESIM Gas Lift task after successful preflight.")
    try:
        return task.run(producer=producer, study=study, system_variables=system_variables)
    except Exception as exc:
        raise _gas_lift_stage_failure(exc, "GAS_LIFT_SOLVER_FAILED", "EXECUTION") from exc


def _execute_gas_lift_performance(model, components, model_kind, parameters, emit_event, study=None):
    parameters = _gas_lift_performance_parameters(parameters)
    if model_kind != "black_oil_liquid":
        raise AdapterFailure("MODEL", "UNSUPPORTED_MODEL",
                             "Gas Lift Performance is supported only for black-oil liquid wells.")
    well_name = components["Well"][0]
    if well_name != parameters["producer"]:
        raise AdapterFailure("MODEL", "PRODUCER_NOT_FOUND",
                             "The requested gas lift producer does not match the validated well.")
    from sixgill.definitions import Constants, Parameters, SystemVariables

    gas_lift_parameters = {
        Parameters.GLDiagnosticsSimulation.OUTLETPRESSURE: parameters["outletPressurePsi"],
        Parameters.GLDiagnosticsSimulation.DIAGNOSTICSTYPE: Constants.GasLift.DiagnosticsOperationDiagnostics.FIXEDINJECTION,
        Parameters.GLDiagnosticsSimulation.THROTTLING: Constants.GasLift.DiagnosticsOperationThrottling.ON,
        Parameters.GLDiagnosticsSimulation.TARGETINJECTIONRATE: parameters["targetInjectionRateMmscfd"],
        Parameters.GLDiagnosticsSimulation.SURFACEINJECTIONTEMPERATURE: parameters["surfaceInjectionTemperatureF"],
        Parameters.GLDiagnosticsSimulation.PRODUCER: parameters["producer"],
        Parameters.GLDiagnosticsSimulation.USEPHASERATIO: True,
        Parameters.GLDiagnosticsSimulation.SENSITIVITYVARIABLE: {
            Parameters.GLDiagnosticsSimulation.SensitivityVariable.COMPONENT: "Gas lift data",
            Parameters.GLDiagnosticsSimulation.SensitivityVariable.VARIABLE: Parameters.Well.TARGETINJECTIONRATE,
            Parameters.GLDiagnosticsSimulation.SensitivityVariable.VALUES: parameters["valuesMmscfd"],
        },
    }
    boundary_parameters = {
        components["Completion"][0]: {
            Parameters.Boundary.GOR: parameters["gorScfPerStb"],
            Parameters.Boundary.WATERCUT: parameters["waterCutPercent"],
            Parameters.Boundary.PRESSURE: parameters["reservoirPressurePsi"],
        }
    }
    system_variables = [SystemVariables.OUTLET_VOLUME_FLOWRATE_LIQUID_STOCKTANK]
    simulation = _run_gas_lift_task(model, parameters["producer"], study, gas_lift_parameters,
                                    boundary_parameters, system_variables, emit_event)
    if str(getattr(simulation, "state", "")) != "Completed":
        raise AdapterFailure("EXECUTION", "GAS_LIFT_PERFORMANCE_FAILED",
                             "PIPESIM Gas Lift Performance simulation did not complete.")

    liquid_values = simulation.system.get("OutletVolumeFlowrateLiquidStockTank", {})
    injection_values = simulation.system.get("Gas lift data-TargetInjectionRate", {})
    if not isinstance(liquid_values, dict) or not isinstance(injection_values, dict):
        raise AdapterFailure("PROTOCOL", "EMPTY_GAS_LIFT_PERFORMANCE_RESULT",
                             "PIPESIM returned no Gas Lift Performance curve values.")
    cases = []
    previous = -math.inf
    for case_name in simulation.cases:
        injection = _clean_number(injection_values.get(case_name))
        liquid = _clean_number(liquid_values.get(case_name))
        if injection is None or liquid is None:
            raise AdapterFailure("PROTOCOL", "INCOMPLETE_GAS_LIFT_PERFORMANCE_RESULT",
                                 "At least one requested Gas Lift Performance case has no finite liquid rate.")
        if injection <= previous:
            raise AdapterFailure("PROTOCOL", "INVALID_GAS_LIFT_PERFORMANCE_RESULT",
                                 "PIPESIM returned Gas Lift Performance cases out of order.")
        previous = injection
        case_text = str(case_name)
        if not case_text or len(case_text) > 1000 or any(character in case_text for character in ("\x00", "\r", "\n")):
            raise AdapterFailure("PROTOCOL", "INVALID_GAS_LIFT_PERFORMANCE_RESULT",
                                 "PIPESIM returned an unsafe Gas Lift Performance case name.")
        cases.append({
            "caseName": case_text,
            "injectionRateMmscfd": injection,
            "liquidRateStbPerDay": liquid,
        })
    if len(cases) < 2:
        raise AdapterFailure("PROTOCOL", "EMPTY_GAS_LIFT_PERFORMANCE_RESULT",
                             "PIPESIM returned fewer than two usable Gas Lift Performance cases.")
    if len(cases) != len(parameters["valuesMmscfd"]) or any(
            not math.isclose(case["injectionRateMmscfd"], requested, rel_tol=1e-6, abs_tol=1e-9)
            for case, requested in zip(cases, parameters["valuesMmscfd"])):
        raise AdapterFailure("PROTOCOL", "INCOMPLETE_GAS_LIFT_PERFORMANCE_RESULT",
                             "Gas lift results do not match every requested injection-rate case.")
    return {
        "schemaVersion": "pipesim-gas-lift-performance-result/1",
        "model_kind": "black_oil_liquid",
        "runTask": "gas-lift-performance",
        "resultContract": "VALID_FULL",
        "producer": parameters["producer"],
        "outletPressurePsi": parameters["outletPressurePsi"],
        "surfaceInjectionTemperatureF": parameters["surfaceInjectionTemperatureF"],
        "targetInjectionRateMmscfd": parameters["targetInjectionRateMmscfd"],
        "reservoirPressurePsi": parameters["reservoirPressurePsi"],
        "gorScfPerStb": parameters["gorScfPerStb"],
        "waterCutPercent": parameters["waterCutPercent"],
        "scanVariable": "gasLiftInjectionRate",
        "scanUnit": "mmscf/d",
        "productionUnit": "STB/d",
        "cases": cases,
    }


def _gas_lift_diagnostics_parameters(parameters):
    required = {
        "schemaVersion", "producer", "outletPressurePsi", "surfaceInjectionTemperatureF",
        "targetInjectionRateMmscfd", "reservoirPressurePsi", "gorScfPerStb", "waterCutPercent",
    }
    if (not isinstance(parameters, dict) or set(parameters) != required
            or parameters.get("schemaVersion") != "pipesim-gas-lift-diagnostics-parameters/1"
            or not isinstance(parameters.get("producer"), str)
            or not parameters["producer"].strip() or len(parameters["producer"]) > 255
            or any(character in parameters["producer"] for character in ("/", "\\", "..", "\x00"))):
        raise AdapterFailure("PROTOCOL", "INVALID_GAS_LIFT_DIAGNOSTICS_PARAMETERS",
                             "Gas Lift Diagnostics parameters are invalid.")
    limits = {
        "outletPressurePsi": (0, 100000),
        "surfaceInjectionTemperatureF": (-1000, 100000),
        "targetInjectionRateMmscfd": (0, 100000),
        "reservoirPressurePsi": (0, 100000),
        "gorScfPerStb": (0, 1000000),
        "waterCutPercent": (0, 100),
    }
    for name, (lower, upper) in limits.items():
        value = parameters.get(name)
        if (isinstance(value, bool) or not isinstance(value, (int, float))
                or not math.isfinite(value) or value < lower or value > upper):
            raise AdapterFailure("PROTOCOL", "INVALID_GAS_LIFT_DIAGNOSTICS_PARAMETERS",
                                 "Gas Lift Diagnostics boundary values are invalid.")
    return parameters


def _diagnostic_number(values, valve_name):
    if not isinstance(values, dict):
        return None
    return _clean_number(values.get(valve_name))


def _diagnostic_text(values, valve_name):
    if not isinstance(values, dict):
        return None
    value = values.get(valve_name)
    return value if isinstance(value, str) and value.strip() else None


def _execute_gas_lift_diagnostics(model, components, model_kind, parameters, emit_event, study=None):
    parameters = _gas_lift_diagnostics_parameters(parameters)
    if model_kind != "black_oil_liquid":
        raise AdapterFailure("MODEL", "UNSUPPORTED_MODEL",
                             "Gas Lift Diagnostics is supported only for black-oil liquid wells.")
    well_name = components["Well"][0]
    if well_name != parameters["producer"]:
        raise AdapterFailure("MODEL", "PRODUCER_NOT_FOUND",
                             "The requested gas lift producer does not match the validated well.")
    from sixgill.definitions import Constants, Parameters, SystemVariables

    gas_lift_parameters = {
        Parameters.GLDiagnosticsSimulation.OUTLETPRESSURE: parameters["outletPressurePsi"],
        Parameters.GLDiagnosticsSimulation.DIAGNOSTICSTYPE: Constants.GasLift.DiagnosticsOperationDiagnostics.FIXEDINJECTION,
        Parameters.GLDiagnosticsSimulation.THROTTLING: Constants.GasLift.DiagnosticsOperationThrottling.ON,
        Parameters.GLDiagnosticsSimulation.TARGETINJECTIONRATE: parameters["targetInjectionRateMmscfd"],
        Parameters.GLDiagnosticsSimulation.SURFACEINJECTIONTEMPERATURE: parameters["surfaceInjectionTemperatureF"],
        Parameters.GLDiagnosticsSimulation.PRODUCER: parameters["producer"],
        Parameters.GLDiagnosticsSimulation.USEPHASERATIO: True,
    }
    boundary_parameters = {
        components["Completion"][0]: {
            Parameters.Boundary.GOR: parameters["gorScfPerStb"],
            Parameters.Boundary.WATERCUT: parameters["waterCutPercent"],
            Parameters.Boundary.PRESSURE: parameters["reservoirPressurePsi"],
        }
    }
    diagnostics_variables = [
        SystemVariables.GAS_LIFT_DIAGNOSTICS_PORT_DIAMETER,
        SystemVariables.GAS_LIFT_DIAGNOSTICS_POSITION_STATUS,
        SystemVariables.GAS_LIFT_DIAGNOSTICS_DOME_TEMPERATURE,
        SystemVariables.GAS_LIFT_DIAGNOSTICS_GAS_RATE_NO_THROTTLING,
        SystemVariables.GAS_LIFT_DIAGNOSTICS_STATUS,
        SystemVariables.GAS_LIFT_DIAGNOSTICS_CLOSING_PRESSURE,
        SystemVariables.GAS_LIFT_DIAGNOSTICS_OPENING_PRESSURE,
        SystemVariables.GAS_LIFT_DIAGNOSTICS_PTRO,
        SystemVariables.GAS_LIFT_DIAGNOSTICS_DISCHARGE_COEFFICIENT,
        SystemVariables.GAS_LIFT_DIAGNOSTICS_PORT_TO_BELLOW_AREA,
        SystemVariables.GAS_LIFT_DIAGNOSTICS_OPERATION_MODE,
        SystemVariables.GAS_LIFT_DIAGNOSTICS_PORT_TYPE,
        SystemVariables.OUTLET_VOLUME_FLOWRATE_LIQUID_STOCKTANK,
    ]
    simulation = _run_gas_lift_task(model, parameters["producer"], study, gas_lift_parameters,
                                    boundary_parameters, diagnostics_variables, emit_event)
    if str(getattr(simulation, "state", "")) != "Completed":
        raise AdapterFailure("EXECUTION", "GAS_LIFT_DIAGNOSTICS_FAILED",
                             "PIPESIM Gas Lift Diagnostics simulation did not complete.")

    cases_from_simulation = getattr(simulation, "cases", None)
    node_results = getattr(simulation, "node", None)
    system_results = getattr(simulation, "system", None)
    if not isinstance(cases_from_simulation, (list, tuple)) or not cases_from_simulation:
        raise AdapterFailure("PROTOCOL", "EMPTY_GAS_LIFT_DIAGNOSTICS_RESULT",
                             "PIPESIM returned no Gas Lift Diagnostics cases.")
    if not isinstance(node_results, dict) or not isinstance(system_results, dict):
        raise AdapterFailure("PROTOCOL", "EMPTY_GAS_LIFT_DIAGNOSTICS_RESULT",
                             "PIPESIM returned no Gas Lift Diagnostics node data.")
    injection_values = system_results.get("Gas lift data-TargetInjectionRate", {})
    liquid_values = system_results.get("OutletVolumeFlowrateLiquidStockTank", {})
    if not isinstance(injection_values, dict) or not isinstance(liquid_values, dict):
        raise AdapterFailure("PROTOCOL", "EMPTY_GAS_LIFT_DIAGNOSTICS_RESULT",
                             "PIPESIM returned no Gas Lift Diagnostics production data.")

    position_variable = "GLDiagnosticsValvePositionStatus"
    valve_names = []
    for case_name in cases_from_simulation:
        case_node = node_results.get(case_name)
        position_values = case_node.get(position_variable) if isinstance(case_node, dict) else None
        if isinstance(position_values, dict):
            valve_names = [str(name) for name in position_values if str(name) != "Unit"]
            if valve_names:
                break
    if not valve_names or len(valve_names) > 64:
        raise AdapterFailure("PROTOCOL", "EMPTY_GAS_LIFT_DIAGNOSTICS_RESULT",
                             "PIPESIM returned no gas lift valve diagnostics.")
    if any(not name.strip() or len(name) > 255 or ".." in name or any(c in name for c in ("/", "\\", "\x00"))
           for name in valve_names):
        raise AdapterFailure("PROTOCOL", "INVALID_GAS_LIFT_DIAGNOSTICS_RESULT",
                             "PIPESIM returned an unsafe gas lift valve name.")

    variable_names = {
        "positionStatus": position_variable,
        "status": "GLDiagnosticsValveStatus",
        "gasRateNoThrottlingMmscfd": "GLDiagnosticsValveGasRateNoThrottling",
        "portDiameterIn": "GLDiagnosticsValvePortDiameter",
        "domeTemperatureF": "GLDiagnosticsValveDomeTemperature",
        "closingPressurePsi": "GLDiagnosticsValveClosingPressure",
        "openingPressurePsi": "GLDiagnosticsValveOpeningPressure",
        "ptroPsi": "GLDiagnosticsValvePtro",
        "dischargeCoefficient": "GLDiagnosticsValveDischargeCoefficient",
        "portToBellowArea": "GLDiagnosticsValvePortToBellowArea",
        "operationMode": "GLDiagnosticsValveOperationMode",
        "portType": "GLDiagnosticsValveType",
    }
    cases = []
    previous = -math.inf
    for case_name in cases_from_simulation:
        case_text = str(case_name)
        injection = _clean_number(injection_values.get(case_name))
        liquid = _clean_number(liquid_values.get(case_name))
        if (not case_text or len(case_text) > 1000 or any(character in case_text for character in ("\x00", "\r", "\n"))
                or injection is None or liquid is None or injection <= previous):
            raise AdapterFailure("PROTOCOL", "INVALID_GAS_LIFT_DIAGNOSTICS_RESULT",
                                 "PIPESIM returned incomplete or unordered Gas Lift Diagnostics cases.")
        previous = injection
        case_node = node_results.get(case_name)
        if not isinstance(case_node, dict):
            raise AdapterFailure("PROTOCOL", "INVALID_GAS_LIFT_DIAGNOSTICS_RESULT",
                                 "PIPESIM returned no node diagnostics for a case.")
        valves = []
        for valve_name in valve_names:
            valve = {"valveName": valve_name}
            for result_field, raw_variable in variable_names.items():
                raw_values = case_node.get(raw_variable)
                valve[result_field] = (_diagnostic_text(raw_values, valve_name)
                                       if result_field in ("positionStatus", "status", "operationMode", "portType")
                                       else _diagnostic_number(raw_values, valve_name))
            if not valve["positionStatus"]:
                raise AdapterFailure("PROTOCOL", "INVALID_GAS_LIFT_DIAGNOSTICS_RESULT",
                                     "PIPESIM returned a case without valve position status.")
            valves.append(valve)
        cases.append({
            "caseName": case_text,
            "injectionRateMmscfd": injection,
            "liquidRateStbPerDay": liquid,
            "valves": valves,
        })
    return {
        "schemaVersion": "pipesim-gas-lift-diagnostics-result/1",
        "model_kind": "black_oil_liquid",
        "runTask": "gas-lift-diagnostics",
        "resultContract": "VALID_FULL",
        "producer": parameters["producer"],
        "outletPressurePsi": parameters["outletPressurePsi"],
        "surfaceInjectionTemperatureF": parameters["surfaceInjectionTemperatureF"],
        "targetInjectionRateMmscfd": parameters["targetInjectionRateMmscfd"],
        "reservoirPressurePsi": parameters["reservoirPressurePsi"],
        "gorScfPerStb": parameters["gorScfPerStb"],
        "waterCutPercent": parameters["waterCutPercent"],
        "diagnosticType": "FIXEDINJECTION",
        "throttling": "ON",
        "usePhaseRatio": True,
        "injectionUnit": "mmscf/d",
        "liquidRateUnit": "STB/d",
        "cases": cases,
    }


def _vfp_tables_parameters(parameters):
    required = {
        "schemaVersion", "producer", "reservoirSimulator", "tableNumber", "includeTemperature",
        "bottomHoleDatumDepth", "liquidRatesStbPerDay", "outletPressuresPsi", "waterCutFraction",
        "gorMscfPerStb", "artificialLiftInjectionDpPsi",
    }
    if (not isinstance(parameters, dict) or set(parameters) != required
            or parameters.get("schemaVersion") != "pipesim-vfp-tables-parameters/1"
            or parameters.get("reservoirSimulator") != "ECLIPSE"
            or not isinstance(parameters.get("producer"), str)
            or not parameters["producer"].strip() or len(parameters["producer"]) > 255
            or any(character in parameters["producer"] for character in ("/", "\\", "..", "\x00"))
            or not isinstance(parameters.get("tableNumber"), int)
            or isinstance(parameters.get("tableNumber"), bool)
            or not 1 <= parameters["tableNumber"] <= 100000
            or not isinstance(parameters.get("includeTemperature"), bool)):
        raise AdapterFailure("PROTOCOL", "INVALID_VFP_TABLES_PARAMETERS", "VFP Tables parameters are invalid.")
    limits = {
        "bottomHoleDatumDepth": (0, 100000),
        "liquidRatesStbPerDay": (0, 1000000),
        "outletPressuresPsi": (0, 1000000),
        "waterCutFraction": (0, 1),
        "gorMscfPerStb": (0, 1000000),
        "artificialLiftInjectionDpPsi": (0, 1000000),
    }
    for name, bounds in limits.items():
        values = parameters[name] if isinstance(parameters[name], list) else [parameters[name]]
        if name != "bottomHoleDatumDepth" and not 1 <= len(values) <= 16:
            raise AdapterFailure("PROTOCOL", "INVALID_VFP_TABLES_PARAMETERS", "VFP Tables axes must contain 1 to 16 values.")
        previous = -math.inf
        for value in values:
            if (isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value)
                    or value < bounds[0] or value > bounds[1]
                    or name != "bottomHoleDatumDepth" and value <= previous):
                raise AdapterFailure("PROTOCOL", "INVALID_VFP_TABLES_PARAMETERS", "VFP Tables axes must be finite and strictly increasing.")
            previous = value
    return parameters


def _vfp_axis_values(content, marker):
    lines = content.splitlines()
    for index, line in enumerate(lines):
        if marker in line:
            values = []
            for following in lines[index + 1:]:
                values.extend(float(token) for token in re.findall(r"[-+]?\d+(?:\.\d*)?(?:[Ee][-+]?\d+)?", following.split("/", 1)[0]))
                if "/" in following:
                    return values
            return values
    return []


def _parse_vfp_table(content, value_name, unit, axes):
    rows = []
    row_keys = set()
    marker = "-- Table body:"
    body = content.split(marker, 1)[1] if marker in content else ""
    index_limits = {
        "thpIndex": len(axes["outletPressuresPsi"]),
        "waterCutIndex": len(axes["waterCutFraction"]),
        "gorIndex": len(axes["gorMscfPerStb"]),
        "artificialLiftIndex": len(axes["artificialLiftInjectionDpPsi"]),
    }
    for line in body.splitlines():
        line = line.strip()
        if not line or line.startswith("--"):
            continue
        tokens = line.rstrip("/").split()
        if len(tokens) < 5 or not all(token.isdigit() for token in tokens[:4]):
            continue
        indices = [int(token) for token in tokens[:4]]
        if any(index < 1 or index > limit for index, limit in zip(indices, index_limits.values())):
            raise AdapterFailure("PROTOCOL", "INVALID_VFP_TABLES_RESULT", "PIPESIM returned an out-of-range VFPPROD row index.")
        row_key = tuple(indices)
        if row_key in row_keys:
            raise AdapterFailure("PROTOCOL", "INVALID_VFP_TABLES_RESULT", "PIPESIM returned a duplicate VFPPROD row index.")
        try:
            values = [_clean_number(float(token)) for token in tokens[4:]]
        except (TypeError, ValueError):
            raise AdapterFailure("PROTOCOL", "INVALID_VFP_TABLES_RESULT", "PIPESIM returned a malformed VFPPROD row.")
        if len(values) != len(axes["liquidRatesStbPerDay"]) or any(value is None for value in values):
            raise AdapterFailure("PROTOCOL", "INVALID_VFP_TABLES_RESULT", "Each VFPPROD row must contain one value per liquid-rate axis point.")
        row_keys.add(row_key)
        rows.append({
            "thpIndex": indices[0],
            "waterCutIndex": indices[1],
            "gorIndex": indices[2],
            "artificialLiftIndex": indices[3],
            "values": values,
        })
    expected_rows = math.prod(index_limits.values())
    if len(rows) != expected_rows:
        raise AdapterFailure("PROTOCOL", "INCOMPLETE_VFP_TABLES_RESULT", "PIPESIM returned an incomplete VFPPROD table grid.")
    return {"valueName": value_name, "unit": unit, "rows": rows}


def _write_vfp_engine_file_manifest(task, producer, study, artifact_directory):
    if artifact_directory is None:
        return []
    if not isinstance(artifact_directory, str) or not artifact_directory.strip():
        raise AdapterFailure("PROTOCOL", "INVALID_VFP_ENGINE_OUTPUT", "The isolated VFP engine output directory is invalid.")
    output_directory = os.path.realpath(artifact_directory)
    if not os.path.isdir(output_directory) or os.path.islink(artifact_directory):
        raise AdapterFailure("STORAGE", "INVALID_VFP_ENGINE_OUTPUT", "The isolated VFP engine output directory is unavailable.")
    try:
        generated_files = task.generate_engine_files(
            producer=producer, folder_path=output_directory, study=study
        )
    except Exception as exc:
        raise AdapterFailure("EXECUTION", "VFP_ENGINE_FILES_FAILED", "PIPESIM could not generate the VFP engine files.") from exc
    if not isinstance(generated_files, (list, tuple)) or not 1 <= len(generated_files) <= 64:
        raise AdapterFailure("PROTOCOL", "INVALID_VFP_ENGINE_FILES", "PIPESIM returned an invalid VFP engine file list.")

    names = []
    seen = set()
    total_bytes = 0
    for generated_file in generated_files:
        if not isinstance(generated_file, str) or not generated_file.strip():
            raise AdapterFailure("PROTOCOL", "INVALID_VFP_ENGINE_FILES", "PIPESIM returned an invalid VFP engine file path.")
        candidate = generated_file if os.path.isabs(generated_file) else os.path.join(output_directory, generated_file)
        resolved = os.path.realpath(candidate)
        if (os.path.normcase(os.path.dirname(resolved)) != os.path.normcase(output_directory)
                or os.path.islink(candidate) or not os.path.isfile(resolved)):
            raise AdapterFailure("STORAGE", "INVALID_VFP_ENGINE_FILES", "PIPESIM generated a file outside the isolated output folder.")
        name = os.path.basename(resolved)
        if (not name or len(name) > 128 or not name[0].isalnum()
                or any(not (character.isascii() and (character.isalnum() or character in "._ -")) for character in name)
                or name in (".", "..") or name.casefold() in seen):
            raise AdapterFailure("PROTOCOL", "INVALID_VFP_ENGINE_FILES", "PIPESIM generated an unsafe or duplicate file name.")
        seen.add(name.casefold())
        size = os.path.getsize(resolved)
        total_bytes += size
        if size <= 0 or size > 100_000_000 or total_bytes > 256_000_000:
            raise AdapterFailure("STORAGE", "VFP_ENGINE_FILES_TOO_LARGE", "PIPESIM generated VFP engine files that exceed the artifact limits.")
        names.append(name)

    manifest_path = os.path.join(output_directory, "engine-files.json")
    try:
        with open(manifest_path, "x", encoding="utf-8") as manifest_file:
            json.dump({"schemaVersion": "pipesim-vfp-engine-files/1", "files": names}, manifest_file)
    except Exception as exc:
        raise AdapterFailure("STORAGE", "VFP_ENGINE_MANIFEST_FAILED", "The isolated VFP engine file manifest could not be written.") from exc
    return names


def _execute_vfp_tables(model, study, components, model_kind, parameters, emit_event, artifact_directory=None):
    parameters = _vfp_tables_parameters(parameters)
    if model_kind != "black_oil_liquid":
        raise AdapterFailure("MODEL", "UNSUPPORTED_MODEL", "VFP Tables is supported only for black-oil liquid wells.")
    well_name = components["Well"][0]
    if well_name != parameters["producer"]:
        raise AdapterFailure("MODEL", "PRODUCER_NOT_FOUND", "The requested VFP producer does not match the validated well.")
    from sixgill.definitions import Constants, Parameters, SystemVariables

    conditions = {
        Parameters.VfpTablesSimulation.RESERVOIRSIMULATOR: Constants.VFPTablesOperationTable.ECLIPSE,
        Parameters.VfpTablesSimulation.TABLENUMBER: parameters["tableNumber"],
        Parameters.VfpTablesSimulation.INCLUDETEMPERATURE: parameters["includeTemperature"],
        Parameters.VfpTablesSimulation.BOTTOMHOLEDATUMDEPTH: parameters["bottomHoleDatumDepth"],
        Parameters.VfpTablesSimulation.RATETYPE: Parameters.VfpTablesSimulation.LIQUIDFLOWRATE,
        Parameters.VfpTablesSimulation.GLRATIOTYPE: Parameters.VfpTablesSimulation.GOR,
        Parameters.VfpTablesSimulation.GWRATIOTYPE: Parameters.VfpTablesSimulation.WATERCUT,
        Parameters.VfpTablesSimulation.SENSITIVITYVARIABLES: {
            Parameters.VfpTablesSimulation.LIQUIDRATESENSITIVITY: parameters["liquidRatesStbPerDay"],
            Parameters.VfpTablesSimulation.OUTLETPRESSURESENSITIVITY: parameters["outletPressuresPsi"],
            # The public contract uses fraction and MSCF/STB.  PIPESIM's
            # FIELD Toolkit inputs use percent and SCF/STB respectively.
            Parameters.VfpTablesSimulation.WATERCUTSENSITIVITY: [value * 100 for value in parameters["waterCutFraction"]],
            Parameters.VfpTablesSimulation.GORSENSITIVITY: [value * 1000 for value in parameters["gorMscfPerStb"]],
            Parameters.VfpTablesSimulation.ARTIFICIALLIFTSENSITIVITY: {
                Parameters.VfpTablesSimulation.SensitivityVariable.COMPONENT: Parameters.GasliftSensitivity.COMPONENTNAME,
                Parameters.VfpTablesSimulation.SensitivityVariable.VARIABLE: Parameters.GasliftSensitivity.MINVALVEINJECTIONDP,
                Parameters.VfpTablesSimulation.SensitivityVariable.VALUES: parameters["artificialLiftInjectionDpPsi"],
            },
        },
    }
    emit_event("RUNNING_NODAL", "Running official PIPESIM VFP Tables simulation.")
    task = model.tasks.vfptablessimulation
    try:
        available_lift_variables = task.get_artificial_lift_variables(
            producer=parameters["producer"], study=study
        )
    except Exception as exc:
        failure = _classify_exception(exc, "VFP_ARTIFICIAL_LIFT_DISCOVERY_FAILED")
        if failure.category == "EXECUTION":
            failure = AdapterFailure("VALIDATION", "VFP_ARTIFICIAL_LIFT_DISCOVERY_FAILED",
                                     "PIPESIM could not discover artificial-lift variables for this producer and Study.")
        raise failure from exc
    if (not isinstance(available_lift_variables, (list, tuple))
            or any(not isinstance(variable, str) or not variable.strip() for variable in available_lift_variables)):
        raise AdapterFailure("PROTOCOL", "INVALID_VFP_ARTIFICIAL_LIFT_VARIABLES",
                             "PIPESIM returned an invalid artificial-lift sensitivity variable list.")
    required_lift_variable = str(Parameters.GasliftSensitivity.MINVALVEINJECTIONDP)
    if required_lift_variable.casefold() not in {variable.strip().casefold() for variable in available_lift_variables}:
        raise AdapterFailure("MODEL", "VFP_ARTIFICIAL_LIFT_VARIABLE_UNSUPPORTED",
                             "The selected producer and Study do not support the requested VFP artificial-lift sensitivity variable.")
    try:
        task.set_conditions(producer=parameters["producer"], study=study, parameters=conditions)
    except Exception as exc:
        failure = _classify_exception(exc, "VFP_TABLES_FAILED")
        if failure.category == "EXECUTION":
            failure = AdapterFailure("EXECUTION", "VFP_TABLES_FAILED", "PIPESIM VFP Tables conditions could not be set.")
        raise failure from exc
    try:
        condition_readback = task.get_conditions(producer=parameters["producer"], study=study)
    except Exception as exc:
        failure = _classify_exception(exc, "VFP_CONDITIONS_READBACK_FAILED")
        if failure.category == "EXECUTION":
            failure = AdapterFailure("VALIDATION", "VFP_CONDITIONS_READBACK_FAILED",
                                     "PIPESIM could not read back VFP conditions for the selected Study.")
        raise failure from exc
    if not isinstance(condition_readback, dict) or not condition_readback:
        raise AdapterFailure("VALIDATION", "VFP_CONDITIONS_READBACK_FAILED",
                             "PIPESIM returned no VFP conditions for the selected Study after setting them.")
    try:
        simulation = task.run(
            producer=parameters["producer"], study=study, parameters=conditions
        )
    except Exception as exc:
        failure = _classify_exception(exc, "VFP_TABLES_FAILED")
        if failure.category == "EXECUTION":
            failure = AdapterFailure("EXECUTION", "VFP_TABLES_FAILED", "PIPESIM VFP Tables simulation failed.")
        raise failure
    if str(getattr(simulation, "state", "")) != "Completed":
        raise AdapterFailure("EXECUTION", "VFP_TABLES_FAILED", "PIPESIM VFP Tables simulation did not complete.")
    system = getattr(simulation, "system", None)
    if not isinstance(system, dict):
        raise AdapterFailure("PROTOCOL", "EMPTY_VFP_TABLES_RESULT", "PIPESIM returned no VFP table system data.")
    content_by_well = system.get(SystemVariables.VFP_TABLES, {})
    temperature_content_by_well = system.get(SystemVariables.VFP_TABLES_WITH_TEMPERATURE, {})
    content = content_by_well.get(parameters["producer"]) if isinstance(content_by_well, dict) else None
    temperature_content = temperature_content_by_well.get(parameters["producer"]) if isinstance(temperature_content_by_well, dict) else None
    if not isinstance(content, str) or (parameters["includeTemperature"] and not isinstance(temperature_content, str)):
        raise AdapterFailure("PROTOCOL", "EMPTY_VFP_TABLES_RESULT", "PIPESIM returned no VFP table text content.")
    if not parameters["includeTemperature"]:
        temperature_content = ""
    axes = {
        "liquidRatesStbPerDay": _vfp_axis_values(content, "LIQ flowrate values"),
        "outletPressuresPsi": _vfp_axis_values(content, "THP values"),
        "waterCutFraction": _vfp_axis_values(content, "WCT  values"),
        "gorMscfPerStb": _vfp_axis_values(content, "GOR  values"),
        "artificialLiftInjectionDpPsi": _vfp_axis_values(content, "ALQ"),
    }
    if any(not values for values in axes.values()):
        raise AdapterFailure("PROTOCOL", "INVALID_VFP_TABLES_RESULT", "PIPESIM returned incomplete VFP axes.")
    table = _parse_vfp_table(content, "BHP", "psia", axes)
    temperature_table = (
        _parse_vfp_table(temperature_content, "TEMP", "F", axes)
        if parameters["includeTemperature"]
        else {"valueName": "TEMP", "unit": "F", "rows": []}
    )
    if parameters["includeTemperature"]:
        bhp_keys = [(row["thpIndex"], row["waterCutIndex"], row["gorIndex"], row["artificialLiftIndex"]) for row in table["rows"]]
        temperature_keys = [(row["thpIndex"], row["waterCutIndex"], row["gorIndex"], row["artificialLiftIndex"]) for row in temperature_table["rows"]]
        if bhp_keys != temperature_keys:
            raise AdapterFailure("PROTOCOL", "INVALID_VFP_TABLES_RESULT", "The BHP and temperature VFP tables do not share the same row grid.")
    _write_vfp_engine_file_manifest(task, parameters["producer"], study, artifact_directory)
    return {
        "schemaVersion": "pipesim-vfp-tables-result/2",
        "model_kind": "black_oil_liquid",
        "runTask": "vfp-tables",
        "resultContract": "VALID_FULL",
        "producer": parameters["producer"],
        "reservoirSimulator": "ECLIPSE",
        "tableNumber": parameters["tableNumber"],
        "includeTemperature": parameters["includeTemperature"],
        "bottomHoleDatumDepth": parameters["bottomHoleDatumDepth"],
        "axes": axes,
        "table": table,
        "temperatureTable": temperature_table,
        "vfpTableContent": content,
        "vfpTableWithTemperatureContent": temperature_content,
    }


def _esp_block(mapping, key):
    if not isinstance(mapping, dict):
        return None
    value = mapping.get(key)
    if value is None and isinstance(key, tuple) and len(key) == 1:
        value = mapping.get(key[0])
    if value is None and isinstance(key, str):
        value = mapping.get((key,))
    return value if isinstance(value, dict) else None


def _esp_scalar(mapping, key):
    block = _esp_block(mapping, key)
    if not block:
        return None
    value = block.get(("Value",))
    if value is None:
        value = block.get("Value")
    if isinstance(value, bool) or not isinstance(value, (int, float, str)):
        return None
    if isinstance(value, float) and not math.isfinite(value):
        return None
    return value


def _esp_series(mapping, key):
    block = _esp_block(mapping, key)
    if not block:
        return None, None
    values = block.get(("Values",))
    if values is None:
        values = block.get("Values")
    unit = block.get(("Unit",))
    if unit is None:
        unit = block.get("Unit")
    if not isinstance(values, (list, tuple)) or not values:
        return None, None
    cleaned = [_clean_number(value) for value in values]
    if any(value is None for value in cleaned):
        return None, None
    return cleaned, unit if isinstance(unit, str) else None


def _esp_frequency(text):
    if not isinstance(text, str):
        return None
    match = re.search(r"FREQ\s*=\s*([-+]?\d+(?:\.\d*)?(?:[Ee][-+]?\d+)?)", text, re.IGNORECASE)
    if not match:
        return None
    try:
        value = float(match.group(1))
    except ValueError:
        return None
    return value if math.isfinite(value) else None


def _normalize_esp_pump(pump_name, pump):
    from sixgill.definitions import EspCurvesVariables

    inputs = _esp_block(pump, EspCurvesVariables.INPUTS)
    speed = _esp_block(pump, EspCurvesVariables.VARIABLESPEEDCURVE)
    if not inputs or not speed:
        raise AdapterFailure("PROTOCOL", "EMPTY_ESP_CURVE_RESULT", "PIPESIM returned no usable ESP curve inputs.")
    frequency = _esp_scalar(inputs, EspCurvesVariables.FREQUENCY)
    manufacturer = _esp_scalar(inputs, EspCurvesVariables.MANUFACTURER)
    model = _esp_scalar(inputs, EspCurvesVariables.MODEL)
    min_flow = _esp_scalar(inputs, EspCurvesVariables.MINFLOWRATE)
    max_flow = _esp_scalar(inputs, EspCurvesVariables.MAXFLOWRATE)
    stages = _esp_scalar(inputs, EspCurvesVariables.STAGES)
    if (not isinstance(frequency, (int, float)) or not isinstance(manufacturer, str) or not manufacturer.strip()
            or not isinstance(model, str) or not model.strip() or not isinstance(min_flow, (int, float))
            or not isinstance(max_flow, (int, float)) or not isinstance(stages, (int, float))
            or min_flow < 0 or max_flow <= min_flow or stages <= 0):
        raise AdapterFailure("PROTOCOL", "INVALID_ESP_CURVE_RESULT", "PIPESIM returned incomplete ESP input metadata.")

    frequency_curves = _esp_block(speed, EspCurvesVariables.FREQUENCIES)
    if not frequency_curves:
        raise AdapterFailure("PROTOCOL", "EMPTY_ESP_CURVE_RESULT", "PIPESIM returned no ESP frequency curves.")
    curves = []
    for label, curve in frequency_curves.items():
        rate_values, rate_unit = _esp_series(curve, EspCurvesVariables.FLOWRATE)
        head_values, head_unit = _esp_series(curve, EspCurvesVariables.HEAD)
        parsed_frequency = _esp_frequency(label)
        if parsed_frequency is None or rate_values is None or head_values is None or len(rate_values) != len(head_values):
            raise AdapterFailure("PROTOCOL", "INVALID_ESP_CURVE_RESULT", "PIPESIM returned an invalid ESP frequency curve.")
        curves.append({
            "frequencyHz": parsed_frequency,
            "frequencyLabel": str(label),
            "flowRate": rate_values,
            "flowRateUnit": rate_unit or "",
            "head": head_values,
            "headUnit": head_unit or "",
        })
    curves.sort(key=lambda item: item["frequencyHz"])

    envelope_source = _esp_block(speed, EspCurvesVariables.OPERATINGENVELOPE)
    if not envelope_source:
        raise AdapterFailure("PROTOCOL", "EMPTY_ESP_CURVE_RESULT", "PIPESIM returned no ESP operating envelope.")
    envelope = {}
    for name, key in (("qMin", EspCurvesVariables.MINCURVE), ("bep", EspCurvesVariables.BEPCURVE), ("qMax", EspCurvesVariables.MAXCURVE)):
        source = _esp_block(envelope_source, key)
        flow_values, flow_unit = _esp_series(source, EspCurvesVariables.FLOWRATE)
        head_values, head_unit = _esp_series(source, EspCurvesVariables.HEAD)
        if flow_values is None or head_values is None or len(flow_values) != len(head_values):
            raise AdapterFailure("PROTOCOL", "INVALID_ESP_CURVE_RESULT", "PIPESIM returned an invalid ESP operating envelope.")
        envelope[name] = {
            "flowRate": flow_values,
            "flowRateUnit": flow_unit or "",
            "head": head_values,
            "headUnit": head_unit or "",
        }

    return {
        "pumpName": pump_name,
        "inputs": {
            "frequency": frequency,
            "frequencyUnit": _esp_block(inputs, EspCurvesVariables.FREQUENCY).get(("Unit",), "Hz"),
            "manufacturer": manufacturer,
            "model": model,
            "minFlowRate": min_flow,
            "maxFlowRate": max_flow,
            "stages": stages,
        },
        "frequencies": curves,
        "operatingEnvelope": envelope,
    }


def _execute_esp_curves(model, study, components, model_kind, parameters, emit_event):
    if model_kind not in ("black_oil_liquid", "basic_gas", "legacy_well"):
        raise AdapterFailure("MODEL", "UNSUPPORTED_MODEL", "ESP Curves is supported for validated PIPESIM well models with a selected ESP context.")
    producer = components["Well"][0]
    from sixgill.definitions import ProfileVariables, SystemVariables

    profile_variables = [
        ProfileVariables.TEMPERATURE,
        ProfileVariables.PRESSURE,
        ProfileVariables.ELEVATION,
        ProfileVariables.TOTAL_DISTANCE,
    ]
    system_variables = [SystemVariables.PRESSURE, SystemVariables.TEMPERATURE]
    emit_event("RUNNING_PROFILE", "Running the official PIPESIM PT Profile ESP curve task.")
    try:
        profile_result = model.tasks.ptprofilesimulation.run(
            producer=producer,
            study=study,
            profile_variables=profile_variables,
            system_variables=system_variables,
        )
        nodal_result = None
        emit_event("RUNNING_NODAL", "Running the official PIPESIM Nodal ESP curve task.")
        nodal_result = model.tasks.nodalanalysis.run(
            producer=producer,
            study=study,
            profile_variables=profile_variables,
            system_variables=system_variables,
        )
    except Exception as exc:
        failure = _classify_exception(exc, "ESP_CURVES_FAILED")
        if failure.category == "EXECUTION":
            failure = AdapterFailure("EXECUTION", "ESP_CURVES_FAILED", "The official PIPESIM ESP curve task failed.")
        raise failure

    def pump_from(result):
        curves = getattr(result, "esp_curves", None)
        if not isinstance(curves, dict) or not curves:
            raise AdapterFailure("PROTOCOL", "EMPTY_ESP_CURVE_RESULT", "PIPESIM returned no ESP curves.")
        first_case = next(iter(curves.values()))
        if not isinstance(first_case, dict) or not first_case:
            raise AdapterFailure("MODEL", "ESP_PUMP_NOT_FOUND", "The official model has no B-ESP pump result.")
        selected_esp = parameters.get("espContext")
        selected_name = selected_esp.rsplit(":", 1)[-1] if selected_esp else None
        matching = [name for name in first_case if name == selected_name or name == selected_esp]
        if len(matching) == 1:
            pump_name = matching[0]
        elif len(first_case) == 1:
            pump_name = next(iter(first_case))
        else:
            raise AdapterFailure("MODEL", "ESP_CURVE_CONTEXT_AMBIGUOUS", "PIPESIM returned multiple pump curves that cannot be mapped unambiguously to the selected ESP context.")
        return _normalize_esp_pump(pump_name, first_case[pump_name])

    payload = {
        "schemaVersion": "pipesim-esp-curves-result/2" if parameters["schemaVersion"] == "pipesim-esp-curves-parameters/2" else "pipesim-esp-curves-result/1",
        "model_kind": model_kind,
        "runTask": "esp-curves",
        "resultContract": "VALID_FULL",
        "producer": producer,
        "pump": pump_from(profile_result),
        "nodalPump": pump_from(nodal_result),
    }
    if parameters["schemaVersion"] == "pipesim-esp-curves-parameters/2":
        payload["espContext"] = parameters["espContext"]
    return payload


def _esp_parameter_parameters(parameters):
    if not isinstance(parameters, dict):
        raise AdapterFailure("PROTOCOL", "INVALID_ESP_PARAMETER_PARAMETERS", "ESP parameter scheme parameters are invalid.")
    schema = parameters.get("schemaVersion")
    if schema == "pipesim-esp-parameter-parameters/1":
        if (set(parameters) != {"schemaVersion", "espContext", "parameter", "targetValueHz"}
                or parameters.get("parameter") != "operatingFrequency"):
            raise AdapterFailure("PROTOCOL", "INVALID_ESP_PARAMETER_PARAMETERS", "ESP parameter scheme parameters are invalid.")
        parameter = "operatingFrequency"
        target = parameters.get("targetValueHz")
        upper = 200
    elif schema == "pipesim-esp-parameter-parameters/2":
        if (set(parameters) != {"schemaVersion", "espContext", "parameter", "targetValue"}
                or parameters.get("parameter") != "headFactor"):
            raise AdapterFailure("PROTOCOL", "INVALID_ESP_PARAMETER_PARAMETERS", "ESP parameter scheme parameters are invalid.")
        parameter = "headFactor"
        target = parameters.get("targetValue")
        # PIPESIM 2022.1 does not publish a HEADFACTOR range in the Toolkit
        # metadata.  This is an explicit platform safety bound, not an
        # official simulator limit.
        upper = 2
    elif schema == "pipesim-esp-parameter-parameters/3":
        if (set(parameters) != {"schemaVersion", "wellContext", "espContext", "parameter", "targetValue"}
                or parameters.get("parameter") not in ("headFactor", "operatingFrequency")):
            raise AdapterFailure("PROTOCOL", "INVALID_ESP_PARAMETER_PARAMETERS", "ESP parameter scheme parameters are invalid.")
        parameter = parameters["parameter"]
        target = parameters.get("targetValue")
        upper = 200 if parameter == "operatingFrequency" else 2
    else:
        raise AdapterFailure("PROTOCOL", "INVALID_ESP_PARAMETER_PARAMETERS", "ESP parameter scheme parameters are invalid.")
    context = parameters.get("espContext")
    if (not isinstance(context, str) or not context.strip() or len(context) > 255
            or ".." in context or "/" in context or "\\" in context
            or any(ord(character) < 32 or ord(character) == 127 for character in context)):
        raise AdapterFailure("PROTOCOL", "INVALID_ESP_PARAMETER_PARAMETERS", "ESP context is invalid.")
    if (isinstance(target, bool) or not isinstance(target, (int, float)) or not math.isfinite(target)
            or target <= 0 or target > upper):
        message = "ESP operating frequency must be between 0 and 200 Hz." if parameter == "operatingFrequency" else "ESP head factor must be positive and at most the platform guard value 2.0."
        raise AdapterFailure("PROTOCOL", "INVALID_ESP_PARAMETER_PARAMETERS", message)
    result = {"schemaVersion": schema, "espContext": context.strip(), "parameter": parameter, "targetValue": float(target)}
    if schema == "pipesim-esp-parameter-parameters/3":
        well = parameters.get("wellContext")
        if not isinstance(well, str) or not well.strip() or len(well) > 255 or ".." in well or "/" in well or "\\" in well:
            raise AdapterFailure("PROTOCOL", "INVALID_ESP_PARAMETER_PARAMETERS", "Well context is invalid.")
        result["wellContext"] = well.strip()
    return result


def _execute_esp_parameter_case(model, study, producer, model_kind, emit_event, case_label):
    output_variables = _well_output_variables()
    try:
        emit_event("RUNNING_PROFILE", "Running the official PIPESIM ESP parameter {0} PT Profile.".format(case_label))
        profile_result = model.tasks.ptprofilesimulation.run(
            producer=producer,
            study=study,
            **output_variables,
        )
        emit_event("RUNNING_NODAL", "Running the official PIPESIM ESP parameter {0} Nodal Analysis.".format(case_label))
        nodal_result = model.tasks.nodalanalysis.run(
            producer=producer,
            study=study,
            **output_variables,
        )
    except Exception as exc:
        raise _classify_exception(exc, "ESP_PARAMETER_RUN_FAILED")
    profile = normalize_profile(profile_result.profile)
    ipr = normalize_curve(nodal_result.inflow_curves[0].curve_data, model_kind == "basic_gas") \
        if nodal_result.inflow_curves else []
    vlp = normalize_curve(nodal_result.outflow_curves[0].curve_data, model_kind == "basic_gas") \
        if nodal_result.outflow_curves else []
    if not ipr or not vlp or not profile:
        raise AdapterFailure("EXECUTION", "EMPTY_ESP_PARAMETER_RESULT", "PIPESIM returned no complete ESP parameter calculation result.")
    return {"ipr": ipr, "vlp": vlp, "profile": profile}


def _max_aligned_result_delta(first, second, independent, dependent):
    def series(rows):
        points = []
        for row in rows:
            x = _clean_number(row.get(independent))
            y = _clean_number(row.get(dependent))
            if x is not None and y is not None:
                points.append((x, y))
        points.sort(key=lambda point: point[0])
        deduplicated = []
        for point in points:
            if deduplicated and math.isclose(point[0], deduplicated[-1][0], rel_tol=1e-12, abs_tol=1e-12):
                deduplicated[-1] = point
            else:
                deduplicated.append(point)
        return deduplicated

    left = series(first)
    right = series(second)
    if not left or not right:
        return None
    low = max(left[0][0], right[0][0])
    high = min(left[-1][0], right[-1][0])
    if low > high and not math.isclose(low, high, rel_tol=1e-12, abs_tol=1e-12):
        return None
    if math.isclose(low, high, rel_tol=1e-12, abs_tol=1e-12):
        shared = (low + high) / 2
        candidates = [shared]
    else:
        candidates = sorted({low, high, *(x for x, _ in left if low <= x <= high), *(x for x, _ in right if low <= x <= high)})

    def interpolate(points, x):
        if x <= points[0][0]:
            return points[0][1]
        if x >= points[-1][0]:
            return points[-1][1]
        for (x0, y0), (x1, y1) in zip(points, points[1:]):
            if x0 <= x <= x1:
                if math.isclose(x0, x1, rel_tol=1e-12, abs_tol=1e-12):
                    return y1
                ratio = (x - x0) / (x1 - x0)
                return y0 + ratio * (y1 - y0)
        return None

    deltas = [abs(interpolate(left, x) - interpolate(right, x)) for x in candidates]
    return max(deltas) if deltas else None


def _execute_esp_parameter(model, study, components, model_kind, parameters, emit_event):
    parameters = _esp_parameter_parameters(parameters)
    esp_contexts = [str(name) for name in model.find(component="ESP")]
    if not esp_contexts:
        raise AdapterFailure("MODEL", "ESP_NOT_FOUND", "The selected PIPESIM model has no ESP component.")
    requested_context = parameters["espContext"]
    if requested_context.upper() == "AUTO":
        if len(esp_contexts) != 1:
            raise AdapterFailure("MODEL", "ESP_CONTEXT_REQUIRED", "Select one ESP context explicitly because the model contains multiple ESP components.")
        context = esp_contexts[0]
    else:
        context = requested_context
    if context not in esp_contexts:
        raise AdapterFailure("MODEL", "ESP_CONTEXT_NOT_FOUND", "The requested ESP context does not exist in the model.")

    from sixgill.definitions import Parameters

    parameter = Parameters.ESP.OPERATINGFREQUENCY if parameters["parameter"] == "operatingFrequency" else Parameters.ESP.HEADFACTOR
    expected_unit = "Hz" if parameters["parameter"] == "operatingFrequency" else None
    result_unit = "Hz" if expected_unit else "1"
    try:
        description = model.describe(context=context, parameter=parameter)
        unit = getattr(description, "units_symbol", None)
        original = model.get_value(context=context, parameter=parameter)
    except Exception as exc:
        raise AdapterFailure("MODEL", "ESP_PARAMETER_READ_FAILED", "The requested official PIPESIM ESP parameter could not be read.") from exc
    if ((expected_unit is not None and unit != expected_unit)
            or (expected_unit is None and unit not in (None, ""))
            or isinstance(original, bool) or not isinstance(original, (int, float)) or not math.isfinite(original)):
        raise AdapterFailure("MODEL", "ESP_PARAMETER_UNSUPPORTED", "The selected ESP parameter is not available with the expected official unit semantics.")
    target = parameters["targetValue"]
    if math.isclose(float(original), target, rel_tol=1e-9, abs_tol=1e-6):
        raise AdapterFailure("MODEL", "ESP_PARAMETER_UNCHANGED", "The target ESP parameter must differ from its original value.")

    # Both cases run on the Worker copy; report each official PTK phase so
    # visible run progress remains aligned with simulator work.
    baseline_case = _execute_esp_parameter_case(
        model, study, components["Well"][0], model_kind, emit_event, "baseline"
    )
    try:
        model.set_value(context=context, parameter=parameter, value=target)
        applied = model.get_value(context=context, parameter=parameter)
    except Exception as exc:
        raise AdapterFailure("MODEL", "ESP_PARAMETER_APPLY_FAILED", "The target ESP parameter could not be applied.") from exc
    if (isinstance(applied, bool) or not isinstance(applied, (int, float)) or not math.isfinite(applied)
            or not math.isclose(float(applied), target, rel_tol=1e-9, abs_tol=1e-6)):
        raise AdapterFailure("MODEL", "ESP_PARAMETER_READBACK_MISMATCH", "The target ESP parameter could not be confirmed.")

    target_profile = _execute_esp_parameter_case(
        model, study, components["Well"][0], model_kind, emit_event, "target"
    )
    is_legacy_frequency_contract = parameters["parameter"] == "operatingFrequency"
    case_parameter_key = "frequencyHz" if is_legacy_frequency_contract else "parameterValue"
    return {
        "schemaVersion": "pipesim-esp-parameter-result/1" if is_legacy_frequency_contract else "pipesim-esp-parameter-result/2",
        "model_kind": model_kind,
        "runTask": "esp-parameter",
        "resultContract": "VALID_FULL",
        "producer": components["Well"][0],
        "espContext": context,
        "parameter": parameters["parameter"],
        "unit": result_unit,
        "originalValue": float(original),
        "targetValue": target,
        "appliedValue": float(applied),
        "sourceModelUnchanged": True,
        "isolatedCopy": True,
        "baseline": {case_parameter_key: float(original), **baseline_case},
        "target": {case_parameter_key: float(applied), **target_profile},
        "comparison": {
            "maxIprPressureDelta": _max_aligned_result_delta(baseline_case["ipr"], target_profile["ipr"], "flow", "pressure"),
            "maxVlpPressureDelta": _max_aligned_result_delta(baseline_case["vlp"], target_profile["vlp"], "flow", "pressure"),
            "maxProfilePressureDelta": _max_aligned_result_delta(baseline_case["profile"], target_profile["profile"], "depth", "pressure"),
            "maxProfileTemperatureDelta": _max_aligned_result_delta(baseline_case["profile"], target_profile["profile"], "depth", "temperature"),
        },
    }


def _trajectory_number(value, field_name, allow_null=True):
    if value is None:
        if allow_null:
            return None
        raise AdapterFailure("PROTOCOL", "INVALID_TRAJECTORY_RESULT", "PIPESIM returned a null trajectory value for {0}.".format(field_name))
    cleaned = _clean_number(value)
    if cleaned is None:
        if allow_null:
            return None
        raise AdapterFailure("PROTOCOL", "INVALID_TRAJECTORY_RESULT", "PIPESIM returned a non-finite trajectory value for {0}.".format(field_name))
    return cleaned


def _trajectory_rows(trajectory):
    columns = {str(column) for column in getattr(trajectory, "columns", [])}
    required_columns = {"MeasuredDepth", "TrueVerticalDepth", "Inclination", "Azimuth", "MaxDogLegSeverity"}
    if not required_columns.issubset(columns):
        raise AdapterFailure("PROTOCOL", "INVALID_TRAJECTORY_RESULT", "PIPESIM trajectory is missing required columns.")
    try:
        raw_rows = trajectory.to_dict(orient="records")
    except Exception as exc:
        raise AdapterFailure("PROTOCOL", "INVALID_TRAJECTORY_RESULT", "PIPESIM trajectory rows could not be read.") from exc
    if not isinstance(raw_rows, list) or not 2 <= len(raw_rows) <= 4096:
        raise AdapterFailure("PROTOCOL", "INVALID_TRAJECTORY_RESULT", "PIPESIM returned an invalid number of trajectory points.")

    points = []
    previous_depth = None
    for row in raw_rows:
        if not isinstance(row, dict):
            raise AdapterFailure("PROTOCOL", "INVALID_TRAJECTORY_RESULT", "PIPESIM returned an invalid trajectory row.")
        measured_depth = _trajectory_number(row.get("MeasuredDepth"), "MeasuredDepth", allow_null=False)
        true_vertical_depth = _trajectory_number(row.get("TrueVerticalDepth"), "TrueVerticalDepth", allow_null=False)
        inclination = _trajectory_number(row.get("Inclination"), "Inclination", allow_null=False)
        azimuth = _trajectory_number(row.get("Azimuth"), "Azimuth")
        dogleg = _trajectory_number(row.get("MaxDogLegSeverity"), "MaxDogLegSeverity")
        if (measured_depth < 0 or true_vertical_depth < 0 or measured_depth < true_vertical_depth
                or inclination < 0 or inclination > 180
                or (azimuth is not None and (azimuth < -360 or azimuth > 360))
                or (dogleg is not None and (dogleg < -1000000 or dogleg > 1000000))):
            raise AdapterFailure("PROTOCOL", "INVALID_TRAJECTORY_RESULT", "PIPESIM returned an out-of-range trajectory point.")
        if previous_depth is not None and measured_depth <= previous_depth:
            raise AdapterFailure("PROTOCOL", "INVALID_TRAJECTORY_RESULT", "PIPESIM trajectory measured depths must increase.")
        previous_depth = measured_depth
        points.append({
            "measuredDepth": measured_depth,
            "trueVerticalDepth": true_vertical_depth,
            "inclination": inclination,
            "azimuth": azimuth,
            "maxDogLegSeverity": dogleg,
        })
    return points


def _trajectory_supported_p101_configuration(model, producer):
    from sixgill.definitions import Parameters, Units

    try:
        unit_system = model.about.unit_system
    except Exception as exc:
        raise AdapterFailure("MODEL", "TRAJECTORY_UNIT_SYSTEM_UNREADABLE", "The selected model's unit system cannot be verified safely.") from exc
    if unit_system != Units.FIELD:
        raise AdapterFailure("MODEL", "TRAJECTORY_EDIT_UNITS_UNSUPPORTED", "Trajectory editing currently requires the verified PIPESIM Field unit system.")

    expected = (
        (Parameters.Well.DeviationSurvey.SURVEYTYPE, "TwoDimensional"),
        (Parameters.Well.DeviationSurvey.RELATIVEDEPTHOPTION, "OriginalRKB"),
        (Parameters.Well.DeviationSurvey.DEPENDENTPARAMETER, "Angle"),
        (Parameters.Well.DeviationSurvey.CALCULATEUSINGTANGENTIALMETHOD, True),
    )
    try:
        actual = [(key, model.get_value(context=producer, parameter=key)) for key, _ in expected]
    except Exception as exc:
        raise AdapterFailure("MODEL", "TRAJECTORY_CONFIGURATION_UNREADABLE", "The selected well's survey configuration cannot be verified safely.") from exc
    for (key, value), (_, wanted) in zip(actual, expected):
        matches = value is wanted if isinstance(wanted, bool) else str(value) == wanted
        if not matches:
            raise AdapterFailure("MODEL", "TRAJECTORY_EDIT_CONFIGURATION_UNSUPPORTED", "Trajectory editing is currently enabled only for the verified 2D Angle/Tangential/OriginalRKB configuration.")


def _validate_p101_tangential_points(points):
    if len(points) < 3:
        raise AdapterFailure("MODEL", "TRAJECTORY_EDIT_UNSUPPORTED", "At least three stations are required to edit an interior trajectory station.")
    for point in points:
        if point["inclination"] > 90:
            raise AdapterFailure("MODEL", "TRAJECTORY_EDIT_UNSUPPORTED", "The verified trajectory editor accepts inclinations from 0 to 90 degrees only.")
    for index in range(1, len(points)):
        prior = points[index - 1]
        current = points[index]
        expected_tvd = prior["trueVerticalDepth"] + (current["measuredDepth"] - prior["measuredDepth"]) * math.cos(math.radians(prior["inclination"]))
        if (not math.isfinite(expected_tvd) or expected_tvd < 0 or expected_tvd > current["measuredDepth"]
                or abs(expected_tvd - current["trueVerticalDepth"]) > 1e-4):
            raise AdapterFailure("MODEL", "TRAJECTORY_GEOMETRY_INCONSISTENT", "The current trajectory does not satisfy the verified P101 Tangential geometry; no edit was applied.")


def _profile_points(model, producer, study, emit_event, phase_message):
    from sixgill.definitions import ProfileVariables, SystemVariables

    try:
        issues = model.tasks.ptprofilesimulation.validate(producer=producer, study=study)
    except Exception as exc:
        raise _classify_exception(exc, "TRAJECTORY_PROFILE_VALIDATION_FAILED")
    if issues:
        diagnostics = _validation_diagnostics(issues)
        raise AdapterFailure("VALIDATION", "PIPESIM_TASK_VALIDATION_FAILED", "PT Profile preflight found {0} issue(s).".format(len(issues)), diagnostics=diagnostics)
    emit_event("RUNNING_PROFILE", phase_message)
    try:
        result = model.tasks.ptprofilesimulation.run(
            producer=producer,
            study=study,
            profile_variables=[ProfileVariables.TEMPERATURE, ProfileVariables.PRESSURE,
                               ProfileVariables.ELEVATION, ProfileVariables.TOTAL_DISTANCE],
            system_variables=[SystemVariables.PRESSURE, SystemVariables.TEMPERATURE],
        )
    except Exception as exc:
        raise _classify_exception(exc, "TRAJECTORY_PROFILE_RUN_FAILED")
    if str(getattr(result, "state", "")) != "Completed":
        raise AdapterFailure("EXECUTION", "TRAJECTORY_PROFILE_NOT_COMPLETED", "PIPESIM PT Profile did not complete successfully.")
    points = normalize_profile(result.profile)
    if not points or len(points) > 4096 or any(
            _clean_number(point.get(field)) is None for point in points for field in ("depth", "pressure", "temperature")):
        raise AdapterFailure("PROTOCOL", "INVALID_TRAJECTORY_PROFILE_RESULT", "PIPESIM returned no complete finite PT Profile points.")
    return points


def _trajectory_profile_comparison(baseline, target):
    def exact_index(points):
        grouped = {}
        for point in points:
            depth = _clean_number(point.get("depth"))
            if depth is None:
                continue
            key = int(math.floor(depth * 1000000 + 0.5))
            grouped.setdefault(key, []).append(point)
        # Some official PT Profiles return multiple segment-boundary samples at
        # the same depth. Keep every raw sample, but only compare unambiguous
        # depths; choosing an arbitrary duplicate would fabricate a pairing.
        return {key: values[0] for key, values in grouped.items() if len(values) == 1}

    baseline_by_depth = exact_index(baseline)
    target_by_depth = exact_index(target)
    matches = []
    for depth_key in sorted(baseline_by_depth.keys() & target_by_depth.keys()):
        depth = depth_key / 1000000.0
        first, second = baseline_by_depth[depth_key], target_by_depth[depth_key]
        matches.append({
            "depth": depth,
            "baselinePressure": first["pressure"],
            "targetPressure": second["pressure"],
            "pressureDelta": second["pressure"] - first["pressure"],
            "baselineTemperature": first["temperature"],
            "targetTemperature": second["temperature"],
            "temperatureDelta": second["temperature"] - first["temperature"],
        })
    return {
        "method": "exact-depth-match-no-interpolation",
        "baselinePointCount": len(baseline),
        "targetPointCount": len(target),
        "matchedPointCount": len(matches),
        "baseline": baseline,
        "target": target,
        "matchedPoints": matches,
        "maxAbsPressureDelta": max((abs(point["pressureDelta"]) for point in matches), default=None),
        "maxAbsTemperatureDelta": max((abs(point["temperatureDelta"]) for point in matches), default=None),
    }


def _execute_trajectory_edit(model, model_factory, model_path, study, parameters, producer, model_kind, emit_event):
    _trajectory_supported_p101_configuration(model, producer)
    original_frame = model.get_trajectory(context=producer)
    original_points = _trajectory_rows(original_frame)
    _validate_p101_tangential_points(original_points)
    station_index = parameters["stationIndex"]
    if station_index >= len(original_points) - 1:
        raise AdapterFailure("PROTOCOL", "TRAJECTORY_STATION_OUT_OF_RANGE", "Only an interior trajectory station can be edited.")
    original_inclination = original_points[station_index]["inclination"]
    target_inclination = parameters["targetInclinationDeg"]
    if math.isclose(original_inclination, target_inclination, rel_tol=0, abs_tol=1e-9):
        raise AdapterFailure("PROTOCOL", "TRAJECTORY_EDIT_NO_CHANGE", "The target inclination must differ from the current value.")

    baseline_profile = _profile_points(model, producer, study, emit_event, "Running baseline PIPESIM PT Profile.")
    edited_frame = original_frame.copy(deep=True)
    edited_frame.at[station_index, "Inclination"] = target_inclination
    edited_points = [dict(point) for point in original_points]
    edited_points[station_index]["inclination"] = target_inclination
    for index in range(station_index + 1, len(edited_points)):
        previous = edited_points[index - 1]
        current = edited_points[index]
        current["trueVerticalDepth"] = previous["trueVerticalDepth"] + (current["measuredDepth"] - previous["measuredDepth"]) * math.cos(math.radians(previous["inclination"]))
        if (not math.isfinite(current["trueVerticalDepth"]) or current["trueVerticalDepth"] < 0
                or current["trueVerticalDepth"] > current["measuredDepth"]
                or current["trueVerticalDepth"] < previous["trueVerticalDepth"] - 1e-9):
            raise AdapterFailure("PROTOCOL", "TRAJECTORY_EDIT_GEOMETRY_INVALID", "The requested inclination produces an invalid downstream TVD geometry.")
        edited_frame.at[index, "TrueVerticalDepth"] = current["trueVerticalDepth"]

    emit_event("APPLYING_TRAJECTORY", "Applying the validated inclination edit to the isolated model copy.")
    try:
        model.set_trajectory(context=producer, value=edited_frame)
        model.save()
    except Exception as exc:
        raise _classify_exception(exc, "TRAJECTORY_EDIT_SAVE_FAILED")
    try:
        model.close()
    except Exception:
        pass
    try:
        reopened_model = model_factory(model_path)
    except Exception as exc:
        raise _classify_exception(exc, "TRAJECTORY_EDIT_REOPEN_FAILED")

    try:
        _trajectory_supported_p101_configuration(reopened_model, producer)
        saved_points = _trajectory_rows(reopened_model.get_trajectory(context=producer))
        if len(saved_points) != len(edited_points):
            raise AdapterFailure("PROTOCOL", "TRAJECTORY_EDIT_READBACK_MISMATCH", "The saved trajectory station count changed after reopening.")
        for index, (expected, actual) in enumerate(zip(edited_points, saved_points)):
            for key in ("measuredDepth", "trueVerticalDepth", "inclination"):
                if not math.isclose(expected[key], actual[key], rel_tol=1e-9, abs_tol=1e-6):
                    raise AdapterFailure("PROTOCOL", "TRAJECTORY_EDIT_READBACK_MISMATCH", "The saved trajectory failed point-by-point readback verification.")
            for key in ("azimuth", "maxDogLegSeverity"):
                if expected[key] is None and actual[key] is None:
                    continue
                if expected[key] is None or actual[key] is None or not math.isclose(expected[key], actual[key], rel_tol=1e-9, abs_tol=1e-6):
                    raise AdapterFailure("PROTOCOL", "TRAJECTORY_EDIT_READBACK_MISMATCH", "An unedited trajectory field changed after saving.")
        _validate_p101_tangential_points(saved_points)
        target_profile = _profile_points(reopened_model, producer, study, emit_event, "Running the edited-model PIPESIM PT Profile.")
        comparison = _trajectory_profile_comparison(baseline_profile, target_profile)
        max_tvd_before = original_points[-1]["trueVerticalDepth"]
        max_tvd_after = saved_points[-1]["trueVerticalDepth"]
        trajectory = {
            "schemaVersion": "pipesim-well-trajectory-result/2",
            "model_kind": model_kind,
            "runTask": "trajectory",
            "resultContract": "VALID_FULL",
            "producer": producer,
            "units": {"measuredDepth": "ft", "trueVerticalDepth": "ft", "inclination": "deg", "azimuth": "deg", "maxDogLegSeverity": "deg/100ft"},
            "points": saved_points,
            "application": {
                "applied": True,
                "isolatedCopy": True,
                "sourceModelUnchanged": True,
                "artifactName": "pipesim-trajectory-applied.pips",
                "stationIndex": station_index,
                "originalInclinationDeg": original_inclination,
                "targetInclinationDeg": target_inclination,
                "maxTvdBeforeFt": max_tvd_before,
                "maxTvdAfterFt": max_tvd_after,
            },
            "profileComparison": comparison,
        }
        return trajectory, reopened_model
    except Exception:
        try:
            reopened_model.close()
        except Exception:
            pass
        raise


def _execute_trajectory(model, components, model_kind, emit_event):
    if model_kind not in ("black_oil_liquid", "basic_gas", "legacy_well"):
        raise AdapterFailure("MODEL", "UNSUPPORTED_MODEL", "Well trajectory is supported only for PIPESIM well models.")
    producer = components["Well"][0]
    emit_event("READING_TRAJECTORY", "Reading the official PIPESIM well trajectory.")
    try:
        trajectory = model.get_trajectory(context=producer)
    except Exception as exc:
        failure = _classify_exception(exc, "TRAJECTORY_READ_FAILED")
        if failure.category == "EXECUTION":
            failure = AdapterFailure("MODEL", "TRAJECTORY_UNAVAILABLE", "The selected well has no readable PIPESIM trajectory.")
        raise failure

    points = _trajectory_rows(trajectory)
    return {
        "schemaVersion": "pipesim-well-trajectory-result/1",
        "model_kind": model_kind,
        "runTask": "trajectory",
        "resultContract": "VALID_FULL",
        "producer": producer,
        "units": {
            "measuredDepth": "ft",
            "trueVerticalDepth": "ft",
            "inclination": "deg",
            "azimuth": "deg",
            "maxDogLegSeverity": "deg/100ft",
        },
        "points": points,
    }


def _network_parameters(parameters):
    if parameters is None:
        return None
    allowed = {
        "node", "pressure", "temperature", "flowRateType",
        "gasFlowRate", "liquidFlowRate", "massFlowRate",
    }
    if (not isinstance(parameters, dict)
            or set(parameters) != {"schemaVersion", "boundaries"}
            or parameters.get("schemaVersion") != "pipesim-network-parameters/1"
            or not isinstance(parameters.get("boundaries"), list)
            or not 1 <= len(parameters["boundaries"]) <= 64):
        raise AdapterFailure("PROTOCOL", "INVALID_NETWORK_PARAMETERS", "Network parameters are invalid.")
    seen = set()
    rate_fields = {"GasFlowRate": "gasFlowRate", "LiquidFlowRate": "liquidFlowRate", "MassFlowRate": "massFlowRate"}
    for boundary in parameters["boundaries"]:
        if not isinstance(boundary, dict) or not set(boundary).issubset(allowed) or "node" not in boundary:
            raise AdapterFailure("PROTOCOL", "INVALID_NETWORK_PARAMETERS", "Network boundary fields are invalid.")
        node = boundary["node"]
        if (not isinstance(node, str) or not node.strip() or len(node) > 255
                or any(character in node for character in ("/", "\\", "..", "\x00"))):
            raise AdapterFailure("PROTOCOL", "INVALID_NETWORK_PARAMETERS", "Network boundary node is invalid.")
        if node in seen:
            raise AdapterFailure("PROTOCOL", "INVALID_NETWORK_PARAMETERS", "Network boundary nodes must be unique.")
        seen.add(node)
        for field, lower, upper in (
            ("pressure", 0, 100000),
            ("temperature", -1000, 100000),
            ("gasFlowRate", 0, 1000000000),
            ("liquidFlowRate", 0, 1000000000),
            ("massFlowRate", 0, 1000000000),
        ):
            if field in boundary:
                value = boundary[field]
                if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or not lower < value <= upper:
                    raise AdapterFailure("PROTOCOL", "INVALID_NETWORK_PARAMETERS", "Network numeric parameters are invalid.")
        if "flowRateType" in boundary:
            flow_type = boundary["flowRateType"]
            if flow_type not in rate_fields or rate_fields[flow_type] not in boundary:
                raise AdapterFailure("PROTOCOL", "INVALID_NETWORK_PARAMETERS", "Network flow rate type must match its rate value.")
    return parameters


def _network_choke_bean_size_parameters(parameters):
    if (not isinstance(parameters, dict)
            or set(parameters) != {"schemaVersion", "baselineRunId", "choke", "originalBeanSize", "targetBeanSize"}
            or parameters.get("schemaVersion") != "pipesim-network-choke-bean-size-parameters/1"
            or isinstance(parameters.get("baselineRunId"), bool)
            or not isinstance(parameters.get("baselineRunId"), int)
            or parameters.get("baselineRunId") <= 0
            or not isinstance(parameters.get("choke"), str)
            or not parameters.get("choke").strip()
            or len(parameters.get("choke")) > 255
            or any(character in parameters.get("choke") for character in ("/", "\\", "..", "\x00"))):
        raise AdapterFailure("PROTOCOL", "INVALID_NETWORK_CHOKE_BEAN_SIZE_PARAMETERS", "Network Choke Bean Size parameters are invalid.")
    for field in ("originalBeanSize", "targetBeanSize"):
        value = parameters.get(field)
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value <= 0 or value > 1000000:
            raise AdapterFailure("PROTOCOL", "INVALID_NETWORK_CHOKE_BEAN_SIZE_PARAMETERS", "Network Choke Bean Size values must be positive finite numbers.")
    if abs(parameters["targetBeanSize"] - parameters["originalBeanSize"]) <= max(1e-12, abs(parameters["originalBeanSize"]) * 1e-12):
        raise AdapterFailure("PROTOCOL", "INVALID_NETWORK_CHOKE_BEAN_SIZE_PARAMETERS", "The target Choke Bean Size must differ from the original value.")
    return parameters


def _network_flowline_diameter_parameters(parameters):
    fields = {"schemaVersion", "baselineRunId", "flowline", "originalInnerDiameter", "unit", "targetInnerDiameter"}
    if (not isinstance(parameters, dict) or set(parameters) != fields
            or parameters.get("schemaVersion") != "pipesim-network-flowline-diameter-parameters/1"
            or isinstance(parameters.get("baselineRunId"), bool)
            or not isinstance(parameters.get("baselineRunId"), int) or parameters["baselineRunId"] <= 0
            or not isinstance(parameters.get("flowline"), str) or not parameters["flowline"].strip()
            or len(parameters["flowline"]) > 255
            or any(character in parameters["flowline"] for character in ("/", "\\", "..", "\x00"))
            or not isinstance(parameters.get("unit"), str) or not parameters["unit"].strip() or len(parameters["unit"]) > 32):
        raise AdapterFailure("PROTOCOL", "INVALID_NETWORK_FLOWLINE_DIAMETER_PARAMETERS", "Network Flowline diameter parameters are invalid.")
    for field in ("originalInnerDiameter", "targetInnerDiameter"):
        value = parameters.get(field)
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value <= 0 or value > 1000:
            raise AdapterFailure("PROTOCOL", "INVALID_NETWORK_FLOWLINE_DIAMETER_PARAMETERS", "Flowline inner diameters must be positive finite values within the supported input guard.")
    if abs(parameters["targetInnerDiameter"] - parameters["originalInnerDiameter"]) <= max(1e-12, abs(parameters["originalInnerDiameter"]) * 1e-12):
        raise AdapterFailure("PROTOCOL", "INVALID_NETWORK_FLOWLINE_DIAMETER_PARAMETERS", "The target Flowline inner diameter must differ from the original value.")
    return parameters


def _well_tubing_diameter_parameters(parameters):
    fields = {"schemaVersion", "baselineRunId", "wellContext", "tubingContext",
              "originalInnerDiameter", "unit", "targetInnerDiameter"}
    if (not isinstance(parameters, dict) or set(parameters) != fields
            or parameters.get("schemaVersion") != "pipesim-well-tubing-diameter-parameters/1"
            or isinstance(parameters.get("baselineRunId"), bool)
            or not isinstance(parameters.get("baselineRunId"), int) or parameters["baselineRunId"] <= 0
            or not isinstance(parameters.get("wellContext"), str) or not parameters["wellContext"].strip()
            or not isinstance(parameters.get("tubingContext"), str) or not parameters["tubingContext"].strip()
            or any(any(character in parameters[name] for character in ("/", "\\", "..", "\x00"))
                   for name in ("wellContext", "tubingContext"))
            or any(len(parameters[name]) > 255 or any(ord(character) < 32 or ord(character) == 127 for character in parameters[name])
                   for name in ("wellContext", "tubingContext"))
            or not isinstance(parameters.get("unit"), str) or not parameters["unit"].strip()
            or len(parameters["unit"]) > 32
            or any(ord(character) < 32 or ord(character) == 127 for character in parameters["unit"])):
        raise AdapterFailure("PROTOCOL", "INVALID_TUBING_DIAMETER_PARAMETERS", "The inspected well and tubing diameter scheme is invalid.")
    for field in ("originalInnerDiameter", "targetInnerDiameter"):
        value = parameters.get(field)
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or not 0 < value <= 1000:
            raise AdapterFailure("PROTOCOL", "INVALID_TUBING_DIAMETER_PARAMETERS", "Tubing inner diameters must be positive finite values within the supported input guard.")
    if math.isclose(parameters["originalInnerDiameter"], parameters["targetInnerDiameter"], rel_tol=1e-12, abs_tol=1e-12):
        raise AdapterFailure("PROTOCOL", "INVALID_TUBING_DIAMETER_PARAMETERS", "The target Tubing inner diameter must differ from the inspected original value.")
    return parameters


def _execute_well_tubing_diameter_profile(model, model_factory, model_path, study, components, model_kind, parameters, emit_event):
    parameters = _well_tubing_diameter_parameters(parameters)
    from sixgill.definitions import Parameters

    well = parameters["wellContext"]
    tubing = parameters["tubingContext"]
    if well not in list(model.find(component="Well")) or tubing not in list(model.find(component="Tubing", Well=well)):
        raise AdapterFailure("MODEL", "TUBING_CONTEXT_NOT_FOUND", "The selected Tubing is not associated with the selected well.")
    try:
        original = float(model.get_value(context=tubing, parameter=Parameters.Tubing.INNERDIAMETER))
        description = model.describe(context=tubing, parameter=Parameters.Tubing.INNERDIAMETER)
        unit = str(getattr(description, "units_symbol", "") or "").strip()
    except Exception:
        raise AdapterFailure("MODEL", "TUBING_DIAMETER_UNAVAILABLE", "The selected Tubing inner diameter or unit could not be read.")
    if (not math.isfinite(original) or original <= 0 or original > 1000
            or not math.isclose(original, parameters["originalInnerDiameter"], rel_tol=1e-9, abs_tol=1e-9)
            or unit != parameters["unit"]):
        raise AdapterFailure("MODEL", "TUBING_DIAMETER_ORIGINAL_VALUE_MISMATCH", "The Tubing inner diameter or unit no longer matches the inspected original value.")

    emit_event("RUNNING_PROFILE", "Applying the Tubing inner diameter to the isolated model copy.")
    try:
        model.set_value(context=tubing, parameter=Parameters.Tubing.INNERDIAMETER, value=parameters["targetInnerDiameter"])
        applied = float(model.get_value(context=tubing, parameter=Parameters.Tubing.INNERDIAMETER))
    except Exception:
        raise AdapterFailure("MODEL", "TUBING_DIAMETER_WRITE_FAILED", "PIPESIM could not apply the selected Tubing inner diameter.")
    if not math.isfinite(applied) or not math.isclose(applied, parameters["targetInnerDiameter"], rel_tol=1e-9, abs_tol=1e-9):
        raise AdapterFailure("MODEL", "TUBING_DIAMETER_READBACK_FAILED", "The changed Tubing inner diameter failed immediate readback.")

    try:
        model.save()
        model.close()
        model = model_factory(model_path)
        if study not in _study_names(model):
            raise AdapterFailure("MODEL", "TUBING_DIAMETER_STUDY_REOPEN_FAILED", "The selected Study was not present after reopening the derived model.")
        if well not in list(model.find(component="Well")) or tubing not in list(model.find(component="Tubing", Well=well)):
            raise AdapterFailure("MODEL", "TUBING_CONTEXT_REOPEN_FAILED", "The selected Tubing context was not present after reopening the derived model.")
        reopened = float(model.get_value(context=tubing, parameter=Parameters.Tubing.INNERDIAMETER))
        reopened_description = model.describe(context=tubing, parameter=Parameters.Tubing.INNERDIAMETER)
        reopened_unit = str(getattr(reopened_description, "units_symbol", "") or "").strip()
    except AdapterFailure:
        raise
    except Exception:
        raise AdapterFailure("MODEL", "TUBING_DIAMETER_SAVE_REOPEN_FAILED", "The derived PIPESIM model could not be reopened and checked.")
    if (not math.isfinite(reopened) or not math.isclose(reopened, parameters["targetInnerDiameter"], rel_tol=1e-9, abs_tol=1e-9)
            or reopened_unit != unit):
        raise AdapterFailure("MODEL", "TUBING_DIAMETER_REOPEN_READBACK_FAILED", "The saved Tubing inner diameter failed reopened-model readback.")

    _preflight_well_task(model.tasks.ptprofilesimulation, well, study)
    emit_event("RUNNING_PROFILE", "Running the selected Study Profile with the persisted Tubing change.")
    try:
        profile_result = model.tasks.ptprofilesimulation.run(
            producer=well, study=study, **_well_output_variables())
        profile = normalize_profile(profile_result.profile)
    except Exception as exc:
        raise _classify_exception(exc, "PROFILE_RUN_FAILED")
    if not profile:
        raise AdapterFailure("EXECUTION", "EMPTY_PROFILE_RESULT", "PIPESIM returned no usable Profile points after the Tubing change.")
    result = _result_document(model_kind, "profile", [], [], profile)
    result["schemaVersion"] = "pipesim-well-result/2"
    result["application"] = {
        "applied": True,
        "isolatedCopy": True,
        "sourceModelUnchanged": True,
        "artifactName": "pipesim-well-tubing-applied.pips",
        "wellContext": well,
        "tubingContext": tubing,
        "unit": unit,
        "originalInnerDiameter": original,
        "targetInnerDiameter": parameters["targetInnerDiameter"],
        "appliedInnerDiameter": reopened,
    }
    return result, model


def _network_connection_parameters(parameters):
    fields = {"schemaVersion", "baselineRunId", "source", "sourcePort", "originalDestination", "newDestination"}
    if (not isinstance(parameters, dict) or set(parameters) != fields
            or parameters.get("schemaVersion") != "pipesim-network-connection-parameters/1"
            or isinstance(parameters.get("baselineRunId"), bool)
            or not isinstance(parameters.get("baselineRunId"), int) or parameters["baselineRunId"] <= 0):
        raise AdapterFailure("PROTOCOL", "INVALID_NETWORK_CONNECTION_PARAMETERS", "Network connection parameters are invalid.")
    for field in ("source", "originalDestination", "newDestination"):
        value = parameters.get(field)
        if (not isinstance(value, str) or not value.strip() or len(value) > 255
                or any(character in value for character in ("/", "\\", "..", "://", "\x00"))
                or any(ord(character) < 32 or ord(character) == 127 for character in value)):
            raise AdapterFailure("PROTOCOL", "INVALID_NETWORK_CONNECTION_PARAMETERS", "Network connection node names are invalid.")
    port = parameters.get("sourcePort")
    if not isinstance(port, str) or len(port) > 64 or any(ord(character) < 32 or ord(character) == 127 for character in port):
        raise AdapterFailure("PROTOCOL", "INVALID_NETWORK_CONNECTION_PARAMETERS", "Network source port is invalid.")
    if port not in ("", "Top Port", "Bottom Port"):
        raise AdapterFailure("PROTOCOL", "INVALID_NETWORK_CONNECTION_PARAMETERS", "Only inspected separator outlet ports are supported.")
    if len({parameters["source"], parameters["originalDestination"], parameters["newDestination"]}) != 3:
        raise AdapterFailure("PROTOCOL", "INVALID_NETWORK_CONNECTION_PARAMETERS", "The source, current destination and new destination must be distinct.")
    return parameters


def _reachable_nodes(topology):
    nodes = {item["id"]: item["componentType"] for item in topology["nodes"]}
    roots = {name for name, kind in nodes.items() if kind in ("Source", "Well", "Injector")}
    reachable = set(roots)
    edges = [(edge["source"], edge["destination"]) for edge in topology["edges"]]
    changed = True
    while changed:
        changed = False
        for source, destination in edges:
            if source in reachable and destination not in reachable:
                reachable.add(destination)
                changed = True
    return {name for name, kind in nodes.items() if kind == "Sink" and name in reachable}


def _execute_network_connection(model, model_factory, model_path, study, parameters, emit_event):
    parameters = _network_connection_parameters(parameters)
    from sixgill.definitions import Connection, ModelComponents

    source = parameters["source"]
    old_destination = parameters["originalDestination"]
    new_destination = parameters["newDestination"]
    source_port = parameters["sourcePort"]
    before = extract_topology(model)
    node_types = {item["id"]: item["componentType"] for item in before["nodes"]}
    source_type = node_types.get(source)
    old_type = node_types.get(old_destination)
    new_type = node_types.get(new_destination)
    if not source_type or not old_type or not new_type or "Unknown" in (source_type, old_type, new_type):
        raise AdapterFailure("MODEL", "NETWORK_CONNECTION_NODE_NOT_FOUND", "All selected connection nodes must exist in the inspected Network topology.")

    separators = ("TwoPhaseSeparator", "ThreePhaseSeparator")
    if source_type in separators:
        port_values = {"Top Port": Connection.Separator.TOP, "Bottom Port": Connection.Separator.BOTTOM}
        if source_port not in port_values:
            raise AdapterFailure("MODEL", "NETWORK_SEPARATOR_PORT_REQUIRED", "A separator connection must use its inspected Top Port or Bottom Port.")
        api_port = port_values[source_port]
    else:
        if source_port:
            raise AdapterFailure("MODEL", "NETWORK_SOURCE_PORT_UNSUPPORTED", "This source component does not support the selected source port in the current connection adapter.")
        api_port = None

    edge_tuple = (source, source_port, old_destination)
    edges_before = [(edge["source"], edge["sourcePort"], edge["destination"]) for edge in before["edges"]]
    if edges_before.count(edge_tuple) != 1 or sum(
            edge["source"] == source and edge["destination"] == old_destination for edge in before["edges"]) != 1:
        raise AdapterFailure("MODEL", "NETWORK_CONNECTION_STALE", "The selected existing connection is missing or ambiguous in the current isolated model.")
    if old_type == "Flowline" and sum(edge["destination"] == old_destination for edge in before["edges"]) == 1:
        raise AdapterFailure(
            "MODEL", "NETWORK_CONNECTION_FLOWLINE_INLET_REQUIRED",
            "Redirecting this edge would leave the original Flowline inlet unconnected (PIPESIM ConnectionsBoth).")
    if any(edge["source"] == source and edge["destination"] == new_destination for edge in before["edges"]):
        raise AdapterFailure("MODEL", "NETWORK_CONNECTION_ALREADY_EXISTS", "The requested replacement connection already exists.")
    if new_type == "Source" or (new_type != "Junction" and
                                 any(edge["destination"] == new_destination for edge in before["edges"])):
        raise AdapterFailure(
            "MODEL", "NETWORK_CONNECTION_TARGET_INLET_OCCUPIED",
            "The selected target has no available inlet; only an unoccupied inlet or a multi-inlet Junction can accept this connection.")
    reachable_before = _reachable_nodes(before)

    source_enum = getattr(ModelComponents, source_type.upper(), None)
    old_enum = getattr(ModelComponents, old_type.upper(), None)
    new_enum = getattr(ModelComponents, new_type.upper(), None)
    if source_enum is None or old_enum is None or new_enum is None:
        raise AdapterFailure("MODEL", "NETWORK_CONNECTION_COMPONENT_UNSUPPORTED", "A selected Network component type is not supported by the official Toolkit connection API.")
    source_context = {source_enum: source}
    old_context = {old_enum: old_destination}
    new_context = {new_enum: new_destination}
    failure_stage = "disconnect"
    try:
        if api_port is None:
            model.disconnect(source=source_context, destination=old_context)
        else:
            model.disconnect(source=source_context, port=api_port)
        failure_stage = "connect"
        if api_port is None:
            model.connect(source_context, new_context)
        else:
            model.connect(source_context, new_context, source_port=api_port)
        failure_stage = "save"
        model.save()
        failure_stage = "close"
        model.close()
        failure_stage = "reopen"
        model = model_factory(model_path)
        if study not in _study_names(model):
            raise AdapterFailure("MODEL", "NETWORK_CONNECTION_STUDY_REOPEN_FAILED", "The selected Study was not found after saving and reopening the derived model.")
        failure_stage = "topology-readback"
        after = extract_topology(model)
    except AdapterFailure:
        raise
    except Exception as exc:
        diagnostic = {
            "path": "network connection",
            "property": failure_stage,
            "message": "{0}: {1}".format(type(exc).__name__, _safe_exception_diagnostic(exc)),
        }
        raise AdapterFailure(
            "MODEL", "NETWORK_CONNECTION_APPLY_FAILED",
            "PIPESIM could not complete the network connection {0} step.".format(failure_stage),
            diagnostics=[diagnostic]) from exc

    expected_edges = list(edges_before)
    expected_edges.remove(edge_tuple)
    expected_edges.append((source, source_port, new_destination))
    actual_edges = [(edge["source"], edge["sourcePort"], edge["destination"]) for edge in after["edges"]]
    if sorted(actual_edges) != sorted(expected_edges):
        raise AdapterFailure("MODEL", "NETWORK_CONNECTION_READBACK_FAILED", "The reopened Network topology did not match the one-edge replacement exactly.")
    reachable_after = _reachable_nodes(after)
    if not reachable_before.issubset(reachable_after):
        raise AdapterFailure("MODEL", "NETWORK_CONNECTION_DISCONNECTS_SINK", "The replacement would disconnect one or more previously reachable Network sinks.")

    result, warnings = _execute_network(model, study, None, emit_event)
    if result.get("resultContract") != "VALID_FULL":
        raise AdapterFailure("EXECUTION", "NETWORK_CONNECTION_RESULT_INCOMPLETE", "The changed Network topology did not produce a complete validated result.")
    result["messages"] = list(result.get("messages", [])) + [
        "Reconnected {0} [{1}] from {2} to {3}; reopened and recalculated Study {4}.".format(
            source, source_port or "default outlet", old_destination, new_destination, study)
    ]
    return result, warnings, model


def _execute_network_flowline_diameter(model, model_factory, model_path, study, parameters, emit_event):
    parameters = _network_flowline_diameter_parameters(parameters)
    from sixgill.definitions import Parameters

    flowline = parameters["flowline"]
    if flowline not in list(model.find(component="Flowline")):
        raise AdapterFailure("MODEL", "NETWORK_FLOWLINE_NOT_FOUND", "The selected Network Flowline was not found in the model.")
    try:
        original = float(model.get_value(context=flowline, parameter=Parameters.Flowline.INNERDIAMETER))
        description = model.describe(context=flowline, parameter=Parameters.Flowline.INNERDIAMETER)
        unit = str(getattr(description, "units_symbol", "") or "").strip()
    except Exception:
        raise AdapterFailure("MODEL", "NETWORK_FLOWLINE_DIAMETER_UNAVAILABLE", "The selected Flowline inner diameter or unit could not be read.")
    if (not math.isfinite(original) or original <= 0 or original > 1000
            or abs(original - parameters["originalInnerDiameter"]) > max(1e-9, abs(original) * 1e-9)
            or unit != parameters["unit"]):
        raise AdapterFailure("MODEL", "NETWORK_FLOWLINE_ORIGINAL_VALUE_MISMATCH", "The selected Flowline inner diameter or unit no longer matches the inspected original value.")
    try:
        model.set_value(context=flowline, parameter=Parameters.Flowline.INNERDIAMETER,
                        value=parameters["targetInnerDiameter"])
        applied = float(model.get_value(context=flowline, parameter=Parameters.Flowline.INNERDIAMETER))
    except Exception:
        raise AdapterFailure("MODEL", "NETWORK_FLOWLINE_DIAMETER_WRITE_FAILED", "The selected Flowline inner diameter could not be changed in the isolated model copy.")
    if not math.isfinite(applied) or abs(applied - parameters["targetInnerDiameter"]) > max(1e-9, abs(parameters["targetInnerDiameter"]) * 1e-9):
        raise AdapterFailure("MODEL", "NETWORK_FLOWLINE_DIAMETER_READBACK_FAILED", "The changed Flowline inner diameter did not pass readback verification.")
    try:
        model.save()
        model.close()
        model = model_factory(model_path)
        if study not in _study_names(model):
            raise AdapterFailure("MODEL", "NETWORK_FLOWLINE_STUDY_REOPEN_FAILED", "The selected Study was not found after saving and reopening the derived model.")
        reopened = float(model.get_value(context=flowline, parameter=Parameters.Flowline.INNERDIAMETER))
        reopened_description = model.describe(context=flowline, parameter=Parameters.Flowline.INNERDIAMETER)
        reopened_unit = str(getattr(reopened_description, "units_symbol", "") or "").strip()
    except AdapterFailure:
        raise
    except Exception:
        raise AdapterFailure("MODEL", "NETWORK_FLOWLINE_SAVE_REOPEN_FAILED", "The Flowline scheme could not be saved and reopened in the isolated model copy.")
    if (not math.isfinite(reopened) or abs(reopened - parameters["targetInnerDiameter"]) > max(1e-9, abs(parameters["targetInnerDiameter"]) * 1e-9)
            or reopened_unit != unit):
        raise AdapterFailure("MODEL", "NETWORK_FLOWLINE_REOPEN_READBACK_FAILED", "The saved Flowline inner diameter did not pass reopened-model readback verification.")
    result, warnings = _execute_network(model, study, None, emit_event)
    audit = "Saved Network Flowline {0} InnerDiameter {1}->{2} {3}; reopened and recalculated Study {4}.".format(
        flowline, _clean_number(original), _clean_number(reopened), unit, study)
    result["messages"] = list(result.get("messages", [])) + [audit]
    return result, warnings, model


def _execute_network_choke_bean_size(model, study, parameters, emit_event):
    parameters = _network_choke_bean_size_parameters(parameters)
    from sixgill.definitions import Parameters

    choke = parameters["choke"]
    if choke not in list(model.find(component="Choke")):
        raise AdapterFailure("MODEL", "NETWORK_CHOKE_NOT_FOUND", "The selected Network Choke was not found in the model.")
    try:
        original = float(model.get_value(context=choke, parameter=Parameters.Choke.BEANSIZE))
    except Exception:
        raise AdapterFailure("MODEL", "NETWORK_CHOKE_BEAN_SIZE_UNAVAILABLE", "The selected Network Choke Bean Size could not be read.")
    if not math.isfinite(original) or original <= 0 or abs(original - parameters["originalBeanSize"]) > max(1e-12, abs(original) * 1e-12):
        raise AdapterFailure("MODEL", "NETWORK_CHOKE_ORIGINAL_VALUE_MISMATCH", "The selected Network Choke Bean Size no longer matches the inspected original value.")
    try:
        model.set_value(Choke=choke, parameter=Parameters.Choke.BEANSIZE, value=parameters["targetBeanSize"])
        after = float(model.get_value(context=choke, parameter=Parameters.Choke.BEANSIZE))
    except Exception:
        raise AdapterFailure("MODEL", "NETWORK_CHOKE_BEAN_SIZE_WRITE_FAILED", "The selected Network Choke Bean Size could not be changed in the isolated model copy.")
    if not math.isfinite(after) or abs(after - parameters["targetBeanSize"]) > max(1e-9, abs(parameters["targetBeanSize"]) * 1e-9):
        raise AdapterFailure("MODEL", "NETWORK_CHOKE_BEAN_SIZE_READBACK_FAILED", "The changed Network Choke Bean Size did not pass readback verification.")
    result, warnings = _execute_network(model, study, None, emit_event)
    audit = "Applied Network Choke BeanSize {0} {1}->{2} in.".format(choke, _clean_number(original), _clean_number(after))
    result["messages"] = list(result.get("messages", [])) + [audit]
    return result, warnings


def _network_boundary_overrides(model, study, parameters):
    parameters = _network_parameters(parameters)
    if parameters is None:
        return None
    try:
        conditions = model.tasks.networksimulation.get_conditions(study=study)
    except Exception:
        raise AdapterFailure("MODEL", "NETWORK_CONDITIONS_UNAVAILABLE", "The selected Network Study conditions could not be read.")
    if not isinstance(conditions, dict):
        raise AdapterFailure("MODEL", "NETWORK_CONDITIONS_UNAVAILABLE", "The selected Network Study conditions are invalid.")
    overrides = {}
    key_map = {
        "pressure": "Pressure",
        "temperature": "Temperature",
        "flowRateType": "FlowRateType",
        "gasFlowRate": "GasFlowRate",
        "liquidFlowRate": "LiquidFlowRate",
        "massFlowRate": "MassFlowRate",
    }
    for boundary in parameters["boundaries"]:
        node = boundary["node"]
        if node not in conditions:
            raise AdapterFailure("MODEL", "NETWORK_BOUNDARY_NOT_FOUND", "The selected Network boundary node was not found in the Study.")
        overrides[node] = {
            key_map[field]: value
            for field, value in boundary.items()
            if field in key_map
        }
    return overrides


def _sensitivity_parameters(parameters):
    if (not isinstance(parameters, dict)
            or set(parameters) != {"schemaVersion", "targetVariable", "values"}
            or parameters.get("schemaVersion") != "pipesim-well-sensitivity-parameters/1"
            or parameters.get("targetVariable") not in ("reservoirPressure", "waterCut", "gor", "tubingInnerDiameter")
            or not isinstance(parameters.get("values"), list)
            or not 2 <= len(parameters["values"]) <= 12):
        raise AdapterFailure("PROTOCOL", "INVALID_SENSITIVITY_PARAMETERS", "Sensitivity parameters are invalid.")
    previous = None
    for value in parameters["values"]:
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value <= 0:
            raise AdapterFailure("PROTOCOL", "INVALID_SENSITIVITY_PARAMETERS", "Sensitivity values must be positive finite numbers.")
        if previous is not None and value <= previous:
            raise AdapterFailure("PROTOCOL", "INVALID_SENSITIVITY_PARAMETERS", "Sensitivity values must be strictly increasing.")
        previous = value
    limits = {
        "reservoirPressure": 100000,
        "waterCut": 100,
        "gor": 1000000,
        "tubingInnerDiameter": 100,
    }
    if previous > limits[parameters["targetVariable"]]:
        raise AdapterFailure("PROTOCOL", "INVALID_SENSITIVITY_PARAMETERS", "Sensitivity values exceed the supported range.")


def _set_sensitivity_value(model, components, model_kind, target, value):
    from sixgill.definitions import Parameters

    if target == "reservoirPressure":
        for completion in components["Completion"]:
            model.set_value(completion, parameter=Parameters.Completion.RESERVOIRPRESSURE, value=value)
        return
    if target == "tubingInnerDiameter":
        for tubing in components["Tubing"]:
            model.set_value(tubing, parameter=Parameters.Tubing.INNERDIAMETER, value=value)
        return
    if model_kind != "black_oil_liquid":
        raise AdapterFailure("MODEL", "SENSITIVITY_VARIABLE_UNSUPPORTED", "Water cut and GOR sensitivity require a black-oil liquid model.")
    parameter = Parameters.BlackOilFluid.WATERCUT if target == "waterCut" else Parameters.BlackOilFluid.GOR
    for fluid in components["BlackOilFluid"]:
        model.set_value(fluid, parameter=parameter, value=value)


def _sensitivity_inlet_conditions(model, study, components, target, value):
    """Build per-case nodal conditions without changing the uploaded model.

    PIPESIM stores a production completion's pressure and phase ratios in the
    Nodal Analysis inlet boundary.  Updating only the Completion/Fluid object
    can therefore leave the Study boundary unchanged during a run.  Reusing
    the Study conditions and overriding the selected boundary value makes the
    case value effective while keeping the isolated model untouched.
    """
    nodal_task = model.tasks.nodalanalysis
    get_conditions = getattr(nodal_task, "get_conditions", None)
    if not callable(get_conditions):
        return None, None

    try:
        general_conditions, source_inlet_conditions = get_conditions(
            producer=components["Well"][0],
            study=study,
        )
        inlet_conditions = copy.deepcopy(source_inlet_conditions)
    except Exception:
        # Older Toolkit builds may not expose Study conditions. The normal
        # nodal call remains the compatible fallback for those installations.
        return None, None

    nodal_parameters = {}
    outlet_pressure = general_conditions.get("OutletPressure") if isinstance(general_conditions, dict) else None
    if isinstance(outlet_pressure, (int, float)) and math.isfinite(outlet_pressure):
        from sixgill.definitions import Parameters

        nodal_parameters[Parameters.NodalAnalysisSimulation.OUTLETPRESSURE] = outlet_pressure

    if not isinstance(inlet_conditions, dict):
        return nodal_parameters or None, None

    boundary_parameter = {
        "reservoirPressure": "Pressure",
        "waterCut": "WaterCut",
        "gor": "GOR",
    }.get(target)
    if boundary_parameter is None:
        return nodal_parameters or None, inlet_conditions

    boundary_matched = False
    for component in components["Completion"]:
        boundary = inlet_conditions.get(component)
        if boundary is None:
            component_name = component.split(":")[-1]
            boundary = next(
                (
                    candidate
                    for key, candidate in inlet_conditions.items()
                    if isinstance(key, str) and key.split(":")[-1] == component_name
                ),
                None,
            )
        if isinstance(boundary, dict):
            boundary[boundary_parameter] = value
            boundary_matched = True

    if not boundary_matched:
        raise AdapterFailure(
            "MODEL",
            "SENSITIVITY_BOUNDARY_UNAVAILABLE",
            "The selected sensitivity value has no matching Nodal inlet boundary.",
        )

    return nodal_parameters or None, inlet_conditions


def _execute_sensitivity(model, study, components, model_kind, parameters, emit_event):
    _sensitivity_parameters(parameters)
    target = parameters["targetVariable"]
    if target in ("waterCut", "gor") and model_kind != "black_oil_liquid":
        raise AdapterFailure("MODEL", "SENSITIVITY_VARIABLE_UNSUPPORTED", "Water cut and GOR sensitivity require a black-oil liquid model.")
    from sixgill.definitions import Parameters

    # Each case starts from the uploaded model values. The model itself is already an isolated copy.
    targets = {
        "reservoirPressure": [(component, Parameters.Completion.RESERVOIRPRESSURE) for component in components["Completion"]],
        "tubingInnerDiameter": [(component, Parameters.Tubing.INNERDIAMETER) for component in components["Tubing"]],
        "waterCut": [(component, Parameters.BlackOilFluid.WATERCUT) for component in components["BlackOilFluid"]],
        "gor": [(component, Parameters.BlackOilFluid.GOR) for component in components["BlackOilFluid"]],
    }[target]
    originals = []
    try:
        for component, parameter in targets:
            originals.append((component, parameter, model.get_value(component, parameter=parameter)))
        emit_event("RUNNING_NODAL", "Running real PIPESIM nodal analysis for each sensitivity case.")
        cases = []
        for value in parameters["values"]:
            for component, parameter, original in originals:
                model.set_value(component, parameter=parameter, value=original)
            _set_sensitivity_value(model, components, model_kind, target, value)
            try:
                nodal_parameters, inlet_conditions = _sensitivity_inlet_conditions(
                    model,
                    study,
                    components,
                    target,
                    value,
                )
                run_kwargs = {"producer": components["Well"][0], "study": study}
                run_kwargs.update(_well_output_variables())
                if nodal_parameters is not None:
                    run_kwargs["parameters"] = nodal_parameters
                if inlet_conditions is not None:
                    run_kwargs["inlet_conditions"] = inlet_conditions
                nodal = model.tasks.nodalanalysis.run(**run_kwargs)
                ipr = normalize_curve(nodal.inflow_curves[0].curve_data, model_kind == "basic_gas") if nodal.inflow_curves else []
                vlp = normalize_curve(nodal.outflow_curves[0].curve_data, model_kind == "basic_gas") if nodal.outflow_curves else []
            except AdapterFailure:
                raise
            except Exception as exc:
                raise _classify_exception(exc, "SENSITIVITY_CASE_FAILED")
            if not ipr or not vlp:
                raise AdapterFailure("EXECUTION", "EMPTY_SENSITIVITY_CASE", "PIPESIM returned no usable curves for a sensitivity case.")
            cases.append({"value": value, "ipr": ipr, "vlp": vlp})
        return {
            "schemaVersion": "pipesim-well-sensitivity-result/1",
            "model_kind": model_kind,
            "runTask": "sensitivity",
            "resultContract": "VALID_FULL",
            "targetVariable": target,
            "units": _result_units(model_kind),
            "cases": cases,
        }
    finally:
        for component, parameter, original in originals:
            try:
                model.set_value(component, parameter=parameter, value=original)
            except Exception:
                pass


def validate_scenario(parameters, run_task):
    if parameters is None:
        if run_task == "sensitivity":
            raise AdapterFailure('PROTOCOL', 'INVALID_SENSITIVITY_PARAMETERS', 'Sensitivity parameters are required.')
        if run_task == "system-analysis":
            raise AdapterFailure('PROTOCOL', 'INVALID_SYSTEM_ANALYSIS_PARAMETERS', 'System Analysis parameters are required.')
        if run_task == "gas-lift-performance":
            raise AdapterFailure('PROTOCOL', 'INVALID_GAS_LIFT_PERFORMANCE_PARAMETERS', 'Gas Lift Performance parameters are required.')
        if run_task == "gas-lift-diagnostics":
            raise AdapterFailure('PROTOCOL', 'INVALID_GAS_LIFT_DIAGNOSTICS_PARAMETERS', 'Gas Lift Diagnostics parameters are required.')
        if run_task == "vfp-tables":
            raise AdapterFailure('PROTOCOL', 'INVALID_VFP_TABLES_PARAMETERS', 'VFP Tables parameters are required.')
        if run_task == "network-optimizer":
            raise AdapterFailure('PROTOCOL', 'INVALID_NETWORK_OPTIMIZER_PARAMETERS', 'Network Optimizer parameters are required.')
        if run_task == "network-well-performance-curves":
            raise AdapterFailure('PROTOCOL', 'INVALID_NETWORK_WELL_PERFORMANCE_PARAMETERS', 'Well Performance Curves parameters are required.')
        if run_task == "trajectory":
            raise AdapterFailure('PROTOCOL', 'INVALID_TRAJECTORY_PARAMETERS', 'Trajectory parameters are required.')
        if run_task == "esp-parameter":
            raise AdapterFailure('PROTOCOL', 'INVALID_ESP_PARAMETER_PARAMETERS', 'ESP parameter scheme parameters are required.')
        return
    if run_task == "sensitivity":
        _sensitivity_parameters(parameters)
        return
    if run_task == "network":
        if isinstance(parameters, dict) and parameters.get("schemaVersion") == "pipesim-network-choke-bean-size-parameters/1":
            _network_choke_bean_size_parameters(parameters)
        elif isinstance(parameters, dict) and parameters.get("schemaVersion") == "pipesim-network-flowline-diameter-parameters/1":
            _network_flowline_diameter_parameters(parameters)
        elif isinstance(parameters, dict) and parameters.get("schemaVersion") == "pipesim-network-connection-parameters/1":
            _network_connection_parameters(parameters)
        else:
            _network_parameters(parameters)
        return
    if run_task == "network-optimizer":
        _network_optimizer_parameters(parameters)
        return
    if run_task == "network-well-performance-curves":
        _network_well_performance_parameters(parameters)
        return
    if run_task == "system-analysis":
        _system_analysis_parameters(parameters)
        return
    if run_task == "gas-lift-performance":
        _gas_lift_performance_parameters(parameters)
        return
    if run_task == "gas-lift-diagnostics":
        _gas_lift_diagnostics_parameters(parameters)
        return
    if run_task == "vfp-tables":
        _vfp_tables_parameters(parameters)
        return
    if run_task == "esp-curves":
        _esp_curves_parameters(parameters)
        return
    if run_task == "esp-parameter":
        _esp_parameter_parameters(parameters)
        return
    if run_task == "trajectory":
        _trajectory_parameters(parameters)
        return
    if run_task == "profile" and isinstance(parameters, dict) and parameters.get("schemaVersion") == "pipesim-well-tubing-diameter-parameters/1":
        _well_tubing_diameter_parameters(parameters)
        return
    if run_task in ('profile', 'combined') and isinstance(parameters, dict) \
            and set(parameters) == {'schemaVersion', 'outletPressurePsi'} \
            and parameters.get('schemaVersion') == 'pipesim-well-profile-parameters/1':
        value = parameters['outletPressurePsi']
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or not 0 < value <= 100000:
            raise AdapterFailure('PROTOCOL', 'INVALID_PROFILE_PARAMETERS', 'Profile outlet pressure must be positive and at most 100000 psia.')
        return
    if (run_task not in ('nodal', 'profile', 'combined') or not isinstance(parameters, dict)
            or set(parameters) != {'schemaVersion', 'reservoirPressurePsi'}
            or parameters['schemaVersion'] != 'pipesim-well-parameters/1'):
        raise AdapterFailure('PROTOCOL', 'INVALID_SCENARIO_PARAMETERS', 'Unsupported well scenario parameters.')
    value = parameters['reservoirPressurePsi']
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or not 0 < value <= 100000:
        raise AdapterFailure('PROTOCOL', 'INVALID_SCENARIO_PARAMETERS', 'Reservoir pressure must be positive and at most 100000 psia.')


def apply_scenario(model, completion, parameters):
    if parameters is None:
        return
    from sixgill.definitions import Parameters
    key = Parameters.Completion.RESERVOIRPRESSURE
    try:
        if model.describe(context=completion, parameter=key).units_symbol != 'psia':
            raise AdapterFailure('MODEL', 'PARAMETER_UNIT_UNSUPPORTED', 'The completion pressure unit must be psia.')
        original = model.get_value(completion, parameter=key)
        if isinstance(original, bool) or not isinstance(original, (int, float)) or not math.isfinite(original):
            raise AdapterFailure('MODEL', 'PARAMETER_READ_FAILED', 'The original reservoir pressure is unavailable.')
        value = parameters['reservoirPressurePsi']
        model.set_value(completion, parameter=key, value=value)
        applied = model.get_value(completion, parameter=key)
        if isinstance(applied, bool) or not isinstance(applied, (int, float)) or not math.isfinite(applied) or not math.isclose(applied, value, rel_tol=1e-9, abs_tol=1e-6):
            raise AdapterFailure('MODEL', 'PARAMETER_READBACK_MISMATCH', 'The scenario pressure could not be confirmed.')
    except AdapterFailure:
        raise
    except Exception:
        raise AdapterFailure('MODEL', 'PARAMETER_APPLY_FAILED', 'The scenario pressure could not be applied.')


def execute_request(request, model_factory=None, emit_event=None):
    emit_event = emit_event or (lambda state, message: None)
    model = None
    profile_warning = None
    try:
        required = ("modelPath", "study", "runTask", "parameters")
        if not isinstance(request, dict) or any(name not in request for name in required):
            raise AdapterFailure("PROTOCOL", "INVALID_REQUEST", "The adapter request is missing required fields.")
        validate_scenario(request['parameters'], request['runTask'])
        run_task = request["runTask"]
        if run_task not in ("nodal", "profile", "combined", "sensitivity", "network", "system-analysis", "network-optimizer", "network-well-performance-curves", "gas-lift-performance", "gas-lift-diagnostics", "vfp-tables", "esp-curves", "esp-parameter", "trajectory"):
            raise AdapterFailure("PROTOCOL", "INVALID_RUN_TASK", "runTask must be a supported PIPESIM task.")
        study = request["study"]
        model_path = request["modelPath"]
        if not isinstance(study, str) or not study.strip():
            raise AdapterFailure("MODEL", "STUDY_REQUIRED", "A model Study is required.")
        if not isinstance(model_path, str) or not os.path.isfile(model_path):
            raise AdapterFailure("STORAGE", "MODEL_COPY_MISSING", "The task model copy is unavailable.")

        if model_factory is None:
            try:
                from sixgill.pipesim import Model
            except Exception as exc:
                raise _classify_exception(exc, "PTK_IMPORT_FAILED")
            # Official apply_results() requires the model to be opened with
            # persisted (non in-memory) results.  The task still runs on the
            # Worker-created copy, never on the uploaded source model.
            model_factory = lambda path: Model.open(path, in_memory_results=False)

        try:
            model = model_factory(model_path)
            if study not in _study_names(model):
                raise AdapterFailure("MODEL", "STUDY_NOT_FOUND", "The selected Study does not exist in the model.")
            if run_task == "network":
                if isinstance(request["parameters"], dict) and request["parameters"].get("schemaVersion") == "pipesim-network-choke-bean-size-parameters/1":
                    result, warnings = _execute_network_choke_bean_size(model, study, request["parameters"], emit_event)
                elif isinstance(request["parameters"], dict) and request["parameters"].get("schemaVersion") == "pipesim-network-flowline-diameter-parameters/1":
                    result, warnings, model = _execute_network_flowline_diameter(
                        model, model_factory, model_path, study, request["parameters"], emit_event)
                elif isinstance(request["parameters"], dict) and request["parameters"].get("schemaVersion") == "pipesim-network-connection-parameters/1":
                    result, warnings, model = _execute_network_connection(
                        model, model_factory, model_path, study, request["parameters"], emit_event)
                else:
                    result, warnings = _execute_network(model, study, request["parameters"], emit_event)
                return {
                    "type": "result",
                    "status": "partial" if result["resultContract"] == "VALID_PARTIAL" else "ok",
                    "result": result,
                    "error": None,
                    "warnings": warnings,
                }
            if run_task == "network-optimizer":
                result = _execute_network_optimizer(model, study, request["parameters"], emit_event)
                emit_event("COLLECTING", "Collecting and normalizing PIPESIM Network Optimizer results.")
                return {
                    "type": "result",
                    "status": "ok",
                    "result": result,
                    "error": None,
                    "warnings": [],
                }
            if run_task == "network-well-performance-curves":
                result = _execute_network_well_performance_curves(model, study, request["parameters"], emit_event)
                emit_event("COLLECTING", "Collecting official Well Performance Curves system/node results and PWI output.")
                return {"type": "result", "status": "ok", "result": result, "error": None, "warnings": []}
            if run_task == "system-analysis":
                result = _execute_system_analysis(model, study, request["parameters"], emit_event)
                emit_event("COLLECTING", "Collecting and normalizing PIPESIM System Analysis results.")
                return {
                    "type": "result",
                    "status": "ok",
                    "result": result,
                    "error": None,
                    "warnings": [],
                }
            selected_well = None
            well_run_parameters = request["parameters"]
            if run_task == "esp-parameter":
                esp_parameters = _esp_parameter_parameters(request["parameters"])
                if esp_parameters["espContext"].upper() == "AUTO":
                    discovered_esps = list(model.find(component="ESP"))
                    if len(discovered_esps) != 1:
                        raise AdapterFailure("MODEL", "ESP_CONTEXT_REQUIRED", "Select one ESP explicitly because the model contains multiple ESP components.")
                    esp_parameters["espContext"] = discovered_esps[0]
                selected_well = esp_parameters.get("wellContext") or _well_for_esp_context(model, esp_parameters["espContext"])
                if esp_parameters["espContext"] not in _well_esp_contexts(model, selected_well):
                    raise AdapterFailure("MODEL", "ESP_CONTEXT_NOT_FOUND", "The selected ESP context is not associated with the selected well.")
                well_run_parameters = dict(request["parameters"])
                well_run_parameters["espContext"] = esp_parameters["espContext"]
            elif run_task == "esp-curves":
                curve_parameters = _esp_curves_parameters(request["parameters"])
                selected_well = curve_parameters["wellContext"]
                if selected_well is None:
                    wells = list(model.find(component="Well"))
                    if len(wells) != 1:
                        raise AdapterFailure("MODEL", "WELL_CONTEXT_REQUIRED", "Select one well explicitly because the model contains multiple wells.")
                    selected_well = wells[0]
                    esps = _well_esp_contexts(model, selected_well)
                    if len(esps) != 1:
                        raise AdapterFailure("MODEL", "ESP_CONTEXT_REQUIRED", "Select one ESP explicitly because the selected well contains multiple ESP components.")
                    curve_parameters["espContext"] = esps[0]
                if curve_parameters["espContext"] not in _well_esp_contexts(model, selected_well):
                    raise AdapterFailure("MODEL", "ESP_CONTEXT_NOT_FOUND", "The selected ESP context is not associated with the selected well.")
                well_run_parameters = curve_parameters
            elif run_task == "trajectory":
                selected_well = _trajectory_parameters(request["parameters"])["wellContext"]
            elif run_task == "profile" and isinstance(request["parameters"], dict) \
                    and request["parameters"].get("schemaVersion") == "pipesim-well-tubing-diameter-parameters/1":
                selected_well = _well_tubing_diameter_parameters(request["parameters"])["wellContext"]
            elif run_task == "vfp-tables":
                selected_well = _vfp_tables_parameters(request["parameters"])["producer"]
            components, model_kind = _run_components(
                model,
                requested_well=selected_well,
                require_flow_components=run_task != "trajectory",
            )
            if run_task == "profile" and isinstance(request["parameters"], dict) \
                    and request["parameters"].get("schemaVersion") == "pipesim-well-tubing-diameter-parameters/1":
                result, model = _execute_well_tubing_diameter_profile(
                    model, model_factory, model_path, study, components, model_kind, request["parameters"], emit_event)
                emit_event("COLLECTING", "Collecting the verified Tubing edit, Profile result, and persisted model artifact.")
                return {"type": "result", "status": "ok", "result": result, "error": None, "warnings": []}
            if run_task == "sensitivity":
                result = _execute_sensitivity(model, study, components, model_kind, request['parameters'], emit_event)
                emit_event("COLLECTING", "Collecting and normalizing PIPESIM sensitivity curves.")
                return {
                    "type": "result",
                    "status": "ok",
                    "result": result,
                    "error": None,
                    "warnings": [],
                }
            if run_task == "gas-lift-performance":
                result = _execute_gas_lift_performance(model, components, model_kind, request['parameters'], emit_event, study=study)
                emit_event("COLLECTING", "Collecting and normalizing PIPESIM Gas Lift Performance results.")
                return {
                    "type": "result",
                    "status": "ok",
                    "result": result,
                    "error": None,
                    "warnings": [],
                }
            if run_task == "gas-lift-diagnostics":
                result = _execute_gas_lift_diagnostics(model, components, model_kind, request['parameters'], emit_event, study=study)
                emit_event("COLLECTING", "Collecting and normalizing PIPESIM Gas Lift Diagnostics results.")
                return {
                    "type": "result",
                    "status": "ok",
                    "result": result,
                    "error": None,
                    "warnings": [],
                }
            if run_task == "vfp-tables":
                result = _execute_vfp_tables(
                    model, study, components, model_kind, request['parameters'], emit_event,
                    request.get("artifactDirectory"),
                )
                emit_event("COLLECTING", "Collecting and normalizing PIPESIM VFP Tables results.")
                return {
                    "type": "result",
                    "status": "ok",
                    "result": result,
                    "error": None,
                    "warnings": [],
                }
            if run_task == "esp-curves":
                result = _execute_esp_curves(model, study, components, model_kind, well_run_parameters, emit_event)
                emit_event("COLLECTING", "Collecting and normalizing PIPESIM ESP curve results.")
                return {
                    "type": "result",
                    "status": "ok",
                    "result": result,
                    "error": None,
                    "warnings": [],
                }
            if run_task == "esp-parameter":
                result = _execute_esp_parameter(model, study, components, model_kind, well_run_parameters, emit_event)
                emit_event("COLLECTING", "Collecting and normalizing PIPESIM ESP parameter results.")
                return {
                    "type": "result",
                    "status": "ok",
                    "result": result,
                    "error": None,
                    "warnings": [],
                }
            if run_task == "trajectory":
                trajectory_parameters = _trajectory_parameters(request["parameters"])
                if trajectory_parameters["schemaVersion"] == "pipesim-well-trajectory-parameters/3":
                    result, model = _execute_trajectory_edit(
                        model, model_factory, model_path, study, trajectory_parameters,
                        components["Well"][0], model_kind, emit_event,
                    )
                    collecting_message = "Collecting the edited trajectory, PT Profile comparison, and persisted model artifact."
                else:
                    result = _execute_trajectory(model, components, model_kind, emit_event)
                    collecting_message = "Collecting and normalizing PIPESIM well trajectory results."
                emit_event("COLLECTING", collecting_message)
                return {
                    "type": "result",
                    "status": "ok",
                    "result": result,
                    "error": None,
                    "warnings": [],
                }
            profile_parameters = request['parameters'] if isinstance(request['parameters'], dict) \
                and request['parameters'].get('schemaVersion') == 'pipesim-well-profile-parameters/1' else None
            tubing_edit = isinstance(request['parameters'], dict) and request['parameters'].get('schemaVersion') == 'pipesim-well-tubing-diameter-parameters/1'
            if request['parameters'] is not None and profile_parameters is None and not tubing_edit and not (
                    model_kind == 'basic_gas' or (model_kind == 'black_oil_liquid' and run_task == 'nodal')):
                raise AdapterFailure('MODEL', 'INVALID_SCENARIO_PARAMETERS', 'The pressure scenario is not supported for this model and task.')
            if request['parameters'] is not None and profile_parameters is None:
                apply_scenario(model, components['Completion'][0], request['parameters'])
            well_name = components["Well"][0]
            is_gas = model_kind == "basic_gas" or str(getattr(model.fluids, "fluid_type", "")).lower().endswith("compositional")

            if run_task in ("nodal", "combined"):
                _preflight_well_task(model.tasks.nodalanalysis, well_name, study)
            if run_task in ("profile", "combined"):
                preflight_profile_parameters = {}
                if profile_parameters is not None:
                    from sixgill.definitions import Parameters
                    preflight_profile_parameters[Parameters.PTProfileSimulation.OUTLETPRESSURE] = profile_parameters["outletPressurePsi"]
                if is_gas:
                    from sixgill.definitions import Constants, Parameters
                    preflight_profile_parameters.update({
                        Parameters.PTProfileSimulation.CALCULATEDVARIABLE: Constants.CalculatedVariable.FLOWRATE,
                        Parameters.PTProfileSimulation.FLOWRATETYPE: Constants.FlowRateType.GASFLOWRATE,
                    })
                    if request['parameters'] is not None and request['parameters'].get('schemaVersion') == 'pipesim-well-parameters/1':
                        preflight_profile_parameters[Parameters.PTProfileSimulation.INLETPRESSURE] = request['parameters']['reservoirPressurePsi']
                _preflight_well_task(model.tasks.ptprofilesimulation, well_name, study, preflight_profile_parameters)
        except AdapterFailure:
            raise
        except Exception as exc:
            classified = _classify_exception(exc, "MODEL_OPEN_FAILED")
            if classified.category == "EXECUTION":
                classified = AdapterFailure("MODEL", "MODEL_OPEN_FAILED", "The task model copy could not be opened.")
            raise classified

        is_gas = model_kind == "basic_gas" or str(getattr(model.fluids, "fluid_type", "")).lower().endswith("compositional")
        ipr = []
        vlp = []
        profile = []

        if run_task in ("nodal", "combined"):
            emit_event("RUNNING_NODAL", "Running the selected Study nodal analysis.")
            try:
                nodal = model.tasks.nodalanalysis.run(
                    producer=well_name,
                    study=study,
                    **_well_output_variables(),
                )
                ipr = normalize_curve(nodal.inflow_curves[0].curve_data, is_gas) if nodal.inflow_curves else []
                vlp = normalize_curve(nodal.outflow_curves[0].curve_data, is_gas) if nodal.outflow_curves else []
            except Exception as exc:
                raise _classify_exception(exc, "NODAL_RUN_FAILED")
            if not ipr or not vlp:
                raise AdapterFailure("EXECUTION", "EMPTY_NODAL_RESULT", "PIPESIM returned no usable nodal curves.")

        if run_task in ("profile", "combined"):
            emit_event("RUNNING_PROFILE", "Running the selected Study pressure-temperature profile.")
            try:
                profile_kwargs = {"producer": well_name, "study": study}
                profile_kwargs.update(_well_output_variables())
                if isinstance(request['parameters'], dict) and request['parameters'].get('schemaVersion') == 'pipesim-well-profile-parameters/1':
                    from sixgill.definitions import Parameters

                    profile_kwargs["parameters"] = {
                        Parameters.PTProfileSimulation.OUTLETPRESSURE: request['parameters']['outletPressurePsi'],
                    }
                if is_gas:
                    from sixgill.definitions import Constants, Parameters

                    profile_kwargs.setdefault("parameters", {}).update({
                        Parameters.PTProfileSimulation.CALCULATEDVARIABLE: Constants.CalculatedVariable.FLOWRATE,
                        Parameters.PTProfileSimulation.FLOWRATETYPE: Constants.FlowRateType.GASFLOWRATE,
                    })
                    if request['parameters'] is not None:
                        if request['parameters'].get('schemaVersion') == 'pipesim-well-parameters/1':
                            profile_kwargs["parameters"][Parameters.PTProfileSimulation.INLETPRESSURE] = request['parameters']['reservoirPressurePsi']
                pt_result = model.tasks.ptprofilesimulation.run(**profile_kwargs)
                profile = normalize_profile(pt_result.profile)
                if not profile:
                    raise AdapterFailure("EXECUTION", "EMPTY_PROFILE_RESULT", "PIPESIM returned no usable profile points.")
            except Exception as exc:
                failure = exc if isinstance(exc, AdapterFailure) else _classify_exception(exc, "PROFILE_RUN_FAILED")
                if run_task == "profile":
                    raise failure
                profile = []
                profile_warning = _error(failure.category, failure.code, failure.args[0], failure.retryable)

        emit_event("COLLECTING", "Collecting and normalizing PIPESIM result arrays.")
        result = _result_document(model_kind, run_task, ipr, vlp, profile)
        if result["resultContract"].startswith("INVALID_"):
            raise AdapterFailure("PROTOCOL", "INVALID_RESULT_CONTRACT", "The normalized result does not satisfy the frozen result contract.")
        return {
            "type": "result",
            "status": "partial" if result["resultContract"] == "VALID_PARTIAL" else "ok",
            "result": result,
            "error": None,
            "warnings": [profile_warning] if profile_warning is not None else [],
        }
    except AdapterFailure as exc:
        error = _error(exc.category, exc.code, exc.args[0], exc.retryable)
        if exc.diagnostics is not None:
            error["diagnostics"] = exc.diagnostics
        return {
            "type": "result",
            "status": "error",
            "result": None,
            "error": error,
            "warnings": [],
        }
    except Exception as exc:
        failure = _classify_exception(exc)
        return {
            "type": "result",
            "status": "error",
            "result": None,
            "error": _error(failure.category, failure.code, failure.args[0], failure.retryable),
            "warnings": [],
        }
    finally:
        if model is not None:
            try:
                model.close()
            except Exception:
                pass


def _emit(value):
    print(
        json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":")),
        file=_PROTOCOL_OUTPUT,
        flush=True,
    )


def _await_start_gate():
    if os.environ.get("GRDP_PTK_START_GATED") != "1":
        return True
    try:
        return json.loads(sys.stdin.readline()) == {"type": "start"}
    except Exception:
        return False


def main():
    # Keep stdout protocol-only even if PTK or a dependency writes human diagnostics.
    sys.stdout = sys.stderr
    if len(sys.argv) != 2:
        _emit({
            "type": "result",
            "status": "error",
            "result": None,
            "error": _error("PROTOCOL", "INVALID_ARGUMENTS", "Exactly one request file is required."),
            "warnings": [],
        })
        return 2
    if not _await_start_gate():
        _emit({
            "type": "result",
            "status": "error",
            "result": None,
            "error": _error("PROTOCOL", "START_GATE_FAILED", "The adapter start gate was not released."),
            "warnings": [],
        })
        return 2
    try:
        with open(sys.argv[1], "r", encoding="utf-8") as request_file:
            request = json.load(request_file)
    except Exception:
        _emit({
            "type": "result",
            "status": "error",
            "result": None,
            "error": _error("PROTOCOL", "INVALID_REQUEST_JSON", "The adapter request file is invalid."),
            "warnings": [],
        })
        return 2
    envelope = execute_request(
        request,
        emit_event=lambda state, message: _emit({"type": "event", "state": state, "message": message}),
    )
    _emit(envelope)
    return 0


if __name__ == "__main__":
    sys.exit(main())
