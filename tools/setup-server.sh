#!/usr/bin/env bash
# Ruby Wars server setup for a Debian 12/13 or Ubuntu 22.04/24.04 VM (Proxmox or anything else).
#
#   curl -fsSL https://raw.githubusercontent.com/gregrose0929/ruby-wars/main/tools/setup-server.sh -o setup-server.sh
#   sudo bash setup-server.sh
#
# What it does (safe to run again; that's also how you update):
#   1. Installs Java 25, git, curl, jq and screen.
#   2. Creates a "minecraft" user and /opt/rubywars.
#   3. Downloads the Ruby Wars source from GitHub and builds the mod jar.
#   4. Downloads the Fabric server launcher (Minecraft 26.3) and the server-side mods
#      from Modrinth: Fabric API, Lithium, FerriteCore, Simple Voice Chat, AppleSkin, Chunky (world pregeneration).
#   5. Writes eula.txt, a starter server.properties (only if there isn't one), and start.sh.
#   6. Installs a systemd service (starts on boot, stops cleanly) and a 5 AM daily restart.
#   7. Opens ports 25565/tcp and 24454/udp if ufw is on, then starts the server.
#   8. Installs the "rubywars" command: rubywars console | cmd "<command>" | logs | status |
#      start | stop | restart | update | newmap.
#
# New installs start on the Ruby Wars Map (a fixed island). "rubywars newmap" (or --newmap) switches an
# existing server to it: the old world moves to /opt/rubywars/old-worlds, then the island is pregenerated.
#
# Settings (environment variables):
#   MEMORY=6G          Java heap (default: the VM's RAM minus ~2 GB, 2G to 6G)
#   OP=<name>          make this player an operator on first start
#   BRANCH=main        which branch of the repo to build
#   JAR=/path/x.jar    use this mod jar instead of building from source
#   ALLOW_USER=<login> let that user run the rubywars commands without a sudo password
#   e.g.  sudo MEMORY=6G OP=YourName bash setup-server.sh
#
# Your own extra server mods: drop them in /opt/rubywars/extra-mods/ and run "rubywars update".
# (The script manages /opt/rubywars/server/mods itself and replaces its contents on update.)
set -euo pipefail

REPO="https://github.com/gregrose0929/ruby-wars.git"
BRANCH="${BRANCH:-main}"
# Default heap: the VM's RAM minus ~2 GB for Linux, between 2G and 6G.
if [[ -z "${MEMORY:-}" ]]; then
	ram_mb="$(awk '/MemTotal/ {print int($2 / 1024)}' /proc/meminfo 2>/dev/null || echo 8192)"
	heap=$(( (ram_mb - 1800) / 1024 ))
	(( heap < 2 )) && heap=2
	(( heap > 6 )) && heap=6
	MEMORY="${heap}G"
fi
OP="${OP:-}"
JAR="${JAR:-}"
MC_VERSION="26.3"
SERVER_MODS=(fabric-api lithium ferrite-core simple-voice-chat appleskin chunky)

# --test DIR: build and download into DIR as the current user, skipping packages, users,
# systemd and the firewall (for trying the script out). Needs java 25 on PATH.
TEST_DIR=""
NEW_MAP=""
if [[ "${1:-}" == "--newmap" ]]; then
	NEW_MAP=1
fi
if [[ "${1:-}" == "--test" ]]; then
	TEST_DIR="$(realpath -m "${2:?usage: setup-server.sh --test DIR}")"
fi

BASE="${TEST_DIR:-/opt/rubywars}"
SERVER="$BASE/server"
SRC="$BASE/src"
USER_NAME="minecraft"

say() { printf '\n\033[1;31m==>\033[0m \033[1m%s\033[0m\n' "$*"; }
die() { printf '\033[1;31mError:\033[0m %s\n' "$*" >&2; exit 1; }
as_mc() {
	if [[ -n "$TEST_DIR" ]]; then "$@"; else sudo -u "$USER_NAME" -H "$@"; fi
}

