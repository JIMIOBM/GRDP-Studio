import json
import os
import sys

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


def _result_document(model_kind, run_task, ipr, vlp, profile):
    for point in ipr + vlp:
        if _clean_number(point.get("flow")) is None or _clean_number(point.get("pressure")) is None:
            raise AdapterFailure("PROTOCOL", "NON_FINITE_RESULT", "The normalized curve contains a non-finite value.")
    for point in profile:
        if any(_clean_number(point.get(name)) is None for name in ("depth", "pressure", "temperature")):
            raise AdapterFailure("PROTOCOL", "NON_FINITE_RESULT", "The normalized profile contains a non-finite value.")
    contract = validate_result_contract(run_task, ipr, vlp, profile)
    flow_unit = (
        {"displayUnit": "mmscf/d", "semantics": "standard_gas_volume_rate"}
        if model_kind == "basic_gas"
        else _unit_descriptor()
    )
    return {
        "schemaVersion": "pipesim-well-result/1",
        "model_kind": model_kind,
        "runTask": run_task,
        "resultContract": contract,
        "units": {
            "flow": flow_unit,
            "pressure": _unit_descriptor(),
            "depth": _unit_descriptor(),
            "temperature": _unit_descriptor(),
        },
        "ipr": ipr,
        "vlp": vlp,
        "profile": profile,
    }


def _run_components(model):
    components = {
        kind: list(model.find(component=kind))
        for kind in ("Well", "BlackOilFluid", "Completion", "Tubing")
    }
    for required in ("Well", "Completion", "Tubing"):
        if len(components[required]) != 1:
            raise AdapterFailure(
                "MODEL",
                "UNSUPPORTED_MODEL",
                "Only a single well with one completion and one tubing is supported.",
            )

    from sixgill.definitions import Constants, Parameters

    completion = components["Completion"][0]
    fluid_type = model.fluids.fluid_type
    geometry = model.get_value(completion, parameter=Parameters.Completion.GEOMETRYPROFILETYPE)
    if fluid_type == Constants.FluidType.COMPOSITIONAL:
        if str(geometry).lower() != "vertical":
            raise AdapterFailure("MODEL", "UNSUPPORTED_MODEL", "The compositional model is not an approved vertical basic-gas well.")
        return components, "basic_gas"
    if fluid_type == Constants.FluidType.BLACKOIL:
        fluid = model.get_value(completion, parameter=Parameters.Well.ASSOCIATEDBLACKOILFLUID)
        if fluid not in components["BlackOilFluid"]:
            raise AdapterFailure("MODEL", "UNSUPPORTED_MODEL", "The completion is not associated with a black-oil fluid.")
        return components, "black_oil_liquid"
    raise AdapterFailure("MODEL", "UNSUPPORTED_MODEL", "The model fluid type is not supported.")


def _study_names(model):
    from sixgill.core.resources import ModelClasses

    return [
        study.name
        for study in model._catalog.lookup_entries_by_class_ids([ModelClasses.STUDY])
    ]


def execute_request(request, model_factory=None, emit_event=None):
    emit_event = emit_event or (lambda state, message: None)
    model = None
    profile_warning = None
    try:
        required = ("modelPath", "study", "runTask", "parameters")
        if not isinstance(request, dict) or any(name not in request for name in required):
            raise AdapterFailure("PROTOCOL", "INVALID_REQUEST", "The adapter request is missing required fields.")
        if request["parameters"] is not None:
            raise AdapterFailure("PROTOCOL", "PARAMETERS_NOT_NULL", "Run parameters must be explicitly null.")
        run_task = request["runTask"]
        if run_task not in ("nodal", "profile", "combined"):
            raise AdapterFailure("PROTOCOL", "INVALID_RUN_TASK", "runTask must be nodal, profile, or combined.")
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
            model_factory = Model.open

        try:
            model = model_factory(model_path)
            components, model_kind = _run_components(model)
            well_name = components["Well"][0]
            if study not in _study_names(model):
                raise AdapterFailure("MODEL", "STUDY_NOT_FOUND", "The selected Study does not exist in the model.")
        except AdapterFailure:
            raise
        except Exception as exc:
            classified = _classify_exception(exc, "MODEL_OPEN_FAILED")
            if classified.category == "EXECUTION":
                classified = AdapterFailure("MODEL", "MODEL_OPEN_FAILED", "The task model copy could not be opened.")
            raise classified

        is_gas = model_kind == "basic_gas"
        ipr = []
        vlp = []
        profile = []

        if run_task in ("nodal", "combined"):
            emit_event("RUNNING_NODAL", "Running the selected Study nodal analysis.")
            try:
                nodal = model.tasks.nodalanalysis.run(producer=well_name, study=study)
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
                if is_gas:
                    from sixgill.definitions import Constants, Parameters

                    profile_kwargs["parameters"] = {
                        Parameters.PTProfileSimulation.CALCULATEDVARIABLE: Constants.CalculatedVariable.FLOWRATE,
                        Parameters.PTProfileSimulation.FLOWRATETYPE: Constants.FlowRateType.GASFLOWRATE,
                    }
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
