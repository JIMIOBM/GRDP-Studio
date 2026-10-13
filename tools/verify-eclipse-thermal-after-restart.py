"""Reuse the E300 full-output and immutable artifact restart contract for thermal evidence."""
from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path


if __name__ == '__main__':
    spec = spec_from_file_location('e300_restart_contract', Path(__file__).with_name('verify-eclipse-compositional-after-restart.py'))
    module = module_from_spec(spec)
    spec.loader.exec_module(module)
    module.main()
