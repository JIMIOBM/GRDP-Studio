"""Recheck source artifacts and both forecast/portable runs after the owned main restart."""
import argparse
import hashlib
import json
from pathlib import Path
import time
import urllib.request


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
    parent = Path(args.platform_evidence)
    prior = json.loads((parent / 'acceptance-summary.json').read_text(encoding='utf-8'))
    assert prior['apiVerified'] is True
    api = 'http://127.0.0.1:5173/api/software-integration'

    def save(name, value):
        (out / name).write_text(json.dumps(value, indent=2, ensure_ascii=False), encoding='utf-8')

    def request(path, body=None, expected=200):
        req = urllib.request.Request(api + path, data=None if body is None else json.dumps(body).encode(),
                                     headers={} if body is None else {'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=60) as response:
            value = json.load(response)
            assert response.status == expected and value['code'] == expected
            return value['data']

    def download(run, artifact_id, filename, expected_file):
        artifact, = [a for a in run['artifacts'] if a['id'] == artifact_id]
        with urllib.request.urlopen(f"{api}/runs/{run['id']}/artifacts/{artifact_id}/download", timeout=60) as response:
            payload = response.read()
        assert payload == expected_file.read_bytes()
        assert len(payload) == artifact['sizeBytes'] and hashlib.sha256(payload).hexdigest() == artifact['sha256']
        (out / filename).write_bytes(payload)

    with urllib.request.urlopen('http://127.0.0.1:5150/api/health', timeout=20) as response:
        health = json.load(response)
    assert health['status'] == 'UP' and health['idle'] is True and health['activeRunId'] is None
    assert health['generationId'] != args.previous_generation
    save('initial-health.json', health)
    report = {'postRestartVerified': False, 'manualUiVerified': False, 'generationId': health['generationId'], 'scenarios': {}}
    try:
        history = request(f"/runs/{prior['historyRunId']}")
        download(history, prior['catalogArtifactId'], 'source-catalog.json', parent / 'source-catalog.json')
        state, = [a for a in history['artifacts'] if a['name'] == prior['parameters']['restartArtifactName']]
        download(history, state['id'], 'history.FUNRST', parent / 'history.FUNRST')
        assert history['result']['summary']['series'] == json.loads((parent / f"run-{history['id']}.json").read_text(encoding='utf-8'))['result']['summary']['series']
        cases = []
        for descriptor in prior['downloads']:
            reference = request(f"/runs/{descriptor['runId']}")
            imported = request(f"/runs/{descriptor['replayRunId']}")
            download(reference, descriptor['artifactId'], descriptor['file'], parent / descriptor['file'])
            assert imported['parameters'] is None and imported['result']['summary']['series'] == reference['result']['summary']['series']
            cases.extend([(descriptor['file'] + '-forecast', reference['modelVersionId'], reference['parameters'], reference),
                          (descriptor['file'] + '-portable', imported['modelVersionId'], None, imported)])
        for label, version, parameters, reference in cases:
            created = request(f'/model-versions/{version}/runs', {'runType': 'eclipse', 'study': None, 'parameters': parameters}, expected=201)
            save(label + '-created.json', created)
            for _ in range(300):
                run = request(f"/runs/{created['id']}")
                if run['status'] in ['SUCCEEDED', 'FAILED', 'PARTIAL_SUCCEEDED', 'CANCELLED', 'TIMED_OUT', 'WORKER_LOST']:
                    break
                time.sleep(1)
            else:
                raise AssertionError('restart replay timeout')
            save(f"run-{run['id']}.json", run)
            assert run['status'] == reference['status'] == 'SUCCEEDED' and run['resultContract'] == reference['resultContract'] == 'VALID_FULL'
            assert run['modelVersionId'] == version and run['parameters'] == parameters
            assert run['result']['eclEnd'] == reference['result']['eclEnd']
            assert all(run['cleanup'][key] for key in ['processTreeExitConfirmed', 'inputDeleted', 'workDirectoryDeleted'])
            expected, actual = reference['result']['summary']['series'], run['result']['summary']['series']
            assert expected and actual == expected, 'Full ordered identity/time/value replay differs'
            report['scenarios'][label] = {'runId': run['id'], 'modelVersionId': version, 'numericPaths': sum(2 * len(s['points']) for s in actual),
                                         'maxDifference': 0, 'eclEnd': run['result']['eclEnd'], 'cleanup': run['cleanup']}
        assert hashlib.sha256((repo / 'test-data/official-eclipse-ex5-history-forecasts.zip').read_bytes()).hexdigest() == prior['sourceSha256']
        report.update(postRestartVerified=True, sourceArtifactsUnchanged=True)
        print(json.dumps(report))
    finally:
        save('restart-replay.json', report)


if __name__ == '__main__':
    main()
