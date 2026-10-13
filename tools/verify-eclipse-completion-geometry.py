"""Real completion numerical edits, native full summaries and exact portable inputs."""
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
    console=ConsoleAcceptance(args.evidence,args.resume);repo=Path(__file__).resolve().parent.parent
    source=(repo/'test-data/ECLIPSE_COMPLETION_GEOMETRY.DATA').read_bytes();base='ECLIPSE_COMPLETION_GEOMETRY'
    assert hashlib.sha256(source).hexdigest()=='b29df227965f5266b60bfc8701ea3c021114905371b87d7eb28c4b73ca822be9'
    native=json.loads((Path(args.native_directory)/'native-acceptance.json').read_text(encoding='utf-8'))
    assert native['nativeVerified'] and native['physicalEquivalenceVerified']
    spec=importlib.util.spec_from_file_location('oracle',repo/'tools/inspect-eclipse-compositional-native.py');oracle=importlib.util.module_from_spec(spec);spec.loader.exec_module(oracle)
    names=['PERMX','PERMY','PORO','DX','DY','DZ','DEPTH'];proof=dict(apiVerified=False,manualUiVerified=False)
    def arrays(run):
        content,_=console.download(run,'eclipse-output-'+base+'.INIT');directory=console.out/('native-run-'+str(run['id']));directory.mkdir(exist_ok=True)
        path=directory/(base+'.INIT');path.write_bytes(content);records=oracle.records(path)
        result={}
        for name in names:
            result[name],=[v for k,t,v in records if k==name and t in ['REAL','DOUB']];assert len(result[name])==225
        return result
    def last(run,key):
        s,=[s for s in run['result']['summary']['series'] if s['keyword']==key and s['objectName']=='P'];return s['points'][-1]['value']
    try:
        version=console.upload('original',source,base+'.DATA');baseline=console.run(version,'baseline')
        assert baseline['result']['summary']['series']==native['baseline']['series']
        before=arrays(baseline);grid,_=console.download(baseline,'eclipse-output-'+base+'.EGRID')
        row,=[r for r in version['inspection']['scheduleMetadata']['records'] if r['keyword']=='COMPDAT' and r['values'][0].strip()=='P']
        values=row['values'];assert values==['P       ','1','1','1','1','OPEN','0','.0','1.0','1000','0']
        common=dict(schemaVersion='eclipse-completion-geometry-parameters/1',baselineRunId=baseline['id'],well=values[0],sourceFile=row['sourceFile'],lineNumber=row['lineNumber'],
                    i=values[1],j=values[2],k1=values[3],k2=values[4],recordValues=values)
        p=dict(**common,field='DIAMETER',unit='m',originalValue=1,targetValue=.2)
        invalid=[{**p,k:v} for k,v in [('targetValue',0),('targetValue',3),('targetValue','0.2'),('originalValue','1'),('unit','mD'),('lineNumber',158),('well','P'),('k2','2'),('i','16'),('sourceFile','../BAD.DATA')]]
        invalid.extend([{**p,'unknown':1},{**p,'field':'SKIN','unit':'1','originalValue':0,'targetValue':-1},
                        {**p,'recordValues':values[:7]+['1.5']+values[8:]},{**p,'recordValues':values[:9]+['999']+values[10:]}])
        console.reject_without_run(version,invalid);console.reject_without_run(version,[{**p,'baselineRunId':1}],expected=409)
        original_row=b"'P       '   1   1   1   1 'OPEN'   0  .0   1.0  1000  0 /";assert source.count(original_row)==1
        results={};paths=192
        for label,field,unit,old,target,changed in [
            ('diameter','DIAMETER','m',1,.2,original_row.replace(b'1.0  1000',b'0.20000000000000001  1000')),
            ('kh','KH','mD.m',1000,500,original_row.replace(b'1000  0',b'500  0')),
            ('skin','SKIN','1',0,2,original_row.replace(b'1000  0 /',b'1000  2 /'))]:
            parameters=dict(**common,field=field,unit=unit,originalValue=old,targetValue=target)
            run=console.run(version,label,parameters);assert run['result']['summary']['series']==native[label]['series'];assert arrays(run)==before
            actual_grid,_=console.download(run,'eclipse-output-'+base+'.EGRID');assert actual_grid==grid
            package,artifact=console.download(run,'eclipse-schedule-applied-package.zip');(console.out/(label+'-input-package.zip')).write_bytes(package)
            with zipfile.ZipFile(io.BytesIO(package)) as z:
                assert z.namelist()==[base+'.DATA'];assert z.read(base+'.DATA')==source.replace(original_row,changed,1)
            replay_version=console.upload(label+'-portable',package,label+'-input-package.zip',base+'.DATA');replay=console.run(replay_version,label+'-portable')
            assert replay['result']['summary']['series']==native[label]['series'];assert arrays(replay)==before
            replay_grid,_=console.download(replay,'eclipse-output-'+base+'.EGRID');assert replay_grid==grid
            assert last(run,'WWPR')==1000 and last(run,'WWPT')==300000 and abs(last(run,'WBHP')-last(baseline,'WBHP'))>0.1
            paths+=384
            results[label]=dict(runId=run['id'],portableRunId=replay['id'],portableVersionId=replay_version['id'],portableArtifact=artifact,
                               fullNativeSummaryVerified=True,gridInputsUnchanged=True,geometryUnchanged=True,onlySelectedTokenChanged=True,lastProducerBhp=last(run,'WBHP'))
        proof.update(apiVerified=True,baselineRunId=baseline['id'],originalVersionId=version['id'],physicalEquivalenceVerified=True,
                     sourceUnchanged=(repo/'test-data/ECLIPSE_COMPLETION_GEOMETRY.DATA').read_bytes()==source,rejectedRequests=len(invalid)+1,rejectedRequestCreatedRun=False,
                     scenarios=results,baselineLastProducerBhp=last(baseline,'WBHP'),summaryNumericPaths=paths,maxDifference=0,bhpUnit='BARSA',waterRateUnit='SM3/DAY',cumulativeUnit='SM3')
        print(json.dumps(proof))
    except Exception as error:proof['error']=str(error);raise
    finally:console.save('acceptance-summary',proof)


if __name__=='__main__':main()
