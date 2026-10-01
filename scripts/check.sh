#!/usr/bin/env bash
set -euo pipefail
project_dir=$(cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir"
gson_jar="$HOME/.m2/repository/com/google/code/gson/gson/2.13.2/gson-2.13.2.jar"
test -f "$gson_jar" || { echo 'Run bash spikes/build-stack.sh first.' >&2; exit 1; }
bash scripts/build.sh
mapfile -t sources < <(rg --files tests -g '*.java')
javac --release 25 -cp ".cache/project/shared:.cache/project/service:$gson_jar" -d .cache/project/tests "${sources[@]}"
java -ea -cp ".cache/project/shared:.cache/project/service:.cache/project/tests:$gson_jar" mtgcraft.Check
