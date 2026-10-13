import copy
import io
import json
import runpy
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from worker.ptk_run import AdapterFailure, _execute_system_analysis, _system_analysis_parameters


def parameters(version=2, producer="Well_1"):
    return {"schemaVersion": "pipesim-system-analysis-parameters/" + str(version),
            "producer": producer, "branchTerminator": "Flowline_1", "outletPressurePsi": 600,
            "scanVariable": "liquidFlowRate", "values": [2400, 3000, 3600]}


class NativeTaskBoundary:
    """SDK boundary only: the adapter, preflight and normalizers are real code."""
    def __init__(self):
        self.conditions = {}
        self.calls = []
        self.issues = []
        self.ignore_set = False
        self.omit_profile_units = False
        self.omit_case = False
        self.pressure_unit = "psia"

    def set_conditions(self, producer, study, parameters):
        self.calls.append(("set", producer, study, copy.deepcopy(parameters)))
        if not self.ignore_set:
            self.conditions = {"Producer": producer, **parameters}

    def get_conditions(self, producer, study):
        self.calls.append(("read", producer, study))
        return copy.deepcopy(self.conditions)

    def validate(self, producer, study, validate_model=True):
        self.calls.append(("validate", producer, study))
        return self.issues

    def run(self, producer, study, parameters, **kwargs):
        self.calls.append(("run", producer, study, copy.deepcopy(parameters)))
        flow = parameters["LiquidFlowRate"]
        return types.SimpleNamespace(state="Completed",
            system={"Pressure": {"Unit": "psia", "Base Case": 1000 + flow / 10}},
            node={"Base Case": {"Pressure": {"Unit": "psia", producer: 1000 + flow / 10}}},
            profile={} if self.omit_case else {"Base Case": {"TotalDistance": [0, 100],
                "Pressure": [1000 + flow / 10, 600], "Temperature": [150, 120]}},
            profile_units={} if self.omit_profile_units else {
                "TotalDistance": "ft", "Pressure": self.pressure_unit, "Temperature": "degF"})


class NativeModelBoundary:
    def __init__(self):
        self.task = NativeTaskBoundary()
        self.tasks = types.SimpleNamespace(systemanalysissimulation=self.task)
        self.about = types.SimpleNamespace(unit_system="PIPESIM_FIELD")
        self.edges = [{"Source": "Well_1", "Destination": "N1"},
                      {"Source": "N1", "Destination": "Flowline_1"}]

    def find(self, component):
        return {"Well": ["Well_1", "Well_2"], "Flowline": ["Flowline_1", "Flowline_2"]}[component]

    def connections(self):
        return copy.deepcopy(self.edges)


def definitions():
    value = types.ModuleType("sixgill.definitions")
    value.Units = types.SimpleNamespace(FIELD="PIPESIM_FIELD")
    value.Constants = types.SimpleNamespace(
        FlowRateType=types.SimpleNamespace(LIQUIDFLOWRATE="LiquidFlowRate"),
        CalculatedVariable=types.SimpleNamespace(INLETPRESSURE="calcInletPressure"))
    value.Parameters = types.SimpleNamespace(SystemAnalysisSimulation=types.SimpleNamespace(
        BRANCHTERMINATOR="BranchTerminator", OUTLETPRESSURE="OutletPressure",
        LIQUIDFLOWRATE="LiquidFlowRate", FLOWRATETYPE="FlowRateType",
        CALCULATEDVARIABLE="CalculationVariableType"))
    value.ProfileVariables = types.SimpleNamespace(TEMPERATURE="Temperature", PRESSURE="Pressure", TOTAL_DISTANCE="TotalDistance")
    value.SystemVariables = types.SimpleNamespace(PRESSURE="Pressure", TEMPERATURE="Temperature",
        VOLUME_FLOWRATE_LIQUID_STOCKTANK="VolumeFlowrateLiquidStockTank", BOTTOM_HOLE_PRESSURE="BottomHolePressure")
    return value


