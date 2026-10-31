package dev.rubymod.game.convoy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import dev.rubymod.game.Explosions;
import dev.rubymod.game.rift.RiftFx;
import dev.rubymod.mixin.BlockDisplayAccessor;
import dev.rubymod.mixin.DisplayAccessor;
import dev.rubymod.mixin.InteractionAccessor;
import dev.rubymod.registry.ModBlocks;
import dev.rubymod.registry.ModDamage;

import com.mojang.math.Transformation;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.core.BlockPos;
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
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The Ruby Truck: an armored truck loaded with rubies, built from blocks. It drives itself
 * along the road while someone stays close (see Convoy). Two seats on top (right-click
 * them): the roof gun at the front and the cargo bed at the back. Crouch beside it to
 * repair it. Its upgrades (from the depots) fire on their own: the roof minigun, rocket
 * pods, the tesla coil, the ram; the shield and the repair drone look after it.
 */
final class ConvoyTruck {
	private record Part(BlockState block, float sx, float sy, float sz, float ox, float oy, float oz) {
	}

	private static final BlockState BODY = Blocks.CONCRETE.pick(DyeColor.RED).defaultBlockState();
	private static final BlockState DARK = Blocks.POLISHED_BLACKSTONE.defaultBlockState();
	private static final BlockState TYRE = Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState();
	private static final BlockState STEEL = Blocks.IRON_BLOCK.defaultBlockState();
	private static final BlockState PLATE = Blocks.CONCRETE.pick(DyeColor.GRAY).defaultBlockState();
	private static final BlockState GLASS = Blocks.STAINED_GLASS.pick(DyeColor.LIGHT_BLUE).defaultBlockState();
	private static final BlockState LIGHT = Blocks.SEA_LANTERN.defaultBlockState();
	private static final BlockState BEACON = Blocks.REDSTONE_LAMP.defaultBlockState().setValue(RedstoneLampBlock.LIT, true);
	private static final BlockState RUBY = ModBlocks.RUBY_BLOCK.defaultBlockState();
	/** Front is +Z. Width 3, length 7. */
	private static final List<Part> MODEL = List.of(
			new Part(DARK, 3.0F, 0.45F, 6.8F, -1.5F, 0.45F, -3.4F),
			new Part(TYRE, 0.5F, 1.0F, 1.1F, -1.65F, 0.0F, 1.6F), new Part(TYRE, 0.5F, 1.0F, 1.1F, 1.15F, 0.0F, 1.6F),
			new Part(TYRE, 0.5F, 1.0F, 1.1F, -1.65F, 0.0F, -1.1F), new Part(TYRE, 0.5F, 1.0F, 1.1F, 1.15F, 0.0F, -1.1F),
			new Part(TYRE, 0.5F, 1.0F, 1.1F, -1.65F, 0.0F, -2.6F), new Part(TYRE, 0.5F, 1.0F, 1.1F, 1.15F, 0.0F, -2.6F),
			// The cab.
			new Part(BODY, 3.0F, 1.6F, 2.1F, -1.5F, 0.9F, 1.3F),
			new Part(BODY, 3.0F, 0.6F, 0.8F, -1.5F, 0.9F, 3.4F),
			new Part(GLASS, 2.6F, 0.7F, 0.08F, -1.3F, 1.65F, 3.38F),
			new Part(GLASS, 0.08F, 0.6F, 1.2F, -1.53F, 1.7F, 1.9F), new Part(GLASS, 0.08F, 0.6F, 1.2F, 1.45F, 1.7F, 1.9F),
			new Part(STEEL, 3.2F, 0.35F, 0.25F, -1.6F, 0.55F, 4.15F),
			new Part(LIGHT, 0.5F, 0.35F, 0.08F, -1.3F, 1.05F, 4.2F), new Part(LIGHT, 0.5F, 0.35F, 0.08F, 0.8F, 1.05F, 4.2F),
			new Part(DARK, 1.6F, 0.3F, 0.08F, -0.8F, 1.0F, 4.2F),
			// The armored cargo box, its ruby load, and plates.
			new Part(PLATE, 3.0F, 1.7F, 4.4F, -1.5F, 0.9F, -3.4F),
			new Part(STEEL, 3.1F, 0.25F, 4.5F, -1.55F, 2.5F, -3.45F),
			new Part(RUBY, 2.4F, 0.4F, 3.6F, -1.2F, 2.6F, -3.0F),
			new Part(RUBY, 1.4F, 0.35F, 2.2F, -0.7F, 3.0F, -2.3F),
			new Part(BODY, 3.06F, 0.3F, 4.46F, -1.53F, 1.6F, -3.43F),
			// The roof gun on the cab, a beacon, an aerial.
			new Part(DARK, 1.1F, 0.35F, 1.1F, -0.55F, 2.5F, 1.75F),
			new Part(STEEL, 0.18F, 0.18F, 1.4F, -0.09F, 2.65F, 2.4F),
			new Part(BEACON, 0.35F, 0.25F, 0.35F, 1.0F, 2.5F, 2.8F),
			new Part(Blocks.END_ROD.defaultBlockState(), 0.1F, 1.3F, 0.1F, 1.2F, 2.75F, -3.25F));
	/** The ruby load glows (you can see where the truck is through walls). */
	private static final int GLOW_PART = 19;

