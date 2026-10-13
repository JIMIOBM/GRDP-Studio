"""Native valve-task discovery. Call only on an isolated, unsaved model copy.

Discovery is not task validation or a promise of convergence. In particular a
GL Port/GLR (ratio) axis must never be substituted for an injection-rate axis.
"""


def discover_gas_lift(model, producer, study, strict=False):
    from sixgill.definitions import Parameters

    capability = {"well": producer, "study": study, "state": "unknown",
                  "reason": "GAS_LIFT_CAPABILITY_UNKNOWN", "useGasliftValve": None,
                  "targetInjectionRateAvailable": None}
    try:
        valve = model.get_value(context=producer, parameter=Parameters.Well.USEGASLIFTVALVE)
        if not isinstance(valve, bool):
            return capability
        capability["useGasliftValve"] = valve
        variables = model.tasks.gldiagnosticssimulation.get_sensitivity_variables(
            producer=producer, study=study)
        if not isinstance(variables, dict):
            return capability
        axes = variables.get("Gas lift data", [])
        if not isinstance(axes, (list, tuple)):
            return capability
        target_available = Parameters.Well.TARGETINJECTIONRATE in axes
        capability["targetInjectionRateAvailable"] = target_available
        if not valve:
            capability.update(state="unsupported", reason="GAS_LIFT_VALVE_REQUIRED")
        elif not target_available:
            capability.update(state="unsupported", reason="GAS_LIFT_TARGET_AXIS_UNAVAILABLE")
        else:
            capability.update(state="supported", reason="GAS_LIFT_VALVE_SUPPORTED")
    except Exception:
        # Optional discovery cannot invent support or invalidate other tasks.
        # Execution independently repeats it and classifies native failures.
        if strict:
            raise
    return capability
