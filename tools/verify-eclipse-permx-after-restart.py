"""Restart replay: preserve actual PERMX input ZIP and results; recheck full INIT and Summary."""
import argparse
import importlib.util
import json
import urllib.request
from pathlib import Path
from eclipse_console_acceptance import ConsoleAcceptance


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--platform-evidence', required=True)
    parser.add_argument('--previous-generation', required=True)
    parser.add_argument('--evidence', required=True)
    args = parser.parse_args()
    console = ConsoleAcceptance(args.evidence)
    prior = Path(args.platform_evidence)
    receipt = json.loads((prior / 'acceptance-summary.json').read_text(encoding='utf-8'))
    assert receipt['apiVerified']
    spec = importlib.util.spec_from_file_location('independent_binary', Path(__file__).parent / 'inspect-eclipse-compositional-native.py')
    oracle = importlib.util.module_from_spec(spec); spec.loader.exec_module(oracle)
    proof = dict(postRestartVerified=False, manualUiVerified=False)
    try:
        with urllib.request.urlopen('http://127.0.0.1:5150/api/health', timeout=20) as response:
            health = json.load(response)
        assert health['idle'] and health['generationId'] != args.previous_generation
        old = {}
        for label in ['baseline', 'target', 'portable']:
            before = json.loads((prior / ('run-' + label + '.json')).read_text(encoding='utf-8'))
            after = console.request('/runs/' + str(before['id']))
            assert before['result'] == after['result'] and before['artifacts'] == after['artifacts']
            old[label] = before
        descriptor = receipt['portableArtifact']
        package, artifact = console.download(old['target'], descriptor['name'])
        assert artifact == descriptor and package == (prior / descriptor['name']).read_bytes()
        originals = json.loads((prior / 'version-original.json').read_text(encoding='utf-8'))
        portable = json.loads((prior / 'version-portable.json').read_text(encoding='utf-8'))
        runs = [console.run(originals, 'target', old['target']['parameters']), console.run(portable, 'portable')]
        base = 'ECLIPSE_PERMX_WATER'
        expected = oracle.records(prior / ('native-run-' + str(old['target']['id'])) / (base + '.INIT'))
        count = 0
        for run in runs:
            assert run['result']['summary']['series'] == old['target']['result']['summary']['series']
            content, _ = console.download(run, 'eclipse-output-' + base + '.INIT')
            path = console.out / (str(run['id']) + '.INIT'); path.write_bytes(content)
            actual = oracle.records(path)
            for name in ['PERMX', 'PERMY', 'PORO', 'DX', 'DY', 'DZ', 'DEPTH']:
                values, = [v for key, kind, v in actual if key == name and kind in ['REAL', 'DOUB']]
                source, = [v for key, kind, v in expected if key == name and kind in ['REAL', 'DOUB']]
                assert values == source and len(values) == 225
                count += len(values)
            grid, _ = console.download(run, 'eclipse-output-' + base + '.EGRID')
            assert grid == (prior / ('eclipse-output-' + base + '.EGRID')).read_bytes()
        proof.update(postRestartVerified=True, generationId=health['generationId'], runIds=[r['id'] for r in runs],
                     savedResultsUnchanged=True, portableBytesUnchanged=True, gridInputValuesVerified=count,
                     fullSummaryVerified=True, maxDifference=0)
        print(json.dumps(proof))
    except Exception as error:
        proof['error'] = str(error); raise
    finally:
        console.save('restart-replay', proof)


if __name__ == '__main__':
    main()
