import copy
import json
import math
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from worker.ptk_normalization import validate_result_contract


ROOT = Path(__file__).resolve().parents[3]
SCHEMA = ROOT / "docs/software-integration/contracts/pipesim-well-result-v1.schema.json"
GOLDEN_ROOT = ROOT / "docs/software-integration/golden/pipesim-well-result-v1"
CAPTURE_SCRIPT = ROOT / "worker/tests/Golden/Capture-AvaloniaPipesimGolden.ps1"
VERIFY_SCRIPT = ROOT / "worker/tests/Golden/Verify-Golden.ps1"
EXPECTED = {
    "CSW_101": "black_oil_liquid",
    "CSW_102": "basic_gas",
}
RUNS = {
    "nodal.json": "nodal",
    "pt-profile.json": "profile",
    "combined.json": "combined",
}


def load_strict_json(path):
    def reject_constant(value):
        raise ValueError("non-standard JSON number: {0}".format(value))

    return json.loads(path.read_text(encoding="utf-8"), parse_constant=reject_constant)


class GoldenContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.schema = load_strict_json(SCHEMA)

    def assert_schema_validation(self, instance_json, expected):
        schema_path = str(SCHEMA).replace("'", "''")
        command = [
            "pwsh",
            "-NoProfile",
            "-NonInteractive",
            "-Command",
            "$json=[Console]::In.ReadToEnd(); if (Test-Json -Json $json -SchemaFile '{0}' -ErrorAction SilentlyContinue) {{ exit 0 }} else {{ exit 1 }}".format(
                schema_path
            ),
        ]
        completed = subprocess.run(
            command,
            input=instance_json,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(expected, completed.returncode == 0, completed.stderr)

    def require_real_golden_capture(self):
        if not GOLDEN_ROOT.is_dir():
            self.skipTest(
                "BLOCKED_MISSING_REAL_GOLDEN: skipped is not Golden acceptance; run serialized Avalonia capture with an available PTK license"
            )

    def test_schema_freezes_version_arrays_points_and_unit_policy(self):
        self.assertEqual(
            "pipesim-well-result/1",
            self.schema["properties"]["schemaVersion"]["const"],
        )
        self.assertTrue({"ipr", "vlp", "profile"}.issubset(self.schema["required"]))
        for field in ("ipr", "vlp", "profile"):
            self.assertEqual("array", self.schema["properties"][field]["type"])
        gas_unit = self.schema["$defs"]["basicGasFlowUnitDescriptor"]["properties"]
        self.assertEqual("mmscf/d", gas_unit["displayUnit"]["const"])
        self.assertEqual("standard_gas_volume_rate", gas_unit["semantics"]["const"])
        self.assertEqual(
            "null",
            self.schema["$defs"]["unspecifiedUnitDescriptor"]["properties"]["displayUnit"]["type"],
        )
        self.assertEqual(
            "unspecified",
            self.schema["$defs"]["unspecifiedUnitDescriptor"]["properties"]["semantics"]["const"],
        )
        black_oil_rule = self.schema["allOf"][0]
        self.assertEqual(
            "black_oil_liquid",
            black_oil_rule["if"]["properties"]["model_kind"]["const"],
        )
        self.assertEqual(
            "#/$defs/unspecifiedUnitDescriptor",
            black_oil_rule["then"]["properties"]["units"]["properties"]["flow"]["$ref"],
        )

    def test_schema_accepts_unit_policy_and_rejects_missing_arrays(self):
        descriptor = {"displayUnit": None, "semantics": "unspecified"}
        result = {
            "schemaVersion": "pipesim-well-result/1",
            "model_kind": "black_oil_liquid",
            "runTask": "nodal",
            "resultContract": "VALID_FULL",
            "units": {
                "flow": descriptor.copy(),
                "pressure": descriptor.copy(),
                "depth": descriptor.copy(),
                "temperature": descriptor.copy(),
            },
            "ipr": [{"flow": 1, "pressure": 2}],
            "vlp": [{"flow": 3, "pressure": 4}],
            "profile": [],
        }
        self.assert_schema_validation(json.dumps(result), True)

        gas_result = copy.deepcopy(result)
        gas_result["model_kind"] = "basic_gas"
        gas_result["units"]["flow"]["displayUnit"] = "mmscf/d"
        gas_result["units"]["flow"]["semantics"] = "standard_gas_volume_rate"
        self.assert_schema_validation(json.dumps(gas_result), True)

        gas_with_null_flow_unit = copy.deepcopy(result)
        gas_with_null_flow_unit["model_kind"] = "basic_gas"
        self.assert_schema_validation(json.dumps(gas_with_null_flow_unit), False)

        black_oil_with_guessed_unit = copy.deepcopy(result)
        black_oil_with_guessed_unit["units"]["flow"]["displayUnit"] = "mmscf/d"
        self.assert_schema_validation(json.dumps(black_oil_with_guessed_unit), False)

        missing_profile_array = copy.deepcopy(result)
        del missing_profile_array["profile"]
        self.assert_schema_validation(json.dumps(missing_profile_array), False)

    def test_schema_validator_rejects_non_finite_json_numbers(self):
        descriptor = {"displayUnit": None, "semantics": "unspecified"}
        invalid_json = json.dumps(
            {
                "schemaVersion": "pipesim-well-result/1",
                "model_kind": "black_oil_liquid",
                "runTask": "nodal",
                "resultContract": "VALID_FULL",
                "units": {
                    "flow": descriptor.copy(),
                    "pressure": descriptor.copy(),
                    "depth": descriptor.copy(),
                    "temperature": descriptor.copy(),
                },
                "ipr": [{"flow": float("nan"), "pressure": 1}],
                "vlp": [{"flow": 1, "pressure": 1}],
                "profile": [],
            }
        )
        self.assert_schema_validation(invalid_json, False)

    def test_capture_coordination_is_machine_wide_and_fails_closed(self):
        script = CAPTURE_SCRIPT.read_text(encoding="utf-8")
        self.assertIn("Global\\GRDP-Pipesim-Golden-Capture", script)
        self.assertIn("COORDINATION_UNVERIFIED_PROCESS_METADATA", script)
        self.assertIn("CommandLine", script)
        self.assertIn("ExecutablePath", script)
        self.assertIn("immediately before adapter process start", script)
        self.assertIn('Phase "before $($case.Id) $($run.Task)"', script)

        process_start = script.index("[Diagnostics.Process]::Start")
        final_scan = script.rfind("Assert-NoExternalSimulatorActivity", 0, process_start)
        self.assertGreater(final_scan, script.index('$startInfo.Environment["PIPESIM_WORKER_LOG"]'))

    def test_verify_rejects_invalid_combined_and_extra_files(self):
        self.require_real_golden_capture()
        with tempfile.TemporaryDirectory() as temporary_directory:
            copied_root = Path(temporary_directory) / "golden"
            shutil.copytree(GOLDEN_ROOT, copied_root)
            combined_path = copied_root / "CSW_101/combined.json"
            combined = load_strict_json(combined_path)
            combined["ipr"] = []
            combined["resultContract"] = "INVALID_EMPTY_NODAL"
            combined_path.write_text(json.dumps(combined), encoding="utf-8")
            invalid_combined = subprocess.run(
                ["pwsh", "-NoProfile", "-File", str(VERIFY_SCRIPT), "-GoldenRoot", str(copied_root)],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertNotEqual(0, invalid_combined.returncode)

            shutil.copy2(GOLDEN_ROOT / "CSW_101/nodal.json", copied_root / "unexpected.json")
            combined_path.write_text(
                (GOLDEN_ROOT / "CSW_101/combined.json").read_text(encoding="utf-8"),
                encoding="utf-8",
            )
            extra_file = subprocess.run(
                ["pwsh", "-NoProfile", "-File", str(VERIFY_SCRIPT), "-GoldenRoot", str(copied_root)],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertNotEqual(0, extra_file.returncode)

    def test_each_real_golden_validates_against_json_schema(self):
        self.require_real_golden_capture()
        for case_id in EXPECTED:
            for file_name in RUNS:
                path = GOLDEN_ROOT / case_id / file_name
                self.assertTrue(path.is_file(), "uncaptured real golden: {0}".format(path))
                self.assert_schema_validation(path.read_text(encoding="utf-8"), True)

    def test_golden_result_semantics_and_metadata(self):
        self.require_real_golden_capture()
        for case_id, model_kind in EXPECTED.items():
            for file_name, run_task in RUNS.items():
                with self.subTest(case=case_id, run=run_task):
                    result_path = GOLDEN_ROOT / case_id / file_name
                    metadata_path = result_path.with_name(result_path.stem + ".metadata.json")
                    self.assertTrue(
                        result_path.is_file(), "uncaptured real golden: {0}".format(result_path)
                    )
                    self.assertTrue(
                        metadata_path.is_file(),
                        "uncaptured golden metadata: {0}".format(metadata_path),
                    )
                    result = load_strict_json(result_path)
                    metadata = load_strict_json(metadata_path)

                    self.assertEqual("pipesim-well-result/1", result["schemaVersion"])
                    self.assertEqual(model_kind, result["model_kind"])
                    self.assertEqual(run_task, result["runTask"])
                    self.assertEqual(
                        result["resultContract"],
                        validate_result_contract(
                            run_task, result["ipr"], result["vlp"], result["profile"]
                        ),
                    )
                    for field in ("ipr", "vlp", "profile"):
                        self.assertIsInstance(result[field], list)
                    for point in result["ipr"] + result["vlp"]:
                        self.assertTrue(math.isfinite(point["flow"]))
                        self.assertTrue(math.isfinite(point["pressure"]))
                    depths = []
                    for point in result["profile"]:
                        depths.append(point["depth"])
                        self.assertGreaterEqual(point["depth"], 0)
                        self.assertTrue(math.isfinite(point["pressure"]))
                        self.assertTrue(math.isfinite(point["temperature"]))
                    self.assertEqual(sorted(depths), depths)

                    self.assertEqual(run_task, metadata["runType"])
                    self.assertEqual(result["schemaVersion"], metadata["schemaVersion"])
                    self.assertEqual(
                        metadata["source"]["sha256Before"],
                        metadata["source"]["sha256After"],
                    )
                    self.assertRegex(metadata["source"]["sha256Before"], r"^[0-9a-f]{64}$")
                    self.assertRegex(metadata["avaloniaAdapter"]["revision"], r"^[0-9a-f]{40}$")
                    self.assertTrue(metadata["generatedAtUtc"].endswith("+00:00"))


if __name__ == "__main__":
    unittest.main()
