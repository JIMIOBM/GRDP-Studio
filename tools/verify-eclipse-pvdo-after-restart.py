"""Preserve saved oil PVT results and exact downloaded input; two full native replays after generation changes."""
import argparse,importlib.util,json,urllib.request
from pathlib import Path
from eclipse_console_acceptance import ConsoleAcceptance
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--platform-evidence',required=True);p.add_argument('--previous-generation',required=True);p.add_argument('--evidence',required=True);args=p.parse_args()
    console=ConsoleAcceptance(args.evidence);prior=Path(args.platform_evidence);receipt=json.loads((prior/'acceptance-summary.json').read_text(encoding='utf-8'));assert receipt['apiVerified']
    spec=importlib.util.spec_from_file_location('fmt',Path(__file__).parent/'inspect-eclipse-restart-native.py');fmt=importlib.util.module_from_spec(spec);spec.loader.exec_module(fmt)
    proof=dict(postRestartVerified=False,manualUiVerified=False);base='ECLIPSE_PVDO_OIL'
    try:
        with urllib.request.urlopen('http://127.0.0.1:5150/api/health',timeout=20) as response:health=json.load(response)
        assert health['idle'] and health['generationId']!=args.previous_generation
        old={}
        for label in ['baseline','target','portable']:
            old[label]=json.loads((prior/('run-'+label+'.json')).read_text(encoding='utf-8'));current=console.request('/runs/'+str(old[label]['id']));assert current['result']==old[label]['result'] and current['artifacts']==old[label]['artifacts']
        package,descriptor=console.download(old['target'],'eclipse-schedule-applied-package.zip');assert descriptor==receipt['portableArtifact'] and package==(prior/'pvdo-input-package.zip').read_bytes()
        original=json.loads((prior/'version-original.json').read_text(encoding='utf-8'));portable=json.loads((prior/'version-portable.json').read_text(encoding='utf-8'));runs=[];count=0;paths=0
        expected={k:v for k,t,v in fmt.blocks(prior/('native-run-'+str(old['baseline']['id']))/(base+'.FINIT')) if k in ['PERMX','PERMY','PERMZ','PORO','DX','DY','DZ','DEPTH']}
        for run in [console.run(original,'target',old['target']['parameters']),console.run(portable,'portable')]:
            assert run['result']['summary']['series']==old['target']['result']['summary']['series'];paths+=sum(2*len(s['points']) for s in run['result']['summary']['series'])
            content,_=console.download(run,'eclipse-output-'+base+'.FINIT');path=console.out/(str(run['id'])+'.FINIT');path.write_bytes(content)
            actual={k:v for k,t,v in fmt.blocks(path) if k in expected};assert len(actual)==8 and actual==expected;count+=80
            grid,_=console.download(run,'eclipse-output-'+base+'.FEGRID');assert grid==(prior/('eclipse-output-'+base+'.FEGRID')).read_bytes();runs.append(run['id'])
        proof.update(postRestartVerified=True,generationId=health['generationId'],runIds=runs,savedResultsUnchanged=True,portableBytesUnchanged=True,gridInputValuesVerified=count,summaryNumericPaths=paths,maxDifference=0)
        print(json.dumps(proof))
    except Exception as error:proof['error']=str(error);raise
    finally:console.save('restart-replay',proof)
if __name__=='__main__':main()
