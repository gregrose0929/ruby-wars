package dev.rubymod.game.rift;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Active abilities (F in the Rift, or Z; picked at the Altar of Echoes): a move on a cooldown,
 * on top of your relics. Dash, Ruby Nova, Shield Dome, Grapple Hook, Overdrive, Rally Banner.
 */
public final class RiftAbilities {
	public enum Ability {
		DASH("Dash", "minecraft:feather", "Burst forward 7 blocks, untouchable for a moment, slicing through anything in the way.", 100, 0xFFFF3D6E),
		NOVA("Ruby Nova", "minecraft:amethyst_shard", "A ruby shockwave around you: hurts and hurls back everything close.", 280, 0xFFFF3D6E),
		DOME("Shield Dome", "minecraft:shield", "A dome for 6 seconds: enemy fire can't get in. Everyone inside is safe.", 500, 0xFF66E0FF),
		GRAPPLE("Grapple Hook", "minecraft:fishing_rod", "Hook where you're looking: fly there, or yank an enemy over to you.", 120, 0xFFB8C4D0),
		OVERDRIVE("Overdrive", "minecraft:blaze_powder", "For 6 seconds your guns fire twice as fast and hit 50% harder.", 600, 0xFFFF7A2E),
		RALLY("Rally Banner", "minecraft:red_banner", "Plant a banner: for 10 seconds it heals and speeds up the team around it.", 600, 0xFF6EE38A);

		public final String title;
		public final String icon;
		public final String about;
		public final int cooldown;
		public final int color;

		Ability(String title, String icon, String about, int cooldown, int color) {
			this.title = title;
			this.icon = icon;
			this.about = about;
			this.cooldown = cooldown;
			this.color = color;
		}

		public String id() {
			return name().toLowerCase();
		}

		public static Ability byId(String id) {
			for (Ability a : values()) {
				if (a.id().equals(id)) {
					return a;
				}
			}
			return DASH;
		}
	}

	private record Dome(Vec3 at, long until) {
	}

	private record Banner(Vec3 at, long until, UUID owner) {
	}

	private static final List<Dome> DOMES = new ArrayList<>();
	private static final List<Banner> BANNERS = new ArrayList<>();
	private static final double DOME_RADIUS = 4.5;

	private RiftAbilities() {
	}

	/** The ability key pressed (F, or Z). */
	static void use(ServerPlayer p, Rift.Runner r) {
		if (!(p.level() instanceof ServerLevel level) || p.isSpectator()) {
			return;
		}
		Ability a = r.ability;
		long now = level.getGameTime();
		if (now < r.abilityReadyAt) {
			p.sendOverlayMessage(Component.literal(a.title + " recharging: " + (r.abilityReadyAt - now + 19) / 20 + "s").withStyle(ChatFormatting.GRAY));
			return;
		}
		boolean used = switch (a) {
			case DASH -> dash(level, p, r);
			case NOVA -> nova(level, p);
			case DOME -> dome(level, p);
			case GRAPPLE -> grapple(level, p, r);
			case OVERDRIVE -> overdrive(level, p, r);
			case RALLY -> rally(level, p);
		};
		if (used) {
			r.abilityReadyAt = now + a.cooldown;
			Rift.sendHudSoon();
		}
	}

	// ----- Dash -----

