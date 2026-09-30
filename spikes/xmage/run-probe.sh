#!/usr/bin/env bash
set -euo pipefail
project_dir=$(cd -- "$(dirname -- "$0")/../.." && pwd)
mage_dir="$project_dir/.cache/upstream/mage"
maven="$project_dir/.cache/tools/apache-maven-3.9.9/bin/mvn"
test "$(git -C "$mage_dir" rev-parse HEAD)" = 7f42784132e054d0c5b6c581fa63fed5893327ab
"$maven" -B -ntp -f "$mage_dir/Mage.Server/pom.xml" dependency:build-classpath \
    -Dmdep.outputFile="$project_dir/.cache/xmage-classpath.txt"
probe_cp="$mage_dir/Mage.Server/target/mage-server-1.4.61.jar:$(cat "$project_dir/.cache/xmage-classpath.txt")"
mkdir -p "$project_dir/.cache/xmage-probe/classes"
javac -cp "$probe_cp" -d "$project_dir/.cache/xmage-probe/classes" "$project_dir/spikes/xmage/XmageProbe.java"
cd "$project_dir/.cache/xmage-probe"
java -ea -Xmx2g --add-opens=java.base/java.io=ALL-UNNAMED \
    -cp "classes:$probe_cp" XmageProbe "$mage_dir/Mage.Server/config/config.xml"
