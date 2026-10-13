"""Package the byte-identical SLB 2024.1 nine-component gas-condensate example."""
import hashlib
import json
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parent.parent
source = Path('C:/ecl/2024.1/e300/data/SPE3_GASCOND.DATA')
content = source.read_bytes()
digest = hashlib.sha256(content).hexdigest()
assert digest == '30f36c71726bae3e1c3da3820eefa60212f0628a5c4c1a2163f42c51d75d7feb'
target = root / 'test-data/official-eclipse-spe3-gascond.zip'
info = zipfile.ZipInfo(source.name, (2024, 1, 1, 0, 0, 0))
info.compress_type = zipfile.ZIP_DEFLATED
with zipfile.ZipFile(target, 'w') as package:
    package.writestr(info, content)
provenance = {'source': str(source), 'sourceSha256': digest, 'sizeBytes': len(content),
              'mainFile': source.name, 'packageSha256': hashlib.sha256(target.read_bytes()).hexdigest(),
              'physicsModified': False, 'controlsModified': False, 'summaryModified': False,
              'scenario': 'SLB ECLIPSE 300 SPE3 nine-component gas-condensate cycling; original native sample.'}
target.with_suffix('.provenance.json').write_text(json.dumps(provenance, indent=2), encoding='utf-8')
print(json.dumps(provenance))
