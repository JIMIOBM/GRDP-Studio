"""Independent complete binary E300 summary decoding for numerical acceptance."""
import argparse
import datetime
import json
import math
import re
import struct
from pathlib import Path


def records(path):
    data = Path(path).read_bytes()
    position = 0
    result = []
    def record():
        nonlocal position
        assert position + 8 <= len(data), 'Truncated record'
        length, = struct.unpack_from('>i', data, position)
        assert 0 < length <= len(data) - position - 8
        start = position + 4
        payload = data[start:start + length]
        assert struct.unpack_from('>i', data, start + length)[0] == length
        position = start + length + 4
        return payload
    while position < len(data):
        header = record()
        assert len(header) == 16
        keyword = header[:8].decode('ascii').strip()
        count, = struct.unpack_from('>i', header, 8)
        kind = header[12:].decode('ascii')
        size = {'INTE': 4, 'LOGI': 4, 'REAL': 4, 'DOUB': 8, 'CHAR': 8, 'MESS': 0}[kind]
        assert count >= 0
        raw = bytearray()
        while len(raw) < count * size:
            raw.extend(record())
        assert len(raw) == count * size
        values = [raw[i:i + 8].decode('ascii').strip() for i in range(0, len(raw), 8)] if kind == 'CHAR' else list(struct.unpack('>' + str(count) + {'INTE': 'i', 'LOGI': 'i', 'REAL': 'f', 'DOUB': 'd'}[kind], raw)) if count else []
        result.append((keyword, kind, values))
    return result


def summary(directory, case='SPE3_GASCOND', solver_id=300):
    root = Path(directory)
    specs = records(root / (case + '.SMSPEC'))
    assert len({key for key, _, _ in specs}) == len(specs)
    arrays = {key: values for key, _, values in specs}
    assert solver_id in (300, 500) and arrays['INTEHEAD'][1] == solver_id
    n = arrays['DIMENS'][0]
    assert all(len(arrays[k]) == n for k in ['KEYWORDS', 'WGNAMES', 'NUMS', 'UNITS'])
    axis, = [i for i, key in enumerate(arrays['KEYWORDS']) if key == 'TIME' and arrays['UNITS'][i] == 'DAYS']
    unified = root / (case + '.UNSMRY')
    files = [unified] if unified.exists() else sorted(p for p in root.iterdir() if re.fullmatch(re.escape(case) + r'\.S[0-9]{4}', p.name))
    assert files
    rows = [v for p in files for k, _, v in records(p) if k == 'PARAMS']
    assert rows and all(len(row) == n and all(math.isfinite(v) for v in row) for row in rows)
    assert all(row[axis] >= 0 and (i == 0 or row[axis] > rows[i - 1][axis]) for i, row in enumerate(rows))
    day, month, year = arrays['STARTDAT'][:3]
    start = datetime.date(year, month, day).isoformat()
    return {'startDate': start, 'timeVectorIndex': axis, 'series': [
        {'vectorIndex': i, 'keyword': key, 'objectName': arrays['WGNAMES'][i] or None,
         'number': arrays['NUMS'][i], 'unit': arrays['UNITS'][i] or None,
         'points': [{'timeDays': row[axis], 'value': row[i]} for row in rows]}
        for i, key in enumerate(arrays['KEYWORDS']) if i != axis]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', required=True)
    parser.add_argument('--worker-summary')
    parser.add_argument('--case', default='SPE3_GASCOND')
    parser.add_argument('--program-identifier', type=int, choices=[300, 500], default=300)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    native = summary(args.directory, args.case, args.program_identifier)
    if args.worker_summary:
        actual = json.loads(Path(args.worker_summary).read_text(encoding='utf-8'))
        assert actual == native, 'Complete identity, actual clock or native values differ'
    proof = {'nativeVerified': True, 'workerReaderVerified': bool(args.worker_summary), 'manualUiVerified': False,
             'seriesCount': len(native['series']), 'pointsPerSeries': len(native['series'][0]['points']),
             'numericPaths': sum(2 * len(s['points']) for s in native['series']), 'maxDifference': 0,
             'summary': native}
    Path(args.output).write_text(json.dumps(proof, indent=2), encoding='utf-8')
    print(json.dumps({k: v for k, v in proof.items() if k != 'summary'}))


if __name__ == '__main__':
    main()
