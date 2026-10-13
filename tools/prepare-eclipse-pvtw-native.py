"""Serial native proof: change only PVTW reference water viscosity, retaining all controls."""
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
    old = b"  .0  1.0  3.03E-06  .5  0.0 /"
    new = b"  .0  1.0  3.03E-06  1  0.0 /"
    assert source.count(old) == 1
    target = source.replace(old, new, 1)
    assert target.replace(new, old, 1) == source
    proof = dict(nativeVerified=False, manualUiVerified=False, sourceSha256=hashlib.sha256(source).hexdigest(), field='VISCOSITY', originalValue=.5, targetValue=1, unit='cP')
    try:
        for label, data in [('baseline', source), ('target', target)]:
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
        assert proof['baseline']['series'] == reference['baseline']['series']
        assert proof['baseline']['arrays'] == proof['target']['arrays'] == reference['baseline']['arrays']
        assert (out/'baseline/PVTW_WATER.EGRID').read_bytes() == (out/'target/PVTW_WATER.EGRID').read_bytes()
        def curve(label, keyword):
            selected, = [s for s in proof[label]['series'] if s['keyword'] == keyword and s['objectName'] == 'P']
            return selected['points']
        for keyword in ['WWPR','WWPT','WSTAT']:
            assert curve('baseline', keyword) == curve('target', keyword)
        assert all(p['value'] == 1 for p in curve('target','WSTAT'))
        old, new = curve('baseline','WBHP'), curve('target','WBHP')
        assert [p['timeDays'] for p in old] == [p['timeDays'] for p in new]
        changed = sum(abs(a['value'] - b['value']) > .01 for a,b in zip(old,new))
        assert changed > 0 and new[-1]['value'] < old[-1]['value']
        assert source_path.read_bytes() == source
        proof.update(nativeVerified=True,physicalEquivalenceVerified=True,onlyWaterViscosityTokenChanged=True,geologicalInputsUnchanged=True,
            geometryUnchanged=True,wellControlsUnchanged=True,pressureChangedReports=changed,
            lastBaselineBhp=old[-1]['value'],lastTargetBhp=new[-1]['value'],lastWaterRate=curve('target','WWPR')[-1]['value'],lastWaterTotal=curve('target','WWPT')[-1]['value'])
        print(json.dumps({k:v for k,v in proof.items() if k not in ['baseline','target']}))
    except Exception as error:
        proof['error'] = str(error); raise
    finally:
        (out/'native-acceptance.json').write_text(json.dumps(proof,indent=2),encoding='utf-8')


if __name__ == '__main__':
    main()
