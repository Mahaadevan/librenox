#!/usr/bin/env python3
"""Minecraft Server Hosting Tool - terminal server dashboard.

    python minecraft_server_hosting_tool.py              # dashboard + start
    python minecraft_server_hosting_tool.py --no-start   # dashboard only

Pure standard library. On first run it will, by itself:
  * download a private Java runtime if your Java is missing/too old (Adoptium)
  * download the newest stable Paper server (official Fill API)
  * write eula.txt (after you accept it) + server.properties and start hosting

Friends on the same Wi-Fi/LAN join the LAN address shown in the dashboard.
Press T for public hosting through a tunnel (playit.gg / bore.pub / ngrok).
"""

from __future__ import annotations

import collections
import json
import os
import platform
import re
import shutil
import socket
import subprocess
import sys
import tarfile
import threading
import time
import urllib.parse
import urllib.request
import zipfile
from datetime import datetime
from pathlib import Path

from minecraft_host_catalog import MOD_API, PAPER_API, get_json, paper_build, release_asset
from minecraft_host_checks import (find_executable, java_major, lan_ip, port_busy,
                                   required_java)
from minecraft_host_config import (BACKUPS, BASE, BIN, CONFIG, DEFAULTS, RUNTIME,
                                   SERVER, TMP, ensure_directories)
from minecraft_host_plugins import newest_jar, search_mods, search_plugins
from minecraft_host_style import (BLUE, BOLD, CYAN, DIM, GREEN, GREY, MAG, RED, WHITE,
                                  YEL, ANSI_RE, RST, bg, box, fg, fit, visible_length)

IS_WIN = os.name == "nt"
if IS_WIN:
    import msvcrt
else:
    import select
    import termios
    import tty

UA = {"User-Agent": "minecraft-server-hosting-tool/1.0"}
GEYSER = "https://download.geysermc.org/v2/projects/{}/versions/latest/builds/latest/downloads/spigot"


def hms(sec: float) -> str:
    sec = int(max(0, sec))
    return f"{sec // 3600:02}:{sec % 3600 // 60:02}:{sec % 60:02}"


def bar(frac, w: int) -> str:
    if frac is None:
        pos = int(time.time() * 8) % max(1, w - 3)
        return CYAN + "░" * pos + "███" + "░" * (w - 3 - pos) + RST
    n = int(max(0.0, min(1.0, frac)) * w)
    return GREEN + "█" * n + GREY + "░" * (w - n) + RST


# ------------------------------------------------------------------ helpers --
def get_json(url: str):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def download(url: str, target: Path, progress=None) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    tmp = target.with_name(target.name + ".part")
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=60) as r, tmp.open("wb") as f:
        total, done = int(r.headers.get("Content-Length") or 0), 0
        while True:
            chunk = r.read(65536)
            if not chunk:
                break
            f.write(chunk)
            done += len(chunk)
            if progress:
                progress(done / total if total else None)
    tmp.replace(target)


def extract(archive: Path, dest: Path) -> None:
    if dest.exists():
        shutil.rmtree(dest, ignore_errors=True)
    dest.mkdir(parents=True)
    if archive.name.endswith(".zip"):
        with zipfile.ZipFile(archive) as z:
            z.extractall(dest)
    else:
        with tarfile.open(archive) as t:
            try:
                t.extractall(dest, filter="data")
            except TypeError:
                t.extractall(dest)


def port_busy(port: int) -> bool:
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=0.4):
            return True
    except OSError:
        return False


def lan_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))  # no packet is actually sent
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


JAVA_NAME = "java.exe" if IS_WIN else "java"


def find_exe(folder: Path):
    for p in folder.rglob(JAVA_NAME):
        if p.parent.name == "bin" and p.is_file():
            return p
    return None


def java_major(exe: str) -> int:
    try:
        r = subprocess.run([exe, "-version"], capture_output=True, text=True, timeout=15)
        m = re.search(r'version "(\d+)(?:\.(\d+))?', r.stderr + r.stdout)
        if not m:
            return 0
        major = int(m.group(1))
        return int(m.group(2) or 0) if major == 1 else major
    except Exception:
        return 0


def required_java(mc: str) -> int:
    nums = [int(x) for x in re.findall(r"\d+", mc)]
    if not nums:
        return 21
    if nums[0] >= 26:
        return 25
    minor = nums[1] if len(nums) > 1 else 0
    patch = nums[2] if len(nums) > 2 else 0
    if minor >= 21 or (minor == 20 and patch >= 5):
        return 21
    return 17


def pick_asset(assets: list):
    arm = platform.machine().lower() in ("arm64", "aarch64")
    oses = ("windows",) if IS_WIN else ("darwin", "macos") if sys.platform == "darwin" else ("linux",)
    archs = ("aarch64", "arm64") if arm else ("amd64", "x86_64", "x64", "intel")
    skip = (".sha256", ".sig", ".txt", ".msi", ".deb", ".rpm", ".asc", ".sha512")
    for a in assets:
        n = a["name"].lower()
        if any(o in n for o in oses) and any(x in n for x in archs) and not n.endswith(skip):
            return a
    return None


