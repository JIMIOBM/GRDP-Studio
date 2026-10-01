import json
import math
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

from worker import ptk_run, ptk_validate
from worker.ptk_network import format_validation_issues, inspect_network, sanitize_message, validate_network_result


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
        self.conditions = {
            "Source-1": {
                "BoundaryNodeType": "Source",
                "Pressure": 120.0,
                "Temperature": 80.0,
                "FlowRateType": "GasFlowRate",
                "GasFlowRate": 5.0,
            },
            "Sink-1": {
                "BoundaryNodeType": "Sink",
                "Pressure": 90.0,
            },
        }

    def validate(self, **kwargs):
        self.validate_calls.append(kwargs)
        return []

    def run(self, **kwargs):
        self.run_calls.append(kwargs)
        return self.results

    def get_conditions(self, **kwargs):
        return self.conditions


class FakeNetworkModel:
    def __init__(self, include_sink=True, include_choke=False):
        self._catalog = Catalog()
        self.closed = False
        self.bean_size = 2.0
        self.network_task = NetworkTask()
        self.tasks = types.SimpleNamespace(networksimulation=self.network_task)
        self.components = {
            "Source": ["Source-1"],
            "Sink": ["Sink-1"] if include_sink else [],
            "Flowline": ["Line-1", "Line-2"],
            "Junction": ["Junction-1"],
            "Well": [],
        }
        if include_choke:
            self.components["Choke"] = ["Choke"]

    def find(self, component):
        return self.components.get(component, [])

    def get_value(self, context, parameter):
        if parameter != "BeanSize" or context != "Choke":
            raise AssertionError((context, parameter))
        return self.bean_size

    def set_value(self, Choke, parameter, value):
        if parameter != "BeanSize" or Choke != "Choke":
            raise AssertionError((Choke, parameter))
        self.bean_size = value

    def describe(self, context, parameter):
        if parameter != "BeanSize" or context != "Choke":
            raise AssertionError((context, parameter))
        return types.SimpleNamespace(units_symbol="in")

    def connections(self):
        return [
            {"Source": "Source-1", "Source Port": "", "Destination": "Line-1"},
            {"Source": "Line-1", "Source Port": "", "Destination": "Junction-1"},
            {"Source": "Junction-1", "Source Port": "", "Destination": "Line-2"},
            {"Source": "Line-2", "Source Port": "", "Destination": "Sink-1"},
        ]

    def close(self):
        self.closed = True


class OptimizerResults:
    state = "Completed"
    variable_names = {
        "OptimizerGas_lift_rate": "Gas lift rate",
        "OptimizerWell_is_shut_off": "Well is shut off",
    }
    units = {"OptimizerGas_lift_rate": "mmscf/d", "OptimizerWell_is_shut_off": " "}
    summary = {"Info": ["optimizer completed"], "Warning": [], "Error": []}
    messages = []
    well_results = {"Well_1": {"OptimizerGas_lift_rate": 0.5663, "OptimizerWell_is_shut_off": False}}
    flowline_results = {"B_1": {"OptimizerGas_lift_rate": float("nan"), "OptimizerWell_is_shut_off": float("nan")}}
    sink_results = {"CPF": {"OptimizerGas_lift_rate": float("nan"), "OptimizerWell_is_shut_off": float("nan")}}


class OptimizerTask:
    def __init__(self):
        self.results = OptimizerResults()
        self.apply_calls = 0

    def run(self):
        return self.results

    def apply_results(self):
        self.apply_calls += 1


