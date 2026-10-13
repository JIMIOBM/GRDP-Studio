"""Preserve both relative-permeability scenarios and portable inputs, with four full replays after restart."""
import argparse,importlib.util,json,urllib.request
from pathlib import Path
from eclipse_console_acceptance import ConsoleAcceptance
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--platform-evidence',required=True);p.add_argument('--previous-generation',required=True);p.add_argument('--evidence',required=True);args=p.parse_args()
    console=ConsoleAcceptance(args.evidence);prior=Path(args.platform_evidence);receipt=json.loads((prior/'acceptance-summary.json').read_text(encoding='utf-8'));assert receipt['apiVerified']
    spec=importlib.util.spec_from_file_location('fmt',Path(__file__).parent/'inspect-eclipse-restart-native.py');fmt=importlib.util.module_from_spec(spec);spec.loader.exec_module(fmt)
    proof=dict(postRestartVerified=False,manualUiVerified=False);base='ECLIPSE_RELPERM_OIL'
    try:
        with urllib.request.urlopen('http://127.0.0.1:5150/api/health',timeout=20) as response:health=json.load(response)
        assert health['idle'] and health['generationId']!=args.previous_generation
        old={}
        for label in ['baseline','SWFN','SWFN-portable','SOF3','SOF3-portable']:
            old[label]=json.loads((prior/('run-'+label+'.json')).read_text(encoding='utf-8'));current=console.request('/runs/'+str(old[label]['id']));assert current['result']==old[label]['result'] and current['artifacts']==old[label]['artifacts']
        expected={k:v for k,t,v in fmt.blocks(prior/('native-run-'+str(old['baseline']['id']))/(base+'.FINIT')) if k in ['PERMX','PERMY','PERMZ','PORO','DX','DY','DZ','DEPTH']};assert len(expected)==8
        original=json.loads((prior/'version-original.json').read_text(encoding='utf-8'));runids=[];paths=0;count=0
        for keyword in ['SWFN','SOF3']:
            package,descriptor=console.download(old[keyword],'eclipse-schedule-applied-package.zip');assert descriptor==receipt['scenarios'][keyword]['portableArtifact'] and package==(prior/(keyword+'-input-package.zip')).read_bytes()
            portable=json.loads((prior/('version-'+keyword+'-portable.json')).read_text(encoding='utf-8'))
            for run in [console.run(original,keyword,old[keyword]['parameters']),console.run(portable,keyword+'-portable')]:
                assert run['result']['summary']['series']==old[keyword]['result']['summary']['series'];paths+=sum(2*len(s['points']) for s in run['result']['summary']['series'])
                content,_=console.download(run,'eclipse-output-'+base+'.FINIT');file=console.out/(str(run['id'])+'.FINIT');file.write_bytes(content);actual={k:v for k,t,v in fmt.blocks(file) if k in expected};assert actual==expected;count+=80
                grid,_=console.download(run,'eclipse-output-'+base+'.FEGRID');assert grid==(prior/('eclipse-output-'+base+'.FEGRID')).read_bytes();runids.append(run['id'])
        proof.update(postRestartVerified=True,generationId=health['generationId'],runIds=runids,savedResultsUnchanged=True,portableBytesUnchanged=True,gridInputValuesVerified=count,summaryNumericPaths=paths,maxDifference=0)
        print(json.dumps(proof))
    except Exception as error:proof['error']=str(error);raise
    finally:console.save('restart-replay',proof)
if __name__=='__main__':main()
