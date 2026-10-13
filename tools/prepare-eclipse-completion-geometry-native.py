"""Native COMPDAT diameter/KH/skin physics and explicit default equivalence, serial only."""
import argparse
import hashlib
import importlib.util
import json
import re
import subprocess
from pathlib import Path


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--evidence',required=True);parser.add_argument('--reference-native',required=True);args=parser.parse_args()
    repo=Path(__file__).resolve().parent.parent;out=Path(args.evidence).resolve()
    assert not out.is_relative_to(repo) and not out.is_relative_to(Path('C:/GRDP-Data'));out.mkdir(parents=True,exist_ok=False)
    source=(repo/'test-data/ECLIPSE_COMPLETION_GEOMETRY.DATA').read_bytes()
    assert hashlib.sha256(source).hexdigest()=='b29df227965f5266b60bfc8701ea3c021114905371b87d7eb28c4b73ca822be9'
    reference=json.loads((Path(args.reference_native)/'native-acceptance.json').read_text(encoding='utf-8'));assert reference['nativeVerified']
    spec=importlib.util.spec_from_file_location('oracle',repo/'tools/inspect-eclipse-compositional-native.py');oracle=importlib.util.module_from_spec(spec);spec.loader.exec_module(oracle)
    spec=importlib.util.spec_from_file_location('canonical',repo/'tools/verify-eclipse-permx.py');canonical=importlib.util.module_from_spec(spec);spec.loader.exec_module(canonical)
    original_row=b"'P       '   1   1   1   1 'OPEN'   0  .0   1.0  1000  0 /"
    assert source.count(original_row)==1
    proof=dict(nativeVerified=False,manualUiVerified=False,physicalEquivalenceVerified=False)
    for label,row in [('baseline',original_row),('diameter',original_row.replace(b'1.0  1000',b'0.2  1000')),
                      ('kh',original_row.replace(b'1000  0',b'500  0')),('skin',original_row.replace(b'1000  0 /',b'1000  2 /'))]:
        directory=out/label;directory.mkdir();data=source.replace(original_row,row);assert data.replace(row,original_row)==source
        (directory/'GEOMETRY.DATA').write_bytes(data)
        with (directory/'launcher.log').open('wb') as log:
            result=subprocess.run(['C:/ecl/macros/eclrun.exe','-v','2024.1','eclipse','GEOMETRY.DATA'],cwd=directory,stdout=log,stderr=subprocess.STDOUT,timeout=300)
        assert result.returncode==0
        text=(directory/'GEOMETRY.ECLEND').read_text(encoding='ascii')
        counts={k.lower():int(re.search(r'\b'+k+r'\s+(\d+)',text)[1]) for k in ['Comments','Warnings','Problems','Errors','Bugs']}
        assert all(counts[k]==0 for k in ['problems','errors','bugs'])
        arrays={k:v for k,_,v in oracle.records(directory/'GEOMETRY.INIT')}
        for name,values in reference['baseline']['arrays'].items():assert arrays[name]==values
        sms={k:v for k,_,v in oracle.records(directory/'GEOMETRY.SMSPEC')};clock=sms['KEYWORDS'].index('TIME')
        rows=[v for k,_,v in oracle.records(directory/'GEOMETRY.UNSMRY') if k=='PARAMS']
        series=canonical.native_series([dict(keyword=key,objectName=sms['WGNAMES'][i] or None,unit=sms['UNITS'][i] or None,
                         points=[dict(timeDays=v[clock],value=v[i]) for v in rows]) for i,key in enumerate(sms['KEYWORDS']) if i!=clock])
        assert len(series)==4 and len(rows)==24 and rows[-1][clock]==300
        if label=='baseline':
            assert series==reference['baseline']['series'];proof['physicalEquivalenceVerified']=True
        else:
            assert (directory/'GEOMETRY.EGRID').read_bytes()==(out/'baseline/GEOMETRY.EGRID').read_bytes()
            for key in ['WWPR','WWPT']:
                actual,=[s for s in series if s['keyword']==key];expected,=[s for s in proof['baseline']['series'] if s['keyword']==key]
                assert actual==expected
            actual,=[s for s in series if s['keyword']=='WBHP'];expected,=[s for s in proof['baseline']['series'] if s['keyword']=='WBHP']
            assert abs(actual['points'][-1]['value']-expected['points'][-1]['value'])>0.1
        proof[label]=dict(eclEnd=counts,inputSha256=hashlib.sha256(data).hexdigest(),series=series)
    proof.update(nativeVerified=True,gridInputsUnchanged=True,sourceUnchanged=(repo/'test-data/ECLIPSE_COMPLETION_GEOMETRY.DATA').read_bytes()==source)
    (out/'native-acceptance.json').write_text(json.dumps(proof,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in proof.items() if k not in ['baseline','diameter','kh','skin']}))


if __name__=='__main__':main()
