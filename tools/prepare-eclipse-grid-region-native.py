"""Independent native rectangular PERMX/PORO proof, serial commercial solver runs."""
import argparse
import hashlib
import importlib.util
import json
import re
import struct
import subprocess
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('--evidence', required=True); args = parser.parse_args()
    repo = Path(__file__).resolve().parent.parent
    out = Path(args.evidence).resolve(); assert not out.is_relative_to(repo) and not out.is_relative_to(Path('C:/GRDP-Data'))
    out.mkdir(exist_ok=False, parents=True)
    original = (repo / 'test-data/ECLIPSE_PERMX_WATER.DATA').read_bytes()
    assert hashlib.sha256(original).hexdigest() == '97b5fdebe5df1c4e5c85b9b67d6d8bb747ee48aff0bf1e3668cfdc0e8fa43222'
    spec = importlib.util.spec_from_file_location('oracle', repo / 'tools/inspect-eclipse-compositional-native.py'); oracle = importlib.util.module_from_spec(spec); spec.loader.exec_module(oracle)
    spec = importlib.util.spec_from_file_location('canonical_names', repo / 'tools/verify-eclipse-permx.py'); names = importlib.util.module_from_spec(spec); spec.loader.exec_module(names)
    proof = dict(nativeVerified=False, manualUiVerified=False); arrays = {}; selected = [0,1,2,15,16,17]
    newline = b'\r\n' if b'\r\n' in original else b'\n'
    for label, keyword, multiplier in [('baseline', None, None), ('permx', 'PERMX', '0.5'), ('poro', 'PORO', '0.8')]:
        directory = out / label; directory.mkdir()
        block = newline.join([b'MULTIPLY', f' {keyword} {multiplier} 1 3 1 2 1 1 /'.encode(), b'/', b'']) if keyword else b''
        data = original.replace(b'PROPS     =', block + b'PROPS     =', 1) if keyword else original
        assert data.replace(block, b'', 1) == original if keyword else data == original
        (directory / 'REGION.DATA').write_bytes(data)
        with (directory / 'launcher.log').open('wb') as log:
            completed = subprocess.run(['C:/ecl/macros/eclrun.exe', '-v', '2024.1', 'eclipse', 'REGION.DATA'], cwd=directory, stdout=log, stderr=subprocess.STDOUT, timeout=300)
        assert completed.returncode == 0
        text = (directory / 'REGION.ECLEND').read_text(encoding='ascii')
        diagnostic = {key.lower(): int(re.search(r'\b' + key + r'\s+(\d+)', text)[1]) for key in ['Comments','Warnings','Problems','Errors','Bugs']}
        assert all(diagnostic[key] == 0 for key in ['problems','errors','bugs'])
        static = {key: values for key, _, values in oracle.records(directory / 'REGION.INIT')}
        arrays[label] = {key: static[key] for key in ['PERMX','PERMY','PORO','DX','DY','DZ','DEPTH']}
        assert all(len(values) == 225 for values in arrays[label].values())
        sms = {key: values for key, _, values in oracle.records(directory / 'REGION.SMSPEC')}; time = sms['KEYWORDS'].index('TIME')
        points = [v for key, _, v in oracle.records(directory / 'REGION.UNSMRY') if key == 'PARAMS']
        series = names.native_series([dict(keyword=key, objectName=sms['WGNAMES'][i] or None, unit=sms['UNITS'][i] or None,
                     points=[dict(timeDays=row[time], value=row[i]) for row in points]) for i,key in enumerate(sms['KEYWORDS']) if i != time])
        assert len(series) == 4 and len(points) == 24 and points[-1][time] == 300
        proof[label] = dict(eclEnd=diagnostic, series=series, arrays=arrays[label])
        if keyword:
            target = struct.unpack('>f', struct.pack('>f', (100 if keyword == 'PERMX' else 1) * float(multiplier)))[0]
            for name, values in arrays[label].items():
                for index, value in enumerate(values):
                    expected = target if name == keyword and index in selected else arrays['baseline'][name][index]
                    assert value == expected, (label, name, index, value, expected)
            assert (directory / 'REGION.EGRID').read_bytes() == (out / 'baseline/REGION.EGRID').read_bytes()
    proof.update(nativeVerified=True, selectedIndices=selected, selectedCells=6, unchangedOutsideCells=219, cellCount=225)
    (out / 'native-acceptance.json').write_text(json.dumps(proof, indent=2), encoding='utf-8')
    print(json.dumps({key: value for key,value in proof.items() if key not in ['baseline','permx','poro']}))


if __name__ == '__main__': main()
