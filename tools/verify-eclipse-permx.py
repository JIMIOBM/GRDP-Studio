"""Real single-cell PERMX edit: full native INIT bounds, business output and exact input ZIP replay."""
import argparse
import hashlib
import importlib.util
import io
import json
import time
import zipfile
from pathlib import Path
from eclipse_console_acceptance import ConsoleAcceptance


def native_series(raw):
    # SMSPEC's non-well placeholder is represented as null in the existing E100
    # API contract. Preserve every vector, unit, clock and value from the oracle.
    return [{**series, 'objectName': None if series['objectName'] in ['', 'FIELD', ':+:+:+:+', None]
             else series['objectName']} for series in raw]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', required=True)
    parser.add_argument('--native-directory', required=True)
    parser.add_argument('--resume', action='store_true')
    args = parser.parse_args()
    console = ConsoleAcceptance(args.evidence, args.resume)
    repo = Path(__file__).resolve().parent.parent
    original = (repo / 'test-data/ECLIPSE_PERMX_WATER.DATA').read_bytes()
    assert hashlib.sha256(original).hexdigest() == '97b5fdebe5df1c4e5c85b9b67d6d8bb747ee48aff0bf1e3668cfdc0e8fa43222'
    native = json.loads((Path(args.native_directory) / 'native-acceptance.json').read_text())
    assert native['nativeVerified']
    spec = importlib.util.spec_from_file_location('independent_binary', repo / 'tools/inspect-eclipse-compositional-native.py')
    oracle = importlib.util.module_from_spec(spec); spec.loader.exec_module(oracle)
    base = 'ECLIPSE_PERMX_WATER'
    proof = dict(apiVerified=False, manualUiVerified=False)

    def arrays(run):
        content, descriptor = console.download(run, 'eclipse-output-' + base + '.INIT')
        directory = console.out / ('native-run-' + str(run['id'])); directory.mkdir(exist_ok=True)
        file = directory / (base + '.INIT')
        if file.exists(): assert file.read_bytes() == content
        else: file.write_bytes(content)
        records = oracle.records(file)
        inputs = {}
        for name in ['PERMX', 'PERMY', 'PORO', 'DX', 'DY', 'DZ', 'DEPTH']:
            values, = [v for key, kind, v in records if key == name and kind in ['REAL', 'DOUB']]
            assert len(values) == 225
            inputs[name] = values
        return inputs, descriptor

    def stale_rejection(version, parameters):
        saved = console.out / 'run-stale-value.json'
        request = {**parameters, 'originalValue': 101}
        run = json.loads(saved.read_text(encoding='utf-8')) if args.resume and saved.exists() else console.request(
            f"/model-versions/{version['id']}/runs", dict(runType='eclipse', study=None, parameters=request), expected=201)
        console.save('run-stale-value', run)
        for _ in range(120):
            current = console.request('/runs/' + str(run['id']))
            if current['status'] in ['FAILED', 'SUCCEEDED', 'PARTIAL_SUCCEEDED', 'CANCELLED', 'TIMED_OUT', 'WORKER_LOST']:
                console.save('run-stale-value', current)
                assert current['status'] == 'FAILED' and current['error']['code'] == 'ECLIPSE_GRID_FIELD_STALE_VALUE'
                assert current['result'] is None and not any(a['name'].endswith('-applied-package.zip') for a in current['artifacts'])
                assert not any(e['status'] == 'RUNNING_ECLIPSE' for e in current['events'])
                assert all(current['cleanup'][k] for k in ['inputDeleted', 'workDirectoryDeleted', 'processTreeExitConfirmed'])
                return current['id']
            time.sleep(1)
        raise AssertionError('Stale value rejection timeout')

    try:
        version = console.upload('original', original, base + '.DATA')
        assert version['modelKind'] == 'eclipse_100' and version['inspection']['unitSystem'] == 'METRIC'
        assert version['inspection']['dimensions'] == dict(nx=15, ny=15, nz=1)
        baseline = console.run(version, 'baseline')
        assert baseline['result']['summary']['series'] == native_series(native['baseline']['series'])
        before, init = arrays(baseline)
        assert before['PERMX'] == native['baselinePermx']
        parameters = dict(schemaVersion='eclipse-grid-field-parameters/1', baselineRunId=baseline['id'], fieldFile=base + '.INIT',
                          fieldArtifactSha256=init['sha256'], nx=15, ny=15, nz=1, i=1, j=1, k=1, originalValue=100, multiplier=0.5)
        invalid = [{**parameters, name: value} for name, value in [('multiplier', 1), ('multiplier', 0), ('multiplier', '0.5'),
                   ('originalValue', '100'), ('i', 16), ('k', 2), ('fieldFile', '../BAD.INIT')]]
        invalid.append({**parameters, 'unknown': 1})
        console.reject_without_run(version, invalid)
        mismatches = [{**parameters, 'nx': 14}, {**parameters, 'fieldArtifactSha256': '0' * 64}, {**parameters, 'baselineRunId': 1}]
        console.reject_without_run(version, mismatches, expected=409)
        rejected = stale_rejection(version, parameters)
        target = console.run(version, 'target', parameters)
        assert target['result']['summary']['series'] == native_series(native['target']['series'])
        after, target_init = arrays(target)
        assert after['PERMX'] == native['targetPermx']
        for name in native['unchangedArrays']: assert after[name] == before[name]
        source_grid, _ = console.download(baseline, 'eclipse-output-' + base + '.EGRID')
        target_grid, _ = console.download(target, 'eclipse-output-' + base + '.EGRID')
        assert source_grid == target_grid
        portable, descriptor = console.download(target, 'eclipse-grid-field-applied-package.zip')
        newline = b'\r\n' if b'\r\n' in original else b'\n'
        block = newline.join([b'-- GRDP controlled single-cell PERMX multiplier; generated in the isolated run copy.',
                              b'MULTIPLY', b' PERMX 0.5 1 1 1 1 1 1 /', b'/', b''])
        expected = original.replace(b'PROPS     =', block + b'PROPS     =', 1)
        assert expected != original
        with zipfile.ZipFile(io.BytesIO(portable)) as z:
            assert z.namelist() == [base + '.DATA'] and z.read(base + '.DATA') == expected
        replay_version = console.upload('portable', portable, 'permx-portable.zip', base + '.DATA')
        replay = console.run(replay_version, 'portable')
        final, _ = arrays(replay)
        assert final == after and replay['result']['summary']['series'] == target['result']['summary']['series']
        assert replay['result']['eclEnd'] == target['result']['eclEnd']
        def last(run, keyword):
            series, = [s for s in run['result']['summary']['series'] if s['keyword'] == keyword and s['objectName'] == 'P']
            return series['points'][-1]['value']
        assert last(target, 'WWPR') == last(baseline, 'WWPR') == 1000 and last(target, 'WWPT') == 300000
        assert abs(last(target, 'WBHP') - last(baseline, 'WBHP')) > 1
        assert (repo / 'test-data/ECLIPSE_PERMX_WATER.DATA').read_bytes() == original
        proof.update(apiVerified=True, runIds=[baseline['id'], target['id'], replay['id']], versionIds=[version['id'], replay_version['id']],
                     rejectedRequests=len(invalid) + len(mismatches), rejectedRequestCreatedRun=False, staleValueFailedRunId=rejected,
                     cellCount=225, changedIndices=[0], unchangedPermxCells=224, unchangedArrays=native['unchangedArrays'],
                     geometryUnchanged=True, sourceUnchanged=True, fullNativeSummaryVerified=True, fullReplayVerified=True,
                     portableArtifact=descriptor, baselineInit=init, targetInit=target_init,
                     lastProducerBhp=[last(baseline, 'WBHP'), last(target, 'WBHP')], bhpUnit='BARSA',
                     lastProducerWaterRate=1000, waterRateUnit='SM3/DAY', lastCumulativeWater=300000, cumulativeUnit='SM3')
        print(json.dumps(proof))
    except Exception as error:
        proof['error'] = str(error); raise
    finally:
        console.save('acceptance-summary', proof)


if __name__ == '__main__':
    main()
