#!/usr/bin/env bash
# Publish the player modpack as a GitHub release, so players always download the newest one from
#   https://github.com/gregrose0929/ruby-wars/releases/latest/download/ruby-wars.mrpack
#
# Usage (from the project root, after ./gradlew build):  tools/release.sh ["what changed"]
# Needs the GitHub CLI (gh) logged in.
set -euo pipefail

notes="${1:-Updated Ruby Wars modpack.}"
python tools/make_mrpack.py
version="$(sed -n 's/^version=//p' gradle.properties)"
cp "dist/ruby-wars-$version.mrpack" dist/ruby-wars.mrpack
# The one-click installer goes out with every release (it always fetches the newest pack itself).
cp tools/installer/Install-Ruby-Wars.cmd dist/Install-Ruby-Wars.cmd

stamp="$(date +%Y-%m-%d-%H%M)"
tag="pack-$stamp"
gh release create "$tag" dist/ruby-wars.mrpack dist/Install-Ruby-Wars.cmd \
	--title "Ruby Wars modpack $stamp" \
	--notes "$notes

**Players (the easy way):** download **Install-Ruby-Wars.cmd** below and double-click it. If Windows says it protected your PC, click *More info*, then *Run anyway*. It sets up the Minecraft Launcher and puts a **Ruby Wars** shortcut on your desktop; that shortcut updates the game by itself every time you use it, so you only ever install once.

Installer link that never changes: https://github.com/gregrose0929/ruby-wars/releases/latest/download/Install-Ruby-Wars.cmd

**Modrinth App instead:** download **ruby-wars.mrpack** below and double-click it (you'll need to do that again for each new version): https://github.com/gregrose0929/ruby-wars/releases/latest/download/ruby-wars.mrpack" \
	--latest
echo "Released $tag: https://github.com/gregrose0929/ruby-wars/releases/latest/download/ruby-wars.mrpack"
echo "Installer: https://github.com/gregrose0929/ruby-wars/releases/latest/download/Install-Ruby-Wars.cmd"
