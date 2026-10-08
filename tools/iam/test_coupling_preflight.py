import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from coupling_preflight import inventory, main, preflight, relative_entry, write_report


class PreflightTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.package = self.root / "package"
        self.package.mkdir()
        (self.package / "case.iam").write_bytes(b"opaque binary document")
        (self.package / "deck.DATA").write_bytes(b"INCLUDE\n'missing.INC' /\n")

    def inspect(self, **options):
        return preflight(self.package, "case.iam", **options)

    def test_manifest_is_deterministic_and_source_is_unchanged(self):
        before = inventory(self.package)
        report = self.inspect()
        self.assertEqual(before, inventory(self.package))
        self.assertEqual(report, self.inspect())
        self.assertEqual(2, report["package"]["fileCount"])
        self.assertEqual(["case.iam", "deck.DATA"], [record["relativePath"] for record in before])
        self.assertTrue(all(len(record["sha256"]) == 64 for record in before))

    def test_content_change_changes_manifest_hash(self):
        old = self.inspect()["package"]["manifestSha256"]
        (self.package / "deck.DATA").write_bytes(b"changed")
        self.assertNotEqual(old, self.inspect()["package"]["manifestSha256"])

    def test_legacy_is_candidate_not_an_inferred_native_reference(self):
        (self.package / "network.bpn").write_bytes(b"old model")
        report = self.inspect()
        self.assertEqual(["network.bpn"], report["fileCandidates"]["legacyNetworks"])
        self.assertIn("LEGACY_BPN_REQUIRES_NATIVE_REVIEW", [item["code"] for item in report["findings"]])
        self.assertFalse(report["document"]["deserialized"])
        self.assertEqual("NOT_VERIFIED", report["gates"]["dependencyClosure"])

    def test_all_files_present_never_passes_native_gate(self):
        iam = self.root / "iam"
        (iam / "Extensions" / "Adapters" / "Stingray2022CLR").mkdir(parents=True)
        (iam / "Schlumberger.Avocet.WebAPI.Server.exe").touch()
        mpi = self.root / "mpi"
        (mpi / "intel64" / "bin").mkdir(parents=True)
        (mpi / "intel64" / "bin" / "mpiexec.exe").touch()
        report = self.inspect(iam_home=iam, pipesim_home=iam, eclipse_home=iam, mpi_root=mpi)
        self.assertEqual("NOT_VERIFIED", report["status"])
        self.assertFalse(report["canCalculate"])
        self.assertFalse(report["coupledSolveVerified"])
        self.assertFalse(report["environment"]["versionsVerified"])
        self.assertTrue(all(value == "NOT_VERIFIED" for value in report["gates"].values()))

    def test_flat_mpi_does_not_verify_legacy_layout(self):
        mpi = self.root / "mpi"
        (mpi / "bin").mkdir(parents=True)
        (mpi / "bin" / "mpiexec.exe").touch()
        report = self.inspect(mpi_root=mpi)
        self.assertTrue(report["environment"]["mpi"]["flatExecutablePresent"])
        self.assertFalse(report["environment"]["mpi"]["legacyExecutablePresent"])

    def test_missing_entry_rejected(self):
        with self.assertRaises(ValueError):
            preflight(self.package, "other.iam")

    def test_entry_must_be_portable_relative_path(self):
        for value in ("../case.iam", "/case.iam", "C:\\case.iam", "a/../case.iam", "a//case.iam", "case.DATA"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                relative_entry(value)
        self.assertEqual("dir/case.iam", relative_entry("dir\\case.iam"))

    def test_limits_rejected(self):
        for options in ({"max_files": 1}, {"max_bytes": 1}):
            with self.subTest(options=options), self.assertRaises(ValueError):
                inventory(self.package, **options)

    def test_link_or_junction_rejected_without_following(self):
        from coupling_preflight import is_link
        with patch("coupling_preflight.is_link", side_effect=lambda path: path.name == "deck.DATA" or is_link(path)):
            with self.assertRaisesRegex(ValueError, "symbolic link or junction"):
                self.inspect()

    def test_unreadable_directory_is_not_silently_omitted(self):
        def broken_walk(*args, **kwargs):
            kwargs["onerror"](PermissionError("denied"))
        with patch("coupling_preflight.os.walk", side_effect=broken_walk), self.assertRaises(PermissionError):
            self.inspect()

    def test_growth_during_read_is_rejected(self):
        with patch("coupling_preflight.Path.open", return_value=io.BytesIO(b"x" * 1024)):
            with self.assertRaisesRegex(ValueError, "changed during inventory"):
                self.inspect()

    def test_case_insensitive_collision_is_rejected(self):
        with patch("coupling_preflight.os.walk", return_value=[(str(self.package), [], ["case.iam", "CASE.iam"])]), patch("coupling_preflight.is_link", return_value=False):
            with self.assertRaisesRegex(ValueError, "collision"):
                inventory(self.package)

    def test_source_root_junction_is_rejected(self):
        with patch("coupling_preflight.is_link", return_value=True):
            with self.assertRaisesRegex(ValueError, "Package path"):
                self.inspect()

    def test_cli_does_not_return_success_for_inventory_only(self):
        output = self.root / "cli.json"
        with patch("sys.argv", ["preflight", "--package", str(self.package), "--entry", "case.iam", "--output", str(output)]), patch("sys.stdout", new_callable=io.StringIO):
            self.assertEqual(3, main())
        self.assertFalse(json.loads(output.read_text(encoding="utf-8"))["coupledSolveVerified"])

    def test_report_is_new_and_outside_package(self):
        report = self.inspect()
        with self.assertRaises(ValueError):
            write_report(report, self.package / "report.json", self.package)
        output = self.root / "report.json"
        write_report(report, output, self.package)
        self.assertEqual(report, json.loads(output.read_text(encoding="utf-8")))
        with self.assertRaises(FileExistsError):
            write_report(report, output, self.package)


if __name__ == "__main__":
    unittest.main()
