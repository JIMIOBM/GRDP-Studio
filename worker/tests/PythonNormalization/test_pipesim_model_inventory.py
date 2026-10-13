import csv
from contextlib import redirect_stderr
import hashlib
import io
import tempfile
import unittest
from pathlib import Path

from tools.pipesim_model_inventory import FIELDS, discover_models, main, write_inventory


class PipesimModelInventoryTests(unittest.TestCase):
    def test_discovers_case_studies_with_relative_names_and_hashes(self):
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            well = home / "Case Studies" / "Well Models" / "CSW_101_Basic Oil Well.pips"
            network = home / "Case Studies" / "Network Models" / "CSN_301_Small Network.PIPS"
            other = home / "Case Studies" / "Other" / "sample.pips"
            outside = home / "Examples" / "ignored.pips"
            for path, content in ((well, b"well model"), (network, b"network model"),
                                  (other, b"other model"), (outside, b"outside")):
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(content)

            rows = discover_models(home)

        self.assertEqual(3, len(rows))
        self.assertEqual(
            ["Case Studies/Network Models/CSN_301_Small Network.PIPS",
             "Case Studies/Other/sample.pips",
             "Case Studies/Well Models/CSW_101_Basic Oil Well.pips"],
            [row["modelRelativePath"] for row in rows],
        )
        well_row = rows[2]
        self.assertEqual("WELL_MODEL", well_row["catalogGroup"])
        self.assertEqual(len(b"well model"), well_row["sizeBytes"])
        self.assertEqual(hashlib.sha256(b"well model").hexdigest(), well_row["sha256"])
        self.assertEqual("NOT_TESTED", well_row["compatibilityStatus"])
        self.assertTrue(all(not Path(str(row["modelRelativePath"])).is_absolute() for row in rows))

    def test_csv_is_portable_and_retains_unverified_status(self):
        with tempfile.TemporaryDirectory() as temporary:
            model = Path(temporary) / "Case Studies" / "Well Models" / "one.pips"
            model.parent.mkdir(parents=True)
            model.write_bytes(b"model")
            output = io.StringIO(newline="")

            write_inventory(discover_models(temporary), output)

        output.seek(0)
        rows = list(csv.DictReader(output))
        self.assertEqual(list(FIELDS), list(rows[0]))
        self.assertEqual("NOT_TESTED", rows[0]["compatibilityStatus"])
        self.assertEqual("Case Studies/Well Models/one.pips", rows[0]["modelRelativePath"])

    def test_requires_a_valid_installation_root(self):
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(ValueError, "Case Studies"):
                discover_models(temporary)

    def test_cli_creates_utf8_output_without_overwriting_existing_file(self):
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary) / "install"
            model = home / "Case Studies" / "Well Models" / "one.pips"
            model.parent.mkdir(parents=True)
            model.write_bytes(b"model")
            output = Path(temporary) / "inventory.csv"
            args = ["--pipesim-home", str(home), "--output", str(output)]

            self.assertEqual(0, main(args))
            original = output.read_bytes()
            self.assertTrue(original.startswith(b"\xef\xbb\xbf"))
            with redirect_stderr(io.StringIO()):
                self.assertEqual(2, main(args))
            self.assertEqual(original, output.read_bytes())


if __name__ == "__main__":
    unittest.main()
