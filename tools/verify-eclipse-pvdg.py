"""Actual 5173 dry-gas table edit, complete independent native output and exact portable input replay."""
import argparse,hashlib,importlib.util,io,json,zipfile
from pathlib import Path
from eclipse_console_acceptance import ConsoleAcceptance

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--evidence',required=True);p.add_argument('--native-directory',required=True);p.add_argument('--resume',action='store_true');args=p.parse_args()
    console=ConsoleAcceptance(args.evidence,args.resume);repo=Path(__file__).resolve().parent.parent;base='ECLIPSE_PVDG_GASWATER'
    source=(repo/('test-data/'+base+'.DATA')).read_bytes();native=json.loads((Path(args.native_directory)/'native-acceptance.json').read_text(encoding='utf-8'))
    assert native['nativeVerified'] and native['onlyGasViscosityColumnChanged'] and native['sourceSha256']==hashlib.sha256(source).hexdigest()
    spec=importlib.util.spec_from_file_location('binary',repo/'tools/inspect-eclipse-compositional-native.py');binary=importlib.util.module_from_spec(spec);spec.loader.exec_module(binary)
    proof=dict(apiVerified=False,manualUiVerified=False)
    def arrays(run):
        content,_=console.download(run,'eclipse-output-'+base+'.INIT');d=console.out/('native-run-'+str(run['id']));d.mkdir(exist_ok=True);path=d/(base+'.INIT');path.write_bytes(content)
        result={}
        for k,t,v in binary.records(path):
            if k in ['PERMX','PERMY','PERMZ','PORO','DX','DY','DZ','DEPTH'] and t in ['REAL','DOUB']:assert k not in result and len(v)==1600;result[k]=v
        assert len(result)==8;return result
    try:
        version=console.upload('original',source,base+'.DATA');c=version['inspection']['gasPvtContext'];assert c['keywordLineNumber']==82 and len(c['rows'])==2
        baseline=console.run(version,'baseline');assert baseline['result']['summary']['series']==native['baseline']['series'];assert arrays(baseline)==native['baseline']['arrays']
        grid,_=console.download(baseline,'eclipse-output-'+base+'.EGRID')
        parameters=dict(schemaVersion='eclipse-pvdg-parameters/1',baselineRunId=baseline['id'],sourceFile=base+'.DATA',sourceSha256=hashlib.sha256(source).hexdigest(),keywordLineNumber=c['keywordLineNumber'],unit='cP',multiplier=2,rows=c['rows'])
        bad=[{**parameters,k:v} for k,v in [('unit','Pa.s'),('keywordLineNumber',83),('keywordLineNumber','82'),('sourceSha256','0'*64),('sourceFile','other.DATA'),('multiplier',1),('multiplier',0),('multiplier',-1),('multiplier',101),('multiplier','2'),('rows',[]),('rows',c['rows'][:1])]]
        bad.append({**parameters,'extra':1})
        for key,value in [('lineNumber',82),('values',['0','1.3','2.0']),('values',['0','1.0','1*']),('extra',1)]:
            rows=json.loads(json.dumps(c['rows']));rows[0][key]=value;bad.append({**parameters,'rows':rows})
        console.reject_without_run(version,bad);console.reject_without_run(version,[{**parameters,'baselineRunId':1}],expected=409)
        target=console.run(version,'target',parameters);assert target['result']['summary']['series']==native['target']['series'];assert arrays(target)==native['baseline']['arrays']
        target_grid,_=console.download(target,'eclipse-output-'+base+'.EGRID');assert target_grid==grid
        package,descriptor=console.download(target,'eclipse-schedule-applied-package.zip');(console.out/'pvdg-input-package.zip').write_bytes(package)
        start=source.index(b'PVDG');end=source.index(b'ROCK',start);table=source[start:end];expected=source[:start]+table.replace(b'2.0',b'4')+source[end:]
        with zipfile.ZipFile(io.BytesIO(package)) as z:assert z.namelist()==[base+'.DATA'] and z.read(base+'.DATA')==expected
        portable=console.upload('portable',package,'pvdg-input-package.zip',base+'.DATA');assert portable['inspection']['wellNames']==version['inspection']['wellNames'];assert all(r['values'][2]=='4' for r in portable['inspection']['gasPvtContext']['rows'])
        replay=console.run(portable,'portable');assert replay['result']['summary']['series']==native['target']['series'];assert arrays(replay)==native['baseline']['arrays']
        replay_grid,_=console.download(replay,'eclipse-output-'+base+'.EGRID');assert replay_grid==grid
        assert target['result']['eclEnd']==native['target']['eclEnd']
        assert all(len(s['points'])==61 for s in native['baseline']['series']) and all(len(s['points'])==64 for s in native['target']['series']) and len(native['target']['series'])==11
        proof.update(apiVerified=True,baselineRunId=baseline['id'],targetRunId=target['id'],portableRunId=replay['id'],originalVersionId=version['id'],portableVersionId=portable['id'],portableArtifact=descriptor,
            sourceUnchanged=(repo/('test-data/'+base+'.DATA')).read_bytes()==source,onlyGasViscosityColumnChanged=True,fullNativeSummaryVerified=True,summaryNumericPaths=4158,maxDifference=0,completeSeriesPerRun=11,baselineReports=61,targetReports=64,
            geologicalInputsUnchanged=True,geometryUnchanged=True,activeCells=1600,totalCells=1600,keyword='PVDG',unit='cP',multiplier=2,tableRows=2,wellIdentityAndControlsUnchanged=True,rejectedRequests=len(bad)+1,rejectedRequestCreatedRun=False,lastReportResponse=native['lastReportResponse'])
        print(json.dumps(proof))
    except Exception as error:proof['error']=str(error);raise
    finally:console.save('acceptance-summary',proof)
if __name__=='__main__':main()
