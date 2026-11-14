package dev.rubymod.game.hub;

import static dev.rubymod.game.hub.HubPark.FLOOR;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.phys.Vec3;

/**
 * Hub pets: Penny on the plaza gives you a puppy, a kitten or a parrot that follows you round the park. Pets only
 * live in the hub (they wait for you while you're off playing, and come back when you do, until the server restarts).
 */
final class Pets {
	private Pets() {
	}

	private static final Map<UUID, String> CHOICE = new HashMap<>();
	private static final Map<UUID, UUID> PET = new HashMap<>();

	static void build(ServerLevel level) {
		PET.clear();
		HubHosts.add(level, new Vec3(8.5, FLOOR + 1, 11.5), 180, VillagerProfession.SHEPHERD, VillagerType.TAIGA,
				new HubHosts.Host("pets", "Penny", "Pet Friends", 0xFFB070, "minecraft:bone",
						"Would you like a pet to follow you round the park? Pick one! (They stay in the hub and wait for you when you go off to play.)",
						"Pets: right-click Penny for a pet friend!",
						p -> List.of("wolf|A puppy", "cat|A kitten", "parrot|A parrot", "none|No pet, thanks"), Pets::choose));
	}

	private static void choose(ServerPlayer p, String key) {
		ServerLevel level = (ServerLevel) p.level();
		remove(level, p.getUUID());
		if ("none".equals(key)) {
			CHOICE.remove(p.getUUID());
			p.sendOverlayMessage(Component.literal("Your pet's gone for a nap.").withStyle(ChatFormatting.GRAY));
			return;
		}
		CHOICE.put(p.getUUID(), key);
		spawn(level, p);
	}

	private static void spawn(ServerLevel level, ServerPlayer p) {
		String kind = CHOICE.get(p.getUUID());
		if (kind == null) {
			return;
		}
		EntityType<? extends TamableAnimal> type = switch (kind) {
			case "cat" -> EntityTypes.CAT;
			case "parrot" -> EntityTypes.PARROT;
			default -> EntityTypes.WOLF;
		};
		TamableAnimal pet = type.create(level, EntitySpawnReason.EVENT);
		if (pet == null) {
			return;
		}
		Vec3 at = p.position().add(p.getLookAngle().multiply(1, 0, 1).normalize().scale(1.5));
		pet.snapTo(at.x, p.getY(), at.z, p.getYRot() + 180, 0);
		pet.tame(p);
		pet.setPermanentlyInvulnerable(true);
		pet.setPersistenceRequired();
		String name = switch (kind) {
			case "cat" -> "Kitten";
			case "parrot" -> "Parrot";
			default -> "Puppy";
		};
		pet.setCustomName(Component.literal(p.getName().getString() + "'s " + name).withStyle(ChatFormatting.YELLOW));
		pet.addTag(HubRides.TEMP);
		HubPark.live(pet);
		level.addFreshEntity(pet);
		PET.put(p.getUUID(), pet.getUUID());
		p.sendOverlayMessage(Component.literal("Say hello to your " + name.toLowerCase() + "! It'll follow you round the park.").withStyle(ChatFormatting.YELLOW));
	}

	private static void remove(ServerLevel level, UUID owner) {
		UUID id = PET.remove(owner);
		if (id != null) {
			HubPark.discard(level, id);
		}
	}

	/** Back in the hub: your pet comes too. */
	static void welcome(ServerPlayer p) {
		if (p.level() instanceof ServerLevel level && CHOICE.containsKey(p.getUUID()) && !PET.containsKey(p.getUUID())) {
			spawn(level, p);
		}
	}

	static void tick(ServerLevel level, int tick) {
		if (tick % 20 != 0) {
			return;
		}
		for (var e : new HashMap<>(PET).entrySet()) {
			ServerPlayer owner = level.getServer().getPlayerList().getPlayer(e.getKey());
			Entity pet = level.getEntity(e.getValue());
			if (owner == null || owner.level() != level) {
				remove(level, e.getKey());
			} else if (pet == null && level.isPositionEntityTicking(owner.blockPosition())) {
				PET.remove(e.getKey());
				spawn(level, owner);
			} else if (pet != null && pet.distanceTo(owner) > 40) {
				pet.teleportTo(owner.getX(), owner.getY(), owner.getZ());
			}
		}
		for (ServerPlayer p : level.players()) {
			if (CHOICE.containsKey(p.getUUID()) && !PET.containsKey(p.getUUID())) {
				spawn(level, p);
			}
		}
	}
}
