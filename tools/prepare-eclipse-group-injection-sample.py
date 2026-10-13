"""Reproduce the native-proven water group demonstrator without changing source physics."""
from pathlib import Path
import hashlib
import json
import re

repo = Path(__file__).resolve().parent.parent
source = Path('C:/ecl/2024.1/eclipse/data/BIG3D3P.DATA')
raw = source.read_bytes()
text = raw.decode('utf-8')
nl = '\r\n' if '\r\n' in text else '\n'
summary = re.search(r'(?m)^SUMMARY[^\r\n]*\r?\n', text)
schedule = re.search(r'(?m)^SCHEDULE[^\r\n]*\r?\n', text)
assert summary and schedule
text = text[:summary.start()] + "SUMMARY\nGWIR\n 'WI' /\nWWIR\n 'INJECTR5' 'INJECTR6' /\nWBHP\n 'INJECTR5' 'INJECTR6' /\n\n".replace('\n', nl) + text[schedule.start():]
assert text.count('10    4    2    6 /') == 1
text = text.replace('10    4    2    6 /', '10    4    3    6 /')
for n in (5, 6):
    text, count = re.subn(r"('INJECTR%d'\s+)'I'" % n, r"\1'WI'", text)
    assert count == 1
    text, count = re.subn(r"('INJECTR%d'\s+'WAT'\s+'OPEN'\s+)'RATE'\s+22500\s+/" % n, r"\1'GRUP' 22500 1* 6000 /", text)
    assert count == 1
tstep = re.search(r'(?m)^TSTEP[ \t]*\r?$', text)
assert tstep
text = text[:tstep.start()] + "GCONINJE\n 'WI' 'WATER' 'RATE' 2 /\n/\n\n".replace('\n', nl) + text[tstep.start():]
sample = repo / 'test-data/ECLIPSE_GROUP_INJECTION.DATA'
payload = text.encode('utf-8')
sample.write_bytes(payload)
receipt = dict(source=str(source), sha256=hashlib.sha256(raw).hexdigest(), sourceUnchanged=source.read_bytes() == raw,
    sample=sample.name, sampleSha256=hashlib.sha256(payload).hexdigest(), originalOfficialModel=False,
    changes=['SUMMARY requests GWIR WI, WWIR and WBHP for existing INJECTR5/6.',
        'WELLDIMS group capacity 2->3 and existing water injectors assigned to dedicated direct WI group.',
        'Water injectors RATE->GRUP; original surface limit 22500 preserved; explicit BHP upper limit 6000 psia replaces default 100000.',
        'Insert GCONINJE WI WATER RATE 2 STB/day. All other reservoir/fluid physics, wells, completions and time plan preserved.'],
    baselineTarget=2, positiveTarget=1, pressureLimitedTarget=10, bhpUpperLimit=6000,
    limitations='Native initial capacity is only about 2.345 STB/day at this pressure. These are demonstration targets, not field operating recommendations. Original PVT/endpoints/convergence warnings remain.')
sample.with_suffix('.provenance.json').write_text(json.dumps(receipt, indent=2), encoding='utf-8')
print(json.dumps(receipt))
