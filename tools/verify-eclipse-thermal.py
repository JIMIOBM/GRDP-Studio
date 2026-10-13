"""Real 5173 steam-drive runs and full independent native comparison, including input ZIP replay."""
import argparse
import hashlib
import io
import json
import time
import urllib.error
import urllib.request
import uuid
import zipfile
from pathlib import Path
from eclipse_thermal_evidence import compare_physics, verify_own_binary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', required=True)
    parser.add_argument('--native-directory', default='C:/GRDP-TestOutput/ECLIPSE-P05-THERMAL-probe-a2')
    parser.add_argument('--resume', action='store_true')
    args = parser.parse_args()
    repo = Path(__file__).resolve().parent.parent
    out = Path(args.evidence).resolve()
    assert not out.is_relative_to(repo) and not out.is_relative_to(Path('C:/GRDP-Data'))
    out.mkdir(parents=True, exist_ok=args.resume)
    native = json.loads((Path(args.native_directory) / 'native-acceptance.json').read_text())
    assert native['nativeVerified'] and native['workerReaderVerified']
    assert native['seriesCount'] == 343 and native['pointsPerSeries'] == 124
    original = (repo / 'test-data/official-eclipse-therm3-temperature.zip').read_bytes()
    with zipfile.ZipFile(io.BytesIO(original)) as z:
        source = z.read('THERM3.DATA')
    assert hashlib.sha256(source).hexdigest() == 'babef9f21032db39dda8ca424a5d8d9fdbf1bfaf4d64fda650372f1820a08de9'
    metadata = dict(solver=300, modelType='THERMAL', componentCount=3,
                    componentNames=['C1', 'C2', 'HEAVY'], fluidModel='LIVEOIL', summaryProgramIdentifier=500)
    api = 'http://127.0.0.1:5173/api/software-integration'
    proof = {'apiVerified': False, 'manualUiVerified': False}

    def save(name, value):
        (out / (name + '.json')).write_text(json.dumps(value, indent=2, ensure_ascii=False), encoding='utf-8')

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
            assert value['code'] == expected
            return value.get('data')

    def upload(label, content):
        saved = out / ('version-' + label + '.json')
        if args.resume and saved.exists():
            return json.loads(saved.read_text(encoding='utf-8'))
        project = request('/projects', {'name': out.name + '-' + label, 'description': 'Official THERM3 steam drive; only unified and requested temperature output added.'})
        save('project-' + label, project)
        boundary = 'grdp-' + uuid.uuid4().hex
        data = (f'--{boundary}\r\nContent-Disposition: form-data; name="mainFile"\r\n\r\nTHERM3.DATA\r\n'
                f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{label}.zip"\r\nContent-Type: application/zip\r\n\r\n').encode()
        data += content + f'\r\n--{boundary}--\r\n'.encode()
        request(f"/projects/{project['id']}/models", data, 'multipart/form-data; boundary=' + boundary)
        for _ in range(120):
            detail = request(f"/projects/{project['id']}")
            versions = [v for m in detail['models'] for v in m['versions']]
            if versions and versions[0]['status'] == 'READY':
                version = versions[0]
                inspection = version['inspection']
                assert version['modelKind'] == 'eclipse_300' and inspection['schemaVersion'] == 'eclipse-data-inspection/15'
                assert inspection['simulation'] == metadata and inspection['unitSystem'] == 'FIELD'
                assert inspection['dimensions'] == dict(nx=9, ny=5, nz=4)
                assert inspection['wellNames'] == ['I', 'PNEAR', 'PFAR']
                save('version-' + label, version)
                return version
            assert not versions or versions[0]['status'] not in ['INVALID', 'FAILED'], versions
            time.sleep(1)
        raise AssertionError('Thermal inspection timeout')

    def run(version, label):
        saved = out / ('run-' + label + '.json')
        created = json.loads(saved.read_text(encoding='utf-8')) if args.resume and saved.exists() else request(
            f"/model-versions/{version['id']}/runs", {'study': None, 'runType': 'eclipse', 'parameters': None}, expected=201)
        save('run-' + label, created)
        for _ in range(300):
            item = request(f"/runs/{created['id']}")
            if item['status'] in ['SUCCEEDED', 'PARTIAL_SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'WORKER_LOST']:
                save('run-' + label, item)
                assert item['status'] == 'SUCCEEDED' and item['resultContract'] == 'VALID_FULL', item.get('error')
                result = item['result']
                assert result['schemaVersion'] == 'eclipse-summary-result/3' and result['modelKind'] == 'eclipse_300'
                assert result['simulation'] == metadata == version['inspection']['simulation']
                assert compare_physics(result['summary'], native['summary']) == 84072
                assert verify_own_binary(api, item, out) == native['numericPaths']
                temperatures = [s for s in result['summary']['series'] if s['keyword'] in ['WTEMP', 'BTEMP']]
                assert len(temperatures) == 5 and all(s['unit'] == 'DEG F' for s in temperatures)
                assert all(s['points'][-1]['timeDays'] == 3650 for s in temperatures)
                assert all(result['eclEnd'][k] == 0 for k in ['problems', 'errors', 'bugs'])
                assert result['eclEnd']['warnings'] == 2 and result['eclEnd']['comments'] == 1
                assert all(item['cleanup'][k] for k in ['processTreeExitConfirmed', 'inputDeleted', 'workDirectoryDeleted'])
                return item
            time.sleep(1)
        raise AssertionError('Native thermal run timeout')

    def download(item, name):
        descriptor, = [a for a in item['artifacts'] if a['name'] == name]
        with urllib.request.urlopen(f"{api}/runs/{item['id']}/artifacts/{descriptor['id']}/download", timeout=60) as r:
            content = r.read()
        assert len(content) == descriptor['sizeBytes'] and hashlib.sha256(content).hexdigest() == descriptor['sha256']
        (out / name).write_bytes(content)
        return content, descriptor

    try:
        caps = request('/capabilities'); save('capabilities', caps)
        assert caps['eclipse300']['status'] == 'AVAILABLE' and caps['eclipse300']['version'] == '2024.1'
        version = upload('official', original)
        before = request(f"/model-versions/{version['id']}/runs")
        faults = [{}, {'schemaVersion': 'eclipse-schedule-parameters/1'},
                  {'schemaVersion': 'eclipse-history-forecast-parameters/2'}, {'solver': 100}]
        for parameters in faults:
            request(f"/model-versions/{version['id']}/runs", {'study': None, 'runType': 'eclipse', 'parameters': parameters}, expected=400)
        assert request(f"/model-versions/{version['id']}/runs") == before
        first = run(version, 'official-first')
        second = run(version, 'official-repeat')
        portable, descriptor = download(first, 'eclipse-thermal-input-package.zip')
        with zipfile.ZipFile(io.BytesIO(portable)) as z:
            assert z.namelist() == ['THERM3.DATA'] and z.read('THERM3.DATA') == source
        replay = run(upload('portable', portable), 'portable')
        assert (repo / 'test-data/official-eclipse-therm3-temperature.zip').read_bytes() == original
        proof.update(apiVerified=True, sourceUnchanged=True, runIds=[first['id'], second['id'], replay['id']],
                     versionIds=[version['id'], replay['modelVersionId']], rejectedRequests=len(faults), rejectedRequestCreatedRun=False,
                     seriesCount=native['seriesCount'], pointsPerSeries=native['pointsPerSeries'],
                     nativeNumericPaths=3 * native['numericPaths'], nativeMaxDifference=0,
                     crossRunPhysicalNumericPaths=3 * 84072, crossRunPhysicalMaxDifference=0,
                     crossRunTimingIndicators=['TCPU', 'ELAPSED', 'TCPUTS', 'TCPUDAY'], portableArtifact=descriptor,
                     diagnostics={str(r['id']): r['result']['eclEnd'] for r in [first, second, replay]})
        print(json.dumps(proof))
    except Exception as error:
        proof['error'] = str(error)
        raise
    finally:
        save('acceptance-summary', proof)


if __name__ == '__main__':
    main()
