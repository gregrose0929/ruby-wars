package dev.rubymod.game;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import dev.rubymod.gun.BlockDamage;
import dev.rubymod.registry.ModDamage;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Aircraft cannon shells. They're simulated as points (not entities, there can
 * be hundreds in the air): each tick a shell moves, drops a little, and ray-tests
 * the segment it covered. Every round leaves a bright tracer visible from far
 * away and bursts on impact (high explosive: a small puff, chips blocks, light
 * splash, anti-armor damage to vehicles).
 */
public final class CannonRounds {
	/** Muzzle velocity in blocks per tick (the aircraft's own speed adds to it). */
	public static final double SPEED = 9.0;
	public static final double GRAVITY = 0.03;
	private static final int LIFE = 30;

	private record Shell(ServerLevel level, ServerPlayer owner, Entity launcher, float damage, float antiArmor, Vec3[] state, int[] age) {
	}

	private static final List<Shell> SHELLS = new ArrayList<>();

	private CannonRounds() {
	}

	public static void fire(ServerLevel level, ServerPlayer owner, Entity launcher, Vec3 from, Vec3 velocity, float damage, float antiArmor) {
		SHELLS.add(new Shell(level, owner, launcher, damage, antiArmor, new Vec3[] {from, velocity}, new int[] {0}));
	}

	/** Called every server tick. */
	public static void tick() {
		Iterator<Shell> it = SHELLS.iterator();
		while (it.hasNext()) {
			Shell shell = it.next();
			if (step(shell) || ++shell.age()[0] > LIFE) {
				it.remove();
			}
		}
	}

	/** Advance one shell; true when it's spent. */
	private static boolean step(Shell shell) {
		ServerLevel level = shell.level();
		Vec3 pos = shell.state()[0];
		Vec3 vel = shell.state()[1];
		Vec3 next = pos.add(vel);
		if (!level.hasChunkAt(net.minecraft.core.BlockPos.containing(next))) {
			return true;
		}
		BlockHitResult blockHit = level.clip(new ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, shell.launcher()));
		Vec3 end = blockHit.getType() == HitResult.Type.MISS ? next : blockHit.getLocation();

		Entity hit = null;
		Vec3 hitAt = null;
		double best = Double.MAX_VALUE;
		for (Entity e : level.getEntities(shell.launcher(), new AABB(pos, end).inflate(1.0),
				e -> e.isAlive() && e.isPickable() && !e.isSpectator() && e != shell.owner() && !shell.launcher().hasPassenger(e))) {
			var clip = e.getBoundingBox().inflate(0.3).clip(pos, end);
			if (clip.isPresent() && pos.distanceToSqr(clip.get()) < best) {
				best = pos.distanceToSqr(clip.get());
				hit = e;
				hitAt = clip.get();
			}
		}

		// Tracer: a bright streak along the segment, visible from far away.
		Vec3 seg = (hitAt != null ? hitAt : end).subtract(pos);
		for (int i = 1; i <= 3; i++) {
			Vec3 p = pos.add(seg.scale(i / 3.0));
			level.sendParticles(ParticleTypes.FLAME, true, true, p.x, p.y, p.z, 1, 0, 0, 0, 0);
		}

		if (hit != null) {
			impact(shell, hitAt, hit);
			return true;
		}
		if (blockHit.getType() == HitResult.Type.BLOCK) {
			impact(shell, end, null);
			BlockDamage.hit(level, blockHit.getBlockPos(), shell.damage() * 2.0F, shell.owner());
			return true;
		}
		shell.state()[0] = next;
		shell.state()[1] = vel.add(0, -GRAVITY, 0);
		return false;
	}

	private static void impact(Shell shell, Vec3 at, Entity target) {
		ServerLevel level = shell.level();
		level.sendParticles(ParticleTypes.EXPLOSION, true, true, at.x, at.y, at.z, 1, 0.1, 0.1, 0.1, 0);
		level.sendParticles(ParticleTypes.LARGE_SMOKE, true, false, at.x, at.y, at.z, 2, 0.2, 0.2, 0.2, 0.02);
		if (level.getRandom().nextInt(3) == 0) {
			level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 0.8F, 1.8F);
		}
		if (target != null) {
			if (Armor.isArmored(target)) {
				Armor.hit(level, target, null, shell.owner(), shell.antiArmor());
			} else if (Rules.canHurt(shell.owner(), target)) {
				target.setInvulnerableTime(0);
				target.hurtServer(level, ModDamage.source(level, ModDamage.BULLET, shell.owner(), shell.owner()), shell.damage());
			}
		}
		// High-explosive splash.
		for (Entity e : level.getEntities(target, new AABB(at, at).inflate(1.5), e -> e instanceof LivingEntity && e.isAlive() && !Armor.isArmored(e))) {
			if (e != shell.owner() && !shell.launcher().hasPassenger(e) && Rules.canHurt(shell.owner(), e)) {
				e.setInvulnerableTime(0);
				e.hurtServer(level, ModDamage.source(level, ModDamage.BULLET, shell.owner(), shell.owner()), shell.damage() * 0.4F);
			}
		}
	}
}
