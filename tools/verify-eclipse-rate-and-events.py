"""Main-console commercial calculations, independently checked edits and actual ZIP replay."""
import argparse
import hashlib
import io
import json
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
    parser.add_argument('--source', required=True)
    parser.add_argument('--evidence', required=True)
    args = parser.parse_args()
    uri = urllib.parse.urlparse(args.api)
    assert uri.scheme == 'http' and uri.hostname in ('127.0.0.1', 'localhost') and not uri.username and not uri.query and not uri.fragment
    source = Path(args.source).resolve(strict=True)
    out = Path(args.evidence).resolve()
    assert not out.is_relative_to(source) and not out.is_relative_to(Path(__file__).resolve().parent.parent)
    out.mkdir(parents=True, exist_ok=False)
    originals = {name: (source / name).read_bytes() for name in ('EX5.DATA', 'BASE.SCH', 'EX5.GRDECL')}
    report = {'apiVerified': False, 'manualUiVerified': False, 'scenarios': []}

    def save(name, value):
        (out / name).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')

    def request(path, body=None, content_type=None, expected=200):
        data = body if isinstance(body, bytes) else json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(args.api.rstrip('/') + path, data=data, headers={'Content-Type': content_type or 'application/json'} if data is not None else {})
        try:
            response = urllib.request.urlopen(req, timeout=30)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read()
            assert response.status == expected, (path, response.status, raw[:400])
            result = json.loads(raw)
            assert result['code'] == expected
            return result.get('data')

    def upload(name, payload):
        project = request('/projects', {'name': 'ECLIPSE-P02-P03-' + name + '-' + out.name, 'description': 'Actual rate/event plan export and replay.'})
        boundary = uuid.uuid4().hex
        body = ('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="' + name + '"\r\nContent-Type: application/zip\r\n\r\n').encode() + payload
        body += ('\r\n--' + boundary + '\r\nContent-Disposition: form-data; name="mainFile"\r\n\r\nEX5.DATA\r\n--' + boundary + '--\r\n').encode()
        request('/projects/' + str(project['id']) + '/models', body, 'multipart/form-data; boundary=' + boundary)
        for _ in range(120):
            detail = request('/projects/' + str(project['id']))
            versions = [v for m in detail['models'] for v in m['versions']]
            if len(versions) == 1 and versions[0]['status'] == 'READY':
                save('version-' + str(versions[0]['id']) + '.json', versions[0])
                return versions[0]
            assert not versions or versions[0]['status'] not in ('INVALID', 'FAILED'), versions
            time.sleep(1)
        raise AssertionError('validation timed out')

    def run(version, parameters):
        accepted = request('/model-versions/' + str(version['id']) + '/runs', {'runType': 'eclipse', 'study': None, 'parameters': parameters}, expected=201)
        for _ in range(600):
            result = request('/runs/' + str(accepted['id']))
            if result['status'] in ('SUCCEEDED', 'PARTIAL_SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT'):
                save('run-' + str(result['id']) + '.json', result)
                assert result['status'] == 'SUCCEEDED' and result['resultContract'] == 'VALID_FULL', (result['status'], result.get('error'))
                assert all(result['result']['eclEnd'][k] == 0 for k in ('problems', 'errors', 'bugs'))
                assert all(result['cleanup'][k] for k in ('processTreeExitConfirmed', 'inputDeleted', 'workDirectoryDeleted'))
                return result
            time.sleep(1)
        raise AssertionError('run timed out')

    def all_series(run):
        items = run['result']['summary']['series']
        indexed = {(s['keyword'], s.get('objectName'), s['unit']): s['points'] for s in items}
        assert indexed and len(indexed) == len(items), 'duplicate series identity'
        for points in indexed.values():
            assert len({p['timeDays'] for p in points}) == len(points), 'duplicate times'
        return indexed

    def points(run, keyword, well):
        candidates = [(key, values) for key, values in all_series(run).items() if key[0] == keyword and key[1] == well]
        key, values = candidates[0]
        assert len(candidates) == 1
        return key[2], {p['timeDays']: p['value'] for p in values}

    try:
        payload = io.BytesIO()
        with zipfile.ZipFile(payload, 'w', zipfile.ZIP_DEFLATED) as archive:
            for name, content in originals.items():
                archive.writestr(name, content)
        (out / 'official-ex5.zip').write_bytes(payload.getvalue())
        version = upload('official-ex5.zip', payload.getvalue())
        baseline = run(version, None)
        report['baselineRunId'] = baseline['id']
        parameters = [
            {'schemaVersion': 'eclipse-schedule-parameters/3', 'baselineRunId': baseline['id'], 'well': 'D1', 'date': '1970-01-15', 'injectionType': 'WATER', 'controlMode': 'RATE', 'targetInjectionRate': 1500},
            {'schemaVersion': 'eclipse-schedule-parameters/2', 'baselineRunId': baseline['id'], 'well': 'G1', 'date': '1970-01-15', 'status': 'OPEN', 'controlMode': 'ORAT', 'targetOilRate': 55.5},
            {'schemaVersion': 'eclipse-schedule-parameters/7', 'baselineRunId': baseline['id'], 'wells': ['G1', 'G2'], 'events': [
                {'date': '1970-01-15', 'status': 'SHUT'}, {'date': '1970-02-01', 'status': 'OPEN'}, {'date': '1970-03-01', 'status': 'SHUT'}]}
        ]
        for index, params in enumerate(parameters):
            bad = {**params, 'well': 'UNKNOWN'} if index < 2 else {**params, 'events': [*params['events'][:2], params['events'][1]]}
            before = request('/model-versions/' + str(version['id']) + '/runs')
            request('/model-versions/' + str(version['id']) + '/runs', {'runType': 'eclipse', 'study': None, 'parameters': bad}, expected=400)
            assert before == request('/model-versions/' + str(version['id']) + '/runs'), 'rejection created run'
            save('request-' + str(index) + '.json', params)
            derived = run(version, params)
            witnesses = []
            for well in params.get('wells', [params.get('well')]):
                keyword = 'WWIR' if index == 0 else 'WOPR'
                unit, old = points(baseline, keyword, well)
                new_unit, current = points(derived, keyword, well)
                assert unit == new_unit
                aligned = [{'timeDays': t, 'baseline': old[t], 'current': v} for t, v in current.items() if t in old and 14 < t <= 31]
                assert aligned
                target = 1500 if index == 0 else 55.5 if index == 1 else 0
                witness = next((p for p in aligned if abs(p['current'] - target) < 0.02 and abs(p['baseline'] - p['current']) > 0.1), None)
                assert witness, (well, aligned)
                if index == 2:
                    assert any(31 < t <= 59 and v > 0 for t, v in current.items()), 'reopening has no production'
                    assert any(59 < t <= 90 and v == 0 for t, v in current.items()), 'third shut event has no response'
                witnesses.append({'well': well, 'keyword': keyword, 'unit': unit, **witness})
            artifact, = [a for a in derived['artifacts'] if a['name'] == 'eclipse-schedule-applied-package.zip']
            with urllib.request.urlopen(args.api.rstrip('/') + '/runs/' + str(derived['id']) + '/artifacts/' + str(artifact['id']) + '/download', timeout=30) as response:
                package = response.read()
            assert len(package) == artifact['sizeBytes'] and hashlib.sha256(package).hexdigest() == artifact['sha256'].lower()
            (out / ('applied-' + str(index) + '.zip')).write_bytes(package)
            with zipfile.ZipFile(io.BytesIO(package)) as archive:
                assert set(archive.namelist()) == set(originals) and len(archive.namelist()) == len(originals)
                actual = {name: archive.read(name) for name in originals}
            expected = dict(originals)
            text = originals['BASE.SCH'].decode('utf-8')
            newline = '\r\n' if '\r\n' in text else '\n'
            if index < 2:
                # Independent exact source-row check in the first selected date, leaving later controls untouched.
                start = text.index("15 'JAN' 1970")
                end = text.index('DATES', start)
                block = text[start:end]
                pattern = r"('D1'\s+'WATER'\s+\S+\s+'RATE'\s+)0\.000" if index == 0 else r"('G1'\s+'OPEN'\s+'ORAT'\s+)35\.686"
                replacement = format(1500 if index == 0 else 55.5, '.17g')
                block, count = re.subn(pattern, lambda m: m[1] + replacement, block)
                assert count == 1
                expected['BASE.SCH'] = (text[:start] + block + text[end:]).encode('utf-8')
                assert actual == expected, 'unexpected input edits'
            else:
                changed = actual['BASE.SCH'].decode('utf-8')
                for status in ('SHUT', 'OPEN', 'SHUT'):
                    fragment = newline + newline.join(['-- GRDP-Studio controlled final well-state event; original schedule records are retained.', 'WELOPEN', "       'G1' '" + status + "' /", "       'G2' '" + status + "' /", '/']) + newline
                    assert fragment in changed
                    changed = changed.replace(fragment, '', 1)
                actual['BASE.SCH'] = changed.encode('utf-8')
                assert actual == originals, 'unexpected event edits'
            replay = run(upload('applied-' + str(index) + '.zip', package), None)
            assert all_series(derived) == all_series(replay), 'replay numerical mismatch'
            scenario = {'schemaVersion': params['schemaVersion'], 'runId': derived['id'], 'replayRunId': replay['id'], 'witnesses': witnesses,
                'numericPaths': sum(2 * len(p) for p in all_series(derived).values()), 'maximumDifference': 0, 'rejectedRequestCreatedRun': False}
            report['scenarios'].append(scenario)
            save('acceptance-summary.json', report)
            print(json.dumps(scenario), flush=True)
        report['apiVerified'] = True
    except Exception as error:
        report['error'] = str(error)
        raise
    finally:
        report['sourceUnchanged'] = all((source / name).read_bytes() == content for name, content in originals.items())
        report['apiVerified'] = report['apiVerified'] and report['sourceUnchanged']
        save('acceptance-summary.json', report)


if __name__ == '__main__':
    main()