	final List<UUID> parts = new ArrayList<>();
	UUID roofSeat;
	UUID bedSeat;
	double z;
	float hp;
	float maxHp;
	float shield;
	long lastHit;
	private int t;
	private float lastYaw;

	static ConvoyTruck spawn(ServerLevel level, double z, float maxHp) {
		ConvoyTruck truck = new ConvoyTruck();
		truck.z = z;
		truck.maxHp = maxHp;
		truck.hp = maxHp;
		Vec3 at = truck.position();
		float yaw = ConvoyRoute.yaw(z);
		for (int i = 0; i < MODEL.size(); i++) {
			Part p = MODEL.get(i);
			Display.BlockDisplay d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
			if (d == null) {
				continue;
			}
			d.snapTo(at.x, at.y, at.z, yaw, 0);
			((BlockDisplayAccessor) d).rubymod$setBlockState(p.block());
			((DisplayAccessor) d).rubymod$setTransformation(new Transformation(new Vector3f(p.ox(), p.oy(), p.oz()), new Quaternionf(),
					new Vector3f(p.sx(), p.sy(), p.sz()), new Quaternionf()));
			((DisplayAccessor) d).rubymod$setPosRotInterpolationDuration(3);
			((DisplayAccessor) d).rubymod$setViewRange(6.0F);
			if (i == GLOW_PART) {
				d.setGlowingTag(true);
				((DisplayAccessor) d).rubymod$setGlowColorOverride(0xFF3050);
			}
			d.addTag(Convoy.TAG);
			level.addFreshEntity(d);
			truck.parts.add(d.getUUID());
		}
		truck.roofSeat = seat(level, at);
		truck.bedSeat = seat(level, at);
		truck.lastYaw = yaw;
		truck.place(level);
		return truck;
	}

	private static UUID seat(ServerLevel level, Vec3 at) {
		Interaction seat = EntityTypes.INTERACTION.create(level, EntitySpawnReason.EVENT);
		if (seat == null) {
			return null;
		}
		seat.snapTo(at.x, at.y, at.z, 0, 0);
		((InteractionAccessor) seat).rubymod$setWidth(1.3F);
		((InteractionAccessor) seat).rubymod$setHeight(0.5F);
		seat.addTag(Convoy.TAG);
		seat.addTag(Convoy.SEAT_TAG);
		level.addFreshEntity(seat);
		return seat.getUUID();
	}

	Vec3 position() {
		return new Vec3(ConvoyRoute.cx(z), ConvoyRoute.Y + 1, z);
	}

	/** A point on the truck, from the model's frame (x right, z forward) to the world. */
	Vec3 local(double x, double y, double fz) {
		double yaw = Math.toRadians(lastYaw);
		double wx = x * Math.cos(yaw) - fz * Math.sin(yaw);
		double wz = x * Math.sin(yaw) + fz * Math.cos(yaw);
		return position().add(wx, y, wz);
	}

	Vec3 forward() {
		return Vec3.directionFromRotation(0, lastYaw);
	}

	/** Move every block of it (and its seats) to where it is now. */
	void place(ServerLevel level) {
		Vec3 at = position();
		float yaw = ConvoyRoute.yaw(z);
		lastYaw = yaw;
		for (UUID id : parts) {
			Entity d = level.getEntity(id);
			if (d != null) {
				d.teleportTo(at.x, at.y, at.z);
				d.setYRot(yaw);
			}
		}
		Vec3 roof = local(0, 2.35, 1.95);
		Vec3 bed = local(0, 2.55, -1.6);
		moveSeat(level, roofSeat, roof);
		moveSeat(level, bedSeat, bed);
	}

