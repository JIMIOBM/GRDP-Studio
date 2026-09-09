import math
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

from worker import ptk_run
from worker.ptk_network import format_validation_issues, inspect_network


class Named:
    def __init__(self, name):
        self.name = name


class Catalog:
    def lookup_entries_by_class_ids(self, _):
        return [Named("Study 1")]


class NetworkResults:
    def __init__(self):
        self.state = "Completed"
        self.system = {
            "SystemOutletPressure": {
                "Unit": "psia",
                "Line-1": 100.0,
                "Line-2": float("inf"),
            }
        }
        self.node = {
            "Pressure": {
                "Unit": "psia",
                "Source-1": 120.0,
                "Sink-1": 90.0,
            }
        }
        self.profile = {
            "Branch-1": {
                "BranchEquipment": ["Line-1", None],
                "TotalDistance": [0.0, 100.0],
                "Pressure": [120.0, 105.0],
                "Temperature": [80.0, 78.0],
            },
            "Branch-2": {
                "BranchEquipment": ["Line-2", None],
                "TotalDistance": [0.0, 200.0],
                "Pressure": [105.0, math.nan],
                "Temperature": [78.0, 75.0],
            },
        }
        self.profile_units = {
            "BranchEquipment": "",
            "TotalDistance": "ft",
            "Pressure": "psia",
            "Temperature": "degF",
        }
        self.summary = {"Info": ["converged"], "Warning": [], "Error": []}
        self.messages = ["network simulation completed"]


class NetworkTask:
    def __init__(self):
        self.validate_calls = []
        self.run_calls = []
        self.results = NetworkResults()

    def validate(self, **kwargs):
        self.validate_calls.append(kwargs)
        return []

    def run(self, **kwargs):
        self.run_calls.append(kwargs)
        return self.results


class FakeNetworkModel:
    def __init__(self, include_sink=True):
        self._catalog = Catalog()
        self.closed = False
        self.network_task = NetworkTask()
        self.tasks = types.SimpleNamespace(networksimulation=self.network_task)
        self.components = {
            "Source": ["Source-1"],
            "Sink": ["Sink-1"] if include_sink else [],
            "Flowline": ["Line-1", "Line-2"],
            "Junction": ["Junction-1"],
            "Well": [],
        }

    def find(self, component):
        return self.components.get(component, [])

    def connections(self):
        return [
            {"Source": "Source-1", "Source Port": "", "Destination": "Line-1"},
            {"Source": "Line-1", "Source Port": "", "Destination": "Junction-1"},
            {"Source": "Junction-1", "Source Port": "", "Destination": "Line-2"},
            {"Source": "Line-2", "Source Port": "", "Destination": "Sink-1"},
        ]

    def close(self):
        self.closed = True


def fake_sixgill_modules():
    definitions = types.ModuleType("sixgill.definitions")
    definitions.ProfileVariables = types.SimpleNamespace(
        TOTAL_DISTANCE="TotalDistance",
        PRESSURE="Pressure",
        TEMPERATURE="Temperature",
        MEAN_VELOCITY_FLUID="MeanVelocityFluid",
        DENSITY_FLUID_INSITU="DensityFluidInSitu",
        Z_FACTOR_GAS_INSITU="ZFactorGasInSitu",
    )
    resources = types.ModuleType("sixgill.core.resources")
    resources.ModelClasses = types.SimpleNamespace(STUDY="study")
    return {
        "sixgill": types.ModuleType("sixgill"),
        "sixgill.definitions": definitions,
        "sixgill.core": types.ModuleType("sixgill.core"),
        "sixgill.core.resources": resources,
    }


