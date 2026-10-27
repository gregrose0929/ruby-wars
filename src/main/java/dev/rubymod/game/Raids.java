package dev.rubymod.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.rubymod.entity.SoldierEntity;
import dev.rubymod.industry.Grid;

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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Practice raids during the build phase: a squad of AI raiders lands outside a
 * base and marches on its Heart. Every raider that reaches the Heart loots the
 * owner's bank each second until it's killed; wipe them out within the time
 * limit for a ruby bonus and research points. They test your walls, turrets
 * and guards before Battle Day does.
 *
 * Raids come on the configured build days (raidDays) once the base's owner or a
 * squadmate is online, and anyone can call one on their own base once a day
 * (/rubywars raid). Turrets and other defenses fire on raiders even before
 * Battle Day.
 */
public final class Raids {
	private static final Map<Integer, Raid> ACTIVE = new HashMap<>();
	private static int nextId = 1;
	/** Owner UUID -> day they were last raided (scheduled or requested). */
	private static final Map<UUID, Integer> LAST_RAID = new HashMap<>();
	private static final Map<UUID, Long> PENDING = new HashMap<>();

	private static final class Raid {
		final int id;
		final UUID owner;
		final String ownerName;
		final BlockPos heart;
		final long endsAt;
		final List<UUID> raiders = new ArrayList<>();
		int killed;
		long looted;

		Raid(int id, UUID owner, String ownerName, BlockPos heart, long endsAt) {
			this.id = id;
			this.owner = owner;
			this.ownerName = ownerName;
			this.heart = heart;
			this.endsAt = endsAt;
		}
	}

	private Raids() {
	}

	/** Is a raid hitting this claim right now (defenses fire on raiders then)? */
	public static boolean active(SeasonData.Claim claim) {
		for (Raid raid : ACTIVE.values()) {
			if (raid.heart.equals(claim.pos())) {
				return true;
			}
		}
		return false;
	}

	/** /rubywars raid: call a practice raid on your own base (once a day, build phase only). */
	public static String request(ServerPlayer player) {
		SeasonData data = SeasonData.get();
		if (data.phase != Phase.BUILD) {
			return "Practice raids only happen during the build phase.";
		}
		SeasonData.Claim claim = data.claimOf(player.getUUID());
		if (claim == null || claim.destroyed) {
			return "Place your Ruby Heart first: raiders go for the Heart.";
		}
		int day = SeasonTicker.currentDay();
		if (LAST_RAID.getOrDefault(player.getUUID(), -1) == day) {
			return "You've already been raided today. Try again tomorrow.";
		}
		if (active(claim)) {
			return "A raid is already underway!";
		}
		LAST_RAID.put(player.getUUID(), day);
		start(player.level().getServer(), claim, 60);
		return null;
	}

	/** Admin: raid this player's base now. */
	public static String force(ServerPlayer target) {
		SeasonData.Claim claim = SeasonData.get().claimOf(target.getUUID());
		if (claim == null || claim.destroyed) {
			return target.getName().getString() + " has no Heart placed.";
		}
		start(target.level().getServer(), claim, 20);
		return null;
	}

