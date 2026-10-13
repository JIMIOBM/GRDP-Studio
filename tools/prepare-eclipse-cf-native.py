"""Run byte-preserving EX5 connection-factor baseline/target copies and independently read all outputs."""
import argparse
import hashlib
import importlib.util
import json
import subprocess
import zipfile
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', required=True)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parent.parent
    out = Path(args.evidence).resolve()
    assert not out.is_relative_to(repo) and not out.is_relative_to(Path('C:/GRDP-Data'))
    out.mkdir(parents=True, exist_ok=False)
    source = repo / 'test-data/ECLIPSE_EX5_INCLUDE.zip'
    assert hashlib.sha256(source.read_bytes()).hexdigest() == '8dacc60e8670b4aa10aa6ac8f12dca5908ad5e691e2dde3b04d302633cd4c266'
    spec = importlib.util.spec_from_file_location('formatted_oracle', repo / 'tools/inspect-eclipse-restart-native.py')
    oracle = importlib.util.module_from_spec(spec); spec.loader.exec_module(oracle)
    proof = {'nativeVerified': False, 'manualUiVerified': False}
    with zipfile.ZipFile(source) as z:
        original = {name: z.read(name) for name in z.namelist()}
    for label in ['baseline', 'target']:
        directory = out / label; directory.mkdir()
        data = dict(original)
        if label == 'target':
            lines = data['BASE.SCH'].splitlines(keepends=True)
            assert b"'G1'" in lines[115] and lines[115].count(b'3.028') == 1
            lines[115] = lines[115].replace(b'3.028', b'1.5', 1)
            data['BASE.SCH'] = b''.join(lines)
            assert data['BASE.SCH'].replace(b'1.5      0.656', b'3.028      0.656', 1) == original['BASE.SCH']
        for name, content in data.items():
            (directory / name).write_bytes(content)
        with (directory / 'launcher.log').open('wb') as log:
            result = subprocess.run(['C:/ecl/macros/eclrun.exe', '-v', '2024.1', 'eclipse', 'EX5.DATA'], cwd=directory,
                                    stdout=log, stderr=subprocess.STDOUT, timeout=300)
        assert result.returncode == 0
        decoded = oracle.summary(directory, 'EX5')
        proof[label] = {'summary': decoded, 'diagnostics': oracle.diagnostics(directory, 'EX5'),
                        'inputSha256': {name: hashlib.sha256(content).hexdigest() for name, content in data.items()}}
    proof['nativeVerified'] = True
    (out / 'native-acceptance.json').write_text(json.dumps(proof, indent=2), encoding='utf-8')
    print(json.dumps({k: {'seriesCount': len(v['summary']['series']), 'points': len(v['summary']['times']), 'diagnostics': v['diagnostics']} for k, v in proof.items() if isinstance(v, dict)}))


if __name__ == '__main__':
    main()
