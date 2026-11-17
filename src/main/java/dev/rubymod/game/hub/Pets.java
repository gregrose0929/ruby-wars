package dev.rubymod.game.hub;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.rubymod.game.Alerts;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

/**
 * Hub pets that grow up with you. Penny gives you a puppy, a kitten, a parrot or a pony: it starts out little and grows
 * up as you look after it (treats, and tricks: right-click your pet). Pick it a name and a hat, or keep it little
 * forever. It follows you round the park, waits while you're off playing, and is never hungry or sad. Kept with the world.
 */
final class Pets {
	private Pets() {
	}

	private static final String[] NAMES = {"Biscuit", "Pip", "Luna", "Max", "Coco", "Bella", "Teddy", "Daisy", "Peanut", "Ziggy", "Willow", "Pickle",
		"Sunny", "Bubbles", "Rocky", "Mochi"};
	private static final String[] HATS = {"", "hat_party", "hat_crown", "hat_bow", "hat_top", "hat_flower"};
	private static final String[] HAT_NAMES = {"no hat", "a party hat", "a crown", "a bow", "a top hat", "a flower crown"};
	private static final int GROWN_AT = 8;
	private static final String TREATS = "Pet Treats";

	private static final class Pet {
		String kind = "wolf";
		int name = -1;
		int growth;
		boolean little;
		int hat;
	}

	private static final Map<String, Pet> PETS = new HashMap<>();
	private static final Map<UUID, UUID> SPAWNED = new HashMap<>();
	private static final Map<UUID, UUID> HAT = new HashMap<>();
	private static final Map<UUID, Integer> SPIN = new HashMap<>();
	private static final Map<UUID, Long> LAST_TRICK = new HashMap<>();
	private static boolean loaded;
	private static final java.util.Set<UUID> ON_BED = new java.util.HashSet<>();

	static void build(ServerLevel level) {
		SPAWNED.clear();
		HAT.clear();
		load(level);
		HubHosts.add(level, new Vec3(8.5, HubPark.FLOOR + 1, 11.5), 180, VillagerProfession.SHEPHERD, VillagerType.TAIGA,
				new HubHosts.Host("pets", "Penny", "Pet Friends", 0xFFB070, "minecraft:bone",
						"Would you like a pet? It starts out little and grows up as you look after it: give it treats, and right-click it "
								+ "for a trick! Pick a name and a hat, or keep it little forever. It follows you round the park.",
						"Pets: right-click Penny for a pet friend!", p -> {
							Pet pet = PETS.get(p.getName().getString());
							List<String> b = new ArrayList<>();
							if (pet == null) {
								b.add("wolf|A puppy");
								b.add("cat|A kitten");
								b.add("parrot|A parrot");
								b.add("pony|A pony (you can ride it!)");
							} else {
								b.add("treats|Some pet treats");
								b.add("name|A new name (now: " + petName(p, pet) + ")");
								b.add("hat|A different hat");
								b.add("little|" + (pet.little ? "Let it grow up" : "Keep it little forever"));
								b.add("none|Pet goes for a nap");
							}
							return b;
						}, Pets::choose));
	}

	private static String petName(ServerPlayer p, Pet pet) {
		if (pet.name >= 0) {
			return NAMES[pet.name];
		}
		String what = switch (pet.kind) {
			case "cat" -> "Kitten";
			case "parrot" -> "Parrot";
			case "pony" -> "Pony";
			default -> "Puppy";
		};
		return p.getName().getString() + "'s " + what;
	}

