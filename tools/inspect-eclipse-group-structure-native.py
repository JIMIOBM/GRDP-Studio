"""Independent native binary proof; all output points, weighted rates and already-weighted totals."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--evidence',required=True);args=parser.parse_args()
    repo=Path(__file__).resolve().parent.parent;out=Path(args.evidence).resolve()
    assert not out.is_relative_to(repo) and not out.is_relative_to(Path('C:/GRDP-Data'));out.mkdir(exist_ok=False)
    spec=importlib.util.spec_from_file_location('binary',repo/'tools/inspect-eclipse-group-rate-native.py')
    binary=importlib.util.module_from_spec(spec);spec.loader.exec_module(binary)
    result={};a2=Path('C:/GRDP-TestOutput/ECLIPSE-P03-GROUP-STRUCTURE-probe-a2');a3=Path('C:/GRDP-TestOutput/ECLIPSE-P03-GROUP-STRUCTURE-probe-a3');a4=Path('C:/GRDP-TestOutput/ECLIPSE-P03-GROUP-STRUCTURE-probe-a4')
    for kind in ['tree','guide','efficiency']:
        result[kind]={}
        source=(repo/f'test-data/ECLIPSE_GROUP_{kind.upper()}.DATA').read_bytes()
        assert (a2/f'{kind}-baseline/CASE.DATA').read_bytes()==source
        for scenario in ['baseline','target','limited']:
            folder=(a4 if scenario=='limited' and kind=='efficiency' else a3 if scenario=='limited' and kind=='guide' else a2)/f'{kind}-{scenario}'
            inp=(folder/'CASE.DATA').read_bytes()
            s=dict(binary.read(folder/'CASE.SMSPEC'));ti=s['KEYWORDS'].index('TIME')
            points=[v for f in sorted(folder.glob('CASE.S[0-9]*')) for k,v in binary.read(f) if k=='PARAMS' and v[ti]>0]
            assert points and len(points)==len({v[ti] for v in points})
            curves=[{'keyword':k,'objectName':o if k[0]!='F' else None,'unit':u,'points':[{'timeDays':v[ti],'value':v[n]} for v in points]} for n,(k,o,u) in enumerate(zip(s['KEYWORDS'],s['WGNAMES'],s['UNITS'])) if k in {'GOPR','GOPT','WOPR','WOPT','WBHP','WEFFG','FOPR','FOPT'}]
            def values(k,o):
                c,=[c for c in curves if c['keyword']==k and c['objectName']==o];return [p['value'] for p in c['points']]
            efficiency=.5 if kind=='efficiency' and scenario=='target' else .8 if kind=='efficiency' else 1
            target=(10000 if kind=='efficiency' else 100000) if scenario=='limited' else 1000 if kind=='tree' and scenario=='target' else 2000
            tolerance=lambda v:max(.0001,abs(v)*1e-6)
            for n in range(len(points)):
                gp=values('GOPR','P')[n];ga=values('GOPR','GA')[n];gb=values('GOPR','GB')[n]
                assert abs(gp-ga*efficiency-gb)<=tolerance(gp)
                assert abs(gp-values('FOPR',None)[n])<=tolerance(gp)
                for group,members in [('GA',[1,2]),('GB',[3,4])]:
                    rate=values('GOPR',group)[n];total=values('GOPT',group)[n]
                    assert abs(rate-sum(values('WOPR',f'PRODUCR{k}')[n] for k in members))<=tolerance(rate)
                    assert abs(total-sum(values('WOPT',f'PRODUCR{k}')[n] for k in members))<=tolerance(total)
                total=values('GOPT','P')[n]
                assert abs(total-values('GOPT','GA')[n]-values('GOPT','GB')[n])<=tolerance(total)
                assert abs(total-values('FOPT',None)[n])<=tolerance(total)
                for k in range(1,5):
                    rate=values('WOPR',f'PRODUCR{k}')[n];bhp=values('WBHP',f'PRODUCR{k}')[n]
                    assert 0<=rate<=20000+tolerance(20000) and bhp>=2499.9
                    assert abs(values('WEFFG',f'PRODUCR{k}')[n]-(efficiency if k<3 else 1))<1e-6
                    if kind=='guide' and scenario=='target':assert abs(rate-target*(3 if k==2 else 1)/6)<=tolerance(rate)
            diag=(folder/'CASE.ECLEND').read_text()
            diagnostics={k.lower():int(re.search(r'^\s*'+k+r'\s+(\d+)',diag,re.M)[1]) for k in ['Comments','Warnings','Problems','Errors','Bugs']}
            # Preserve the actual convergence problem as a separate diagnostic case,
            # never certify it as a full-success constraint reference.
            partial=kind=='efficiency' and scenario=='limited'
            assert diagnostics['problems']==(1 if partial else 0) and diagnostics['errors']==0 and diagnostics['bugs']==0
            attained=sum(abs(v-target)<=tolerance(target) for v in values('GOPR','P'))
            assert attained==len(points) if scenario!='limited' else attained<len(points)
            result[kind][scenario]={'nativeVerified':True,'fullSuccessVerified':not partial,'resultContract':'VALID_PARTIAL' if partial else 'VALID_FULL','manualUiVerified':False,'inputSha256':hashlib.sha256(inp).hexdigest(),'nativeDirectory':str(folder),'target':target,'pointCount':len(points),'targetAttainedCount':attained,'diagnostics':diagnostics,'curves':curves}
    (out/'native-acceptance.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(json.dumps({kind:{s:{k:v for k,v in d.items() if k!='curves'} for s,d in cases.items()} for kind,cases in result.items()}))
if __name__=='__main__':main()
