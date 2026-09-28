"""Configuration and directory layout for Minecraft Server Hosting Tool."""

import json
from pathlib import Path

BASE = Path(__file__).resolve().parent / "minecraft-host"
SERVER = BASE / "server"
BIN = BASE / "bin"
RUNTIME = BASE / "runtime"
BACKUPS = BASE / "backups"
TMP = BASE / "tmp"
CONFIG = BASE / "host.json"

DEFAULTS = {
    "version": "", "memory": "2G", "port": 25565, "max_players": 20,
    "online_mode": True, "motd": "A Minecraft Server", "eula": False,
    "tunnel": "", "tunnel_address": "", "autotunnel": False,
}


def load() -> dict:
    BASE.mkdir(parents=True, exist_ok=True)
    result = dict(DEFAULTS)
    if CONFIG.exists():
        try:
            result.update(json.loads(CONFIG.read_text(encoding="utf-8")))
        except (OSError, ValueError):
            pass
    return result


def save(config: dict) -> None:
    BASE.mkdir(parents=True, exist_ok=True)
    CONFIG.write_text(json.dumps(config, indent=2) + "\n", encoding="utf-8")


def ensure_directories() -> None:
    for path in (BASE, SERVER, BIN, RUNTIME, BACKUPS, TMP):
        path.mkdir(parents=True, exist_ok=True)
