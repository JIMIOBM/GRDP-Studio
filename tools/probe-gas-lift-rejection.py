"""Negative native adapter probe: caller supplies an already isolated copy.

Never saves a model. A supported model is NOT accepted by this negative test;
use the main API acceptance script for real positive calculation evidence.
"""
import argparse
import contextlib
import io
import json
import sys
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--isolated-copy', required=True)
parser.add_argument('--ptkzip', required=True)
args = parser.parse_args()
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'worker'))
sys.path.insert(0, args.ptkzip)
from ptk_run import AdapterFailure, _execute_gas_lift_performance
from ptk_gas_lift import discover_gas_lift
from sixgill.pipesim import Model

events = []
model = None
with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
    try:
        model = Model.open(args.isolated_copy, in_memory_results=True)
        components = {kind: list(model.find(component=kind)) for kind in ('Well', 'Completion')}
        producer = components['Well'][0]
        capability = discover_gas_lift(model, producer, 'Study 1', strict=True)
        if capability['state'] != 'unsupported':
            raise RuntimeError('Negative probe requires a natively unsupported valve-task model.')
        parameters = {'schemaVersion': 'pipesim-gas-lift-performance-parameters/1', 'producer': producer,
                      'outletPressurePsi': 151, 'surfaceInjectionTemperatureF': 110,
                      'targetInjectionRateMmscfd': 1.1, 'reservoirPressurePsi': 1700,
                      'gorScfPerStb': 400, 'waterCutPercent': 80, 'valuesMmscfd': [0.55, 1.1]}
        try:
            _execute_gas_lift_performance(model, components, 'black_oil_liquid', parameters,
                                          lambda state, message: events.append(state), study='Study 1')
        except AdapterFailure as failure:
            if failure.code != 'GAS_LIFT_VALVE_REQUIRED' or events:
                raise RuntimeError('Adapter did not reject the port model before solver phase.') from failure
            report = {'negativeNativeProbePassed': True, 'code': failure.code,
                      'capability': capability, 'solverPhaseEntered': False,
                      'modelSaved': False, 'diagnostics': failure.diagnostics}
        else:
            raise RuntimeError('Unsupported task unexpectedly reached calculation.')
    finally:
        if model is not None:
            model.close()
print(json.dumps(report, ensure_ascii=False, indent=2))
