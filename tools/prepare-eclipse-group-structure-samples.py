"""Reproduce three declared group-structure reference inputs and bind them to actual native calculations."""
from pathlib import Path
import hashlib
import json
import re

repo=Path(__file__).resolve().parent.parent
seed=repo/'test-data/ECLIPSE_GROUP_PRODUCTION.DATA'
original=seed.read_bytes()
assert hashlib.sha256(original).hexdigest()=='057e9672f369c0ae5c444b4ff200fb81d862b35bae1cb61ce9933c05e59da463'
official=Path('C:/ecl/2024.1/eclipse/data/BIG3D3P.DATA')
assert hashlib.sha256(official.read_bytes()).hexdigest()=='940a5e181cc9dc65b4d8ff2ae9ffed21547c94a69057a5a5605aeade27fc8a1a'
native=Path('C:/GRDP-TestOutput/ECLIPSE-P03-GROUP-STRUCTURE-probe-a2')
proof=json.loads((native/'native-acceptance.json').read_text())
text=original.decode()
text,count=re.subn(r'(WELLDIMS\s*\n\s*10\s+4\s+)2(\s+6\s*/)',r'\g<1>10\2',text);assert count==1
for k in range(1,5):
    old=f"'PRODUCR{k}' 'G'";assert text.count(old)==1
    text=text.replace(old,f"'PRODUCR{k}' '{'GA' if k<3 else 'GB'}'")
summary="SUMMARY\nGOPR\n 'P' 'GA' 'GB' /\nGOPT\n 'P' 'GA' 'GB' /\nFOPR\nFOPT\n"
for keyword in ['WOPR','WOPT','WBHP','WEFFG']:
    summary+=keyword+"\n 'PRODUCR1' 'PRODUCR2' 'PRODUCR3' 'PRODUCR4' /\n"
text,count=re.subn(r'SUMMARY\s*\n.*?(?=SCHEDULE)',lambda _:summary+'\n',text,flags=re.S);assert count==1
for name,kind in [('TREE','tree'),('GUIDE','guide'),('EFFICIENCY','efficiency')]:
    records="GRUPTREE\n 'GA' 'P' /\n 'GB' 'P' /\n/\nGCONPROD\n 'P' 'ORAT' 2000 /\n 'GA' 'FLD' /\n 'GB' 'FLD' /\n/\n"
    if kind=='guide':
        records+='WGRUPCON\n'+''.join(f" 'PRODUCR{k}' 'YES' 1 'OIL' /\n" for k in range(1,5))+'/\n'
    if kind=='efficiency':records+="GEFAC\n 'GA' 0.8 /\n/\n"
    changed,count=re.subn(r'GCONPROD\s*\n.*?\n/\s*\n',lambda _:records+'\n',text,flags=re.S);assert count==1
    data=changed.encode();baseline=native/f'{kind}-baseline'/'CASE.DATA'
    assert data==baseline.read_bytes()
    result=proof[f'{kind}-baseline'];assert result['nativeVerified'] and result['targetAttainedCount']==result['pointCount']
    sample=repo/f'test-data/ECLIPSE_GROUP_{name}.DATA';sample.write_bytes(data)
    provenance={'source':str(official),'sourceSha256':hashlib.sha256(official.read_bytes()).hexdigest(),'seed':str(seed),'seedSha256':hashlib.sha256(original).hexdigest(),'seedUnchanged':seed.read_bytes()==original,
      'originalOfficialModel':False,'sampleSha256':hashlib.sha256(data).hexdigest(),'kind':kind,'parentGroup':'P','leafGroups':['GA','GB'],'members':[f'PRODUCR{k}' for k in range(1,5)],'nativeBaseline':str(baseline),'nativeByteEquivalent':True,
      'changes':['Seed derivations retained; see ECLIPSE_GROUP_PRODUCTION.provenance.json.','Existing PRODUCR1/2 reassigned GA, PRODUCR3/4 GB, GRUPTREE declares GA/GB under P; no added wells.','Group capacity increased2 to10; geometry/completions/reservoir/fluid/initialization and all other well controls unchanged.','Parent ORAT2000 and explicit leafFLD; complete10*365 days retained.','SUMMARY requests parent/leaf/well oil rates and totals, BHP and efficiency product.',*(['WGRUPCON explicit YES/OIL guide1 for each producer.'] if kind=='guide' else ['GEFAC GA efficiency0.8, no well or other group efficiency.'] if kind=='efficiency' else [])],
      'baselineTarget':2000,'positiveTarget':1000 if kind=='tree' else 3 if kind=='guide' else 0.5,'editedObject':'P' if kind=='tree' else 'PRODUCR2' if kind=='guide' else 'GA','limitedTarget':100000 if kind=='tree' else None,
      'bhpLowerLimit':2500,'oilRateUpperLimit':20000,'unit':'STB/day' if kind=='tree' else 'dimensionless','manualUiVerified':False,
      'limitations':'E100 FIELD native reference; delivered group-structure subset awaits human acceptance. Deep trees, formula guides, multiplied efficiency and extra constraints remain unsupported. High-target partial and convergence-problem cases remain preserved; original warnings retained.'}
    sample.with_suffix('.provenance.json').write_text(json.dumps(provenance,indent=2),encoding='utf-8')
    print(json.dumps({'sample':sample.name,'sha256':provenance['sampleSha256'],'nativeByteEquivalent':True}))