# ----- 1. Packages and Java 25 -----
if [[ -z "$TEST_DIR" ]]; then
	[[ $EUID -eq 0 ]] || die "run it with sudo: sudo bash $0"
	command -v apt-get >/dev/null || die "this script is for Debian or Ubuntu (apt)."
	export DEBIAN_FRONTEND=noninteractive
	say "Installing packages"
	apt-get update -qq
	apt-get install -y -qq curl ca-certificates git jq screen gpg sudo >/dev/null

	java_major() { java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1; }
	if ! command -v java >/dev/null || [[ "$(java_major)" -lt 25 ]]; then
		say "Installing Java 25"
		if ! apt-get install -y -qq openjdk-25-jdk-headless >/dev/null 2>&1; then
			# Not in this distro's repos: use Eclipse Temurin's.
			install -d -m 0755 /etc/apt/keyrings
			curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor --yes -o /etc/apt/keyrings/adoptium.gpg
			codename="$(. /etc/os-release && echo "${VERSION_CODENAME:-bookworm}")"
			echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $codename main" \
				> /etc/apt/sources.list.d/adoptium.list
			apt-get update -qq
			apt-get install -y -qq temurin-25-jdk >/dev/null || die "couldn't install Java 25."
		fi
	fi
	[[ "$(java_major)" -ge 25 ]] || die "java on PATH is not version 25 (update-alternatives --config java)."

	# ----- 2. User and folders -----
	if ! id "$USER_NAME" >/dev/null 2>&1; then
		say "Creating the $USER_NAME user"
		useradd --system --home-dir "$BASE" --shell /usr/sbin/nologin "$USER_NAME"
	fi
	install -d -o "$USER_NAME" -g "$USER_NAME" "$BASE" "$SERVER" "$SERVER/mods" "$BASE/extra-mods"
else
	command -v java >/dev/null || die "--test needs java 25 on PATH."
	mkdir -p "$SERVER/mods" "$BASE/extra-mods"
fi

# ----- 3. The mod -----
if [[ -n "$JAR" ]]; then
	[[ -f "$JAR" ]] || die "JAR=$JAR doesn't exist."
	MOD_JAR="$JAR"
else
	say "Building Ruby Wars from $REPO ($BRANCH)"
	if [[ -d "$SRC/.git" ]]; then
		as_mc git -C "$SRC" fetch -q origin "$BRANCH"
		as_mc git -C "$SRC" checkout -q -B "$BRANCH" "origin/$BRANCH"
	else
		as_mc git clone -q --branch "$BRANCH" "$REPO" "$SRC"
	fi
	# If this script itself changed upstream, run the new version (same settings) so the change applies now.
	if [[ -z "${RUBYWARS_REEXEC:-}" && -f "$SRC/tools/setup-server.sh" ]] && ! cmp -s "$SRC/tools/setup-server.sh" "$0"; then
		say "The setup script was updated; running the new version"
		export RUBYWARS_REEXEC=1 MEMORY OP BRANCH JAR ALLOW_USER
		exec bash "$SRC/tools/setup-server.sh" "$@"
	fi
	(cd "$SRC" && as_mc bash ./gradlew build --no-daemon -q -x test) || die "the mod didn't build."
	MOD_JAR="$(ls -t "$SRC"/build/libs/rubymod-*.jar | grep -v -- '-sources' | head -1)"
fi
LOADER="$(sed -n 's/^loader_version=//p' "${SRC}/gradle.properties" 2>/dev/null || true)"
LOADER="${LOADER:-0.19.5}"

# ----- 4. Fabric server and mods -----
say "Downloading the Fabric server launcher (Minecraft $MC_VERSION, loader $LOADER)"
INSTALLER="$(curl -fsSL https://meta.fabricmc.net/v2/versions/installer | jq -r '[.[] | select(.stable)][0].version')"
curl -fsSL -o "$SERVER/fabric-server-launch.jar.new" \
	"https://meta.fabricmc.net/v2/versions/loader/$MC_VERSION/$LOADER/$INSTALLER/server/jar"
mv "$SERVER/fabric-server-launch.jar.new" "$SERVER/fabric-server-launch.jar"

say "Downloading server mods"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
query="game_versions=%5B%22$MC_VERSION%22%5D&loaders=%5B%22fabric%22%5D"
for slug in "${SERVER_MODS[@]}"; do
	json="$(curl -fsSL "https://api.modrinth.com/v2/project/$slug/version?$query")"
	file="$(jq -c '([.[] | select(.version_type == "release")] + .)[0].files | (map(select(.primary)) + .)[0]' <<<"$json")"
	[[ "$file" != "null" && -n "$file" ]] || die "no $MC_VERSION version of $slug on Modrinth yet."
	name="$(jq -r .filename <<<"$file")"
	curl -fsSL -o "$STAGE/$name" "$(jq -r .url <<<"$file")"
	[[ "$(sha512sum "$STAGE/$name" | cut -d' ' -f1)" == "$(jq -r .hashes.sha512 <<<"$file")" ]] || die "$name failed its checksum."
	echo "  + $name"
