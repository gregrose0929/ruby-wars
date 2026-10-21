package dev.rubymod.game;

import java.util.ArrayList;
import java.util.List;

import dev.rubymod.entity.AircraftEntity;
import dev.rubymod.entity.AircraftType;
import dev.rubymod.entity.VehicleEntity;
import dev.rubymod.item.LockOnLauncherItem;
import dev.rubymod.net.Net;
import dev.rubymod.net.Payloads;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/**
 * Long-range play past Minecraft's render and simulation distances.
 *
 * - {@link #keepLoaded}: missiles, bombs, shells and pilotless aircraft hold a
 *   short-lived chunk ticket where they are and where they're heading (the same
 *   kind a flying ender pearl uses), so they keep flying out of everyone's view.
 * - Contacts: every half second, pilots, drivers and players holding a lock-on
 *   launcher get the positions of aircraft and vehicles within 1500 blocks, so
 *   their HUD can show (and their seeker lock) things the client can't render.
 */
public final class Range {
	public static final double CONTACT_RANGE = 1500;

	private Range() {
	}

	/** Keep the chunks at and ahead of a fast mover loaded for the next ~2 seconds. */
	public static void keepLoaded(ServerLevel level, Entity e) {
		if (e.tickCount % 10 != 0) {
			return;
		}
		level.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.ENDER_PEARL, e.chunkPosition(), 2);
		Vec3 ahead = e.position().add(e.getDeltaMovement().scale(20));
		ChunkPos next = ChunkPos.containing(net.minecraft.core.BlockPos.containing(ahead));
		if (!next.equals(e.chunkPosition())) {
			level.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.ENDER_PEARL, next, 2);
		}
	}

	/** Contact kinds for the HUD. */
	public static byte kind(Entity e) {
		if (e instanceof VehicleEntity v) {
			return (byte) (v.isTank() ? 1 : 0);
		}
		if (e instanceof AircraftEntity a) {
			if (a.isDrone()) {
				return 5;
			}
			return (byte) (a.kind() == AircraftType.FIGHTER ? 2 : a.kind() == AircraftType.ATTACK ? 3 : a.kind() == AircraftType.HELICOPTER ? 6 : 4);
		}
		return 0;
	}

	/** Called every server tick. */
	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % 10 != 0) {
			return;
		}
		for (ServerLevel level : server.getAllLevels()) {
			List<Entity> armored = new ArrayList<>();
			for (Entity e : level.getAllEntities()) {
				if (Armor.isArmored(e) && e.isAlive()) {
					armored.add(e);
				}
			}
			for (ServerPlayer player : level.players()) {
				boolean wants = Armor.isArmored(player.getVehicle()) || player.getMainHandItem().getItem() instanceof LockOnLauncherItem;
				if (!wants) {
					continue;
				}
				List<Payloads.Contact> contacts = new ArrayList<>();
				for (Entity e : armored) {
					if (e == player.getVehicle() || e.distanceTo(player) > CONTACT_RANGE) {
						continue;
					}
					contacts.add(new Payloads.Contact(e.getId(), kind(e), (float) e.getX(), (float) e.getY(), (float) e.getZ(), !e.onGround(),
							e.getControllingPassenger() != null));
					if (contacts.size() >= 64) {
						break;
					}
				}
				Net.send(player, new Payloads.Contacts(contacts));
			}
		}
	}
}
