"""Verify real 5173 history, both forecasts, source rejection and downloaded portable replays."""
import argparse
import copy
import hashlib
import io
import json
from pathlib import Path
import time
import urllib.error
import urllib.request
import uuid
import zipfile
import importlib.util

native_spec = importlib.util.spec_from_file_location('restart_native_proof', Path(__file__).with_name('inspect-eclipse-restart-native.py'))
native_reader = importlib.util.module_from_spec(native_spec)
native_spec.loader.exec_module(native_reader)
selected_report = native_reader.selected_report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', required=True)
    parser.add_argument('--native-directory', default='C:/GRDP-TestOutput/ECLIPSE-P04-RESTART-EX5-probe-a4')
    parser.add_argument('--resume', action='store_true')
    args = parser.parse_args()
    repo = Path(__file__).resolve().parent.parent
    out = Path(args.evidence).resolve()
    assert not out.is_relative_to(repo) and not out.is_relative_to(Path('C:/GRDP-Data'))
    if args.resume:
        assert out.is_dir()
    else:
        out.mkdir(parents=True, exist_ok=False)
    package_file = repo / 'test-data/official-eclipse-ex5-history-forecasts.zip'
    original = package_file.read_bytes()
    native_root = Path(args.native_directory)
    native = json.loads((native_root / 'native-acceptance.json').read_text(encoding='utf-8'))
    assert native['nativeVerified']
    with zipfile.ZipFile(io.BytesIO(original)) as z:
        assert z.read('EX5.DATA') == (native_root / 'history/EX5.DATA').read_bytes()
        for name in ['FOREA', 'FOREB']:
            assert z.read(name + '.DATA') == (native_root / name / (name + '.DATA')).read_bytes()
    api = 'http://127.0.0.1:5173/api/software-integration'
    proof = {'apiVerified': False, 'manualUiVerified': False, 'sourceSha256': hashlib.sha256(original).hexdigest()}

    def save(name, value):
        (out / name).write_text(json.dumps(value, indent=2, ensure_ascii=False), encoding='utf-8')

    def request(path, body=None, content_type='application/json', expected=200):
        payload = body if isinstance(body, bytes) else json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(api + path, data=payload, headers={'Content-Type': content_type} if payload is not None else {})
        try:
            response = urllib.request.urlopen(req, timeout=60)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read()
            assert response.status == expected, (path, response.status, raw[:1200])
            value = json.loads(raw)
            assert value['code'] == expected, value
            return value.get('data')

    def upload(name, content, main_file):
        if args.resume:
            for file in out.glob('version-*.json'):
                value = json.loads(file.read_text(encoding='utf-8'))
                if value['originalName'] == name:
                    return value
        project = request('/projects', {'name': out.name + '-' + name, 'description': 'Actual history and two portable restart forecasts.'})
        save('project-' + name + '.json', project)
        boundary = 'grdp-' + uuid.uuid4().hex
        form = (f'--{boundary}\r\nContent-Disposition: form-data; name="mainFile"\r\n\r\n{main_file}\r\n'
                f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\nContent-Type: application/zip\r\n\r\n').encode()
        form += content + (f'\r\n--{boundary}--\r\n').encode()
        request(f"/projects/{project['id']}/models", form, 'multipart/form-data; boundary=' + boundary)
        for _ in range(120):
            detail = request(f"/projects/{project['id']}")
            versions = [v for m in detail['models'] for v in m['versions']]
            if versions and versions[0]['status'] == 'READY':
                save(f"version-{versions[0]['id']}.json", versions[0])
                return versions[0]
            assert not versions or versions[0]['status'] not in ('INVALID', 'FAILED'), versions
            time.sleep(1)
        raise AssertionError('inspection timeout')

    def run(version, parameters):
        created = None
        if args.resume:
            for file in out.glob('run-*.json'):
                previous = json.loads(file.read_text(encoding='utf-8'))
                if previous['modelVersionId'] == version['id'] and previous.get('parameters') == parameters:
                    created = request(f"/runs/{previous['id']}")
                    break
        if created is None:
            created = request(f"/model-versions/{version['id']}/runs", {'runType': 'eclipse', 'study': None, 'parameters': parameters}, expected=201)
            save(f"run-{created['id']}.json", created)
        for _ in range(300):
            item = request(f"/runs/{created['id']}")
            if item['status'] in ['SUCCEEDED', 'FAILED', 'PARTIAL_SUCCEEDED', 'CANCELLED', 'TIMED_OUT', 'WORKER_LOST']:
                save(f"run-{item['id']}.json", item)
                assert item['status'] == 'SUCCEEDED' and item['resultContract'] == 'VALID_FULL', item.get('error')
                assert all(item['result']['eclEnd'][key] == 0 for key in ['problems', 'errors', 'bugs'])
                assert all(item['cleanup'][key] for key in ['processTreeExitConfirmed', 'inputDeleted', 'workDirectoryDeleted'])
                return item
            time.sleep(1)
        raise AssertionError('run timeout')

    def download(item, name, filename):
        artifact, = [a for a in item['artifacts'] if a['name'] == name]
        with urllib.request.urlopen(f"{api}/runs/{item['id']}/artifacts/{artifact['id']}/download", timeout=60) as response:
            content = response.read()
        assert len(content) == artifact['sizeBytes'] and hashlib.sha256(content).hexdigest() == artifact['sha256']
        (out / filename).write_bytes(content)
        return content, artifact

    def curves(item):
        return item['result']['summary']['series']

    try:
        version = upload(package_file.name, original, 'EX5.DATA')
        assert version['inspection']['schemaVersion'] == 'eclipse-data-inspection/4'
        history = run(version, None)
        catalog_bytes, catalog_artifact = download(history, 'eclipse-restart-catalog.json', 'source-catalog.json')
        source = json.loads(catalog_bytes)
        catalog = source['catalog']
        assert source['schemaVersion'] == 'eclipse-restart-source/1' and len(catalog['reports']) == 81 and len(source['forecastInputs']) == 2
        report, = [r for r in catalog['reports'] if r['reportNumber'] == 80]
        assert (report['date'], report['timeDays'], catalog['startDate']) == ('1975-12-31', 2190, '1970-01-01')
        full_state, state_artifact = download(history, catalog['artifactName'], 'history.FUNRST')
        selected, reports, source_sha = selected_report(out / 'history.FUNRST', 80)
        assert reports == list(range(81)) and source_sha == catalog['artifactSha256'] == state_artifact['sha256']
        assert len(selected) == report['sizeBytes'] and hashlib.sha256(selected).hexdigest() == report['sha256']
        parameters = {'schemaVersion': 'eclipse-history-forecast-parameters/2', 'historyRunId': history['id'], 'forecastDataFile': 'FOREA.DATA',
                      'restartArtifactName': catalog['artifactName'], 'restartArtifactSha256': catalog['artifactSha256'], 'restartReport': 80,
                      'restartTimeDays': 2190, 'restartDate': '1975-12-31', 'physicsSha256': source['physicsSha256']}
        before = request(f"/model-versions/{version['id']}/runs")
        faults = [{'restartReport': 79}, {'restartReport': 81}, {'restartReport': 0}, {'restartTimeDays': 2191}, {'restartTimeDays': '2190'},
                  {'restartDate': '1975-12-30'}, {'restartDate': '1975-02-31'}, {'physicsSha256': 'b' * 64}, {'restartArtifactSha256': 'b' * 64},
                  {'restartArtifactName': 'eclipse-output-OTHER.FUNRST'}, {'forecastDataFile': 'EX5.DATA'}, {'forecastDataFile': '../FOREA.DATA'},
                  {'extra': True}, {'historyRunId': 397}]
        for fault in faults:
            request(f"/model-versions/{version['id']}/runs", {'runType': 'eclipse', 'study': None, 'parameters': {**parameters, **fault}}, expected=409 if 'historyRunId' in fault or 'restartArtifactName' in fault else 400)
        assert before == request(f"/model-versions/{version['id']}/runs")
        first = run(version, parameters)
        second = run(version, {**parameters, 'forecastDataFile': 'FOREB.DATA'})
        numerical_paths = 0
        for label, item in [('history', history), ('FOREA', first), ('FOREB', second)]:
            expected = native['history']['summary']['series'] if label == 'history' else native['forecasts'][label]['summary']['series']
            assert curves(item) == expected, (label, 'full ordered native curves differ')
            numerical_paths += sum(2 * len(s['points']) for s in expected)
        replays, portable_paths, downloads = [], 0, []
        for label, item in [('FOREA', first), ('FOREB', second)]:
            content, descriptor = download(item, 'eclipse-forecast-applied-package.zip', label + '-portable.zip')
            with zipfile.ZipFile(io.BytesIO(content)) as z, zipfile.ZipFile(io.BytesIO(original)) as original_zip:
                assert set(z.namelist()) == {label + '.DATA', label + '.SCH', 'EX5.GRDECL', 'EX5.FUNRST', 'eclipse-restart-binding.json'}
                assert z.read('EX5.FUNRST') == selected
                for name in [label + '.DATA', label + '.SCH', 'EX5.GRDECL']:
                    assert z.read(name) == original_zip.read(name)
                binding = json.loads(z.read('eclipse-restart-binding.json'))
                assert binding['physicsSha256'] == source['physicsSha256'] and len(binding['catalog']['reports']) == 1
                assert binding['catalog']['artifactSha256'] == report['sha256'] and binding['catalog']['reports'][0]['byteOffset'] == 0
            replay = run(upload(label + '-portable.zip', content, label + '.DATA'), None)
            assert curves(replay) == curves(item), 'portable reupload differs'
            portable_paths += sum(2 * len(s['points']) for s in curves(replay))
            replays.append(replay)
            downloads.append({'runId': item['id'], 'replayRunId': replay['id'], 'artifactId': descriptor['id'], 'sha256': descriptor['sha256'], 'file': label + '-portable.zip'})
        assert package_file.read_bytes() == original
        proof.update(apiVerified=True, historyRunId=history['id'], firstRunId=first['id'], secondRunId=second['id'],
                     runIds=[history['id'], first['id'], second['id'], *[r['id'] for r in replays]], sourceUnchanged=True,
                     parameters=parameters, rejectedRequests=len(faults), rejectedRequestCreatedRun=False, catalogArtifactId=catalog_artifact['id'],
                     selectedReportSha256=report['sha256'], nativeNumericPaths=numerical_paths, nativeMaxDifference=0,
                     reimportNumericPaths=portable_paths, reimportMaxDifference=0, downloads=downloads,
                     diagnostics={str(r['id']): r['result']['eclEnd'] for r in [history, first, second, *replays]})
        print(json.dumps(proof))
    except Exception as error:
        proof['error'] = str(error)
        raise
    finally:
        save('acceptance-summary.json', proof)


if __name__ == '__main__':
    main()
