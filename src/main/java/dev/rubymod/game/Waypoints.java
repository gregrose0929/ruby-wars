package dev.rubymod.game;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import dev.rubymod.net.MorePayloads;
import dev.rubymod.net.Net;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * Map markers: your own named waypoints, your War Grave, squad pings (30 s),
 * supply drops and practice raids for everyone, your Heart and your squad's
 * Hearts. Sent to each player once a second with squadmates and nearby claims.
 */
public final class Waypoints {
	public static final int PING_SECONDS = 30;

	public static class Waypoint {
		public String name;
		public int x;
		public int y;
		public int z;
		public byte kind;

		public Waypoint() {
		}

		Waypoint(String name, BlockPos pos, int kind) {
			this.name = name;
			this.x = pos.getX();
			this.y = pos.getY();
			this.z = pos.getZ();
			this.kind = (byte) kind;
		}
	}

	private record Ping(UUID from, String squad, String name, BlockPos pos, long until) {
	}

	private record Global(String name, BlockPos pos, int kind, long until) {
	}

	private record Spot(UUID side, long until) {
	}

	private static final List<Ping> PINGS = new ArrayList<>();
	/** Spotted enemies (entity UUID -> who spotted them and until when). */
	private static final java.util.Map<UUID, Spot> SPOTS = new java.util.HashMap<>();
	private static final List<Global> GLOBAL = new ArrayList<>();

	private Waypoints() {
	}

	private static List<Waypoint> own(UUID player) {
		return SeasonData.get().waypoints.computeIfAbsent(player.toString(), k -> new ArrayList<>());
	}

	public static String add(ServerPlayer player, String name) {
		List<Waypoint> list = own(player.getUUID());
		list.removeIf(w -> w.kind == 0 && w.name.equalsIgnoreCase(name));
		if (list.stream().filter(w -> w.kind == 0).count() >= 16) {
			return "You can have 16 waypoints. Remove one first (/rubywars waypoint remove <name>).";
		}
		list.add(new Waypoint(name, player.blockPosition(), 0));
		SeasonData.markDirty();
		return null;
	}

	public static boolean remove(ServerPlayer player, String name) {
		boolean removed = own(player.getUUID()).removeIf(w -> w.name.equalsIgnoreCase(name));
		SeasonData.markDirty();
		return removed;
	}

	public static List<Waypoint> list(ServerPlayer player) {
		return own(player.getUUID());
	}

	public static void setGrave(ServerPlayer player, BlockPos pos) {
		List<Waypoint> list = own(player.getUUID());
		list.removeIf(w -> w.kind == 1);
		list.add(new Waypoint("Your grave", pos, 1));
		SeasonData.markDirty();
	}

	public static void clearGrave(UUID owner) {
		List<Waypoint> list = SeasonData.get().waypoints.get(owner.toString());
		if (list != null && list.removeIf(w -> w.kind == 1)) {
			SeasonData.markDirty();
		}
	}

