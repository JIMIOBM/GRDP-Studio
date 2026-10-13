"""Real API ORAT calculation, source/ZIP checks and parameter-free reimport. No commercial solver substitute."""
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
    parser.add_argument('--well', default='PRODUCR1')
    parser.add_argument('--target', type=float, default=10000)
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
        project = request('/projects', {'name': 'ECLIPSE-P01-' + name + '-' + out.name, 'description': 'Real ORAT and reimport verification.'})
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
        return {(s['keyword'], s.get('objectName'), s.get('unit')): s['points'] for s in items}

    try:
        version = upload(source.name, original)
        baseline = run(version, None)
        record, = [r for r in version['inspection']['scheduleMetadata']['records'] if r['keyword'] == 'WCONPROD' and r['values'][0] == args.well]
        params = {'schemaVersion': 'eclipse-production-target-parameters/1', 'baselineRunId': baseline['id'], 'well': args.well,
            'phase': 'INITIAL', 'sourceFile': record['sourceFile'], 'lineNumber': record['lineNumber'], 'unit': 'STB/day',
            'originalOilRate': float(record['values'][3]), 'targetOilRate': args.target}
        save('request.json', params)
        before = request('/model-versions/' + str(version['id']) + '/runs')
        request('/model-versions/' + str(version['id']) + '/runs', {'runType': 'eclipse', 'study': None, 'parameters': {**params, 'originalOilRate': params['originalOilRate'] - 1}}, expected=400)
        assert before == request('/model-versions/' + str(version['id']) + '/runs'), 'rejected request created run'
        derived = run(version, params)
        left, right = series(baseline), series(derived)
        key, = [k for k in right if k[0] == 'WOPR' and k[1] == args.well]
        old = {p['timeDays']: p['value'] for p in left[key]}
        common = [p for p in right[key] if p['timeDays'] > 0 and p['timeDays'] in old]
        assert common and math.isclose(common[0]['value'], args.target, abs_tol=0.1) and old[common[0]['timeDays']] > common[0]['value']
        report['firstCommonPoint'] = {**common[0], 'baseline': old[common[0]['timeDays']], 'unit': key[2]}
        artifact, = [a for a in derived['artifacts'] if a['name'] == 'eclipse-schedule-applied-package.zip']
        with urllib.request.urlopen(args.api.rstrip('/') + '/runs/' + str(derived['id']) + '/artifacts/' + str(artifact['id']) + '/download', timeout=30) as response:
            package = response.read()
        assert len(package) == artifact['sizeBytes'] and hashlib.sha256(package).hexdigest() == artifact['sha256'].lower()
        (out / 'applied.zip').write_bytes(package)
        with zipfile.ZipFile(io.BytesIO(package)) as z:
            assert z.namelist() == [source.name], z.namelist()
            changed = z.read(source.name)
        pattern = rb"('" + args.well.encode() + rb"'\s+'OPEN'\s+'ORAT'\s+)" + re.escape(record['values'][3].encode()) + rb'(?=\s)'
        expected, count = re.subn(pattern, lambda m: m[1] + format(args.target, '.17g').encode(), original)
        assert count == 1 and changed == expected, 'unexpected input modification'
        replay = run(upload('applied.zip', package), None)
        assert series(replay) == right, 'reimport summary differs'
        assert source.read_bytes() == original, 'official source changed'
        report.update(apiVerified=True, runIds=[baseline['id'], derived['id'], replay['id']], sourceUnchanged=True,
            reimportNumericPaths=sum(2 * len(p) for p in right.values()), reimportMaxDifference=0, rejectedRequestCreatedRun=False)
        print(json.dumps(report, ensure_ascii=False))
    except Exception as error:
        report['error'] = str(error)
        raise
    finally:
        save('acceptance-summary.json', report)


if __name__ == '__main__':
    main()
