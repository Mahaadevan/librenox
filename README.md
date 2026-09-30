# MC Host - Minecraft Server Launcher (Java)

Multi-server Minecraft hosting with an Aternos/launcher-style web UI. JDK only, no dependencies.

## Run
Needs Java 17+ for the tool itself (a newer runtime for Minecraft is fetched automatically).

    ./start.sh        # Linux/macOS
    start.bat         # Windows

Opens http://localhost:8765. Options: `--port N`, `--bind ADDR` (default 127.0.0.1; anything else
exposes full server control to your network), `--no-browser`. `build.sh`/`build.bat` rebuild the jar.

## Features
- **Home dashboard**: server cards with icon, status, players, address, Start/Stop; add unlimited servers;
  switch servers from the top-bar menu or sidebar. Several servers can run at once (unique ports auto-assigned).
- **Software**: Paper, Purpur, Folia, Vanilla, Fabric or a custom jar (Forge etc.). Switch software/version
  any time (optional backup first), update to newest build, offline fallback to the existing jar.
- **Options**: every server.properties setting with Normal / Expert toggle, search, per-field reset,
  live MOTD colour preview, unknown keys and custom properties in Expert. Comments/order preserved.
- **Icons**: taken from the downloaded server jar when it contains one, otherwise generated; upload your
  own, pick one from images inside server.jar / plugins / mods, or use a PNG from Files. Saved as the
  64x64 server-icon.png, so it shows in the Minecraft server list too.
- **Players**: online list with op/gamemode/kick/ban menu, whitelist, operators, banned players/IPs
  (commands while running, JSON edit while stopped, Mojang UUID lookup).
- **Console** with history + quick commands, **Logs** viewer (incl. old .gz logs), **Files** manager
  (browse, edit, upload, download, rename, delete, unzip), **Worlds** (upload, download, switch, delete).
- **Add-ons** from Modrinth: plugins, mods, datapacks, with enable/disable; Geyser + Floodgate one-click.
- **Backups**: manual + scheduled, retention, one-click restore (auto safety backup first), download.
- **Automation**: scheduled backup/restart/command, auto-stop when empty, crash auto-restart, start with launcher.
- **Startup**: RAM, JVM presets (default, Aikar, custom), custom Java path.
- **Network**: playit.gg / bore.pub / ngrok tunnels, custom address. **Overview** shows CPU/RAM graphs.

Data lives in `minecraft-host/servers/<id>/{server,backups,config.json}`. An old single-server
`minecraft-host/` is migrated automatically into a server called "My Server".

## Security
Loopback only; Host header checked; POSTs must be same-origin JSON (or carry a custom header for uploads).
All file access is confined to the server folder. `.env` is local and git-ignored. Public IP is never queried.
