package dev.rubymod.game.rift;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.rubymod.entity.VehicleEntity;
import dev.rubymod.mixin.BlockDisplayAccessor;
import dev.rubymod.mixin.DisplayAccessor;
import dev.rubymod.registry.ModDamage;
import dev.rubymod.registry.ModEntities;

import com.mojang.math.Transformation;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
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
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Tank Assault rooms: your team starts in tanks, against Rift Tanks (dark red, built
 * from blocks around an unseen walker) that roll toward you and lob shells, plus their
 * infantry. A shell that lands near you hurts your tank, or you if you're on foot.
 */
final class RiftTanks {
	private record Part(BlockState block, float sx, float sy, float sz, float ox, float oy, float oz) {
	}

	private static final List<Part> MODEL = List.of(
			new Part(Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState(), 0.6F, 0.8F, 3.6F, -1.6F, 0.0F, -1.8F),
			new Part(Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState(), 0.6F, 0.8F, 3.6F, 1.0F, 0.0F, -1.8F),
			new Part(Blocks.RED_NETHER_BRICKS.defaultBlockState(), 2.6F, 0.9F, 3.4F, -1.3F, 0.4F, -1.7F),
			new Part(Blocks.NETHER_BRICKS.defaultBlockState(), 1.5F, 0.8F, 1.6F, -0.75F, 1.3F, -0.9F),
			new Part(Blocks.POLISHED_BLACKSTONE.defaultBlockState(), 0.3F, 0.3F, 2.4F, -0.15F, 1.55F, 0.6F),
			new Part(Blocks.REDSTONE_LAMP.defaultBlockState().setValue(RedstoneLampBlock.LIT, true), 0.3F, 0.2F, 0.3F, -0.15F, 2.1F, -0.5F));

	private record Tank(List<UUID> parts, int[] cooldown) {
	}

	private static final Map<UUID, Tank> ENEMIES = new HashMap<>();
	private static final List<UUID> OURS = new ArrayList<>();
	private static final Map<UUID, Integer> BOARD = new HashMap<>();

	private RiftTanks() {
	}

	/** The team's tanks, by the entrance (everyone climbs in a moment later). */
	static void giveTanks(ServerLevel level, RiftRoom room, List<ServerPlayer> players) {
		int i = 0;
		for (ServerPlayer p : players) {
			Mob tank = ModEntities.TANK.create(level, EntitySpawnReason.SPAWN_ITEM_USE);
			if (tank == null) {
				continue;
			}
			double x = room.entry.x - 4 + i * 4;
			double z = room.entry.z - 3;
			tank.snapTo(x, dev.rubymod.game.Hub.safeY(level, x, room.entry.y, z), z, 180, 0);
			tank.yBodyRot = 180;
			tank.addTag(Rift.TAG);
			level.addFreshEntity(tank);
			OURS.add(tank.getUUID());
			BOARD.put(p.getUUID(), 20);
			p.teleportTo(x, tank.getY(), z + 2);
			i++;
		}
	}

