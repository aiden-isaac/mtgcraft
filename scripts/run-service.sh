#!/usr/bin/env bash
set -euo pipefail
project_dir=$(cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir"
config_path=$(realpath "${1:?Pass the path to private local service properties}")
state_dir=$(realpath -m "${2:-$project_dir/.cache/match-service}")
bash scripts/build.sh
engine_cp="$project_dir/.cache/upstream/mage/Mage.Server/target/mage-server-1.4.61.jar:$(cat .cache/xmage-server-classpath.txt)"
mkdir -p "$state_dir"
cd "$state_dir"
exec java -Xmx2g --add-opens=java.base/java.io=ALL-UNNAMED -Dxmage.testMode=false \
    -Dmtgcraft.setsJar="$HOME/.m2/repository/org/mage/mage-sets/1.4.61/mage-sets-1.4.61.jar" \
    -cp "$project_dir/.cache/project/mtgcraft-shared.jar:$project_dir/.cache/project/mtgcraft-service.jar:$engine_cp" \
    mage.server.ServiceMain "$config_path"
