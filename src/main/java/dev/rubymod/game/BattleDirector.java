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
				Alerts.broadcastTitle(server, "Season " + data.season, "Build phase: 14 days to prepare", ChatFormatting.RED);
				Alerts.broadcast(server, Component.literal("The season has begun! Place your Ruby Heart to claim land, mine rubies, "
						+ "and get ready for Battle Day.").withStyle(ChatFormatting.GOLD));
			}
			case LOCKDOWN -> {
				for (SeasonData.PlayerRecord record : data.players.values()) {
					record.missilesUsed = 0;
					record.killstreak = 0;
				}
				Alerts.broadcastTitle(server, "BATTLE DAY", "Lockdown: claims open, PvP in " + config.lockdownMinutes + " min", ChatFormatting.RED);
				Alerts.broadcast(server, Component.literal("Battle Day has begun! Claims are open and defenses are online. "
						+ "PvP starts in " + config.lockdownMinutes + " minutes.").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
				playToAll(server, SoundEvents.RAID_HORN.value(), 1.0F);
			}
			case WAR -> {
				data.nextSupplyDropAt = SeasonTicker.now() + config.supplyDropIntervalMinutes * 60_000L;
				Alerts.broadcastTitle(server, "OPEN WAR", "PvP is on. Hearts can be destroyed.", ChatFormatting.DARK_RED);
				playToAll(server, SoundEvents.ENDER_DRAGON_GROWL, 1.0F);
			}
			case SUDDEN_DEATH -> {
				for (SeasonData.Claim claim : data.claims.values()) {
					if (!claim.destroyed) {
						Claims.destroyHeart(overworld, claim, null);
					}
				}
				for (Map.Entry<String, SeasonData.PlayerRecord> entry : data.players.entrySet()) {
					if (!entry.getValue().eliminated && server.getPlayerList().getPlayer(UUID.fromString(entry.getKey())) == null) {
						eliminate(server, UUID.fromString(entry.getKey()), entry.getValue(), "didn't show up for Sudden Death");
					}
				}
				WorldBorder border = overworld.getWorldBorder();
				border.setCenter(config.borderCenterX, config.borderCenterZ);
				border.lerpSizeBetween(border.getSize(), config.borderEndSize, config.suddenDeathMinutes * 60L * 20L, overworld.getGameTime());
				Alerts.broadcastTitle(server, "SUDDEN DEATH", "No more respawns. The border is closing.", ChatFormatting.DARK_RED);
				playToAll(server, SoundEvents.WITHER_SPAWN, 1.0F);
			}
			case FINAL_CIRCLE -> {
				WorldBorder border = overworld.getWorldBorder();
				border.setSize(config.borderEndSize);
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

	/** /rubywars start: begins the season from the lobby. */
	public static void startSeason(MinecraftServer server) {
		SeasonData data = SeasonData.get();
		GameConfig config = GameConfig.get();
		WorldBorder border = server.overworld().getWorldBorder();
		border.setCenter(config.borderCenterX, config.borderCenterZ);
		border.setSize(config.borderStartSize);
		data.winner = "";
		enterPhase(server, Phase.BUILD);
	}

	// ----- Ticking -----

	public static void tickSecond(MinecraftServer server) {
		SeasonData data = SeasonData.get();
		if (data.phase == Phase.WAR && SeasonTicker.now() >= data.nextSupplyDropAt && data.pausedRemaining < 0) {
			data.nextSupplyDropAt = SeasonTicker.now() + GameConfig.get().supplyDropIntervalMinutes * 60_000L;
			supplyDrop(server.overworld());
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
		if (!record.heartGiven && !data.phase.isBattle() && data.phase != Phase.ENDED) {
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
		}
	}

	public static void onJoin(ServerPlayer player) {
		SeasonData data = SeasonData.get();
		data.player(player.getUUID(), player.getName().getString());
		if (data.phase != Phase.LOBBY) {
			giveHeartIfNeeded(player);
		}
		if (data.phase == Phase.BUILD) {
			Bounties.today(player);
		}
		if (Rules.isEliminated(player.getUUID())) {
			player.setGameMode(GameType.SPECTATOR);
			player.sendSystemMessage(Component.literal("You've been eliminated this season. You can watch as a spectator.").withStyle(ChatFormatting.GRAY));
		}
	}

	public static void onLeave(MinecraftServer server, ServerPlayer player) {
		Gunfire.forget(player.getUUID());
		dev.rubymod.gun.LagCompensation.forget(player.getUUID());
		SeasonData data = SeasonData.get();
		if (data.phase == Phase.SUDDEN_DEATH || data.phase == Phase.FINAL_CIRCLE) {
			SeasonData.PlayerRecord record = data.player(player.getUUID());
			if (record != null && !record.eliminated) {
				eliminate(server, player.getUUID(), record, "left during " + data.phase.title());
			}
		}
	}

	public static void onDeath(ServerPlayer victim, DamageSource source) {
		MinecraftServer server = victim.level().getServer();
		SeasonData data = SeasonData.get();
		SeasonData.PlayerRecord record = data.player(victim.getUUID(), victim.getName().getString());
		record.deaths++;
		record.killstreak = 0;
		SeasonData.markDirty();

		if (source.getEntity() instanceof ServerPlayer killer && killer != victim) {
			broadcastKill(server, killer, victim, source);
		}
		if (source.getEntity() instanceof ServerPlayer killer && killer != victim && data.phase.pvp()) {
			SeasonData.PlayerRecord k = data.player(killer.getUUID(), killer.getName().getString());
			k.kills++;
			k.killstreak++;
			k.bestKillstreak = Math.max(k.bestKillstreak, k.killstreak);
			if (k.killstreak == GameConfig.get().killstreakForAirStrike) {
				killer.getInventory().add(new ItemStack(ModItems.AIR_STRIKE_FLARE));
				Alerts.title(killer, k.killstreak + " KILL STREAK", "Air Strike Flare unlocked!", ChatFormatting.GOLD);
			}
		}

		if (!data.phase.isBattle()) {
			return;
		}
		SeasonData.Claim claim = data.claimOf(victim.getUUID());
		boolean canRespawn = data.phase.heartsGiveRespawns() && claim != null && !claim.destroyed;
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
		SeasonData.Claim claim = data.claimOf(player.getUUID());
		if (claim != null && !claim.destroyed) {
			ServerLevel level = player.level().getServer().overworld();
			BlockPos spot = safeSpotNear(level, claim.pos());
			player.teleportTo(level, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, Set.<Relative>of(), player.getYRot(), player.getXRot(), true);
			SpawnShield.grant(player);
			player.sendSystemMessage(Component.literal("Respawned at your Ruby Heart. Spawn protection for "
					+ SpawnShield.TICKS / 20 + "s (ends if you shoot).").withStyle(ChatFormatting.GREEN), true);
		}
	}

	private static BlockPos safeSpotNear(ServerLevel level, BlockPos heart) {
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

	public static void eliminate(MinecraftServer server, UUID id, SeasonData.PlayerRecord record, String reason) {
		if (record.eliminated) {
			return;
		}
		record.eliminated = true;
		SeasonData.markDirty();
		int left = aliveCount();
		String why = reason == null ? "" : " (" + reason + ")";
		Alerts.broadcast(server, Component.literal(record.name + " has been eliminated" + why + "! " + left + " player" + (left == 1 ? "" : "s") + " remain.")
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
		if (!data.phase.pvp() || data.players.size() < 2) {
			return;
		}
		List<SeasonData.PlayerRecord> alive = new ArrayList<>();
		for (SeasonData.PlayerRecord record : data.players.values()) {
			if (!record.eliminated) {
				alive.add(record);
			}
		}
		if (alive.size() <= 1) {
			data.winner = alive.isEmpty() ? "" : alive.get(0).name;
			enterPhase(server, Phase.ENDED);
		}
	}

	private static void announceWinner(MinecraftServer server) {
		SeasonData data = SeasonData.get();
		if (data.winner.isEmpty()) {
			Alerts.broadcastTitle(server, "NO SURVIVORS", "Season " + data.season + " ends in a draw", ChatFormatting.GRAY);
			return;
		}
		data.pastChampions.add("Season " + data.season + ": " + data.winner);
		Alerts.broadcastTitle(server, data.winner.toUpperCase() + " WINS", "Champion of Season " + data.season, ChatFormatting.GOLD);
		playToAll(server, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0F);
		Alerts.broadcast(server, Component.literal("=== Season " + data.season + " results ===").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
		Alerts.broadcast(server, Component.literal("Champion: " + data.winner).withStyle(ChatFormatting.GOLD));
		data.players.values().stream()
				.sorted((a, b) -> Integer.compare(b.kills, a.kills))
				.limit(5)
				.forEach(r -> Alerts.broadcast(server, Component.literal(r.name + ": " + r.kills + " kills, " + r.heartsDestroyed
						+ " Hearts destroyed, best streak " + r.bestKillstreak).withStyle(ChatFormatting.YELLOW)));
		SeasonData.save();
	}

	private static void broadcastKill(MinecraftServer server, ServerPlayer killer, ServerPlayer victim, DamageSource source) {
		String weapon;
		if (source.is(ModDamage.TURRET)) {
			weapon = "Sentry Turret";
		} else if (source.getDirectEntity() instanceof Projectile projectile) {
			weapon = projectile.getName().getString();
		} else {
			ItemStack held = killer.getMainHandItem();
			weapon = held.isEmpty() ? "Fists" : held.getHoverName().getString();
		}
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

	private static void playToAll(MinecraftServer server, net.minecraft.sounds.SoundEvent sound, float pitch) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			player.level().playSound(null, player.getX(), player.getY(), player.getZ(), sound, SoundSource.MASTER, 1.0F, pitch);
		}
	}

	// ----- Supply drops -----

	private static void supplyDrop(ServerLevel level) {
		RandomSource random = level.getRandom();
		WorldBorder border = level.getWorldBorder();
		double half = border.getSize() * 0.4;
		int x = (int) (border.getCenterX() + (random.nextDouble() * 2 - 1) * half);
		int z = (int) (border.getCenterZ() + (random.nextDouble() * 2 - 1) * half);
		level.getChunk(x >> 4, z >> 4); // Load (or generate) the chunk so the heightmap is real.
		BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
		level.setBlockAndUpdate(top, Blocks.CHEST.defaultBlockState());
		if (level.getBlockEntity(top) instanceof ChestBlockEntity chest) {
			List<ItemStack> loot = supplyLoot(random);
			for (int i = 0; i < loot.size() && i < chest.getContainerSize(); i++) {
				chest.setItem(random.nextInt(chest.getContainerSize()), loot.get(i));
			}
		}
		ACTIVE_DROPS.add(top);
		dropsVisibleUntil = SeasonTicker.now() + 3 * 60_000L;
		Alerts.broadcast(level.getServer(), Component.literal("SUPPLY DROP landed at " + top.getX() + ", " + top.getY() + ", " + top.getZ() + "!")
				.withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
		playToAll(level.getServer(), SoundEvents.FIREWORK_ROCKET_LARGE_BLAST_FAR, 0.8F);
	}

	private static List<ItemStack> supplyLoot(RandomSource random) {
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