done
cp "$MOD_JAR" "$STAGE/"
echo "  + $(basename "$MOD_JAR")"
cp -n "$BASE"/extra-mods/*.jar "$STAGE/" 2>/dev/null || true
# Swap the mods folder in one go (old versions out, new in).
rm -f "$SERVER"/mods/*.jar
cp "$STAGE"/*.jar "$SERVER/mods/"

# ----- 5. Config files -----
say "Writing server files"
# Running this script means you accept the Minecraft EULA: https://aka.ms/MinecraftEULA
echo "eula=true" > "$SERVER/eula.txt"
if [[ ! -f "$SERVER/server.properties" ]]; then
	cat > "$SERVER/server.properties" <<-EOF
	motd=§c§lRuby Wars§r - build for two weeks, fight for one day
	max-players=12
	difficulty=normal
	view-distance=10
	simulation-distance=8
	spawn-protection=0
	allow-flight=true
	enable-command-block=false
	sync-chunk-writes=false
	level-type=rubymod\:ruby_wars
	EOF
	NEW_MAP=1
fi
cat > "$SERVER/start.sh" <<EOF
#!/usr/bin/env bash
# Started by systemd (rubywars.service). Change MEMORY in /etc/default/rubywars.
cd "\$(dirname "\$0")"
exec java -Xms\${MEMORY:-$MEMORY} -Xmx\${MEMORY:-$MEMORY} -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 \\
	-XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 \\
	-XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20 -XX:InitiatingHeapOccupancyPercent=15 \\
	-jar fabric-server-launch.jar nogui
EOF
chmod +x "$SERVER/start.sh"
if [[ -n "$OP" && ! -f "$SERVER/ops.json" ]]; then
	echo "$OP" > "$BASE/pending-op"
fi

if [[ -n "$TEST_DIR" ]]; then
	say "Test build done in $SERVER (no service installed). Run: cd $SERVER && MEMORY=2G ./start.sh"
	exit 0
fi
chown -R "$USER_NAME:$USER_NAME" "$BASE"

# ----- 6. systemd service, daily restart and the rubywars command -----
say "Installing the rubywars service"
[[ -f /etc/default/rubywars ]] || echo "MEMORY=$MEMORY" > /etc/default/rubywars
cat > /etc/systemd/system/rubywars.service <<EOF
[Unit]
Description=Ruby Wars Minecraft server
After=network-online.target
Wants=network-online.target

[Service]
User=$USER_NAME
Group=$USER_NAME
WorkingDirectory=$SERVER
EnvironmentFile=-/etc/default/rubywars
# The console lives in a screen session: "rubywars console" attaches (Ctrl-A then D detaches).
ExecStart=/usr/bin/screen -DmS rubywars $SERVER/start.sh
ExecStop=-/usr/bin/screen -p 0 -S rubywars -X eval 'stuff "stop"\\\\015'
ExecStop=/bin/bash -c 'while pgrep -u $USER_NAME -x java >/dev/null; do sleep 1; done'
TimeoutStopSec=120
Restart=on-failure
RestartSec=15

[Install]
WantedBy=multi-user.target
EOF

cat > /etc/systemd/system/rubywars-restart.service <<'EOF'
[Unit]
Description=Daily Ruby Wars restart

[Service]
Type=oneshot
ExecStart=/usr/local/bin/rubywars cmd "say Daily restart in 1 minute."
ExecStart=/bin/sleep 60
ExecStart=/bin/systemctl restart rubywars
EOF
cat > /etc/systemd/system/rubywars-restart.timer <<'EOF'
[Unit]
Description=Restart Ruby Wars every day at 5 AM

[Timer]
OnCalendar=*-*-* 05:00:00
Persistent=false

[Install]
WantedBy=timers.target
EOF

# "rubywars update" runs a root-owned copy of this script (so the minecraft account can't change what
# runs as root), refreshed from the repo each time so updates also pick up changes to this script.
SCRIPT_COPY=/usr/local/sbin/rubywars-setup
if [[ -f "$SRC/tools/setup-server.sh" ]]; then
	install -m 755 -o root -g root "$SRC/tools/setup-server.sh" "$SCRIPT_COPY"
elif [[ "$(realpath "$0")" != "$SCRIPT_COPY" ]]; then
	install -m 755 -o root -g root "$0" "$SCRIPT_COPY"
fi
rm -f "$BASE/setup-server.sh"

# ALLOW_USER=<login>: that user may run the rubywars commands (update, restart, console, cmd) without a
# sudo password, and nothing else. Stays in place on later updates.
if [[ -n "${ALLOW_USER:-}" ]]; then
	id "$ALLOW_USER" >/dev/null 2>&1 || die "ALLOW_USER=$ALLOW_USER isn't a user on this machine."
	cat > /etc/sudoers.d/rubywars.new <<EOF
# Ruby Wars server commands for $ALLOW_USER, without a password (written by setup-server.sh).
$ALLOW_USER ALL=(root) NOPASSWD: $SCRIPT_COPY, /usr/bin/systemctl start rubywars, /usr/bin/systemctl stop rubywars, /usr/bin/systemctl restart rubywars
$ALLOW_USER ALL=($USER_NAME) NOPASSWD: /usr/bin/screen
EOF
	chmod 440 /etc/sudoers.d/rubywars.new
	visudo -cf /etc/sudoers.d/rubywars.new >/dev/null || die "the sudo rule didn't check out; nothing was changed."
	mv /etc/sudoers.d/rubywars.new /etc/sudoers.d/rubywars
	say "$ALLOW_USER can now run rubywars commands without a sudo password"
fi
cat > /usr/local/bin/rubywars <<EOF
#!/usr/bin/env bash
# Ruby Wars server helper.
case "\${1:-}" in
	console) exec sudo -u $USER_NAME screen -r rubywars ;;
	cmd)     shift; sudo -u $USER_NAME screen -p 0 -S rubywars -X eval "stuff \"\$*\"\\\\015" && sleep 1 && tail -n 5 $SERVER/logs/latest.log ;;
	logs)    exec tail -n 100 -f $SERVER/logs/latest.log ;;
	status)  systemctl status rubywars --no-pager ;;
	start|stop|restart) exec sudo systemctl "\$1" rubywars ;;
	update)  sudo $SCRIPT_COPY ;;
	newmap)  sudo $SCRIPT_COPY --newmap ;;
	*) echo "usage: rubywars console | cmd \"<command>\" | logs | status | start | stop | restart | update | newmap" ;;
esac
EOF
chmod +x /usr/local/bin/rubywars

# ----- 7. Firewall, then go -----
if command -v ufw >/dev/null && ufw status | grep -q "Status: active"; then
	say "Opening ports in ufw"
	ufw allow 25565/tcp >/dev/null
	ufw allow 24454/udp >/dev/null
fi

# --newmap: put the old world aside and make the Ruby Wars Map on the next start.
if [[ -n "$NEW_MAP" && -z "$TEST_DIR" ]]; then
	if [[ -d "$SERVER/world" ]]; then
		say "Moving the old world to $BASE/old-worlds (nothing is deleted)"
		systemctl stop rubywars || true
		mkdir -p "$BASE/old-worlds"
		mv "$SERVER/world" "$BASE/old-worlds/world-$(date +%Y%m%d-%H%M)"
	fi
	if grep -q '^level-type=' "$SERVER/server.properties"; then
		sed -i 's/^level-type=.*/level-type=rubymod\\:ruby_wars/' "$SERVER/server.properties"
	else
		echo 'level-type=rubymod\:ruby_wars' >> "$SERVER/server.properties"
	fi
