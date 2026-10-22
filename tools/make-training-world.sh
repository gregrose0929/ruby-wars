#!/bin/bash
# Build the "Ruby Wars Training" singleplayer world:
#   a fresh superflat world, /rubywars training run at spawn, cheats and creative on.
# Output: dist/Ruby Wars Training/ (copy into .minecraft/saves) and dist/ruby-wars-training.zip
set -e
cd "$(dirname "$0")/.."
EXTRA_PROPS=$'level-type=minecraft\\:flat\ngamemode=creative\ngenerate-structures=false\ndifficulty=peaceful' \
	tools/test-server.sh tools/tests/training.txt --fresh
grep -E "Training ground|Unknown|Incorrect|error" run/test-output.log | cut -c1-200 || true
OUT="dist/Ruby Wars Training"
rm -rf "$OUT"
mkdir -p dist
cp -r run/world "$OUT"
rm -f "$OUT/session.lock"
python tools/leveldat.py "$OUT/level.dat" allowCommands=1b GameType=1 LevelName="Ruby Wars Training"
(cd dist && rm -f ruby-wars-training.zip && python -c "import shutil; shutil.make_archive('ruby-wars-training', 'zip', '.', 'Ruby Wars Training')")
echo "Training world: $OUT (and dist/ruby-wars-training.zip)"