	/** Once a second. */
	public static void tick(MinecraftServer server) {
		SeasonData data = SeasonData.get();
		long now = System.currentTimeMillis();
		// Scheduled raids: on raid days, a few minutes after the owner (or a squadmate) is around.
		if (data.phase == Phase.BUILD && server.getTickCount() % 1200 == 0) {
			int day = SeasonTicker.currentDay();
			boolean raidDay = false;
			for (int d : GameConfig.get().raidDays) {
				raidDay |= d == day;
			}
			if (raidDay) {
				for (SeasonData.Claim claim : data.claims.values()) {
					UUID owner = UUID.fromString(claim.owner);
					if (claim.destroyed || LAST_RAID.getOrDefault(owner, -1) == day || PENDING.containsKey(owner) || !defenderOnline(server, owner)) {
						continue;
					}
					long delay = (2 + server.overworld().getRandom().nextInt(8)) * 60_000L;
					PENDING.put(owner, now + delay);
					tellSide(server, owner, Component.literal("Scouts report raiders massing near your base. They'll strike within 10 minutes.")
							.withStyle(ChatFormatting.GOLD));
				}
			}
		}
		for (Iterator<Map.Entry<UUID, Long>> it = PENDING.entrySet().iterator(); it.hasNext();) {
			Map.Entry<UUID, Long> e = it.next();
			if (now < e.getValue()) {
				continue;
			}
			it.remove();
			SeasonData.Claim claim = data.claimOf(e.getKey());
			if (claim != null && !claim.destroyed && data.phase == Phase.BUILD) {
				LAST_RAID.put(e.getKey(), SeasonTicker.currentDay());
				start(server, claim, 60);
			}
		}
		// Running raids: time out, clean up.
		for (Iterator<Raid> it = ACTIVE.values().iterator(); it.hasNext();) {
			Raid raid = it.next();
			ServerLevel level = server.overworld();
			raid.raiders.removeIf(id -> {
				Entity e = level.getEntity(id);
				return e == null || !e.isAlive();
			});
			int left = (int) Math.max(0, (raid.endsAt - now) / 1000);
			for (ServerPlayer p : sidePlayers(server, raid.owner)) {
				dev.rubymod.net.Net.send(p, new dev.rubymod.net.MorePayloads.RaidStatus(raid.raiders.size(), left, (int) (raid.looted / 1000)));
			}
			if (raid.raiders.isEmpty()) {
				it.remove();
				win(server, raid);
			} else if (now > raid.endsAt) {
				for (UUID id : raid.raiders) {
					Entity e = level.getEntity(id);
					if (e != null) {
						level.sendParticles(ParticleTypes.LARGE_SMOKE, e.getX(), e.getY() + 1, e.getZ(), 10, 0.3, 0.6, 0.3, 0.02);
						e.discard();
					}
				}
				it.remove();
				Waypoints.clearGlobal(raid.heart);
				tellSide(server, raid.owner, Component.literal("The raiders pulled out with " + raid.looted / 1000 + " of your rubies. "
						+ raid.killed + " didn't make it home.").withStyle(ChatFormatting.RED));
			}
		}
	}

	/** Raids only come when someone from the base is actually in Ruby Wars (not off in the hub or Horde). */
	private static boolean defenderOnline(MinecraftServer server, UUID owner) {
		if (inRubyWars(server, owner)) {
			return true;
		}
		for (UUID mate : Squads.mates(owner)) {
			if (inRubyWars(server, mate)) {
				return true;
			}
		}
		return false;
	}

	private static boolean inRubyWars(MinecraftServer server, UUID id) {
		ServerPlayer player = server.getPlayerList().getPlayer(id);
		return player != null && Hub.where(player) == Hub.Where.RUBY_WARS;
	}

	private static void start(MinecraftServer server, SeasonData.Claim claim, int warningSeconds) {
		ServerLevel level = server.overworld();
		RandomSource random = level.getRandom();
		UUID owner = UUID.fromString(claim.owner);
		int size = GameConfig.get().raidSize[Math.max(0, Math.min(GameConfig.get().raidSize.length, claim.tier) - 1)];
		double angle = random.nextDouble() * Math.PI * 2;
		int dist = claim.radius() + 16 + random.nextInt(12);
		int x = claim.x + (int) Math.round(Math.cos(angle) * dist);
		int z = claim.z + (int) Math.round(Math.sin(angle) * dist);
		level.getChunk(x >> 4, z >> 4);
		BlockPos landing = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
		Raid raid = new Raid(nextId++, owner, claim.ownerName, claim.pos(), System.currentTimeMillis()
				+ GameConfig.get().raidMinutes * 60_000L + warningSeconds * 1000L);
		for (int i = 0; i < size; i++) {
			int role = i % 4 == 3 ? SoldierEntity.AT_GUNNER : SoldierEntity.RIFLEMAN;
			BlockPos at = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, landing.offset(random.nextInt(7) - 3, 0, random.nextInt(7) - 3));
			SoldierEntity s = SoldierEntity.raider(level, at, claim.pos(), role, raid.id);
			if (s != null && level.addFreshEntity(s)) {
				raid.raiders.add(s.getUUID());
			}
		}
		ACTIVE.put(raid.id, raid);
		level.sendParticles(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, landing.getX() + 0.5, landing.getY() + 1, landing.getZ() + 0.5, 20, 1, 1, 1, 0.02);
		level.playSound(null, landing, SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 64.0F, 1.0F);
		// Only the raided squad sees these: other players don't learn where the base is.
		Waypoints.forSquad(owner, "RAID on " + claim.ownerName, claim.pos(), 6, GameConfig.get().raidMinutes * 60 + warningSeconds);
		Waypoints.forSquad(owner, "Raiders", landing, 6, 120);
		tellSide(server, owner, Component.literal("RAID! " + raid.raiders.size() + " raiders are coming for your Heart from the "
				+ direction(claim.pos(), landing) + ". Kill them before they loot your bank!").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
		for (ServerPlayer p : sidePlayers(server, owner)) {
			Alerts.title(p, "RAID INCOMING", raid.raiders.size() + " raiders from the " + direction(claim.pos(), landing), ChatFormatting.RED);
		}
	}

