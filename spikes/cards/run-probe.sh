#!/usr/bin/env bash
set -euo pipefail
project_dir=$(cd -- "$(dirname -- "$0")/../.." && pwd)
mtgcard_dir="$project_dir/.cache/upstream/mtgcard"
test "$(git -C "$mtgcard_dir" rev-parse HEAD)" = bab304144c9b94e9d005a85cddb77ed41f6c2385
cd "$mtgcard_dir"
bash gradlew -I "$project_dir/spikes/mtgcard/probe.init.gradle" \
    -Dprobe.dir="$project_dir/spikes/mtgcard" \
    -Dprobe.cards="$project_dir/spikes/cards/printings.json" \
    -Dprobe.output="$project_dir/.cache/card-projections.json" \
    :fabric:boundaryProbe --no-daemon --console=plain
bash "$project_dir/spikes/xmage/run-probe.sh" \
    "$project_dir/spikes/cards/printings.json" "$project_dir/.cache/card-projections.json"
