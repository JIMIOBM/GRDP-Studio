"""L42 native gate: explicit FIELD inputs, save/reopen/readback, then PT Profile.

This is a developer CLI, not a production endpoint or a calibrated reservoir model.
Geometry and IPR remain inherited from the official Simple vertical template.
"""
import argparse
import json
import math
from pathlib import Path
import sys

from pipesim_template_preflight import create_template, diagnostic, toolkit_runtime


FIELD_INPUTS = {
    "oilApi": (0, 100), "gasSpecificGravity": (0, 10),
    "waterSpecificGravity": (0, 10), "gorScfStb": (0, 1000000),
    "waterCutPercent": (0, 100), "reservoirPressurePsia": (0, 100000),
    "reservoirTemperatureDegF": (-459.67, 1000),
    "outletPressurePsia": (0, 100000), "liquidRateStbDay": (0, 1000000),
}


def validate_inputs(config):
    expected = set(FIELD_INPUTS) | {"schemaVersion", "unitsSystem", "study"}
    if not isinstance(config, dict) or set(config) != expected:
        raise ValueError("Supply exactly the declared input fields; no implicit defaults")
    if config["schemaVersion"] != "pipesim-template-profile-inputs/1" or config["unitsSystem"] != "PIPESIM_FIELD":
        raise ValueError("Only the explicit FIELD input contract is supported")
    study = config["study"]
    if not isinstance(study, str) or not study.strip() or len(study) > 64 or any(ord(c) < 32 for c in study):
        raise ValueError("Study must be an explicit valid name")
    for name, (lower, upper) in FIELD_INPUTS.items():
        value = config[name]
        inclusive_zero = name in {"gorScfStb", "waterCutPercent"}
        if (isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value)
                or value > upper or (value < lower if inclusive_zero else value <= lower)):
            raise ValueError(f"Invalid finite FIELD value: {name}")
    return dict(config)


def parameter_maps(config, definitions):
    p, c = definitions.Parameters, definitions.Constants
    return (
        {p.BlackOilFluid.API: config["oilApi"],
         p.BlackOilFluid.GASSPECIFICGRAVITY: config["gasSpecificGravity"],
         p.BlackOilFluid.WATERSPECIFICGRAVITY: config["waterSpecificGravity"],
         p.BlackOilFluid.GOR: config["gorScfStb"],
         p.BlackOilFluid.WATERCUT: config["waterCutPercent"]},
        {p.Completion.RESERVOIRPRESSURE: config["reservoirPressurePsia"],
         p.Completion.RESERVOIRTEMPERATURE: config["reservoirTemperatureDegF"]},
        {p.PTProfileSimulation.OUTLETPRESSURE: config["outletPressurePsia"],
         p.PTProfileSimulation.LIQUIDFLOWRATE: config["liquidRateStbDay"],
         p.PTProfileSimulation.FLOWRATETYPE: c.FlowRateType.LIQUIDFLOWRATE,
         p.PTProfileSimulation.CALCULATEDVARIABLE: c.CalculatedVariable.INLETPRESSURE},
    )


def verify_values(model, context, expected):
    actual = {key: model.get_value(context=context, parameter=key) for key in expected}
    for key, value in expected.items():
        got = actual[key]
        if isinstance(got, bool) or not isinstance(got, (int, float)) or not math.isfinite(got) or not math.isclose(got, value, rel_tol=1e-9, abs_tol=1e-9):
            raise ValueError(f"Saved parameter readback mismatch: {context}/{key}")
    return actual


def normalize_native_profile(profile, normalize_profile):
    """Check actual native fields before a legacy normalizer could fill missing temperature."""
    import pandas as pd
    if not isinstance(profile, dict) or len(profile) != 1:
        raise ValueError("Expected exactly one native Profile case")
    case = next(iter(profile.values()))
    rows = case.to_dict(orient="records") if hasattr(case, "to_dict") else pd.DataFrame.from_dict(case).to_dict(orient="records")
    if not rows or len(rows) > 4096:
        raise ValueError("Empty or oversized native Profile")
    for row in rows:
        fields = {str(key).split(".")[-1].lower(): value for key, value in row.items()}
        for name in ("pressure", "temperature", "elevation"):
            value = fields.get(name)
            if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
                raise ValueError(f"Missing finite native Profile field: {name}")
    points = normalize_profile(profile)
    if len(points) != len(rows):
        raise ValueError("Profile normalization discarded native samples")
    return points


