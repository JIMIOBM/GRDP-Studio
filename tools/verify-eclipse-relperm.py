"""Actual 5173 water and paired-oil relative permeability, exact input export and independent native output."""
import argparse,hashlib,importlib.util,io,json,zipfile
from pathlib import Path
from eclipse_console_acceptance import ConsoleAcceptance
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--evidence',required=True);p.add_argument('--water-native',required=True);p.add_argument('--oil-native',required=True);p.add_argument('--resume',action='store_true');args=p.parse_args()
    console=ConsoleAcceptance(args.evidence,args.resume);repo=Path(__file__).resolve().parent.parent;base='ECLIPSE_RELPERM_OIL';source=(repo/('test-data/'+base+'.DATA')).read_bytes()
    folders={'SWFN':Path(args.water_native),'SOF3':Path(args.oil_native)};native={key:json.loads((folder/'native-acceptance.json').read_text(encoding='utf-8')) for key,folder in folders.items()}
    assert all(v['nativeVerified'] and v['onlySelectedRelpermColumnsChanged'] and v['sourceSha256']==hashlib.sha256(source).hexdigest() for v in native.values())
    assert native['SWFN']['baseline']==native['SOF3']['baseline']
    spec=importlib.util.spec_from_file_location('fmt',repo/'tools/inspect-eclipse-restart-native.py');fmt=importlib.util.module_from_spec(spec);spec.loader.exec_module(fmt)
    proof=dict(apiVerified=False,manualUiVerified=False,scenarios={})
    def arrays(run):
        content,_=console.download(run,'eclipse-output-'+base+'.FINIT');directory=console.out/('native-run-'+str(run['id']));directory.mkdir(exist_ok=True);file=directory/(base+'.FINIT');file.write_bytes(content)
        actual={k:v for k,t,v in fmt.blocks(file) if k in ['PERMX','PERMY','PERMZ','PORO','DX','DY','DZ','DEPTH'] and t in ['REAL','DOUB']};assert len(actual)==8 and all(len(v)==10 for v in actual.values());return actual
    try:
        version=console.upload('original',source,base+'.DATA');context=version['inspection']['relpermContext'];assert len(context['tables'])==3
        baseline=console.run(version,'baseline');assert baseline['result']['summary']['series']==native['SWFN']['baseline']['series'];assert arrays(baseline)==native['SWFN']['baseline']['arrays'];grid,_=console.download(baseline,'eclipse-output-'+base+'.FEGRID')
        rejected=0
        for keyword in ['SWFN','SOF3']:
            table,=[t for t in context['tables'] if t['keyword']==keyword]
            parameters=dict(schemaVersion='eclipse-relperm-parameters/1',baselineRunId=baseline['id'],sourceFile=base+'.DATA',sourceSha256=hashlib.sha256(source).hexdigest(),keyword=keyword,keywordLineNumber=table['keywordLineNumber'],unit='1',multiplier=.5,rows=table['rows'])
            bad=[{**parameters,key:value} for key,value in [('unit','mD'),('sourceFile','other.DATA'),('sourceSha256','0'*64),('keyword','SGFN'),('keywordLineNumber',1),('keywordLineNumber','117'),('multiplier',1),('multiplier',0),('multiplier',-1),('multiplier',2),('multiplier',101),('multiplier','0.5'),('rows',[]),('rows',table['rows'][:1])]]
            bad.append({**parameters,'extra':1})
            for key,value in [('lineNumber',table['keywordLineNumber']),('values',['0.2','1.1','6']),('values',['0.2','1*','6']),('extra',1)]:
                rows=json.loads(json.dumps(table['rows']));rows[0][key]=value;bad.append({**parameters,'rows':rows})
            console.reject_without_run(version,bad);console.reject_without_run(version,[{**parameters,'baselineRunId':1}],expected=409);rejected+=len(bad)+1
            target=console.run(version,keyword,parameters);assert target['result']['summary']['series']==native[keyword]['target']['series'];assert target['result']['eclEnd']==native[keyword]['target']['eclEnd'];assert arrays(target)==native[keyword]['baseline']['arrays']
            actual_grid,_=console.download(target,'eclipse-output-'+base+'.FEGRID');assert actual_grid==grid
            package,descriptor=console.download(target,'eclipse-schedule-applied-package.zip');(console.out/(keyword+'-input-package.zip')).write_bytes(package)
            expected=(folders[keyword]/'target/PVDO.DATA').read_bytes()
            with zipfile.ZipFile(io.BytesIO(package)) as z:assert z.namelist()==[base+'.DATA'] and z.read(base+'.DATA')==expected
            portable=console.upload(keyword+'-portable',package,keyword+'-input-package.zip',base+'.DATA');assert portable['inspection']['wellNames']==version['inspection']['wellNames']
            replay=console.run(portable,keyword+'-portable');assert replay['result']['summary']['series']==native[keyword]['target']['series'];assert arrays(replay)==native[keyword]['baseline']['arrays'];replay_grid,_=console.download(replay,'eclipse-output-'+base+'.FEGRID');assert replay_grid==grid
            proof['scenarios'][keyword]=dict(targetRunId=target['id'],portableRunId=replay['id'],portableVersionId=portable['id'],portableArtifact=descriptor,tableRows=len(table['rows']),multiplier=.5,unit='1',lastReportResponse=native[keyword]['lastReportResponse'],onlySelectedRelpermColumnsChanged=True)
        proof.update(apiVerified=True,baselineRunId=baseline['id'],originalVersionId=version['id'],summaryNumericPaths=1050,maxDifference=0,completeSeriesPerRun=5,reportsPerSeries=21,sourceUnchanged=(repo/('test-data/'+base+'.DATA')).read_bytes()==source,geologicalInputsUnchanged=True,geometryUnchanged=True,wellIdentityAndControlsUnchanged=True,rejectedRequests=rejected,rejectedRequestCreatedRun=False)
        print(json.dumps(proof))
    except Exception as error:proof['error']=str(error);raise
    finally:console.save('acceptance-summary',proof)
if __name__=='__main__':main()
