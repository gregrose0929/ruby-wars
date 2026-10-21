#!/bin/bash
# Launch the dev client in autotest mode and collect screenshots.
#   tools/client-test.sh [guns|vehicles|full]
# Screenshots land in run/screenshots/autotest-*.png; the log in run/client-test.log.
set -e
cd "$(dirname "$0")/.."
# Use JDK 25 if it's installed in the usual Windows spot and JAVA_HOME points elsewhere.
if [ -d "/c/Program Files/Java/jdk-25" ]; then export JAVA_HOME="C:\Program Files\Java\jdk-25"; fi
MODE="${1:-full}"
mkdir -p run/saves
# Start every run from a clean copy of the test world (run/saves/autotest-template), so a death or
# a changed world from the last run doesn't leak in. Defaults to the training world.
if [ ! -d run/saves/autotest-template ] && [ -d "dist/Ruby Wars Training" ]; then cp -r "dist/Ruby Wars Training" run/saves/autotest-template; fi
if [ -d run/saves/autotest-template ]; then rm -rf run/saves/autotest; cp -r run/saves/autotest-template run/saves/autotest; fi
if [ ! -d run/saves/autotest ]; then
	echo "Create run/saves/autotest first (copy any test world there)."
	exit 1
fi
# Skip the first-launch accessibility screen and keep running when the window loses focus.
if [ -f run/options.txt ]; then
	sed -i 's/onboardAccessibility:true/onboardAccessibility:false/; s/pauseOnLostFocus:true/pauseOnLostFocus:false/' run/options.txt
else
	printf 'onboardAccessibility:false\npauseOnLostFocus:false\n' > run/options.txt
fi
rm -f run/screenshots/autotest-*.png
RUBYMOD_AUTOTEST="$MODE" timeout ${CLIENT_TIMEOUT:-900} ./gradlew runClient --no-daemon -q --args="--quickPlaySingleplayer autotest" > run/client-test.log 2>&1 || true
grep -E 'autotest\]' run/client-test.log | grep -v screenshot | cut -c1-200
ls run/screenshots | grep autotest | tr '\n' ' '; echo
