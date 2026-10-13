"""Real persistent batch execution: independent commercial oracles, failure isolation and idempotency."""
import argparse
import copy
import hashlib
import io
import json
import time
import uuid
import zipfile
from pathlib import Path
from eclipse_console_acceptance import ConsoleAcceptance

TERMINAL = {'SUCCEEDED', 'PARTIAL_SUCCEEDED', 'FAILED', 'CANCELLED'}


def load(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def wait(console, batch_id):
    for _ in range(360):
        batch = console.request(f'/eclipse-batches/{batch_id}')
        console.save(f'batch-{batch_id}', batch)
        if batch['status'] in TERMINAL:
            return batch
        time.sleep(1)
    raise AssertionError('Batch did not finish')


def ids(console, version_id):
    return {run['id'] for run in console.request(f'/model-versions/{version_id}/runs?limit=100')}


def submitted(console, version, request):
    result = console.request(f'/model-versions/{version}/eclipse-batches', request, expected=201)
    console.save(f'request-{result["id"]}', request)
    console.save(f'batch-{result["id"]}', result)
    return result


def serial(batch):
    runs = [member['run'] for member in batch['members']]
    assert all(run['finishedAt'] and run['claimedAt'] for run in runs)
    assert all(before['finishedAt'] <= after['claimedAt'] for before, after in zip(runs, runs[1:])), 'Commercial runs overlapped'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--relperm-evidence', required=True)
    parser.add_argument('--permx-evidence', required=True)
    parser.add_argument('--evidence', required=True)
    args = parser.parse_args()
    console = ConsoleAcceptance(args.evidence)
    rel, perm = Path(args.relperm_evidence), Path(args.permx_evidence)
    assert load(rel/'acceptance-summary.json')['apiVerified'] and load(perm/'acceptance-summary.json')['apiVerified']
    baseline = load(rel/'run-baseline.json')
    expected = {keyword: load(rel/f'run-{keyword}.json') for keyword in ['SWFN', 'SOF3']}
    request = dict(requestKey=str(uuid.uuid4()), name='相渗两项独立方案', baselineRunId=baseline['id'], cases=[dict(name=keyword, parameters=run['parameters']) for keyword, run in expected.items()])
    proof = dict(apiVerified=False, manualUiVerified=False)
    try:
        path = f'/model-versions/{baseline["modelVersionId"]}/eclipse-batches'
        before = ids(console, baseline['modelVersionId'])
        rejects = []
        for mutate in [lambda p: p['cases'].pop(), lambda p: p.update(extra=1),
                       lambda p: p['cases'][1].update(name=p['cases'][0]['name']),
                       lambda p: p['cases'][1].update(parameters=copy.deepcopy(p['cases'][0]['parameters'])),
                       lambda p: p['cases'][1]['parameters'].update(unit='mD'),
                       lambda p: p['cases'][1]['parameters'].update(sourceSha256='0'*64)]:
            bad = copy.deepcopy(request); mutate(bad); console.request(path, bad, expected=400); rejects.append(bad)
        assert ids(console, baseline['modelVersionId']) == before
        assert console.request(path) == []
        console.save('rejected-requests', rejects)
        created = submitted(console, baseline['modelVersionId'], request)
        child_ids = [member['run']['id'] for member in created['members']]
        replay = submitted(console, baseline['modelVersionId'], copy.deepcopy(request))
        assert replay['id'] == created['id'] and [m['run']['id'] for m in replay['members']] == child_ids
        changed = copy.deepcopy(request); changed['name'] = '不同内容'
        console.request(path, changed, expected=409)
        assert ids(console, baseline['modelVersionId']) == before | set(child_ids)
        completed = wait(console, created['id']); assert completed['status'] == 'SUCCEEDED'; serial(completed)
        numeric = 0; packages = {}
        for member in completed['members']:
            keyword, run = member['name'], member['run']; original = expected[keyword]
            assert run['status'] == 'SUCCEEDED' and run['resultContract'] == 'VALID_FULL'
            assert run['result']['summary']['series'] == original['result']['summary']['series']
            assert all(run['cleanup'][key] for key in ['inputDeleted', 'workDirectoryDeleted', 'processTreeExitConfirmed'])
            numeric += sum(len(s['points'])*2 for s in run['result']['summary']['series'])
            content, artifact = console.download(run, 'eclipse-schedule-applied-package.zip')
            prior = (rel/f'{keyword}-input-package.zip').read_bytes()
            with zipfile.ZipFile(io.BytesIO(content)) as current, zipfile.ZipFile(io.BytesIO(prior)) as old:
                assert current.namelist() == old.namelist()
                assert all(current.read(name) == old.read(name) for name in old.namelist())
            (console.out/f'{keyword}-input-package.zip').write_bytes(content)
            packages[keyword] = artifact
            console.save(f'run-{keyword}', run)
        console.save('batch-completed', completed)
        # Deliberately stale actual INIT value passes request shape/source binding but is rejected by the real Worker before eclrun.
        perm_base = load(perm/'run-baseline.json'); perm_target = load(perm/'run-target.json')
        valid = perm_target['parameters']; stale = {**valid, 'originalValue': valid['originalValue']+1}
        failure_request = dict(requestKey=str(uuid.uuid4()), name='真实旧场值失败隔离', baselineRunId=perm_base['id'], cases=[dict(name='故意失效场值', parameters=stale), dict(name='有效PERMX减半', parameters=valid)])
        isolated = wait(console, submitted(console, perm_base['modelVersionId'], failure_request)['id']); serial(isolated)
        assert isolated['status'] == 'PARTIAL_SUCCEEDED'
        failed, success = [m['run'] for m in isolated['members']]
        assert failed['status'] == 'FAILED' and failed['result'] is None and failed['error']['code'] == 'ECLIPSE_GRID_FIELD_STALE_VALUE'
        assert all(event['status'] != 'RUNNING_ECLIPSE' for event in failed['events'])
        assert all(failed['cleanup'][key] for key in ['inputDeleted', 'workDirectoryDeleted', 'processTreeExitConfirmed'])
        assert success['status'] == 'SUCCEEDED' and success['result']['summary']['series'] == perm_target['result']['summary']['series']
        numeric += sum(len(s['points'])*2 for s in success['result']['summary']['series'])
        console.save('batch-isolated', isolated)
        cancellation = {**copy.deepcopy(request), 'requestKey': str(uuid.uuid4()), 'name': '真实取消批量测试'}
        cancelling = submitted(console, baseline['modelVersionId'], cancellation)
        console.request(f'/eclipse-batches/{cancelling["id"]}/cancel', {})
        cancelled = wait(console, cancelling['id'])
        assert any(m['run']['status'] == 'CANCELLED' for m in cancelled['members'])
        assert all(m['run']['status'] in ['CANCELLED', 'SUCCEEDED'] for m in cancelled['members'])
        assert console.request('/runs/'+str(baseline['id'])) == baseline, 'Cancelling children changed baseline'
        console.save('batch-cancelled', cancelled)
        listing = console.request(path)
        assert {batch['id'] for batch in listing} == {created['id'], cancelling['id']}
        assert console.request(f'/eclipse-batches/{created["id"]}') == completed
        proof.update(apiVerified=True, successBatchId=created['id'], failureBatchId=isolated['id'], cancelledBatchId=cancelled['id'], childRunIds=child_ids,
                     isolatedRunIds=[failed['id'], success['id']], serialExecutionVerified=True, idempotentSubmissionVerified=True,
                     rollbackVerified=True, invalidRequests=len(rejects)+1, failureHasNoFabricatedResult=True, baselineUnchanged=True,
                     summaryNumericPaths=numeric, maxDifference=0, inputPackages=packages)
        print(json.dumps(proof))
    except Exception as error:
        proof['error'] = str(error); raise
    finally:
        console.save('acceptance-summary', proof)


if __name__ == '__main__':
    main()
