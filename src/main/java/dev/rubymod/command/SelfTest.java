package dev.rubymod.command;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import dev.rubymod.RubyMod;
import dev.rubymod.game.BattleDirector;
import dev.rubymod.game.Bounties;
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

	public static int run(CommandSourceStack source) {
		SelfTest test = new SelfTest();
		String saved = SeasonData.snapshot();
		try {
			test.runAll(source.getLevel());
		} catch (Throwable t) {
			test.fail("crashed: " + t);
			RubyMod.LOGGER.error("Self-test crashed", t);
		} finally {
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
		claim.trusted.add(bravo.getUUID().toString());
		check("protection: trusted player can build", Rules.canBuild(bravo, level, inside));
		claim.trusted.clear();
		data.phase = Phase.WAR;
		check("protection: claims open on Battle Day", Rules.canBuild(bravo, level, inside));
		check("pvp: players can't hurt each other in the build phase", runAs(Phase.BUILD, () -> !Rules.canHurt(alpha, bravo)));
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

		// ----- Shop -----
		alpha.getInventory().clearContent();
		alpha.getInventory().add(new ItemStack(ModItems.RUBY, 10));
		check("shop: buying takes rubies", Shop.takeRubies(alpha, 7) && Shop.countRubies(alpha) == 3);
		check("shop: can't buy without enough rubies", !Shop.takeRubies(alpha, 5) && Shop.countRubies(alpha) == 3);

		// ----- Bounties -----
		data.phase = Phase.BUILD;
		data.buildStartedAt = SeasonTicker.now();
		data.phaseEndsAt = SeasonTicker.now() + 14L * 24 * 3_600_000L;
		var bounties = Bounties.today(alpha);
		check("bounties: three handed out on day 1", bounties.size() == 3);
		Bounties.Bounty kills = new Bounties.Bounty();
		kills.type = Bounties.Type.KILL_MONSTERS;
		kills.target = 2;
		kills.reward = 3;
		bounties.set(0, kills);
		int rubiesBefore = Shop.countRubies(alpha);
		Zombie victim = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
		Bounties.onKill(alpha, victim);
		Bounties.onKill(alpha, victim);
		check("bounties: finishing one pays rubies", kills.done && Shop.countRubies(alpha) == rubiesBefore + 3);

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
			for (int x = 1; x <= 5; x++) {
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
		check("guns: rifle hits a target 5 blocks away and uses a round [" + gunDetail + "]", gunWorks);
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