	private static void moveSeat(ServerLevel level, UUID id, Vec3 to) {
		Entity seat = id == null ? null : level.getEntity(id);
		if (seat != null) {
			seat.teleportTo(to.x, to.y, to.z);
		}
	}

	boolean isSeat(Entity e) {
		return e != null && (e.getUUID().equals(roofSeat) || e.getUUID().equals(bedSeat));
	}

	boolean onRoof(ServerPlayer p) {
		return p.getVehicle() != null && p.getVehicle().getUUID().equals(roofSeat);
	}

	boolean riding(ServerPlayer p) {
		return p.getVehicle() != null && isSeat(p.getVehicle());
	}

	/** Roughly inside the truck's box? */
	boolean touches(Vec3 p, double pad) {
		Vec3 rel = p.subtract(position());
		double yaw = Math.toRadians(lastYaw);
		double lx = rel.x * Math.cos(yaw) + rel.z * Math.sin(yaw);
		double lz = -rel.x * Math.sin(yaw) + rel.z * Math.cos(yaw);
		return Math.abs(lx) < 1.6 + pad && Math.abs(lz) < 3.6 + pad && rel.y > -1.5 && rel.y < 3.5 + pad;
	}

	double distanceTo(Vec3 p) {
		Vec3 rel = p.subtract(position());
		double yaw = Math.toRadians(lastYaw);
		double lx = rel.x * Math.cos(yaw) + rel.z * Math.sin(yaw);
		double lz = -rel.x * Math.sin(yaw) + rel.z * Math.cos(yaw);
		double dx = Math.max(0, Math.abs(lx) - 1.5);
		double dz = Math.max(0, Math.abs(lz) - 3.5);
		return Math.sqrt(dx * dx + dz * dz + Math.max(0, Math.abs(rel.y - 1.2) - 1.5) * Math.max(0, Math.abs(rel.y - 1.2) - 1.5));
	}

	void remove(ServerLevel level) {
		for (UUID id : parts) {
			Entity e = level.getEntity(id);
			if (e != null) {
				e.discard();
			}
		}
		for (UUID id : new UUID[] {roofSeat, bedSeat}) {
			Entity e = id == null ? null : level.getEntity(id);
			if (e != null) {
				e.ejectPassengers();
				e.discard();
			}
		}
		parts.clear();
	}

	// ----- Getting hurt, and mended -----

	/** Damage to the truck (armor upgrades and the shield take their share). Returns what got through. */
	float hurt(ServerLevel level, float amount, Vec3 from) {
		if (amount <= 0 || hp <= 0) {
			return 0;
		}
		amount *= Convoy.damageScale();
		int plating = Convoy.level(ConvoyUpgrade.PLATING);
		amount *= (float) Math.pow(0.88, plating);
		lastHit = level.getGameTime();
		if (shield > 0) {
			float soak = Math.min(shield, amount);
			shield -= soak;
			amount -= soak;
			RiftFx.ring(level, position().add(0, 1.4, 0), 3.4, RiftFx.dust(0x60C0FF, 1.2F), 18);
			if (shield <= 0) {
				level.playSound(null, BlockPos.containing(position()), SoundEvents.SHIELD_BREAK.value(), SoundSource.NEUTRAL, 1.2F, 0.8F);
			}
		}
		if (amount <= 0) {
			return 0;
		}
		hp = Math.max(0, hp - amount);
		Vec3 at = from == null ? position().add(0, 1.5, 0) : from;
		level.sendParticles(ParticleTypes.CRIT, at.x, at.y, at.z, 6, 0.3, 0.3, 0.3, 0.2);
		if (amount >= 8) {
			level.playSound(null, BlockPos.containing(position()), SoundEvents.IRON_GOLEM_DAMAGE, SoundSource.NEUTRAL, 1.0F, 0.8F);
		} else if (t % 6 == 0) {
			level.playSound(null, BlockPos.containing(position()), SoundEvents.ANVIL_LAND, SoundSource.NEUTRAL, 0.25F, 1.6F);
		}
		return amount;
	}

	void repair(float amount) {
		hp = Math.min(maxHp, hp + amount);
	}

	float fraction() {
		return maxHp <= 0 ? 0 : hp / maxHp;
	}

