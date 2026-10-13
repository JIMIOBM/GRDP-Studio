"""Serial native candidate for a new independent water producer; preserve existing inputs."""
import argparse
import hashlib
import importlib.util
import json
import re
import subprocess
from pathlib import Path


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--evidence',required=True);args=parser.parse_args()
    repo=Path(__file__).resolve().parent.parent;out=Path(args.evidence).resolve()
    assert not out.is_relative_to(repo) and not out.is_relative_to(Path('C:/GRDP-Data'));out.mkdir(parents=True,exist_ok=False)
    source=(repo/'test-data/ECLIPSE_NEW_WELL_WATER.DATA').read_bytes()
    assert hashlib.sha256(source).hexdigest()=='dbae4c8a9f20f0a9c521f77d63fae3bbbdd3ec22cb8ab962897db5cb05a028c2'
    spec=importlib.util.spec_from_file_location('oracle',repo/'tools/inspect-eclipse-compositional-native.py');oracle=importlib.util.module_from_spec(spec);spec.loader.exec_module(oracle)
    spec=importlib.util.spec_from_file_location('canonical',repo/'tools/verify-eclipse-permx.py');canonical=importlib.util.module_from_spec(spec);spec.loader.exec_module(canonical)
    newline=b'\r\n' if b'\r\n' in source else b'\n'
    lines=[b'-- GRDP controlled new water producer; existing wells and controls retained.',b'WELSPECS',b" 'P2' 'G' 15 1 4000 'WAT' /",b'/',
           b'COMPDAT',b" 'P2' 15 1 1 1 'OPEN' 0 0 0.2 1000 0 /",b'/',b'WCONPROD',b" 'P2' 'OPEN' 'WRAT' 1* 100 3* 3000 /",b'/',b'']
    block=newline.join(lines);assert source.count(newline+b'TSTEP'+newline)==1
    target=source.replace(newline+b'TSTEP'+newline,newline+block+b'TSTEP'+newline,1)
    assert target.replace(block,b'',1)==source
    proof=dict(nativeVerified=False,manualUiVerified=False,sourceSha256=hashlib.sha256(source).hexdigest(),newWell='P2')
    try:
        for label,data in [('baseline',source),('target',target)]:
            directory=out/label;directory.mkdir();(directory/'NEW_WELL.DATA').write_bytes(data)
            with (directory/'launcher.log').open('wb') as log:
                result=subprocess.run(['C:/ecl/macros/eclrun.exe','-v','2024.1','eclipse','NEW_WELL.DATA'],cwd=directory,stdout=log,stderr=subprocess.STDOUT,timeout=300)
            assert result.returncode==0
            text=(directory/'NEW_WELL.ECLEND').read_text(encoding='ascii')
            counts={k.lower():int(re.search(r'\b'+k+r'\s+(\d+)',text)[1]) for k in ['Comments','Warnings','Problems','Errors','Bugs']}
            assert all(counts[k]==0 for k in ['problems','errors','bugs'])
            arrays={k:v for k,_,v in oracle.records(directory/'NEW_WELL.INIT')};names=['PERMX','PERMY','PORO','DX','DY','DZ','DEPTH']
            static={k:arrays[k] for k in names};assert all(len(v)==225 for v in static.values())
            sms={k:v for k,_,v in oracle.records(directory/'NEW_WELL.SMSPEC')};clock=sms['KEYWORDS'].index('TIME');rows=[v for k,_,v in oracle.records(directory/'NEW_WELL.UNSMRY') if k=='PARAMS']
            series=canonical.native_series([dict(keyword=k,objectName=sms['WGNAMES'][i] or None,unit=sms['UNITS'][i] or None,
                    points=[dict(timeDays=v[clock],value=v[i]) for v in rows]) for i,k in enumerate(sms['KEYWORDS']) if i!=clock])
            assert rows[-1][clock]==300
            proof[label]=dict(eclEnd=counts,inputSha256=hashlib.sha256(data).hexdigest(),series=series,arrays=static)
        assert proof['baseline']['arrays']==proof['target']['arrays']
        assert (out/'baseline/NEW_WELL.EGRID').read_bytes()==(out/'target/NEW_WELL.EGRID').read_bytes()
        selected={s['keyword']:s for s in proof['target']['series'] if s['objectName']=='P2'}
        assert {'WWPR','WWPT','WBHP','WSTAT'}.issubset(selected)
        assert any(p['value']>0 for p in selected['WWPR']['points'])
        assert all(-1e-6<=p['value']<=100.001 for p in selected['WWPR']['points'])
        # Official SUMMARY WSTAT 1=producer, 3=shut. Zero WBHP is a real
        # shut-well output, so bind it to explicit state and zero rate/cumulative
        # invariants rather than applying an open-well limit to a shut report.
        by_key={k:{p['timeDays']:p['value'] for p in s['points']} for k,s in selected.items()}
        clocks=set(by_key['WSTAT']);assert all(set(v)==clocks for v in by_key.values())
        previous_total=0;shut_reports=0;limited_reports=0
        for time in sorted(clocks):
            state=by_key['WSTAT'][time];rate=by_key['WWPR'][time];bhp=by_key['WBHP'][time];total=by_key['WWPT'][time]
            assert state in [1,3] and total>=previous_total
            if state==1:
                assert bhp>=2999.99
                if time>0 and rate<99.999 and abs(bhp-3000)<.01:limited_reports+=1
            else:
                assert rate==0 and bhp==0 and total==previous_total;shut_reports+=1
            previous_total=total
        assert limited_reports>0 and shut_reports>0
        prt=(out/'target/NEW_WELL.PRT').read_text(encoding='ascii')
        assert 'THE RESERVOIR PRESSURE IS TOO LOW FOR WELL P2' in prt and re.search(r'WELL P2\s+IS\s+SHUT',prt)
        reference=json.loads(Path('C:/GRDP-TestOutput/ECLIPSE-P06-GEOMETRY-native-a1/native-acceptance.json').read_text(encoding='utf-8'))
        for series in reference['baseline']['series']:
            actual,=[s for s in proof['baseline']['series'] if (s['keyword'],s['objectName'])==(series['keyword'],series['objectName'])]
            assert actual==series
        proof.update(nativeVerified=True,physicalEquivalenceVerified=True,explicitStateVerified=True,limitedReports=limited_reports,shutReports=shut_reports,
                     staticInputsUnchanged=True,geometryUnchanged=True,onlyAddedNewWellBlock=True,
                     maxNewWellWaterRate=max(p['value'] for p in selected['WWPR']['points']),lastNewWellWaterRate=selected['WWPR']['points'][-1]['value'],
                     lastNewWellWaterTotal=selected['WWPT']['points'][-1]['value'],lastNewWellBhp=selected['WBHP']['points'][-1]['value'],
                     sourceUnchanged=(repo/'test-data/ECLIPSE_NEW_WELL_WATER.DATA').read_bytes()==source)
        (out/'new-well-block.txt').write_bytes(block);print(json.dumps({k:v for k,v in proof.items() if k not in ['baseline','target']}))
    except Exception as error:proof['error']=str(error);raise
    finally:(out/'native-acceptance.json').write_text(json.dumps(proof,indent=2),encoding='utf-8')


if __name__=='__main__':main()
