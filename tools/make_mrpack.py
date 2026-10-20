"""Build a Modrinth modpack (.mrpack) that players can import into Modrinth App,
Prism Launcher, or similar, to get Fabric, Fabric API, and Ruby Wars in one click.

Usage:  python tools/make_mrpack.py
Run from the project root after ./gradlew build. Writes dist/ruby-wars-<version>.mrpack.

Fabric API is downloaded by the launcher from Modrinth (only its URL and hashes
go in the pack). The Ruby Wars jar itself is bundled in the pack's overrides.
"""
import json
import os
import urllib.parse
import urllib.request
import zipfile

MC_VERSION = "26.3"


def gradle_property(name):
    with open("gradle.properties") as f:
        for line in f:
            if line.startswith(name + "="):
                return line.split("=", 1)[1].strip()
    raise SystemExit(f"{name} not found in gradle.properties")


def latest_fabric_api():
    query = urllib.parse.urlencode({"game_versions": json.dumps([MC_VERSION]), "loaders": json.dumps(["fabric"])})
    with urllib.request.urlopen(f"https://api.modrinth.com/v2/project/fabric-api/version?{query}") as response:
        versions = json.load(response)
    release = next((v for v in versions if v["version_type"] == "release"), versions[0])
    file = next(f for f in release["files"] if f["primary"]) if any(f["primary"] for f in release["files"]) else release["files"][0]
    return release["version_number"], file


def main():
    version = gradle_property("version")
    loader = gradle_property("loader_version")
    jar = f"build/libs/rubymod-{version}.jar"
    if not os.path.exists(jar):
        raise SystemExit(f"{jar} not found. Run ./gradlew build first.")

    api_version, api_file = latest_fabric_api()
    index = {
        "formatVersion": 1,
        "game": "minecraft",
        "versionId": version,
        "name": "Ruby Wars",
        "summary": "Build for two weeks. Fight for one day.",
        "files": [{
            "path": f"mods/{api_file['filename']}",
            "hashes": {"sha1": api_file["hashes"]["sha1"], "sha512": api_file["hashes"]["sha512"]},
            "env": {"client": "required", "server": "required"},
            "downloads": [api_file["url"]],
            "fileSize": api_file["size"],
        }],
        "dependencies": {"minecraft": MC_VERSION, "fabric-loader": loader},
    }

    os.makedirs("dist", exist_ok=True)
    out = f"dist/ruby-wars-{version}.mrpack"
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("modrinth.index.json", json.dumps(index, indent=2))
        z.write(jar, f"overrides/mods/rubymod-{version}.jar")
    print(f"Wrote {out} (Fabric {loader}, Fabric API {api_version})")


if __name__ == "__main__":
    main()
