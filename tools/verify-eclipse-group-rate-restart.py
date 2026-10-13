"""After a controlled main-service restart, recalculate both actually imported rate ZIPs."""
import argparse
import hashlib
import json
from pathlib import Path
import time
import urllib.request

p = argparse.ArgumentParser()
p.add_argument('--grat-evidence', required=True)
p.add_argument('--lrat-evidence', required=True)
p.add_argument('--previous-generation', required=True)
p.add_argument('--evidence', required=True)
args = p.parse_args()
repo = Path(__file__).resolve().parent.parent
out = Path(args.evidence).resolve()
assert not out.is_relative_to(repo) and not out.is_relative_to(Path('C:/GRDP-Data'))
out.mkdir(parents=True, exist_ok=False)
api = 'http://127.0.0.1:5173/api/software-integration'

def save(name, value):
    with (out/name).open('x', encoding='utf-8') as f:
        json.dump(value, f, indent=2, ensure_ascii=False)

def request(path, body=None, expected=200):
    req = urllib.request.Request(api+path, data=None if body is None else json.dumps(body).encode(),
                                 headers={} if body is None else {'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=30) as response:
        value = json.load(response)
        assert response.status == expected and value['code'] == expected
        return value['data']

with urllib.request.urlopen('http://127.0.0.1:5150/api/health', timeout=20) as response:
    health = json.load(response)
assert health['status'] == 'UP' and health['idle'] is True and health['activeRunId'] is None
assert health['generationId'] != args.previous_generation
report = {'postRestartVerified': False, 'manualUiVerified': False, 'generationId': health['generationId'], 'modes': {}}
save('initial-health.json', health)
for mode, folder in [('GRAT', args.grat_evidence), ('LRAT', args.lrat_evidence)]:
    parent = Path(folder)
    prior = json.loads((parent/'acceptance-summary.json').read_text(encoding='utf-8'))
    assert prior['apiVerified'] is True and prior['mode'] == mode
    original = request(f"/runs/{prior['runIds'][1]}")
    imported = request(f"/runs/{prior['runIds'][2]}")
    assert original['status'] == imported['status'] == 'SUCCEEDED'
    assert original['resultContract'] == imported['resultContract'] == 'VALID_FULL' and imported['parameters'] is None
    artifact, = [a for a in original['artifacts'] if a['name'] == 'eclipse-schedule-applied-package.zip']
    with urllib.request.urlopen(f"{api}/runs/{original['id']}/artifacts/{artifact['id']}/download", timeout=30) as response:
        payload = response.read()
    assert payload == (parent/'applied.zip').read_bytes()
    assert len(payload) == artifact['sizeBytes'] and hashlib.sha256(payload).hexdigest() == artifact['sha256'].lower()
    with (out/f'{mode}-applied.zip').open('xb') as f:
        f.write(payload)
    created = request(f"/model-versions/{imported['modelVersionId']}/runs", {'runType': 'eclipse', 'study': None, 'parameters': None}, expected=201)
    save(f'{mode}-created.json', created)
    for _ in range(300):
        run = request(f"/runs/{created['id']}")
        if run['status'] in ['SUCCEEDED', 'FAILED', 'PARTIAL_SUCCEEDED', 'CANCELLED', 'TIMED_OUT']:
            break
        time.sleep(1)
    else:
        raise AssertionError('restart replay timeout')
    save(f"run-{run['id']}.json", run)
    assert run['status'] == 'SUCCEEDED' and run['resultContract'] == 'VALID_FULL'
    assert run['modelVersionId'] == imported['modelVersionId'] and run['parameters'] is None
    assert all(run['cleanup'][k] for k in ['processTreeExitConfirmed', 'inputDeleted', 'workDirectoryDeleted'])
    assert all(run['result']['eclEnd'][k] == 0 for k in ['problems', 'errors', 'bugs'])
    expected, actual = original['result']['summary']['series'], run['result']['summary']['series']
    assert expected and actual == expected, 'full ordered identity/time/value replay differs'
    assert (repo/f'test-data/ECLIPSE_GROUP_{mode}.DATA').is_file()
    assert hashlib.sha256((repo/f'test-data/ECLIPSE_GROUP_{mode}.DATA').read_bytes()).hexdigest() == prior['sourceSha256']
    report['modes'][mode] = {'runId': run['id'], 'modelVersionId': run['modelVersionId'], 'reimportNumericPaths': sum(2*len(s['points']) for s in actual),
                             'reimportMaxDifference': 0, 'actualDownloadVerified': True, 'sourceUnchanged': True,
                             'eclEnd': run['result']['eclEnd'], 'cleanup': run['cleanup']}
report['postRestartVerified'] = True
save('restart-replay.json', report)
print(json.dumps(report))