	// ----- Every tick -----

	void tick(ServerLevel level, boolean moving, List<ServerPlayer> crew) {
		t++;
		Vec3 at = position();
		// Exhaust, and damage you can see.
		if (moving && t % 3 == 0) {
			Vec3 pipe = local(1.3, 0.6, -3.6);
			level.sendParticles(ParticleTypes.SMOKE, pipe.x, pipe.y, pipe.z, 1, 0.05, 0.05, 0.05, 0.01);
		}
		float f = fraction();
		if (f < 0.5F && t % 4 == 0) {
			level.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y + 2.6, at.z, 1, 0.6, 0.2, 0.6, 0.02);
		}
		if (f < 0.25F && t % 2 == 0) {
			Vec3 hood = local(0, 1.6, 3.2);
			level.sendParticles(ParticleTypes.FLAME, hood.x, hood.y, hood.z, 2, 0.4, 0.1, 0.3, 0.01);
		}
		if (moving && t % 20 == 0) {
			level.playSound(null, BlockPos.containing(at), SoundEvents.MINECART_RIDING, SoundSource.NEUTRAL, 0.35F, 0.6F);
		}
		// The shield: recharges after 6 seconds without a hit.
		int shieldLevel = Convoy.level(ConvoyUpgrade.SHIELD);
		if (shieldLevel > 0) {
			float cap = maxHp * 0.2F * shieldLevel;
			if (level.getGameTime() - lastHit > 120 && shield < cap) {
				shield = Math.min(cap, shield + cap / 80);
			}
			if (shield > 0 && t % 10 == 0) {
				RiftFx.ring(level, at.add(0, 0.2, 0), 4.0, RiftFx.dust(0x60C0FF, 0.8F), 14);
			}
		}
		// The repair drone: mends it while it's out of a fight.
		int drone = Convoy.level(ConvoyUpgrade.DRONE);
		if (drone > 0 && t % 20 == 0 && level.getGameTime() - lastHit > 60 && hp < maxHp) {
			repair(maxHp * 0.006F * drone);
			Vec3 d = at.add(Math.cos(t * 0.05) * 2.5, 4.2, Math.sin(t * 0.05) * 2.5);
			level.sendParticles(ParticleTypes.ELECTRIC_SPARK, d.x, d.y, d.z, 4, 0.2, 0.2, 0.2, 0.05);
			level.sendParticles(ParticleTypes.HAPPY_VILLAGER, at.x, at.y + 1.5, at.z, 2, 1.0, 0.6, 1.5, 0);
		}
		// The field hospital: everyone close by heals.
		if (Convoy.level(ConvoyUpgrade.MEDIC) > 0 && t % 40 == 0) {
			for (ServerPlayer p : crew) {
				if (distanceTo(p.position()) < 10 && p.getHealth() < p.getMaxHealth()) {
					p.heal(1.0F * Convoy.level(ConvoyUpgrade.MEDIC) + 1.0F);
					level.sendParticles(ParticleTypes.HEART, p.getX(), p.getY() + 2.1, p.getZ(), 1, 0.2, 0.1, 0.2, 0);
				}
			}
		}
		weapons(level, crew);
	}

	private List<Mob> enemiesWithin(ServerLevel level, double range) {
		List<Mob> out = new ArrayList<>();
		for (UUID id : Convoy.MOBS) {
			if (level.getEntity(id) instanceof Mob m && m.isAlive() && distanceTo(m.position()) <= range) {
				out.add(m);
			}
		}
		out.sort(Comparator.comparingDouble(m -> distanceTo(m.position())));
		return out;
	}

	private boolean canSee(ServerLevel level, Vec3 from, Entity target) {
		Vec3 to = target.getBoundingBox().getCenter();
		return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, target)).getType() == HitResult.Type.MISS;
	}

	private void weapons(ServerLevel level, List<ServerPlayer> crew) {
		ServerPlayer credit = crew.isEmpty() ? null : crew.get(0);
		int gun = Convoy.level(ConvoyUpgrade.MINIGUN);
		if (gun > 0 && t % Math.max(2, 5 - gun) == 0) {
			Vec3 muzzle = local(0, 2.75, 3.1);
			for (Mob m : enemiesWithin(level, 24)) {
				if (canSee(level, muzzle, m)) {
					Vec3 hit = m.getBoundingBox().getCenter();
					RiftFx.line(level, muzzle, hit, ParticleTypes.CRIT, 0.8);
					m.setInvulnerableTime(0);
					m.hurtServer(level, ModDamage.source(level, ModDamage.TURRET, credit, credit), 2.0F);
					if (t % 4 == 0) {
						level.playSound(null, BlockPos.containing(muzzle), dev.rubymod.registry.ModSounds.RIFLE_SHOT, SoundSource.NEUTRAL, 0.6F, 1.5F);
					}
					break;
				}
			}
		}
		int pods = Convoy.level(ConvoyUpgrade.ROCKETS);
		if (pods > 0 && t % (pods >= 2 ? 70 : 100) == 0) {
			Mob best = null;
			for (Mob m : enemiesWithin(level, 32)) {
				if (best == null || m.getMaxHealth() > best.getMaxHealth()) {
					best = m;
				}
			}
			if (best != null) {
				Vec3 from = local(1.2, 2.8, -0.5);
				Vec3 to = best.position().add(0, 0.6, 0);
				RiftFx.line(level, from, to, ParticleTypes.FLAME, 0.7);
				RiftFx.line(level, from, to, ParticleTypes.SMOKE, 0.5);
				level.playSound(null, BlockPos.containing(from), SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.NEUTRAL, 1.4F, 0.7F);
				Explosions.explode(level, credit, to.x, to.y, to.z, 2.0F, false);
				for (Mob m : enemiesWithin(level, 60)) {
					if (m.position().distanceTo(to) < 3.5) {
						m.setInvulnerableTime(0);
						m.hurtServer(level, ModDamage.source(level, ModDamage.ANTI_ARMOR, credit, credit), 18.0F);
					}
				}
			}
		}
		int tesla = Convoy.level(ConvoyUpgrade.TESLA);
		if (tesla > 0 && t % 40 == 0) {
			Vec3 coil = local(0, 3.4, -1.5);
			int zapped = 0;
			for (Mob m : enemiesWithin(level, 7 + tesla * 2)) {
				RiftFx.arc(level, coil, m.getBoundingBox().getCenter(), 0x80E0FF);
				m.setInvulnerableTime(0);
				m.hurtServer(level, ModDamage.source(level, ModDamage.TURRET, credit, credit), 5.0F + tesla * 2);
				if (++zapped >= 2 + tesla) {
					break;
				}
			}
			if (zapped > 0) {
				level.playSound(null, BlockPos.containing(coil), SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.NEUTRAL, 0.7F, 1.8F);
			}
		}
		if (Convoy.level(ConvoyUpgrade.RAM) > 0 && t % 10 == 0) {
			Vec3 front = local(0, 1, 4.6);
			for (Mob m : enemiesWithin(level, 6)) {
				if (m.position().distanceTo(front) < 2.6) {
					m.setInvulnerableTime(0);
					m.hurtServer(level, ModDamage.source(level, ModDamage.TURRET, credit, credit), 6.0F);
					Vec3 push = m.position().subtract(position()).multiply(1, 0, 1).normalize().scale(0.9);
					m.push(push.x, 0.35, push.z);
					level.sendParticles(ParticleTypes.CRIT, m.getX(), m.getY() + 1, m.getZ(), 8, 0.3, 0.3, 0.3, 0.2);
				}
			}
		}
	}

	/** Gone up in flames (wrecked). */
	void explode(ServerLevel level) {
		Vec3 at = position();
		level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y + 1, at.z, 2, 1, 0.5, 1, 0);
		level.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y + 1.5, at.z, 60, 1.5, 1.2, 2.5, 0.05);
		level.sendParticles(ParticleTypes.FLAME, at.x, at.y + 1.5, at.z, 40, 1.5, 1.0, 2.5, 0.05);
		level.playSound(null, BlockPos.containing(at), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.NEUTRAL, 3.0F, 0.6F);
		for (UUID id : new UUID[] {roofSeat, bedSeat}) {
			Entity e = id == null ? null : level.getEntity(id);
			if (e != null) {
				e.ejectPassengers();
			}
		}
	}

	/** Whoever's on the roof gun is tougher and hits harder (applied each second). */
	static void gunnerBuff(ServerPlayer p) {
		p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 40, 1, false, false));
		p.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 40, 0, false, false));
	}

	static boolean isPart(Entity e) {
		return e != null && e.entityTags().contains(Convoy.TAG) && (e instanceof Display || e instanceof Interaction);
	}
}
