"""Explicit four-producer group reference derived from installed BIG3D3P, with provenance."""
from pathlib import Path
import hashlib
import json
import re

repo = Path(__file__).resolve().parent.parent
source = Path('C:/ecl/2024.1/eclipse/data/BIG3D3P.DATA')
raw = source.read_bytes()
text = raw.decode('utf-8')
summary = re.search(r'(?m)^SUMMARY[^\r\n]*\r?\n', text)
schedule = re.search(r'(?m)^SCHEDULE[^\r\n]*\r?\n', text)
assert summary and schedule
nl = '\r\n' if '\r\n' in text else '\n'
new_summary = "SUMMARY\nGOPR\n 'G' /\nFOPR\nWOPR\n 'PRODUCR1' 'PRODUCR2' 'PRODUCR3' 'PRODUCR4' /\nWBHP\n 'PRODUCR1' 'PRODUCR2' 'PRODUCR3' 'PRODUCR4' /\n\n".replace('\n', nl)
text = text[:summary.start()] + new_summary + text[schedule.start():]
for n in range(1,5):
    text,count = re.subn(r"('PRODUCR%d'\s+'OPEN'\s+)'ORAT'" % n, r"\1'GRUP'", text)
    assert count == 1
injection = re.search(r'(?m)^WCONINJE[ \t]*\r?$', text)
assert injection
text = text[:injection.start()] + "GCONPROD\n 'G' 'ORAT' 60000 /\n/\n\n".replace('\n', nl) + text[injection.start():]
sample = repo/'test-data/ECLIPSE_GROUP_PRODUCTION.DATA'
payload = text.encode('utf-8'); sample.write_bytes(payload)
receipt = {'source':str(source),'sourceSha256':hashlib.sha256(raw).hexdigest(),'sourceUnchanged':source.read_bytes()==raw,
    'sample':sample.name,'sampleSha256':hashlib.sha256(payload).hexdigest(),'originalOfficialModel':False,
    'changes':['SUMMARY requests GOPR G, FOPR, WOPR and WBHP for the four existing producers.',
        'Four existing OPEN ORAT producer modes changed to GRUP; individual oil limits 20000 STB/day and BHP limits 2500 psia preserved.',
        'Initial GCONPROD G ORAT 60000 STB/day inserted. Well specifications, completions, reservoir/fluid physics, injectors and TSTEP preserved.'],
    'group':'G','members':['PRODUCR1','PRODUCR2','PRODUCR3','PRODUCR4'],'baselineTarget':60000,'positiveTarget':30000,'pressureLimitedTarget':80000,
    'warnings':'Installed reference has endpoint/PVT/default injection BHP and convergence warnings; keep all diagnostics.'}
sample.with_suffix('.provenance.json').write_text(json.dumps(receipt,indent=2),encoding='utf-8')
print(json.dumps(receipt))
