package dev.rubymod.game.rift;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.mojang.math.Transformation;

import dev.rubymod.entity.RocketEntity;
import dev.rubymod.gun.GunItem;
import dev.rubymod.gun.GunStats;
import dev.rubymod.mixin.DisplayAccessor;
import dev.rubymod.mixin.ItemDisplayAccessor;
import dev.rubymod.registry.ModComponents;
import dev.rubymod.registry.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The Rift's exotic weapons (found at the Arsenal). Each fires its own way:
 *
 * <ul>
 * <li>Ruby Minigun: spins up from a stutter to a roar (fire rate climbs the longer you hold).</li>
 * <li>Rail Lance: a heartbeat of charge, then a beam that goes through every enemy in a line.</li>
 * <li>Ruby Chakram: a spinning ruby blade that ricochets between six enemies, then flies home.</li>
 * <li>Dragon's Breath: a roaring cone of flame that sets everything in it alight.</li>
 * <li>Swarm Launcher: six mini missiles that each pick their own target.</li>
 * <li>Tesla Coilgun: a crackling beam that jumps from enemy to enemy.</li>
 * <li>Bouncer: grenades that bounce off walls and burst on anything they touch.</li>
 * <li>Six-Shooter: fan the hammer; headshots ricochet into the next head.</li>
 * <li>Ruby Warhammer: a slam that cracks the ground and throws enemies into the air.</li>
 * <li>Singularity Cannon: a slow orb that drags everything in, then crushes it.</li>
 * </ul>
 */
public final class RiftWeapons {
	private RiftWeapons() {
	}

	/** Weapons fired here rather than as ordinary bullets. */
	public static boolean handles(GunStats.Special s) {
		return switch (s) {
			case RAIL, CHAKRAM, FLAME, SWARM, TESLA, BOUNCER, HAMMER, SINGULARITY -> true;
			default -> false;
		};
	}

	// ----- Ruby Minigun: spin-up -----

	private static final Map<UUID, int[]> SPIN = new HashMap<>();

	/** Ticks until the next minigun shot: 6 when it's just starting, down to 1 at full spin. */
	public static int spinInterval(ServerPlayer p) {
		long now = p.level().getGameTime();
		int[] s = SPIN.computeIfAbsent(p.getUUID(), k -> new int[2]);
		s[0] = now - s[1] <= 8 ? Math.min(30, s[0] + 1) : 0;
		s[1] = (int) now;
		if (s[0] >= 30 && now % 3 == 0 && p.level() instanceof ServerLevel level) {
			// Red-hot barrels at full spin.
			Vec3 m = muzzle(p, 0.9);
			level.sendParticles(ParticleTypes.SMALL_FLAME, m.x, m.y, m.z, 2, 0.05, 0.05, 0.05, 0.01);
			level.sendParticles(ParticleTypes.SMOKE, m.x, m.y, m.z, 1, 0.05, 0.05, 0.05, 0.02);
		}
		if (s[0] % 6 == 1 && p.level() instanceof ServerLevel level) {
			level.playSound(null, p.blockPosition(), SoundEvents.MINECART_RIDING, SoundSource.PLAYERS, 0.25F, 1.2F + s[0] * 0.03F);
		}
		return Math.max(1, 6 - s[0] / 6);
	}

	// ----- Fire -----

	public static void fire(ServerLevel level, ServerPlayer p, ItemStack stack, GunStats stats) {
		switch (stats.special()) {
			case RAIL -> rail(level, p, stats);
			case CHAKRAM -> chakram(level, p, stack, stats);
			case FLAME -> flame(level, p, stats);
			case SWARM -> swarm(level, p, stats);
			case TESLA -> tesla(level, p, stats);
			case BOUNCER -> bouncer(level, p, stats);
			case HAMMER -> hammer(level, p, stats);
			case SINGULARITY -> singularity(level, p, stats);
			default -> {
			}
		}
	}

	private static Vec3 muzzle(ServerPlayer p, double out) {
		Vec3 look = p.getLookAngle();
		Vec3 right = look.cross(new Vec3(0, 1, 0)).normalize();
		return p.getEyePosition().add(look.scale(out)).add(right.scale(0.25)).add(0, -0.2, 0);
	}

	/** A weapon hit: coloured damage number; every few hits it also sets off the shooter's relics and its fused traits. */
	private static void hit(ServerPlayer p, LivingEntity e, float damage, int color, boolean relics) {
		RelicEffects.hurt(p, e, damage, color);
		if (relics && e.isAlive()) {
			RelicEffects.onHit(p, e, damage);
		}
		List<dev.rubymod.gun.Trait> traits = ARMED.getOrDefault(p.getUUID(), List.of());
		if (relics && !traits.isEmpty()) {
			traitHit((ServerLevel) p.level(), p, e, damage, traits, false);
		}
	}

	// ----- Fused traits -----

	/** The traits of each player's weapon (the exotic ones hit later, after the shot). */
	private static final Map<UUID, List<dev.rubymod.gun.Trait>> ARMED = new HashMap<>();
	private static final Map<UUID, Integer> SHOTS = new HashMap<>();

