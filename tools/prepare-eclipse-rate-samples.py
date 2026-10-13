"""Prepare explicit acceptance references; never modify the installed official examples."""
import hashlib
import json
from pathlib import Path
import re


def prepare(mode, source, destination, well):
    original = source.read_bytes()
    text = original.decode('utf-8')
    newline = '\r\n' if b'\r\n' in original else '\n'
    summary = re.search(r'(?m)^SUMMARY\b[^\r\n]*', text)
    schedule = re.search(r'(?m)^SCHEDULE\b[^\r\n]*', text)
    assert summary and schedule and summary.start() < schedule.start()
    keyword = {'WRAT': 'WWPR', 'GRAT': 'WGPR', 'LRAT': 'WLPR'}[mode]
    requests = ['SUMMARY', keyword, " '" + well + "' /", 'WBHP', " '" + well + "' /", 'WOPR', " '" + well + "' /", 'WWPR', " '" + well + "' /", '']
    # Avoid duplicate identities: WRAT already requests WWPR.
    if mode == 'WRAT': requests = requests[:5] + ['WOPR', " '" + well + "' /", '']
    changes = ['Replace SUMMARY requests with unique target flow, WBHP and liquid constituents.']
    text = text[:summary.start()] + newline.join(requests) + text[schedule.start():]
    # Legacy official headers contain metadata after a keyword. Make it an explicit comment.
    text = re.sub(r'(?m)^(TSTEP)[ \t]+(METRIC[^\r\n]*)', r'\1    -- \2', text)
    if mode == 'GRAT': changes.append('Annotate the legacy TSTEP METRIC timestamp as a comment; time steps unchanged.')
    if mode == 'LRAT':
        old = "'P1'  'OPEN'  'BHP' 5*  3000 /"
        assert text.count(old) == 1
        text = text.replace(old, "'P1' 'OPEN' 'LRAT' 3* 5000 1* 3000 /")
        changes.append('P1 BHP production becomes LRAT 5000 STB/day; retain original 3000 psia BHP limit. Reservoir, fluid, geometry, injection and time steps unchanged.')
    payload = text.encode('utf-8')
    destination.parent.mkdir(exist_ok=True, parents=True)
    if destination.exists(): assert destination.read_bytes() == payload, 'Existing sample differs; refusing overwrite'
    else: destination.write_bytes(payload)
    provenance = {'officialSource': str(source), 'officialSha256': hashlib.sha256(original).hexdigest(),
                  'sampleSha256': hashlib.sha256(payload).hexdigest(), 'controlMode': mode, 'well': well,
                  'changes': changes, 'physicsAndScheduleUnchanged': mode != 'LRAT'}
    destination.with_suffix('.provenance.json').write_text(json.dumps(provenance, indent=2), encoding='utf-8')
    assert source.read_bytes() == original
    print(destination, provenance['sampleSha256'])


if __name__ == '__main__':
    repo = Path(__file__).resolve().parent.parent
    official = Path('C:/ecl/2024.1/eclipse/data')
    for mode, filename, well in [('WRAT', 'TRACERDISPERS.DATA', 'P'), ('GRAT', 'GASCOND.DATA', 'PRODUCER'), ('LRAT', 'BASE_WATER.DATA', 'P1')]:
        prepare(mode, official / filename, repo / 'test-data' / ('ECLIPSE_' + mode + '.DATA'), well)
