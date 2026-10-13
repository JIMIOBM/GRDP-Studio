"""Independent formatted native relative-permeability table proof; retain saturation and capillary pressure and all well controls."""
import argparse,hashlib,importlib.util,json,re,subprocess,urllib.request
from pathlib import Path

def main():
 p=argparse.ArgumentParser();p.add_argument('--evidence',required=True);p.add_argument('--baseline-run',required=True);p.add_argument('--keyword',choices=['SWFN','SOF3'],required=True);args=p.parse_args()
 repo=Path(__file__).resolve().parent.parent;out=Path(args.evidence).resolve();assert not out.is_relative_to(repo) and not out.is_relative_to(Path('C:/GRDP-Data'));out.mkdir(exist_ok=False)
 with urllib.request.urlopen('http://127.0.0.1:5150/api/health') as r:health=json.load(r)
 assert health['idle'] and health['activeRunId'] is None
 source=(repo/'test-data/ECLIPSE_PVDO_OIL.DATA').read_bytes();assert hashlib.sha256(source).hexdigest()=='c128f7a4a20ee1680938af85b47aa76f5739789faa721941123acda605b8aad9'
 reference=json.loads(Path(args.baseline_run).read_text(encoding='utf-8'));assert reference['status']=='SUCCEEDED' and reference['parameters'] is None
 spec=importlib.util.spec_from_file_location('fmt',repo/'tools/inspect-eclipse-restart-native.py');fmt=importlib.util.module_from_spec(spec);spec.loader.exec_module(fmt)
 spec=importlib.util.spec_from_file_location('canonical',repo/'tools/verify-eclipse-permx.py');canonical=importlib.util.module_from_spec(spec);spec.loader.exec_module(canonical)
 start=source.index(args.keyword.encode());end=source.index(b'SGFN' if args.keyword=='SWFN' else b'PVDO',start);table=source[start:end];rows=list(re.finditer(rb'(?m)^([ \t]*)([0-9.]+)([ \t]+)([0-9.]+)([ \t]+)([0-9.]+)',table));assert len(rows)==(13 if args.keyword=='SWFN' else 15)
 replacement=table;edits=[]
 for m in rows:
  for column in ([4] if args.keyword=='SWFN' else [4,6]):
   edits.append((m.start(column),m.end(column),format(float(m[column])*.5,'.17g').encode()))
 for begin,finish,value in sorted(edits,reverse=True):replacement=replacement[:begin]+value+replacement[finish:]
 target=source[:start]+replacement+source[end:];assert target.replace(replacement,table,1)==source
 proof=dict(nativeVerified=False,manualUiVerified=False,sourceSha256=hashlib.sha256(source).hexdigest(),keyword=args.keyword,column='WATER_KR' if args.keyword=='SWFN' else 'BOTH_OIL_KR',unit='fraction',multiplier=.5,tableRows=len(rows))
 try:
  for label,data in [('baseline',source),('target',target)]:
   d=out/label;d.mkdir();(d/'PVDO.DATA').write_bytes(data)
   with (d/'launcher.log').open('wb') as log:run=subprocess.run(['C:/ecl/macros/eclrun.exe','-v','2024.1','eclipse','PVDO.DATA'],cwd=d,stdout=log,stderr=subprocess.STDOUT,timeout=300)
   assert run.returncode==0
   text=(d/'PVDO.ECLEND').read_text(encoding='ascii');counts={k.lower():int(re.search(r'\b'+k+r'\s+(\d+)',text)[1]) for k in ['Comments','Warnings','Problems','Errors','Bugs']};assert all(counts[k]==0 for k in ['problems','errors','bugs'])
   sms={k:v for k,_,v in fmt.blocks(d/'PVDO.FSMSPEC')};clock=sms['KEYWORDS'].index('TIME');points=[v for k,_,v in fmt.blocks(d/'PVDO.FUNSMRY') if k=='PARAMS']
   series=canonical.native_series([dict(keyword=k,objectName=sms['WGNAMES'][i] or None,unit=sms['UNITS'][i] or None,points=[dict(timeDays=v[clock],value=v[i]) for v in points]) for i,k in enumerate(sms['KEYWORDS']) if i!=clock])
   static={}
   for k,t,v in fmt.blocks(d/'PVDO.FINIT'):
    if k in ['PERMX','PERMY','PERMZ','PORO','DX','DY','DZ','DEPTH'] and t in ['REAL','DOUB']:assert k not in static;static[k]=v
   actnum,=[v for k,t,v in fmt.blocks(d/'PVDO.FEGRID') if k=='ACTNUM' and t=='INTE'];assert len(actnum)==13 and sum(n>0 for n in actnum)==10
   assert len(static)==8 and all(len(v)==sum(n>0 for n in actnum) for v in static.values())
   proof[label]=dict(eclEnd=counts,inputSha256=hashlib.sha256(data).hexdigest(),series=series,arrays=static)
  assert proof['baseline']['series']==reference['result']['summary']['series']
  assert proof['baseline']['arrays']==proof['target']['arrays'];assert (out/'baseline/PVDO.FEGRID').read_bytes()==(out/'target/PVDO.FEGRID').read_bytes()
  response=[]
  for b in proof['baseline']['series']:
   a,=[s for s in proof['target']['series'] if s['keyword']==b['keyword'] and s['objectName']==b['objectName'] and s['unit']==b['unit']]
   assert b['points'][-1]['timeDays']==a['points'][-1]['timeDays']==100
   response.append(dict(keyword=b['keyword'],objectName=b['objectName'],unit=b['unit'],baseline=b['points'][-1]['value'],target=a['points'][-1]['value']))
  assert any(abs(s['target']-s['baseline'])>.001 for s in response);assert (repo/'test-data/ECLIPSE_PVDO_OIL.DATA').read_bytes()==source
  proof.update(nativeVerified=True,physicalEquivalenceVerified=True,onlySelectedRelpermColumnsChanged=True,geologicalInputsUnchanged=True,geometryUnchanged=True,wellControlsUnchanged=True,lastReportResponse=response)
  print(json.dumps({k:v for k,v in proof.items() if k not in ['baseline','target']}))
 except Exception as e:proof['error']=str(e);raise
 finally:(out/'native-acceptance.json').write_text(json.dumps(proof,indent=2),encoding='utf-8')
if __name__=='__main__':main()
