"""Independent thermal oracle: all raw columns per run, physical equality between runs.

File Formats Reference Manual PDF18 defines TCPU/ELAPSED/TCPUTS/TCPUDAY as
computer performance measures. Their values belong to that run, not its reservoir state.
"""
import hashlib
import json
import math
import urllib.request
from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path

TIMING = frozenset(['TCPU', 'ELAPSED', 'TCPUTS', 'TCPUDAY'])


def compare_physics(actual, expected):
    assert actual.keys() == expected.keys()
    assert actual['startDate'] == expected['startDate'] and actual['timeVectorIndex'] == expected['timeVectorIndex']
    assert len(actual['series']) == len(expected['series'])
    physical_paths = 0
    timings = []
    for a, b in zip(actual['series'], expected['series']):
        assert {k: v for k, v in a.items() if k != 'points'} == {k: v for k, v in b.items() if k != 'points'}
        assert len(a['points']) == len(b['points'])
        if a['keyword'] in TIMING:
            assert a['unit'] == ('SEC/DAY' if a['keyword'] == 'TCPUDAY' else 'SECONDS')
            assert [p['timeDays'] for p in a['points']] == [p['timeDays'] for p in b['points']]
            assert all(math.isfinite(p['value']) and p['value'] >= 0 for p in a['points'])
            timings.append(a['keyword'])
        else:
            assert a['points'] == b['points'], ('Physical output differs', a['keyword'], a['vectorIndex'])
            physical_paths += 2 * len(a['points'])
    assert sorted(timings) == sorted(TIMING)
    return physical_paths


def verify_own_binary(api, run, out):
    assert run['result']['schemaVersion'] == 'eclipse-summary-result/3'
    directory = Path(out) / ('native-run-' + str(run['id']))
    directory.mkdir(exist_ok=True)
    for extension in ['SMSPEC', 'UNSMRY']:
        name = 'eclipse-output-THERM3.' + extension
        artifact, = [a for a in run['artifacts'] if a['name'] == name]
        with urllib.request.urlopen(f"{api}/runs/{run['id']}/artifacts/{artifact['id']}/download", timeout=60) as response:
            content = response.read()
        assert len(content) == artifact['sizeBytes'] and hashlib.sha256(content).hexdigest() == artifact['sha256']
        file = directory / ('THERM3.' + extension)
        if file.exists():
            assert file.read_bytes() == content, 'Saved native artifact changed'
        else:
            file.write_bytes(content)
    spec = spec_from_file_location('independent_binary', Path(__file__).with_name('inspect-eclipse-compositional-native.py'))
    decoder = module_from_spec(spec); spec.loader.exec_module(decoder)
    native = decoder.summary(directory, 'THERM3', 500)
    assert native == run['result']['summary'], 'All identities, clock and values must match this run native binary, including CPU timings'
    paths = sum(2 * len(s['points']) for s in native['series'])
    (directory / 'acceptance.json').write_text(json.dumps(dict(runId=run['id'], nativeBinaryVerified=True,
        seriesCount=len(native['series']), numericPaths=paths, maxDifference=0), indent=2), encoding='utf-8')
    return paths
