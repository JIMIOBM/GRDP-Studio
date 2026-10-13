"""Serial native proof: independent single-field proofs for all five water PVT attributes."""
import argparse
import hashlib
import importlib.util
import json
import re
import subprocess
import urllib.request
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', required=True)
    parser.add_argument('--baseline-proof', required=True)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parent.parent
    out = Path(args.evidence).resolve()
    assert not out.is_relative_to(repo) and not out.is_relative_to(Path('C:/GRDP-Data'))
    with urllib.request.urlopen('http://127.0.0.1:5150/api/health', timeout=20) as response:
        health = json.load(response)
    assert health['idle'] and health['activeRunId'] is None
    reference = json.loads(Path(args.baseline_proof).read_text(encoding='utf-8'))
    assert reference['nativeVerified'] and reference['physicalEquivalenceVerified']
    source_path = repo / 'test-data/ECLIPSE_PVTW_WATER.DATA'
    source = source_path.read_bytes()
    assert hashlib.sha256(source).hexdigest() == reference['sourceSha256']
    out.mkdir(parents=True, exist_ok=False)
    spec = importlib.util.spec_from_file_location('oracle', repo / 'tools/inspect-eclipse-compositional-native.py')
    oracle = importlib.util.module_from_spec(spec); spec.loader.exec_module(oracle)
    spec = importlib.util.spec_from_file_location('canonical', repo / 'tools/verify-eclipse-permx.py')
    canonical = importlib.util.module_from_spec(spec); spec.loader.exec_module(canonical)
    old=b"  .0  1.0  3.03E-06  .5  0.0 /"
    assert source.count(old)==1
    originals=['.0','1.0','3.03E-06','.5','0.0']
    options=[('pressure',0,'4000','REFERENCE_PRESSURE','barsa'),('volume',1,'1.02','FORMATION_VOLUME_FACTOR','rm3/sm3'),('compressibility',2,'6.06E-06','COMPRESSIBILITY','1/bar'),('viscosity',3,'1','VISCOSITY','cP'),('viscosibility',4,'1E-06','VISCOSIBILITY','1/bar')]
    cases=[('baseline',source)];proof=dict(nativeVerified=False,manualUiVerified=False,sourceSha256=hashlib.sha256(source).hexdigest(),scenarios={})
    for label,index,value,field,unit in options:
        row=old.split();assert len(row)==6 and row[index].decode()==originals[index]
        # Locate the exact selected token; retain every whitespace and other source byte.
        import re as regex
        tokens=list(regex.finditer(rb'[^\s/]+',old));selected=tokens[index]
        new=old[:selected.start()]+value.encode()+old[selected.end():]
        target=source.replace(old,new,1);assert target.replace(new,old,1)==source
        cases.append((label,target));proof['scenarios'][label]=dict(field=field,unit=unit,originalValue=float(originals[index]),targetValue=float(value),token=value,index=index)
    try:
        for label, data in cases:
            directory = out / label; directory.mkdir()
            (directory / 'PVTW_WATER.DATA').write_bytes(data)
            with (directory / 'launcher.log').open('wb') as log:
                result = subprocess.run(['C:/ecl/macros/eclrun.exe', '-v', '2024.1', 'eclipse', 'PVTW_WATER.DATA'], cwd=directory, stdout=log, stderr=subprocess.STDOUT, timeout=300)
            assert result.returncode == 0
            text = (directory / 'PVTW_WATER.ECLEND').read_text(encoding='ascii')
            counts = {k.lower():int(re.search(r'\b'+k+r'\s+(\d+)', text)[1]) for k in ['Comments','Warnings','Problems','Errors','Bugs']}
            assert all(counts[k] == 0 for k in ['problems','errors','bugs'])
            records = oracle.records(directory / 'PVTW_WATER.INIT')
            static = {}
            for name in ['PERMX','PERMY','PORO','DX','DY','DZ','DEPTH']:
                static[name], = [values for key,kind,values in records if key == name and kind in ['REAL','DOUB']]
                assert len(static[name]) == 225
            sms = {k:v for k,_,v in oracle.records(directory / 'PVTW_WATER.SMSPEC')}
            clock = sms['KEYWORDS'].index('TIME')
            rows = [v for k,_,v in oracle.records(directory / 'PVTW_WATER.UNSMRY') if k == 'PARAMS']
            series = canonical.native_series([dict(keyword=k,objectName=sms['WGNAMES'][i] or None,unit=sms['UNITS'][i] or None,
                points=[dict(timeDays=v[clock],value=v[i]) for v in rows]) for i,k in enumerate(sms['KEYWORDS']) if i != clock])
            assert len(series) == 13 and len(rows) == 24 and rows[-1][clock] == 300
            proof[label] = dict(eclEnd=counts,inputSha256=hashlib.sha256(data).hexdigest(),series=series,arrays=static)
        assert proof['baseline']['series']==reference['baseline']['series']
        def curve(label,keyword):
            selected,=[s for s in proof[label]['series'] if s['keyword']==keyword and s['objectName']=='P'];return selected['points']
        for label,index,value,field,unit in options:
            assert proof[label]['arrays']==proof['baseline']['arrays']==reference['baseline']['arrays']
            assert (out/'baseline/PVTW_WATER.EGRID').read_bytes()==(out/label/'PVTW_WATER.EGRID').read_bytes()
            for keyword in ['WWPR','WWPT','WSTAT']:assert curve(label,keyword)==curve('baseline',keyword)
            oldpoints,newpoints=curve('baseline','WBHP'),curve(label,'WBHP');assert [p['timeDays'] for p in oldpoints]==[p['timeDays'] for p in newpoints]
            changed=sum(abs(a['value']-b['value'])>.01 for a,b in zip(oldpoints,newpoints));assert changed>0
            proof['scenarios'][label].update(pressureChangedReports=changed,lastBaselineBhp=oldpoints[-1]['value'],lastTargetBhp=newpoints[-1]['value'])
        assert proof['viscosity']['series']==reference['target']['series']
        assert source_path.read_bytes()==source
        proof.update(nativeVerified=True,physicalEquivalenceVerified=True,onlySelectedPvtwTokenChanged=True,geologicalInputsUnchanged=True,geometryUnchanged=True,wellControlsUnchanged=True)
        print(json.dumps({k:v for k,v in proof.items() if k not in ['baseline',*[x[0] for x in options]]}))
    except Exception as error:
        proof['error'] = str(error); raise
    finally:
        (out/'native-acceptance.json').write_text(json.dumps(proof,indent=2),encoding='utf-8')


if __name__ == '__main__':
    main()
