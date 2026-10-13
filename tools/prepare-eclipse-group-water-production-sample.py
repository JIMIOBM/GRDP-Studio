"""Reproduce the licensed WRAT reference without changing reservoir physics or wells."""
from pathlib import Path
import hashlib
import json
import re

source = Path('C:/ecl/2024.1/eclipse/data/UDT_LC.DATA')
raw = source.read_bytes()
text = raw.decode('utf-8')
summary = re.search(r'(?m)^SUMMARY[^\n]*\n', text)
schedule = re.search(r'(?m)^SCHEDULE[^\n]*\n', text)
assert summary and schedule
text = text[:summary.start()] + "SUMMARY\r\nGWPR\r\n 'G2' /\r\nWWPR\r\n 'P20' 'P24' /\r\nWBHP\r\n 'P20' 'P24' /\r\nWOPR\r\n 'P20' 'P24' /\r\n\r\n" + text[schedule.start():]
text, count = re.subn(r'(?ms)^UDT\r?\n.*?(?=^WELSPECS)', '', text)
assert count == 1
text, count = re.subn(r'(?ms)^UDQ\r?\n.*?(?=^TSTEP)', '', text)
assert count == 1
for well in ['P20', 'P24']:
    text, count = re.subn(r"('%s'\s+'OPEN'\s+)'ORAT'" % well, r"\1'GRUP'", text)
    assert count == 1
position = text.index('TSTEP', text.index('SCHEDULE'))
text = text[:position] + "TUNING\r\n /\r\n /\r\n 2* 50 1* 40 /\r\n\r\nGCONPROD\r\n 'G2' 'WRAT' 1* 2000 /\r\n/\r\n\r\n" + text[position:]
payload = text.encode('utf-8')
probe = Path('C:/GRDP-TestOutput/ECLIPSE-P03-GROUP-WRAT-probe-a3/WRAT/baseline/CASE.DATA')
assert payload == probe.read_bytes(), 'Reference must equal independent native baseline'
sample = Path(__file__).resolve().parent.parent / 'test-data/ECLIPSE_GROUP_WRAT.DATA'
sample.write_bytes(payload)
receipt = {
    'source': str(source), 'sourceSha256': hashlib.sha256(raw).hexdigest(),
    'sourceUnchanged': source.read_bytes() == raw, 'sampleSha256': hashlib.sha256(payload).hexdigest(),
    'originalOfficialModel': False, 'mode': 'WRAT', 'group': 'G2', 'members': ['P20', 'P24'],
    'unit': 'STB/day', 'baselineTarget': 2000, 'positiveTarget': 1000, 'limitedTarget': 20000,
    'bhpLowerLimit': 1000, 'oilRateUpperLimits': {'P20': 8000, 'P24': 4000},
    'changes': [
        'SUMMARY requests GWPR G2 and WWPR/WBHP/WOPR for existing P20/P24.',
        'Remove diagnostic-only UDT/UDQ WUPRICE table; no physical or control dependency.',
        'P20/P24 ORAT -> GRUP; insert G2 WRAT 2000; preserve original oil upper limits and BHP lower limits.',
        'TUNING record 3: LITMAX 25 -> 50 and MXWSIT 8 -> 40. All convergence tolerances unchanged. Initial failed probe retained.',
        'All grid, fluid, initial conditions, existing wells/completions, other controls and 20*10 report days unchanged.'
    ],
    'nativeBaseline': str(probe), 'nativeByteEquivalent': True, 'manualUiVerified': False
}
sample.with_suffix('.provenance.json').write_text(json.dumps(receipt, indent=2), encoding='utf-8')
print(json.dumps(receipt))
