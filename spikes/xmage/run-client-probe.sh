#!/usr/bin/env bash
set -euo pipefail
project_dir=$(cd -- "$(dirname -- "$0")/../.." && pwd)
mage_dir="$project_dir/.cache/upstream/mage"
probe_cp="$mage_dir/Mage.Server/target/mage-server-1.4.61.jar:$(cat "$project_dir/.cache/xmage-server-classpath.txt")"
mkdir -p "$project_dir/.cache/xmage-server/probe-classes"
javac -cp "$probe_cp" -d "$project_dir/.cache/xmage-server/probe-classes" "$project_dir/spikes/xmage/NativeClientProbe.java"
cd "$project_dir/.cache/xmage-server"
timeout 60s java -ea -Xmx512m --add-opens=java.base/java.io=ALL-UNNAMED \
    -cp "probe-classes:$probe_cp" NativeClientProbe "$@"