	private static void choose(ServerPlayer p, String key) {
		ServerLevel level = (ServerLevel) p.level();
		String who = p.getName().getString();
		Pet pet = PETS.get(who);
		switch (key) {
			case "none" -> {
				remove(level, p.getUUID());
				PETS.remove(who);
				save(level);
				p.sendOverlayMessage(Component.literal("Your pet's gone for a nap. Come back to Penny any time.").withStyle(ChatFormatting.GRAY));
			}
			case "treats" -> {
				HubGames.hand(p, HubGames.toy(Items.BONE, 8, TREATS, ChatFormatting.GOLD));
				p.sendOverlayMessage(Component.literal("Right-click your pet with a treat!").withStyle(ChatFormatting.GOLD));
			}
			case "name" -> {
				if (pet != null) {
					pet.name = (pet.name + 1) % NAMES.length;
					save(level);
					respawn(level, p);
					p.sendOverlayMessage(Component.literal("Your pet is now called " + NAMES[pet.name] + "! (Ask again for another name.)").withStyle(ChatFormatting.YELLOW));
				}
			}
			case "hat" -> {
				if (pet != null) {
					pet.hat = (pet.hat + 1) % HATS.length;
					save(level);
					respawn(level, p);
					p.sendOverlayMessage(Component.literal("Your pet's wearing " + HAT_NAMES[pet.hat] + "!").withStyle(ChatFormatting.YELLOW));
				}
			}
			case "little" -> {
				if (pet != null) {
					pet.little = !pet.little;
					save(level);
					respawn(level, p);
					p.sendOverlayMessage(Component.literal(pet.little ? "A golden flower: your pet stays little forever." : "Your pet can grow up now.")
							.withStyle(ChatFormatting.YELLOW));
				}
			}
			default -> {
				Pet fresh = new Pet();
				fresh.kind = key;
				PETS.put(who, fresh);
				save(level);
				respawn(level, p);
				p.sendOverlayMessage(Component.literal("Say hello to your new friend! Give it treats and right-click it for tricks, and it'll grow up.")
						.withStyle(ChatFormatting.YELLOW));
			}
		}
	}

	private static void respawn(ServerLevel level, ServerPlayer p) {
		remove(level, p.getUUID());
		spawn(level, p);
	}

	private static boolean grown(Pet pet) {
		return pet.growth >= GROWN_AT && !pet.little;
	}

	private static void spawn(ServerLevel level, ServerPlayer p) {
		Pet pet = PETS.get(p.getName().getString());
		if (pet == null) {
			return;
		}
		EntityType<? extends Mob> type = switch (pet.kind) {
			case "cat" -> EntityTypes.CAT;
			case "parrot" -> EntityTypes.PARROT;
			case "pony" -> EntityTypes.HORSE;
			default -> EntityTypes.WOLF;
		};
		Mob m = type.create(level, EntitySpawnReason.EVENT);
		if (m == null) {
			return;
		}
		Vec3 at = p.position().add(p.getLookAngle().multiply(1, 0, 1).normalize().scale(1.5));
		m.snapTo(at.x, p.getY(), at.z, p.getYRot() + 180, 0);
		if (m instanceof TamableAnimal t) {
			t.tame(p);
		}
		if (m instanceof net.minecraft.world.entity.animal.equine.AbstractHorse h) {
			h.tameWithName(p);
			if (grown(pet)) {
				h.setItemSlot(net.minecraft.world.entity.EquipmentSlot.SADDLE, new ItemStack(Items.SADDLE));
			}
		}
		if (m instanceof AgeableMob a && !grown(pet)) {
			a.setAge(-24000);
		}
		m.setPermanentlyInvulnerable(true);
		m.setPersistenceRequired();
		m.setCustomName(Component.literal(petName(p, pet)).withStyle(ChatFormatting.YELLOW));
		m.addTag(HubRides.TEMP);
		HubPark.live(m);
		level.addFreshEntity(m);
		SPAWNED.put(p.getUUID(), m.getUUID());
		if (pet.hat > 0) {
			Display.ItemDisplay hat = HubPark.tempModel(level, m.position(), 0, HATS[pet.hat], 0.6F, 0.1F);
			if (hat != null) {
				hat.startRiding(m, true, true);
				HAT.put(p.getUUID(), hat.getUUID());
			}
		}
	}