def popen_flags() -> dict:
    if IS_WIN:
        return {"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP}
    return {"start_new_session": True}


# ----------------------------------------------------------------- terminal --
class Terminal:
    def __enter__(self):
        if not (sys.stdin.isatty() and sys.stdout.isatty()):
            raise SystemExit("Please run this in a real terminal window.")
        if IS_WIN:
            os.system("")
            try:
                import ctypes
                k = ctypes.windll.kernel32
                h = k.GetStdHandle(-11)
                m = ctypes.c_uint()
                k.GetConsoleMode(h, ctypes.byref(m))
                k.SetConsoleMode(h, m.value | 4)
            except Exception:
                pass
        else:
            self.fd = sys.stdin.fileno()
            self.old = termios.tcgetattr(self.fd)
            tty.setcbreak(self.fd)
        try:
            sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass
        sys.stdout.write("\033[?1049h\033[?25l\033[2J")
        sys.stdout.flush()
        return self

    def __exit__(self, *exc):
        sys.stdout.write("\033[?25h\033[?1049l")
        sys.stdout.flush()
        if not IS_WIN:
            termios.tcsetattr(self.fd, termios.TCSADRAIN, self.old)

    def key(self, timeout: float = 0.15):
        """Return a chunk of typed text, '' for ignored keys, None on timeout."""
        if IS_WIN:
            end = time.time() + timeout
            while time.time() < end:
                if msvcrt.kbhit():
                    ch = msvcrt.getwch()
                    if ch in ("\x00", "\xe0"):
                        msvcrt.getwch()
                        return ""
                    return ch
                time.sleep(0.02)
            return None
        r, _, _ = select.select([self.fd], [], [], timeout)
        if not r:
            return None
        data = os.read(self.fd, 64).decode("utf-8", "ignore")
        if data.startswith("\x1b") and len(data) > 1:
            return ""
        return data


# ------------------------------------------------------------------- tunnel --
PLAYIT_ADDR = re.compile(r"\b((?:[a-z0-9-]+\.)+(?:joinmc\.link|ply\.gg|playit\.gg)(?::\d+)?)", re.I)
CLAIM_RE = re.compile(r"https://playit\.gg/claim/\S+")


class Tunnel:
    def __init__(self, app: "App"):
        self.app = app
        self.proc = None
        self.provider = ""
        self.status = "off"
        self.address = ""
        self.starting = False

    def alive(self) -> bool:
        return bool(self.proc and self.proc.poll() is None)

    def log(self, text: str) -> None:
        self.app.log("tun", text)

    def start(self, provider: str) -> None:
        if self.starting:
            return
        self.starting = True
        try:
            self.stop()
            self.provider, self.address, self.status = provider, "", "starting"
            port = str(self.app.cfg["port"])
            if provider == "playit":
                exe = self.app.fetch_tool("playit-cloud/playit-agent", "playit")
                attempts = [[str(exe), "--stdout"], [str(exe)]]
            elif provider == "bore":
                exe = self.app.fetch_tool("ekzhang/bore", "bore")
                attempts = [[str(exe), "local", port, "--to", "bore.pub"]]
            elif provider == "ngrok":
                exe = shutil.which("ngrok")
                if not exe:
                    raise RuntimeError("ngrok is not installed (install it and run 'ngrok config add-authtoken ...').")
                attempts = [[exe, "tcp", port, "--log=stdout"]]
            else:
                raise RuntimeError(f"Unknown tunnel provider {provider}")
            for i, cmd in enumerate(attempts):
                self.proc = subprocess.Popen(cmd, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                             stderr=subprocess.STDOUT, text=True, encoding="utf-8",
                                             errors="replace", bufsize=1, **popen_flags())
                threading.Thread(target=self.pump, args=(self.proc,), daemon=True).start()
                time.sleep(4 if provider == "playit" and i == 0 and len(attempts) > 1 else 1.5)
                if self.alive():
                    break
            else:
                self.status = "failed"
                raise RuntimeError("tunnel program exited immediately - see log lines above")
            if provider == "ngrok":
                threading.Thread(target=self.poll_ngrok, daemon=True).start()
            if provider == "playit":
                self.log("If a claim link appears, open it in a browser and approve this agent.")
                self.log("No address afterwards? Copy it from your playit.gg dashboard with T -> 4.")
        finally:
            self.starting = False

    def pump(self, proc) -> None:
        for raw in proc.stdout:
            line = ANSI_RE.sub("", raw).strip()
            if not line:
                continue
            self.log(line)
            if self.provider == "playit":
                c = CLAIM_RE.search(line)
                if c:
                    self.status = "claim"
                    continue
                if "claim" not in line.lower():
                    m = PLAYIT_ADDR.search(line)
                    if m and not m.group(1).lower().startswith(("api.", "www.")):
                        self.address, self.status = m.group(1), "online"
                        continue
                if self.status == "claim" and re.search(r"(?i)tunnel|connected|running", line):
                    self.status = "running"
            elif self.provider == "bore":
                m = re.search(r"listening at (\S+)", line)
                if m:
                    self.address, self.status = m.group(1), "online"
        if self.proc is proc:
            self.status = "off" if self.status in ("off", "stopping") else "failed"
            self.log("tunnel process ended")

    def poll_ngrok(self) -> None:
        for _ in range(60):
            if not self.alive():
                return
            try:
                data = get_json("http://127.0.0.1:4040/api/tunnels")
                for t in data.get("tunnels", []):
                    url = t.get("public_url", "")
                    if url.startswith("tcp://"):
                        self.address, self.status = url[6:], "online"
                        return
            except Exception:
                pass
            time.sleep(1)

    def stop(self) -> None:
        p = self.proc
        if p and p.poll() is None:
            self.status = "stopping"
            p.terminate()
            try:
                p.wait(5)
            except subprocess.TimeoutExpired:
                p.kill()
        self.proc, self.status, self.address = None, "off", ""


# ---------------------------------------------------------------------- app --
LOG_RE = re.compile(r"^\[(\d\d:\d\d:\d\d)\]? ?\[?(?:[^/\]\[]*/)?([A-Z]+)\]:? ?(.*)$")
DONE_RE = re.compile(r"Done \([\d.,]+s\)!")
JOIN_RE = re.compile(r"^(\S+) joined the game$")
LEAVE_RE = re.compile(r"^(\S+) left the game$")
CHAT_RE = re.compile(r"^<[^>]+> ")


class App:
    def __init__(self):
        ensure_directories()
        self.cfg = dict(DEFAULTS)
        if CONFIG.exists():
            try:
                self.cfg.update(json.loads(CONFIG.read_text(encoding="utf-8")))
            except ValueError:
                pass
        self.logs = collections.deque(maxlen=1500)
        self.proc = None
        self.state = "offline"
        self.players: set = set()
        self.t0 = self.ready = 0.0
        self.busy = ""
        self.task = ""
        self.progress = None
        self.tunnel = Tunnel(self)
        self.hint = None
        self.inp = None
        self.term = None
        self._last = ""
        self._size = None
        self._lan = ("", 0.0)

    # -- state helpers
    def save(self) -> None:
        BASE.mkdir(parents=True, exist_ok=True)
        CONFIG.write_text(json.dumps(self.cfg, indent=2) + "\n", encoding="utf-8")

    def log(self, kind: str, text: str) -> None:
        self.logs.append((datetime.now().strftime("%H:%M:%S"), kind, text))

    def host(self, text: str) -> None:
        self.log("host", text)

    def set_task(self, label: str, frac=None) -> None:
        self.task, self.progress = label, frac

    def clear_task(self) -> None:
        self.task, self.progress = "", None

    def fetch(self, url: str, target: Path, label: str) -> None:
        try:
            download(url, target, lambda f: self.set_task(label, f))
        finally:
            self.clear_task()

    def work(self, label: str, fn) -> None:
        if self.busy:
            self.host(f"Busy: {self.busy} - please wait.")
            return
        self.busy = label

        def run():
            try:
                fn()
            except Exception as e:  # noqa: BLE001
                self.log("err", f"{label} failed: {e}")
            finally:
                self.busy = ""
                self.clear_task()

        threading.Thread(target=run, daemon=True).start()

    # -- downloads: java / paper / tools
    def resolve_paper(self):
        return paper_build(self.cfg["version"])

    def ensure_java(self, need: int) -> str:
        cands = []
        if RUNTIME.exists():
            for d in sorted(RUNTIME.glob("jre-*")):
                exe = find_exe(d)
                if exe:
                    cands.append(str(exe))
        home = os.environ.get("JAVA_HOME")
        if home and (Path(home) / "bin" / JAVA_NAME).exists():
            cands.append(str(Path(home) / "bin" / JAVA_NAME))
        if shutil.which("java"):
            cands.append(shutil.which("java"))
        for c in cands:
            if java_major(c) >= need:
                return c
        self.host(f"Java {need}+ not found - downloading a private copy (one time only).")
        osn = "windows" if IS_WIN else "mac" if sys.platform == "darwin" else "linux"
        arch = "aarch64" if platform.machine().lower() in ("arm64", "aarch64") else "x64"
        url = f"https://api.adoptium.net/v3/binary/latest/{need}/ga/{osn}/{arch}/jre/hotspot/normal/eclipse"
        archive = TMP / f"jre{need}{'.zip' if IS_WIN else '.tar.gz'}"
        self.fetch(url, archive, f"Downloading Java {need}")
        self.set_task("Unpacking Java", None)
        dest = RUNTIME / f"jre-{need}"
        extract(archive, dest)
        archive.unlink(missing_ok=True)
        exe = find_exe(dest)
        if not exe:
            raise RuntimeError("Java download unpacked but no java executable was found.")
        if not IS_WIN:
            exe.chmod(0o755)
        return str(exe)

    def ensure_jar(self, version: str, build: dict) -> Path:
        jar = SERVER / f"paper-{version}-{build['id']}.jar"
        if jar.exists() and jar.stat().st_size > 1_000_000:
            return jar
        art = build.get("downloads", {}).get("server:default")
        if not art or not art.get("url"):
            raise RuntimeError("Paper returned no server download for this build.")
        self.host(f"Downloading Paper {version} build {build['id']} ...")
        self.fetch(art["url"], jar, f"Downloading Paper {version}")
        if jar.stat().st_size < 1_000_000:
            jar.unlink(missing_ok=True)
            raise RuntimeError("Paper download was incomplete.")
        for old in SERVER.glob("paper-*.jar"):
            if old != jar:
                old.unlink(missing_ok=True)
        return jar

    def fetch_tool(self, repo: str, name: str) -> Path:
        exe = BIN / (name + (".exe" if IS_WIN else ""))
        if exe.exists():
            return exe
        asset = release_asset(repo, name)
        self.host(f"Downloading {name} ...")
        dl = TMP / asset["name"]
        self.fetch(asset["browser_download_url"], dl, f"Downloading {name}")
        BIN.mkdir(parents=True, exist_ok=True)
        if dl.name.endswith((".zip", ".tar.gz", ".tgz")):
            out = TMP / f"{name}-x"
            extract(dl, out)
            found = next((p for p in out.rglob("*") if p.is_file()
                          and p.name.lower() in (name, name + ".exe")), None)
            if not found:
                raise RuntimeError(f"{name} archive did not contain the program.")
            shutil.copy2(found, exe)
            shutil.rmtree(out, ignore_errors=True)
        else:
            shutil.copy2(dl, exe)
        dl.unlink(missing_ok=True)
        exe.chmod(0o755)
        return exe

    # -- server lifecycle
    def write_props(self) -> None:
        SERVER.mkdir(parents=True, exist_ok=True)
        (SERVER / "eula.txt").write_text("# accepted in Minecraft Server Hosting Tool\neula=true\n", encoding="utf-8")
        path, values = SERVER / "server.properties", {}
        if path.exists():
            for line in path.read_text(encoding="utf-8").splitlines():
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    values[k] = v
        values.update({
            "server-ip": "", "server-port": str(self.cfg["port"]),
            "online-mode": str(self.cfg["online_mode"]).lower(),
            "max-players": str(self.cfg["max_players"]), "motd": self.cfg["motd"],
            "enable-status": "true", "white-list": values.get("white-list", "false"),
        })
        path.write_text("\n".join(f"{k}={v}" for k, v in values.items()) + "\n", encoding="utf-8")

    def _boot(self) -> None:
        self.state = "preparing"
        try:
            if port_busy(self.cfg["port"]):
                raise RuntimeError(f"Port {self.cfg['port']} is already in use (another server running?).")
            self.set_task("Checking Paper", None)
            version, build = self.resolve_paper()
            if not self.cfg["version"]:
                self.cfg["version"] = version  # pin, so the world never auto-upgrades
                self.save()
            java = self.ensure_java(required_java(version))
            jar = self.ensure_jar(version, build)
            self.write_props()
            mem = self.cfg["memory"]
            cmd = [java, f"-Xms{mem}", f"-Xmx{mem}", "-XX:+UseG1GC", "-XX:+ParallelRefProcEnabled",
                   "-Dfile.encoding=UTF-8", "-jar", str(jar), "--nogui"]
            self.proc = subprocess.Popen(cmd, cwd=SERVER, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                         stderr=subprocess.STDOUT, text=True, encoding="utf-8",
                                         errors="replace", bufsize=1, **popen_flags())
            self.state, self.t0, self.ready = "starting", time.time(), 0.0
            self.players.clear()
            self.host(f"Launching Paper {version} with {mem} RAM ...")
            threading.Thread(target=self.pump, args=(self.proc,), daemon=True).start()
        except Exception:
            self.state = "offline"
            raise

    def pump(self, proc) -> None:
        for raw in proc.stdout:
            self.ingest(raw.rstrip("\r\n"))
        code = proc.wait()
        if self.proc is proc:
            self.proc, self.state = None, "offline"
            self.players.clear()
            self.host(f"Server stopped (exit code {code}).")

    def ingest(self, line: str) -> None:
        line = ANSI_RE.sub("", line).replace("\t", "    ")
        if not line.strip():
            return
        self.log("srv", line)
        m = LOG_RE.match(line)
        msg = m.group(3) if m else line
        if DONE_RE.search(msg) and self.state == "starting":
            self.state, self.ready = "online", time.time()
            self.host(f"Server is ONLINE - LAN players join: {lan_ip()}:{self.cfg['port']}")
            if self.cfg["autotunnel"] and self.cfg["tunnel"] and not self.tunnel.alive():
                self.tunnel_async(self.cfg["tunnel"])
        elif JOIN_RE.match(msg):
            self.players.add(JOIN_RE.match(msg).group(1))
        elif LEAVE_RE.match(msg):
            self.players.discard(LEAVE_RE.match(msg).group(1))
        elif "UnsupportedClassVersionError" in line:
            self.log("err", "Java too old for this Paper version - delete minecraft-host/runtime and retry.")
        elif "FAILED TO BIND" in line.upper():
            self.log("err", "Port is taken - change it in Settings (E).")

    def stop_server(self) -> None:
        p = self.proc
        if not p or p.poll() is not None:
            return
        self.state = "stopping"
        try:
            p.stdin.write("stop\n")
            p.stdin.flush()
        except Exception:
            pass
        try:
            p.wait(timeout=90)
        except subprocess.TimeoutExpired:
            self.host("Server did not stop in time - killing it.")
            p.kill()
            p.wait(10)
        for _ in range(30):
            if self.state == "offline":
                break
            time.sleep(0.1)

    def send(self, cmd: str) -> None:
        p = self.proc
        if not p or p.poll() is not None:
            self.log("err", "Server is not running.")
            return
        try:
            p.stdin.write(cmd + "\n")
            p.stdin.flush()
            self.host(f"> {cmd}")
        except Exception as e:  # noqa: BLE001
            self.log("err", f"Could not send command: {e}")

    def ensure_eula(self) -> bool:
        if self.cfg["eula"]:
            return True
        ans = self.prompt("Accept the Minecraft EULA? (y/n)", "y", title="FIRST RUN",
                          hint=["", f"  {WHITE}By hosting a server you must accept the Minecraft EULA:",
                                f"  {CYAN}https://aka.ms/MinecraftEULA"])
        if ans and ans.lower().startswith("y"):
            self.cfg["eula"] = True
            self.save()
            return True
        self.host("EULA not accepted - server not started.")
        return False

    def toggle(self) -> None:
        if self.state in ("starting", "online"):
            self.work("Stopping server", self.stop_server)
        elif self.state == "offline":
            if self.ensure_eula():
                self.work("Preparing server", self._boot)
        else:
            self.host("Please wait ...")

    def restart(self) -> None:
        if self.state not in ("starting", "online"):
            self.host("Server is not running - press ENTER to start it.")
            return

        def go():
            self.stop_server()
            self._boot()

        self.work("Restarting server", go)

    # -- extras
    def tunnel_async(self, provider: str) -> None:
        def go():
            try:
                self.tunnel.start(provider)
            except Exception as e:  # noqa: BLE001
                self.tunnel.status = "failed"
                self.log("err", f"Tunnel: {e}")
            finally:
                self.clear_task()

        threading.Thread(target=go, daemon=True).start()

    def tunnel_menu(self) -> None:
        hint = [
            "",
            f"  {CYAN}1{RST}  playit.gg   free, no router setup, TCP+UDP (Java + Bedrock)  {GREEN}recommended",
            f"  {CYAN}2{RST}  bore.pub    free, instant, no account, Java only",
            f"  {CYAN}3{RST}  ngrok       needs the ngrok program + account authtoken",
            f"  {CYAN}4{RST}  Custom      show an address you already have (port-forward / domain)",
            f"  {CYAN}0{RST}  Stop the tunnel",
            "",
            f"  {GREY}Tunnels expose your server to the internet: keep online-mode ON and consider /whitelist.",
        ]
        ch = self.prompt("Choose 0-4", "", hint=hint, title="PUBLIC HOSTING / TUNNEL")
        if ch is None or not ch:
            return
        if ch == "0":
            self.cfg["autotunnel"] = False
            self.save()
            threading.Thread(target=self.tunnel.stop, daemon=True).start()
            self.host("Tunnel stopped.")
        elif ch in ("1", "2", "3"):
            provider = {"1": "playit", "2": "bore", "3": "ngrok"}[ch]
            self.cfg.update(tunnel=provider, autotunnel=True)
            self.save()
            self.host(f"Starting {provider} tunnel ...")
            self.tunnel_async(provider)
        elif ch == "4":
            addr = self.prompt("Public address (blank clears)", self.cfg["tunnel_address"], title="CUSTOM ADDRESS")
            if addr is not None:
                self.cfg["tunnel_address"] = addr
                self.save()

    def settings(self) -> None:
        c = self.cfg

        def mem(v):
            if not re.fullmatch(r"\d+[MmGg]", v):
                raise ValueError
            return v.upper()

        fields = [
            ("Minecraft version (blank = newest)", "version", str),
            ("Memory (e.g. 2G, 4G)", "memory", mem),
            ("Port", "port", int),
            ("Max players", "max_players", int),
            ("Premium accounts only? (y/n)", "online_mode", lambda v: v.lower().startswith("y")),
            ("Server name (MOTD)", "motd", str),
        ]
        for label, key, cast in fields:
            cur = ("y" if c[key] else "n") if key == "online_mode" else str(c[key])
            raw = self.prompt(label, cur, title="SETTINGS (Esc cancels, restart to apply)")
            if raw is None:
                self.host("Settings cancelled.")
                return
            try:
                c[key] = cast(raw)
            except ValueError:
                self.log("err", f"Invalid value for {key}: {raw!r} (kept {c[key]!r})")
        self.save()
        if not c["online_mode"]:
            self.log("err", "online-mode is OFF: anyone can join with any name. Avoid this with a public tunnel.")
        self.host("Settings saved. Restart (R) to apply.")

    def plugin(self) -> None:
        q = self.prompt("Search Modrinth plugins", "", title="INSTALL PLUGIN")
        if not q:
            return
        try:
            hits = search_plugins(q, self.cfg["version"])
        except Exception as e:  # noqa: BLE001
            self.log("err", f"Search failed: {e}")
            return
        if not hits:
            self.host("No plugins found.")
            return
        hint = [""] + [f"  {CYAN}{i + 1}{RST}  {WHITE}{h['title']}{GREY} - {h['description'][:70]}" for i, h in enumerate(hits)]
        ch = self.prompt(f"Install which? 1-{len(hits)}", "1", hint=hint, title="RESULTS")
        if not ch or not ch.isdigit() or not 1 <= int(ch) <= len(hits):
            return
        hit = hits[int(ch) - 1]
        self.work(f"Installing {hit['title']}", lambda: self._install_plugin(hit))

    def _install_plugin(self, hit: dict) -> None:
        f = newest_jar(hit["project_id"], ["paper", "spigot", "bukkit"], self.cfg["version"])
        self.fetch(f["url"], SERVER / "plugins" / f["filename"], f"Downloading {hit['title']}")
        self.host(f"Installed {hit['title']} ({f['filename']}). Restart (R) to load it.")

    def mod(self) -> None:
        q = self.prompt("Search Modrinth mods", "", title="INSTALL MOD")
        if not q:
            return
        try:
            hits = search_mods(q, self.cfg["version"])
        except Exception as error:
            self.log("err", f"Mod search failed: {error}")
            return
        if not hits:
            self.host("No mods found.")
            return
        hint = [""] + [f"  {CYAN}{i + 1}{RST}  {WHITE}{h['title']}{GREY} - {h['description'][:70]}"
                       for i, h in enumerate(hits)]
        choice = self.prompt(f"Install which? 1-{len(hits)}", "1", hint=hint, title="MOD RESULTS")
        if not choice or not choice.isdigit() or not 1 <= int(choice) <= len(hits):
            return
        hit = hits[int(choice) - 1]
        def install():
            jar = newest_jar(hit["project_id"], ["fabric", "forge", "neoforge", "quilt"], self.cfg["version"])
            self.fetch(jar["url"], SERVER / "mods" / jar["filename"], f"Downloading {hit['title']}")
            self.host(f"Installed mod {hit['title']}. Use a modded server loader to load it.")
        self.work(f"Installing {hit['title']}", install)

    def geyser(self) -> None:
        def go():
            for name in ("geyser", "floodgate"):
                self.host(f"Downloading {name} ...")
                self.fetch(GEYSER.format(name), SERVER / "plugins" / f"{name}-spigot.jar", f"Downloading {name}")
            self.host("Geyser + Floodgate installed. Restart (R). Bedrock players connect to this PC on UDP 19132.")
            self.host("Tip: set auth-type: floodgate in plugins/Geyser-Spigot/config.yml (no Java account needed).")

        self.work("Installing Bedrock crossplay", go)

    def checks(self) -> None:
        """Show actionable readiness checks instead of making the user guess."""
        java = shutil.which("java")
        checks = [
            ("Server folder", SERVER.exists(), str(SERVER)),
            ("Paper jar", any(SERVER.glob("paper-*.jar")), "downloaded on first start"),
            ("Java runtime", bool(java), java or "Install Java or let the launcher download it"),
            ("Configured port", not port_busy(self.cfg["port"]) or self.state != "offline",
             f"TCP {self.cfg['port']}"),
            ("EULA accepted", self.cfg["eula"], "press ENTER and accept on first start"),
        ]
        hint = ["", f"  {BOLD}SETUP CHECKS{RST}"]
        for label, ok, detail in checks:
            hint.append(f"  {GREEN + '✓' if ok else RED + '✗'}{RST} {label:<18} {GREY}{detail}{RST}")
        hint.extend(["", f"  {GREY}Java port: {self.cfg['port']} (TCP)",
                     f"  LAN join: {lan_ip()}:{self.cfg['port']}",
                     "  Public IP is never queried by this tool."])
        self.hint = ("CHECKS", hint)

    def directories(self) -> None:
        self.hint = ("DIRECTORIES", [
            "", f"  {CYAN}Tool data{RST}       {BASE}",
            f"  {CYAN}Server files{RST}     {SERVER}",
            f"  {CYAN}Backups{RST}          {BACKUPS}",
            f"  {CYAN}Downloaded tools{RST} {BIN}",
            f"  {CYAN}Java runtime{RST}     {RUNTIME}",
            f"  {CYAN}Temporary files{RST}  {TMP}",
            "", "  Change the tool location by moving this script and the",
            "  minecraft-host folder together.",
        ])

    def api_help(self) -> None:
        self.hint = ("ACCOUNT / TOKEN HELP", [
            "", f"  {BOLD}No token is needed for Paper, Modrinth, or Geyser.{RST}",
            "", f"  {CYAN}playit.gg{RST}  Press T -> 1. Open the claim link shown in the log.",
            f"  {CYAN}bore.pub{RST}   Press T -> 2. No account/token; Java only.",
            f"  {CYAN}ngrok{RST}      Install ngrok, then run:",
            f"              ngrok config add-authtoken YOUR_TOKEN",
            f"              The token belongs to ngrok and is never stored here.",
            f"  {CYAN}Google Drive{RST} Use rclone config; complete its browser OAuth flow.",
            "              This tool never asks you to paste a Google API token.",
        ])

    def backup(self) -> None:
        def go():
            live = self.state == "online"
            if live:
                self.send("save-off")
                self.send("save-all flush")
                time.sleep(4)
            try:
                BACKUPS.mkdir(parents=True, exist_ok=True)
                target = BACKUPS / f"world-{datetime.now():%Y%m%d-%H%M%S}.zip"
                skip = {"cache", "logs", "libraries", "versions"}
                self.set_task("Creating backup", None)
                with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as z:
                    for p in SERVER.rglob("*"):
                        rel = p.relative_to(SERVER)
                        if p.is_file() and rel.parts[0] not in skip and not p.name.startswith("paper-"):
                            try:
                                z.write(p, rel)
                            except OSError:
                                pass
                self.host(f"Backup saved: {target} ({target.stat().st_size // 1024 // 1024} MB)")
            finally:
                if live:
                    self.send("save-on")

        self.work("Backing up", go)

    # -- input
    def prompt(self, label: str, default: str = "", hint=None, title: str = "INPUT"):
        buf = default
        self.hint = (title, hint or [])
        try:
            while True:
                self.inp = (label, buf)
                self.render()
                k = self.term.key(0.1)
                if k is None or k == "":
                    continue
                if k in ("\r", "\n"):
                    return buf.strip()
                if k == "\x1b":
                    return None
                if k == "\x03":
                    raise KeyboardInterrupt
                if k in ("\x7f", "\b"):
                    buf = buf[:-1]
                else:
                    buf += "".join(ch for ch in k if ch.isprintable())
        finally:
            self.inp = self.hint = None

    def handle(self, k: str) -> bool:
        k = k[0].lower()
        if self.hint:
            self.hint = None
        if k in ("\r", "\n", "s"):
            self.toggle()
        elif k == "r":
            self.restart()
        elif k == "t":
            self.tunnel_menu()
        elif k in ("c", "/"):
            cmd = self.prompt("server >", "", title="CONSOLE COMMAND")
            if cmd:
                self.send(cmd.lstrip("/"))
        elif k == "p":
            self.plugin()
        elif k == "m":
            self.mod()
        elif k == "g":
            self.geyser()
        elif k == "k":
            self.checks()
        elif k == "d":
            self.directories()
        elif k == "a":
            self.api_help()
        elif k == "b":
            self.backup()
        elif k == "e":
            self.settings()
        elif k == "q":
            return False
        return True

    # -- rendering
    def fmt_log(self, ts: str, kind: str, text: str) -> str:
        head = GREY + ts + RST + " "
        if kind == "host":
            return head + CYAN + "HOST  " + RST + fg(153) + text
        if kind == "err":
            return head + RED + "ERROR " + RST + RED + text
        if kind == "tun":
            return head + MAG + "TUNL  " + RST + fg(182) + text
        m = LOG_RE.match(text)
        if not m:
            return head + GREY + "      " + (RED if text.lstrip().startswith(("at ", "Caused", "java.", "Exception")) else GREY) + text
        lvl, msg = m.group(2), m.group(3)
        tag = {"INFO": GREY, "WARN": YEL, "ERROR": RED, "FATAL": RED}.get(lvl, GREY) + f"{lvl:<6}" + RST
        if lvl in ("ERROR", "FATAL"):
            body = RED + msg
        elif lvl == "WARN":
            body = YEL + msg
        elif JOIN_RE.match(msg) or DONE_RE.search(msg):
            body = GREEN + BOLD + msg
        elif LEAVE_RE.match(msg):
            body = YEL + msg
        elif CHAT_RE.match(msg):
            body = MAG + msg
        else:
            body = WHITE + msg
        return head + tag + body

    def frame(self) -> list:
        W, H = shutil.get_terminal_size((110, 32))
        if W < 70 or H < 22:
            return [f"Terminal too small ({W}x{H}); need at least 70x22."] + [""] * (H - 1)
        st = self.state
        spin = "◐◓◑◒"[int(time.time() * 4) % 4]
        badge = {
            "online": GREEN + "● ONLINE", "offline": RED + "○ OFFLINE",
            "starting": YEL + f"{spin} STARTING", "preparing": YEL + f"{spin} PREPARING",
            "stopping": YEL + f"{spin} STOPPING",
        }[st] + RST
        up = ""
        if st == "online":
            up = f"  {GREY}up {hms(time.time() - self.ready)}"
        elif st == "starting":
            up = f"  {GREY}{hms(time.time() - self.t0)}"
        plugins = len(list((SERVER / "plugins").glob("*.jar"))) if (SERVER / "plugins").exists() else 0
        pl = f"{len(self.players)}/{self.cfg['max_players']}"
        names = ", ".join(sorted(self.players))
        inner = 40
        srv = [
            f"{GREY}STATUS   {RST}{badge}{up}",
            f"{GREY}VERSION  {RST}Paper {self.cfg['version'] or 'newest'}   {GREY}RAM {RST}{self.cfg['memory']}",
            f"{GREY}PLAYERS  {RST}{GREEN if self.players else WHITE}{pl}{RST}  {CYAN}{names}",
            f"{GREY}PLUGINS  {RST}{plugins} installed",
        ]
        if self.task:
            srv.append(f"{YEL}{self.task[:24]:<24}{RST} " + bar(self.progress, 12) +
                       (f" {int(self.progress * 100)}%" if self.progress is not None else ""))
        else:
            srv.append(f"{GREY}TASK     idle")
        now = time.time()
        if now - self._lan[1] > 10:
            self._lan = (lan_ip(), now)
        port = self.cfg["port"]
        ts = self.tunnel.status
        tlabel = {"off": (GREY, "off"), "starting": (YEL, "connecting ..."), "claim": (YEL, "waiting for claim link (see log)"),
                  "running": (GREEN, "running"), "online": (GREEN, "online"), "stopping": (YEL, "stopping"),
                  "failed": (RED, "failed")}[ts]
        prov = self.tunnel.provider or self.cfg["tunnel"] or "none"
        pub = self.tunnel.address or self.cfg["tunnel_address"]
        net = [
            f"{GREY}LAN      {RST}{BOLD}{GREEN if st == 'online' else WHITE}{self._lan[0]}:{port}{RST}  {GREY}<- same Wi-Fi",
            f"{GREY}LOCAL    {RST}localhost:{port}",
            f"{GREY}TUNNEL   {RST}{prov} {tlabel[0]}{tlabel[1]}",
            f"{GREY}PUBLIC   {RST}{(BOLD + GREEN + pub) if pub else GREY + '- press T to set up'}",
            f"{GREY}ACCOUNTS {RST}{'premium only' if self.cfg['online_mode'] else RED + 'offline mode (unsafe public)'}",
        ]
        title = bg(24) + WHITE + BOLD + fit(f"  ◆ MINECRAFT SERVER HOSTING TOOL   {fg(153)}terminal dashboard", W - 10) \
            + fit(datetime.now().strftime("%H:%M:%S  "), 10) + RST
        rows = [title]
        if W >= 100:
            ph, lw = 9, W // 2
            rows += [a + b for a, b in zip(box("SERVER", srv, lw, ph), box("NETWORK", net, W - lw, ph, BLUE))]
        else:
            rows += box("SERVER", srv, W, 8) + box("NETWORK", net, W, 8, BLUE)
        log_h = H - len(rows) - 1
        if self.hint:
            rows += box(self.hint[0], self.hint[1], W, log_h, YEL)
        else:
            entries = list(self.logs)[-(log_h - 2):] if log_h > 2 else []
            rows += box("LIVE LOG", [self.fmt_log(*e) for e in entries], W, log_h, GREY)
        if self.inp:
            foot = f" {YEL}{BOLD}{self.inp[0]}{RST} {WHITE}{self.inp[1]}{CYAN}█"
        else:
            keys = [("ENTER", "stop" if st in ("starting", "online") else "start"), ("R", "restart"), ("T", "tunnel"),
                    ("/", "cmd"), ("P", "plugins"), ("M", "mods"), ("G", "bedrock"), ("B", "backup"),
                    ("K", "checks"), ("D", "dirs"), ("A", "tokens"), ("E", "settings"), ("Q", "quit")]
            foot = " ".join(f"{bg(238)}{WHITE} {k} {RST}{GREY} {v}{RST}" for k, v in keys)
        rows.append(foot)
        return rows[:H]

    def render(self) -> None:
        size = shutil.get_terminal_size((110, 32))
        out = "".join(f"\033[{i + 1};1H{fit(line, size.columns)}{RST}\033[K" for i, line in enumerate(self.frame()))
        if size != self._size:
            out = "\033[2J" + out
            self._size = size
        elif out == self._last:
            return
        self._last = out
        sys.stdout.write(out)
        sys.stdout.flush()

    # -- main
    def shutdown(self) -> None:
        self.host("Shutting down ...")
        t = threading.Thread(target=lambda: (self.tunnel.stop(), self.stop_server()), daemon=True)
        t.start()
        try:
            while t.is_alive():
                self.render()
                time.sleep(0.15)
        except KeyboardInterrupt:
            for p in (self.proc, self.tunnel.proc):
                if p and p.poll() is None:
                    p.kill()

    def run(self, autostart: bool = True) -> None:
        with Terminal() as term:
            self.term = term
            try:
                if autostart:
                    self.toggle()
                while True:
                    self.render()
                    k = term.key(0.15)
                    if not k:
                        continue
                    if k == "\x03" or not self.handle(k):
                        break
            except KeyboardInterrupt:
                pass
            self.shutdown()


if __name__ == "__main__":
    if "-h" in sys.argv or "--help" in sys.argv:
        print(__doc__)
        raise SystemExit
    App().run(autostart="--no-start" not in sys.argv)
    print("Minecraft Server Hosting Tool stopped. World data is in", SERVER)
