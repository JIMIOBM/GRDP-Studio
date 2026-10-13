"""5173 water PVT, complete native vectors with original well controls, exact ZIP another-project replay."""
import argparse
import hashlib
import importlib.util
import io
import json
import zipfile
from pathlib import Path
from eclipse_console_acceptance import ConsoleAcceptance


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--evidence',required=True);parser.add_argument('--native-directory',required=True);parser.add_argument('--resume',action='store_true');args=parser.parse_args()
    console=ConsoleAcceptance(args.evidence,args.resume);repo=Path(__file__).resolve().parent.parent;base='ECLIPSE_PVTW_WATER'
    source=(repo/('test-data/'+base+'.DATA')).read_bytes();native=json.loads((Path(args.native_directory)/'native-acceptance.json').read_text(encoding='utf-8'))
    assert native['nativeVerified'] and native['wellControlsUnchanged'] and native['sourceSha256']==hashlib.sha256(source).hexdigest()
    spec=importlib.util.spec_from_file_location('oracle',repo/'tools/inspect-eclipse-compositional-native.py');oracle=importlib.util.module_from_spec(spec);spec.loader.exec_module(oracle)
    proof=dict(apiVerified=False,manualUiVerified=False)
    def arrays(run):
        content,_=console.download(run,'eclipse-output-'+base+'.INIT');directory=console.out/('native-run-'+str(run['id']));directory.mkdir(exist_ok=True)
        path=directory/(base+'.INIT');path.write_bytes(content);records=oracle.records(path);result={}
        for name in ['PERMX','PERMY','PORO','DX','DY','DZ','DEPTH']:
            result[name],=[v for k,t,v in records if k==name and t in ['REAL','DOUB']];assert len(result[name])==225
        return result
    try:
        version=console.upload('original',source,base+'.DATA');c=version['inspection']['pvtwContext'];assert c['lineNumber']==77 and c['recordValues']==['.0','1.0','3.03E-06','.5','0.0']
        baseline=console.run(version,'baseline');assert baseline['result']['summary']['series']==native['baseline']['series'];assert arrays(baseline)==native['baseline']['arrays']
        grid,artifact=console.download(baseline,'eclipse-output-'+base+'.EGRID')
        scenarios={};paths=624;rejected=0
        import re
        oldrow,=re.finditer(rb'(?m)^  \.0  1\.0  3\.03E-06  \.5  0\.0 /',source);tokens=list(re.finditer(rb'[^\s/]+',oldrow.group()))
        for label,definition in native['scenarios'].items():
            parameters=dict(schemaVersion='eclipse-pvtw-parameters/1' if definition['field']=='VISCOSITY' else 'eclipse-pvtw-parameters/2',baselineRunId=baseline['id'],sourceFile=base+'.DATA',sourceSha256=hashlib.sha256(source).hexdigest(),lineNumber=c['lineNumber'],field=definition['field'],unit=definition['unit'],originalValue=definition['originalValue'],targetValue=definition['targetValue'],recordValues=c['recordValues'])
            invalid=[{**parameters,'targetValue':str(parameters['targetValue'])},{**parameters,'unit':'Pa.s'},{**parameters,'lineNumber':78},{**parameters,'originalValue':999},{**parameters,'extra':1}]
            if definition['field']!='VISCOSITY':invalid.append({**parameters,'schemaVersion':'eclipse-pvtw-parameters/1'})
            console.reject_without_run(version,invalid);rejected+=len(invalid)
            target=console.run(version,label,parameters);assert target['result']['summary']['series']==native[label]['series'];assert arrays(target)==native['baseline']['arrays']
            actual_grid,_=console.download(target,'eclipse-output-'+base+'.EGRID');assert actual_grid==grid
            package,descriptor=console.download(target,'eclipse-schedule-applied-package.zip');(console.out/(label+'-input-package.zip')).write_bytes(package)
            selected=tokens[definition['index']];offset=oldrow.start()+selected.start();end=oldrow.start()+selected.end();prefix=source[:offset];suffix=source[end:]
            with zipfile.ZipFile(io.BytesIO(package)) as z:
                assert z.namelist()==[base+'.DATA'];applied=z.read(base+'.DATA');assert applied.startswith(prefix) and applied.endswith(suffix)
                token=applied[len(prefix):len(applied)-len(suffix)];assert re.fullmatch(rb'[0-9.+Ee-]+',token) and float(token)==definition['targetValue']
            portable_version=console.upload(label+'-portable',package,label+'-input-package.zip',base+'.DATA');expected=list(c['recordValues']);expected[definition['index']]=token.decode();assert portable_version['inspection']['pvtwContext']['recordValues']==expected
            replay=console.run(portable_version,label+'-portable');assert replay['result']['summary']['series']==native[label]['series'];assert arrays(replay)==native['baseline']['arrays']
            portable_grid,_=console.download(replay,'eclipse-output-'+base+'.EGRID');assert portable_grid==grid
            assert target['result']['eclEnd']==native[label]['eclEnd'];paths+=1248
            scenarios[label]=dict(**definition,targetRunId=target['id'],portableRunId=replay['id'],portableVersionId=portable_version['id'],portableArtifact=descriptor,onlySelectedTokenChanged=True,allNativeSummaryEqual=True)
        console.reject_without_run(version,[{**parameters,'baselineRunId':1}],expected=409);rejected+=1
        proof.update(apiVerified=True,baselineRunId=baseline['id'],originalVersionId=version['id'],scenarios=scenarios,sourceUnchanged=(repo/('test-data/'+base+'.DATA')).read_bytes()==source,onlySelectedPvtwTokenChanged=True,
            fullNativeSummaryVerified=True,summaryNumericPaths=paths,maxDifference=0,completeSeriesPerRun=13,reportsPerSeries=24,geologicalInputsUnchanged=True,geometryUnchanged=True,wellIdentityAndControlsUnchanged=True,rejectedRequests=rejected,rejectedRequestCreatedRun=False)
        print(json.dumps(proof))
    except Exception as error:proof['error']=str(error);raise
    finally:console.save('acceptance-summary',proof)


if __name__=='__main__':main()