	private static void remove(ServerLevel level, UUID owner) {
		UUID id = SPAWNED.remove(owner);
		if (id != null) {
			HubPark.discard(level, id);
		}
		UUID hat = HAT.remove(owner);
		if (hat != null) {
			HubPark.discard(level, hat);
		}
	}

	/** Right-clicking your own pet: a treat (if you're holding one), or a trick. True if it was your pet. */
	static boolean clicked(ServerPlayer p, Entity e) {
		UUID mine = SPAWNED.get(p.getUUID());
		if (mine == null || !mine.equals(e.getUUID())) {
			return false;
		}
		ServerLevel level = (ServerLevel) p.level();
		Pet pet = PETS.get(p.getName().getString());
		if (pet == null) {
			return false;
		}
		ItemStack held = p.getMainHandItem();
		boolean treat = HubGames.isToy(held) && held.getHoverName().getString().equals(TREATS);
		if (treat) {
			held.shrink(1);
			level.sendParticles(ParticleTypes.HEART, e.getX(), e.getY() + e.getBbHeight() + 0.3, e.getZ(), 5, 0.3, 0.2, 0.3, 0.02);
			level.playSound(null, e.blockPosition(), SoundEvents.GENERIC_EAT.value(), SoundSource.NEUTRAL, 0.8F, 1.3F);
			grow(level, p, pet, 1, "Yum!");
			return true;
		}
		if (pet.kind.equals("pony") && grown(pet)) {
			return false; // a grown pony: right-click to ride
		}
		long now = level.getGameTime();
		if (now - LAST_TRICK.getOrDefault(p.getUUID(), 0L) < 30) {
			return true;
		}
		LAST_TRICK.put(p.getUUID(), now);
		int trick = level.getRandom().nextInt(3);
		String what;
		if (trick == 0) {
			SPIN.put(e.getUUID(), 20);
			what = "spins round!";
		} else if (trick == 1) {
			e.setDeltaMovement(e.getDeltaMovement().add(0, 0.55, 0));
			what = "jumps for joy!";
		} else if (e instanceof TamableAnimal t) {
			t.setOrderedToSit(!t.isOrderedToSit());
			what = t.isOrderedToSit() ? "sits nicely! (Right-click again for another trick.)" : "gets up!";
		} else {
			SPIN.put(e.getUUID(), 20);
			what = "does a twirl!";
		}
		level.sendParticles(ParticleTypes.HAPPY_VILLAGER, e.getX(), e.getY() + e.getBbHeight(), e.getZ(), 6, 0.3, 0.2, 0.3, 0.02);
		grow(level, p, pet, 0, petName(p, pet) + " " + what);
		return true;
	}

	/** Looking after it helps it grow (treats count; every few tricks count too). */
	private static void grow(ServerLevel level, ServerPlayer p, Pet pet, int amount, String message) {
		boolean was = grown(pet);
		if (amount == 0 && level.getRandom().nextInt(3) == 0) {
			amount = 1;
		}
		pet.growth = Math.min(GROWN_AT, pet.growth + amount);
		save(level);
		if (!was && grown(pet)) {
			Alerts.title(p, petName(p, pet) + " grew up!", "Thanks for looking after it so well", ChatFormatting.GOLD);
			level.playSound(null, p.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0F, 1.3F);
			respawn(level, p);
			if (pet.kind.equals("pony")) {
				p.sendSystemMessage(Component.literal("Your pony's big enough to ride now: right-click it!").withStyle(ChatFormatting.GOLD));
			}
			return;
		}
		String more = pet.little || grown(pet) ? "" : "  (growing: " + pet.growth + "/" + GROWN_AT + ")";
		p.sendOverlayMessage(Component.literal(message + more).withStyle(ChatFormatting.YELLOW));
	}

	/** Back in the hub: your pet comes too. */
	static void welcome(ServerPlayer p) {
		if (p.level() instanceof ServerLevel level && PETS.containsKey(p.getName().getString()) && !SPAWNED.containsKey(p.getUUID())) {
			spawn(level, p);
		}
	}

