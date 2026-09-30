#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
command -v java >/dev/null 2>&1 || { echo "Java 17+ is required (https://adoptium.net)."; exit 1; }
[[ -f mc-host.jar ]] || ./build.sh
if [[ ! -f .env ]]; then cp .env.example .env; chmod 600 .env; fi
exec java -jar mc-host.jar "$@"
