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
#      from Modrinth: Fabric API, Lithium, FerriteCore, Simple Voice Chat, AppleSkin.
#   5. Writes eula.txt, a starter server.properties (only if there isn't one), and start.sh.
#   6. Installs a systemd service (starts on boot, stops cleanly) and a 5 AM daily restart.
#   7. Opens ports 25565/tcp and 24454/udp if ufw is on, then starts the server.
#   8. Installs the "rubywars" command: rubywars console | cmd "<command>" | logs | status |
#      start | stop | restart | update.
#
# Settings (environment variables):
#   MEMORY=6G          Java heap (default: the VM's RAM minus ~2 GB, 2G to 6G)
#   OP=<name>          make this player an operator on first start
#   BRANCH=main        which branch of the repo to build
#   JAR=/path/x.jar    use this mod jar instead of building from source
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
SERVER_MODS=(fabric-api lithium ferrite-core simple-voice-chat appleskin)

# --test DIR: build and download into DIR as the current user, skipping packages, users,
# systemd and the firewall (for trying the script out). Needs java 25 on PATH.
TEST_DIR=""
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
	EOF
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

SCRIPT_COPY="$BASE/setup-server.sh"
if [[ "$(realpath "$0")" != "$SCRIPT_COPY" ]]; then
	cp "$0" "$SCRIPT_COPY"
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
	update)  sudo bash $SCRIPT_COPY ;;
	*) echo "usage: rubywars console | cmd \"<command>\" | logs | status | start | stop | restart | update" ;;
esac
EOF
chmod +x /usr/local/bin/rubywars

# ----- 7. Firewall, then go -----
if command -v ufw >/dev/null && ufw status | grep -q "Status: active"; then
	say "Opening ports in ufw"
	ufw allow 25565/tcp >/dev/null
	ufw allow 24454/udp >/dev/null
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
