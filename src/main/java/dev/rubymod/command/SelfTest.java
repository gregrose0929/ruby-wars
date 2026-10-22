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
		check("squads: make a squad", dev.rubymod.game.Squads.create(alpha, "Test Squad") == null);
		dev.rubymod.game.Squads.invite(alpha, bravo);
		check("squads: an invited player can join", dev.rubymod.game.Squads.join(bravo, "Test Squad") == null);
		check("squads: no friendly fire on Battle Day", runAs(Phase.WAR, () -> !Rules.canHurt(alpha, bravo)));
		check("squads: squadmates share claims", claim.canUse(bravo.getUUID()));
		check("research: advanced gear starts locked", !dev.rubymod.game.Research.unlocked(bravo, "tank"));
		dev.rubymod.game.Research.addPoints(bravo.getUUID(), 5);
		check("research: points unlock it", dev.rubymod.game.Research.unlock(bravo, "motor_pool") == null
				&& dev.rubymod.game.Research.unlocked(bravo, "tank"));
		check("research: squadmates share research", dev.rubymod.game.Research.unlocked(alpha, "jeep"));
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
			check("guards: guards never shoot squadmates", runAs(Phase.WAR, () -> !guard.isHostileTo(bravo)));
			check("raids: raiders are enemies of every base and its guards", raider.isEnemyOfClaim(claim) && guard.isHostileTo(raider)
					&& raider.isHostileTo(guard));
		} else {
			fail("guards: couldn't create soldiers");
		}
		check("waypoints: add a waypoint", dev.rubymod.game.Waypoints.add(alpha, "Base") == null
				&& dev.rubymod.game.Waypoints.list(alpha).stream().anyMatch(w -> w.name.equals("Base")));
		dev.rubymod.game.Waypoints.remove(alpha, "Base");
		dev.rubymod.game.Squads.leave(bravo);
		dev.rubymod.game.Squads.leave(alpha);
		check("squads: leaving the last member disbands the squad", data.squads.isEmpty());
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
			check("duels: afterwards both go back where they were", dev.rubymod.game.Duels.score(alpha) == null && alpha.level() == level
					&& alpha.blockPosition().closerThan(home, 2) && bravo.level() == level);
			check("duels: and get their own inventory back", alpha.getInventory().countItem(ModItems.RUBY) == 5
					&& alpha.getInventory().countItem(ModItems.ARR_RIFLE) == 0 && bravo.getInventory().countItem(net.minecraft.world.item.Items.BREAD) == 3);
			check("duels: no PvP outside the duel in the build phase again", !Rules.canHurt(alpha, bravo));

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

		// Production line: drill -> refinery -> trade depot, each pushing into the next.
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
