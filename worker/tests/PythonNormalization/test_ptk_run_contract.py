import copy
import json
import subprocess
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch

from worker import ptk_run


ROOT = Path(__file__).resolve().parents[3]
COMPARATOR = ROOT / "worker/tests/Golden/Compare-RunToGolden.ps1"
GOLDEN = ROOT / "docs/software-integration/golden/pipesim-well-result-v1/CSW_101/nodal.json"


class Named:
    def __init__(self, name):
        self.name = name


class Catalog:
    def __init__(self, studies):
        self.studies = studies

    def lookup_entries_by_class_ids(self, _):
        return [Named(name) for name in self.studies]


class Curve:
    def __init__(self, data):
        self.curve_data = data


class NodalResult:
    def __init__(self, gas=False):
        rate = "GasRate" if gas else "LiquidRate"
        self.inflow_curves = [Curve([{rate: 2, "Pressure": 20}])]
        self.outflow_curves = [Curve([{rate: 3, "Pressure": 30}])]


class ProfileResult:
    profile = {"Well_1": [{"MeasuredDepth": -10, "Pressure": 100, "Temperature": 60}]}


class Runnable:
    def __init__(self, callback):
        self.callback = callback

    def run(self, **kwargs):
        return self.callback(kwargs)


class FakeModel:
    def __init__(self, profile_failure=None, nodal_failure=None, studies=None, gas=False):
        self._catalog = Catalog(studies or ["Study 1"])
        self.gas = gas
        self.fluids = types.SimpleNamespace(fluid_type="compositional" if gas else "blackoil")
        self.closed = False
        self.tasks = types.SimpleNamespace(
            nodalanalysis=Runnable(
                lambda _: (_ for _ in ()).throw(nodal_failure)
                if nodal_failure is not None
                else NodalResult(gas)
            ),
            ptprofilesimulation=Runnable(
                lambda _: (_ for _ in ()).throw(profile_failure)
                if profile_failure is not None
                else ProfileResult()
            ),
        )

    def find(self, component):
        return {
            "Well": ["Well_1"],
            "BlackOilFluid": ["Fluid_1"],
            "Completion": ["Completion_1"],
            "Tubing": ["Tubing_1"],
        }[component]

    def get_value(self, component, parameter=None):
        if parameter == "geometry":
            return "vertical"
        if parameter == "associated_fluid":
            return "Fluid_1"
        raise AssertionError("Unexpected model read")

    def set_value(self, *args, **kwargs):
        raise AssertionError("Demo-01 must never overwrite model parameters")

    def close(self):
        self.closed = True


def fake_sixgill_modules():
    definitions = types.ModuleType("sixgill.definitions")
    definitions.Constants = types.SimpleNamespace(
        FluidType=types.SimpleNamespace(COMPOSITIONAL="compositional", BLACKOIL="blackoil"),
        CalculatedVariable=types.SimpleNamespace(FLOWRATE="flowrate"),
        FlowRateType=types.SimpleNamespace(GASFLOWRATE="gasflowrate"),
    )
    definitions.Parameters = types.SimpleNamespace(
        Completion=types.SimpleNamespace(GEOMETRYPROFILETYPE="geometry"),
        Well=types.SimpleNamespace(ASSOCIATEDBLACKOILFLUID="associated_fluid"),
        PTProfileSimulation=types.SimpleNamespace(
            CALCULATEDVARIABLE="calculated_variable",
            FLOWRATETYPE="flow_rate_type",
        ),
    )
    resources = types.ModuleType("sixgill.core.resources")
    resources.ModelClasses = types.SimpleNamespace(STUDY="study")
    return {
        "sixgill": types.ModuleType("sixgill"),
        "sixgill.definitions": definitions,
        "sixgill.core": types.ModuleType("sixgill.core"),
        "sixgill.core.resources": resources,
    }


