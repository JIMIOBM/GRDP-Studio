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
    build_network_result,
    build_safe_partial_network_result,
    extract_topology,
    format_validation_issues,
    inspect_network,
    network_failure_message,
    network_profile_variables,
    validate_network_result,
)


class AdapterFailure(Exception):
    def __init__(self, category, code, message, retryable=False):
        super().__init__(message)
        self.category = category
        self.code = code
        self.retryable = retryable


def _error(category, code, message, retryable=False):
    return {
        "category": category,
        "code": code,
        "message": message,
        "retryable": retryable,
    }


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


def _run_components(model):
    components = {
        kind: list(model.find(component=kind))
        for kind in ("Well", "BlackOilFluid", "Completion", "Tubing")
    }
    if len(components["Well"]) != 1 or not components["Completion"] or not components["Tubing"]:
        raise AdapterFailure(
            "MODEL",
            "UNSUPPORTED_MODEL",
            "The PIPESIM well model must contain exactly one well and at least one completion and tubing.",
        )

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


def _system_analysis_parameters(parameters):
    if (not isinstance(parameters, dict)
            or set(parameters) != {
                "schemaVersion", "producer", "branchTerminator",
                "outletPressurePsi", "scanVariable", "values"
            }
            or parameters.get("schemaVersion") != "pipesim-system-analysis-parameters/1"
            or parameters.get("producer") != "Well"
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


def _normalize_system_analysis_profile(profile, case_name):
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
        variables.append({"variable": variable, "unit": None, "values": normalized_values})
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


def _execute_system_analysis(model, study, parameters, emit_event):
    parameters = _system_analysis_parameters(parameters)
    from sixgill.definitions import Constants, Parameters, ProfileVariables, SystemVariables

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
            simulation = model.tasks.systemanalysissimulation.run(
                producer=parameters["producer"],
                study=study,
                parameters=task_parameters,
                system_variables=system_variables,
                profile_variables=profile_variables,
            )
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
        cases.append({
            "caseName": case_name,
            "scanValue": scan_value,
            "system": _normalize_system_analysis_system(raw_system, case_name),
            "node": _normalize_system_analysis_nodes(raw_node, case_name),
            "profile": _normalize_system_analysis_profile(raw_profile, case_name),
        })
    return {
        "schemaVersion": "pipesim-system-analysis-result/1",
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


def _execute_gas_lift_performance(model, components, model_kind, parameters, emit_event):
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
        parameters["producer"] + ":INFLOW": {
            Parameters.Boundary.GOR: parameters["gorScfPerStb"],
            Parameters.Boundary.WATERCUT: parameters["waterCutPercent"],
            Parameters.Boundary.PRESSURE: parameters["reservoirPressurePsi"],
        }
    }
    system_variables = [SystemVariables.OUTLET_VOLUME_FLOWRATE_LIQUID_STOCKTANK]
    emit_event("RUNNING_NODAL", "Running PIPESIM Gas Lift Performance sensitivity.")
    try:
        task = model.tasks.gldiagnosticssimulation
        task.set_conditions(parameters["producer"], gas_lift_parameters, boundary_parameters)
        simulation = task.run(producer=parameters["producer"], system_variables=system_variables)
    except Exception as exc:
        failure = _classify_exception(exc, "GAS_LIFT_PERFORMANCE_FAILED")
        if failure.category == "EXECUTION":
            failure = AdapterFailure("EXECUTION", "GAS_LIFT_PERFORMANCE_FAILED",
                                     "PIPESIM Gas Lift Performance simulation failed.")
        raise failure
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


def _execute_gas_lift_diagnostics(model, components, model_kind, parameters, emit_event):
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
        parameters["producer"] + ":INFLOW": {
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
    emit_event("RUNNING_NODAL", "Running official PIPESIM Gas Lift Diagnostics.")
    try:
        task = model.tasks.gldiagnosticssimulation
        task.set_conditions(parameters["producer"], gas_lift_parameters, boundary_parameters)
        simulation = task.run(producer=parameters["producer"], system_variables=diagnostics_variables)
    except Exception as exc:
        failure = _classify_exception(exc, "GAS_LIFT_DIAGNOSTICS_FAILED")
        if failure.category == "EXECUTION":
            failure = AdapterFailure("EXECUTION", "GAS_LIFT_DIAGNOSTICS_FAILED",
                                     "PIPESIM Gas Lift Diagnostics simulation failed.")
        raise failure
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


def _parse_vfp_table(content, value_name, unit, outlet_count):
    rows = []
    marker = "-- Table body:"
    body = content.split(marker, 1)[1] if marker in content else ""
    for line in body.splitlines():
        line = line.strip()
        if not line or line.startswith("--"):
            continue
        tokens = line.rstrip("/").split()
        # PIPESIM's VFPPROD body indexes LIQ/WCT/GOR/ALQ; THP is the
        # value axis, so each row has four indexes followed by one value
        # per outlet pressure.
        if len(tokens) < 5 or not all(token.isdigit() for token in tokens[:4]):
            continue
        try:
            values = [_clean_number(float(token)) for token in tokens[4:]]
        except (TypeError, ValueError):
            continue
        if len(values) == outlet_count and all(value is not None for value in values):
            rows.append({
                "liquidRateIndex": int(tokens[0]),
                "waterCutIndex": int(tokens[1]),
                "gorIndex": int(tokens[2]),
                "artificialLiftIndex": int(tokens[3]),
                "values": values,
            })
    if not rows:
        raise AdapterFailure("PROTOCOL", "EMPTY_VFP_TABLES_RESULT", "PIPESIM returned no usable VFP table rows.")
    return {"valueName": value_name, "unit": unit, "rows": rows}


def _execute_vfp_tables(model, components, model_kind, parameters, emit_event):
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
    try:
        simulation = model.tasks.vfptablessimulation.run(producer=parameters["producer"], parameters=conditions)
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
    table = _parse_vfp_table(content, "BHP", "psia", len(axes["outletPressuresPsi"]))
    temperature_table = (
        _parse_vfp_table(temperature_content, "TEMP", "F", len(axes["outletPressuresPsi"]))
        if parameters["includeTemperature"]
        else {"valueName": "TEMP", "unit": "F", "rows": []}
    )
    return {
        "schemaVersion": "pipesim-vfp-tables-result/1",
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


def _execute_esp_curves(model, study, components, model_kind, emit_event):
    if model_kind not in ("black_oil_liquid", "basic_gas"):
        raise AdapterFailure("MODEL", "UNSUPPORTED_MODEL", "ESP Curves is supported for validated black-oil liquid or basic-gas wells.")
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
        if not isinstance(first_case, dict) or "B-ESP" not in first_case:
            raise AdapterFailure("MODEL", "ESP_PUMP_NOT_FOUND", "The official model has no B-ESP pump result.")
        return _normalize_esp_pump("B-ESP", first_case["B-ESP"])

    return {
        "schemaVersion": "pipesim-esp-curves-result/1",
        "model_kind": model_kind,
        "runTask": "esp-curves",
        "resultContract": "VALID_FULL",
        "producer": producer,
        "pump": pump_from(profile_result),
        "nodalPump": pump_from(nodal_result),
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

    columns = {str(column) for column in getattr(trajectory, "columns", [])}
    required_columns = {"MeasuredDepth", "TrueVerticalDepth", "Inclination", "Azimuth"}
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
        if run_task == "trajectory":
            raise AdapterFailure('PROTOCOL', 'INVALID_TRAJECTORY_PARAMETERS', 'Trajectory parameters are required.')
        return
    if run_task == "sensitivity":
        _sensitivity_parameters(parameters)
        return
    if run_task == "network":
        if isinstance(parameters, dict) and parameters.get("schemaVersion") == "pipesim-network-choke-bean-size-parameters/1":
            _network_choke_bean_size_parameters(parameters)
        else:
            _network_parameters(parameters)
        return
    if run_task == "network-optimizer":
        _network_optimizer_parameters(parameters)
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
        if (not isinstance(parameters, dict) or set(parameters) != {"schemaVersion"}
                or parameters.get("schemaVersion") != "pipesim-esp-curves-parameters/1"):
            raise AdapterFailure("PROTOCOL", "INVALID_ESP_CURVES_PARAMETERS", "ESP Curves parameters are invalid.")
        return
    if run_task == "trajectory":
        if (not isinstance(parameters, dict) or set(parameters) != {"schemaVersion"}
                or parameters.get("schemaVersion") != "pipesim-well-trajectory-parameters/1"):
            raise AdapterFailure("PROTOCOL", "INVALID_TRAJECTORY_PARAMETERS", "Trajectory parameters are invalid.")
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
        if run_task not in ("nodal", "profile", "combined", "sensitivity", "network", "system-analysis", "network-optimizer", "gas-lift-performance", "gas-lift-diagnostics", "vfp-tables", "esp-curves", "trajectory"):
            raise AdapterFailure("PROTOCOL", "INVALID_RUN_TASK", "runTask must be nodal, profile, combined, sensitivity, network, system-analysis, network-optimizer, gas-lift-performance, gas-lift-diagnostics, vfp-tables, esp-curves, or trajectory.")
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
            components, model_kind = _run_components(model)
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
                result = _execute_gas_lift_performance(model, components, model_kind, request['parameters'], emit_event)
                emit_event("COLLECTING", "Collecting and normalizing PIPESIM Gas Lift Performance results.")
                return {
                    "type": "result",
                    "status": "ok",
                    "result": result,
                    "error": None,
                    "warnings": [],
                }
            if run_task == "gas-lift-diagnostics":
                result = _execute_gas_lift_diagnostics(model, components, model_kind, request['parameters'], emit_event)
                emit_event("COLLECTING", "Collecting and normalizing PIPESIM Gas Lift Diagnostics results.")
                return {
                    "type": "result",
                    "status": "ok",
                    "result": result,
                    "error": None,
                    "warnings": [],
                }
            if run_task == "vfp-tables":
                result = _execute_vfp_tables(model, components, model_kind, request['parameters'], emit_event)
                emit_event("COLLECTING", "Collecting and normalizing PIPESIM VFP Tables results.")
                return {
                    "type": "result",
                    "status": "ok",
                    "result": result,
                    "error": None,
                    "warnings": [],
                }
            if run_task == "esp-curves":
                result = _execute_esp_curves(model, study, components, model_kind, emit_event)
                emit_event("COLLECTING", "Collecting and normalizing PIPESIM ESP curve results.")
                return {
                    "type": "result",
                    "status": "ok",
                    "result": result,
                    "error": None,
                    "warnings": [],
                }
            if run_task == "trajectory":
                result = _execute_trajectory(model, components, model_kind, emit_event)
                emit_event("COLLECTING", "Collecting and normalizing PIPESIM well trajectory results.")
                return {
                    "type": "result",
                    "status": "ok",
                    "result": result,
                    "error": None,
                    "warnings": [],
                }
            profile_parameters = request['parameters'] if isinstance(request['parameters'], dict) \
                and request['parameters'].get('schemaVersion') == 'pipesim-well-profile-parameters/1' else None
            if request['parameters'] is not None and profile_parameters is None and not (
                    model_kind == 'basic_gas' or (model_kind == 'black_oil_liquid' and run_task == 'nodal')):
                raise AdapterFailure('MODEL', 'INVALID_SCENARIO_PARAMETERS', 'The pressure scenario is not supported for this model and task.')
            if request['parameters'] is not None and profile_parameters is None:
                apply_scenario(model, components['Completion'][0], request['parameters'])
            well_name = components["Well"][0]
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
        return {
            "type": "result",
            "status": "error",
            "result": None,
            "error": _error(exc.category, exc.code, exc.args[0], exc.retryable),
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