class FakeOptimizerModel:
    def __init__(self):
        self._catalog = Catalog()
        self.closed = False
        self.saved = False
        self.optimizer_task = OptimizerTask()
        self.tasks = types.SimpleNamespace(networkoptimizersimulation=self.optimizer_task)
        self.gas_rates = {
            "Well_1:Tubing_Gas lift injection": 0.4488,
            "Well_2:Tubing_Gas lift injection": 0.8,
        }

    def find(self, component):
        return list(self.gas_rates) if component == "GasLiftInjection" else []

    def get_value(self, context, parameter):
        self.assert_parameter(parameter)
        return self.gas_rates[context]

    def describe(self, context, parameter):
        self.assert_parameter(parameter)
        return types.SimpleNamespace(units_symbol="mmscf/d")

    def assert_parameter(self, parameter):
        if parameter != "GasRate":
            raise AssertionError(parameter)

    def close(self):
        self.closed = True

    def save(self):
        self.saved = True


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
    definitions.Parameters = types.SimpleNamespace(
        GasLiftInjection=types.SimpleNamespace(GASRATE="GasRate"),
        Choke=types.SimpleNamespace(BEANSIZE="BeanSize"),
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
    def test_network_inspection_contains_only_display_safe_study_boundaries(self):
        model = FakeNetworkModel(include_choke=True)
        with patch.dict(sys.modules, fake_sixgill_modules()):
            inspection = ptk_validate._network_inspection(model, ["Study 1"])
        self.assertEqual("pipesim-network-inspection/3", inspection["schemaVersion"])
        self.assertEqual([{"name": "Choke", "beanSize": 2.0, "unit": "in"}], inspection["chokes"])
        boundaries = inspection["studies"][0]["boundaries"]
        self.assertEqual(["Sink-1", "Source-1"], [item["node"] for item in boundaries])
        source = boundaries[1]
        self.assertEqual("GasFlowRate", source["flowRateType"])
        self.assertEqual(5.0, source["gasFlowRate"])
        self.assertEqual(120.0, source["pressure"])

    def test_network_choke_bean_size_parameters_apply_and_record_readback(self):
        model = FakeNetworkModel(include_choke=True)
        envelope, events = self.execute(model, {
            "schemaVersion": "pipesim-network-choke-bean-size-parameters/1",
            "baselineRunId": 1001,
            "choke": "Choke",
            "originalBeanSize": 2.0,
            "targetBeanSize": 3.0,
        })

        self.assertEqual("ok", envelope["status"])
        self.assertEqual("VALID_FULL", envelope["result"]["resultContract"])
        self.assertEqual(3.0, model.bean_size)
        self.assertIn("Applied Network Choke BeanSize Choke 2.0->3.0 in.", envelope["result"]["messages"])
        self.assertEqual(["RUNNING_NETWORK", "COLLECTING"], [item[0] for item in events])

    def test_network_choke_bean_size_rejects_original_value_mismatch(self):
        model = FakeNetworkModel(include_choke=True)
        envelope, _ = self.execute(model, {
            "schemaVersion": "pipesim-network-choke-bean-size-parameters/1",
            "baselineRunId": 1001,
            "choke": "Choke",
            "originalBeanSize": 2.5,
            "targetBeanSize": 3.0,
        })

        self.assertEqual("error", envelope["status"])
        self.assertEqual("NETWORK_CHOKE_ORIGINAL_VALUE_MISMATCH", envelope["error"]["code"])
        self.assertEqual(2.0, model.bean_size)
        self.assertEqual([], model.network_task.run_calls)

    def execute(self, model, parameters=None):
        events = []
        with tempfile.NamedTemporaryFile(suffix=".pips") as model_file:
            with patch.dict(sys.modules, fake_sixgill_modules()):
                envelope = ptk_run.execute_request(
                    {
                        "modelPath": model_file.name,
                        "study": "Study 1",
                        "runTask": "network",
                        "parameters": parameters,
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
        self.assertEqual("VALID_FULL", result["resultContract"])
        self.assertTrue(validate_network_result(result, "Study 1"))
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

    def test_network_run_preserves_native_boolean_and_enum_scalars(self):
        model = FakeNetworkModel()
        model.network_task.results.node["IsInjectingIntoCompletion"] = {
            "Unit": "",
            "Sink-1": False,
        }
        model.network_task.results.node["LimitedBy"] = {
            "Unit": "",
            "Sink-1": "SPEED",
        }

        envelope, _ = self.execute(model)

        self.assertEqual("ok", envelope["status"])
        self.assertEqual("VALID_FULL", envelope["result"]["resultContract"])
        values = {
            group["variable"]: group["values"][0]["value"]
            for group in envelope["result"]["node"]
            if group["variable"] in {"IsInjectingIntoCompletion", "LimitedBy"}
        }
        self.assertFalse(values["IsInjectingIntoCompletion"])
        self.assertEqual("SPEED", values["LimitedBy"])

    def test_network_parameters_override_only_selected_boundaries(self):
        model = FakeNetworkModel()
        envelope, _ = self.execute(model, {
            "schemaVersion": "pipesim-network-parameters/1",
            "boundaries": [{"node": "Source-1", "pressure": 1500, "temperature": 130}],
        })
        self.assertEqual("ok", envelope["status"])
        self.assertEqual(
            {"Source-1": {"Pressure": 1500, "Temperature": 130}},
            model.network_task.run_calls[0]["boundaries"],
        )

    def test_network_parameters_reject_unknown_boundary_node(self):
        model = FakeNetworkModel()
        envelope, _ = self.execute(model, {
            "schemaVersion": "pipesim-network-parameters/1",
            "boundaries": [{"node": "Missing", "pressure": 1500}],
        })
        self.assertEqual("error", envelope["status"])
        self.assertEqual("NETWORK_BOUNDARY_NOT_FOUND", envelope["error"]["code"])
        self.assertEqual([], model.network_task.run_calls)
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
        self.assertEqual("Service listening at '[local pipe]'", result["messages"][1])
        self.assertNotIn("net.pipe", result["messages"][1])
        self.assertNotIn("localhost", result["messages"][1])
        self.assertNotIn("private-id", result["messages"][1])
        self.assertEqual(result["messages"][1], sanitize_message(result["messages"][1]))

    def test_sanitize_message_replaces_local_pipe_uri_without_retaining_host_or_id(self):
        for value in (
            "net.pipe://localhost/pipe/private-id",
            "net.pipe://localhost/PIPESIM-private",
            "net.pipe://worker-host/pipe/run-36-private",
        ):
            with self.subTest(value=value):
                sanitized = sanitize_message(value)
                self.assertEqual("[local pipe]", sanitized)
                self.assertEqual(sanitized, sanitize_message(sanitized))

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

    def test_completed_strict_invalid_network_result_drops_string_numeric_from_partial(self):
        model = FakeNetworkModel()
        model.network_task.results.system["SystemOutletPressure"]["Line-1"] = "100"

        envelope, _ = self.execute(model)

        self.assertEqual("partial", envelope["status"])
        self.assertIsNone(envelope["error"])
        self.assertEqual("VALID_PARTIAL", envelope["result"]["resultContract"])
        self.assertEqual([], envelope["result"]["system"])
        self.assertNotIn('"value": "100"', json.dumps(envelope, allow_nan=False))
        self.assertEqual(
            {
                "category": "PROTOCOL",
                "code": "NETWORK_RESULT_LIMITED",
                "message": "PIPESIM Network returned partial display data; full result validation did not pass.",
                "retryable": False,
            },
            envelope["warnings"][0],
        )
        json.dumps(envelope, allow_nan=False)

    def test_network_partial_result_preserves_official_native_boolean_and_text_values(self):
        model = FakeNetworkModel()
        model.network_task.results.system["Route"] = {"Unit": "", "Network": "MOLLIER"}
        model.network_task.results.node["LimitedBy"] = {"Unit": "", "Node-1": "POWER"}
        model.network_task.results.node["FlowrateBeyondCurveMaxRate"] = {"Unit": "", "Node-1": False}

        envelope, _ = self.execute(model)

        self.assertEqual("ok", envelope["status"])
        self.assertEqual("MOLLIER", next(item for item in envelope["result"]["system"] if item["variable"] == "Route")["values"][0]["value"])
        self.assertEqual("POWER", next(item for item in envelope["result"]["node"] if item["variable"] == "LimitedBy")["values"][0]["value"])
        self.assertFalse(next(item for item in envelope["result"]["node"] if item["variable"] == "FlowrateBeyondCurveMaxRate")["values"][0]["value"])

    def test_partial_network_result_omits_raw_simulator_diagnostics(self):
        model = FakeNetworkModel()
        model.network_task.results.system["SystemOutletPressure"]["Line-1"] = "100"
        model.network_task.results.summary = {
            "Info": [r"Run17 loaded C:\\PIPESIM\\private-model.pips"],
            "Warning": ["Service net.pipe://localhost/pipe/run17-private-id timed out"],
            "Error": ["Run17 solver diagnostic"],
        }
        model.network_task.results.messages = [
            r"Run17 raw simulator message from C:\\PIPESIM\\private-model.pips",
            "Run17 net.pipe://localhost/pipe/run17-private-id",
        ]

        envelope, _ = self.execute(model)

        self.assertEqual("partial", envelope["status"])
        self.assertEqual("VALID_PARTIAL", envelope["result"]["resultContract"])
        self.assertEqual({"info": [], "warnings": [], "errors": []}, envelope["result"]["summary"])
        self.assertEqual([], envelope["result"]["messages"])
        self.assertEqual([], envelope["result"]["quality"])
        serialized = json.dumps(envelope, allow_nan=False)
        self.assertNotIn("Run17", serialized)
        self.assertNotIn("private-model.pips", serialized)
        self.assertNotIn("run17-private-id", serialized)
        self.assertEqual(["NETWORK_RESULT_LIMITED"], [warning["code"] for warning in envelope["warnings"]])

    def test_completed_strict_invalid_network_result_drops_empty_numeric_containers_from_partial(self):
        for value in ({}, []):
            with self.subTest(value=value):
                model = FakeNetworkModel()
                model.network_task.results.system["SystemOutletPressure"]["Line-1"] = value

                envelope, _ = self.execute(model)

                self.assertEqual("partial", envelope["status"])
                self.assertEqual("VALID_PARTIAL", envelope["result"]["resultContract"])
                self.assertEqual([], envelope["result"]["system"])
                self.assertNotIn(value, [item["value"] for group in envelope["result"]["system"] for item in group["values"]])
                json.dumps(envelope, allow_nan=False)

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
        self.assertIn("[local pipe]", diagnostic)

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

    def test_network_optimizer_apply_results_returns_isolated_audit(self):
        model = FakeOptimizerModel()
        events = []
        with tempfile.NamedTemporaryFile(suffix=".pips") as model_file:
            with patch.dict(sys.modules, fake_sixgill_modules()):
                envelope = ptk_run.execute_request(
                    {
                        "modelPath": model_file.name,
                        "study": "Study 1",
                        "runTask": "network-optimizer",
                        "parameters": {
                            "schemaVersion": "pipesim-network-optimizer-parameters/2",
                            "applyResults": True,
                        },
                    },
                    model_factory=lambda _: model,
                    emit_event=lambda state, message: events.append((state, message)),
                )

        self.assertEqual("ok", envelope["status"])
        self.assertEqual("pipesim-network-optimizer-result/2", envelope["result"]["schemaVersion"])
        self.assertEqual("isolated-model-copy", envelope["result"]["application"]["scope"])
        self.assertEqual(2, len(envelope["result"]["application"]["changes"]))
        self.assertEqual(1, model.optimizer_task.apply_calls)
        self.assertTrue(model.saved)
        self.assertTrue(model.closed)
        self.assertEqual(["RUNNING_NETWORK", "COLLECTING"], [item[0] for item in events])

    def test_single_well_internal_source_does_not_select_network_validation(self):
        model = FakeNetworkModel(include_sink=False)
        model.components["Flowline"] = []

        inspection = inspect_network(model)

        self.assertFalse(inspection["candidate"])
        self.assertFalse(inspection["valid"])

    def test_well_completion_and_tubing_take_precedence_over_internal_surface_objects(self):
        model = FakeNetworkModel(include_sink=True)
        model.components.update({
            "Well": ["Well_1"],
            "Completion": ["Well_1:Completion"],
            "Tubing": ["Well_1:Tubing"],
        })

        inspection = inspect_network(model)

        self.assertTrue(inspection["well_model"])
        self.assertFalse(inspection["candidate"])


if __name__ == "__main__":
    unittest.main()