fi

systemctl daemon-reload
systemctl enable -q rubywars rubywars-restart.timer
systemctl start rubywars-restart.timer
if systemctl is-active -q rubywars; then
	say "Restarting the server with the new version"
	systemctl restart rubywars
else
	say "Starting the server"
	systemctl start rubywars
fi

# Wait for the first start (it generates the world), then apply OP.
printf "Waiting for the server to finish starting"
for _ in $(seq 1 180); do
	if grep -q 'Done (' "$SERVER/logs/latest.log" 2>/dev/null; then
		break
	fi
	printf "."
	sleep 2
done
echo
if [[ -f "$BASE/pending-op" ]]; then
	/usr/local/bin/rubywars cmd "op $(cat "$BASE/pending-op")" >/dev/null && rm -f "$BASE/pending-op"
fi
if [[ -n "$NEW_MAP" && -z "$TEST_DIR" ]]; then
	# Generate the whole island now (a few minutes, in the background) so nobody waits on new chunks.
	for c in "chunky world minecraft:overworld" "chunky center 0 0" "chunky shape square" "chunky radius 880" "chunky start"; do
		/usr/local/bin/rubywars cmd "$c" >/dev/null
	done
	say "New map made. The island is being pregenerated in the background (see: rubywars logs)."
fi

IP="$(hostname -I | awk '{print $1}')"
say "Ruby Wars is running."
cat <<EOF
  Join from Minecraft (with the Ruby Wars modpack):  $IP   (port 25565)
  Server console:   rubywars console   (Ctrl-A then D to leave it running)
  One command:      rubywars cmd "op YourName"
  Live log:         rubywars logs
  Update the mod:   rubywars update    (pulls the latest from GitHub, rebuilds, restarts)
  Memory:           edit /etc/default/rubywars, then: rubywars restart
  World:            $SERVER/world      Backups: $SERVER/backups (daily, newest 7 kept)
EOF
