package dev.rubymod.game;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.rubymod.RubyMod;
import dev.rubymod.entity.BombEntity;
import dev.rubymod.net.Net;
import dev.rubymod.net.Payloads;
import dev.rubymod.registry.ModDamage;
import dev.rubymod.gun.Gunfire;
import dev.rubymod.registry.ModComponents;
import dev.rubymod.registry.ModItems;
import dev.rubymod.gun.GunItem;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/** Runs the season's phases: Battle Day stages, respawns, eliminations, supply drops, and the win. */
public final class BattleDirector {
	private static final List<BlockPos> ACTIVE_DROPS = new ArrayList<>();
	private static long dropsVisibleUntil;

	private BattleDirector() {
	}

	// ----- Phase changes -----

	public static void enterPhase(MinecraftServer server, Phase phase) {
		SeasonData data = SeasonData.get();
		data.phase = phase;
		data.pausedRemaining = -1;
		data.autoPaused = false;
		data.battleReady = false;
		long duration = SeasonTicker.durationOf(phase);
		data.phaseEndsAt = duration > 0 ? SeasonTicker.now() + duration : 0;
		SeasonData.markDirty();
		SeasonTicker.resetNotices();
		RubyMod.LOGGER.info("Ruby Wars phase is now {}", phase);
		ServerLevel overworld = server.overworld();
		GameConfig config = GameConfig.get();

		switch (phase) {
			case BUILD -> {
				data.buildStartedAt = SeasonTicker.now();
				for (ServerPlayer player : server.getPlayerList().getPlayers()) {
					giveHeartIfNeeded(player);
				}
				Alerts.broadcastTitle(server, "Season " + data.season, duration > 0 ? "The build phase has begun" : "Build until Battle Day is called",
						ChatFormatting.RED);
				Alerts.broadcast(server, Component.literal("The season has begun! Place your Ruby Heart to claim land, mine rubies, "
						+ "and get ready for Battle Day.").withStyle(ChatFormatting.GOLD));
				Alerts.news(server, "Season " + data.season + " has begun.");
			}
			case LOCKDOWN -> {
				data.battleStartedAt = SeasonTicker.now();
				// Anyone not here (or with no Heart) sits it out, their base kept safe all day. Back in Lockdown? They join in.
				for (Map.Entry<String, SeasonData.PlayerRecord> entry : new java.util.ArrayList<>(data.players.entrySet())) {
					SeasonData.PlayerRecord r = entry.getValue();
					UUID id = UUID.fromString(entry.getKey());
					SeasonData.Claim claim = data.claimOf(id);
					boolean here = server.getPlayerList().getPlayer(id) != null;
					boolean noHeart = claim == null || claim.destroyed;
					if (!r.eliminated && (!here || noHeart || r.sitOutChoice)) {
						r.satOut = true;
						r.eliminated = true;
						SeasonData.markDirty();
						String why = noHeart ? " has no Heart, so" : r.sitOutChoice ? " chose to" : " isn't on, so";
						Alerts.broadcast(server, Component.literal(r.name + why + " sit" + (r.sitOutChoice && !noHeart ? "" : "s") + " this Battle Day out"
								+ (noHeart ? "." : ": their base is safe all day.")).withStyle(ChatFormatting.GRAY));
						ServerPlayer online = server.getPlayerList().getPlayer(id);
						if (online != null) {
							watchInstead(online);
						}
					}
				}
				SeasonChoices.onBattleDay(server);
				FinalCircle.plan(server);
				for (SeasonData.PlayerRecord record : data.players.values()) {
					record.missilesUsed = 0;
					record.killstreak = 0;
				}
				Alerts.broadcastTitle(server, "BATTLE DAY", "Lockdown: bases safe, Open War in " + config.lockdownMinutes + " min", ChatFormatting.RED);
				Alerts.broadcast(server, Component.literal("Battle Day has begun! Bases stay safe for " + config.lockdownMinutes
						+ " minutes: last touches, then get in position. Then Open War: PvP on, bases open, defenses fight.")
						.withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
				boolean anyTrust = data.claims.values().stream().anyMatch(c -> !c.trusted.isEmpty());
				if (anyTrust) {
					Alerts.broadcast(server, Component.literal("On Battle Day, trust doesn't count: only teammates share a base.")
							.withStyle(ChatFormatting.GRAY));
				}
				playToAll(server, SoundEvents.RAID_HORN.value(), 1.0F);
			}
			case WAR -> {
				data.nextSupplyDropAt = SeasonTicker.now() + config.supplyDropIntervalMinutes * 60_000L;
				Alerts.broadcastTitle(server, "OPEN WAR", "PvP is on. Hearts can be destroyed.", ChatFormatting.DARK_RED);
				playToAll(server, SoundEvents.ENDER_DRAGON_GROWL, 1.0F);
			}
			case SUDDEN_DEATH -> {
				for (SeasonData.Claim claim : data.claims.values()) {
					if (!claim.destroyed && !Rules.sittingOut(claim)) {
						Claims.destroyHeart(overworld, claim, null);
					}
				}
				// Anyone not on now sits the rest out (out of the running so the day can end, but they didn't lose).
				for (Map.Entry<String, SeasonData.PlayerRecord> entry : new java.util.ArrayList<>(data.players.entrySet())) {
					if (data.phase != Phase.SUDDEN_DEATH) {
						break; // that settled it (the last one standing won): nobody else is put out after the win
					}
					if (!entry.getValue().eliminated && server.getPlayerList().getPlayer(UUID.fromString(entry.getKey())) == null) {
						sitOut(server, UUID.fromString(entry.getKey()), entry.getValue(), "wasn't on when Sudden Death began");
					}
				}
				if (data.phase != Phase.SUDDEN_DEATH) {
					return;
				}
				for (Map.Entry<String, SeasonData.PlayerRecord> entry : new java.util.ArrayList<>(data.players.entrySet())) {
					if (!entry.getValue().eliminated) {
						Goals.add(server, UUID.fromString(entry.getKey()), "sudden", 1);
					}
				}
				SpareHearts.suddenDeath(server);
				// The border closes on the final circle (on your map), in three steps.
				FinalCircle.close(server, config.suddenDeathMinutes * 60_000L);
				Alerts.broadcastTitle(server, "SUDDEN DEATH", "No more respawns. The border is closing on the final circle (your map).", ChatFormatting.DARK_RED);
				playToAll(server, SoundEvents.WITHER_SPAWN, 1.0F);
			}
			case FINAL_CIRCLE -> {
				WorldBorder border = overworld.getWorldBorder();
				if (border.getSize() > config.borderEndSize + 1) {
					// Sudden Death cut short (an admin skipped ahead): close the rest of the way over a minute, so nobody
					// outside it is killed by the border on the spot.
					FinalCircle.close(server, 60_000L);
				} else {
					FinalCircle.snap(server);
				}
				Alerts.broadcastTitle(server, "FINAL CIRCLE", "Everyone is glowing. Artillery incoming.", ChatFormatting.GOLD);
				Alerts.broadcast(server, Component.literal("Artillery will shell the circle every 15 seconds. Keep moving!")
						.withStyle(ChatFormatting.GOLD));
			}
			case ENDED -> {
				announceWinner(server);
			}
			case LOBBY -> {
			}
		}
		checkForWinner(server);
		SeasonData.save();
	}

	/** The border at the start of a season: the island's size on the Ruby Wars map, the config's elsewhere. */
	public static int startBorder(MinecraftServer server) {
		return onWarzoneMap(server) ? dev.rubymod.world.WarzoneTerrain.BORDER : GameConfig.get().borderStartSize;
	}

	public static boolean onWarzoneMap(MinecraftServer server) {
		return server.overworld().getChunkSource().getGenerator() instanceof dev.rubymod.world.WarzoneGenerator;
	}

	/** /rubywars start: begins the season from the lobby. */
	public static void startSeason(MinecraftServer server) {
		SeasonData data = SeasonData.get();
		GameConfig config = GameConfig.get();
		WorldBorder border = server.overworld().getWorldBorder();
		border.setCenter(config.borderCenterX, config.borderCenterZ);
		border.setSize(startBorder(server));
		data.winner = "";
		FinalCircle.forget();
		enterPhase(server, Phase.BUILD);
	}

	// ----- Ticking -----

	public static void tickSecond(MinecraftServer server) {
		SeasonData data = SeasonData.get();
		FinalCircle.tick(server);
		if (data.phase == Phase.WAR && data.pausedRemaining >= 0) {
			data.nextSupplyDropAt += 1000; // paused: the next drop waits too, so one doesn't come the moment it's back on
		}
		if (data.phase == Phase.WAR && SeasonTicker.now() >= data.nextSupplyDropAt && data.pausedRemaining < 0) {
			data.nextSupplyDropAt = SeasonTicker.now() + GameConfig.get().supplyDropIntervalMinutes * 60_000L;
			SupplyDrops.incoming(server.overworld());
		}
		SupplyDrops.tick(server);
		// The build phase's world event: a ruby meteor, somewhere nobody has claimed. Worth racing for (and fighting over, come Battle Day).
		if (data.phase == Phase.BUILD && data.pausedRemaining >= 0 && data.nextMeteorAt != 0) {
			data.nextMeteorAt += 1000; // paused: the meteor's clock waits too
		}
		if (data.phase == Phase.BUILD && data.pausedRemaining < 0 && GameConfig.get().meteorIntervalHours > 0) {
			if (data.nextMeteorAt == 0) {
				data.nextMeteorAt = SeasonTicker.now() + 30 * 60_000L;
				SeasonData.markDirty();
			} else if (SeasonTicker.now() >= data.nextMeteorAt && !server.overworld().players().isEmpty()) {
				data.nextMeteorAt = SeasonTicker.now() + GameConfig.get().meteorIntervalHours * 3_600_000L;
				SeasonData.markDirty();
				meteor(server.overworld());
			}
		}
		if (data.phase == Phase.FINAL_CIRCLE) {
			if (server.getTickCount() % 300 < 20) {
				artillery(server.overworld());
			}
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				if (!Rules.isEliminated(player.getUUID()) && !player.isSpectator()) {
					player.addEffect(new MobEffectInstance(MobEffects.GLOWING, 60, 0, false, false));
				}
			}
		}
		if (!ACTIVE_DROPS.isEmpty()) {
			ServerLevel level = server.overworld();
			if (SeasonTicker.now() > dropsVisibleUntil) {
				ACTIVE_DROPS.clear();
			} else {
				for (BlockPos pos : ACTIVE_DROPS) {
					for (int y = 1; y < 40; y += 2) {
						level.sendParticles(ParticleTypes.END_ROD, pos.getX() + 0.5, pos.getY() + y, pos.getZ() + 0.5, 1, 0.05, 0.3, 0.05, 0.0);
					}
				}
			}
		}
		if (data.phase.pvp()) {
			checkForWinner(server);
		}
	}

