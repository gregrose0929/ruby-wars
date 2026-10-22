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

stamp="$(date +%Y-%m-%d-%H%M)"
tag="pack-$stamp"
gh release create "$tag" dist/ruby-wars.mrpack \
	--title "Ruby Wars modpack $stamp" \
	--notes "$notes

**Players:** install the Modrinth App (modrinth.com/app), then download **ruby-wars.mrpack** below and double-click it. Play the newest Ruby Wars in the app.

Direct link that always gets the newest version: https://github.com/gregrose0929/ruby-wars/releases/latest/download/ruby-wars.mrpack" \
	--latest
echo "Released $tag: https://github.com/gregrose0929/ruby-wars/releases/latest/download/ruby-wars.mrpack"
