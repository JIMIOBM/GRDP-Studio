"""Real API WRAT/GRAT/LRAT calculation, source/ZIP checks and parameter-free reimport. No commercial solver substitute."""
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
    parser.add_argument('--mode', choices=['WRAT', 'GRAT', 'LRAT'], required=True)
    parser.add_argument('--target', type=float, required=True)
    parser.add_argument('--expect-bhp-constrained', type=float)
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
        project = request('/projects', {'name': 'ECLIPSE-P02-RATES-' + name + '-' + out.name, 'description': 'Real production rate control and reimport verification.'})
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
        selected, = [s for s in items if s['keyword'] == {'WRAT': 'WWPR', 'GRAT': 'WGPR', 'LRAT': 'WLPR'}[args.mode] and s.get('objectName') == args.well]
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
        token = expanded[{'WRAT': 4, 'GRAT': 5, 'LRAT': 6}[args.mode]]
        assert '*' not in token.group() and expanded[0].group().strip("'") == args.well and expanded[2].group().strip("'") == args.mode
        return marker.end() + token.start(), marker.end() + token.end(), float(token.group())

    try:
        version = upload(source.name, original)
        baseline = run(version, None)
        record, = [r for r in version['inspection']['scheduleMetadata']['records'] if r['keyword'] == 'WCONPROD' and r['values'][0] == args.well]
        start, end, original_rate = original_token(original)
        unit = 'sm3/day' if version['inspection']['unitSystem'] == 'METRIC' else 'Mscf/day' if args.mode == 'GRAT' else 'STB/day'
        params = {'schemaVersion': 'eclipse-production-control-parameters/1', 'baselineRunId': baseline['id'], 'well': args.well,
            'phase': 'INITIAL', 'sourceFile': record['sourceFile'], 'lineNumber': record['lineNumber'], 'unit': unit,
            'controlMode': args.mode, 'originalTargetRate': original_rate, 'targetRate': args.target}
        save('request.json', params)
        before = request('/model-versions/' + str(version['id']) + '/runs')
        faults = [{'originalTargetRate': params['originalTargetRate'] - 1}, {'unit': 'STB/day' if unit == 'sm3/day' else 'sm3/day'},
                  {'lineNumber': params['lineNumber'] + 1}, {'controlMode': 'BHP'}]
        for fault in faults:
            request('/model-versions/' + str(version['id']) + '/runs', {'runType': 'eclipse', 'study': None, 'parameters': {**params, **fault}}, expected=400)
        assert before == request('/model-versions/' + str(version['id']) + '/runs'), 'rejected request created run'
        derived = run(version, params)
        left, right = series(baseline), series(derived)
        old_series, current_series = target_points(left), target_points(right)
        assert old_series['unit'] == current_series['unit'] and current_series['unit']
        assert current_series['unit'].upper() == {'sm3/day': 'SM3/DAY', 'STB/day': 'STB/DAY', 'Mscf/day': 'MSCF/DAY'}[unit]
        old = {p['timeDays']: p['value'] for p in old_series['points']}
        common = [p for p in current_series['points'] if p['timeDays'] > 0 and p['timeDays'] in old]
        assert common, 'no common positive time'
        if args.expect_bhp_constrained is None:
            assert math.isclose(common[0]['value'], args.target, abs_tol=0.1) and old[common[0]['timeDays']] > common[0]['value'], common[:1]
            report['targetReached'] = True
        else:
            assert all(not math.isclose(p['value'], args.target, abs_tol=0.1) for p in common)
            assert common[0]['value'] > 0 and common[0]['value'] != old[common[0]['timeDays']]
            pressure, = [s for s in right if s['keyword'] == 'WBHP' and s.get('objectName') == args.well]
            point, = [p for p in pressure['points'] if p['timeDays'] == common[0]['timeDays']]
            assert pressure['unit'] == 'PSIA' and math.isclose(point['value'], args.expect_bhp_constrained, abs_tol=0.1)
            report.update(targetReached=False, actualBhp=point['value'], reason='Original BHP lower limit is active; requested rate not reached.')
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