	private static boolean dash(ServerLevel level, ServerPlayer p, Rift.Runner r) {
		Vec3 look = p.getLookAngle();
		Vec3 dir = new Vec3(look.x, Math.max(-0.2, Math.min(0.35, look.y)), look.z).normalize();
		Vec3 from = p.position().add(0, 0.6, 0);
		var hit = level.clip(new ClipContext(from, from.add(dir.scale(7)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
		Vec3 to = hit.getType() == HitResult.Type.MISS ? from.add(dir.scale(7)) : hit.getLocation().subtract(dir.scale(0.7));
		// Afterimages along the way, and a cut through every enemy on the path.
		for (int i = 0; i <= 10; i++) {
			Vec3 at = from.lerp(to, i / 10.0);
			level.sendParticles(RiftFx.dust(0xFF3D6E, 1.6F), true, false, at.x, at.y + 0.4, at.z, 3, 0.2, 0.5, 0.2, 0);
			level.sendParticles(ParticleTypes.END_ROD, true, false, at.x, at.y + 0.6, at.z, 1, 0.1, 0.3, 0.1, 0.01);
		}
		for (LivingEntity e : RelicEffects.enemiesNear(level, from.lerp(to, 0.5), from.distanceTo(to) / 2 + 1.2, null)) {
			Vec3 c = e.getBoundingBox().getCenter();
			Vec3 ab = to.subtract(from);
			double t = Math.max(0, Math.min(1, c.subtract(from).dot(ab) / Math.max(0.01, ab.lengthSqr())));
			if (from.add(ab.scale(t)).distanceTo(c) < 1.6) {
				RelicEffects.hurt(p, e, 9, 0xFFFF3D6E);
				level.sendParticles(ParticleTypes.SWEEP_ATTACK, c.x, c.y, c.z, 1, 0, 0, 0, 0);
			}
		}
		p.teleportTo(to.x, to.y - 0.6, to.z);
		p.setDeltaMovement(dir.scale(0.6));
		p.needsSync = true;
		p.resetFallDistance();
		r.fx.untouchableUntil = level.getGameTime() + 10;
		level.playSound(null, p.blockPosition(), SoundEvents.BREEZE_WIND_CHARGE_BURST.value(), SoundSource.PLAYERS, 1.0F, 1.4F);
		level.playSound(null, p.blockPosition(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 0.8F, 0.8F);
		return true;
	}

	// ----- Ruby Nova -----

	private static boolean nova(ServerLevel level, ServerPlayer p) {
		Vec3 at = p.position().add(0, 0.6, 0);
		for (int ring = 1; ring <= 6; ring++) {
			RiftFx.ring(level, at.add(0, ring * 0.05, 0), ring, RiftFx.dust(ring % 2 == 0 ? 0xFF3D6E : 0xFFFFFF, 1.6F), 14 * ring);
		}
		RiftFx.burst(level, at.add(0, 0.6, 0), 0xFFFF3D6E, 60, 1.6);
		level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + 0.5, at.z, 1, 0, 0, 0, 0);
		for (LivingEntity e : RelicEffects.enemiesNear(level, at, 6.5, null)) {
			RelicEffects.hurt(p, e, 12, 0xFFFF3D6E);
			Vec3 push = e.position().subtract(at).multiply(1, 0, 1).normalize().scale(1.4);
			e.setDeltaMovement(push.x, 0.55, push.z);
			e.needsSync = true;
		}
		RiftFx.shake(p, 1.0F, 8);
		level.playSound(null, p.blockPosition(), SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 0.7F, 1.6F);
		level.playSound(null, p.blockPosition(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 1.5F, 0.8F);
		return true;
	}

	// ----- Shield Dome -----

	private static boolean dome(ServerLevel level, ServerPlayer p) {
		DOMES.add(new Dome(p.position(), level.getGameTime() + 120));
		level.playSound(null, p.blockPosition(), SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 1.2F, 1.5F);
		level.playSound(null, p.blockPosition(), SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.PLAYERS, 1.0F, 1.2F);
		return true;
	}

	/** Inside a Shield Dome? */
	static boolean domed(Entity e) {
		for (Dome d : DOMES) {
			if (e.position().distanceTo(d.at()) < DOME_RADIUS) {
				return true;
			}
		}
		return false;
	}

	/** A hit on a player: false when a dome is between them and whoever fired it. */
	static boolean domeBlocks(ServerPlayer victim, DamageSource source) {
		Entity attacker = source.getEntity();
		if (attacker == null || attacker instanceof ServerPlayer) {
			return false;
		}
		for (Dome d : DOMES) {
			if (victim.position().distanceTo(d.at()) < DOME_RADIUS && attacker.position().distanceTo(d.at()) >= DOME_RADIUS - 0.5) {
				return true;
			}
		}
		return false;
	}

	// ----- Grapple Hook -----

	private static boolean grapple(ServerLevel level, ServerPlayer p, Rift.Runner r) {
		Vec3 eye = p.getEyePosition();
		LivingEntity target = RelicEffects.aimed(level, p, 30);
		if (target != null && !JuggernautMech.isBoss(target) && Rift.isEnemy(target)) {
			// Yank them over.
			chain(level, eye, target.getBoundingBox().getCenter());
			Vec3 pull = eye.subtract(target.position()).normalize().scale(Math.min(2.4, target.distanceTo(p) * 0.18));
			target.setDeltaMovement(pull.x, 0.45, pull.z);
			target.needsSync = true;
			target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 3, false, false));
			level.playSound(null, target.blockPosition(), SoundEvents.CHAIN_BREAK, SoundSource.PLAYERS, 1.0F, 0.8F);
			return true;
		}
		var hit = level.clip(new ClipContext(eye, eye.add(p.getLookAngle().scale(30)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
		if (hit.getType() == HitResult.Type.MISS) {
			p.sendOverlayMessage(Component.literal("Nothing to hook onto there.").withStyle(ChatFormatting.GRAY));
			return false;
		}
		Vec3 to = hit.getLocation();
		chain(level, eye, to);
		Vec3 fly = to.subtract(p.position()).normalize().scale(Math.min(2.6, 0.9 + to.distanceTo(p.position()) * 0.08));
		p.setDeltaMovement(fly.x, Math.max(0.5, fly.y + 0.4), fly.z);
		p.needsSync = true;
		p.resetFallDistance();
		r.fx.noFallUntil = level.getGameTime() + 60;
		level.playSound(null, p.blockPosition(), SoundEvents.CROSSBOW_SHOOT, SoundSource.PLAYERS, 1.0F, 0.7F);
		level.playSound(null, BlockPos.containing(to), SoundEvents.CHAIN_PLACE, SoundSource.PLAYERS, 1.0F, 1.0F);
		return true;
	}

	private static void chain(ServerLevel level, Vec3 from, Vec3 to) {
		RiftFx.line(level, from, to, RiftFx.dust(0xB8C4D0, 0.9F), 0.25);
		RiftFx.line(level, from, to, ParticleTypes.CRIT, 0.8);
		level.sendParticles(ParticleTypes.ELECTRIC_SPARK, to.x, to.y, to.z, 12, 0.2, 0.2, 0.2, 0.1);
	}

	// ----- Overdrive -----

	private static boolean overdrive(ServerLevel level, ServerPlayer p, Rift.Runner r) {
		r.fx.overdriveUntil = level.getGameTime() + 120;
		RiftFx.screen(p, "flash", 0xFFFF7A2E, 15);
		RiftFx.beam(level, p.position(), 6, ParticleTypes.FLAME, 0xFFFF7A2E);
		level.playSound(null, p.blockPosition(), SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 1.0F, 0.6F);
		level.playSound(null, p.blockPosition(), SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 1.0F, 0.8F);
		p.sendOverlayMessage(Component.literal("OVERDRIVE!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
		return true;
	}

	static boolean overdriven(ServerPlayer p) {
		Rift.Runner r = Rift.runner(p);
		return r != null && p.level().getGameTime() < r.fx.overdriveUntil;
	}

	// ----- Rally Banner -----

	private static boolean rally(ServerLevel level, ServerPlayer p) {
		BANNERS.add(new Banner(p.position(), level.getGameTime() + 200, p.getUUID()));
		RiftFx.beam(level, p.position(), 8, ParticleTypes.HAPPY_VILLAGER, 0xFF6EE38A);
		level.playSound(null, p.blockPosition(), SoundEvents.RAID_HORN.value(), SoundSource.PLAYERS, 0.6F, 1.5F);
		return true;
	}

	// ----- Every tick -----

	static void tick(ServerLevel level) {
		long now = level.getGameTime();
		for (Iterator<Dome> it = DOMES.iterator(); it.hasNext();) {
			Dome d = it.next();
			if (now >= d.until()) {
				it.remove();
				level.playSound(null, BlockPos.containing(d.at()), SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 1.0F, 1.5F);
				continue;
			}
			// A shimmering sphere; enemy shots that touch it burst.
			if (now % 3 == 0) {
				int n = 70;
				double turn = now * 0.05;
				for (int i = 0; i < n; i++) {
					double y = 1 - (i + 0.5) * 2 / n;
					if (y < -0.1) {
						continue;
					}
					double rad = Math.sqrt(1 - y * y);
					double a = i * 2.39996 + turn;
					level.sendParticles(RiftFx.dust(i % 5 == 0 ? 0xFFFFFF : 0x66E0FF, 0.9F), true, false, d.at().x + Math.cos(a) * rad * DOME_RADIUS,
							d.at().y + y * DOME_RADIUS, d.at().z + Math.sin(a) * rad * DOME_RADIUS, 1, 0, 0, 0, 0);
				}
			}
			for (Projectile shot : level.getEntitiesOfClass(Projectile.class, new AABB(BlockPos.containing(d.at())).inflate(DOME_RADIUS + 1),
					s -> !(s.getOwner() instanceof ServerPlayer) && Math.abs(s.position().distanceTo(d.at()) - DOME_RADIUS) < 1.0)) {
				level.sendParticles(ParticleTypes.ELECTRIC_SPARK, shot.getX(), shot.getY(), shot.getZ(), 10, 0.2, 0.2, 0.2, 0.2);
				shot.discard();
			}
		}
		for (Iterator<Banner> it = BANNERS.iterator(); it.hasNext();) {
			Banner b = it.next();
			if (now >= b.until()) {
				it.remove();
				continue;
			}
			if (now % 4 == 0) {
				RiftFx.ring(level, b.at().add(0, 0.15, 0), 5, RiftFx.dust(0x6EE38A, 1.2F), 30);
				level.sendParticles(RiftFx.dust(0xFF3D6E, 1.6F), true, false, b.at().x, b.at().y + 2.4, b.at().z, 4, 0.3, 0.2, 0.05, 0);
				RiftFx.line(level, b.at(), b.at().add(0, 2.6, 0), RiftFx.dust(0x8A6A40, 0.8F), 0.25);
			}
			if (now % 20 == 0) {
				for (ServerPlayer mate : Rift.players(level.getServer())) {
					if (!mate.isSpectator() && mate.position().distanceTo(b.at()) < 5) {
						mate.heal(2);
						mate.addEffect(new MobEffectInstance(MobEffects.SPEED, 30, 1, false, false));
						level.sendParticles(ParticleTypes.HEART, mate.getX(), mate.getY() + 2, mate.getZ(), 2, 0.3, 0.2, 0.3, 0);
					}
				}
			}
		}
		// Overdrive: a fiery aura while it lasts.
		for (ServerPlayer p : Rift.players(level.getServer())) {
			if (overdriven(p) && now % 2 == 0) {
				level.sendParticles(ParticleTypes.FLAME, p.getX(), p.getY() + 1, p.getZ(), 2, 0.35, 0.5, 0.35, 0.01);
				level.sendParticles(RiftFx.dust(0xFF7A2E, 1.0F), p.getX(), p.getY() + 0.2, p.getZ(), 2, 0.4, 0.1, 0.4, 0);
			}
		}
	}

	/** Leaving a room: domes and banners go. */
	static void clear() {
		DOMES.clear();
		BANNERS.clear();
	}
}
