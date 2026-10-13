"""Real E100 BHP API runs, independent deck edits and parameter-free ZIP replay."""
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
    ap = argparse.ArgumentParser()
    ap.add_argument('--api', default='http://127.0.0.1:5173/api/software-integration')
    ap.add_argument('--model', required=True)
    ap.add_argument('--injection-model', required=True)
    ap.add_argument('--evidence', required=True)
    ap.add_argument('--injection-target', type=float, default=4700)
    args = ap.parse_args()
    url = urllib.parse.urlparse(args.api)
    assert url.scheme == 'http' and url.hostname in ('127.0.0.1', 'localhost') and not url.username and not url.query and not url.fragment
    source = Path(args.model).resolve(strict=True)
    injection_source = Path(args.injection_model).resolve(strict=True)
    out = Path(args.evidence).resolve()
    assert not out.is_relative_to(Path(__file__).resolve().parent.parent) and not out.is_relative_to(source.parent)
    out.mkdir(parents=True, exist_ok=False)
    original = source.read_bytes()
    injection_original = injection_source.read_bytes()
    report = {'apiVerified': False, 'manualUiVerified': False, 'sourceSha256': hashlib.sha256(original).hexdigest(), 'scenarios': []}
    report['injectionSourceSha256'] = hashlib.sha256(injection_original).hexdigest()

    def save(name, value):
        (out / name).write_text(json.dumps(value, indent=2, ensure_ascii=False), encoding='utf-8')

    def request(path, body=None, mime=None, expected=200):
        data = body if isinstance(body, bytes) else json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(args.api.rstrip('/') + path, data=data, headers={'Content-Type': mime or 'application/json'} if data is not None else {})
        try:
            response = urllib.request.urlopen(req, timeout=30)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read()
            assert response.status == expected, (path, response.status, raw[:1000])
            value = json.loads(raw)
            assert value['code'] == expected, value
            return value.get('data')

    def upload(name, data):
        project = request('/projects', {'name': 'ECLIPSE-P02-BHP-' + name + '-' + out.name, 'description': 'BASE_WATER derived reference pressure and ZIP replay verification.'})
        boundary = 'grdp-' + uuid.uuid4().hex
        body = ('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="' + name + '"\r\nContent-Type: application/octet-stream\r\n\r\n').encode() + data + ('\r\n--' + boundary + '--\r\n').encode()
        request('/projects/' + str(project['id']) + '/models', body, 'multipart/form-data; boundary=' + boundary)
        for _ in range(90):
            detail = request('/projects/' + str(project['id']))
            versions = [v for m in detail['models'] for v in m['versions']]
            if versions and versions[0]['status'] == 'READY':
                save('version-' + str(versions[0]['id']) + '.json', versions[0])
                return versions[0]
            assert not versions or versions[0]['status'] not in ('INVALID', 'FAILED'), versions
            time.sleep(1)
        raise AssertionError('model validation timed out')

    def run(version, parameters):
        accepted = request('/model-versions/' + str(version['id']) + '/runs', {'runType': 'eclipse', 'study': None, 'parameters': parameters}, expected=201)
        for _ in range(300):
            current = request('/runs/' + str(accepted['id']))
            if current['status'] in ('SUCCEEDED', 'PARTIAL_SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT'):
                save('run-' + str(current['id']) + '.json', current)
                assert current['status'] == 'SUCCEEDED' and current['resultContract'] == 'VALID_FULL', (current['status'], current.get('error'))
                assert all(current['result']['eclEnd'][k] == 0 for k in ('problems', 'errors', 'bugs'))
                assert all(current['cleanup'][k] for k in ('processTreeExitConfirmed', 'inputDeleted', 'workDirectoryDeleted'))
                return current
            time.sleep(1)
        raise AssertionError('calculation timed out')

    def points(current, well, keyword):
        s, = [s for s in current['result']['summary']['series'] if s['objectName'] == well and s['keyword'] == keyword]
        assert s['unit'].strip() and s['points']
        d = {p['timeDays']: p['value'] for p in s['points']}
        assert len(d) == len(s['points']) and all(math.isfinite(t) and math.isfinite(v) for t, v in d.items())
        return s['unit'], d

    def expanded(values):
        result = []
        for v in values:
            repeat = re.fullmatch(r'(\d+)\*(.*)', v) if result else None
            result += [repeat[2]] * int(repeat[1]) if repeat else [v]
        return result

    try:
        version = upload(source.name, original)
        baseline = run(version, None)
        report['baselineRunId'] = baseline['id']
        production_version, production_baseline = version, baseline
        for well, keyword, target, rate in [('P1', 'WCONPROD', 3500, 'WOPR'), ('I1', 'WCONINJE', args.injection_target, 'WWIR')]:
            deck_source, deck_bytes = source, original
            if keyword == 'WCONINJE':
                deck_source, deck_bytes = injection_source, injection_original
                version = upload(deck_source.name, deck_bytes)
                baseline = run(version, None)
            record, = [r for r in version['inspection']['scheduleMetadata']['records'] if r['keyword'] == keyword and r['values'][0] == well]
            v = expanded(record['values'])
            p = {'schemaVersion': 'eclipse-pressure-control-parameters/1', 'baselineRunId': baseline['id'], 'well': well, 'phase': 'INITIAL',
                'sourceFile': record['sourceFile'], 'lineNumber': record['lineNumber'], 'keyword': keyword, 'originalControlMode': v[2 if keyword == 'WCONPROD' else 3],
                'originalBhp': float(v[8 if keyword == 'WCONPROD' else 6]), 'targetBhp': target, 'unit': 'psia', 'injectionType': 'NONE' if keyword == 'WCONPROD' else v[1]}
            save(well + '-request.json', p)
            path = '/model-versions/' + str(version['id']) + '/runs'
            before = request(path)
            for bad in [{**p, 'originalBhp': p['originalBhp'] - 1}, {**p, 'unit': 'barsa'}, {**p, 'injectionType': 'GAS' if keyword == 'WCONINJE' else 'WATER'}]:
                request(path, {'runType': 'eclipse', 'study': None, 'parameters': bad}, expected=400)
            assert request(path) == before, 'invalid request created a run'
            derived = run(version, p)
            item = {'well': well, 'baselineRunId': baseline['id'], 'model': deck_source.name, 'derivedRunId': derived['id'], 'targetBhp': target, 'comparison': {}}
            report['scenarios'].append(item)
            for k in ('WBHP', rate):
                unit, old = points(baseline, well, k)
                other_unit, new = points(derived, well, k)
                assert unit == other_unit
                common = sorted(t for t in new if t > 0 and t in old)
                assert common
                changed = [t for t in common if not math.isclose(new[t], old[t], rel_tol=1e-8, abs_tol=1e-6)]
                item['comparison'][k] = {'unit': unit, 'alignedPoints': len(common), 'changedPoints': len(changed), 'first': {'timeDays': common[0], 'baseline': old[common[0]], 'current': new[common[0]]}}
                assert changed, ('no business difference', well, k, item)
                if k == 'WBHP':
                    assert math.isclose(new[common[0]], target, abs_tol=0.1), ('other constraint active', item)
                elif keyword == 'WCONPROD':
                    assert new[common[0]] < old[common[0]], ('increasing production BHP did not reduce oil rate', item)
                else:
                    assert new[common[0]] < old[common[0]], ('decreasing injection pressure did not reduce injection', item)
            artifact, = [a for a in derived['artifacts'] if a['name'] == 'eclipse-schedule-applied-package.zip']
            with urllib.request.urlopen(args.api.rstrip('/') + '/runs/' + str(derived['id']) + '/artifacts/' + str(artifact['id']) + '/download', timeout=30) as response:
                package = response.read()
            assert len(package) == artifact['sizeBytes'] and hashlib.sha256(package).hexdigest() == artifact['sha256'].lower()
            (out / (well + '-applied.zip')).write_bytes(package)
            with zipfile.ZipFile(io.BytesIO(package)) as z:
                assert z.namelist() == [deck_source.name]
                changed_deck = z.read(deck_source.name)
            # Independent edits against the selected reference, preserving constraints and whitespace.
            if keyword == 'WCONPROD':
                expected, count = re.subn(rb"('P1'\s+'OPEN'\s+)'BHP'(\s+5\*\s+)3000(?=\s)", lambda m: m[1] + b"'BHP'" + m[2] + b'3500', deck_bytes)
            else:
                expected, count = re.subn(rb"('I1'\s+WATER\s+OPEN\s+)BHP(\s+1\*\s+1\*\s+)5000(?=/)", lambda m: m[1] + b"'BHP'" + m[2] + format(target, '.17g').encode(), deck_bytes)
            assert count == 1 and changed_deck == expected, 'unintended control, rate, constraint or byte changes'
            replay = run(upload(well + '-applied.zip', package), None)
            # Retain ordered identities and duplicates; never collapse series into an identity map.
            a, b = derived['result']['summary']['series'], replay['result']['summary']['series']
            assert a == b, 'reuploaded deck gives different summary series'
            item.update(replayRunId=replay['id'], reimportNumericPaths=sum(2 * len(s['points']) for s in a), reimportMaxDifference=0, inputEditVerified=True)
            print(json.dumps(item), flush=True)
        # A higher pressure target is limited by the preserved RESV=1000 cap.
        injection_record, = [r for r in production_version['inspection']['scheduleMetadata']['records'] if r['keyword'] == 'WCONINJE']
        constrained_parameters = {**p, 'baselineRunId': production_baseline['id'], 'sourceFile': source.name, 'lineNumber': injection_record['lineNumber'],
            'originalControlMode': 'RESV', 'originalBhp': 6000, 'targetBhp': 5000}
        constrained = run(production_version, constrained_parameters)
        constraint_evidence = {'runId': constrained['id'], 'targetBhp': 5000, 'actual': {}}
        for k in ('WBHP', 'WWIR'):
            unit, old = points(production_baseline, 'I1', k)
            other_unit, new = points(constrained, 'I1', k)
            assert unit == other_unit and old == new, ('preserved rate cap did not constrain BHP', k)
            t = min(t for t in new if t > 0)
            constraint_evidence['actual'][k] = {'timeDays': t, 'value': new[t], 'unit': unit}
        assert constraint_evidence['actual']['WBHP']['value'] < 5000
        artifact, = [a for a in constrained['artifacts'] if a['name'] == 'eclipse-schedule-applied-package.zip']
        with urllib.request.urlopen(args.api.rstrip('/') + '/runs/' + str(constrained['id']) + '/artifacts/' + str(artifact['id']) + '/download', timeout=30) as response:
            package = response.read()
        assert len(package) == artifact['sizeBytes'] and hashlib.sha256(package).hexdigest() == artifact['sha256'].lower()
        (out / 'I1-constrained-applied.zip').write_bytes(package)
        expected, count = re.subn(rb"('I1'\s+WATER\s+OPEN\s+)RESV(\s+1\*\s+1000\s+)6000(?=/)", lambda m: m[1] + b"'BHP'" + m[2] + b'5000', original)
        with zipfile.ZipFile(io.BytesIO(package)) as z:
            assert count == 1 and z.namelist() == [source.name] and z.read(source.name) == expected
        replay = run(upload('I1-constrained-applied.zip', package), None)
        assert replay['result']['summary']['series'] == constrained['result']['summary']['series']
        constraint_evidence.update(replayRunId=replay['id'], reimportNumericPaths=sum(2 * len(s['points']) for s in replay['result']['summary']['series']), reimportMaxDifference=0, inputEditVerified=True)
        report['preservedRateConstraint'] = constraint_evidence
        assert source.read_bytes() == original and injection_source.read_bytes() == injection_original
        report.update(apiVerified=True, sourceUnchanged=True, rejectedRequestsCreatedRun=False)
    except Exception as error:
        report['error'] = str(error)
        raise
    finally:
        save('acceptance-summary.json', report)
    print(json.dumps(report), flush=True)


if __name__ == '__main__':
    main()
