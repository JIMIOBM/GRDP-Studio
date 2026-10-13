"""Real unified-console acceptance transport, with saved resumable run identities."""
import hashlib
import json
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path


class ConsoleAcceptance:
    api = 'http://127.0.0.1:5173/api/software-integration'

    def __init__(self, evidence, resume=False):
        self.out = Path(evidence).resolve()
        repo = Path(__file__).resolve().parent.parent
        assert not self.out.is_relative_to(repo) and not self.out.is_relative_to(Path('C:/GRDP-Data'))
        self.out.mkdir(parents=True, exist_ok=resume)
        self.resume = resume

    def save(self, label, value):
        (self.out / (label + '.json')).write_text(json.dumps(value, indent=2, ensure_ascii=False), encoding='utf-8')

    def request(self, path, body=None, expected=200, content_type='application/json'):
        data = body if isinstance(body, bytes) else json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(self.api + path, data=data, headers={'Content-Type': content_type} if data is not None else {})
        try:
            response = urllib.request.urlopen(req, timeout=60)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read()
            assert response.status == expected, (path, response.status, raw[:1500])
            parsed = json.loads(raw)
            assert parsed['code'] == expected
            return parsed.get('data')

    def upload(self, label, content, filename, main_file=None):
        saved = self.out / ('version-' + label + '.json')
        if self.resume and saved.exists():
            return json.loads(saved.read_text(encoding='utf-8'))
        project = self.request('/projects', {'name': self.out.name + '-' + label, 'description': 'Real input editing acceptance; commercial solver and actual downloaded ZIP replay.'})
        self.save('project-' + label, project)
        boundary = 'grdp-' + uuid.uuid4().hex
        data = b''
        if main_file:
            data += f'--{boundary}\r\nContent-Disposition: form-data; name="mainFile"\r\n\r\n{main_file}\r\n'.encode()
        data += f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\nContent-Type: application/octet-stream\r\n\r\n'.encode()
        data += content + f'\r\n--{boundary}--\r\n'.encode()
        self.request(f"/projects/{project['id']}/models", data, content_type='multipart/form-data; boundary=' + boundary)
        for _ in range(120):
            detail = self.request(f"/projects/{project['id']}")
            versions = [v for m in detail['models'] for v in m['versions']]
            if versions and versions[0]['status'] == 'READY':
                self.save('version-' + label, versions[0]); return versions[0]
            assert not versions or versions[0]['status'] not in ['FAILED', 'INVALID'], versions
            time.sleep(1)
        raise AssertionError('Model inspection timeout')

    def run(self, version, label, parameters=None):
        saved = self.out / ('run-' + label + '.json')
        run = json.loads(saved.read_text(encoding='utf-8')) if self.resume and saved.exists() else self.request(
            f"/model-versions/{version['id']}/runs", {'runType': 'eclipse', 'study': None, 'parameters': parameters}, expected=201)
        assert run.get('parameters') == parameters
        self.save('run-' + label, run)
        for _ in range(300):
            current = self.request('/runs/' + str(run['id']))
            if current['status'] in ['SUCCEEDED', 'PARTIAL_SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'WORKER_LOST']:
                self.save('run-' + label, current)
                assert current['status'] == 'SUCCEEDED' and current['resultContract'] == 'VALID_FULL', current.get('error')
                assert all(current['result']['eclEnd'][k] == 0 for k in ['problems', 'errors', 'bugs'])
                assert all(current['cleanup'][k] for k in ['inputDeleted', 'workDirectoryDeleted', 'processTreeExitConfirmed'])
                return current
            time.sleep(1)
        raise AssertionError('Native solver run timeout')

    def download(self, run, name):
        artifact, = [a for a in run['artifacts'] if a['name'] == name]
        with urllib.request.urlopen(f"{self.api}/runs/{run['id']}/artifacts/{artifact['id']}/download", timeout=60) as response:
            content = response.read()
        assert len(content) == artifact['sizeBytes'] and hashlib.sha256(content).hexdigest() == artifact['sha256']
        (self.out / name).write_bytes(content)
        return content, artifact

    def reject_without_run(self, version, requests, expected=400):
        path = f"/model-versions/{version['id']}/runs"
        before = self.request(path)
        for parameters in requests:
            self.request(path, {'runType': 'eclipse', 'study': None, 'parameters': parameters}, expected=expected)
        assert self.request(path) == before, 'Rejected request created a Run'
