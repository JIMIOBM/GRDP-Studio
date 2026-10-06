import importlib.util
import json
from pathlib import Path
import sys
from types import SimpleNamespace as NS
import unittest
from unittest.mock import Mock, patch

tools = Path(__file__).parents[1]
sys.path.insert(0, str(tools))
spec = importlib.util.spec_from_file_location("template_profile", tools / "pipesim_template_profile.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def definition_group(names):
    return NS(**{name: name for name in names.split()})


class ProfileTests(unittest.TestCase):
    def setUp(self):
        self.config = json.loads((tools / "examples/template-profile-field.json").read_text())
        self.definitions = NS(
            Parameters=NS(BlackOilFluid=definition_group("API GASSPECIFICGRAVITY WATERSPECIFICGRAVITY GOR WATERCUT"),
                          Completion=definition_group("RESERVOIRPRESSURE RESERVOIRTEMPERATURE"),
                          Well=definition_group("ASSOCIATEDBLACKOILFLUID"),
                          PTProfileSimulation=definition_group("OUTLETPRESSURE LIQUIDFLOWRATE FLOWRATETYPE CALCULATEDVARIABLE")),
            Constants=NS(FlowRateType=NS(LIQUIDFLOWRATE="Liquid"), CalculatedVariable=NS(INLETPRESSURE="Inlet")),
            ModelComponents=NS(BLACKOILFLUID="BlackOilFluid"), Units=NS(FIELD="PIPESIM_FIELD"),
            ProfileVariables=definition_group("TEMPERATURE PRESSURE ELEVATION TOTAL_DISTANCE"))
        self.writer, self.reader = Mock(), Mock()
        self.reader.about.unit_system = "PIPESIM_FIELD"
        fluid, completion, conditions = module.parameter_maps(self.config, self.definitions)
        self.reader.get_value.side_effect = lambda context, parameter: (
            "ConsoleBlackOil" if parameter == "ASSOCIATEDBLACKOILFLUID" else
            (fluid if context == "ConsoleBlackOil" else completion)[parameter])
        self.reader.validate.return_value = []
        task = self.reader.tasks.ptprofilesimulation
        task.get_conditions.return_value = conditions.copy()
        task.validate.return_value = []
        task.run.return_value = NS(state="Completed", profile={"Case 1": {
            "Pressure": [250], "Temperature": [70], "Elevation": [0]}})
        self.api = Mock(open=Mock(side_effect=[self.writer, self.reader]))
        self.normalizer = Mock(return_value=[{"depth": 0, "pressure": 250, "temperature": 70}])

    def run_gate(self):
        with patch.object(module, "create_template", return_value={
                "completionContexts": ["ConsoleWell:Completion"], "tubingContexts": ["ConsoleWell:Tubing"]}):
            return module.run_template_profile(self.api, self.definitions, "new.pips", "ConsoleWell", self.config, self.normalizer)

    def test_success_requires_saved_values_and_native_result_not_platform_claim(self):
        result = self.run_gate()
        self.assertTrue(result["calculationVerified"])
        self.assertFalse(result["platformVerified"])
        self.writer.save.assert_called_once_with("new.pips")
        self.writer.close.assert_called_once()
        self.reader.close.assert_called_once()
        self.assertEqual(result["inputs"], self.config)

    def test_unknown_missing_bool_nan_units_and_bounds_fail_before_creation(self):
        cases = []
        for key, value in [("extra", 1), ("oilApi", True), ("gorScfStb", float("nan")),
                           ("unitsSystem", "PIPESIM_SI"), ("waterCutPercent", 101), ("liquidRateStbDay", 0)]:
            config = dict(self.config)
            config[key] = value
            cases.append(config)
        missing = dict(self.config)
        del missing["gasSpecificGravity"]
        cases.append(missing)
        for config in cases:
            with self.subTest(config=config), patch.object(module, "create_template") as create:
                with self.assertRaises(ValueError):
                    module.run_template_profile(self.api, self.definitions, "new.pips", "ConsoleWell", config, self.normalizer)
                create.assert_not_called()

    def test_diagnostic_blocks_run_and_is_preserved(self):
        self.reader.validate.return_value = ["No associated fluid"]
        result = self.run_gate()
        self.assertFalse(result["calculationVerified"])
        self.assertEqual(result["modelDiagnostics"][0]["message"], "No associated fluid")
        self.reader.tasks.ptprofilesimulation.run.assert_not_called()
        self.reader.close.assert_called_once()

    def test_mismatched_condition_or_association_blocks_native_run(self):
        self.reader.tasks.ptprofilesimulation.get_conditions.return_value = {}
        with self.assertRaises(ValueError):
            self.run_gate()
        self.reader.tasks.ptprofilesimulation.run.assert_not_called()
        self.reader.close.assert_called_once()

    def test_failed_write_closes_writer_and_does_not_reopen(self):
        self.writer.add.side_effect = RuntimeError("native add failed")
        with self.assertRaises(RuntimeError):
            self.run_gate()
        self.writer.close.assert_called_once()
        self.reader.close.assert_not_called()

    def test_wrong_unit_or_saved_fluid_value_blocks_run(self):
        self.reader.about.unit_system = "PIPESIM_SI"
        with self.assertRaises(ValueError):
            self.run_gate()
        self.reader.tasks.ptprofilesimulation.run.assert_not_called()
        self.setUp()
        self.reader.get_value.side_effect = None
        self.reader.get_value.return_value = float("nan")
        with self.assertRaises(ValueError):
            self.run_gate()
        self.reader.tasks.ptprofilesimulation.run.assert_not_called()

    def test_saved_fluid_association_mismatch_blocks_run(self):
        original = self.reader.get_value.side_effect
        self.reader.get_value.side_effect = lambda context, parameter: (
            "AnotherFluid" if parameter == "ASSOCIATEDBLACKOILFLUID" else original(context, parameter))
        with self.assertRaises(ValueError):
            self.run_gate()
        self.reader.tasks.ptprofilesimulation.run.assert_not_called()
        self.reader.close.assert_called_once()

    def test_empty_or_unfinished_profile_is_never_success(self):
        for state, points in [("Failed", [{"depth": 0, "pressure": 250, "temperature": 70}]), ("Completed", [])]:
            self.setUp()
            self.reader.tasks.ptprofilesimulation.run.return_value.state = state
            self.normalizer.return_value = points
            with self.subTest(state=state), self.assertRaises(ValueError):
                self.run_gate()
            self.reader.close.assert_called_once()

    def test_missing_temperature_cannot_be_filled_in_as_success(self):
        self.reader.tasks.ptprofilesimulation.run.return_value.profile = {
            "Case 1": {"Pressure": [250], "Elevation": [0]}}
        with self.assertRaises(ValueError):
            self.run_gate()
        self.normalizer.assert_not_called()
        self.reader.close.assert_called_once()


if __name__ == "__main__":
    unittest.main()
