#!/usr/bin/env bash
set -euo pipefail
project_dir=$(cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir"
# Reuse the already-approved EULA only for disposable local test servers.
test "$(cat .cache/upstream/mtgcard/fabric/run/eula.txt | tail -1)" = eula=true
probe_dir=$(mktemp -d "$project_dir/.cache/custody-XXXXXX")
cp .cache/upstream/mtgcard/fabric/run/eula.txt "$probe_dir/eula.txt"
cat > "$probe_dir/server.properties" <<'PROPERTIES'
server-ip=127.0.0.1
server-port=0
online-mode=false
level-type=minecraft:flat
generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}
generate-structures=false
view-distance=2
simulation-distance=2
spawn-protection=0
max-tick-time=60000
PROPERTIES
bash .cache/upstream/mtgcard/gradlew -p addon -Dcustody.runDir="$probe_dir" runCustodyProbe --no-daemon --console=plain
rg 'PASS live custody|CUSTODY_GATE_BLOCKED' "$probe_dir/logs/latest.log"
if rg -q CUSTODY_GATE_BLOCKED "$probe_dir/logs/latest.log"; then exit 2; fi
rg -q 'PASS live custody' "$probe_dir/logs/latest.log"
rg -q 'PASS commands/async HTTP' "$probe_dir/logs/latest.log"
if rg -q 'private-fixture-password' "$probe_dir/logs/latest.log"; then exit 1; fi
