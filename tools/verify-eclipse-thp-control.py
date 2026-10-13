"""Real API THP/VFP calculation, source/ZIP checks and parameter-free reimport. No commercial solver substitute."""
import argparse
import hashlib
import io
import json
import math
from pathlib import Path
import re
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--api', default='http://127.0.0.1:5173/api/software-integration')
    parser.add_argument('--model', required=True)
    parser.add_argument('--evidence', required=True)
    parser.add_argument('--well', required=True)
    parser.add_argument('--target', type=float, required=True)
    parser.add_argument('--expect-extrapolation', action='store_true')
    args = parser.parse_args()
    url = urllib.parse.urlparse(args.api)
    assert url.scheme == 'http' and url.hostname in ('127.0.0.1', 'localhost') and not url.username and not url.query and not url.fragment
    source = Path(args.model).resolve(strict=True)
    out = Path(args.evidence).resolve()
    repo = Path(__file__).resolve().parent.parent
    assert not out.is_relative_to(repo) and not out.is_relative_to(source.parent)
    out.mkdir(exist_ok=False, parents=True)
    original = source.read_bytes()
    report = {'apiVerified': False, 'manualUiVerified': False, 'sourceSha256': hashlib.sha256(original).hexdigest()}

    def save(name, data):
        (out / name).write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')

    def request(path, body=None, content_type=None, expected=200):
        data = body if isinstance(body, bytes) else json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(args.api.rstrip('/') + path, data=data,
            headers={'Content-Type': content_type or 'application/json'} if data is not None else {})
        try:
            response = urllib.request.urlopen(req, timeout=30)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read()
            assert response.status == expected, (path, response.status, raw[:500])
            value = json.loads(raw)
            assert value['code'] == expected, value
            return value.get('data')

    def wait_run(run_id):
        for _ in range(300):
            run = request('/runs/' + str(run_id))
            if run['status'] in ('SUCCEEDED', 'PARTIAL_SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT'):
                save('run-' + str(run_id) + '.json', run)
                assert run['status'] == 'SUCCEEDED' and run['resultContract'] == 'VALID_FULL', (run['status'], run.get('error'))
                end = run['result']['eclEnd']
                assert all(end[k] == 0 for k in ('problems', 'errors', 'bugs')), end
                assert all(run['cleanup'][k] for k in ('processTreeExitConfirmed', 'inputDeleted', 'workDirectoryDeleted'))
                return run
            time.sleep(1)
        raise AssertionError('run timed out')

    def upload(name, payload):
        project = request('/projects', {'name': 'ECLIPSE-P02-THP-' + name + '-' + out.name, 'description': 'Real production rate control and reimport verification.'})
        project_id = project['id']
        boundary = 'grdp-' + uuid.uuid4().hex
        body = ('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="' + name + '"\r\nContent-Type: application/octet-stream\r\n\r\n').encode() + payload + ('\r\n--' + boundary + '--\r\n').encode()
        request('/projects/' + str(project_id) + '/models', body, 'multipart/form-data; boundary=' + boundary)
        for _ in range(90):
            detail = request('/projects/' + str(project_id))
            versions = [v for m in detail['models'] for v in m['versions']]
            if versions and versions[0]['status'] == 'READY':
                save('version-' + str(versions[0]['id']) + '.json', versions[0])
                return versions[0]
            assert not versions or versions[0]['status'] not in ('INVALID', 'FAILED'), versions
            time.sleep(1)
        raise AssertionError('model validation timed out')

    def run(version, parameters):
        accepted = request('/model-versions/' + str(version['id']) + '/runs', {'runType': 'eclipse', 'study': None, 'parameters': parameters}, expected=201)
        return wait_run(accepted['id'])

    def series(run):
        items = run['result']['summary']['series']
        assert items
        identities = [(s['keyword'], s.get('objectName'), s.get('unit')) for s in items]
        assert len(identities) == len(set(identities)), 'duplicate series identity'
        return items

    def target_points(items):
        selected, = [s for s in items if s['keyword'] == 'WLPR' and s.get('objectName') == args.well]
        return selected

    def original_token(payload):
        text = payload.decode('utf-8')
        marker = re.search(r'(?m)^WCONPROD[ \t]*\r?$', text)
        assert marker
        tokens = list(re.finditer(r"'(?:[^']|'')*'|[^\s/]+", text[marker.end():text.index('/', marker.end())]))
        expanded = []
        for token in tokens:
            value = token.group().strip("'")
            repeat = re.fullmatch(r'(\d+)\*(.*)', value) if expanded else None
            expanded.extend([token] * (int(repeat[1]) if repeat else 1))
        token = expanded[9]
        assert '*' not in token.group() and expanded[0].group().strip("'") == args.well and expanded[2].group().strip("'") == 'THP'
        return marker.end() + token.start(), marker.end() + token.end(), float(token.group())

    try:
        version = upload(source.name, original)
        baseline = run(version, None)
        record, = [r for r in version['inspection']['scheduleMetadata']['records'] if r['keyword'] == 'WCONPROD' and r['values'][0] == args.well]
        start, end, original_rate = original_token(original)
        table, = version['inspection']['vfpTables']
        unit = 'psia'
        params = {'schemaVersion': 'eclipse-thp-control-parameters/1', 'baselineRunId': baseline['id'], 'well': args.well,
            'phase': 'INITIAL', 'sourceFile': record['sourceFile'], 'lineNumber': record['lineNumber'], 'unit': unit,
            'originalThp': original_rate, 'targetThp': args.target, 'vfpTableNumber': table['number'], 'vfpTableSha256': table['sha256'], 'originalAlq': 0}
        save('request.json', params)
        before = request('/model-versions/' + str(version['id']) + '/runs')
        faults = [{'originalThp': params['originalThp'] - 1}, {'unit': 'barsa'}, {'lineNumber': params['lineNumber'] + 1},
                  {'vfpTableSha256': 'b'*64}, {'vfpTableNumber': 99}, {'originalAlq': 1}, {'targetThp': table['thpAxis'][-1]+1}]
        for fault in faults:
            request('/model-versions/' + str(version['id']) + '/runs', {'runType': 'eclipse', 'study': None, 'parameters': {**params, **fault}}, expected=400)
        assert before == request('/model-versions/' + str(version['id']) + '/runs'), 'rejected request created run'
        derived = run(version, params)
        left, right = series(baseline), series(derived)
        old_series, current_series = target_points(left), target_points(right)
        assert old_series['unit'] == current_series['unit'] and current_series['unit']
        assert current_series['unit'].upper() == 'STB/DAY'
        old = {p['timeDays']: p['value'] for p in old_series['points']}
        common = [p for p in current_series['points'] if p['timeDays'] > 0 and p['timeDays'] in old]
        assert common, 'no common positive time'
        assert common[0]['value'] > 0 and common[0]['value'] < old[common[0]['timeDays']], common[:1]
        def unique_points(items, keyword, unit):
            item, = [s for s in items if s['keyword'] == keyword and s.get('objectName') == args.well]
            assert item['unit'].upper() == unit
            points = item['points']
            assert len({p['timeDays'] for p in points}) == len(points)
            return {p['timeDays']: p['value'] for p in points}
        thp_before = unique_points(left, 'WTHP', 'PSIA')
        thp_after = unique_points(right, 'WTHP', 'PSIA')
        bhp_after = unique_points(right, 'WBHP', 'PSIA')
        time_day = common[0]['timeDays']
        assert math.isclose(thp_after[time_day], args.target, abs_tol=.1)
        assert math.isclose(thp_before[time_day], original_rate, abs_tol=.1)
        report.update(targetReached=True, actualThp=thp_after[time_day], baselineThp=thp_before[time_day], actualBhp=bhp_after[time_day])
        coverage = []
        for items in (left, right):
            liquid = unique_points(items, 'WLPR', 'STB/DAY')
            oil = unique_points(items, 'WOPR', 'STB/DAY')
            water = unique_points(items, 'WWPR', 'STB/DAY')
            gas = unique_points(items, 'WGPR', 'MSCF/DAY')
            thp = unique_points(items, 'WTHP', 'PSIA')
            rows = []
            for t, flow in liquid.items():
                if t <= 0 or flow <= 0: continue
                assert oil[t] > 0 and water[t] >= 0 and gas[t] >= 0
                point = {'timeDays': t, 'flow': flow, 'wct': water[t]/flow, 'gor': gas[t]/oil[t], 'thp': thp[t]}
                point['inRange'] = all(table[axis][0] <= point[key] <= table[axis][-1] for key, axis in [('flow','flowAxis'),('wct','waterFractionAxis'),('gor','gasFractionAxis'),('thp','thpAxis')])
                rows.append(point)
            assert rows
            coverage.append(rows)
        report['vfpCoverage'] = coverage
        in_table = all(p['inRange'] for rows in coverage for p in rows)
        report['inTableVerified'] = in_table
        assert in_table != args.expect_extrapolation, 'Unexpected VFP table coverage; cannot claim in-table verification'
        report['firstCommonPoint'] = {**common[0], 'baseline': old[common[0]['timeDays']], 'unit': current_series['unit']}
        artifact, = [a for a in derived['artifacts'] if a['name'] == 'eclipse-schedule-applied-package.zip']
        with urllib.request.urlopen(args.api.rstrip('/') + '/runs/' + str(derived['id']) + '/artifacts/' + str(artifact['id']) + '/download', timeout=30) as response:
            package = response.read()
        assert len(package) == artifact['sizeBytes'] and hashlib.sha256(package).hexdigest() == artifact['sha256'].lower()
        (out / 'applied.zip').write_bytes(package)
        with zipfile.ZipFile(io.BytesIO(package)) as z:
            assert z.namelist() == [source.name], z.namelist()
            changed = z.read(source.name)
        expected = original[:start] + format(args.target, '.17g').encode() + original[end:]
        assert changed == expected, 'unexpected input modification'
        replay = run(upload('applied.zip', package), None)
        assert series(replay) == right, 'reimport summary differs'
        assert source.read_bytes() == original, 'official source changed'
        report.update(apiVerified=True, runIds=[baseline['id'], derived['id'], replay['id']], sourceUnchanged=True,
            reimportNumericPaths=sum(2 * len(s['points']) for s in right), reimportMaxDifference=0, rejectedRequestCreatedRun=False)
        print(json.dumps(report, ensure_ascii=False))
    except Exception as error:
        report['error'] = str(error)
        raise
    finally:
        save('acceptance-summary.json', report)


if __name__ == '__main__':
    main()
