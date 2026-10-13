"""Verify complete phased/initial ZIP and all source scenarios after an owned main restart."""
import argparse
import hashlib
import json
from pathlib import Path
import time
import urllib.request

p = argparse.ArgumentParser()
p.add_argument('--platform-evidence', required=True)
p.add_argument('--previous-generation', required=True)
p.add_argument('--evidence', required=True)
args = p.parse_args()
repo = Path(__file__).resolve().parent.parent
out = Path(args.evidence).resolve()
assert not out.is_relative_to(repo) and not out.is_relative_to(Path('C:/GRDP-Data'))
out.mkdir(parents=True, exist_ok=False)
parent = Path(args.platform_evidence)
prior = json.loads((parent/'acceptance-summary.json').read_text(encoding='utf-8'))
assert prior['apiVerified'] is True
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
save('initial-health.json', health)
report = {'postRestartVerified': False, 'manualUiVerified': False, 'generationId': health['generationId'], 'scenarios': {}}
cases = []
for download in prior['downloads']:
    reference = request(f"/runs/{download['runId']}")
    imported = request(f"/runs/{download['replayRunId']}")
    artifact, = [a for a in reference['artifacts'] if a['id'] == download['artifactId']]
    with urllib.request.urlopen(f"{api}/runs/{reference['id']}/artifacts/{artifact['id']}/download", timeout=30) as response:
        payload = response.read()
    assert payload == (parent/download['file']).read_bytes()
    assert len(payload) == artifact['sizeBytes'] and hashlib.sha256(payload).hexdigest() == artifact['sha256'].lower()
    (out/download['file']).write_bytes(payload)
    assert imported['parameters'] is None and imported['result']['summary']['series'] == reference['result']['summary']['series']
    cases.append((download['file'], imported['modelVersionId'], None, reference))
for label, identifier in [('positive', prior['positiveRunId']), ('limited', prior['limitedRunId']), ('initial', prior['initialRunId'])]:
    reference = request(f'/runs/{identifier}')
    cases.append((label, reference['modelVersionId'], reference['parameters'], reference))
for label, version, parameters, reference in cases:
    created = request(f'/model-versions/{version}/runs', {'runType': 'eclipse', 'study': None, 'parameters': parameters}, expected=201)
    save(label+'-created.json', created)
    for _ in range(300):
        run = request(f"/runs/{created['id']}")
        if run['status'] in ['SUCCEEDED', 'FAILED', 'PARTIAL_SUCCEEDED', 'CANCELLED', 'TIMED_OUT']:
            break
        time.sleep(1)
    else:
        raise AssertionError('restart replay timeout')
    save(f"run-{run['id']}.json", run)
    assert run['status'] == reference['status'] == 'SUCCEEDED' and run['resultContract'] == reference['resultContract'] == 'VALID_FULL'
    assert run['modelVersionId'] == version and run['parameters'] == parameters
    assert run['result']['eclEnd'] == reference['result']['eclEnd']
    assert all(run['cleanup'][k] for k in ['processTreeExitConfirmed', 'inputDeleted', 'workDirectoryDeleted'])
    expected, actual = reference['result']['summary']['series'], run['result']['summary']['series']
    assert expected and actual == expected, 'full ordered identity/time/value replay differs'
    report['scenarios'][label] = {'runId': run['id'], 'modelVersionId': version, 'reimportNumericPaths': sum(2*len(s['points']) for s in actual),
                                'reimportMaxDifference': 0, 'eclEnd': run['result']['eclEnd'], 'cleanup': run['cleanup']}
assert hashlib.sha256((repo/'test-data/ECLIPSE_CONTROL_PLAN.DATA').read_bytes()).hexdigest() == prior['sourceSha256']
report['postRestartVerified'] = True
save('restart-replay.json', report)
print(json.dumps(report))
