package dev.rubymod.client;

import java.util.ArrayList;
import java.util.List;

import dev.rubymod.net.Payloads;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Something the HUD can point at and seekers can lock: either an entity this
 * client can see, or a long-range contact the server reported (see game.Range)
 * that's too far away to be loaded here.
 */
public final class Contacts {
	public record Target(int id, Vec3 pos, String name, boolean airborne, boolean visible) {
	}

	private static List<Payloads.Contact> latest = List.of();
	private static long receivedAt;

	private Contacts() {
	}

	public static void receive(Payloads.Contacts packet) {
		latest = packet.contacts();
		receivedAt = System.currentTimeMillis();
	}

	public static String kindName(byte kind) {
		return switch (kind) {
			case 0 -> "Jeep";
			case 1 -> "Tank";
			case 2 -> "Fighter";
			case 3 -> "Attack Jet";
			case 4 -> "Bomber";
			case 5 -> "Drone";
			default -> "Contact";
		};
	}

	public static Target of(Entity e) {
		return new Target(e.getId(), e.getBoundingBox().getCenter(), e.getName().getString(), !e.onGround(), true);
	}

	/** Server contacts this client can't see itself (not loaded here). */
	public static List<Target> remote(Minecraft mc) {
		List<Target> out = new ArrayList<>();
		if (mc.level == null || System.currentTimeMillis() - receivedAt > 3000) {
			return out;
		}
		for (Payloads.Contact c : latest) {
			if (mc.level.getEntity(c.id()) == null) {
				out.add(new Target(c.id(), new Vec3(c.x(), c.y() + 1.0, c.z()), kindName(c.kind()), c.airborne(), false));
			}
		}
		return out;
	}

	/** Current position of a target by id: the visible entity, else the latest contact report. */
	public static Target refresh(Minecraft mc, Target t) {
		if (t == null || mc.level == null) {
			return null;
		}
		Entity e = mc.level.getEntity(t.id());
		if (e != null) {
			return e.isAlive() ? of(e) : null;
		}
		for (Payloads.Contact c : latest) {
			if (c.id() == t.id()) {
				return new Target(c.id(), new Vec3(c.x(), c.y() + 1.0, c.z()), kindName(c.kind()), c.airborne(), false);
			}
		}
		return System.currentTimeMillis() - receivedAt > 3000 ? null : t;
	}
}