	static void tick(ServerLevel level, int tick) {
		for (var it = SPIN.entrySet().iterator(); it.hasNext();) {
			var e = it.next();
			Entity m = level.getEntity(e.getKey());
			if (m == null || e.getValue() <= 0) {
				it.remove();
				continue;
			}
			e.setValue(e.getValue() - 1);
			m.setYRot(m.getYRot() + 18);
			if (m instanceof net.minecraft.world.entity.LivingEntity le) {
				le.yBodyRot = m.getYRot();
				le.setYHeadRot(m.getYRot());
			}
		}
		if (tick % 20 != 0) {
			return;
		}
		load(level);
		for (var e : new HashMap<>(SPAWNED).entrySet()) {
			ServerPlayer owner = level.getServer().getPlayerList().getPlayer(e.getKey());
			Entity pet = level.getEntity(e.getValue());
			Pet state = owner == null ? null : PETS.get(owner.getName().getString());
			if (owner == null || owner.level() != level || state == null) {
				remove(level, e.getKey());
			} else if (pet == null && level.isPositionEntityTicking(owner.blockPosition())) {
				remove(level, e.getKey());
				spawn(level, owner);
			} else if (pet != null && pet.distanceTo(owner) > 40) {
				pet.teleportTo(owner.getX(), owner.getY(), owner.getZ());
			} else if (pet instanceof Mob m && !(pet instanceof TamableAnimal) && !m.isVehicle() && m.distanceTo(owner) > 5) {
				m.getNavigation().moveTo(owner, 1.4);
			}
			// Little ones stay little until they've grown (and forever, with the golden flower).
			if (pet instanceof AgeableMob a && state != null && !grown(state) && tick % 600 == 0) {
				a.setAge(-24000);
			}
		}
		for (ServerPlayer p : level.players()) {
			if (PETS.containsKey(p.getName().getString()) && !SPAWNED.containsKey(p.getUUID())) {
				spawn(level, p);
			}
		}
	}

	private static Path file(ServerLevel level) {
		return level.getServer().getWorldPath(LevelResource.ROOT).resolve("rubywars").resolve("pets.txt");
	}

	private static void load(ServerLevel level) {
		if (loaded) {
			return;
		}
		loaded = true;
		try {
			Path f = file(level);
			if (Files.exists(f)) {
				for (String line : Files.readAllLines(f)) {
					String[] s = line.split("\\|");
					if (s.length >= 6) {
						Pet pet = new Pet();
						pet.kind = s[1];
						pet.name = Integer.parseInt(s[2]);
						pet.growth = Integer.parseInt(s[3]);
						pet.little = Boolean.parseBoolean(s[4]);
						pet.hat = Math.floorMod(Integer.parseInt(s[5]), HATS.length);
						PETS.put(s[0], pet);
					}
				}
			}
		} catch (Exception e) {
			dev.rubymod.RubyMod.LOGGER.warn("Couldn't read the pets", e);
		}
	}

	private static void save(ServerLevel level) {
		StringBuilder b = new StringBuilder();
		for (var e : PETS.entrySet()) {
			Pet p = e.getValue();
			b.append(e.getKey()).append('|').append(p.kind).append('|').append(p.name).append('|').append(p.growth).append('|').append(p.little).append('|')
					.append(p.hat).append('\n');
		}
		try {
			Files.createDirectories(file(level).getParent());
			dev.rubymod.game.SafeFiles.write(file(level), b.toString());
		} catch (Exception e) {
			dev.rubymod.RubyMod.LOGGER.warn("Couldn't save the pets", e);
		}
	}

	/** Someone's changed their name: their pet comes with them. */
	static void rename(ServerLevel level, String before, String now) {
		load(level);
		Pet pet = PETS.remove(before);
		if (pet != null && !PETS.containsKey(now)) {
			PETS.put(now, pet);
		}
		save(level);
	}
}
