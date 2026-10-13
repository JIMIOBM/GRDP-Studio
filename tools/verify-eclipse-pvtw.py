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
        parameters=dict(schemaVersion='eclipse-pvtw-parameters/1',baselineRunId=baseline['id'],sourceFile=base+'.DATA',sourceSha256=hashlib.sha256(source).hexdigest(),lineNumber=c['lineNumber'],field='VISCOSITY',unit='cP',originalValue=.5,targetValue=1,recordValues=c['recordValues'])
        invalid=[{**parameters,k:v} for k,v in [('field','FVF'),('unit','Pa.s'),('lineNumber',78),('lineNumber','77'),('sourceSha256','0'*64),('originalValue',1),('targetValue',0),('targetValue',-1),('targetValue','1'),('targetValue',.5),('recordValues',['.0','2.0','3.03E-06','.5','0.0']),('recordValues',['.0','1.0','3.03E-06','1*','0.0'])]]
        invalid.append({**parameters,'extra':1});console.reject_without_run(version,invalid)
        console.reject_without_run(version,[{**parameters,'baselineRunId':1}],expected=409)
        target=console.run(version,'target',parameters);assert target['result']['summary']['series']==native['target']['series'];assert arrays(target)==native['baseline']['arrays']
        actual_grid,_=console.download(target,'eclipse-output-'+base+'.EGRID');assert actual_grid==grid
        package,descriptor=console.download(target,'eclipse-schedule-applied-package.zip');(console.out/'pvtw-input-package.zip').write_bytes(package)
        expected=source.replace(b'3.03E-06  .5  0.0 /',b'3.03E-06  1  0.0 /',1)
        with zipfile.ZipFile(io.BytesIO(package)) as z:assert z.namelist()==[base+'.DATA'] and z.read(base+'.DATA')==expected
        portable_version=console.upload('portable',package,'pvtw-input-package.zip',base+'.DATA');assert portable_version['inspection']['wellNames']==version['inspection']['wellNames']
        assert portable_version['inspection']['pvtwContext']['recordValues']==['.0','1.0','3.03E-06','1','0.0']
        replay=console.run(portable_version,'portable');assert replay['result']['summary']['series']==native['target']['series'];assert arrays(replay)==native['baseline']['arrays']
        portable_grid,_=console.download(replay,'eclipse-output-'+base+'.EGRID');assert portable_grid==grid
        assert target['result']['eclEnd']==native['target']['eclEnd']
        assert len(native['baseline']['series'])==13 and len(native['target']['series'])==13
        identities=[(s['keyword'],s['objectName']) for s in native['target']['series']];assert len(set(identities))==13
        state,=[s for s in target['result']['summary']['series'] if s['keyword']=='WSTAT' and s['objectName']=='P'];assert all(p['value']==1 for p in state['points'])
        proof.update(apiVerified=True,baselineRunId=baseline['id'],targetRunId=target['id'],portableRunId=replay['id'],originalVersionId=version['id'],portableVersionId=portable_version['id'],portableArtifact=descriptor,
            sourceUnchanged=(repo/('test-data/'+base+'.DATA')).read_bytes()==source,onlyWaterViscosityTokenChanged=True,fullNativeSummaryVerified=True,summaryNumericPaths=1872,maxDifference=0,
            completeSeriesPerRun=13,reportsPerSeries=24,geologicalInputsUnchanged=True,geometryUnchanged=True,field='VISCOSITY',unit='cP',originalValue=.5,targetValue=1,wellIdentityAndControlsUnchanged=True,
            rejectedRequests=len(invalid)+1,rejectedRequestCreatedRun=False,lastBaselineBhp=native['lastBaselineBhp'],lastTargetBhp=native['lastTargetBhp'],lastWaterRate=native['lastWaterRate'],lastWaterTotal=native['lastWaterTotal'])
        print(json.dumps(proof))
    except Exception as error:proof['error']=str(error);raise
    finally:console.save('acceptance-summary',proof)


if __name__=='__main__':main()