	// ----- Players -----

	public static void giveHeartIfNeeded(ServerPlayer player) {
		SeasonData data = SeasonData.get();
		SeasonData.PlayerRecord record = data.player(player.getUUID(), player.getName().getString());
		// Off in the hub or Horde: you get it when you go into Ruby Wars.
		if (!record.heartGiven && !data.phase.isBattle() && data.phase != Phase.ENDED && Hub.inRubyWarsWorld(player)) {
			record.heartGiven = true;
			SeasonData.markDirty();
			ItemStack heart = Claims.heartItem();
			if (!player.getInventory().add(heart)) {
				player.drop(heart, false, net.minecraft.util.Prediction.SERVER_ONLY);
			}
			ItemStack pistol = new ItemStack(ModItems.SERVICE_PISTOL);
			pistol.set(ModComponents.AMMO, ((GunItem) ModItems.SERVICE_PISTOL).stats().magazine());
			player.getInventory().add(pistol);
			player.getInventory().add(new ItemStack(ModItems.LIGHT_AMMO, 24));
			player.sendSystemMessage(Component.literal("You got your Ruby Heart and a sidearm! Place the Heart where you want your base.")
					.withStyle(ChatFormatting.GREEN));
			// The season's start for them: their saved Blueprints come back (when this is a fresh season's start).
			if (data.freshSeason == data.season) {
				dev.rubymod.industry.BlueprintLibrary.giveBack(player);
			}
		}
	}