class PtkNetworkContractTests(unittest.TestCase):
    def execute(self, model):
        events = []
        with tempfile.NamedTemporaryFile(suffix=".pips") as model_file:
            with patch.dict(sys.modules, fake_sixgill_modules()):
                envelope = ptk_run.execute_request(
                    {
                        "modelPath": model_file.name,
                        "study": "Study 1",
                        "runTask": "network",
                        "parameters": None,
                    },
                    model_factory=lambda _: model,
                    emit_event=lambda state, message: events.append((state, message)),
                )
        return envelope, events

    def test_network_run_preserves_every_branch_and_ptk_units(self):
        model = FakeNetworkModel()
        envelope, events = self.execute(model)

        self.assertEqual("ok", envelope["status"])
        self.assertEqual(["RUNNING_NETWORK", "COLLECTING"], [item[0] for item in events])
        result = envelope["result"]
        self.assertEqual("pipesim-network-result/1", result["schemaVersion"])
        self.assertEqual("Study 1", result["study"])
        self.assertEqual(2, len(result["profiles"]))
        self.assertEqual("ft", result["profiles"][0]["variables"][1]["unit"])
        self.assertEqual(5, result["topology"]["counts"]["nodes"])
        self.assertEqual(4, result["topology"]["counts"]["edges"])
        self.assertEqual(2, len(result["quality"]))
        self.assertIsNone(result["system"][0]["values"][1]["value"])
        self.assertIsNone(result["profiles"][1]["variables"][2]["values"][1])
        self.assertEqual("Study 1", model.network_task.validate_calls[0]["study"])
        self.assertEqual(6, len(model.network_task.run_calls[0]["profile_variables"]))
        self.assertTrue(model.closed)

    def test_network_run_cleans_finite_sentinels_and_local_paths(self):
        model = FakeNetworkModel()
        model.network_task.results.system["SystemOutletPressure"]["Line-1"] = 1.2345e25
        model.network_task.results.messages = [
            r"Loading engine from D:\Simulator\Programs",
            "Service listening at 'net.pipe://localhost/pipe/private-id'",
        ]

        envelope, _ = self.execute(model)

        result = envelope["result"]
        self.assertIsNone(result["system"][0]["values"][0]["value"])
        self.assertEqual("UNAVAILABLE", result["quality"][0]["code"])
        self.assertNotIn("C:\\", result["messages"][0])
        self.assertIn("[local path]", result["messages"][0])
        self.assertIn("[redacted]", result["messages"][1])

    def test_network_run_marks_native_numeric_missing_values_unavailable(self):
        model = FakeNetworkModel()
        model.network_task.results.node["Pressure"]["Sink-1"] = None

        envelope, _ = self.execute(model)

        result = envelope["result"]
        self.assertIsNone(result["node"][0]["values"][1]["value"])
        self.assertIn(
            {"path": "node.Pressure.Sink-1", "code": "UNAVAILABLE"},
            result["quality"],
        )

    def test_network_run_rejects_text_in_numeric_result_series(self):
        model = FakeNetworkModel()
        model.network_task.results.system["SystemOutletPressure"]["Line-1"] = "100"

        envelope, _ = self.execute(model)

        self.assertEqual("error", envelope["status"])
        self.assertEqual("INVALID_NETWORK_RESULT_CONTRACT", envelope["error"]["code"])

    def test_validation_issue_diagnostics_are_sanitized(self):
        issue = types.SimpleNamespace(
            path=r"C:\Users\operator\model.tnt",
            property_name="Pressure",
            message="Service net.pipe://localhost/pipe/private-id failed",
        )

        diagnostic = format_validation_issues([issue])

        self.assertNotIn("C:\\", diagnostic)
        self.assertNotIn("private-id", diagnostic)
        self.assertIn("[local path]", diagnostic)
        self.assertIn("[redacted]", diagnostic)

    def test_failed_simulation_is_an_execution_error_with_sanitized_diagnostic(self):
        model = FakeNetworkModel()
        failed = NetworkResults()
        failed.state = "Failed"
        failed.summary = {"Info": [], "Warning": [], "Error": [r"Failed at C:\Users\operator\model.tnt"]}
        model.network_task.results = failed

        envelope, events = self.execute(model)

        self.assertEqual("error", envelope["status"])
        self.assertEqual("EXECUTION", envelope["error"]["category"])
        self.assertEqual("NETWORK_SIMULATION_FAILED", envelope["error"]["code"])
        self.assertIn("[local path]", envelope["error"]["message"])
        self.assertNotIn("C:\\", envelope["error"]["message"])
        self.assertEqual(["RUNNING_NETWORK"], [item[0] for item in events])

    def test_network_run_rejects_missing_required_components(self):
        envelope, events = self.execute(FakeNetworkModel(include_sink=False))

        self.assertEqual("error", envelope["status"])
        self.assertEqual("UNSUPPORTED_NETWORK_MODEL", envelope["error"]["code"])
        self.assertEqual([], events)

    def test_single_well_internal_source_does_not_select_network_validation(self):
        model = FakeNetworkModel(include_sink=False)
        model.components["Flowline"] = []

        inspection = inspect_network(model)

        self.assertFalse(inspection["candidate"])
        self.assertFalse(inspection["valid"])


if __name__ == "__main__":
    unittest.main()
