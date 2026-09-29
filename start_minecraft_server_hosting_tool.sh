#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

if [[ ! -x ".venv/bin/python" ]]; then
  echo "Creating the local Python environment..."
  python3 -m venv .venv
fi

if [[ ! -f ".env" ]]; then
  cp .env.example .env
  chmod 600 .env
  echo "Created .env. Add provider tokens there only when needed."
fi

exec .venv/bin/python minecraft_server_hosting_tool.py "$@"
