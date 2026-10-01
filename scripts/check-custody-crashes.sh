#!/usr/bin/env bash
set -euo pipefail
project_dir=$(cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir"
test "$(tail -1 .cache/upstream/mtgcard/fabric/run/eula.txt)" = eula=true
for point in before_intent after_intent after_marked after_removed after_reserved before_return_pending after_return_pending before_claim after_claim_intent after_claim_insert after_claim_saved after_returned; do
    probe_dir=$(mktemp -d "$project_dir/.cache/crash-$point-XXXXXX")
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
    if bash .cache/upstream/mtgcard/gradlew -p addon -Dcustody.runDir="$probe_dir" -Dcustody.fault="$point" runCustodyProbe --no-daemon --console=plain > "$probe_dir/crash.log" 2>&1; then
        echo "Crash injection did not halt the JVM at $point" >&2
        exit 1
    fi
    rg -q "CRASH_INJECTED=$point" "$probe_dir/crash.log"
    bash .cache/upstream/mtgcard/gradlew -p addon -Dcustody.runDir="$probe_dir" -Dcustody.recover=true runCustodyProbe --no-daemon --console=plain > "$probe_dir/recover.log" 2>&1
    rg "PASS fresh-JVM crash boundary $point:" "$probe_dir/recover.log"
done