	/** A squad ping (or a personal one if you're solo), with a sound for everyone who sees it. */
	public static void ping(ServerPlayer player, BlockPos pos) {
		long now = System.currentTimeMillis();
		Squads.Squad squad = Squads.of(player.getUUID());
		PINGS.removeIf(p -> p.from.equals(player.getUUID()));
		PINGS.add(new Ping(player.getUUID(), squad == null ? "" : squad.name, player.getName().getString(), pos, now + PING_SECONDS * 1000L));
		MinecraftServer server = player.level().getServer();
		List<UUID> hear = new ArrayList<>(Squads.mates(player.getUUID()));
		hear.add(player.getUUID());
		for (UUID id : hear) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p != null) {
				p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 0.5F, 1.8F);
			}
		}
	}

	/** Mark an enemy for the spotter's squad for a few seconds (pings on enemies, recon drones, spotters). */
	public static void spot(net.minecraft.world.entity.Entity target, UUID spotter, int seconds) {
		long until = System.currentTimeMillis() + seconds * 1000L;
		Spot old = SPOTS.get(target.getUUID());
		if (old == null || old.until < until || !Squads.same(old.side, spotter)) {
			SPOTS.put(target.getUUID(), new Spot(spotter, until));
		}
	}

	/** A ping from the client: on an enemy it spots them for 10 s, otherwise it marks the spot. */
	public static void ping(ServerPlayer player, MorePayloads.Ping payload) {
		if (payload.entityId() >= 0) {
			net.minecraft.world.entity.Entity e = player.level().getEntity(payload.entityId());
			if (e != null && e.distanceTo(player) < 400 && hostile(player.getUUID(), e)) {
				spot(e, player.getUUID(), 10);
				player.sendOverlayMessage(net.minecraft.network.chat.Component.literal("Spotted " + e.getName().getString())
						.withStyle(net.minecraft.ChatFormatting.RED));
				return;
			}
		}
		ping(player, new BlockPos(payload.x(), payload.y(), payload.z()));
	}

	private static boolean hostile(UUID side, net.minecraft.world.entity.Entity e) {
		if (e instanceof net.minecraft.world.entity.player.Player p) {
			return !Squads.same(side, p.getUUID()) && !Rules.isFriendly(side, p.getUUID()) && !p.isSpectator();
		}
		if (e instanceof dev.rubymod.entity.SoldierEntity s) {
			return s.isRaider() || !Squads.same(side, s.owner());
		}
		if (Armor.isArmored(e)) {
			return !(e.getFirstPassenger() instanceof net.minecraft.world.entity.player.Player crew) || hostile(side, crew);
		}
		return e instanceof net.minecraft.world.entity.monster.Enemy;
	}

	/** Everyone sees this marker until it expires (supply drops, raids). */
	public static void global(String name, BlockPos pos, int kind, int seconds) {
		GLOBAL.removeIf(g -> g.pos.equals(pos));
		GLOBAL.add(new Global(name, pos, kind, System.currentTimeMillis() + seconds * 1000L));
	}

	public static void clearGlobal(BlockPos pos) {
		GLOBAL.removeIf(g -> g.pos.equals(pos));
	}

	/** Once a second: every player's markers, squadmates and the claims within 600 blocks. */
	public static void sync(MinecraftServer server) {
		long now = System.currentTimeMillis();
		PINGS.removeIf(p -> p.until < now);
		SPOTS.values().removeIf(s -> s.until < now);
		for (Iterator<Global> it = GLOBAL.iterator(); it.hasNext();) {
			if (it.next().until < now) {
				it.remove();
			}
		}
		SeasonData data = SeasonData.get();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			UUID id = player.getUUID();
			Squads.Squad squad = Squads.of(id);
			List<MorePayloads.Marker> markers = new ArrayList<>();
			for (Waypoint w : own(id)) {
				markers.add(new MorePayloads.Marker(w.name, w.x, w.y, w.z, w.kind));
			}
			for (Ping p : PINGS) {
				boolean mine = p.from.equals(id) || (squad != null && squad.name.equals(p.squad));
				if (mine) {
					markers.add(new MorePayloads.Marker(p.name + "'s ping", p.pos.getX(), p.pos.getY(), p.pos.getZ(), (byte) 2));
				}
			}
			for (Global g : GLOBAL) {
				markers.add(new MorePayloads.Marker(g.name, g.pos.getX(), g.pos.getY(), g.pos.getZ(), (byte) g.kind));
			}
			for (java.util.Map.Entry<UUID, Spot> e : SPOTS.entrySet()) {
				if (!Squads.same(id, e.getValue().side)) {
					continue;
				}
				net.minecraft.world.entity.Entity target = ((net.minecraft.server.level.ServerLevel) player.level()).getEntity(e.getKey());
				if (target != null && target.isAlive()) {
					markers.add(new MorePayloads.Marker("Spotted: " + target.getName().getString(), target.getBlockX(), target.getBlockY() + 1,
							target.getBlockZ(), (byte) 7));
				}
			}
			List<MorePayloads.Mate> mates = new ArrayList<>();
			for (UUID mate : Squads.mates(id)) {
				ServerPlayer m = server.getPlayerList().getPlayer(mate);
				if (m != null && m.level() == player.level() && !m.isSpectator()) {
					mates.add(new MorePayloads.Mate(m.getName().getString(), (float) m.getX(), (float) m.getY(), (float) m.getZ(),
							m.getHealth() / m.getMaxHealth()));
				}
			}
			List<MorePayloads.ClaimInfo> claims = new ArrayList<>();
			for (SeasonData.Claim claim : data.claims.values()) {
				if (claim.destroyed) {
					continue;
				}
				UUID owner = UUID.fromString(claim.owner);
				byte relation = (byte) (owner.equals(id) ? 0 : Squads.same(owner, id) ? 1 : 2);
				if (relation == 0) {
					markers.add(new MorePayloads.Marker("Your Heart", claim.x, claim.y, claim.z, (byte) 4));
				} else if (relation == 1) {
					markers.add(new MorePayloads.Marker(claim.ownerName + "'s Heart", claim.x, claim.y, claim.z, (byte) 5));
				}
				double dx = claim.x - player.getX();
				double dz = claim.z - player.getZ();
				if (relation < 2 || dx * dx + dz * dz < 600 * 600) {
					claims.add(new MorePayloads.ClaimInfo(claim.ownerName, claim.x, claim.z, claim.radius(), relation));
				}
			}
			Net.send(player, new MorePayloads.MapSync(squad == null ? "" : squad.name, markers, mates, claims));
		}
	}
}
