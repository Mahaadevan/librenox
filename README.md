# Minecraft Server Hosting Tool

A single-file, terminal-first Minecraft server manager for Paper/Java. It
automatically prepares the Java runtime and Paper server, then provides a
colored terminal dashboard for hosting, checks, directories, tunnels,
plugins, mods, Geyser/Floodgate, console commands, and backups.

## One-click start

### Windows

Double-click `start_minecraft_server_hosting_tool.bat`, or run:

```powershell
.\start_minecraft_server_hosting_tool.bat
```

The launcher creates `.venv` automatically and runs
`minecraft_server_hosting_tool.py`. It uses only Python's standard library;
there is no `requirements.txt` and no `pip install` step.

### Linux/macOS

```bash
chmod +x start_minecraft_server_hosting_tool.sh
./start_minecraft_server_hosting_tool.sh
```

The launcher creates `.venv`, creates a private `.env` from `.env.example`,
and starts the terminal dashboard. Use `--no-start` to open the dashboard
without starting Paper automatically.

## What is generated

The tool keeps generated data beside the script:

```text
minecraft-host/
├── server/    Paper, worlds, plugins, mods, logs, properties
├── runtime/   private Java runtime downloaded when needed
├── bin/       tunnel helper binaries
├── backups/   ZIP backups
└── tmp/       temporary downloads
```

The script creates these directories itself. They are intentionally ignored by
Git because they can contain large binaries, worlds, logs, private keys, and
server state.

## Dashboard keys

`Enter` starts/stops, `R` restarts, `T` opens tunnel setup, `/` sends a Paper
console command, `P` installs a Paper plugin, `M` installs a Java mod, `G`
installs Geyser + Floodgate, `B` creates a backup, `K` runs checks, `D` shows
directories, `A` shows token instructions, `E` edits settings, and `Q` quits.

Mods are downloaded into `minecraft-host/server/mods/`; plugins go into
`minecraft-host/server/plugins/`. A mod requires a compatible mod-loader
server, while Paper plugins require Paper/Spigot/Bukkit compatibility.

## Tokens and credentials

`.env` is local-only and is ignored by Git. Never commit it. Copy
`.env.example` to `.env` if you need a local provider variable:

```text
MCSHT_NGROK_AUTHTOKEN=replace_me
```

The tool does not need tokens for Paper, Modrinth, Geyser, Playit, or Bore.
For ngrok, use the provider command shown by the `A` panel:

```text
ngrok config add-authtoken YOUR_TOKEN
```

For Google Drive, use `rclone config`, complete its browser OAuth flow, and
keep credentials in rclone's own protected configuration. Do not put Google
credentials in `.env`.

The tool does not query or print your public IP. Direct router forwarding
exposes it; use a tunnel for remote players and keep `online-mode` enabled.

## Requirements

- Python 3.10 or newer
- Internet access on first Paper/Java/mod download
- A real interactive terminal (PowerShell, Windows Terminal, bash, or a Linux
  terminal)
- For Bedrock crossplay, Geyser/Floodgate are installed by the dashboard; the
  official Bedrock client/server licensing still applies.

The repository intentionally contains only one Python source file. The
launchers, README, `.env.example`, `.gitignore`, and LICENSE are support files;
runtime data is never pushed.