class SystemAnalysisTests(unittest.TestCase):
    def execute(self, model, request=None):
        with patch.dict("sys.modules", {"sixgill.definitions": definitions()}):
            return _execute_system_analysis(model, "Study 1", request or parameters(), lambda *args: None)

    def test_v2_accepts_real_named_producer_and_keeps_v1_well_restriction(self):
        self.assertEqual("Well_1", _system_analysis_parameters(parameters())["producer"])
        with self.assertRaises(AdapterFailure):
            _system_analysis_parameters(parameters(1))
        self.assertEqual("Well", _system_analysis_parameters(parameters(1, "Well"))["producer"])

    def test_rejects_unknown_schema_unsafe_objects_and_bad_axes(self):
        for updates in [{"schemaVersion": "pipesim-system-analysis-parameters/3"}, {"producer": ""},
                        {"producer": " Well_1"}, {"producer": "Well_1\n"}, {"branchTerminator": "../other"},
                        {"outletPressurePsi": True}, {"values": [1, 1]}, {"values": [3, 2]},
                        {"values": [1, float("nan")]}, {"values": [1]}]:
            with self.subTest(updates=updates), self.assertRaises(AdapterFailure):
                _system_analysis_parameters(dict(parameters(), **updates))

    def test_runs_selected_context_and_readback_validate_precede_each_case(self):
        model = NativeModelBoundary()
        result = self.execute(model)
        self.assertEqual("pipesim-system-analysis-result/2", result["schemaVersion"])
        self.assertEqual("Well_1", result["producer"])
        self.assertEqual([2400, 3000, 3600], [c["scanValue"] for c in result["cases"]])
        self.assertEqual(["set", "read", "validate", "run"] * 3, [c[0] for c in model.task.calls])
        self.assertTrue(all(c[1:3] == ("Well_1", "Study 1") for c in model.task.calls))
        self.assertEqual("ft", result["cases"][0]["profile"]["variables"][0]["unit"])

    def test_nonexistent_or_unreachable_objects_never_set_or_run(self):
        for changes in [{"producer": "Missing"}, {"branchTerminator": "Flowline_2"}, {"branchTerminator": "Missing"}]:
            model = NativeModelBoundary()
            with self.subTest(changes=changes), self.assertRaises(AdapterFailure):
                self.execute(model, dict(parameters(), **changes))
            self.assertEqual([], model.task.calls)

    def test_metric_or_unknown_units_never_set_or_run(self):
        for unit in ["PIPESIM_SI", None, ""]:
            model = NativeModelBoundary()
            model.about.unit_system = unit
            with self.subTest(unit=unit), self.assertRaises(AdapterFailure):
                self.execute(model)
            self.assertEqual([], model.task.calls)

    def test_readback_mismatch_and_validation_issues_block_run(self):
        for cause in ["readback", "issues"]:
            model = NativeModelBoundary()
            model.task.ignore_set = cause == "readback"
            model.task.issues = [types.SimpleNamespace(message="Bad branch", path="Well_1", property_name="BranchTerminator")] if cause == "issues" else []
            with self.subTest(cause=cause), self.assertRaises(AdapterFailure):
                self.execute(model)
            self.assertNotIn("run", [c[0] for c in model.task.calls])

    def test_missing_profile_or_units_cannot_be_full_success(self):
        for cause in ["units", "case"]:
            model = NativeModelBoundary()
            model.task.omit_profile_units = cause == "units"
            model.task.omit_case = cause == "case"
            with self.subTest(cause=cause), self.assertRaises(AdapterFailure):
                self.execute(model)

    def test_wrong_dimension_and_unknown_profile_unit_cannot_be_full_success(self):
        for unit in ["STB/d", "banana", "psig", "bar"]:
            model = NativeModelBoundary()
            model.task.pressure_unit = unit
            with self.subTest(unit=unit), self.assertRaises(AdapterFailure):
                self.execute(model)


