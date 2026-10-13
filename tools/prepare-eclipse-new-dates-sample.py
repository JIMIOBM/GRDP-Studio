"""Derive an explicit calendar sample; never change the installed official model."""
from pathlib import Path
import datetime as dt
import hashlib
import json
import re

repo = Path(__file__).resolve().parent.parent
source = Path('C:/ecl/2024.1/eclipse/data/BASE_WATER.DATA')
base = repo / 'test-data/ECLIPSE_BASE_WATER_BHP.DATA'
sample = repo / 'test-data/ECLIPSE_NEW_DATES.DATA'
raw = base.read_bytes()
text = raw.decode('utf-8')
assert len(re.findall(r'(?m)^TSTEP\s*$', text)) == 2
# Replace the two 10*5 blocks by the same twenty original report dates, grouped into two adjacent-date windows.
dates = [dt.date(1996,1,1)+dt.timedelta(days=5*n) for n in range(1,21)]
blocks = []
for half in (dates[:10],dates[10:]):
    blocks.append(''.join(f"DATES\n {d.day} '{d.strftime('%b').upper()}' {d.year} /\n/\n" for d in half))
counter = iter(blocks)
text, count = re.subn(r'(?m)^TSTEP[ \t]*\r?\n10\*5[ \t]*/', lambda m: next(counter), text)
assert count == 2
text = text.replace('SUMMARY', 'SUMMARY\n\nDATE',1)
# Enlarge one pair of adjacent reports to leave space for a practical ten-day maintenance outage.
# Delete only the 10,15,20-day report markers; the 5 and 25-day boundaries and all well controls remain.
for d in dates[1:4]:
    text = text.replace(f"DATES\n {d.day} '{d.strftime('%b').upper()}' {d.year} /\n/\n", '')
payload = text.encode('utf-8')
sample.write_bytes(payload)
receipt = {'source':str(source), 'sourceSha256':hashlib.sha256(source.read_bytes()).hexdigest(), 'baseSample':base.name,
    'baseSha256':hashlib.sha256(raw).hexdigest(),'sample':sample.name,'sampleSha256':hashlib.sha256(payload).hexdigest(),
    'physicsAndWellControlsUnchanged':True,'startDate':'1996-01-01','endDate':'1996-04-10',
    'changes':['SUMMARY DATE added for real calendar series.', 'Two 10*5 TSTEP blocks converted to equivalent midnight DATES; three report markers at 10/15/20 days omitted to create 5-25 day window. Total simulation length remains 100 days. No solver rates or limits changed.'],
    'scenario':{'well':'P1','beforeDate':'1996-01-06','afterDate':'1996-01-26','shutDate':'1996-01-11','openDate':'1996-01-21'}}
sample.with_suffix('.provenance.json').write_text(json.dumps(receipt,indent=2),encoding='utf-8')
print(json.dumps(receipt))
