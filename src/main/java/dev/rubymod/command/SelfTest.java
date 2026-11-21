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
				z.setPos(range.getX() + 6.5, alpha.getY(), range.getZ() + 0.5);
				level.addFreshEntity(z);
				ItemStack g = new ItemStack(gun);
				g.set(ModComponents.AMMO, 30);
				// Best of three shots (hip fire has a little spread, and can miss outright).
				float dealt = 0;
				for (int shot = 0; shot < 3 && dealt == 0; shot++) {
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
					&& alpha.blockPosition().closerThan(home, 2) && bravo.level() == level);
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