	/** A raider standing at the Heart: take a cut of the bank, up to the cap. */
	public static void loot(ServerLevel level, SoldierEntity raider) {
		Raid raid = ACTIVE.get(raider.raidId());
		if (raid == null) {
			return;
		}
		long bank = Grid.milliRubies(raid.owner);
		long cap = (long) ((bank + raid.looted) * GameConfig.get().raidMaxLootPercent / 100.0);
		long take = Math.min(Math.max(1000, bank / 100), cap - raid.looted);
		if (take <= 0 || bank < 1000) {
			return;
		}
		long rubies = take / 1000;
		if (rubies > 0 && Grid.withdraw(raid.owner, rubies)) {
			raid.looted += rubies * 1000;
			level.sendParticles(ParticleTypes.ENCHANTED_HIT, raider.getX(), raider.getY() + 1.5, raider.getZ(), 6, 0.3, 0.3, 0.3, 0.1);
			ServerPlayer owner = level.getServer().getPlayerList().getPlayer(raid.owner);
			if (owner != null) {
				owner.sendOverlayMessage(Component.literal("Raiders are looting your bank! -" + rubies + " rubies").withStyle(ChatFormatting.RED));
			}
		}
	}

	public static void raiderKilled(ServerLevel level, SoldierEntity raider, Entity killer) {
		Raid raid = ACTIVE.get(raider.raidId());
		if (raid == null) {
			return;
		}
		raid.killed++;
		raid.raiders.remove(raider.getUUID());
		ServerPlayer payee = null;
		if (killer instanceof ServerPlayer p) {
			payee = p;
		} else if (killer instanceof SoldierEntity guard && !guard.isRaider()) {
			payee = level.getServer().getPlayerList().getPlayer(guard.owner());
		}
		if (payee != null) {
			Grid.deposit(payee.getUUID(), GameConfig.get().raidRewardPerKill * 1000L);
			payee.sendOverlayMessage(Component.literal("+" + GameConfig.get().raidRewardPerKill + " rubies: raider down").withStyle(ChatFormatting.GREEN));
		}
	}

	private static void win(MinecraftServer server, Raid raid) {
		Waypoints.clearGlobal(raid.heart);
		int bonus = GameConfig.get().raidVictoryBonus;
		Grid.deposit(raid.owner, bonus * 1000L);
		Goals.add(server, raid.owner, "raid", 1);
		tellSide(server, raid.owner, Component.literal("Raid repelled! " + raid.killed + " raiders down. +" + bonus + " rubies"
				+ (raid.looted > 0 ? " (they got " + raid.looted / 1000 + " rubies)" : "") + ".").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
		for (ServerPlayer p : sidePlayers(server, raid.owner)) {
			p.level().playSound(null, p.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.8F, 1.0F);
		}
	}

	private static List<ServerPlayer> sidePlayers(MinecraftServer server, UUID owner) {
		List<ServerPlayer> out = new ArrayList<>();
		ServerPlayer o = server.getPlayerList().getPlayer(owner);
		if (o != null) {
			out.add(o);
		}
		for (UUID mate : Squads.mates(owner)) {
			ServerPlayer m = server.getPlayerList().getPlayer(mate);
			if (m != null) {
				out.add(m);
			}
		}
		return out;
	}

	private static void tellSide(MinecraftServer server, UUID owner, Component message) {
		for (ServerPlayer p : sidePlayers(server, owner)) {
			p.sendSystemMessage(message);
		}
	}

	private static String direction(BlockPos from, BlockPos to) {
		double angle = Math.toDegrees(Math.atan2(to.getZ() - from.getZ(), to.getX() - from.getX()));
		String[] names = {"east", "southeast", "south", "southwest", "west", "northwest", "north", "northeast"};
		return names[Math.floorMod(Math.round((float) angle / 45.0F), 8)];
	}
}
