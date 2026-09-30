#!/usr/bin/env bash
# Builds mc-host.jar (needs a JDK 17+ with javac).
set -euo pipefail
cd "$(dirname "$0")"
rm -rf out && mkdir out
javac --release 17 -d out $(find src/main/java -name '*.java')
cp -r src/main/resources/web out/web
printf 'Main-Class: mcht.Main\n' > out/MANIFEST.MF
jar cfm mc-host.jar out/MANIFEST.MF -C out mcht -C out web
rm -rf out
echo "Built mc-host.jar"