	public static void armed(ServerPlayer p, List<dev.rubymod.gun.Trait> traits) {
		if (traits.isEmpty()) {
			ARMED.remove(p.getUUID());
		} else {
			ARMED.put(p.getUUID(), traits);
		}
	}

	/** Homing: bend a shot toward the nearest enemy close to where it's going. */
	public static Vec3 homing(ServerLevel level, ServerPlayer p, Vec3 eye, Vec3 dir) {
		LivingEntity best = null;
		double bestDot = Math.cos(Math.toRadians(14));
		for (LivingEntity e : enemies(level, eye.add(dir.scale(16)), 18, null)) {
			Vec3 to = e.getBoundingBox().getCenter().subtract(eye).normalize();
			double d = to.dot(dir);
			if (d > bestDot) {
				bestDot = d;
				best = e;
			}
		}
		return best == null ? dir : best.getBoundingBox().getCenter().subtract(eye).normalize();
	}

	/** The shot traits that add something to the shot itself: Slam, Rail, Grenadier. */
	public static void traitShot(ServerLevel level, ServerPlayer p, GunStats stats, List<dev.rubymod.gun.Trait> traits) {
		int n = SHOTS.merge(p.getUUID(), 1, Integer::sum);
		if (traits.contains(dev.rubymod.gun.Trait.SLAM)) {
			Vec3 look = p.getLookAngle().multiply(1, 0, 1).normalize();
			Vec3 at = p.position().add(look.scale(2.0));
			RiftFx.ring(level, at.add(0, 0.2, 0), 2.2, RiftFx.dust(0xFF3D6E, 1.2F), 16);
			for (LivingEntity e : enemies(level, at, 2.8, null)) {
				RelicEffects.hurt(p, e, Math.max(3, stats.damage() * stats.pellets() * 0.6F), 0xFFFF3D6E);
				e.setDeltaMovement(look.x * 0.5, 0.4, look.z * 0.5);
			}
			if (n % 3 == 0) {
				level.playSound(null, p.blockPosition(), SoundEvents.MACE_SMASH_GROUND, SoundSource.PLAYERS, 0.5F, 1.2F);
			}
		}
		if (traits.contains(dev.rubymod.gun.Trait.RAIL) && n % 5 == 0) {
			CHARGES.add(new Charge(p.getUUID(), level.getGameTime() + 4, Math.max(14, stats.damage() * stats.pellets() * 3), 90));
		}
		if (traits.contains(dev.rubymod.gun.Trait.GRENADE) && n % 4 == 0) {
			bouncer(level, p, stats.scaled(Math.max(1, 12 / Math.max(1, stats.damage()))));
		}
	}