class MixedNetworkInspectionTests(unittest.TestCase):
    """Exercise real validation/discovery code with only the PTK SDK replaced."""
    def validate_multiwell(self, native_issues):
        wells = ["Well_1", "Well_2", "Well_3", "Well_4"]
        model = Mock()
        model._catalog.lookup_entries_by_class_ids.return_value = [types.SimpleNamespace(name="Study 1")]
        model.fluids.fluid_type = "BLACKOIL"

        def find(component, **filters):
            selected = [filters["Well"]] if "Well" in filters else wells
            return {
                "Well": wells, "BlackOilFluid": ["Fluid"], "Source": [], "Sink": ["Sink"],
                "Flowline": ["Flowline_" + str(index) for index in range(1, 5)], "ESP": [], "Choke": [],
                "Completion": [name + ":Completion" for name in selected],
                "Tubing": [name + ":Tubing" for name in selected],
            }.get(component, [])

        model.find.side_effect = find
        model.connections.return_value = [{"Source": "Well_1", "Destination": "Flowline_1"},
                                          {"Source": "Flowline_1", "Destination": "Sink"}]
        model.get_value.return_value = 4.0
        model.describe.return_value = types.SimpleNamespace(units_symbol="in")
        model.tasks.networksimulation.validate.return_value = native_issues
        model.tasks.networksimulation.get_conditions.return_value = {
            "Sink": {"BoundaryNodeType": "Sink", "IsActive": True, "Pressure": 600}}
        sdk_definitions = types.ModuleType("sixgill.definitions")
        sdk_definitions.Constants = types.SimpleNamespace(FluidType=types.SimpleNamespace(
            BLACKOIL="BLACKOIL", COMPOSITIONAL="COMPOSITIONAL"))
        sdk_definitions.Parameters = types.SimpleNamespace(
            Completion=types.SimpleNamespace(RESERVOIRPRESSURE="ReservoirPressure"),
            Tubing=types.SimpleNamespace(INNERDIAMETER="InnerDiameter"),
            Flowline=types.SimpleNamespace(INNERDIAMETER="InnerDiameter"))
        sdk_pipesim = types.ModuleType("sixgill.pipesim")
        sdk_pipesim.Model = Mock()
        sdk_pipesim.Model.open.return_value = model
        sdk_resources = types.ModuleType("sixgill.core.resources")
        sdk_resources.ModelClasses = types.SimpleNamespace(STUDY="Study")
        # Do not replace ptk_network, _validated_network_capability or validate.
        with tempfile.NamedTemporaryFile(suffix=".pips") as source:
            output = io.StringIO()
            with patch.dict(sys.modules, {"sixgill.pipesim": sdk_pipesim,
                                          "sixgill.definitions": sdk_definitions,
                                          "sixgill.core.resources": sdk_resources}), \
                    patch.object(sys, "argv", ["ptk_validate.py", source.name]), \
                    patch.object(sys, "stdout", output), \
                    patch.dict("os.environ", {"GRDP_PTK_START_GATED": "0"}):
                runpy.run_path(str(Path(__file__).resolve().parents[2] / "ptk_validate.py"))
        model.close.assert_called_once()
        model.set_value.assert_not_called()
        model.save.assert_not_called()
        model.tasks.networksimulation.run.assert_not_called()
        model.tasks.networksimulation.validate.assert_called_once_with(study="Study 1", validate_model=False)
        return json.loads(output.getvalue())

    def test_verified_multiwell_network_preserves_capability_without_inventing_shared_pressure(self):
        envelope = self.validate_multiwell([])
        self.assertEqual("READY", envelope["status"])
        self.assertEqual("legacy_well", envelope["modelKind"])
        self.assertIsNone(envelope["well"])
        self.assertEqual({"schemaVersion": "pipesim-well-inspection/1", "reservoirPressure": None},
                         envelope["inspection"])
        self.assertEqual(["Well_1", "Well_2", "Well_3", "Well_4"],
                         [context["context"] for context in envelope["wellContexts"]])
        capability = envelope["networkCapability"]
        self.assertEqual("pipesim-network-capability/1", capability["schemaVersion"])
        self.assertEqual(["Study 1"], capability["studies"])
        self.assertEqual(["Well_1", "Well_2", "Well_3", "Well_4"],
                         capability["networkInspection"]["wellContexts"])
        self.assertEqual(["Flowline_1", "Flowline_2", "Flowline_3", "Flowline_4"],
                         capability["networkInspection"]["flowlineContexts"])

    def test_multiwell_without_native_network_validation_does_not_advertise_capability(self):
        envelope = self.validate_multiwell([types.SimpleNamespace(message="Invalid network")])
        self.assertEqual("READY", envelope["status"])
        self.assertIsNone(envelope["well"])
        self.assertIsNone(envelope["inspection"])
        self.assertNotIn("networkCapability", envelope)


if __name__ == "__main__":
    unittest.main()