	public static void onJoin(ServerPlayer player) {
		FreshStart.check(player);
		Goals.syncAdvancements(player);
		SeasonData data = SeasonData.get();
		data.player(player.getUUID(), player.getName().getString());
		if (data.phase != Phase.LOBBY) {
			giveHeartIfNeeded(player);
		}
		Research.sync(player);
		SeasonData.PlayerRecord me = data.player(player.getUUID());
		SeasonData.Claim mine = data.claimOf(player.getUUID());
		if (data.phase == Phase.LOCKDOWN && me != null && me.satOut && !me.sitOutChoice && mine != null && !mine.destroyed) {
			me.satOut = false;
			me.eliminated = false;
			me.finished = 0;
			SeasonData.markDirty();
			player.setGameMode(GameType.SURVIVAL);
			Alerts.broadcast(player.level().getServer(), Component.literal(me.name + " made it in time and joins Battle Day!").withStyle(ChatFormatting.GOLD));
			SeasonChoices.onBattleDay(player.level().getServer()); // their team (if they picked one) has them back
		}
		if (data.phase == Phase.BUILD && data.battleReady) {
			player.sendSystemMessage(SeasonTicker.readyMessage(player.level().getServer()));
		}
		SeasonChoices.sync(player);
		if (Rules.isEliminated(player.getUUID()) && data.phase.isBattle()) {
			player.setGameMode(GameType.SPECTATOR);
			SeasonData.PlayerRecord out = data.player(player.getUUID());
			player.sendSystemMessage(Component.literal(out != null && out.satOut
					? "You're sitting this Battle Day out (your base is safe). You can watch as a spectator."
					: "You've been eliminated this season. You can watch as a spectator.").withStyle(ChatFormatting.GRAY));
		}
	}

	public static void onLeave(MinecraftServer server, ServerPlayer player) {
		Gunfire.forget(player.getUUID());
		dev.rubymod.gun.LagCompensation.forget(player.getUUID());
		SeasonData data = SeasonData.get();
		// A server shutdown disconnects everyone; that isn't anyone leaving.
		if (server.isRunning() && (data.phase == Phase.SUDDEN_DEATH || data.phase == Phase.FINAL_CIRCLE)) {
			SeasonData.PlayerRecord record = data.player(player.getUUID());
			if (record != null && !record.eliminated) {
				sitOut(server, player.getUUID(), record, "had to go during " + data.phase.title());
			}
		}
	}

	public static void onDeath(ServerPlayer victim, DamageSource source) {
		MinecraftServer server = victim.level().getServer();
		SeasonData data = SeasonData.get();
		SeasonData.PlayerRecord record = data.player(victim.getUUID(), victim.getName().getString());
		record.deaths++;
		record.killstreakBefore = record.killstreak;
		record.killstreak = 0;
		SeasonData.markDirty();

		if (source.getEntity() instanceof ServerPlayer killer && killer != victim) {
			broadcastKill(server, killer, victim, source);
		}
		if (source.getEntity() instanceof ServerPlayer killer && killer != victim && data.phase.pvp()) {
			SeasonData.PlayerRecord k = data.player(killer.getUUID(), killer.getName().getString());
			k.kills++;
			Goals.add(server, killer.getUUID(), "knockout", 1);
			k.killstreak++;
			k.bestKillstreak = Math.max(k.bestKillstreak, k.killstreak);
			String streak = switch (k.killstreak) {
				case 3 -> "TRIPLE KILL";
				case 5 -> "RAMPAGE";
				case 7 -> "UNSTOPPABLE";
				case 10 -> "LEGENDARY";
				default -> null;
			};
			if (streak != null && k.killstreak != GameConfig.get().killstreakForAirStrike) {
				Alerts.title(killer, streak, k.killstreak + " kills without dying", ChatFormatting.RED);
				killer.level().playSound(null, killer.blockPosition(), SoundEvents.RAID_HORN.value(), net.minecraft.sounds.SoundSource.PLAYERS, 0.4F, 1.6F);
				if (k.killstreak >= 5) {
					Alerts.broadcast(server, Component.literal(killer.getName().getString() + " is on a " + k.killstreak + "-kill streak (" + streak + ")!")
							.withStyle(ChatFormatting.RED));
				}
			}
			// Ending someone's streak is worth calling out.
			if (record.killstreakBefore >= 3) {
				Alerts.broadcast(server, Component.literal(killer.getName().getString() + " ended " + record.name + "'s " + record.killstreakBefore
						+ "-kill streak.").withStyle(ChatFormatting.GOLD));
			}
			if (k.killstreak == GameConfig.get().killstreakForAirStrike) {
				killer.getInventory().add(new ItemStack(ModItems.AIR_STRIKE_FLARE));
				Alerts.title(killer, k.killstreak + " KILL STREAK", "Air Strike Flare unlocked!", ChatFormatting.GOLD);
			}
		}

		if (!data.phase.isBattle()) {
			return;
		}
		boolean canRespawn = data.phase.heartsGiveRespawns()
				&& (respawnHeart(victim.getUUID()) != null || SpareHearts.canRespawn(server, victim.getUUID()));
		if (!canRespawn) {
			eliminate(server, victim.getUUID(), record, null);
		}
	}

