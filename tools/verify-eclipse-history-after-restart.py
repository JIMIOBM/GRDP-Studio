"""Verify exact persisted observation sources and fitting results without creating solver jobs."""
import argparse
import hashlib
import json
import urllib.request
from pathlib import Path
from eclipse_console_acceptance import ConsoleAcceptance


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--platform-evidence',required=True);parser.add_argument('--previous-generation',required=True);parser.add_argument('--evidence',required=True);args=parser.parse_args()
    console=ConsoleAcceptance(args.evidence);prior=Path(args.platform_evidence);load=lambda name:json.loads((prior/(name+'.json')).read_text(encoding='utf-8'))
    receipt=load('acceptance-summary');assert receipt['apiVerified'];proof=dict(postRestartVerified=False,manualUiVerified=False)
    try:
        with urllib.request.urlopen('http://127.0.0.1:5150/api/health',timeout=20) as response:health=json.load(response)
        assert health['idle'] and health['generationId']!=args.previous_generation
        sources={label:load('dataset-'+label) for label in ['main','water','gas','extreme']}
        versions={d['metadata']['modelVersionId'] for d in sources.values()}
        before={version:{r['id'] for r in console.request(f'/model-versions/{version}/runs?limit=100')} for version in versions}
        for label,old in sources.items():
            actual=console.request('/eclipse-history-datasets/'+str(old['metadata']['id']));assert actual==old
            assert hashlib.sha256(actual['csv'].encode('utf-8')).hexdigest()==actual['metadata']['sourceSha256']
            listing=console.request(f'/model-versions/{actual["metadata"]["modelVersionId"]}/eclipse-history-datasets')
            assert actual['metadata'] in listing and all('csv' not in row and 'observations' not in row for row in listing)
            console.save('dataset-'+label,actual)
        rows=0
        for file in sorted(prior.glob('comparison-*.json')):
            old=json.loads(file.read_text(encoding='utf-8'))
            actual=console.request(f'/eclipse-history-datasets/{old["dataset"]["id"]}/comparison?runId={old["runId"]}')
            assert actual==old;rows+=len(actual['comparison']['points']);console.save(file.stem,actual)
        assert before=={version:{r['id'] for r in console.request(f'/model-versions/{version}/runs?limit=100')} for version in versions}
        proof.update(postRestartVerified=True,generationId=health['generationId'],datasetIds=receipt['datasetIds'],sourceBytesAndShaUnchanged=True,savedComparisonsUnchanged=True,comparisonRows=rows,solverRunCountUnchanged=True)
        print(json.dumps(proof))
    except Exception as error:proof['error']=str(error);raise
    finally:console.save('restart-replay',proof)


if __name__=='__main__':main()
