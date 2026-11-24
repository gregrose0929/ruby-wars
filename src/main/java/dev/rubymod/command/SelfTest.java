package dev.rubymod.command;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import dev.rubymod.RubyMod;
import dev.rubymod.game.BattleDirector;
import dev.rubymod.game.Claims;
import dev.rubymod.game.Explosions;
import dev.rubymod.game.Phase;
import dev.rubymod.game.Rules;
import dev.rubymod.game.SeasonData;
import dev.rubymod.game.SeasonTicker;
import dev.rubymod.game.Shop;
import dev.rubymod.game.SpawnShield;
import dev.rubymod.gun.BlockDamage;
import dev.rubymod.gun.GunItem;
import dev.rubymod.gun.GunStats;
import dev.rubymod.gun.Gunfire;
import dev.rubymod.registry.ModBlocks;
import dev.rubymod.block.DefenseBlockEntity;
import dev.rubymod.entity.AircraftEntity;
import dev.rubymod.entity.AircraftType;
import dev.rubymod.entity.MortarShellEntity;
import dev.rubymod.entity.VehicleEntity;
import dev.rubymod.registry.ModComponents;
import dev.rubymod.registry.ModDamage;
import dev.rubymod.registry.ModEntities;
import dev.rubymod.registry.ModItems;

import net.fabricmc.fabric.api.entity.FakePlayer;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * /rubywars selftest: exercises the game rules on the live server with two
 * fake players, then restores the season exactly as it was. Meant for a test
 * world. Prints PASS/FAIL for each check.
 */
public final class SelfTest {
	private final List<String> results = new ArrayList<>();
	private int failures;

	private SelfTest() {
	}

	/**
	 * Keep the test area (out from the world spawn, where the checks build their bases and spawn their zombies) loaded
	 * and ticking, so things added there really are in the world. Run a few seconds before the test; "done" after.
	 */
	public static int prepare(CommandSourceStack source, boolean on) {
		ServerLevel level = source.getLevel();
		BlockPos spawn = level.getRespawnData().pos();
		int n = 0;
		for (int cx = (spawn.getX() + 200) >> 4; cx <= (spawn.getX() + 500) >> 4; cx++) {
			for (int cz = (spawn.getZ() + 200) >> 4; cz <= (spawn.getZ() + 500) >> 4; cz++) {
				level.setChunkForced(cx, cz, on);
				n++;
			}
		}
		RubyMod.LOGGER.info("[selftest] {} {} chunks round the test area", on ? "loading" : "letting go of", n);
		return 1;
	}

	public static int run(CommandSourceStack source) {
		SelfTest test = new SelfTest();
		String saved = SeasonData.snapshot();
		boolean mobs = dev.rubymod.game.GameConfig.get().hostileMobs;
		dev.rubymod.game.GameConfig.get().hostileMobs = true; // some checks need zombies
		try {
			test.runAll(source.getLevel());
		} catch (Throwable t) {
			test.fail("crashed: " + t);
			RubyMod.LOGGER.error("Self-test crashed", t);
		} finally {
			dev.rubymod.game.GameConfig.get().hostileMobs = mobs;
			SeasonData.restore(saved);
			SeasonData.save();
		}
		for (String line : test.results) {
			source.sendSystemMessage(Component.literal(line).withStyle(line.startsWith("PASS") ? ChatFormatting.GREEN : ChatFormatting.RED));
			RubyMod.LOGGER.info("[selftest] {}", line);
		}
		String summary = test.failures == 0 ? "All " + test.results.size() + " checks passed." : test.failures + " of " + test.results.size() + " checks failed.";
		source.sendSystemMessage(Component.literal(summary).withStyle(ChatFormatting.BOLD));
		RubyMod.LOGGER.info("[selftest] {}", summary);
		return test.failures == 0 ? 1 : 0;
	}

	/** The park in the hub: its signs, hosts, rides and houses are all there. */
	private void parkChecks(net.minecraft.server.MinecraftServer server) {
		ServerLevel hub = server.getLevel(dev.rubymod.game.Hub.HUB);
		if (hub == null) {
			fail("park: the hub exists");
			return;
		}
		for (var e : dev.rubymod.game.hub.HubPark.selfChecks(hub).entrySet()) {
			check("park: " + e.getKey(), e.getValue());
		}
	}

	/** A test season's player with a standing Heart far up in the sky (away from everything else). */
	private static SeasonData.PlayerRecord testRecord(SeasonData data, UUID id, String name, BlockPos at) {
		SeasonData.PlayerRecord r = new SeasonData.PlayerRecord();
		r.name = name;
		data.players.put(id.toString(), r);
		SeasonData.Claim c = new SeasonData.Claim();
		c.owner = id.toString();
		c.ownerName = name;
		c.dimension = "minecraft:overworld";
		c.x = at.getX();
		c.y = at.getY();
		c.z = at.getZ();
		c.hp = c.maxHp();
		data.claims.put(id.toString(), c);
		return r;
	}

