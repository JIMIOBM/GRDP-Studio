"""Real CF source binding, all native curves, exact derived package and parameter-free replay."""
import argparse
import hashlib
import io
import json
import zipfile
from pathlib import Path
from eclipse_console_acceptance import ConsoleAcceptance


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', required=True)
    parser.add_argument('--native-directory', required=True)
    parser.add_argument('--resume', action='store_true')
    args = parser.parse_args()
    console = ConsoleAcceptance(args.evidence, args.resume)
    repo = Path(__file__).resolve().parent.parent
    source = (repo / 'test-data/ECLIPSE_EX5_INCLUDE.zip').read_bytes()
    assert hashlib.sha256(source).hexdigest() == '8dacc60e8670b4aa10aa6ac8f12dca5908ad5e691e2dde3b04d302633cd4c266'
    native = json.loads((Path(args.native_directory) / 'native-acceptance.json').read_text())
    assert native['nativeVerified']
    proof = dict(apiVerified=False, manualUiVerified=False)
    try:
        version = console.upload('original', source, 'ECLIPSE_EX5_INCLUDE.zip', 'EX5.DATA')
        assert version['modelKind'] == 'eclipse_100' and version['inspection']['unitSystem'] == 'FIELD'
        baseline = console.run(version, 'baseline')
        record, = [r for r in version['inspection']['scheduleMetadata']['records'] if r['keyword'] == 'COMPDAT' and r['sourceFile'] == 'BASE.SCH' and r['lineNumber'] == 116]
        assert record['values'][:8] == ['G1', '14', '2', '1', '1', 'OPEN', '1*', '3.028']
        parameters = dict(schemaVersion='eclipse-completion-factor-parameters/1', baselineRunId=baseline['id'], well='G1',
                          sourceFile='BASE.SCH', lineNumber=116, i='14', j='2', k1='1', k2='1', originalConnectionFactor=3.028, targetConnectionFactor=1.5)
        faults = []
        for name, invalid in [('targetConnectionFactor', 0), ('targetConnectionFactor', 3.028), ('targetConnectionFactor', '1.5'),
                              ('originalConnectionFactor', 3), ('sourceFile', '../BASE.SCH'), ('lineNumber', 117), ('i', 14),
                              ('j', '1'), ('well', 'G2'), ('baselineRunId', 0)]:
            faults.append({**parameters, name: invalid})
        faults.append({**parameters, 'unknown': 1})
        console.reject_without_run(version, faults)
        target = console.run(version, 'target', parameters)
        for label, run in [('baseline', baseline), ('target', target)]:
            assert run['result']['summary']['series'] == native[label]['summary']['series'], 'Full native output differs'
        portable, descriptor = console.download(target, 'eclipse-schedule-applied-package.zip')
        with zipfile.ZipFile(io.BytesIO(source)) as z:
            original = {name: z.read(name) for name in z.namelist()}
        with zipfile.ZipFile(io.BytesIO(portable)) as z:
            assert sorted(z.namelist()) == sorted(original)
            for name, content in original.items():
                if name != 'BASE.SCH': assert z.read(name) == content
                else:
                    lines = content.splitlines(keepends=True); lines[115] = lines[115].replace(b'3.028', b'1.5', 1)
                    assert z.read(name) == b''.join(lines), 'Only the chosen CF token may change'
        replay_version = console.upload('portable', portable, 'cf-portable.zip', 'EX5.DATA')
        replay = console.run(replay_version, 'portable')
        assert replay['result']['summary']['series'] == target['result']['summary']['series']
        assert replay['result']['eclEnd'] == target['result']['eclEnd']
        effects = []
        missing = []
        for keyword in ['WOPR', 'WWPR', 'WGPR', 'WBHP', 'WOPT']:
            originals = [s for s in baseline['result']['summary']['series'] if s['keyword'] == keyword and s['objectName'] == 'G1']
            targets = [s for s in target['result']['summary']['series'] if s['keyword'] == keyword and s['objectName'] == 'G1']
            if not originals and not targets:
                missing.append(keyword); continue
            a, = originals
            b, = targets
            assert a['unit'] == b['unit']
            before = {p['timeDays']: p['value'] for p in a['points']}
            effects += [dict(keyword=keyword, unit=a['unit'], timeDays=p['timeDays'], baseline=before[p['timeDays']], current=p['value'])
                        for p in b['points'] if p['timeDays'] in before and abs(p['value'] - before[p['timeDays']]) > max(1e-6, abs(before[p['timeDays']]) * 1e-6)]
        assert any(e['keyword'] == 'WBHP' for e in effects), 'A physical target-well response is required'
        assert (repo / 'test-data/ECLIPSE_EX5_INCLUDE.zip').read_bytes() == source
        proof.update(apiVerified=True, runIds=[baseline['id'], target['id'], replay['id']], versionIds=[version['id'], replay_version['id']],
                     rejectedRequests=len(faults), rejectedRequestCreatedRun=False, sourceUnchanged=True, onlySelectedTokenChanged=True,
                     fullReplayVerified=True, fullNativeVerified=True, portableArtifact=descriptor, effectCount=len(effects), effects=effects,
                     unrequestedIndicators=missing)
        print(json.dumps({k: v for k, v in proof.items() if k != 'effects'}))
    except Exception as error:
        proof['error'] = str(error); raise
    finally:
        console.save('acceptance-summary', proof)


if __name__ == '__main__':
    main()
