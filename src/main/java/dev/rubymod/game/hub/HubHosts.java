package dev.rubymod.game.hub;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

import dev.rubymod.game.Props;
import dev.rubymod.net.MorePayloads;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.phys.Vec3;

/**
 * The park's hosts: one friendly villager at every game and ride. Walk up and they say what they run; right-click them
 * for a card with how to play and a big Play button. That's the one way to start anything in the park.
 */
public final class HubHosts {
	/**
	 * A host. {@code buttons} gives the card's buttons ("key|label") for the player asking (so they can change, like the
	 * DJ's song list); {@code pick} runs when one is pressed.
	 */
	public record Host(String id, String name, String role, int color, String icon, String body, String hint,
			Function<ServerPlayer, List<String>> buttons, BiConsumer<ServerPlayer, String> pick) {
	}

	private record Placed(Host host, UUID entity, Vec3 at) {
	}

	private static final List<Placed> HOSTS = new ArrayList<>();
	/** The host each player was last greeted by (so the hint shows once as you walk up). */
	private static final Map<UUID, String> NEAR = new HashMap<>();

	private HubHosts() {
	}

	static void reset() {
		HOSTS.clear();
		NEAR.clear();
	}

	/** One Play button. */
	static Function<ServerPlayer, List<String>> play(String label) {
		return p -> List.of("play|" + label);
	}

	/** Other players near {@code p} who could come along on a ride. */
	static List<ServerPlayer> friends(ServerPlayer p) {
		List<ServerPlayer> out = new ArrayList<>();
		for (ServerPlayer q : ((ServerLevel) p.level()).players()) {
			if (q != p && !q.isSpectator() && !q.isPassenger() && q.position().distanceTo(p.position()) < 6) {
				out.add(q);
			}
		}
		return out;
	}

	/** A ride's buttons: Play, and (when friends are standing close by) one that takes them along too. */
	static Function<ServerPlayer, List<String>> rideButtons(String label) {
		return p -> {
			int n = friends(p).size();
			List<String> b = new ArrayList<>();
			if (n > 0) {
				b.add("friends|Ride together (you + " + n + (n == 1 ? " friend)" : " friends)"));
			}
			b.add("play|" + (n > 0 ? "Just me" : label));
			return b;
		};
	}

	/** Who's riding: just {@code p}, or {@code p} and the friends nearby (who are told who brought them). */
	static List<ServerPlayer> group(ServerPlayer p, String key) {
		List<ServerPlayer> g = new ArrayList<>();
		g.add(p);
		if ("friends".equals(key)) {
			for (ServerPlayer q : friends(p)) {
				g.add(q);
				q.sendSystemMessage(Component.literal(p.getName().getString() + " brought you along for the ride! (Crouch to hop off.)")
						.withStyle(ChatFormatting.LIGHT_PURPLE));
			}
		}
		return g;
	}

	/** A host calls out to everyone nearby (who isn't already on it), e.g. "the train's leaving in 5 seconds!". */
	static void callOut(ServerLevel level, String id, String text, java.util.function.Predicate<ServerPlayer> skip) {
		for (Placed h : HOSTS) {
			if (!h.host().id().equals(id)) {
				continue;
			}
			for (ServerPlayer p : level.players()) {
				if (p.position().distanceTo(h.at()) < 28 && !skip.test(p)) {
					p.sendSystemMessage(Component.literal("[" + h.host().name() + "] ").withStyle(s -> s.withColor(TextColor.fromRgb(h.host().color())).withBold(true))
							.append(Component.literal(text).withStyle(ChatFormatting.WHITE)));
				}
			}
		}
	}