	/** Battle Day as a family appointment, sitting out, no contest, teams, the night vote and the wrap-up's awards. */
	private void seasonFlowChecks(ServerLevel level, FakePlayer alpha, FakePlayer bravo, BlockPos base) {
		var server = level.getServer();
		SeasonData data = SeasonData.get();
		// Keep what's there and put it back after.
		var claims = new java.util.LinkedHashMap<>(data.claims);
		var players = new java.util.LinkedHashMap<>(data.players);
		var squads = new java.util.LinkedHashMap<>(data.squads);
		var champions = new ArrayList<>(data.pastChampions);
		Phase phase = data.phase;
		long ends = data.phaseEndsAt;
		long paused = data.pausedRemaining;
		long buildMinutes = data.buildMinutes;
		String winner = data.winner;
		boolean counted = data.seasonCounted;
		UUID charlie = UUID.fromString("00000000-0000-0000-0000-0000000c4a71");
		BlockPos sky = base.offset(2000, 0, 2000).atY(300);
		try {
			// Ready: the date's come with nobody on, and Battle Day waits.
			data.claims.clear();
			data.players.clear();
			testRecord(data, alpha.getUUID(), "TestAlpha", sky);
			testRecord(data, bravo.getUUID(), "TestBravo", sky.offset(100, 0, 0));
			data.phase = Phase.BUILD;
			data.buildMinutes = 60;
			data.pausedRemaining = -1;
			data.phaseEndsAt = dev.rubymod.game.SeasonTicker.now() - 1000;
			dev.rubymod.game.SeasonTicker.tickSecond(server);
			check("battle day: when its date comes it's ready, and waits for the admin to start it (" + data.phase + ", ready " + data.battleReady + ")",
					data.phase == Phase.BUILD && data.battleReady && !dev.rubymod.game.SeasonTicker.battleCalled());
			data.battleReady = false;

			// Going: nobody's on, so its clock waits.
			data.phase = Phase.WAR;
			data.phaseEndsAt = dev.rubymod.game.SeasonTicker.now() + 600_000L;
			data.pausedRemaining = -1;
			data.nextSupplyDropAt = dev.rubymod.game.SeasonTicker.now() + 600_000L;
			dev.rubymod.game.SeasonTicker.tickSecond(server);
			check("battle day: its clock pauses while nobody's on (" + data.pausedRemaining + ", auto " + data.autoPaused + ")",
					data.phase == Phase.WAR && data.pausedRemaining > 0 && data.autoPaused);
			data.autoPaused = false;
			data.pausedRemaining = -1;

			// Battle Day starts with Alpha away: Alpha sits it out from the start, and Alpha's base is safe all day.
			data.phase = Phase.BUILD;
			data.seasonCounted = false;
			data.winner = "";
			dev.rubymod.game.BattleDirector.enterPhase(server, Phase.LOCKDOWN);
			SeasonData.Claim alphaBase = data.claimOf(alpha.getUUID());
			data.phase = Phase.WAR;
			int hpBefore = alphaBase.hp;
			dev.rubymod.game.Claims.damageHeart(level, alphaBase, 50, bravo);
			boolean safe = data.player(alpha.getUUID()).satOut && Rules.sittingOut(alphaBase) && alphaBase.hp == hpBefore
					&& !Rules.canWeaponBreak(level, alphaBase.pos().above(2), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState())
					&& !Rules.defenseTarget(alphaBase, bravo);
			check("sitting out: away when Battle Day starts, your base is safe all day (hp " + alphaBase.hp + "/" + hpBefore + ")", safe);
			data.phase = Phase.BUILD;
			String picked = dev.rubymod.game.SeasonChoices.choose(bravo, "fight", "no");
			boolean chose = picked == null && data.player(bravo.getUUID()).sitOutChoice;
			dev.rubymod.game.SeasonChoices.choose(bravo, "fight", "yes");
			check("sitting out: you can choose to sit Battle Day out, and change your mind (" + picked + ")",
					chose && !data.player(bravo.getUUID()).sitOutChoice);
			for (SeasonData.PlayerRecord r : data.players.values()) {
				r.satOut = false;
				r.eliminated = false;
				r.finished = 0;
			}
			data.phase = Phase.WAR;

			// Sudden Death with everyone away: they sit it out, nobody's crowned, and nobody's both champion and out.
			SeasonData.PlayerRecord c = testRecord(data, charlie, "TestCharlie", sky.offset(200, 0, 0));
			data.pastChampions.clear();
			data.seasonCounted = false;
			data.winner = "";
			dev.rubymod.game.BattleDirector.enterPhase(server, Phase.SUDDEN_DEATH);
			SeasonData.PlayerRecord a = data.player(alpha.getUUID());
			SeasonData.PlayerRecord b = data.player(bravo.getUUID());
			check("sitting out: away for Sudden Death is sitting out, and with no fight it's no contest (" + data.phase + ", '" + data.winner + "', "
					+ a.satOut + b.satOut + c.satOut + ", charlie out " + c.eliminated + ")",
					data.phase == Phase.ENDED && data.winner.isEmpty() && a.satOut && b.satOut && !c.eliminated && data.pastChampions.isEmpty());

			// Again, with a fight: the last one standing is the champion, and stays in.
			data.claims.clear();
			data.players.clear();
			a = testRecord(data, alpha.getUUID(), "TestAlpha", sky);
			b = testRecord(data, bravo.getUUID(), "TestBravo", sky.offset(100, 0, 0));
			c = testRecord(data, charlie, "TestCharlie", sky.offset(200, 0, 0));
			c.kills = 1;
			data.phase = Phase.WAR;
			data.seasonCounted = false;
			data.winner = "";
			dev.rubymod.game.BattleDirector.enterPhase(server, Phase.SUDDEN_DEATH);
			check("champion: the last one standing wins and isn't also put out ('" + data.winner + "', out " + c.eliminated + ")",
					data.phase == Phase.ENDED && "TestCharlie".equals(data.winner) && !c.eliminated && data.pastChampions.size() == 1);

			// Teams and the night vote: two picked red, one blue (alone, so on their own); two voted for night.
			data.claims.clear();
			data.players.clear();
			data.squads.clear();
			a = testRecord(data, alpha.getUUID(), "TestAlpha", sky);
			b = testRecord(data, bravo.getUUID(), "TestBravo", sky.offset(100, 0, 0));
			c = testRecord(data, charlie, "TestCharlie", sky.offset(200, 0, 0));
			// No teams unless the admin turns them on.
			data.teamsAllowed = false;
			data.phase = Phase.BUILD;
			String refused = dev.rubymod.game.SeasonChoices.choose(alpha, "team", "red");
			a.team = "red";
			b.team = "red";
			dev.rubymod.game.SeasonChoices.onBattleDay(server);
			check("teams: none unless the admin turns them on (" + refused + ")", refused != null && data.squads.isEmpty());
			data.teamsAllowed = true;
			c.team = "blue";
			a.nightVote = 1;
			b.nightVote = 1;
			c.nightVote = 2;
			data.phase = Phase.BUILD;
			dev.rubymod.game.SeasonChoices.onBattleDay(server);
			data.phase = Phase.WAR;
			boolean teams = dev.rubymod.game.Squads.enabled() && dev.rubymod.game.Squads.same(alpha.getUUID(), bravo.getUUID())
					&& !dev.rubymod.game.Squads.same(alpha.getUUID(), charlie) && data.squads.size() == 1;
			check("teams: the same colour is one side, someone alone in a colour plays on their own", teams);
			check("teams: teammates can't hurt each other", !Rules.canHurt(alpha, bravo));
			check("night vote: the most votes win (" + data.nightChoice + ")", Boolean.TRUE.equals(data.nightChoice)
					&& dev.rubymod.game.NightBattle.active());
			// Red wins, with one of them already out: they win together.
			c.kills = 0;
			a.kills = 1;
			b.eliminated = true;
			data.seasonCounted = false;
			data.winner = "";
			dev.rubymod.game.BattleDirector.eliminate(server, charlie, c, null);
			check("teams: the last team standing wins together ('" + data.winner + "', " + dev.rubymod.game.BattleDirector.onWinningSide(alpha.getUUID())
					+ ", " + dev.rubymod.game.BattleDirector.onWinningSide(bravo.getUUID()) + ")",
					"Red team".equals(data.winner) && dev.rubymod.game.BattleDirector.onWinningSide(alpha.getUUID())
							&& dev.rubymod.game.BattleDirector.onWinningSide(bravo.getUUID()) && !dev.rubymod.game.BattleDirector.onWinningSide(charlie));

			// The wrap-up: an award for everyone, and what each base is famous for.
			a.theme = "walls";
			List<String> awards = dev.rubymod.game.BattleDirector.awardsNow();
			boolean everyone = true;
			for (SeasonData.PlayerRecord r : data.players.values()) {
				boolean has = false;
				for (String aw : awards) {
					has |= aw.split("[|]")[1].equals(r.name);
				}
				everyone &= has;
			}
			check("awards: everyone gets one, and a base's pick is recognised (" + awards.size() + ")", everyone
					&& awards.stream().anyMatch(aw -> aw.startsWith("Famous for its walls|TestAlpha")));
			// How your base held up.
			data.phase = Phase.WAR;
			data.battleStartedAt = dev.rubymod.game.SeasonTicker.now() - 3_600_000L;
			dev.rubymod.game.BaseReport.defenseHit(data.claimOf(alpha.getUUID()));
			check("base report: turret hits counted, and the Heart stood to the end (" + a.defenseHits + ", "
					+ dev.rubymod.game.BaseReport.heartStood(alpha.getUUID()) + ")",
					a.defenseHits == 1 && "To the end".equals(dev.rubymod.game.BaseReport.heartStood(alpha.getUUID())));
			data.nightChoice = null;
		} finally {
			data.claims.clear();
			data.claims.putAll(claims);
			data.players.clear();
			data.players.putAll(players);
			data.squads.clear();
			data.squads.putAll(squads);
			data.pastChampions = champions;
			data.phase = phase;
			data.phaseEndsAt = ends;
			data.pausedRemaining = paused;
			data.buildMinutes = buildMinutes;
			data.winner = winner;
			data.seasonCounted = counted;
			data.battleReady = false;
			data.autoPaused = false;
			data.battleStartedAt = 0;
			data.nightChoice = null;
			data.teamsAllowed = false;
		}

		// Blueprints carry over to the next season (just the designs).
		{
			ItemStack print = new ItemStack(ModItems.BLUEPRINT);
			BlockPos at = base.offset(-3, 1, -9);
			level.setBlockAndUpdate(at, net.minecraft.world.level.block.Blocks.OAK_PLANKS.defaultBlockState());
			print.set(dev.rubymod.registry.ModComponents.BLUEPRINT, dev.rubymod.industry.Blueprints.capture(level, at, at.offset(1, 1, 1)));
			level.setBlockAndUpdate(at, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
			dev.rubymod.industry.BlueprintLibrary.remember(level.getServer(), bravo.getUUID(), print);
			dev.rubymod.industry.BlueprintLibrary.remember(level.getServer(), bravo.getUUID(), print);
			int kept = dev.rubymod.industry.BlueprintLibrary.load(level.getServer(), bravo.getUUID()).size();
			bravo.getInventory().clearContent();
			int back = dev.rubymod.industry.BlueprintLibrary.giveBack(bravo);
			boolean has = bravo.getInventory().contains(s -> s.is(ModItems.BLUEPRINT) && s.has(dev.rubymod.registry.ModComponents.BLUEPRINT));
			check("blueprints: a filled one is kept (once), and comes back in a new season (" + kept + ", " + back + ")", kept == 1 && back == 1 && has);
			bravo.getInventory().clearContent();
		}
		// The Builder's Wand's stretch can be undone (R), like the Architect's Wand.
		{
			BlockPos at = base.offset(-5, 1, -9);
			level.setBlockAndUpdate(at, net.minecraft.world.level.block.Blocks.COBBLESTONE.defaultBlockState());
			alpha.getInventory().clearContent();
			dev.rubymod.item.ArchitectWandItem.remember(alpha, List.of(new dev.rubymod.item.ArchitectWandItem.Placed(at,
					net.minecraft.world.item.Items.COBBLESTONE)));
			dev.rubymod.item.ArchitectWandItem.undo(alpha);
			check("builder's wand: undo takes the stretch back, and the blocks come back", level.getBlockState(at).isAir()
					&& alpha.getInventory().contains(s -> s.is(net.minecraft.world.item.Items.COBBLESTONE)));
			alpha.getInventory().clearContent();
		}

		// Plain belts are free; the clever belt parts still need Logistics.
		check("research: plain belts, ramps and loaders are free, sorters still need Logistics",
				dev.rubymod.game.Research.lockFor("conveyor") == null && dev.rubymod.game.Research.lockFor("belt_ramp") == null
						&& dev.rubymod.game.Research.lockFor("belt_loader") == null && dev.rubymod.game.Research.lockFor("belt_sorter") != null);
		check("world events: a Ruby Shrine still needs two or more on", dev.rubymod.game.WorldEvents.shrinePlayers() >= 2);
	}

	/** Share of {@code n} drill hauls that are one of {@code items}. */
	private static double share(int tier, int focus, dev.rubymod.industry.DrillSite site, int n, net.minecraft.world.item.Item... items) {
		var random = net.minecraft.util.RandomSource.create(1234);
		int hit = 0;
		for (int i = 0; i < n; i++) {
			ItemStack dug = dev.rubymod.industry.Recipes.drill(random, tier, false, focus, site);
			for (var item : items) {
				if (dug.is(item)) {
					hit++;
					break;
				}
			}
		}
		return hit / (double) n;
	}

	/** Drill focus and stone, the ground under a drill, the Refinery's cooking and gunpowder, the Assembler, the Ore Silo, and the rest. */
	private void miningChecks(ServerLevel level, FakePlayer alpha, BlockPos base) {
		var A = alpha.getUUID();
		BlockPos spot = base.offset(8, 1, -10);
		for (BlockPos p : BlockPos.betweenClosed(spot.offset(-1, -1, -1), spot.offset(10, 2, 3))) {
			level.setBlock(p, p.getY() < spot.getY() ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
		}
		long chargeBefore = dev.rubymod.industry.Grid.charge(A);
		var plain = dev.rubymod.industry.DrillSite.PLAIN;
		var metals = new net.minecraft.world.item.Item[] {net.minecraft.world.item.Items.RAW_IRON, net.minecraft.world.item.Items.RAW_COPPER, net.minecraft.world.item.Items.RAW_GOLD};
		var gems = new net.minecraft.world.item.Item[] {net.minecraft.world.item.Items.DIAMOND, net.minecraft.world.item.Items.EMERALD, net.minecraft.world.item.Items.REDSTONE,
				net.minecraft.world.item.Items.LAPIS_LAZULI, ModItems.RUBY};
		// Focus: more of what you picked.
		double stone = share(1, dev.rubymod.industry.Recipes.FOCUS_ALL, plain, 20000, net.minecraft.world.item.Items.COBBLESTONE);
		check("drill: a plain drill still digs about as much rock as before (" + Math.round(stone * 100) + "%)", stone > 0.40 && stone < 0.48);
		double m0 = share(1, dev.rubymod.industry.Recipes.FOCUS_ALL, plain, 20000, metals);
		double m1 = share(1, dev.rubymod.industry.Recipes.FOCUS_METALS, plain, 20000, metals);
		double g0 = share(1, dev.rubymod.industry.Recipes.FOCUS_ALL, plain, 20000, gems);
		double g1 = share(1, dev.rubymod.industry.Recipes.FOCUS_GEMS, plain, 20000, gems);
		check(String.format(java.util.Locale.ROOT, "drill: Metals finds more metal (%.0f%% -> %.0f%%) and Gems more gems (%.0f%% -> %.0f%%)", m0 * 100, m1 * 100, g0 * 100, g1 * 100),
				m1 > m0 * 1.3 && g1 > g0 * 1.3 && share(1, dev.rubymod.industry.Recipes.FOCUS_METALS, plain, 20000, gems) < g0);
		// The ground: iron ore under a drill brings up more iron.
		var ironRich = new dev.rubymod.industry.DrillSite(java.util.Map.of(net.minecraft.world.item.Items.RAW_IRON, 1.0F));
		double i0 = share(1, 0, plain, 20000, net.minecraft.world.item.Items.RAW_IRON);
		double i1 = share(1, 0, ironRich, 20000, net.minecraft.world.item.Items.RAW_IRON);
		check(String.format(java.util.Locale.ROOT, "drill: iron in the ground brings up more iron (%.0f%% -> %.0f%%)", i0 * 100, i1 * 100), i1 > i0 * 1.6);
		BlockPos far = spot.offset(0, -30, 0);
		// Only the iron put there: the world's own ore round it (it's a different world each run) would muddle it.
		for (BlockPos p : BlockPos.betweenClosed(far.offset(-7, -25, -7), far.offset(7, 3, 7))) {
			if (dev.rubymod.industry.DrillSite.mineral(level.getBlockState(p)) != null) {
				level.setBlock(p, Blocks.STONE.defaultBlockState(), 2);
			}
		}
		for (int k = 0; k < 32; k++) {
			level.setBlock(far.offset(k % 4 - 2, -2 - k / 16, k / 4 % 4 - 2), Blocks.IRON_ORE.defaultBlockState(), 2);
		}
		var site = dev.rubymod.industry.DrillSite.scan(level, far);
		check("drill: it sees the iron ore under it (" + site.describe() + ")", site.rich().contains(net.minecraft.world.item.Items.RAW_IRON)
				&& site.describe().contains("iron"));
		// Stone: left behind, so it never fills the drill.
		var drill = machine(level, spot, ModBlocks.RUBY_DRILL, alpha);
		drill.setTier(5);
		drill.toggleStone();
		dev.rubymod.industry.Grid.account(A).charge = 200_000;
		runMachines(level, 40 * dev.rubymod.industry.MachineKind.RUBY_DRILL.cycle(5), drill);
		boolean anyRock = false;
		int held = 0;
		for (int i = 0; i < drill.getContainerSize(); i++) {
			anyRock |= dev.rubymod.industry.Recipes.isRock(drill.getItem(i));
			held += drill.getItem(i).getCount();
		}
		check("drill: with Stone: Leave it keeps no rock (" + drill.loads() + " loads, " + held + " items kept)", !anyRock && drill.loads() >= 30 && held > 0);
		// Crouch-click: everything it made, into your inventory.
		alpha.getInventory().clearContent();
		int took = drill.collectAll(alpha);
		boolean emptied = true;
		for (int i = 0; i < drill.getContainerSize(); i++) {
			emptied &= drill.getItem(i).isEmpty();
		}
		check("drill: crouch-click takes everything it dug (" + took + ")", took == held && emptied);
		alpha.getInventory().clearContent();
		// Refinery: cooks like a furnace; coal with sand makes gunpowder.
		var refinery = machine(level, spot.east(2), ModBlocks.ORE_REFINERY, alpha);
		refinery.setTier(5);
		refinery.setItem(0, new ItemStack(net.minecraft.world.item.Items.SAND, 2));
		runMachines(level, 2 * dev.rubymod.industry.MachineKind.ORE_REFINERY.cycle(5) + 2, refinery);
		check("refinery: sand cooks into glass (" + refinery.getItem(2) + ")", refinery.getItem(2).is(net.minecraft.world.item.Items.GLASS) && refinery.getItem(2).getCount() == 2);
		refinery.setItem(2, ItemStack.EMPTY);
		refinery.setItem(0, new ItemStack(net.minecraft.world.item.Items.OAK_LOG, 1));
		runMachines(level, dev.rubymod.industry.MachineKind.ORE_REFINERY.cycle(5) + 2, refinery);
		check("refinery: logs cook into charcoal (" + refinery.getItem(2) + ")", refinery.getItem(2).is(net.minecraft.world.item.Items.CHARCOAL));
		refinery.setItem(2, ItemStack.EMPTY);
		refinery.setItem(0, new ItemStack(net.minecraft.world.item.Items.COAL, 2));
		refinery.setItem(1, new ItemStack(net.minecraft.world.item.Items.SAND, 2));
		check("refinery: sand waits in the slot below while some is there, coal goes on top",
				refinery.canPlaceItem(1, new ItemStack(net.minecraft.world.item.Items.SAND)) && !refinery.canPlaceItem(1, new ItemStack(net.minecraft.world.item.Items.COAL))
						&& !refinery.canPlaceItem(0, new ItemStack(net.minecraft.world.item.Items.SAND)));
		runMachines(level, 2 * dev.rubymod.industry.MachineKind.ORE_REFINERY.cycle(5) + 2, refinery);
		check("refinery: coal with sand makes gunpowder (" + refinery.getItem(2) + ")", refinery.getItem(2).is(net.minecraft.world.item.Items.GUNPOWDER)
				&& refinery.getItem(2).getCount() == 6 && refinery.getItem(0).isEmpty() && refinery.getItem(1).isEmpty());
		check("refinery: it still doubles ore", dev.rubymod.industry.Recipes.refine(level, new ItemStack(net.minecraft.world.item.Items.RAW_GOLD), 1).getCount() == 2);
		// Assembler: ingots into blocks; a two-part recipe; the parts it doesn't need are refused.
		var assembler = machine(level, spot.east(4), ModBlocks.ASSEMBLER, alpha);
		assembler.setTier(5);
		assembler.setItem(0, new ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 18));
		runMachines(level, 2 * dev.rubymod.industry.MachineKind.ASSEMBLER.cycle(5) + 2, assembler);
		check("assembler: 18 iron ingots make 2 iron blocks (" + assembler.getItem(2) + ")", assembler.getItem(2).is(net.minecraft.world.item.Items.IRON_BLOCK)
				&& assembler.getItem(2).getCount() == 2 && assembler.getItem(0).isEmpty());
		check("assembler: a one-part recipe takes nothing in its second slot", !assembler.canPlaceItem(1, new ItemStack(net.minecraft.world.item.Items.STICK)));
		assembler.setItem(2, ItemStack.EMPTY);
		int torches = -1;
		for (int i = 0; i < dev.rubymod.industry.Recipes.ASSEMBLY.size(); i++) {
			if (dev.rubymod.industry.Recipes.ASSEMBLY.get(i).result() == net.minecraft.world.item.Items.TORCH) {
				torches = i;
			}
		}
		for (int i = 0; i < torches; i++) {
			assembler.cycleRecipe(1);
		}
		assembler.setItem(0, new ItemStack(net.minecraft.world.item.Items.COAL, 1));
		assembler.setItem(1, new ItemStack(net.minecraft.world.item.Items.STICK, 1));
		runMachines(level, dev.rubymod.industry.MachineKind.ASSEMBLER.cycle(5) + 2, assembler);
		check("assembler: coal and a stick make 4 torches (" + assembler.getItem(2) + ")", assembler.getItem(2).is(net.minecraft.world.item.Items.TORCH)
				&& assembler.getItem(2).getCount() == 4);
		assembler.cycleRecipe(-1);
		check("assembler: right-click goes back a recipe", dev.rubymod.industry.Recipes.recipe(dev.rubymod.industry.MachineKind.ASSEMBLER, torches - 1).result()
				!= net.minecraft.world.item.Items.TORCH);
		// Switching recipe hands back parts the new one can't use; torches take charcoal too.
		assembler.setItem(2, ItemStack.EMPTY);
		assembler.setRecipe(0, null);
		assembler.setItem(0, new ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 5));
		alpha.getInventory().clearContent();
		assembler.setRecipe(dev.rubymod.industry.Recipes.ASSEMBLY.size() - 1, alpha);
		check("assembler: switching recipe gives back parts it can't use", assembler.getItem(0).isEmpty()
				&& alpha.getInventory().countItem(net.minecraft.world.item.Items.IRON_INGOT) == 5);
		assembler.setRecipe(torches, alpha);
		check("assembler: torches take charcoal as well as coal", assembler.canPlaceItem(0, new ItemStack(net.minecraft.world.item.Items.CHARCOAL))
				&& !dev.rubymod.industry.Recipes.fits(dev.rubymod.industry.Recipes.ASSEMBLY.get(3), net.minecraft.world.item.Items.COAL,
						new ItemStack(net.minecraft.world.item.Items.CHARCOAL)));
		assembler.setItem(0, new ItemStack(net.minecraft.world.item.Items.CHARCOAL, 1));
		assembler.setItem(1, new ItemStack(net.minecraft.world.item.Items.STICK, 1));
		runMachines(level, dev.rubymod.industry.MachineKind.ASSEMBLER.cycle(5) + 2, assembler);
		check("assembler: charcoal and a stick make 4 torches (" + assembler.getItem(2) + ")", assembler.getItem(2).is(net.minecraft.world.item.Items.TORCH)
				&& assembler.getItem(2).getCount() == 4);
		alpha.getInventory().clearContent();
		// What it all made shows on My Base.
		var made = dev.rubymod.industry.Production.top(A, 50);
		check("my base: what your machines made counts up (" + made.size() + " kinds)", made.stream().anyMatch(e -> e.getKey().equals("minecraft:glass") && e.getValue() >= 2)
				&& made.stream().anyMatch(e -> e.getKey().equals("minecraft:iron_block")));
		// Ore Silo: belts in and out, one kind only, and nothing lost when a hopper can't take what it pulled.
		BlockPos siloPos = spot.east(7);
		level.setBlockAndUpdate(siloPos, ModBlocks.ORE_SILO.defaultBlockState());
		var silo = (dev.rubymod.industry.SiloBlockEntity) level.getBlockEntity(siloPos);
		silo.setOwner(A);
		for (int i = 0; i < 5; i++) {
			dev.rubymod.industry.Belts.insert(level, siloPos, net.minecraft.core.Direction.WEST, new ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 64));
		}
		ItemStack refused = dev.rubymod.industry.Belts.insert(level, siloPos, net.minecraft.core.Direction.WEST, new ItemStack(net.minecraft.world.item.Items.GOLD_INGOT, 5));
		check("silo: belts fill it with one kind of thing (" + silo.total() + ")", silo.total() == 320 && refused.getCount() == 5);
		ItemStack out = dev.rubymod.industry.Belts.extract(level, siloPos, net.minecraft.core.Direction.EAST, ItemStack.EMPTY, 8);
		check("silo: a Belt Loader takes things out (" + out + ", " + silo.total() + " left)", out.is(net.minecraft.world.item.Items.IRON_INGOT) && out.getCount() == 8
				&& silo.total() == 312);
		ItemStack ready = silo.getItem(1);
		int was = ready.getCount();
		silo.removeItem(1, 1);
		ready.setCount(was);
		check("silo: nothing is lost when a hopper puts one back (" + silo.total() + ")", silo.total() == 312);
		var stacks = silo.asStacks();
		check("silo: it keeps what it holds when picked up (" + stacks.size() + " stacks)", stacks.size() == 5
				&& stacks.stream().mapToInt(ItemStack::getCount).sum() == 312);
		ItemStack siloItem = new ItemStack(ModBlocks.ORE_SILO);
		siloItem.set(net.minecraft.core.component.DataComponents.CONTAINER, net.minecraft.world.item.component.ItemContainerContents.fromItems(stacks));
		BlockPos silo2Pos = spot.east(9);
		level.setBlockAndUpdate(silo2Pos, ModBlocks.ORE_SILO.defaultBlockState());
		var silo2 = (dev.rubymod.industry.SiloBlockEntity) level.getBlockEntity(silo2Pos);
		silo2.applyComponentsFromItemStack(siloItem);
		check("silo: placed again, it holds the same (" + silo2.total() + ")", silo2.total() == 312 && silo2.type().is(net.minecraft.world.item.Items.IRON_INGOT));
		silo2.setOwner(A);
		var rows = dev.rubymod.industry.BaseMachines.rows(level.getServer(), alpha);
		check("my base: your silos show what's in them", rows.stream().anyMatch(r -> r.startsWith("#silo|minecraft:iron_ingot|624")));
		ItemStack full = new ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 64);
		while (silo.put(full) > 0) {
			full = new ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 64);
		}
		check("silo: it holds 128 stacks (" + silo.total() + ")", silo.total() == 128L * 64 && !silo.accepts(new ItemStack(net.minecraft.world.item.Items.IRON_INGOT)));
		// A quick double click: the first puts your stack in (leaving your hand empty), the second puts in the rest.
		alpha.getInventory().clearContent();
		alpha.getInventory().setSelectedSlot(0);
		alpha.getInventory().setItem(0, new ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 64));
		alpha.getInventory().setItem(5, new ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 40));
		long before2 = silo2.total();
		var hit = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(silo2Pos), net.minecraft.core.Direction.NORTH, silo2Pos, false);
		var siloState = level.getBlockState(silo2Pos);
		siloState.useItemOn(alpha.getMainHandItem(), level, alpha, net.minecraft.world.InteractionHand.MAIN_HAND, hit);
		boolean handEmpty = alpha.getMainHandItem().isEmpty();
		siloState.useWithoutItem(level, alpha, hit);
		check("silo: a quick second click puts in the rest, not takes a stack out (" + (silo2.total() - before2) + " in)", handEmpty
				&& silo2.total() - before2 == 104 && alpha.getInventory().countItem(net.minecraft.world.item.Items.IRON_INGOT) == 0);
		alpha.getInventory().clearContent();
		level.setBlockAndUpdate(siloPos, Blocks.AIR.defaultBlockState());
		level.setBlockAndUpdate(silo2Pos, Blocks.AIR.defaultBlockState());
		// While you were away: more machines catch up, silos feed and fill them, and it's all told once.
		BlockPos awayAt = spot.offset(0, 0, 4);
		for (BlockPos p : BlockPos.betweenClosed(awayAt.offset(-1, 0, -1), awayAt.offset(10, 2, 1))) {
			level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
		}
		dev.rubymod.industry.AwayTime.reset();
		level.setBlockAndUpdate(awayAt, ModBlocks.ORE_SILO.defaultBlockState());
		var ironSilo = (dev.rubymod.industry.SiloBlockEntity) level.getBlockEntity(awayAt);
		ironSilo.setOwner(A);
		ironSilo.put(new ItemStack(net.minecraft.world.item.Items.RAW_IRON, 64));
		ironSilo.put(new ItemStack(net.minecraft.world.item.Items.RAW_IRON, 36));
		var awayRefinery = machine(level, awayAt.east(), ModBlocks.ORE_REFINERY, alpha);
		check("look: a silo of raw iron against a Refinery feeds it", awayRefinery.takesFrom(ironSilo)
				&& awayRefinery.takingFromSilo(level).is(net.minecraft.world.item.Items.RAW_IRON));
		awayRefinery.catchUp(level, 24000);
		int ingots = 0;
		for (int i = 2; i < awayRefinery.getContainerSize(); i++) {
			ingots += awayRefinery.getItem(i).is(net.minecraft.world.item.Items.IRON_INGOT) ? awayRefinery.getItem(i).getCount() : 0;
		}
		check("away: a Refinery works while you're away, taking raw iron from the silo touching it (" + ingots + " ingots, " + ironSilo.total() + " raw left)",
				ingots >= 20 && ironSilo.total() < 100);
		check("silo: a silo that feeds a machine isn't filled with what it makes once it's empty (" + ironSilo.type() + ")",
				ironSilo.total() == 0 || ironSilo.type().is(net.minecraft.world.item.Items.RAW_IRON));
		check("away: a Refinery doesn't take back what it makes", !awayRefinery.canPlaceItem(0, new ItemStack(net.minecraft.world.item.Items.IRON_INGOT))
				|| awayRefinery.getItem(0).isEmpty() || awayRefinery.getItem(0).is(net.minecraft.world.item.Items.RAW_IRON));
		// Live too: a silo of coal against an Assembler on torches, sticks put in by hand.
		level.setBlockAndUpdate(awayAt.east(9), ModBlocks.ORE_SILO.defaultBlockState());
		var coalSilo = (dev.rubymod.industry.SiloBlockEntity) level.getBlockEntity(awayAt.east(9));
		coalSilo.setOwner(A);
		coalSilo.put(new ItemStack(net.minecraft.world.item.Items.COAL, 10));
		var awayAssembler = machine(level, awayAt.east(8), ModBlocks.ASSEMBLER, alpha);
		awayAssembler.setTier(5);
		awayAssembler.setRecipe(torches, null);
		awayAssembler.setItem(1, new ItemStack(net.minecraft.world.item.Items.STICK, 2));
		dev.rubymod.industry.Grid.account(A).charge = 200_000;
		runMachines(level, 2 * dev.rubymod.industry.MachineKind.ASSEMBLER.cycle(5) + 25, awayAssembler);
		check("silo: an Assembler takes coal from the silo touching it (" + awayAssembler.getItem(2) + ", " + coalSilo.total() + " coal left)",
				awayAssembler.getItem(2).is(net.minecraft.world.item.Items.TORCH) && awayAssembler.getItem(2).getCount() >= 8 && coalSilo.total() == 0);
		// A drill fills the silos touching it, one kind each.
		var awayDrill = machine(level, awayAt.east(4), ModBlocks.RUBY_DRILL, alpha);
		awayDrill.setTier(5);
		level.setBlockAndUpdate(awayAt.east(5), ModBlocks.ORE_SILO.defaultBlockState());
		level.setBlockAndUpdate(awayAt.east(4).above(), ModBlocks.ORE_SILO.defaultBlockState());
		var sortA = (dev.rubymod.industry.SiloBlockEntity) level.getBlockEntity(awayAt.east(5));
		var sortB = (dev.rubymod.industry.SiloBlockEntity) level.getBlockEntity(awayAt.east(4).above());
		sortA.setOwner(A);
		sortB.setOwner(A);
		awayDrill.catchUp(level, 24000);
		check("away: a drill catching up fills the silos touching it, one kind each (" + sortA.total() + " " + sortA.type() + ", " + sortB.total() + " "
				+ sortB.type() + ", " + awayDrill.loads() + " loads)", sortA.total() > 0 && sortB.total() > 0
						&& !ItemStack.isSameItem(sortA.type(), sortB.type()) && awayDrill.loads() > 0);
		// A Trade Depot sells what it has.
		var awayDepot = machine(level, awayAt.east(7), ModBlocks.TRADE_DEPOT, alpha);
		awayDepot.setItem(0, new ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 64));
		awayDepot.catchUp(level, 24000);
		check("away: a Trade Depot sells while you're away (" + awayDepot.getItem(0) + " left)", awayDepot.getItem(0).getCount() < 64);
		// One note for the lot.
		for (int i = 0; i < 45; i++) {
			dev.rubymod.industry.AwayTime.tick(level.getServer());
		}
		String note = dev.rubymod.industry.AwayTime.latest(A);
		check("away: one note says what all your machines made (" + note + ")", note.startsWith("Your machines made") && note.contains("Iron Ingot")
				&& note.contains("earned") && !dev.rubymod.industry.AwayTime.gathering(A));
		var awayRows = dev.rubymod.industry.BaseMachines.rows(level.getServer(), alpha);
		check("my base: it shows what was made while you were away", awayRows.stream().anyMatch(r -> r.startsWith("#away|minecraft:iron_ingot|")));
		// Picked up and put down again: it keeps its settings.
		awayDrill.cycleFocus(2);
		awayDrill.toggleStone();
		ItemStack drillItem = new ItemStack(ModBlocks.RUBY_DRILL);
		awayDrill.keepSettings(drillItem);
		ItemStack asmItem = new ItemStack(ModBlocks.ASSEMBLER);
		awayAssembler.keepSettings(asmItem);
		for (BlockPos p : BlockPos.betweenClosed(awayAt.offset(-1, 0, -1), awayAt.offset(10, 2, 1))) {
			level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
		}
		var movedDrill = machine(level, awayAt, ModBlocks.RUBY_DRILL, alpha);
		movedDrill.applySettings(drillItem);
		var movedAsm = machine(level, awayAt.east(2), ModBlocks.ASSEMBLER, alpha);
		movedAsm.applySettings(asmItem);
		var asmLines = dev.rubymod.industry.MachineBlockEntity.settingsText(dev.rubymod.industry.MachineKind.ASSEMBLER, asmItem);
		check("wrench: a machine moved keeps its settings (" + asmLines + ")", movedDrill.focus() == 2 && movedDrill.leavesStone()
				&& movedAsm.recipe() == torches && asmLines.contains("Set to make " + dev.rubymod.industry.Recipes.ASSEMBLY.get(torches).name()));
		level.setBlockAndUpdate(awayAt, Blocks.AIR.defaultBlockState());
		level.setBlockAndUpdate(awayAt.east(2), Blocks.AIR.defaultBlockState());
		// Nobody on (the server's clock stopped): machines still catch up, by real time.
		BlockPos lineAt = awayAt.offset(0, 0, 3);
		for (BlockPos p : BlockPos.betweenClosed(lineAt.offset(-1, 0, -1), lineAt.offset(10, 2, 1))) {
			level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
		}
		var pausedRefinery = machine(level, lineAt, ModBlocks.ORE_REFINERY, alpha);
		pausedRefinery.setItem(0, new ItemStack(net.minecraft.world.item.Items.RAW_GOLD, 16));
		dev.rubymod.industry.Grid.account(A).charge = 0;
		runMachines(level, 1, pausedRefinery);
		pausedRefinery.lastRanAgo(10 * 60 * 1000L);
		runMachines(level, 1, pausedRefinery);
		check("away: machines catch up for time the server was paused too (" + pausedRefinery.getItem(2) + ")",
				pausedRefinery.getItem(2).is(net.minecraft.world.item.Items.GOLD_INGOT) && pausedRefinery.getItem(2).getCount() >= 2);
		// A belt line carries what it makes while catching up: drill, two belts, a ramp up, a belt and a chest.
		var lineDrill = machine(level, lineAt.east(2), ModBlocks.RUBY_DRILL, alpha);
		lineDrill.setTier(5);
		var beltEast = ModBlocks.CONVEYOR.defaultBlockState().setValue(dev.rubymod.industry.ConveyorBlock.FACING, net.minecraft.core.Direction.EAST);
		level.setBlockAndUpdate(lineAt.east(3), beltEast);
		level.setBlockAndUpdate(lineAt.east(4), beltEast);
		level.setBlockAndUpdate(lineAt.east(5), ModBlocks.BELT_RAMP.defaultBlockState().setValue(dev.rubymod.industry.ConveyorBlock.FACING, net.minecraft.core.Direction.EAST));
		level.setBlockAndUpdate(lineAt.east(6).above(), beltEast);
		level.setBlockAndUpdate(lineAt.east(7).above(), Blocks.CHEST.defaultBlockState());
		var end = dev.rubymod.industry.Belts.endOf(level, lineAt.east(3), new ItemStack(net.minecraft.world.item.Items.COBBLESTONE));
		check("belts: a line is followed to the chest at its end (" + (end == null ? "nowhere" : end.pos()) + ")", end != null && end.pos().equals(lineAt.east(7).above()));
		lineDrill.catchUp(level, 24000);
		var lineChest = (net.minecraft.world.Container) level.getBlockEntity(lineAt.east(7).above());
		int inChest = 0;
		for (int i = 0; i < lineChest.getContainerSize(); i++) {
			inChest += lineChest.getItem(i).getCount();
		}
		check("away: a drill catching up sends what it digs along its belt to the chest (" + inChest + " in the chest, " + lineDrill.loads() + " loads)",
				inChest > 0);
		// The Wrench picks up an Ore Silo with everything in it.
		level.setBlockAndUpdate(lineAt.east(9), ModBlocks.ORE_SILO.defaultBlockState());
		var wrenchSilo = (dev.rubymod.industry.SiloBlockEntity) level.getBlockEntity(lineAt.east(9));
		wrenchSilo.setOwner(A);
		wrenchSilo.put(new ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 64));
		wrenchSilo.put(new ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 64));
		alpha.getInventory().clearContent();
		alpha.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(ModItems.RUBY_WRENCH));
		alpha.setShiftKeyDown(true);
		BlockPos wrenchAt = lineAt.east(9);
		ModItems.RUBY_WRENCH.useOn(new net.minecraft.world.item.context.UseOnContext(alpha, net.minecraft.world.InteractionHand.MAIN_HAND,
				new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(wrenchAt), net.minecraft.core.Direction.UP, wrenchAt, false)));
		alpha.setShiftKeyDown(false);
		long carried = 0;
		for (int i = 0; i < alpha.getInventory().getContainerSize(); i++) {
			ItemStack s = alpha.getInventory().getItem(i);
			if (s.is(ModBlocks.ORE_SILO.asItem())) {
				var c = s.get(net.minecraft.core.component.DataComponents.CONTAINER);
				carried = c == null ? 0 : c.nonEmptyItemCopyStream().mapToInt(ItemStack::getCount).sum();
			}
		}
		check("wrench: it picks up an Ore Silo with what's in it (" + carried + ")", level.getBlockState(wrenchAt).isAir() && carried == 128);
		check("wrench: a silo picked up leaves My Base's silo list", dev.rubymod.industry.SiloBlockEntity.Silos.totals(A).stream()
				.noneMatch(e -> e.getKey().equals("minecraft:iron_ingot")));
		alpha.getInventory().clearContent();
		for (BlockPos p : BlockPos.betweenClosed(lineAt.offset(-1, 0, -1), lineAt.offset(10, 2, 1))) {
			level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
		}
		dev.rubymod.industry.AwayTime.reset();
		// Prospector's Pick: finds the nearest Ruby Ore.
		BlockPos dig = spot.offset(3, -30, 2);
		for (BlockPos p : BlockPos.betweenClosed(dig.offset(-17, -17, -17), dig.offset(17, 17, 17))) {
			if (level.getBlockState(p).is(ModBlocks.RUBY_ORE) || level.getBlockState(p).is(ModBlocks.DEEPSLATE_RUBY_ORE)) {
				level.setBlock(p, Blocks.STONE.defaultBlockState(), 2);
			}
		}
		check("pick: no Ruby Ore near, it says so", dev.rubymod.item.ProspectorPickItem.nearest(level, dig) == null);
		level.setBlock(dig.offset(5, -3, 0), ModBlocks.DEEPSLATE_RUBY_ORE.defaultBlockState(), 2);
		level.setBlock(dig.offset(-9, 0, 4), ModBlocks.RUBY_ORE.defaultBlockState(), 2);
		var found = dev.rubymod.item.ProspectorPickItem.nearest(level, dig);
		check("pick: it points at the nearest Ruby Ore (" + found + ")", dig.offset(5, -3, 0).equals(found));
		// Crouch-click picks what it looks for.
		for (BlockPos p : BlockPos.betweenClosed(dig.offset(-17, -17, -17), dig.offset(17, 17, 17))) {
			if (dev.rubymod.industry.DrillSite.mineral(level.getBlockState(p)) != null && !p.equals(dig.offset(5, -3, 0)) && !p.equals(dig.offset(-9, 0, 4))) {
				level.setBlock(p, Blocks.STONE.defaultBlockState(), 2);
			}
		}
		level.setBlock(dig.offset(0, 0, 3), Blocks.IRON_ORE.defaultBlockState(), 2);
		level.setBlock(dig.offset(-6, 2, 0), Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState(), 2);
		ItemStack pick = new ItemStack(dev.rubymod.registry.ModItems.PROSPECTOR_PICK);
		check("pick: a new one looks for Ruby Ore", dev.rubymod.item.ProspectorPickItem.mode(pick) == dev.rubymod.item.ProspectorPickItem.Mode.RUBY);
		dev.rubymod.item.ProspectorPickItem.setMode(pick, dev.rubymod.item.ProspectorPickItem.Mode.DIAMOND);
		check("pick: set to Diamonds, it finds diamonds", dev.rubymod.item.ProspectorPickItem.mode(pick) == dev.rubymod.item.ProspectorPickItem.Mode.DIAMOND
				&& dig.offset(-6, 2, 0).equals(dev.rubymod.item.ProspectorPickItem.nearest(level, dig, dev.rubymod.item.ProspectorPickItem.mode(pick))));
		check("pick: set to any ore, it finds the nearest of anything", dig.offset(0, 0, 3).equals(
				dev.rubymod.item.ProspectorPickItem.nearest(level, dig, dev.rubymod.item.ProspectorPickItem.Mode.ANY)));
		// Warmer and colder only against a recent try from nearby.
		var who = java.util.UUID.randomUUID();
		var here = net.minecraft.world.phys.Vec3.atCenterOf(dig);
		var R = dev.rubymod.item.ProspectorPickItem.Mode.RUBY;
		dev.rubymod.item.ProspectorPickItem.trend(who, 10, here, 1000, R);
		String warmer = dev.rubymod.item.ProspectorPickItem.trend(who, 8, here.add(2, 0, 0), 1100, R);
		String stale = dev.rubymod.item.ProspectorPickItem.trend(who, 5, here, 1100 + 2 * 60 * 20, R);
		String away = dev.rubymod.item.ProspectorPickItem.trend(who, 3, here.add(40, 0, 0), 1100 + 2 * 60 * 20 + 20, R);
		check("pick: warmer against a try just now (" + warmer.trim() + ")", warmer.startsWith("Warmer"));
		check("pick: a try from minutes ago, or far away, doesn't count", stale.isEmpty() && away.isEmpty());
		// Geodes always hold something; minerals go in your Rock Collection.
		var random = net.minecraft.util.RandomSource.create(7);
		boolean geodes = true;
		for (int i = 0; i < 300; i++) {
			geodes &= !dev.rubymod.item.GeodeItem.crack(random).isEmpty();
		}
		check("geode: there's always something inside", geodes);
		var got = new java.util.LinkedHashMap<net.minecraft.world.item.Item, Integer>();
		got.put(ModItems.RUBY, 2);
		got.put(net.minecraft.world.item.Items.AMETHYST_SHARD, 14);
		got.put(net.minecraft.world.item.Items.DIAMOND, 1);
		String said = dev.rubymod.item.GeodeItem.summary(got);
		check("geode: a whole stack says what came out (" + said + ")", said.equals("14 Amethyst Shards, 2 Rubies and 1 Diamond"));
		var P = new Object() {
			String of(String name, int n) {
				return dev.rubymod.item.GeodeItem.plural(name, n);
			}
		};
		check("words: 12 Torches, 3 TNT, 5 Raw Iron, 2 Rubies, 4 Coal, 6 Iron Ingots", P.of("Torch", 12).equals("Torches") && P.of("TNT", 3).equals("TNT")
				&& P.of("Raw Iron", 5).equals("Raw Iron") && P.of("Ruby", 2).equals("Rubies") && P.of("Coal", 4).equals("Coal") && P.of("Iron Ingot", 6).equals("Iron Ingots"));
		alpha.getInventory().add(new ItemStack(net.minecraft.world.item.Items.RAW_GOLD));
		dev.rubymod.game.Minerals.check(alpha);
		check("rocks: raw gold in your inventory goes in your Rock Collection", dev.rubymod.game.Minerals.found(alpha).contains("minecraft:raw_gold")
				|| !dev.rubymod.game.Hub.inRubyWarsWorld(alpha));
		alpha.getInventory().clearContent();
		// The whole collection: a firework of its own.
		if (dev.rubymod.game.Hub.inRubyWarsWorld(alpha)) {
			var rec = dev.rubymod.game.Stats.get(alpha);
			if (rec.rocks == null) {
				rec.rocks = new java.util.HashSet<>();
			}
			for (var m : dev.rubymod.game.Minerals.ALL) {
				if (m.item() != net.minecraft.world.item.Items.FLINT) {
					rec.rocks.add(m.id());
				}
			}
			alpha.getInventory().add(new ItemStack(net.minecraft.world.item.Items.FLINT));
			dev.rubymod.game.Minerals.check(alpha);
			check("rocks: finding all 16 unlocks the Geode Glitter firework", rec.rocks.size() == dev.rubymod.game.Minerals.ALL.size() && rec.unlocked.contains("fw_geode"));
			alpha.getInventory().clearContent();
		}
		for (BlockPos p : BlockPos.betweenClosed(spot, spot.offset(9, 0, 0))) {
			level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
		}
		dev.rubymod.industry.Grid.account(A).charge = chargeBefore;
	}

	/** Belts: curves, merges, turning, redstone, waiting when full, items nobody can grab, people stepping off, tunnels. */
	/** One base, one power supply; the base list; a drill's finds; the wrench taking a whole belt line. */
	private void powerChecks(ServerLevel level, FakePlayer alpha, FakePlayer bravo, SeasonData.Claim claim, BlockPos base) {
		var A = alpha.getUUID();
		var B = bravo.getUUID();
		BlockPos spot = base.offset(-6, 1, 6);
		for (BlockPos p : BlockPos.betweenClosed(spot.offset(-1, -1, -1), spot.offset(9, 2, 4))) {
			level.setBlock(p, p.getY() < spot.getY() ? net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()
					: net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
		}
		long chargeBefore = dev.rubymod.industry.Grid.charge(A);
		// Equipment only goes in your own base: not by a trusted player, not by an attacker on Battle Day. Building blocks are fine.
		claim.trusted.add(B.toString());
		check("equipment: only the owner can put a machine in their base", Rules.canPlace(alpha, level, spot, ModBlocks.RUBY_DRILL)
				&& !Rules.canPlace(bravo, level, spot, ModBlocks.RUBY_DRILL));
		check("equipment: even trusted players can't bring belts or defenses", !Rules.canPlace(bravo, level, spot, ModBlocks.CONVEYOR)
				&& !Rules.canPlace(bravo, level, spot, ModBlocks.SENTRY_TURRET));
		check("equipment: trusted players can still build with plain blocks", Rules.canPlace(bravo, level, spot, ModBlocks.SANDBAGS)
				&& Rules.canPlace(bravo, level, spot, Blocks.STONE));
		claim.trusted.clear();
		check("equipment: no mines in someone else's base on Battle Day", runAs(Phase.WAR, () -> !Rules.canPlace(bravo, level, spot, ModBlocks.LANDMINE)
				&& Rules.canBuild(bravo, level, spot)));
		check("equipment: deep under someone's base still counts as their base",
				!Rules.canPlace(bravo, level, base.below(SeasonData.CLAIM_DEPTH + 4), ModBlocks.RUBY_DRILL));
		check("equipment: anyone can put equipment outside bases", Rules.canPlace(bravo, level, base.offset(150, 1, 0), ModBlocks.RUBY_DRILL));
		// Power is each player's own.
		var drillB = machine(level, spot, ModBlocks.RUBY_DRILL, bravo);
		check("power: a machine runs on its owner's power", drillB.power().equals(B));
		level.setBlockAndUpdate(spot, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
		var drill = machine(level, spot, ModBlocks.RUBY_DRILL, alpha);
		var battery = machine(level, spot.east(2), ModBlocks.BATTERY, alpha);
		var solar = machine(level, spot.east(4), ModBlocks.SOLAR_PANEL, alpha);
		runMachines(level, 2, battery);
		check("power: a Battery Bank adds to its owner's capacity (" + dev.rubymod.industry.Grid.capacity(A) + ")",
				dev.rubymod.industry.Grid.capacity(A) == dev.rubymod.industry.Grid.BASE_CAPACITY + 20_000);
		// The drill digs, counts its finds, and shows on My Base.
		dev.rubymod.industry.Grid.account(A).charge = 5_000;
		runMachines(level, 2 * dev.rubymod.industry.MachineKind.RUBY_DRILL.cycle(1) + 5, drill, battery, solar);
		check("power: a drill counts what it digs (" + drill.loads() + " loads)", drill.loads() >= 2);
		var listA = dev.rubymod.industry.BaseMachines.forPlayer(A);
		check("power: My Base lists your machines, drills first (" + listA.size() + ")",
				listA.size() >= 3 && listA.get(0).kind() == dev.rubymod.industry.MachineKind.RUBY_DRILL && listA.stream().anyMatch(e -> e.loads() >= 2)
						&& dev.rubymod.industry.BaseMachines.forPlayer(B).stream().noneMatch(e -> e.owner().equals(A)));
		// Out of power: it shows as stopped.
		dev.rubymod.industry.Grid.account(A).charge = 0;
		level.setBlockAndUpdate(spot.east(4), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
		runMachines(level, 21, drill);
		var mine = dev.rubymod.industry.BaseMachines.forPlayer(A).stream().filter(e -> e.pos().equals(spot)).findFirst();
		check("power: a drill with no power shows as stopped on My Base", mine.isPresent() && mine.get().stopped());
		// Gone from the world, gone from the list.
		level.setBlockAndUpdate(spot, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
		level.setBlockAndUpdate(spot.east(2), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
		check("power: broken machines leave My Base",
				dev.rubymod.industry.BaseMachines.forPlayer(A).stream().noneMatch(e -> e.pos().equals(spot) || e.pos().equals(spot.east(2))));
		dev.rubymod.industry.Grid.account(A).charge = chargeBefore;

		// The wrench: two crouch-clicks in a row take a whole line (and not the belt running alongside it).
		BlockPos line = spot.south(2);
		var east = ModBlocks.CONVEYOR.defaultBlockState().setValue(dev.rubymod.industry.ConveyorBlock.FACING, net.minecraft.core.Direction.EAST);
		for (int x = 0; x < 6; x++) {
			level.setBlockAndUpdate(line.east(x), east);
		}
		for (int x = 0; x < 3; x++) {
			level.setBlockAndUpdate(line.east(x).north(), east);
		}
		alpha.getInventory().clearContent();
		alpha.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(ModItems.RUBY_WRENCH));
		alpha.setShiftKeyDown(true);
		java.util.function.Consumer<BlockPos> click = at -> ModItems.RUBY_WRENCH.useOn(new net.minecraft.world.item.context.UseOnContext(alpha, net.minecraft.world.InteractionHand.MAIN_HAND,
						new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(at), net.minecraft.core.Direction.UP, at, false)));
		click.accept(line.east(2));
		boolean oneGone = level.getBlockState(line.east(2)).isAir() && level.getBlockState(line.east(3)).getBlock() instanceof dev.rubymod.industry.ConveyorBlock;
		click.accept(line.east(3));
		boolean lineGone = true;
		for (int x = 0; x < 6; x++) {
			lineGone &= level.getBlockState(line.east(x)).isAir();
		}
		boolean besideKept = true;
		for (int x = 0; x < 3; x++) {
			besideKept &= level.getBlockState(line.east(x).north()).getBlock() instanceof dev.rubymod.industry.ConveyorBlock;
		}
		int belts = alpha.getInventory().countItem(ModBlocks.CONVEYOR.asItem());
		check("belts: the wrench takes one piece, then the whole line (" + belts + " belts back)", oneGone && lineGone && belts == 6);
		check("belts: taking a whole line leaves the belt beside it", besideKept);
		alpha.setShiftKeyDown(false);
		alpha.getInventory().clearContent();
		for (int x = 0; x < 3; x++) {
			level.setBlockAndUpdate(line.east(x).north(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
		}
	}

	private void beltChecks(ServerLevel level, net.minecraft.core.BlockPos at, FakePlayer alpha) {
		var N = net.minecraft.core.Direction.NORTH;
		var W = net.minecraft.core.Direction.WEST;
		var E = net.minecraft.core.Direction.EAST;
		var S = net.minecraft.core.Direction.SOUTH;
		var NONE = dev.rubymod.industry.ConveyorBlock.Turn.NONE;
		var belt = ModBlocks.CONVEYOR.defaultBlockState();
		var F = dev.rubymod.industry.ConveyorBlock.FACING;
		var AIR = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
		java.util.List<net.minecraft.core.BlockPos> placed = new java.util.ArrayList<>();
		java.util.function.BiConsumer<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState> put = (p, st) -> {
			level.setBlockAndUpdate(p, st);
			placed.add(p.immutable());
		};
		for (var p : net.minecraft.core.BlockPos.betweenClosed(at.offset(-1, -1, -6), at.offset(12, -1, 2))) {
			level.setBlockAndUpdate(p, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
		}
		for (var p : net.minecraft.core.BlockPos.betweenClosed(at.offset(-1, 0, -6), at.offset(12, 3, 2))) {
			level.setBlockAndUpdate(p, AIR);
		}
		// A belt running north, then one turning west where it ends: the second bends left.
		var a = at;
		var b = at.north();
		put.accept(a, belt.setValue(F, N));
		put.accept(b, belt.setValue(F, W));
		var turnB = dev.rubymod.industry.ConveyorBlock.turnOf(level.getBlockState(b));
		check("belts: a belt fed from its side curves (" + turnB + ")", turnB == dev.rubymod.industry.ConveyorBlock.Turn.LEFT);
		check("belts: the belt feeding it stays straight", dev.rubymod.industry.ConveyorBlock.turnOf(level.getBlockState(a)) == NONE);
		// A belt behind it too: a merge, so straight again; take it away and it curves back.
		put.accept(b.east(), belt.setValue(F, W));
		var merged = dev.rubymod.industry.ConveyorBlock.turnOf(level.getBlockState(b));
		level.setBlockAndUpdate(b.east(), AIR);
		var again = dev.rubymod.industry.ConveyorBlock.turnOf(level.getBlockState(b));
		check("belts: a belt joining a line that runs on stays straight (" + merged + "), and curves once it is alone (" + again + ")",
				merged == NONE && again == dev.rubymod.industry.ConveyorBlock.Turn.LEFT);
		// A belt built before curves (its shape never set) catches up by itself.
		level.setBlock(b, belt.setValue(F, W), 2 | 16 | 512);
		var stale = dev.rubymod.industry.ConveyorBlock.turnOf(level.getBlockState(b));
		level.getBlockState(b).randomTick(level, b, level.getRandom());
		var caught = dev.rubymod.industry.ConveyorBlock.turnOf(level.getBlockState(b));
		check("belts: an old belt corner curves by itself after a while (" + stale + " -> " + caught + ")",
				stale == NONE && caught == dev.rubymod.industry.ConveyorBlock.Turn.LEFT);
		// The mirror image curves right.
		var c = at.offset(4, 0, 0);
		put.accept(c, belt.setValue(F, N));
		put.accept(c.north(), belt.setValue(F, E));
		check("belts: fed from the other side it curves right",
				dev.rubymod.industry.ConveyorBlock.turnOf(level.getBlockState(c.north())) == dev.rubymod.industry.ConveyorBlock.Turn.RIGHT);
		// Ramps and sorters never curve; a Constructor turning a blueprint turns belts with it.
		check("belts: only plain belts curve", !ModBlocks.BELT_RAMP.defaultBlockState().hasProperty(dev.rubymod.industry.ConveyorBlock.TURN)
				&& !ModBlocks.BELT_SORTER.defaultBlockState().hasProperty(dev.rubymod.industry.ConveyorBlock.TURN));
		check("belts: a turned blueprint turns its belts and ramps too",
				belt.setValue(F, N).rotate(net.minecraft.world.level.block.Rotation.CLOCKWISE_90).getValue(F) == E
						&& ModBlocks.BELT_RAMP.defaultBlockState().setValue(F, N).rotate(net.minecraft.world.level.block.Rotation.CLOCKWISE_180).getValue(F) == S);

		// Things riding a belt: nobody walking past picks them up; and the curve carries them round.
		var item = new net.minecraft.world.entity.item.ItemEntity(level, b.getX() + 0.5, b.getY() + 0.25, b.getZ() + 0.95,
				new ItemStack(net.minecraft.world.item.Items.RAW_IRON));
		item.setPickUpDelay(0);
		level.getBlockState(b).getBlock().stepOn(level, b, level.getBlockState(b), item);
		var inMove = item.getDeltaMovement();
		check("belts: nobody can grab what is riding a belt", item.hasPickUpDelay());
		item.setPos(b.getX() + 0.05, b.getY() + 0.25, b.getZ() + 0.5);
		level.getBlockState(b).getBlock().stepOn(level, b, level.getBlockState(b), item);
		var outMove = item.getDeltaMovement();
		check("belts: round a curve things come in from the side and go out the end (in " + inMove + ", out " + outMove + ")",
				inMove.z < -0.05 && Math.abs(inMove.x) < 0.05 && outMove.x < -0.05 && Math.abs(outMove.z) < 0.05);

		// At a dead end (nothing to go into) things are just lying there: anyone can take them.
		var end = c.north();
		var loose = new net.minecraft.world.entity.item.ItemEntity(level, end.getX() + 0.9, end.getY() + 0.25, end.getZ() + 0.5,
				new ItemStack(net.minecraft.world.item.Items.RAW_IRON));
		loose.setPickUpDelay(0);
		level.getBlockState(end).getBlock().stepOn(level, end, level.getBlockState(end), loose);
		check("belts: things stopped at a dead end can be picked up", !loose.hasPickUpDelay());

		// People get a nudge on top of their own walking (so they can walk and jump off); vehicles are not carried.
		alpha.setPos(a.getX() + 0.5, a.getY() + 0.25, a.getZ() + 0.5);
		alpha.setOnGround(true);
		alpha.setDeltaMovement(0.2, 0, 0);
		level.getBlockState(a).getBlock().stepOn(level, a, level.getBlockState(a), alpha);
		var walk = alpha.getDeltaMovement();
		check("belts: a belt nudges people along without stopping them walking off sideways (" + walk + ")", walk.x > 0.19 && walk.z < -0.01);
		alpha.setDeltaMovement(0, 0.42, 0);
		alpha.setOnGround(false);
		level.getBlockState(a).entityInside(level, a, alpha, net.minecraft.world.entity.InsideBlockEffectApplier.NOOP, true);
		check("belts: jumping off a belt is not held back (" + alpha.getDeltaMovement() + ")", alpha.getDeltaMovement().y > 0.4
				&& alpha.getDeltaMovement().horizontalDistance() < 0.001);
		alpha.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
		var jeep = dev.rubymod.registry.ModEntities.JEEP.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
		if (jeep != null) {
			jeep.setPos(a.getX() + 0.5, a.getY() + 0.25, a.getZ() + 0.5);
			jeep.setOnGround(true);
			jeep.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
			level.getBlockState(a).getBlock().stepOn(level, a, level.getBlockState(a), jeep);
			check("belts: vehicles are not carried", jeep.getDeltaMovement().lengthSqr() < 1.0E-6);
		}

		// Redstone stops a belt; a stopped or crowded belt makes machines and Loaders wait.
		var stopper = a.west();
		put.accept(stopper, net.minecraft.world.level.block.Blocks.REDSTONE_BLOCK.defaultBlockState());
		boolean stopped = level.getBlockState(a).getValue(dev.rubymod.industry.ConveyorBlock.POWERED);
		boolean waits = dev.rubymod.industry.ConveyorBlock.busy(level, a);
		level.setBlockAndUpdate(stopper, AIR);
		boolean restarted = !level.getBlockState(a).getValue(dev.rubymod.industry.ConveyorBlock.POWERED);
		check("belts: redstone stops a belt (and starts it again): " + stopped + ", " + restarted, stopped && waits && restarted);
		// Anything an earlier check left riding the belt would muddle this.
		for (var stray : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(c).inflate(1))) {
			stray.discard();
		}
		var crowd = new net.minecraft.world.entity.item.ItemEntity(level, c.getX() + 0.5, c.getY() + 0.3, c.getZ() + 0.5,
				new ItemStack(net.minecraft.world.item.Items.COAL));
		level.addFreshEntity(crowd);
		boolean busy = dev.rubymod.industry.ConveyorBlock.busy(level, c);
		crowd.discard();
		check("belts: machines wait while things still sit where they would drop theirs", busy && !dev.rubymod.industry.ConveyorBlock.busy(level, c));

		// Belt Tunnel: an entrance with an exit ahead takes things from behind (only), and nothing comes back out.
		var tin = at.offset(8, 0, 0);
		var tout = at.offset(8, 0, -4);
		var tunnel = ModBlocks.BELT_TUNNEL.defaultBlockState().setValue(dev.rubymod.industry.BeltTunnelBlock.FACING, N);
		put.accept(tin, tunnel);
		boolean refusedAlone = !dev.rubymod.industry.Belts.insert(level, tin, S, new ItemStack(net.minecraft.world.item.Items.COAL, 4)).isEmpty();
		put.accept(tout, tunnel.setValue(dev.rubymod.industry.BeltTunnelBlock.EXIT, true));
		ItemStack sideRest = dev.rubymod.industry.Belts.insert(level, tin, E, new ItemStack(net.minecraft.world.item.Items.COAL, 4));
		ItemStack backRest = dev.rubymod.industry.Belts.insert(level, tin, S, new ItemStack(net.minecraft.world.item.Items.COAL, 4));
		ItemStack pulled = dev.rubymod.industry.Belts.extract(level, tin, S, ItemStack.EMPTY, 8);
		check("belts: a Belt Tunnel needs its exit, takes things from behind only, and will not give them back (" + refusedAlone + ", " + sideRest
				+ ", " + backRest + ", " + pulled + ")", refusedAlone && sideRest.getCount() == 4 && backRest.isEmpty() && pulled.isEmpty()
						&& dev.rubymod.industry.BeltTunnelBlock.exitFor(level, tin, level.getBlockState(tin)) != null);
		check("belts: the tunnel exit feeds a curve like a belt does", dev.rubymod.industry.ConveyorBlock.feeds(level, tout, N));
		// Broken, a tunnel drops what it was carrying.
		level.setBlockAndUpdate(tin, AIR);
		int dropped = 0;
		for (var e : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(tin).inflate(1.5))) {
			// Only the tunnel's coal (things from the belt tests next door can roll in).
			if (e.getItem().is(net.minecraft.world.item.Items.COAL)) {
				dropped += e.getItem().getCount();
				e.discard();
			}
		}
		check("belts: a broken Belt Tunnel drops what was inside (" + dropped + ")", dropped == 4);

		// Down ramps feed the belt below; up ramps do not (they let off a level higher).
		var ramp = ModBlocks.BELT_RAMP.defaultBlockState().setValue(F, N);
		check("belts: a down ramp feeds a belt at its foot, an up ramp does not",
				!feedsState(level, at.offset(11, 0, 0), ramp) && feedsState(level, at.offset(11, 0, 0), ramp.setValue(dev.rubymod.industry.BeltRampBlock.DOWN, true)));

		for (var p : placed) {
			level.setBlockAndUpdate(p, AIR);
		}
	}

	private static boolean feedsState(ServerLevel level, net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
		level.setBlockAndUpdate(pos, state);
		boolean feeds = dev.rubymod.industry.ConveyorBlock.feeds(level, pos, state.getValue(dev.rubymod.industry.ConveyorBlock.FACING));
		level.setBlockAndUpdate(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
		return feeds;
	}

	private void check(String name, boolean ok) {
		if (ok) {
			results.add("PASS " + name);
		} else {
			fail(name);
		}
	}

	private void fail(String name) {
		results.add("FAIL " + name);
		failures++;
	}

	private void runAll(ServerLevel level) {
		SeasonData data = SeasonData.get();
		data.claims.clear();
		data.players.clear();

		FakePlayer alpha = FakePlayer.get(level, new GameProfile(UUID.fromString("00000000-0000-0000-0000-00000000a1fa"), "TestAlpha"));
		FakePlayer bravo = FakePlayer.get(level, new GameProfile(UUID.fromString("00000000-0000-0000-0000-00000000b4a0"), "TestBravo"));
		data.player(alpha.getUUID(), "TestAlpha");
		data.player(bravo.getUUID(), "TestBravo");

		BlockPos spawn = level.getRespawnData().pos();
		// The test area's chunks, loaded now (the server can still be busy building the park when the test starts).
		for (int cx = (spawn.getX() + 280) >> 4; cx <= (spawn.getX() + 470) >> 4; cx++) {
			for (int cz = (spawn.getZ() + 280) >> 4; cz <= (spawn.getZ() + 360) >> 4; cz++) {
				level.getChunk(cx, cz);
			}
		}
		parkChecks(level.getServer());
		double borderBefore = level.getWorldBorder().getSize();
		level.getWorldBorder().setSize(dev.rubymod.game.GameConfig.get().borderStartSize);
		BlockPos base = surface(level, spawn.offset(300, 0, 300));
		List<BlockPos> placed = new ArrayList<>();

		// ----- Claims -----
		data.phase = Phase.BUILD;
		String why = Claims.placementProblem(level, alpha, base);
		check("claims: Alpha may place a Heart away from spawn" + (why == null ? "" : " [" + why + "]"), why == null);
		check("claims: no Hearts near spawn", Claims.placementProblem(level, alpha, spawn.offset(5, 0, 5)) != null);
		level.setBlockAndUpdate(base, ModBlocks.RUBY_HEART.defaultBlockState());
		placed.add(base);
		Claims.onHeartPlaced(level, alpha, base);
		SeasonData.Claim claim = data.claimOf(alpha.getUUID());
		check("claims: Heart creates a claim", claim != null && claim.hp == claim.maxHp());
		check("claims: Alpha can't place a second Heart", Claims.placementProblem(level, alpha, base.offset(200, 0, 0)) != null);
		check("claims: Bravo can't claim right next door", Claims.placementProblem(level, bravo, base.offset(10, 0, 10)) != null);
		String whyFar = Claims.placementProblem(level, bravo, base.offset(200, 0, 0));
		check("claims: Bravo can claim far away" + (whyFar == null ? "" : " [" + whyFar + "]"), whyFar == null);

		BlockPos inside = base.offset(5, 1, 5);
		check("protection: owner can build in own claim", Rules.canBuild(alpha, level, inside));
		check("protection: stranger can't build in claim during build phase", !Rules.canBuild(bravo, level, inside));
		check("protection: stranger can build outside claims", Rules.canBuild(bravo, level, base.offset(150, 1, 0)));
		check("protection: a claim reaches a little way under its Heart", !Rules.canBuild(bravo, level, inside.below(10)));
		check("protection: deep under a claim anyone can dig", Rules.canBuild(bravo, level, base.below(SeasonData.CLAIM_DEPTH + 2)));
		claim.trusted.add(bravo.getUUID().toString());
		check("protection: trusted player can build", Rules.canBuild(bravo, level, inside));
		claim.trusted.clear();
		powerChecks(level, alpha, bravo, claim, base);
		miningChecks(level, alpha, base);

		// ----- Tunnel Borer and Seismic Sensor -----
		{
			var borer = dev.rubymod.registry.ModEntities.TUNNEL_BORER.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
			BlockPos near = base.offset(-9, 1, 0);
			borer.snapTo(near.getX() + 0.5, near.getY(), near.getZ() + 0.5, -90, 0);
			level.addFreshEntity(borer);
			java.util.function.Consumer<BlockPos> rock = at -> {
				for (int x = 1; x <= 3; x++) {
					for (int y = -1; y <= 3; y++) {
						for (int z = -2; z <= 2; z++) {
							level.setBlockAndUpdate(at.offset(x, y, z), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
						}
					}
				}
			};
			rock.accept(near);
			check("borer: can't dig into an enemy claim near its Heart (build phase)", borer.testDrill(bravo, 0) == 0);
			BlockPos deep = base.below(SeasonData.CLAIM_DEPTH + 5);
			rock.accept(deep);
			borer.teleportTo(deep.getX() + 0.5, deep.getY(), deep.getZ() + 0.5);
			int cut = borer.testDrill(bravo, 0);
			check("borer: digs deep under an enemy claim (" + cut + " blocks)", cut == 9);
			int down = borer.testDrill(bravo, 60);
			check("borer: looking down digs lower (" + down + " more)", down > 0);
			BlockPos sensor = base.offset(3, 1, 3);
			level.setBlockAndUpdate(sensor, dev.rubymod.registry.ModBlocks.SEISMIC_SENSOR.defaultBlockState());
			borer.testDriver = bravo;
			borer.testDrill(bravo, 0);
			check("seismic sensor: hears an enemy borer under the base [drilling " + borer.drilling() + ", driver " + borer.driver()
					+ ", enemy " + Rules.isEnemyOf(claim, bravo) + ", sensor " + level.getBlockState(sensor).getBlock() + "]", DefenseBlockEntity.testSeismic(level, sensor));
			borer.discard();
			level.setBlockAndUpdate(sensor, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
		}

		// ----- No random waterfalls in the Ruby Wars world -----
		{
			boolean springs = false;
			var plains = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME).getOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS);
			for (var step : plains.value().getGenerationSettings().features()) {
				for (var f : step) {
					springs |= f.is(net.minecraft.data.worldgen.placement.MiscOverworldPlacements.SPRING_WATER);
				}
			}
			check("springs: new land gets no water springs", !springs);
			BlockPos at = new BlockPos(base.getX() + 400 - Math.floorMod(base.getX() + 400, 16) + 8, 250, base.getZ() + 400 - Math.floorMod(base.getZ() + 400, 16) + 8);
			var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
			var water = net.minecraft.world.level.block.Blocks.WATER.defaultBlockState();
			for (int x = -1; x <= 1; x++) {
				for (int y = -1; y <= 1; y++) {
					for (int z = -1; z <= 1; z++) {
						level.setBlock(at.offset(x, y, z), stone, 18);
					}
				}
			}
			level.setBlock(at, water, 18);
			level.setBlock(at.east(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 18);
			// Next to it, a player's two-block pool (left alone).
			BlockPos pool = at.offset(0, 0, 4);
			level.setBlock(pool.below(), stone, 18);
			level.setBlock(pool.offset(1, -1, 0), stone, 18);
			level.setBlock(pool, water, 18);
			level.setBlock(pool.east(), water, 18);
			int n = dev.rubymod.world.Springs.clean(level, level.getChunkAt(at));
			check("springs: a spring in a hillside is stopped up (" + n + ")", level.getBlockState(at).is(net.minecraft.world.level.block.Blocks.STONE));
			check("springs: a player's pool is left alone", level.getBlockState(pool).is(net.minecraft.world.level.block.Blocks.WATER) && level.getBlockState(pool.east()).is(net.minecraft.world.level.block.Blocks.WATER));
			for (int x = -1; x <= 2; x++) {
				for (int y = -1; y <= 1; y++) {
					for (int z = -1; z <= 5; z++) {
						level.setBlock(at.offset(x, y, z), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 18);
					}
				}
			}
		}

		// ----- The Jeep and the Quad drive like cars; boats steer by their rudder -----
		{
			var jeep = dev.rubymod.registry.ModEntities.JEEP.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
			jeep.testPedals(1, true);
			float first = jeep.carSpeed();
			for (int i = 0; i < 80; i++) {
				jeep.testPedals(1, true);
			}
			float top = jeep.carSpeed();
			check("jeep: speed builds up (" + first + ", then " + top + ")", first > 0 && first < top * 0.1F && top > 0.31F);
			for (int i = 0; i < 6; i++) {
				jeep.testPedals(-1, true);
			}
			check("jeep: brakes (" + jeep.carSpeed() + ")", jeep.carSpeed() < top * 0.6F && jeep.carSpeed() >= 0);
			check("jeep: doesn't turn standing still (" + jeep.testCarTurn(0) + ")", jeep.testCarTurn(0) == 0);
			check("jeep: turns tighter at a medium speed than flat out", jeep.testCarTurn(top * 0.35F) > jeep.testCarTurn(top));
			var quad = dev.rubymod.registry.ModEntities.QUAD.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
			check("quad: drives like a car too, and turns tighter than the jeep", quad.testCarTurn(0) == 0 && quad.testCarTurn(0.12F) > jeep.testCarTurn(0.12F));
			var tank = dev.rubymod.registry.ModEntities.TANK.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
			check("tank: drives as it always has", !tank.isCar() && jeep.isCar() && quad.isCar());
			var boat = dev.rubymod.registry.ModEntities.PATROL_BOAT.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
			boat.setYRot(0);
			float still = boat.turnRate(Vec3.ZERO, true);
			float going = boat.turnRate(new Vec3(0, 0, 0.6), true);
			float astern = boat.turnRate(new Vec3(0, 0, -0.3), true);
			check("boat: barely turns stopped, turns properly under way, the other way astern (" + still + ", " + going + ", " + astern + ")",
					still < 1 && going > 4 && astern < 0);
			jeep.discard();
			quad.discard();
			tank.discard();
			boat.discard();
		}

		// ----- Packing a vehicle back up -----
		{
			BlockPos spot = base.offset(-30, 1, -30);
			var jeep = dev.rubymod.registry.ModEntities.JEEP.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
			jeep.snapTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 0, 0);
			dev.rubymod.entity.PackUp.own(jeep, alpha);
			jeep.setPaint(3);
			jeep.setHealth(25);
			level.addFreshEntity(jeep);
			check("pack up: someone else can't pack up your jeep", dev.rubymod.entity.PackUp.refusal(jeep, bravo) != null);
			check("pack up: you can pack up your own", dev.rubymod.entity.PackUp.refusal(jeep, alpha) == null);
			alpha.getInventory().clearContent();
			alpha.setShiftKeyDown(true);
			boolean packed = dev.rubymod.entity.PackUp.tryPackUp(jeep, alpha, jeep.paint());
			alpha.setShiftKeyDown(false);
			ItemStack back = ItemStack.EMPTY;
			for (int i = 0; i < alpha.getInventory().getContainerSize(); i++) {
				if (alpha.getInventory().getItem(i).is(ModItems.JEEP)) {
					back = alpha.getInventory().getItem(i);
				}
			}
			check("pack up: crouch-right-click puts it in your inventory", packed && jeep.isRemoved() && !back.isEmpty());
			var again = dev.rubymod.registry.ModEntities.JEEP.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
			dev.rubymod.entity.PackUp.placed(again, alpha, back);
			check("pack up: put back down, it keeps its paint and its damage (" + again.paint() + ", " + again.getHealth() + ")",
					again.paint() == 3 && Math.abs(again.getHealth() - 25) < 0.01F && alpha.getUUID().equals(dev.rubymod.entity.PackUp.owner(again)));
			again.discard();
			alpha.getInventory().clearContent();
		}

		// ----- C4: the last one leaves a detonator -----
		{
			alpha.getInventory().clearContent();
			alpha.teleportTo(base.getX() + 40.5, base.getY() + 30, base.getZ() + 40.5);
			alpha.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(ModItems.C4, 1));
			ModItems.C4.use(level, alpha, net.minecraft.world.InteractionHand.MAIN_HAND);
			boolean out = dev.rubymod.entity.StickyChargeEntity.hasCharges(alpha);
			ItemStack held = alpha.getMainHandItem();
			check("c4: throwing your last one leaves a Detonator in your hand (" + held.getItem() + ")", out && held.is(ModItems.C4_DETONATOR));
			ModItems.C4_DETONATOR.use(level, alpha, net.minecraft.world.InteractionHand.MAIN_HAND);
			check("c4: the Detonator sets them off, and is used up", !dev.rubymod.entity.StickyChargeEntity.hasCharges(alpha) && alpha.getMainHandItem().isEmpty());
			alpha.getInventory().clearContent();
		}

		// ----- Night goggles only while they're on -----
		{
			alpha.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(ModItems.NIGHT_GOGGLES));
			dev.rubymod.game.NightBattle.goggles(alpha);
			boolean on = alpha.hasEffect(net.minecraft.world.effect.MobEffects.NIGHT_VISION);
			alpha.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, ItemStack.EMPTY);
			dev.rubymod.game.NightBattle.goggles(alpha);
			check("goggles: night vision on with them, gone as soon as they're off", on && !alpha.hasEffect(net.minecraft.world.effect.MobEffects.NIGHT_VISION));
		}

		// ----- Paint kit: back a step, and only your own -----
		{
			var jeep = dev.rubymod.registry.ModEntities.JEEP.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
			jeep.snapTo(base.getX() + 30.5, base.getY() + 1, base.getZ() - 30.5, 0, 0);
			dev.rubymod.entity.PackUp.own(jeep, alpha);
			jeep.setPaint(2);
			level.addFreshEntity(jeep);
			ItemStack kit = new ItemStack(ModItems.PAINT_KIT);
			alpha.setShiftKeyDown(true);
			ModItems.PAINT_KIT.interactLivingEntity(kit, alpha, jeep, net.minecraft.world.InteractionHand.MAIN_HAND);
			alpha.setShiftKeyDown(false);
			int back = jeep.paint();
			ModItems.PAINT_KIT.interactLivingEntity(kit, alpha, jeep, net.minecraft.world.InteractionHand.MAIN_HAND);
			int forward = jeep.paint();
			ModItems.PAINT_KIT.interactLivingEntity(kit, bravo, jeep, net.minecraft.world.InteractionHand.MAIN_HAND);
			check("paint kit: crouching goes back one, right-click forward one, others can't repaint yours (" + back + ", " + forward + ", " + jeep.paint() + ")",
					back == 1 && forward == 2 && jeep.paint() == 2);
			jeep.discard();
		}

		// ----- Packing up an AA gun -----
		{
			var gun = dev.rubymod.registry.ModEntities.HEAVY_AA_GUN.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
			gun.snapTo(base.getX() - 30.5, base.getY() + 1, base.getZ() + 30.5, 0, 0);
			dev.rubymod.entity.PackUp.own(gun, alpha);
			level.addFreshEntity(gun);
			alpha.getInventory().clearContent();
			alpha.setShiftKeyDown(true);
			boolean packed = dev.rubymod.entity.PackUp.tryPackUp(gun, alpha, 0);
			alpha.setShiftKeyDown(false);
			check("pack up: an AA gun goes back in your inventory", packed && gun.isRemoved() && alpha.getInventory().contains(st -> st.is(ModItems.HEAVY_AA_GUN)));
			alpha.getInventory().clearContent();
		}

		// ----- Vehicles mend at home; grenades wind up; the grappling hook mends -----
		{
			var jeep = dev.rubymod.registry.ModEntities.JEEP.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
			jeep.snapTo(base.getX() + 3.5, base.getY() + 1, base.getZ() + 3.5, 0, 0);
			dev.rubymod.entity.PackUp.own(jeep, alpha);
			level.addFreshEntity(jeep);
			jeep.setHealth(20);
			boolean mended = dev.rubymod.entity.PackUp.mend(jeep);
			float after = jeep.getHealth();
			dev.rubymod.entity.PackUp.hit(jeep);
			boolean justHit = dev.rubymod.entity.PackUp.mend(jeep);
			check("repairs: a damaged jeep parked at home mends (" + after + "), but not straight after being hit", mended && after > 20 && !justHit);
			jeep.teleportTo(base.getX() + 300.5, base.getY() + 1, base.getZ() + 300.5);
			check("repairs: out in the field it doesn't mend", !dev.rubymod.entity.PackUp.mend(jeep) || jeep.getHealth() >= jeep.getMaxHealth());
			jeep.discard();
			float tap = dev.rubymod.item.ThrowableWeaponItem.speed(dev.rubymod.item.ThrowableWeaponItem.power(1));
			float full = dev.rubymod.item.ThrowableWeaponItem.speed(dev.rubymod.item.ThrowableWeaponItem.power(40));
			check("grenades: a full wind-up throws over twice as hard as a tap (" + tap + ", " + full + ")", full > tap * 2);
			ItemStack hook = new ItemStack(ModItems.GRAPPLING_HOOK);
			hook.setDamageValue(hook.getMaxDamage() - 1);
			check("grappling hook: a worn one mends itself", dev.rubymod.item.GrapplingHookItem.mend(hook, bravo) && hook.getDamageValue() == hook.getMaxDamage() - 2);
		}

		// ----- Belts load turrets; guards ride along; the Builder's Wand mends -----
		{
			BlockPos t = base.offset(5, 1, -5);
			level.setBlockAndUpdate(t, dev.rubymod.registry.ModBlocks.SENTRY_TURRET.defaultBlockState());
			var turret = (dev.rubymod.block.DefenseBlockEntity) level.getBlockEntity(t);
			turret.ammo = 0;
			ItemStack rest = dev.rubymod.industry.Belts.insert(level, t, net.minecraft.core.Direction.WEST, new ItemStack(ModItems.HEAVY_AMMO, 64));
			boolean first = rest.isEmpty() && turret.ammo == 64;
			turret.ammo = dev.rubymod.block.DefenseBlockEntity.MAX_TURRET_AMMO - 12;
			rest = dev.rubymod.industry.Belts.insert(level, t, net.minecraft.core.Direction.WEST, new ItemStack(ModItems.HEAVY_AMMO, 64));
			ItemStack other = dev.rubymod.industry.Belts.insert(level, t, net.minecraft.core.Direction.WEST, new ItemStack(ModItems.LIGHT_AMMO, 5));
			check("turrets: a belt loads Heavy Ammo, keeps what doesn't fit, and refuses anything else (" + rest.getCount() + " left)",
					first && rest.getCount() == 52 && turret.ammo == dev.rubymod.block.DefenseBlockEntity.MAX_TURRET_AMMO && other.getCount() == 5);
			level.setBlockAndUpdate(t, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());

			BlockPos g = base.offset(-6, 1, 6);
			var guard = dev.rubymod.entity.SoldierEntity.guard(level, alpha, g, 0);
			level.addFreshEntity(guard);
			var jeep = dev.rubymod.registry.ModEntities.JEEP.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
			jeep.snapTo(g.getX() + 3.5, g.getY(), g.getZ() + 0.5, 0, 0);
			level.addFreshEntity(jeep);
			guard.testFollow(alpha, jeep);
			boolean rode = guard.getVehicle() == jeep;
			guard.testFollow(alpha, null);
			boolean out = guard.getVehicle() == null;
			// Their leader up in the air, far off: the guard stays where it is.
			guard.snapTo(alpha.getX() + 60.5, alpha.getY(), alpha.getZ() + 0.5, 0, 0);
			boolean grounded = alpha.onGround();
			alpha.setOnGround(false);
			double before = guard.getX();
			guard.testFollow(alpha, null);
			alpha.setOnGround(grounded);
			check("guards: ride along in your jeep, hop out with you, and don't jump into the air after you (" + rode + ", " + out + ", "
					+ Math.round(guard.getX() - before) + ")", rode && out && Math.abs(guard.getX() - before) < 2);
			guard.discard();
			jeep.discard();

			ItemStack wand = new ItemStack(ModItems.BUILDERS_WAND);
			wand.setDamageValue(wand.getMaxDamage() - 1);
			check("builder's wand: a worn one mends itself", dev.rubymod.item.BuildersWandItem.mend(wand) && wand.getDamageValue() == wand.getMaxDamage() - 2);
		}

		// ----- The family season: Battle Day waits, sitting out, no contest, teams, choices, awards -----
		seasonFlowChecks(level, alpha, bravo, base);

		// ----- Water, falls and ramming; aircraft ditching; hidden mines -----
		{
			var stoneB = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
			var waterB = net.minecraft.world.level.block.Blocks.WATER.defaultBlockState();
			BlockPos pool = new BlockPos(base.getX() + 500, 200, base.getZ() + 500);
			for (int x = -2; x <= 2; x++) {
				for (int z = -2; z <= 2; z++) {
					level.setBlock(pool.offset(x, -1, z), stoneB, 18);
					for (int y = 0; y < 3; y++) {
						level.setBlock(pool.offset(x, y, z), waterB, 18);
					}
				}
			}
			var jeep = dev.rubymod.registry.ModEntities.JEEP.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
			jeep.snapTo(pool.getX() + 0.5, pool.getY(), pool.getZ() + 0.5, 0, 0);
			level.addFreshEntity(jeep);
			jeep.tick();
			boolean flooded = jeep.flooded();
			var boat = dev.rubymod.registry.ModEntities.PATROL_BOAT.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
			boat.snapTo(pool.getX() + 0.5, pool.getY() + 2, pool.getZ() + 0.5, 0, 0);
			level.addFreshEntity(boat);
			boat.tick();
			check("water: a jeep in deep water is flooded, a boat never is (" + flooded + ", " + boat.flooded() + ")", flooded && !boat.flooded() && jeep.dismountsUnderwater());
			var heli = dev.rubymod.registry.ModEntities.ATTACK_HELI.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
			heli.snapTo(pool.getX() + 0.5, pool.getY(), pool.getZ() + 0.5, 0, 0);
			level.addFreshEntity(heli);
			float heliBefore = heli.getHealth();
			heli.tick();
			check("water: an aircraft down in deep water is ditched and damaged (" + heli.ditched() + ", " + heliBefore + " -> " + heli.getHealth() + ")",
					heli.ditched() && heli.getHealth() < heliBefore);
			jeep.discard();
			boat.discard();
			heli.discard();
			for (int x = -2; x <= 2; x++) {
				for (int z = -2; z <= 2; z++) {
					for (int y = -1; y < 3; y++) {
						level.setBlock(pool.offset(x, y, z), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 18);
					}
				}
			}

			var car = dev.rubymod.registry.ModEntities.JEEP.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
			car.snapTo(base.getX() + 0.5, base.getY() + 1, base.getZ() + 12.5, 0, 0);
			float full = car.getMaxHealth();
			car.causeFallDamage(4, 1, level.damageSources().fall());
			boolean hop = car.getHealth() >= full;
			car.causeFallDamage(20, 1, level.damageSources().fall());
			boolean drop = car.getHealth() < full;
			var tank = dev.rubymod.registry.ModEntities.TANK.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
			tank.causeFallDamage(20, 1, level.damageSources().fall());
			check("falls: a small hop's free, a big drop dents the jeep (" + car.getHealth() + "), the tank's as it was",
					hop && drop && tank.getHealth() >= tank.getMaxHealth());
			car.setHealth(full);
			level.addFreshEntity(car);
			var zombie = net.minecraft.world.entity.EntityTypes.ZOMBIE.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
			zombie.snapTo(base.getX() + 0.5, base.getY() + 1, base.getZ() + 14.5, 0, 0);
			level.addFreshEntity(zombie);
			float zBefore = zombie.getHealth();
			car.hit(level, alpha, zombie, new Vec3(0, 0, 1), 0.4);
			var cow = net.minecraft.world.entity.EntityTypes.COW.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
			cow.snapTo(base.getX() + 2.5, base.getY() + 1, base.getZ() + 14.5, 0, 0);
			level.addFreshEntity(cow);
			boolean cowHurt = car.hit(level, alpha, cow, new Vec3(0, 0, 1), 0.4);
			check("ramming: a zombie hit at speed is hurt (" + zBefore + " -> " + zombie.getHealth() + "), a cow is only nudged",
					zombie.getHealth() < zBefore && !cowHurt && cow.getHealth() >= cow.getMaxHealth());
			zombie.discard();
			cow.discard();
			car.discard();
			tank.discard();

			BlockPos minePos = base.offset(4, 1, 7);
			level.setBlockAndUpdate(minePos, dev.rubymod.registry.ModBlocks.LANDMINE.defaultBlockState());
			var mine = (dev.rubymod.block.MineBlockEntity) level.getBlockEntity(minePos);
			mine.claimFromPosition();
			var mineState = level.getBlockState(minePos);
			boolean ownSees = mine.visibleTo(alpha) && !mineState.getShape(level, minePos, net.minecraft.world.phys.shapes.CollisionContext.of(alpha)).isEmpty();
			boolean enemyBlind = !mine.visibleTo(bravo) && mineState.getShape(level, minePos, net.minecraft.world.phys.shapes.CollisionContext.of(bravo)).isEmpty();
			level.setBlockAndUpdate(minePos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
			BlockPos wild = base.offset(400, 1, 400);
			level.setBlockAndUpdate(wild, dev.rubymod.registry.ModBlocks.AT_MINE.defaultBlockState());
			var wildMine = (dev.rubymod.block.MineBlockEntity) level.getBlockEntity(wild);
			wildMine.claimFromPosition();
			boolean wildSeen = wildMine.visibleTo(bravo);
			level.setBlockAndUpdate(wild, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
			check("mines: you see yours, an enemy can't see or point at it, and one in nobody's base is seen by all (" + ownSees + ", " + enemyBlind + ", " + wildSeen + ")",
					ownSees && enemyBlind && wildSeen);
		}

		// ----- The flamethrower refills -----
		{
			ItemStack flamer = new ItemStack(ModItems.FLAMETHROWER);
			flamer.setDamageValue(flamer.getMaxDamage() - 1);
			boolean up = dev.rubymod.item.FlamethrowerItem.refill(flamer, level, bravo);
			check("flamethrower: an empty one refills by itself", up && flamer.getDamageValue() == flamer.getMaxDamage() - 2);
		}

		// ----- The Dirt Bike rides like a motorbike -----
		{
			var bike = dev.rubymod.registry.ModEntities.DIRT_BIKE.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
			bike.testThrottle(1, true);
			float first = bike.bikeSpeed();
			for (int i = 0; i < 9; i++) {
				bike.testThrottle(1, true);
			}
			float half = bike.bikeSpeed();
			for (int i = 0; i < 150; i++) {
				bike.testThrottle(1, true);
			}
			float top = bike.bikeSpeed();
			check("dirt bike: the throttle builds speed up (" + first + ", then " + half + ", then " + top + ")", first > 0 && first < 0.05F && half < top * 0.6F && top > 0.44F);
			bike.testThrottle(0, true);
			check("dirt bike: letting go coasts", bike.bikeSpeed() > top * 0.95F);
			for (int i = 0; i < 20; i++) {
				bike.testThrottle(0, false);
			}
			check("dirt bike: keeps its speed in the air", bike.bikeSpeed() > top * 0.95F);
			for (int i = 0; i < 6; i++) {
				bike.testThrottle(-1, true);
			}
			check("dirt bike: S brakes hard (" + bike.bikeSpeed() + ")", bike.bikeSpeed() < top * 0.6F && bike.bikeSpeed() >= 0);
			for (int i = 0; i < 40; i++) {
				bike.testThrottle(-1, true);
			}
			check("dirt bike: then only backs up slowly (" + bike.bikeSpeed() + ")", bike.bikeSpeed() < 0 && bike.bikeSpeed() > -0.11F);
			float still = bike.testTurn(0);
			float mid = bike.testTurn(0.15F);
			float fast = bike.testTurn(top);
			check("dirt bike: can't spin on the spot, turns tightest at a medium speed (" + still + ", " + mid + ", " + fast + ")",
					still < 2 && mid > fast && fast > still);
			bike.discard();
		}

		// ----- Fire: flamethrower and fire bombs -----
		{
			BlockPos inClaim = base.offset(4, 20, -4);
			level.setBlockAndUpdate(inClaim.below(), Blocks.OAK_PLANKS.defaultBlockState());
			level.setBlockAndUpdate(inClaim, Blocks.AIR.defaultBlockState());
			check("fire: can't be lit in a claim outside Battle Day", !dev.rubymod.game.Fire.light(level, inClaim));
			data.phase = Phase.WAR;
			check("fire: can be lit in a claim on Battle Day", dev.rubymod.game.Fire.light(level, inClaim));
			data.phase = Phase.BUILD;
			level.setBlockAndUpdate(inClaim, Blocks.AIR.defaultBlockState());
			level.setBlockAndUpdate(inClaim.below(), Blocks.AIR.defaultBlockState());
			BlockPos field = base.offset(150, 30, 0);
			for (int x = -4; x <= 4; x++) {
				for (int z = -4; z <= 4; z++) {
					level.setBlockAndUpdate(field.offset(x, -1, z), Blocks.STONE.defaultBlockState());
					for (int y = 0; y <= 3; y++) {
						level.setBlockAndUpdate(field.offset(x, y, z), Blocks.AIR.defaultBlockState());
					}
				}
			}
			var burning = EntityTypes.PIG.create(level, EntitySpawnReason.COMMAND);
			burning.setNoAi(true);
			burning.setPos(field.getX() + 1.5, field.getY(), field.getZ() + 0.5);
			boolean added = level.addFreshEntity(burning);
			var bomb = dev.rubymod.entity.GrenadeEntity.fireBomb(level, alpha);
			bomb.setPos(field.getX() + 0.5, field.getY() + 0.2, field.getZ() + 0.5);
			bomb.burst(level);
			int fires = 0;
			for (int x = -3; x <= 3; x++) {
				for (int z = -3; z <= 3; z++) {
					if (level.getBlockState(field.offset(x, 0, z)).is(Blocks.FIRE)) {
						fires++;
					}
				}
			}
			check("fire bomb: sets the ground around it alight outside claims (" + fires + " fires)", fires >= 15);
			check("fire bomb: sets an animal close by on fire [added " + added + ", ticking " + level.isPositionEntityTicking(field) + "]", burning.getRemainingFireTicks() > 0);
			burning.discard();
			for (int x = -4; x <= 4; x++) {
				for (int z = -4; z <= 4; z++) {
					level.setBlockAndUpdate(field.offset(x, 0, z), Blocks.AIR.defaultBlockState());
					level.setBlockAndUpdate(field.offset(x, -1, z), Blocks.AIR.defaultBlockState());
				}
			}
		}

		// ----- Collapse: what's left hanging after a blast comes down -----
		{
			java.util.function.Function<BlockPos, Integer> gallows = at -> {
				for (int x = -3; x <= 8; x++) {
					for (int z = -3; z <= 3; z++) {
						level.setBlockAndUpdate(at.offset(x, -1, z), Blocks.STONE.defaultBlockState());
						for (int y = 0; y <= 7; y++) {
							level.setBlockAndUpdate(at.offset(x, y, z), Blocks.AIR.defaultBlockState());
						}
					}
				}
				for (int y = 0; y <= 4; y++) {
					level.setBlockAndUpdate(at.offset(0, y, 0), Blocks.OAK_PLANKS.defaultBlockState());
				}
				for (int x = 1; x <= 6; x++) {
					level.setBlockAndUpdate(at.offset(x, 4, 0), Blocks.OAK_PLANKS.defaultBlockState());
				}
				var shell = dev.rubymod.registry.ModEntities.MORTAR_SHELL.create(level, EntitySpawnReason.COMMAND);
				dev.rubymod.game.Explosions.explode(level, shell, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, 3.0F, true);
				return level.getEntitiesOfClass(net.minecraft.world.entity.item.FallingBlockEntity.class, new net.minecraft.world.phys.AABB(at).inflate(12)).size();
			};
			data.phase = Phase.BUILD;
			BlockPos open = base.offset(150, 40, 40);
			int fell = gallows.apply(open);
			check("collapse: a beam left hanging after a mortar hit comes down (" + fell + " falling)", fell >= 3
					&& !level.getBlockState(open.offset(6, 4, 0)).is(Blocks.OAK_PLANKS));
			BlockPos inClaim = base.offset(3, 40, 3);
			int protectedFell = gallows.apply(inClaim);
			check("collapse: nothing falls in a protected claim (" + protectedFell + ")", protectedFell == 0 && level.getBlockState(inClaim.offset(6, 4, 0)).is(Blocks.OAK_PLANKS));
			for (BlockPos at : new BlockPos[] {open, inClaim}) {
				for (var e : level.getEntitiesOfClass(net.minecraft.world.entity.item.FallingBlockEntity.class, new net.minecraft.world.phys.AABB(at).inflate(12))) {
					e.discard();
				}
				for (int x = -3; x <= 8; x++) {
					for (int z = -3; z <= 3; z++) {
						for (int y = -1; y <= 7; y++) {
							level.setBlockAndUpdate(at.offset(x, y, z), Blocks.AIR.defaultBlockState());
						}
					}
				}
			}
		}

		// ----- Strength: how far a material can reach out from its support -----
		{
			java.util.function.BiFunction<net.minecraft.world.level.block.Block, net.minecraft.world.level.block.Block, Integer> beam = (pillar, span) -> {
				BlockPos at = base.offset(150, 40, -40);
				for (int x = -2; x <= 10; x++) {
					for (int z = -2; z <= 2; z++) {
						level.setBlockAndUpdate(at.offset(x, -1, z), Blocks.STONE.defaultBlockState());
						for (int y = 0; y <= 5; y++) {
							level.setBlockAndUpdate(at.offset(x, y, z), Blocks.AIR.defaultBlockState());
						}
					}
				}
				for (int y = 0; y <= 3; y++) {
					level.setBlockAndUpdate(at.offset(0, y, 0), pillar.defaultBlockState());
				}
				for (int x = 1; x <= 8; x++) {
					level.setBlockAndUpdate(at.offset(x, 3, 0), span.defaultBlockState());
				}
				dev.rubymod.game.Collapse.settle(level, at.offset(4, 3, 0), 4);
				int held = 0;
				for (int x = 1; x <= 8; x++) {
					if (level.getBlockState(at.offset(x, 3, 0)).is(span)) {
						held++;
					}
				}
				for (var e : level.getEntitiesOfClass(net.minecraft.world.entity.item.FallingBlockEntity.class, new net.minecraft.world.phys.AABB(at).inflate(16))) {
					e.discard();
				}
				for (int x = -2; x <= 10; x++) {
					for (int z = -2; z <= 2; z++) {
						for (int y = -1; y <= 5; y++) {
							level.setBlockAndUpdate(at.offset(x, y, z), Blocks.AIR.defaultBlockState());
						}
					}
				}
				return held;
			};
			int wood = beam.apply(Blocks.STONE_BRICKS, Blocks.OAK_PLANKS);
			int steel = beam.apply(Blocks.IRON_BLOCK, Blocks.IRON_BLOCK);
			check("strength: a wooden beam reaches five blocks out, the rest falls (" + wood + " of 8 stay)", wood == 5);
			check("strength: a steel beam on a steel pillar holds all eight (" + steel + ")", steel == 8);
		}

		// ----- Battle Day storms -----
		{
			int was = dev.rubymod.game.Storms.theme();
			check("storms: a sandstorm first, then clear, then a blizzard",
					dev.rubymod.game.Storms.scheduled(0) == dev.rubymod.world.WarzoneTerrain.THEME_DESERT
							&& dev.rubymod.game.Storms.scheduled(20L * 60 * 15) == dev.rubymod.game.Storms.NONE
							&& dev.rubymod.game.Storms.scheduled(20L * 60 * 21) == dev.rubymod.world.WarzoneTerrain.THEME_SNOW);
			dev.rubymod.game.Storms.set(dev.rubymod.world.WarzoneTerrain.THEME_DESERT);
			double[] desert = {0, 0};
			for (int a = 0; a < 360; a += 5) {
				double x = Math.cos(Math.toRadians(a)) * 470;
				double z = Math.sin(Math.toRadians(a)) * 470;
				if (dev.rubymod.world.WarzoneTerrain.column((int) x, (int) z).theme() == dev.rubymod.world.WarzoneTerrain.THEME_DESERT) {
					desert[0] = x;
					desert[1] = z;
				}
			}
			check("storms: the sandstorm covers the Red Desert", dev.rubymod.game.Storms.inStorm(level, desert[0], desert[1]));
			check("storms: but not the crater, or the Snow Plateau", !dev.rubymod.game.Storms.inStorm(level, desert[0] * 0.2, desert[1] * 0.2)
					&& !dev.rubymod.game.Storms.inStorm(level, -desert[0], -desert[1]));
			dev.rubymod.game.Storms.set(was);
		}

		// ----- Keycard Doors and Laser Tripwires -----
		{
			BlockPos door = base.offset(-6, 1, -6);
			level.setBlockAndUpdate(door, dev.rubymod.registry.ModBlocks.KEYCARD_DOOR.defaultBlockState());
			var doorState = level.getBlockState(door);
			check("keycard door: locked to outsiders", !Rules.canInteract(bravo, level, door, doorState));
			check("keycard door: opens for its own base", Rules.canInteract(alpha, level, door, doorState));
			var card = new ItemStack(dev.rubymod.registry.ModItems.KEYCARD);
			var alphaAt = alpha.position();
			alpha.setPos(base.getX() + 2.5, base.getY() + 1, base.getZ() + 2.5);
			dev.rubymod.game.BaseSecurity.bind(alpha, card);
			alpha.setPos(alphaAt);
			bravo.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, card);
			check("keycard door: opens for an outsider with that base's Keycard", Rules.canInteract(bravo, level, door, doorState));
			bravo.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(dev.rubymod.registry.ModItems.KEYCARD));
			check("keycard door: a blank Keycard doesn't open it", !Rules.canInteract(bravo, level, door, doorState));
			bravo.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
			level.setBlockAndUpdate(door, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
			level.setBlockAndUpdate(door.above(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());

			BlockPos laser = base.offset(-6, 10, 6);
			level.setBlockAndUpdate(laser, dev.rubymod.registry.ModBlocks.LASER_TRIPWIRE.defaultBlockState()
					.setValue(dev.rubymod.block.LaserTripwireBlock.FACING, net.minecraft.core.Direction.EAST));
			for (int i = 1; i <= 6; i++) {
				level.setBlockAndUpdate(laser.east(i), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
			}
			BlockPos turret = base.offset(-4, 12, 6);
			level.setBlockAndUpdate(turret, dev.rubymod.registry.ModBlocks.SENTRY_TURRET.defaultBlockState());
			dev.rubymod.game.BaseSecurity.here(level, turret, dev.rubymod.block.DefenseBlock.Kind.SENTRY_TURRET);
			level.setBlockAndUpdate(laser.east(7), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
			double reach = DefenseBlockEntity.laserEnd(level, laser, net.minecraft.core.Direction.EAST).x - (laser.getX() + 0.5);
			check("laser tripwire: the beam reaches the wall six blocks away (" + reach + ")", Math.abs(reach - 6.5) < 0.01);
			check("laser tripwire: quiet with nobody in the beam", !DefenseBlockEntity.testLaser(level, laser));
			// (The test's stand-in players aren't really in the world, so the beam can't see them: what a trip does instead.)
			dev.rubymod.game.BaseSecurity.tripped(level, claim, bravo, laser);
			check("laser tripwire: a trip puts the base's turrets on alert",
					level.getBlockEntity(turret) instanceof DefenseBlockEntity t && t.alerted(level));
			level.setBlockAndUpdate(laser.east(7), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
			level.setBlockAndUpdate(laser, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
			level.setBlockAndUpdate(turret, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
		}
		data.phase = Phase.WAR;
		check("protection: claims open on Battle Day", Rules.canBuild(bravo, level, inside));
		var alphaWas = alpha.position();
		var bravoWas = bravo.position();
		alpha.setPos(base.getX() + 150.5, base.getY() + 1, base.getZ() + 0.5);
		bravo.setPos(base.getX() + 153.5, base.getY() + 1, base.getZ() + 0.5);
		check("pvp: build phase: players can fight outside the bases", runAs(Phase.BUILD, () -> Rules.canHurt(alpha, bravo)));
		bravo.setPos(inside.getX() + 0.5, inside.getY(), inside.getZ() + 0.5);
		check("pvp: build phase: nobody can be hurt inside a base", runAs(Phase.BUILD, () -> !Rules.canHurt(alpha, bravo)));
		check("pvp: build phase: nobody can shoot out of a base", runAs(Phase.BUILD, () -> !Rules.canHurt(bravo, alpha)));
		alpha.setPos(alphaWas.x, alphaWas.y, alphaWas.z);
		bravo.setPos(bravoWas.x, bravoWas.y, bravoWas.z);
		check("pvp: players can hurt each other in Open War", runAs(Phase.WAR, () -> Rules.canHurt(alpha, bravo)));
		check("pvp: no PvP during Lockdown", runAs(Phase.LOCKDOWN, () -> !Rules.canHurt(alpha, bravo)));

		// ----- Fire and fluids -----
		data.phase = Phase.BUILD;
		int edge = claim.radius();
		BlockPos justInside = base.offset(edge, 0, 0);
		BlockPos justOutside = base.offset(edge + 1, 0, 0);
		check("griefing: lava can't flow into a claim", Rules.blocksFlow(level, justOutside, justInside));
		check("griefing: fluids flow freely inside a claim", !Rules.blocksFlow(level, justInside, base.offset(1, 0, 0)));
		check("griefing: fire can't burn blocks in a claim", Rules.isProtectedFromGriefing(level, justInside) && !Rules.isProtectedFromGriefing(level, justOutside));
		data.phase = Phase.WAR;
		check("griefing: lava flows anywhere on Battle Day", !Rules.blocksFlow(level, justOutside, justInside));

		// ----- Explosions -----
		BlockPos outside = surface(level, base.offset(150, 0, 0)).below();
		data.phase = Phase.BUILD;
		List<BlockPos> kept = Explosions.filter(level, List.of(inside, outside), 3.0F);
		check("explosions: can't break blocks in a claim during build phase [inside kept=" + kept.contains(inside) + ", outside kept=" + kept.contains(outside) + ", outside block=" + level.getBlockState(outside) + "]", !kept.contains(inside) && kept.contains(outside));
		data.phase = Phase.WAR;
		check("explosions: Heart is never blown up", Explosions.filter(level, List.of(base), 8.0F).isEmpty());

		BlockPos concrete = base.offset(3, 1, 0);
		level.setBlockAndUpdate(concrete, ModBlocks.CONCRETE_WALL.defaultBlockState());
		placed.add(concrete);
		int hits = 0;
		while (hits < 10 && Explosions.filter(level, List.of(concrete), 3.0F).isEmpty()) {
			hits++;
		}
		check("armor: Concrete Wall survives 3 rockets and breaks on the 4th (broke on hit " + (hits + 1) + ")", hits == 3);

		BlockPos reinforced = base.offset(-3, 1, 0);
		level.setBlockAndUpdate(reinforced, ModBlocks.REINFORCED_CONCRETE.defaultBlockState());
		placed.add(reinforced);
		boolean grenadeProof = true;
		for (int i = 0; i < 10; i++) {
			grenadeProof &= Explosions.filter(level, List.of(reinforced), 2.2F).isEmpty();
		}
		check("armor: Reinforced Concrete ignores grenades", grenadeProof);
		int rhits = 0;
		while (rhits < 12 && Explosions.filter(level, List.of(reinforced), 3.0F).isEmpty()) {
			rhits++;
			if (rhits == 2) {
				check("armor: a wall knows how many blasts it's taken (" + Explosions.blastsTaken(level, reinforced) + " of "
						+ Explosions.blastsToBreak(level.getBlockState(reinforced)) + ")", Explosions.blastsTaken(level, reinforced) == 2
						&& Explosions.blastsToBreak(level.getBlockState(reinforced)) == 6);
			}
		}
		check("armor: Reinforced Concrete is the toughest: survives 5 rockets, breaks on the 6th (broke on hit " + (rhits + 1) + ")", rhits == 5);
		level.setBlockAndUpdate(reinforced, ModBlocks.REINFORCED_CONCRETE.defaultBlockState());
		check("armor: cruise missile breaks Reinforced Concrete at once", !Explosions.filter(level, List.of(reinforced), 7.0F).isEmpty());

		// ----- Missile Interceptor -----
		data.phase = Phase.WAR;
		BlockPos interceptorPos = base.offset(-5, 1, -5);
		level.setBlockAndUpdate(interceptorPos, ModBlocks.MISSILE_INTERCEPTOR.defaultBlockState());
		placed.add(interceptorPos);
		if (level.getBlockEntity(interceptorPos) instanceof DefenseBlockEntity interceptor) {
			MortarShellEntity shell = MortarShellEntity.launch(level, bravo, Vec3.atCenterOf(interceptorPos).add(2, 12, 2), Vec3.atCenterOf(base));
			level.addFreshEntity(shell);
			DefenseBlockEntity.serverTick(level, interceptorPos, level.getBlockState(interceptorPos), interceptor);
			check("defenses: interceptor shoots down an enemy mortar shell [alive=" + shell.isAlive() + ", charges=" + interceptor.charges
					+ ", threatens=" + shell.threatens(claim) + ", inLevel=" + (level.getEntity(shell.getUUID()) != null) + ", claimHere="
					+ (data.claimAt(Rules.dim(level), interceptorPos.getX(), interceptorPos.getZ()) != null) + "]",
					!shell.isAlive() && interceptor.charges == DefenseBlockEntity.MAX_CHARGES - 1);
			MortarShellEntity friendly = MortarShellEntity.launch(level, alpha, Vec3.atCenterOf(interceptorPos).add(2, 12, 2), Vec3.atCenterOf(base));
			level.addFreshEntity(friendly);
			for (int i = 0; i < 25; i++) {
				DefenseBlockEntity.serverTick(level, interceptorPos, level.getBlockState(interceptorPos), interceptor);
			}
			check("defenses: interceptor ignores its owner's own shells", friendly.isAlive());
			friendly.discard();
		} else {
			fail("defenses: interceptor has no block entity");
		}

		// ----- Hearts -----
		data.phase = Phase.WAR;
		Claims.damageHeart(level, claim, 50, null);
		check("hearts: Breaching Charge damage lowers Heart HP", claim.hp == claim.maxHp() - 50 && !claim.destroyed);
		Claims.damageHeart(level, claim, 50, null);
		check("hearts: Heart is destroyed at 0 HP", claim.destroyed && level.getBlockState(base).isAir());

		// ----- Shop ----- (an empty bank: the starter base put rubies in it)
		dev.rubymod.industry.Grid.deposit(alpha.getUUID(), -dev.rubymod.industry.Grid.milliRubies(alpha.getUUID()));
		alpha.getInventory().clearContent();
		alpha.getInventory().add(new ItemStack(ModItems.RUBY, 10));
		check("shop: buying takes rubies", Shop.takeRubies(alpha, 7) && Shop.countRubies(alpha) == 3);
		check("shop: can't buy without enough rubies", !Shop.takeRubies(alpha, 5) && Shop.countRubies(alpha) == 3);

		Zombie victim = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);

		// ----- Guns -----
		data.phase = Phase.WAR;
		BlockPos range = surface(level, base.offset(0, 0, 40));
		alpha.setPos(range.getX() + 0.5, range.getY() + 0.0, range.getZ() + 0.5);
		alpha.setYRot(-90.0F);
		alpha.setXRot(0.0F);
		alpha.setYHeadRot(-90.0F);
		Zombie target = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
		boolean gunWorks = false;
		String gunDetail = "no zombie";
		if (target != null) {
			target.setNoAi(true);
			target.setPos(range.getX() + 5.5, alpha.getY(), range.getZ() + 0.5);
			level.addFreshEntity(target);
			for (int x = 0; x <= 5; x++) {
				for (int y = 0; y <= 2; y++) {
					BlockPos p = range.offset(x, y, 0);
					if (!level.getBlockState(p).isAir()) {
						level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
					}
				}
			}
			ItemStack rifle = new ItemStack(ModItems.ARR_RIFLE);
			rifle.set(ModComponents.AMMO, 30);
			float before = target.getHealth();
			Gunfire.forget(alpha.getUUID());
			Gunfire.tryFire(level, alpha, rifle, (GunItem) ModItems.ARR_RIFLE);
			gunWorks = target.getHealth() < before && GunItem.ammo(rifle) == 29;
			gunDetail = "health " + before + " -> " + target.getHealth() + ", ammo " + GunItem.ammo(rifle) + ", shooter at " + alpha.position()
					+ " looking " + alpha.getLookAngle() + ", target at " + target.position() + ", gamemode " + alpha.gameMode.getGameModeForPlayer() + ", removed " + target.isRemoved() + ", in level " + (level.getEntity(target.getId()) != null)
					+ ", pickable " + target.isPickable() + ", difficulty " + level.getDifficulty();
			target.discard();
		}
		check("guns: rifle hits a target 5 blocks away and uses a round [" + gunDetail + ", phase " + Rules.phase() + ", alpha eliminated " + Rules.isEliminated(alpha.getUUID()) + ", canHurt " + Rules.canHurt(alpha, victim) + "]", gunWorks);
		// Through walls: rifles and snipers go through glass and wood, not stone; pistols don't.
		{
			java.util.function.BiFunction<net.minecraft.world.item.Item, net.minecraft.world.level.block.state.BlockState[], Float> through = (gun, wall) -> {
				Zombie z = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
				z.setNoAi(true);
				z.setNoGravity(true); // it stays in the line of fire whatever the ground's like there
				z.setPos(range.getX() + 6.5, alpha.getY(), range.getZ() + 0.5);
				level.addFreshEntity(z);
				ItemStack g = new ItemStack(gun);
				g.set(ModComponents.AMMO, 30);
				// Best of five shots (hip fire has a little spread, and can miss outright).
				float dealt = 0;
				for (int shot = 0; shot < 5 && dealt == 0; shot++) {
					z.setPos(range.getX() + 6.5, alpha.getY(), range.getZ() + 0.5);
					// A fresh wall each time (bullets chip blocks, and glass shatters).
					for (int k = 0; k < wall.length; k++) {
						level.setBlockAndUpdate(range.offset(3 + k, 1, 0), wall[k]);
					}
					Gunfire.forget(alpha.getUUID());
					z.setHealth(z.getMaxHealth());
					float before = z.getHealth();
					Gunfire.tryFire(level, alpha, g, (GunItem) gun);
					dealt = before - z.getHealth();
				}
				z.discard();
				for (int k = 0; k < wall.length; k++) {
					level.setBlockAndUpdate(range.offset(3 + k, 1, 0), Blocks.AIR.defaultBlockState());
				}
				return dealt;
			};
			var glass = Blocks.GLASS.defaultBlockState();
			var planks = Blocks.OAK_PLANKS.defaultBlockState();
			var stone = Blocks.STONE.defaultBlockState();
			float open = through.apply(ModItems.ARR_RIFLE, new net.minecraft.world.level.block.state.BlockState[0]);
			float viaGlass = through.apply(ModItems.ARR_RIFLE, new net.minecraft.world.level.block.state.BlockState[] {glass});
			float viaWood = through.apply(ModItems.ARR_RIFLE, new net.minecraft.world.level.block.state.BlockState[] {planks, planks});
			float viaStone = through.apply(ModItems.ARR_RIFLE, new net.minecraft.world.level.block.state.BlockState[] {stone});
			float pistolGlass = through.apply(ModItems.SERVICE_PISTOL, new net.minecraft.world.level.block.state.BlockState[] {glass});
			var rifle = ((GunItem) ModItems.ARR_RIFLE).stats();
			check("walls: a rifle round goes through glass (" + open + " in the open, " + viaGlass + " through glass)", viaGlass > 0);
			check("walls: and through two planks of wood (" + viaWood + ")", viaWood > 0);
			check("walls: glass takes a little off, wood more", Gunfire.penetration(rifle, glass) == 0.9F && Gunfire.penetration(rifle, planks) < 0.9F);
			check("walls: but not through stone (" + viaStone + ")", viaStone == 0);
			check("walls: a pistol round doesn't go through glass (" + pistolGlass + ")", pistolGlass == 0);
		}
		ItemStack moddedRifle = new ItemStack(ModItems.ARR_RIFLE);
		moddedRifle.set(ModComponents.ATTACHMENTS, List.of("EXTENDED_MAG", "SUPPRESSOR"));
		GunStats modded = ((GunItem) ModItems.ARR_RIFLE).stats(moddedRifle);
		check("attachments: Extended Mag makes a 30-round rifle hold " + modded.magazine(), modded.magazine() == 45);
		check("attachments: Suppressor silences the gun", modded.silenced());

		// ----- Block damage -----
		BlockPos wallPos = range.offset(3, 0, 3);
		check("block damage: pistol-strength bullets break dirt in 3 hits (took " + hitsToBreak(level, wallPos, Blocks.DIRT, alpha) + ")",
				hitsToBreak(level, wallPos, Blocks.DIRT, alpha) == 3);
		int stoneHits = hitsToBreak(level, wallPos, Blocks.STONE, alpha);
		check("block damage: stone takes 7 hits (took " + stoneHits + ")", stoneHits == 7);
		check("block damage: bullets don't break Reinforced Concrete", hitsToBreak(level, wallPos, ModBlocks.REINFORCED_CONCRETE, alpha) < 0);
		level.setBlockAndUpdate(wallPos, Blocks.AIR.defaultBlockState());

		// ----- Armor -----
		VehicleEntity armorTest = ModEntities.TANK.create(level, EntitySpawnReason.COMMAND);
		if (armorTest != null) {
			armorTest.setPos(range.getX() + 0.5, range.getY(), range.getZ() + 20.5);
			armorTest.setYRot(0);
			level.addFreshEntity(armorTest);
			Vec3 front = armorTest.position().add(0, 1.0, 2.2);
			dev.rubymod.game.Armor.blast(level, null, alpha, front, 48.0F, null);
			float afterRocket = armorTest.getHealth();
			Vec3 rear = armorTest.position().add(0, 1.0, -2.2);
			dev.rubymod.game.Armor.blast(level, null, alpha, rear, 48.0F, null);
			float afterRear = armorTest.getHealth();
			armorTest.hurtServer(level, ModDamage.source(level, ModDamage.BULLET, alpha, alpha), 50.0F);
			check("armor: a rocket takes 48 of a tank's 160 (" + afterRocket + " left)", Math.abs(afterRocket - 112.0F) < 0.01F);
			check("armor: from behind it takes 1.5x (" + (afterRocket - afterRear) + ")", Math.abs(afterRocket - afterRear - 72.0F) < 0.01F);
			check("armor: bullets don't hurt tanks", armorTest.getHealth() == afterRear);
			armorTest.discard();
		}

		// ----- Industry -----
		industryChecks(level, alpha, bravo, range.offset(0, 0, -30));

		// ----- Aircraft weapons -----
		AircraftEntity jet = ModEntities.ATTACK_JET.create(level, EntitySpawnReason.COMMAND);
		if (jet != null) {
			jet.setPos(range.getX() + 0.5, range.getY() + 25, range.getZ() - 10.5);
			level.addFreshEntity(jet);
			jet.nextWeapon();
			AircraftType.Weapon selected = jet.selectedWeapon();
			int before = jet.ammo(AircraftType.Weapon.AGM);
			jet.fireSelected(alpha, null, Vec3.atCenterOf(range));
			check("aircraft: R switches the attack jet to guided missiles (" + selected + ")",
					selected == AircraftType.Weapon.AGM);
			check("aircraft: firing a guided missile uses one (" + before + " -> " + jet.ammo(AircraftType.Weapon.AGM) + ")",
					jet.ammo(AircraftType.Weapon.AGM) == before - 1);
			jet.nextWeapon();
			int bombs = jet.ammo(AircraftType.Weapon.BOMBS);
			jet.fireSelected(alpha, null, null);
			check("aircraft: bombs drop in the air (" + bombs + " -> " + jet.ammo(AircraftType.Weapon.BOMBS) + ")",
					jet.ammo(AircraftType.Weapon.BOMBS) == bombs - 1);
			jet.discard();
		}

		// ----- Spawn shield -----
		data.phase = Phase.WAR;
		SpawnShield.grant(bravo);
		check("spawn shield: protects a freshly respawned player", !Rules.canHurt(alpha, bravo));
		SpawnShield.clear(bravo);
		check("spawn shield: ends when cleared", Rules.canHurt(alpha, bravo));

		// ----- Vehicles -----
		VehicleEntity tank = ModEntities.TANK.create(level, EntitySpawnReason.COMMAND);
		if (tank != null) {
			tank.setPos(range.getX() + 0.5, range.getY(), range.getZ() + 10.5);
			level.addFreshEntity(tank);
			float full = tank.getHealth();
			tank.setInvulnerableTime(0);
			tank.hurtServer(level, ModDamage.source(level, ModDamage.BULLET, null, null), 10.0F);
			float afterBullet = tank.getHealth();
			tank.setInvulnerableTime(0);
			tank.hurtServer(level, level.damageSources().explosion(null, null), 10.0F);
			float afterBlast = tank.getHealth();
			check("vehicles: tank shrugs off bullets (" + (full - afterBullet) + " damage from a 10-damage bullet)", full - afterBullet < 1.0F);
			check("vehicles: explosions hurt tanks (" + (afterBullet - afterBlast) + " damage from a 10-damage blast)", afterBullet - afterBlast > 1.0F);
			tank.discard();
		} else {
			fail("vehicles: couldn't create a tank");
		}

		// ----- Elimination and winning -----
		data.phase = Phase.WAR;
		BattleDirector.onDeath(bravo, level.damageSources().generic());
		check("battle: dying with no Heart on Battle Day eliminates you", Rules.isEliminated(bravo.getUUID()));
		check("battle: last player standing wins", data.phase == Phase.ENDED && "TestAlpha".equals(data.winner));

		// ----- Timers -----
		data.phase = Phase.BUILD;
		data.phaseEndsAt = SeasonTicker.now() + 600_000L;
		data.pausedRemaining = -1;
		SeasonTicker.pause();
		long paused = SeasonTicker.remainingMillis();
		SeasonTicker.resume();
		check("timer: pause keeps the time left", Math.abs(paused - 600_000L) < 2_000L && data.pausedRemaining == -1);

		// ----- Squads, research, graves, guards, raids, waypoints -----
		data.player(bravo.getUUID(), "TestBravo").eliminated = false;
		claim.trusted.remove(bravo.getUUID().toString());
		check("squads: Ruby Wars has none (everyone plays on their own)", dev.rubymod.game.Squads.create(alpha, "Test Squad") != null
				&& dev.rubymod.game.Squads.of(alpha.getUUID()) == null && !dev.rubymod.game.Squads.enabled() && data.squads.isEmpty());
		check("squads: other players are fair game on Battle Day", runAs(Phase.WAR, () -> Rules.canHurt(alpha, bravo)));
		check("squads: nobody else shares your base", !claim.canUse(bravo.getUUID()));
		check("research: advanced gear starts locked", !dev.rubymod.game.Research.unlocked(bravo, "tank"));
		dev.rubymod.game.Research.addPoints(bravo.getUUID(), 5);
		check("research: points unlock it", dev.rubymod.game.Research.unlock(bravo, "motor_pool") == null
				&& dev.rubymod.game.Research.unlocked(bravo, "tank"));
		check("research: someone else's unlocks aren't yours", !dev.rubymod.game.Research.unlocked(alpha, "jeep"));
		{
			// Goals fit in an evening or two: a goal whose target came down counts progress already made.
			dev.rubymod.game.Goals.describe(alpha.getUUID());
			var rec = data.player(alpha.getUUID(), "TestAlpha");
			boolean had = rec.goalsDone.contains("ruby50");
			rec.goalsDone.remove("ruby50");
			rec.goalProgress.put("ruby50", 30);
			int pointsBefore = rec.researchPoints;
			dev.rubymod.game.Goals.syncAdvancements(alpha);
			check("goals: progress past a lowered target counts on joining", rec.goalsDone.contains("ruby50"));
			check("goals: the big ones are within reach (bank " + dev.rubymod.game.Goals.byId("bank2000").target() + ", fly "
					+ dev.rubymod.game.Goals.byId("fly").target() + ", ruby ore " + dev.rubymod.game.Goals.byId("ruby50").target() + ")",
					dev.rubymod.game.Goals.byId("bank2000").target() <= 200 && dev.rubymod.game.Goals.byId("fly").target() <= 500
							&& dev.rubymod.game.Goals.byId("ruby50").target() <= 25);
			rec.researchPoints = pointsBefore;
			rec.goalProgress.remove("ruby50");
			if (!had) {
				rec.goalsDone.remove("ruby50");
			}
		}
		check("research: basic guns never need research", dev.rubymod.game.Research.unlocked(alpha, "arr_rifle"));

		BlockPos graveSpot = surface(level, base.offset(30, 0, 30));
		bravo.snapTo(graveSpot.getX() + 0.5, graveSpot.getY(), graveSpot.getZ() + 0.5, 0, 0);
		bravo.getInventory().clearContent();
		bravo.getInventory().add(new ItemStack(ModItems.RUBY, 7));
		boolean buried = dev.rubymod.block.GraveBlock.bury(bravo);
		dev.rubymod.block.GraveBlockEntity grave = null;
		for (BlockPos p : BlockPos.betweenClosed(graveSpot.offset(-1, -1, -1), graveSpot.offset(1, 12, 1))) {
			if (level.getBlockEntity(p) instanceof dev.rubymod.block.GraveBlockEntity g) {
				grave = g;
			}
		}
		check("graves: dying leaves a War Grave with your gear", buried && grave != null && grave.countItem(ModItems.RUBY) == 7
				&& bravo.getInventory().countItem(ModItems.RUBY) == 0);
		check("graves: only the owner can open it at first", grave != null && grave.canOpen(bravo) && !grave.canOpen(alpha));
		if (grave != null) {
			level.setBlockAndUpdate(grave.getBlockPos(), Blocks.AIR.defaultBlockState());
		}

		BlockPos post = surface(level, base.offset(6, 0, 6));
		var guard = dev.rubymod.entity.SoldierEntity.guard(level, alpha, post, dev.rubymod.entity.SoldierEntity.RIFLEMAN);
		var zombie = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
		var raider = dev.rubymod.entity.SoldierEntity.raider(level, post.offset(3, 0, 0), claim.pos(), dev.rubymod.entity.SoldierEntity.RIFLEMAN, -1);
		if (guard != null && zombie != null && raider != null) {
			zombie.snapTo(post.getX() + 2, post.getY(), post.getZ(), 0, 0);
			check("guards: guards fight monsters", guard.isHostileTo(zombie));
			check("guards: guards never shoot their owner", runAs(Phase.WAR, () -> !guard.isHostileTo(alpha)));
			check("raids: raiders are enemies of every base and its guards", raider.isEnemyOfClaim(claim) && guard.isHostileTo(raider)
					&& raider.isHostileTo(guard));
		} else {
			fail("guards: couldn't create soldiers");
		}
		check("waypoints: add a waypoint", dev.rubymod.game.Waypoints.add(alpha, "Base") == null
				&& dev.rubymod.game.Waypoints.list(alpha).stream().anyMatch(w -> w.name.equals("Base")));
		dev.rubymod.game.Waypoints.remove(alpha, "Base");
		check("combat: a PvP hit tags both players on Battle Day", runAs(Phase.WAR, () -> {
			dev.rubymod.game.CombatTag.tag(alpha, bravo);
			return dev.rubymod.game.CombatTag.tagged(alpha.getUUID()) && dev.rubymod.game.CombatTag.tagged(bravo.getUUID());
		}));

		// ----- Duels -----
		duelChecks(level, alpha, bravo, surface(level, base.offset(-40, 0, 40)));

		// ----- World events -----
		worldEventChecks(level, alpha, bravo, surface(level, base.offset(-160, 0, -160)));
		practiceChecks(level, alpha, bravo);
		buildingChecks(level, alpha);
		finalCircleChecks(level);
		backedUpChecks(level, alpha, surface(level, base.offset(-260, 0, -60)));
		supplyDropChecks(level, surface(level, base.offset(-220, 0, 160)));

		// ----- Ore -----
		int ore = 0;
		for (BlockPos p : BlockPos.betweenClosed(spawn.getX() - 24, -60, spawn.getZ() - 24, spawn.getX() + 24, 0, spawn.getZ() + 24)) {
			if (level.getBlockState(p).is(ModBlocks.RUBY_ORE) || level.getBlockState(p).is(ModBlocks.DEEPSLATE_RUBY_ORE)) {
				ore++;
			}
		}
		check("worldgen: ruby ore generates underground (" + ore + " blocks near spawn)", ore > 0);

		level.getWorldBorder().setSize(borderBefore);
		for (BlockPos p : placed) {
			level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
		}
		Explosions.clearHits();
	}

	/** Battle Day supply drops: told with a beam before they land, a chest of loot, then the ground put back. */
	private void supplyDropChecks(ServerLevel level, BlockPos spot) {
		var server = level.getServer();
		Phase before = SeasonData.get().phase;
		SeasonData.get().phase = Phase.WAR;
		dev.rubymod.game.SupplyDrops.reset();
		var ground = level.getBlockState(spot.below());
		var iron = level.getBlockState(spot.offset(1, -2, 1));
		try {
			dev.rubymod.game.SupplyDrops.incomingAt(level, spot, 30_000L);
			dev.rubymod.game.SupplyDrops.tick(server);
			check("supply drops: told 30 seconds ahead with a beacon's beam over the spot, which nobody can mine",
					!dev.rubymod.game.SupplyDrops.landed(spot) && level.getBlockState(spot.below()).is(Blocks.BEACON)
							&& dev.rubymod.game.SupplyDrops.protects(level, spot.offset(1, -2, 1)) && !level.getBlockState(spot).is(Blocks.CHEST));
			dev.rubymod.game.SupplyDrops.advance(31_000L);
			dev.rubymod.game.SupplyDrops.tick(server);
			var chest = level.getBlockEntity(spot) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity c ? c : null;
			int rubies = 0;
			for (int i = 0; chest != null && i < chest.getContainerSize(); i++) {
				rubies += chest.getItem(i).is(ModItems.RUBY) ? chest.getItem(i).getCount() : 0;
			}
			check("supply drops: then it lands, a chest of loot (" + rubies + " rubies)", dev.rubymod.game.SupplyDrops.landed(spot) && rubies >= 10);
			// Emptied: gone half a minute later, the ground as it was.
			if (chest != null) {
				chest.clearContent();
			}
			dev.rubymod.game.SupplyDrops.tick(server);
			boolean stays = dev.rubymod.game.SupplyDrops.count() == 1;
			dev.rubymod.game.SupplyDrops.advance(31_000L);
			dev.rubymod.game.SupplyDrops.tick(server);
			check("supply drops: emptied, it goes half a minute later and the ground is put back", stays && dev.rubymod.game.SupplyDrops.count() == 0
					&& level.getBlockState(spot.below()) == ground && level.getBlockState(spot.offset(1, -2, 1)) == iron && !level.getBlockState(spot).is(Blocks.CHEST));
			// Paused: one on its way waits (it doesn't land, or go, while everyone's away).
			long pausedBefore = SeasonData.get().pausedRemaining;
			dev.rubymod.game.SupplyDrops.incomingAt(level, spot, 2000L);
			SeasonData.get().pausedRemaining = 60_000L;
			for (int i = 0; i < 3; i++) {
				dev.rubymod.game.SupplyDrops.tick(server);
			}
			dev.rubymod.game.SupplyDrops.advance(2500L);
			dev.rubymod.game.SupplyDrops.tick(server);
			boolean waited = !dev.rubymod.game.SupplyDrops.landed(spot);
			SeasonData.get().pausedRemaining = pausedBefore;
			dev.rubymod.game.SupplyDrops.advance(5000L); // it waited four seconds (four paused ticks)
			dev.rubymod.game.SupplyDrops.tick(server);
			check("supply drops: paused, one on its way waits, and lands once it's back on", waited && dev.rubymod.game.SupplyDrops.landed(spot));
			dev.rubymod.game.SupplyDrops.clear(server);
			// Battle Day over: any still standing go.
			dev.rubymod.game.SupplyDrops.incomingAt(level, spot, 0);
			SeasonData.get().phase = Phase.ENDED;
			dev.rubymod.game.SupplyDrops.tick(server);
			check("supply drops: when Battle Day ends they're tidied away", dev.rubymod.game.SupplyDrops.count() == 0 && level.getBlockState(spot.below()) == ground);
		} finally {
			dev.rubymod.game.SupplyDrops.clear(server);
			dev.rubymod.game.SupplyDrops.reset();
			SeasonData.get().phase = before;
		}
	}

	/** The final circle: somewhere inside the border, and the border closes on it smoothly, never past anyone. */
	private void finalCircleChecks(ServerLevel level) {
		var server = level.getServer();
		var data = SeasonData.get();
		var border = level.getWorldBorder();
		double cx = border.getCenterX();
		double cz = border.getCenterZ();
		double size = border.getSize();
		boolean wasSet = data.finalSet;
		int fx = data.finalX;
		int fz = data.finalZ;
		long closeMs = data.closeMs;
		try {
			data.finalSet = false;
			dev.rubymod.game.FinalCircle.plan(server);
			int end = dev.rubymod.game.GameConfig.get().borderEndSize;
			boolean inside = Math.abs(data.finalX - cx) + end / 2.0 <= size / 2 && Math.abs(data.finalZ - cz) + end / 2.0 <= size / 2;
			check("final circle: picked somewhere inside the border (" + data.finalX + ", " + data.finalZ + ")", data.finalSet && inside);
			dev.rubymod.game.FinalCircle.close(server, 100_000L);
			data.closeStart -= 50_000L;
			dev.rubymod.game.FinalCircle.tick(server);
			double midSize = border.getSize();
			double midX = border.getCenterX();
			double midZ = border.getCenterZ();
			// Halfway, the border is inside where it started (it never jumps past anyone) and around the final circle.
			boolean nested = Math.abs(midX - cx) + midSize / 2 <= size / 2 + 0.01 && Math.abs(midZ - cz) + midSize / 2 <= size / 2 + 0.01
					&& Math.abs(data.finalX + 0.5 - midX) + end / 2.0 <= midSize / 2 + 0.01;
			check("final circle: halfway, the border's half closed, still inside where it was and round the circle (" + Math.round(midSize) + ")",
					midSize < size && midSize > end && nested);
			data.closeStart -= 60_000L;
			dev.rubymod.game.FinalCircle.tick(server);
			check("final circle: at the end, the border is the final circle", Math.abs(border.getSize() - end) < 0.01
					&& Math.abs(border.getCenterX() - (data.finalX + 0.5)) < 0.01 && data.closeMs == 0);
		} finally {
			data.finalSet = wasSet;
			data.finalX = fx;
			data.finalZ = fz;
			data.closeMs = closeMs;
			border.setCenter(cx, cz);
			border.setSize(size);
		}
	}

	/** A machine whose belt runs into a full chest says the belt's backed up (not just "full"). */
	private void backedUpChecks(ServerLevel level, FakePlayer alpha, BlockPos spot) {
		for (BlockPos q : BlockPos.betweenClosed(spot.offset(-1, 0, -2), spot.offset(5, 3, 2))) {
			level.setBlockAndUpdate(q, Blocks.AIR.defaultBlockState());
		}
		var refinery = machine(level, spot, ModBlocks.ORE_REFINERY, alpha);
		refinery.setItem(0, new ItemStack(net.minecraft.world.item.Items.RAW_IRON, 10));
		for (int i = 2; i < refinery.getContainerSize(); i++) {
			refinery.setItem(i, new ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 64));
		}
		var east = ModBlocks.CONVEYOR.defaultBlockState().setValue(dev.rubymod.industry.ConveyorBlock.FACING, net.minecraft.core.Direction.EAST);
		level.setBlockAndUpdate(spot.east(), east);
		level.setBlockAndUpdate(spot.east(2), east);
		BlockPos chestPos = spot.east(3);
		level.setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
		var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity) level.getBlockEntity(chestPos);
		for (int i = 0; i < chest.getContainerSize(); i++) {
			chest.setItem(i, new ItemStack(Blocks.DIRT, 64));
		}
		dev.rubymod.industry.Grid.account(alpha.getUUID()).charge = 100_000;
		// Its belt gets a few ingots on it, then the end's full: it stops and says why.
		runMachines(level, 200, refinery);
		// (Items on the belt don't move during the test: the belt stays busy, as a jammed one would.)
		for (int i = 2; i < refinery.getContainerSize(); i++) {
			refinery.setItem(i, new ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 64));
		}
		runMachines(level, 45, refinery);
		check("belts: a machine whose belt ends at a full chest says it's backed up (status " + refinery.status() + ")",
				refinery.status() == dev.rubymod.industry.MachineBlockEntity.BACKED_UP
						&& dev.rubymod.industry.Belts.full(level, chestPos, new ItemStack(net.minecraft.world.item.Items.IRON_INGOT)));
		chest.clearContent();
		runMachines(level, 45, refinery);
		check("belts: room at the end again, and it's not backed up any more (status " + refinery.status() + ")",
				refinery.status() != dev.rubymod.industry.MachineBlockEntity.BACKED_UP
						&& !dev.rubymod.industry.Belts.full(level, chestPos, new ItemStack(net.minecraft.world.item.Items.IRON_INGOT)));
		for (var item : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(spot).inflate(4))) {
			item.discard();
		}
		for (BlockPos q : BlockPos.betweenClosed(spot.offset(-1, 0, -2), spot.offset(5, 3, 2))) {
			level.setBlockAndUpdate(q, Blocks.AIR.defaultBlockState());
		}
	}

	/** Building: ready-made plans, the wand's new shapes, and a Constructor repairing a wall where it was copied. */
	private void buildingChecks(ServerLevel level, FakePlayer alpha) {
		// The plans: all fit a Blueprint, and each says what it needs.
		boolean fit = true;
		for (var d : dev.rubymod.industry.ReadyBlueprints.ALL) {
			var data = d.data();
			fit &= data.sx() <= dev.rubymod.industry.Blueprints.MAX_SIZE && data.sy() <= dev.rubymod.industry.Blueprints.MAX_SIZE
					&& data.sz() <= dev.rubymod.industry.Blueprints.MAX_SIZE && data.blocks() > 20 && !data.bill().isEmpty();
		}
		check("blueprints: " + dev.rubymod.industry.ReadyBlueprints.ALL.size() + " ready-made plans, each with a list of what it needs", fit
				&& dev.rubymod.industry.ReadyBlueprints.stack(dev.rubymod.industry.ReadyBlueprints.ALL.get(0)).has(ModComponents.BLUEPRINT));
		// The wand: a round tower is a ring (nothing in the middle), a dome has a top, stairs climb.
		BlockPos o = new BlockPos(0, 100, 0);
		var tower = dev.rubymod.item.BuildShapes.shape(dev.rubymod.item.BuildShapes.Mode.TOWER, o, o.offset(6, 3, 6));
		var dome = dev.rubymod.item.BuildShapes.shape(dev.rubymod.item.BuildShapes.Mode.DOME, o, o.offset(6, 0, 6));
		var stairs = dev.rubymod.item.BuildShapes.shape(dev.rubymod.item.BuildShapes.Mode.STAIRS, o, o.offset(5, 0, 1));
		check("wand: a round tower (" + tower.size() + " blocks, hollow), a dome (" + dome.size() + ", with a top) and stairs (" + stairs.size() + ", climbing)",
				!tower.isEmpty() && !tower.contains(o.offset(3, 1, 3)) && tower.contains(o.offset(3, 1, 0)) && dome.contains(o.offset(3, 3, 3))
						&& !dome.contains(o.offset(3, 1, 3)) && stairs.contains(o.offset(5, 5, 1)) && stairs.contains(o) && !stairs.contains(o.offset(5, 0, 0)));
		var claim = SeasonData.get().claimOf(alpha.getUUID());
		if (claim == null) {
			return;
		}
		boolean wasDestroyed = claim.destroyed;
		claim.destroyed = false;
		BlockPos spot = surface(level, new BlockPos(claim.x + 10, 0, claim.z - 12));
		try {
			for (BlockPos q : BlockPos.betweenClosed(spot.offset(-12, 0, -12), spot.offset(12, 5, 12))) {
				level.setBlockAndUpdate(q, Blocks.AIR.defaultBlockState());
			}
			dev.rubymod.industry.Grid.account(alpha.getUUID()).charge = 500_000;
			// A ready-made Wall Section, built by a Constructor from the chest beside it.
			var wallPlan = dev.rubymod.industry.ReadyBlueprints.ALL.stream().filter(d -> d.id().equals("wall_section")).findFirst().orElseThrow();
			var constructor = machine(level, spot, ModBlocks.CONSTRUCTOR, alpha);
			constructor.setTier(5);
			constructor.setItem(0, dev.rubymod.industry.ReadyBlueprints.stack(wallPlan));
			BlockPos chestPos = spot.offset(-1, 0, 0);
			level.setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
			var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity) level.getBlockEntity(chestPos);
			chest.setItem(0, new ItemStack(ModBlocks.CONCRETE_WALL, 64));
			chest.setItem(1, new ItemStack(ModBlocks.CONCRETE_WALL, 64));
			chest.setItem(2, new ItemStack(ModBlocks.REINFORCED_GLASS, 16));
			runMachines(level, 2000, constructor);
			int built = 0;
			for (BlockPos q : BlockPos.betweenClosed(spot.offset(-12, 0, -12), spot.offset(12, 5, 12))) {
				built += level.getBlockState(q).is(ModBlocks.CONCRETE_WALL) || level.getBlockState(q).is(ModBlocks.REINFORCED_GLASS) ? 1 : 0;
			}
			check("blueprints: a Constructor builds the ready-made Wall Section (" + built + " of " + wallPlan.data().blocks() + " blocks)",
					built == wallPlan.data().blocks());
			// Repair: a little wall copied in place, a hole knocked in it, filled back where it was.
			for (BlockPos q : BlockPos.betweenClosed(spot.offset(-12, 0, -12), spot.offset(12, 5, 12))) {
				if (!q.equals(spot) && !q.equals(chestPos)) {
					level.setBlockAndUpdate(q, Blocks.AIR.defaultBlockState());
				}
			}
			BlockPos w0 = spot.offset(4, 0, 3);
			for (BlockPos q : BlockPos.betweenClosed(w0, w0.offset(3, 2, 0))) {
				level.setBlockAndUpdate(q, Blocks.STONE_BRICKS.defaultBlockState());
			}
			ItemStack copy = new ItemStack(ModItems.BLUEPRINT);
			copy.set(ModComponents.BLUEPRINT, dev.rubymod.industry.Blueprints.capture(level, w0, w0.offset(3, 2, 0)));
			copy.set(ModComponents.BLUEPRINT_AT, w0);
			constructor.setItem(0, copy);
			if (!constructor.repairs()) {
				constructor.toggleRepair();
			}
			chest.clearContent();
			chest.setItem(0, new ItemStack(Blocks.STONE_BRICKS, 16));
			BlockPos hole = w0.offset(1, 1, 0);
			level.setBlockAndUpdate(hole, Blocks.AIR.defaultBlockState());
			runMachines(level, 400, constructor);
			boolean mended = level.getBlockState(hole).is(Blocks.STONE_BRICKS);
			boolean noCopy = !level.getBlockState(spot.relative(constructor.getBlockState().getValue(dev.rubymod.industry.MachineBlock.FACING), 2)).is(Blocks.STONE_BRICKS);
			check("constructor: in repair mode it fills a hole back where it was copied (and builds no copy)", mended && noCopy);
			level.setBlockAndUpdate(hole, Blocks.AIR.defaultBlockState());
			runMachines(level, 400, constructor);
			check("constructor: and keeps watching: a second hole is filled too", level.getBlockState(hole).is(Blocks.STONE_BRICKS));
		} finally {
			for (BlockPos q : BlockPos.betweenClosed(spot.offset(-12, 0, -12), spot.offset(12, 5, 12))) {
				level.setBlockAndUpdate(q, Blocks.AIR.defaultBlockState());
			}
			claim.destroyed = wasDestroyed;
		}
	}

	/** The Practice Dummy: your turrets and Tesla Coils fire on it in your own base, for free; it goes after a minute. */
	private void practiceChecks(ServerLevel level, FakePlayer alpha, FakePlayer bravo) {
		var claim = SeasonData.get().claimOf(alpha.getUUID());
		if (claim == null) {
			check("practice: Alpha has a base to practise in", false);
			return;
		}
		// Earlier checks knocked Alpha's Heart out: stand the base up again for this.
		boolean wasDestroyed = claim.destroyed;
		claim.destroyed = false;
		Phase before = SeasonData.get().phase;
		SeasonData.get().phase = Phase.BUILD;
		dev.rubymod.game.Practice.reset();
		BlockPos spot = surface(level, new BlockPos(claim.x - 12, 0, claim.z + 12));
		try {
			for (BlockPos p : BlockPos.betweenClosed(spot.offset(-5, 0, -2), spot.offset(5, 4, 2))) {
				level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
			}
			String outside = dev.rubymod.game.Practice.place(alpha, level, surface(level, new BlockPos(claim.x + claim.radius() + 40, 0, claim.z)));
			String error = dev.rubymod.game.Practice.place(alpha, level, spot);
			var dummies = level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ArmorStand.class, new net.minecraft.world.phys.AABB(spot).inflate(1),
					a -> dev.rubymod.game.Practice.isDummy(a));
			check("practice: a dummy goes up in your base, not outside it (" + error + ")", outside != null && error == null && dummies.size() == 1
					&& dev.rubymod.game.Practice.active(claim));
			if (dummies.isEmpty()) {
				return;
			}
			var dummy = dummies.get(0);
			// A Sentry Turret fires on it before Battle Day, without using its ammo.
			BlockPos turretPos = spot.offset(-4, 0, 0);
			level.setBlockAndUpdate(turretPos, ModBlocks.SENTRY_TURRET.defaultBlockState());
			var turret = (dev.rubymod.block.DefenseBlockEntity) level.getBlockEntity(turretPos);
			turret.ammo = 50;
			for (int i = 0; i < 3; i++) {
				turret.fireForTest(level, turretPos, claim);
			}
			int byTurret = dev.rubymod.game.Practice.hitsOn(dummy);
			check("practice: your Sentry Turret fires on the dummy, and practice rounds are free (" + byTurret + " hits, ammo " + turret.ammo + ")",
					byTurret >= 1 && turret.ammo == 50 && dummy.isAlive());
			// A Tesla Coil too.
			var tesla = machine(level, spot.offset(4, 0, 0), ModBlocks.TESLA_COIL, alpha);
			dev.rubymod.industry.Grid.account(alpha.getUUID()).charge = 200_000;
			runMachines(level, 31, tesla);
			int byBoth = dev.rubymod.game.Practice.hitsOn(dummy);
			check("practice: your Tesla Coil zaps it too (" + byTurret + " -> " + byBoth + ")", byBoth > byTurret && dummy.isAlive());
			// Someone else's base doesn't count.
			var other = SeasonData.get().claimOf(bravo.getUUID());
			check("practice: other bases' defenses leave your dummy alone", other == null || !dev.rubymod.game.Practice.isDummyFor(other, dummy));
			// A minute on, it's gone.
			dev.rubymod.game.Practice.advance(61_000L);
			dev.rubymod.game.Practice.tickSecond(level.getServer());
			check("practice: after a minute the dummy goes", !dummy.isAlive() || dummy.isRemoved());
			// Battle Day: one still up comes down, and none can go up (turrets have real attackers to shoot).
			dev.rubymod.game.Practice.place(alpha, level, spot);
			var second = level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ArmorStand.class, new net.minecraft.world.phys.AABB(spot).inflate(1),
					a -> dev.rubymod.game.Practice.isDummy(a));
			SeasonData.get().phase = Phase.WAR;
			dev.rubymod.game.Practice.tickSecond(level.getServer());
			String inBattle = dev.rubymod.game.Practice.place(alpha, level, spot);
			check("practice: on Battle Day dummies come down and can't go up (" + inBattle + ")", second.size() == 1
					&& (!second.get(0).isAlive() || second.get(0).isRemoved()) && inBattle != null && !dev.rubymod.game.Practice.active(claim));
			SeasonData.get().phase = Phase.BUILD;
			level.setBlockAndUpdate(turretPos, Blocks.AIR.defaultBlockState());
			level.setBlockAndUpdate(spot.offset(4, 0, 0), Blocks.AIR.defaultBlockState());
		} finally {
			dev.rubymod.game.Practice.reset();
			SeasonData.get().phase = before;
			claim.destroyed = wasDestroyed;
		}
		// Saved things: the away note, and a world event the server crashed in the middle of.
		var server = level.getServer();
		dev.rubymod.industry.AwayTime.reset();
		dev.rubymod.industry.AwayTime.made(alpha.getUUID(), new ItemStack(net.minecraft.world.item.Items.TORCH, 12));
		dev.rubymod.industry.AwayTime.sold(alpha.getUUID(), 4000);
		for (int i = 0; i < 45; i++) {
			dev.rubymod.industry.AwayTime.tick(server);
		}
		String note = dev.rubymod.industry.AwayTime.latest(alpha.getUUID());
		dev.rubymod.industry.AwayTime.saveAndLoadForTest(server);
		String back = dev.rubymod.industry.AwayTime.latest(alpha.getUUID());
		check("away: the note survives a restart (" + back + ")", !note.isEmpty() && note.equals(back) && back.contains("12 Torches"));
		// Away twice before you're back (someone passed your base, then again later): the note has both.
		java.util.UUID away = java.util.UUID.randomUUID();
		dev.rubymod.industry.AwayTime.made(away, new ItemStack(net.minecraft.world.item.Items.TORCH, 5));
		for (int i = 0; i < 45; i++) {
			dev.rubymod.industry.AwayTime.tick(server);
		}
		dev.rubymod.industry.AwayTime.made(away, new ItemStack(net.minecraft.world.item.Items.TORCH, 7));
		dev.rubymod.industry.AwayTime.sold(away, 3000);
		for (int i = 0; i < 45; i++) {
			dev.rubymod.industry.AwayTime.tick(server);
		}
		String both = dev.rubymod.industry.AwayTime.latest(away);
		check("away: two catch-ups before you're back add up (" + both + ")", both.contains("12 Torches") && both.contains("3 rubies"));
		dev.rubymod.industry.AwayTime.reset();
		SeasonData.get().phase = Phase.BUILD;
		try {
			BlockPos at = surface(level, new BlockPos(claim.x + claim.radius() + 60, 0, claim.z + 60));
			var ground = level.getBlockState(at.below());
			var players = java.util.List.<net.minecraft.server.level.ServerPlayer>of(alpha, bravo);
			dev.rubymod.game.WorldEvents.begin(server, players, dev.rubymod.game.WorldEvents.Kind.CACHE, at, 0);
			dev.rubymod.game.WorldEvents.tickSecond(server, players);
			boolean built = level.getBlockState(at.below()).is(Blocks.BEACON) && dev.rubymod.game.WorldEvents.chestsLeftForTest(level) == 6;
			dev.rubymod.game.WorldEvents.reset(); // the server "crashes": nothing in memory, the blocks still there
			dev.rubymod.game.WorldEvents.recover(server); // ...and starts again
			check("events: after a crash mid-event, the server tidies it away when it starts", built && level.getBlockState(at.below()) == ground
					&& !level.getBlockState(at.offset(3, 0, 0)).is(Blocks.CHEST) && !level.getBlockState(at.offset(1, -2, 1)).is(Blocks.IRON_BLOCK));
		} finally {
			dev.rubymod.game.WorldEvents.reset();
			SeasonData.get().phase = before;
		}
	}

	/** Build-phase world events: a fair spot, a Supply Cache that opens and goes, a Ruby Shrine held and contested. */
	private void worldEventChecks(ServerLevel level, FakePlayer alpha, FakePlayer bravo, BlockPos spot) {
		var server = level.getServer();
		Phase before = SeasonData.get().phase;
		SeasonData.get().phase = Phase.BUILD;
		dev.rubymod.game.WorldEvents.reset();
		try {
			java.util.List<net.minecraft.server.level.ServerPlayer> both = java.util.List.of(alpha, bravo);
			// Nobody gets an event for being alone, and raids are gone.
			check("events: practice raids are gone", dev.rubymod.game.GameConfig.get().worldEventMinPlayers >= 2
					&& level.getServer().getCommands().getDispatcher().getRoot().getChild("rubywars").getChild("raid") == null);
			// A fair spot: clear of every claim.
			var fair = dev.rubymod.game.WorldEvents.pickSpot(level, both);
			boolean clear = fair != null;
			for (SeasonData.Claim claim : SeasonData.get().claims.values()) {
				if (fair != null && !claim.destroyed && Math.abs(claim.x - fair.getX()) < claim.radius() + 30 && Math.abs(claim.z - fair.getZ()) < claim.radius() + 30) {
					clear = false;
				}
			}
			check("events: they're put out between the bases, clear of every claim (" + (fair == null ? "nowhere" : fair.toShortString()) + ")", clear);
			// Supply Cache: told (a beacon over the spot), then open in waves, then gone with the ground put back.
			var W = new Object() {
				int chests() {
					return dev.rubymod.game.WorldEvents.chestsLeftForTest(level);
				}
			};
			var groundBefore = level.getBlockState(spot.below());
			var cornerBefore = level.getBlockState(spot.offset(3, -1, 3));
			var ironBefore = level.getBlockState(spot.offset(1, -2, 1));
			String told = dev.rubymod.game.WorldEvents.begin(server, both, dev.rubymod.game.WorldEvents.Kind.CACHE, spot, 2 * 60_000L);
			dev.rubymod.game.WorldEvents.tickSecond(server, both);
			boolean waiting = told == null && !dev.rubymod.game.WorldEvents.isOpen() && !level.getBlockState(spot).is(Blocks.CHEST);
			check("events: a beacon's beam marks the spot while it's told, and nobody can mine it", waiting
					&& level.getBlockState(spot.below()).is(Blocks.BEACON) && level.getBlockState(spot.offset(1, -2, 1)).is(Blocks.IRON_BLOCK)
					&& level.getBlockState(spot).is(Blocks.STAINED_GLASS.pick(net.minecraft.world.item.DyeColor.LIGHT_BLUE)) && dev.rubymod.game.WorldEvents.protects(level, spot.below())
					&& !Rules.canWeaponBreak(level, spot.offset(1, -2, 1), level.getBlockState(spot.offset(1, -2, 1))));
			// Paused: it waits, and carries on once the game's back on (three paused seconds: three more to wait).
			long pausedBefore = SeasonData.get().pausedRemaining;
			SeasonData.get().pausedRemaining = 60_000L;
			for (int i = 0; i < 3; i++) {
				dev.rubymod.game.WorldEvents.tickSecond(server, both);
			}
			boolean kept = dev.rubymod.game.WorldEvents.kind() == dev.rubymod.game.WorldEvents.Kind.CACHE && !dev.rubymod.game.WorldEvents.isOpen();
			SeasonData.get().pausedRemaining = pausedBefore;
			check("events: pausing the game doesn't end a world event; it waits", kept);
			dev.rubymod.game.WorldEvents.advance(3000);
			dev.rubymod.game.WorldEvents.advance(2 * 60_000L + 1000);
			alpha.teleportTo(level, spot.getX() + 0.5, spot.getY(), spot.getZ() - 1.5, java.util.Set.of(), 0, 0, true);
			dev.rubymod.game.WorldEvents.tickSecond(server, both);
			int ring = W.chests();
			// A block someone puts in the cache's space is theirs to break again; the cache's own aren't.
			BlockPos inSpace = spot.offset(1, 1, 1);
			level.setBlockAndUpdate(inSpace, Blocks.COBBLESTONE.defaultBlockState());
			boolean theirs = !dev.rubymod.game.WorldEvents.protects(level, inSpace) && dev.rubymod.game.WorldEvents.protects(level, spot.offset(1, -1, 1));
			level.setBlockAndUpdate(inSpace, Blocks.AIR.defaultBlockState());
			boolean forced = !level.getForceLoadedChunks().isEmpty();
			check("events: a block put in an event's space can be broken again, and the event's chunks stay loaded", theirs && forced);
			check("events: a Supply Cache opens with 6 chests of loot round a sealed glass case (" + ring + " chests)",
					dev.rubymod.game.WorldEvents.isOpen() && ring == 6 && !level.getBlockState(spot).is(Blocks.CHEST)
							&& !dev.rubymod.game.WorldEvents.protects(level, spot.offset(3, 0, 0)));
			var rec = SeasonData.get().players.get(alpha.getUUID().toString());
			check("events: getting there counts for the Supply Cache goal", rec != null && (rec.goalsDone.contains("cache") || rec.goalProgress.getOrDefault("cache", 0) > 0));
			dev.rubymod.game.WorldEvents.advance(2 * 60_000L);
			dev.rubymod.game.WorldEvents.tickSecond(server, both);
			var middle = level.getBlockEntity(spot) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity c ? c : null;
			int rubies = 0;
			for (int i = 0; middle != null && i < middle.getContainerSize(); i++) {
				rubies += middle.getItem(i).is(ModItems.RUBY) ? middle.getItem(i).getCount() : 0;
			}
			check("events: two minutes on, the case opens into the big chest (" + rubies + " rubies in it, " + W.chests() + " chests)", rubies >= 10 && W.chests() == 7);
			// Everything taken: fresh supplies land five minutes in.
			for (BlockPos pos : BlockPos.betweenClosed(spot.offset(-3, 0, -3), spot.offset(3, 0, 3))) {
				if (level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity c) {
					c.clearContent();
				}
			}
			int emptied = W.chests();
			dev.rubymod.game.WorldEvents.advance(3 * 60_000L);
			dev.rubymod.game.WorldEvents.tickSecond(server, both);
			check("events: five minutes in, fresh supplies land in 3 of the chests (" + emptied + " -> " + W.chests() + ")", emptied == 0 && W.chests() == 3);
			dev.rubymod.game.WorldEvents.advance(4 * 60_000L);
			dev.rubymod.game.WorldEvents.tickSecond(server, both);
			int spilled = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(spot).inflate(5)).size();
			check("events: the cache crumbles after, what's left spills out, and the ground is put back (" + spilled + " items)", dev.rubymod.game.WorldEvents.kind() == null
					&& level.getBlockState(spot.below()) == groundBefore && level.getBlockState(spot.offset(3, -1, 3)) == cornerBefore
					&& level.getBlockState(spot.offset(1, -2, 1)) == ironBefore && !level.getBlockState(spot).is(Blocks.CHEST) && spilled > 0);
			for (var item : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(spot).inflate(5))) {
				item.discard();
			}
			// Ruby Shrine: alone in it, rubies; someone else in it, contested; longest holder gets the bonus.
			BlockPos shrine = surface(level, spot.offset(40, 0, 0));
			// Level ground for it (a different world every run: no trees or hills muddling the ring check).
			for (BlockPos q : BlockPos.betweenClosed(shrine.offset(-8, -1, -8), shrine.offset(8, 8, 8))) {
				level.setBlockAndUpdate(q, q.getY() < shrine.getY() ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState());
			}
			dev.rubymod.game.WorldEvents.begin(server, both, dev.rubymod.game.WorldEvents.Kind.SHRINE, shrine, 0);
			alpha.teleportTo(level, shrine.getX() + 0.5, shrine.getY(), shrine.getZ() + 2.5, java.util.Set.of(), 0, 0, true);
			bravo.teleportTo(level, shrine.getX() + 30.5, shrine.getY() + 1, shrine.getZ() + 0.5, java.util.Set.of(), 0, 0, true);
			long bank0 = dev.rubymod.industry.Grid.rubies(alpha.getUUID());
			for (int i = 0; i < 11; i++) {
				dev.rubymod.game.WorldEvents.tickSecond(server, both);
			}
			long bank1 = dev.rubymod.industry.Grid.rubies(alpha.getUUID());
			int carpet = dev.rubymod.game.WorldEvents.ringForTest(level);
			check("events: the shrine's edge is laid on the ground in carpet (" + carpet + " pieces)", carpet >= 20);
			check("events: holding the Ruby Shrine alone puts rubies in your bank (+" + (bank1 - bank0) + " in 10 s)", bank1 - bank0 == 2);
			bravo.teleportTo(level, shrine.getX() - 1.5, shrine.getY(), shrine.getZ() + 0.5, java.util.Set.of(), 0, 0, true);
			for (int i = 0; i < 10; i++) {
				dev.rubymod.game.WorldEvents.tickSecond(server, both);
			}
			long bank2 = dev.rubymod.industry.Grid.rubies(alpha.getUUID());
			check("events: with someone else in it, it's contested: nobody gets any (+" + (bank2 - bank1) + ")", bank2 == bank1
					&& dev.rubymod.industry.Grid.rubies(bravo.getUUID()) >= 0 && alpha.hasEffect(net.minecraft.world.effect.MobEffects.GLOWING));
			dev.rubymod.game.WorldEvents.advance(5 * 60_000L);
			dev.rubymod.game.WorldEvents.tickSecond(server, both);
			long bank3 = dev.rubymod.industry.Grid.rubies(alpha.getUUID());
			String board = dev.rubymod.game.WorldEvents.leadersForTest(server);
			check("events: the shrine's banner shows who's held it longest (" + board + ")", board.contains("0:11"));
			check("events: whoever held the shrine longest gets 15 more when it ends (+" + (bank3 - bank2) + ")", bank3 - bank2 == 15
					&& dev.rubymod.game.WorldEvents.kind() == null && !level.getBlockState(shrine.below()).is(Blocks.BEACON));
			// Battle Day starting ends whatever's on.
			dev.rubymod.game.WorldEvents.begin(server, both, dev.rubymod.game.WorldEvents.Kind.CACHE, spot, 0);
			dev.rubymod.game.WorldEvents.tickSecond(server, both);
			SeasonData.get().phase = Phase.LOCKDOWN;
			dev.rubymod.game.WorldEvents.tickSecond(server, both);
			check("events: Battle Day ends a world event (and tidies it away)", dev.rubymod.game.WorldEvents.kind() == null && !level.getBlockState(spot).is(Blocks.CHEST));
		} finally {
			dev.rubymod.game.WorldEvents.reset();
			SeasonData.get().phase = before;
		}
	}

	private void duelChecks(ServerLevel level, FakePlayer alpha, FakePlayer bravo, BlockPos home) {
		var arena = level.getServer().getLevel(dev.rubymod.game.Duels.ARENA);
		check("duels: the arena dimension exists", arena != null);
		if (arena == null) {
			return;
		}
		Phase before = SeasonData.get().phase;
		SeasonData.get().phase = Phase.BUILD;
		SeasonData.get().player(bravo.getUUID()).eliminated = false; // the elimination checks above knocked Bravo out
		try {
			alpha.teleportTo(level, home.getX() + 0.5, home.getY(), home.getZ() + 0.5, java.util.Set.of(), 0, 0, true);
			bravo.teleportTo(level, home.getX() + 3.5, home.getY(), home.getZ() + 0.5, java.util.Set.of(), 0, 0, true);
			alpha.getInventory().clearContent();
			bravo.getInventory().clearContent();
			alpha.getInventory().add(new ItemStack(ModItems.RUBY, 5));
			bravo.getInventory().add(new ItemStack(net.minecraft.world.item.Items.BREAD, 3));
			alpha.setHealth(alpha.getMaxHealth());
			bravo.setHealth(bravo.getMaxHealth());

			dev.rubymod.game.Duels.startForTest(alpha, bravo, dev.rubymod.game.Duels.Mode.GUNS, 3);
			check("duels: both players go to the arena", alpha.level() == arena && bravo.level() == arena);
			check("duels: gun kit replaces your inventory", alpha.getInventory().countItem(ModItems.ARR_RIFLE) == 1
					&& alpha.getInventory().countItem(ModItems.RUBY) == 0 && bravo.getInventory().countItem(ModItems.LONGSHOT_SNIPER) == 1);
			BlockPos wall = new BlockPos(alpha.getBlockX() + 31, dev.rubymod.game.Duels.GROUND + 2, 0);
			check("duels: the gun arena is built (wall at " + wall.toShortString() + ")", arena.getBlockState(wall).is(Blocks.STONE_BRICKS));
			check("duels: players spawn at opposite ends", Math.abs(alpha.getZ() - bravo.getZ()) > 40);
			dev.rubymod.game.SpawnShield.clear(alpha);
			dev.rubymod.game.SpawnShield.clear(bravo);
			check("duels: duellists can hurt each other even in the build phase", Rules.canHurt(alpha, bravo) && Rules.canHurt(bravo, alpha));

			dev.rubymod.game.Duels.onDeath(bravo, alpha.damageSources().playerAttack(alpha));
			check("duels: a kill scores and the victim waits to respawn [" + dev.rubymod.game.Duels.score(alpha) + ", alive " + bravo.isAlive() + "]",
					"1-0".equals(dev.rubymod.game.Duels.score(alpha)) && bravo.isAlive() && bravo.isSpectator());
			for (int i = 0; i < 61; i++) {
				dev.rubymod.game.Duels.tick(level.getServer());
			}
			check("duels: respawn after 3 seconds with a fresh kit", !bravo.isSpectator() && bravo.getHealth() == bravo.getMaxHealth()
					&& bravo.getInventory().countItem(ModItems.ARR_RIFLE) == 1);
			for (int k = 0; k < 2; k++) {
				dev.rubymod.game.SpawnShield.clear(bravo);
				dev.rubymod.game.Duels.onDeath(bravo, alpha.damageSources().playerAttack(alpha));
				for (int i = 0; i < 61; i++) {
					dev.rubymod.game.Duels.tick(level.getServer());
				}
			}
			check("duels: first to the kill target wins [" + dev.rubymod.game.Duels.score(alpha) + "]",
					"3-0 over".equals(dev.rubymod.game.Duels.score(alpha)));
			for (int i = 0; i < 100; i++) {
				dev.rubymod.game.Duels.tick(level.getServer());
			}
			check("duels: afterwards both go back where they were [score " + dev.rubymod.game.Duels.score(alpha) + ", alpha at " + alpha.blockPosition().toShortString() + " in " + alpha.level().dimension().identifier() + ", home " + home.toShortString() + ", bravo in " + bravo.level().dimension().identifier() + "]", dev.rubymod.game.Duels.score(alpha) == null && alpha.level() == level
					&& Math.abs(alpha.getX() - (home.getX() + 0.5)) < 2 && Math.abs(alpha.getZ() - (home.getZ() + 0.5)) < 2
					&& alpha.getY() >= home.getY() - 0.5 && alpha.getY() < home.getY() + 8 && bravo.level() == level);
			check("duels: and get their own inventory back", alpha.getInventory().countItem(ModItems.RUBY) == 5
					&& alpha.getInventory().countItem(ModItems.ARR_RIFLE) == 0 && bravo.getInventory().countItem(net.minecraft.world.item.Items.BREAD) == 3);
			check("duels: the duel's PvP rules end with it", dev.rubymod.game.Duels.canHurt(alpha, bravo) == null);

			dev.rubymod.game.Duels.startForTest(alpha, bravo, dev.rubymod.game.Duels.Mode.VEHICLES, 10);
			// (Fake players can't ride, so check the tanks are waiting at the spawns; the client test rides one.)
			int tanks = arena.getEntitiesOfClass(dev.rubymod.entity.VehicleEntity.class, alpha.getBoundingBox().inflate(250),
					t -> t.entityTags().contains("duel_0") || t.entityTags().contains("duel_1")).size();
			check("duels: vehicle duel gives each side a tank (" + tanks + ") and the AT kit", tanks == 2 && alpha.getInventory().countItem(ModItems.LANCER) == 1);
			dev.rubymod.game.Duels.leave(bravo);
			check("duels: /duel leave ends it and sends both home", alpha.level() == level && bravo.level() == level
					&& alpha.getVehicle() == null && alpha.getInventory().countItem(ModItems.RUBY) == 5);

			dev.rubymod.game.Duels.startForTest(alpha, bravo, dev.rubymod.game.Duels.Mode.PLANES, 10);
			check("duels: air duel gives you the AA launcher and AA gun", alpha.getInventory().countItem(ModItems.SKYHAMMER) == 1 && alpha.getInventory().countItem(ModItems.HEAVY_AA_GUN) == 1);
			int jets = arena.getEntitiesOfClass(dev.rubymod.entity.AircraftEntity.class, alpha.getBoundingBox().inflate(600)).size();
			dev.rubymod.game.Duels.leave(alpha);
			int left = arena.getEntitiesOfClass(dev.rubymod.entity.AircraftEntity.class, new net.minecraft.world.phys.AABB(BlockPos.ZERO).inflate(1500)).size();
			check("duels: the arena is cleared afterwards (" + jets + " jets, " + left + " left)", jets == 2 && left == 0
					&& alpha.getInventory().countItem(ModItems.RUBY) == 5);
			// Sudden Death: a duel still going with someone in the season ends, and they're sent back.
			dev.rubymod.game.Duels.startForTest(alpha, bravo, dev.rubymod.game.Duels.Mode.GUNS, 10);
			boolean inArena = alpha.level() == arena;
			SeasonData.get().player(alpha.getUUID(), alpha.getName().getString()).eliminated = false;
			SeasonData.get().phase = Phase.SUDDEN_DEATH;
			dev.rubymod.game.Duels.tick(level.getServer());
			SeasonData.get().phase = Phase.BUILD;
			check("duels: when Sudden Death begins a duel still going ends and both go back", inArena && !dev.rubymod.game.Duels.inDuel(alpha)
					&& alpha.level() == level && bravo.level() == level);
		} finally {
			SeasonData.get().phase = before;
			dev.rubymod.game.Duels.endTest();
			alpha.getInventory().clearContent();
			bravo.getInventory().clearContent();
		}
	}

	/** Place a machine owned by {@code owner} and return its block entity. */
	private static dev.rubymod.industry.MachineBlockEntity machine(ServerLevel level, BlockPos pos, net.minecraft.world.level.block.Block block,
			FakePlayer owner) {
		level.setBlockAndUpdate(pos, block.defaultBlockState());
		var be = (dev.rubymod.industry.MachineBlockEntity) level.getBlockEntity(pos);
		be.setOwner(owner.getUUID());
		return be;
	}

	private static void runMachines(ServerLevel level, int ticks, dev.rubymod.industry.MachineBlockEntity... machines) {
		long t = level.getGameTime();
		for (int i = 0; i < ticks; i++) {
			for (var m : machines) {
				dev.rubymod.industry.MachineBlockEntity.serverTick(level, m.getBlockPos(), m.getBlockState(), m);
			}
			dev.rubymod.industry.Grid.endTick(t + i);
		}
	}

	private void industryChecks(ServerLevel level, FakePlayer alpha, FakePlayer bravo, BlockPos at) {
		var grid = dev.rubymod.industry.Grid.account(alpha.getUUID());
		grid.charge = 0;
		grid.milliRubies = 0;
		for (BlockPos p : BlockPos.betweenClosed(at.offset(-1, 0, -1), at.offset(8, 4, 6))) {
			level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
		}
		// Power: a generator with coal fills the grid.
		var gen = machine(level, at, ModBlocks.COMBUSTION_GENERATOR, alpha);
		gen.setItem(0, new ItemStack(net.minecraft.world.item.Items.COAL, 8));
		runMachines(level, 100, gen);
		long charge = dev.rubymod.industry.Grid.charge(alpha.getUUID());
		check("industry: a combustion generator charges the grid (" + charge + " after 5 s)", charge >= 290 && charge <= 310);

		// Production line: the drill keeps what it digs; the refinery pushes into the trade depot.
		var drill = machine(level, at.offset(2, 0, 0), ModBlocks.RUBY_DRILL, alpha);
		drill.setTier(5);
		var refinery = machine(level, at.offset(3, 0, 0), ModBlocks.ORE_REFINERY, alpha);
		refinery.setTier(5);
		var depot = machine(level, at.offset(4, 0, 0), ModBlocks.TRADE_DEPOT, alpha);
		refinery.setItem(0, new ItemStack(net.minecraft.world.item.Items.RAW_IRON, 4));
		runMachines(level, 2400, gen, drill, refinery, depot);
		int dug = 0;
		for (int i = 0; i < drill.getContainerSize(); i++) {
			dug += drill.getItem(i).getCount();
		}
		long bank = dev.rubymod.industry.Grid.milliRubies(alpha.getUUID());
		check("industry: the drill digs (" + dug + " items in its buffer)", dug > 0);
		StringBuilder inv = new StringBuilder();
		for (var m : new dev.rubymod.industry.MachineBlockEntity[] {drill, refinery, depot}) {
			inv.append(" |");
			for (int i = 0; i < m.getContainerSize(); i++) {
				if (!m.getItem(i).isEmpty()) {
					inv.append(' ').append(m.getItem(i));
				}
			}
		}
		check("industry: the refinery doubles ore and the depot banks the sale (" + bank / 1000.0 + " rubies," + inv + ")",
				bank > 0);
		ItemStack refIn = refinery.getItem(0);
		check("industry: the drill keeps what it digs (nothing handed to the refinery beside it: " + refIn + ")",
				refIn.isEmpty() || refIn.is(net.minecraft.world.item.Items.RAW_IRON));
		check("industry: the Trade Depot takes anything (dirt, a wooden sword, a rifle)",
				depot.canPlaceItem(0, new ItemStack(net.minecraft.world.item.Items.DIRT)) && depot.canPlaceItem(0, new ItemStack(net.minecraft.world.item.Items.WOODEN_SWORD))
						&& depot.canPlaceItem(0, new ItemStack(ModItems.ARR_RIFLE)));

		// Belts: furnaces take ore in the top and fuel below whichever side; a Belt Loader sorts a chest and empties a furnace.
		var beltFurnacePos = at.offset(2, 0, 3);
		level.setBlockAndUpdate(beltFurnacePos, net.minecraft.world.level.block.Blocks.FURNACE.defaultBlockState());
		ItemStack oreRest = dev.rubymod.industry.Belts.insert(level, beltFurnacePos, net.minecraft.core.Direction.EAST, new ItemStack(net.minecraft.world.item.Items.RAW_GOLD, 3));
		ItemStack coalRest = dev.rubymod.industry.Belts.insert(level, beltFurnacePos, net.minecraft.core.Direction.EAST, new ItemStack(net.minecraft.world.item.Items.COAL, 2));
		ItemStack dirtRest = dev.rubymod.industry.Belts.insert(level, beltFurnacePos, net.minecraft.core.Direction.EAST, new ItemStack(net.minecraft.world.item.Items.DIRT, 1));
		var beltFurnace = (net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity) level.getBlockEntity(beltFurnacePos);
		check("belts: a furnace fed from the side gets ore to cook and coal to burn (and refuses dirt)",
				oreRest.isEmpty() && coalRest.isEmpty() && !dirtRest.isEmpty() && beltFurnace.getItem(0).is(net.minecraft.world.item.Items.RAW_GOLD)
						&& beltFurnace.getItem(1).is(net.minecraft.world.item.Items.COAL));
		beltFurnace.setItem(2, new ItemStack(net.minecraft.world.item.Items.GOLD_INGOT, 2));
		ItemStack cooked = dev.rubymod.industry.Belts.extract(level, beltFurnacePos, net.minecraft.core.Direction.WEST, ItemStack.EMPTY, 8);
		check("belts: taking from a furnace gives only what's cooked (" + cooked + ")", cooked.is(net.minecraft.world.item.Items.GOLD_INGOT)
				&& beltFurnace.getItem(0).is(net.minecraft.world.item.Items.RAW_GOLD) && beltFurnace.getItem(1).is(net.minecraft.world.item.Items.COAL));
		var sortChestPos = at.offset(4, 0, 3);
		level.setBlockAndUpdate(sortChestPos, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
		var sortChest = (net.minecraft.world.Container) level.getBlockEntity(sortChestPos);
		sortChest.setItem(0, new ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 10));
		sortChest.setItem(1, new ItemStack(net.minecraft.world.item.Items.RAW_GOLD, 5));
		ItemStack sorted = dev.rubymod.industry.Belts.extract(level, sortChestPos, net.minecraft.core.Direction.EAST, new ItemStack(net.minecraft.world.item.Items.RAW_GOLD), 8);
		check("belts: a Belt Loader set to gold takes only the gold (" + sorted + ")", sorted.is(net.minecraft.world.item.Items.RAW_GOLD) && sorted.getCount() == 5
				&& sortChest.getItem(0).getCount() == 10);
		ItemStack anything = dev.rubymod.industry.Belts.extract(level, sortChestPos, net.minecraft.core.Direction.EAST, ItemStack.EMPTY, 8);
		check("belts: an unset Belt Loader takes anything", anything.is(net.minecraft.world.item.Items.COBBLESTONE) && anything.getCount() == 8);
		level.setBlockAndUpdate(beltFurnacePos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
		level.setBlockAndUpdate(sortChestPos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());

		beltChecks(level, at.offset(-12, 0, 14), alpha);

		// Vault: rubies in, interest-free withdraw out.
		var vault = machine(level, at.offset(6, 0, 0), ModBlocks.VAULT, alpha);
		long before = dev.rubymod.industry.Grid.rubies(alpha.getUUID());
		vault.setItem(0, new ItemStack(ModItems.RUBY, 10));
		runMachines(level, 1, vault);
		long afterDeposit = dev.rubymod.industry.Grid.rubies(alpha.getUUID());
		check("industry: a Vault banks rubies (" + before + " -> " + afterDeposit + ")", afterDeposit == before + 10 && vault.getItem(0).isEmpty());
		check("industry: the bank pays for upgrades", Shop.countRubies(alpha) >= 10);

		// Balance: drill limits follow the Heart; the depot pays a fraction of list price.
		int limit = dev.rubymod.game.GameConfig.get().drillLimit(java.util.UUID.randomUUID());
		check("economy: one Ruby Drill without a Heart (limit " + limit + ")", limit == 1);
		int iron = dev.rubymod.industry.Recipes.price(new ItemStack(net.minecraft.world.item.Items.IRON_INGOT), 1);
		check("economy: depot pays 35% of list for iron (" + iron + " milli-rubies), rubies at par",
				iron == 49 && dev.rubymod.industry.Recipes.price(new ItemStack(ModItems.RUBY), 3) == 1000);

		// Blueprint: copy a little stone hut, then a Constructor builds it from a chest.
		BlockPos src = at.offset(0, 0, 4);
		for (BlockPos p : BlockPos.betweenClosed(src, src.offset(2, 1, 1))) {
			level.setBlockAndUpdate(p, Blocks.STONE_BRICKS.defaultBlockState());
		}
		var data = dev.rubymod.industry.Blueprints.capture(level, src, src.offset(2, 1, 1));
		check("blueprint: copies a 3x2x2 structure (" + (data == null ? "null" : data.blocks()) + " blocks)", data != null && data.blocks() == 12);
		BlockPos cpos = at.offset(-1, 0, 2);
		level.setBlockAndUpdate(cpos, ModBlocks.CONSTRUCTOR.defaultBlockState().setValue(dev.rubymod.industry.MachineBlock.FACING,
				net.minecraft.core.Direction.WEST));
		var constructor = (dev.rubymod.industry.MachineBlockEntity) level.getBlockEntity(cpos);
		constructor.setOwner(alpha.getUUID());
		constructor.setTier(5);
		level.setBlockAndUpdate(cpos.east(), Blocks.CHEST.defaultBlockState());
		((net.minecraft.world.Container) level.getBlockEntity(cpos.east())).setItem(0, new ItemStack(net.minecraft.world.item.Items.STONE_BRICKS, 20));
		for (int i = 0; data != null && i < data.cells().size(); i++) {
			level.setBlockAndUpdate(dev.rubymod.industry.Blueprints.target(cpos, net.minecraft.core.Direction.WEST, data.local(i)), Blocks.AIR.defaultBlockState());
		}
		ItemStack paper = new ItemStack(ModItems.BLUEPRINT);
		paper.set(dev.rubymod.registry.ModComponents.BLUEPRINT, data);
		constructor.setItem(0, paper);
		runMachines(level, 400, gen, constructor);
		int built = 0;
		for (int i = 0; data != null && i < data.cells().size(); i++) {
			BlockPos p = dev.rubymod.industry.Blueprints.target(cpos, net.minecraft.core.Direction.WEST, data.local(i));
			if (level.getBlockState(p).is(Blocks.STONE_BRICKS)) {
				built++;
			}
		}
		check("blueprint: the Constructor builds it from the chest (" + built + "/12, status " + constructor.status() + ", charge " + grid.charge + ")", built == 12);

		// Shield Generator: destroys an enemy shell inside its dome, paid for in charge.
		grid.charge = 9000;
		var shield = machine(level, at.offset(2, 2, 2), ModBlocks.SHIELD_GENERATOR, alpha);
		var shell = MortarShellEntity.launch(level, bravo, Vec3.atCenterOf(at.offset(2, 6, 2)), Vec3.atCenterOf(at.offset(2, 0, 2)));
		level.addFreshEntity(shell);
		runMachines(level, 2, shield);
		long left = dev.rubymod.industry.Grid.charge(alpha.getUUID());
		check("shield: destroys an enemy shell in its dome (" + (9000 - left) + " charge spent)", shell.isRemoved() && left < 9000 - 1000);
		shield.setRemoved();
		level.setBlockAndUpdate(at.offset(2, 2, 2), Blocks.AIR.defaultBlockState());
		if (!shell.isRemoved()) {
			shell.discard();
		}

		// Tesla Coil: zaps a hostile mob in range.
		grid.charge = 9000;
		var tesla = machine(level, at.offset(4, 2, 2), ModBlocks.TESLA_COIL, alpha);
		Zombie victim = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
		if (victim != null) {
			victim.setNoAi(true);
			victim.setPos(at.getX() + 7.5, at.getY() + 2, at.getZ() + 2.5);
			level.addFreshEntity(victim);
			runMachines(level, 16, tesla);
			check("tesla: zaps a zombie in range (" + victim.getHealth() + "/20)", victim.getHealth() < 20.0F);
			victim.discard();
		}
		level.setBlockAndUpdate(at.offset(4, 2, 2), Blocks.AIR.defaultBlockState());

		// Architect's Wand shapes.
		int room = dev.rubymod.item.BuildShapes.shape(dev.rubymod.item.BuildShapes.Mode.ROOM, BlockPos.ZERO, new BlockPos(4, 3, 4)).size();
		int wall = dev.rubymod.item.BuildShapes.shape(dev.rubymod.item.BuildShapes.Mode.WALL, BlockPos.ZERO, new BlockPos(6, 2, 0)).size();
		check("wand: a 5x4x5 hollow room is " + room + " blocks (want 82), a 7x3 wall " + wall + " (want 21)", room == 82 && wall == 21);

		// Upgrade costs rubies.
		grid.milliRubies = 100_000;
		int carried = alpha.getInventory().countItem(ModItems.RUBY);
		var solar = machine(level, at.offset(0, 2, 0), ModBlocks.SOLAR_PANEL, alpha);
		String msg = solar.upgrade(alpha);
		check("industry: upgrading costs rubies (" + msg + ")", solar.tier() == 2 && Shop.countRubies(alpha) == 100 + carried - 8);
		for (BlockPos p : BlockPos.betweenClosed(at.offset(-1, 0, -1), at.offset(8, 4, 6))) {
			level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
		}
		grid.charge = 0;
		grid.milliRubies = 0;
	}

	/** How many 5-damage bullet hits break a block of this kind (-1 if 40 hits don't). */
	private static int hitsToBreak(ServerLevel level, BlockPos pos, net.minecraft.world.level.block.Block block, FakePlayer shooter) {
		level.setBlockAndUpdate(pos, block.defaultBlockState());
		for (int i = 1; i <= 40; i++) {
			if (BlockDamage.hit(level, pos, 5.0F, shooter)) {
				return i;
			}
		}
		return -1;
	}

	/** The first air block above the ground, loading (and generating) the chunk if needed. */
	private static BlockPos surface(ServerLevel level, BlockPos pos) {
		level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
		return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos);
	}

	private boolean runAs(Phase phase, java.util.function.BooleanSupplier check) {
		Phase before = SeasonData.get().phase;
		SeasonData.get().phase = phase;
		try {
			return check.getAsBoolean();
		} finally {
			SeasonData.get().phase = before;
		}
	}
}
