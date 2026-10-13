"""After restart, preserve both real regional input ZIPs and replay bound/portable scenarios."""
import argparse
import importlib.util
import json
import urllib.request
from pathlib import Path
from eclipse_console_acceptance import ConsoleAcceptance


def main():
    parser=argparse.ArgumentParser(description=__doc__); parser.add_argument('--platform-evidence',required=True)
    parser.add_argument('--previous-generation',required=True); parser.add_argument('--evidence',required=True); args=parser.parse_args()
    console=ConsoleAcceptance(args.evidence); prior=Path(args.platform_evidence)
    receipt=json.loads((prior/'acceptance-summary.json').read_text(encoding='utf-8')); assert receipt['apiVerified']
    spec=importlib.util.spec_from_file_location('oracle',Path(__file__).parent/'inspect-eclipse-compositional-native.py'); oracle=importlib.util.module_from_spec(spec);spec.loader.exec_module(oracle)
    proof=dict(postRestartVerified=False,manualUiVerified=False); base='ECLIPSE_PERMX_WATER'
    try:
        with urllib.request.urlopen('http://127.0.0.1:5150/api/health',timeout=20) as response:health=json.load(response)
        assert health['idle'] and health['generationId']!=args.previous_generation
        old={}
        for label in ['baseline','permx','permx-portable','poro','poro-portable']:
            old[label]=json.loads((prior/('run-'+label+'.json')).read_text(encoding='utf-8'))
            current=console.request('/runs/'+str(old[label]['id']))
            assert current['result']==old[label]['result'] and current['artifacts']==old[label]['artifacts']
        original=json.loads((prior/'version-original.json').read_text(encoding='utf-8'))
        runs=[]; count=0; paths=0
        for label in ['permx','poro']:
            package,artifact=console.download(old[label],'eclipse-grid-field-applied-package.zip')
            assert artifact==receipt['scenarios'][label]['portableArtifact'] and package==(prior/(label+'-input-package.zip')).read_bytes()
            portable=json.loads((prior/('version-'+label+'-portable.json')).read_text(encoding='utf-8'))
            expected=oracle.records(prior/('native-run-'+str(old[label]['id']))/(base+'.INIT'))
            for run in [console.run(original,label,old[label]['parameters']),console.run(portable,label+'-portable')]:
                assert run['result']['summary']['series']==old[label]['result']['summary']['series']
                paths+=sum(2*len(s['points']) for s in run['result']['summary']['series'])
                content,_=console.download(run,'eclipse-output-'+base+'.INIT'); path=console.out/(str(run['id'])+'.INIT');path.write_bytes(content)
                actual=oracle.records(path)
                for name in ['PERMX','PERMY','PORO','DX','DY','DZ','DEPTH']:
                    values,=[v for key,kind,v in actual if key==name and kind in ['REAL','DOUB']]
                    source,=[v for key,kind,v in expected if key==name and kind in ['REAL','DOUB']]
                    assert len(values)==225 and values==source;count+=225
                grid,_=console.download(run,'eclipse-output-'+base+'.EGRID');assert grid==(prior/('eclipse-output-'+base+'.EGRID')).read_bytes()
                runs.append(run['id'])
        proof.update(postRestartVerified=True,generationId=health['generationId'],runIds=runs,savedResultsUnchanged=True,portableBytesUnchanged=True,
                     gridInputValuesVerified=count,summaryNumericPaths=paths,maxDifference=0)
        print(json.dumps(proof))
    except Exception as error:proof['error']=str(error);raise
    finally:console.save('restart-replay',proof)


if __name__=='__main__':main()
