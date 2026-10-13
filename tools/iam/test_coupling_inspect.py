"""Transport-fixture tests; these do not certify a commercial coupled solve."""
import copy
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch

from coupling_inspect import DiscoveryClient, REQUIRED_OPERATIONS, inspect_package, mapping_review
from coupling_preflight import inventory
from iam_probe import Client, ProbeError


WID = "a3db8c88-1756-446b-81ba-a17fe8cc9a1e"


class FixtureClient:
    def __init__(self, url, output, timeout):
        self.output = Path(output)
        self.output.mkdir(parents=True, exist_ok=False)
        self.calls, self.saved = [], {}
        self.workspaces = []
        self.fail_create = False
        self.fail_read = False
        self.delete_result = True
        self.busy = False
        self.swagger = {"paths": {}}
        for path, method, operation in REQUIRED_OPERATIONS:
            self.swagger["paths"].setdefault(path, {})[method] = {"operationId": operation}
        self.models = [{"id": "ecl", "name": "ECL", "type": "EclipseModel",
                        "isActive": False, "requiresActivation": True},
                       {"id": "pip", "name": "PIP", "type": "PipeSimModel",
                        "isActive": False, "requiresActivation": True}]
        self.variables = [{"name": "ECL.U1.P", "type": "Real", "measurement": "Pressure",
                           "unit": "pa", "readOnly": True},
                          {"name": "PIP.U1.P", "type": "Real", "measurement": "Pressure",
                           "unit": "pa", "readOnly": False}]

    def save(self, name, value):
        self.saved[name] = copy.deepcopy(value)

    def request(self, method, path):
        self.calls.append((method, path))
        return self.swagger

    def wait_idle(self, workspace_id, timeout):
        if self.busy:
            raise ProbeError("Workspace remained Busy")

    def api(self, method, path, body=None):
        self.calls.append((method, path, body))
        if path == "workspaces":
            if method == "GET":
                return list(self.workspaces)
            self.workspaces.append({"id": WID, "name": body["name"], "status": "Idle"})
            if self.fail_create:
                raise ProbeError("POST timeout; no automatic retry")
            return {"id": WID}
        if method == "DELETE":
            if self.delete_result:
                self.workspaces.clear()
            return self.delete_result
        if self.fail_read and path.startswith("variables/"):
            raise ProbeError("variables unavailable")
        if path.startswith("models/"):
            return self.models
        if path.startswith("variables/"):
            return self.variables
        if path.startswith("couplings/"):
            return [{"fromModel": "ECL", "toModel": "PIP", "variableMappings": [
                {"fromVariable": "ECL.U1.P", "toVariable": "PIP.U1.P"}]}]
        if path.startswith("units/"):
            return [{"name": "Pressure", "units": ["pa"]}]
        if path.startswith("time/"):
            return {"status": "Idle", "isFinished": True}
        return {}


class NativeInspectionTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.package = self.root / "package"
        self.package.mkdir()
        (self.package / "case.iam").write_bytes(b"opaque")
        (self.package / "network.bpn").write_bytes(b"legacy")
        self.client = None

    def factory(self, url, output, timeout):
        self.client = FixtureClient(url, output, timeout)
        return self.client

    def run_inspection(self, factory=None, output=None):
        return inspect_package(self.package, "case.iam", output or self.root / "evidence",
                               client_factory=factory or self.factory, timeout=2)

    def test_only_isolated_copy_is_passed_to_official_reader(self):
        before = inventory(self.package)
        report = self.run_inspection()
        create = next(call for call in self.client.calls if call[:2] == ("POST", "workspaces"))
        opened = Path(create[2]["file"]["filePath"])
        self.assertTrue(opened.is_relative_to(self.root / "evidence"))
        self.assertEqual(b"opaque", opened.read_bytes())
        self.assertEqual(before, inventory(self.package))
        self.assertTrue(report["sourceUnchanged"])
        self.assertTrue(report["workspaceDeleted"])
        self.assertTrue(report["metadataRead"])
        self.assertFalse(report["canCalculate"])
        self.assertFalse(report["coupledSolveVerified"])
        self.assertEqual("PipeSimModel", report["parts"]["models"][1]["type"])

    def test_no_activation_control_changes_or_solver_requests(self):
        self.run_inspection()
        mutations = [call[:2] for call in self.client.calls if call[0] != "GET"]
        self.assertEqual([("POST", "workspaces"), ("DELETE", "workspaces/" + WID)], mutations)

    def test_live_swagger_missing_operation_rejected_before_open(self):
        def factory(*args, **kwargs):
            client = self.factory(*args, **kwargs)
            client.swagger["paths"].pop("/iam/api/v1/couplings/workspaces/{workspaceId}")
            return client
        report = self.run_inspection(factory)
        self.assertFalse(report["metadataRead"])
        self.assertFalse(any(call[0] == "POST" for call in self.client.calls))

    def test_nonempty_host_is_not_modified(self):
        def factory(*args, **kwargs):
            client = self.factory(*args, **kwargs)
            client.workspaces = [{"id": "other", "name": "user"}]
            return client
        report = self.run_inspection(factory)
        self.assertFalse(report["metadataRead"])
        self.assertFalse(any(call[0] != "GET" for call in self.client.calls))

    def test_uncertain_create_is_not_retried_and_confirmed_owned_workspace_is_cleaned(self):
        def factory(*args, **kwargs):
            client = self.factory(*args, **kwargs)
            client.fail_create = True
            return client
        report = self.run_inspection(factory)
        self.assertTrue(report["outcomeUncertain"])
        self.assertTrue(report["workspaceDeleted"])
        self.assertFalse(report["metadataRead"])
        self.assertEqual(1, sum(call[:2] == ("POST", "workspaces") for call in self.client.calls))

    def test_read_error_keeps_other_evidence_and_cleans_up(self):
        def factory(*args, **kwargs):
            client = self.factory(*args, **kwargs)
            client.fail_read = True
            return client
        report = self.run_inspection(factory)
        self.assertFalse(report["metadataRead"])
        self.assertTrue(report["workspaceDeleted"])
        self.assertIn("models", report["parts"])
        self.assertIn("time", report["parts"])

    def test_busy_timeout_cleans_up_not_success(self):
        def factory(*args, **kwargs):
            client = self.factory(*args, **kwargs)
            client.busy = True
            return client
        report = self.run_inspection(factory)
        self.assertFalse(report["metadataRead"])
        self.assertTrue(report["workspaceDeleted"])

    def test_delete_false_never_marks_cleanup_verified(self):
        def factory(*args, **kwargs):
            client = self.factory(*args, **kwargs)
            client.delete_result = False
            return client
        report = self.run_inspection(factory)
        self.assertFalse(report["workspaceDeleted"])
        self.assertTrue(report["cleanupErrors"])
        self.assertFalse(report["canCalculate"])

    def test_source_change_during_copy_prevents_native_open(self):
        import shutil
        original = shutil.copy2
        def copy_then_change(source, destination):
            value = original(source, destination)
            (self.package / "case.iam").write_bytes(b"changed")
            return value
        with patch("coupling_inspect.shutil.copy2", side_effect=copy_then_change):
            with self.assertRaisesRegex(ValueError, "changed"):
                self.run_inspection()
        self.assertFalse(any(call[0] == "POST" for call in self.client.calls))

    def test_output_inside_source_and_existing_output_rejected(self):
        with self.assertRaises(ValueError):
            self.run_inspection(output=self.package / "output")
        self.assertIsNone(self.client)
        self.run_inspection()
        with self.assertRaises(FileExistsError):
            self.run_inspection()

    def test_wire_204_only_permits_empty_workspace_collection(self):
        client = DiscoveryClient("http://localhost:9001", self.root / "wire")
        client.sequence = 1
        with patch.object(Client, "request", return_value=None):
            for status, expected in ((204, []), (200, None)):
                client.save("0001-http.json", {"status": status, "responseText": ""})
                self.assertEqual(expected, client.request("GET", "/iam/api/v1/workspaces"))
            self.assertIsNone(client.request("GET", "/iam/api/v1/models/workspaces/" + WID))


class MappingReviewTests(unittest.TestCase):
    def setUp(self):
        self.variables = [{"name": "ECL.P", "type": "Real", "measurement": "Pressure",
                           "unit": "pa", "readOnly": True},
                          {"name": "PIP.P", "type": "Real", "measurement": "Pressure",
                           "unit": "pa", "readOnly": False}]
        self.couplings = [{"variableMappings": [{"fromVariable": "ECL.P", "toVariable": "PIP.P"}]}]

    def codes(self):
        return [item["code"] for item in mapping_review(self.couplings, self.variables)["findings"]]

    def test_same_units_still_does_not_verify_physical_contract(self):
        report = mapping_review(self.couplings, self.variables)
        self.assertEqual([], report["findings"])
        self.assertEqual(1, report["explicitMappingCount"])
        self.assertEqual("NOT_VERIFIED", report["physicalContract"])

    def test_missing_variable_or_unit_not_guessed(self):
        self.variables.pop()
        self.assertIn("MAPPING_VARIABLE_NOT_OBSERVED", self.codes())
        self.variables = [{"name": "ECL.P"}, {"name": "PIP.P"}]
        self.assertIn("MAPPING_TYPE_NOT_OBSERVED", self.codes())

    def test_measurement_and_unit_mismatch_rejected_for_review(self):
        self.variables[1].update(measurement="Temperature", unit="K")
        self.assertIn("MAPPING_MEASUREMENT_MISMATCH", self.codes())
        self.assertIn("MAPPING_UNIT_MISMATCH", self.codes())

    def test_target_readonly_duplicate_names_and_empty_mapping_flagged(self):
        self.variables[1]["readOnly"] = True
        self.assertIn("MAPPING_TARGET_READ_ONLY", self.codes())
        self.variables.append(dict(self.variables[0]))
        self.assertIn("VARIABLE_NAME_DUPLICATE", self.codes())
        self.couplings = []
        self.assertIn("EXPLICIT_MAPPINGS_NOT_OBSERVED", self.codes())


if __name__ == "__main__":
    unittest.main()
