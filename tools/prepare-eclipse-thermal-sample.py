"""Keep official steam-drive physics and controls; add explicit temperature outputs and unified binary output."""
import hashlib
import json
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parent.parent
source = Path('C:/ecl/2024.1/e300/data/thermal/THERM3.DATA')
original = source.read_bytes()
assert hashlib.sha256(original).hexdigest() == '1612e17b770a35181057627c4efb6bca3cccea788c53bcade751ccd45782404d'
line_end = b'\r\n' if b'\r\n' in original else b'\n'
unified = b'UNIFOUT' + line_end + line_end
temperature = line_end.join([b'BTEMP', b' 1 1 1 /', b' 9 1 4 /', b'/', b'', b'WTEMP', b' I PNEAR PFAR /', b'', b''])
marker = b'THERMAL' + line_end + line_end
assert original.count(marker) == 1
content = original.replace(marker, marker + unified, 1)
marker_summary = b'ALL' + line_end + line_end
assert content.count(marker_summary) == 1
content = content.replace(marker_summary, marker_summary + temperature, 1)
assert content.replace(unified, b'', 1).replace(temperature, b'', 1) == original
target = root / 'test-data/official-eclipse-therm3-temperature.zip'
info = zipfile.ZipInfo('THERM3.DATA', (2024, 1, 1, 0, 0, 0)); info.compress_type = zipfile.ZIP_DEFLATED
with zipfile.ZipFile(target, 'w') as package:
    package.writestr(info, content)
provenance = {'source': str(source), 'sourceSha256': hashlib.sha256(original).hexdigest(),
              'mainFile': 'THERM3.DATA', 'dataSha256': hashlib.sha256(content).hexdigest(),
              'packageSha256': hashlib.sha256(target.read_bytes()).hexdigest(),
              'physicsModified': False, 'controlsModified': False, 'summaryModified': True,
              'outputChanges': ['UNIFOUT for complete binary artifacts', 'BTEMP cells (1,1,1) and (9,1,4)', 'WTEMP I/PNEAR/PFAR'],
              'scenario': 'Official SPE steam-drive THERM3; steam quality 0.70 at 450 degF; original 3650-day plan.'}
target.with_suffix('.provenance.json').write_text(json.dumps(provenance, indent=2), encoding='utf-8')
print(json.dumps(provenance))