def run_template_profile(model_api, definitions, output, well, config, normalize_profile):
    config = validate_inputs(config)  # reject bad input before creating any model
    created = create_template(model_api, output, well)
    if len(created["completionContexts"]) != 1 or len(created["tubingContexts"]) != 1:
        raise ValueError("Only the single-completion/tubing official template is supported")
    completion = created["completionContexts"][0]
    fluid = "ConsoleBlackOil"
    fluid_values, completion_values, conditions = parameter_maps(config, definitions)
    association = definitions.Parameters.Well.ASSOCIATEDBLACKOILFLUID
    model = model_api.open(str(output))
    try:
        model.add(definitions.ModelComponents.BLACKOILFLUID, fluid, parameters=fluid_values)
        model.set_value(context=well, parameter=association, value=fluid)
        model.set_value(context=completion, parameter=association, value=fluid)
        for key, value in completion_values.items():
            model.set_value(context=completion, parameter=key, value=value)
        model.tasks.ptprofilesimulation.set_conditions(producer=well, study=config["study"], parameters=conditions)
        model.save(str(output))
    finally:
        model.close()
    model = model_api.open(str(output))
    try:
        if model.about.unit_system != definitions.Units.FIELD:
            raise ValueError("Reopened model has a different unit system")
        readback = {"fluid": verify_values(model, fluid, fluid_values),
                    "completion": verify_values(model, completion, completion_values)}
        for context in (well, completion):
            if model.get_value(context=context, parameter=association) != fluid:
                raise ValueError("Saved fluid association readback mismatch")
        task = model.tasks.ptprofilesimulation
        stored = task.get_conditions(producer=well, study=config["study"])
        for key, value in conditions.items():
            if stored.get(key) != value:
                raise ValueError(f"Saved Profile condition readback mismatch: {key}")
        issues = list(model.validate()) + list(task.validate(producer=well, study=config["study"]))
        evidence = {"schemaVersion": "pipesim-template-profile-preflight/1", "template": "Simple vertical",
                    "geometryOrigin": "official-template-inherited",
                    "fluidOrigin": "explicit-scalar-inputs-with-installed-SDK-default-correlations", "well": well,
                    "inputs": config, "readback": readback, "conditionsReadback": stored,
                    "modelDiagnostics": [diagnostic(issue) for issue in issues],
                    "calculationVerified": False, "platformVerified": False}
        if issues:
            return evidence
        pv = definitions.ProfileVariables
        result = task.run(producer=well, study=config["study"],
                          profile_variables=[pv.TEMPERATURE, pv.PRESSURE, pv.ELEVATION, pv.TOTAL_DISTANCE])
        points = normalize_native_profile(result.profile, normalize_profile)
        if str(result.state) != "Completed" or not points or any(
                not isinstance(point.get(key), (int, float)) or not math.isfinite(point[key])
                for point in points for key in ("depth", "pressure", "temperature")):
            raise ValueError("Native task did not produce a completed finite Profile")
        evidence.update(calculationVerified=True, nativeState=str(result.state), profile=points,
                        resultUnits="unspecified; no inferred conversion")
        return evidence
    finally:
        model.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ptk", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--inputs", required=True, help="Explicit JSON contract; no field defaults")
    parser.add_argument("--well", default="ConsoleWell")
    args = parser.parse_args()
    config = validate_inputs(json.loads(Path(args.inputs).read_text(encoding="utf-8-sig")))
    # Reuse the existing read-only normalization helper; do not import the run dispatcher.
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "worker"))
    from ptk_normalization import normalize_profile
    with toolkit_runtime(args.ptk):
        import sixgill.definitions as definitions
        from sixgill.pipesim import Model
        result = run_template_profile(Model, definitions, args.output, args.well, config, normalize_profile)
        print(json.dumps(result, ensure_ascii=False, allow_nan=False))
        return 0 if result["calculationVerified"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
