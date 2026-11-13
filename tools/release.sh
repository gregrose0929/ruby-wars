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
# The installer goes out with every release (it always fetches the newest pack itself): the Windows program, and the
# older script version for anyone with a link to it.
bash tools/installer/build.sh
cp tools/installer/Install-Ruby-Wars.cmd dist/Install-Ruby-Wars.cmd

stamp="$(date +%Y-%m-%d-%H%M)"
tag="pack-$stamp"
gh release create "$tag" dist/Ruby-Wars-Setup.exe dist/ruby-wars.mrpack dist/Install-Ruby-Wars.cmd \
	--title "Ruby Wars modpack $stamp" \
	--notes "$notes

**Players:** download **Ruby-Wars-Setup.exe** below and open it, then click *Install and play*. If Windows says it protected your PC, click *More info*, then *Run anyway*. It sets up the Minecraft Launcher, adds the server to your list, and puts a **Ruby Wars** shortcut on your desktop and in the Start menu. That shortcut updates the game by itself every time, so you only ever install once.

Installer link that never changes: https://github.com/gregrose0929/ruby-wars/releases/latest/download/Ruby-Wars-Setup.exe

**Modrinth App instead:** download **ruby-wars.mrpack** below and double-click it (you'll need to do that again for each new version): https://github.com/gregrose0929/ruby-wars/releases/latest/download/ruby-wars.mrpack" \
	--latest
echo "Released $tag: https://github.com/gregrose0929/ruby-wars/releases/latest/download/ruby-wars.mrpack"
echo "Installer: https://github.com/gregrose0929/ruby-wars/releases/latest/download/Ruby-Wars-Setup.exe"
