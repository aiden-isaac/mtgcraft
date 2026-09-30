#!/usr/bin/env bash
set -euo pipefail
project_dir=$(cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir"
mkdir -p .cache/upstream .cache/tools
checkout() {
    local repo=$1 revision=$2 destination=$3
    if [[ ! -d "$destination/.git" ]]; then
        git init "$destination"
        git -C "$destination" remote add origin "https://github.com/$repo.git"
        git -C "$destination" fetch --depth 1 origin "$revision"
        git -C "$destination" checkout --detach FETCH_HEAD
    fi
    test "$(git -C "$destination" rev-parse HEAD)" = "$revision"
    git -C "$destination" diff --exit-code HEAD --
}
checkout blackspider9678/MtgCard bab304144c9b94e9d005a85cddb77ed41f6c2385 .cache/upstream/mtgcard
checkout magefree/mage 7f42784132e054d0c5b6c581fa63fed5893327ab .cache/upstream/mage
if [[ ! -f .cache/tools/maven.tar.gz ]]; then
    curl --fail --location --retry 2 \
        https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.tar.gz \
        --output .cache/tools/maven.tar.gz
fi
printf '%s  %s\n' a555254d6b53d267965a3404ecb14e53c3827c09c3b94b5678835887ab404556bfaf78dcfe03ba76fa2508649dca8531c74bca4d5846513522404d48e8c4ac8b .cache/tools/maven.tar.gz | sha512sum --check
if [[ ! -x .cache/tools/apache-maven-3.9.9/bin/mvn ]]; then
    tar -xzf .cache/tools/maven.tar.gz -C .cache/tools
fi
bash .cache/upstream/mtgcard/gradlew -p .cache/upstream/mtgcard \
    -Ploom_version=1.17.21 :fabric:build --no-daemon --console=plain
maven="$project_dir/.cache/tools/apache-maven-3.9.9/bin/mvn"
"$maven" -B -ntp -f .cache/upstream/mage/pom.xml -pl Mage.Server,Mage.Client -am install -DskipTests
for module in Server Client; do
    "$maven" -B -ntp -f ".cache/upstream/mage/Mage.$module/pom.xml" dependency:build-classpath \
        "-Dmdep.outputFile=$project_dir/.cache/xmage-${module,,}-classpath.txt"
done
