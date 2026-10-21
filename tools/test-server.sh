#!/bin/bash
# Run a headless dev server, feed it console commands from a script, then stop it.
#
#   tools/test-server.sh tools/tests/smoke.txt [--fresh]
#
# Script lines are server console commands, except:
#   wait <seconds>    pause before the next command
#   # ...             comment
# The server log ends up in run/test-output.log.
set -e
cd "$(dirname "$0")/.."
# Use JDK 25 if it's installed in the usual Windows spot and JAVA_HOME points elsewhere.
if [ -d "/c/Program Files/Java/jdk-25" ]; then export JAVA_HOME="C:\Program Files\Java\jdk-25"; fi
SCRIPT="$1"
mkdir -p run
if [ "$2" == "--fresh" ]; then
	rm -rf run/world run/config/rubywars.json
fi
echo "eula=true" > run/eula.txt
cat > run/server.properties <<'EOF'
online-mode=false
spawn-protection=0
view-distance=6
simulation-distance=6
pause-when-empty-seconds=-1
EOF

LOG=run/test-output.log
: > "$LOG"
(
	# Wait for the server to finish starting.
	for i in $(seq 1 200); do
		grep -q 'Done (' "$LOG" 2>/dev/null && break
		if grep -qE "Failed to load datapacks|Exception in server tick loop|Failed to start the minecraft server|BUILD FAILED" "$LOG" 2>/dev/null; then
			echo "stop"
			exit 0
		fi
		sleep 2
	done
	sleep 2
	while IFS= read -r line || [ -n "$line" ]; do
		line="$(printf '%s' "$line" | tr -d '')"
		case "$line" in
			"" | \#*) ;;
			wait\ *) sleep "${line#wait }" ;;
			*) echo "$line"; sleep 0.5 ;;
		esac
	done < "$SCRIPT"
	sleep 2
	echo "stop"
) | timeout 600 ./gradlew runServer --no-daemon -q --args=nogui > "$LOG" 2>&1 || true
echo "Server stopped. Log: $LOG"