	/** Put a host at {@code at}, facing {@code yaw}, dressed as {@code profession} from {@code type}. */
	static void add(ServerLevel level, Vec3 at, float yaw, ResourceKey<VillagerProfession> profession, ResourceKey<VillagerType> type, Host host) {
		Villager v = EntityTypes.VILLAGER.create(level, EntitySpawnReason.EVENT);
		if (v == null) {
			return;
		}
		v.setVillagerData(v.getVillagerData().withProfession(level.registryAccess(), profession).withType(level.registryAccess(), type).withLevel(5));
		v.setNoAi(true);
		v.setSilent(true);
		v.setPermanentlyInvulnerable(true);
		v.setPersistenceRequired();
		v.setCustomName(Component.literal(host.name()).withStyle(s -> s.withColor(TextColor.fromRgb(host.color()))));
		v.snapTo(at.x, at.y, at.z, yaw, 0);
		v.setYHeadRot(yaw);
		v.setYBodyRot(yaw);
		level.addFreshEntity(v);
		HubPark.keep(v);
		HubPark.keep(Props.text(level, at.add(0, 2.75, 0), Component.literal(host.name() + " · " + host.role())
				.withStyle(s -> s.withColor(TextColor.fromRgb(host.color())).withBold(true))
				.append(Component.literal("\nRight-click me!").withStyle(ChatFormatting.WHITE)), 0.75F, HubPark.TAG));
		HOSTS.add(new Placed(host, v.getUUID(), at));
	}

	/** Right-clicked something in the hub: if it's a host, open their card. */
	static boolean clicked(ServerPlayer p, Entity e) {
		for (Placed h : HOSTS) {
			if (h.entity().equals(e.getUUID())) {
				open(p, h.host());
				return true;
			}
		}
		return false;
	}

	static void open(ServerPlayer p, Host host) {
		ServerPlayNetworking.send(p, new MorePayloads.HostCard(host.id(), host.name() + " · " + host.role(), host.body(), host.icon(), host.color(),
				host.buttons().apply(p)));
		p.level().playSound(null, p.blockPosition(), SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 0.7F, 1.1F);
	}

	/** A button on a card (only from someone standing near that host). */
	public static void picked(ServerPlayer p, String id, String key) {
		if (p.level().dimension() != dev.rubymod.game.Hub.HUB) {
			return;
		}
		for (Placed h : HOSTS) {
			if (h.host().id().equals(id) && p.position().distanceTo(h.at()) < 10) {
				h.host().pick().accept(p, key);
				return;
			}
		}
	}

	static void tick(ServerLevel level, int tick) {
		if (tick % 4 != 0) {
			return;
		}
		// Hosts turn to look at whoever's nearest.
		for (Placed h : HOSTS) {
			Entity e = level.getEntity(h.entity());
			if (e == null) {
				continue;
			}
			ServerPlayer near = null;
			for (ServerPlayer p : level.players()) {
				if (!p.isSpectator() && p.position().distanceTo(h.at()) < 8 && (near == null || p.distanceToSqr(e) < near.distanceToSqr(e))) {
					near = p;
				}
			}
			if (near != null) {
				Vec3 d = near.getEyePosition().subtract(e.getEyePosition());
				float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
				float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
				e.setYHeadRot(yaw);
				e.setXRot(Math.max(-30, Math.min(30, pitch)));
			}
			if (e.position().distanceToSqr(h.at()) > 0.25) {
				e.teleportTo(h.at().x, h.at().y, h.at().z);
			}
		}
		// A hint as you walk up to a host.
		for (ServerPlayer p : level.players()) {
			Placed best = null;
			double bestD = 5.5;
			for (Placed h : HOSTS) {
				double d = p.position().distanceTo(h.at());
				if (d < bestD) {
					bestD = d;
					best = h;
				}
			}
			if (best == null) {
				NEAR.remove(p.getUUID());
			} else if (!best.host().id().equals(NEAR.put(p.getUUID(), best.host().id())) && !p.isPassenger()) {
				p.sendOverlayMessage(Component.literal(best.host().hint()).withStyle(s -> s.withColor(TextColor.fromRgb(0xFFFFFF))));
			}
		}
	}
}
