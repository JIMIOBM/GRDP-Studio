"""Reproduce a native-qualified, explicit two-well three-date ORAT reference without altering physics."""
from datetime import date,timedelta
import hashlib,json,re
from pathlib import Path

repo=Path(__file__).resolve().parent.parent
seed=repo/'test-data/ECLIPSE_GROUP_PRODUCTION.DATA';original=seed.read_bytes();text=seed.read_text(encoding='utf-8')
text,count=re.subn(r"'GRUP' 20000","'ORAT' 500",text);assert count==4
text,count=re.subn(r"(?m)^GCONPROD\n 'G' 'ORAT' 60000 /\n/\n",'',text);assert count==1
start=date(1982,10,19);months=['JAN','FEB','MAR','APR','MAY','JUN','JUL','AUG','SEP','OCT','NOV','DEC'];schedule=''
for n in range(1,11):
    d=start+timedelta(days=n*365);schedule+=f"DATES\n {d.day} '{months[d.month-1]}' {d.year} /\n/\n"
    if n<=3:schedule+='WCONPROD\n'+''.join(f" 'PRODUCR{k}' 'OPEN' 'ORAT' 500 4* 2500 /\n" for k in [1,2])+'/\n'
text,count=re.subn(r'(?m)^TSTEP\n  10\*365\n\n  /\n',lambda _:schedule,text);assert count==1
native=Path('C:/GRDP-TestOutput/ECLIPSE-P03-CONTROL-PLAN-probe-a1');proof=json.loads((native/'native-acceptance.json').read_text())
data=text.replace('\n','\r\n').encode();assert data==(native/'baseline/CASE.DATA').read_bytes()
assert all(p['nativeVerified'] and all(p['diagnostics'][k]==0 for k in ['problems','errors','bugs']) for p in proof.values())
assert proof['baseline']['targetCounts']==[20,20] and proof['target']['targetCounts']==[20,20]
sample=repo/'test-data/ECLIPSE_CONTROL_PLAN.DATA';sample.write_bytes(data)
declarations=[{'date':(start+timedelta(days=n*365)).isoformat(),'timeDays':n*365,'wells':['PRODUCR1','PRODUCR2'],'originalOilRate':500,'targetOilRate':target} for n,target in enumerate([250,350,450],1)]
provenance={'seed':str(seed),'seedSha256':hashlib.sha256(original).hexdigest(),'seedUnchanged':seed.read_bytes()==original,'sampleSha256':hashlib.sha256(data).hexdigest(),'originalOfficialModel':False,
 'changes':['Seed derivations retained; see ECLIPSE_GROUP_PRODUCTION.provenance.json.','Four existing producers changed from GRUP to independent ORAT500; direct GCONPROD removed.','10*365 report days expressed as corresponding midnight DATES; total3650 days retained, including leap-year calendar shifts.','PRODUCR1/2 explicitly restate ORAT500 after first three report dates.','No change to reservoir physics, geometry, completions, injector controls or BHP2500.'],
 'startDate':start.isoformat(),'endTimeDays':3650,'positiveEdits':declarations,'limitedTargetOilRate':100000,'bhpLowerLimit':2500,'unit':'STB/day','nativeDirectory':str(native),'nativeByteEquivalent':True,'manualUiVerified':False,
 'limitations':'Native-qualified E100 FIELD reference; platform control-plan delivery still in progress. Other modes, injection, INCLUDE, mixed or fractional time and dynamic work controls require separate qualification.'}
sample.with_suffix('.provenance.json').write_text(json.dumps(provenance,indent=2),encoding='utf-8');print(json.dumps({'sample':sample.name,'sha256':provenance['sampleSha256']}))
