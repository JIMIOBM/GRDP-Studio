import unittest
from unittest.mock import Mock, patch

from iam_probe import Client, ProbeError, diagnostics, validate_base_url


class IamProbeTests(unittest.TestCase):
    def test_local_url(self):
        self.assertEqual("http://localhost:9001", validate_base_url("http://localhost:9001/"))

    def test_reject_other_endpoints(self):
        for url in ("http://server:9001", "http://127.0.0.1:9001", "http://localhost",
                    "http://user:pass@localhost:9001", "http://localhost:9001/path",
                    "http://localhost:9001?query=x", "https://localhost:9001"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                validate_base_url(url)

    def test_finished_does_not_override_failure(self):
        found = diagnostics([], {"isFinished": True, "status": "FailedToInitializeSimulator"}, {})
        self.assertTrue(any("SIMULATION_FAILED" in error for error in found))

    def test_model_activation_failure(self):
        self.assertTrue(diagnostics([{"name": "PIP", "activationFailed": True}], {}, {}))

    def test_inactive_required_model(self):
        self.assertTrue(diagnostics([{"name": "PIP", "requiresActivation": True, "isActive": False}], {}, {}))

    def test_both_message_channels_and_casing(self):
        for key in ("statusMessages", "solverMessages", "StatusMessages", "SolverMessages"):
            with self.subTest(key=key):
                self.assertEqual(["Path missing"], diagnostics([], {}, {key: [
                    {"severity": "Critical", "text": "Path missing"}]}))

    def test_warning_is_not_error(self):
        self.assertEqual([], diagnostics([], {}, {"statusMessages": [{"severity": "Warning"}]}))

    def client_without_transport(self):
        client = Client.__new__(Client)
        client.request = Mock(return_value={})
        client.api = Mock()
        client.save = Mock()
        client.wait_idle = Mock()
        return client

    def test_smoke_cleans_up_after_wait_failure(self):
        client = self.client_without_transport()
        wid = "a3db8c88-1756-446b-81ba-a17fe8cc9a1e"
        client.api.side_effect = [{"id": wid}, None]
        client.wait_idle.side_effect = ProbeError("Busy")
        result = client.smoke()
        self.assertFalse(result["passed"])
        self.assertTrue(result["workspaceDeleted"])
        client.api.assert_called_with("DELETE", "workspaces/" + wid)

    def test_uncertain_create_not_retried(self):
        client = self.client_without_transport()
        client.api.side_effect = ProbeError("timeout")
        self.assertFalse(client.smoke()["passed"])
        self.assertEqual(1, client.api.call_count)

    def test_snapshot_never_claims_coupled_solve(self):
        client = self.client_without_transport()
        client.api.side_effect = [[], [], {"isFinished": True, "status": "Idle"}, {}]
        result = client.snapshot("a3db8c88-1756-446b-81ba-a17fe8cc9a1e")
        self.assertFalse(result["coupledSolveVerified"])

    def test_cleanup_failure_fails_smoke(self):
        client = self.client_without_transport()
        client.api.side_effect = [{"id": "a3db8c88-1756-446b-81ba-a17fe8cc9a1e"}, ProbeError("Busy")]
        client.snapshot = Mock(return_value={"diagnostics": [], "readErrors": []})
        result = client.smoke()
        self.assertFalse(result["passed"])
        self.assertFalse(result["workspaceDeleted"])

    def test_wait_idle_uses_collection_endpoint(self):
        client = self.client_without_transport()
        client.api.return_value = [{"id": "test", "status": "Idle"}]
        self.assertEqual("Idle", Client.wait_idle(client, "test")["workspace"]["status"])
        client.api.assert_called_once_with("GET", "workspaces")

    def test_missing_workspace_fails(self):
        client = self.client_without_transport()
        client.api.return_value = []
        with self.assertRaises(ProbeError):
            Client.wait_idle(client, "missing")

    def test_failed_solver_is_terminal_even_if_workspace_stays_busy(self):
        client = self.client_without_transport()
        client.api.side_effect = [
            [{"id": "test", "status": "Busy"}],
            {"isFinished": True, "status": "FailedToInitializeSimulator"},
        ]
        terminal = Client.wait_idle(client, "test", solver_started=True)
        self.assertEqual("Busy", terminal["workspace"]["status"])
        self.assertEqual("FailedToInitializeSimulator", terminal["simulation"]["status"])

    def test_initializing_workspace_does_not_read_unready_time_controller(self):
        client = self.client_without_transport()
        client.api.side_effect = [[{"id": "test", "status": "Busy"}],
                                  [{"id": "test", "status": "Idle"}]]
        with patch("iam_probe.time.sleep"):
            Client.wait_idle(client, "test")
        self.assertEqual([("GET", "workspaces"), ("GET", "workspaces")],
                         [call.args for call in client.api.call_args_list])


if __name__ == "__main__":
    unittest.main()
