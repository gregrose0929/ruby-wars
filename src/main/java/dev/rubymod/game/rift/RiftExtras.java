package dev.rubymod.game.rift;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.rubymod.game.Stats;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/**
 * Two things picked at the Altar of Echoes (its EXTRAS page), just for fun:
 * <ul>
 * <li>Mutators, for the whole team: Low Gravity, Tiny Heroes, Bouncy, Swarm (tiny monsters,
 * lots of them), Explosive Kills, Party Mode.</li>
 * <li>A companion each: a Ruby Hound, a Spark Fox, Pebble the mini golem or Buzz the drone.
 * It comes into every room, and levels up as you rack up kills (new tricks at 3 and 5).</li>
 * </ul>
 */
public final class RiftExtras {
	public enum Mutator {
		LOW_GRAVITY("Low Gravity", "minecraft:feather", "Everyone floats: big jumps, slow falls (monsters too)."),
		TINY_HEROES("Tiny Heroes", "minecraft:rabbit_foot", "You're tiny! Small and quick."),
		BOUNCY("Bouncy", "minecraft:slime_ball", "Every hit sends monsters flying."),
		SWARM("Swarm", "minecraft:spider_eye", "Tiny monsters with half the health, but nearly twice as many."),
		EXPLOSIVE_KILLS("Explosive Kills", "minecraft:tnt", "Every monster you kill explodes."),
		PARTY("Party Mode", "minecraft:firework_rocket", "Kills burst into confetti and music.");

		public final String title;
		public final String icon;
		public final String about;

		Mutator(String title, String icon, String about) {
			this.title = title;
			this.icon = icon;
			this.about = about;
		}
	}

	public enum Companion {
		NONE("No Companion", "minecraft:barrier", "Go it alone."),
		HOUND("Ruby Hound", "minecraft:bone", "A loyal wolf. Level 3: its bites burn. Level 5: its howl speeds up the team."),
		FOX("Spark Fox", "minecraft:sweet_berries", "A quick fox. Level 3: its bites throw lightning. Level 5: it blinks onto its prey."),
		PEBBLE("Pebble", "minecraft:iron_block", "A little golem. Level 3: it tosses monsters in the air. Level 5: it grows big."),
		BUZZ("Buzz", "minecraft:amethyst_shard", "A flying drone that zaps from the air. Level 3: two zaps. Level 5: lightning.");

		public final String title;
		public final String icon;
		public final String about;

		Companion(String title, String icon, String about) {
			this.title = title;
			this.icon = icon;
			this.about = about;
		}

		public String id() {
			return name().toLowerCase();
		}

		static Companion byId(String id) {
			for (Companion c : values()) {
				if (c.id().equals(id)) {
					return c;
				}
			}
			return NONE;
		}
	}

	/** The team's mutators (chosen in the Threshold, kept between runs while the server's up). */
	static final Set<Mutator> MUTATORS = EnumSet.noneOf(Mutator.class);
	/** Each runner's companion in this room, and its level. */
	private static final Map<UUID, UUID> PETS = new HashMap<>();
	private static final Map<UUID, Integer> PET_LEVELS = new HashMap<>();

	private RiftExtras() {
	}

	static boolean on(Mutator m) {
		return MUTATORS.contains(m);
	}

	static Companion companion(ServerPlayer p) {
		String id = Stats.get(p).riftCompanion;
		return Companion.byId(id == null ? "none" : id);
	}

	/** Altar lines: "mutator|id|title|icon|about|on/off" and "companion|id|title|icon|about|selected/owned". */
	static void lines(ServerPlayer p, List<String> lines) {
		for (Mutator m : Mutator.values()) {
			lines.add("mutator|" + m.name().toLowerCase() + "|" + m.title + "|" + m.icon + "|" + m.about + "|" + (on(m) ? "on" : "off"));
		}
		Companion mine = companion(p);
		for (Companion c : Companion.values()) {
			lines.add("companion|" + c.id() + "|" + c.title + "|" + c.icon + "|" + c.about + "|" + (c == mine ? "selected" : "owned"));
		}
	}

