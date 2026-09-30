#!/usr/bin/env bash
set -euo pipefail
project_dir=$(cd -- "$(dirname -- "$0")/../.." && pwd)
mage_dir="$project_dir/.cache/upstream/mage"
test "$(git -C "$mage_dir" rev-parse HEAD)" = 7f42784132e054d0c5b6c581fa63fed5893327ab
server_cp="$mage_dir/Mage.Server/target/mage-server-1.4.61.jar:$(cat "$project_dir/.cache/xmage-server-classpath.txt")"
mkdir -p "$project_dir/.cache/xmage-server/plugins"
cd "$project_dir/.cache/xmage-server"
for name in mage-player-human mage-player-ai-mad mage-game-twoplayerduel mage-deck-constructed; do
    ln -sf "$HOME/.m2/repository/org/mage/$name/1.4.61/$name-1.4.61.jar" "plugins/$name.jar"
done
exec java -Xmx2g --add-opens=java.base/java.io=ALL-UNNAMED \
    -Dlog4j.configuration="file:$mage_dir/Mage.Server/src/main/resources/log4j.properties" \
    -Dxmage.config.path="$project_dir/spikes/xmage/config.xml" -Dxmage.testMode=false \
    -cp "$server_cp" mage.server.Main
