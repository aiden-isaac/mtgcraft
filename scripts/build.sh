#!/usr/bin/env bash
set -euo pipefail
project_dir=$(cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir"
mage_dir="$project_dir/.cache/upstream/mage"
test "$(git -C "$mage_dir" rev-parse HEAD)" = 7f42784132e054d0c5b6c581fa63fed5893327ab
gson_jar="$HOME/.m2/repository/com/google/code/gson/gson/2.13.2/gson-2.13.2.jar"
engine_cp="$mage_dir/Mage.Server/target/mage-server-1.4.61.jar:$(cat .cache/xmage-server-classpath.txt)"
mkdir -p .cache/project/shared .cache/project/service .cache/project/tests
mapfile -t shared_sources < <(rg --files shared -g '*.java')
javac --release 25 -cp "$gson_jar" -d .cache/project/shared "${shared_sources[@]}"
mapfile -t service_sources < <(rg --files service/src -g '*.java')
javac --release 25 -cp ".cache/project/shared:$engine_cp" -d .cache/project/service "${service_sources[@]}"
jar --create --file .cache/project/mtgcraft-shared.jar -C .cache/project/shared .
jar --create --file .cache/project/mtgcraft-service.jar -C .cache/project/service .
