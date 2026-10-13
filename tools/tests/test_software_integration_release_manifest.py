import json
import subprocess
import tempfile
import unittest
from pathlib import Path

from tools.software_integration_release_manifest import create_manifest, fingerprint_path


class ReleaseManifestTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        subprocess.run(["git", "-C", str(self.root), "config", "user.email", "test@example.invalid"], check=True)
        subprocess.run(["git", "-C", str(self.root), "config", "user.name", "Test"], check=True)
        (self.root / "tracked.txt").write_text("baseline", encoding="utf-8")
        subprocess.run(["git", "-C", str(self.root), "add", "tracked.txt"], check=True)
        subprocess.run(["git", "-C", str(self.root), "commit", "-qm", "baseline"], check=True)

    def tearDown(self):
        self.temporary.cleanup()

    def test_manifest_fingerprints_release_and_source_without_serializing_absolute_root(self):
        (self.root / "tracked.txt").write_text("modified", encoding="utf-8")
        build = self.root / "worker" / "publish"
        build.mkdir(parents=True)
        (build / "Worker.dll").write_bytes(b"build-output")

        manifest = create_manifest(
            self.root,
            build_outputs=[("worker", "worker/publish")],
            pipesim_ptk_version="2022.1",
            eclipse_version="2024.1",
            worker_generation="generation-42",
        )
        serialized = json.dumps(manifest)

        self.assertEqual("grdp-software-integration-release-manifest/1", manifest["schemaVersion"])
        self.assertTrue(manifest["repository"]["dirty"])
        self.assertEqual(2, manifest["repository"]["changedFileCount"])
        self.assertEqual("FINGERPRINTED", manifest["buildFingerprints"]["worker"]["status"])
        self.assertEqual("2024.1", manifest["simulatorEnvironment"]["eclipseVersion"])
        self.assertNotIn(str(self.root), serialized)
        self.assertNotIn("Worker.dll", serialized)

    def test_worktree_fingerprint_changes_when_untracked_file_content_changes(self):
        from tools.software_integration_release_manifest import _worktree_fingerprint

        extra = self.root / "new-evidence.json"
        extra.write_text('{"status":"NOT_TESTED"}', encoding="utf-8")
        first = _worktree_fingerprint(self.root)
        extra.write_text('{"status":"PASSED"}', encoding="utf-8")
        second = _worktree_fingerprint(self.root)

        self.assertTrue(first["dirty"])
        self.assertEqual(1, first["changedFileCount"])
        self.assertNotEqual(first["diffSha256"], second["diffSha256"])

    def test_fingerprint_rejects_paths_that_escape_repository(self):
        with self.assertRaises(ValueError):
            fingerprint_path(self.root, "../outside")


if __name__ == "__main__":
    unittest.main()
