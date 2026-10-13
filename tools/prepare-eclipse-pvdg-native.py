"""Independent official gas-water PVDG viscosity proof, keeping pressure/Bg, relperm, grid and well controls."""
import argparse,hashlib,importlib.util,json,re,subprocess,urllib.request
from pathlib import Path
def main():
    p=argparse.ArgumentParser();p.add_argument('--evidence',required=True);args=p.parse_args();repo=Path(__file__).resolve().parent.parent;out=Path(args.evidence).resolve();assert not out.is_relative_to(repo) and not out.is_relative_to(Path('C:/GRDP-Data'));out.mkdir(exist_ok=False)
    with urllib.request.urlopen('http://127.0.0.1:5150/api/health') as r:health=json.load(r)
    assert health['idle'] and health['activeRunId'] is None
    source=(repo/'test-data/ECLIPSE_PVDG_GASWATER.DATA').read_bytes();provenance=json.loads((repo/'test-data/ECLIPSE_PVDG_GASWATER.provenance.json').read_text(encoding='utf-8'));assert provenance['sampleSha256']==hashlib.sha256(source).hexdigest()
    spec=importlib.util.spec_from_file_location('binary',repo/'tools/inspect-eclipse-compositional-native.py');binary=importlib.util.module_from_spec(spec);spec.loader.exec_module(binary)
    spec=importlib.util.spec_from_file_location('canonical',repo/'tools/verify-eclipse-permx.py');canonical=importlib.util.module_from_spec(spec);spec.loader.exec_module(canonical)
    start=source.index(b'PVDG');end=source.index(b'ROCK',start);table=source[start:end];matches=list(re.finditer(rb'(?m)^([ \t]*)([0-9.]+)([ \t]+)([0-9.]+)([ \t]+)([0-9.]+)',table));assert len(matches)==2
    replacement=table
    for m in reversed(matches):assert float(m[6])==2;replacement=replacement[:m.start(6)]+b'4'+replacement[m.end(6):]
    target=source[:start]+replacement+source[end:];assert target.replace(replacement,table,1)==source
    proof=dict(nativeVerified=False,manualUiVerified=False,sourceSha256=hashlib.sha256(source).hexdigest(),keyword='PVDG',column='VISCOSITY',unit='cP',multiplier=2)
    try:
        for label,data in [('baseline',source),('target',target)]:
            d=out/label;d.mkdir();(d/'PVDG.DATA').write_bytes(data)
            with (d/'launcher.log').open('wb') as log:run=subprocess.run(['C:/ecl/macros/eclrun.exe','-v','2024.1','eclipse','PVDG.DATA'],cwd=d,stdout=log,stderr=subprocess.STDOUT,timeout=600)
            assert run.returncode==0
            text=(d/'PVDG.ECLEND').read_text(encoding='ascii');counts={k.lower():int(re.search(r'\b'+k+r'\s+(\d+)',text)[1]) for k in ['Comments','Warnings','Problems','Errors','Bugs']};assert all(counts[k]==0 for k in ['problems','errors','bugs'])
            sms={k:v for k,_,v in binary.records(d/'PVDG.SMSPEC')};clock=sms['KEYWORDS'].index('TIME')
            summaries=[d/'PVDG.UNSMRY'] if (d/'PVDG.UNSMRY').exists() else sorted(f for f in d.iterdir() if re.fullmatch(r'PVDG\.S[0-9]{4,5}',f.name))
            assert summaries;points=[v for file in summaries for k,_,v in binary.records(file) if k=='PARAMS']
            series=canonical.native_series([dict(keyword=k,objectName=sms['WGNAMES'][i] or None,unit=sms['UNITS'][i] or None,points=[dict(timeDays=v[clock],value=v[i]) for v in points]) for i,k in enumerate(sms['KEYWORDS']) if i!=clock])
            arrays={k:v for k,t,v in binary.records(d/'PVDG.INIT') if k in ['PERMX','PERMY','PERMZ','PORO','DX','DY','DZ','DEPTH'] and t in ['REAL','DOUB']};assert len(arrays)==8 and all(len(v)==1600 for v in arrays.values())
            proof[label]=dict(eclEnd=counts,inputSha256=hashlib.sha256(data).hexdigest(),series=series,arrays=arrays)
        assert proof['baseline']['arrays']==proof['target']['arrays'];assert (out/'baseline/PVDG.EGRID').read_bytes()==(out/'target/PVDG.EGRID').read_bytes()
        response=[]
        for b in proof['baseline']['series']:
            a,=[s for s in proof['target']['series'] if (s['keyword'],s['objectName'],s['unit'])==(b['keyword'],b['objectName'],b['unit'])];assert b['points'][-1]['timeDays']==a['points'][-1]['timeDays']==6000
            old={v['timeDays']:v['value'] for v in b['points']};new={v['timeDays']:v['value'] for v in a['points']};assert len(old)==len(b['points']) and len(new)==len(a['points'])
            common=old.keys()&new.keys();unmatched=old.keys()^new.keys()
            response.append(dict(keyword=b['keyword'],objectName=b['objectName'],unit=b['unit'],baseline=b['points'][-1]['value'],target=a['points'][-1]['value'],baselinePoints=len(old),targetPoints=len(new),alignedPoints=len(common),unmatchedPoints=len(unmatched),changedAlignedPoints=sum(abs(old[t]-new[t])>max(1e-6,abs(old[t])*1e-6) for t in common)))
        assert any(s['changedAlignedPoints']>0 for s in response);assert (repo/'test-data/ECLIPSE_PVDG_GASWATER.DATA').read_bytes()==source
        proof.update(nativeVerified=True,onlyGasViscosityColumnChanged=True,geologicalInputsUnchanged=True,geometryUnchanged=True,wellControlsUnchanged=True,lastReportResponse=response)
        print(json.dumps({k:v for k,v in proof.items() if k not in ['baseline','target']}))
    except Exception as e:proof['error']=str(e);raise
    finally:(out/'native-acceptance.json').write_text(json.dumps(proof,indent=2),encoding='utf-8')
if __name__=='__main__':main()
