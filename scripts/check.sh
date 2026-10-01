#!/usr/bin/env bash
set -euo pipefail
project_dir=$(cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir"
gson_jar="$HOME/.m2/repository/com/google/code/gson/gson/2.13.2/gson-2.13.2.jar"
test -f "$gson_jar" || { echo 'Run bash spikes/build-stack.sh first.' >&2; exit 1; }
mkdir -p .cache/project/classes
mapfile -t sources < <(rg --files shared service tests -g '*.java')
javac --release 25 -cp "$gson_jar" -d .cache/project/classes "${sources[@]}"
java -ea -cp ".cache/project/classes:$gson_jar" mtgcraft.Check
