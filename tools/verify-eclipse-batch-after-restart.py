"""Read persisted batch history after restart and replay both actual input packages."""
import argparse
import json
import urllib.request
from pathlib import Path
from eclipse_console_acceptance import ConsoleAcceptance

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--platform-evidence',required=True);parser.add_argument('--previous-generation',required=True);parser.add_argument('--evidence',required=True);args=parser.parse_args()
    console=ConsoleAcceptance(args.evidence);prior=Path(args.platform_evidence);load=lambda name:json.loads((prior/(name+'.json')).read_text(encoding='utf-8'))
    receipt=load('acceptance-summary');assert receipt['apiVerified']
    proof=dict(postRestartVerified=False,manualUiVerified=False)
    try:
        with urllib.request.urlopen('http://127.0.0.1:5150/api/health',timeout=20) as response:health=json.load(response)
        assert health['idle'] and health['generationId']!=args.previous_generation
        for label in ['completed','isolated','cancelled']:
            old=load('batch-'+label);current=console.request('/eclipse-batches/'+str(old['id']))
            assert current==old,'Saved batch or result changed'
            listing=console.request('/model-versions/'+str(old['modelVersionId'])+'/eclipse-batches')
            summary,=[b for b in listing if b['id']==old['id']]
            assert summary['status']==old['status'] and summary['caseCount']==len(old['members']) and 'members' not in summary and 'baseline' not in summary
            console.save('batch-'+label,current)
        completed=load('batch-completed');runs=[];paths=0
        for member in completed['members']:
            name,run=member['name'],member['run'];content,artifact=console.download(run,'eclipse-schedule-applied-package.zip')
            assert artifact==receipt['inputPackages'][name] and content==(prior/(name+'-input-package.zip')).read_bytes()
            (console.out/(name+'-input-package.zip')).write_bytes(content)
            version=console.upload(name+'-portable',content,name+'.zip')
            replay=console.run(version,name+'-portable');assert replay['result']['summary']['series']==run['result']['summary']['series']
            paths+=sum(2*len(s['points']) for s in replay['result']['summary']['series']);runs.append(replay['id'])
        proof.update(postRestartVerified=True,generationId=health['generationId'],batchIds=[receipt[k] for k in ['successBatchId','failureBatchId','cancelledBatchId']],
                     runIds=runs,savedBatchesAndResultsUnchanged=True,portableBytesUnchanged=True,summaryNumericPaths=paths,maxDifference=0)
        print(json.dumps(proof))
    except Exception as error:proof['error']=str(error);raise
    finally:console.save('restart-replay',proof)

if __name__=='__main__':main()
