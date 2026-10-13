import types
import unittest
from unittest.mock import patch

from worker import ptk_run
from worker.ptk_gas_lift import discover_gas_lift


class GasLiftTask:
    def __init__(self, axis=True, reject=None, issues=None):
        self.axis, self.reject, self.issues = axis, reject, issues
        self.calls = []

    def get_sensitivity_variables(self, **kwargs):
        self.calls.append(('discover', kwargs))
        return {'Gas lift data': ['TargetInjectionRate']} if self.axis else {'GL Port': ['GLR']}

    def set_conditions(self, **kwargs):
        self.calls.append(('set', kwargs))
        if self.reject:
            raise self.reject

    def validate(self, **kwargs):
        self.calls.append(('validate', kwargs))
        return self.issues

    def run(self, **kwargs):
        self.calls.append(('run', kwargs))
        return 'native-result-placeholder'


class GasLiftPreflightTests(unittest.TestCase):
    def setUp(self):
        defs = types.ModuleType('sixgill.definitions')
        defs.Parameters = types.SimpleNamespace(Well=types.SimpleNamespace(
            USEGASLIFTVALVE='UseGasliftValve', TARGETINJECTIONRATE='TargetInjectionRate'))
        defs.Units = types.SimpleNamespace(FIELD='Field')
        gas_keys = ['OUTLETPRESSURE', 'DIAGNOSTICSTYPE', 'THROTTLING', 'TARGETINJECTIONRATE',
                    'SURFACEINJECTIONTEMPERATURE', 'PRODUCER', 'USEPHASERATIO', 'SENSITIVITYVARIABLE']
        defs.Parameters.GLDiagnosticsSimulation = types.SimpleNamespace(**{key: key for key in gas_keys},
            SensitivityVariable=types.SimpleNamespace(COMPONENT='Component', VARIABLE='Variable', VALUES='Values'))
        defs.Parameters.Boundary = types.SimpleNamespace(GOR='GOR', WATERCUT='WaterCut', PRESSURE='Pressure')
        defs.Constants = types.SimpleNamespace(GasLift=types.SimpleNamespace(
            DiagnosticsOperationDiagnostics=types.SimpleNamespace(FIXEDINJECTION='FixedInjection'),
            DiagnosticsOperationThrottling=types.SimpleNamespace(ON='On')))
        defs.SystemVariables = types.SimpleNamespace(OUTLET_VOLUME_FLOWRATE_LIQUID_STOCKTANK='LiquidRate')
        self.sdk = patch.dict('sys.modules', {'sixgill.definitions': defs})
        self.sdk.start()
        self.addCleanup(self.sdk.stop)

    def model(self, task, valve=True):
        return types.SimpleNamespace(tasks=types.SimpleNamespace(gldiagnosticssimulation=task),
                                     about=types.SimpleNamespace(unit_system='Field'),
                                     get_value=lambda **kw: valve)

    def execute(self, model):
        return ptk_run._run_gas_lift_task(model, 'Well_2', 'Study 2', {'pressure': 1},
                                        {'Well_2:INFLOW': {}}, [], lambda *_: None)

    def test_selected_study_and_well_bind_every_sdk_call_and_validation_precedes_run(self):
        task = GasLiftTask(issues=[])
        self.execute(self.model(task))
        self.assertEqual(['discover', 'set', 'validate', 'run'], [name for name, _ in task.calls])
        for _, args in task.calls:
            self.assertEqual('Study 2', args['study'])
            self.assertEqual('Well_2', args['producer'])
        self.assertTrue(task.calls[2][1]['validate_model'])

    def test_port_ratio_is_not_injection_rate_and_never_reaches_set_or_solver(self):
        task = GasLiftTask(axis=False)
        with self.assertRaises(ptk_run.AdapterFailure) as error:
            self.execute(self.model(task, valve=False))
        self.assertEqual('GAS_LIFT_VALVE_REQUIRED', error.exception.code)
        self.assertEqual(['discover'], [name for name, _ in task.calls])

    def test_valve_without_native_axis_is_rejected(self):
        task = GasLiftTask(axis=False)
        with self.assertRaises(ptk_run.AdapterFailure) as error:
            self.execute(self.model(task))
        self.assertEqual('GAS_LIFT_TARGET_AXIS_UNAVAILABLE', error.exception.code)
        self.assertFalse(any(name == 'run' for name, _ in task.calls))

    def test_unknown_flag_is_not_truthy_support(self):
        for invalid in [None, 1, 'true']:
            task = GasLiftTask()
            self.assertEqual('unknown', discover_gas_lift(self.model(task, invalid), 'Well_2', 'Study 2')['state'])
            with self.assertRaises(ptk_run.AdapterFailure):
                self.execute(self.model(task, invalid))
            self.assertEqual([], task.calls)

    def test_parameter_rejection_keeps_sanitized_cause_without_running(self):
        task = GasLiftTask(reject=ValueError(r'Unsupported axis C:\private\model.pips token=abc'))
        with self.assertRaises(ptk_run.AdapterFailure) as error:
            self.execute(self.model(task))
        failure = error.exception
        self.assertEqual('GAS_LIFT_PARAMETERS_REJECTED', failure.code)
        text = failure.diagnostics[0]['message']
        self.assertIn('Unsupported axis', text)
        self.assertNotIn('private', text)
        self.assertNotIn('abc', text)
        self.assertEqual(['discover', 'set'], [name for name, _ in task.calls])

    def test_license_rejection_remains_license_not_generic_parameter_error(self):
        task = GasLiftTask(reject=RuntimeError('license unavailable'))
        with self.assertRaises(ptk_run.AdapterFailure) as error:
            self.execute(self.model(task))
        self.assertEqual('LICENSE_UNAVAILABLE', error.exception.code)
        self.assertTrue(error.exception.retryable)
        self.assertFalse(any(name == 'run' for name, _ in task.calls))

    def test_validation_issues_and_missing_status_never_start_solver(self):
        for issues, code in [(None, 'GAS_LIFT_VALIDATION_UNAVAILABLE'),
                             ([{'path': 'Well_2', 'property_name': 'Pressure', 'message': 'Required'}], 'GAS_LIFT_VALIDATION_FAILED')]:
            task = GasLiftTask(issues=issues)
            with self.assertRaises(ptk_run.AdapterFailure) as error:
                self.execute(self.model(task))
            self.assertEqual(code, error.exception.code)
            self.assertEqual(['discover', 'set', 'validate'], [name for name, _ in task.calls])

    def test_discovery_failure_stays_unknown_not_supported(self):
        task = GasLiftTask()
        task.get_sensitivity_variables = lambda **_: (_ for _ in ()).throw(ValueError('bad Study'))
        self.assertEqual('unknown', discover_gas_lift(self.model(task), 'Well_2', 'Study 2')['state'])
        with self.assertRaises(ptk_run.AdapterFailure) as error:
            self.execute(self.model(task))
        self.assertEqual('GAS_LIFT_CAPABILITY_UNKNOWN', error.exception.code)
        self.assertIn('bad Study', error.exception.diagnostics[0]['message'])

    def test_metric_model_is_not_mislabeled_as_field_units(self):
        task = GasLiftTask(issues=[])
        model = self.model(task)
        model.about.unit_system = 'Metric'
        with self.assertRaises(ptk_run.AdapterFailure) as error:
            self.execute(model)
        self.assertEqual('GAS_LIFT_UNITS_UNSUPPORTED', error.exception.code)
        self.assertEqual([], task.calls)

    def performance(self, injections):
        task = GasLiftTask(issues=[])
        simulation = types.SimpleNamespace(state='Completed', cases=list(injections), system={
            'Gas lift data-TargetInjectionRate': injections,
            'OutletVolumeFlowrateLiquidStockTank': {name: 1000 for name in injections}})
        task.run = lambda **kw: simulation
        parameters = {'schemaVersion': 'pipesim-gas-lift-performance-parameters/1', 'producer': 'Well_2',
                      'outletPressurePsi': 151, 'surfaceInjectionTemperatureF': 110,
                      'targetInjectionRateMmscfd': 1.1, 'reservoirPressurePsi': 1700,
                      'gorScfPerStb': 400, 'waterCutPercent': 80, 'valuesMmscfd': [0.55, 1.1]}
        result = ptk_run._execute_gas_lift_performance(self.model(task),
            {'Well': ['Well_2'], 'Completion': ['Well_2:ActualCompletion']},
            'black_oil_liquid', parameters, lambda *_: None, study='Study 2')
        return result, task

    def test_performance_binds_actual_completion_and_all_requested_axis_values(self):
        result, task = self.performance({'A': 0.55, 'B': 1.1})
        self.assertEqual('VALID_FULL', result['resultContract'])
        self.assertEqual([0.55, 1.1], [case['injectionRateMmscfd'] for case in result['cases']])
        set_call = next(args for name, args in task.calls if name == 'set')
        self.assertEqual(['Well_2:ActualCompletion'], list(set_call['boundaries']))
        self.assertEqual('Study 2', set_call['study'])

    def test_completed_but_missing_or_changed_cases_are_not_full_success(self):
        for injections in [{'A': 0.55, 'B': 1.2}, {'A': 0.55, 'B': 1.1, 'C': 1.2}]:
            with self.assertRaises(ptk_run.AdapterFailure) as error:
                self.performance(injections)
            self.assertEqual('INCOMPLETE_GAS_LIFT_PERFORMANCE_RESULT', error.exception.code)


if __name__ == '__main__':
    unittest.main()
