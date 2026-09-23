import types
import unittest
from unittest.mock import patch
from worker.ptk_run import validate_scenario, apply_scenario, AdapterFailure


class ScenarioTests(unittest.TestCase):
    def test_strict_parameters(self):
        valid = {'schemaVersion': 'pipesim-well-parameters/1', 'reservoirPressurePsi': 4000}
        validate_scenario(valid, 'nodal')
        for task in ['profile', 'combined']:
            validate_scenario(valid, task)
        for task in ['network', 'eclipse']:
            validate_scenario(None, task)
            with self.assertRaises(AdapterFailure):
                validate_scenario(valid, task)
        system_analysis = {
            'schemaVersion': 'pipesim-system-analysis-parameters/1',
            'producer': 'Well',
            'branchTerminator': 'FL-2',
            'outletPressurePsi': 600,
            'scanVariable': 'liquidFlowRate',
            'values': [2400, 3000, 3600],
        }
        validate_scenario(system_analysis, 'system-analysis')
        with self.assertRaises(AdapterFailure):
            validate_scenario(None, 'system-analysis')
        for value in [True, '4000', None, 0, -1, 100001, float('nan'), float('inf')]:
            with self.assertRaises(AdapterFailure):
                validate_scenario(dict(valid, reservoirPressurePsi=value), 'nodal')
        with self.assertRaises(AdapterFailure):
            validate_scenario(dict(valid, arbitrary=1), 'nodal')

    def test_units_and_readback(self):
        class Model:
            value = 3000
            unit = 'psia'
            ignore = False
            writes = 0

            def describe(self, **kwargs):
                return types.SimpleNamespace(units_symbol=self.unit)

            def get_value(self, *args, **kwargs):
                return self.value

            def set_value(self, *args, **kwargs):
                self.writes += 1
                if not self.ignore:
                    self.value = kwargs['value']

        definitions = types.ModuleType('sixgill.definitions')
        definitions.Parameters = types.SimpleNamespace(Completion=types.SimpleNamespace(RESERVOIRPRESSURE='ReservoirPressure'))
        with patch.dict('sys.modules', {'sixgill.definitions': definitions}):
            model = Model()
            apply_scenario(model, 'Completion', {'reservoirPressurePsi': 4000})
            self.assertEqual(4000, model.value)
            model.unit = 'bara'
            with self.assertRaises(AdapterFailure) as error:
                apply_scenario(model, 'Completion', {'reservoirPressurePsi': 5000})
            self.assertEqual('PARAMETER_UNIT_UNSUPPORTED', error.exception.code)
            self.assertEqual(1, model.writes)
            model.unit = 'psia'
            model.ignore = True
            with self.assertRaises(AdapterFailure) as error:
                apply_scenario(model, 'Completion', {'reservoirPressurePsi': 5000})
            self.assertEqual('PARAMETER_READBACK_MISMATCH', error.exception.code)
