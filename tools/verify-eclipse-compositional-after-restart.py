"""Verify saved complete E300 output and model bytes, then replay both versions after restart."""
import argparse
import hashlib
import json
import time
import urllib.request
from pathlib import Path
from eclipse_thermal_evidence import compare_physics, verify_own_binary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--platform-evidence', required=True)
    parser.add_argument('--previous-generation', required=True)
    parser.add_argument('--evidence', required=True)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parent.parent
    out = Path(args.evidence).resolve()
    assert not out.is_relative_to(repo) and not out.is_relative_to(Path('C:/GRDP-Data'))
    out.mkdir(parents=True, exist_ok=False)
    prior = Path(args.platform_evidence)
    receipt = json.loads((prior / 'acceptance-summary.json').read_text())
    assert receipt['apiVerified']
    api = 'http://127.0.0.1:5173/api/software-integration'
    proof = {'postRestartVerified': False, 'manualUiVerified': False, 'runIds': []}

    def save(name, value):
        (out / (name + '.json')).write_text(json.dumps(value, indent=2), encoding='utf-8')

    def request(path, body=None, expected=200):
        req = urllib.request.Request(api + path, data=json.dumps(body).encode() if body else None,
                                     headers={'Content-Type': 'application/json'} if body else {})
        with urllib.request.urlopen(req, timeout=60) as r:
            value = json.load(r)
            assert r.status == expected and value['code'] == expected
            return value['data']

    try:
        with urllib.request.urlopen('http://127.0.0.1:5150/api/health', timeout=20) as r:
            health = json.load(r)
        assert health['idle'] and health['generationId'] != args.previous_generation
        save('initial-health', health)
        proof['generationId'] = health['generationId']
        caps = request('/capabilities')
        assert caps['eclipse300']['status'] == 'AVAILABLE' and caps['eclipse100']['status'] == 'AVAILABLE'
        save('capabilities', caps)
        for label in ['official-first', 'official-repeat', 'portable']:
            before = json.loads((prior / ('run-' + label + '.json')).read_text(encoding='utf-8'))
            after = request('/runs/' + str(before['id']))
            assert before['result'] == after['result'] and before['artifacts'] == after['artifacts']
        before = json.loads((prior / 'run-official-first.json').read_text(encoding='utf-8'))
        descriptor = receipt['portableArtifact']
        with urllib.request.urlopen(f"{api}/runs/{before['id']}/artifacts/{descriptor['id']}/download", timeout=60) as r:
            content = r.read()
        assert content == (prior / descriptor['name']).read_bytes() and hashlib.sha256(content).hexdigest() == descriptor['sha256']
        (out / descriptor['name']).write_bytes(content)
        paths = 0
        for version in receipt['versionIds']:
            run = request(f'/model-versions/{version}/runs', {'runType': 'eclipse', 'study': None, 'parameters': None}, expected=201)
            save('run-' + str(run['id']), run)
            for _ in range(300):
                current = request('/runs/' + str(run['id']))
                if current['status'] in ['SUCCEEDED', 'PARTIAL_SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'WORKER_LOST']:
                    save('run-' + str(current['id']), current)
                    assert current['status'] == 'SUCCEEDED' and current['resultContract'] == 'VALID_FULL', current.get('error')
                    if before['result']['schemaVersion'] == 'eclipse-summary-result/3':
                        assert current['result']['schemaVersion'] == 'eclipse-summary-result/3'
                        physical = compare_physics(current['result']['summary'], before['result']['summary'])
                        proof['crossRunPhysicalNumericPaths'] = proof.get('crossRunPhysicalNumericPaths', 0) + physical
                        proof['crossRunTimingIndicators'] = ['TCPU', 'ELAPSED', 'TCPUTS', 'TCPUDAY']
                        verify_own_binary(api, current, out)
                    else:
                        assert current['result']['summary'] == before['result']['summary']
                    assert current['result']['simulation'] == before['result']['simulation']
                    assert all(current['result']['eclEnd'][k] == 0 for k in ['problems', 'errors', 'bugs'])
                    assert all(current['cleanup'][k] for k in ['inputDeleted', 'workDirectoryDeleted', 'processTreeExitConfirmed'])
                    paths += sum(2 * len(s['points']) for s in current['result']['summary']['series'])
                    proof['runIds'].append(current['id'])
                    break
                time.sleep(1)
            else:
                raise AssertionError('Post-restart E300 replay timeout')
        proof.update(postRestartVerified=True, savedResultsUnchanged=True, portableBytesUnchanged=True, numericPaths=paths, maxDifference=0)
        print(json.dumps(proof))
    except Exception as error:
        proof['error'] = str(error)
        raise
    finally:
        save('restart-replay', proof)


if __name__ == '__main__':
    main()