	/** The hit traits: on every hit, from every fused weapon. */
	public static void traitHit(ServerLevel level, ServerPlayer p, LivingEntity target, float damage, List<dev.rubymod.gun.Trait> traits, boolean headshot) {
		if (RelicEffects.inEffect) {
			return;
		}
		Vec3 at = target.getBoundingBox().getCenter();
		for (dev.rubymod.gun.Trait t : traits) {
			switch (t) {
				case IGNITE -> {
					target.igniteForSeconds(4);
					level.sendParticles(ParticleTypes.FLAME, at.x, at.y, at.z, 4, 0.2, 0.3, 0.2, 0.02);
				}
				case BLAST -> RelicEffects.blast(level, p, at, 2.2, Math.max(3, damage * 0.5F), 0xFFFFD040, false);
				case CHAIN -> {
					List<LivingEntity> near = enemies(level, target.position(), 7, target);
					near.sort(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(target)));
					for (int i = 0; i < Math.min(2, near.size()); i++) {
						RiftFx.arc(level, at, near.get(i).getBoundingBox().getCenter(), 0xFF66E0FF);
						RelicEffects.hurt(p, near.get(i), Math.max(2, damage * 0.5F), 0xFF66E0FF);
					}
				}
				case VORTEX -> {
					for (LivingEntity e : enemies(level, target.position(), 5, target)) {
						if (!JuggernautMech.isBoss(e)) {
							Vec3 in = target.position().subtract(e.position()).normalize().scale(0.5);
							e.setDeltaMovement(in.x, 0.15, in.z);
							e.needsSync = true;
						}
					}
					level.sendParticles(ParticleTypes.REVERSE_PORTAL, at.x, at.y, at.z, 12, 0.4, 0.4, 0.4, 0.3);
				}
				case RETURN -> {
					LivingEntity next = null;
					double best = 10 * 10;
					for (LivingEntity e : enemies(level, target.position(), 10, target)) {
						if (e.distanceToSqr(target) < best) {
							best = e.distanceToSqr(target);
							next = e;
						}
					}
					if (next != null) {
						RiftFx.line(level, at, next.getBoundingBox().getCenter(), RiftFx.dust(0xFF3D6E, 1.0F), 0.4);
						RelicEffects.hurt(p, next, damage * 0.7F, 0xFFFF3D6E);
					}
				}
				case FAN -> {
					if (headshot) {
						fanRicochet(level, p, target, damage);
					}
				}
				case SKYHUNTER -> {
					if (!target.onGround() && (target.isNoGravity() || target.getType() == EntityTypes.GHAST
							|| target instanceof dev.rubymod.entity.AircraftEntity || target.getType() == EntityTypes.BLAZE || target.getType() == EntityTypes.VEX
							|| target.getType() == EntityTypes.BREEZE || target.getType() == EntityTypes.PHANTOM)) {
						RelicEffects.hurt(p, target, damage, 0xFF66E0FF);
					}
				}
				case ARMOR -> {
					if (JuggernautMech.isBoss(target) || target instanceof net.minecraft.world.entity.Mob m && RiftEnemies.twistOf(m) != null) {
						RelicEffects.hurt(p, target, damage * 0.6F, 0xFFB8C4D0);
					}
				}
				default -> {
				}
			}
		}
	}

	private static List<LivingEntity> enemies(ServerLevel level, Vec3 at, double r, Entity except) {
		return RelicEffects.enemiesNear(level, at, r, except);
	}

	// ----- Rail Lance -----

	private record Charge(UUID shooter, long when, float damage, double range) {
	}

	private static final List<Charge> CHARGES = new ArrayList<>();

	private static void rail(ServerLevel level, ServerPlayer p, GunStats stats) {
		CHARGES.add(new Charge(p.getUUID(), level.getGameTime() + 10, stats.damage(), stats.range()));
		level.playSound(null, p.blockPosition(), SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 1.0F, 1.8F);
		level.playSound(null, p.blockPosition(), SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.PLAYERS, 0.8F, 1.6F);
	}

	private static void railFire(ServerLevel level, ServerPlayer p, float damage, double range) {
		Vec3 from = p.getEyePosition();
		Vec3 dir = p.getLookAngle();
		BlockHitResult wall = level.clip(new ClipContext(from, from.add(dir.scale(range)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
		Vec3 to = wall.getType() == HitResult.Type.MISS ? from.add(dir.scale(range)) : wall.getLocation();
		Vec3 start = muzzle(p, 1.0);
		// The beam: a white-hot core in a cyan sheath, sparks all along.
		double len = start.distanceTo(to);
		for (double d = 0; d < len; d += 0.35) {
			Vec3 at = start.add(to.subtract(start).normalize().scale(d));
			level.sendParticles(ParticleTypes.END_ROD, true, false, at.x, at.y, at.z, 1, 0.02, 0.02, 0.02, 0);
			level.sendParticles(RiftFx.dust(0x66E0FF, 1.6F), true, false, at.x, at.y, at.z, 1, 0.08, 0.08, 0.08, 0);
			if (d % 2 < 0.35) {
				level.sendParticles(ParticleTypes.ELECTRIC_SPARK, true, false, at.x, at.y, at.z, 2, 0.15, 0.15, 0.15, 0.1);
			}
		}
		level.sendParticles(ParticleTypes.EXPLOSION, to.x, to.y, to.z, 1, 0, 0, 0, 0);
		level.playSound(null, p.blockPosition(), SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 1.2F, 1.5F);
		level.playSound(null, p.blockPosition(), SoundEvents.TRIDENT_THUNDER.value(), SoundSource.PLAYERS, 0.6F, 1.8F);
		RiftFx.shake(p, 0.8F, 6);
		// Through everything in its path.
		for (LivingEntity e : enemies(level, start.lerp(to, 0.5), len / 2 + 2, null)) {
			Vec3 c = e.getBoundingBox().getCenter();
			Vec3 ab = to.subtract(start);
			double t = Math.max(0, Math.min(1, c.subtract(start).dot(ab) / Math.max(0.01, ab.lengthSqr())));
			if (start.add(ab.scale(t)).distanceTo(c) < e.getBbWidth() / 2 + 0.8) {
				hit(p, e, damage, 0xFF66E0FF, true);
				level.sendParticles(ParticleTypes.CRIT, c.x, c.y, c.z, 20, 0.3, 0.3, 0.3, 0.4);
			}
		}
	}

	// ----- Ruby Chakram -----

	private static final class Blade {
		final UUID owner;
		final UUID display;
		Vec3 pos;
		Vec3 vel;
		final Set<UUID> hit = new HashSet<>();
		LivingEntity target;
		boolean home;
		int age;
		final float damage;

		Blade(UUID owner, UUID display, Vec3 pos, Vec3 vel, float damage) {
			this.owner = owner;
			this.display = display;
			this.pos = pos;
			this.vel = vel;
			this.damage = damage;
		}
	}

	private static final List<Blade> BLADES = new ArrayList<>();

	private static void chakram(ServerLevel level, ServerPlayer p, ItemStack stack, GunStats stats) {
		Vec3 from = muzzle(p, 0.8);
		Display.ItemDisplay d = display(level, from, new ItemStack(ModItems.RUBY_CHAKRAM), 1.2F);
		Blade b = new Blade(p.getUUID(), d == null ? null : d.getUUID(), from, p.getLookAngle().scale(1.6), stats.damage());
		b.target = RelicEffects.aimed(level, p, 28);
		BLADES.add(b);
		level.playSound(null, p.blockPosition(), SoundEvents.TRIDENT_THROW.value(), SoundSource.PLAYERS, 1.0F, 1.3F);
		level.playSound(null, p.blockPosition(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 0.8F, 1.6F);
	}

	private static void tickBlade(ServerLevel level, Blade b, Iterator<Blade> it) {
		b.age++;
		ServerPlayer owner = level.getServer().getPlayerList().getPlayer(b.owner);
		if (owner == null || b.age > 200) {
			remove(level, b.display);
			it.remove();
			return;
		}
		Vec3 aim;
		if (b.home) {
			aim = owner.getEyePosition().add(0, -0.4, 0);
			if (b.pos.distanceTo(aim) < 1.6) {
				// Back in your hand.
				remove(level, b.display);
				it.remove();
				for (int i = 0; i < owner.getInventory().getContainerSize(); i++) {
					ItemStack s = owner.getInventory().getItem(i);
					if (s.is(ModItems.RUBY_CHAKRAM)) {
						s.set(ModComponents.AMMO, 1);
					}
				}
				dev.rubymod.gun.Gunfire.forgetReload(owner);
				level.playSound(null, owner.blockPosition(), SoundEvents.ARMOR_EQUIP_IRON.value(), SoundSource.PLAYERS, 0.8F, 1.6F);
				return;
			}
		} else if (b.target != null && b.target.isAlive()) {
			aim = b.target.getBoundingBox().getCenter();
		} else {
			aim = null;
			if (b.age > 12) {
				b.home = true;
			}
		}
		if (aim != null) {
			b.vel = b.vel.lerp(aim.subtract(b.pos).normalize().scale(b.home ? 1.8 : 1.6), 0.35);
		}
		Vec3 next = b.pos.add(b.vel);
		BlockHitResult wall = level.clip(new ClipContext(b.pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, owner));
		if (wall.getType() != HitResult.Type.MISS && !b.home) {
			// Ricochet off the wall.
			Vec3 n = Vec3.atLowerCornerOf(wall.getDirection().getUnitVec3i());
			b.vel = b.vel.subtract(n.scale(2 * b.vel.dot(n)));
			next = wall.getLocation().add(n.scale(0.2));
			level.sendParticles(ParticleTypes.ELECTRIC_SPARK, next.x, next.y, next.z, 8, 0.1, 0.1, 0.1, 0.2);
			level.playSound(null, BlockPos.containing(next), SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.3F, 2.0F);
		}
		b.pos = next;
		if (b.display != null && level.getEntity(b.display) instanceof Display.ItemDisplay d) {
			d.setPos(b.pos);
			((DisplayAccessor) d).rubymod$setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotateX((float) (Math.PI / 2)),
					new Vector3f(1.2F, 1.2F, 1.2F), new Quaternionf().rotateZ(b.age * 0.9F)));
		}
		level.sendParticles(RiftFx.dust(0xFF3D6E, 1.2F), true, false, b.pos.x, b.pos.y, b.pos.z, 2, 0.1, 0.1, 0.1, 0);
		if (b.age % 3 == 0) {
			level.sendParticles(ParticleTypes.SWEEP_ATTACK, true, false, b.pos.x, b.pos.y, b.pos.z, 1, 0, 0, 0, 0);
		}
		if (b.age % 5 == 0) {
			level.playSound(null, BlockPos.containing(b.pos), SoundEvents.TRIDENT_RIPTIDE_1.value(), SoundSource.PLAYERS, 0.25F, 1.8F);
		}
		if (b.home) {
			return;
		}
		for (LivingEntity e : enemies(level, b.pos, 1.3, null)) {
			if (b.hit.add(e.getUUID())) {
				hit(owner, e, b.damage, 0xFFFF3D6E, true);
				level.sendParticles(ParticleTypes.CRIT, e.getX(), e.getY() + 1, e.getZ(), 14, 0.3, 0.4, 0.3, 0.3);
				level.playSound(null, e.blockPosition(), SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.0F, 1.2F);
				if (b.hit.size() >= 6) {
					b.home = true;
				} else {
					// On to the nearest one it hasn't cut yet.
					b.target = null;
					double best = 12 * 12;
					for (LivingEntity o : enemies(level, b.pos, 12, e)) {
						if (!b.hit.contains(o.getUUID()) && o.distanceToSqr(b.pos) < best) {
							best = o.distanceToSqr(b.pos);
							b.target = o;
						}
					}
					if (b.target == null) {
						b.home = true;
					}
				}
				break;
			}
		}
	}

	// ----- Dragon's Breath -----

	private static final Map<UUID, Integer> FLAME_TICKS = new HashMap<>();

	private static void flame(ServerLevel level, ServerPlayer p, GunStats stats) {
		Vec3 from = muzzle(p, 1.0);
		Vec3 look = p.getLookAngle();
		var random = level.getRandom();
		for (int i = 0; i < 14; i++) {
			Vec3 v = look.add((random.nextDouble() - 0.5) * 0.35, (random.nextDouble() - 0.5) * 0.25, (random.nextDouble() - 0.5) * 0.35).normalize()
					.scale(0.45 + random.nextDouble() * 0.35);
			level.sendParticles(i % 4 == 0 ? ParticleTypes.SOUL_FIRE_FLAME : ParticleTypes.FLAME, true, false, from.x, from.y, from.z, 0, v.x, v.y, v.z, 1.0);
		}
		if (random.nextInt(3) == 0) {
			Vec3 v = look.scale(0.3);
			level.sendParticles(ParticleTypes.LARGE_SMOKE, true, false, from.x, from.y, from.z, 0, v.x, v.y + 0.05, v.z, 1.0);
		}
		int n = FLAME_TICKS.merge(p.getUUID(), 1, Integer::sum);
		if (n % 5 == 0) {
			level.playSound(null, p.blockPosition(), SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 0.7F, 0.6F + random.nextFloat() * 0.2F);
		}
		for (LivingEntity e : enemies(level, from.add(look.scale(4)), 5, null)) {
			Vec3 to = e.getBoundingBox().getCenter().subtract(from);
			if (to.length() < stats.range() && to.normalize().dot(look) > 0.85) {
				e.igniteForSeconds(4);
				hit(p, e, stats.damage(), 0xFFFF7A2E, n % 6 == 0);
			}
		}
	}

	// ----- Swarm Launcher -----

	private record Volley(UUID shooter, long when, int index) {
	}

	private static final List<Volley> VOLLEYS = new ArrayList<>();

	private static void swarm(ServerLevel level, ServerPlayer p, GunStats stats) {
		for (int i = 0; i < 6; i++) {
			VOLLEYS.add(new Volley(p.getUUID(), level.getGameTime() + i * 2, i));
		}
	}

	private static void swarmMissile(ServerLevel level, ServerPlayer p, int index) {
		Vec3 look = p.getLookAngle();
		// Each one picks its own target, nearest to where you're aiming first.
		List<LivingEntity> near = enemies(level, p.getEyePosition().add(look.scale(14)), 18, null);
		near.sort(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(p.getEyePosition().add(look.scale(10)))));
		LivingEntity target = near.isEmpty() ? null : near.get(index % near.size());
		double a = index * Math.PI / 3;
		Vec3 right = look.cross(new Vec3(0, 1, 0)).normalize();
		Vec3 up = right.cross(look).normalize();
		Vec3 spread = right.scale(Math.cos(a) * 0.35).add(up.scale(Math.sin(a) * 0.35));
		Vec3 from = muzzle(p, 1.0).add(spread.scale(0.6));
		RocketEntity m = RocketEntity.missile(level, p, p, from, look.add(spread).normalize().scale(1.9), 1.6F, 12.0, target,
				target == null ? p.getEyePosition().add(look.scale(30)) : null);
		level.addFreshEntity(m);
		level.playSound(null, p.blockPosition(), dev.rubymod.registry.ModSounds.ROCKET_LAUNCH, SoundSource.PLAYERS, 0.6F, 1.6F + index * 0.05F);
		level.sendParticles(ParticleTypes.FIREWORK, from.x, from.y, from.z, 4, 0.05, 0.05, 0.05, 0.05);
	}

	// ----- Tesla Coilgun -----

	private static void tesla(ServerLevel level, ServerPlayer p, GunStats stats) {
		Vec3 from = muzzle(p, 1.0);
		LivingEntity first = RelicEffects.aimed(level, p, stats.range());
		if (first == null) {
			// Nothing in the sights: lightning crawls out and fizzles.
			Vec3 to = from.add(p.getLookAngle().scale(6 + level.getRandom().nextDouble() * 4));
			RiftFx.arc(level, from, to, 0xFF66E0FF);
			return;
		}
		RiftFx.arc(level, from, first.getBoundingBox().getCenter(), 0xFF66E0FF);
		hit(p, first, stats.damage(), 0xFF66E0FF, level.getGameTime() % 4 == 0);
		LivingEntity last = first;
		Set<UUID> struck = new HashSet<>();
		struck.add(first.getUUID());
		for (int k = 0; k < 3; k++) {
			LivingEntity next = null;
			double best = 8 * 8;
			for (LivingEntity e : enemies(level, last.position(), 8, last)) {
				if (!struck.contains(e.getUUID()) && e.distanceToSqr(last) < best) {
					best = e.distanceToSqr(last);
					next = e;
				}
			}
			if (next == null) {
				break;
			}
			RiftFx.arc(level, last.getBoundingBox().getCenter(), next.getBoundingBox().getCenter(), 0xFFA8F0FF);
			hit(p, next, stats.damage() * 0.7F, 0xFF66E0FF, false);
			struck.add(next.getUUID());
			last = next;
		}
		level.sendParticles(ParticleTypes.ELECTRIC_SPARK, first.getX(), first.getY() + 1, first.getZ(), 6, 0.3, 0.5, 0.3, 0.2);
		if (level.getGameTime() % 4 == 0) {
			level.playSound(null, p.blockPosition(), SoundEvents.TRIDENT_THUNDER.value(), SoundSource.PLAYERS, 0.15F, 2.0F);
		}
	}

	// ----- Bouncer -----

	private static final class Nade {
		final UUID owner;
		final UUID display;
		Vec3 pos;
		Vec3 vel;
		int age;
		int bounces;
		final float damage;

		Nade(UUID owner, UUID display, Vec3 pos, Vec3 vel, float damage) {
			this.owner = owner;
			this.display = display;
			this.pos = pos;
			this.vel = vel;
			this.damage = damage;
		}
	}

	private static final List<Nade> NADES = new ArrayList<>();

	private static void bouncer(ServerLevel level, ServerPlayer p, GunStats stats) {
		Vec3 from = muzzle(p, 1.0);
		Display.ItemDisplay d = display(level, from, new ItemStack(ModItems.FRAG_GRENADE), 0.7F);
		NADES.add(new Nade(p.getUUID(), d == null ? null : d.getUUID(), from, p.getLookAngle().scale(1.25).add(0, 0.12, 0), stats.damage()));
		level.playSound(null, p.blockPosition(), SoundEvents.FIREWORK_ROCKET_SHOOT, SoundSource.PLAYERS, 1.0F, 0.6F);
		level.sendParticles(ParticleTypes.LARGE_SMOKE, from.x, from.y, from.z, 3, 0.05, 0.05, 0.05, 0.02);
	}

	private static boolean tickNade(ServerLevel level, Nade n) {
		n.age++;
		ServerPlayer owner = level.getServer().getPlayerList().getPlayer(n.owner);
		n.vel = n.vel.add(0, -0.045, 0);
		Vec3 next = n.pos.add(n.vel);
		BlockHitResult wall = level.clip(new ClipContext(n.pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, owner));
		if (wall.getType() != HitResult.Type.MISS) {
			Vec3 normal = Vec3.atLowerCornerOf(wall.getDirection().getUnitVec3i());
			n.vel = n.vel.subtract(normal.scale(2 * n.vel.dot(normal))).scale(0.72);
			next = wall.getLocation().add(normal.scale(0.15));
			n.bounces++;
			level.playSound(null, BlockPos.containing(next), SoundEvents.SLIME_BLOCK_HIT, SoundSource.PLAYERS, 1.0F, 1.4F);
			level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, level.getBlockState(wall.getBlockPos())), next.x, next.y, next.z, 6, 0.1,
					0.1, 0.1, 0.1);
			if (wall.getDirection() == Direction.UP && n.vel.lengthSqr() < 0.02) {
				n.vel = Vec3.ZERO;
			}
		}
		n.pos = next;
		if (n.display != null && level.getEntity(n.display) instanceof Display.ItemDisplay d) {
			d.setPos(n.pos);
			((DisplayAccessor) d).rubymod$setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotateXYZ(n.age * 0.4F, n.age * 0.3F, 0),
					new Vector3f(0.7F, 0.7F, 0.7F), new Quaternionf()));
		}
		level.sendParticles(RiftFx.dust(n.age % 6 < 3 ? 0xFF3D6E : 0xFFFFFF, 0.8F), true, false, n.pos.x, n.pos.y, n.pos.z, 1, 0, 0, 0, 0);
		boolean touched = owner != null && !enemies(level, n.pos, 1.0, null).isEmpty();
		if (touched || n.age > 60 || n.bounces > 6) {
			remove(level, n.display);
			if (owner != null) {
				RelicEffects.blast(level, owner, n.pos, 3.6, n.damage, 0xFFFF3D6E, false);
			}
			level.sendParticles(ParticleTypes.EXPLOSION, n.pos.x, n.pos.y, n.pos.z, 1, 0, 0, 0, 0);
			return true;
		}
		return false;
	}

	// ----- Ruby Warhammer -----

	private static void hammer(ServerLevel level, ServerPlayer p, GunStats stats) {
		// Falling onto them: a bigger, harder slam.
		boolean slam = !p.onGround() && p.getDeltaMovement().y < -0.25;
		double reach = slam ? 6.0 : 4.2;
		float damage = stats.damage() * (slam ? 1.6F : 1.0F);
		Vec3 look = p.getLookAngle().multiply(1, 0, 1).normalize();
		Vec3 at = slam ? p.position() : p.position().add(look.scale(1.8));
		for (LivingEntity e : enemies(level, at, reach, null)) {
			Vec3 to = e.position().subtract(p.position()).multiply(1, 0, 1);
			if (slam || to.lengthSqr() < 1.5 || to.normalize().dot(look) > 0.25) {
				hit(p, e, damage, 0xFFFF3D6E, true);
				Vec3 push = to.lengthSqr() < 0.01 ? look : to.normalize();
				e.setDeltaMovement(push.x * 0.6, slam ? 1.0 : 0.75, push.z * 0.6);
				e.needsSync = true;
				e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 30, 2, false, false));
			}
		}
		// The ground cracks in a ring.
		var under = level.getBlockState(p.blockPosition().below());
		for (int r = 1; r <= (int) reach; r++) {
			RiftFx.ring(level, at.add(0, 0.15, 0), r, new BlockParticleOption(ParticleTypes.BLOCK, under.isAir() ? Blocks.STONE.defaultBlockState() : under),
					8 * r);
		}
		RiftFx.ring(level, at.add(0, 0.25, 0), reach, RiftFx.dust(0xFF3D6E, 1.8F), 40);
		level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + 0.3, at.z, slam ? 3 : 1, 0.6, 0.1, 0.6, 0);
		level.playSound(null, p.blockPosition(), SoundEvents.MACE_SMASH_GROUND_HEAVY, SoundSource.PLAYERS, 1.2F, slam ? 0.6F : 0.85F);
		level.playSound(null, p.blockPosition(), SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.5F, 0.6F);
		RiftFx.shake(p, slam ? 1.4F : 0.8F, 8);
		if (slam) {
			p.resetFallDistance();
		}
	}

	// ----- Singularity Cannon -----

	private static final class Orb {
		final UUID owner;
		final UUID display;
		Vec3 pos;
		final Vec3 vel;
		int age;
		int collapse = -1;
		final float damage;

		Orb(UUID owner, UUID display, Vec3 pos, Vec3 vel, float damage) {
			this.owner = owner;
			this.display = display;
			this.pos = pos;
			this.vel = vel;
			this.damage = damage;
		}
	}

	private static final List<Orb> ORBS = new ArrayList<>();

	private static void singularity(ServerLevel level, ServerPlayer p, GunStats stats) {
		Vec3 from = muzzle(p, 1.2);
		Display.ItemDisplay d = display(level, from, new ItemStack(net.minecraft.world.item.Items.CRYING_OBSIDIAN), 0.6F);
		ORBS.add(new Orb(p.getUUID(), d == null ? null : d.getUUID(), from, p.getLookAngle().scale(0.55), stats.damage()));
		level.playSound(null, p.blockPosition(), SoundEvents.END_PORTAL_SPAWN, SoundSource.PLAYERS, 0.5F, 1.8F);
		level.playSound(null, p.blockPosition(), SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.PLAYERS, 0.8F, 1.6F);
	}

	private static boolean tickOrb(ServerLevel level, Orb o) {
		o.age++;
		ServerPlayer owner = level.getServer().getPlayerList().getPlayer(o.owner);
		if (owner == null) {
			remove(level, o.display);
			return true;
		}
		if (o.collapse < 0) {
			Vec3 next = o.pos.add(o.vel);
			BlockHitResult wall = level.clip(new ClipContext(o.pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, owner));
			boolean stop = wall.getType() != HitResult.Type.MISS || o.age > 50 || !enemies(level, next, 1.6, null).isEmpty();
			o.pos = wall.getType() != HitResult.Type.MISS ? wall.getLocation().subtract(o.vel.normalize().scale(0.6)) : next;
			if (stop) {
				o.collapse = 0;
				level.playSound(null, BlockPos.containing(o.pos), SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 1.5F, 0.4F);
			}
		} else {
			o.collapse++;
			// Everything nearby is dragged in, spiralling.
			for (LivingEntity e : enemies(level, o.pos, 8, null)) {
				if (JuggernautMech.isBoss(e)) {
					continue;
				}
				Vec3 in = o.pos.subtract(e.position());
				Vec3 swirl = new Vec3(-in.z, 0, in.x).normalize().scale(0.15);
				e.setDeltaMovement(in.normalize().scale(0.45).add(swirl).add(0, 0.08, 0));
				e.needsSync = true;
				if (o.collapse % 10 == 0) {
					hit(owner, e, 2, 0xFFC27BFF, false);
				}
			}
			if (o.collapse >= 45) {
				// Crushed.
				remove(level, o.display);
				for (LivingEntity e : enemies(level, o.pos, 5, null)) {
					hit(owner, e, o.damage, 0xFFC27BFF, true);
				}
				level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, o.pos.x, o.pos.y, o.pos.z, 1, 0, 0, 0, 0);
				level.sendParticles(ParticleTypes.REVERSE_PORTAL, o.pos.x, o.pos.y, o.pos.z, 120, 0.5, 0.5, 0.5, 1.2);
				RiftFx.burst(level, o.pos, 0xFFC27BFF, 60, 1.2);
				level.playSound(null, BlockPos.containing(o.pos), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 1.6F, 0.5F);
				level.playSound(null, BlockPos.containing(o.pos), SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 1.0F, 0.6F);
				RiftFx.shake(owner, 1.0F, 10);
				return true;
			}
		}
		double size = o.collapse < 0 ? 0.6 : 0.6 + Math.min(1.4, o.collapse * 0.04);
		if (o.display != null && level.getEntity(o.display) instanceof Display.ItemDisplay d) {
			d.setPos(o.pos);
			((DisplayAccessor) d).rubymod$setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotateXYZ(o.age * 0.2F, o.age * 0.3F, 0),
					new Vector3f((float) size, (float) size, (float) size), new Quaternionf()));
		}
		// A dark core, a ring of light, purple sparks spiralling in.
		RiftFx.ring(level, o.pos, 0.9 * size + 0.4, RiftFx.dust(o.age % 4 < 2 ? 0xC27BFF : 0xFFFFFF, 1.0F), 14);
		level.sendParticles(ParticleTypes.REVERSE_PORTAL, true, false, o.pos.x, o.pos.y, o.pos.z, o.collapse < 0 ? 3 : 12, 0.3, 0.3, 0.3,
				o.collapse < 0 ? 0.1 : 0.6);
		if (o.collapse >= 0 && o.collapse % 8 == 0) {
			level.playSound(null, BlockPos.containing(o.pos), SoundEvents.PORTAL_AMBIENT, SoundSource.PLAYERS, 0.6F, 1.5F + o.collapse * 0.01F);
		}
		return false;
	}

	// ----- Six-Shooter -----

	/** Six-Shooter: a headshot ricochets into the nearest other enemy's head. */
	public static void fanRicochet(ServerLevel level, ServerPlayer p, Entity target, float damage) {
		LivingEntity next = null;
		double best = 10 * 10;
		for (LivingEntity e : enemies(level, target.position(), 10, target)) {
			if (e.distanceToSqr(target) < best) {
				best = e.distanceToSqr(target);
				next = e;
			}
		}
		if (next != null) {
			RiftFx.line(level, target.getEyePosition(), next.getEyePosition(), ParticleTypes.CRIT, 0.4);
			RiftFx.line(level, target.getEyePosition(), next.getEyePosition(), RiftFx.dust(0xFFD040, 0.8F), 0.5);
			hit(p, next, damage * 0.8F, 0xFFFFD040, false);
			level.playSound(null, next.blockPosition(), SoundEvents.ARROW_HIT_PLAYER, SoundSource.PLAYERS, 0.8F, 1.6F);
		}
	}

	// ----- Shared -----

	private static Display.ItemDisplay display(ServerLevel level, Vec3 at, ItemStack stack, float scale) {
		Display.ItemDisplay d = EntityTypes.ITEM_DISPLAY.create(level, EntitySpawnReason.EVENT);
		if (d == null) {
			return null;
		}
		d.snapTo(at.x, at.y, at.z, 0, 0);
		((ItemDisplayAccessor) d).rubymod$setItemStack(stack);
		((DisplayAccessor) d).rubymod$setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(scale, scale, scale),
				new Quaternionf()));
		((DisplayAccessor) d).rubymod$setPosRotInterpolationDuration(1);
		d.addTag(Rift.TAG);
		level.addFreshEntity(d);
		return d;
	}

	private static void remove(ServerLevel level, UUID display) {
		if (display != null && level.getEntity(display) instanceof Entity e) {
			e.discard();
		}
	}

	/** Every tick (in the Rift). */
	static void tick(ServerLevel level) {
		long now = level.getGameTime();
		for (Iterator<Charge> it = CHARGES.iterator(); it.hasNext();) {
			Charge c = it.next();
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(c.shooter());
			if (p == null) {
				it.remove();
				continue;
			}
			long left = c.when() - now;
			if (left > 0) {
				// Light gathering at the muzzle.
				Vec3 m = muzzle(p, 1.0);
				for (int k = 0; k < 3; k++) {
					double a = now * 0.7 + k * 2.1;
					double r = 0.15 + left * 0.06;
					level.sendParticles(RiftFx.dust(0x66E0FF, 0.7F), true, false, m.x + Math.cos(a) * r, m.y + Math.sin(a) * r, m.z, 1, 0, 0, 0, 0);
				}
				continue;
			}
			it.remove();
			railFire(level, p, c.damage(), c.range());
		}
		for (Iterator<Volley> it = VOLLEYS.iterator(); it.hasNext();) {
			Volley v = it.next();
			if (now < v.when()) {
				continue;
			}
			it.remove();
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(v.shooter());
			if (p != null) {
				swarmMissile(level, p, v.index());
			}
		}
		for (Iterator<Blade> it = BLADES.iterator(); it.hasNext();) {
			tickBlade(level, it.next(), it);
		}
		NADES.removeIf(n -> tickNade(level, n));
		ORBS.removeIf(o -> tickOrb(level, o));
	}

	/** Leaving a room: everything in flight goes. */
	static void clear(ServerLevel level) {
		for (Blade b : BLADES) {
			remove(level, b.display);
		}
		for (Nade n : NADES) {
			remove(level, n.display);
		}
		for (Orb o : ORBS) {
			remove(level, o.display);
		}
		BLADES.clear();
		NADES.clear();
		ORBS.clear();
		CHARGES.clear();
		VOLLEYS.clear();
	}
}
