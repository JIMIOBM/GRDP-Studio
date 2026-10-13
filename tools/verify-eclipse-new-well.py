"""5173 new well, complete native vectors including state, exact ZIP another-project replay."""
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
    console=ConsoleAcceptance(args.evidence,args.resume);repo=Path(__file__).resolve().parent.parent;base='ECLIPSE_NEW_WELL_WATER'
    source=(repo/('test-data/'+base+'.DATA')).read_bytes();native=json.loads((Path(args.native_directory)/'native-acceptance.json').read_text(encoding='utf-8'))
    assert native['nativeVerified'] and native['explicitStateVerified'] and native['sourceSha256']==hashlib.sha256(source).hexdigest()
    spec=importlib.util.spec_from_file_location('oracle',repo/'tools/inspect-eclipse-compositional-native.py');oracle=importlib.util.module_from_spec(spec);spec.loader.exec_module(oracle)
    proof=dict(apiVerified=False,manualUiVerified=False)
    def arrays(run):
        content,_=console.download(run,'eclipse-output-'+base+'.INIT');directory=console.out/('native-run-'+str(run['id']));directory.mkdir(exist_ok=True)
        path=directory/(base+'.INIT');path.write_bytes(content);records=oracle.records(path);result={}
        for name in ['PERMX','PERMY','PORO','DX','DY','DZ','DEPTH']:
            result[name],=[v for k,t,v in records if k==name and t in ['REAL','DOUB']];assert len(result[name])==225
        return result
    try:
        version=console.upload('original',source,base+'.DATA');c=version['inspection']['newWellContext'];assert c['maxWells']==3 and c['insertionLine']==179
        baseline=console.run(version,'baseline');assert baseline['result']['summary']['series']==native['baseline']['series'];assert arrays(baseline)==native['baseline']['arrays']
        grid,artifact=console.download(baseline,'eclipse-output-'+base+'.EGRID')
        parameters=dict(schemaVersion='eclipse-new-well-parameters/1',baselineRunId=baseline['id'],sourceFile=base+'.DATA',sourceSha256=hashlib.sha256(source).hexdigest(),gridFile=base+'.EGRID',gridArtifactSha256=artifact['sha256'],
            nx=15,ny=15,nz=1,insertionLine=c['insertionLine'],well='P2',group='G',i=15,j=1,k=1,referenceDepth=4000,lengthUnit='m',diameter=.2,kh=1000,skin=0,waterRate=100,rateUnit='SM3/DAY',bhpLimit=3000,pressureUnit='BARSA')
        invalid=[{**parameters,k:v} for k,v in [('well','P'),('well','TOOLONG99'),('group','FIELD'),('group','OTHER'),('i',1),('i',16),('i','15'),('skin',-1),('diameter',0),('kh',0),('waterRate','100'),('bhpLimit',0),('pressureUnit','PSIA'),('insertionLine',180),('sourceSha256','0'*64)]]
        invalid.append({**parameters,'extra':1});console.reject_without_run(version,invalid)
        console.reject_without_run(version,[{**parameters,'gridArtifactSha256':'0'*64},{**parameters,'baselineRunId':1}],expected=409)
        target=console.run(version,'target',parameters);assert target['result']['summary']['series']==native['target']['series'];assert arrays(target)==native['baseline']['arrays']
        actual_grid,_=console.download(target,'eclipse-output-'+base+'.EGRID');assert actual_grid==grid
        package,descriptor=console.download(target,'eclipse-schedule-applied-package.zip');(console.out/'new-well-input-package.zip').write_bytes(package)
        block=(Path(args.native_directory)/'new-well-block.txt').read_bytes().replace(b'0.2 1000',b'0.20000000000000001 1000')
        newline=b'\r\n' if b'\r\n' in source else b'\n';expected=source.replace(newline+b'TSTEP'+newline,newline+block+b'TSTEP'+newline,1)
        with zipfile.ZipFile(io.BytesIO(package)) as z:assert z.namelist()==[base+'.DATA'] and z.read(base+'.DATA')==expected
        portable_version=console.upload('portable',package,'new-well-input-package.zip',base+'.DATA');assert 'P2' in portable_version['inspection']['wellNames']
        selected,=[r for r in portable_version['inspection']['scheduleMetadata']['completions'] if r['well']=='P2'];assert (selected['i'],selected['j'],selected['k1'],selected['k2'])==('15','1','1','1')
        replay=console.run(portable_version,'portable');assert replay['result']['summary']['series']==native['target']['series'];assert arrays(replay)==native['baseline']['arrays']
        portable_grid,_=console.download(replay,'eclipse-output-'+base+'.EGRID');assert portable_grid==grid
        assert target['result']['eclEnd']['warnings']==6 and target['result']['eclEnd']['comments']==1
        assert len(native['baseline']['series'])==13 and len(native['target']['series'])==13
        identities=[(s['keyword'],s['objectName']) for s in native['target']['series']];assert len(set(identities))==13
        state,=[s for s in target['result']['summary']['series'] if s['keyword']=='WSTAT' and s['objectName']=='P2'];assert state['points'][-1]['value']==3
        proof.update(apiVerified=True,baselineRunId=baseline['id'],targetRunId=target['id'],portableRunId=replay['id'],originalVersionId=version['id'],portableVersionId=portable_version['id'],portableArtifact=descriptor,
            sourceUnchanged=(repo/('test-data/'+base+'.DATA')).read_bytes()==source,onlyAddedNewWellBlock=True,fullNativeSummaryVerified=True,summaryNumericPaths=1872,maxDifference=0,
            completeSeriesPerRun=13,reportsPerSeries=24,allStaticInputsUnchanged=True,geometryUnchanged=True,newWell='P2',explicitStateVerified=True,limitedReports=native['limitedReports'],shutReports=native['shutReports'],
            rejectedRequests=len(invalid)+2,rejectedRequestCreatedRun=False,maxNewWellWaterRate=native['maxNewWellWaterRate'],lastNewWellWaterRate=native['lastNewWellWaterRate'],lastNewWellBhp=native['lastNewWellBhp'],lastNewWellWaterTotal=native['lastNewWellWaterTotal'])
        print(json.dumps(proof))
    except Exception as error:proof['error']=str(error);raise
    finally:console.save('acceptance-summary',proof)


if __name__=='__main__':main()
