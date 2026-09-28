"""Mod and Paper plugin discovery/install helpers."""

import json
import urllib.parse

from minecraft_host_catalog import MOD_API, get_json


def search_plugins(query: str, version: str = "") -> list[dict]:
    facets = [["categories:paper", "categories:spigot", "categories:bukkit"],
              ["project_type:plugin"]]
    if version:
        facets.append([f"versions:{version}"])
    url = f"{MOD_API}/search?query={urllib.parse.quote(query)}&limit=6&facets={urllib.parse.quote(json.dumps(facets))}"
    return get_json(url).get("hits", [])


def search_mods(query: str, version: str = "") -> list[dict]:
    facets = [["project_type:mod"]]
    if version:
        facets.append([f"versions:{version}"])
    url = f"{MOD_API}/search?query={urllib.parse.quote(query)}&limit=6&facets={urllib.parse.quote(json.dumps(facets))}"
    return get_json(url).get("hits", [])


def newest_jar(project_id: str, loaders: list[str], version: str = "") -> dict:
    params = {"loaders": json.dumps(loaders)}
    if version:
        params["game_versions"] = json.dumps([version])
    url = f"{MOD_API}/project/{project_id}/version?{urllib.parse.urlencode(params)}"
    versions = get_json(url)
    for version_info in versions:
        jars = [item for item in version_info.get("files", []) if item["filename"].endswith(".jar")]
        if jars:
            return next((item for item in jars if item.get("primary")), jars[0])
    raise RuntimeError("No compatible JAR was published for this project.")
