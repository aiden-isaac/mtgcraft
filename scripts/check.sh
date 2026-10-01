#!/usr/bin/env bash
set -euo pipefail
project_dir=$(cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir"
gson_jar="$HOME/.m2/repository/com/google/code/gson/gson/2.13.2/gson-2.13.2.jar"
test -f "$gson_jar" || { echo 'Run bash spikes/build-stack.sh first.' >&2; exit 1; }
bash scripts/build.sh
mapfile -t sources < <(rg --files tests -g '*.java')
engine_cp="$project_dir/.cache/upstream/mage/Mage.Server/target/mage-server-1.4.61.jar:$(cat .cache/xmage-server-classpath.txt)"
javac --release 25 -cp ".cache/project/shared:.cache/project/service:$engine_cp" -d .cache/project/tests "${sources[@]}"
java -ea -cp ".cache/project/shared:.cache/project/service:.cache/project/tests:$gson_jar" mtgcraft.Check
if [[ "${1:-}" == engine ]]; then
    mkdir -p .cache/engine-check
    cd .cache/engine-check
    java -ea -Xmx2g --add-opens=java.base/java.io=ALL-UNNAMED \
        -cp "$project_dir/.cache/project/shared:$project_dir/.cache/project/service:$project_dir/.cache/project/tests:$engine_cp" \
        mtgcraft.EngineCheck "$project_dir/service/catalog/manifest.json"
fi