	public static void onRespawn(ServerPlayer player) {
		if (Rules.isEliminated(player.getUUID())) {
			player.setGameMode(GameType.SPECTATOR);
			return;
		}
		SeasonData data = SeasonData.get();
		if (!data.phase.isBattle()) {
			return;
		}
		SeasonData.Claim claim = respawnHeart(player.getUUID());
		if (claim == null && SpareHearts.respawn(player)) {
			player.sendSystemMessage(Component.literal("Back at your Spare Heart.").withStyle(ChatFormatting.GREEN), true);
			return;
		}
		if (claim != null) {
			ServerLevel level = player.level().getServer().overworld();
			BlockPos spot = safeSpotNear(level, claim.pos());
			player.teleportTo(level, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, Set.<Relative>of(), player.getYRot(), player.getXRot(), true);
			SpawnShield.grant(player);
			player.sendSystemMessage(Component.literal("Respawned at your Ruby Heart. Spawn protection for "
					+ SpawnShield.TICKS / 20 + "s (ends if you shoot).").withStyle(ChatFormatting.GREEN), true);
		}
	}

	/** Your own standing Heart, or else a squadmate's. */
	public static SeasonData.Claim respawnHeart(UUID player) {
		SeasonData data = SeasonData.get();
		SeasonData.Claim own = data.claimOf(player);
		if (own != null && !own.destroyed) {
			return own;
		}
		for (UUID mate : Squads.mates(player)) {
			SeasonData.Claim claim = data.claimOf(mate);
			if (claim != null && !claim.destroyed) {
				return claim;
			}
		}
		return null;
	}

