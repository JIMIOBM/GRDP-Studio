import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock

spec = importlib.util.spec_from_file_location("template_preflight", Path(__file__).parents[1] / "pipesim_template_preflight.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class TemplateTests(unittest.TestCase):
    def test_save_close_reopen_does_not_claim_calculation(self):
        with tempfile.TemporaryDirectory() as root:
            model, reopened = Mock(), Mock()
            model.find.return_value = ["ConsoleWell"]
            reopened.find.side_effect = [["ConsoleWell"], ["ConsoleWell:Completion"], ["ConsoleWell:Tubing"]]
            reopened.validate.return_value = ["Fluid is required"]
            api = Mock(new=Mock(return_value=model), open=Mock(return_value=reopened))
            output = Path(root) / "new.pips"
            result = module.create_template(api, output, "ConsoleWell")
            api.new.assert_called_once_with(str(output), overwrite=False)
            model.copy.assert_called_once_with("Simple vertical", "ConsoleWell", True)
            model.save.assert_called_once_with(str(output))
            model.close.assert_called_once()
            reopened.close.assert_called_once()
            self.assertFalse(result["calculationVerified"])
            self.assertEqual(result["modelDiagnostics"], [{"path": "", "property": "", "message": "Fluid is required"}])

    def test_invalid_inputs_never_open_toolkit_model(self):
        with tempfile.TemporaryDirectory() as root:
            existing = Path(root) / "existing.pips"
            existing.touch()
            api = Mock()
            for output, name in [(existing, "Well"), (Path(root) / "new.pips", "../Well"),
                                 (Path("relative.pips"), "Well"), (Path(root) / "new.DATA", "Well")]:
                with self.subTest(output=output, name=name), self.assertRaises(ValueError):
                    module.create_template(api, output, name)
            api.new.assert_not_called()

    def test_copy_failure_closes_model_and_never_reopens(self):
        with tempfile.TemporaryDirectory() as root:
            model = Mock()
            model.copy.side_effect = RuntimeError("Template not installed")
            api = Mock(new=Mock(return_value=model))
            with self.assertRaises(RuntimeError):
                module.create_template(api, Path(root) / "new.pips", "Well")
            model.close.assert_called_once()
            api.open.assert_not_called()


if __name__ == "__main__":
    unittest.main()
