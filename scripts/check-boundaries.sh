#!/usr/bin/env bash
set -euo pipefail
project_dir=$(cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir"
python3 - <<'PY'
import json
from pathlib import Path
import re
import zipfile

root = Path.cwd()
for module, forbidden in [('shared', r'\b(?:mage|net\.minecraft|net\.fabricmc|com\.spider)\.'),
                          ('service/src', r'\b(?:net\.minecraft|net\.fabricmc|com\.spider)\.'),
                          ('addon/src/main/java', r'\bmage\.')]:
    for path in (root / module).rglob('*.java'):
        assert not re.search(forbidden, path.read_text()), f'Runtime boundary violation: {path}'
for path in (root / 'addon/src/main/java').rglob('*.java'):
    text = path.read_text()
    if 'com.spider.' in text:
        assert re.search(r'package mtgcraft\.minecraft\.mtgcard(?:\.mixin)?;', text), path
for row in json.loads((root / 'spikes/mtgcard/dependencies.lock.json').read_text()):
    assert not row['artifact'].startswith('org.mage:'), row['artifact']
for path in [root / '.cache/project/mtgcraft-shared.jar', root / 'addon/build/libs/mtgcraft-addon-0.1.0.jar']:
    with zipfile.ZipFile(path) as archive:
        for name in archive.namelist():
            if not name.endswith('.class'):
                continue
            allowed = ('mtgcraft/protocol/', 'mtgcraft/minecraft/mtgcard/') if 'addon' in path.name else ('mtgcraft/protocol/',)
            assert name.startswith(allowed), f'Unexpected bundled runtime: {name}'
            assert not any(probe in name for probe in ('CustodyProbe', 'CrashProbe', 'CommandProbe', 'LifecycleProbe', 'AdapterCheck')), name
            assert b'mage/' not in archive.read(name), f'Engine dependency in frontend/shared bytecode: {name}'
            if name.startswith('mtgcraft/protocol/'):
                assert not any(package in archive.read(name) for package in (b'net/minecraft/', b'net/fabricmc/', b'com/spider/')), name
        if 'addon' in path.name:
            metadata = json.loads(archive.read('fabric.mod.json'))
            assert metadata['id'] == 'mtgcraft' and metadata['environment'] == 'server'
print('PASS source and packaged boundaries: shared has no game runtime, addon has no engine/probes, MTGCard layouts stay in its integration package')
PY
