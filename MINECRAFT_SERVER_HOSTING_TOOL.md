# Minecraft Server Hosting Tool

The launcher is now split into focused standard-library modules:

- `minecraft_server_hosting_tool.py` — terminal dashboard, lifecycle, tunnel, backup, and controls.
- `minecraft_host_config.py` — configuration and managed directories.
- `minecraft_host_style.py` — light blue, yellow, green, pink, and neutral terminal theme.
- `minecraft_host_checks.py` — Java, port, LAN, and runtime readiness checks.
- `minecraft_host_catalog.py` — official Paper and public catalog/release downloads.
- `minecraft_host_plugins.py` — Modrinth mod/plugin search and compatible JAR selection.

Run the tool from the project directory:

```text
python minecraft_server_hosting_tool.py
```

The dashboard keys include:

| Key | Action |
|---|---|
| `Enter` | Start/stop the server |
| `R` | Restart |
| `T` | Tunnel/public hosting |
| `/` | Send a server console command |
| `P` | Search/install Paper plugins |
| `M` | Search/install Java mods |
| `G` | Install Geyser + Floodgate |
| `B` | Create a backup |
| `K` | Run setup/readiness checks |
| `D` | Show every managed directory |
| `A` | Show API/token instructions |
| `E` | Edit server settings |
| `Q` | Quit |

Paper, Modrinth, Geyser, and Bore do not require an API token. For a provider
that does require one, the `A` panel gives the provider-specific command and
the token is entered into that provider's own CLI. The host does not collect or
store third-party tokens. Google Drive backups should use `rclone config`,
which opens Google's OAuth flow in a browser instead of requiring an API key.

Mods belong in a mod-loader server and plugins belong in Paper's `plugins/`
directory; the tool keeps these install paths separate and tells you when a
restart is needed.