	public static BlockPos safeSpotNear(ServerLevel level, BlockPos heart) {
		for (int dy = 1; dy < 6; dy++) {
			BlockPos p = heart.above(dy);
			if (level.getBlockState(p).isAir() && level.getBlockState(p.above()).isAir()) {
				return p;
			}
		}
		for (BlockPos p : BlockPos.betweenClosed(heart.offset(-3, -1, -3), heart.offset(3, 3, 3))) {
			if (level.getBlockState(p).isAir() && level.getBlockState(p.above()).isAir() && !level.getBlockState(p.below()).isAir()) {
				return p.immutable();
			}
		}
		return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, heart);
	}

	/** Sitting Battle Day out while on: watch as a spectator (or go and play in the hub). */
	private static void watchInstead(ServerPlayer player) {
		if (Hub.inRubyWarsWorld(player)) {
			player.setGameMode(GameType.SPECTATOR);
		}
		player.sendSystemMessage(Component.literal("You're sitting this Battle Day out (your base is safe). Watch, or press H for the hub and party games.")
				.withStyle(ChatFormatting.GRAY));
	}

	/** Out of the running without losing: they weren't there (bedtime, dinner). The wrap-up says they sat this one out. */
	public static void sitOut(MinecraftServer server, UUID id, SeasonData.PlayerRecord record, String why) {
		if (record.eliminated || SeasonData.get().phase == Phase.ENDED) {
			return;
		}
		record.satOut = true;
		record.eliminated = true;
		SeasonData.markDirty();
		int left = aliveCount();
		record.finished = left + 1;
		Alerts.broadcast(server, Component.literal(record.name + " sits the rest of Battle Day out (" + why + "). " + left
				+ (left == 1 ? " player is still in." : " players are still in.")).withStyle(ChatFormatting.GRAY));
		checkForWinner(server);
	}

	public static void eliminate(MinecraftServer server, UUID id, SeasonData.PlayerRecord record, String reason) {
		if (record.eliminated || SeasonData.get().phase == Phase.ENDED) {
			return;
		}
		record.eliminated = true;
		SeasonData.markDirty();
		int left = aliveCount();
		record.finished = left + 1;
		String why = reason == null ? "" : " (" + reason + ")";
		Alerts.broadcast(server, Component.literal(record.name + " has been eliminated" + why + "! " + left + (left == 1 ? " player remains." : " players remain."))
				.withStyle(ChatFormatting.RED));
		ServerPlayer player = server.getPlayerList().getPlayer(id);
		if (player != null && player.isAlive()) {
			player.setGameMode(GameType.SPECTATOR);
		}
		checkForWinner(server);
	}

	public static int aliveCount() {
		int alive = 0;
		for (SeasonData.PlayerRecord record : SeasonData.get().players.values()) {
			if (!record.eliminated) {
				alive++;
			}
		}
		return alive;
	}

	private static void checkForWinner(MinecraftServer server) {
		SeasonData data = SeasonData.get();
		if (!data.phase.pvp()) {
			return;
		}
		if (data.players.size() < 2) {
			// A one-player season (testing, or everyone else left) ends once nobody's left standing.
			if (aliveCount() == 0) {
				data.winner = "";
				enterPhase(server, Phase.ENDED);
			}
			return;
		}
		// One squad (or one solo player) left standing wins.
		java.util.Set<String> sides = Squads.aliveSides();
		if (sides.size() <= 1) {
			data.winner = sides.isEmpty() ? "" : sides.iterator().next();
			enterPhase(server, Phase.ENDED);
		}
	}

	/** Did anybody actually fight this Battle Day (a knockdown, a Heart broken by someone, or someone knocked out, not just away)? */
	public static boolean anyoneFought() {
		for (SeasonData.PlayerRecord r : SeasonData.get().players.values()) {
			if (r.kills > 0 || r.heartsDestroyed > 0 || r.eliminated && !r.satOut) {
				return true;
			}
		}
		return false;
	}

	/** On the side that won: the last one standing, or anyone on the winning team. */
	public static boolean onWinningSide(UUID id) {
		SeasonData data = SeasonData.get();
		SeasonData.PlayerRecord r = data.player(id);
		if (r == null || data.winner.isEmpty()) {
			return false;
		}
		Squads.Squad squad = Squads.of(id);
		return squad != null ? squad.name.equals(data.winner) : !r.eliminated && r.name.equals(data.winner);
	}

	/** " (Ann, Ben)" when the winner is a team, else "". */
	private static String teamList(String winner) {
		for (Squads.Squad squad : SeasonData.get().squads.values()) {
			if (squad.name.equals(winner)) {
				List<String> names = new ArrayList<>();
				for (String id : squad.members) {
					SeasonData.PlayerRecord r = SeasonData.get().players.get(id);
					if (r != null) {
						names.add(r.name);
					}
				}
				return " (" + String.join(", ", names) + ")";
			}
		}
		return "";
	}

	private static void announceWinner(MinecraftServer server) {
		SeasonData data = SeasonData.get();
		if (!anyoneFought()) {
			// Nobody fought: nobody's crowned (and nobody "lost").
			data.winner = "";
			countSeason(server);
			Alerts.broadcastTitle(server, "NO CONTEST", "Nobody fought this Battle Day", ChatFormatting.GRAY);
			Alerts.broadcast(server, Component.literal("Season " + data.season + " ends with no contest: nobody fought, so nobody's crowned. "
					+ "Try again next season!").withStyle(ChatFormatting.GRAY));
			Alerts.news(server, "Season " + data.season + " ended with no contest.");
			sendRecapToAll(server);
			return;
		}
		countSeason(server);
		if (data.winner.isEmpty()) {
			Alerts.broadcastTitle(server, "NO SURVIVORS", "Season " + data.season + " ends in a draw", ChatFormatting.GRAY);
			Alerts.news(server, "Season " + data.season + " ended in a draw.");
			sendRecapToAll(server);
			return;
		}
		data.pastChampions.add("Season " + data.season + ": " + data.winner + teamList(data.winner));
		// A team wins together: everyone on it, standing or not.
		for (Map.Entry<String, SeasonData.PlayerRecord> e : data.players.entrySet()) {
			if (onWinningSide(UUID.fromString(e.getKey()))) {
				Stats.seasonWon(UUID.fromString(e.getKey()), e.getValue().name);
			}
		}
		Alerts.broadcastTitle(server, data.winner.toUpperCase() + (data.winner.endsWith(" team") ? " WIN" : " WINS"), "Champion of Season " + data.season,
				ChatFormatting.GOLD);
		Alerts.news(server, data.winner + " won Season " + data.season + "!");
		playToAll(server, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0F);
		Alerts.broadcast(server, Component.literal("=== Season " + data.season + " results ===").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
		Alerts.broadcast(server, Component.literal("Champion: " + data.winner + teamList(data.winner)).withStyle(ChatFormatting.GOLD));
		data.players.values().stream()
				.sorted((a, b) -> Integer.compare(b.kills, a.kills))
				.limit(5)
				.forEach(r -> Alerts.broadcast(server, Component.literal(r.name + ": " + r.kills + " kills, " + r.heartsDestroyed
						+ " Hearts destroyed, best streak " + r.bestKillstreak).withStyle(ChatFormatting.YELLOW)));
		SeasonData.save();
		sendRecapToAll(server);
	}

	// ----- The season wrap-up card -----

	private static void sendRecapToAll(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (SeasonData.get().players.containsKey(player.getUUID().toString())) {
				sendRecap(player);
			}
		}
		Alerts.broadcast(server, Component.literal("See the season's wrap-up again any time: /rubywars recap").withStyle(ChatFormatting.GRAY));
	}

	/** The wrap-up card: the winner, a few friendly awards, and how your own season went. */
	public static String sendRecap(ServerPlayer player) {
		SeasonData data = SeasonData.get();
		if (data.phase != Phase.ENDED || data.players.isEmpty()) {
			return "The wrap-up shows once a season is over.";
		}
		List<String> awards = awards(data);

		List<String> mine = new ArrayList<>();
		SeasonData.PlayerRecord me = data.players.get(player.getUUID().toString());
		if (me != null) {
			int count = data.players.size();
			String place = !anyoneFought() ? "No contest" : me.satOut ? "Sat this one out"
					: !me.eliminated || onWinningSide(player.getUUID()) ? (data.winner.isEmpty() ? "Still standing" : "Winner!")
					: ordinal(me.finished) + " of " + count;
			mine.add("Finished|" + place);
			mine.add("Knockdowns|" + me.kills);
			mine.add("Times knocked down|" + me.deaths);
			mine.add("Hearts destroyed|" + me.heartsDestroyed);
			mine.add("Best streak|" + me.bestKillstreak);
			mine.add("Goals done|" + me.goalsDone.size());
			mine.add("Rubies in the bank|" + String.format(java.util.Locale.ROOT, "%,d", dev.rubymod.industry.Grid.milliRubies(player.getUUID()) / 1000));
			SeasonData.Claim claim = data.claimOf(player.getUUID());
			mine.add("Your Heart|" + (claim == null ? "Never placed" : "Tier " + claim.tier + (claim.destroyed ? " (destroyed)" : " (standing)")));
			// How your base held up on Battle Day.
			String stood = BaseReport.heartStood(player.getUUID());
			if (!stood.isEmpty()) {
				// (Lines starting "@" go on the wrap-up's "Your base" page.)
				mine.add("@Your Heart on Battle Day|" + stood);
				mine.add("@Attacks stopped|" + DefenseCredit.stopped(me));
				String by = DefenseCredit.stoppedBy(me);
				if (!by.isEmpty()) {
					mine.add("@What stopped them|" + by);
				}
				mine.add("@Turret hits|" + me.defenseHits);
				mine.add("@Base blocks blown up|" + String.format(java.util.Locale.ROOT, "%,d", me.blocksLost));
			}
			int battleGoals = 0;
			for (String g : List.of("knockout", "heartkill", "sudden")) {
				if (me.goalsDone.contains(g)) {
					battleGoals++;
				}
			}
			mine.add("@Battle Day goals|" + battleGoals + " of 3");
			if (!me.theme.isEmpty()) {
				mine.add("@Famous for|" + SeasonChoices.themeName(me.theme).replace("Its ", "its "));
			}
		}
		Net.send(player, new dev.rubymod.net.MorePayloads.SeasonRecap("Season " + data.season + (anyoneFought() ? " is over!" : ": no contest"),
				data.winner, awards, mine));
		return null;
	}

	/**
	 * Once a season ends: everyone who took part gets a season played (a veteran star), award winners an award, and
	 * any cosmetics that unlocks.
	 */
	private static void countSeason(MinecraftServer server) {
		SeasonData data = SeasonData.get();
		if (data.seasonCounted) {
			return;
		}
		data.seasonCounted = true;
		SeasonData.markDirty();
		for (Map.Entry<String, SeasonData.PlayerRecord> e : data.players.entrySet()) {
			SeasonData.PlayerRecord r = e.getValue();
			UUID id = UUID.fromString(e.getKey());
			if (r.heartGiven || r.starterBase || data.claimOf(id) != null) {
				Stats.get(id, r.name).seasonsPlayed++;
			}
		}
		for (String award : awards(data)) {
			String who = award.split("[|]")[1];
			for (Map.Entry<String, SeasonData.PlayerRecord> e : data.players.entrySet()) {
				if (e.getValue().name.equals(who)) {
					Stats.get(UUID.fromString(e.getKey()), who).awardsWon++;
					break;
				}
			}
		}
		Stats.markDirty();
		Stats.save();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			Cosmetics.checkUnlocks(p);
		}
		Veterans.syncAll(server);
	}

	/** The wrap-up card's awards: "title|who|detail|item id" each. */
	/** For the self-test: the wrap-up's awards as they'd be now. */
	public static List<String> awardsNow() {
		return awards(SeasonData.get());
	}

	private static List<String> awards(SeasonData data) {
		List<String> awards = new ArrayList<>();
		award(awards, "Top fighter", data, r -> r.kills, n -> n + (n == 1 ? " knockdown" : " knockdowns"), "minecraft:iron_sword");
		award(awards, "Heart breaker", data, r -> r.heartsDestroyed, n -> n + (n == 1 ? " Heart" : " Hearts"), "minecraft:tnt");
		award(awards, "Best streak", data, r -> r.bestKillstreak >= 2 ? r.bestKillstreak : 0, n -> n + " in a row", "minecraft:blaze_powder");
		award(awards, "Star collector", data, r -> r.goalsDone.size(), n -> n + (n == 1 ? " goal done" : " goals done"), "minecraft:nether_star");
		// Richest bank and the biggest Heart.
		String rich = null;
		long richest = 0;
		String big = null;
		int bigTier = 0;
		for (Map.Entry<String, SeasonData.PlayerRecord> e : data.players.entrySet()) {
			UUID id = UUID.fromString(e.getKey());
			long bank = dev.rubymod.industry.Grid.milliRubies(id) / 1000;
			if (bank > richest) {
				richest = bank;
				rich = e.getValue().name;
			}
			SeasonData.Claim claim = data.claimOf(id);
			if (claim != null && claim.tier > bigTier) {
				bigTier = claim.tier;
				big = e.getValue().name;
			}
		}
		if (rich != null) {
			awards.add("Richest base|" + rich + "|" + String.format(java.util.Locale.ROOT, "%,d", richest) + " rubies|rubymod:ruby");
		}
		if (big != null && bigTier > 1) {
			awards.add("Biggest Heart|" + big + "|Tier " + bigTier + "|minecraft:beacon");
		}
		data.players.values().stream().filter(r -> r.finished == 2 && !r.satOut).findFirst()
				.ifPresent(r -> awards.add("Runner-up|" + r.name + "|So close!|minecraft:iron_ingot"));
		// The build side: the busiest machines, and the toughest defenses.
		award(awards, "Busiest factory", data, r -> (int) Math.min(Integer.MAX_VALUE, BaseReport.made(r)),
				n -> String.format(java.util.Locale.ROOT, "%,d", n) + " things made", "rubymod:ruby_drill");
		// Attacks stopped first, then turret hits.
		award(awards, "Toughest defenses", data, r -> r.attacksStopped * 1000 + Math.min(999, r.defenseHits), n -> {
			int stopped = n / 1000;
			int hits = n % 1000;
			String h = hits + (hits == 1 ? " turret hit" : " turret hits");
			return stopped == 0 ? h : stopped + (stopped == 1 ? " attack stopped" : " attacks stopped") + (hits > 0 ? ", " + h : "");
		}, "rubymod:sentry_turret");
		// What each base was famous for (their own pick: recognition, not a contest).
		for (SeasonData.PlayerRecord r : data.players.values()) {
			if (!r.theme.isEmpty()) {
				String item = switch (r.theme) {
					case "walls" -> "rubymod:reinforced_concrete";
					case "factory" -> "rubymod:assembler";
					case "garage" -> "rubymod:jeep";
					default -> "rubymod:fighter_jet";
				};
				awards.add("Famous for " + SeasonChoices.themeName(r.theme).replace("Its ", "its ") + "|" + r.name + "|Their own pick|" + item);
			}
		}
		// Everyone gets at least one.
		for (SeasonData.PlayerRecord r : data.players.values()) {
			boolean has = false;
			for (String a : awards) {
				if (a.split("[|]")[1].equals(r.name)) {
					has = true;
					break;
				}
			}
			if (has) {
				continue;
			}
			if (r.research.size() > 0) {
				awards.add("Researcher|" + r.name + "|" + r.research.size() + (r.research.size() == 1 ? " upgrade" : " upgrades") + "|minecraft:enchanted_book");
			} else if (!r.goalsDone.isEmpty()) {
				awards.add("Busy builder|" + r.name + "|" + r.goalsDone.size() + (r.goalsDone.size() == 1 ? " goal done" : " goals done") + "|minecraft:bricks");
			} else {
				awards.add("Good sport|" + r.name + "|Played Season " + data.season + "|minecraft:emerald");
			}
		}
		return awards;
	}

	private static void award(List<String> out, String title, SeasonData data, java.util.function.ToIntFunction<SeasonData.PlayerRecord> score,
			java.util.function.IntFunction<String> detail, String item) {
		SeasonData.PlayerRecord best = null;
		for (SeasonData.PlayerRecord r : data.players.values()) {
			if (score.applyAsInt(r) > 0 && (best == null || score.applyAsInt(r) > score.applyAsInt(best))) {
				best = r;
			}
		}
		if (best != null) {
			out.add(title + "|" + best.name + "|" + detail.apply(score.applyAsInt(best)) + "|" + item);
		}
	}

	private static String ordinal(int n) {
		int t = n % 100;
		String suffix = t >= 11 && t <= 13 ? "th" : n % 10 == 1 ? "st" : n % 10 == 2 ? "nd" : n % 10 == 3 ? "rd" : "th";
		return n + suffix;
	}

	// ----- Watching when you're out -----

	/** Knocked-out players: watch the next (dir 1) or previous (dir -1) player still in, or fly free (dir 0). */
	public static String watch(ServerPlayer player, int dir) {
		if (!Rules.isEliminated(player.getUUID()) || !player.isSpectator() || !Hub.inRubyWarsWorld(player)) {
			return "Only knocked-out players can watch the battle.";
		}
		if (dir == 0) {
			player.setCamera(player);
			player.sendOverlayMessage(Component.literal("Flying free. Click to watch a player.").withStyle(ChatFormatting.GRAY));
			return null;
		}
		List<ServerPlayer> alive = new ArrayList<>();
		for (ServerPlayer p : player.level().getServer().getPlayerList().getPlayers()) {
			if (p != player && !Rules.isEliminated(p.getUUID()) && !p.isSpectator() && Hub.inRubyWarsWorld(p)) {
				alive.add(p);
			}
		}
		if (alive.isEmpty()) {
			return "Nobody left out there to watch.";
		}
		alive.sort(java.util.Comparator.comparing(p -> p.getName().getString().toLowerCase()));
		int at = alive.indexOf(player.getCamera());
		int next = at < 0 ? (dir > 0 ? 0 : alive.size() - 1) : Math.floorMod(at + dir, alive.size());
		ServerPlayer target = alive.get(next);
		if (player.level() != target.level() || player.distanceTo(target) > 64) {
			player.teleportTo((ServerLevel) target.level(), target.getX(), target.getY() + 2, target.getZ(), Set.<Relative>of(),
					target.getYRot(), target.getXRot(), true);
		}
		player.setCamera(target);
		player.sendOverlayMessage(Component.literal("Watching " + target.getName().getString() + " (" + (next + 1) + " of " + alive.size()
				+ ")  -  click: next  -  right-click: back  -  crouch: fly free").withStyle(ChatFormatting.AQUA));
		return null;
	}

	/** What a kill was made with, for the kill feed, the death card and duel stats. */
	public static String weaponName(net.minecraft.world.entity.Entity killer, DamageSource source) {
		if (source.is(ModDamage.TURRET)) {
			return "Sentry Turret";
		}
		if (source.getDirectEntity() instanceof Projectile projectile) {
			return projectile.getName().getString();
		}
		if (source.getDirectEntity() instanceof dev.rubymod.entity.AircraftEntity aircraft && source.getDirectEntity() != killer) {
			return "the " + aircraft.getName().getString() + " exploding";
		}
		if (killer instanceof net.minecraft.world.entity.LivingEntity living) {
			if (living.getVehicle() != null && (source.getDirectEntity() == null || source.getDirectEntity() == killer)) {
				return living.getVehicle().getName().getString();
			}
			ItemStack held = living.getMainHandItem();
			return held.isEmpty() ? "Fists" : held.getHoverName().getString();
		}
		return killer == null ? "" : killer.getName().getString();
	}

	/** Tell a player who just died who got them (the death card and killcam). */
	public static void sendDeathInfo(ServerPlayer victim, DamageSource source) {
		net.minecraft.world.entity.Entity killer = source.getEntity();
		if (killer == null || killer == victim) {
			Net.send(victim, new dev.rubymod.net.MorePayloads.DeathInfo("", source.getLocalizedDeathMessage(victim).getString(), 0, -1, false));
			return;
		}
		Net.send(victim, new dev.rubymod.net.MorePayloads.DeathInfo(killer.getDisplayName().getString(), weaponName(killer, source),
				Math.round(killer.distanceTo(victim)), killer.level() == victim.level() ? killer.getId() : -1, source.is(ModDamage.HEADSHOT)));
	}

	private static void broadcastKill(MinecraftServer server, ServerPlayer killer, ServerPlayer victim, DamageSource source) {
		String weapon = weaponName(killer, source);
		boolean headshot = source.is(ModDamage.HEADSHOT);
		Payloads.KillFeed payload = new Payloads.KillFeed(killer.getName().getString(), victim.getName().getString(), weapon, headshot);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Net.send(player, payload);
		}
	}

	/** Final Circle: artillery shells the circle every 15 seconds so nobody can turtle forever. */
	private static void artillery(ServerLevel level) {
		WorldBorder border = level.getWorldBorder();
		RandomSource random = level.getRandom();
		double half = border.getSize() / 2 - 4;
		for (int i = 0; i < 4; i++) {
			int x = (int) (border.getCenterX() + (random.nextDouble() * 2 - 1) * half);
			int z = (int) (border.getCenterZ() + (random.nextDouble() * 2 - 1) * half);
			level.getChunk(x >> 4, z >> 4);
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 50;
			level.addFreshEntity(BombEntity.drop(level, null, new Vec3(x + 0.5, y, z + 0.5)));
		}
		playToAll(level.getServer(), SoundEvents.FIREWORK_ROCKET_LARGE_BLAST_FAR, 0.5F);
	}

	/** A sound for everyone in Ruby Wars (not the hub or the other games). */
	private static void playToAll(MinecraftServer server, net.minecraft.sounds.SoundEvent sound, float pitch) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!Hub.inRubyWarsWorld(player)) {
				continue;
			}
			player.level().playSound(null, player.getX(), player.getY(), player.getZ(), sound, SoundSource.MASTER, 1.0F, pitch);
		}
	}

	// ----- The ruby meteor -----

	/** For tests and admins: bring one down now. Returns where (null if nowhere was free). */
	public static BlockPos meteor(ServerLevel level) {
		RandomSource random = level.getRandom();
		WorldBorder border = level.getWorldBorder();
		double half = Math.min(border.getSize() * 0.4, 1500);
		SeasonData data = SeasonData.get();
		BlockPos top = null;
		for (int tries = 0; tries < 20 && top == null; tries++) {
			int x = (int) (border.getCenterX() + (random.nextDouble() * 2 - 1) * half);
			int z = (int) (border.getCenterZ() + (random.nextDouble() * 2 - 1) * half);
			boolean free = true;
			for (SeasonData.Claim claim : data.claims.values()) {
				if (!claim.destroyed && Math.abs(claim.x - x) < claim.radius() + 80 && Math.abs(claim.z - z) < claim.radius() + 80) {
					free = false;
				}
			}
			if (!free) {
				continue;
			}
			level.getChunk(x >> 4, z >> 4);
			BlockPos p = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
			// Dry land, not water (and not the empty edge of a test world).
			if (level.getFluidState(p.below()).isEmpty() && p.getY() > level.getMinY() && !level.getBlockState(p.below()).isAir()) {
				top = p;
			}
		}
		return top == null ? null : meteorAt(level, top);
	}

	/** A ruby meteor comes down at {@code top} (the ground there). Returns the crater's middle. */
	public static BlockPos meteorAt(ServerLevel level, BlockPos top) {
		RandomSource random = level.getRandom();
		// A crater, scorched round the rim, with the meteor in the bottom: ruby blocks and ore in a shell of magma.
		BlockPos c = top.below(2);
		for (BlockPos p : BlockPos.betweenClosed(c.offset(-6, -3, -6), c.offset(6, 6, 6))) {
			double d = Math.sqrt(p.distSqr(c));
			if (level.getBlockState(p).getDestroySpeed(level, p) < 0) {
				continue;
			}
			if (d < 4.6 && p.getY() >= c.getY() - 1) {
				level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
			} else if (d < 5.8 && !level.getBlockState(p).isAir() && random.nextFloat() < 0.6F) {
				level.setBlock(p, random.nextFloat() < 0.3F ? Blocks.MAGMA_BLOCK.defaultBlockState() : Blocks.BLACKSTONE.defaultBlockState(), 3);
			}
		}
		for (BlockPos p : BlockPos.betweenClosed(c.offset(-2, -2, -2), c.offset(2, 1, 2))) {
			double d = Math.sqrt(p.distSqr(c.below()));
			if (d < 1.3) {
				level.setBlock(p, dev.rubymod.registry.ModBlocks.RUBY_BLOCK.defaultBlockState(), 3);
			} else if (d < 2.3) {
				level.setBlock(p, random.nextFloat() < 0.45F ? dev.rubymod.registry.ModBlocks.RUBY_ORE.defaultBlockState()
						: random.nextFloat() < 0.5F ? Blocks.MAGMA_BLOCK.defaultBlockState() : Blocks.BLACKSTONE.defaultBlockState(), 3);
			}
		}
		level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, c.getX() + 0.5, c.getY() + 2, c.getZ() + 0.5, 4, 2, 1, 2, 0);
		level.sendParticles(ParticleTypes.LAVA, c.getX() + 0.5, c.getY() + 2, c.getZ() + 0.5, 80, 3, 1, 3, 0);
		level.playSound(null, c, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 8.0F, 0.6F);
		ACTIVE_DROPS.add(c.above(2));
		dropsVisibleUntil = Math.max(dropsVisibleUntil, SeasonTicker.now() + 15 * 60_000L);
		Waypoints.global("Ruby meteor", c.above(2), 3, 15 * 60);
		for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
			if (Hub.inRubyWarsWorld(p)) {
				p.sendSystemMessage(Component.literal("★ Ruby Meteor down at " + c.getX() + ", " + c.getZ() + ": full of rubies").withStyle(ChatFormatting.RED));
			}
		}
		for (ServerPlayer player : level.players()) {
			Alerts.title(player, "RUBY METEOR", "It's on your map (K): " + c.getX() + ", " + c.getZ(), ChatFormatting.RED);
		}
		playToAll(level.getServer(), SoundEvents.FIREWORK_ROCKET_LARGE_BLAST_FAR, 0.5F);
		return c;
	}

	// ----- Supply drops -----

	static List<ItemStack> supplyLoot(RandomSource random) {
		List<ItemStack> loot = new ArrayList<>();
		loot.add(new ItemStack(ModItems.RUBY, 10 + random.nextInt(11)));
		Item[][] picks = {
				{ModItems.ROCKET_LAUNCHER, ModItems.ROCKET},
				{ModItems.LONGSHOT_SNIPER, ModItems.SNIPER_ROUND},
				{ModItems.RUBY_BLASTER, ModItems.RUBY_CELL},
				{ModItems.AIR_STRIKE_FLARE, ModItems.FRAG_GRENADE},
				{ModItems.CRUISE_MISSILE, ModItems.RANGEFINDER},
				{ModItems.BREACHING_CHARGE, ModItems.SMOKE_GRENADE}};
		for (int i = 0; i < 2; i++) {
			Item[] pick = picks[random.nextInt(picks.length)];
			ItemStack main = new ItemStack(pick[0]);
			if (pick[0] instanceof GunItem gun) {
				main.set(ModComponents.AMMO, gun.stats().magazine());
			}
			loot.add(main);
			loot.add(new ItemStack(pick[1], Math.min(pick[1].getDefaultMaxStackSize(), 4 + random.nextInt(12))));
		}
		return loot;
	}

	static void clearDrops() {
		for (Iterator<BlockPos> it = ACTIVE_DROPS.iterator(); it.hasNext(); ) {
			it.next();
			it.remove();
		}
	}
}
