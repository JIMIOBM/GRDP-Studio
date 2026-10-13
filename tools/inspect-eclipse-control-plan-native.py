"""Independently inspect all raw native phased-control outputs, including later override."""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import re

parser = argparse.ArgumentParser()
parser.add_argument('--phased', required=True)
parser.add_argument('--initial', required=True)
parser.add_argument('--output', required=True)
args = parser.parse_args()
spec = importlib.util.spec_from_file_location('native', Path(__file__).with_name('inspect-eclipse-group-rate-native.py'))
native = importlib.util.module_from_spec(spec)
spec.loader.exec_module(native)
result = {}
for kind in ['baseline', 'target', 'limited', 'initial']:
    folder = Path(args.initial) if kind == 'initial' else Path(args.phased) / kind
    metadata = dict(native.read(folder / 'CASE.SMSPEC'))
    time_index = metadata['KEYWORDS'].index('TIME')
    points = [values for file in sorted(folder.glob('CASE.S[0-9]*'))
              for key, values in native.read(file) if key == 'PARAMS' and values[time_index] > 0]
    assert points and points[-1][time_index] == 3650
    curves = [{'keyword': key, 'objectName': well if key[0] != 'F' else None, 'unit': unit,
               'points': [{'timeDays': v[time_index], 'value': v[index]} for v in points]}
              for index, (key, well, unit) in enumerate(zip(metadata['KEYWORDS'], metadata['WGNAMES'], metadata['UNITS']))
              if key in ['WOPR', 'WBHP', 'GOPR']]
    def curve(key, well):
        row, = [c for c in curves if c['keyword'] == key and c['objectName'] == well]
        return row['points']
    counts = []
    for well in ['PRODUCR1', 'PRODUCR2']:
        reached = 0
        for index, point in enumerate(curve('WOPR', well)):
            time = point['timeDays']
            phase = sum(time > boundary for boundary in [365, 730, 1095])
            target = 500 if phase == 0 or kind in ['baseline', 'initial'] else [250, 350, 450][phase - 1] if kind == 'target' else 100000
            if kind == 'initial' and well == 'PRODUCR1' and time <= 365:
                target = 400
            assert curve('WBHP', well)[index]['value'] >= 2499.9
            attained = abs(point['value'] - target) <= max(.0001, abs(target) * 1e-6)
            reached += attained
            if kind != 'limited':
                assert attained, (kind, well, time, point['value'], target)
            elif not attained:
                assert abs(curve('WBHP', well)[index]['value'] - 2500) <= .1
        counts.append(reached)
    diagnostics = (folder / 'CASE.ECLEND').read_text(encoding='utf-8')
    diagnostics = {key.lower(): int(re.search(r'^\s*' + key + r'\s+(\d+)', diagnostics, re.M)[1])
                   for key in ['Comments', 'Warnings', 'Problems', 'Errors', 'Bugs']}
    assert all(diagnostics[key] == 0 for key in ['problems', 'errors', 'bugs'])
    result[kind] = {'nativeVerified': True, 'manualUiVerified': False,
                    'inputSha256': hashlib.sha256((folder / 'CASE.DATA').read_bytes()).hexdigest(),
                    'pointCount': len(points), 'targetCounts': counts, 'diagnostics': diagnostics, 'curves': curves}
Path(args.output).write_text(json.dumps(result, indent=2), encoding='utf-8')
print(json.dumps({key: {k: v for k, v in value.items() if k != 'curves'} for key, value in result.items()}))
