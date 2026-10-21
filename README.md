# Ruby Wars

A Fabric mod for Minecraft 26.3 that turns a server into a two-week season:
build a fortified base for 14 days, then survive one Battle Day of guns,
mortars, and cruise missiles. The last player standing wins.

Design doc: https://claude.ai/code/artifact/7412ee76-bb2f-47f6-8045-6372edb0be5d

## How a season works

1. **Lobby.** Players join. An admin runs `/rubywars start`.
2. **Build phase (14 days).** Everyone gets a Ruby Heart, a pistol, and some ammo.
   Place the Heart to claim the land around it (49x49 blocks at tier 1). Nobody
   else can build, break, or open things in your claim, PvP is off, and explosions
   can't damage claims. Mine Ruby Ore (rarer than diamonds, below Y 0), and spend
   rubies at an Armory.
3. **Battle Day.**
   - **Lockdown (15 min):** claims open, defenses switch on, PvP still off.
   - **Open War (60 min):** PvP on. Hearts can be destroyed with Breaching Charges.
     While your Heart stands you respawn at it; once it's gone, your next death
     eliminates you. Supply drops land every 15 minutes.
   - **Sudden Death (20 min):** every Heart shatters, no more respawns, and the
     world border shrinks from 3000 to 200 blocks.
   - **Final Circle:** everyone glows and artillery shells random spots every
     15 seconds, so nobody can hide in a bunker. Last one alive wins.

During the build phase everyone also gets three **daily bounties** (kill monsters,
land gun hits, mine stone or ores) that pay rubies. See them with `/rubywars bounties`.
Fire and lava can't spread into claims until Battle Day.

All timings, prices, and sizes are in `config/rubywars.json`.

## Controls

| Action | How |
| --- | --- |
| Fire a gun or rocket launcher | Left-click (hold for automatic guns) |
| Aim down sights | Hold right-click (the sniper shows a full scope) |
| Reload | R (rebindable under Controls > Ruby Wars) |
| Throw grenade / flare | Right-click |
| Mark a target for mortars and missiles | Right-click with a Rangefinder |
| Fire a mortar / launch a missile | Right-click the Mortar or Missile Silo holding shells or a missile |
| Load a turret | Right-click it with Heavy Ammo |
| Recharge an interceptor | Right-click it with a Ruby |
| Deploy a vehicle | Right-click the ground with a Jeep, Tank, or Jet item |
| Get in / drive / get out | Right-click the vehicle / W-S drive, A-D steer / Sneak |
| Aim and fire the tank cannon | Look to turn the turret, left-click to fire |
| Fly the jet | W/S throttle, look to steer, left-click missiles, Space drops a bomb, Sneak to eject (you get a parachute) |

## What's in the Armory

Guns: Service Pistol, AR-R Rifle, Combat Shotgun, Longshot Sniper, and the Ruby
Blaster. Throwables: frag and smoke grenades. Raid gear: Rocket
Launcher, Rangefinder, Breaching Charge, Mortar, Missile Silo, Cruise Missile.
Defenses: Sandbags, Concrete Wall (takes 4 blasts), Reinforced Concrete (ignores
grenades), Blast Door (only you can open it), Sentry Turret, Missile Interceptor,
Radar Station, Alarm Block, Claymore, Landmine, Barbed Wire. Tools: Builder's
Wand, Excavator Pickaxe, and Heart upgrades (bigger claim, more HP).

Ammo can also be crafted (copper, iron, and gunpowder; check the recipe book).
Killing 5 players in a row earns an Air Strike Flare.

**Gun attachments** (Armory, row 5): Extended Mag (+50% magazine), Red Dot Sight
(tighter spread, more zoom), Vertical Grip (-40% recoil), and Suppressor (quiet,
no tracers, hides the sniper glint). Hold the attachment in your main hand and the gun
in your off hand, then right-click. Each gun takes one of each.

Vehicles: the **Jet** (fast, needs speed to take off, stalls if it slows down in
the air, 8 wing missiles that reload over time, bombs), the **Jeep** (fast, 4 seats),
and the **Tank** (slow, 2 seats, a turret that follows your aim, main
cannon, nearly bulletproof). Bullets barely scratch them; rockets, mines, and
explosions are how you take them out. Ruby armor, sword, and pickaxe sit between
iron and diamond.

## Combat details

- Guns are hitscan with **lag compensation**: the server checks your shot against
  where targets were on your screen (up to 300 ms back), so 100 ms of ping doesn't
  make you miss.
- After respawning at your Heart you get 4 seconds of **spawn protection**. It ends
  the moment you fire.
- A **kill feed** in the top-right shows who killed whom, with which weapon, and
  headshots.

## Commands

Everyone: `/rubywars` (status), `/rubywars heart`, `/rubywars bounties`, `/rubywars trust <player>`,
`/rubywars untrust <player>`, `/rubywars champions`.

Admins: `start`, `phase <lobby|build|lockdown|war|sudden_death|final_circle|ended>`,
`timer <minutes>`, `pause`, `resume`, `reload` (config), `backup`, `revive <player>`,
`reset confirm` (new season), `kit` (every gun, ammo, raid gear, attachments,
vehicles, and ruby armor, for testing), `selftest` (rule checks; use on a test world).

## Running the server (Linux)

1. Install Java 25 (your distro's OpenJDK 25 package, or Temurin 25 from adoptium.net).
2. Download the Fabric server launcher for Minecraft 26.3 from https://fabricmc.net/use/server/
   into an empty folder, and run it once: `java -Xmx6G -jar fabric-server-launch.jar nogui`.
   Accept the EULA in `eula.txt`.
3. Put `rubymod-1.0.0.jar` and Fabric API (for 26.3) in the server's `mods/` folder.
4. Start it again. Give yourself op (`op <name>` in the console), then `/rubywars start`
   when everyone's ready.
5. Players need the mod too. The easiest way: send them `dist/ruby-wars-1.0.0.mrpack`
   (built with `python tools/make_mrpack.py`). They import it in Modrinth App or
   Prism Launcher, which installs Fabric, Fabric API, and Ruby Wars in one click.

The mod writes a zipped world backup to `backups/` every 24 hours and keeps the
newest 7. Season state lives in `world/rubywars/season.json`.

To test a full season quickly, set short times in `config/rubywars.json`
(for example `"buildPhaseMinutes": 10`), run `/rubywars reload`, then `/rubywars start`.

## Building

Requires JDK 25.

    ./gradlew build        # jar in build/libs/
    ./gradlew runClient    # dev client
    tools/test-server.sh tools/tests/smoke.txt --fresh   # headless server test + /rubywars selftest
    python tools/make_mrpack.py   # dist/ruby-wars-<version>.mrpack for players
    RUBYMOD_AUTOTEST=1 ./gradlew runClient --args="--quickPlaySingleplayer autotest"
                           # scripted client run that saves screenshots to run/screenshots/

Art is generated: edit the pixel grids in `tools/textures.py` and models/data in
`tools/gen_assets.py`, then run them from the project root. `tools/recolor.py`
makes the ruby armor and tool textures by recoloring Minecraft's diamond ones;
swap in original art before publishing the mod publicly.
