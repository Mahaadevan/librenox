"""Local readiness and runtime checks."""

import re
import shutil
import socket
import subprocess
from pathlib import Path


def port_open(port: int, host: str = "127.0.0.1") -> bool:
    try:
        with socket.create_connection((host, port), timeout=0.4):
            return True
    except OSError:
        return False


def port_busy(port: int) -> bool:
    return port_open(port)


def lan_ip() -> str:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("10.255.255.255", 1))
        return sock.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        sock.close()


def find_executable(folder: Path, name: str) -> Path | None:
    return next((path for path in folder.rglob(name) if path.parent.name == "bin" and path.is_file()), None)


def java_major(executable: str) -> int:
    try:
        result = subprocess.run([executable, "-version"], capture_output=True,
                                text=True, timeout=15)
        match = re.search(r'version "(\d+)(?:\.(\d+))?', result.stderr + result.stdout)
        if not match:
            return 0
        major = int(match.group(1))
        return int(match.group(2) or 0) if major == 1 else major
    except (OSError, subprocess.SubprocessError):
        return 0


def required_java(minecraft_version: str) -> int:
    numbers = [int(part) for part in re.findall(r"\d+", minecraft_version)]
    if not numbers:
        return 21
    if numbers[0] >= 26:
        return 25
    minor = numbers[1] if len(numbers) > 1 else 0
    patch = numbers[2] if len(numbers) > 2 else 0
    return 21 if minor >= 21 or (minor == 20 and patch >= 5) else 17


def java_on_path() -> str | None:
    return shutil.which("java")
