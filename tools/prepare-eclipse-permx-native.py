"""Native full-grid proof: only (1,1,1) PERMX changes, all other static arrays remain equal."""
import argparse
import hashlib
import importlib.util
import json
import re
import subprocess
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', required=True)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parent.parent
    out = Path(args.evidence).resolve()
    assert not out.is_relative_to(repo) and not out.is_relative_to(Path('C:/GRDP-Data'))
    out.mkdir(parents=True, exist_ok=False)
    original = (repo / 'test-data/ECLIPSE_PERMX_WATER.DATA').read_bytes()
    assert hashlib.sha256(original).hexdigest() == '97b5fdebe5df1c4e5c85b9b67d6d8bb747ee48aff0bf1e3668cfdc0e8fa43222'
    newline = b'\r\n' if b'\r\n' in original else b'\n'
    block = newline.join([b'MULTIPLY', b' PERMX 0.5 1 1 1 1 1 1 /', b'/', b''])
    marker, = re.findall(rb'(?m)^PROPS[^\r\n]*', original)
    spec = importlib.util.spec_from_file_location('independent_binary', repo / 'tools/inspect-eclipse-compositional-native.py')
    oracle = importlib.util.module_from_spec(spec); spec.loader.exec_module(oracle)
    proof = dict(nativeVerified=False, manualUiVerified=False)
    arrays = {}
    for label in ['baseline', 'target']:
        directory = out / label; directory.mkdir()
        data = original if label == 'baseline' else original.replace(marker, block + marker, 1)
        if label == 'target': assert data.replace(block, b'', 1) == original
        (directory / 'PERMX.DATA').write_bytes(data)
        with (directory / 'launcher.log').open('wb') as log:
            result = subprocess.run(['C:/ecl/macros/eclrun.exe', '-v', '2024.1', 'eclipse', 'PERMX.DATA'], cwd=directory,
                                    stdout=log, stderr=subprocess.STDOUT, timeout=300)
        assert result.returncode == 0
        text = (directory / 'PERMX.ECLEND').read_text(encoding='ascii')
        diagnostics = {k.lower(): int(re.search(r'\b' + k + r'\s+(\d+)', text)[1]) for k in ['Comments', 'Warnings', 'Problems', 'Errors', 'Bugs']}
        assert all(diagnostics[k] == 0 for k in ['problems', 'errors', 'bugs'])
        assert (directory / 'PERMX.EGRID').is_file()
        arrays[label] = {k: values for k, _, values in oracle.records(directory / 'PERMX.INIT')}
        assert len(arrays[label]['PERMX']) == 225
        summary_arrays = {k: values for k, _, values in oracle.records(directory / 'PERMX.SMSPEC')}
        axis = summary_arrays['KEYWORDS'].index('TIME')
        points = [values for k, _, values in oracle.records(directory / 'PERMX.UNSMRY') if k == 'PARAMS']
        curves = [dict(keyword=key, objectName=summary_arrays['WGNAMES'][i] or None, unit=summary_arrays['UNITS'][i] or None,
                       points=[dict(timeDays=row[axis], value=row[i]) for row in points])
                  for i, key in enumerate(summary_arrays['KEYWORDS']) if i != axis]
        assert points[-1][axis] == 300
        proof[label] = dict(diagnostics=diagnostics, inputSha256=hashlib.sha256(data).hexdigest(), series=curves)
    assert arrays['baseline']['PERMX'] == [100] * 225
    assert arrays['target']['PERMX'] == [50] + [100] * 224
    unchanged = ['PERMY', 'PORO', 'DX', 'DY', 'DZ', 'DEPTH']
    for name in unchanged:
        assert arrays['baseline'][name] == arrays['target'][name]
    proof.update(nativeVerified=True, cellCount=225, changedIndices=[0], unchangedPermxCells=224, unchangedArrays=unchanged,
                 baselinePermx=arrays['baseline']['PERMX'], targetPermx=arrays['target']['PERMX'])
    (out / 'native-acceptance.json').write_text(json.dumps(proof, indent=2), encoding='utf-8')
    print(json.dumps({k: v for k, v in proof.items() if k not in ['baseline', 'target', 'baselinePermx', 'targetPermx']}))


if __name__ == '__main__':
    main()
