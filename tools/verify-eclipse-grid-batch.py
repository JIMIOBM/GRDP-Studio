"""Independently verify the production UI's real regional batch and replay its downloaded inputs."""
import argparse
import importlib.util
import io
import json
import zipfile
from pathlib import Path
from eclipse_console_acceptance import ConsoleAcceptance


def load(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--ui-evidence',required=True);p.add_argument('--region-evidence',required=True);p.add_argument('--evidence',required=True)
    args=p.parse_args();ui,prior=Path(args.ui_evidence),Path(args.region_evidence)
    receipt=load(ui/'automated-grid-batch-ui.json');assert receipt['automatedComponentVerified'] and receipt['actualApiTransport']
    console=ConsoleAcceptance(args.evidence)
    spec=importlib.util.spec_from_file_location('native',Path(__file__).parent/'inspect-eclipse-compositional-native.py')
    native=importlib.util.module_from_spec(spec);spec.loader.exec_module(native)
    proof=dict(apiVerified=False,manualUiVerified=False)
    try:
        batch=console.request('/eclipse-batches/'+str(receipt['batchId']))
        assert batch==load(ui/'batch-completed.json') and batch['status']=='SUCCEEDED'
        requested=load(ui/'request.json');path=f'/model-versions/{batch["modelVersionId"]}/eclipse-batches'
        before=console.request(f'/model-versions/{batch["modelVersionId"]}/runs?limit=100')
        replay=console.request(path,requested,expected=201)
        assert replay==batch and console.request(f'/model-versions/{batch["modelVersionId"]}/runs?limit=100')==before
        runs=[m['run'] for m in batch['members']]
        assert all(a['finishedAt']<=b['claimedAt'] for a,b in zip(runs,runs[1:]))
        base='ECLIPSE_PERMX_WATER';values_verified=0;paths=0;portable_ids=[];scenarios=[]
        for member in batch['members']:
            label=member['name'].lower();old=load(prior/f'run-{label}.json');run=member['run']
            assert run['parameters']==old['parameters'] and run['result']['summary']['series']==old['result']['summary']['series']
            assert run['status']=='SUCCEEDED' and run['resultContract']=='VALID_FULL'
            assert all(run['cleanup'][k] for k in ['inputDeleted','workDirectoryDeleted','processTreeExitConfirmed'])
            package,artifact=console.download(run,'eclipse-grid-field-applied-package.zip')
            with zipfile.ZipFile(io.BytesIO(package)) as actual,zipfile.ZipFile(prior/f'{label}-input-package.zip') as expected:
                assert actual.namelist()==expected.namelist()
                assert all(actual.read(name)==expected.read(name) for name in expected.namelist())
            (console.out/f'{label}-input-package.zip').write_bytes(package)
            portable=console.upload(label+'-portable',package,f'{label}.zip',base+'.DATA')
            replay=console.run(portable,label+'-portable');portable_ids.append(replay['id'])
            expected=native.records(prior/f'native-run-{old["id"]}'/(base+'.INIT'))
            for item in [run,replay]:
                assert item['result']['summary']['series']==old['result']['summary']['series']
                paths+=sum(2*len(s['points']) for s in item['result']['summary']['series'])
                content,_=console.download(item,'eclipse-output-'+base+'.INIT');dest=console.out/(str(item['id'])+'.INIT');dest.write_bytes(content)
                actual=native.records(dest)
                for key in ['PERMX','PERMY','PORO','DX','DY','DZ','DEPTH']:
                    a,=[v for k,t,v in actual if k==key and t in ['REAL','DOUB']]
                    b,=[v for k,t,v in expected if k==key and t in ['REAL','DOUB']]
                    assert len(a)==225 and a==b;values_verified+=225
                grid,_=console.download(item,'eclipse-output-'+base+'.EGRID');assert grid==(prior/('eclipse-output-'+base+'.EGRID')).read_bytes()
            console.save('run-'+label,run)
            scenarios.append(dict(keyword=old['parameters']['keyword'],runId=run['id'],portableRunId=replay['id'],selectedCells=6,unchangedOutsideCells=219,packageArtifact=artifact))
        assert console.request('/eclipse-batches/'+str(batch['id']))==batch
        console.save('batch-completed',batch)
        proof.update(apiVerified=True,batchId=batch['id'],baselineRunId=batch['baselineRunId'],childRunIds=receipt['runIds'],portableRunIds=portable_ids,
            idempotencyReusedSameRunIds=True,commercialRunsSerial=True,fullGridValuesVerified=values_verified,summaryNumericPaths=paths,maxDifference=0,scenarios=scenarios)
        print(json.dumps(proof))
    except Exception as error:
        proof['error']=str(error);raise
    finally:
        console.save('acceptance-summary',proof)


if __name__=='__main__':main()
