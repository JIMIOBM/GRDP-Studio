import math
import numbers


def inspect_well_pressure(model, completion, parameter):
    inspection = {'schemaVersion': 'pipesim-well-inspection/1', 'reservoirPressure': None}
    try:
        descriptor = model.describe(context=completion, parameter=parameter)
        if descriptor.units_symbol != 'psia':
            return inspection
        value = model.get_value(completion, parameter=parameter)
        if isinstance(value, bool) or not isinstance(value, numbers.Real):
            return inspection
        number = float(value)
        if math.isfinite(number) and number > 0 and not math.isclose(number, 1.2345e25, rel_tol=1e-12):
            inspection['reservoirPressure'] = {'value': number, 'unit': 'psia'}
    except Exception:
        # Optional metadata must not change the model's validation outcome.
        pass
    return inspection
