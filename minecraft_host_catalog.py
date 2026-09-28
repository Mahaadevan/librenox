"""Official and public catalog clients used by the host."""

import json
import platform
import re
import urllib.parse
import urllib.request

PAPER_API = "https://fill.papermc.io/v3/projects/paper"
MOD_API = "https://api.modrinth.com/v2"
USER_AGENT = {"User-Agent": "minecraft-server-hosting-tool/1.0"}


def get_json(url: str):
    request = urllib.request.Request(url, headers=USER_AGENT)
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def paper_build(version: str = "") -> tuple[str, dict]:
    project = get_json(PAPER_API)
    versions = [item for group in project["versions"].values() for item in group
                if re.fullmatch(r"\d+(\.\d+)+", item)]
    versions.sort(key=lambda value: tuple(int(part) for part in value.split(".")), reverse=True)
    if version and version not in versions:
        raise RuntimeError(f"Paper has no version {version}. Newest: {', '.join(versions[:6])}")
    for candidate in ([version] if version else versions[:8]):
        builds = get_json(f"{PAPER_API}/versions/{urllib.parse.quote(candidate)}/builds")
        builds.sort(key=lambda item: item["id"], reverse=True)
        stable = [item for item in builds if item.get("channel") == "STABLE"]
        if stable or builds:
            return candidate, (stable or builds)[0]
    raise RuntimeError("No Paper build is currently available.")


def modrinth_search(query: str, project_type: str, version: str = "") -> list[dict]:
    facets = [[f"project_type:{project_type}"]]
    if version:
        facets.append([f"versions:{version}"])
    url = f"{MOD_API}/search?{urllib.parse.urlencode({'query': query, 'limit': 6, 'facets': json.dumps(facets)})}"
    return get_json(url).get("hits", [])


def release_asset(repository: str, name: str):
    release = get_json(f"https://api.github.com/repos/{repository}/releases/latest")
    arm = platform.machine().lower() in ("arm64", "aarch64")
    os_name = "windows" if platform.system() == "Windows" else "darwin" if platform.system() == "Darwin" else "linux"
    arch = ("aarch64", "arm64") if arm else ("amd64", "x86_64", "x64", "intel")
    rejected = (".sha256", ".sig", ".txt", ".msi", ".deb", ".rpm", ".asc", ".sha512")
    for asset in release.get("assets", []):
        asset_name = asset["name"].lower()
        if os_name in asset_name and any(part in asset_name for part in arch) and not asset_name.endswith(rejected):
            return asset
    raise RuntimeError(f"No compatible {name} release was found.")
