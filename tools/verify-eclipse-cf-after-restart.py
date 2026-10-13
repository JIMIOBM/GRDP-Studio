"""Preserve CF evidence and actual ZIP bytes, then replay bound target and portable after restart."""
import argparse
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
    proof = dict(postRestartVerified=False, manualUiVerified=False)
    try:
        with urllib.request.urlopen('http://127.0.0.1:5150/api/health', timeout=20) as response:
            health = json.load(response)
        assert health['idle'] and health['generationId'] != args.previous_generation
        console.save('initial-health', health)
        old = {}
        for label in ['baseline', 'target', 'portable']:
            before = json.loads((prior / ('run-' + label + '.json')).read_text(encoding='utf-8'))
            after = console.request('/runs/' + str(before['id']))
            assert before['result'] == after['result'] and before['artifacts'] == after['artifacts']
            old[label] = before
        descriptor = receipt['portableArtifact']
        portable, artifact = console.download(old['target'], descriptor['name'])
        assert artifact == descriptor and portable == (prior / descriptor['name']).read_bytes()
        originals = json.loads((prior / 'version-original.json').read_text(encoding='utf-8'))
        package = json.loads((prior / 'version-portable.json').read_text(encoding='utf-8'))
        target = console.run(originals, 'target', old['target']['parameters'])
        replay = console.run(package, 'portable')
        paths = 0
        for run in [target, replay]:
            assert run['result']['summary']['series'] == old['target']['result']['summary']['series']
            assert run['result']['eclEnd'] == old['target']['result']['eclEnd']
            paths += sum(2 * len(s['points']) for s in run['result']['summary']['series'])
        proof.update(postRestartVerified=True, generationId=health['generationId'], runIds=[target['id'], replay['id']],
                     savedResultsUnchanged=True, portableBytesUnchanged=True, numericPaths=paths, maxDifference=0)
        print(json.dumps(proof))
    except Exception as error:
        proof['error'] = str(error); raise
    finally:
        console.save('restart-replay', proof)


if __name__ == '__main__':
    main()
