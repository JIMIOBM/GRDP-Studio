import types
import unittest
import io
import json
import runpy
import sys
import tempfile
from pathlib import Path
from unittest.mock import Mock, patch
from worker.ptk_well_inspection import inspect_well_pressure


class WellInspectionTests(unittest.TestCase):
    def test_validation_ready_survives_optional_read_failure_and_closes_model(self):
        model = self.model()
        model._catalog.lookup_entries_by_class_ids.return_value = [types.SimpleNamespace(name='Study 1')]
        model.find.side_effect = lambda component: [component]
        model.fluids.fluid_type = 'COMPOSITIONAL'
        model.get_value.return_value = 'vertical'
        model.describe.side_effect = RuntimeError('optional descriptor unavailable')
        definitions = types.ModuleType('sixgill.definitions')
        definitions.Constants = types.SimpleNamespace(FluidType=types.SimpleNamespace(COMPOSITIONAL='COMPOSITIONAL', BLACKOIL='BLACKOIL'))
        definitions.Parameters = types.SimpleNamespace(Completion=types.SimpleNamespace(GEOMETRYPROFILETYPE='Geometry', RESERVOIRPRESSURE='Pressure'))
        pipesim = types.ModuleType('sixgill.pipesim')
        pipesim.Model = Mock()
        pipesim.Model.open.return_value = model
        resources = types.ModuleType('sixgill.core.resources')
        resources.ModelClasses = types.SimpleNamespace(STUDY='Study')
        network = types.ModuleType('ptk_network')
        network.inspect_network = lambda ignored: {'candidate': False}
        network.format_validation_issues = str
        modules = {'sixgill.pipesim': pipesim, 'sixgill.definitions': definitions,
                   'sixgill.core.resources': resources, 'ptk_network': network}
        with tempfile.NamedTemporaryFile(suffix='.pips') as source:
            output = io.StringIO()
            with patch.dict(sys.modules, modules), patch.object(sys, 'argv', ['ptk_validate.py', source.name]), \
                    patch.object(sys, 'stdout', output), patch.dict('os.environ', {'GRDP_PTK_START_GATED': '0'}):
                runpy.run_path(str(Path(__file__).resolve().parents[2] / 'ptk_validate.py'))
        envelope = json.loads(output.getvalue())
        self.assertEqual('READY', envelope['status'])
        self.assertEqual('basic_gas', envelope['modelKind'])
        self.assertEqual(['Study 1'], envelope['studies'])
        self.assertIsNone(envelope['inspection']['reservoirPressure'])
        model.close.assert_called_once()
        model.set_value.assert_not_called()
        model.save.assert_not_called()

    def model(self, value=4000, unit='psia'):
        model = Mock()
        model.describe.return_value = types.SimpleNamespace(units_symbol=unit)
        model.get_value.return_value = value
        return model

    def test_read_only_exact_units_and_no_scenario_limit(self):
        model = self.model(100001)
        self.assertEqual({'schemaVersion': 'pipesim-well-inspection/1',
                          'reservoirPressure': {'value': 100001.0, 'unit': 'psia'}},
                         inspect_well_pressure(model, 'Completion', 'ReservoirPressure'))
        model.describe.assert_called_once_with(context='Completion', parameter='ReservoirPressure')
        model.get_value.assert_called_once_with('Completion', parameter='ReservoirPressure')
        model.set_value.assert_not_called()
        model.save.assert_not_called()

    def test_unavailable_values_and_units_remain_optional(self):
        for value in [None, True, '4000', 0, -1, -1e31, 1.2345e25, float('nan'), float('inf')]:
            self.assertIsNone(inspect_well_pressure(self.model(value), 'C', 'P')['reservoirPressure'])
        for unit in ['psi', 'bara', None, 'PSIA']:
            model = self.model(unit=unit)
            self.assertIsNone(inspect_well_pressure(model, 'C', 'P')['reservoirPressure'])
            model.get_value.assert_not_called()
        for operation in ['describe', 'get_value']:
            model = self.model()
            getattr(model, operation).side_effect = RuntimeError('unavailable')
            self.assertIsNone(inspect_well_pressure(model, 'C', 'P')['reservoirPressure'])
