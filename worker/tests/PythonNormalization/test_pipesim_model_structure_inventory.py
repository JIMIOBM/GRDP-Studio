import tempfile
import types
import unittest
from pathlib import Path

from tools.pipesim_model_structure_inventory import (
    collect_inventory,
    inspect_model,
    inspect_open_model,
)


class FakeEntry:
    def __init__(self, name):
        self.name = name


class FakeCatalog:
    def __init__(self):
        self.values = {
            "study": [FakeEntry("Study 1")],
            "network-sim": [FakeEntry("Network simulation")],
            "well-curves": [],
        }

    def lookup_entries_by_class_ids(self, class_ids):
        return self.values.get(class_ids[0], [])


class FakeModel:
    def __init__(self, fail_component=None):
        self._catalog = FakeCatalog()
        self.about = types.SimpleNamespace(toolkit_version="2022.1")
        self.closed = False
        self.fail_component = fail_component

    def find(self, component):
        if component == self.fail_component:
            raise RuntimeError("C:\\private\\path\\must-not-escape")
        return {
            "Well": ["Well_1"],
            "Source": ["Source_1", "Source_2"],
            "Sink": ["Sink_1"],
            "Flowline": ["Line_1"],
            "Completion": ["Well_1:Completion"],
            "Tubing": ["Well_1:Tubing"],
        }.get(component, [])

    def close(self):
        self.closed = True


class PipesimModelStructureInventoryTests(unittest.TestCase):
    def test_reads_stored_metadata_without_claiming_solver_compatibility(self):
        model = FakeModel()
        classes = types.SimpleNamespace(
            STUDY="study",
            NETWORKSIMULATION="network-sim",
            WELLPERFORMANCECURVESOPERATION="well-curves",
        )

        inspection = inspect_open_model(model, classes)

        self.assertEqual(["Study 1"], inspection["studies"])
        self.assertEqual("2022.1", inspection["toolkitVersion"])
        self.assertEqual(["Well_1"], inspection["wellContexts"])
        self.assertEqual(2, inspection["componentCounts"]["Source"])
        self.assertEqual({"NETWORKSIMULATION": ["Network simulation"]}, inspection["storedTaskOperations"])
        self.assertEqual("NOT_TESTED", inspection["compatibilityStatus"])
        self.assertNotIn("run", inspection)

    def test_inspection_closes_model_and_records_only_portable_identity(self):
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            model_path = home / "Case Studies" / "Network Models" / "model.pips"
            model_path.parent.mkdir(parents=True)
            model_path.write_bytes(b"model")
            opened = []

            def factory(_path):
                model = FakeModel()
                opened.append(model)
                return model

            row = inspect_model(model_path, home, factory, types.SimpleNamespace(STUDY="study"))

        self.assertEqual("INSPECTED", row["inspectionStatus"])
        self.assertEqual("Case Studies/Network Models/model.pips", row["modelRelativePath"])
        self.assertTrue(opened[0].closed)
        self.assertNotIn(temporary, repr(row))

    def test_component_read_failure_does_not_prevent_close_or_leak_error_text(self):
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            model_path = home / "Case Studies" / "Well Models" / "model.pips"
            model_path.parent.mkdir(parents=True)
            model_path.write_bytes(b"model")
            model = FakeModel(fail_component="Well")

            row = inspect_model(model_path, home, lambda _path: model,
                                types.SimpleNamespace(STUDY="study"))

        self.assertEqual("INSPECTION_FAILED", row["inspectionStatus"])
        self.assertEqual("PTK_RUNTIMEERROR", row["diagnosticCode"])
        self.assertTrue(model.closed)
        self.assertNotIn("private", repr(row))

    def test_collect_inventory_can_select_one_portable_relative_model(self):
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            model_path = home / "Case Studies" / "Network Models" / "one.pips"
            model_path.parent.mkdir(parents=True)
            model_path.write_bytes(b"model")
            payload = collect_inventory(
                home,
                lambda _path: FakeModel(),
                types.SimpleNamespace(STUDY="study"),
                selected_relative_path="case studies/NETWORK MODELS/ONE.PIPS",
            )

        self.assertEqual("pipesim-model-structure-inventory/1", payload["schemaVersion"])
        self.assertEqual(1, len(payload["models"]))
        self.assertEqual("NETWORK_MODEL", payload["models"][0]["catalogGroup"])


if __name__ == "__main__":
    unittest.main()