	/** A Rift Tank rolls in. */
	static Mob spawnEnemy(ServerLevel level, BlockPos at, double toughness) {
		Mob base = EntityTypes.RAVAGER.create(level, EntitySpawnReason.EVENT);
		if (base == null) {
			return null;
		}
		base.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
		set(base, Attributes.SCALE, 1.2);
		set(base, Attributes.MAX_HEALTH, 90 * toughness);
		set(base, Attributes.MOVEMENT_SPEED, 0.12);
		set(base, Attributes.FOLLOW_RANGE, 70);
		set(base, Attributes.ATTACK_DAMAGE, 6);
		base.setHealth(base.getMaxHealth());
		base.setSilent(true);
		base.setPersistenceRequired();
		base.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, -1, 0, false, false));
		base.setCustomName(Component.literal("Rift Tank").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
		base.addTag(Rift.TAG);
		level.addFreshEntity(base);
		List<UUID> parts = new ArrayList<>();
		for (Part part : MODEL) {
			Display.BlockDisplay d = EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.EVENT);
			if (d == null) {
				continue;
			}
			d.snapTo(base.getX(), base.getY(), base.getZ(), 0, 0);
			((BlockDisplayAccessor) d).rubymod$setBlockState(part.block());
			((DisplayAccessor) d).rubymod$setTransformation(new Transformation(new Vector3f(part.ox(), part.oy(), part.oz()), new Quaternionf(),
					new Vector3f(part.sx(), part.sy(), part.sz()), new Quaternionf()));
			((DisplayAccessor) d).rubymod$setPosRotInterpolationDuration(3);
			d.addTag(Rift.TAG);
			level.addFreshEntity(d);
			parts.add(d.getUUID());
		}
		ENEMIES.put(base.getUUID(), new Tank(parts, new int[] {60 + level.getRandom().nextInt(40)}));
		return base;
	}

	private static void set(Mob mob, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> a, double v) {
		AttributeInstance i = mob.getAttribute(a);
		if (i != null) {
			i.setBaseValue(v);
		}
	}

	static void tick(ServerLevel level, List<ServerPlayer> targets) {
		for (Iterator<Map.Entry<UUID, Integer>> it = BOARD.entrySet().iterator(); it.hasNext();) {
			var e = it.next();
			int left = e.getValue() - 1;
			e.setValue(left);
			if (left > 0) {
				continue;
			}
			it.remove();
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(e.getKey());
			if (p == null || p.getVehicle() != null) {
				continue;
			}
			for (UUID id : OURS) {
				if (level.getEntity(id) instanceof Entity tank && tank.isAlive() && !tank.isVehicle() && p.distanceTo(tank) < 12) {
					p.startRiding(tank, true, true);
					break;
				}
			}
		}
		for (Iterator<Map.Entry<UUID, Tank>> it = ENEMIES.entrySet().iterator(); it.hasNext();) {
			var e = it.next();
			Entity base = level.getEntity(e.getKey());
			Tank tank = e.getValue();
			if (!(base instanceof Mob mob) || !mob.isAlive()) {
				Vec3 at = base != null ? base.position() : null;
				for (UUID id : tank.parts()) {
					Entity d = level.getEntity(id);
					if (d != null) {
						if (at == null) {
							at = d.position();
						}
						d.discard();
					}
				}
				if (at != null) {
					level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y + 1, at.z, 1, 0, 0, 0, 0);
					level.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y + 1, at.z, 30, 1, 0.6, 1, 0.05);
					level.playSound(null, BlockPos.containing(at), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 2.0F, 0.7F);
				}
				it.remove();
				continue;
			}
			for (UUID id : tank.parts()) {
				Entity d = level.getEntity(id);
				if (d != null) {
					d.teleportTo(mob.getX(), mob.getY(), mob.getZ());
					d.setYRot(mob.yBodyRot);
				}
			}
			if (level.getGameTime() % 4 == 0) {
				level.sendParticles(ParticleTypes.SMOKE, mob.getX(), mob.getY() + 2.4, mob.getZ(), 1, 0.1, 0.1, 0.1, 0.01);
			}
			ServerPlayer target = null;
			for (ServerPlayer p : targets) {
				if (target == null || p.distanceToSqr(mob) < target.distanceToSqr(mob)) {
					target = p;
				}
			}
			if (target == null) {
				continue;
			}
			mob.setTarget(target);
			// Keep a little distance and shell from there.
			if (target.distanceTo(mob) < 9) {
				mob.getNavigation().stop();
			}
			if (--tank.cooldown()[0] <= 0) {
				tank.cooldown()[0] = 70 + level.getRandom().nextInt(30);
				fire(level, mob, target);
			}
		}
	}

	private static void fire(ServerLevel level, Mob mob, ServerPlayer target) {
		double yaw = Math.toRadians(mob.yBodyRot);
		Vec3 muzzle = mob.position().add(-Math.sin(yaw) * 3.0, 1.9, Math.cos(yaw) * 3.0);
		Vec3 aim = target.position().add(0, 1, 0);
		var hit = level.clip(new ClipContext(muzzle, aim, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mob));
		Vec3 land = hit.getType() == HitResult.Type.MISS ? aim : hit.getLocation();
		RiftFx.line(level, muzzle, land, ParticleTypes.SMOKE, 0.5);
		RiftFx.line(level, muzzle, land, RiftFx.dust(0xFF4020, 1.0F), 0.9);
		level.sendParticles(ParticleTypes.FLAME, muzzle.x, muzzle.y, muzzle.z, 12, 0.2, 0.2, 0.2, 0.05);
		level.sendParticles(ParticleTypes.EXPLOSION, land.x, land.y, land.z, 2, 0.4, 0.4, 0.4, 0);
		level.playSound(null, BlockPos.containing(muzzle), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 1.4F, 1.3F);
		for (ServerPlayer p : Rift.players(level.getServer())) {
			if (p.isSpectator() || p.position().distanceTo(land) > 3.0) {
				continue;
			}
			if (p.getVehicle() instanceof VehicleEntity v) {
				v.hurtServer(level, ModDamage.source(level, ModDamage.ANTI_ARMOR, mob, mob), 22);
			} else {
				p.hurtServer(level, level.damageSources().explosion(mob, mob), 7);
			}
			RiftFx.shake(p, 0.9F, 8);
		}
	}

	/** Room over: the tanks go (yours too). */
	static void clear(ServerLevel level) {
		for (UUID id : OURS) {
			Entity e = level.getEntity(id);
			if (e != null) {
				e.ejectPassengers();
				e.discard();
			}
		}
		OURS.clear();
		BOARD.clear();
		for (var e : ENEMIES.entrySet()) {
			Entity base = level.getEntity(e.getKey());
			if (base != null) {
				base.discard();
			}
			for (UUID id : e.getValue().parts()) {
				Entity d = level.getEntity(id);
				if (d != null) {
					d.discard();
				}
			}
		}
		ENEMIES.clear();
	}
}
