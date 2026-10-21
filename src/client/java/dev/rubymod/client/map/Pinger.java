package dev.rubymod.client.map;

import dev.rubymod.net.MorePayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** B: ping the spot you're looking at for your squad, or spot the enemy under your crosshair (up to 400 blocks). */
public final class Pinger {
	private static final double RANGE = 400.0;

	private Pinger() {
	}

	public static void ping(Minecraft mc) {
		if (mc.player == null || mc.level == null) {
			return;
		}
		Vec3 eye = mc.player.getEyePosition();
		Vec3 look = mc.player.getViewVector(1.0F);
		HitResult blockHit = mc.player.pick(RANGE, 1.0F, false);
		double limit = blockHit.getType() == HitResult.Type.MISS ? RANGE : blockHit.getLocation().distanceTo(eye);
		Vec3 end = eye.add(look.scale(limit));
		Entity best = null;
		double bestDist = limit;
		for (Entity e : mc.level.getEntities(mc.player, new AABB(eye, end).inflate(2.0), e -> e instanceof LivingEntity && e.isAlive())) {
			var hit = e.getBoundingBox().inflate(0.6).clip(eye, end);
			if (hit.isPresent() && hit.get().distanceTo(eye) < bestDist) {
				best = e;
				bestDist = hit.get().distanceTo(eye);
			}
		}
		if (best != null) {
			ClientPlayNetworking.send(new MorePayloads.Ping(best.getBlockX(), best.getBlockY(), best.getBlockZ(), best.getId()));
		} else if (blockHit instanceof BlockHitResult b && blockHit.getType() == HitResult.Type.BLOCK) {
			var p = b.getBlockPos();
			ClientPlayNetworking.send(new MorePayloads.Ping(p.getX(), p.getY(), p.getZ(), -1));
		}
	}
}
