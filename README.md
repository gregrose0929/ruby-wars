# Ruby Wars

**Ruby Wars** is a big, friendly Minecraft mod (Fabric, Minecraft Java **26.3**) for
playing together on one server. Its main game is a **season**: everyone claims land,
builds a base and an economy, and gets ready for **Battle Day**, one big fight with
guns, tanks, jets, mortars and missiles where the last player standing
wins. Around that sits a whole amusement-park **hub** and a dozen other games: co-op
modes, quick party games, and a roller coaster builder.

There's no grinding: no daily chores, no streaks, nothing that punishes you for
taking a week off. Play when you like.

---

## Installing

You need **Minecraft: Java Edition** (the official launcher, signed in at least once).
Everything else (Fabric, the mod, and a few helper mods) is set up for you.

### Windows: the installer (easiest)

1. Download **[Ruby-Wars-Setup.exe](https://github.com/gregrose0929/ruby-wars/releases/latest/download/Ruby-Wars-Setup.exe)**
   and open it. If Windows says it protected your PC, click *More info*, then *Run anyway*.
2. Click **Install and play**.

It adds a *Ruby Wars* profile to the Minecraft Launcher and a **Ruby Wars** shortcut to
your desktop. The shortcut checks for updates each time you use it, so you never have
to install twice. Ruby Wars keeps its own folder (`%APPDATA%\.rubywars`), so your normal
Minecraft worlds and mods aren't touched.

### Any computer: the modpack

1. Install the free [Modrinth App](https://modrinth.com/app) or [Prism Launcher](https://prismlauncher.org/).
2. Download **[ruby-wars.mrpack](https://github.com/gregrose0929/ruby-wars/releases/latest/download/ruby-wars.mrpack)**.
3. In the launcher, *Add instance → Import* and pick the file. Play that instance.

When the mod is updated, import the new `.mrpack` the same way. (If you join a server
with an older version, the game tells you and gives you a link to the new one.)

The pack also includes some helpful mods: Sodium, Lithium, FerriteCore and Entity
Culling (smoother frame rate), LambDynamicLights (flares and muzzle flashes light up
the dark), Simple Voice Chat (talk to each other, and Field Radios), AppleSkin and
Mouse Tweaks.

### Playing

- **On a server:** *Multiplayer → Add Server*, then type the address you were given.
- **With people in the same house:** one person opens a world, presses Esc → *Open to
  LAN*. Everyone else finds it in *Multiplayer*.
- **On your own:** make a singleplayer world. For the real season map, choose
  *World Type: Ruby Wars Map*. A ready-made practice world is also in
  `dist/Ruby Wars Training/` (copy it into `.minecraft/saves/`).

---

## What's in it

### The hub

On a server you arrive in **the hub**, a floating amusement park. Walk into a portal to
pick a game, or press **H** anywhere for the game picker (or type `/hub` and `/play <game>`).

The park is there to explore between games: a Fun Park with a roller coaster, a wild
mouse, bumper cars, swings, a big wheel, a carousel, a drop tower, a pirate ship and
teacups; **Splash Island** with slides, a flume, river rapids and a wave pool; four holes of
crazy **Mini Golf**; **Sky Parkour**; kart racing at the **Speedway**; sky tours on a happy
ghast; a stage with a DJ and a dance floor; carnival games; pets that grow up with you;
and a fireworks show at night. The friendly hosts standing around explain everything:
right-click them. Your **Park Map** (in your hotbar) flies you anywhere.

Your Ruby Wars things are kept safe while you're in the hub or other games: leaving
saves your inventory and position, and coming back puts it all back.

### The main game: a Ruby Wars season

1. **The build phase.** Everyone gets a **Ruby Heart**. Place it to claim the land around
   it: nobody else can build, break or open things there, and nobody can be hurt inside a
   claim. Mine **Ruby Ore** (rarer than diamonds, deep underground) and spend rubies at an
   **Armory**: its tabs sort everything by kind (Guns, Ammo, Defenses, Vehicles, Machines…),
   each card shows its name and price, and the panel beside it says what it does. Everyone
   plays on their own.
2. **Battle Day** starts when the admin says so (or on a date they set). Everyone in the
   season is called back to Ruby Wars:
   - **Lockdown (15 minutes):** claims open and defenses switch on, but no fighting yet.
   - **Open War (60 minutes):** PvP is on. Breaching Charges can destroy a Heart. While
     your Heart stands you respawn at it; once it's gone, your next death knocks you out.
     A line under the timer always says whether you'll respawn.
     Supply drops land every 15 minutes.
   - **Sudden Death (20 minutes):** every Heart shatters, no more respawns, and the world
     border closes in.
   - **Final Circle:** everyone glows and artillery shells random spots, so nobody can hide.
     The last player standing is the champion.
   - **Knocked out?** Watch the rest: click to follow the next player still in, right-click
     for the one before, crouch to fly free. Or press **H** and play party games in the hub.
   - **The wrap-up:** when the season ends, everyone gets a card with the winner, a few
     awards (top fighter, richest base, biggest Heart…) and how their own season went.
     `/rubywars recap` shows it again.

There are no hostile mobs and no hunger by default: the game is building and battling.

**The Ruby Wars Map** is a hand-designed island about 1,500 blocks across, the same every
season: a ruby crater in the middle (the richest ruby ore, worth fighting over), a ring of
mountains, four fair home corners (Green Hills, Pine Woods, Red Desert, Snow Plateau) with
villages, rivers to the sea, and ruins to explore.

### Building a base

- **Your base is yours:** everyone plays on their own (no squads), and only you can put Ruby
  Wars equipment (machines, defenses, belts, mines, security) in your base, even on Battle
  Day. Plain building blocks follow the usual claim rules.
- **Defenses:** sandbags, concrete walls, reinforced concrete and glass, blast doors,
  barbed wire, landmines, claymores, anti-tank mines, Sentry Turrets, Tesla Coils, Flak
  Cannons, SAM Sites, Missile Interceptors, Shield Generators, Point Defense Guns, Heavy AA
  Guns and a Railgun, and Holo Camouflage that looks like any block. All of them follow one
  rule: they go for monsters any time, and enemy players once
  PvP is on.
- **Security:** keycard doors and readers, security cameras with monitors, laser
  tripwires, seismic sensors, alarms and floodlights.
- **Moving parts:** the **Ruby Bearing** (swing a drawbridge or a gate) and the **Ruby
  Piston** (slide a wall, a door or a lift up to eight blocks), plus Elevators and Conveyor
  Belts.
- **Real damage:** bullets chip blocks, explosions blow holes, buildings that lose their
  supports **collapse** (heavy materials hold more), and fire spreads on Battle Day.
- **Building tools:** the Architect's Wand (walls, floors and rooms in two clicks), the
  Remodeler (swap a whole wall's material), Blueprints and a Constructor (copy a building),
  the Builder's Wand and the Excavator, and the **Tunnel Borer** for digging under an
  enemy base.

### Making rubies (industry)

Build machines on one shared power grid: generators and solar panels for power, Battery
Banks, a **Ruby Drill** that mines by itself and keeps what it finds until you collect it (or
run a conveyor belt from it to a chest), an **Ore Refinery** that doubles your ore, an Ammo
Factory, a **Trade Depot** that buys anything you put in it, and a **Ruby Vault** that holds
your bank (and pays a little interest). Every machine upgrades from Mk I to Mk V; conveyor
belts carry things from one to the next. **Belt Loaders** take things out of chests, furnaces
and machines (set one to an item to take only that), **Belt Sorters** turn one kind of
item off to the side, **Belt Splitters** share things left and right, **Belt Tunnels** carry
things under walls, and belts curve by themselves where one feeds the side of another (redstone
stops a belt; nobody can grab what's riding one), so you can automate a whole line: a chest of ore, sorted onto belts,
smelted in furnaces (belts put ore in the top and fuel below), and sold at the Trade Depot. Upgrade your **Ruby Heart** at the Armory (the
Your Heart tab) for a bigger claim, more health and room for more drills.
`/rubywars leaderboard` shows the top tycoons.

Mining and making: a drill's **Find** button picks what it looks for (everything, metals or
gems) and its **Stone** button leaves plain rock in the hole so it fills up slower; ore in the
ground around a drill (and some biomes) comes up more often, and its screen says what the ground
is rich in. Crouch and right-click a machine with an empty hand to take everything it made. The
Ore Refinery also cooks anything a furnace can and makes gunpowder from coal and sand; the
**Assembler** crafts ingots into blocks, torches, rails, TNT and more (pick a recipe from a
grid); the **Ore Silo** holds 128 stacks of one thing and shows it in its window (belts and
hoppers work with it, and it keeps its contents when picked up). The **Prospector's Pick**
chimes and sparkles toward the nearest Ruby Ore (crouch-click it to look for diamonds or any
ore). **Geodes** turn up from drills and mining (right-click to crack one open, crouch for
the whole stack), and the Field Manual has a **Rock Collection** that fills in as you find
each mineral; find all 16 for the Geode Glitter firework. My Base shows what your machines
made this season and what's in your silos.

While you're away, drills, Refineries, Assemblers, Ammo Factories and Trade Depots keep
working at half speed (for up to a day, even while nobody's on the server), and belt lines
carry what they make to the chest or machine at the end; when you're back one note says what
they all made, and My Base shows it too. A drill fills the **Ore Silos** touching it (each takes one kind, so
a few round a drill sort what it digs), and a Refinery or Assembler takes what it needs from a
silo touching it, with no belts needed. Machines picked up with the Wrench keep their settings, and it picks up Ore Silos with
what's in them. Look at a silo or machine to see what it feeds or fills. A **Practice Dummy**
(Armory, Defenses) stands in your base for a minute so you can watch your turrets and Tesla
Coils work before Battle Day.

### Weapons and vehicles

- **Guns:** pistol, six-shooter, rifle, shotgun, sniper, minigun, flamethrower, the Ruby
  Blaster and more, with attachments (extended mag, red dot, grip, suppressor). Full 3D
  models with aiming down sights, recoil and reloads.
- **Launchers and explosives:** rocket launcher, the Lancer (anti-tank, locks on), the
  Skyhammer (anti-aircraft), a swarm launcher, grenades, sticky C4, breaching charges,
  mortars, missile silos and cruise missiles, and air strike flares.
- **On the ground:** jeeps, tanks, dirt bikes and quads.
- **In the air:** a fighter jet, an attack jet, a bomber and an attack helicopter, with a
  full pilot HUD (they take off from runways, so build an airfield).
- **On the water:** a patrol boat, a gunboat with a deck gun, and a landing craft that
  carries a squad and a jeep and drops its ramp on a beach.
- **Gear:** jetpacks, grappling hooks, night-vision goggles, backpacks, Field Radios
  (voice chat at any range), recon drones and hired guards.

### Battle Day extras

Night battles (the admin can switch them on) with searchlights, flares and night-vision
goggles; sandstorms and blizzards over the desert and snow corners; and, while you build,
world events (a Supply Cache that opens in waves, a Ruby Shrine to hold alone, a Ruby Meteor), marked by a
beacon's beam and told to everyone two minutes ahead, that draw everyone out to fight over them; **War Graves** that keep your
gear where you fell; and a kill feed, hit markers and kill-cam.

### Goals and upgrades

Press **J** for goals (upgrade your Heart, sell at a Trade Depot, get to a Supply Cache, make it to Sudden Death, sail 500 blocks…).
Each earns stars to spend on unlocking advanced gear in four branches (Arms, Vehicles,
Defense, Support). Upgrades come from doing things, never from how long you play.

### Other games

| Game | How to play | What it is |
| --- | --- | --- |
| **Co-op Horde** | green portal, `/play horde` | Everyone builds one base around the Ruby Core and holds off bigger and bigger waves of monsters and soldiers. Shared team bank, an Armory, Easy/Normal/Hard. |
| **Ruby Rift** | pink portal, `/play rift` | Co-op roguelike for 1-4: fight down through three acts of rooms, pick a relic after each one that changes how you play, beat the bosses. |
| **Ruby Convoy** | orange portal, `/play convoy` | Co-op for 1-4: escort the ruby truck through five stages of enemy country to the vault, picking a job and convoy upgrades on the way. |
| **Coaster Workshop** | `/play coasters` | **Ruby Coaster Park**: everyone gets their own spot (up to ten) along one themed main street and builds a roller coaster there, piece by piece: loops, corkscrews, banked turns, launches, splashdowns. Change any piece later, preview it before it goes in, name it, set laps, and ride everyone's coasters in three-car trains (press Ride! in the plaza to pick one). |
| **Free Roam** | `/play freeroam` | Try every gun, vehicle and aircraft, no rules. |
| **Party Board** | `/play board` | A board game: roll the dice, collect coins, buy stars, a minigame every round. Computer players fill empty seats. |
| **Pie Mystery** | `/play pie` | Someone is secretly pieing people: find the clues and catch the Prankster. |
| **Gun Game** | `/play gungame` | Each kill moves you to the next weapon; win with a sword kill. Bots can fill in. |
| **Holo Hide & Seek** | `/play hideseek` | Hiders turn into blocks; seekers find them. |
| **Infection** | `/play infection` | One zombie at the start; anyone caught joins them. Survivors hold out for 4 minutes. |
| **Capture the Flag** | `/play ctf` | Red vs Blue, build defenses, then grab their flag. Bots can fill in. |
| **King of the Hill** | `/play koth` | Hold the moving gold zone to score. Bots can fill in. |
| **Tank Battle** | `/play tanks` | Red vs Blue in tanks. |
| **Air Battle** | `/play air` | Red vs Blue in jets and helicopters. |
| **Sky Race** | `/play skyrace` | Race through rings by jetpack, elytra or ice boat. |
| **Build Battle** | `/play build` | Everyone builds the same theme in 5 minutes, then votes. |
| **Ruby Rush** | `/play rush` | A quick battle royale on a small island. |

**Duels:** `/duel guns|vehicles|planes <player>` (or `/duel guns bot`) starts a 1v1 in its own
arena to try weapons without touching the season. Press **I** for your profile: stats from
every mode and cosmetics you earn by playing: armor looks, trails, kill effects, truck paint,
**Ruby Heart looks** (Frosted, Emerald, Lava, Crystal, Shadow, Golden) and **victory fireworks**
that go up when you win a duel or a party game (and a big show over the hub for a season win).
Every Ruby Wars season you play adds a gold **veteran star** after your name, and each season's
champion gets a **statue** in the Hall of Champions.

---

## Controls

| Action | How |
| --- | --- |
| Fire | Left-click (hold for automatic guns) |
| Aim / lock on | Hold right-click |
| Reload | R (rebind under *Controls → Ruby Wars*) |
| Throw a grenade / C4 | Right-click (sneak + right-click detonates C4) |
| Get in / out of a vehicle | Right-click it / Sneak |
| Drive | W/S and A/D; in a tank, look to aim the turret, Space for smoke |
| Fly a jet | Full throttle down a runway, look up when the HUD says ROTATE; then look where you want to go. R switches weapon, X flares, G landing gear, Z autopilot, Sneak ejects |
| Fly the helicopter | W/S forward and back, A/D strafe, Space up, Ctrl down |
| Free look in a vehicle | Hold Left Alt |
| Ping / spot an enemy | B |
| Map | K (the minimap is top right; U hides it) |
| Goals & upgrades | J |
| Field Manual (in-game guide) | Y |
| Ruby Wars menu (map, guide, profile, manual, games, settings) | M |
| Game picker | H |
| Profile | I |
| How-to card for a game or vehicle | N (show / hide) |
| Park map in the hub (fly anywhere) | , (comma) |
| Knocked out: watch the others | Click next player, right-click the one before, crouch to fly free |

---

## For server admins

### Starting a season

Make yourself an operator (`op <yourname>` in the server console), then type
**`/rubywars admin`** in game: it shows the season at a glance with buttons to click.

- `/rubywars start` starts the season. Battle Day comes **when you say** (the default);
  or `/rubywars start 1week`, `2weeks`, or `days <n>` to set a date.
- `/rubywars schedule untilisay|1week|2weeks|days <n>` changes the date mid-season
  (counted from now). Everyone gets a heads-up the day before and an hour before.
- `/rubywars battle` starts Battle Day now, after a one-minute countdown
  (`/rubywars battle 300` for a longer one, `/rubywars battle cancel` to call it off).
- `/rubywars reset confirm` ends the season and goes back to the lobby for the next one.
  Everyone starts fresh in Ruby Wars (their Ruby Wars gear is cleared); the hub, party games,
  Coaster Park, stats and the Hall of Champions are kept.
- **A fresh island too:** on a Linux server, `rubywars newseason` does the reset *and* clears
  the old bases off the map (the old island is moved to `/opt/rubywars/old-worlds`, nothing is
  deleted, and a fresh one is made). `rubywars newseason everything` goes all the way back to
  Season 1 as well (no past champions, no season wins); `/rubywars reset everything confirm`
  does that part in game.

Other admin commands (all start with `/rubywars`): `status`, `phase <name>`, `timer <minutes>`,
`pause`, `resume`, `reload` (re-read the config), `backup`, `revive <player>`,
`nightbattle on|off`, `event cache|shrine|meteor|stop`, `research <player> <stars>`, `kit` (every item, for
testing), `training` (build a practice ground here), `rearm` (refill your aircraft),
`coasters` (who has which Coaster Park spot; `coasters free <name>` gives one up), and
`selftest` (rule checks, on a test world only).

Everything else (phase lengths, prices, claim sizes, the world border, how often world events come and more)
is in `config/rubywars.json`.

### Running a server on Linux (Debian/Ubuntu)

One script installs Java 25, builds the mod, downloads Fabric and the server mods, and sets
up a service that starts on boot:

    curl -fsSL https://raw.githubusercontent.com/gregrose0929/ruby-wars/main/tools/setup-server.sh -o setup-server.sh
    sudo MEMORY=6G OP=YourName bash setup-server.sh

Afterwards: `rubywars console` (Ctrl-A then D to leave), `rubywars cmd "<command>"`,
`rubywars logs`, `rubywars restart`, and **`rubywars update`** to pull and install the latest
version. The server lives in `/opt/rubywars/server`, backs the world up every 24 hours (the
newest 7 are kept), and runs Chunky for pre-generating terrain
(`rubywars cmd "chunky start"`).

### Running a server by hand (any OS)

1. Install Java 25 (for example Temurin 25 from adoptium.net).
2. Download the Fabric **server** launcher for Minecraft 26.3 from https://fabricmc.net/use/server/
   into an empty folder and run it: `java -Xmx6G -jar fabric-server-launch.jar nogui`.
   Accept the EULA in `eula.txt` and start it again.
3. Put `rubymod-1.0.0.jar` and Fabric API (for 26.3) in `mods/`. For voice chat, add Simple
   Voice Chat too and open UDP port 24454. Lithium and FerriteCore help the server run smoothly.
4. For the season map, set `level-type=rubymod\:ruby_wars` in `server.properties` before the
   world is first created.
5. Recommended: `view-distance=16` and `simulation-distance=10`, and pre-generate the map
   with Chunky (`/chunky radius 1500`, then `/chunky start`).

Friends outside your home network need port 25565 (TCP) forwarded on your router, or a
tunnel such as playit.gg.

---

## For developers

Requires JDK 25.

    ./gradlew build                                      # the jar, in build/libs/
    ./gradlew runClient                                  # a dev client
    tools/test-server.sh tools/tests/selftest.txt --fresh  # headless server + /rubywars selftest
    tools/client-test.sh <mode>                          # scripted client run; screenshots in run/screenshots/
    python tools/make_mrpack.py                          # dist/ruby-wars-<version>.mrpack
    tools/release.sh "notes"                             # publish a release on GitHub (pack + installer)
    tools/make-training-world.sh                         # dist/Ruby Wars Training/

Art and sounds are generated by the scripts in `tools/` (`textures.py`, `gen_assets.py`,
`gun_models.py`, `vehicle_models.py`, `park_models.py`, `sounds.py` and friends): edit them and
run them from the project root. The Windows installer's source is in `tools/installer/`.
Players' game state lives in the world folder under `world/rubywars/` (season, stats, saved
coasters and so on).
