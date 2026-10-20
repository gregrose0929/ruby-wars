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
   - **Final Circle:** everyone glows. Last one alive wins.

All timings, prices, and sizes are in `config/rubywars.json`.

## Controls

| Action | How |
| --- | --- |
| Fire | Right-click (hold for automatic guns) |
| Aim / zoom | Sneak |
| Reload | R (rebindable under Controls > Ruby Wars) |
| Throw grenade / flare | Right-click |
| Mark a target for mortars and missiles | Right-click with a Rangefinder |
| Fire a mortar / launch a missile | Right-click the Mortar or Missile Silo holding shells or a missile |
| Load a turret | Right-click it with Heavy Ammo |
| Recharge an interceptor | Right-click it with a Ruby |

## What's in the Armory

Guns: Service Pistol, Viper SMG, Combat Shotgun, AR-R Rifle, Longshot Sniper,
Bulldog LMG, Ruby Blaster. Throwables: frag and smoke grenades. Raid gear: Rocket
Launcher, Rangefinder, Breaching Charge, Mortar, Missile Silo, Cruise Missile.
Defenses: Sandbags, Concrete Wall (takes 4 blasts), Reinforced Concrete (ignores
grenades), Blast Door (only you can open it), Sentry Turret, Missile Interceptor,
Radar Station, Alarm Block, Claymore, Landmine, Barbed Wire. Tools: Builder's
Wand, Excavator Pickaxe, and Heart upgrades (bigger claim, more HP).

Ammo can also be crafted (copper, iron, and gunpowder; check the recipe book).
Killing 5 players in a row earns an Air Strike Flare.

## Commands

Everyone: `/rubywars` (status), `/rubywars heart`, `/rubywars trust <player>`,
`/rubywars untrust <player>`, `/rubywars champions`.

Admins: `start`, `phase <lobby|build|lockdown|war|sudden_death|final_circle|ended>`,
`timer <minutes>`, `pause`, `resume`, `reload` (config), `backup`, `revive <player>`,
`reset confirm` (new season), `selftest` (rule checks; use on a test world).

## Running the server (Linux)

1. Install Java 25 (your distro's OpenJDK 25 package, or Temurin 25 from adoptium.net).
2. Download the Fabric server launcher for Minecraft 26.3 from https://fabricmc.net/use/server/
   into an empty folder, and run it once: `java -Xmx6G -jar fabric-server-launch.jar nogui`.
   Accept the EULA in `eula.txt`.
3. Put `rubymod-1.0.0.jar` and Fabric API (for 26.3) in the server's `mods/` folder.
4. Start it again. Give yourself op (`op <name>` in the console), then `/rubywars start`
   when everyone's ready.
5. Players need the same two jars in their own `mods/` folder (or a modpack).

The mod writes a zipped world backup to `backups/` every 24 hours and keeps the
newest 7. Season state lives in `world/rubywars/season.json`.

To test a full season quickly, set short times in `config/rubywars.json`
(for example `"buildPhaseMinutes": 10`), run `/rubywars reload`, then `/rubywars start`.

## Building

Requires JDK 25.

    ./gradlew build        # jar in build/libs/
    ./gradlew runClient    # dev client
    tools/test-server.sh tools/tests/smoke.txt --fresh   # headless server test + /rubywars selftest

Art is generated: edit the pixel grids in `tools/textures.py` and models/data in
`tools/gen_assets.py`, then run them from the project root.