	static void toggleMutator(ServerPlayer p, String id) {
		for (Mutator m : Mutator.values()) {
			if (m.name().toLowerCase().equals(id)) {
				boolean now = !MUTATORS.remove(m);
				if (now) {
					MUTATORS.add(m);
				}
				Rift.tell(p.level().getServer(), Component.literal(p.getName().getString() + " turned " + m.title + (now ? " ON" : " off") + ".")
						.withStyle(now ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.GRAY));
			}
		}
	}

	static void pickCompanion(ServerPlayer p, String id) {
		Stats.get(p).riftCompanion = Companion.byId(id).id();
		Stats.markDirty();
		p.level().playSound(null, p.blockPosition(), SoundEvents.FOX_AMBIENT, SoundSource.PLAYERS, 0.8F, 1.3F);
	}

	// ----- Mutators -----

	/** Each room: what the mutators do to you. */
	static void onRoomStart(ServerPlayer p) {
		if (on(Mutator.LOW_GRAVITY)) {
			p.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 20 * 600, 0, false, false));
			p.addEffect(new MobEffectInstance(MobEffects.JUMP_BOOST, 20 * 600, 2, false, false));
		}
	}

	/** A monster just came through the Rift. */
	static void onSpawn(Mob mob) {
		if (RiftBoss.isBoss(mob)) {
			return;
		}
		if (on(Mutator.SWARM)) {
			AttributeInstance s = mob.getAttribute(Attributes.SCALE);
			if (s != null) {
				s.setBaseValue(s.getBaseValue() * 0.6);
			}
			AttributeInstance hp = mob.getAttribute(Attributes.MAX_HEALTH);
			if (hp != null) {
				hp.setBaseValue(Math.max(2, hp.getBaseValue() * 0.5));
				mob.setHealth(mob.getMaxHealth());
			}
		}
		if (on(Mutator.LOW_GRAVITY)) {
			mob.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, -1, 0, false, false));
		}
	}

	static void onHit(ServerPlayer p, LivingEntity target) {
		if (on(Mutator.BOUNCY) && !RiftBoss.isBoss(target)) {
			Vec3 away = target.position().subtract(p.position()).multiply(1, 0, 1).normalize();
			target.setDeltaMovement(away.x * 0.8, 0.9, away.z * 0.8);
			target.needsSync = true;
		}
	}

	static void onKill(ServerLevel level, ServerPlayer p, LivingEntity victim) {
		Vec3 at = victim.getBoundingBox().getCenter();
		if (on(Mutator.EXPLOSIVE_KILLS)) {
			RelicEffects.blast(level, p, at, 3.0, 6, 0xFFFFD040, false);
		}
		if (on(Mutator.PARTY)) {
			int[] colors = {0xFF3D6E, 0xFFC02E, 0x66E0FF, 0x6EE38A, 0xC27BFF};
			for (int c : colors) {
				level.sendParticles(RiftFx.dust(c, 1.2F), at.x, at.y, at.z, 6, 0.5, 0.5, 0.5, 0);
			}
			level.sendParticles(ParticleTypes.NOTE, at.x, at.y + 1, at.z, 3, 0.4, 0.3, 0.4, 1);
			level.playSound(null, victim.blockPosition(), SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 0.8F,
					0.5F + level.getRandom().nextFloat() * 1.5F);
		}
	}

	/** Extra monsters in a Swarm. */
	static double budgetScale() {
		return on(Mutator.SWARM) ? 1.8 : 1.0;
	}

	/** Size change for you (Tiny Heroes). */
	static double scaleBonus() {
		return on(Mutator.TINY_HEROES) ? -0.45 : 0;
	}

	// ----- Companions -----

	static int level(Rift.Runner r) {
		return Math.min(5, 1 + r.kills / 20);
	}

	/** Each room: your companion comes through with you. */
	static void bringCompanions(ServerLevel level, List<ServerPlayer> players) {
		for (ServerPlayer p : players) {
			Companion c = companion(p);
			Rift.Runner r = Rift.runner(p);
			if (c == Companion.NONE || r == null) {
				continue;
			}
			int lv = level(r);
			Mob pet = create(level, c, lv);
			if (pet == null) {
				continue;
			}
			pet.snapTo(p.getX() + 1.5, p.getY(), p.getZ() - 1.5, p.getYRot(), 0);
			AttributeInstance hp = pet.getAttribute(Attributes.MAX_HEALTH);
			if (hp != null) {
				hp.setBaseValue(30 + 15 * lv);
				pet.setHealth(pet.getMaxHealth());
			}
			AttributeInstance atk = pet.getAttribute(Attributes.ATTACK_DAMAGE);
			if (atk != null) {
				atk.setBaseValue(4 + 2 * lv);
			}
			pet.setCustomName(Component.literal(p.getName().getString() + "'s " + c.title + "  Lv " + lv).withStyle(ChatFormatting.AQUA));
			pet.setPersistenceRequired();
			pet.addTag(Rift.TAG);
			level.addFreshEntity(pet);
			RelicEffects.ALLIES.put(pet.getUUID(), new RelicEffects.Ally(Long.MAX_VALUE, p.getUUID(), false));
			PETS.put(p.getUUID(), pet.getUUID());
			PET_LEVELS.putIfAbsent(p.getUUID(), lv);
			level.sendParticles(ParticleTypes.HEART, pet.getX(), pet.getY() + 1, pet.getZ(), 4, 0.3, 0.3, 0.3, 0);
		}
	}

	private static Mob create(ServerLevel level, Companion c, int lv) {
		EntityType<? extends Mob> type = switch (c) {
			case HOUND -> EntityTypes.WOLF;
			case FOX -> EntityTypes.FOX;
			case PEBBLE -> EntityTypes.IRON_GOLEM;
			case BUZZ -> EntityTypes.ALLAY;
			default -> null;
		};
		Mob pet = type == null ? null : type.create(level, EntitySpawnReason.EVENT);
		if (pet != null && c == Companion.PEBBLE) {
			AttributeInstance s = pet.getAttribute(Attributes.SCALE);
			if (s != null) {
				s.setBaseValue(lv >= 5 ? 0.85 : 0.5);
			}
		}
		return pet;
	}

	/** Every tick: companions stay close, pick fights, use their tricks, and level up. */
	static void tick(ServerLevel level) {
		long now = level.getGameTime();
		for (Map.Entry<UUID, UUID> e : new ArrayList<>(PETS.entrySet())) {
			ServerPlayer owner = level.getServer().getPlayerList().getPlayer(e.getKey());
			Entity ent = level.getEntity(e.getValue());
			Rift.Runner r = owner == null ? null : Rift.runner(owner);
			if (owner == null || r == null || !(ent instanceof Mob pet) || !pet.isAlive()) {
				continue;
			}
			Companion c = companion(owner);
			int lv = level(r);
			// Level up!
			if (lv > PET_LEVELS.getOrDefault(owner.getUUID(), 1)) {
				PET_LEVELS.put(owner.getUUID(), lv);
				pet.setCustomName(Component.literal(owner.getName().getString() + "'s " + c.title + "  Lv " + lv).withStyle(ChatFormatting.AQUA));
				AttributeInstance atk = pet.getAttribute(Attributes.ATTACK_DAMAGE);
				if (atk != null) {
					atk.setBaseValue(4 + 2 * lv);
				}
				RiftFx.beam(level, pet.position(), 8, ParticleTypes.END_ROD, 0xFF66E0FF);
				level.playSound(null, pet.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8F, 1.4F);
				owner.sendSystemMessage(Component.literal("✦ Your " + c.title + " reached level " + lv + "!" + (lv == 3 || lv == 5 ? " New trick: "
						+ c.about.substring(c.about.indexOf("Level " + lv) + 9, c.about.indexOf('.', c.about.indexOf("Level " + lv)) + 1) : ""))
						.withStyle(ChatFormatting.AQUA));
			}
			// Never far behind.
			if (pet.distanceTo(owner) > 14) {
				pet.teleportTo(owner.getX() + 1, owner.getY(), owner.getZ() + 1);
			}
			LivingEntity target = RelicEffects.nearestEnemy(level, pet.position(), 16, null, true);
			if (c == Companion.BUZZ) {
				// Buzz hovers by your shoulder and zaps.
				Vec3 want = owner.position().add(Math.cos(now * 0.06) * 1.6, 2.4, Math.sin(now * 0.06) * 1.6);
				pet.setDeltaMovement(want.subtract(pet.position()).scale(0.3));
				pet.needsSync = true;
				if (target != null && now % (lv >= 3 ? 12 : 20) == 0) {
					RiftFx.line(level, pet.position(), target.getBoundingBox().getCenter(), RiftFx.dust(0xC27BFF, 0.8F), 0.4);
					RelicEffects.hurt(owner, target, 2 + lv * 1.2F, 0xFFC27BFF);
					if (lv >= 5) {
						RiftFx.arc(level, pet.position(), target.getBoundingBox().getCenter(), 0xFF66E0FF);
						RelicCombos.shocked(level, owner, target, false);
					}
					level.playSound(null, pet.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.5F, 1.8F);
				}
				continue;
			}
			if (target != null && now % 10 == 0) {
				pet.setTarget(target);
			}
			// Tricks, as the bites land (when its target's right beside it).
			if (target != null && pet.distanceTo(target) < 2.2 && now % 20 == 0) {
				switch (c) {
					case HOUND -> {
						if (lv >= 3) {
							target.igniteForSeconds(4);
						}
						if (lv >= 5 && now % 200 == 0) {
							level.playSound(null, pet.blockPosition(), SoundEvents.FOX_SCREECH, SoundSource.PLAYERS, 1.2F, 0.7F);
							for (ServerPlayer mate : Rift.players(level.getServer())) {
								mate.addEffect(new MobEffectInstance(MobEffects.SPEED, 120, 1, false, true));
							}
						}
					}
					case FOX -> {
						if (lv >= 3) {
							LivingEntity next = RelicEffects.nearestEnemy(level, target.position(), 7, target, false);
							if (next != null) {
								RiftFx.arc(level, target.getBoundingBox().getCenter(), next.getBoundingBox().getCenter(), 0xFF66E0FF);
								RelicEffects.hurt(owner, next, 3 + lv, 0xFF66E0FF);
							}
						}
					}
					case PEBBLE -> {
						if (lv >= 3 && !RiftBoss.isBoss(target)) {
							target.setDeltaMovement(0, 0.9, 0);
							target.needsSync = true;
						}
					}
					default -> {
					}
				}
			}
			// The Spark Fox (level 5) blinks straight onto its prey.
			if (c == Companion.FOX && lv >= 5 && target != null && pet.distanceTo(target) > 5 && now % 60 == 0) {
				level.sendParticles(ParticleTypes.ELECTRIC_SPARK, pet.getX(), pet.getY() + 0.5, pet.getZ(), 12, 0.3, 0.3, 0.3, 0.2);
				pet.teleportTo(target.getX() + 1, target.getY(), target.getZ());
			}
		}
	}

	/** Leaving a room: the companions go (they come back in the next). */
	static void clearPets() {
		PETS.clear();
	}

	/** A new run: everyone's companion starts at level 1 again. */
	static void newRun() {
		PETS.clear();
		PET_LEVELS.clear();
	}
}