class PtkRunContractTests(unittest.TestCase):
    def execute(self, run_task, model=None, parameters=None):
        events = []
        model = model or FakeModel()
        with tempfile.NamedTemporaryFile(suffix=".pips") as model_file:
            with patch.dict(sys.modules, fake_sixgill_modules()):
                envelope = ptk_run.execute_request(
                    {
                        "modelPath": model_file.name,
                        "study": "Study 1",
                        "runTask": run_task,
                        "parameters": parameters,
                    },
                    model_factory=lambda _: model,
                    emit_event=lambda state, message: events.append((state, message)),
                )
        return envelope, events, model

    def test_rejects_non_null_parameters_without_opening_model(self):
        opened = []
        with tempfile.NamedTemporaryFile(suffix=".pips") as model_file:
            envelope = ptk_run.execute_request(
                {
                    "modelPath": model_file.name,
                    "study": "Study 1",
                    "runTask": "nodal",
                    "parameters": {},
                },
                model_factory=lambda _: opened.append(True),
            )
        self.assertEqual("error", envelope["status"])
        self.assertEqual("PARAMETERS_NOT_NULL", envelope["error"]["code"])
        self.assertEqual([], opened)

    def test_nodal_contract_and_event_order(self):
        envelope, events, model = self.execute("nodal")
        self.assertEqual("ok", envelope["status"])
        self.assertEqual(["RUNNING_NODAL", "COLLECTING"], [item[0] for item in events])
        self.assertEqual("VALID_FULL", envelope["result"]["resultContract"])
        self.assertEqual([], envelope["result"]["profile"])
        self.assertIsNone(envelope["result"]["units"]["flow"]["displayUnit"])
        self.assertTrue(model.closed)

    def test_profile_contract_and_event_order(self):
        envelope, events, _ = self.execute("profile")
        self.assertEqual("ok", envelope["status"])
        self.assertEqual(["RUNNING_PROFILE", "COLLECTING"], [item[0] for item in events])
        self.assertEqual([], envelope["result"]["ipr"])
        self.assertEqual(10.0, envelope["result"]["profile"][0]["depth"])

    def test_basic_gas_uses_frozen_standard_gas_flow_unit(self):
        envelope, _, _ = self.execute("nodal", FakeModel(gas=True))
        self.assertEqual("ok", envelope["status"])
        self.assertEqual("basic_gas", envelope["result"]["model_kind"])
        self.assertEqual("mmscf/d", envelope["result"]["units"]["flow"]["displayUnit"])
        self.assertEqual(
            "standard_gas_volume_rate",
            envelope["result"]["units"]["flow"]["semantics"],
        )

    def test_combined_contract_and_phase_order(self):
        envelope, events, _ = self.execute("combined")
        self.assertEqual("ok", envelope["status"])
        self.assertEqual(
            ["RUNNING_NODAL", "RUNNING_PROFILE", "COLLECTING"],
            [item[0] for item in events],
        )
        self.assertEqual("VALID_FULL", envelope["result"]["resultContract"])

    def test_combined_profile_failure_preserves_nodal_partial(self):
        envelope, events, _ = self.execute(
            "combined", FakeModel(profile_failure=RuntimeError("profile solver failed"))
        )
        self.assertEqual("partial", envelope["status"])
        self.assertEqual("VALID_PARTIAL", envelope["result"]["resultContract"])
        self.assertTrue(envelope["result"]["ipr"])
        self.assertTrue(envelope["result"]["vlp"])
        self.assertEqual([], envelope["result"]["profile"])
        self.assertEqual("PROFILE_RUN_FAILED", envelope["warnings"][0]["code"])
        self.assertEqual("COLLECTING", events[-1][0])

    def test_profile_failure_and_license_failure_are_structured(self):
        profile, _, _ = self.execute("profile", FakeModel(profile_failure=RuntimeError("failed")))
        licensed, _, _ = self.execute("nodal", FakeModel(nodal_failure=RuntimeError("license checkout failed")))
        self.assertEqual("PROFILE_RUN_FAILED", profile["error"]["code"])
        self.assertEqual("LICENSE", licensed["error"]["category"])
        self.assertEqual("LICENSE_UNAVAILABLE", licensed["error"]["code"])
        self.assertTrue(licensed["error"]["retryable"])

    def test_requires_an_existing_study(self):
        envelope, _, _ = self.execute("nodal", FakeModel(studies=["Other Study"]))
        self.assertEqual("STUDY_NOT_FOUND", envelope["error"]["code"])


class GoldenComparatorTests(unittest.TestCase):
    def run_comparator(self, actual):
        with tempfile.TemporaryDirectory() as temporary:
            actual_path = Path(temporary) / "actual.json"
            actual_path.write_text(json.dumps(actual), encoding="utf-8")
            return subprocess.run(
                [
                    "pwsh",
                    "-NoProfile",
                    "-File",
                    str(COMPARATOR),
                    "-ActualResult",
                    str(actual_path),
                    "-GoldenResult",
                    str(GOLDEN),
                ],
                capture_output=True,
                text=True,
                check=False,
            )

    def test_comparator_accepts_exact_result(self):
        actual = json.loads(GOLDEN.read_text(encoding="utf-8"))
        completed = self.run_comparator(actual)
        self.assertEqual(0, completed.returncode, completed.stderr)

    def test_comparator_rejects_value_and_order_changes(self):
        actual = json.loads(GOLDEN.read_text(encoding="utf-8"))
        changed_value = copy.deepcopy(actual)
        changed_value["ipr"][0]["flow"] += 0.000001
        self.assertNotEqual(0, self.run_comparator(changed_value).returncode)
        changed_order = copy.deepcopy(actual)
        changed_order["vlp"][0], changed_order["vlp"][1] = changed_order["vlp"][1], changed_order["vlp"][0]
        self.assertNotEqual(0, self.run_comparator(changed_order).returncode)

    def test_comparator_rejects_schema_category_units_and_length_changes(self):
        actual = json.loads(GOLDEN.read_text(encoding="utf-8"))
        changed_schema = copy.deepcopy(actual)
        changed_schema["schemaVersion"] = "pipesim-well-result/other"
        self.assertNotEqual(0, self.run_comparator(changed_schema).returncode)
        changed_category = copy.deepcopy(actual)
        changed_category["model_kind"] = "basic_gas"
        self.assertNotEqual(0, self.run_comparator(changed_category).returncode)
        changed_units = copy.deepcopy(actual)
        changed_units["units"]["flow"] = {
            "displayUnit": "mmscf/d",
            "semantics": "standard_gas_volume_rate",
        }
        self.assertNotEqual(0, self.run_comparator(changed_units).returncode)
        changed_length = copy.deepcopy(actual)
        changed_length["ipr"].pop()
        self.assertNotEqual(0, self.run_comparator(changed_length).returncode)


if __name__ == "__main__":
    unittest.main()
