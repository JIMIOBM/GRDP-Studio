"""Independent raw binary checks for all four actually calculated injection reference modes."""
import argparse
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import re

p = argparse.ArgumentParser()
p.add_argument('--evidence', required=True)
args = p.parse_args()
out = Path(args.evidence)
out.mkdir(parents=True, exist_ok=False)
repo = Path(__file__).resolve().parent.parent
module = importlib.util.spec_from_file_location('native', repo/'tools/inspect-eclipse-group-rate-native.py')
native = importlib.util.module_from_spec(module)
module.loader.exec_module(native)
proof = {}
for name in ['OIL_INJECTION', 'GAS_RESV', 'GAS_REIN', 'GAS_VREP']:
    provenance = json.loads((repo/f'test-data/ECLIPSE_GROUP_{name}.provenance.json').read_text())
    mode, phase = provenance['mode'], provenance['injectionPhase']
    base = Path(provenance['nativeBaseline']).parent.parent
    proof[name] = {}
    for label, expected in [('baseline', provenance['baselineTarget']), ('target', provenance['positiveTarget']), ('limited', provenance['limitedTarget'])]:
        d = base/label
        spec = dict(native.read(d/'CASE.SMSPEC'))
        time = spec['KEYWORDS'].index('TIME')
        files = sorted(d.glob('CASE.S[0-9]*'))
        points = [v for f in files for k, v in native.read(f) if k == 'PARAMS' and v[time] > 0]
        assert points and len(points) == len({v[time] for v in points})
        keys = {'GOIR', 'WOIR', 'GGIR', 'WGIR', 'GVIR', 'WVIR', 'GGPR', 'WGPR', 'GVPR', 'WVPR', 'WBHP'}
        curves = [{'keyword': k, 'objectName': o, 'unit': u, 'points': [{'timeDays': v[time], 'value': v[n]} for v in points]}
                  for n, (k, o, u) in enumerate(zip(spec['KEYWORDS'], spec['WGNAMES'], spec['UNITS'])) if k in keys]
        assert len(curves) == len({(c['keyword'], c['objectName']) for c in curves})
        assert all(math.isfinite(p['value']) and math.isfinite(p['timeDays']) for c in curves for p in c['points'])
        def row(k, o):
            c, = [c for c in curves if c['keyword'] == k and c['objectName'] == o]
            return c['points']
        group_key, well_key = ('GOIR', 'WOIR') if mode == 'RATE' else ('GVIR', 'WVIR') if mode in ['RESV', 'VREP'] else ('GGIR', 'WGIR')
        surface_key = 'WOIR' if phase == 'OIL' else 'WGIR'
        production_key, production_well_key = ('GVPR', 'WVPR') if mode == 'VREP' else ('GGPR', 'WGPR')
        group = row(group_key, 'I')
        production = row(production_key, 'G')
        actual = []
        for n, value in enumerate(group):
            total = sum(row(well_key, well)[n]['value'] for well in provenance['members'])
            assert abs(total-value['value']) <= max(1e-4, abs(value['value'])*1e-6)
            for well in provenance['members']:
                assert row('WBHP', well)[n]['value'] <= 4900.1
                assert row(surface_key, well)[n]['value'] <= 50000.1
            if mode in ['REIN', 'VREP']:
                produced = production[n]['value']
                assert produced > 0
                production_total = sum(row(production_well_key, f'PRODUCR{k}')[n]['value'] for k in range(1, 5))
                assert abs(production_total-produced) <= max(1e-4, abs(produced)*1e-6)
                actual.append(value['value']/produced)
            else:
                actual.append(value['value'])
        if label != 'limited':
            tolerance = 0.0001 if mode in ['REIN', 'VREP'] else max(1e-4, abs(expected)*1e-6)
            assert all(abs(v-expected) <= tolerance for v in actual), (name, label, min(actual), max(actual))
        else:
            assert any(v < expected for v in actual)
        counts = {k.lower(): int(re.search(k+r'\s+(\d+)', (d/'CASE.ECLEND').read_text()).group(1)) for k in ['Comments', 'Warnings', 'Problems', 'Errors', 'Bugs']}
        assert all(counts[k] == 0 for k in ['problems', 'errors', 'bugs'])
        proof[name][label] = {'nativeDirectory': str(d), 'inputSha256': hashlib.sha256((d/'CASE.DATA').read_bytes()).hexdigest(),
                              'curves': curves, 'eclEnd': counts, 'actualMinimum': min(actual), 'actualMaximum': max(actual),
                              'groupKeyword': group_key, 'wellKeyword': well_key, 'groupFirst': group[0]['value']}
        print(name, label, 'groupFirst', group[0]['value'], 'actualRange', min(actual), max(actual), 'diagnostics', counts)
with (out/'native-acceptance.json').open('x') as f:
    json.dump(proof, f, indent=2)
